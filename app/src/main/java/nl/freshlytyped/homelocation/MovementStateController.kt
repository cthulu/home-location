package nl.freshlytyped.homelocation

/** What triggered a state observation. */
sealed class MovementEvidence {
    data class Fix(val fix: nl.freshlytyped.homelocation.LocationFix) : MovementEvidence()
    data class Activity(val activityType: Int, val timeMs: Long) : MovementEvidence()
    data object WifiLost : MovementEvidence()
}

/** Tunables the controller needs, snapshot from [LocationSettings]. */
data class MovementConfig(
    val movementDetectionDistanceMeters: Float,
    val burstDurationMs: Long
)

/** Result of observing an evidence sample. */
sealed class MovementDecision {
    /** Whether the sample itself counted as movement evidence. */
    abstract val isEvidence: Boolean

    data class NoChange(override val isEvidence: Boolean = false) : MovementDecision()

    data class ToMoving(val previous: MovementState) : MovementDecision() {
        override val isEvidence: Boolean = true
    }

    data class ToStationary(val previous: MovementState) : MovementDecision() {
        override val isEvidence: Boolean = false
    }
}

/**
 * Tracks whether the device is moving. Movement evidence comes from:
 * - activity recognition (in vehicle / on foot / on bike / walking / running)
 * - Wi-Fi network loss (opportunistic)
 * - accumulated travel distance across successive location fixes
 *
 * The controller is stateless between calls; the persisted state,
 * accumulated distance and evidence timestamp live in [LocationSettings]
 * and are passed in.
 */
class MovementStateController(private val config: MovementConfig) {

    /**
     * Observes an evidence sample and returns the state transition, if any.
     *
     * @param state current persisted movement state
     * @param lastKnownLat/Lon last known fix (0/0 when unknown)
     * @param hasLastKnown whether the last known fix is valid
     * @param accumulatedMeters travel distance accumulated since the last reset
     * @param lastEvidenceTime last time movement evidence was seen
     * @param nowMs current time
     */
    fun observe(
        evidence: MovementEvidence,
        state: MovementState,
        lastKnownLat: Double,
        lastKnownLon: Double,
        hasLastKnown: Boolean,
        accumulatedMeters: Float,
        lastEvidenceTime: Long,
        nowMs: Long
    ): MovementDecision {
        return when (evidence) {
            is MovementEvidence.Fix -> observeFix(
                evidence.fix, state, lastKnownLat, lastKnownLon, hasLastKnown,
                accumulatedMeters, lastEvidenceTime, nowMs
            )

            is MovementEvidence.Activity -> if (isMovingActivity(evidence.activityType)) {
                markEvidence(state)
            } else if (isStationaryActivity(evidence.activityType)) {
                burstExpired(state, lastEvidenceTime, nowMs)
            } else {
                MovementDecision.NoChange()
            }

            is MovementEvidence.WifiLost -> markEvidence(state)
        }
    }

    private fun observeFix(
        fix: LocationFix,
        state: MovementState,
        lastKnownLat: Double,
        lastKnownLon: Double,
        hasLastKnown: Boolean,
        accumulatedMeters: Float,
        lastEvidenceTime: Long,
        nowMs: Long
    ): MovementDecision {
        val moved = if (hasLastKnown && fix.timeMs > 0 &&
            GeoMath.distanceMeters(lastKnownLat, lastKnownLon, fix.latitude, fix.longitude) >= MIN_SEGMENT_METERS
        ) {
            // Ignore tiny segments: GPS jitter otherwise accumulates forever.
            GeoMath.distanceMeters(lastKnownLat, lastKnownLon, fix.latitude, fix.longitude)
        } else {
            0.0
        }
        if (accumulatedMeters + moved >= config.movementDetectionDistanceMeters) {
            return markEvidence(state)
        }
        return if (state == MovementState.MOVING) {
            burstExpired(state, lastEvidenceTime, nowMs)
        } else {
            MovementDecision.NoChange()
        }
    }

    /**
     * Accumulated travel since the last reset. The caller persists the
     * returned value alongside the updated last-known position.
     */
    fun nextAccumulatedMeters(
        lastKnownLat: Double,
        lastKnownLon: Double,
        hasLastKnown: Boolean,
        accumulatedMeters: Float,
        fix: nl.freshlytyped.homelocation.LocationFix
    ): Float {
        if (!hasLastKnown || fix.timeMs <= 0) return 0f
        val moved = GeoMath.distanceMeters(lastKnownLat, lastKnownLon, fix.latitude, fix.longitude)
        if (moved < MIN_SEGMENT_METERS) return accumulatedMeters
        return accumulatedMeters + moved.toFloat()
    }

    /**
     * Movement evidence: go moving if stationary, otherwise the sample
     * still counts as evidence (the caller refreshes the burst window).
     */
    private fun markEvidence(state: MovementState): MovementDecision =
        if (state == MovementState.MOVING) {
            MovementDecision.NoChange(isEvidence = true)
        } else {
            MovementDecision.ToMoving(state)
        }

    /**
     * While moving, the high-accuracy burst expires when no fresh evidence
     * arrived within the burst window.
     */
    fun burstExpired(
        state: MovementState,
        lastEvidenceTime: Long,
        nowMs: Long
    ): MovementDecision = if (state == MovementState.MOVING && lastEvidenceTime > 0 &&
        nowMs - lastEvidenceTime >= config.burstDurationMs
    ) {
        MovementDecision.ToStationary(state)
    } else {
        MovementDecision.NoChange()
    }

    fun isMovingActivity(activityType: Int): Boolean =
        activityType == ACTIVITY_TYPE_IN_VEHICLE ||
                activityType == ACTIVITY_TYPE_ON_FOOT ||
                activityType == ACTIVITY_TYPE_ON_BIKE ||
                activityType == ACTIVITY_TYPE_WALKING ||
                activityType == ACTIVITY_TYPE_RUNNING

    fun isStationaryActivity(activityType: Int): Boolean =
        activityType == ACTIVITY_TYPE_STILL

    /**
     * Fixes with worse accuracy cannot be trusted as movement evidence.
     * On the Razr a 129 m-accuracy fix switched into high-accuracy mode and
     * was then rejected by the publishing filter.
     */
    fun isReliableFix(fix: LocationFix): Boolean =
        fix.accuracyMeters.isFinite() && fix.accuracyMeters <= MOVEMENT_MAX_ACCURACY_METERS

    companion object {
        /** Accuracy required before a fix can trigger high-accuracy mode. */
        const val MOVEMENT_MAX_ACCURACY_METERS = 50f

        // Activity types from com.google.android.gms.location.DetectedActivity.
        const val ACTIVITY_TYPE_IN_VEHICLE = 1
        const val ACTIVITY_TYPE_ON_FOOT = 2
        const val ACTIVITY_TYPE_ON_BIKE = 3
        const val ACTIVITY_TYPE_WALKING = 4
        const val ACTIVITY_TYPE_RUNNING = 5
        const val ACTIVITY_TYPE_STILL = 6

        /** Segments shorter than this are treated as GPS jitter. */
        private const val MIN_SEGMENT_METERS = 5.0
    }
}
