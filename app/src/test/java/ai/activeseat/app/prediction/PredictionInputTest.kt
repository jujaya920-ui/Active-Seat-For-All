package ai.activeseat.app.prediction

import ai.activeseat.app.vehicle.SignalSample
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionInputTest {
    private fun sample(ageMs: Long) = SignalSample(ageMs, 42.5, 3.5, false, "left")
    private val fullWindow = listOf(800L, 600L, 400L, 200L, 0L).map(::sample)

    private class FixedNav(val turn: NavItem?, val event: NavItem?) : NavContext {
        override fun nextTurn() = turn
        override fun frontEvent() = event
    }

    @Test
    fun serializesContractShape() {
        val nav = FixedNav(NavItem("TURN_LEFT", 80), NavItem("CONGESTION", 200))

        val json = PredictionAssembler({ fullWindow }, nav).assemble()!!.toJson()

        assertEquals(setOf("signals", "nextTurn", "frontEvent", "horizonsSec"), json.keySet())
        val signals = json.getJSONArray("signals")
        assertEquals(5, signals.length())
        val oldest = signals.getJSONObject(0)
        assertEquals(setOf("ageMs", "speedKph", "steeringDeg", "brake", "turnSignal"), oldest.keySet())
        assertEquals(800L, oldest.getLong("ageMs"))
        assertEquals(42.5, oldest.getDouble("speedKph"), 0.0)
        assertEquals(3.5, oldest.getDouble("steeringDeg"), 0.0)
        assertEquals(false, oldest.getBoolean("brake"))
        assertEquals("left", oldest.getString("turnSignal"))
        assertEquals(0L, signals.getJSONObject(4).getLong("ageMs"))
        assertEquals("TURN_LEFT", json.getJSONObject("nextTurn").getString("type"))
        assertEquals(80, json.getJSONObject("nextTurn").getInt("distanceM"))
        assertEquals(200, json.getJSONObject("frontEvent").getInt("distanceM"))
        assertEquals(2, json.getJSONArray("horizonsSec").length())
        assertEquals(2.0, json.getJSONArray("horizonsSec").getDouble(0), 0.0)
    }

    @Test
    fun missingNavIsExplicitNull() {
        val json = PredictionAssembler({ fullWindow }, NoNavContext).assemble()!!.toJson()
        val text = JSONObject(json.toString())

        assertTrue(text.has("nextTurn") && text.isNull("nextTurn"))
        assertTrue(text.has("frontEvent") && text.isNull("frontEvent"))
    }

    @Test
    fun partialWindowProducesNoInput() {
        assertNull(PredictionAssembler({ fullWindow.take(4) }, NoNavContext).assemble())
    }

    @Test
    fun blankServerSettingsReportNotConfigured() {
        val input = PredictionInput(fullWindow, null, null)

        assertEquals(PredictResult.NotConfigured, PredictClient("", "").predict(input))
        assertEquals(PredictResult.NotConfigured, PredictClient("https://example.invalid", "").predict(input))
    }

    @Test
    fun parsesContractResponse() {
        val body = """{ "predictions": [{ "horizonSec": 1.6,
            "lateral": { "value": "left", "probability": 0.8, "confidence": 0.7 },
            "longitudinal": { "value": "brake", "probability": 0.7, "confidence": 0.6 },
            "hint": 0.8 }], "serverMs": 210 }"""

        val prediction = parsePredictResponse(body).single()

        assertEquals(Prediction(1.6, "left", 0.8, "brake", 0.7, 0.8), prediction)
    }
}
