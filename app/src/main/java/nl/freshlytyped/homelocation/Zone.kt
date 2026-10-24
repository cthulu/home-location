package nl.freshlytyped.homelocation

/** Zone definition received from Home Assistant via PubNub. */
data class Zone(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Int
)
