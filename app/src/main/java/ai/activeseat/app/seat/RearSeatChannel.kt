package ai.activeseat.app.seat

import ai.activeseat.app.hardware.HardwareCommand
import ai.activeseat.app.hardware.HardwareSettings
import ai.activeseat.app.hardware.toHardwareCommand
import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.MotionProbabilities
import ai.activeseat.app.hint.toProbabilities
import ai.activeseat.app.motion.MotionConfig
import ai.activeseat.app.motion.MotionEngine
import ai.activeseat.app.motion.NavSnapshot
import ai.activeseat.app.motion.PreviewManeuver
import ai.activeseat.app.motion.previewHint
import ai.activeseat.app.vehicle.SeatPosition
import ai.activeseat.app.vehicle.VehicleSignal
import ai.activeseat.app.vehicle.runsMotionFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PREVIEW_STEP_MS = 2500L

/** What one rear seat shows and how it is set. */
data class RearSeatState(
    val hardware: HardwareCommand = HardwareCommand(),
    val forecast: Hint = Hint(),
    val probabilities: MotionProbabilities = MotionProbabilities(),
    val settings: HardwareSettings = HardwareSettings(),
    val previewLabel: String? = null,
    /** Bumped on every settings change: the display snaps this seat and its watch to rest. */
    val restKey: Int = 0,
)

/**
 * One rear seat's own engines, settings and preview. The sampling and navigation are shared with the
 * passenger seat: [step] is called from the app's 0.2 s sample loop.
 */
class RearSeatChannel(
    val position: SeatPosition,
    private val store: SeatSettingsStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(RearSeatState(settings = store.load()))
    val state: StateFlow<RearSeatState> = _state.asStateFlow()

    @Volatile private var seatEngine = engineFor(_state.value.settings.seatSensitivity)
    @Volatile private var watchEngine = engineFor(_state.value.settings.watchSensitivity)
    @Volatile private var seatHint = Hint()
    @Volatile private var watchHint = Hint()
    @Volatile private var preview: PreviewManeuver? = null
    @Volatile private var speedKph = 0.0
    private var previewJob: Job? = null

    fun step(signal: VehicleSignal, nowMs: Long, nav: NavSnapshot) {
        val active = signal.runsMotionFor(position)
        speedKph = signal.speedKph
        seatHint = seatEngine?.step(signal, nowMs, nav, active)?.hint ?: Hint()
        watchHint = watchEngine?.step(signal, nowMs, nav, active)?.hint ?: Hint()
        publish()
    }

    fun updateSettings(next: HardwareSettings) {
        if (next != _state.value.settings) apply(next)
    }

    fun resetSettings() = apply(HardwareSettings())

    /** Plays each preview scene in turn at this seat's sensitivities. */
    fun startPreview() {
        if (_state.value.settings.allOff || previewJob?.isActive == true) return
        previewJob = scope.launch {
            try {
                for (maneuver in PreviewManeuver.entries) {
                    preview = maneuver
                    _state.update { it.copy(previewLabel = maneuver.label) }
                    publish()
                    delay(PREVIEW_STEP_MS)
                }
            } finally {
                preview = null
                _state.update { it.copy(previewLabel = null) }
                publish()
            }
        }
    }

    private fun apply(next: HardwareSettings) {
        previewJob?.cancel()
        val previous = _state.value.settings
        if (next.seatSensitivity != previous.seatSensitivity) seatEngine = engineFor(next.seatSensitivity)
        if (next.watchSensitivity != previous.watchSensitivity) watchEngine = engineFor(next.watchSensitivity)
        store.save(next)
        _state.update { it.copy(settings = next, restKey = it.restKey + 1) }
        publish()
    }

    private fun publish() {
        val settings = _state.value.settings
        val seat = shownHint(settings.seatSensitivity, seatHint)
        val watch = shownHint(settings.watchSensitivity, watchHint)
        val shown = if (maxOf(watch.lateralStrength, watch.longitudinalStrength) > maxOf(seat.lateralStrength, seat.longitudinalStrength)) watch else seat
        val yaw = YawContext(if (preview != null) PREVIEW_SPEED_KPH else speedKph, position)
        _state.update { it.copy(hardware = toHardwareCommand(seat, watch, settings, yaw), forecast = shown, probabilities = shown.toProbabilities()) }
    }

    private fun shownHint(sensitivity: Int, engineHint: Hint): Hint {
        val scale = HardwareSettings.thresholdScale(sensitivity) ?: return Hint()
        return preview?.let { MotionConfig().scaled(scale).previewHint(it) } ?: engineHint
    }

    private fun engineFor(sensitivity: Int): MotionEngine? =
        HardwareSettings.thresholdScale(sensitivity)?.let { MotionEngine(MotionConfig().scaled(it)) }
}
