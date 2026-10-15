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
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.ActivityRecognitionClient
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.LocationServices
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
    }

    fun stop() {
        settings.trackingEnabled = false
        removeLocationRegistration()
        removeActivityRegistration()
        releaseWifiCallback()
        cancelBurstAlarm()
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
        registerLocationUpdates()
        registerActivityRecognition()
        if (settings.movementState == MovementState.MOVING) scheduleBurstRevert()
        registerWifiCallback()
        scheduleHeartbeat()
    }

    fun onHeartbeat() {
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

        val ctx = FilterContext(
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
        when (val result = filter.evaluate(fix, now, ctx)) {
            is LocationDecision.Accept -> publish(fix)
            is LocationDecision.Reject -> Log.d(TAG, "Fix not published: ${result.reason}")
        }
    }

    private fun publish(fix: LocationFix) {
        if (roamingPaused) return
        if (!settings.isConfigured()) {
            Log.w(TAG, "Fix accepted but PubNub not configured; skipping publish")
            return
        }
        // Optimistic update suppresses duplicate publishes for rapid fixes.
        settings.lastPublishedLat = fix.latitude
        settings.lastPublishedLon = fix.longitude
        settings.lastPublishedTimeMs = fix.timeMs
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
            }

            is MovementDecision.ToStationary -> {
                settings.movementState = MovementState.STATIONARY
                settings.accumulatedDistanceMeters = 0f
                Log.i(TAG, "State -> STATIONARY")
                registerLocationUpdates()
                cancelBurstAlarm()
            }

            is MovementDecision.NoChange -> Unit
        }
    }

    // --- Registration helpers ---

    private fun locationPendingIntent(): PendingIntent =
        broadcastIntent(REQUEST_CODE_LOCATION, LocationReceiver.ACTION_LOCATION_UPDATE)

    private fun activityPendingIntent(): PendingIntent =
        broadcastIntent(REQUEST_CODE_ACTIVITY, LocationReceiver.ACTION_ACTIVITY_UPDATE)

    private fun burstEndPendingIntent(): PendingIntent =
        broadcastIntent(REQUEST_CODE_BURST_END, LocationReceiver.ACTION_BURST_END)

    private fun heartbeatPendingIntent(): PendingIntent =
        broadcastIntent(REQUEST_CODE_HEARTBEAT, LocationReceiver.ACTION_HEARTBEAT)

    private fun broadcastIntent(requestCode: Int, action: String): PendingIntent {
        val intent = Intent(context, LocationReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun registerLocationUpdates() {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = requestFactory.forState(
            settings.movementState,
            settings.preciseMode,
            permissions.fineGranted()
        )
        runCatching { client.requestLocationUpdates(request, locationPendingIntent()) }
            .onFailure { Log.w(TAG, "Failed to register location updates", it) }
    }

    private fun removeLocationRegistration() {
        runCatching {
            LocationServices.getFusedLocationProviderClient(context)
                .removeLocationUpdates(locationPendingIntent())
        }
    }

    private fun registerActivityRecognition() {
        if (!permissions.activityRecognitionGranted()) return
        val client: ActivityRecognitionClient = ActivityRecognition.getClient(context)
        runCatching {
            client.requestActivityUpdates(ACTIVITY_UPDATE_INTERVAL_MS, activityPendingIntent())
        }.onFailure { Log.w(TAG, "Failed to register activity recognition", it) }
    }

    private fun removeActivityRegistration() {
        if (!permissions.activityRecognitionGranted()) return
        runCatching {
            ActivityRecognition.getClient(context)
                .removeActivityUpdates(activityPendingIntent())
        }
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
    }
}
