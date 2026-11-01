package ai.activeseat.app.hint

enum class LateralCue { NONE, LEFT, RIGHT }

enum class LongitudinalCue { NONE, ACCELERATE, BRAKE }

/** The stabilized decision shown to the passenger. Strengths are 0..1 and 0 when the cue is NONE. */
data class Hint(
    val lateral: LateralCue = LateralCue.NONE,
    val lateralStrength: Double = 0.0,
    val longitudinal: LongitudinalCue = LongitudinalCue.NONE,
    val longitudinalStrength: Double = 0.0,
)

/** Jev's raw probability of each direction at the chosen horizon, all 0 when there is no fresh result. */
data class MotionProbabilities(
    val forward: Double = 0.0,
    val back: Double = 0.0,
    val left: Double = 0.0,
    val right: Double = 0.0,
)
