package nl.freshlytyped.homelocation

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.tasks.Tasks
import java.io.File
import java.time.Duration
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
import org.robolectric.Shadows.shadowOf
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
    private lateinit var diagnostics: DiagnosticLogger

    private var clockValue: Long = 1_000_000L

    /** What the one-shot location request (hysteresis fallback) answers with. */
    private var oneShotLocation: Location? = null

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        File(context.filesDir, "diagnostics.log").delete()
        diagnostics = DiagnosticLogger(context)
        settings = LocationSettings(context)
        settings.publishKey = "pub"
        settings.subscribeKey = "sub"
        settings.trackingEnabled = true
        settings.currentZoneId = null
        val permissions = mock<LocationPermissionManager>()
        whenever(permissions.trackingAllowed()).thenReturn(true)
        whenever(permissions.fineGranted()).thenReturn(true)
        val publisher = mock<LocationPublisher>()
        runBlocking { whenever(publisher.publish(any(), any())).thenReturn(Result.success(Unit)) }
        coordinator = LocationCoordinator(
            context = context,
            settings = settings,
            permissions = permissions,
            publisher = publisher,
            diagnostics = diagnostics,
            clock = { clockValue },
            zoneEntryHysteresisMs = 60_000L,
            currentLocation = { Tasks.forResult(oneShotLocation) }
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
        pauseIn("home")
    }

    /** Makes [zoneId] current and pauses tracking, as on a real device. */
    private fun pauseIn(zoneId: String) {
        settings.currentZoneId = zoneId
        // Long enough ago that the 3-minute enter debounce does not block.
        settings.lastZoneTransitionMs = clockValue - 10 * 60_000L
        coordinator.ensureRegistered()
    }

    private fun countInLog(event: String): Int =
        diagnostics.snapshot().lines().count { it.contains(event) }

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
        pauseIn("home")

        coordinator.onZoneEntered("cgu", fixAt(52.0, cguLongitude, accuracy = 100f))

        assertEquals("home", settings.currentZoneId)
        // The entry is still armed so a later usable fix can confirm the switch.
        assertEquals("cgu", coordinator.testPendingZoneEntryId)
        // The triggering fix is evaluated once, not again as a plain entry.
        assertEquals(1, countInLog("zone_switch_rejected"))
    }

    @Test
    fun `a switch publishes under the entered zone cap`() {
        // A 200 m fix is within the 300 m cgu cap but far above the 50 m cap of
        // the small home zone it leaves; it must still be published.
        persistZones(zone("home", radius = 50), zone("cgu", longitude = cguLongitude, radius = 300))
        pauseIn("home")

        coordinator.onZoneEntered("cgu", fixAt(52.0, cguLongitude, accuracy = 200f))

        assertEquals("cgu", settings.currentZoneId)
        assertEquals(cguLongitude, settings.lastPublishedLon, 1e-6)
    }

    @Test
    fun `a triggering fix still inside the current zone does not switch`() {
        // 0.0035 deg lon east of home center is ~240 m: inside both 300 m
        // radii, so the fix cannot prove the device left home.
        persistZones(zone("home", radius = 300), zone("cgu", longitude = 5.0035, radius = 300))
        pauseIn("home")

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
    fun `the hysteresis one-shot confirms a switch for a pending entry`() {
        // cgu radius 40 m so the 100 m trigger cannot confirm the switch; the
        // entry arms, and the one-shot after the hysteresis delay confirms it.
        persistZones(zone("home"), zone("cgu", longitude = cguLongitude, radius = 40))
        pauseIn("home")

        coordinator.onZoneEntered("cgu", fixAt(52.0, cguLongitude, accuracy = 100f))
        assertEquals("home", settings.currentZoneId)

        clockValue += 60_000L
        oneShotLocation = fixAt(52.0, cguLongitude, accuracy = 10f)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))

        assertEquals("cgu", settings.currentZoneId)
        assertNull(coordinator.testPendingZoneEntryId)
    }

    @Test
    fun `location updates are not delivered while paused`() {
        persistZones(zone("home"), zone("cgu", longitude = cguLongitude, radius = 40))
        pauseIn("home")
        coordinator.onZoneEntered("cgu", fixAt(52.0, cguLongitude, accuracy = 100f))

        clockValue += 30_000L
        coordinator.onLocationUpdates(listOf(fixAt(52.0, cguLongitude, accuracy = 10f)))

        assertEquals("home", settings.currentZoneId)
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
