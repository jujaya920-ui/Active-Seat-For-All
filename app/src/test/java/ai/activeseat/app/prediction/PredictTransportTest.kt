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
    fun `response keeps every push direction`() {
        val push = parsePredictResponse(
            """{"horizonSec":2,"push":{
              "forward":{"pOn":0.88,"level":2,"levelMean":2.08},
              "backward":{"pOn":0.04,"level":1,"levelMean":1.35},
              "left":{"pOn":0.06,"level":1,"levelMean":1.25},
              "right":{"pOn":0.62,"level":3,"levelMean":2.6}},"serverMs":512}""",
        )
        assertEquals(PushPrediction(0.88, 2, 2.08), push.forward)
        assertEquals(PushPrediction(0.04, 1, 1.35), push.backward)
        assertEquals(PushPrediction(0.06, 1, 1.25), push.left)
        assertEquals(PushPrediction(0.62, 3, 2.6), push.right)
        assertEquals(2.0, push.horizonSec, 0.0)
    }

    @Test(expected = org.json.JSONException::class)
    fun `missing direction is rejected`() {
        parsePredictResponse("""{"horizonSec":2,"push":{"forward":{"pOn":0.5,"level":1,"levelMean":1.0}}}""")
    }
}
