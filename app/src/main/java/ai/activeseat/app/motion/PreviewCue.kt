package ai.activeseat.app.motion

import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.hint.LongitudinalCue

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
 * threshold, with the vibration level and lean angle of the scene's acceleration. Computed directly,
 * so the preview needs no engine run.
 */
fun MotionConfig.previewHint(maneuver: PreviewManeuver): Hint {
    val magnitude = maneuver.accelerationMps2
    val on = if (maneuver == PreviewManeuver.BRAKE) brakeOnMps2 else lateralOnMps2
    if (magnitude < on) return Hint()
    val strength = level(magnitude).toDouble() / MotionConfig.MAX_LEVEL
    return when (maneuver) {
        PreviewManeuver.LEFT -> Hint(lateral = LateralCue.LEFT, lateralStrength = strength, lateralMps2 = magnitude)
        PreviewManeuver.RIGHT -> Hint(lateral = LateralCue.RIGHT, lateralStrength = strength, lateralMps2 = magnitude)
        PreviewManeuver.BRAKE ->
            Hint(longitudinal = LongitudinalCue.BRAKE, longitudinalStrength = strength, longitudinalMps2 = magnitude)
    }
}
