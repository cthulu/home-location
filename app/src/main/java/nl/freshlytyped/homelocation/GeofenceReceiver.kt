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
        val diagnostics = app.diagnostics

        val geofencingEvent = GeofencingEvent.fromIntent(intent)
            ?: run {
                diagnostics.record("geofence_event_invalid_intent")
                throw IllegalStateException("Invalid geofence intent")
            }

        if (geofencingEvent.hasError()) {
            diagnostics.record("geofence_event_error code=${geofencingEvent.errorCode}")
            Log.w(TAG, "Geofence error: ${geofencingEvent.errorCode}")
            return
        }

        val transition = geofencingEvent.geofenceTransition
        val triggeringLocation = geofencingEvent.triggeringLocation
        val triggeredGeofences = geofencingEvent.triggeringGeofences
        diagnostics.record(
            "geofence_event transition=$transition " +
                "count=${triggeredGeofences?.size ?: 0} " +
                "location=${triggeringLocation != null} " +
                "accuracy=${triggeringLocation?.accuracy ?: -1f}"
        )
        if (triggeredGeofences == null) {
            diagnostics.record("geofence_event_missing_triggered_geofences")
            return
        }

        for (geofence in triggeredGeofences) {
            val zoneId = geofence.requestId
            diagnostics.record("geofence_transition transition=$transition id=$zoneId")
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
