package nl.freshlytyped.homelocation

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiagnosticLogger(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var lastPruneMs = 0L

    @Synchronized
    fun record(event: String) {
        val now = System.currentTimeMillis()
        file.appendText("$now|${format.format(Date(now))}|${event.replace('|', '/')}\n")
        if (now - lastPruneMs >= PRUNE_INTERVAL_MS || file.length() > MAX_FILE_BYTES) {
            lastPruneMs = now
            prune(now)
        }
    }

    private fun prune(now: Long) {
        val cutoff = now - RETENTION_WINDOW_MS
        var retained = if (file.exists()) {
            file.readLines().filter { line ->
                line.substringBefore('|').toLongOrNull()?.let { it >= cutoff } ?: false
            }
        } else {
            emptyList()
        }
        // Hard size cap: keep the newest half when retention alone is not enough.
        var text = retained.joinToString("\n", postfix = "\n")
        while (text.toByteArray().size > MAX_FILE_BYTES && retained.isNotEmpty()) {
            retained = retained.subList(retained.size / 2, retained.size)
            text = retained.joinToString("\n", postfix = "\n")
        }
        file.writeText(text)
    }

    @Synchronized
    fun snapshot(): String = if (file.exists()) file.readText() else ""

    fun deviceSummary(): String = buildString {
        appendLine("model=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android=${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
        appendLine("package=nl.freshlytyped.homelocation")
    }

    companion object {
        private const val FILE_NAME = "diagnostics.log"
        private const val RETENTION_WINDOW_MS = 24 * 60 * 60 * 1000L
        private const val PRUNE_INTERVAL_MS = 60 * 60 * 1000L
        private const val MAX_FILE_BYTES = 512 * 1024
    }
}
