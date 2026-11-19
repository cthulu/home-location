package nl.freshlytyped.homelocation

import android.Manifest
import android.app.Application
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.switchmaterial.SwitchMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The GPS fix interval only applies while high accuracy is on, so the field
 * (and its label) is disabled and dimmed when the switch is off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsActivityTest {

    private lateinit var app: App

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).grantPermissions(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
        app = context as App
    }

    private fun launch(preciseMode: Boolean): SettingsActivity {
        app.locationSettings.preciseMode = preciseMode
        return Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
    }

    private fun intervalField(activity: SettingsActivity): EditText =
        activity.findViewById(R.id.etGpsFixInterval)

    private fun intervalLabel(activity: SettingsActivity): TextView =
        activity.findViewById(R.id.tvGpsFixIntervalLabel)

    @Test
    fun `interval field is disabled when high accuracy is off`() {
        val activity = launch(preciseMode = false)
        assertFalse(intervalField(activity).isEnabled)
        assertFalse(intervalLabel(activity).isEnabled)
        assertEquals(0.5f, intervalField(activity).alpha, 0.001f)
        assertEquals(0.5f, intervalLabel(activity).alpha, 0.001f)
    }

    @Test
    fun `interval field is enabled when high accuracy is on`() {
        val activity = launch(preciseMode = true)
        assertTrue(intervalField(activity).isEnabled)
        assertTrue(intervalLabel(activity).isEnabled)
    }

    @Test
    fun `toggling high accuracy disables and re-enables the interval field`() {
        val activity = launch(preciseMode = true)
        val field = intervalField(activity)
        val switch = activity.findViewById<SwitchMaterial>(R.id.swPreciseMode)

        switch.isChecked = false
        assertFalse(field.isEnabled)
        assertEquals(0.5f, field.alpha, 0.001f)

        switch.isChecked = true
        assertTrue(field.isEnabled)
        assertEquals(1f, field.alpha, 0.001f)
    }
}
