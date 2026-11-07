package ai.activeseat.app.hardware

/** One seat module's vibration per zone, 0..1. Zones are named from the passenger's seat. */
data class Vibration(
    val left: Double = 0.0,
    val right: Double = 0.0,
    val front: Double = 0.0,
    val back: Double = 0.0,
)

/**
 * One seat module's target, in assumed real-hardware units. The screen applies its own scale.
 * rollDeg is positive to the left, pitchDeg positive forward, yawDeg positive to the left.
 */
data class ModuleCommand(
    val vibration: Vibration = Vibration(),
    val rollDeg: Double = 0.0,
    val pitchDeg: Double = 0.0,
    val yawDeg: Double = 0.0,
)

/**
 * A watch has one vibration motor and cannot show direction, so it only gets a strength.
 * [leanSec] is how long the seat takes to reach 95% of a new pose, on a slow-fast-slow curve.
 */
data class HardwareCommand(
    val headrest: ModuleCommand = ModuleCommand(),
    val backrest: ModuleCommand = ModuleCommand(),
    val cushion: ModuleCommand = ModuleCommand(),
    val watchStrength: Double = 0.0,
    val leanSec: Double = DEFAULT_LEAN_SEC,
) {
    companion object {
        const val DEFAULT_LEAN_SEC = 2.0
    }
}
