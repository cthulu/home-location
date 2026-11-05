package nl.freshlytyped.homelocation

/** Zone definition received from Home Assistant via PubNub. */
data class Zone(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Int
) {
    /** Validity for geofence registration; invalid zones must never reach Geofence.Builder. */
    fun isValid(): Boolean =
        name.isNotBlank() &&
            latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0 &&
            radius > 0
}
