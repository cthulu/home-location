package nl.freshlytyped.homelocation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult

class LocationTrackingService : Service() {

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            (application as App).locationCoordinator.onLocationUpdates(result.locations)
        }
    }

    override fun onCreate() {
        super.onCreate()
        (application as App).diagnostics.record("foreground_service_created")
        createNotificationChannel()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        }.onFailure {
            Log.e(TAG, "Failed to start foreground service", it)
            stopSelf()
            return
        }
        val coordinator = (application as App).locationCoordinator
        coordinator.setDirectLocationCallback(locationCallback, Looper.getMainLooper())
        coordinator.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY.also {
            (application as App).locationCoordinator.start()
        }

    override fun onDestroy() {
        (application as App).diagnostics.record("foreground_service_destroyed")
        val coordinator = (application as App).locationCoordinator
        coordinator.clearDirectLocationCallback()
        coordinator.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.location_tracking_notification))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.location_tracking_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.location_tracking_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "HomeLocation"
        private const val CHANNEL_ID = "location_tracking"
        private const val NOTIFICATION_ID = 1001

        fun start(context: android.content.Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationTrackingService::class.java)
            )
        }
    }
}
