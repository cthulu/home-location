package nl.freshlytyped.homelocation

import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority

/**
 * Builds the [LocationRequest] for a movement state.
 *
 * Stationary: balanced power accuracy with modest batching — cheap,
 * but still detects movement via distance accumulation.
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
                .setMaxUpdateDelayMillis(interval)
                .build()
        }
    }

    /**
     * Builds a low-frequency balanced-power request for use inside a known
     * zone. Uses the configured interval with batching to minimize wake-ups.
     */
    fun forLowFrequency(intervalMs: Long): LocationRequest {
        val interval = intervalMs.coerceIn(MIN_LOW_FREQUENCY_INTERVAL_MS, MAX_LOW_FREQUENCY_INTERVAL_MS)
        return LocationRequest.Builder(interval)
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateDelayMillis(interval)
            .build()
    }

    companion object {
        const val MOVING_INTERVAL_MS = 30_000L
        const val MOVING_MAX_DELAY_MS = 60_000L
        const val STATIONARY_INTERVAL_MS = 120_000L
        const val STATIONARY_MAX_DELAY_MS = 180_000L
        const val MIN_STATIONARY_INTERVAL_MS = 30_000L
        const val MAX_STATIONARY_INTERVAL_MS = 600_000L
        const val MIN_LOW_FREQUENCY_INTERVAL_MS = 60_000L
        const val MAX_LOW_FREQUENCY_INTERVAL_MS = 30 * 60_000L
    }
}
