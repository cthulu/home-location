package nl.freshlytyped.homelocation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The system can destroy the foreground service without the user asking;
 * that must not disable tracking or wipe its persisted state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocationTrackingServiceTest {

    private lateinit var settings: LocationSettings

    @Before
    fun setup() {
        settings = ApplicationProvider.getApplicationContext<Context>()
            .let { (it as App).locationSettings }
        settings.resetState()
        settings.trackingEnabled = true
        settings.currentZoneId = "home"
    }

    @Test
    fun `destroying the service keeps tracking enabled`() {
        Robolectric.buildService(LocationTrackingService::class.java).create().destroy()

        assertTrue(settings.trackingEnabled)
        assertEquals("home", settings.currentZoneId)
    }
}
