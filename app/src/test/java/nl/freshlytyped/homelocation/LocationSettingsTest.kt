package nl.freshlytyped.homelocation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocationSettingsTest {

    private lateinit var settings: LocationSettings

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        settings = LocationSettings(context)
    }

    @Test
    fun `defaults match the documented tunables`() {
        assertEquals("home-location", settings.channel)
        assertTrue(settings.deviceName.isNotBlank())
        assertEquals(50f, settings.maxAccuracyMeters)
        assertEquals(60_000L, settings.minPublishIntervalMs)
        assertEquals(50f, settings.minDistanceMeters)
        assertEquals(100f, settings.movementDetectionDistanceMeters)
        assertEquals(10 * 60_000L, settings.maxLocationAgeMs)
        assertEquals(10 * 60_000L, settings.burstDurationMs)
        assertFalse(settings.periodicFallbackEnabled)
        assertTrue(settings.preciseMode)
        assertEquals(5 * 60_000L, settings.gpsFixIntervalMs)
        assertFalse(settings.trackingEnabled)
        assertEquals(MovementState.STATIONARY, settings.movementState)
    }

    @Test
    fun `pubnub credentials round trip and drive isConfigured`() {
        assertFalse(settings.isConfigured())
        settings.publishKey = "pub-c"
        assertFalse(settings.isConfigured())
        settings.subscribeKey = "sub-c"
        assertTrue(settings.isConfigured())
    }

    @Test
    fun `credentials are trimmed on save`() {
        settings.publishKey = "  pub-c  "
        assertEquals("pub-c", settings.publishKey)
    }

    @Test
    fun `device name is persisted and trimmed`() {
        settings.deviceName = "  Pixel 8  "
        assertEquals("Pixel 8", settings.deviceName)
    }

    @Test
    fun `movement state round trips`() {
        assertEquals(MovementState.STATIONARY, settings.movementState)
        settings.movementState = MovementState.MOVING
        assertEquals(MovementState.MOVING, settings.movementState)
    }

    @Test
    fun `coordinate state survives at sub-meter precision`() {
        assertFalse(settings.hasLastKnown())
        settings.lastKnownLat = 52.3703
        settings.lastKnownLon = 5.12345
        settings.lastKnownTimeMs = 123L
        assertTrue(settings.hasLastKnown())
        assertEquals(52.3703, settings.lastKnownLat, 1e-6)
        assertEquals(5.12345, settings.lastKnownLon, 1e-6)
        assertEquals(123L, settings.lastKnownTimeMs)
    }

    @Test
    fun `resetState clears all runtime state`() {
        settings.movementState = MovementState.MOVING
        settings.lastKnownLat = 52.0
        settings.lastKnownTimeMs = 42L
        settings.lastPublishedTimeMs = 41L
        settings.lastPublishedLat = 52.0
        settings.lastPublishedAccuracyMeters = 12f
        settings.accumulatedDistanceMeters = 120f
        settings.lastMovementEvidenceTimeMs = 40L

        settings.resetState()

        assertEquals(MovementState.STATIONARY, settings.movementState)
        assertFalse(settings.hasLastKnown())
        assertFalse(settings.hasLastPublished())
        assertEquals(0f, settings.accumulatedDistanceMeters)
        assertEquals(0L, settings.lastMovementEvidenceTimeMs)
        assertEquals(0f, settings.lastPublishedAccuracyMeters)
    }

    @Test
    fun `pending publish keeps its accuracy cap`() {
        val fix = LocationFix(52.0, 5.0, 120f, 1_000L)

        settings.pendingPublish = PendingPublish(fix, 150f)

        assertEquals(PendingPublish(fix, 150f), settings.pendingPublish)
    }

    @Test
    fun `a pending fix saved without a cap falls back to the publish cap`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("location_settings", Context.MODE_PRIVATE).edit()
            .putString("pending_location", """{"latitude":52.0,"longitude":5.0,"accuracy":12.0,"time":1000}""")
            .commit()

        assertEquals(settings.maxAccuracyMeters, settings.pendingPublish?.accuracyCapMeters)
    }
}
