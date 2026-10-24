package nl.freshlytyped.homelocation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class ZonePersistenceTest {

    @Test
    fun `persistZones and parseZones are inverse operations`() {
        val zones = listOf(
            Zone(name = "Home", latitude = 52.123456, longitude = 5.456789, radius = 150),
            Zone(name = "Work", latitude = 52.789012, longitude = 5.123456, radius = 100)
        )
        val json = persistZones(zones)
        val parsed = parseZones(json)
        assertEquals(zones.size, parsed.size)
        assertEquals(zones[0].name, parsed[0].name)
        assertEquals(zones[0].latitude, parsed[0].latitude, 0.000001)
        assertEquals(zones[0].longitude, parsed[0].longitude, 0.000001)
        assertEquals(zones[0].radius, parsed[0].radius)
    }

    @Test
    fun `parseZones returns empty list on invalid JSON`() {
        val parsed = parseZones("not-json")
        assertEquals(emptyList<Zone>(), parsed)
    }

    @Test
    fun `parseZones returns empty list on empty array`() {
        val parsed = parseZones("[]")
        assertEquals(emptyList<Zone>(), parsed)
    }

    @Test
    fun `parseZones skips entries with missing fields`() {
        val json = """[{"name":"Home","latitude":52.0,"longitude":5.0,"radius":150},{"latitude":53.0}]"""
        val parsed = parseZones(json)
        assertEquals(1, parsed.size)
        assertEquals("Home", parsed[0].name)
    }

    // Replicate ZoneManager persistence/parsing logic for testing
    private fun persistZones(zones: List<Zone>): String {
        val arr = JSONArray()
        for (zone in zones) {
            arr.put(JSONObject().apply {
                put("name", zone.name)
                put("latitude", zone.latitude)
                put("longitude", zone.longitude)
                put("radius", zone.radius)
            })
        }
        return arr.toString()
    }

    private fun parseZones(json: String): List<Zone> {
        return try {
            val arr = JSONArray(json)
            val zones = mutableListOf<Zone>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name", null) ?: continue
                val lat = if (obj.has("latitude")) obj.getDouble("latitude") else continue
                val lon = if (obj.has("longitude")) obj.getDouble("longitude") else continue
                val radius = if (obj.has("radius")) obj.getInt("radius") else continue
                zones.add(Zone(name = name, latitude = lat, longitude = lon, radius = radius))
            }
            zones
        } catch (_: org.json.JSONException) {
            emptyList()
        }
    }
}
