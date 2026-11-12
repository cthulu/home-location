package nl.freshlytyped.homelocation

import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns all ongoing location infrastructure: the fused-location
 * registration (delivered via [LocationReceiver] or a direct
 * [LocationCallback] from the foreground service), activity recognition,
 * the Wi-Fi loss callback, and the burst/heartbeat alarms.
 *
 * Everything is re-established by [ensureRegistered], which is idempotent
 * and is called on boot, on heartbeat, and after every receiver wake so
 * the setup survives process death and app updates.
 */
data class TrackingUiState(
    val trackingEnabled: Boolean,
    val movementState: MovementState,
    val currentZoneId: String?,
    val lastPublishedTimeMs: Long
)

class LocationCoordinator(
    private val context: Context,
    private val settings: LocationSettings,
    private val permissions: LocationPermissionManager,
    private val publisher: LocationPublisher,
    /**
     * Shared with [App] (and every other component) so there is exactly one
     * writer per log file. Two [DiagnosticLogger] instances writing the same
     * file would each hold their own lock and prune state and interleave their
     * read-all/write-back cycles, silently dropping lines.
     */
    private val diagnostics: DiagnosticLogger,
    private val requestFactory: LocationRequestFactory = LocationRequestFactory(),
    private val filter: LocationFilter = LocationFilter(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zoneEntryHysteresisMs: Long = 60_000L,
    private val zoneEntryWindowMs: Long = 10 * 60_000L
) {

    private val zoneManager = ZoneManager(context, settings, diagnostics)
    private val zonesSubscriber = ZonesSubscriber(settings, zoneManager, diagnostics)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var roamingPaused = false
    @Volatile private var zonePaused = false
    @Volatile private var appInForeground = false
    private var directLocationCallback: LocationCallback? = null
    private var directLocationLooper: Looper? = null
    private var pendingZoneEntryJob: Job? = null
    private var pendingZoneEntryId: String? = null
    private var pendingZoneEntryArmedAtMs: Long = 0L
    private val publishLock = Any()
    private var publishInFlight = false
    private var lastPublishedFixTimeMs = 0L
    private var registeredLocationKey: LocationRegistrationKey? = null
    private var activityRegistrationActive = false
    @Volatile private var lastLocationCallbackTimeMs = 0L
    private val _uiState = MutableStateFlow(currentUiState())

    val uiState: StateFlow<TrackingUiState> = _uiState

    private val controller: MovementStateController
        get() = MovementStateController(
            MovementConfig(settings.movementDetectionDistanceMeters, settings.burstDurationMs)
        )

    // --- Lifecycle ---

    fun start() {
        diagnostics.record("tracking_start_requested")
        if (!settings.isConfigured()) {
            Log.i(TAG, "Start ignored: PubNub is not configured")
            return
        }
        if (!permissions.trackingAllowed()) {
            Log.i(TAG, "Start ignored: location permissions missing")
            return
        }
        normalizeMovementState(clock())
        settings.trackingEnabled = true
        publishUiState()
        if (settings.geofencingEnabled) {
            zoneManager.registerGeofences()
            zonesSubscriber.start()
        }
        ensureRegistered()
        requestImmediateLocation()
    }

    fun stop() {
        diagnostics.record("tracking_stop_requested")
        settings.trackingEnabled = false
        clearPendingZoneEntry()
        zonesSubscriber.stop()
        removeLocationRegistration()
        removeActivityRegistration()
        releaseWifiCallback()
        cancelBurstAlarm()
        cancelPeriodicPublish()
        cancelHeartbeatAlarm()
        releaseNetworkCallback()
        zoneManager.removeGeofences()
        settings.resetState()
        publishUiState()
    }

    /**
     * (Re)establishes every registration that must be active while
     * tracking is enabled. Safe to call repeatedly.
     */
    fun ensureRegistered() {
        if (!settings.trackingEnabled) return
        if (!permissions.trackingAllowed()) {
            Log.w(TAG, "Registration skipped: location permissions missing")
            return
        }
        registerNetworkCallback()
        retryPendingPublish()
        if (roamingPaused) return
        if (!appInForeground && !settings.backgroundLocationEnabled) {
            removeLocationRegistration()
            removeActivityRegistration()
            releaseWifiCallback()
            cancelBurstAlarm()
            cancelPeriodicPublish()
            cancelHeartbeatAlarm()
            return
        }

        // Pause all tracking when inside a zone: keep geofencing, PubNub and
        // the heartbeat (which re-registers geofences so zone exit survives
        // process death and geofence loss).
        if (zoneManager.isInsideZone) {
            if (!zonePaused) {
                zonePaused = true
                removeLocationRegistration()
                removeActivityRegistration()
                releaseWifiCallback()
                cancelBurstAlarm()
                cancelPeriodicPublish()
                diagnostics.record("zone_pause_active")
            }
            scheduleHeartbeat()
            return
        }

        if (zonePaused) {
            zonePaused = false
            diagnostics.record("zone_pause_inactive")
        }

        registerLocationUpdates()
        registerActivityRecognition()
        if (settings.movementState == MovementState.MOVING) scheduleBurstRevert()
        if (settings.movementState == MovementState.STATIONARY && settings.periodicFallbackEnabled) {
            schedulePeriodicPublish()
        } else {
            cancelPeriodicPublish()
        }
        registerWifiCallback()
        scheduleHeartbeat()
    }

    fun refreshZones() {
        if (!settings.geofencingEnabled) return
        zonesSubscriber.start()
    }

    /** Return the zone names currently cached on this device. */
    fun getCachedZoneNames(): List<String> = zoneManager.getZones().map { it.name }

    fun onHeartbeat() {
        checkLocationCallbackHealth()
        if (settings.geofencingEnabled) zoneManager.registerGeofences()
        ensureRegistered()
        // Backstop: expire a never-confirmed entry even if no fixes arrive.
        expirePendingZoneEntryIfDue(clock())
    }

    /**
     * Called from [GeofenceReceiver] when Play services reports a geofence
     * error. GEOFENCE_NOT_AVAILABLE fires when location is toggled off/on and
     * clears all geofences, so re-register from the persisted zone list.
     */
    fun onGeofenceError(errorCode: Int) {
        if (!settings.geofencingEnabled || !settings.trackingEnabled) return
        if (errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
            diagnostics.record("geofence_error_recovering code=$errorCode")
            zoneManager.registerGeofences()
        }
    }

    /** Re-register geofences from the persisted list (e.g. after boot when the service start is blocked). */
    fun restoreGeofences() {
        if (settings.geofencingEnabled && settings.trackingEnabled) {
            zoneManager.registerGeofences()
        }
    }

    fun onAppForegrounded() {
        appInForeground = true
        ensureRegistered()
    }

    fun onAppBackgrounded() {
        appInForeground = false
        ensureRegistered()
    }

    // --- Direct location callback from foreground service ---

    fun setDirectLocationCallback(callback: LocationCallback, looper: Looper) {
        removeLocationRegistration()
        directLocationCallback = callback
        directLocationLooper = looper
        registerLocationUpdates()
    }

    fun clearDirectLocationCallback() {
        removeLocationRegistration()
        directLocationCallback = null
        directLocationLooper = null
    }

    // --- Evidence handlers (called from [LocationReceiver] or direct callback) ---

    fun onLocationUpdates(locations: List<Location>) {
        lastLocationCallbackTimeMs = clock()
        scope.launch(Dispatchers.IO) {
            diagnostics.record(
                "location_callback count=${locations.size} " +
                    "times=${locations.map { it.time }.joinToString(",")} " +
                    "state=${settings.movementState}"
            )
        }
        if (!settings.trackingEnabled || roamingPaused || zonePaused) return
        if (enforceHighAccuracyLimit(clock())) return
        val now = clock()
        for (location in locations) {
            handleFix(location.toFix(), now)
        }
    }

    /** Requests one fresh fix so starting tracking gives immediate feedback. */
    private fun requestImmediateLocation() {
        oneShotLocationRequest(30_000L)
            .addOnSuccessListener { location ->
                if (location != null && settings.trackingEnabled) {
                    handleFix(location.toFix(), clock())
                } else if (location == null) {
                    Log.w(TAG, "Immediate location request returned no fix")
                }
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Immediate location request failed", error)
            }
    }

    @SuppressLint("MissingPermission")
    private fun oneShotLocationRequest(
        durationMs: Long,
        forceBalanced: Boolean = false,
        maxUpdateAgeMillis: Long = 0L
    ): Task<Location> {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location request skipped: no location permission")
            val tcs = TaskCompletionSource<Location>()
            tcs.setException(SecurityException("Location permission not granted"))
            return tcs.task
        }
        val priority = if (!forceBalanced && settings.preciseMode && permissions.fineGranted()) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        val request = CurrentLocationRequest.Builder()
            .setPriority(priority)
            .setMaxUpdateAgeMillis(maxUpdateAgeMillis)
            .setDurationMillis(durationMs)
            .build()
        val cancellation = CancellationTokenSource()
        return LocationServices.getFusedLocationProviderClient(context)
            .getCurrentLocation(request, cancellation.token)
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
    }

    /**
     * Periodic stationary publish. Driven by an alarm rather than by incoming
     * fixes: a stationary device (or an emulator with a static mock location)
     * may not produce new fixes on its own, so the cadence would never fire.
     */
    fun onPeriodicPublish() {
        if (!settings.trackingEnabled || roamingPaused || zonePaused) return
        if (settings.movementState != MovementState.STATIONARY || !settings.periodicFallbackEnabled) {
            return
        }
        oneShotLocationRequest(settings.periodicFallbackIntervalMs)
            .addOnSuccessListener { location ->
                if (location == null) {
                    Log.w(TAG, "Periodic publish: no location available")
                } else {
                    val fix = location.toFix()
                    val result = filter.evaluate(fix, clock(), buildFilterContext())
                    if (result is LocationDecision.Accept) {
                        publish(fix)
                    } else if (result is LocationDecision.Reject) {
                        Log.d(TAG, "Periodic publish skipped: ${result.reason}")
                    }
                }
                if (settings.trackingEnabled) schedulePeriodicPublish()
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Periodic location request failed", error)
                if (settings.trackingEnabled) schedulePeriodicPublish()
            }
    }

    private fun handleFix(fix: LocationFix, now: Long) {
        if (fix.timeMs <= settings.lastKnownTimeMs) return
        evaluatePendingZoneEntry(fix, now)
        if (zonePaused) return // the fix confirmed a zone entry; tracking is paused
        if (!controller.isReliableFix(fix)) {
            diagnosticsRecord(
                "location_fix rejected_for_movement accuracy=${fix.accuracyMeters} " +
                    "max=${MovementStateController.MOVEMENT_MAX_ACCURACY_METERS}"
            )
            return
        }

        val previousDistance = if (settings.hasLastKnown()) {
            GeoMath.distanceMeters(
                settings.lastKnownLat,
                settings.lastKnownLon,
                fix.latitude,
                fix.longitude
            )
        } else {
            null
        }
        val publishedDistance = if (settings.hasLastPublished()) {
            GeoMath.distanceMeters(
                settings.lastPublishedLat,
                settings.lastPublishedLon,
                fix.latitude,
                fix.longitude
            )
        } else {
            null
        }
        diagnosticsRecord(
            "location_fix ageMs=${now - fix.timeMs} accuracy=${fix.accuracyMeters} " +
                "distanceFromLast=${previousDistance ?: -1.0} " +
                "distanceFromPublished=${publishedDistance ?: -1.0} state=${settings.movementState}"
        )

        val movementCooldownActive =
            settings.movementState == MovementState.STATIONARY &&
                now < settings.activityCooldownUntilMs
        val decision = if (movementCooldownActive) {
            MovementDecision.NoChange()
        } else {
            controller.observe(
                MovementEvidence.Fix(fix),
                settings.movementState,
                settings.lastKnownLat,
                settings.lastKnownLon,
                settings.hasLastKnown(),
                settings.accumulatedDistanceMeters,
                settings.lastMovementEvidenceTimeMs,
                now
            )
        }

        if (decision.isEvidence) {
            settings.lastMovementEvidenceTimeMs = now
            settings.accumulatedDistanceMeters = 0f
        } else if (movementCooldownActive) {
            settings.accumulatedDistanceMeters = 0f
        } else {
            settings.accumulatedDistanceMeters = controller.nextAccumulatedMeters(
                settings.lastKnownLat,
                settings.lastKnownLon,
                settings.hasLastKnown(),
                settings.accumulatedDistanceMeters,
                fix
            )
        }
        settings.lastKnownLat = fix.latitude
        settings.lastKnownLon = fix.longitude
        settings.lastKnownTimeMs = fix.timeMs

        applyStateChange(decision)

        when (val result = filter.evaluate(fix, now, buildFilterContext())) {
            is LocationDecision.Accept -> {
                diagnosticsRecord("location_decision accept")
                publish(fix)
            }
            is LocationDecision.Reject -> {
                diagnosticsRecord("location_decision reject reason=${result.reason}")
                Log.d(TAG, "Fix not published: ${result.reason}")
            }
        }
    }

    private fun diagnosticsRecord(event: String) {
        scope.launch(Dispatchers.IO) { diagnostics.record(event) }
    }

    private fun buildFilterContext() = FilterContext(
        maxAccuracyMeters = settings.maxAccuracyMeters,
        maxLocationAgeMs = settings.maxLocationAgeMs,
        minPublishIntervalMs = settings.minPublishIntervalMs,
        minDistanceMeters = settings.minDistanceMeters,
        periodicFallbackEnabled = settings.periodicFallbackEnabled,
        periodicFallbackIntervalMs = settings.periodicFallbackIntervalMs,
        lastPublishedLat = settings.lastPublishedLat,
        lastPublishedLon = settings.lastPublishedLon,
        lastPublishedTimeMs = settings.lastPublishedTimeMs
    )

    private fun publish(fix: LocationFix) {
        if (roamingPaused) return
        val invalidReason = validatePublishFix(fix)
        if (invalidReason != null) {
            diagnosticsRecord("publish rejected reason=${invalidReason.name.lowercase()}")
            return
        }
        if (!settings.isConfigured()) {
            Log.w(TAG, "Fix accepted but PubNub not configured; skipping publish")
            return
        }
        storePendingIfNewer(fix)
        publishAttempt(fix)
    }

    private fun validatePublishFix(fix: LocationFix): RejectReason? = filter.validate(
        fix,
        clock(),
        settings.maxLocationAgeMs,
        settings.maxAccuracyMeters
    )

    private fun storePendingIfNewer(fix: LocationFix) {
        val pending = settings.pendingLocation
        if (pending == null || fix.timeMs >= pending.timeMs) {
            settings.pendingLocation = fix
        }
    }

    private fun publishAttempt(fix: LocationFix) {
        // Serialized via publishLock: a second fix queues as pending instead of
        // racing the in-flight publish (out-of-order completion would corrupt
        // the last-published baseline).
        val start = synchronized(publishLock) {
            if (publishInFlight) {
                storePendingIfNewer(fix)
                false
            } else {
                publishInFlight = true
                true
            }
        }
        if (!start) return
        scope.launch {
            try {
                publisher.publish(fix)
                    .onSuccess {
                        markPublished(fix)
                        diagnosticsRecord("publish success state=${settings.movementState}")
                        Log.i(TAG, "Location published (state=${settings.movementState})")
                    }
                    .onFailure {
                        val invalidReason = validatePublishFix(fix)
                        val category = PublishFailureMapper.categoryFor(it)
                        if (invalidReason != null || isLocationRejected(category)) {
                            clearPendingIfMatches(fix)
                            val reason = invalidReason?.name?.lowercase() ?: category.name.lowercase()
                            diagnosticsRecord("publish rejected reason=$reason")
                        } else {
                            storePendingIfNewer(fix)
                            diagnosticsRecord("publish failure category=$category")
                            Log.w(TAG, "Publish failed: $category")
                        }
                    }
            } finally {
                finishPublish()
            }
        }
    }

    private fun finishPublish() {
        val next = synchronized(publishLock) {
            publishInFlight = false
            settings.pendingLocation
        }
        if (next != null) retryPendingPublish()
    }

    private fun retryPendingPublish() {
        if (roamingPaused) return
        val start = synchronized(publishLock) {
            if (publishInFlight) {
                false
            } else {
                publishInFlight = true
                true
            }
        }
        if (!start) return
        val pending = settings.pendingLocation
        if (pending == null) {
            synchronized(publishLock) { publishInFlight = false }
            return
        }
        val invalidReason = validatePublishFix(pending)
        if (invalidReason != null) {
            clearPendingIfMatches(pending)
            diagnosticsRecord("pending_publish discarded reason=${invalidReason.name.lowercase()}")
            synchronized(publishLock) { publishInFlight = false }
            return
        }
        scope.launch {
            try {
                publisher.publish(pending)
                    .onSuccess {
                        markPublished(pending)
                        diagnosticsRecord("pending_publish success")
                    }
                    .onFailure {
                        val reason = validatePublishFix(pending)
                        val category = PublishFailureMapper.categoryFor(it)
                        if (reason != null || isLocationRejected(category)) {
                            clearPendingIfMatches(pending)
                            val rejection = reason?.name?.lowercase() ?: category.name.lowercase()
                            diagnosticsRecord("pending_publish discarded reason=$rejection")
                        } else {
                            diagnosticsRecord("pending_publish failure category=$category")
                        }
                    }
            } finally {
                synchronized(publishLock) { publishInFlight = false }
            }
        }
    }

    private fun clearPendingIfMatches(fix: LocationFix) {
        if (settings.pendingLocation?.timeMs == fix.timeMs) {
            settings.pendingLocation = null
        }
    }

    private fun isLocationRejected(category: PublishFailureCategory): Boolean =
        category == PublishFailureCategory.STALE ||
            category == PublishFailureCategory.INVALID_LOCATION

    private fun markPublished(fix: LocationFix) {
        if (settings.pendingLocation?.timeMs == fix.timeMs) {
            settings.pendingLocation = null
        }
        synchronized(publishLock) {
            // An older fix must never overwrite the baseline of a newer one.
            if (fix.timeMs < lastPublishedFixTimeMs) return
            lastPublishedFixTimeMs = fix.timeMs
            settings.lastPublishedLat = fix.latitude
            settings.lastPublishedLon = fix.longitude
            settings.lastPublishedTimeMs = clock()
            settings.lastPublishedAccuracyMeters = fix.accuracyMeters
        }
        publishUiState()
    }

    fun onActivityUpdate(activityType: Int, confidence: Int, timeMs: Long) {
        if (!settings.trackingEnabled || roamingPaused || zonePaused) return
        val now = if (timeMs > 0) timeMs else clock()
        diagnostics.record("activity_update type=$activityType confidence=$confidence state=${settings.movementState}")
        if (enforceHighAccuracyLimit(now)) return
        if (controller.isMovingActivity(activityType) && confidence < MIN_MOVING_ACTIVITY_CONFIDENCE) {
            diagnosticsRecord("activity_update ignored reason=low_confidence threshold=$MIN_MOVING_ACTIVITY_CONFIDENCE")
            return
        }
        if (now < settings.activityCooldownUntilMs && controller.isMovingActivity(activityType)) {
            diagnosticsRecord("activity_update ignored reason=high_accuracy_cooldown")
            return
        }
        val decision = controller.observe(
            MovementEvidence.Activity(activityType, timeMs),
            settings.movementState,
            settings.lastKnownLat,
            settings.lastKnownLon,
            settings.hasLastKnown(),
            settings.accumulatedDistanceMeters,
            settings.lastMovementEvidenceTimeMs,
            now
        )
        if (decision.isEvidence) {
            settings.lastMovementEvidenceTimeMs = now
        }
        if (controller.isStationaryActivity(activityType)) {
            settings.accumulatedDistanceMeters = 0f
        }
        applyStateChange(decision)
    }

    fun onWifiLost() {
        if (!settings.trackingEnabled || roamingPaused || zonePaused) return
        diagnosticsRecord("wifi_lost verification_requested")
        oneShotLocationRequest(30_000L, forceBalanced = true)
            .addOnSuccessListener { location ->
                if (location == null || !settings.trackingEnabled || roamingPaused) return@addOnSuccessListener
                handleFix(location.toFix(), clock())
            }
            .addOnFailureListener {
                diagnosticsRecord("wifi_lost verification_failed ${it.javaClass.simpleName}")
            }
    }

    fun onBurstEnd() {
        if (!settings.trackingEnabled || roamingPaused || zonePaused) return
        // The cap must win even when fixes keep arriving as movement evidence.
        if (enforceHighAccuracyLimit(clock())) return
        val decision = controller.burstExpired(
            settings.movementState,
            settings.lastMovementEvidenceTimeMs,
            clock()
        )
        if (decision is MovementDecision.ToStationary) {
            applyStateChange(decision)
        } else {
            scheduleBurstRevert()
        }
    }

    /**
     * Called from [GeofenceReceiver] when the device enters a zone.
     *
     * [triggeringLocation] is the fix the geofence engine used to decide the
     * transition. When it is usable it confirms the entry immediately, so the
     * confirmation does not depend on a background-frozen hysteresis delay or
     * a fresh one-shot that may return nothing.
     */
    fun onZoneEntered(zoneId: String, triggeringLocation: Location? = null) {
        val now = clock()
        if (settings.currentZoneId == zoneId) return
        val lastTransition = settings.lastZoneTransitionMs
        if (settings.currentZoneId != null && lastTransition > 0L &&
            now - lastTransition < settings.geofenceDebounceMs
        ) {
            diagnostics.record("zone_entered id=$zoneId debounce_ignored=true")
            Log.i(TAG, "Zone enter debounced: $zoneId")
            return
        }
        if (zoneEntryHysteresisMs > 0L) {
            pendingZoneEntryJob?.cancel()
            pendingZoneEntryId = zoneId
            pendingZoneEntryArmedAtMs = now
            diagnostics.record(
                "zone_enter_pending id=$zoneId delayMs=$zoneEntryHysteresisMs " +
                    "triggering=${triggeringLocation != null}"
            )
            if (!confirmZoneEntryFromFix(zoneId, triggeringLocation?.toFix(), now)) {
                pendingZoneEntryJob = scope.launch {
                    delay(zoneEntryHysteresisMs)
                    confirmZoneEntry(zoneId)
                }
            }
            return
        }
        applyZoneEntered(zoneId, now)
    }

    /**
     * Applies the pending entry for [zoneId] when [fix] is usable for it.
     *
     * Returns true when the entry was applied. A fix that is merely not usable
     * (coarse, stale, or outside the radius) leaves the pending entry in place
     * so it can be confirmed by a later fix; only a missing zone clears it.
     */
    private fun confirmZoneEntryFromFix(zoneId: String, fix: LocationFix?, now: Long): Boolean {
        if (pendingZoneEntryId != zoneId || settings.currentZoneId != null) {
            return false
        }
        expirePendingZoneEntryIfDue(now)
        if (pendingZoneEntryId != zoneId) {
            return false
        }
        if (fix == null) {
            return false
        }
        val zone = zoneManager.getZone(zoneId)
        if (zone == null) {
            diagnostics.record("zone_enter_rejected id=$zoneId reason=zone_not_found")
            clearPendingZoneEntry()
            return false
        }
        val reason = filter.validate(fix, now, settings.maxLocationAgeMs, accuracyCapForZone(zone))
        if (reason != null) {
            diagnostics.record("zone_enter_rejected id=$zoneId reason=${reason.name.lowercase()}")
            return false
        }
        val distance = GeoMath.distanceMeters(fix.latitude, fix.longitude, zone.latitude, zone.longitude)
        if (distance > zone.radius) {
            diagnostics.record(
                "zone_enter_rejected id=$zoneId reason=outside distanceMeters=${"%.0f".format(distance)}"
            )
            return false
        }
        publish(fix)
        clearPendingZoneEntry()
        applyZoneEntered(zoneId, now)
        return true
    }

    /**
     * Hysteresis fallback: after [zoneEntryHysteresisMs] ask for one fresh fix
     * and confirm from it. The relaxed [maxUpdateAgeMillis] lets the provider
     * answer with a recent cached fix instead of null when no new fix exists.
     */
    private fun confirmZoneEntry(zoneId: String) {
        if (pendingZoneEntryId != zoneId || settings.currentZoneId != null) return
        oneShotLocationRequest(30_000L, forceBalanced = true, maxUpdateAgeMillis = ZONE_CONFIRM_MAX_AGE_MS)
            .addOnSuccessListener { location ->
                if (pendingZoneEntryId != zoneId) return@addOnSuccessListener
                val fix = location?.toFix()
                if (fix == null) {
                    diagnostics.record("zone_enter_rejected id=$zoneId reason=no_location")
                    // A missing fix does not kill the entry; the bounded window
                    // or a later fix resolves it.
                    expirePendingZoneEntryIfDue(clock())
                    return@addOnSuccessListener
                }
                confirmZoneEntryFromFix(zoneId, fix, clock())
            }
            .addOnFailureListener {
                if (pendingZoneEntryId != zoneId) return@addOnFailureListener
                diagnostics.record("zone_enter_rejected id=$zoneId reason=location_failed")
                // Keep the entry pending; the bounded window or a later fix resolves it.
                expirePendingZoneEntryIfDue(clock())
            }
    }

    /** Cancels the confirmation job and drops the pending entry. */
    private fun clearPendingZoneEntry() {
        pendingZoneEntryJob?.cancel()
        pendingZoneEntryJob = null
        pendingZoneEntryId = null
        pendingZoneEntryArmedAtMs = 0L
    }

    /**
     * Clears the pending entry once the bounded confirmation window has elapsed,
     * so a never-confirmed entry (e.g. only coarse fixes) does not linger.
     */
    private fun expirePendingZoneEntryIfDue(now: Long) {
        val zoneId = pendingZoneEntryId ?: return
        if (settings.currentZoneId != null) return
        if (pendingZoneEntryArmedAtMs > 0L && now - pendingZoneEntryArmedAtMs > zoneEntryWindowMs) {
            diagnostics.record("zone_enter_expired id=$zoneId")
            clearPendingZoneEntry()
        }
    }

    /**
     * Re-evaluates the pending zone entry against a fresh fix, so an entry that
     * could not be confirmed by the triggering location (or the one-shot) is
     * confirmed as soon as a usable fix arrives within the bounded window.
     */
    private fun evaluatePendingZoneEntry(fix: LocationFix, now: Long) {
        val zoneId = pendingZoneEntryId ?: return
        if (settings.currentZoneId != null) return
        expirePendingZoneEntryIfDue(now)
        if (pendingZoneEntryId != zoneId) return // expired in the call above
        confirmZoneEntryFromFix(zoneId, fix, now)
    }

    /**
     * Accuracy cap for confirming a zone entry: the normal publish cap widened
     * to the zone radius, so a coarse-but-plausible fix (e.g. an indoor Wi-Fi
     * fix) can confirm a large zone without waiting for perfect GPS.
     */
    private fun accuracyCapForZone(zone: Zone): Float =
        maxOf(settings.maxAccuracyMeters, zone.radius.toFloat())

    private fun applyZoneEntered(zoneId: String, now: Long) {
        settings.currentZoneId = zoneId
        settings.lastZoneTransitionMs = now
        publishUiState()
        diagnostics.record("zone_entered id=$zoneId")
        Log.i(TAG, "Zone entered: $zoneId — pausing tracking")
        if (settings.trackingEnabled) {
            zonePaused = true
            removeLocationRegistration()
            removeActivityRegistration()
            releaseWifiCallback()
            cancelBurstAlarm()
            cancelPeriodicPublish()
            diagnostics.record("zone_pause_active")
        }
    }

    /** Called from [GeofenceReceiver] when the device exits a zone. */
    fun onZoneExited(zoneId: String) {
        if (pendingZoneEntryId == zoneId) {
            pendingZoneEntryJob?.cancel()
            pendingZoneEntryJob = null
            pendingZoneEntryId = null
            diagnostics.record("zone_enter_cancelled id=$zoneId reason=exit_before_confirmation")
        }
        if (settings.currentZoneId != zoneId) {
            diagnostics.record("zone_exited id=$zoneId stale_ignored=true")
            Log.i(TAG, "Ignoring stale zone exit: $zoneId")
            return
        }
        settings.currentZoneId = null
        settings.lastZoneTransitionMs = clock()
        publishUiState()
        diagnostics.record("zone_exited id=$zoneId")
        Log.i(TAG, "Zone exited: $zoneId — resuming tracking")
        if (settings.trackingEnabled) {
            ensureRegistered()
        }
    }

    /** Applies a state transition and (re)registers the matching request. */
    private fun applyStateChange(decision: MovementDecision) {
        when (decision) {
            is MovementDecision.ToMoving -> {
                settings.movementState = MovementState.MOVING
                settings.movingStartedTimeMs = clock()
                settings.activityCooldownUntilMs = 0L
                settings.accumulatedDistanceMeters = 0f
                Log.i(TAG, "State -> MOVING")
                diagnostics.record("state_transition stationary_to_moving")
                registerLocationUpdates()
                scheduleBurstRevert()
                cancelPeriodicPublish()
            }

            is MovementDecision.ToStationary -> {
                settings.movementState = MovementState.STATIONARY
                settings.movingStartedTimeMs = 0L
                settings.accumulatedDistanceMeters = 0f
                Log.i(TAG, "State -> STATIONARY")
                diagnostics.record("state_transition moving_to_stationary")
                registerLocationUpdates()
                cancelBurstAlarm()
                if (settings.periodicFallbackEnabled) schedulePeriodicPublish()
                else cancelPeriodicPublish()
            }

            is MovementDecision.NoChange -> Unit
        }
        publishUiState()
    }

    private fun currentUiState() = TrackingUiState(
        trackingEnabled = settings.trackingEnabled,
        movementState = settings.movementState,
        currentZoneId = settings.currentZoneId,
        lastPublishedTimeMs = settings.lastPublishedTimeMs
    )

    private fun publishUiState() {
        _uiState.value = currentUiState()
    }

    // --- Registration helpers ---

    private fun normalizeMovementState(now: Long) {
        if (settings.movementState != MovementState.MOVING) return
        val started = settings.movingStartedTimeMs
        if (started <= 0L || now - started >= MAX_HIGH_ACCURACY_DURATION_MS) {
            settings.movementState = MovementState.STATIONARY
            settings.movingStartedTimeMs = 0L
            settings.activityCooldownUntilMs = now + ACTIVITY_COOLDOWN_MS
            settings.accumulatedDistanceMeters = 0f
            diagnostics.record("stale_moving_state_reset")
        }
    }

    private fun enforceHighAccuracyLimit(now: Long): Boolean {
        if (settings.movementState != MovementState.MOVING) return false
        val started = settings.movingStartedTimeMs
        if (started > 0L && now - started < MAX_HIGH_ACCURACY_DURATION_MS) return false
        settings.movementState = MovementState.STATIONARY
        settings.movingStartedTimeMs = 0L
        settings.activityCooldownUntilMs = now + ACTIVITY_COOLDOWN_MS
        settings.accumulatedDistanceMeters = 0f
        diagnosticsRecord("state_transition moving_to_stationary reason=high_accuracy_limit")
        applyStateChange(MovementDecision.ToStationary(MovementState.MOVING))
        return true
    }

    private fun locationPendingIntent(): PendingIntent =
        broadcastIntent(REQUEST_CODE_LOCATION, LocationReceiver.ACTION_LOCATION_UPDATE, mutable = true)

    private fun activityPendingIntent(): PendingIntent =
        broadcastIntent(REQUEST_CODE_ACTIVITY, LocationReceiver.ACTION_ACTIVITY_UPDATE, mutable = true)

    private fun burstEndPendingIntent(): PendingIntent =
        broadcastIntent(REQUEST_CODE_BURST_END, LocationReceiver.ACTION_BURST_END)

    private fun heartbeatPendingIntent(): PendingIntent =
        broadcastIntent(REQUEST_CODE_HEARTBEAT, LocationReceiver.ACTION_HEARTBEAT)

    private fun periodicPublishPendingIntent(): PendingIntent =
        broadcastIntent(REQUEST_CODE_PERIODIC_PUBLISH, LocationReceiver.ACTION_PERIODIC_PUBLISH)

    private fun broadcastIntent(requestCode: Int, action: String, mutable: Boolean = false): PendingIntent {
        val intent = Intent(context, LocationReceiver::class.java).setAction(action)
        // Play services mutates the intent to attach its results, so the
        // location and activity intents must be mutable on Android 12+
        // (ApiException 10 otherwise rejects the registration).
        val mutability = if (mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutability
        )
    }

    @SuppressLint("MissingPermission")
    private fun registerLocationUpdates() {
        if (!hasLocationPermission()) return
        val client = LocationServices.getFusedLocationProviderClient(context)
        val registrationKey = LocationRegistrationKey(
            state = settings.movementState,
            precise = settings.preciseMode,
            fineGranted = permissions.fineGranted(),
            stationaryIntervalMs = settings.backgroundScanIntervalMs,
            direct = directLocationCallback != null
        )
        if (registeredLocationKey == registrationKey) return
        removeLocationRegistration()
        val request = requestFactory.forState(
            settings.movementState,
            settings.preciseMode,
            permissions.fineGranted(),
            settings.backgroundScanIntervalMs
        )
        val callback = directLocationCallback
        if (callback != null && directLocationLooper != null) {
            runCatching {
                client.requestLocationUpdates(request, callback, directLocationLooper!!)
            }.onSuccess { task ->
                registeredLocationKey = registrationKey
                diagnostics.record("location_registration_requested mode=direct state=${settings.movementState}")
                task.addOnFailureListener {
                    registeredLocationKey = null
                    Log.w(TAG, "Failed to register direct location updates", it)
                }
            }.onFailure {
                registeredLocationKey = null
                diagnostics.record("location_registration_exception mode=direct ${it.javaClass.simpleName}")
                Log.w(TAG, "Failed to register direct location updates", it)
            }
        } else {
            runCatching { client.requestLocationUpdates(request, locationPendingIntent()) }
                .onSuccess { task ->
                    registeredLocationKey = registrationKey
                    diagnostics.record("location_registration_requested mode=pending state=${settings.movementState}")
                    task.addOnFailureListener {
                        registeredLocationKey = null
                        Log.w(TAG, "Failed to register location updates", it)
                    }
                }
                .onFailure {
                    registeredLocationKey = null
                    Log.w(TAG, "Failed to register location updates", it)
                }
        }
    }

    private fun removeLocationRegistration() {
        registeredLocationKey = null
        val client = LocationServices.getFusedLocationProviderClient(context)
        directLocationCallback?.let { callback ->
            runCatching { client.removeLocationUpdates(callback) }
        }
        runCatching { client.removeLocationUpdates(locationPendingIntent()) }
    }

    private fun checkLocationCallbackHealth() {
        if (!settings.trackingEnabled || roamingPaused || zonePaused) return
        if (directLocationCallback == null) return
        val last = lastLocationCallbackTimeMs
        if (last == 0L) return
        val elapsed = clock() - last
        val threshold = maxOf(settings.backgroundScanIntervalMs * 3, 5 * 60_000L)
        if (elapsed > threshold) {
            diagnostics.record("location_callback_watchdog elapsed=${elapsed}ms threshold=${threshold}ms")
            Log.w(TAG, "No location callback for ${elapsed}ms; falling back to PendingIntent")
            clearDirectLocationCallback()
            registerLocationUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerActivityRecognition() {
        if (!hasActivityRecognitionPermission()) return
        if (activityRegistrationActive) return
        val client: ActivityRecognitionClient = ActivityRecognition.getClient(context)
        activityRegistrationActive = true
        runCatching {
            client.requestActivityUpdates(ACTIVITY_UPDATE_INTERVAL_MS, activityPendingIntent())
                .addOnSuccessListener {
                    activityRegistrationActive = true
                    diagnostics.record("activity_registration_succeeded")
                }
                .addOnFailureListener {
                    activityRegistrationActive = false
                    diagnostics.record("activity_registration_failed ${it.javaClass.simpleName}")
                }
        }.onFailure {
            activityRegistrationActive = false
            diagnostics.record("activity_registration_exception ${it.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun removeActivityRegistration() {
        if (!hasActivityRecognitionPermission()) return
        activityRegistrationActive = false
        runCatching {
            ActivityRecognition.getClient(context)
                .removeActivityUpdates(activityPendingIntent())
        }
    }

    private fun hasActivityRecognitionPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun registerWifiCallback() {
        releaseWifiCallback()
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                super.onLost(network)
                onWifiLost()
            }
        }
        wifiCallback = callback
        runCatching { connectivity.registerNetworkCallback(request, callback) }
            .onFailure { Log.w(TAG, "Failed to register Wi-Fi callback", it) }
    }

    private fun releaseWifiCallback() {
        val callback = wifiCallback ?: return
        wifiCallback = null
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                retryPendingPublish()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, capabilities)
                updateRoamingState(isCellularRoaming(capabilities))
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                updateRoamingState(false)
            }
        }
        networkCallback = callback
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onFailure {
                networkCallback = null
                Log.w(TAG, "Failed to register network callback", it)
            }
    }

    private fun releaseNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
    }

    private fun isCellularRoaming(capabilities: NetworkCapabilities): Boolean {
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return false
        // NET_CAPABILITY_NOT_ROAMING only exists on API 28+; TelephonyManager
        // works on every API level and needs no permission.
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return telephony?.isNetworkRoaming == true
    }

    private fun updateRoamingState(roaming: Boolean) {
        if (roamingPaused == roaming) return
        roamingPaused = roaming
        if (roaming) {
            Log.i(TAG, "Roaming detected; pausing location tracking and publishing")
            removeLocationRegistration()
            removeActivityRegistration()
            releaseWifiCallback()
            cancelBurstAlarm()
            cancelPeriodicPublish()
            cancelHeartbeatAlarm()
        } else if (settings.trackingEnabled) {
            Log.i(TAG, "Roaming ended; resuming location tracking and publishing")
            ensureRegistered()
        }
    }

    // --- Alarm helpers ---

    private fun alarmManager(): AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    private fun scheduleBurstRevert() {
        val alarm = alarmManager() ?: return
        // Fire at the earlier of burst expiry and the high-accuracy cap, so
        // MOVING never outlives the cap when no further fixes arrive.
        val started = settings.movingStartedTimeMs
        val capRemainingMs = if (started > 0L) {
            (MAX_HIGH_ACCURACY_DURATION_MS - (clock() - started)).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }
        val delayMs = minOf(settings.burstDurationMs, capRemainingMs)
            .coerceAtLeast(MIN_BURST_ALARM_DELAY_MS)
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        runCatching {
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, triggerAt, burstEndPendingIntent())
        }.onFailure { Log.w(TAG, "Failed to schedule burst revert", it) }
    }

    private fun cancelBurstAlarm() {
        val alarm = alarmManager() ?: return
        runCatching { alarm.cancel(burstEndPendingIntent()) }
    }

    private fun schedulePeriodicPublish() {
        val alarm = alarmManager() ?: return
        val triggerAt = SystemClock.elapsedRealtime() + settings.periodicFallbackIntervalMs
        runCatching {
            alarm.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME, triggerAt, periodicPublishPendingIntent()
            )
        }.onFailure { Log.w(TAG, "Failed to schedule periodic publish", it) }
    }

    private fun cancelPeriodicPublish() {
        val alarm = alarmManager() ?: return
        runCatching { alarm.cancel(periodicPublishPendingIntent()) }
    }

    private fun scheduleHeartbeat() {
        val alarm = alarmManager() ?: return
        val triggerAt = SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS
        runCatching {
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, triggerAt, heartbeatPendingIntent())
        }.onFailure { Log.w(TAG, "Failed to schedule heartbeat", it) }
    }

    private fun cancelHeartbeatAlarm() {
        val alarm = alarmManager() ?: return
        runCatching { alarm.cancel(heartbeatPendingIntent()) }
    }

    companion object {
        private const val TAG = "HomeLocation"
        private const val ACTIVITY_UPDATE_INTERVAL_MS = 60_000L
        private const val MIN_MOVING_ACTIVITY_CONFIDENCE = 70
        private const val HEARTBEAT_INTERVAL_MS = 30 * 60_000L
        private const val MAX_HIGH_ACCURACY_DURATION_MS = 15 * 60_000L
        private const val ACTIVITY_COOLDOWN_MS = 15 * 60_000L
        private const val MIN_BURST_ALARM_DELAY_MS = 60_000L

        /** How stale a one-shot fix may be when confirming a zone entry. */
        private const val ZONE_CONFIRM_MAX_AGE_MS = 60_000L

        private const val REQUEST_CODE_LOCATION = 1
        private const val REQUEST_CODE_ACTIVITY = 2
        private const val REQUEST_CODE_BURST_END = 3
        private const val REQUEST_CODE_HEARTBEAT = 4
        private const val REQUEST_CODE_PERIODIC_PUBLISH = 5
    }

    private data class LocationRegistrationKey(
        val state: MovementState,
        val precise: Boolean,
        val fineGranted: Boolean,
        val stationaryIntervalMs: Long,
        val direct: Boolean
    )
}
