package ai.activeseat.app.motion

import ai.activeseat.app.demo.Scenario
import ai.activeseat.app.demo.parseScenario
import ai.activeseat.app.demo.toNavRoute
import ai.activeseat.app.demo.toPlannedRoute
import ai.activeseat.app.nav.FakeNaviHelper
import ai.activeseat.app.nav.Guidance
import ai.activeseat.app.nav.GuidancePhase
import ai.activeseat.app.vehicle.TurnSignal
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.activeseat.app.vehicle.VehicleSignal
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

const val STEP_MS = 200L
private const val KPH_TO_MPS = 1 / 3.6

/**
 * Ground truth: a motion a passenger feels. |a| at least [FEEL_MPS2] for at least [MIN_EVENT_MS].
 * 0.6 m/s² sits above the generator's lane-keeping nudges (at most 0.5 m/s²) and is the
 * project's existing longitudinal label threshold.
 */
const val FEEL_MPS2 = 0.6
const val MIN_EVENT_MS = 1000L

/** A hint this long before a motion still counts as for that motion. */
const val EARLY_WINDOW_MS = 4000L

val SCENARIO_DIR = File("src/main/assets/scenarios")

fun loadScenarios(): List<Scenario> = SCENARIO_DIR.listFiles { f -> f.extension == "json" && f.name != "smoke.json" }!!
    .sortedBy { it.name }
    .map { parseScenario(it.readText()) }

data class Tick(
    val ms: Long,
    val truthLateral: Double,
    val truthLongitudinal: Double,
    val out: MotionOutput,
    val phase: GuidancePhase = GuidancePhase.GUIDING,
    val progressM: Double = 0.0,
)

/**
 * Plays a scenario as the app would see it on the emulator: the console takes whole km/h and
 * degrees, the sampler ticks every 0.2 s, and the fake navigation reports once a second through
 * the same guidance session as the app, reroute included. Without `navigation` the engine hears
 * no reports at all.
 */
fun replay(scenario: Scenario, config: MotionConfig, navigation: Boolean = true): List<Tick> {
    val guidance = Guidance(scenario.toNavRoute(), scenario.toPlannedRoute())
    val navi = FakeNaviHelper(guidance)
    val feed = NaviFeed()
    val listener = JvmListener(feed)
    val engine = MotionEngine(config)
    val ticks = mutableListOf<Tick>()
    val endMs = (scenario.durationSec * 1000).toLong()
    var ms = 0L
    while (ms <= endMs) {
        val t = ms / 1000.0
        val frame = scenario.frameAt(t)
        val speedKph = frame.speedKph.roundToInt().toDouble()
        guidance.onSpeed(speedKph, ms)
        if (navigation && ms % FakeNaviHelper.EMIT_INTERVAL_MS == 0L) navi.emit(speedKph, listener)
        val signal = VehicleSignal(
            speedKph = speedKph,
            steeringDeg = frame.steeringDeg.roundToInt().toDouble(),
            brake = frame.brakePct > 0,
            turnSignal = when (frame.turnSignal) {
                "BlinkLeft" -> TurnSignal.LEFT
                "BlinkRight" -> TurnSignal.RIGHT
                else -> TurnSignal.OFF
            },
        )
        val out = engine.step(signal, ms, feed.snapshot)
        ticks += Tick(ms, truthLateral(scenario, t), truthLongitudinal(scenario, t), out, guidance.phase, guidance.state().progressM)
        ms += STEP_MS
    }
    return ticks
}

/** Passes the reports to the feed. The SDK's default onReRouteEnded logs through android.util.Log, absent on the JVM. */
private class JvmListener(private val feed: NaviFeed) : NaviHelperEventListener {
    override fun onReRouteEnded() = Unit

    override fun onTBTInfo(p0: List<TBTInfo>) = feed.onTBTInfo(p0)

    override fun onCameraAlert(p0: List<CameraAlert>) = feed.onCameraAlert(p0)

    override fun onCautionPointInfo(p0: List<CautionPoint>) = feed.onCautionPointInfo(p0)
}

private fun truthLateral(scenario: Scenario, t: Double): Double {
    val frame = scenario.frameAt(t)
    val v = frame.speedKph * KPH_TO_MPS
    return v * v * tan(Math.toRadians(frame.steeringDeg / 14.0)) / 2.9
}

private fun truthLongitudinal(scenario: Scenario, t: Double): Double {
    val a = scenario.frameAt((t - 0.2).coerceAtLeast(0.0)).speedKph
    val b = scenario.frameAt((t + 0.2).coerceAtMost(scenario.durationSec)).speedKph
    return (b - a) * KPH_TO_MPS / 0.4
}

/** `dir` is +1 or -1 on the axis. */
data class Span(val dir: Int, val fromMs: Long, val toMs: Long)

fun spans(ticks: List<Tick>, sign: (Tick) -> Int): List<Span> {
    val out = mutableListOf<Span>()
    var open: Span? = null
    for (tick in ticks) {
        val d = sign(tick)
        if (open != null && d != open.dir) {
            out += open.copy(toMs = tick.ms - STEP_MS)
            open = null
        }
        if (open == null && d != 0) open = Span(d, tick.ms, tick.ms)
    }
    open?.let { out += it.copy(toMs = ticks.last().ms) }
    return out
}

fun truthSpans(ticks: List<Tick>, value: (Tick) -> Double) =
    spans(ticks) { t -> value(t).let { if (abs(it) >= FEEL_MPS2) (if (it > 0) 1 else -1) else 0 } }
        .filter { it.toMs - it.fromMs + STEP_MS >= MIN_EVENT_MS }

data class AxisScore(
    val events: Int,
    val hits: Int,
    /** Hint start minus motion start, negative when early, one per hit. */
    val leadsMs: List<Long>,
    /** Leads of the hits whose cue began as a prediction. */
    val predictedLeadsMs: List<Long>,
    val falseMs: Long,
    val flickers: Int,
    val onMs: Long,
) {
    operator fun plus(o: AxisScore) = AxisScore(
        events + o.events, hits + o.hits, leadsMs + o.leadsMs, predictedLeadsMs + o.predictedLeadsMs,
        falseMs + o.falseMs, flickers + o.flickers, onMs + o.onMs,
    )

    companion object {
        val ZERO = AxisScore(0, 0, emptyList(), emptyList(), 0, 0, 0)
    }
}

/**
 * A hint counts for a motion when it is on in the same direction at some point from
 * [EARLY_WINDOW_MS] before the motion to its end. Hint time with no such motion is false time.
 */
fun score(
    ticks: List<Tick>,
    truth: (Tick) -> Double,
    hintDir: (Tick) -> Int,
    source: (Tick) -> CueSource,
): AxisScore {
    val events = truthSpans(ticks, truth)
    val hints = spans(ticks, hintDir)
    var hits = 0
    val leads = mutableListOf<Long>()
    val predicted = mutableListOf<Long>()
    for (e in events) {
        val hint = hints.firstOrNull { it.dir == e.dir && it.toMs >= e.fromMs - EARLY_WINDOW_MS && it.fromMs <= e.toMs } ?: continue
        hits++
        val startMs = maxOf(hint.fromMs, e.fromMs - EARLY_WINDOW_MS)
        leads += startMs - e.fromMs
        val startTick = ticks.first { it.ms == hint.fromMs }
        if (source(startTick) == CueSource.PREDICTED) predicted += startMs - e.fromMs
    }
    var falseMs = 0L
    var onMs = 0L
    for (tick in ticks) {
        val d = hintDir(tick)
        if (d == 0) continue
        onMs += STEP_MS
        val explained = events.any { it.dir == d && tick.ms >= it.fromMs - EARLY_WINDOW_MS && tick.ms <= it.toMs + 1000 }
        if (!explained) falseMs += STEP_MS
    }
    val flickers = hints.count { it.toMs - it.fromMs + STEP_MS < 1000 }
    return AxisScore(events.size, hits, leads, predicted, falseMs, flickers, onMs)
}

fun lateralScore(ticks: List<Tick>) = score(
    ticks, { it.truthLateral },
    { when (it.out.hint.lateral) { ai.activeseat.app.hint.LateralCue.LEFT -> 1; ai.activeseat.app.hint.LateralCue.RIGHT -> -1; else -> 0 } },
    { it.out.lateralSource },
)

fun longitudinalScore(ticks: List<Tick>) = score(
    ticks, { it.truthLongitudinal },
    { when (it.out.hint.longitudinal) { ai.activeseat.app.hint.LongitudinalCue.ACCELERATE -> 1; ai.activeseat.app.hint.LongitudinalCue.BRAKE -> -1; else -> 0 } },
    { it.out.longitudinalSource },
)

/** Brake events only, the ones a camera or caution point can predict. */
fun brakeScore(ticks: List<Tick>) = score(
    ticks, { minOf(it.truthLongitudinal, 0.0) },
    { if (it.out.hint.longitudinal == ai.activeseat.app.hint.LongitudinalCue.BRAKE) -1 else 0 },
    { it.out.longitudinalSource },
)

fun median(values: List<Long>): Long? = values.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

fun AxisScore.line(): String {
    val lead = median(leadsMs)?.let { "%+.1f".format(it / 1000.0) } ?: "-"
    val predLead = median(predictedLeadsMs)?.let { "%+.1f".format(it / 1000.0) } ?: "-"
    val late = leadsMs.count { it > 0 }
    return "hit %3d/%-3d late %3d medLead %5s s  pred %3d medPredLead %5s s  false %6.1f s (%4.1f%% of on)  flicker %3d".format(
        hits, events, late, lead, predictedLeadsMs.size, predLead, falseMs / 1000.0,
        if (onMs == 0L) 0.0 else 100.0 * falseMs / onMs, flickers,
    )
}
