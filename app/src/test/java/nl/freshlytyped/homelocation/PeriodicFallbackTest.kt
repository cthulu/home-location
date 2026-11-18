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
 * The stationary fallback refreshes the position without GPS, and its fix is
 * handled like any other fix, so it also counts for movement detection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PeriodicFallbackTest {

    private lateinit var settings: LocationSettings
    private lateinit var coordinator: LocationCoordinator

    private var clockValue: Long = 100_000_000L
    private val requests = mutableListOf<CurrentLocationRequest>()
    private var location: Location? = null

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
        settings.periodicFallbackEnabled = true
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
            clock = { clockValue },
            currentLocation = { request ->
                requests += request
                Tasks.forResult(location)
            }
        )
        settings.lastKnownLat = 52.0
        settings.lastKnownLon = 5.0
        settings.lastKnownTimeMs = clockValue - 60_000L
        settings.lastPublishedLat = 52.0
        settings.lastPublishedLon = 5.0
        settings.lastPublishedTimeMs = clockValue - 31 * 60_000L
    }

    private fun fixAt(latitude: Double) = Location("test").apply {
        this.latitude = latitude
        longitude = 5.0
        accuracy = 20f
        time = clockValue
    }

    private fun fallback() {
        coordinator.onPeriodicPublish()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `the fallback fix is balanced even with high accuracy on`() {
        location = fixAt(52.0)

        fallback()

        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY, requests.single().priority)
        assertEquals(30_000L, requests.single().durationMillis)
    }

    @Test
    fun `an unchanged position is still published after the fallback interval`() {
        location = fixAt(52.0)

        fallback()

        assertEquals(clockValue, settings.lastPublishedTimeMs)
    }

    @Test
    fun `a fallback fix that proves travel switches to moving`() {
        // ~222 m from the last known position.
        location = fixAt(52.002)

        fallback()

        assertEquals(MovementState.MOVING, settings.movementState)
        assertEquals(52.002, settings.lastKnownLat, 1e-9)
    }
}
