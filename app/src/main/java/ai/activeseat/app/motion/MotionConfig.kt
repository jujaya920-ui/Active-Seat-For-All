package ai.activeseat.app.motion

/**
 * Every threshold of the motion engine in one place. Values are provisional: set by replaying the
 * generated scenarios, and to be set again from real driving logs when there are any.
 */
data class MotionConfig(
    // Vehicle constants: the scenario generator's car, to be checked against the real one.
    val steeringRatio: Double = 14.0,
    val wheelbaseM: Double = 2.9,

    // Measured model.
    /** Speed samples in the least-squares slope, 0.2 s apart. */
    val slopeSamples: Int = 5,
    /** A_LAT, A_BRAKE, A_ACCEL: the motion a passenger feels, not a harsh one. */
    val lateralOnMps2: Double = 0.8,
    val brakeOnMps2: Double = 1.0,
    val accelOnMps2: Double = 1.0,
    /** A measured cue stays on down to this share of its on threshold. */
    val offRatio: Double = 0.6,
    /** Where vibration strength levels 2, 3 and 4 begin. Fixed: the sensitivity does not move them. */
    val levelBoundsMps2: List<Double> = listOf(1.5, 2.5, 4.0),

    // Predicted model.
    /** T_LEAD: how long before the motion starts the hint should start. */
    val leadSec: Double = 1.0,
    /** T_APPROACH: how long before reaching the turn point the car starts to turn. */
    val approachSec: Double = 1.2,
    /**
     * The lateral acceleration a predicted turn is expected to bring, since none can be measured
     * before the wheel turns: sharp turns and U-turns, ordinary turns, and lane keeps or ramps.
     * A planning choice, not measured.
     */
    val sharpTurnMps2: Double = 3.0,
    val turnMps2: Double = 2.0,
    val gentleTurnMps2: Double = 1.0,
    /** V_MIN: below this the time to a turn means nothing. */
    val minSpeedKph: Double = 5.0,
    /** A_REQ: braking to the target speed would need at least this, so the driver brakes now. */
    val requiredDecelMps2: Double = 1.3,
    /** V_MARGIN: within this of the target speed there is nothing to brake for. */
    val speedMarginKph: Double = 3.0,
    /** V_BUMP: the speed a driver takes a speed bump at. */
    val bumpKph: Double = 20.0,
    /** Korean school zones are limited to 30 km/h. */
    val schoolZoneKph: Double = 30.0,
    /** T_GRACE: after passing the point, a prediction waits this long for the motion. */
    val graceMs: Long = 500,
    /** T_PRED_MAX: no prediction stays on longer than this without the motion. */
    val maxPredictMs: Long = 8000,
    /** The same maneuver reported again this much farther away is a new one. */
    val newTurnJumpM: Double = 20.0,

    // Output.
    /** A cue stays on at least this long, unless the measured motion changes it. */
    val minHoldMs: Long = 1000,
) {
    /**
     * The on thresholds times [factor], for a passenger's sensitivity. Sensitivity decides only whether
     * a cue turns on: the vibration bands and the lean angle stay, so a dull setting that turns on only
     * for a hard motion also vibrates hard. Nav turn prediction has no acceleration threshold.
     */
    fun scaled(factor: Double): MotionConfig = copy(
        lateralOnMps2 = lateralOnMps2 * factor,
        brakeOnMps2 = brakeOnMps2 * factor,
        accelOnMps2 = accelOnMps2 * factor,
        requiredDecelMps2 = requiredDecelMps2 * factor,
    )

    /** Vibration strength level 1..4 for an acceleration magnitude. */
    fun level(magnitudeMps2: Double): Int = (1 + levelBoundsMps2.count { magnitudeMps2 >= it }).coerceAtMost(MAX_LEVEL)

    companion object {
        const val MAX_LEVEL = 4
    }
}
