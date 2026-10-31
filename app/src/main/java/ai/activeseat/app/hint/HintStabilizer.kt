package ai.activeseat.app.hint

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
        lateral.update(prediction.lateral.toLateralCue(), prediction.lateralProbability, nowMs)
        longitudinal.update(prediction.longitudinal.toLongitudinalCue(), prediction.longitudinalProbability, nowMs)
        return hint()
    }

    @Synchronized
    fun tick(): Hint {
        val successMs = lastSuccessMs
        if (successMs != null && clockMs() - successMs > config.staleAfterMs) allOff()
        return hint()
    }

    /** On a tie the first listed prediction wins. */
    private fun nearest(predictions: List<Prediction>): Prediction {
        val targetSec = config.displayLeadSec + rtt.estimateMs() / MS_PER_SEC
        return predictions.minBy { abs(it.horizonSec - targetSec) }
    }

    private fun allOff() {
        lateral.off()
        longitudinal.off()
    }

    private fun hint() = Hint(lateral.cue, lateral.strength, longitudinal.cue, longitudinal.strength)
}

private class Axis<C : Any>(private val config: HintConfig, private val none: C) {
    var cue: C = none
        private set
    var strength = 0.0
        private set
    private var onSinceMs = 0L

    fun update(next: C, probability: Double, nowMs: Long) {
        when {
            next == none -> releaseAfterHold(nowMs)
            next == cue && probability >= config.offProbability -> strength = strengthOf(probability)
            next != cue && probability >= config.onProbability -> {
                // Covers both turning on and a direct reversal, which skips the min hold.
                cue = next
                strength = strengthOf(probability)
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

    private fun strengthOf(probability: Double): Double =
        ((probability - config.offProbability) / (1 - config.offProbability)).coerceIn(0.0, 1.0)
}

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
