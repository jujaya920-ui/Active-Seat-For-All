package ai.activeseat.app.hint

import ai.activeseat.app.prediction.AxisPrediction
import ai.activeseat.app.prediction.PredictResult
import ai.activeseat.app.prediction.Prediction
import kotlin.math.abs

private const val MS_PER_SEC = 1000.0

/**
 * Turns prediction results into a steady [Hint]. Synchronized because results and ticks may arrive
 * on different threads of the default dispatcher.
 */
class HintStabilizer(
    private val config: HintConfig,
    private val clockMs: () -> Long,
    private val rtt: RttTracker,
) {
    private val lateral = Axis(config, LateralCue.NONE)
    private val longitudinal = Axis(config, LongitudinalCue.NONE)
    private var lastSuccessMs: Long? = null
    private var probabilities = MotionProbabilities()

    @Synchronized
    fun onResult(result: PredictResult?): Hint {
        if (result !is PredictResult.Success || result.predictions.isEmpty()) {
            allOff()
            return hint()
        }
        val nowMs = clockMs()
        lastSuccessMs = nowMs
        // Recorded first: horizons count from the input this response answers, so its own RTT
        // is the best evidence of how much of the horizon is already spent.
        rtt.record(result.rttMs)
        val prediction = nearest(result.predictions)
        lateral.update(prediction.lateral.value.toLateralCue(), prediction.lateral, nowMs)
        longitudinal.update(prediction.longitudinal.value.toLongitudinalCue(), prediction.longitudinal, nowMs)
        probabilities = prediction.toMotionProbabilities()
        return hint()
    }

    @Synchronized
    fun tick(): Hint {
        val successMs = lastSuccessMs
        if (successMs != null && clockMs() - successMs > config.staleAfterMs) allOff()
        return hint()
    }

    @Synchronized
    fun probabilities(): MotionProbabilities = probabilities

    /** On a tie the first listed prediction wins. */
    private fun nearest(predictions: List<Prediction>): Prediction {
        val targetSec = config.displayLeadSec + rtt.estimateMs() / MS_PER_SEC
        return predictions.minBy { abs(it.horizonSec - targetSec) }
    }

    private fun allOff() {
        lateral.off()
        longitudinal.off()
        probabilities = MotionProbabilities()
    }

    private fun hint() = Hint(lateral.cue, lateral.strength, longitudinal.cue, longitudinal.strength)
}

private class Axis<C : Any>(private val config: HintConfig, private val none: C) {
    var cue: C = none
        private set
    var strength = 0.0
        private set
    private var onSinceMs = 0L

    /** Probability decides whether the cue is on. How strong it is comes from the prediction alone. */
    fun update(next: C, axis: AxisPrediction, nowMs: Long) {
        when {
            next == none -> releaseAfterHold(nowMs)
            next == cue && axis.probability >= config.offProbability -> strength = axis.strength
            next != cue && axis.probability >= config.onProbability -> {
                // Covers both turning on and a direct reversal, which skips the min hold.
                cue = next
                strength = axis.strength
                onSinceMs = nowMs
            }
            else -> releaseAfterHold(nowMs)
        }
    }

    fun off() {
        cue = none
        strength = 0.0
    }

    /** While the hold runs, the cue keeps its last strength. */
    private fun releaseAfterHold(nowMs: Long) {
        if (nowMs - onSinceMs >= config.minHoldMs) off()
    }
}

private fun Prediction.toMotionProbabilities() = MotionProbabilities(
    forward = longitudinal.probabilities["accelerate"] ?: 0.0,
    back = longitudinal.probabilities["brake"] ?: 0.0,
    left = lateral.probabilities["left"] ?: 0.0,
    right = lateral.probabilities["right"] ?: 0.0,
)

private fun String.toLateralCue() = when (this) {
    "left" -> LateralCue.LEFT
    "right" -> LateralCue.RIGHT
    else -> LateralCue.NONE
}

private fun String.toLongitudinalCue() = when (this) {
    "accelerate" -> LongitudinalCue.ACCELERATE
    "brake" -> LongitudinalCue.BRAKE
    else -> LongitudinalCue.NONE
}
