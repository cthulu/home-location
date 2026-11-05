package nl.freshlytyped.homelocation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneTest {

    @Test
    fun `valid zone passes validation`() {
        assertTrue(Zone("Home", 52.0, 5.0, 150).isValid())
    }

    @Test
    fun `blank name is invalid`() {
        assertFalse(Zone(" ", 52.0, 5.0, 150).isValid())
    }

    @Test
    fun `latitude out of range is invalid`() {
        assertFalse(Zone("A", 90.1, 5.0, 150).isValid())
        assertFalse(Zone("B", -90.1, 5.0, 150).isValid())
        assertFalse(Zone("C", Double.NaN, 5.0, 150).isValid())
    }

    @Test
    fun `longitude out of range is invalid`() {
        assertFalse(Zone("A", 52.0, 180.1, 150).isValid())
        assertFalse(Zone("B", 52.0, -180.1, 150).isValid())
    }

    @Test
    fun `non-positive radius is invalid`() {
        assertFalse(Zone("A", 52.0, 5.0, 0).isValid())
        assertFalse(Zone("B", 52.0, 5.0, -10).isValid())
    }
}
