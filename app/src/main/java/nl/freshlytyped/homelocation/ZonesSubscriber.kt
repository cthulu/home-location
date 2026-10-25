package nl.freshlytyped.homelocation

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Subscribes to the shared PubNub channel using the HTTP stream API.
 * On connection (and reconnection), publishes a request_zones message
 * to bootstrap the zone list. Filters incoming messages to only process
 * `zones` type responses.
 *
 * State machine:
 *   STOPPED → CONNECTING → STREAMING → (request zones) → STREAMING
 *   STREAMING → (timeout/error) → CONNECTING → ...
 *   Any state → STOPPED on stop()
 */
class ZonesSubscriber(
    private val settings: LocationSettings,
    private val zoneManager: ZoneManager,
    private val diagnostics: DiagnosticLogger,
    private val client: OkHttpClient = defaultClient()
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamJob: Job? = null
    @Volatile private var subscribeCall: Call? = null
    private var reconnectAttempts = 0

    fun start() {
        if (streamJob != null && streamJob!!.isActive) return
        if (settings.subscribeKey.isBlank()) {
            Log.w(TAG, "ZonesSubscriber skipped: no subscribe key configured")
            diagnostics.record("zones_subscriber_skipped reason=no_subscribe_key")
            return
        }
        // Restore cached zones immediately so geofencing works offline
        val cachedZones = zoneManager.getZones()
        if (cachedZones.isNotEmpty()) {
            diagnostics.record("zones_restored_from_cache count=${cachedZones.size}")
            zoneManager.updateZones(cachedZones)
        }
        streamJob = scope.launch { startStream() }
        diagnostics.record("zones_subscriber_started channel=${settings.channel}")
        Log.i(TAG, "ZonesSubscriber started, channel=${settings.channel}")
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        subscribeCall?.cancel()
        subscribeCall = null
        reconnectAttempts = 0
        diagnostics.record("zones_subscriber_stopped")
    }

    private suspend fun startStream() {
        var timetoken: Long = 0
        var lastZoneRequestTime = 0L

        while (streamJob?.isActive == true) {
            // Connect phase
            val connected = connectAndStream(timetoken, lastZoneRequestTime)
            val newTimetoken = connected.second
            lastZoneRequestTime = connected.third
            timetoken = newTimetoken

            if (!connected.first) {
                // Connection failed — apply backoff
                reconnectAttempts++
                val delayMs = backoffDelay(reconnectAttempts)
                Log.w(TAG, "Stream disconnected, retrying in ${delayMs}ms (attempt $reconnectAttempts)")
                delay(delayMs)
                // Re-request zones on reconnection after a gap
                if (connected.third == 0L || System.currentTimeMillis() - lastZoneRequestTime > RECONNECT_ZONE_REFRESH_MS) {
                    requestZones()
                    lastZoneRequestTime = System.currentTimeMillis()
                }
            } else {
                // Successful stream — reset backoff
                reconnectAttempts = 0
            }
        }
    }

    /**
     * Open the HTTP stream and read messages until it closes.
     * Returns triple: (wasSuccessful, newTimetoken, lastZoneRequestTime)
     */
    private suspend fun connectAndStream(
        timetoken: Long,
        lastZoneRequestTime: Long
    ): Triple<Boolean, Long, Long> {
        val subscribeUrl = buildSubscribeUrl(timetoken)
        val call = client.newCall(Request.Builder().url(subscribeUrl).build())
        subscribeCall = call
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Subscribe response: ${response.code}")
                    return Triple(false, timetoken, lastZoneRequestTime)
                }

                // Connection successful — request zones on first connect
                val currentZoneRequestTime = if (lastZoneRequestTime == 0L ||
                    System.currentTimeMillis() - lastZoneRequestTime > PERIODIC_ZONE_REFRESH_MS
                ) {
                    requestZones()
                    System.currentTimeMillis()
                } else {
                    lastZoneRequestTime
                }

                val body = response.body?.string()
                    ?: return Triple(false, timetoken, currentZoneRequestTime)
                diagnostics.record("zones_stream_response bytes=${body.length}")
                Log.d(TAG, "Subscribe response received (${body.length} bytes)")
                if (body.isBlank()) return Triple(true, timetoken, currentZoneRequestTime)

                val parsed = parseStreamMessage(body)
                if (parsed == null) {
                    Log.w(TAG, "Unable to parse subscribe response (${body.length} bytes)")
                    diagnostics.record("zones_stream_parse_failed bytes=${body.length}")
                    Triple(true, timetoken, currentZoneRequestTime)
                } else {
                    Log.d(TAG, "Parsed ${parsed.messages.size} PubNub message(s)")
                    parsed.messages.forEach(::handleMessage)
                    Triple(true, parsed.timetoken, currentZoneRequestTime)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Stream error", e)
            diagnostics.record("zones_stream_error ${e.javaClass.simpleName}")
            Triple(false, timetoken, lastZoneRequestTime)
        } finally {
            if (subscribeCall === call) subscribeCall = null
        }
    }

    private fun buildSubscribeUrl(timetoken: Long): String {
        val baseUrl = LocationSettings.PUBNUB_BASE_URL.replace("https://", "")
        return "https://$baseUrl/subscribe/${settings.subscribeKey}/${settings.channel}/0/$timetoken"
    }

    /** Exponential backoff with jitter, capped at MAX_BACKOFF_MS. */
    private fun backoffDelay(attempt: Int): Long {
        val base = minOf(INITIAL_BACKOFF_MS * (1L shl (attempt - 1)), MAX_BACKOFF_MS)
        return (base * (0.5 + (0.5 * (1..100).random() / 100.0))).toLong()
    }

    /** Publish a request_zones message to the existing channel via REST. */
    fun requestZones() {
        scope.launch {
            val payload = mapOf(
                "type" to "request_zones",
                "device_name" to settings.deviceName
            )
            publishZonesRequest(payload)
        }
    }

    private suspend fun publishZonesRequest(payload: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        val publishUrl = "${LocationSettings.PUBNUB_BASE_URL}/publish/${settings.publishKey}/${settings.subscribeKey}/0/${settings.channel}/0"
        val body = Gson().toJson(payload).toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(publishUrl).post(body).build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    diagnostics.record("zones_requested")
                    return@withContext true
                } else {
                    Log.w(TAG, "Request zones failed: ${response.code}")
                    diagnostics.record("zones_request_failed status=${response.code}")
                    return@withContext false
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to request zones", error)
            diagnostics.record("zones_request_failed ${error.javaClass.simpleName}")
            return@withContext false
        }
        false
    }

    private fun handleMessage(message: Map<String, Any>) {
        val type = message["type"] as? String
        Log.d(TAG, "PubNub message type=${type ?: "missing"}")
        diagnostics.record("zones_message_type type=${type ?: "missing"}")
        if (type != "zones") return

        val rawZones = message["zones"] as? List<*>
        if (rawZones == null) {
            Log.w(TAG, "Zones message has no zones array")
            diagnostics.record("zones_message_invalid missing_zones_array=true")
            return
        }

        val deviceName = settings.deviceName
        val zones = parseZones(message, deviceName)
        Log.i(TAG, "Received zones message: ${rawZones.size} zone(s), ${zones.size} matching device")
        diagnostics.record("zones_message_received raw_count=${rawZones.size} matching_count=${zones.size}")
        // An explicitly empty list is the protocol's signal to clear cached zones.
        // Do not clear existing zones if a non-empty list only contains other devices
        // or malformed entries.
        if (rawZones.isEmpty() || zones.isNotEmpty()) {
            zoneManager.updateZones(zones)
        }
    }

    /** Parse a zones message, filtering by device name. */
    private fun parseZones(message: Map<String, Any>, deviceName: String): List<Zone> {
        val zonesRaw = message["zones"] ?: return emptyList()
        val zonesList = when (zonesRaw) {
            is List<*> -> zonesRaw
            else -> return emptyList()
        }
        return zonesList.mapNotNull { raw ->
            if (raw !is Map<*, *>) return@mapNotNull null
            val name = (raw["name"] as? String) ?: return@mapNotNull null
            val lat = (raw["latitude"] as? Number)?.toDouble() ?: return@mapNotNull null
            val lon = (raw["longitude"] as? Number)?.toDouble() ?: return@mapNotNull null
            val radius = (raw["radius"] as? Number)?.toInt() ?: return@mapNotNull null
            val zoneDeviceName = raw["device_name"] as? String
            if (zoneDeviceName != deviceName) return@mapNotNull null
            Zone(name = name, latitude = lat, longitude = lon, radius = radius)
        }
    }

    private fun parseStreamMessage(json: String): StreamEntry? {
        return try {
            val array = Gson().fromJson(json, List::class.java)
            if (array.size < 2) return null
            // The legacy /subscribe endpoint returns [messages, timetoken, ...].
            val messages = (array[0] as? List<*>)
                ?.filterIsInstance<Map<String, Any>>()
                ?: return null
            val timetoken = array[1]?.toString()?.toLongOrNull() ?: return null
            StreamEntry(timetoken = timetoken, messages = messages)
        } catch (_: JsonSyntaxException) {
            null
        }
    }

    private data class StreamEntry(
        val timetoken: Long,
        val messages: List<Map<String, Any>>
    )

    companion object {
        private const val TAG = "ZonesSubscriber"

        /** Initial backoff delay after first connection failure. */
        private const val INITIAL_BACKOFF_MS = 10_000L

        /** Maximum backoff delay. */
        private const val MAX_BACKOFF_MS = 5 * 60_000L

        /** Request zones again after this much time since last request. */
        private const val PERIODIC_ZONE_REFRESH_MS = 12 * 60 * 60_000L

        /** Re-request zones on reconnect if the gap was this long. */
        private const val RECONNECT_ZONE_REFRESH_MS = 30_000L

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(330, TimeUnit.SECONDS)
            .build()
    }
}
