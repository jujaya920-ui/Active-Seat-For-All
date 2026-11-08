package ai.activeseat.app.ui

import ai.activeseat.app.vehicle.SPEED_PERMISSION
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

// Lets a test run start a scenario without touching the screen.
private const val EXTRA_SCENARIO = "scenario"

// With EXTRA_SCENARIO, plays the whole route instead of its demo window.
private const val EXTRA_FULL_ROUTE = "fullRoute"

// Shows a fixed hint on the modules: left, right, brake, accelerate, or off.
private const val EXTRA_TEST_HINT = "testHint"

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    // Requested by a button, never on launch: the system blocks this dialog in Drive and it
    // then covers the whole app. The result needs no handling, onResume runs after it closes.
    private val speedPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ui by viewModel.ui.collectAsState()
            val player by viewModel.playerState.collectAsState()
            val rearSeats = viewModel.rearSeats.associate { it.position to it.state.collectAsState().value }
            AppScreen(
                ui = ui,
                player = player,
                navActions = NavActions(
                    onOpenFavorites = viewModel::openFavorites,
                    onGoHome = viewModel::goHome,
                    onSelect = viewModel::select,
                    onStart = viewModel::start,
                    onEndGuidance = viewModel::endGuidance,
                ),
                settingsActions = SettingsActions(
                    onClose = viewModel::closeSettings,
                    onChange = viewModel::updateSettings,
                    onReset = viewModel::resetSettings,
                    onPreview = viewModel::startPreview,
                ),
                rearSeats = rearSeats,
                rearSettingsActions = { position ->
                    val channel = viewModel.rearSeats.first { it.position == position }
                    SettingsActions(
                        onClose = viewModel::closeSettings,
                        onChange = channel::updateSettings,
                        onReset = channel::resetSettings,
                        onPreview = channel::startPreview,
                    )
                },
                onToggleSettings = viewModel::toggleSettings,
                onRequestSpeedPermission = { speedPermission.launch(SPEED_PERMISSION) },
            )
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSpeed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        intent.getStringExtra(EXTRA_TEST_HINT)?.let(viewModel::showTestHint)
        val id = intent.getStringExtra(EXTRA_SCENARIO) ?: return
        viewModel.startWhenLoaded(id, intent.getBooleanExtra(EXTRA_FULL_ROUTE, false))
    }
}
