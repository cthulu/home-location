package nl.freshlytyped.homelocation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.LocationResult

/**
 * Single delivery point for all system-generated events:
 * - fused location updates (PendingIntent delivery from the Fused
 *   Location Provider — this works even when the app process is dead)
 * - activity recognition updates
 * - burst-expiry and heartbeat alarms
 *
 * The coordinator is idempotent, so each wake re-establishes whatever
 * should be active. Declared in the manifest with exported=false and a
 * private action.
 */
class LocationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? App
        if (app == null) {
            Log.e(TAG, "Location event ignored: unexpected application context")
            return
        }
        val coordinator = app.locationCoordinator

        val pendingResult = goAsync()
        try {
            when (intent.action) {
                ACTION_LOCATION_UPDATE -> {
                    val result = LocationResult.extractResult(intent)
                    if (result == null) {
                        app.diagnostics.record("location_update_empty_result")
                    } else {
                        coordinator.onLocationUpdates(result.locations)
                    }
                }

                ACTION_ACTIVITY_UPDATE -> {
                    val result = ActivityRecognitionResult.extractResult(intent)
                    coordinator.onActivityUpdate(
                        result?.mostProbableActivity?.type ?: DetectedActivity.UNKNOWN,
                        result?.mostProbableActivity?.confidence ?: 0,
                        result?.time ?: System.currentTimeMillis()
                    )
                }

                ACTION_BURST_END -> coordinator.onBurstEnd()
                ACTION_HEARTBEAT -> coordinator.onHeartbeat()
                ACTION_PERIODIC_PUBLISH -> coordinator.onPeriodicPublish()
            }
        } finally {
            pendingResult.finish()
        }
    }

    companion object {
        private const val TAG = "LocationReceiver"
        const val ACTION_LOCATION_UPDATE = "nl.freshlytyped.homelocation.ACTION_LOCATION_UPDATE"
        const val ACTION_ACTIVITY_UPDATE = "nl.freshlytyped.homelocation.ACTION_ACTIVITY_UPDATE"
        const val ACTION_BURST_END = "nl.freshlytyped.homelocation.ACTION_BURST_END"
        const val ACTION_HEARTBEAT = "nl.freshlytyped.homelocation.ACTION_HEARTBEAT"
        const val ACTION_PERIODIC_PUBLISH = "nl.freshlytyped.homelocation.ACTION_PERIODIC_PUBLISH"
    }
}
