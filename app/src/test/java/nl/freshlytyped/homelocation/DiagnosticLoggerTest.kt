package nl.freshlytyped.homelocation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiagnosticLoggerTest {

    private lateinit var logFile: File
    private lateinit var logger: DiagnosticLogger

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        logFile = File(context.filesDir, "diagnostics.log")
        logFile.delete()
        logger = DiagnosticLogger(context)
    }

    @Test
    fun `record retains entries from the last 24 hours only`() {
        val now = System.currentTimeMillis()
        logFile.writeText(
            "${now - 25 * 60 * 60 * 1000L}|old|older than 24 hours\n" +
                "${now - 23 * 60 * 60 * 1000L}|recent|within 24 hours\n"
        )

        logger.record("new event")

        val snapshot = logger.snapshot()
        assertFalse(snapshot.contains("older than 24 hours"))
        assertTrue(snapshot.contains("within 24 hours"))
        assertTrue(snapshot.contains("new event"))
    }

    @Test
    fun `a single shared instance survives concurrent writers without losing lines`() {
        val threads = (0 until 8).map { t ->
            Thread {
                repeat(25) { i -> logger.record("concurrent-$t-$i") }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val snapshot = logger.snapshot()
        val missing = (0 until 8).flatMap { t -> (0 until 25).map { i -> "concurrent-$t-$i" } }
            .count { line -> !snapshot.contains(line) }
        assertEquals("concurrent writes to one instance must not drop lines", 0, missing)
    }
}
