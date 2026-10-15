package nl.freshlytyped.homelocation

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationFilterTest {

    private val filter = LocationFilter()
    private val now = 1_000_000_000_000L

    private fun ctx(
        lastLat: Double = 52.0,
        lastLon: Double = 5.0,
        lastTime: Long = 0L,
        fallback: Boolean = false,
        fallbackIntervalMs: Long = 30 * 60_000L,
        maxAccuracyMeters: Float = 50f,
        maxAgeMs: Long = 15 * 60_000L,
        minDistanceMeters: Float = 50f
    ) = FilterContext(
        maxAccuracyMeters = maxAccuracyMeters,
        maxLocationAgeMs = maxAgeMs,
        minPublishIntervalMs = 60_000L,
        minDistanceMeters = minDistanceMeters,
        periodicFallbackEnabled = fallback,
        periodicFallbackIntervalMs = fallbackIntervalMs,
        lastPublishedLat = lastLat,
        lastPublishedLon = lastLon,
        lastPublishedTimeMs = lastTime
    )

    private fun fix(
        lat: Double = 52.0,
        lon: Double = 5.0,
        accuracy: Float = 10f,
        time: Long = now
    ) = LocationFix(lat, lon, accuracy, time)

    private fun expectAccept(fix: LocationFix, ctx: FilterContext) {
        assertEquals(LocationDecision.Accept, filter.evaluate(fix, now, ctx))
    }

    private fun expectReject(fix: LocationFix, ctx: FilterContext, reason: RejectReason) {
        assertEquals(LocationDecision.Reject(reason), filter.evaluate(fix, now, ctx))
    }

    @Test
    fun `first fix is accepted`() {
        expectAccept(fix(), ctx(lastTime = 0))
    }

    @Test
    fun `fix beyond minimum distance is accepted`() {
        // 0.001 degree of latitude is about 111 m, above the 50 m threshold
        expectAccept(fix(lat = 52.001), ctx(lastLat = 52.0, lastTime = now - 30_000))
    }

    @Test
    fun `fix inside the minimum distance is rejected as no change`() {
        // 0.0004 degree is about 44.5 m, below the 50 m threshold
        expectReject(
            fix(lat = 52.0004),
            ctx(lastLat = 52.0, lastTime = now - 30_000),
            RejectReason.NO_CHANGE
        )
    }

    @Test
    fun `movement is accepted even inside the minimum publish interval`() {
        expectAccept(fix(lat = 52.001), ctx(lastLat = 52.0, lastTime = now - 10_000))
    }

    @Test
    fun `periodic fallback accepts after the fallback interval elapsed`() {
        expectAccept(
            fix(lat = 52.0004),
            ctx(lastLat = 52.0, lastTime = now - 31 * 60_000L, fallback = true)
        )
    }

    @Test
    fun `periodic fallback does not accept before the fallback interval elapsed`() {
        expectReject(
            fix(lat = 52.0004),
            ctx(lastLat = 52.0, lastTime = now - 30_000, fallback = true),
            RejectReason.NO_CHANGE
        )
    }

    @Test
    fun `without fallback a stationary fix is never accepted`() {
        expectReject(fix(), ctx(lastTime = now - 2 * 3_600_000L), RejectReason.NO_CHANGE)
    }

    @Test
    fun `accuracy at the maximum is accepted`() {
        expectAccept(fix(accuracy = 50f), ctx(lastTime = 0))
    }

    @Test
    fun `accuracy beyond the maximum is rejected`() {
        expectReject(fix(accuracy = 51f), ctx(lastTime = 0), RejectReason.BAD_ACCURACY)
    }

    @Test
    fun `unknown accuracy is rejected`() {
        expectReject(fix(accuracy = Float.NaN), ctx(lastTime = 0), RejectReason.BAD_ACCURACY)
    }

    @Test
    fun `fix at the maximum age is accepted`() {
        expectAccept(fix(time = now - 15 * 60_000L), ctx(lastTime = 0))
    }

    @Test
    fun `stale fix is rejected`() {
        expectReject(fix(time = now - 16 * 60_000L), ctx(lastTime = 0), RejectReason.STALE)
    }

    @Test
    fun `slightly future fix within the tolerance is accepted`() {
        expectAccept(fix(time = now + 30_000), ctx(lastTime = 0))
    }

    @Test
    fun `fix far in the future is rejected`() {
        expectReject(fix(time = now + 5 * 60_000L), ctx(lastTime = 0), RejectReason.FUTURE_TIMESTAMP)
    }

    @Test
    fun `latitude beyond 90 is rejected`() {
        expectReject(fix(lat = 91.0), ctx(lastTime = 0), RejectReason.INVALID_COORDINATES)
    }

    @Test
    fun `longitude beyond 180 is rejected`() {
        expectReject(fix(lon = 181.0), ctx(lastTime = 0), RejectReason.INVALID_COORDINATES)
    }

    @Test
    fun `non-finite coordinates are rejected`() {
        expectReject(fix(lat = Double.NaN), ctx(lastTime = 0), RejectReason.INVALID_COORDINATES)
    }

    @Test
    fun `boundary coordinates are accepted`() {
        expectAccept(fix(lat = 90.0, lon = -180.0), ctx(lastTime = 0))
    }
}
