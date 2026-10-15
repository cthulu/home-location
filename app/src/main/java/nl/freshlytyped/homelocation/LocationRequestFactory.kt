package nl.freshlytyped.homelocation

import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority

/**
 * Builds the [LocationRequest] for a movement state.
 *
 * Stationary: balanced power accuracy with generous batching (1-3 min) —
 * cheap, and still detects movement via distance accumulation.
 *
 * Moving: high accuracy (when requested and permitted) at a tighter
 * cadence, matching the desired 1-5 minute freshness while moving.
 */
class LocationRequestFactory {

    fun forState(
        state: MovementState,
        preciseMode: Boolean,
        fineLocationGranted: Boolean,
        stationaryIntervalMs: Long = STATIONARY_INTERVAL_MS
    ): LocationRequest {
        val highAccuracy = state == MovementState.MOVING && preciseMode && fineLocationGranted
        return if (highAccuracy) {
            LocationRequest.Builder(MOVING_INTERVAL_MS)
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateDelayMillis(MOVING_MAX_DELAY_MS)
                .build()
        } else {
            val interval = stationaryIntervalMs.coerceIn(MIN_STATIONARY_INTERVAL_MS, MAX_STATIONARY_INTERVAL_MS)
            LocationRequest.Builder(interval)
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setMaxUpdateDelayMillis(interval * 3)
                .build()
        }
    }

    companion object {
        const val MOVING_INTERVAL_MS = 30_000L
        const val MOVING_MAX_DELAY_MS = 60_000L
        const val STATIONARY_INTERVAL_MS = 60_000L
        const val STATIONARY_MAX_DELAY_MS = 180_000L
        const val MIN_STATIONARY_INTERVAL_MS = 60_000L
        const val MAX_STATIONARY_INTERVAL_MS = 60 * 60_000L
    }
}
