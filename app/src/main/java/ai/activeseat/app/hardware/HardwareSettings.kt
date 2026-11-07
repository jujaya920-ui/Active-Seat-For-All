package ai.activeseat.app.hardware

/** Every setting is a level: 0 is off, 1..5 rise in strength, speed or sensitivity. */
const val LEVEL_OFF = 0
const val LEVEL_MAX = 5
const val LEVEL_DEFAULT = 3

/**
 * The passenger's settings for the seat and the watch. Provisional step values, see the plan's
 * 사용자 설정: sensitivity scales the rule engine's thresholds, vibration scales the output, and
 * lean speed is the time the seat takes to reach its lean.
 */
data class HardwareSettings(
    val seatSensitivity: Int = LEVEL_DEFAULT,
    val seatVibration: Int = LEVEL_DEFAULT,
    val leanSpeed: Int = LEVEL_DEFAULT,
    val watchSensitivity: Int = LEVEL_DEFAULT,
    val watchVibration: Int = LEVEL_DEFAULT,
) {
    val seatOn: Boolean get() = seatSensitivity != LEVEL_OFF
    val watchOn: Boolean get() = watchSensitivity != LEVEL_OFF

    /** Both off: the app controls no hardware at all. */
    val allOff: Boolean get() = !seatOn && !watchOn

    companion object {
        /** Threshold multiplier for sensitivity 1..5; 3 keeps MotionConfig as it is. */
        private val SENSITIVITY_SCALE = doubleArrayOf(0.0, 2.0, 1.4, 1.0, 0.7, 0.5)

        /** Seconds to reach 95% of the lean for lean speed 1..5. */
        private val LEAN_SECONDS = doubleArrayOf(0.0, 8.0, 4.0, 2.0, 1.0, 0.5)

        /** Null when off. */
        fun thresholdScale(sensitivity: Int): Double? =
            if (sensitivity == LEVEL_OFF) null else SENSITIVITY_SCALE[sensitivity.coerceIn(1, LEVEL_MAX)]

        /** Null when off: the seat does not lean at all. */
        fun leanSeconds(leanSpeed: Int): Double? =
            if (leanSpeed == LEVEL_OFF) null else LEAN_SECONDS[leanSpeed.coerceIn(1, LEVEL_MAX)]

        fun vibrationScale(level: Int): Double = level.coerceIn(LEVEL_OFF, LEVEL_MAX).toDouble() / LEVEL_MAX
    }
}
