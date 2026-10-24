package nl.freshlytyped.homelocation

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import nl.freshlytyped.homelocation.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val settings get() = (application as App).locationSettings

    override fun onStart() {
        super.onStart()
        (application as App).locationCoordinator.onAppForegrounded()
    }

    override fun onStop() {
        (application as App).locationCoordinator.onAppBackgrounded()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        loadIntoForm()
        binding.btnSave.setOnClickListener {
            saveFromForm()
            binding.tvSaved.visibility = View.VISIBLE
            binding.tvSaved.postDelayed({ binding.tvSaved.visibility = View.GONE }, 2000)
            updateCachedZonesDisplay()
        }
        binding.btnRefreshZones.setOnClickListener {
            (application as App).locationCoordinator.refreshZones()
            binding.tvZonesRefreshed.visibility = View.VISIBLE
            binding.tvZonesRefreshed.postDelayed({
                binding.tvZonesRefreshed.visibility = View.GONE
                updateCachedZonesDisplay()
            }, 2000)
        }
    }

    private fun loadIntoForm() {
        binding.etPublishKey.setText(settings.publishKey)
        binding.etSubscribeKey.setText(settings.subscribeKey)
        binding.etApiKey.setText(settings.apiKey)
        binding.etChannel.setText(settings.channel)
        binding.etDeviceName.setText(settings.deviceName)
        binding.swPreciseMode.isChecked = settings.preciseMode
        binding.swGeofencing.isChecked = settings.geofencingEnabled
        updateCachedZonesDisplay()
    }

    private fun updateCachedZonesDisplay() {
        val coordinator = (application as App).locationCoordinator
        val zoneNames = coordinator.getCachedZoneNames()
        if (zoneNames.isNotEmpty()) {
            binding.tvCachedZones.text = getString(
                R.string.settings_cached_zones_label,
                zoneNames.joinToString()
            )
            binding.tvCachedZones.visibility = View.VISIBLE
        } else {
            binding.tvCachedZones.visibility = View.GONE
        }
    }

    private fun saveFromForm() {
        settings.publishKey = binding.etPublishKey.text.toString()
        settings.subscribeKey = binding.etSubscribeKey.text.toString()
        settings.apiKey = binding.etApiKey.text.toString()
        settings.channel = binding.etChannel.text.toString().ifBlank { LocationSettings.DEFAULT_CHANNEL }
        settings.deviceName = binding.etDeviceName.text.toString()
        settings.preciseMode = binding.swPreciseMode.isChecked
        settings.geofencingEnabled = binding.swGeofencing.isChecked
    }
}
