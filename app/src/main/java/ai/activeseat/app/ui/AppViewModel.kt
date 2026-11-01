package ai.activeseat.app.ui

import ai.activeseat.app.BuildConfig
import ai.activeseat.app.demo.ConsoleClient
import ai.activeseat.app.demo.PlayerState
import ai.activeseat.app.demo.Scenario
import ai.activeseat.app.demo.ScenarioPlayer
import ai.activeseat.app.demo.ScenarioRepository
import ai.activeseat.app.demo.toNavRoute
import ai.activeseat.app.hardware.HardwareCommand
import ai.activeseat.app.hardware.toHardwareCommand
import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.HintConfig
import ai.activeseat.app.hint.HintStabilizer
import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.hint.LongitudinalCue
import ai.activeseat.app.hint.RttTracker
import ai.activeseat.app.nav.NavRoute
import ai.activeseat.app.nav.NavState
import ai.activeseat.app.nav.RouteNavigator
import ai.activeseat.app.prediction.NavContext
import ai.activeseat.app.prediction.NavItem
import ai.activeseat.app.prediction.PredictClient
import ai.activeseat.app.prediction.PredictResult
import ai.activeseat.app.prediction.PredictionAssembler
import ai.activeseat.app.prediction.PredictionLoop
import ai.activeseat.app.vehicle.CarSignalSource
import ai.activeseat.app.vehicle.SignalSampler
import ai.activeseat.app.vehicle.VehicleSignal
import android.app.Application
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "ActiveSeat"

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
    val predictionStatus: String = "대기 중",
    val route: NavRoute? = null,
    val map: RouteMap? = null,
    val nav: NavState? = null,
    val hardware: HardwareCommand = HardwareCommand(),
)

/** Wires the packages together: the only place that sees both demo and product code. */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val clock = { SystemClock.elapsedRealtime() }
    private val source = CarSignalSource(application)
    private val sampler = SignalSampler(source, clock)
    private val scenarios = ScenarioRepository(application)
    private val player = ScenarioPlayer(ConsoleClient(), clock) { Log.i(TAG, it) }
    private val stabilizer = HintStabilizer(HintConfig(), clock, RttTracker())
    private var playJob: Job? = null

    @Volatile
    private var loaded: Map<String, Scenario> = emptyMap()

    // Scenarios load in the background, so a start asked for early waits for them.
    private var pendingStart: Pair<String, Boolean>? = null
    private var lastHint = Hint()

    // A test value shown instead of the real hint, for checking the modules without driving.
    @Volatile
    private var testHint: Hint? = null

    @Volatile
    private var navigator: RouteNavigator? = null

    // Follows whichever destination is selected, so the assembler never sees a scenario.
    private val currentNav = object : NavContext {
        override fun nextTurn(): NavItem? = navigator?.nextTurn()
        override fun frontEvent(): NavItem? = navigator?.frontEvent()
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    val playerState: StateFlow<PlayerState> = player.state

    init {
        viewModelScope.launch(Dispatchers.IO) { loadScenarios() }

        source.start()
        refreshSpeed()
        viewModelScope.launch(Dispatchers.Default) {
            sampler.run { signal ->
                navigator?.onSpeed(signal.speedKph, clock())
                _ui.update { it.copy(signal = signal, nav = navigator?.state()) }
                publish(stabilizer.tick())
                if (BuildConfig.DEBUG) Log.d(TAG, "SAMPLE ${sampler.window().last()} nav=${navigator?.state()?.progressM}")
            }
        }

        val client = PredictClient(BuildConfig.PREDICT_URL, BuildConfig.PREDICT_TOKEN)
        val assembler = PredictionAssembler(sampler::window, currentNav)
        val loop = PredictionLoop(assembler, client, ::shouldPredict) { result ->
            if (BuildConfig.DEBUG) Log.d(TAG, "PREDICT ${describe(result)}")
            _ui.update { it.copy(predictionStatus = describe(result)) }
            publish(stabilizer.onResult(result))
        }
        viewModelScope.launch(Dispatchers.Default) { loop.run() }
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
        navigator?.reset(window?.let { scenario.distanceAtM(it.fromSec) } ?: 0.0)
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
            "left" -> Hint(lateral = LateralCue.LEFT, lateralStrength = 1.0)
            "right" -> Hint(lateral = LateralCue.RIGHT, lateralStrength = 1.0)
            "brake" -> Hint(longitudinal = LongitudinalCue.BRAKE, longitudinalStrength = 1.0)
            "accelerate" -> Hint(longitudinal = LongitudinalCue.ACCELERATE, longitudinalStrength = 1.0)
            else -> null
        }
        publish(lastHint)
    }

    override fun onCleared() {
        source.stop()
    }

    private fun place(id: String) {
        val scenario = loaded[id] ?: return
        val route = scenario.toNavRoute()
        val next = RouteNavigator(route)
        navigator = next
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

    private fun shouldPredict(): Boolean {
        val state = player.state.value
        return state is PlayerState.Warmup || state is PlayerState.Playing || source.latest().speedKph > 0
    }

    private fun publish(hint: Hint) {
        if (BuildConfig.DEBUG && hint != lastHint) Log.d(TAG, "HINT $hint")
        lastHint = hint
        val hardware = (testHint ?: hint).toHardwareCommand()
        _ui.update { it.copy(hardware = hardware) }
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

    private fun describe(result: PredictResult?): String = when (result) {
        null -> "대기 중"
        PredictResult.NotConfigured -> "서버 미설정"
        is PredictResult.Failure -> "실패: ${result.message}"
        is PredictResult.Success -> {
            val first = result.predictions.firstOrNull()
            val detail = first?.let { "${it.horizonSec}s ${it.lateral}/${it.longitudinal}" }
            "왕복 ${result.rttMs}ms ${detail ?: "예측 없음"}"
        }
    }
}
