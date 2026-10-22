package nl.freshlytyped.homelocation

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

enum class MovementState {
    STATIONARY,
    MOVING
}

class LocationSettings(context: Context) {

    companion object {
        private const val PREFS_NAME = "location_settings"
        private const val KEY_PUBLISH_KEY = "pubnub_publish_key"
        private const val KEY_SUBSCRIBE_KEY = "pubnub_subscribe_key"
        private const val KEY_API_KEY = "pubnub_api_key"
        private const val KEY_CHANNEL = "pubnub_channel"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_TRACKING_ENABLED = "tracking_enabled"
        private const val KEY_PRECISE_MODE = "precise_mode"
        private const val KEY_MAX_ACCURACY = "max_accuracy_meters"
        private const val KEY_MIN_PUBLISH_INTERVAL = "min_publish_interval_ms"
        private const val KEY_MIN_DISTANCE = "min_distance_meters"
        private const val KEY_MOVEMENT_DISTANCE = "movement_detection_distance_meters"
        private const val KEY_MAX_AGE = "max_location_age_ms"
        private const val KEY_PERIODIC_FALLBACK_ENABLED = "periodic_fallback_enabled"
        private const val KEY_PERIODIC_FALLBACK_INTERVAL = "periodic_fallback_interval_ms"
        private const val KEY_BURST_DURATION = "burst_duration_ms"
        private const val KEY_BACKGROUND_LOCATION_ENABLED = "background_location_enabled"
        private const val KEY_BACKGROUND_SCAN_INTERVAL = "background_scan_interval_ms"
        private const val KEY_MOVEMENT_STATE = "movement_state"
        private const val KEY_LAST_KNOWN_LAT = "last_known_lat"
        private const val KEY_LAST_KNOWN_LON = "last_known_lon"
        private const val KEY_LAST_KNOWN_TIME = "last_known_time"
        private const val KEY_LAST_PUBLISHED_LAT = "last_published_lat"
        private const val KEY_LAST_PUBLISHED_LON = "last_published_lon"
        private const val KEY_LAST_PUBLISHED_TIME = "last_published_time"
        private const val KEY_LAST_PUBLISHED_ACCURACY = "last_published_accuracy_meters"
        private const val KEY_LAST_EVIDENCE_TIME = "last_movement_evidence_time"
        private const val KEY_MOVING_STARTED_TIME = "moving_started_time"
        private const val KEY_ACTIVITY_COOLDOWN_UNTIL = "activity_cooldown_until"
        private const val KEY_ACCUMULATED_DISTANCE = "accumulated_distance_meters"

        const val DEFAULT_CHANNEL = "home-location"

        // Not const so tests can override via reflection (compiler would inline a const).
        @JvmField var PUBNUB_BASE_URL = "https://ps.pndsn.com"

        val MINUTE_MS = 60_000L
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var publishKey: String
        get() = prefs.getString(KEY_PUBLISH_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PUBLISH_KEY, value.trim()).apply()

    var subscribeKey: String
        get() = prefs.getString(KEY_SUBSCRIBE_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SUBSCRIBE_KEY, value.trim()).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    var channel: String
        get() = prefs.getString(KEY_CHANNEL, DEFAULT_CHANNEL) ?: DEFAULT_CHANNEL
        set(value) = prefs.edit().putString(KEY_CHANNEL, value.trim()).apply()

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, Build.MODEL) ?: Build.MODEL
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value.trim().ifBlank { Build.MODEL }).apply()

    var trackingEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_TRACKING_ENABLED, value).apply()

    /** When true and fine location is granted, the moving state uses high-accuracy updates. */
    var preciseMode: Boolean
        get() = prefs.getBoolean(KEY_PRECISE_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_PRECISE_MODE, value).apply()

    /** Fixes whose reported accuracy is worse than this (in meters) are rejected. */
    var maxAccuracyMeters: Float
        get() = prefs.getFloat(KEY_MAX_ACCURACY, 50f)
        set(value) = prefs.edit().putFloat(KEY_MAX_ACCURACY, value).apply()

    /** Minimum time between two published fixes. */
    var minPublishIntervalMs: Long
        get() = prefs.getLong(KEY_MIN_PUBLISH_INTERVAL, MINUTE_MS)
        set(value) = prefs.edit().putLong(KEY_MIN_PUBLISH_INTERVAL, value).apply()

    /** A fix is published when the device moved at least this far from the last published fix. */
    var minDistanceMeters: Float
        get() = prefs.getFloat(KEY_MIN_DISTANCE, 50f)
        set(value) = prefs.edit().putFloat(KEY_MIN_DISTANCE, value).apply()

    /** A fix further than this from the last known fix triggers the moving state. */
    var movementDetectionDistanceMeters: Float
        get() = prefs.getFloat(KEY_MOVEMENT_DISTANCE, 100f)
        set(value) = prefs.edit().putFloat(KEY_MOVEMENT_DISTANCE, value).apply()

    /** Fixes older than this are rejected. */
    var maxLocationAgeMs: Long
        get() = prefs.getLong(KEY_MAX_AGE, 15 * MINUTE_MS)
        set(value) = prefs.edit().putLong(KEY_MAX_AGE, value).apply()

    /** When enabled, a fix is published once per interval even without movement. */
    var periodicFallbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_PERIODIC_FALLBACK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_PERIODIC_FALLBACK_ENABLED, value).apply()

    /** Interval for the periodic fallback (same as [minPublishIntervalMs] unless overridden). */
    var periodicFallbackIntervalMs: Long
        get() = prefs.getLong(KEY_PERIODIC_FALLBACK_INTERVAL, 30 * MINUTE_MS)
        set(value) = prefs.edit().putLong(KEY_PERIODIC_FALLBACK_INTERVAL, value).apply()

    /** How long the high-accuracy burst stays active after the last movement evidence. */
    var burstDurationMs: Long
        get() = prefs.getLong(KEY_BURST_DURATION, 10 * MINUTE_MS)
        set(value) = prefs.edit().putLong(KEY_BURST_DURATION, value).apply()

    var backgroundLocationEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_LOCATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND_LOCATION_ENABLED, value).apply()

    /** Stationary/background scan interval. */
    var backgroundScanIntervalMs: Long
        get() = prefs.getLong(KEY_BACKGROUND_SCAN_INTERVAL, 60_000L)
        set(value) = prefs.edit().putLong(KEY_BACKGROUND_SCAN_INTERVAL, value).apply()

    fun isConfigured(): Boolean =
        publishKey.isNotBlank() && subscribeKey.isNotBlank()

    // --- Persisted runtime state ---

    var movementState: MovementState
        get() = if (prefs.getString(KEY_MOVEMENT_STATE, null) == MovementState.MOVING.name) {
            MovementState.MOVING
        } else {
            MovementState.STATIONARY
        }
        set(value) = prefs.edit().putString(KEY_MOVEMENT_STATE, value.name).apply()

    var lastKnownLat: Double
        get() = prefs.getLong(KEY_LAST_KNOWN_LAT, 0L).toDouble() / 1e7
        set(value) = prefs.edit().putLong(KEY_LAST_KNOWN_LAT, (value * 1e7).toLong()).apply()

    var lastKnownLon: Double
        get() = prefs.getLong(KEY_LAST_KNOWN_LON, 0L).toDouble() / 1e7
        set(value) = prefs.edit().putLong(KEY_LAST_KNOWN_LON, (value * 1e7).toLong()).apply()

    var lastKnownTimeMs: Long
        get() = prefs.getLong(KEY_LAST_KNOWN_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_KNOWN_TIME, value).apply()

    var lastPublishedLat: Double
        get() = prefs.getLong(KEY_LAST_PUBLISHED_LAT, 0L).toDouble() / 1e7
        set(value) = prefs.edit().putLong(KEY_LAST_PUBLISHED_LAT, (value * 1e7).toLong()).apply()

    var lastPublishedLon: Double
        get() = prefs.getLong(KEY_LAST_PUBLISHED_LON, 0L).toDouble() / 1e7
        set(value) = prefs.edit().putLong(KEY_LAST_PUBLISHED_LON, (value * 1e7).toLong()).apply()

    var lastPublishedTimeMs: Long
        get() = prefs.getLong(KEY_LAST_PUBLISHED_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_PUBLISHED_TIME, value).apply()

    var lastPublishedAccuracyMeters: Float
        get() = prefs.getFloat(KEY_LAST_PUBLISHED_ACCURACY, 0f)
        set(value) = prefs.edit().putFloat(KEY_LAST_PUBLISHED_ACCURACY, value).apply()

    var lastMovementEvidenceTimeMs: Long
        get() = prefs.getLong(KEY_LAST_EVIDENCE_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_EVIDENCE_TIME, value).apply()

    var movingStartedTimeMs: Long
        get() = prefs.getLong(KEY_MOVING_STARTED_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_MOVING_STARTED_TIME, value).apply()

    var activityCooldownUntilMs: Long
        get() = prefs.getLong(KEY_ACTIVITY_COOLDOWN_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_ACTIVITY_COOLDOWN_UNTIL, value).apply()

    /** Travel distance accumulated since the last reset (used for movement detection). */
    var accumulatedDistanceMeters: Float
        get() = prefs.getFloat(KEY_ACCUMULATED_DISTANCE, 0f)
        set(value) = prefs.edit().putFloat(KEY_ACCUMULATED_DISTANCE, value).apply()

    fun hasLastKnown(): Boolean = lastKnownTimeMs > 0

    fun hasLastPublished(): Boolean = lastPublishedTimeMs > 0

    /** Resets all persisted runtime state (used when tracking is stopped). */
    fun resetState() {
        prefs.edit()
            .putString(KEY_MOVEMENT_STATE, MovementState.STATIONARY.name)
            .putLong(KEY_LAST_KNOWN_TIME, 0L)
            .putLong(KEY_LAST_KNOWN_LAT, 0L)
            .putLong(KEY_LAST_KNOWN_LON, 0L)
            .putLong(KEY_LAST_PUBLISHED_TIME, 0L)
            .putLong(KEY_LAST_PUBLISHED_LAT, 0L)
            .putLong(KEY_LAST_PUBLISHED_LON, 0L)
            .putFloat(KEY_LAST_PUBLISHED_ACCURACY, 0f)
            .putLong(KEY_LAST_EVIDENCE_TIME, 0L)
            .putLong(KEY_MOVING_STARTED_TIME, 0L)
            .putLong(KEY_ACTIVITY_COOLDOWN_UNTIL, 0L)
            .putFloat(KEY_ACCUMULATED_DISTANCE, 0f)
            .apply()
    }
}
