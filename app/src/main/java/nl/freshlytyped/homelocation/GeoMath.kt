package nl.freshlytyped.homelocation

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle distance helpers. */
object GeoMath {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    private const val DEG_TO_RAD = Math.PI / 180.0

    /** Haversine distance in meters between two coordinates. */
    fun distanceMeters(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double
    ): Double {
        val lat1 = fromLat * DEG_TO_RAD
        val lat2 = toLat * DEG_TO_RAD
        val dLat = (toLat - fromLat) * DEG_TO_RAD
        val dLon = (toLon - fromLon) * DEG_TO_RAD

        val h = sin(dLat / 2).pow(2) +
                cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h))
    }
}
