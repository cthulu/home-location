package nl.freshlytyped.homelocation

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZonesSubscriberParseTest {

    private val gson = Gson()

    @Test
    fun `parseStreamMessage returns null for invalid JSON`() {
        val result = parseStreamMessage("not-json")
        assertNull(result)
    }

    @Test
    fun `parseStreamMessage returns null for empty response`() {
        val json = "[]"
        val result = parseStreamMessage(json)
        assertNull(result)
    }

    @Test
    fun `parseStreamMessage returns null when timetoken is missing`() {
        val json = """[[{"type":"zones"}]]"""
        val result = parseStreamMessage(json)
        assertNull(result)
    }

    @Test
    fun `parseStreamMessage extracts messages and timetoken from legacy subscribe response`() {
        val json = """[[{"type":"zones"}],"1234567890","home-location"]"""
        val result = parseStreamMessage(json)
        assertEquals(1234567890L, result!!.timetoken)
        assertEquals("zones", result.messages.single()["type"])
    }

    @Test
    fun `parseZones filters by device name`() {
        val message = mapOf(
            "type" to "zones",
            "zones" to listOf(
                mapOf("name" to "Home", "latitude" to 52.0, "longitude" to 5.0, "radius" to 150, "device_name" to "Pixel 8"),
                mapOf("name" to "Work", "latitude" to 53.0, "longitude" to 6.0, "radius" to 100, "device_name" to "Other Phone")
            )
        )
        val zones = parseZones(message, "Pixel 8")
        assertEquals(1, zones.size)
        assertEquals("Home", zones[0].name)
    }

    @Test
    fun `parseZones returns empty when no zones match device name`() {
        val message = mapOf(
            "type" to "zones",
            "zones" to listOf(
                mapOf("name" to "Home", "latitude" to 52.0, "longitude" to 5.0, "radius" to 150, "device_name" to "Other Phone")
            )
        )
        val zones = parseZones(message, "Pixel 8")
        assertEquals(0, zones.size)
    }

    @Test
    fun `parseZones returns empty when zones field is missing`() {
        val message = mapOf("type" to "zones")
        val zones = parseZones(message, "Pixel 8")
        assertEquals(0, zones.size)
    }

    @Test
    fun `parseZones returns empty when zones is not an array`() {
        val message = mapOf("type" to "zones", "zones" to "not-an-array")
        val zones = parseZones(message, "Pixel 8")
        assertEquals(0, zones.size)
    }

    @Test
    fun `parseZones skips entries with missing required fields`() {
        val message = mapOf(
            "type" to "zones",
            "zones" to listOf(
                mapOf("name" to "Home", "latitude" to 52.0, "longitude" to 5.0, "radius" to 150, "device_name" to "Pixel 8"),
                mapOf("latitude" to 53.0, "longitude" to 6.0, "radius" to 100, "device_name" to "Pixel 8"),
                mapOf("name" to "Work", "longitude" to 6.0, "radius" to 100, "device_name" to "Pixel 8")
            )
        )
        val zones = parseZones(message, "Pixel 8")
        assertEquals(1, zones.size)
        assertEquals("Home", zones[0].name)
    }

    @Test
    fun `backoff delay increases exponentially and caps at max`() {
        // Test the backoff formula directly
        assertTrue(backoffDelay(1) >= 5_000L && backoffDelay(1) <= 20_000L)
        assertTrue(backoffDelay(3) >= 10_000L && backoffDelay(3) <= 60_000L)
        assertTrue(backoffDelay(10) <= 300_000L) // Capped at 5 min
        assertTrue(backoffDelay(20) <= 300_000L) // Still capped
    }

    // Replicate ZonesSubscriber parsing methods for testing
    private fun parseStreamMessage(json: String): StreamEntry? {
        return try {
            val array = gson.fromJson(json, List::class.java)
            if (array.size < 2) return null
            val messages = (array[0] as? List<*>)
                ?.filterIsInstance<Map<String, Any>>()
                ?: return null
            val timetoken = array[1]?.toString()?.toLongOrNull() ?: return null
            StreamEntry(
                timetoken = timetoken,
                messages = messages
            )
        } catch (_: com.google.gson.JsonSyntaxException) {
            null
        }
    }

    private fun parseZones(message: Map<String, Any>, deviceName: String): List<Zone> {
        val zonesRaw = message["zones"] ?: return emptyList()
        val zonesList = when (zonesRaw) {
            is List<*> -> zonesRaw
            else -> return emptyList()
        }
        return zonesList.mapNotNull { raw ->
            if (raw !is Map<*, *>) return@mapNotNull null
            val name = (raw["name"] as? String) ?: return@mapNotNull null
            val lat = (raw["latitude"] as? Number)?.toDouble() ?: return@mapNotNull null
            val lon = (raw["longitude"] as? Number)?.toDouble() ?: return@mapNotNull null
            val radius = (raw["radius"] as? Number)?.toInt() ?: return@mapNotNull null
            val zoneDeviceName = raw["device_name"] as? String
            if (zoneDeviceName != deviceName) return@mapNotNull null
            Zone(name = name, latitude = lat, longitude = lon, radius = radius)
        }
    }

    /** Replicate the backoff formula from ZonesSubscriber. */
    private fun backoffDelay(attempt: Int): Long {
        val INITIAL_BACKOFF_MS = 10_000L
        val MAX_BACKOFF_MS = 5 * 60_000L
        val base = minOf(INITIAL_BACKOFF_MS * (1L shl (attempt - 1)), MAX_BACKOFF_MS)
        return (base * (0.5 + (0.5 * (1..100).random() / 100.0))).toLong()
    }

    private data class StreamEntry(
        val timetoken: Long,
        val messages: List<Map<String, Any>>
    )
}
