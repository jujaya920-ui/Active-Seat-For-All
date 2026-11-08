package ai.activeseat.app.ui

import ai.activeseat.app.nav.NavMark
import ai.activeseat.app.nav.NavState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

private const val MAP_ATTRIBUTION = "© OpenStreetMap contributors · OpenFreeMap © OpenMapTiles"

// The sampler ticks at this rate, so the car glides from one sample to the next.
private const val CAR_GLIDE_MS = 200

// A bend in the polyline turns the map over this long instead of snapping it.
private const val MAP_TURN_MS = 700

private val NavInk = Color(0xFF111111)
private val SubInk = Color(0xFF6B7280)
private val Line = Color(0xFFD5D9E0)
private val Disabled = Color(0xFFB0B0B0)
private val Divider = Color(0xFFF0F1F4)
private val Selected = Color(0xFF6C5CE7)
private val Unselected = Color(0xFFF5F5F5)
private val Warning = Color(0xFFFF8A00)
private val CardWidth = 400.dp

/** One route option on the preview. */
data class RouteChoice(val label: String, val durationSec: Double, val lengthM: Double, val fullRoute: Boolean)

/** One scenario's map: its size in map pixels, its wide overview, and its detail tiles. */
class RouteMap(val width: Int, val height: Int, val overview: ImageBitmap?, private val tile: (MapTile) -> ImageBitmap?) {
    fun loadTile(at: MapTile): ImageBitmap? = tile(at)
}

fun turnLabel(type: String): String = when (type) {
    "TURN_LEFT" -> "좌회전"
    "TURN_RIGHT" -> "우회전"
    "TURN_KEEP_L" -> "왼쪽 방향"
    "TURN_KEEP_R" -> "오른쪽 방향"
    "TURN_STRAIGHT" -> "직진"
    "TURN_ENTER_IC_C" -> "IC 진입"
    "TURN_EXIT_IC_R" -> "IC 진출"
    "TURN_ARRIVE" -> "도착"
    else -> type
}

fun formatDistance(metres: Double): String =
    if (metres < 1000) "${metres.roundToInt()}m" else String.format(Locale.US, "%.1fkm", metres / 1000)

fun formatDuration(seconds: Double): String = "${(seconds / 60).roundToInt().coerceAtLeast(1)}분"

/** The angle equal to `target` modulo 360 that is nearest to `current`, so the map turns the short way. */
fun nearestAngle(current: Float, target: Float): Float {
    val delta = (target - current) % 360f
    return current + when {
        delta > 180f -> delta - 360f
        delta < -180f -> delta + 360f
        else -> delta
    }
}

/** What NavPanel asks of the app. */
class NavActions(
    val onOpenFavorites: () -> Unit,
    val onGoHome: () -> Unit,
    val onSelect: (String) -> Unit,
    val onStart: (fullRoute: Boolean) -> Unit,
    val onEndGuidance: () -> Unit,
)

@Composable
fun NavPanel(ui: UiState, actions: NavActions, modifier: Modifier) {
    val state = ui.nav
    var bearing by remember { mutableFloatStateOf(0f) }
    bearing = nearestAngle(bearing, state?.headingDeg ?: 0f)
    val heading by animateFloatAsState(bearing, tween(MAP_TURN_MS, easing = LinearEasing), label = "heading")
    val fraction by animateFloatAsState(
        (state?.fraction ?: 0.0).toFloat(),
        tween(CAR_GLIDE_MS, easing = LinearEasing),
        label = "fraction",
    )
    val shownFraction by animateFloatAsState(
        (state?.shownFraction ?: 0.0).toFloat(),
        tween(CAR_GLIDE_MS, easing = LinearEasing),
        label = "shownFraction",
    )
    var overlayHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current.density

    BoxWithConstraints(modifier = modifier.clipToBounds().background(Color(0xFFF1F4F9))) {
        // The car is on the driven route; the guidance may draw another one until a reroute.
        val car = ui.route?.pointAt(fraction.toDouble())
        val shown = state?.shownRoute ?: ui.route
        val camera = when {
            ui.screen == NavScreen.PREVIEW && shown != null -> overviewCamera(
                shown,
                constraints.maxWidth.toFloat(),
                constraints.maxHeight.toFloat(),
                topInset = overlayHeightPx.toFloat(),
                bottomInset = ATTRIBUTION_DP * density,
                padding = PREVIEW_PADDING_DP * density,
                maxScale = density,
            )
            car == null -> MapCamera(0f, 0f, density)
            ui.screen == NavScreen.GUIDING -> MapCamera(car.x, car.y, density, heading)
            else -> MapCamera(car.x, car.y, density)
        }
        NavMap(
            map = ui.map,
            route = shown,
            showRoute = ui.screen == NavScreen.PREVIEW || ui.screen == NavScreen.GUIDING,
            camera = camera,
            passedFraction = shownFraction,
            car = car,
            carHeadingDeg = heading,
            modifier = Modifier.fillMaxSize(),
        )
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (ui.screen) {
                    NavScreen.HOME -> HomeOverlay(ui, actions)
                    NavScreen.FAVORITES -> FavoritesOverlay(ui, actions)
                    NavScreen.PREVIEW -> PreviewOverlay(ui, actions, Modifier.onSizeChanged { overlayHeightPx = it.height })
                    NavScreen.GUIDING -> GuidingOverlay(state, actions)
                }
            }
            Attribution()
        }
    }
}

private const val ATTRIBUTION_DP = 28
private const val PREVIEW_PADDING_DP = 72

@Composable
private fun Attribution() {
    Text(
        MAP_ATTRIBUTION,
        color = SubInk,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth().height(ATTRIBUTION_DP.dp).background(Color.White).padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

@Composable
private fun HomeOverlay(ui: UiState, actions: NavActions) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Column(modifier = Modifier.align(Alignment.BottomStart).width(CardWidth).border(1.dp, Line)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .clickable(onClick = actions.onOpenFavorites)
                    .padding(horizontal = 24.dp, vertical = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = Color.White, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(16.dp))
                Text("어디로 갈까요?", color = Color(0xFFA0A0A0), fontSize = 28.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(100.dp).background(Color.White),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShortcutCell(enabled = false) { Icon(Icons.Filled.Home, "집", tint = Disabled, modifier = Modifier.size(40.dp)) }
                VerticalRule()
                ShortcutCell(enabled = false) { BuildingIcon(Disabled, 36.dp) }
                VerticalRule()
                ShortcutCell(enabled = true, onClick = actions.onOpenFavorites) {
                    Icon(Icons.Filled.Star, "즐겨찾기", tint = Color.Black, modifier = Modifier.size(44.dp))
                }
            }
        }
        // The name is only known at the origin, so it would be wrong anywhere else on the route.
        if (ui.originRoad.isNotEmpty() && ui.nav?.progressM == 0.0) {
            Text(
                ui.originRoad,
                color = NavInk,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(Color.White)
                    .border(1.dp, Line)
                    .padding(horizontal = 22.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun RowScope.ShortcutCell(
    enabled: Boolean,
    onClick: () -> Unit = {},
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.weight(1f).fillMaxHeight().clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { icon() }
}

@Composable
private fun VerticalRule() {
    Box(modifier = Modifier.width(1.dp).height(48.dp).background(Line))
}

// The icon font in the app has no building, and a whole icon library is not worth one glyph.
@Composable
private fun BuildingIcon(color: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        drawRect(color)
        val window = this.size.width * 0.18f
        for (row in 0..1) {
            for (col in 0..2) {
                drawRect(
                    Color.White,
                    topLeft = Offset(this.size.width * (0.12f + col * 0.28f), this.size.height * (0.14f + row * 0.26f)),
                    size = Size(window, window),
                )
            }
        }
        drawRect(Color.White, topLeft = Offset(this.size.width * 0.4f, this.size.height * 0.72f), size = Size(window, this.size.height * 0.28f))
    }
}

@Composable
private fun FavoritesOverlay(ui: UiState, actions: NavActions) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .width(CardWidth)
            .fillMaxHeight()
            .background(Color.White)
            .border(1.dp, Line),
    ) {
        HeaderRow("즐겨찾기", actions.onGoHome)
        DisabledPlaceRow("집을 등록해 주세요") { Icon(Icons.Filled.Home, null, tint = Disabled, modifier = Modifier.size(32.dp)) }
        HorizontalRule()
        DisabledPlaceRow("회사를 등록해 주세요") { BuildingIcon(Disabled, 28.dp) }
        Box(modifier = Modifier.fillMaxWidth().height(8.dp).background(Divider))
        Text("장소", color = NavInk, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, top = 22.dp, bottom = 8.dp))
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (ui.destinations.isEmpty()) {
                Text("등록된 장소가 없습니다", color = SubInk, fontSize = 22.sp, modifier = Modifier.padding(24.dp))
            }
            ui.destinations.forEach { destination ->
                PlaceRow(destination, onClick = { actions.onSelect(destination.id) })
                HorizontalRule()
            }
        }
    }
}

@Composable
private fun HeaderRow(title: String, onClose: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(88.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            color = NavInk,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 24.dp),
        )
        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Line))
        Box(modifier = Modifier.size(88.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Close, "닫기", tint = NavInk, modifier = Modifier.size(36.dp))
        }
    }
    HorizontalRule()
}

@Composable
private fun HorizontalRule() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Line))
}

@Composable
private fun DisabledPlaceRow(label: String, icon: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(84.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        icon()
        Text(label, color = Disabled, fontSize = 22.sp)
    }
}

@Composable
private fun PlaceRow(destination: Destination, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Place, null, tint = Color.Black, modifier = Modifier.size(38.dp))
        Column {
            Text(destination.title, color = NavInk, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatDistance(destination.full.lengthM)} · ${destination.originRoad} 출발",
                color = SubInk,
                fontSize = 19.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PreviewOverlay(ui: UiState, actions: NavActions, modifier: Modifier) {
    val destination = ui.destinations.firstOrNull { it.id == ui.selectedScenario } ?: return
    val choices = destination.choices
    var selected by remember(destination.id) { mutableStateOf(choices.first()) }
    Column(modifier = modifier.fillMaxWidth().background(Color.White)) {
        HeaderRow(destination.title, actions.onGoHome)
        choices.forEachIndexed { index, choice ->
            RouteChoiceRow(index + 1, choice, choice == selected, onClick = { selected = choice })
            HorizontalRule()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(92.dp)
                .background(Color(0xFF1A1A1A))
                .clickable { actions.onStart(selected.fullRoute) },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("안내 시작", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RouteChoiceRow(number: Int, choice: RouteChoice, selected: Boolean, onClick: () -> Unit) {
    val ink = if (selected) NavInk else SubInk
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (selected) Color.White else Unselected)
            .clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.width(8.dp).fillMaxHeight().background(if (selected) Selected else Unselected))
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.size(28.dp).background(ink, CircleShape), contentAlignment = Alignment.Center) {
                    Text("$number", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Text(choice.label, color = ink, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            }
            Text(formatDuration(choice.durationSec), color = ink, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text(formatDistance(choice.lengthM), color = ink, fontSize = 22.sp)
        }
    }
}

@Composable
private fun GuidingOverlay(state: NavState?, actions: NavActions) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1A1A1A)).padding(horizontal = 28.dp, vertical = 20.dp)) {
            when {
                state == null -> Unit
                state.arrived -> GuidanceTitle(TurnIcon.ARRIVE, "목적지 도착", null)
                state.rerouting -> GuidanceTitle(TurnIcon.REROUTE, "경로 재검색 중", null)
                else -> state.nextTurn?.let { NextTurn(it, state) } ?: GuidanceTitle(TurnIcon.STRAIGHT, "직진", null)
            }
        }
        state?.takeUnless { it.arrived }?.frontEvent?.let { event ->
            Text(
                "${event.description} ${state.frontEventDistanceM}m",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().background(Warning).padding(horizontal = 28.dp, vertical = 12.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth().background(Color.White).border(1.dp, Line),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(horizontal = 24.dp, vertical = 14.dp)) {
                Text("남은 거리", color = SubInk, fontSize = 20.sp)
                Text(formatDistance((state?.remainingM ?: 0.0).coerceAtLeast(0.0)), color = NavInk, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                modifier = Modifier.height(92.dp).width(180.dp).background(Color(0xFF1A1A1A)).clickable(onClick = actions.onEndGuidance),
                contentAlignment = Alignment.Center,
            ) {
                Text("안내 종료", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NextTurn(turn: NavMark, state: NavState) {
    GuidanceTitle(turnIcon(turn.type), "${state.nextTurnDistanceM}m ${turnLabel(turn.type)}", turn.description)
}

@Composable
private fun GuidanceTitle(icon: TurnIcon, title: String, detail: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        TurnIconView(icon, title, Modifier.size(72.dp))
        Column {
            Text(title, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            detail?.let { Text(it, color = Color(0xFFD0D0D0), fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
