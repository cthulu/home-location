package nl.freshlytyped.homelocation

import android.app.Application

/**
 * Holds the per-process [LocationCoordinator] singleton so that the
 * activity, receivers and callbacks all share one instance (and one
 * Wi-Fi callback).
 */
class App : Application() {

    val locationSettings = LocationSettings(this)

    val locationCoordinator: LocationCoordinator by lazy {
        LocationCoordinator(
            context = this,
            settings = locationSettings,
            permissions = LocationPermissionManager(this),
            publisher = LocationPublisher(locationSettings)
        )
    }
}
