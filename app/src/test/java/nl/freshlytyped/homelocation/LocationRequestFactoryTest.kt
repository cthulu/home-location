package nl.freshlytyped.homelocation

import com.google.android.gms.location.Priority
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationRequestFactoryTest {

    private val factory = LocationRequestFactory()

    @Test
    fun `continuous request uses balanced power accuracy with modest batching`() {
        val request = factory.balanced()
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, request.priority)
        assertEquals(120_000L, request.interval)
        assertEquals(120_000L, request.maxUpdateDelayMillis)
    }

    @Test
    fun `interval is clamped to the supported range`() {
        val tooShort = factory.balanced(1_000L)
        val tooLong = factory.balanced(900_000L)

        assertEquals(30_000L, tooShort.interval)
        assertEquals(30_000L, tooShort.maxUpdateDelayMillis)
        assertEquals(600_000L, tooLong.interval)
        assertEquals(600_000L, tooLong.maxUpdateDelayMillis)
    }
}
