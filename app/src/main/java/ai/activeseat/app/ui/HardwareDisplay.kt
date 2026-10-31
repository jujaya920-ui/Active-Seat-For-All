package ai.activeseat.app.ui

import ai.activeseat.app.hardware.ModuleCommand
import ai.activeseat.app.hardware.WatchPattern
import java.util.Locale
import kotlin.math.abs

/** Real degrees are too small to see on stage, so the panel draws them this many times larger. */
const val DISPLAY_SCALE = 4f

const val ARROW_THRESHOLD_DEG = 0.2f
const val WATCH_GAP_MS = 150L
const val WATCH_REST_MS = 800L

enum class LateralArrow { NONE, LEFT, RIGHT }

enum class PitchArrow { NONE, FORWARD, BACK }

/** For roll and yaw, both positive to the left. */
fun lateralArrow(deg: Float): LateralArrow = when {
    deg > ARROW_THRESHOLD_DEG -> LateralArrow.LEFT
    deg < -ARROW_THRESHOLD_DEG -> LateralArrow.RIGHT
    else -> LateralArrow.NONE
}

fun pitchArrow(deg: Float): PitchArrow = when {
    deg > ARROW_THRESHOLD_DEG -> PitchArrow.FORWARD
    deg < -ARROW_THRESHOLD_DEG -> PitchArrow.BACK
    else -> PitchArrow.NONE
}

/** Scaled clockwise canvas rotation for roll or yaw: positive left is counterclockwise on screen. */
fun screenLateralDeg(realDeg: Float): Float = -realDeg * DISPLAY_SCALE

/** Scaled clockwise canvas rotation for pitch. The side view faces right, so forward is clockwise. */
fun screenPitchDeg(realDeg: Float): Float = realDeg * DISPLAY_SCALE

fun isModuleActive(module: ModuleCommand): Boolean =
    module.vibration > 0.0 ||
        lateralArrow(module.rollDeg.toFloat()) != LateralArrow.NONE ||
        pitchArrow(module.pitchDeg.toFloat()) != PitchArrow.NONE ||
        lateralArrow(module.yawDeg.toFloat()) != LateralArrow.NONE

fun lateralText(realDeg: Double): String {
    val side = when (lateralArrow(realDeg.toFloat())) {
        LateralArrow.LEFT -> "왼쪽 "
        LateralArrow.RIGHT -> "오른쪽 "
        LateralArrow.NONE -> ""
    }
    return side + degrees(realDeg)
}

fun pitchText(realDeg: Double): String {
    val side = when (pitchArrow(realDeg.toFloat())) {
        PitchArrow.FORWARD -> "앞으로 "
        PitchArrow.BACK -> "뒤로 "
        PitchArrow.NONE -> ""
    }
    return side + degrees(realDeg)
}

private fun degrees(realDeg: Double): String = String.format(Locale.US, "%.1f°", abs(realDeg))

fun watchPatternText(pattern: WatchPattern): String = when (pattern) {
    WatchPattern.NONE -> "대기"
    WatchPattern.LEFT -> "왼쪽"
    WatchPattern.RIGHT -> "오른쪽"
    WatchPattern.BRAKE -> "제동"
    WatchPattern.ACCELERATE -> "가속"
}

/** Whether a pulse is on at [elapsedMs] since the pattern started, repeating with a rest. */
fun isWatchPulseOn(pulsesMs: List<Long>, elapsedMs: Long): Boolean {
    if (pulsesMs.isEmpty()) return false
    val cycleMs = pulsesMs.sum() + WATCH_GAP_MS * (pulsesMs.size - 1) + WATCH_REST_MS
    var t = elapsedMs % cycleMs
    for (i in pulsesMs.indices) {
        if (t < pulsesMs[i]) return true
        t -= pulsesMs[i] + WATCH_GAP_MS
        if (t < 0) return false
    }
    return false
}
