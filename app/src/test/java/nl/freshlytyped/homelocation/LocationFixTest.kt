package nl.freshlytyped.homelocation

import android.location.Location
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocationFixTest {

    @Test
    fun `toFix maps the location fields`() {
        val location = Location("gps").apply {
            latitude = 52.5
            longitude = 4.8
            accuracy = 12f
            time = 1_760_000_000_000L
        }
        val fix = location.toFix()
        assertEquals(52.5, fix.latitude, 0.0)
        assertEquals(4.8, fix.longitude, 0.0)
        assertEquals(12f, fix.accuracyMeters, 0.001f)
        assertEquals(1_760_000_000_000L, fix.timeMs)
    }

    @Test
    fun `an empty location yields zeroed fields`() {
        val fix = Location("gps").toFix()
        assertEquals(0.0, fix.latitude, 0.0)
        assertEquals(0.0, fix.longitude, 0.0)
        assertEquals(0L, fix.timeMs)
    }
}
