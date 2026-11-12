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
 * rollDeg is positive to the left, pitchDeg positive forward, yawDeg positive to the left. Each
 * angle is the module's own actuator: on the passenger seat every module carries the whole seat's
 * pose, on the rear seats each module moves alone (see [HardwareCommand]).
 */
data class ModuleCommand(
    val vibration: Vibration = Vibration(),
    val rollDeg: Double = 0.0,
    val pitchDeg: Double = 0.0,
    val yawDeg: Double = 0.0,
)

/**
 * The three modules of one seat and the watch of whoever sits there; each seat has its own settings.
 * A watch has one vibration motor and cannot show direction, so it only gets a strength. [leanSec] is
 * how long the seat takes to reach 95% of a new pose, on a slow-fast-slow curve.
 */
data class SeatCommand(
    val headrest: ModuleCommand = ModuleCommand(),
    val backrest: ModuleCommand = ModuleCommand(),
    val cushion: ModuleCommand = ModuleCommand(),
    val watchStrength: Double = 0.0,
    val leanSec: Double = HardwareCommand.DEFAULT_LEAN_SEC,
)

/**
 * One command per seat, each from that seat's own engines and settings. The front passenger seat
 * turns and tilts as one on its turntable. The rear seats have no room to turn beside the centre
 * console: their headrests turn alone, cushions roll, backrests and cushions pitch.
 */
data class HardwareCommand(
    val passenger: SeatCommand = SeatCommand(),
    val rearLeft: SeatCommand = SeatCommand(),
    val rearRight: SeatCommand = SeatCommand(),
) {
    fun of(place: CabinPlace): SeatCommand = when (place) {
        CabinPlace.PASSENGER -> passenger
        CabinPlace.REAR_LEFT -> rearLeft
        CabinPlace.REAR_RIGHT -> rearRight
    }

    companion object {
        const val DEFAULT_LEAN_SEC = 2.0
    }
}

/** The screen's seat tabs: the front passenger seat, and the rear row with both its seats. */
enum class CabinSeat(val label: String) {
    PASSENGER("조수석"),
    REAR("뒷좌석"),
}

/**
 * The seats the app drives. Each has its own engines, settings and occupancy: an empty seat never
 * moves, whoever sits next to it.
 */
enum class CabinPlace(val label: String, val tab: CabinSeat) {
    PASSENGER("조수석", CabinSeat.PASSENGER),
    REAR_LEFT("뒷좌석 왼쪽", CabinSeat.REAR),
    REAR_RIGHT("뒷좌석 오른쪽", CabinSeat.REAR),
}

/** Which seats have someone aboard. */
data class Occupancy(
    val passenger: Boolean = true,
    val rearLeft: Boolean = true,
    val rearRight: Boolean = true,
) {
    fun of(place: CabinPlace): Boolean = when (place) {
        CabinPlace.PASSENGER -> passenger
        CabinPlace.REAR_LEFT -> rearLeft
        CabinPlace.REAR_RIGHT -> rearRight
    }
}
