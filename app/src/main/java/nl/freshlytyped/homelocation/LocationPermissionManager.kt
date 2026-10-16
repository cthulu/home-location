package nl.freshlytyped.homelocation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Checks and requests the location-related runtime permissions.
 *
 * The request flow is two steps because Android requires foreground
 * location to be granted before background location:
 * 1. coarse + fine location (and activity recognition, optional)
 * 2. background location (Android 10+)
 */
class LocationPermissionManager(private val context: Context) {

    fun coarseGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    fun fineGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    fun backgroundGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun activityRecognitionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED
    }

    /** The system-wide location switch is on. */
    fun systemLocationEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return try {
            manager.isLocationEnabled
        } catch (e: SecurityException) {
            false
        }
    }

    /** All permissions required for ongoing background tracking. */
    fun trackingAllowed(): Boolean =
        systemLocationEnabled() && (coarseGranted() || fineGranted()) && backgroundGranted()

    /** Permissions required to receive updates while the app is open. */
    fun foregroundTrackingAllowed(): Boolean =
        systemLocationEnabled() && (coarseGranted() || fineGranted())

    /** First request: coarse + fine location, plus activity recognition when available. */
    fun firstRequestPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        return permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }

    /** Second request: background location (only needed and possible on Android 10+). */
    fun backgroundRequestPermissions(): Array<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyArray()
        return arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
    }
}
