package ai.activeseat.app.motion

import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.hint.LongitudinalCue

private const val PREVIEW_MAX_LEVEL = 4

/**
 * The settings preview's scenes: a gentle left turn, a firmer right turn and a brake. The values sit
 * between the thresholds so that changing the sensitivity visibly changes what turns on.
 */
enum class PreviewManeuver(val label: String, val accelerationMps2: Double) {
    LEFT("좌회전", 1.2),
    RIGHT("우회전", 2.2),
    BRAKE("감속", 1.3),
}

/**
 * The hint the engine's measured rule gives for this maneuver held steady: on at the axis's on
 * threshold, one level per bound reached. Computed directly, so the preview needs no engine run.
 */
fun MotionConfig.previewHint(maneuver: PreviewManeuver): Hint {
    val magnitude = maneuver.accelerationMps2
    val on = if (maneuver == PreviewManeuver.BRAKE) brakeOnMps2 else lateralOnMps2
    if (magnitude < on) return Hint()
    val level = (1 + levelBoundsMps2.count { it <= magnitude }).coerceAtMost(PREVIEW_MAX_LEVEL)
    val strength = level.toDouble() / PREVIEW_MAX_LEVEL
    return when (maneuver) {
        PreviewManeuver.LEFT -> Hint(lateral = LateralCue.LEFT, lateralStrength = strength)
        PreviewManeuver.RIGHT -> Hint(lateral = LateralCue.RIGHT, lateralStrength = strength)
        PreviewManeuver.BRAKE -> Hint(longitudinal = LongitudinalCue.BRAKE, longitudinalStrength = strength)
    }
}
