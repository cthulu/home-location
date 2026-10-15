package nl.freshlytyped.homelocation

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocationPermissionManagerTest {

    private lateinit var app: Application
    private lateinit var manager: LocationPermissionManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        manager = LocationPermissionManager(app)
        setLocationSwitch(true)
    }

    private fun grant(vararg names: String) {
        shadowOf(app).grantPermissions(*names)
    }

    private fun setLocationSwitch(enabled: Boolean) {
        val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(locationManager).setLocationEnabled(enabled)
    }

    @Test
    fun `nothing is granted by default`() {
        assertFalse(manager.coarseGranted())
        assertFalse(manager.fineGranted())
        assertFalse(manager.backgroundGranted())
        assertFalse(manager.activityRecognitionGranted())
    }

    @Test
    fun `granted foreground permissions are detected`() {
        grant(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(manager.coarseGranted())
        assertTrue(manager.fineGranted())
    }

    @Test
    fun `background permission tracks its state`() {
        assertFalse(manager.backgroundGranted())
        grant(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertTrue(manager.backgroundGranted())
    }

    @Test
    fun `first request asks only for the missing permissions`() {
        grant(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        val requested = manager.firstRequestPermissions().toSet()
        assertEquals(setOf(Manifest.permission.ACTIVITY_RECOGNITION), requested)
    }

    @Test
    fun `first request asks for everything when nothing is granted`() {
        val requested = manager.firstRequestPermissions().toSet()
        assertEquals(
            setOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACTIVITY_RECOGNITION
            ),
            requested
        )
    }

    @Test
    fun `background request is empty once granted`() {
        val requested = manager.backgroundRequestPermissions().toSet()
        assertEquals(setOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), requested)
        grant(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertEquals(emptySet<String>(), manager.backgroundRequestPermissions().toSet())
    }

    @Test
    fun `tracking requires coarse or fine plus background permission`() {
        grant(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
        assertTrue(manager.trackingAllowed())
    }

    @Test
    fun `tracking is blocked without the background permission`() {
        grant(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        assertFalse(manager.trackingAllowed())
    }

    @Test
    fun `tracking is blocked when the system location switch is off`() {
        grant(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
        setLocationSwitch(false)
        assertFalse(manager.trackingAllowed())
    }
}
