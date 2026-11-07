package ai.activeseat.app.hardware

import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.hint.LongitudinalCue
import kotlin.math.max

/** Provisional full-strength angles, in degrees, shared by the three seat modules. */
object HardwareMapping {
    const val ROLL_DEG = 3.0
    const val PITCH_DEG = 2.0
    const val YAW_DEG = 6.0
}

/**
 * Leans against the coming inertia: into the corner, back for a brake, forward for acceleration.
 * Vibration marks the direction of motion: the inner side in a corner. Front and rear only exist
 * on the cushion, where the seat has a front and a rear half.
 */
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
    val corner = Vibration(left = max(left, 0.0), right = max(-left, 0.0))
    val module = ModuleCommand(
        vibration = corner,
        rollDeg = HardwareMapping.ROLL_DEG * left,
        pitchDeg = HardwareMapping.PITCH_DEG * forward,
        yawDeg = HardwareMapping.YAW_DEG * left,
    )
    return HardwareCommand(
        headrest = module,
        backrest = module,
        cushion = module.copy(vibration = corner.copy(front = max(forward, 0.0), back = max(-forward, 0.0))),
        watchStrength = max(lateralStrength, longitudinalStrength),
    )
}

/**
 * The seat and the watch each follow their own engine's hint, shaped by the passenger's settings.
 * A device turned off rests. Lean speed off keeps the seat upright but leaves its vibration on.
 */
fun toHardwareCommand(seat: Hint, watch: Hint, settings: HardwareSettings): HardwareCommand {
    val base = (if (settings.seatOn) seat else Hint()).toHardwareCommand()
    val seatScale = HardwareSettings.vibrationScale(settings.seatVibration)
    val leanSec = HardwareSettings.leanSeconds(settings.leanSpeed)
    fun ModuleCommand.shaped() = ModuleCommand(
        vibration = Vibration(
            left = this.vibration.left * seatScale,
            right = this.vibration.right * seatScale,
            front = this.vibration.front * seatScale,
            back = this.vibration.back * seatScale,
        ),
        rollDeg = if (leanSec == null) 0.0 else rollDeg,
        pitchDeg = if (leanSec == null) 0.0 else pitchDeg,
        yawDeg = if (leanSec == null) 0.0 else yawDeg,
    )
    val watchStrength = if (settings.watchOn) {
        max(watch.lateralStrength, watch.longitudinalStrength) * HardwareSettings.vibrationScale(settings.watchVibration)
    } else {
        0.0
    }
    return HardwareCommand(
        headrest = base.headrest.shaped(),
        backrest = base.backrest.shaped(),
        cushion = base.cushion.shaped(),
        watchStrength = watchStrength,
        leanSec = leanSec ?: HardwareCommand.DEFAULT_LEAN_SEC,
    )
}
