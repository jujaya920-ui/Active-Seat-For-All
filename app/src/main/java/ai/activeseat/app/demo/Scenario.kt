package ai.activeseat.app.demo

import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

const val TURN_OFF = "Off"
private val TURN_MODES = setOf(TURN_OFF, "BlinkLeft", "BlinkRight")
private const val TIME_EPSILON = 1e-6

data class RouteMark(val atM: Double, val type: String, val description: String)

/** One timeline row. Steering is positive to the left, as everywhere inside the app. */
data class ScenarioFrame(
    val t: Double,
    val speedKph: Double,
    val steeringDeg: Double,
    val brakePct: Double,
    val acceleratorPct: Double,
    val turnSignal: String,
)

data class Scenario(
    val id: String,
    val title: String,
    val mapImage: String,
    val routePx: List<Pair<Int, Int>>,
    val routeLengthM: Double,
    val turns: List<RouteMark>,
    val frontEvents: List<RouteMark>,
    val timeline: List<ScenarioFrame>,
) {
    val durationSec: Double get() = timeline.last().t

    /** Linear between rows for analog values; the turn signal holds the earlier row's value. */
    fun frameAt(t: Double): ScenarioFrame {
        val nextIndex = timeline.indexOfFirst { it.t >= t - TIME_EPSILON }
        if (nextIndex < 0) return timeline.last()
        val b = timeline[nextIndex]
        if (nextIndex == 0 || b.t - t < TIME_EPSILON) return b
        val a = timeline[nextIndex - 1]
        val f = (t - a.t) / (b.t - a.t)
        return ScenarioFrame(
            t = t,
            speedKph = lerp(a.speedKph, b.speedKph, f),
            steeringDeg = lerp(a.steeringDeg, b.steeringDeg, f),
            brakePct = lerp(a.brakePct, b.brakePct, f),
            acceleratorPct = lerp(a.acceleratorPct, b.acceleratorPct, f),
            turnSignal = a.turnSignal,
        )
    }

    private fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f
}

/** The integer values `PUT /vehicle/controls` takes. Steering is positive to the right. */
data class ConsoleControls(val speed: Int, val accelerator: Int, val brake: Int, val steering: Int)

fun ScenarioFrame.toConsoleControls() = ConsoleControls(
    speed = speedKph.roundToInt(),
    accelerator = acceleratorPct.roundToInt(),
    brake = brakePct.roundToInt(),
    steering = -steeringDeg.roundToInt(),
)

fun parseScenario(json: String): Scenario {
    val root = JSONObject(json)
    val timeline = root.getJSONArray("timeline").objects().map { row ->
        val turnSignal = row.getString("turnSignal")
        require(turnSignal in TURN_MODES) { "unknown turnSignal '$turnSignal'" }
        ScenarioFrame(
            t = row.getDouble("t"),
            speedKph = row.getDouble("speedKph"),
            steeringDeg = row.getDouble("steeringDeg"),
            brakePct = row.getDouble("brakePct"),
            acceleratorPct = row.getDouble("acceleratorPct"),
            turnSignal = turnSignal,
        )
    }
    require(timeline.isNotEmpty()) { "timeline is empty" }
    require(timeline.zipWithNext().all { (a, b) -> a.t < b.t }) { "timeline must be sorted by t" }

    val route = root.getJSONArray("routePx")
    return Scenario(
        id = root.getString("id"),
        title = root.getString("title"),
        mapImage = root.getString("mapImage"),
        routePx = List(route.length()) { i ->
            val point = route.getJSONArray(i)
            point.getInt(0) to point.getInt(1)
        },
        routeLengthM = root.getDouble("routeLengthM"),
        turns = root.getJSONArray("turns").objects().map { it.toRouteMark() },
        frontEvents = root.getJSONArray("frontEvents").objects().map { it.toRouteMark() },
        timeline = timeline,
    )
}

private fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

private fun JSONObject.toRouteMark() =
    RouteMark(getDouble("atM"), getString("type"), getString("description"))
