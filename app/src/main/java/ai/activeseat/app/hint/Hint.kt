package ai.activeseat.app.hint

enum class LateralCue { NONE, LEFT, RIGHT }

enum class LongitudinalCue { NONE, ACCELERATE, BRAKE }

/**
 * The decision shown to the passenger. Each axis carries two independent values: the strength,
 * 0..1 from the vibration bands, which sets how hard it vibrates, and the acceleration magnitude in
 * m/s², which sets the lean angle. Both are 0 when the cue is NONE.
 */
data class Hint(
    val lateral: LateralCue = LateralCue.NONE,
    val lateralStrength: Double = 0.0,
    val longitudinal: LongitudinalCue = LongitudinalCue.NONE,
    val longitudinalStrength: Double = 0.0,
    val lateralMps2: Double = 0.0,
    val longitudinalMps2: Double = 0.0,
)

/** Cue strength toward each car maneuver, 0..1, shown as bars. All 0 when no cue is on. */
data class MotionProbabilities(
    val forward: Double = 0.0,
    val back: Double = 0.0,
    val left: Double = 0.0,
    val right: Double = 0.0,
)

/** Each bar is the cue's strength toward that car maneuver, 0 when it is off. */
fun Hint.toProbabilities() = MotionProbabilities(
    forward = if (longitudinal == LongitudinalCue.ACCELERATE) longitudinalStrength else 0.0,
    back = if (longitudinal == LongitudinalCue.BRAKE) longitudinalStrength else 0.0,
    left = if (lateral == LateralCue.LEFT) lateralStrength else 0.0,
    right = if (lateral == LateralCue.RIGHT) lateralStrength else 0.0,
)
