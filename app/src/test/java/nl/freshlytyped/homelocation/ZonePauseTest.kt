package nl.freshlytyped.homelocation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(RobolectricTestRunner::class)
class ZonePauseTest {

    private lateinit var context: Context
    private lateinit var settings: LocationSettings
    private lateinit var permissions: LocationPermissionManager
    private lateinit var publisher: LocationPublisher
    private lateinit var coordinator: LocationCoordinator

    private var clockValue: Long = 0L

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        settings = LocationSettings(context)
        settings.publishKey = "pub"
        settings.subscribeKey = "sub"
        permissions = mock()
        whenever(permissions.trackingAllowed()).thenReturn(true)
        whenever(permissions.fineGranted()).thenReturn(true)
        publisher = mock()
        clockValue = 1_000L // Start at 1s so debounce check works (lastTransition > 0)

        coordinator = LocationCoordinator(
            context = context,
            settings = settings,
            permissions = permissions,
            publisher = publisher,
            clock = { clockValue }
        )
    }

    @Test
    fun `zone enter sets current zone and updates transition timestamp`() {
        settings.trackingEnabled = true
        clockValue = 1_000L
        coordinator.onZoneEntered("home")

        assertEquals("home", settings.currentZoneId)
        assertEquals(1_000L, settings.lastZoneTransitionMs)
    }

    @Test
    fun `zone exit clears zone and updates transition timestamp`() {
        settings.trackingEnabled = true
        clockValue = 1_000L
        coordinator.onZoneEntered("home")

        clockValue = 2_000L
        coordinator.onZoneExited("home")

        assertNull(settings.currentZoneId)
        assertEquals(2_000L, settings.lastZoneTransitionMs)
    }

    @Test
    fun `zone enter respects debounce - ignored when within window`() {
        settings.trackingEnabled = true
        settings.geofenceDebounceMs = 180_000L // 3 min
        clockValue = 1_000L
        coordinator.onZoneEntered("home")
        assertEquals("home", settings.currentZoneId)
        assertEquals(1_000L, settings.lastZoneTransitionMs)

        // Simulate a quick re-entry within debounce window
        clockValue = 120_000L // ~2 min later
        coordinator.onZoneEntered("work")
        assertEquals("home", settings.currentZoneId) // Still home, not switched
        assertEquals(1_000L, settings.lastZoneTransitionMs) // Not updated
    }

    @Test
    fun `zone enter respects debounce - accepted after window`() {
        settings.trackingEnabled = true
        settings.geofenceDebounceMs = 180_000L // 3 min
        clockValue = 1_000L
        coordinator.onZoneEntered("home")
        assertEquals("home", settings.currentZoneId)

        // Simulate entry after debounce window
        clockValue = 182_000L // 3 min + 1s + 1s later
        coordinator.onZoneEntered("work")
        assertEquals("work", settings.currentZoneId)
        assertEquals(182_000L, settings.lastZoneTransitionMs)
    }

    @Test
    fun `zone enter sets zone regardless of tracking state`() {
        settings.trackingEnabled = false
        clockValue = 1_000L
        coordinator.onZoneEntered("home")

        assertEquals("home", settings.currentZoneId)
        assertEquals(1_000L, settings.lastZoneTransitionMs)
    }

    @Test
    fun `zone exit clears zone regardless of tracking state`() {
        settings.trackingEnabled = false
        settings.currentZoneId = "home"
        clockValue = 1_000L
        coordinator.onZoneExited("home")

        assertNull(settings.currentZoneId)
        assertEquals(1_000L, settings.lastZoneTransitionMs)
    }

    @Test
    fun `exit updates debounce window so quick re-entry is debounced`() {
        settings.trackingEnabled = true
        settings.geofenceDebounceMs = 180_000L
        clockValue = 1_000L
        coordinator.onZoneEntered("home")
        assertEquals("home", settings.currentZoneId)

        clockValue = 200_000L
        coordinator.onZoneExited("home")
        assertNull(settings.currentZoneId)
        assertEquals(200_000L, settings.lastZoneTransitionMs)

        // Quick re-entry should be debounced
        clockValue = 250_000L // Only 50s after exit
        coordinator.onZoneEntered("home")
        assertNull(settings.currentZoneId) // Still null (debounced)
        assertEquals(200_000L, settings.lastZoneTransitionMs) // Not updated
    }

    @Test
    fun `activity update does not change state when zone paused`() {
        settings.trackingEnabled = true
        settings.movementState = MovementState.STATIONARY
        clockValue = 1_000L
        coordinator.onZoneEntered("home")

        // Simulate a moving activity with high confidence
        clockValue = 2_000L
        coordinator.onActivityUpdate(2, 90, clockValue) // IN_VEHICLE with high confidence

        // State should remain STATIONARY because we're paused
        assertEquals(MovementState.STATIONARY, settings.movementState)
    }

    @Test
    fun `burst end does not revert state when zone paused`() {
        settings.trackingEnabled = true
        settings.movementState = MovementState.MOVING
        settings.lastMovementEvidenceTimeMs = 0L
        clockValue = 1_000L
        coordinator.onZoneEntered("home")

        // Simulate burst end - it should be skipped
        clockValue = 2_000L
        coordinator.onBurstEnd()

        // State should remain MOVING (not reverted to STATIONARY)
        assertEquals(MovementState.MOVING, settings.movementState)
    }
}
