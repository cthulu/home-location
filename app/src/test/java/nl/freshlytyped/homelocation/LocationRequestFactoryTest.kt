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
        assertEquals(180_000L, request.maxUpdateDelayMillis)
    }

    @Test
    fun `moving with precise mode disabled uses balanced`() {
        val request = factory.forState(MovementState.MOVING, preciseMode = false, fineLocationGranted = true)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, request.priority)
    }

    @Test
    fun `stationary uses balanced power accuracy with generous batching`() {
        val request = factory.forState(MovementState.STATIONARY, preciseMode = true, fineLocationGranted = true)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, request.priority)
        assertEquals(60_000L, request.interval)
        assertEquals(180_000L, request.maxUpdateDelayMillis)
    }
}
