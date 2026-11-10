package ai.activeseat.app.ui

import ai.activeseat.app.BuildConfig
import ai.activeseat.app.demo.ConsoleClient
import ai.activeseat.app.demo.PlayerState
import ai.activeseat.app.demo.Scenario
import ai.activeseat.app.demo.ScenarioPlayer
import ai.activeseat.app.demo.ScenarioRepository
import ai.activeseat.app.demo.toNavRoute
import ai.activeseat.app.demo.toPlannedRoute
import ai.activeseat.app.hardware.HardwareCommand
import ai.activeseat.app.hardware.HardwareSettings
import ai.activeseat.app.hardware.Occupancy
import ai.activeseat.app.hardware.LEVEL_MAX
import ai.activeseat.app.hardware.LEVEL_OFF
import ai.activeseat.app.hardware.CabinPlace
import ai.activeseat.app.hardware.CabinSeat
import ai.activeseat.app.hardware.seatCommand
import ai.activeseat.app.vehicle.occupancy
import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.hint.LongitudinalCue
import ai.activeseat.app.hint.MotionProbabilities
import ai.activeseat.app.motion.CueSource
import ai.activeseat.app.motion.MotionConfig
import ai.activeseat.app.motion.MotionEngine
import ai.activeseat.app.motion.MotionOutput
import ai.activeseat.app.motion.NaviFeed
import ai.activeseat.app.motion.PreviewManeuver
import ai.activeseat.app.motion.previewHint
import ai.activeseat.app.nav.FakeNaviHelper
import ai.activeseat.app.nav.Guidance
import ai.activeseat.app.nav.NavRoute
import ai.activeseat.app.nav.NavState
import ai.activeseat.app.vehicle.CarSignalSource
import ai.activeseat.app.vehicle.DRIVER_SEAT
import ai.activeseat.app.vehicle.Gear
import ai.activeseat.app.vehicle.position
import ai.activeseat.app.vehicle.runsMotionFor
import ai.activeseat.app.vehicle.SignalSampler
import ai.activeseat.app.vehicle.VehicleSignal
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

private const val TAG = "ActiveSeat"
private const val PREVIEW_STEP_MS = 2500L

// A full-strength test hint: level 4 vibration, and the lean of a hard 4 m/s² motion.
private const val TEST_MPS2 = 4.0
private const val TEST_WHEEL_DEG = 25.0

enum class NavScreen { HOME, FAVORITES, PREVIEW, GUIDING }

/** A favorite on the navigation panel. `demo` is absent when the demo window is the whole route. */
data class Destination(
    val id: String,
    val title: String,
    val originRoad: String,
    val full: RouteChoice,
    val demo: RouteChoice?,
) {
    val choices: List<RouteChoice> get() = listOfNotNull(demo, full)
}

data class UiState(
    val signal: VehicleSignal = VehicleSignal(),
    val speedConnected: Boolean = false,
    val destinations: List<Destination> = emptyList(),
    val selectedScenario: String? = null,
    val screen: NavScreen = NavScreen.HOME,
    val originRoad: String = "",
    val playerError: String? = null,
    val motionStatus: String = "대기 중",
    val route: NavRoute? = null,
    val map: RouteMap? = null,
    val nav: NavState? = null,
    val hardware: HardwareCommand = HardwareCommand(),
    val forecast: Hint = Hint(),
    val probabilities: MotionProbabilities = MotionProbabilities(),
    /** Each seat's own settings. The settings panel edits [selectedPlace]'s. */
    val seatSettings: Map<CabinPlace, HardwareSettings> = CabinPlace.entries.associateWith { HardwareSettings() },
    /** The seat tab in view. */
    val selectedSeat: CabinSeat = CabinSeat.PASSENGER,
    /** Which rear seat the rear tab's title, bars, watch and settings follow. */
    val selectedRear: CabinPlace = CabinPlace.REAR_LEFT,
    val settingsOpen: Boolean = false,
    /** The label of the preview scene playing, null when no preview plays. */
    val previewLabel: String? = null,
    /** Bumped on every settings change or reset: the display snaps the seat and watch to rest. */
    val restKey: Int = 0,
) {
    /** The seat the title, bars, watch and settings follow: the passenger seat, or the chosen rear seat. */
    val selectedPlace: CabinPlace get() = if (selectedSeat == CabinSeat.PASSENGER) CabinPlace.PASSENGER else selectedRear
    val settings: HardwareSettings get() = seatSettings.getValue(selectedPlace)
    val hintsOff: Boolean get() = settings.allOff
}

/**
 * One seat's own settings, and the seat and watch engines that run at its sensitivities; an engine
 * is null when turned off.
 */
private class SeatChannel(settings: HardwareSettings, engineFor: (Int) -> MotionEngine?) {
    @Volatile var seatEngine: MotionEngine? = engineFor(settings.seatSensitivity)
    @Volatile var watchEngine: MotionEngine? = engineFor(settings.watchSensitivity)
    @Volatile var lastSeatHint = Hint()
    @Volatile var lastWatchHint = Hint()
}

/** Wires the packages together: the only place that sees both demo and product code. */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val clock = { SystemClock.elapsedRealtime() }
    private val source = CarSignalSource(application)
    private val sampler = SignalSampler(source, clock)
    private val scenarios = ScenarioRepository(application)
    private val player = ScenarioPlayer(ConsoleClient(), clock) { Log.i(TAG, it) }
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val naviFeed = NaviFeed()
    @Volatile
    private var lastNaviEmitMs: Long? = null
    private var playJob: Job? = null

    @Volatile
    private var loaded: Map<String, Scenario> = emptyMap()

    // Scenarios load in the background, so a start asked for early waits for them.
    private var pendingStart: Pair<String, Boolean>? = null

    // Each seat runs its own seat and watch engines at its own sensitivities.
    private lateinit var channels: Map<CabinPlace, SeatChannel>

    // Until the passenger picks a seat, the screen opens on the first one taken.
    @Volatile
    private var seatChosen = false

    // The preview scene playing, shown instead of the engines' hints.
    @Volatile
    private var preview: PreviewManeuver? = null
    private var previewJob: Job? = null

    // A test value shown instead of the real hint, for checking the modules without driving.
    @Volatile
    private var testHint: Hint? = null

    // The car's position on the selected route and what the navigation guides there.
    @Volatile
    private var guidance: Guidance? = null

    // Stands in for the built-in navigation on the selected route. The engine hears it only
    // through the NaviHelper listener, as it would the real one.
    @Volatile
    private var fakeNavi: FakeNaviHelper? = null

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    val playerState: StateFlow<PlayerState> = player.state

    init {
        val settings = CabinPlace.entries.associateWith { loadSettings(it) }
        channels = settings.mapValues { (_, value) -> SeatChannel(value, ::engineFor) }
        _ui.update { it.copy(seatSettings = settings) }

        viewModelScope.launch(Dispatchers.IO) { loadScenarios() }

        source.start()
        refreshSpeed()
        viewModelScope.launch(Dispatchers.Default) {
            sampler.run { signal ->
                val now = clock()
                guidance?.onSpeed(signal.speedKph, now)
                emitNavi(signal.speedKph, now)
                val nav = naviFeed.snapshot
                // The ON/OFF model: out of a forward gear or with no passenger the engines reset.
                // Each seat runs only with someone in that very seat.
                val outputs = channels.mapValues { (place, channel) ->
                    val active = signal.runsMotionFor(place.position)
                    val seatOutput = channel.seatEngine?.step(signal, now, nav, active)
                    val watchOutput = channel.watchEngine?.step(signal, now, nav, active)
                    channel.lastSeatHint = seatOutput?.hint ?: Hint()
                    channel.lastWatchHint = watchOutput?.hint ?: Hint()
                    seatOutput ?: watchOutput
                }
                _ui.update {
                    val opened = if (seatChosen) it else it.openOnTakenSeat(signal.seats.occupancy())
                    opened.copy(signal = signal, nav = guidance?.state(), motionStatus = describe(outputs[opened.selectedPlace], signal))
                }
                publish()
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "SAMPLE $signal nav=${guidance?.state()?.progressM} phase=${guidance?.phase} outputs=$outputs")
                }
            }
        }
    }

    fun refreshSpeed() {
        source.connectSpeed()
        _ui.update { it.copy(speedConnected = source.speedConnected) }
    }

    fun openFavorites() {
        _ui.update { it.copy(screen = NavScreen.FAVORITES) }
    }

    fun goHome() {
        _ui.update { it.copy(screen = NavScreen.HOME) }
    }

    /** Shows the scenario's route. Its origin becomes the car's position, since each route starts elsewhere. */
    fun select(id: String) {
        place(id)
        _ui.update { it.copy(screen = NavScreen.PREVIEW) }
    }

    fun endGuidance() {
        stop()
        goHome()
    }

    /** Plays the scenario's demo window, or the whole route when `fullRoute` or it has none. */
    fun start(fullRoute: Boolean = false) {
        if (playJob?.isActive == true) return
        val id = _ui.value.selectedScenario ?: return
        val scenario = loaded[id] ?: return
        val window = scenario.demoWindow.takeUnless { fullRoute }
        guidance?.reset(window?.let { scenario.distanceAtM(it.fromSec) } ?: 0.0)
        _ui.update { it.copy(playerError = null, screen = NavScreen.GUIDING) }
        playJob = viewModelScope.launch(Dispatchers.Default) {
            if (window == null) player.play(scenario) else player.play(scenario, window.fromSec, window.toSec)
        }
    }

    /** For a test run asking before the scenarios finished loading. */
    fun startWhenLoaded(id: String, fullRoute: Boolean) {
        if (loaded.isEmpty()) {
            pendingStart = id to fullRoute
            return
        }
        select(id)
        start(fullRoute)
    }

    fun stop() {
        playJob?.cancel()
    }

    /** `left`, `right`, `brake` or `accelerate`; anything else returns to the real hint. */
    fun showTestHint(name: String) {
        testHint = when (name) {
            "left" -> Hint(lateral = LateralCue.LEFT, lateralStrength = 1.0, lateralMps2 = TEST_MPS2, wheelDeg = TEST_WHEEL_DEG)
            "right" -> Hint(lateral = LateralCue.RIGHT, lateralStrength = 1.0, lateralMps2 = TEST_MPS2, wheelDeg = TEST_WHEEL_DEG)
            "brake" -> Hint(longitudinal = LongitudinalCue.BRAKE, longitudinalStrength = 1.0, longitudinalMps2 = TEST_MPS2)
            "accelerate" -> Hint(longitudinal = LongitudinalCue.ACCELERATE, longitudinalStrength = 1.0, longitudinalMps2 = TEST_MPS2)
            else -> null
        }
        publish()
    }

    fun toggleSettings() {
        _ui.update { it.copy(settingsOpen = !it.settingsOpen) }
    }

    fun closeSettings() {
        _ui.update { it.copy(settingsOpen = false) }
    }

    /** Shows another seat's tab: its hints, and its settings when the panel is open. Stops a preview. */
    fun selectSeat(seat: CabinSeat) {
        if (seat == _ui.value.selectedSeat) return
        seatChosen = true
        stopPreview()
        _ui.update { it.copy(selectedSeat = seat, previewLabel = null) }
        publish()
    }

    /** Which rear seat the rear tab follows and edits. Stops a preview. */
    fun selectRear(place: CabinPlace) {
        if (place.tab != CabinSeat.REAR || place == _ui.value.selectedRear) return
        seatChosen = true
        stopPreview()
        _ui.update { it.copy(selectedRear = place, previewLabel = null) }
        publish()
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        preview = null
    }

    /** Opens on the first seat taken, the passenger seat first; stays put when nobody is aboard. */
    private fun UiState.openOnTakenSeat(occupancy: Occupancy): UiState {
        val taken = CabinPlace.entries.firstOrNull { occupancy.of(it) } ?: return this
        val rear = if (taken.tab == CabinSeat.REAR) taken else CabinPlace.entries.firstOrNull { it.tab == CabinSeat.REAR && occupancy.of(it) } ?: selectedRear
        return copy(selectedSeat = taken.tab, selectedRear = rear)
    }

    fun updateSettings(next: HardwareSettings) {
        if (next != _ui.value.settings) applySettings(next)
    }

    /** All five back to level 3. Stops a preview and rests the hardware even when nothing changed. */
    fun resetSettings() = applySettings(HardwareSettings())

    /** Plays each preview scene in turn at the current sensitivities. Ignored with both devices off. */
    fun startPreview() {
        if (_ui.value.settings.allOff || previewJob?.isActive == true) return
        previewJob = viewModelScope.launch {
            try {
                for (maneuver in PreviewManeuver.entries) {
                    preview = maneuver
                    _ui.update { it.copy(previewLabel = maneuver.label) }
                    publish()
                    delay(PREVIEW_STEP_MS)
                }
            } finally {
                // A settings change already cleared it and may have started another preview.
                if (previewJob === coroutineContext.job) clearPreview()
            }
        }
    }

    override fun onCleared() {
        source.stop()
    }

    private fun place(id: String) {
        val scenario = loaded[id] ?: return
        val route = scenario.toNavRoute()
        val next = Guidance(route, scenario.toPlannedRoute())
        guidance = next
        fakeNavi = FakeNaviHelper(next)
        naviFeed.clear()
        lastNaviEmitMs = null
        _ui.update {
            it.copy(
                selectedScenario = id,
                originRoad = scenario.originRoad,
                route = route,
                map = loadMap(scenario),
                nav = next.state(),
            )
        }
    }

    private fun loadScenarios() {
        loaded = scenarios.ids().mapNotNull { id ->
            runCatching { id to scenarios.load(id) }
                .onFailure { Log.e(TAG, "scenario $id failed", it) }
                .getOrNull()
        }.toMap()
        _ui.update { state -> state.copy(destinations = demoRoutes().map { it.toDestination() }) }
        demoRoutes().firstOrNull()?.let { place(it.id) }
        pendingStart?.let { (id, fullRoute) -> startWhenLoaded(id, fullRoute) }
    }

    // Test scenarios have no demo window and stay reachable only through the intent.
    private fun demoRoutes() = loaded.values.filter { it.demoWindow != null }

    /** NaviHelper-style reports once a second while a route is selected. */
    private fun emitNavi(speedKph: Double, nowMs: Long) {
        val navi = fakeNavi ?: return
        if (lastNaviEmitMs?.let { nowMs - it < FakeNaviHelper.EMIT_INTERVAL_MS } == true) return
        lastNaviEmitMs = nowMs
        navi.emit(speedKph, naviFeed)
    }

    /**
     * Shows each seat's device hints, each at that seat's own settings: the preview scene for the
     * seat in view, else the test hint, else its engine's. The title and bars follow the seat in
     * view, the stronger of its seat and watch hints, the seat on a tie.
     */
    private fun publish() {
        val state = _ui.value
        val occupancy = state.signal.seats.occupancy()
        val shown = CabinPlace.entries.associateWith { place ->
            val settings = state.seatSettings.getValue(place)
            val channel = channels.getValue(place)
            val scene = preview.takeIf { place == state.selectedPlace }
            val seat = shownHint(settings.seatSensitivity, channel.lastSeatHint, scene)
            val watch = shownHint(settings.watchSensitivity, channel.lastWatchHint, scene)
            Triple(seat, watch, seatCommand(place, seat, watch, settings, occupancy))
        }
        val hardware = HardwareCommand(
            passenger = shown.getValue(CabinPlace.PASSENGER).third,
            rearLeft = shown.getValue(CabinPlace.REAR_LEFT).third,
            rearRight = shown.getValue(CabinPlace.REAR_RIGHT).third,
        )
        val (seat, watch) = shown.getValue(state.selectedPlace)
        val forecast = if (watch.peak() > seat.peak()) watch else seat
        if (BuildConfig.DEBUG && forecast != state.forecast) Log.d(TAG, "HINT ${state.selectedPlace} $forecast seat=$seat watch=$watch")
        _ui.update { it.copy(hardware = hardware, forecast = forecast, probabilities = forecast.toProbabilities()) }
    }

    private fun shownHint(sensitivity: Int, engineHint: Hint, scene: PreviewManeuver?): Hint {
        val scale = HardwareSettings.thresholdScale(sensitivity) ?: return Hint()
        return scene?.let { MotionConfig().scaled(scale).previewHint(it) } ?: testHint ?: engineHint
    }

    private fun Hint.peak() = maxOf(lateralStrength, longitudinalStrength)

    private fun engineFor(sensitivity: Int): MotionEngine? =
        HardwareSettings.thresholdScale(sensitivity)?.let { MotionEngine(MotionConfig().scaled(it)) }

    private fun applySettings(next: HardwareSettings) {
        previewJob?.cancel()
        previewJob = null
        preview = null
        val cabin = _ui.value.selectedPlace
        val previous = _ui.value.settings
        val channel = channels.getValue(cabin)
        if (next.seatSensitivity != previous.seatSensitivity) channel.seatEngine = engineFor(next.seatSensitivity)
        if (next.watchSensitivity != previous.watchSensitivity) channel.watchEngine = engineFor(next.watchSensitivity)
        saveSettings(cabin, next)
        _ui.update { it.copy(seatSettings = it.seatSettings + (cabin to next), previewLabel = null, restKey = it.restKey + 1) }
        publish()
    }

    private fun clearPreview() {
        preview = null
        _ui.update { it.copy(previewLabel = null) }
        publish()
    }

    /**
     * Each seat has its own preferences file. The passenger seat keeps the app's original one; a rear
     * seat's is named after its position, as the earlier per-seat version kept them.
     */
    private fun prefsFor(place: CabinPlace) = when (place) {
        CabinPlace.PASSENGER -> prefs
        else -> getApplication<Application>().getSharedPreferences("settings_${place.position}", Context.MODE_PRIVATE)
    }

    private fun loadSettings(place: CabinPlace): HardwareSettings {
        val default = HardwareSettings()
        val store = prefsFor(place)
        fun level(key: String, fallback: Int) = store.getInt(key, fallback).coerceIn(LEVEL_OFF, LEVEL_MAX)
        return HardwareSettings(
            seatSensitivity = level(KEY_SEAT_SENSITIVITY, default.seatSensitivity),
            seatVibration = level(KEY_SEAT_VIBRATION, default.seatVibration),
            leanSpeed = level(KEY_LEAN_SPEED, default.leanSpeed),
            watchSensitivity = level(KEY_WATCH_SENSITIVITY, default.watchSensitivity),
            watchVibration = level(KEY_WATCH_VIBRATION, default.watchVibration),
        )
    }

    private fun saveSettings(place: CabinPlace, settings: HardwareSettings) {
        prefsFor(place).edit()
            .putInt(KEY_SEAT_SENSITIVITY, settings.seatSensitivity)
            .putInt(KEY_SEAT_VIBRATION, settings.seatVibration)
            .putInt(KEY_LEAN_SPEED, settings.leanSpeed)
            .putInt(KEY_WATCH_SENSITIVITY, settings.watchSensitivity)
            .putInt(KEY_WATCH_VIBRATION, settings.watchVibration)
            .apply()
    }

    private fun Scenario.toDestination(): Destination {
        val window = demoWindow?.takeIf { it.fromSec > 0 || it.toSec < durationSec }
        return Destination(
            id = id,
            title = title,
            originRoad = originRoad,
            full = RouteChoice(if (window == null) "추천" else "전체 경로", durationSec, routeLengthM, fullRoute = window != null),
            demo = window?.let {
                RouteChoice("시연 구간", it.toSec - it.fromSec, distanceAtM(it.toSec) - distanceAtM(it.fromSec), fullRoute = false)
            },
        )
    }

    private fun loadMap(scenario: Scenario): RouteMap {
        val tileDir = scenario.mapImage.removeSuffix(".webp")
        val (width, height) = scenario.mapSize
        return RouteMap(width, height, decodeAsset(scenario.mapImage)) { decodeAsset("$tileDir/${it.col}_${it.row}.webp") }
    }

    // A missing file is normal: tiles exist only near the route, and test scenarios have no map.
    private fun decodeAsset(path: String): ImageBitmap? = runCatching {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        getApplication<Application>().assets.open(path).use { BitmapFactory.decodeStream(it, null, options)!!.asImageBitmap() }
    }.getOrNull()

    /** Each bar is the cue's strength toward that car maneuver, 0 when it is off. */
    private fun Hint.toProbabilities() = MotionProbabilities(
        forward = if (longitudinal == LongitudinalCue.ACCELERATE) longitudinalStrength else 0.0,
        back = if (longitudinal == LongitudinalCue.BRAKE) longitudinalStrength else 0.0,
        left = if (lateral == LateralCue.LEFT) lateralStrength else 0.0,
        right = if (lateral == LateralCue.RIGHT) lateralStrength else 0.0,
    )

    // e.g. "D · 동승 · 횡 예측 +0.3 · 종 실측 -1.4 m/s²": the gates, which model holds each axis, and what is measured now.
    // The seat engine's view when the seat is on, else the watch's; null when both are off.
    private fun describe(output: MotionOutput?, signal: VehicleSignal): String {
        val gear = when (signal.gear) {
            Gear.UNKNOWN -> "기어 미확인"
            Gear.PARK -> "P"
            Gear.REVERSE -> "R"
            Gear.NEUTRAL -> "N"
            Gear.DRIVE -> "D"
        }
        val passengers = signal.seats.filterKeys { it != DRIVER_SEAT }
        val seats = when {
            passengers.isEmpty() -> "탑승 미확인"
            passengers.values.any { it } -> "동승 ${passengers.count { it.value }}명"
            else -> "동승 없음"
        }
        if (output == null) return "$gear · $seats · 힌트 꺼짐"
        return "$gear · $seats · 횡 ${output.lateralSource.text()} %+.1f · 종 ${output.longitudinalSource.text()} %+.1f m/s²"
            .format(output.lateralMps2, output.longitudinalMps2)
    }

    private fun CueSource.text() = when (this) {
        CueSource.NONE -> "없음"
        CueSource.PREDICTED -> "예측"
        CueSource.MEASURED -> "실측"
    }

    private companion object {
        const val KEY_SEAT_SENSITIVITY = "seatSensitivity"
        const val KEY_SEAT_VIBRATION = "seatVibration"
        const val KEY_LEAN_SPEED = "leanSpeed"
        const val KEY_WATCH_SENSITIVITY = "watchSensitivity"
        const val KEY_WATCH_VIBRATION = "watchVibration"
    }
}
