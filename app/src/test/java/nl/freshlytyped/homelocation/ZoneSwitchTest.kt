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
 * Covers zone switching: while a (possibly stale) zone is current the
 * coordinator is paused and never receives fixes, so a geofence ENTER for a
 * *different* zone can confirm a switch from the triggering location. That is
 * the only way a stale current zone can resolve itself; without it tracking
 * stays paused until the geofence engine happens to fire an EXIT for the
 * stale zone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ZoneSwitchTest {

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

    private fun persistZones(vararg zones: Zone) {
        settings.persistedZones = JSONArray().apply {
            for (zone in zones) {
                put(JSONObject().apply {
                    put("name", zone.name)
                    put("latitude", zone.latitude)
                    put("longitude", zone.longitude)
                    put("radius", zone.radius)
                })
            }
        }.toString()
    }

    private fun zone(
        name: String,
        latitude: Double = 52.0,
        longitude: Double = 5.0,
        radius: Int = 150
    ) = Zone(name = name, latitude = latitude, longitude = longitude, radius = radius)

    private fun fixAt(latitude: Double, longitude: Double, accuracy: Float, ageMs: Long = 0L) =
        Location("test").apply {
            this.latitude = latitude
            this.longitude = longitude
            this.accuracy = accuracy
            this.time = clockValue - ageMs
        }

    /** home is ~1.2 km west of cgu, well outside both 150 m radii. */
    private val cguLongitude = 5.018

    /** Puts the coordinator in a stale "home" state: current, not debounced. */
    private fun staleHome() {
        persistZones(zone("home"), zone("cgu", longitude = cguLongitude))
        settings.currentZoneId = "home"
        // Long enough ago that the 3-minute enter debounce does not block.
        settings.lastZoneTransitionMs = clockValue - 10 * 60_000L
    }

    @Test
    fun `a good triggering fix switches a stale current zone`() {
        staleHome()

        coordinator.onZoneEntered("cgu", fixAt(52.0, cguLongitude, accuracy = 10f))

        assertEquals("cgu", settings.currentZoneId)
        assertEquals(clockValue, settings.lastZoneTransitionMs)
        assertNull(coordinator.testPendingZoneEntryId)
        // The triggering fix doubles as the departure point for the stale zone.
        assertEquals(52.0, settings.lastPublishedLat, 1e-9)
        assertEquals(cguLongitude, settings.lastPublishedLon, 1e-6)
    }

    @Test
    fun `a coarse triggering fix does not switch the current zone`() {
        // cgu radius 40 m: the zone-aware cap is max(50, 40) = 50 m, so a
        // 100 m trigger cannot confirm a switch.
        persistZones(zone("home"), zone("cgu", longitude = cguLongitude, radius = 40))
        settings.currentZoneId = "home"
        settings.lastZoneTransitionMs = clockValue - 10 * 60_000L

        coordinator.onZoneEntered("cgu", fixAt(52.0, cguLongitude, accuracy = 100f))

        assertEquals("home", settings.currentZoneId)
        // The entry is still armed so a later usable fix can confirm the switch.
        assertEquals("cgu", coordinator.testPendingZoneEntryId)
    }

    @Test
    fun `a triggering fix still inside the current zone does not switch`() {
        // 0.0035 deg lon east of home center is ~240 m: inside both 300 m
        // radii, so the fix cannot prove the device left home.
        persistZones(zone("home", radius = 300), zone("cgu", longitude = 5.0035, radius = 300))
        settings.currentZoneId = "home"
        settings.lastZoneTransitionMs = clockValue - 10 * 60_000L

        coordinator.onZoneEntered("cgu", fixAt(52.0, 5.0035, accuracy = 10f))

        assertEquals("home", settings.currentZoneId)
        assertEquals("cgu", coordinator.testPendingZoneEntryId)
    }

    @Test
    fun `a stale triggering fix does not switch the current zone`() {
        staleHome()
        settings.maxLocationAgeMs = 10 * 60_000L

        coordinator.onZoneEntered("cgu", fixAt(52.0, cguLongitude, accuracy = 10f, ageMs = 11 * 60_000L))

        assertEquals("home", settings.currentZoneId)
        assertEquals("cgu", coordinator.testPendingZoneEntryId)
    }

    @Test
    fun `a later usable fix confirms a switch for a pending entry`() {
        // cgu radius 40 m so the 100 m trigger cannot confirm the switch; the
        // entry arms, and a later good fix (still while "home" is current)
        // confirms the switch through the pending-entry path.
        persistZones(zone("home"), zone("cgu", longitude = cguLongitude, radius = 40))
        settings.currentZoneId = "home"
        settings.lastZoneTransitionMs = clockValue - 10 * 60_000L

        coordinator.onZoneEntered("cgu", fixAt(52.0, cguLongitude, accuracy = 100f))
        assertEquals("home", settings.currentZoneId)

        // Within the 10-minute confirmation window a usable fix arrives.
        clockValue += 60_000L
        coordinator.onLocationUpdates(listOf(fixAt(52.0, cguLongitude, accuracy = 10f)))

        assertEquals("cgu", settings.currentZoneId)
        assertNull(coordinator.testPendingZoneEntryId)
    }

    @Test
    fun `an enter for the current zone is ignored`() {
        staleHome()

        coordinator.onZoneEntered("home", fixAt(52.0, 5.0, accuracy = 10f))

        assertEquals("home", settings.currentZoneId)
        assertEquals(clockValue - 10 * 60_000L, settings.lastZoneTransitionMs)
    }

    @Test
    fun `a switch is debounced like any other enter`() {
        persistZones(zone("home"), zone("cgu", longitude = cguLongitude))
        settings.currentZoneId = "home"
        // 1 minute ago: inside the 3-minute debounce window.
        settings.lastZoneTransitionMs = clockValue - 60_000L

        coordinator.onZoneEntered("cgu", fixAt(52.0, cguLongitude, accuracy = 10f))

        assertEquals("home", settings.currentZoneId)
        assertEquals(clockValue - 60_000L, settings.lastZoneTransitionMs)
    }

    @Test
    fun `switching resolves the stale pause and the next exit resumes`() {
        staleHome()

        coordinator.onZoneEntered("cgu", fixAt(52.0, cguLongitude, accuracy = 10f))
        assertEquals("cgu", settings.currentZoneId)

        clockValue += 60_000L
        coordinator.onZoneExited("cgu", fixAt(52.0, cguLongitude, accuracy = 10f))

        assertNull(settings.currentZoneId)
    }
}
