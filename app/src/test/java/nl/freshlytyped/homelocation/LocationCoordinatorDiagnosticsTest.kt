package nl.freshlytyped.homelocation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocationCoordinatorDiagnosticsTest {

    private lateinit var context: Context
    private lateinit var settings: LocationSettings
    private lateinit var sharedDiagnostics: DiagnosticLogger
    private lateinit var coordinator: LocationCoordinator

    private var clockValue: Long = 1_000L

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Start from a clean log so the assertion is unambiguous.
        File(context.filesDir, "diagnostics.log").delete()
        settings = LocationSettings(context)
        sharedDiagnostics = DiagnosticLogger(context)
        val permissions = mock<LocationPermissionManager>()
        whenever(permissions.trackingAllowed()).thenReturn(true)
        whenever(permissions.fineGranted()).thenReturn(true)
        coordinator = LocationCoordinator(
            context = context,
            settings = settings,
            permissions = permissions,
            publisher = mock(),
            diagnostics = sharedDiagnostics,
            clock = { clockValue }
        )
    }

    @Test
    fun `coordinator routes diagnostic records through the injected shared instance`() {
        settings.currentZoneId = "home"
        settings.lastZoneTransitionMs = clockValue // recent -> within debounce window
        settings.geofenceDebounceMs = 180_000L

        coordinator.onZoneEntered("work")

        assertTrue(
            "records made inside the coordinator must land in the shared instance",
            sharedDiagnostics.snapshot().contains("debounce_ignored=true")
        )
    }
}
