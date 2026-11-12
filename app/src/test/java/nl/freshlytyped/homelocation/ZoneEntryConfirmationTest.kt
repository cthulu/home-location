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

    private fun zone(name: String, latitude: Double = 52.0, longitude: Double = 5.0) =
        Zone(name = name, latitude = latitude, longitude = longitude, radius = 150)

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

    @Test
    fun `a later good fix confirms an entry whose triggering fix was rejected`() {
        persistZone("cgu", 52.0, 5.0, radius = 150)
        settings.maxLocationAgeMs = 10 * 60_000L
        // Within the cap (10 m) but stale (11 min): the entry arms, but the
        // triggering fix can't confirm it, so it is kept pending.
        coordinator.onZoneEntered("cgu", fixAt(52.0, 5.0, accuracy = 10f, ageMs = 11 * 60_000L))
        assertNull(settings.currentZoneId)

        // Within the bounded window, a usable update confirms the entry.
        clockValue += 60_000L
        coordinator.onLocationUpdates(listOf(fixAt(52.0, 5.0, accuracy = 10f)))

        assertEquals("cgu", settings.currentZoneId)
    }

    @Test
    fun `a never-confirmed entry expires after the bounded window`() {
        persistZone("cgu", 52.0, 5.0, radius = 150)
        settings.maxLocationAgeMs = 10 * 60_000L
        // Within the cap (10 m) but stale (11 min): the entry arms, but the
        // triggering fix can't confirm it, so it stays pending.
        coordinator.onZoneEntered("cgu", fixAt(52.0, 5.0, accuracy = 10f, ageMs = 11 * 60_000L))
        assertNull(settings.currentZoneId)

        // Past the 10-minute window, even a good fix no longer confirms.
        clockValue += 11 * 60_000L
        coordinator.onLocationUpdates(listOf(fixAt(52.0, 5.0, accuracy = 10f)))

        assertNull(settings.currentZoneId)
    }

    @Test
    fun `a coarse triggering fix beyond the zone cap still arms`() {
        persistZone("cgu", 52.0, 5.0, radius = 40)
        // 100 m exceeds the zone-aware cap (max(50, 40) = 50 m): the fix cannot
        // confirm on its own, but the entry is still armed (not discarded), so it
        // stays pending.
        coordinator.onZoneEntered("cgu", fixAt(52.0, 5.0, accuracy = 100f))
        assertNull(settings.currentZoneId)
        assertEquals("cgu", coordinator.testPendingZoneEntryId)

        // Within the window, a usable fix confirms the armed entry.
        clockValue += 60_000L
        coordinator.onLocationUpdates(listOf(fixAt(52.0, 5.0, accuracy = 10f)))
        assertEquals("cgu", settings.currentZoneId)
    }

    @Test
    fun `a coarse-triggered entry that never receives a usable fix expires`() {
        persistZone("cgu", 52.0, 5.0, radius = 40)
        // 100 m exceeds the zone cap: the entry arms but cannot be confirmed by
        // this fix.
        coordinator.onZoneEntered("cgu", fixAt(52.0, 5.0, accuracy = 100f))
        assertNull(settings.currentZoneId)
        assertEquals("cgu", coordinator.testPendingZoneEntryId)

        // Past the 10-minute window, the entry expires even though only coarse
        // (unusable) fixes arrive.
        clockValue += 11 * 60_000L
        coordinator.onLocationUpdates(listOf(fixAt(52.0, 5.0, accuracy = 100f)))

        assertNull(settings.currentZoneId)
        assertNull(coordinator.testPendingZoneEntryId)
    }

    @Test
    fun `a coarse triggering fix within a large zone cap still arms and confirms`() {
        persistZone("cgu", 52.0, 5.0, radius = 150)
        // 100 m is coarser than the 50 m publish cap but within the zone-aware cap
        // (max(50, 150) = 150 m): the entry arms and confirms, so a coarse indoor
        // fix still pauses tracking for a large zone.
        coordinator.onZoneEntered("cgu", fixAt(52.0, 5.0, accuracy = 100f))

        assertEquals("cgu", settings.currentZoneId)
    }

    @Test
    fun `a valid exit publishes the departure fix`() {
        persistZone("cgu", 52.0, 5.0, radius = 150)
        coordinator.onZoneEntered("cgu", fixAt(52.0, 5.0, accuracy = 10f))
        assertEquals("cgu", settings.currentZoneId)
        assertEquals(52.0, settings.lastPublishedLat, 1e-9)

        // ~222 m north of center (outside the 150 m radius), 100 m accuracy —
        // coarser than the 50 m publish cap but within the zone cap, so the
        // departure registers.
        clockValue += 60_000L
        coordinator.onZoneExited("cgu", fixAt(52.002, 5.0, accuracy = 100f))

        assertNull(settings.currentZoneId)
        assertEquals(52.002, settings.lastPublishedLat, 1e-9)
        assertEquals(5.0, settings.lastPublishedLon, 1e-9)
    }

    @Test
    fun `a valid exit with no triggering fix does not publish`() {
        persistZone("cgu", 52.0, 5.0, radius = 150)
        coordinator.onZoneEntered("cgu", fixAt(52.0, 5.0, accuracy = 10f))
        assertEquals(52.0, settings.lastPublishedLat, 1e-9)

        // No triggering location: the exit applies, but there is nothing to
        // publish as a departure point.
        clockValue += 60_000L
        coordinator.onZoneExited("cgu")

        assertNull(settings.currentZoneId)
        assertEquals(52.0, settings.lastPublishedLat, 1e-9)
    }

    @Test
    fun `a pending entry is dropped when its armed zone is removed from the list`() {
        // cgu is armed; office sits ~2 km east so a cgu fix can never confirm it.
        settings.maxLocationAgeMs = 10 * 60_000L
        coordinator.testZoneManager.updateZones(
            listOf(zone("cgu"), zone("office", latitude = 52.0, longitude = 5.018))
        )

        // Stale triggering fix at cgu's center: the entry arms but stays
        // pending (stale, not usable); cgu exists in the list.
        coordinator.onZoneEntered("cgu", fixAt(52.0, 5.0, accuracy = 10f, ageMs = 11 * 60_000L))
        assertNull(settings.currentZoneId)

        // Prove the pending entry is still alive: a fresh usable fix within the
        // window re-evaluates it and confirms "cgu".
        clockValue += 5 * 60_000L // still inside the 10-minute window
        coordinator.onLocationUpdates(listOf(fixAt(52.0, 5.0, accuracy = 10f)))
        assertEquals("cgu", settings.currentZoneId)
        assertEquals(clockValue, settings.lastZoneTransitionMs)

        // Re-arm a pending entry with another stale cgu fix.
        settings.currentZoneId = null
        coordinator.onZoneEntered("cgu", fixAt(52.0, 5.0, accuracy = 10f, ageMs = 11 * 60_000L))
        assertNull(settings.currentZoneId)
        // The pending entry is "cgu" again.
        assertEquals("cgu", coordinator.testPendingZoneEntryId)

        // Apply a new zone list that no longer contains "cgu" (the real
        // updateZones boundary, which must drop the pending entry).
        clockValue += 5 * 60_000L // still inside the 10-minute window
        coordinator.testZoneManager.updateZones(listOf(zone("office", latitude = 52.0, longitude = 5.018)))
        // The pending entry must be gone right after the list update.
        assertNull(coordinator.testPendingZoneEntryId)

        // A fresh usable fix at cgu's center: if the "cgu" entry had lingered it
        // would be re-evaluated and confirmed (currentZoneId -> "cgu"). It must
        // not — only "office" exists, and the fix is ~2 km from it.
        clockValue += 5 * 60_000L
        coordinator.onLocationUpdates(listOf(fixAt(52.0, 5.0, accuracy = 10f)))

        assertNull(settings.currentZoneId)
    }

    @Test
    fun `a too-coarse departure fix is not published`() {
        persistZone("cgu", 52.0, 5.0, radius = 40)
        coordinator.onZoneEntered("cgu", fixAt(52.0, 5.0, accuracy = 10f))
        assertEquals(52.0, settings.lastPublishedLat, 1e-9)

        // 100 m is coarser than the zone cap (max(50, 40) = 50 m): the departure
        // is ignored, so the last published point is unchanged.
        clockValue += 60_000L
        coordinator.onZoneExited("cgu", fixAt(52.002, 5.0, accuracy = 100f))

        assertNull(settings.currentZoneId)
        assertEquals(52.0, settings.lastPublishedLat, 1e-9)
    }
}
