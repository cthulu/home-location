package nl.freshlytyped.homelocation

import com.google.android.gms.location.Priority
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationRequestFactoryTest {

    private val factory = LocationRequestFactory()

    @Test
    fun `moving with precise mode and fine location uses high accuracy`() {
        val request = factory.forState(MovementState.MOVING, preciseMode = true, fineLocationGranted = true)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, request.priority)
        assertEquals(30_000L, request.interval)
        assertEquals(60_000L, request.maxUpdateDelayMillis)
    }

    @Test
    fun `moving with precise mode but no fine location falls back to balanced`() {
        val request = factory.forState(MovementState.MOVING, preciseMode = true, fineLocationGranted = false)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, request.priority)
        assertEquals(60_000L, request.interval)
        assertEquals(60_000L, request.maxUpdateDelayMillis)
    }

    @Test
    fun `moving with precise mode disabled uses balanced`() {
        val request = factory.forState(MovementState.MOVING, preciseMode = false, fineLocationGranted = true)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, request.priority)
    }

    @Test
    fun `stationary uses balanced power accuracy with modest batching`() {
        val request = factory.forState(MovementState.STATIONARY, preciseMode = true, fineLocationGranted = true)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, request.priority)
        assertEquals(60_000L, request.interval)
        assertEquals(60_000L, request.maxUpdateDelayMillis)
    }

    @Test
    fun `stationary interval is clamped to the supported range`() {
        val tooShort = factory.forState(MovementState.STATIONARY, true, true, 1_000L)
        val tooLong = factory.forState(MovementState.STATIONARY, true, true, 900_000L)

        assertEquals(30_000L, tooShort.interval)
        assertEquals(30_000L, tooShort.maxUpdateDelayMillis)
        assertEquals(600_000L, tooLong.interval)
        assertEquals(600_000L, tooLong.maxUpdateDelayMillis)
    }

    @Test
    fun `low frequency uses balanced power accuracy with the configured interval`() {
        val request = factory.forLowFrequency(300_000L)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, request.priority)
        assertEquals(300_000L, request.interval)
        assertEquals(300_000L, request.maxUpdateDelayMillis)
    }

    @Test
    fun `low frequency interval is clamped to the supported range`() {
        val tooShort = factory.forLowFrequency(10_000L)
        val tooLong = factory.forLowFrequency(60 * 60_000L)

        assertEquals(60_000L, tooShort.interval)
        assertEquals(60_000L, tooShort.maxUpdateDelayMillis)
        assertEquals(30 * 60_000L, tooLong.interval)
        assertEquals(30 * 60_000L, tooLong.maxUpdateDelayMillis)
    }
}
