package ai.activeseat.app.ui

import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.hint.LongitudinalCue
import kotlin.math.max
import kotlin.math.roundToInt

/** The angles are the real cancelling angles, large enough to see, so the panel draws them as they are. */
const val DISPLAY_SCALE = 1f

/** Provisional strength from which a longitudinal forecast reads as sudden rather than ordinary. */
private const val SUDDEN_STRENGTH = 0.7

fun forecastText(hint: Hint): String {
    val lateral = when (hint.lateral) {
        LateralCue.LEFT -> "좌회전"
        LateralCue.RIGHT -> "우회전"
        LateralCue.NONE -> null
    }
    val sudden = hint.longitudinalStrength >= SUDDEN_STRENGTH
    val longitudinal = when (hint.longitudinal) {
        LongitudinalCue.BRAKE -> if (sudden) "급정거" else "감속"
        LongitudinalCue.ACCELERATE -> if (sudden) "급출발" else "가속"
        LongitudinalCue.NONE -> null
    }
    return listOfNotNull(lateral, longitudinal).joinToString(" + ").ifEmpty { "유지" }
}

/** The title: a live hint always shows; with none, why the seat is at rest. */
fun seatStatusText(hint: Hint, occupancy: SeatOccupancy): String = when {
    isForecastActive(hint) || occupancy == SeatOccupancy.OCCUPIED -> forecastText(hint)
    occupancy == SeatOccupancy.EMPTY -> "미탑승"
    else -> "탑승 미확인"
}

/** The line under an idle seat, null for a seat with someone in it. */
fun occupancyNotice(occupancy: SeatOccupancy): String? = when (occupancy) {
    SeatOccupancy.OCCUPIED -> null
    SeatOccupancy.EMPTY -> "탑승이 감지되면 시트가 작동합니다"
    SeatOccupancy.UNCONFIRMED -> "탑승 여부를 확인할 수 없습니다"
}

fun isForecastActive(hint: Hint): Boolean = hint.lateral != LateralCue.NONE || hint.longitudinal != LongitudinalCue.NONE

/** The stronger axis, as a whole percentage. */
fun strengthPercent(hint: Hint): Int = (max(hint.lateralStrength, hint.longitudinalStrength) * 100).roundToInt()

fun percentText(probability: Double): String = "${(probability * 100).roundToInt()}%"

/** A setting level as the settings panel shows it: off reads as 끔. */
fun levelText(level: Int): String = if (level <= 0) "끔" else level.toString()

/** The level 0..[max] nearest a point [fraction] of the way along a slider track. */
fun nearestLevel(fraction: Float, max: Int): Int =
    if (fraction.isNaN()) 0 else (fraction * max).roundToInt().coerceIn(0, max)
