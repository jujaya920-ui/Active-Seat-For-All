package ai.activeseat.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Lets a test run start a scenario without touching the screen.
private const val EXTRA_SCENARIO = "scenario"

// With EXTRA_SCENARIO, plays the whole route instead of its demo window.
private const val EXTRA_FULL_ROUTE = "fullRoute"

// Shows a fixed hint on the modules: left, right, brake, accelerate, or off.
private const val EXTRA_TEST_HINT = "testHint"

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    private var gate by mutableStateOf(PermissionGateState())

    // Requested from the gate's button, never on its own: the system blocks this dialog in Drive.
    // A permission still denied with no rationale to show will never get the dialog again.
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val missing = missingRuntimePermissions(this)
        gate = PermissionGateState(missing, missing.any { !shouldShowRequestPermissionRationale(it) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ui by viewModel.ui.collectAsState()
            val player by viewModel.playerState.collectAsState()
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
                    onSelectRear = viewModel::selectRear,
                ),
                onToggleSettings = viewModel::toggleSettings,
                onSelectSeat = viewModel::selectSeat,
                onSelectRear = viewModel::selectRear,
            )
            PermissionGate(
                context = this,
                state = gate,
                gear = ui.signal.gear,
                onRequest = { permissionRequest.launch(gate.missing.toTypedArray()) },
                onOpenSettings = {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                },
            )
        }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // Permissions can change in Settings while the app is away, so check on every return.
        val missing = missingRuntimePermissions(this)
        gate = PermissionGateState(missing, gate.needsSettings && missing.isNotEmpty())
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
