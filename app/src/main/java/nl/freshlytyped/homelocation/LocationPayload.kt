package nl.freshlytyped.homelocation

import org.json.JSONObject

/**
 * Builds the PubNub message for a location fix.
 *
 * The "type" discriminator lets a shared channel carry both location
 * and other message types; consumers must filter on it.
 */
object LocationPayload {

    const val TYPE = "location"

    /** Coordinates are rounded to 6 decimals to match the Home Assistant helper step. */
    fun build(fix: LocationFix, deviceName: String): String = JSONObject()
        .put("type", TYPE)
        .put("device_name", deviceName)
        .put("latitude", round6(fix.latitude))
        .put("longitude", round6(fix.longitude))
        .put("accuracy", if (fix.accuracyMeters.isFinite()) round1(fix.accuracyMeters) else JSONObject.NULL)
        .put("timestamp", fix.timeMs)
        .toString()

    private fun round6(value: Double): Double =
        Math.round(value * 1_000_000.0) / 1_000_000.0

    private fun round1(value: Float): Double =
        Math.round(value.toDouble() * 10.0) / 10.0
}
