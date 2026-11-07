package ai.activeseat.app.motion

import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.hint.LongitudinalCue
import ai.activeseat.app.vehicle.TurnSignal
import ai.activeseat.app.vehicle.VehicleSignal
import ai.pleos.playground.navi.constants.CameraType
import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.TurnType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.tan

private const val KPH_TO_MPS = 1 / 3.6
private const val MAX_LEVEL = 4

enum class CueSource { NONE, PREDICTED, MEASURED }

/** One tick's decision, with the raw measurements for display and replay. */
data class MotionOutput(
    val hint: Hint = Hint(),
    val lateralSource: CueSource = CueSource.NONE,
    val longitudinalSource: CueSource = CueSource.NONE,
    val lateralMps2: Double = 0.0,
    val longitudinalMps2: Double = 0.0,
)

/**
 * Decides the seat hint every 0.2 s from vehicle signals and NaviHelper reports, on the device.
 * A measured model reacts to motion already under way; a predicted model starts the cue before
 * it from what lies ahead. Each axis takes measured over predicted, so a prediction only fills
 * the time before the motion shows. Not thread-safe: call from one coroutine.
 *
 * Signs: lateral and steering are positive to the left, longitudinal positive when speeding up.
 */
class MotionEngine(private val config: MotionConfig = MotionConfig()) {
    private val speeds = ArrayDeque<Pair<Long, Double>>()
    private var lastMs: Long? = null
    private var odometerM = 0.0

    // Each report's distances are as of its arrival, counted from the odometer then.
    private var tbtSeen: Any? = null
    private var tbtOdometerM = 0.0
    private val pointOdometerM = mutableMapOf<String, Double>()
    private var pointsSeen: Pair<Any?, Any?> = null to null

    private var measuredLateral = 0
    private var measuredLongitudinal = 0

    private var lastTurnKey: String? = null
    private var lastTurnRemainingM: Double? = null
    private var turnGeneration = 0
    private val turn = Prediction()
    private val slowdown = Prediction()

    private val lateralOut = Held()
    private val longitudinalOut = Held()

    fun reset() {
        speeds.clear()
        lastMs = null
        odometerM = 0.0
        tbtSeen = null
        pointOdometerM.clear()
        pointsSeen = null to null
        measuredLateral = 0
        measuredLongitudinal = 0
        lastTurnKey = null
        lastTurnRemainingM = null
        turn.clear()
        slowdown.clear()
        lateralOut.clear()
        longitudinalOut.clear()
    }

    /** `drive` is false in P, N and R: everything turns off at once and starts over. */
    fun step(signal: VehicleSignal, nowMs: Long, nav: NavSnapshot, drive: Boolean = true): MotionOutput {
        if (!drive) {
            reset()
            return MotionOutput()
        }
        val v = max(signal.speedKph, 0.0) * KPH_TO_MPS
        lastMs?.let { odometerM += v * (nowMs - it) / 1000.0 }
        lastMs = nowMs
        speeds.addLast(nowMs to v)
        while (speeds.size > config.slopeSamples) speeds.removeFirst()
        trackArrivals(nav)

        val lateral = lateralMps2(v, signal.steeringDeg)
        val longitudinal = slope()
        measuredLateral = measure(measuredLateral, lateral, config.lateralOnMps2, config.lateralOnMps2)
        measuredLongitudinal = measure(measuredLongitudinal, longitudinal, config.accelOnMps2, config.brakeOnMps2)

        updateTurn(nav, v, signal.turnSignal, nowMs)
        updateSlowdown(nav, v, nowMs)

        val lateralCue = when {
            measuredLateral != 0 -> Cue(measuredLateral, level(abs(lateral), config.lateralOnMps2), CueSource.MEASURED)
            turn.on -> Cue(turn.direction, turn.level, CueSource.PREDICTED)
            else -> Cue.NONE
        }
        val longitudinalCue = when {
            measuredLongitudinal > 0 -> Cue(1, level(longitudinal, config.accelOnMps2), CueSource.MEASURED)
            measuredLongitudinal < 0 -> Cue(-1, level(-longitudinal, config.brakeOnMps2), CueSource.MEASURED)
            slowdown.on -> Cue(-1, slowdown.level, CueSource.PREDICTED)
            else -> Cue.NONE
        }
        val lat = lateralOut.update(lateralCue, nowMs)
        val lon = longitudinalOut.update(longitudinalCue, nowMs)
        return MotionOutput(
            hint = Hint(
                lateral = when (lat.direction) {
                    1 -> LateralCue.LEFT
                    -1 -> LateralCue.RIGHT
                    else -> LateralCue.NONE
                },
                lateralStrength = lat.strength(),
                longitudinal = when (lon.direction) {
                    1 -> LongitudinalCue.ACCELERATE
                    -1 -> LongitudinalCue.BRAKE
                    else -> LongitudinalCue.NONE
                },
                longitudinalStrength = lon.strength(),
            ),
            lateralSource = lat.source,
            longitudinalSource = lon.source,
            lateralMps2 = lateral,
            longitudinalMps2 = longitudinal,
        )
    }

    // Kinematic bicycle model: low-speed geometry, so it may overstate the push at high speed.
    private fun lateralMps2(v: Double, steeringDeg: Double): Double =
        v * v * tan(Math.toRadians(steeringDeg / config.steeringRatio)) / config.wheelbaseM

    /** Least-squares slope over the kept samples, using their real times. */
    private fun slope(): Double {
        if (speeds.size < 2) return 0.0
        val t0 = speeds.first().first
        val xs = speeds.map { (it.first - t0) / 1000.0 }
        val ys = speeds.map { it.second }
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx == 0.0) return 0.0
        return xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
    }

    /** -1, 0 or 1 with hysteresis; `positiveOn` and `negativeOn` are the thresholds each way. */
    private fun measure(current: Int, value: Double, positiveOn: Double, negativeOn: Double): Int {
        val on = if (value >= 0) positiveOn else negativeOn
        val keep = (if (current > 0) positiveOn else negativeOn) * config.offRatio
        return when {
            abs(value) >= on -> sign(value).toInt()
            current != 0 && sign(value).toInt() == current && abs(value) >= keep -> current
            else -> 0
        }
    }

    private fun level(magnitude: Double, onMps2: Double): Int =
        if (magnitude < onMps2 * config.offRatio) 1 else 1 + config.levelBoundsMps2.count { magnitude >= it }

    private fun trackArrivals(nav: NavSnapshot) {
        if (nav.tbt !== tbtSeen) {
            tbtSeen = nav.tbt
            tbtOdometerM = odometerM
        }
        if (nav.cameras !== pointsSeen.first || nav.cautionPoints !== pointsSeen.second) {
            if (nav.cameras !== pointsSeen.first) nav.cameras.forEach { pointOdometerM[it.id] = odometerM }
            if (nav.cautionPoints !== pointsSeen.second) nav.cautionPoints.forEach { pointOdometerM[it.id] = odometerM }
            pointsSeen = nav.cameras to nav.cautionPoints
        }
    }

    private fun updateTurn(nav: NavSnapshot, v: Double, signal: TurnSignal, nowMs: Long) {
        val next = nav.tbt.firstOrNull()
        val remainingM = next?.let { it.distance - (odometerM - tbtOdometerM) }
        val key = next?.let { "${it.type}|${it.description}" }
        // The same maneuver reported much farther away is a new one, so its key changes.
        val jumped = key != null && key == lastTurnKey && remainingM != null && lastTurnRemainingM?.let { remainingM > it + config.newTurnJumpM } == true
        if (key != lastTurnKey || jumped) turnGeneration++
        lastTurnKey = key
        lastTurnRemainingM = remainingM
        val turnId = key?.let { "$it#$turnGeneration" }
        turn.consumed.retainAll(setOfNotNull(turnId))

        if (turn.on) {
            if (turn.key != turnId || (remainingM != null && remainingM <= 0)) turn.markPassed(nowMs)
            turn.settle(measuredLateral != 0, nowMs)
            if (turn.on && turn.passedAtMs == null && (v * 3.6 < config.minSpeedKph || signal.side() != turn.direction)) turn.release()
        }
        if (turn.on || next == null || remainingM == null || turnId == null || turnId in turn.consumed) return
        val direction = next.type.side()
        if (direction == 0 || signal.side() != direction || v * 3.6 < config.minSpeedKph || remainingM <= 0) return
        if (measuredLateral == direction) return
        if (remainingM / v <= config.leadSec + config.approachSec) turn.start(turnId, direction, next.type.predictedLevel(), nowMs)
    }

    private fun updateSlowdown(nav: NavSnapshot, v: Double, nowMs: Long) {
        val targets = nav.cameras.filter { it.type in SPEED_CAMERAS }.map {
            Target(it.id, it.remainDistance - travelledSince(it.id), it.speedLimit.toDouble())
        } + nav.cautionPoints.mapNotNull { point ->
            targetKph(point.type)?.let { Target(point.id, point.remainDistance - travelledSince(point.id), it) }
        }
        // A point no longer reported is behind the car, so it can never come back.
        slowdown.consumed.retainAll(targets.map { it.id }.toSet())

        if (slowdown.on) {
            val tracked = targets.firstOrNull { it.id == slowdown.key }
            if (tracked == null || tracked.remainingM <= 0) slowdown.markPassed(nowMs)
            slowdown.settle(measuredLongitudinal != 0, nowMs)
            // Down to the target speed: there is nothing left to brake for.
            if (slowdown.on && tracked != null && v * 3.6 <= tracked.kph + config.speedMarginKph) slowdown.consume()
        }
        if (slowdown.on) return
        val (target, decel) = targets
            .filter { it.remainingM > 0 && it.id !in slowdown.consumed && v * 3.6 > it.kph + config.speedMarginKph }
            .map { it to (v * v - square(it.kph * KPH_TO_MPS)) / (2 * it.remainingM) }
            .maxByOrNull { it.second } ?: return
        // While measured motion holds the axis a prediction would only be used up by it.
        if (decel >= config.requiredDecelMps2 && measuredLongitudinal == 0) {
            slowdown.start(target.id, -1, level(decel, config.brakeOnMps2), nowMs)
        }
    }

    private fun square(x: Double) = x * x

    private fun travelledSince(id: String) = odometerM - (pointOdometerM[id] ?: odometerM)

    private fun targetKph(type: CautionPointType): Double? = when (type) {
        CautionPointType.SPEED_BUMP -> config.bumpKph
        CautionPointType.SCHOOL_ZONE -> config.schoolZoneKph
        CautionPointType.RAILROAD_CROSSING -> 0.0
        else -> null
    }

    private data class Target(val id: String, val remainingM: Double, val kph: Double)

    /**
     * One predicted cue. A consumed maneuver stays off until the navigation reports another one,
     * so a turn the navigation keeps reporting cannot switch it on again.
     */
    private inner class Prediction {
        var key: String? = null
        var on = false
        var direction = 0
        var level = 1
        var passedAtMs: Long? = null
        val consumed = mutableSetOf<String>()
        private var onSinceMs = 0L

        fun start(key: String, direction: Int, level: Int, nowMs: Long) {
            this.key = key
            this.direction = direction
            this.level = level
            on = true
            onSinceMs = nowMs
            passedAtMs = null
        }

        fun markPassed(nowMs: Long) {
            if (passedAtMs == null) passedAtMs = nowMs
        }

        /** Measured motion on the axis confirms or overrules it; otherwise it expires. */
        fun settle(measured: Boolean, nowMs: Long) {
            when {
                measured -> consume()
                passedAtMs?.let { nowMs - it > config.graceMs } == true -> consume()
                nowMs - onSinceMs > config.maxPredictMs -> consume()
            }
        }

        fun consume() {
            key?.let { consumed += it }
            on = false
            passedAtMs = null
        }

        /** Off without using it up: the conditions may hold again for the same maneuver. */
        fun release() {
            on = false
        }

        fun clear() {
            key = null
            on = false
            passedAtMs = null
            consumed.clear()
        }
    }

    private data class Cue(val direction: Int, val level: Int, val source: CueSource) {
        fun strength(): Double = if (direction == 0) 0.0 else level.coerceIn(1, MAX_LEVEL).toDouble() / MAX_LEVEL

        companion object {
            val NONE = Cue(0, 0, CueSource.NONE)
        }
    }

    /** Turns on at once; turns off only after the minimum hold, unless measured motion replaces it. */
    private inner class Held {
        private var cue = Cue.NONE
        private var onSinceMs = 0L

        fun update(next: Cue, nowMs: Long): Cue {
            when {
                next.direction != 0 -> {
                    if (next.direction != cue.direction) onSinceMs = nowMs
                    cue = next
                }
                cue.direction != 0 && nowMs - onSinceMs >= config.minHoldMs -> cue = Cue.NONE
            }
            return cue
        }

        fun clear() {
            cue = Cue.NONE
        }
    }

    private companion object {
        val SPEED_CAMERAS = setOf(
            CameraType.SPEED_CAMERA_FIX,
            CameraType.SPEED_CAMERA_MOV,
            CameraType.SPEED_CAMERA_TRAFFIC_LIGHT,
            CameraType.SPEED_CAMERA_SCHOOL_ZONE,
            CameraType.SPEED_CAMERA_SCHOOL_ZONE_TRAFFIC_LIGHT,
            CameraType.VARIABLE_SPEED_CAMERA,
            CameraType.SPEED_CAMERA_SECTION_START,
        )
    }
}

private fun TurnSignal.side(): Int = when (this) {
    TurnSignal.LEFT -> 1
    TurnSignal.RIGHT -> -1
    TurnSignal.OFF -> 0
}

/** 1 for a maneuver to the left, -1 to the right, 0 for none. */
internal fun TurnType.side(): Int = when (this) {
    TurnType.TURN_LEFT, TurnType.TURN_SHARP_L, TurnType.TURN_UTURN, TurnType.TURN_KEEP_L,
    TurnType.TURN_ENTER_IC_L, TurnType.TURN_EXIT_IC_L,
    -> 1
    TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R, TurnType.TURN_KEEP_R,
    TurnType.TURN_ENTER_IC_R, TurnType.TURN_EXIT_IC_R,
    -> -1
    else -> 0
}

/** Provisional fixed strength before the turn shows: a planning choice, not measured. */
private fun TurnType.predictedLevel(): Int = when (this) {
    TurnType.TURN_SHARP_L, TurnType.TURN_SHARP_R, TurnType.TURN_UTURN -> 3
    TurnType.TURN_LEFT, TurnType.TURN_RIGHT -> 2
    else -> 1
}
