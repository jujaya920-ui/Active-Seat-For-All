package ai.activeseat.app.vehicle

enum class TurnSignal(val wire: String) {
    OFF("off"),
    LEFT("left"),
    RIGHT("right"),
}

/** The selected gear, from AAOS GEAR_SELECTION. UNKNOWN until the car has reported one. */
enum class Gear { UNKNOWN, PARK, REVERSE, NEUTRAL, DRIVE }

/** Seats as the Pleos Seat API names them. Korean cars are left-hand drive, so ROW_1_LEFT is the driver. */
enum class SeatPosition { ROW_1_LEFT, ROW_1_CENTER, ROW_1_RIGHT, ROW_2_LEFT, ROW_2_CENTER, ROW_2_RIGHT }

val DRIVER_SEAT = SeatPosition.ROW_1_LEFT

/**
 * Latest vehicle state. Steering is positive to the left (SDK convention). `seats` holds only
 * the seats that have reported, so a missing seat means unknown, not empty.
 */
data class VehicleSignal(
    val speedKph: Double = 0.0,
    val steeringDeg: Double = 0.0,
    val brake: Boolean = false,
    val turnSignal: TurnSignal = TurnSignal.OFF,
    val gear: Gear = Gear.UNKNOWN,
    val seats: Map<SeatPosition, Boolean> = emptyMap(),
)

/**
 * The motion models run only in a forward gear. A gear the car never reported counts as D, so a
 * car that cannot report it still gets hints; the screen shows it as unconfirmed.
 */
fun Gear.runsMotion(): Boolean = this == Gear.DRIVE || this == Gear.UNKNOWN

/**
 * Whether anyone besides the driver is seated. With no passenger seat reported at all it is
 * assumed, since not every car or the emulator reports occupancy; the screen shows it as unconfirmed.
 */
fun Map<SeatPosition, Boolean>.hasPassenger(): Boolean {
    val passengers = filterKeys { it != DRIVER_SEAT }
    return passengers.isEmpty() || passengers.values.any { it }
}

/** One entry of the prediction contract's `signals` array. */
data class SignalSample(
    val ageMs: Long,
    val speedKph: Double,
    val steeringDeg: Double,
    val brake: Boolean,
    val turnSignal: String,
)
