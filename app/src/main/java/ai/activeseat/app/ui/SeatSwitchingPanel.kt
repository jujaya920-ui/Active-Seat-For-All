package ai.activeseat.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** The hardware panel with seat buttons and arrows; the seat graphics follow a finger across the seats. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeatSwitchingPanel(
    displays: Map<CabinSeat, SeatDisplay>,
    selected: CabinSeat,
    onSelect: (CabinSeat) -> Unit,
    settingsOpen: Boolean,
    onToggleSettings: () -> Unit,
    modifier: Modifier,
) {
    val seats = CabinSeat.entries
    val pager = rememberPagerState(selected.ordinal) { seats.size }
    val scope = rememberCoroutineScope()
    val selectedNow by rememberUpdatedState(selected)
    val latestSelect by rememberUpdatedState(onSelect)
    LaunchedEffect(selected) {
        if (pager.currentPage != selected.ordinal) pager.scrollToPage(selected.ordinal)
    }
    // Only a page the passenger reached counts as a choice: the app's own moves already match.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page -> if (page != selectedNow.ordinal) latestSelect(seats[page]) }
    }
    val current = pager.currentPage
    fun show(page: Int) {
        latestSelect(seats[page])
        scope.launch { pager.animateScrollToPage(page) }
    }
    Box(modifier) {
        HardwarePanel(displays, settingsOpen, onToggleSettings, Modifier.fillMaxSize(), pager)
        Column(
            Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 22.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            seats.forEach { seat ->
                SeatButton(seat, seat.ordinal == current, displays.getValue(seat).occupancy == SeatOccupancy.OCCUPIED) { show(seat.ordinal) }
            }
        }
        Box(Modifier.fillMaxSize().padding(top = 170.dp, bottom = 136.dp)) {
            ArrowButton("이전 좌석", "◀", current > 0, Modifier.align(Alignment.TopStart)) { show(current - 1) }
            ArrowButton("다음 좌석", "▶", current < seats.lastIndex, Modifier.align(Alignment.TopEnd)) { show(current + 1) }
        }
    }
}

@Composable
private fun SeatButton(seat: CabinSeat, selected: Boolean, occupied: Boolean, onClick: () -> Unit) {
    val ink = if (selected) PanelColor else TextBright
    Row(
        Modifier
            .width(148.dp)
            .height(36.dp)
            .background(if (selected) Amber else PanelColor)
            .border(1.dp, if (selected) Amber else BracketColor)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (occupied) "${seat.label}, 탑승 중" else seat.label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (occupied) Box(Modifier.padding(end = 6.dp).size(8.dp).background(if (selected) PanelColor else Amber, CircleShape))
        Text(seat.label, color = ink, fontSize = 17.sp, maxLines = 1)
    }
}

@Composable
private fun ArrowButton(label: String, glyph: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (!enabled) return
    Box(
        modifier
            .padding(horizontal = 16.dp)
            .size(64.dp)
            .alpha(0.85f)
            .border(1.dp, BracketColor)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = TextBright, fontSize = 26.sp)
    }
}
