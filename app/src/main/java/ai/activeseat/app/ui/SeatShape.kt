package ai.activeseat.app.ui

import ai.activeseat.app.vehicle.SeatPosition

/** A headrest post, drawn as a line between two points at model x. */
class SeatPost(val x: Float, val y0: Float, val z0: Float, val y1: Float, val z1: Float)

/**
 * What one seat looks like. [parts] lean with the seat and are indexed by PART_*. [fixture] stays with
 * the car, listed back to front.
 */
class SeatShape(
    val parts: List<SeatPart>,
    val posts: List<SeatPost>,
    val fixture: List<SeatPart> = emptyList(),
)

val PASSENGER_SHAPE: SeatShape by lazy {
    SeatShape(SEAT_PARTS, listOf(SeatPost(-6f, 62f, -23f, 70f, -21f), SeatPost(6f, 62f, -23f, 70f, -21f)))
}

/**
 * The seats a passenger can sit in. Each side view looks from the door side, so [sideDirection] says
 * which way forward points on screen there.
 */
enum class CabinSeat(val label: String, val position: SeatPosition, val sideCamera: SeatCamera, val sideDirection: Float) {
    PASSENGER("조수석", SeatPosition.ROW_1_RIGHT, SeatCamera.SIDE, 1f),
    REAR_LEFT("뒷좌석 왼쪽", SeatPosition.ROW_2_LEFT, SeatCamera.SIDE_MIRRORED, -1f),
    REAR_RIGHT("뒷좌석 오른쪽", SeatPosition.ROW_2_RIGHT, SeatCamera.SIDE, 1f),
    ;

    val shape: SeatShape
        get() = when (this) {
            PASSENGER -> PASSENGER_SHAPE
            REAR_LEFT -> REAR_LEFT_SHAPE
            REAR_RIGHT -> REAR_RIGHT_SHAPE
        }
}

enum class SeatOccupancy { OCCUPIED, EMPTY, UNCONFIRMED }

/** A seat that has never reported is unconfirmed, not empty. */
fun CabinSeat.occupancy(seats: Map<SeatPosition, Boolean>): SeatOccupancy = when (seats[position]) {
    true -> SeatOccupancy.OCCUPIED
    false -> SeatOccupancy.EMPTY
    null -> SeatOccupancy.UNCONFIRMED
}

/** The seat to open on: the first one taken, or the passenger seat with nobody aboard. */
fun firstOccupiedSeat(seats: Map<SeatPosition, Boolean>): CabinSeat =
    CabinSeat.entries.firstOrNull { it.occupancy(seats) == SeatOccupancy.OCCUPIED } ?: CabinSeat.PASSENGER
