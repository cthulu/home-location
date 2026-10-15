package nl.freshlytyped.homelocation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocationPublisherTest {

    private lateinit var server: MockWebServer
    private lateinit var settings: LocationSettings
    private lateinit var publisher: LocationPublisher

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        settings = LocationSettings(context)
        server = MockWebServer()
        server.start()
        LocationSettings.PUBNUB_BASE_URL = server.url("/").toString().removeSuffix("/")
        settings.publishKey = "pub-c"
        settings.subscribeKey = "sub-c"
        publisher = LocationPublisher(settings, retryDelayMs = { })
    }

    @After
    fun tearDown() {
        LocationSettings.PUBNUB_BASE_URL = "https://ps.pndsn.com"
        server.shutdown()
    }

    private fun fix() = LocationFix(52.123, 5.123, 12f, 1_760_000_000_000L)

    @Test
    fun `success posts the payload to the documented endpoint`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        assertTrue(publisher.publish(fix()).isSuccess)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/publish/pub-c/sub-c/0/home-location/0", recorded.path)
        val json = JSONObject(recorded.body.readUtf8())
        assertEquals("location", json.getString("type"))
        assertEquals(52.123, json.getDouble("latitude"), 0.0)
    }

    @Test
    fun `api key is sent as a header`() = runTest {
        settings.apiKey = "secret"
        server.enqueue(MockResponse().setResponseCode(200))
        publisher.publish(fix())
        val recorded = server.takeRequest()
        assertEquals("secret", recorded.getHeader("x-api-key"))
    }

    @Test
    fun `missing credentials fail as configuration without any request`() = runTest {
        settings.publishKey = ""
        val result = publisher.publish(fix())
        assertTrue(result.isFailure)
        assertEquals(
            PublishFailureCategory.CONFIGURATION,
            (result.exceptionOrNull() as PublishException).category
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `server errors fail without retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = publisher.publish(fix())
        assertTrue(result.isFailure)
        assertEquals(
            PublishFailureCategory.SERVER,
            (result.exceptionOrNull() as PublishException).category
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `auth errors fail as configuration without retry`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = publisher.publish(fix())
        assertTrue(result.isFailure)
        assertEquals(
            PublishFailureCategory.CONFIGURATION,
            (result.exceptionOrNull() as PublishException).category
        )
        assertEquals(1, server.requestCount)
    }

    @Test
    // A short-circuiting interceptor makes the retry count deterministic; the
    // real socket path is covered by the other tests. runBlocking is used
    // because the retry path is real-time only (no delays to virtualize).
    fun `transient timeout status is retried until the attempt limit`() = runBlocking {
        var attempts = 0
        val timeoutClient = OkHttpClient.Builder().addInterceptor { chain ->
            attempts++
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(408)
                .message("Request Timeout")
                .body("{}".toResponseBody())
                .build()
        }.build()
        val publisher = LocationPublisher(settings, client = timeoutClient, retryDelayMs = { })
        val result = publisher.publish(fix())
        assertTrue(result.isFailure)
        assertEquals(
            PublishFailureCategory.TIMEOUT,
            (result.exceptionOrNull() as PublishException).category
        )
        assertEquals(3, attempts)
    }

    @Test
    fun `connection failures are retried and reported as offline`() = runTest {
        val port = server.port
        server.shutdown()
        LocationSettings.PUBNUB_BASE_URL = "http://localhost:$port"
        var attempts = 0
        val countingClient = OkHttpClient.Builder().addInterceptor { chain ->
            attempts++
            chain.proceed(chain.request())
        }.build()
        val publisher = LocationPublisher(settings, client = countingClient, retryDelayMs = { })
        val result = publisher.publish(fix())
        val exception = result.exceptionOrNull()
        assertTrue(result.isFailure)
        // Network-level failures surface as the raw exception; the category
        // is derived from it, mirroring how the publisher decides to retry.
        assertEquals(PublishFailureCategory.OFFLINE, PublishFailureMapper.categoryFor(exception!!))
        assertEquals(3, attempts)
    }
}
