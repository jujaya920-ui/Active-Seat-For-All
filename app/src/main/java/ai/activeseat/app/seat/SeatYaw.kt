package ai.activeseat.app.seat

import ai.activeseat.app.motion.MotionConfig
import ai.activeseat.app.vehicle.SeatPosition
import kotlin.math.max
import kotlin.math.min

private const val KPH_TO_MPS = 1 / 3.6

/** How far ahead the seat looks: the time before a turn at which the turn prediction starts. */
private val LOOK_AHEAD_SEC = MotionConfig().let { it.leadSec + it.approachSec }

/** Below this the turn rate means little, and an acceleration held after stopping would blow up. Provisional. */
private const val MIN_SPEED_MPS = 5.0

/** A rear seat turns this much toward its door, away from the view between the front seats. Provisional. */
private const val DOOR_SIDE_SHARE = 0.5

/** Provisional, chosen to read clearly without swinging the passenger around. */
const val MAX_YAW_DEG = 20.0

/** The speed a settings preview is shown at, since it plays with the car standing still. */
const val PREVIEW_SPEED_KPH = 36.0

/** What the seat's turn depends on besides the hint. */
data class YawContext(val speedKph: Double, val position: SeatPosition)

/**
 * Faces the seat where the car will be heading after [LOOK_AHEAD_SEC], from the turn rate
 * (lateral acceleration over speed). A rear seat favours the side with a view forward. [leftSign] is
 * 1 for a left turn, -1 for a right turn and 0 for none; the result is positive to the left.
 */
fun yawDeg(leftSign: Double, lateralMps2: Double, context: YawContext): Double {
    val speedMps = max(context.speedKph * KPH_TO_MPS, MIN_SPEED_MPS)
    val full = Math.toDegrees(lateralMps2 / speedMps * LOOK_AHEAD_SEC)
    // The limit comes first, so the door side stays smaller however hard the corner.
    return leftSign * min(full, MAX_YAW_DEG) * viewShare(leftSign, context.position)
}

private fun viewShare(leftSign: Double, position: SeatPosition): Double = when {
    position == SeatPosition.ROW_2_LEFT && leftSign > 0 -> DOOR_SIDE_SHARE
    position == SeatPosition.ROW_2_RIGHT && leftSign < 0 -> DOOR_SIDE_SHARE
    else -> 1.0
}
