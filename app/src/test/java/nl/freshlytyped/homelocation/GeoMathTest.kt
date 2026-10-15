package nl.freshlytyped.homelocation

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoMathTest {

    @Test
    fun `same point has zero distance`() {
        assertEquals(0.0, GeoMath.distanceMeters(52.0, 5.0, 52.0, 5.0), 0.001)
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        val meters = GeoMath.distanceMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, meters, 100.0)
    }

    @Test
    fun `amsterdam to utrecht is about 35 km`() {
        val meters = GeoMath.distanceMeters(52.3703, 4.8952, 52.0907, 5.1214)
        assertEquals(35_000.0, meters, 1_500.0)
    }

    @Test
    fun `distance is symmetric`() {
        val a = GeoMath.distanceMeters(52.0, 5.0, 52.1, 5.2)
        val b = GeoMath.distanceMeters(52.1, 5.2, 52.0, 5.0)
        assertEquals(a, b, 0.001)
    }
}
