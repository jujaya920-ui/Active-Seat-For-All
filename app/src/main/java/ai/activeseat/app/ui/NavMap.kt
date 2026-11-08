package ai.activeseat.app.ui

import ai.activeseat.app.nav.NavMark
import ai.activeseat.app.nav.NavRoute
import ai.activeseat.app.nav.RoutePoint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Same as TILE_PX in tools/map/render.mjs.
const val MAP_TILE_PX = 1024

// Same as OVERVIEW_EXTENT in tools/map/render.mjs: the overview is a square this many long sides wide.
const val MAP_OVERVIEW_EXTENT = 1.6f

// Covers the panel's half diagonal in any rotation, with a tile's load time of driving to spare.
private const val TILE_RADIUS_PX = 900f

// Below this zoom the panel shows the overview, and loading detail tiles would be wasted.
private const val DETAIL_MIN_SCALE = 0.5f

private val MapLand = Color(0xFFF1F4F9)
private val RouteAhead = Color(0xFF1E88FF)
private val RouteCasing = Color(0xFF0F5FBF)
private val RoutePassed = Color(0xFFAEB7C4)
private val FrontEvent = Color(0xFFFF8A00)
private val CarBlue = Color(0xFF0A84FF)

/** The map pixel at the panel's reference point, map pixels to screen pixels, and the map's heading. */
data class MapCamera(val x: Float, val y: Float, val scale: Float, val bearingDeg: Float = 0f) {
    /** Where a map point lands on a canvas whose centre shows the camera position. */
    fun toScreen(px: Float, py: Float, canvasWidth: Float, canvasHeight: Float): Offset {
        val radians = Math.toRadians(-bearingDeg.toDouble())
        val dx = (px - x) * scale
        val dy = (py - y) * scale
        return Offset(
            canvasWidth / 2 + (dx * cos(radians) - dy * sin(radians)).toFloat(),
            canvasHeight / 2 + (dx * sin(radians) + dy * cos(radians)).toFloat(),
        )
    }
}

/** Fits the whole route between the insets, north up, centred in the space they leave, never past `maxScale`. */
fun overviewCamera(route: NavRoute, width: Float, height: Float, topInset: Float, bottomInset: Float, padding: Float, maxScale: Float): MapCamera {
    val xs = route.routePx.map { it.first }
    val ys = route.routePx.map { it.second }
    val minX = xs.min().toFloat()
    val minY = ys.min().toFloat()
    val spanX = (xs.max() - minX).coerceAtLeast(1f)
    val spanY = (ys.max() - minY).coerceAtLeast(1f)
    val fit = min((width - 2 * padding) / spanX, (height - topInset - bottomInset - 2 * padding) / spanY)
    val scale = min(fit, maxScale)
    return MapCamera(minX + spanX / 2, minY + spanY / 2 + (bottomInset - topInset) / 2 / scale, scale)
}

data class MapTile(val col: Int, val row: Int)

/** Every tile within `radius` map pixels of a point. */
fun tilesAround(x: Float, y: Float, radius: Float): Set<MapTile> {
    val columns = floor((x - radius) / MAP_TILE_PX).toInt()..floor((x + radius) / MAP_TILE_PX).toInt()
    val rows = floor((y - radius) / MAP_TILE_PX).toInt()..floor((y + radius) / MAP_TILE_PX).toInt()
    return rows.flatMap { row -> columns.map { col -> MapTile(col, row) } }.filterTo(mutableSetOf()) { it.col >= 0 && it.row >= 0 }
}

/** Keeps the detail tiles around the camera in memory. Tiles off the route have no file and stay absent. */
@Composable
private fun rememberTiles(map: RouteMap?, camera: MapCamera): Map<MapTile, ImageBitmap> {
    val tiles = remember(map) { mutableStateMapOf<MapTile, ImageBitmap>() }
    val wanted = if (map == null || camera.scale < DETAIL_MIN_SCALE) emptySet() else tilesAround(camera.x, camera.y, TILE_RADIUS_PX)
    LaunchedEffect(map, wanted) {
        tiles.keys.retainAll(wanted)
        for (tile in wanted - tiles.keys) {
            val bitmap = withContext(Dispatchers.IO) { map?.loadTile(tile) }
            if (bitmap != null) tiles[tile] = bitmap
        }
    }
    return tiles
}

/**
 * The map, the route and the car. Everything is drawn in map pixels through one transform, so
 * following the car and fitting the whole route differ only in the camera. `route` is the route
 * guided, passed up to `passedFraction`; the car is where it really is, which can be off it.
 */
@Composable
fun NavMap(
    map: RouteMap?,
    route: NavRoute?,
    showRoute: Boolean,
    camera: MapCamera,
    passedFraction: Float,
    car: RoutePoint?,
    carHeadingDeg: Float,
    modifier: Modifier,
) {
    val tiles = rememberTiles(map, camera)
    Canvas(modifier = modifier) {
        drawRect(MapLand)
        withTransform({
            translate(size.width / 2, size.height / 2)
            rotate(-camera.bearingDeg, pivot = Offset.Zero)
            scale(camera.scale, camera.scale, pivot = Offset.Zero)
            translate(-camera.x, -camera.y)
        }) {
            if (map != null) drawMapImages(map, tiles)
            if (route != null && showRoute) drawRoute(route, passedFraction, camera.scale)
        }
        if (route != null && showRoute) {
            val end = route.pointAt(1.0)
            drawDestinationPin(camera.toScreen(end.x, end.y, size.width, size.height))
        }
        car?.let {
            drawCar(camera.toScreen(it.x, it.y, size.width, size.height), carHeadingDeg - camera.bearingDeg)
        }
    }
}

private fun DrawScope.drawMapImages(map: RouteMap, tiles: Map<MapTile, ImageBitmap>) {
    map.overview?.let {
        val side = (maxOf(map.width, map.height) * MAP_OVERVIEW_EXTENT).roundToInt()
        drawImage(image = it, dstOffset = IntOffset((map.width - side) / 2, (map.height - side) / 2), dstSize = IntSize(side, side))
    }
    tiles.forEach { (tile, bitmap) ->
        // One pixel of overlap hides the hairline seam tiles show when the map is rotated.
        drawImage(
            image = bitmap,
            dstOffset = IntOffset(tile.col * MAP_TILE_PX, tile.row * MAP_TILE_PX),
            dstSize = IntSize(bitmap.width + 1, bitmap.height + 1),
        )
    }
}

private fun DrawScope.drawRoute(route: NavRoute, passedFraction: Float, scale: Float) {
    val vertices = route.routePx.map { (x, y) -> Offset(x.toFloat(), y.toFloat()) }
    val car = route.pointAt(passedFraction.toDouble())
    val all = polyline(vertices)
    drawPath(all, RouteCasing, style = lineOf(14.dp.toPx() / scale))
    drawPath(all, RouteAhead, style = lineOf(10.dp.toPx() / scale))
    val passed = polyline(vertices.take(car.segment + 1) + Offset(car.x, car.y))
    drawPath(passed, RoutePassed, style = lineOf(14.dp.toPx() / scale))
    route.frontEvents.forEach { drawMark(route, it, scale) }
}

private fun lineOf(width: Float) = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.drawMark(route: NavRoute, mark: NavMark, scale: Float) {
    val point = route.pointAt(mark.atM / route.lengthM)
    val center = Offset(point.x, point.y)
    drawCircle(Color.White, radius = 10.dp.toPx() / scale, center = center)
    drawCircle(FrontEvent, radius = 7.dp.toPx() / scale, center = center)
}

private fun DrawScope.drawDestinationPin(tip: Offset) {
    val radius = 18.dp.toPx()
    val center = Offset(tip.x, tip.y - 2.4f * radius)
    val pin = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(center.x - radius * 0.92f, center.y + radius * 0.4f)
        lineTo(center.x + radius * 0.92f, center.y + radius * 0.4f)
        close()
    }
    drawPath(pin, CarBlue)
    drawCircle(CarBlue, radius = radius, center = center)
    drawCircle(Color.White, radius = radius * 0.42f, center = center)
}

private fun DrawScope.drawCar(center: Offset, headingDeg: Float) {
    val radius = 30.dp.toPx()
    drawCircle(Color.White, radius = radius, center = center)
    drawCircle(CarBlue, radius = radius, center = center, style = Stroke(width = 5.dp.toPx()))
    val tip = radius * 0.6f
    val halfWidth = radius * 0.5f
    val tail = radius * 0.45f
    val notch = radius * 0.2f
    val arrow = Path().apply {
        moveTo(center.x, center.y - tip)
        lineTo(center.x + halfWidth, center.y + tail)
        lineTo(center.x, center.y + notch)
        lineTo(center.x - halfWidth, center.y + tail)
        close()
    }
    rotate(headingDeg, pivot = center) { drawPath(arrow, CarBlue) }
}

private fun polyline(points: List<Offset>) = Path().apply {
    points.firstOrNull()?.let { moveTo(it.x, it.y) }
    points.drop(1).forEach { lineTo(it.x, it.y) }
}
