package nl.freshlytyped.homelocation

import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority

/**
 * Builds the continuous [LocationRequest]. It is balanced power accuracy in
 * every movement state; GPS is only used for periodic one-shot fixes while
 * moving, so it is never left running.
 */
class LocationRequestFactory {

    fun balanced(intervalMs: Long = DEFAULT_INTERVAL_MS): LocationRequest {
        val interval = intervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
        return LocationRequest.Builder(interval)
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateDelayMillis(interval)
            .build()
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 120_000L
        const val MIN_INTERVAL_MS = 30_000L
        const val MAX_INTERVAL_MS = 600_000L
    }
}
