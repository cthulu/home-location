package nl.freshlytyped.homelocation

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.location.Location
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * A lost geofence EXIT must not pause tracking forever: the heartbeat checks
 * one fix against the current zone, and a zone list that drops the current
 * zone resumes tracking right away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CurrentZoneCheckTest {

    private lateinit var context: Context
    private lateinit var settings: LocationSettings
    private lateinit var diagnostics: DiagnosticLogger
    private lateinit var coordinator: LocationCoordinator

    private var clockValue: Long = 1_000_000L
    private var oneShotTask: () -> Task<Location> = { Tasks.forResult(null) }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        File(context.filesDir, "diagnostics.log").delete()
        diagnostics = DiagnosticLogger(context)
        settings = LocationSettings(context)
        settings.resetState()
        settings.publishKey = "pub"
        settings.subscribeKey = "sub"
        settings.trackingEnabled = true
        settings.persistedZones = JSONArray().put(JSONObject().apply {
            put("name", "home")
            put("latitude", 52.0)
            put("longitude", 5.0)
            put("radius", 100)
        }).toString()
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
            diagnostics = diagnostics,
            clock = { clockValue },
            currentLocation = { oneShotTask() }
        )
        settings.currentZoneId = "home"
        settings.lastZoneTransitionMs = clockValue - 60 * 60_000L
        coordinator.ensureRegistered()
    }

    private fun fixAt(latitude: Double, accuracy: Float) = Location("test").apply {
        this.latitude = latitude
        longitude = 5.0
        this.accuracy = accuracy
        time = clockValue
    }

    private fun heartbeatWith(location: Location?) {
        oneShotTask = { Tasks.forResult(location) }
        coordinator.onHeartbeat()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `a fix clearly outside the current zone exits it and resumes tracking`() {
        // ~1.1 km north of the 100 m home zone.
        heartbeatWith(fixAt(52.01, accuracy = 30f))

        assertNull(settings.currentZoneId)
        assertTrue(diagnostics.snapshot().contains("zone_stale_exit id=home"))
        assertTrue(diagnostics.snapshot().contains("zone_pause_inactive"))
    }

    @Test
    fun `a fix inside the current zone keeps the pause`() {
        heartbeatWith(fixAt(52.0, accuracy = 30f))

        assertEquals("home", settings.currentZoneId)
    }

    @Test
    fun `a fix outside only within its accuracy keeps the pause`() {
        // ~167 m north: outside the 100 m radius, but 150 m accuracy could
        // still put the device inside.
        heartbeatWith(fixAt(52.0015, accuracy = 150f))

        assertEquals("home", settings.currentZoneId)
    }

    @Test
    fun `no fix keeps the pause`() {
        heartbeatWith(null)

        assertEquals("home", settings.currentZoneId)
    }

    @Test
    fun `removing the current zone from the list resumes tracking`() {
        coordinator.testZoneManager.updateZones(
            listOf(Zone(name = "work", latitude = 52.1, longitude = 5.1, radius = 100))
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(settings.currentZoneId)
        assertTrue(diagnostics.snapshot().contains("zone_pause_released reason=zone_removed"))
        assertTrue(diagnostics.snapshot().contains("zone_pause_inactive"))
    }

    @Test
    fun `the heartbeat completes only after the zone check fix arrives`() {
        val pending = TaskCompletionSource<Location>()
        oneShotTask = { pending.task }
        var completed = false

        coordinator.onHeartbeat { completed = true }
        idle()
        assertFalse("must hold the wakeup until the fix arrives", completed)

        pending.setResult(fixAt(52.01, accuracy = 30f))
        idle()
        assertTrue(completed)
        assertNull(settings.currentZoneId)
    }

    @Test
    fun `the heartbeat completes when the zone check fails`() {
        oneShotTask = { Tasks.forException(IllegalStateException("no provider")) }
        var completed = false

        coordinator.onHeartbeat { completed = true }
        idle()

        assertTrue(completed)
        assertEquals("home", settings.currentZoneId)
    }

    @Test
    fun `the heartbeat outside a zone completes immediately`() {
        settings.currentZoneId = null
        var completed = false

        coordinator.onHeartbeat { completed = true }

        assertTrue(completed)
    }

    @Test
    fun `the heartbeat alarm wakes the device`() {
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
        val heartbeat = alarms.single {
            shadowOf(it.operation).savedIntent.action == LocationReceiver.ACTION_HEARTBEAT
        }

        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, heartbeat.type)
    }
}
