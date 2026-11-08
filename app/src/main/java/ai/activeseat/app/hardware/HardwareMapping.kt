package ai.activeseat.app.hardware

import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.hint.LongitudinalCue
import ai.activeseat.app.seat.YawContext
import ai.activeseat.app.seat.yawDeg
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min

/** Lean geometry shared by the three seat modules. The limit is provisional. */
object HardwareMapping {
    const val GRAVITY_MPS2 = 9.81

    /** Roll and pitch never go past this, whatever the acceleration. */
    const val MAX_LEAN_DEG = 15.0

    /** The angle that cancels [mps2]: leaning by it sets gravity's sideways part against the inertia. */
    fun cancelDeg(mps2: Double): Double = min(Math.toDegrees(atan(mps2 / GRAVITY_MPS2)), MAX_LEAN_DEG)
}

/**
 * Leans against the coming inertia: into the corner, back for a brake, forward for acceleration. The
 * angle comes from the acceleration alone; the strength sets only the vibration. Vibration marks the
 * direction of motion: the inner side in a corner. Front and rear only exist on the cushion, where
 * the seat has a front and a rear half.
 */
fun Hint.toHardwareCommand(yaw: YawContext): HardwareCommand {
    val leftSign = when (lateral) {
        LateralCue.LEFT -> 1.0
        LateralCue.RIGHT -> -1.0
        LateralCue.NONE -> 0.0
    }
    val forwardSign = when (longitudinal) {
        LongitudinalCue.ACCELERATE -> 1.0
        LongitudinalCue.BRAKE -> -1.0
        LongitudinalCue.NONE -> 0.0
    }
    val left = leftSign * lateralStrength
    val forward = forwardSign * longitudinalStrength
    val rollDeg = leftSign * HardwareMapping.cancelDeg(lateralMps2)
    val corner = Vibration(left = max(left, 0.0), right = max(-left, 0.0))
    val module = ModuleCommand(
        vibration = corner,
        rollDeg = rollDeg,
        pitchDeg = forwardSign * HardwareMapping.cancelDeg(longitudinalMps2),
        yawDeg = yawDeg(leftSign, lateralMps2, yaw),
    )
    return HardwareCommand(
        headrest = module,
        backrest = module,
        cushion = module.copy(vibration = corner.copy(front = max(forward, 0.0), back = max(-forward, 0.0))),
        watchStrength = max(lateralStrength, longitudinalStrength),
    )
}

/**
 * The seat and the watch each follow their own engine's hint, shaped by the passenger's settings, each
 * of which does one thing: vibration scales the vibration, lean speed sets how fast the seat reaches
 * its angle and never the angle itself. A device turned off rests. Lean speed off keeps the seat
 * upright but leaves its vibration on.
 */
fun toHardwareCommand(seat: Hint, watch: Hint, settings: HardwareSettings, yaw: YawContext): HardwareCommand {
    val base = (if (settings.seatOn) seat else Hint()).toHardwareCommand(yaw)
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
