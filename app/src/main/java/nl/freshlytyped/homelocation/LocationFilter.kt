package nl.freshlytyped.homelocation

/** Why a fix was not published. */
enum class RejectReason {
    INVALID_COORDINATES,
    BAD_ACCURACY,
    STALE,
    FUTURE_TIMESTAMP,
    NO_CHANGE
}

sealed class LocationDecision {
    data object Accept : LocationDecision()
    data class Reject(val reason: RejectReason) : LocationDecision()
}

/**
 * Input for [LocationFilter.evaluate]. The "last published" fields are
 * 0/empty when nothing was published yet.
 */
data class FilterContext(
    val maxAccuracyMeters: Float,
    val maxLocationAgeMs: Long,
    val minPublishIntervalMs: Long,
    val minDistanceMeters: Float,
    val periodicFallbackEnabled: Boolean,
    val periodicFallbackIntervalMs: Long,
    val lastPublishedLat: Double = 0.0,
    val lastPublishedLon: Double = 0.0,
    val lastPublishedTimeMs: Long = 0L
)

/** Tolerance for clock skew: fixes up to this far in the future are accepted. */
const val FUTURE_TOLERANCE_MS = 60_000L

/**
 * Decides whether a fix should be published. Pure logic, no Android
 * dependencies, so it is straightforward to unit test.
 */
class LocationFilter {

    fun evaluate(fix: LocationFix, nowMs: Long, ctx: FilterContext): LocationDecision {
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite()) {
            return reject(RejectReason.INVALID_COORDINATES)
        }
        if (fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0) {
            return reject(RejectReason.INVALID_COORDINATES)
        }
        if (!fix.accuracyMeters.isFinite() || fix.accuracyMeters > ctx.maxAccuracyMeters) {
            return reject(RejectReason.BAD_ACCURACY)
        }
        if (fix.timeMs > nowMs + FUTURE_TOLERANCE_MS) {
            return reject(RejectReason.FUTURE_TIMESTAMP)
        }
        if (nowMs - fix.timeMs > ctx.maxLocationAgeMs) {
            return reject(RejectReason.STALE)
        }

        if (ctx.lastPublishedTimeMs <= 0L) return LocationDecision.Accept
        if (movedEnough(fix, ctx)) return LocationDecision.Accept
        if (ctx.periodicFallbackEnabled &&
            nowMs - ctx.lastPublishedTimeMs >= ctx.periodicFallbackIntervalMs
        ) {
            return LocationDecision.Accept
        }
        return reject(RejectReason.NO_CHANGE)
    }

    private fun movedEnough(fix: LocationFix, ctx: FilterContext): Boolean =
        GeoMath.distanceMeters(
            ctx.lastPublishedLat, ctx.lastPublishedLon, fix.latitude, fix.longitude
        ) >= ctx.minDistanceMeters

    private fun reject(reason: RejectReason) = LocationDecision.Reject(reason)
}
