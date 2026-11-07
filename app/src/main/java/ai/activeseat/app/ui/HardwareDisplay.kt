package ai.activeseat.app.ui

import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.hint.LongitudinalCue
import kotlin.math.max
import kotlin.math.roundToInt

/** Real degrees are too small to see on stage, so the panel draws them this many times larger. */
const val DISPLAY_SCALE = 4f

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

fun isForecastActive(hint: Hint): Boolean = hint.lateral != LateralCue.NONE || hint.longitudinal != LongitudinalCue.NONE

/** The stronger axis, as a whole percentage. */
fun strengthPercent(hint: Hint): Int = (max(hint.lateralStrength, hint.longitudinalStrength) * 100).roundToInt()

fun percentText(probability: Double): String = "${(probability * 100).roundToInt()}%"

/** A setting level as the settings panel shows it: off reads as 끔. */
fun levelText(level: Int): String = if (level <= 0) "끔" else level.toString()

/** The level 0..[max] nearest a point [fraction] of the way along a slider track. */
fun nearestLevel(fraction: Float, max: Int): Int =
    if (fraction.isNaN()) 0 else (fraction * max).roundToInt().coerceIn(0, max)
