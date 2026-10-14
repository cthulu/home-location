package nl.freshlytyped.homelocation

import android.location.Location

/**
 * Plain representation of a location fix, decoupled from the Android
 * framework so filtering and payload logic stay unit-testable.
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val timeMs: Long
)

fun Location.toFix(): LocationFix =
    LocationFix(latitude, longitude, accuracy, time)
