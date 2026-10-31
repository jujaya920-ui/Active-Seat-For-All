package ai.activeseat.app.hardware

/**
 * One seat module's target, in assumed real-hardware units. The screen applies its own scale.
 * rollDeg is positive to the left, pitchDeg positive forward, yawDeg positive to the left.
 */
data class ModuleCommand(
    val vibration: Double = 0.0,
    val rollDeg: Double = 0.0,
    val pitchDeg: Double = 0.0,
    val yawDeg: Double = 0.0,
)

/** Pulse lengths in ms, in order, with a fixed gap between them. */
enum class WatchPattern(val pulsesMs: List<Long>) {
    NONE(emptyList()),
    LEFT(listOf(120L, 120L)),
    RIGHT(listOf(500L)),
    BRAKE(listOf(500L, 500L)),
    ACCELERATE(listOf(120L, 120L, 120L)),
}

data class HardwareCommand(
    val headrest: ModuleCommand = ModuleCommand(),
    val backrest: ModuleCommand = ModuleCommand(),
    val cushion: ModuleCommand = ModuleCommand(),
    val watch: WatchPattern = WatchPattern.NONE,
    val watchStrength: Double = 0.0,
)
