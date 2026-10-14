package nl.freshlytyped.homelocation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

enum class PublishFailureCategory {
    OFFLINE,
    TIMEOUT,
    CONFIGURATION,
    SERVER
}

class PublishException(val category: PublishFailureCategory) : IOException(category.name)

object PublishFailureMapper {

    fun categoryFor(statusCode: Int): PublishFailureCategory = when {
        statusCode == 408 -> PublishFailureCategory.TIMEOUT
        statusCode in 400..499 -> PublishFailureCategory.CONFIGURATION
        else -> PublishFailureCategory.SERVER
    }

    fun categoryFor(error: Throwable): PublishFailureCategory = when (error) {
        is PublishException -> error.category
        is SocketTimeoutException -> PublishFailureCategory.TIMEOUT
        is UnknownHostException, is ConnectException, is NoRouteToHostException ->
            PublishFailureCategory.OFFLINE
        else -> PublishFailureCategory.SERVER
    }

    fun isTransient(category: PublishFailureCategory): Boolean =
        category == PublishFailureCategory.OFFLINE || category == PublishFailureCategory.TIMEOUT
}

/**
 * Publishes location fixes to PubNub via the REST publish endpoint.
 *
 * A Home Assistant access token never appears here: PubNub is the
 * transport, and the separate consumer service holds the HA credential.
 */
class LocationPublisher(
    private val settings: LocationSettings,
    private val client: OkHttpClient = defaultClient(),
    private val retryDelayMs: suspend (Long) -> Unit = { delay(it) }
) {

    /**
     * Publishes the fix, retrying transient network failures with bounded
     * backoff. Never logs coordinates or credentials.
     */
    suspend fun publish(fix: LocationFix): Result<Unit> = withContext(Dispatchers.IO) {
        val publishKey = settings.publishKey
        val subscribeKey = settings.subscribeKey
        if (publishKey.isBlank() || subscribeKey.isBlank()) {
            return@withContext Result.failure(PublishException(PublishFailureCategory.CONFIGURATION))
        }

        val payload = LocationPayload.build(fix)
        var lastFailure: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            val attemptResult = runCatching {
                publishOnce(payload, publishKey, subscribeKey, settings.apiKey, settings.channel)
            }
            val failure = attemptResult.exceptionOrNull()
            val category = failure?.let { PublishFailureMapper.categoryFor(it) }
                ?: PublishFailureCategory.SERVER

            if (failure == null) return@withContext Result.success(Unit)
            lastFailure = failure
            Log.w(TAG, "Publish attempt $attempt failed: $category")
            if (category !in TRANSIENT || attempt == MAX_ATTEMPTS) break
            retryDelayMs(Backoff.msFor(attempt))
        }
        Result.failure(lastFailure ?: PublishException(PublishFailureCategory.SERVER))
    }

    private fun publishOnce(
        payload: String,
        publishKey: String,
        subscribeKey: String,
        apiKey: String,
        channel: String
    ) {
        val url = "${LocationSettings.PUBNUB_BASE_URL}/publish".toHttpUrl().newBuilder()
            .addPathSegment(publishKey)
            .addPathSegment(subscribeKey)
            .addPathSegment("0")
            .addPathSegment(channel)
            .addPathSegment("0")
            .build()
            .toString()

        val body = payload.toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder().url(url).post(body)
        if (apiKey.isNotBlank()) requestBuilder.header("x-api-key", apiKey)

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw PublishException(PublishFailureMapper.categoryFor(response.code))
            }
        }
    }

    companion object {
        private const val TAG = "LocationPublisher"
        private const val MAX_ATTEMPTS = 3

        private val TRANSIENT = setOf(
            PublishFailureCategory.OFFLINE,
            PublishFailureCategory.TIMEOUT
        )

        private object Backoff {
            fun msFor(attempt: Int): Long = when (attempt) {
                1 -> 5_000L
                else -> 15_000L
            }
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
