package ai.activeseat.app.vehicle

enum class TurnSignal(val wire: String) {
    OFF("off"),
    LEFT("left"),
    RIGHT("right"),
}

/** Latest vehicle state. Steering is positive to the left (SDK convention). */
data class VehicleSignal(
    val speedKph: Double = 0.0,
    val steeringDeg: Double = 0.0,
    val brake: Boolean = false,
    val turnSignal: TurnSignal = TurnSignal.OFF,
)

/** One entry of the prediction contract's `signals` array. */
data class SignalSample(
    val ageMs: Long,
    val speedKph: Double,
    val steeringDeg: Double,
    val brake: Boolean,
    val turnSignal: String,
)
