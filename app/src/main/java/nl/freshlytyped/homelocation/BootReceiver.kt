package nl.freshlytyped.homelocation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-establishes the tracking service after a device reboot or app update.
 * Fused-location callback registrations and geofences are lost on reboot, so
 * the foreground service must be restarted when tracking is enabled. If the
 * foreground-service start is blocked (background-start restrictions), at
 * least re-register the geofences so zone exit is still detected.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val app = context.applicationContext as? App ?: return
        if (!app.locationSettings.trackingEnabled) return
        runCatching { LocationTrackingService.start(context) }
            .onFailure {
                Log.w(TAG, "Foreground service start blocked; restoring geofences only", it)
                app.diagnostics.record("boot_service_start_blocked ${it.javaClass.simpleName}")
                app.locationCoordinator.restoreGeofences()
            }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
