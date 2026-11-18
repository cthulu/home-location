package nl.freshlytyped.homelocation

import android.content.Context
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Balanced requests often deliver only coarse network fixes. Those must still
 * detect clear travel, without moving the reliable baseline or being published.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CoarseFixMovementTest {

    private lateinit var settings: LocationSettings
    private lateinit var coordinator: LocationCoordinator

    private var clockValue: Long = 100_000_000L

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        settings = LocationSettings(context)
        settings.resetState()
        settings.publishKey = "pub"
        settings.subscribeKey = "sub"
        settings.trackingEnabled = true
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
            diagnostics = DiagnosticLogger(context),
            clock = { clockValue }
        )
        settings.lastKnownLat = 52.0
        settings.lastKnownLon = 5.0
        settings.lastKnownTimeMs = clockValue - 60_000L
    }

    private fun fixAt(latitude: Double, accuracy: Float) = Location("test").apply {
        this.latitude = latitude
        longitude = 5.0
        this.accuracy = accuracy
        time = clockValue
    }

    @Test
    fun `a coarse fix far from the baseline switches to moving`() {
        // ~1.1 km away with 300 m accuracy.
        coordinator.onLocationUpdates(listOf(fixAt(52.01, accuracy = 300f)))

        assertEquals(MovementState.MOVING, settings.movementState)
        // The coarse fix neither replaces the baseline nor gets published.
        assertEquals(52.0, settings.lastKnownLat, 1e-9)
        assertEquals(0L, settings.lastPublishedTimeMs)
    }

    @Test
    fun `coarse jitter near the baseline stays stationary`() {
        coordinator.onLocationUpdates(listOf(fixAt(52.0015, accuracy = 129f)))

        assertEquals(MovementState.STATIONARY, settings.movementState)
    }
}
