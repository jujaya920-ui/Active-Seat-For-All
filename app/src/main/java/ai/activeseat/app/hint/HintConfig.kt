package ai.activeseat.app.hint

/** Provisional tuning values. Probabilities apply per push direction. */
data class HintConfig(
    val onProbability: Double = 0.55,
    val offProbability: Double = 0.45,
    val minHoldMs: Long = 1000,
    val staleAfterMs: Long = 2000,
    /** A response that took longer than this from its request start is dropped. */
    val maxRoundTripMs: Long = 1000,
    /** A response is applied this long after its request start, never earlier. */
    val applyDelayMs: Long = 1000,
)
