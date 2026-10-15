package nl.freshlytyped.homelocation

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import nl.freshlytyped.homelocation.databinding.ActivitySettingsBinding
import java.util.Locale

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
        }
    }

    private fun loadIntoForm() {
        binding.etPublishKey.setText(settings.publishKey)
        binding.etSubscribeKey.setText(settings.subscribeKey)
        binding.etApiKey.setText(settings.apiKey)
        binding.etChannel.setText(settings.channel)
        binding.swPreciseMode.isChecked = settings.preciseMode
        binding.etMaxAccuracy.setText(numberOf(settings.maxAccuracyMeters))
        binding.etMinInterval.setText(numberOf(settings.minPublishIntervalMs / 1000))
        binding.etMinDistance.setText(numberOf(settings.minDistanceMeters))
        binding.etMovementDetection.setText(numberOf(settings.movementDetectionDistanceMeters))
        binding.etMaxAge.setText(numberOf(settings.maxLocationAgeMs / 60_000))
        binding.swPeriodicFallback.isChecked = settings.periodicFallbackEnabled
        binding.etPeriodicInterval.setText(numberOf(settings.periodicFallbackIntervalMs / 60_000))
        binding.etBurst.setText(numberOf(settings.burstDurationMs / 60_000))
        binding.swBackgroundLocation.isChecked = settings.backgroundLocationEnabled
        binding.etBackgroundInterval.setText(numberOf(settings.backgroundScanIntervalMs / 1000))
    }

    private fun saveFromForm() {
        settings.publishKey = binding.etPublishKey.text.toString()
        settings.subscribeKey = binding.etSubscribeKey.text.toString()
        settings.apiKey = binding.etApiKey.text.toString()
        settings.channel = binding.etChannel.text.toString().ifBlank { LocationSettings.DEFAULT_CHANNEL }
        settings.preciseMode = binding.swPreciseMode.isChecked
        settings.maxAccuracyMeters =
            binding.etMaxAccuracy.text.toString().toFloatOrNull() ?: settings.maxAccuracyMeters
        settings.minPublishIntervalMs = parseSeconds(
            binding.etMinInterval.text.toString(), settings.minPublishIntervalMs
        )
        settings.minDistanceMeters =
            binding.etMinDistance.text.toString().toFloatOrNull() ?: settings.minDistanceMeters
        settings.movementDetectionDistanceMeters = binding.etMovementDetection.text.toString()
            .toFloatOrNull() ?: settings.movementDetectionDistanceMeters
        settings.maxLocationAgeMs = parseMinutes(
            binding.etMaxAge.text.toString(), settings.maxLocationAgeMs
        )
        settings.periodicFallbackEnabled = binding.swPeriodicFallback.isChecked
        settings.periodicFallbackIntervalMs = parseMinutes(
            binding.etPeriodicInterval.text.toString(), settings.periodicFallbackIntervalMs
        )
        settings.burstDurationMs =
            parseMinutes(binding.etBurst.text.toString(), settings.burstDurationMs)
        settings.backgroundLocationEnabled = binding.swBackgroundLocation.isChecked
        settings.backgroundScanIntervalMs = parseBackgroundSeconds(
            binding.etBackgroundInterval.text.toString(), settings.backgroundScanIntervalMs
        )
    }

    private fun numberOf(value: Number): String =
        String.format(Locale.getDefault(), "%.0f", value.toDouble())

    private fun parseSeconds(text: String, currentMs: Long): Long {
        val seconds = text.toLongOrNull() ?: return currentMs
        return (seconds * 1000).coerceIn(10_000L, 24 * 3600_000L)
    }

    private fun parseMinutes(text: String, currentMs: Long): Long {
        val minutes = text.toLongOrNull() ?: return currentMs
        return (minutes * 60_000).coerceIn(30_000L, 24 * 3600_000L)
    }

    private fun parseBackgroundSeconds(text: String, currentMs: Long): Long {
        val seconds = text.toLongOrNull() ?: return currentMs
        return (seconds * 1000).coerceIn(30_000L, 600_000L)
    }
}
