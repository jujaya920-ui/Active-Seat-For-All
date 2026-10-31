package ai.activeseat.app.ui

import ai.activeseat.app.nav.NavMark
import ai.activeseat.app.nav.NavRoute
import ai.activeseat.app.nav.NavState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAP_ATTRIBUTION = "© OpenStreetMap contributors · OpenFreeMap © OpenMapTiles"
const val MAP_WIDTH_PX = 1000f
const val MAP_HEIGHT_PX = 1000f
private const val GRID_STEP_PX = 100

private val Background = Color(0xFF10161C)
private val GridLine = Color(0xFF1E2A33)
private val RouteAhead = Color(0xFF546E7A)
private val RouteTravelled = Color(0xFF4FC3F7)
private val TurnMarker = Color(0xFFECEFF1)
private val Amber = Color(0xFFFFB300)
private val CardBackground = Color(0xE61A232B)
private val Label = Color(0xFFB0BEC5)

/** How route coordinates land on a canvas: `canvas = offset + scale * route`. */
data class MapFit(val scale: Float, val offsetX: Float, val offsetY: Float)

/** Scales the route space to fit the canvas without distortion, top aligned so the card never covers the map. */
fun fitMap(canvasWidth: Float, canvasHeight: Float): MapFit {
    val scale = min(canvasWidth / MAP_WIDTH_PX, canvasHeight / MAP_HEIGHT_PX)
    return MapFit(scale, (canvasWidth - scale * MAP_WIDTH_PX) / 2, 0f)
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

@Composable
fun NavPanel(route: NavRoute?, state: NavState?, map: ImageBitmap?, modifier: Modifier) {
    Box(modifier = modifier.background(Background)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val fit = fitMap(size.width, size.height)
            if (map != null) drawMapImage(map, fit) else drawGrid(fit)
            if (route != null) drawRoute(route, state, fit)
        }
        if (route == null) {
            Text(
                "목적지를 선택하세요",
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            GuidanceCard(route, state, Modifier.align(Alignment.BottomCenter))
        }
    }
}

private fun DrawScope.drawMapImage(map: ImageBitmap, fit: MapFit) {
    drawImage(
        image = map,
        dstOffset = IntOffset(fit.offsetX.roundToInt(), fit.offsetY.roundToInt()),
        dstSize = IntSize((MAP_WIDTH_PX * fit.scale).roundToInt(), (MAP_HEIGHT_PX * fit.scale).roundToInt()),
    )
}

private fun DrawScope.drawGrid(fit: MapFit) {
    for (x in 0..MAP_WIDTH_PX.toInt() step GRID_STEP_PX) {
        drawLine(GridLine, fit.at(x.toFloat(), 0f), fit.at(x.toFloat(), MAP_HEIGHT_PX), 1.dp.toPx())
    }
    for (y in 0..MAP_HEIGHT_PX.toInt() step GRID_STEP_PX) {
        drawLine(GridLine, fit.at(0f, y.toFloat()), fit.at(MAP_WIDTH_PX, y.toFloat()), 1.dp.toPx())
    }
}

private fun DrawScope.drawRoute(route: NavRoute, state: NavState?, fit: MapFit) {
    val line = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val vertices = route.routePx.map { (x, y) -> fit.at(x.toFloat(), y.toFloat()) }
    drawPath(polyline(vertices), RouteAhead, style = line)

    if (state != null) {
        val passed = vertices.take(route.pointAt(state.fraction).segment + 1)
        drawPath(polyline(passed + fit.at(state.carX, state.carY)), RouteTravelled, style = line)
    }
    route.turns.forEach { drawMark(route, it, fit, TurnMarker) }
    route.frontEvents.forEach { drawMark(route, it, fit, Amber) }
    if (state != null) drawCar(fit.at(state.carX, state.carY), state.headingDeg)
}

private fun DrawScope.drawMark(route: NavRoute, mark: NavMark, fit: MapFit, color: Color) {
    val point = route.pointAt(mark.atM / route.lengthM)
    val center = fit.at(point.x, point.y)
    drawCircle(Background, radius = 12.dp.toPx(), center = center)
    drawCircle(color, radius = 9.dp.toPx(), center = center)
}

private fun DrawScope.drawCar(center: Offset, headingDeg: Float) {
    val tip = 20.dp.toPx()
    val halfWidth = 14.dp.toPx()
    val tail = 14.dp.toPx()
    val car = Path().apply {
        moveTo(center.x, center.y - tip)
        lineTo(center.x + halfWidth, center.y + tail)
        lineTo(center.x - halfWidth, center.y + tail)
        close()
    }
    rotate(headingDeg, pivot = center) {
        drawPath(car, Color.White)
        drawPath(car, Background, style = Stroke(width = 2.dp.toPx()))
    }
}

private fun MapFit.at(x: Float, y: Float) = Offset(offsetX + scale * x, offsetY + scale * y)

private fun polyline(points: List<Offset>) = Path().apply {
    points.firstOrNull()?.let { moveTo(it.x, it.y) }
    points.drop(1).forEach { lineTo(it.x, it.y) }
}

@Composable
private fun GuidanceCard(route: NavRoute, state: NavState?, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
            .background(CardBackground, RoundedCornerShape(20.dp))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when {
            state == null -> Text(route.title, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            state.arrived -> Text("목적지 도착", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)
            else -> {
                NextTurn(state)
                state.frontEvent?.let { Field("전방 이벤트", "${it.description} ${state.frontEventDistanceM}m", Amber) }
                Field("남은 거리", "${state.remainingM.roundToInt()}m", Color.White)
                Text(MAP_ATTRIBUTION, color = Color(0xFF90A4AE), fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun NextTurn(state: NavState) {
    val turn = state.nextTurn
    Column {
        Text("다음 안내", color = Label, fontSize = 24.sp)
        if (turn == null) {
            Text("안내 없음", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        } else {
            Text(
                "${turnLabel(turn.type)} ${state.nextTurnDistanceM}m",
                color = Color.White,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(turn.description, color = Color.White, fontSize = 30.sp)
        }
    }
}

@Composable
private fun Field(label: String, value: String, color: Color) {
    Column {
        Text(label, color = Label, fontSize = 24.sp)
        Text(value, color = color, fontSize = 34.sp, fontWeight = FontWeight.Bold)
    }
}
