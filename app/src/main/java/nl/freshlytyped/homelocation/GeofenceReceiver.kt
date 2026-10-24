package nl.freshlytyped.homelocation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * BroadcastReceiver for geofence transition events from GeofencingClient.
 *
 * On ENTER: applies debounce — if the last zone transition was less than
 * the debounce interval ago, the event is ignored. Records the zone entry
 * time and notifies the coordinator to pause tracking.
 *
 * On EXIT: immediately notifies the coordinator to resume tracking.
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? App
            ?: throw IllegalStateException("Expected HomeLocation application")
        val coordinator = app.locationCoordinator

        val geofencingEvent = GeofencingEvent.fromIntent(intent)
                ?: throw IllegalStateException("Invalid geofence intent")

        if (geofencingEvent.hasError()) {
            Log.w(TAG, "Geofence error: ${geofencingEvent.errorCode}")
            return
        }

        val transition = geofencingEvent.geofenceTransition
        val triggeredGeofences = geofencingEvent.triggeringGeofences ?: return
        val triggeringLocation = geofencingEvent.triggeringLocation

        for (geofence in triggeredGeofences) {
            val zoneId = geofence.requestId
            when (transition) {
                Geofence.GEOFENCE_TRANSITION_ENTER -> {
                    coordinator.onZoneEntered(zoneId)
                }
                Geofence.GEOFENCE_TRANSITION_EXIT -> {
                    coordinator.onZoneExited(zoneId)
                }
            }
        }
    }

    companion object {
        private const val TAG = "GeofenceReceiver"
        const val ACTION_GEOFENCE_TRANSITION = "nl.freshlytyped.homelocation.ACTION_GEOFENCE_TRANSITION"
    }
}
