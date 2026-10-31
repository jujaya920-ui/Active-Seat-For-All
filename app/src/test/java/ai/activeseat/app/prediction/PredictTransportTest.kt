package ai.activeseat.app.prediction

import org.junit.Assert.assertEquals
import org.junit.Test

class PredictTransportTest {
    @Test
    fun `next call waits only for the rest of the interval`() {
        assertEquals(1000L, nextCallDelayMs(0))
        assertEquals(480L, nextCallDelayMs(520))
        assertEquals(0L, nextCallDelayMs(1000))
        assertEquals(0L, nextCallDelayMs(1700))
    }
}
