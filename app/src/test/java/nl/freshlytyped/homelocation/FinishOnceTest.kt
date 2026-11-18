package nl.freshlytyped.homelocation

import org.junit.Assert.assertEquals
import org.junit.Test

class FinishOnceTest {

    @Test
    fun `finishes only once when completion and timeout both fire`() {
        var finishes = 0
        val finish = FinishOnce { finishes++ }

        finish.run()
        finish.run()

        assertEquals(1, finishes)
    }
}
