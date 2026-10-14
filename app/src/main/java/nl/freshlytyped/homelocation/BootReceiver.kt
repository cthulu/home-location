package nl.freshlytyped.homelocation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-establishes the tracking registrations after a device reboot.
 * Fused-location PendingIntent subscriptions are lost on reboot, so
 * the coordinator must re-register them when tracking is enabled.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? App
            ?: return
        app.locationCoordinator.ensureRegistered()
    }
}
