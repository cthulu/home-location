package nl.freshlytyped.homelocation

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
 * While moving, GPS is used only for one-shot fixes at the configured interval
 * and only when the user allows it; without GPS, coarse fixes are published.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MovingGpsTest {

    private lateinit var settings: LocationSettings
    private lateinit var permissions: LocationPermissionManager
    private lateinit var coordinator: LocationCoordinator

    private var clockValue: Long = 100_000_000L
    private val gpsRequests = mutableListOf<CurrentLocationRequest>()
    private var gpsLocation: Location? = null

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        settings = LocationSettings(context)
        settings.resetState()
        settings.publishKey = "pub"
        settings.subscribeKey = "sub"
        settings.trackingEnabled = true
        settings.preciseMode = true
        permissions = mock()
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
            clock = { clockValue },
            currentLocation = { request ->
                gpsRequests += request
                Tasks.forResult(gpsLocation)
            }
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

    private fun update(latitude: Double, accuracy: Float) {
        coordinator.onLocationUpdates(listOf(fixAt(latitude, accuracy)))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun advanceMinutes(minutes: Int) {
        clockValue += minutes * 60_000L
    }

    @Test
    fun `entering moving takes one high-accuracy GPS fix and publishes it`() {
        // The GPS fix arrives after the coarse fix that triggered it.
        gpsLocation = fixAt(52.0105, accuracy = 8f).apply { time = clockValue + 10_000L }

        // ~1.1 km away with 300 m accuracy: coarse evidence switches to moving.
        update(52.01, accuracy = 300f)

        assertEquals(MovementState.MOVING, settings.movementState)
        assertEquals(1, gpsRequests.size)
        assertEquals(Priority.PRIORITY_HIGH_ACCURACY, gpsRequests.single().priority)
        assertEquals(52.0105, settings.lastPublishedLat, 1e-9)
    }

    @Test
    fun `GPS fixes repeat only after the configured interval`() {
        update(52.01, accuracy = 300f)
        assertEquals(1, gpsRequests.size)

        advanceMinutes(2)
        update(52.02, accuracy = 300f)
        assertEquals(1, gpsRequests.size)

        advanceMinutes(3)
        update(52.03, accuracy = 300f)
        assertEquals(2, gpsRequests.size)
    }

    @Test
    fun `stationary never requests GPS`() {
        update(52.0001, accuracy = 20f)

        assertEquals(MovementState.STATIONARY, settings.movementState)
        assertEquals(0, gpsRequests.size)
    }

    @Test
    fun `with high accuracy off no GPS is requested and coarse fixes are published`() {
        settings.preciseMode = false

        update(52.01, accuracy = 150f)

        assertEquals(MovementState.MOVING, settings.movementState)
        assertEquals(0, gpsRequests.size)
        assertEquals(52.01, settings.lastPublishedLat, 1e-9)
    }

    @Test
    fun `without fine location no GPS is requested`() {
        whenever(permissions.fineGranted()).thenReturn(false)

        update(52.01, accuracy = 150f)

        assertEquals(0, gpsRequests.size)
        assertEquals(52.01, settings.lastPublishedLat, 1e-9)
    }

    @Test
    fun `without GPS a coarse fix moved less than its accuracy is not published`() {
        settings.preciseMode = false
        update(52.01, accuracy = 150f)

        // ~111 m from the last published fix, within its 150 m accuracy.
        advanceMinutes(2)
        update(52.011, accuracy = 150f)

        assertEquals(52.01, settings.lastPublishedLat, 1e-9)
    }

    @Test
    fun `without GPS a fix coarser than the cap is not published`() {
        settings.preciseMode = false

        update(52.05, accuracy = 400f)

        assertEquals(MovementState.MOVING, settings.movementState)
        assertEquals(0L, settings.lastPublishedTimeMs)
    }
}
