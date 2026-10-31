package ai.activeseat.app.ui

import ai.activeseat.app.demo.PlayerState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
fun AppScreen(
    ui: UiState,
    player: PlayerState,
    onSelect: (String) -> Unit,
    onStart: () -> Unit,
    onStartFull: () -> Unit,
    onStop: () -> Unit,
    onRequestSpeedPermission: () -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavPanel(ui.route, ui.nav, ui.map, Modifier.weight(1f).fillMaxHeight())
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    HardwarePanel(ui.hardware, Modifier.weight(1f).fillMaxWidth())
                    ControlBar(ui, player, onSelect, onStart, onStartFull, onStop, onRequestSpeedPermission)
                }
            }
        }
    }
}

@Composable
private fun ControlBar(
    ui: UiState,
    player: PlayerState,
    onSelect: (String) -> Unit,
    onStart: () -> Unit,
    onStartFull: () -> Unit,
    onStop: () -> Unit,
    onRequestSpeedPermission: () -> Unit,
) {
    val active = player is PlayerState.Warmup || player is PlayerState.Playing
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("목적지", style = MaterialTheme.typography.titleLarge)
            ui.destinations.forEach { destination ->
                if (destination.id == ui.selectedScenario) {
                    Button(onClick = { onSelect(destination.id) }, enabled = !active) { Text(destination.title, style = MaterialTheme.typography.titleLarge) }
                } else {
                    OutlinedButton(onClick = { onSelect(destination.id) }, enabled = !active) { Text(destination.title, style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onStart, enabled = !active && ui.selectedScenario != null) { Text("시연 구간", style = MaterialTheme.typography.titleLarge) }
            OutlinedButton(onClick = onStartFull, enabled = !active && ui.selectedScenario != null) { Text("전체", style = MaterialTheme.typography.titleLarge) }
            OutlinedButton(onClick = onStop, enabled = active) { Text("중지", style = MaterialTheme.typography.titleLarge) }
        }
        if (!ui.speedConnected) {
            OutlinedButton(onClick = onRequestSpeedPermission) { Text("속도 권한 허용 (P단에서)") }
        }
        Text(
            text = status(ui, player),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun status(ui: UiState, player: PlayerState): String {
    val speed = if (ui.speedConnected) String.format(Locale.US, "%.0f km/h", ui.signal.speedKph) else "속도 없음"
    val steering = String.format(Locale.US, "조향 %+.0f°", ui.signal.steeringDeg)
    return "$speed · $steering · ${ui.playerError ?: describe(player)} · ${ui.predictionStatus}"
}

private fun describe(player: PlayerState): String = when (player) {
    PlayerState.Idle -> "대기"
    PlayerState.Warmup -> "콘솔 연결 중"
    is PlayerState.Playing -> String.format(Locale.US, "%.1f / %.1f초", player.tSec, player.durationSec)
    is PlayerState.Finished ->
        if (player.failedWrites == 0) "주행 종료" else "주행 종료, 쓰기 실패 ${player.failedWrites}건"
}
