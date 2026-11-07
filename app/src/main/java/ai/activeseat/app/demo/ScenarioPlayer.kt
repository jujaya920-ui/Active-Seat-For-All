package ai.activeseat.app.demo

import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val FRAME_INTERVAL_MS = 200L
const val HEARTBEAT_INTERVAL_MS = 800L

// The bridge needs a few heartbeats before it forwards writes to the vehicle.
const val BRIDGE_WARMUP_MS = 3000L

// Keeps the heartbeat alive after the reset so the zeros reach the vehicle.
const val RESET_SETTLE_MS = 1000L

/** Every seat the Virtual Console's Seat Occupancy control knows. */
val CONSOLE_SEATS = listOf("driver", "passenger", "rear_left", "rear_right")

sealed interface PlayerState {
    data object Idle : PlayerState
    data object Warmup : PlayerState
    data class Playing(val tSec: Double, val durationSec: Double) : PlayerState
    data class Finished(val failedWrites: Int) : PlayerState
}

/**
 * Plays a scenario into the Virtual Console at 5 Hz while keeping the browser session alive.
 * Steering is negated on write: the scenario uses left-positive, the console right-positive.
 * The console is reset to zero however playback ends, including cancellation.
 */
class ScenarioPlayer(
    private val console: ConsoleApi,
    private val clockMs: () -> Long,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var failedWrites = 0

    /** Plays `fromSec` to `toSec` of the timeline, by default all of it. */
    suspend fun play(scenario: Scenario, fromSec: Double = 0.0, toSec: Double = scenario.durationSec) {
        failedWrites = 0
        coroutineScope {
            val heartbeat = launch {
                while (isActive) {
                    write { console.session() }
                    delay(HEARTBEAT_INTERVAL_MS)
                }
            }
            try {
                _state.value = PlayerState.Warmup
                delay(BRIDGE_WARMUP_MS)
                write { console.gear("Drive") }
                // The app reads occupancy only through the Vehicle SDK, as in a real car, so the
                // scenario seats its riders in the console; with every seat empty no hint goes out.
                scenario.occupiedSeats?.let { seated ->
                    CONSOLE_SEATS.forEach { write { console.seatOccupied(it, it in seated) } }
                }
                playTimeline(scenario, fromSec, toSec)
            } finally {
                withContext(NonCancellable) {
                    write { console.controls(ConsoleControls(0, 0, 0, 0)) }
                    write { console.turnSignal(TURN_OFF) }
                    delay(RESET_SETTLE_MS)
                    heartbeat.cancel()
                    log("PLAY reset failedWrites=$failedWrites")
                    _state.value = PlayerState.Finished(failedWrites)
                }
            }
        }
    }

    private suspend fun playTimeline(scenario: Scenario, fromSec: Double, toSec: Double) {
        val frameCount = ceil((toSec - fromSec) * 1000 / FRAME_INTERVAL_MS).toInt() + 1
        val start = clockMs()
        var lastTurn: String? = null
        var index = 0
        while (index < frameCount && currentCoroutineContext().isActive) {
            val tSec = fromSec + index * FRAME_INTERVAL_MS / 1000.0
            val frame = scenario.frameAt(tSec)
            val controls = frame.toConsoleControls()
            log("PLAY t=$tSec $controls turn=${frame.turnSignal}")
            write { console.controls(controls) }
            if (frame.turnSignal != lastTurn) {
                write { console.turnSignal(frame.turnSignal) }
                lastTurn = frame.turnSignal
            }
            _state.value = PlayerState.Playing(tSec, toSec)

            // Stay on the time grid: skip frames rather than stretch the scenario when late.
            index = max(index + 1, ((clockMs() - start) / FRAME_INTERVAL_MS).toInt() + 1)
            delay(start + index * FRAME_INTERVAL_MS - clockMs())
        }
    }

    private suspend fun write(call: suspend () -> Unit) {
        try {
            call()
        } catch (e: IOException) {
            failedWrites++
            log("PLAY write failed: $e")
        }
    }
}
