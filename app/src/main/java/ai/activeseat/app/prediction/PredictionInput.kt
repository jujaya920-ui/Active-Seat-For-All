package ai.activeseat.app.prediction

import ai.activeseat.app.vehicle.SAMPLE_WINDOW_SIZE
import ai.activeseat.app.vehicle.SignalSample
import org.json.JSONArray
import org.json.JSONObject

val DEFAULT_HORIZONS_SEC = listOf(2.0, 2.4)

/** `type` carries NaviHelper's value unchanged. */
data class NavItem(val type: String, val distanceM: Int)

/** What lies ahead on the route. Must never reveal future driving actions. */
interface NavContext {
    fun nextTurn(): NavItem?
    fun frontEvent(): NavItem?
}

object NoNavContext : NavContext {
    override fun nextTurn(): NavItem? = null
    override fun frontEvent(): NavItem? = null
}

data class PredictionInput(
    val signals: List<SignalSample>,
    val nextTurn: NavItem?,
    val frontEvent: NavItem?,
    val horizonsSec: List<Double> = DEFAULT_HORIZONS_SEC,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("signals", JSONArray().apply { signals.forEach { put(it.toJson()) } })
        put("nextTurn", nextTurn.toJsonOrNull())
        put("frontEvent", frontEvent.toJsonOrNull())
        put("horizonsSec", JSONArray().apply { horizonsSec.forEach { put(it) } })
    }
}

private fun SignalSample.toJson() = JSONObject().apply {
    put("ageMs", ageMs)
    put("speedKph", speedKph)
    put("steeringDeg", steeringDeg)
    put("brake", brake)
    put("turnSignal", turnSignal)
}

private fun NavItem?.toJsonOrNull(): Any = this?.let {
    JSONObject().put("type", it.type).put("distanceM", it.distanceM)
} ?: JSONObject.NULL

/** Builds the contract input from vehicle signals and navigation only. */
class PredictionAssembler(
    private val window: () -> List<SignalSample>,
    private val nav: NavContext,
) {
    /** Null until the sampler holds a full window. */
    fun assemble(): PredictionInput? {
        val signals = window()
        if (signals.size < SAMPLE_WINDOW_SIZE) return null
        return PredictionInput(signals, nav.nextTurn(), nav.frontEvent())
    }
}
