package ai.activeseat.app.vehicle

import ai.activeseat.app.hardware.CabinPlace
import ai.activeseat.app.hardware.Occupancy

enum class TurnSignal { OFF, LEFT, RIGHT }

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
 * The ON/OFF model's gates before the motion models run: a forward gear and a passenger aboard.
 * When either fails the engines reset, so they start over once both hold again.
 */
fun VehicleSignal.runsMotion(): Boolean = gear.runsMotion() && seats.hasPassenger()

/**
 * Whether anyone besides the driver is seated. With no passenger seat reported at all it is
 * assumed, since not every car or the emulator reports occupancy; the screen shows it as unconfirmed.
 */
fun Map<SeatPosition, Boolean>.hasPassenger(): Boolean {
    val passengers = filterKeys { it != DRIVER_SEAT }
    return passengers.isEmpty() || passengers.values.any { it }
}

/** The car's seat for each seat the app drives. */
val CabinPlace.position: SeatPosition
    get() = when (this) {
        CabinPlace.PASSENGER -> SeatPosition.ROW_1_RIGHT
        CabinPlace.REAR_LEFT -> SeatPosition.ROW_2_LEFT
        CabinPlace.REAR_RIGHT -> SeatPosition.ROW_2_RIGHT
    }

/**
 * Whether someone sits in that very seat. A seat that has not reported counts as taken only when no
 * passenger seat has reported at all, as in [hasPassenger]: then the car cannot tell, and the screen
 * shows it as unconfirmed.
 */
fun Map<SeatPosition, Boolean>.isTaken(position: SeatPosition): Boolean =
    this[position] ?: filterKeys { it != DRIVER_SEAT }.isEmpty()

/** The ON/OFF model for one seat: a forward gear and someone in that very seat. */
fun VehicleSignal.runsMotionFor(position: SeatPosition): Boolean = gear.runsMotion() && seats.isTaken(position)

/** Whether each seat the app drives is taken, by [isTaken]. */
fun Map<SeatPosition, Boolean>.occupancy() = Occupancy(
    passenger = isTaken(CabinPlace.PASSENGER.position),
    rearLeft = isTaken(CabinPlace.REAR_LEFT.position),
    rearRight = isTaken(CabinPlace.REAR_RIGHT.position),
)
