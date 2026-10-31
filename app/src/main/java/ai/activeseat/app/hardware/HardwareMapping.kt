package ai.activeseat.app.hardware

import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.hint.LongitudinalCue
import kotlin.math.max

/** Provisional full-strength angles, in degrees. */
object HardwareMapping {
    const val HEADREST_ROLL_DEG = 3.0
    const val HEADREST_YAW_DEG = 6.0
    const val HEADREST_PITCH_DEG = 2.0
    const val BACKREST_ROLL_DEG = 2.0
    const val BACKREST_PITCH_DEG = 2.0
    const val CUSHION_ROLL_DEG = 2.0
    const val CUSHION_PITCH_DEG = 1.5
}

/** Leans against the coming inertia: into the corner, back for a brake, forward for acceleration. */
fun Hint.toHardwareCommand(): HardwareCommand {
    val left = when (lateral) {
        LateralCue.LEFT -> lateralStrength
        LateralCue.RIGHT -> -lateralStrength
        LateralCue.NONE -> 0.0
    }
    val forward = when (longitudinal) {
        LongitudinalCue.ACCELERATE -> longitudinalStrength
        LongitudinalCue.BRAKE -> -longitudinalStrength
        LongitudinalCue.NONE -> 0.0
    }
    val vibration = max(lateralStrength, longitudinalStrength)
    val (watch, watchStrength) = when {
        lateral == LateralCue.LEFT -> WatchPattern.LEFT to lateralStrength
        lateral == LateralCue.RIGHT -> WatchPattern.RIGHT to lateralStrength
        longitudinal == LongitudinalCue.BRAKE -> WatchPattern.BRAKE to longitudinalStrength
        longitudinal == LongitudinalCue.ACCELERATE -> WatchPattern.ACCELERATE to longitudinalStrength
        else -> WatchPattern.NONE to 0.0
    }
    return HardwareCommand(
        headrest = ModuleCommand(
            vibration = vibration,
            rollDeg = HardwareMapping.HEADREST_ROLL_DEG * left,
            pitchDeg = HardwareMapping.HEADREST_PITCH_DEG * forward,
            yawDeg = HardwareMapping.HEADREST_YAW_DEG * left,
        ),
        backrest = ModuleCommand(
            vibration = vibration,
            rollDeg = HardwareMapping.BACKREST_ROLL_DEG * left,
            pitchDeg = HardwareMapping.BACKREST_PITCH_DEG * forward,
        ),
        cushion = ModuleCommand(
            vibration = vibration,
            rollDeg = HardwareMapping.CUSHION_ROLL_DEG * left,
            pitchDeg = HardwareMapping.CUSHION_PITCH_DEG * forward,
        ),
        watch = watch,
        watchStrength = watchStrength,
    )
}
