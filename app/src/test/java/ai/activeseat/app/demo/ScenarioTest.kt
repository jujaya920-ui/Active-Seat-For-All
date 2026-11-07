package ai.activeseat.app.demo

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Test

private fun row(t: Double, speed: Int, steering: Int, brake: Int, turn: String) =
    """{"t":$t,"speedKph":$speed,"steeringDeg":$steering,"brakePct":$brake,"acceleratorPct":0,"turnSignal":"$turn"}"""

fun scenarioJson(rows: List<String>) = """{
    "id": "test", "title": "Test", "mapImage": "maps/test.webp", "mapSize": [2000, 3000],
    "routePx": [[120, 800], [400, 780]], "routeLengthM": 2400,
    "turns": [{ "atM": 500, "type": "TURN_RIGHT", "description": "ramp" }],
    "frontEvents": [{ "atM": 1500, "type": "CONGESTION", "description": "jam" }],
    "cameras": [{ "atM": 900, "id": "cam-1", "type": "SPEED_CAMERA_FIX", "speedLimit": 50, "sectionAvrSpeed": 0 }],
    "cautionPoints": [{ "atM": 1200, "id": "cp-1", "type": "SPEED_BUMP" }],
    "timeline": [${rows.joinToString(",")}]
}"""

class ScenarioTest {
    @Test
    fun parsesCamerasAndCautionPoints() {
        val scenario = parseScenario(scenarioJson(listOf(row(0.0, 0, 0, 0, "Off"))))

        assertEquals(listOf(CameraMark(900.0, "cam-1", "SPEED_CAMERA_FIX", 50, 0)), scenario.cameras)
        assertEquals(listOf(CautionMark(1200.0, "cp-1", "SPEED_BUMP")), scenario.cautionPoints)
    }

    @Test
    fun treatsMissingCamerasAndCautionPointsAsNone() {
        val json = scenarioJson(listOf(row(0.0, 0, 0, 0, "Off")))
            .replace(Regex("""\s*"cameras": \[.*?],"""), "")
            .replace(Regex("""\s*"cautionPoints": \[.*?],"""), "")

        val scenario = parseScenario(json)

        assertEquals(emptyList<CameraMark>(), scenario.cameras)
        assertEquals(emptyList<CautionMark>(), scenario.cautionPoints)
    }

    @Test
    fun parsesContractFormat() {
        val scenario = parseScenario(scenarioJson(listOf(row(0.0, 0, 0, 0, "Off"), row(0.2, 30, 40, 10, "BlinkLeft"))))

        assertEquals("test", scenario.id)
        assertEquals(listOf(120 to 800, 400 to 780), scenario.routePx)
        assertEquals(2400.0, scenario.routeLengthM, 0.0)
        assertEquals(RouteMark(500.0, "TURN_RIGHT", "ramp"), scenario.turns.single())
        assertEquals(RouteMark(1500.0, "CONGESTION", "jam"), scenario.frontEvents.single())
        assertEquals(ScenarioFrame(0.2, 30.0, 40.0, 10.0, 0.0, "BlinkLeft"), scenario.timeline[1])
        assertEquals(0.2, scenario.durationSec, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownTurnMode() {
        parseScenario(scenarioJson(listOf(row(0.0, 0, 0, 0, "Left"))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsortedTimeline() {
        parseScenario(scenarioJson(listOf(row(1.0, 0, 0, 0, "Off"), row(0.0, 0, 0, 0, "Off"))))
    }

    @Test(expected = JSONException::class)
    fun rejectsMissingField() {
        parseScenario("""{"id":"x"}""")
    }

    @Test
    fun steeringIsNegatedForConsole() {
        val left = ScenarioFrame(0.0, 60.4, 40.0, 12.6, 5.0, "Off")
        val right = left.copy(steeringDeg = -125.0)

        assertEquals(ConsoleControls(speed = 60, accelerator = 5, brake = 13, steering = -40), left.toConsoleControls())
        assertEquals(125, right.toConsoleControls().steering)
    }

    @Test
    fun frameAtInterpolatesBetweenSparseRows() {
        val scenario = parseScenario(scenarioJson(listOf(row(0.0, 0, 0, 0, "Off"), row(1.0, 50, 100, 0, "BlinkLeft"))))

        val mid = scenario.frameAt(0.4)

        assertEquals(20.0, mid.speedKph, 1e-9)
        assertEquals(40.0, mid.steeringDeg, 1e-9)
        assertEquals("Off", mid.turnSignal)
        assertEquals("BlinkLeft", scenario.frameAt(1.0).turnSignal)
        assertEquals(50.0, scenario.frameAt(9.0).speedKph, 0.0)
    }

    @Test
    fun frameAtReturnsExactRowsDespiteFloatingPointTime() {
        val scenario = parseScenario(scenarioJson(listOf(row(0.0, 0, 0, 0, "Off"), row(0.6, 7, 0, 0, "Off"))))

        assertEquals(7.0, scenario.frameAt(3 * 200 / 1000.0).speedKph, 0.0)
    }
}
