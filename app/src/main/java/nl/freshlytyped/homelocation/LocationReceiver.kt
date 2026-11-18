package nl.freshlytyped.homelocation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.LocationResult
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single delivery point for all system-generated events:
 * - fused location updates (PendingIntent delivery from the Fused
 *   Location Provider — this works even when the app process is dead)
 * - activity recognition updates
 * - burst-expiry and heartbeat alarms
 *
 * The coordinator is idempotent, so each wake re-establishes whatever
 * should be active. Declared in the manifest with exported=false and a
 * private action.
 */
class LocationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? App
        if (app == null) {
            Log.e(TAG, "Location event ignored: unexpected application context")
            return
        }
        val coordinator = app.locationCoordinator

        val pendingResult = goAsync()
        if (intent.action == ACTION_HEARTBEAT) {
            holdUntilDone(pendingResult::finish) { done -> coordinator.onHeartbeat(done) }
            return
        }
        try {
            when (intent.action) {
                ACTION_LOCATION_UPDATE -> {
                    val result = LocationResult.extractResult(intent)
                    if (result == null) {
                        app.diagnostics.record("location_update_empty_result")
                    } else {
                        coordinator.onLocationUpdates(result.locations)
                    }
                }

                ACTION_ACTIVITY_UPDATE -> {
                    val result = ActivityRecognitionResult.extractResult(intent)
                    coordinator.onActivityUpdate(
                        result?.mostProbableActivity?.type ?: DetectedActivity.UNKNOWN,
                        result?.mostProbableActivity?.confidence ?: 0,
                        result?.time ?: System.currentTimeMillis()
                    )
                }

                ACTION_BURST_END -> coordinator.onBurstEnd()
                ACTION_PERIODIC_PUBLISH -> coordinator.onPeriodicPublish()
            }
        } finally {
            pendingResult.finish()
        }
    }

    /**
     * Keeps the broadcast (and the alarm's wakelock) open until [work] reports
     * completion, so the device does not sleep before its one-shot fix arrives.
     * The bound stays under the 60 s background broadcast limit.
     */
    private fun holdUntilDone(finish: () -> Unit, work: (done: () -> Unit) -> Unit) {
        val done = FinishOnce(finish)
        Handler(Looper.getMainLooper()).postDelayed(done, MAX_HOLD_MS)
        try {
            work(done::run)
        } catch (e: Throwable) {
            done.run()
            throw e
        }
    }

    companion object {
        private const val TAG = "LocationReceiver"
        private const val MAX_HOLD_MS = 45_000L
        const val ACTION_LOCATION_UPDATE = "nl.freshlytyped.homelocation.ACTION_LOCATION_UPDATE"
        const val ACTION_ACTIVITY_UPDATE = "nl.freshlytyped.homelocation.ACTION_ACTIVITY_UPDATE"
        const val ACTION_BURST_END = "nl.freshlytyped.homelocation.ACTION_BURST_END"
        const val ACTION_HEARTBEAT = "nl.freshlytyped.homelocation.ACTION_HEARTBEAT"
        const val ACTION_PERIODIC_PUBLISH = "nl.freshlytyped.homelocation.ACTION_PERIODIC_PUBLISH"
    }
}

/** Runs [finish] at most once, whichever of completion or timeout comes first. */
internal class FinishOnce(private val finish: () -> Unit) : Runnable {
    private val finished = AtomicBoolean(false)

    override fun run() {
        if (finished.compareAndSet(false, true)) finish()
    }
}
