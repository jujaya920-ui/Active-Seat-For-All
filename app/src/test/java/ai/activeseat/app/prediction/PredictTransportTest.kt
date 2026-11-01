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

    @Test
    fun `response keeps strength and every direction's probability per axis`() {
        val prediction = parsePredictResponse(
            """{"predictions":[{"horizonSec":2,
              "lateral":{"value":"right","probability":0.9,"confidence":0.8,"strength":0.73,
                "probabilities":{"left":0,"right":0.9,"straight":0.1}},
              "longitudinal":{"value":"brake","probability":0.7,"confidence":0.6,"strength":0.4,
                "probabilities":{"accelerate":0.1,"brake":0.7,"steady":0.2}},
              "hint":0.9}],"serverMs":210}""",
        ).single()
        assertEquals("right", prediction.lateral.value)
        assertEquals(0.73, prediction.lateral.strength, 1e-9)
        assertEquals(mapOf("left" to 0.0, "right" to 0.9, "straight" to 0.1), prediction.lateral.probabilities)
        assertEquals(0.4, prediction.longitudinal.strength, 1e-9)
    }
}
