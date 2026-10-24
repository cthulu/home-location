package nl.freshlytyped.homelocation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-establishes the tracking service after a device reboot.
 * Fused-location callback registrations are lost on reboot, so
 * the foreground service must be restarted when tracking is enabled.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? App
            ?: return
        if (app.locationSettings.trackingEnabled) {
            LocationTrackingService.start(context)
        }
    }
}
