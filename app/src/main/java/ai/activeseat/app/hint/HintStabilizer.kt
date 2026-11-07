package ai.activeseat.app.hint

import ai.activeseat.app.prediction.PredictResult
import ai.activeseat.app.prediction.PushForecast
import ai.activeseat.app.prediction.PushPrediction
import kotlin.math.max

private const val MAX_LEVEL = 4

/**
 * Turns prediction results into a steady [Hint]. Synchronized because results and ticks may arrive
 * on different threads of the default dispatcher.
 *
 * Timing: a response answers the window read at its request start t0 and is applied at exactly
 * t0 + [HintConfig.applyDelayMs], never earlier. [onResult] only stores it as pending; [onResult] and
 * [tick] apply whatever pending is due. The sampler ticks every 200 ms, so callers that want the exact
 * moment schedule one extra [tick] at [pendingApplyAtMs].
 */
class HintStabilizer(
    private val config: HintConfig,
    private val clockMs: () -> Long,
) {
    private val forward = Push(config)
    private val backward = Push(config)
    private val left = Push(config)
    private val right = Push(config)
    private var pending: Pending? = null
    private var lastApplyMs: Long? = null
    private var probabilities = MotionProbabilities()

    private class Pending(val push: PushForecast, val applyAtMs: Long)

    /**
     * Anything but a success turns every cue off at once. A success slower than
     * [HintConfig.maxRoundTripMs] from [requestStartMs] is dropped and changes nothing: the stale rule
     * turns cues off if no newer response is applied.
     */
    @Synchronized
    fun onResult(result: PredictResult?, requestStartMs: Long): Hint {
        val nowMs = clockMs()
        // An older pending that is already due goes first, so a late tick never skips it.
        advance(nowMs)
        if (result !is PredictResult.Success) {
            pending = null
            allOff()
            return hint()
        }
        // The clock covers time before the call; rttMs covers the call alone.
        if (max(result.rttMs, nowMs - requestStartMs) > config.maxRoundTripMs) return hint()
        pending = Pending(result.push, requestStartMs + config.applyDelayMs)
        advance(nowMs)
        return hint()
    }

    @Synchronized
    fun tick(): Hint {
        advance(clockMs())
        return hint()
    }

    /** When the stored response will be applied, or null when none waits. */
    @Synchronized
    fun pendingApplyAtMs(): Long? = pending?.applyAtMs

    @Synchronized
    fun probabilities(): MotionProbabilities = probabilities

    private fun advance(nowMs: Long) {
        pending?.takeIf { nowMs >= it.applyAtMs }?.let {
            pending = null
            apply(it.push, nowMs)
        }
        val appliedMs = lastApplyMs
        if (appliedMs != null && nowMs - appliedMs > config.staleAfterMs) allOff()
    }

    private fun apply(push: PushForecast, nowMs: Long) {
        lastApplyMs = nowMs
        forward.update(push.forward, nowMs)
        backward.update(push.backward, nowMs)
        left.update(push.left, nowMs)
        right.update(push.right, nowMs)
        probabilities = push.toMotionProbabilities()
    }

    private fun allOff() {
        forward.off()
        backward.off()
        left.off()
        right.off()
        lastApplyMs = null
        probabilities = MotionProbabilities()
    }

    /** Body push maps to the car's maneuver: forward is braking, and a push right means a left turn. */
    private fun hint(): Hint {
        val longitudinal = stronger(forward, backward)
        val lateral = stronger(right, left)
        return Hint(
            lateral = when (lateral) {
                right -> LateralCue.LEFT
                left -> LateralCue.RIGHT
                else -> LateralCue.NONE
            },
            lateralStrength = lateral?.strength() ?: 0.0,
            longitudinal = when (longitudinal) {
                forward -> LongitudinalCue.BRAKE
                backward -> LongitudinalCue.ACCELERATE
                else -> LongitudinalCue.NONE
            },
            longitudinalStrength = longitudinal?.strength() ?: 0.0,
        )
    }

    /**
     * The one that is on. When both are, the higher latest pOn wins, since Jev often raises both
     * sides yet ranks the real one higher; then the higher level. A full tie cancels to none.
     */
    private fun stronger(a: Push, b: Push): Push? = when {
        a.on && b.on -> when {
            a.pOn > b.pOn -> a
            b.pOn > a.pOn -> b
            a.level > b.level -> a
            b.level > a.level -> b
            else -> null
        }
        a.on -> a
        b.on -> b
        else -> null
    }
}

/** One push direction with hysteresis and a minimum hold. */
private class Push(private val config: HintConfig) {
    var on = false
        private set
    var level = 0
        private set

    /** Latest applied probability, kept even while off so a conflict compares current evidence. */
    var pOn = 0.0
        private set
    private var onSinceMs = 0L

    fun update(prediction: PushPrediction, nowMs: Long) {
        pOn = prediction.pOn
        when {
            !on && prediction.pOn >= config.onProbability -> {
                on = true
                level = prediction.level
                onSinceMs = nowMs
            }
            on && prediction.pOn >= config.offProbability -> level = prediction.level
            // While the hold runs, the cue keeps its last level.
            on && nowMs - onSinceMs < config.minHoldMs -> Unit
            else -> off()
        }
    }

    fun off() {
        on = false
        level = 0
        pOn = 0.0
    }

    /** Level 1..4 maps to 0.25..1.0. */
    fun strength(): Double = level.coerceIn(1, MAX_LEVEL).toDouble() / MAX_LEVEL
}

/** Shown as the car's maneuver, so each body push lands on the opposite side. */
private fun PushForecast.toMotionProbabilities() = MotionProbabilities(
    forward = backward.pOn,
    back = forward.pOn,
    left = right.pOn,
    right = left.pOn,
)
