package nl.freshlytyped.homelocation

import android.app.Application

/**
 * Holds the per-process [LocationCoordinator] singleton so that the
 * activity, receivers and callbacks all share one instance (and one
 * Wi-Fi callback).
 */
class App : Application() {

    /**
     * Lazy: [Application] is constructed before [attachBaseContext], so the
     * context must not be touched in a property initializer.
     */
    val locationSettings by lazy { LocationSettings(this) }

    val locationCoordinator: LocationCoordinator by lazy {
        LocationCoordinator(
            context = this,
            settings = locationSettings,
            permissions = LocationPermissionManager(this),
            publisher = LocationPublisher(locationSettings)
        )
    }
}
