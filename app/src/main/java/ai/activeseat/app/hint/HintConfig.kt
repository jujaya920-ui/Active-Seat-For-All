package ai.activeseat.app.hint

/** Provisional tuning values. Probabilities apply per axis. */
data class HintConfig(
    val displayLeadSec: Double = 1.5,
    val onProbability: Double = 0.55,
    val offProbability: Double = 0.45,
    val minHoldMs: Long = 1000,
    val staleAfterMs: Long = 2000,
)
