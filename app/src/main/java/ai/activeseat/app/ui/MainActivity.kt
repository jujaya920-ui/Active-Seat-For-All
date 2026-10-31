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
            AppScreen(
                ui = ui,
                player = player,
                onSelect = viewModel::select,
                onStart = viewModel::start,
                onStop = viewModel::stop,
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
        viewModel.select(id)
        viewModel.start()
    }
}
