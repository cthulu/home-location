package nl.freshlytyped.homelocation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPayloadTest {

    @Test
    fun `builds the documented payload`() {
        val json = JSONObject(
            LocationPayload.build(
                LocationFix(52.123456789, 5.123456789, 18.456f, 1_760_000_000_000L),
                "Pixel 8"
            )
        )
        assertEquals("location", json.getString("type"))
        assertEquals("Pixel 8", json.getString("device_name"))
        assertEquals(52.123457, json.getDouble("latitude"), 0.0)
        assertEquals(5.123457, json.getDouble("longitude"), 0.0)
        assertEquals(18.5, json.getDouble("accuracy"), 0.0)
        assertEquals(1_760_000_000_000L, json.getLong("timestamp"))
    }

    @Test
    fun `unknown accuracy is serialized as null`() {
        val json = JSONObject(
            LocationPayload.build(LocationFix(52.0, 5.0, Float.NaN, 1L), "Phone")
        )
        assertTrue(json.isNull("accuracy"))
    }

    @Test
    fun `coordinates are rounded to six decimals`() {
        val json = JSONObject(
            LocationPayload.build(LocationFix(52.0000004, 5.0000004, 1f, 1L), "Phone")
        )
        assertEquals(52.0, json.getDouble("latitude"), 0.0)
        assertEquals(5.0, json.getDouble("longitude"), 0.0)
    }

    @Test
    fun `type discriminator matches the consumer contract`() {
        assertEquals("location", LocationPayload.TYPE)
    }
}
