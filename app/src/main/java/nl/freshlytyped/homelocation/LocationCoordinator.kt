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
import android.util.Log
import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.CurrentLocationRequest
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
class LocationCoordinator(
    private val context: Context,
    private val settings: LocationSettings,
    private val permissions: LocationPermissionManager,
    private val publisher: LocationPublisher,
    private val requestFactory: LocationRequestFactory = LocationRequestFactory(),
    private val filter: LocationFilter = LocationFilter(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zoneEntryHysteresisMs: Long = 60_000L
) {

    private val diagnostics = DiagnosticLogger(context)
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
    private var pendingPublishInFlight = false
    private var registeredLocationKey: LocationRegistrationKey? = null
    private var activityRegistrationActive = false
    @Volatile private var lastLocationCallbackTimeMs = 0L

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

        // Pause all tracking when inside a zone: keep only geofencing and PubNub.
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
        forceBalanced: Boolean = false
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
            .setMaxUpdateAgeMillis(0L)
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
        if (!settings.isConfigured()) {
            Log.w(TAG, "Fix accepted but PubNub not configured; skipping publish")
            return
        }
        storePendingIfNewer(fix)
        publishAttempt(fix)
    }

    private fun storePendingIfNewer(fix: LocationFix) {
        val pending = settings.pendingLocation
        if (pending == null || fix.timeMs >= pending.timeMs) {
            settings.pendingLocation = fix
        }
    }

    private fun publishAttempt(fix: LocationFix) {
        scope.launch {
            if (roamingPaused) return@launch
            publisher.publish(fix)
                .onSuccess {
                    markPublished(fix)
                    diagnosticsRecord("publish success state=${settings.movementState}")
                    Log.i(TAG, "Location published (state=${settings.movementState})")
                }
                .onFailure {
                    storePendingIfNewer(fix)
                    diagnosticsRecord("publish failure category=${PublishFailureMapper.categoryFor(it)}")
                    Log.w(TAG, "Publish failed: ${PublishFailureMapper.categoryFor(it)}")
                }
        }
    }

    private fun retryPendingPublish() {
        if (pendingPublishInFlight || roamingPaused) return
        val pending = settings.pendingLocation ?: return
        pendingPublishInFlight = true
        scope.launch {
            publisher.publish(pending)
                .onSuccess {
                    markPublished(pending)
                    diagnosticsRecord("pending_publish success")
                }
                .onFailure {
                    diagnosticsRecord("pending_publish failure category=${PublishFailureMapper.categoryFor(it)}")
                }
            pendingPublishInFlight = false
        }
    }

    private fun markPublished(fix: LocationFix) {
        if (settings.pendingLocation?.timeMs == fix.timeMs) {
            settings.pendingLocation = null
        }
        settings.lastPublishedLat = fix.latitude
        settings.lastPublishedLon = fix.longitude
        settings.lastPublishedTimeMs = clock()
        settings.lastPublishedAccuracyMeters = fix.accuracyMeters
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

    /** Called from [GeofenceReceiver] when the device enters a zone. */
    fun onZoneEntered(zoneId: String) {
        val now = clock()
        val lastTransition = settings.lastZoneTransitionMs
        val debounceMs = settings.geofenceDebounceMs
        val currentZoneId = settings.currentZoneId
        if (currentZoneId == zoneId) return
        if (currentZoneId != null && lastTransition > 0L && now - lastTransition < debounceMs) {
            diagnostics.record("zone_entered id=$zoneId debounce_ignored=true")
            Log.i(TAG, "Zone enter debounced: $zoneId")
            return
        }
        if (zoneEntryHysteresisMs > 0L) {
            pendingZoneEntryJob?.cancel()
            pendingZoneEntryId = zoneId
            diagnostics.record("zone_enter_pending id=$zoneId delayMs=$zoneEntryHysteresisMs")
            pendingZoneEntryJob = scope.launch {
                delay(zoneEntryHysteresisMs)
                confirmZoneEntry(zoneId)
            }
            return
        }
        applyZoneEntered(zoneId, now)
    }

    private fun confirmZoneEntry(zoneId: String) {
        if (pendingZoneEntryId != zoneId || settings.currentZoneId != null) return
        val zone = zoneManager.getZone(zoneId)
        if (zone == null) {
            diagnostics.record("zone_enter_rejected id=$zoneId reason=zone_not_found")
            pendingZoneEntryId = null
            return
        }
        oneShotLocationRequest(30_000L, forceBalanced = true)
            .addOnSuccessListener { location ->
                if (pendingZoneEntryId != zoneId) return@addOnSuccessListener
                pendingZoneEntryId = null
                if (location == null) {
                    diagnostics.record("zone_enter_rejected id=$zoneId reason=no_location")
                    return@addOnSuccessListener
                }
                val distance = GeoMath.distanceMeters(
                    location.latitude,
                    location.longitude,
                    zone.latitude,
                    zone.longitude
                )
                if (distance <= zone.radius) {
                    publish(location.toFix())
                    applyZoneEntered(zoneId, clock())
                } else {
                    diagnostics.record("zone_enter_rejected id=$zoneId reason=outside distanceMeters=$distance")
                }
            }
            .addOnFailureListener {
                pendingZoneEntryId = null
                diagnostics.record("zone_enter_rejected id=$zoneId reason=location_failed")
            }
    }

    private fun applyZoneEntered(zoneId: String, now: Long) {
        settings.currentZoneId = zoneId
        settings.lastZoneTransitionMs = now
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

    private fun isCellularRoaming(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)

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
        val triggerAt = SystemClock.elapsedRealtime() + settings.burstDurationMs
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
