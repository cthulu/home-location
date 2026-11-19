package nl.freshlytyped.homelocation

import android.app.AlarmManager
import android.content.Context
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * The high-accuracy cap and its cooldown must not hide real travel: activity
 * evidence is silenced during the cooldown, but location fixes that cover the
 * movement distance resume moving mode and are published.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MovementCooldownTest {

    private lateinit var context: Context
    private lateinit var settings: LocationSettings
    private lateinit var coordinator: LocationCoordinator

    private var clockValue: Long = 100_000_000L

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
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

    private fun fixAt(longitude: Double, accuracy: Float = 10f) = Location("test").apply {
        latitude = 52.0
        this.longitude = longitude
        this.accuracy = accuracy
        time = clockValue
    }

    /** ~205 m east of the last known position at latitude 52. */
    private val travelledLongitude = 5.003

    @Test
    fun `a travelling fix during the cooldown resumes moving`() {
        settings.movementState = MovementState.STATIONARY
        settings.activityCooldownUntilMs = clockValue + 10 * 60_000L

        coordinator.onLocationUpdates(listOf(fixAt(travelledLongitude)))

        assertEquals(MovementState.MOVING, settings.movementState)
        assertEquals(travelledLongitude, settings.lastPublishedLon, 1e-6)
    }

    @Test
    fun `moving activity during the cooldown is still ignored`() {
        settings.movementState = MovementState.STATIONARY
        settings.activityCooldownUntilMs = clockValue + 10 * 60_000L

        coordinator.onActivityUpdate(MovementStateController.ACTIVITY_TYPE_IN_VEHICLE, 100, clockValue)

        assertEquals(MovementState.STATIONARY, settings.movementState)
    }

    @Test
    fun `the batch that hits the cap is still processed`() {
        settings.movementState = MovementState.MOVING
        settings.movingStartedTimeMs = clockValue - 16 * 60_000L

        coordinator.onLocationUpdates(listOf(fixAt(travelledLongitude)))

        // The cap reset the session, then the travelling fix resumed it.
        assertEquals(MovementState.MOVING, settings.movementState)
        assertEquals(clockValue, settings.movingStartedTimeMs)
        assertTrue(settings.activityCooldownUntilMs == 0L)
        assertEquals(travelledLongitude, settings.lastPublishedLon, 1e-6)
    }

    @Test
    fun `a stationary fix after the cap stays stationary`() {
        settings.movementState = MovementState.MOVING
        settings.movingStartedTimeMs = clockValue - 16 * 60_000L

        coordinator.onLocationUpdates(listOf(fixAt(5.00001)))

        assertEquals(MovementState.STATIONARY, settings.movementState)
        assertTrue(settings.activityCooldownUntilMs > clockValue)
    }

    private fun burstAlarmScheduled(): Boolean =
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
            .any { shadowOf(it.operation).savedIntent.action == LocationReceiver.ACTION_BURST_END }

    @Test
    fun `a leftover burst alarm while stationary is not rescheduled`() {
        settings.movementState = MovementState.STATIONARY

        coordinator.onBurstEnd()

        assertFalse(burstAlarmScheduled())
    }

    @Test
    fun `resetting a stale moving state on start cancels the burst alarm`() {
        settings.movementState = MovementState.MOVING
        settings.movingStartedTimeMs = clockValue - 5 * 60_000L
        coordinator.ensureRegistered()
        assertTrue(burstAlarmScheduled())

        clockValue += 20 * 60_000L
        coordinator.start()

        assertEquals(MovementState.STATIONARY, settings.movementState)
        assertFalse(burstAlarmScheduled())
    }
}
