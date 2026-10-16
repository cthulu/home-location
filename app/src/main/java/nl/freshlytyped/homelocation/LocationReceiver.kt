package nl.freshlytyped.homelocation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
            ?: throw IllegalStateException("Expected HomeLocation application")
        val coordinator = app.locationCoordinator

        when (intent.action) {
            ACTION_LOCATION_UPDATE -> {
                LocationResult.extractResult(intent)?.locations?.let(coordinator::onLocationUpdates)
            }

            ACTION_ACTIVITY_UPDATE -> {
                val activityType = extractActivityType(intent)
                coordinator.onActivityUpdate(activityType, System.currentTimeMillis())
            }

            ACTION_BURST_END -> coordinator.onBurstEnd()
            ACTION_HEARTBEAT -> coordinator.onHeartbeat()
            ACTION_PERIODIC_PUBLISH -> coordinator.onPeriodicPublish()
        }
    }

    private fun extractActivityType(intent: Intent): Int {
        val result = ActivityRecognitionResult.extractResult(intent) ?: return DetectedActivity.UNKNOWN
        return result.mostProbableActivity.type
    }

    companion object {
        const val ACTION_LOCATION_UPDATE = "nl.freshlytyped.homelocation.ACTION_LOCATION_UPDATE"
        const val ACTION_ACTIVITY_UPDATE = "nl.freshlytyped.homelocation.ACTION_ACTIVITY_UPDATE"
        const val ACTION_BURST_END = "nl.freshlytyped.homelocation.ACTION_BURST_END"
        const val ACTION_HEARTBEAT = "nl.freshlytyped.homelocation.ACTION_HEARTBEAT"
        const val ACTION_PERIODIC_PUBLISH = "nl.freshlytyped.homelocation.ACTION_PERIODIC_PUBLISH"
    }
}
