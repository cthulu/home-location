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
import android.os.SystemClock
import android.util.Log
import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns all ongoing location infrastructure: the fused-location
 * registration (delivered via [LocationReceiver]), activity recognition,
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
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var roamingPaused = false
    @Volatile private var appInForeground = false

    private val controller: MovementStateController
        get() = MovementStateController(
            MovementConfig(settings.movementDetectionDistanceMeters, settings.burstDurationMs)
        )

    // --- Lifecycle ---

    fun start() {
        if (!settings.isConfigured()) {
            Log.i(TAG, "Start ignored: PubNub is not configured")
            return
        }
        if (!permissions.trackingAllowed()) {
            Log.i(TAG, "Start ignored: location permissions missing")
            return
        }
        settings.trackingEnabled = true
        ensureRegistered()
        requestImmediateLocation()
    }

    fun stop() {
        settings.trackingEnabled = false
        removeLocationRegistration()
        removeActivityRegistration()
        releaseWifiCallback()
        cancelBurstAlarm()
        cancelPeriodicPublish()
        cancelHeartbeatAlarm()
        releaseNetworkCallback()
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

    fun onHeartbeat() {
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

    // --- Evidence handlers (called from [LocationReceiver]) ---

    fun onLocationUpdates(locations: List<Location>) {
        if (!settings.trackingEnabled || roamingPaused) return
        val now = clock()
        for (location in locations) {
            handleFix(location.toFix(), now)
        }
        ensureRegistered()
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
    private fun oneShotLocationRequest(durationMs: Long): Task<Location> {
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location request skipped: no location permission")
            val tcs = TaskCompletionSource<Location>()
            tcs.setException(SecurityException("Location permission not granted"))
            return tcs.task
        }
        val priority = if (settings.preciseMode && permissions.fineGranted()) {
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
        if (!settings.trackingEnabled || roamingPaused) return
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

        val decision = controller.observe(
            MovementEvidence.Fix(fix),
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
            is LocationDecision.Accept -> publish(fix)
            is LocationDecision.Reject -> Log.d(TAG, "Fix not published: ${result.reason}")
        }
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
        // Optimistic update suppresses duplicate publishes for rapid fixes.
        settings.lastPublishedLat = fix.latitude
        settings.lastPublishedLon = fix.longitude
        // The UI and interval checks describe when the publish happened, not
        // the timestamp attached to a potentially batched location fix.
        settings.lastPublishedTimeMs = clock()
        settings.lastPublishedAccuracyMeters = fix.accuracyMeters
        scope.launch {
            if (roamingPaused) return@launch
            publisher.publish(fix)
                .onSuccess { Log.i(TAG, "Location published (state=${settings.movementState})") }
                .onFailure {
                    Log.w(TAG, "Publish failed: ${PublishFailureMapper.categoryFor(it)}")
                }
        }
    }

    fun onActivityUpdate(activityType: Int, timeMs: Long) {
        if (!settings.trackingEnabled || roamingPaused) return
        val now = if (timeMs > 0) timeMs else clock()
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
        if (!settings.trackingEnabled || roamingPaused) return
        val now = clock()
        val decision = controller.observe(
            MovementEvidence.WifiLost,
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
        applyStateChange(decision)
    }

    fun onBurstEnd() {
        if (!settings.trackingEnabled || roamingPaused) return
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

    /** Applies a state transition and (re)registers the matching request. */
    private fun applyStateChange(decision: MovementDecision) {
        when (decision) {
            is MovementDecision.ToMoving -> {
                settings.movementState = MovementState.MOVING
                settings.accumulatedDistanceMeters = 0f
                Log.i(TAG, "State -> MOVING")
                registerLocationUpdates()
                scheduleBurstRevert()
                cancelPeriodicPublish()
            }

            is MovementDecision.ToStationary -> {
                settings.movementState = MovementState.STATIONARY
                settings.accumulatedDistanceMeters = 0f
                Log.i(TAG, "State -> STATIONARY")
                registerLocationUpdates()
                cancelBurstAlarm()
                if (settings.periodicFallbackEnabled) schedulePeriodicPublish()
                else cancelPeriodicPublish()
            }

            is MovementDecision.NoChange -> Unit
        }
    }

    // --- Registration helpers ---

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
        val request = requestFactory.forState(
            settings.movementState,
            settings.preciseMode,
            permissions.fineGranted(),
            settings.backgroundScanIntervalMs
        )
        runCatching { client.requestLocationUpdates(request, locationPendingIntent()) }
            .onSuccess { task ->
                task.addOnFailureListener {
                    Log.w(TAG, "Failed to register location updates", it)
                }
            }
            .onFailure { Log.w(TAG, "Failed to register location updates", it) }
    }

    private fun removeLocationRegistration() {
        runCatching {
            LocationServices.getFusedLocationProviderClient(context)
                .removeLocationUpdates(locationPendingIntent())
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerActivityRecognition() {
        if (!hasActivityRecognitionPermission()) return
        val client: ActivityRecognitionClient = ActivityRecognition.getClient(context)
        runCatching {
            client.requestActivityUpdates(ACTIVITY_UPDATE_INTERVAL_MS, activityPendingIntent())
        }.onFailure { Log.w(TAG, "Failed to register activity recognition", it) }
    }

    @SuppressLint("MissingPermission")
    private fun removeActivityRegistration() {
        if (!hasActivityRecognitionPermission()) return
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
        private const val ACTIVITY_UPDATE_INTERVAL_MS = 30_000L
        private const val HEARTBEAT_INTERVAL_MS = 30 * 60_000L

        private const val REQUEST_CODE_LOCATION = 1
        private const val REQUEST_CODE_ACTIVITY = 2
        private const val REQUEST_CODE_BURST_END = 3
        private const val REQUEST_CODE_HEARTBEAT = 4
        private const val REQUEST_CODE_PERIODIC_PUBLISH = 5
    }
}
