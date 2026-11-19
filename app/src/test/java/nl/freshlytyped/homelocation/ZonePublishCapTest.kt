package nl.freshlytyped.homelocation

import android.content.Context
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Zone entry and exit fixes are accepted under a zone-aware cap wider than
 * the default publish cap. Uses the real [LocationPublisher] so a second,
 * tighter accuracy check inside the publisher cannot silently drop them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ZonePublishCapTest {

    private lateinit var server: MockWebServer
    private lateinit var settings: LocationSettings
    private lateinit var coordinator: LocationCoordinator

    private val now = System.currentTimeMillis()

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        LocationSettings.PUBNUB_BASE_URL = server.url("/").toString().removeSuffix("/")
        val context = ApplicationProvider.getApplicationContext<Context>()
        settings = LocationSettings(context)
        settings.resetState()
        settings.pendingPublish = null
        settings.publishKey = "pub"
        settings.subscribeKey = "sub"
        settings.trackingEnabled = true
        settings.persistedZones = JSONArray().put(JSONObject().apply {
            put("name", "cgu")
            put("latitude", 52.0)
            put("longitude", 5.0)
            put("radius", 150)
        }).toString()
        val permissions = mock<LocationPermissionManager>()
        whenever(permissions.trackingAllowed()).thenReturn(true)
        whenever(permissions.fineGranted()).thenReturn(true)
        coordinator = LocationCoordinator(
            context = context,
            settings = settings,
            permissions = permissions,
            publisher = LocationPublisher(settings, retryDelayMs = { }),
            diagnostics = DiagnosticLogger(context),
            clock = { now }
        )
    }

    @After
    fun tearDown() {
        LocationSettings.PUBNUB_BASE_URL = "https://ps.pndsn.com"
        server.shutdown()
    }

    private fun fixAt(latitude: Double, accuracy: Float) = Location("test").apply {
        this.latitude = latitude
        longitude = 5.0
        this.accuracy = accuracy
        time = now
    }

    private fun publishedLatitude(): Double {
        val request = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("the fix must reach PubNub", request)
        return JSONObject(request!!.body.readUtf8()).getDouble("latitude")
    }

    @Test
    fun `a coarse entry fix confirmed by the zone cap reaches PubNub`() {
        server.enqueue(MockResponse().setResponseCode(200))

        coordinator.onZoneEntered("cgu", fixAt(52.0, accuracy = 100f))

        assertEquals("cgu", settings.currentZoneId)
        assertEquals(52.0, publishedLatitude(), 1e-9)
    }

    @Test
    fun `a coarse departure fix reaches PubNub`() {
        settings.currentZoneId = "cgu"
        server.enqueue(MockResponse().setResponseCode(200))

        // ~222 m north of the center, outside the 150 m radius.
        coordinator.onZoneExited("cgu", fixAt(52.002, accuracy = 100f))

        assertEquals(52.002, publishedLatitude(), 1e-9)
    }
}
