package nl.freshlytyped.homelocation

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import nl.freshlytyped.homelocation.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app get() = application as App
    private val permissions by lazy { LocationPermissionManager(this) }

    private var awaitingBackgroundPermission = false
    private var startTrackingAfterPermission = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (startTrackingAfterPermission) {
                startTrackingAfterPermission = false
                LocationTrackingService.start(this)
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            onPermissionsResult(it)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.btnGrantPermissions.setOnClickListener {
            requestTrackingPermissions()
        }
        binding.btnToggleTracking.setOnClickListener {
            if (app.locationSettings.trackingEnabled) {
                stopService(Intent(this, LocationTrackingService::class.java))
            } else if (permissions.foregroundTrackingAllowed()) {
                if (app.locationSettings.isConfigured()) {
                    if (permissions.trackingAllowed()) {
                        startTracking()
                    } else {
                        startTrackingAfterPermission = true
                        requestBackgroundPermission()
                    }
                } else {
                    Toast.makeText(
                        this,
                        R.string.tracking_requires_configuration,
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                requestTrackingPermissions()
            }
            updateUi()
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-assert registrations while the app is open (also recovers from
        // app updates and permission changes).
        app.locationCoordinator.ensureRegistered()
        updateUi()
    }

    override fun onStart() {
        super.onStart()
        app.locationCoordinator.onAppForegrounded()
    }

    override fun onStop() {
        app.locationCoordinator.onAppBackgrounded()
        super.onStop()
    }

    private fun requestTrackingPermissions() {
        val first = permissions.firstRequestPermissions()
        if (first.isNotEmpty()) {
            permissionLauncher.launch(first)
        } else if (permissions.backgroundRequestPermissions().isNotEmpty()) {
            requestBackgroundPermission()
        } else {
            updateUi()
        }
    }

    private fun requestBackgroundPermission() {
        val background = permissions.backgroundRequestPermissions()
        if (background.isNotEmpty()) {
            awaitingBackgroundPermission = true
            permissionLauncher.launch(background)
        }
    }

    private fun startTracking() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !permissions.notificationsGranted()
        ) {
            startTrackingAfterPermission = true
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            LocationTrackingService.start(this)
        }
    }

    private fun onPermissionsResult(granted: Map<String, Boolean>) {
        if (awaitingBackgroundPermission) {
            awaitingBackgroundPermission = false
            if (permissions.trackingAllowed() && startTrackingAfterPermission) {
                startTrackingAfterPermission = false
                startTracking()
            }
        } else if (permissions.firstRequestPermissions().isEmpty()) {
            requestBackgroundPermission()
        }
        updateUi()
    }

    private fun updateUi() {
        val settings = app.locationSettings

        if (settings.trackingEnabled) {
            binding.tvTrackingStatus.text = getString(R.string.tracking_on)
            binding.tvMovementState.visibility = View.VISIBLE
            val stateLabel = getString(
                if (settings.movementState == MovementState.MOVING) {
                    R.string.state_moving
                } else {
                    R.string.state_stationary
                }
            )
            binding.tvMovementState.text = getString(R.string.movement_state, stateLabel)
            binding.btnToggleTracking.setText(R.string.btn_stop_tracking)
            binding.btnGrantPermissions.visibility =
                if (permissions.backgroundGranted()) View.GONE else View.VISIBLE
        } else {
            binding.tvTrackingStatus.text = getString(R.string.tracking_off)
            binding.tvMovementState.visibility = View.GONE
            binding.btnToggleTracking.setText(R.string.btn_enable_tracking)
            binding.btnGrantPermissions.visibility =
                if (permissions.foregroundTrackingAllowed() && permissions.backgroundGranted()) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
        }

        binding.tvLastPublished.text = formatLastPublished(settings)
        binding.tvPermissionStatus.text = buildPermissionStatus()
        binding.tvPubnubStatus.text = getString(
            if (settings.isConfigured()) R.string.pubnub_configured else R.string.pubnub_not_configured
        )
        binding.tvPubnubStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (settings.isConfigured()) R.color.success else R.color.error
            )
        )
    }

    private fun buildPermissionStatus(): String {
        val lines = mutableListOf(
            rowLabel(R.string.perm_system_location,
                if (permissions.systemLocationEnabled()) R.string.perm_granted else R.string.perm_off),
            rowLabel(R.string.perm_precise,
                if (permissions.fineGranted()) R.string.perm_granted else R.string.perm_denied)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            lines.add(
                rowLabel(
                    R.string.perm_background,
                    if (permissions.backgroundGranted()) R.string.perm_granted else R.string.perm_denied
                )
            )
        } else {
            lines.add(rowLabel(R.string.perm_background, R.string.perm_not_applicable))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            lines.add(
                rowLabel(
                    R.string.perm_activity,
                    if (permissions.activityRecognitionGranted()) R.string.perm_granted else R.string.perm_denied
                )
            )
        }
        return lines.joinToString("\n")
    }

    private fun rowLabel(labelRes: Int, stateRes: Int): String =
        "• ${getString(labelRes)}: ${getString(stateRes)}"

    private fun formatLastPublished(settings: LocationSettings): String {
        val publishedAt = settings.lastPublishedTimeMs
        if (publishedAt <= 0L) return getString(R.string.last_published_never)
        val accuracy = String.format(Locale.getDefault(), "%.0f", settings.lastPublishedAccuracyMeters)
        return getString(R.string.last_published, relativeTime(publishedAt), accuracy)
    }

    private fun relativeTime(timestampMs: Long): String {
        val seconds = (System.currentTimeMillis() - timestampMs) / 1000
        return when {
            seconds < 60 -> "just now"
            seconds < 3600 -> String.format(Locale.getDefault(), "%d min ago", seconds / 60)
            seconds < 86_400 -> String.format(Locale.getDefault(), "%d h ago", seconds / 3600)
            else -> String.format(Locale.getDefault(), "%d d ago", seconds / 86_400)
        }
    }
}
