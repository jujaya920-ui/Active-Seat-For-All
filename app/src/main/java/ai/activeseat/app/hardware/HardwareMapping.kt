package ai.activeseat.app.hardware

import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.hint.LongitudinalCue
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min

/** Lean geometry shared by the three seat modules. The limits are provisional. */
object HardwareMapping {
    const val GRAVITY_MPS2 = 9.81

    /** Roll and pitch never go past this, whatever the acceleration. */
    const val MAX_LEAN_DEG = 15.0

    /** The seat's turntable never turns past this, whatever the wheel angle. */
    const val MAX_YAW_DEG = 30.0

    /**
     * The seat turns by the road-wheel angle, toward the inside of the corner, so the passenger's
     * line of sight follows the direction the front wheels point, as the driver's does.
     */
    fun yawDeg(wheelDeg: Double): Double = min(wheelDeg, MAX_YAW_DEG)

    /** The angle that cancels [mps2]: leaning by it sets gravity's sideways part against the inertia. */
    fun cancelDeg(mps2: Double): Double = min(Math.toDegrees(atan(mps2 / GRAVITY_MPS2)), MAX_LEAN_DEG)
}

/**
 * Leans against the coming inertia: into the corner, back for a brake, forward for acceleration. The
 * lean angle comes from the acceleration alone; the strength sets only the vibration. The turn comes
 * from the road-wheel angle, so the passenger faces where the car is about to go. Vibration marks the
 * direction of motion: the inner side in a corner, on the backrest and cushion only. Front and rear
 * only exist on the cushion, where the seat has a front and a rear half.
 *
 * Passenger seat: the whole seat rolls, pitches and turns. Rear seats: the headrest turns, the
 * cushion rolls, backrest and cushion pitch.
 */
fun Hint.toHardwareCommand(): HardwareCommand {
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
    val pitchDeg = forwardSign * HardwareMapping.cancelDeg(longitudinalMps2)
    val yawDeg = leftSign * HardwareMapping.yawDeg(wheelDeg)
    val corner = Vibration(left = max(left, 0.0), right = max(-left, 0.0))
    val cushionVibration = corner.copy(front = max(forward, 0.0), back = max(-forward, 0.0))
    val whole = ModuleCommand(rollDeg = rollDeg, pitchDeg = pitchDeg, yawDeg = yawDeg)
    val watch = max(lateralStrength, longitudinalStrength)
    val rear = SeatCommand(
        headrest = ModuleCommand(yawDeg = yawDeg),
        backrest = ModuleCommand(vibration = corner, pitchDeg = pitchDeg),
        cushion = ModuleCommand(vibration = cushionVibration, rollDeg = rollDeg, pitchDeg = pitchDeg),
        watchStrength = watch,
    )
    return HardwareCommand(
        passenger = SeatCommand(
            headrest = whole,
            backrest = whole.copy(vibration = corner),
            cushion = whole.copy(vibration = cushionVibration),
            watchStrength = watch,
        ),
        rearLeft = rear,
        rearRight = rear,
    )
}

/**
 * The seat and the watch each follow their own engine's hint, shaped by the passenger's settings, each
 * of which does one thing: vibration scales the vibration, lean speed sets how fast the seat reaches
 * its angle and never the angle itself. A device turned off rests, and so does a seat nobody sits in.
 * Lean speed off keeps the seat upright but leaves its vibration on.
 */
fun toHardwareCommand(
    seat: Hint,
    watch: Hint,
    settings: HardwareSettings,
    occupancy: Occupancy = Occupancy(),
): HardwareCommand {
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
    // Nobody in the seat: neither the seat nor that passenger's watch moves.
    fun SeatCommand.shaped(occupied: Boolean) = if (occupied) {
        SeatCommand(headrest.shaped(), backrest.shaped(), cushion.shaped(), watchStrength, leanSec ?: HardwareCommand.DEFAULT_LEAN_SEC)
    } else {
        SeatCommand()
    }
    return HardwareCommand(
        passenger = base.passenger.shaped(occupancy.passenger),
        rearLeft = base.rearLeft.shaped(occupancy.rearLeft),
        rearRight = base.rearRight.shaped(occupancy.rearRight),
    )
}

/** One seat's command from its own hints and its own settings; nothing when nobody sits in it. */
fun seatCommand(place: CabinPlace, seatHint: Hint, watchHint: Hint, settings: HardwareSettings, occupancy: Occupancy): SeatCommand =
    toHardwareCommand(seatHint, watchHint, settings, occupancy).of(place)
