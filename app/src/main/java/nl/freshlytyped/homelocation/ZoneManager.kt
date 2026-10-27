package nl.freshlytyped.homelocation

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.os.Build
import android.util.Log
import android.annotation.SuppressLint
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Manages zone definitions received from Home Assistant via PubNub.
 * Persists the zone list, registers geofences with GeofencingClient,
 * and reports the current zone state.
 */
class ZoneManager(
    private val context: Context,
    private val settings: LocationSettings,
    private val diagnostics: DiagnosticLogger
) {
    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)

    val isInsideZone: Boolean
        get() = settings.isInsideZone

    val currentZoneId: String?
        get() = settings.currentZoneId

    /**
     * Apply a new zone list: persist it, register geofences, and clear
     * the current zone if it no longer exists in the list.
     */
    fun updateZones(zones: List<Zone>) {
        diagnostics.record("zone_update received count=${zones.size}")
        val json = persistZones(zones)
        settings.persistedZones = json
        if (zones.isEmpty()) {
            settings.currentZoneId = null
            removeGeofences()
            return
        }
        val ids = zones.map { it.name }.toSet()
        if (settings.currentZoneId != null && !ids.contains(settings.currentZoneId)) {
            settings.currentZoneId = null
        }
        registerGeofencesInternal(zones)
    }

    /** Parse the persisted zone JSON back into a list. Returns empty list on parse error. */
    fun getZones(): List<Zone> {
        val json = settings.persistedZones ?: return emptyList()
        return runCatching { parseZones(json) }.getOrNull() ?: emptyList()
    }

    /** Re-register geofences from the persisted zone list. Used on cold start. */
    fun registerGeofences() {
        val zones = getZones()
        if (zones.isNotEmpty()) {
            registerGeofencesInternal(zones)
        }
    }

    /** Remove all registered geofences. */
    fun removeGeofences() {
        val pendingIntent = geofencePendingIntent()
        try {
            geofencingClient.removeGeofences(pendingIntent)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to remove geofences", e)
        }
    }

    private fun persistZones(zones: List<Zone>): String {
        val arr = JSONArray()
        for (zone in zones) {
            arr.put(JSONObject().apply {
                put("name", zone.name)
                put("latitude", zone.latitude)
                put("longitude", zone.longitude)
                put("radius", zone.radius)
            })
        }
        return arr.toString()
    }

    private fun parseZones(json: String): List<Zone> {
        val arr = JSONArray(json)
        val zones = mutableListOf<Zone>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            zones.add(Zone(
                name = obj.getString("name"),
                latitude = obj.getDouble("latitude"),
                longitude = obj.getDouble("longitude"),
                radius = obj.getInt("radius")
            ))
        }
        return zones
    }

    @SuppressLint("MissingPermission")
    private fun registerGeofencesInternal(zones: List<Zone>) {
        val geofences = zones.map { zone ->
            Geofence.Builder().apply {
                setRequestId(zone.name)
                setCircularRegion(zone.latitude, zone.longitude, zone.radius.toFloat())
                setExpirationDuration(TimeUnit.HOURS.toMillis(24))
                setTransitionTypes(
                    Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
                )
            }.build()
        }
        if (geofences.isEmpty()) return
        val pendingIntent = geofencePendingIntent()
        val request = GeofencingRequest.Builder().apply {
            addGeofences(geofences)
        }.build()
        try {
            geofencingClient.removeGeofences(pendingIntent).addOnCompleteListener {
                geofencingClient.addGeofences(request, pendingIntent)
                .addOnSuccessListener {
                    diagnostics.record("zone_geofences_registered count=${geofences.size}")
                    Log.i(TAG, "Registered ${geofences.size} geofences")
                }
            }
                .addOnFailureListener { error: java.lang.Exception ->
                    diagnostics.record("zone_geofence_registration_failed ${error.javaClass.simpleName}")
                    Log.w(TAG, "Failed to register geofences", error)
                }
        } catch (e: Throwable) {
            diagnostics.record("zone_geofence_request_rejected ${e.javaClass.simpleName}")
            Log.w(TAG, "Geofence request rejected", e)
        }
    }

    private fun geofencePendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java).setAction(
            GeofenceReceiver.ACTION_GEOFENCE_TRANSITION
        )
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_GEOFENCE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
        )
    }

    companion object {
        private const val TAG = "ZoneManager"
        private const val REQUEST_CODE_GEOFENCE = 10
    }
}
