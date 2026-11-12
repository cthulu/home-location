package nl.freshlytyped.homelocation

import android.content.Context
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the background-capable zone-entry confirmation: the geofence event's
 * triggering location is used to confirm the entry immediately, with a
 * zone-aware accuracy cap so a coarse-but-plausible fix can confirm a large
 * zone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ZoneEntryConfirmationTest {

    private lateinit var context: Context
    private lateinit var settings: LocationSettings
    private lateinit var coordinator: LocationCoordinator

    private var clockValue: Long = 1_000_000L

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        settings = LocationSettings(context)
        settings.publishKey = "pub"
        settings.subscribeKey = "sub"
        settings.trackingEnabled = true
        settings.currentZoneId = null
        val permissions = mock<LocationPermissionManager>()
        whenever(permissions.trackingAllowed()).thenReturn(true)
        whenever(permissions.fineGranted()).thenReturn(true)
        val publisher = mock<LocationPublisher>()
        runBlocking { whenever(publisher.publish(any())).thenReturn(Result.success(Unit)) }
        coordinator = LocationCoordinator(
            context = context,
            settings = settings,
            permissions = permissions,
            publisher = publisher,
            diagnostics = DiagnosticLogger(context),
            clock = { clockValue },
            zoneEntryHysteresisMs = 60_000L
        )
    }

    private fun persistZone(name: String, latitude: Double, longitude: Double, radius: Int) {
        settings.persistedZones = JSONArray().apply {
            put(JSONObject().apply {
                put("name", name)
                put("latitude", latitude)
                put("longitude", longitude)
                put("radius", radius)
            })
        }.toString()
    }

    private fun fixAt(latitude: Double, longitude: Double, accuracy: Float, ageMs: Long = 0L) =
        Location("test").apply {
            this.latitude = latitude
            this.longitude = longitude
            this.accuracy = accuracy
            this.time = clockValue - ageMs
        }

    @Test
    fun `a good triggering fix confirms the entry immediately`() {
        persistZone("cgu", 52.0, 5.0, radius = 150)
        coordinator.onZoneEntered("cgu", fixAt(52.0, 5.0, accuracy = 10f))

        assertEquals("cgu", settings.currentZoneId)
        assertEquals(clockValue, settings.lastZoneTransitionMs)
    }

    @Test
    fun `a coarse triggering fix confirms a large zone via the zone-aware cap`() {
        persistZone("cgu", 52.0, 5.0, radius = 150)
        // 100 m is above the 50 m publish cap but within the zone radius, so the
        // zone-aware cap (max(50, 150) = 150 m) allows confirmation.
        coordinator.onZoneEntered("cgu", fixAt(52.0, 5.0, accuracy = 100f))

        assertEquals("cgu", settings.currentZoneId)
    }

    @Test
    fun `a coarse triggering fix does not confirm a small zone`() {
        persistZone("cgu", 52.0, 5.0, radius = 40)
        // 100 m exceeds the cap (max(50, 40) = 50 m) for a small zone.
        coordinator.onZoneEntered("cgu", fixAt(52.0, 5.0, accuracy = 100f))

        assertNull(settings.currentZoneId)
    }

    @Test
    fun `an outside triggering fix does not confirm the entry`() {
        persistZone("cgu", 52.0, 5.0, radius = 150)
        // ~1 km north of the center, well outside the 150 m radius.
        coordinator.onZoneEntered("cgu", fixAt(52.009, 5.0, accuracy = 10f))

        assertNull(settings.currentZoneId)
    }

    @Test
    fun `a stale triggering fix does not confirm the entry`() {
        persistZone("cgu", 52.0, 5.0, radius = 150)
        settings.maxLocationAgeMs = 10 * 60_000L
        coordinator.onZoneEntered("cgu", fixAt(52.0, 5.0, accuracy = 10f, ageMs = 11 * 60_000L))

        assertNull(settings.currentZoneId)
    }
}
