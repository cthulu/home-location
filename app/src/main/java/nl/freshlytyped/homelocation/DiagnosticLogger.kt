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

    @Synchronized
    fun record(event: String) {
        val now = System.currentTimeMillis()
        val cutoff = now - ONE_HOUR_MS
        val retained = if (file.exists()) {
            file.readLines().filter { line ->
                line.substringBefore('|').toLongOrNull()?.let { it >= cutoff } ?: false
            }
        } else {
            emptyList()
        }
        file.writeText((retained + "$now|${format.format(Date(now))}|$event").joinToString("\n", postfix = "\n"))
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
        private const val ONE_HOUR_MS = 60 * 60 * 1000L
    }
}
