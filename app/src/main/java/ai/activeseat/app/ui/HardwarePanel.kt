package ai.activeseat.app.ui

import ai.activeseat.app.hardware.HardwareCommand
import ai.activeseat.app.hardware.HardwareMapping
import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.MotionProbabilities
import android.graphics.Bitmap
import android.graphics.Canvas as NativeCanvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin


private val PanelColor = Color(0xFF05080F)
private val TextBright = Color(0xFFCFE6F7)
private val TextDim = Color(0xFFA9CDE6)
private val Amber = Color(0xFFFFC247)
private val BracketColor = Color(0x803AA0FF)
private val BarTrack = Color(0xFF12283A)
private val Grid = Color(0x123AA0FF)
private val Ink = Color(0xFF8FD0FF)
private val Glow = Color(0x293AA0FF)
private val GaugeDim = Color(0x477FE7FF)
private val GaugeRest = Color(0x577FE7FF)
private val GaugeLit = Color(0xFFD6F7FF)
private val GaugeArc = Color(0xFFBFF3FF)
private val GaugePointer = Color(0xFFEFFFFF)
private val GaugeIdle = Color(0x997FE7FF)
private val WatchRing = Color(0xFFFFB454)
private val WatchInk = Color(0xFF6FC0FF)

private val BracketLength = 20.dp
private val BracketStroke = 1.dp

@Composable
fun HardwarePanel(command: HardwareCommand, forecast: Hint, probabilities: MotionProbabilities, modifier: Modifier) {
    val motion = remember { SeatMotion() }
    val frame = remember { mutableLongStateOf(0L) }
    val latest = rememberUpdatedState(command)
    LaunchedEffect(Unit) {
        var lastNs = 0L
        while (true) {
            withFrameNanos { now ->
                val dtSec = if (lastNs == 0L) 0f else min(0.25f, (now - lastNs) / 1e9f)
                lastNs = now
                if (motion.step(dtSec, latest.value)) frame.longValue++
            }
        }
    }
    val front = remember { SeatRenderer(SeatCamera.FRONT, tiltSteps = 6) { it.rollDeg / HardwareMapping.ROLL_DEG.toFloat() } }
    val side = remember { SeatRenderer(SeatCamera.SIDE, tiltSteps = 4) { it.pitchDeg / HardwareMapping.PITCH_DEG.toFloat() } }
    Column(
        modifier = modifier.background(PanelColor).padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ForecastTitle(forecast)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SeatView(front, motion, frame)
            SeatView(side, motion, frame)
        }
        Row(Modifier.fillMaxWidth().height(116.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ProbabilityBars(probabilities, Modifier.weight(1f).fillMaxHeight())
            WatchView(motion, frame, forecast, Modifier.width(130.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun ForecastTitle(forecast: Hint) {
    Box(
        modifier = Modifier.fillMaxWidth().height(132.dp).drawBehind { drawBrackets() }.padding(top = 10.dp, bottom = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("차량 움직임 예측", color = TextDim, fontSize = 21.sp, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Text(
                    forecastText(forecast),
                    color = if (isForecastActive(forecast)) Amber else TextBright,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
                Text("강도 ${strengthPercent(forecast)}", color = TextBright, fontSize = 28.sp, maxLines = 1, modifier = Modifier.alignByBaseline())
            }
        }
    }
}

@Composable
private fun ProbabilityBars(probabilities: MotionProbabilities, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.SpaceEvenly) {
        ProbabilityBar("전", probabilities.forward)
        ProbabilityBar("후", probabilities.back)
        ProbabilityBar("좌", probabilities.left)
        ProbabilityBar("우", probabilities.right)
    }
}

@Composable
private fun ProbabilityBar(label: String, probability: Double) {
    val color = if (probability > 0.0) Amber else TextDim
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(label, color = color, fontSize = 20.sp, maxLines = 1, modifier = Modifier.width(28.dp))
        Box(
            Modifier.weight(1f).height(7.dp).drawBehind {
                drawRect(BarTrack)
                drawRect(color, size = Size(size.width * probability.toFloat().coerceIn(0f, 1f), size.height))
            },
        )
        Text(
            percentText(probability),
            color = color,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(76.dp),
        )
    }
}

@Composable
private fun RowScope.SeatView(renderer: SeatRenderer, motion: SeatMotion, frame: LongState) {
    Canvas(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
        frame.longValue // Redraws only while something moves.
        drawGrid()
        drawBrackets()
        val unit = min(size.width / SEAT_VIEW_W, size.height / SEAT_VIEW_H)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.translate((size.width - SEAT_VIEW_W * unit) / 2, (size.height - SEAT_VIEW_H * unit) / 2)
            native.scale(unit, unit)
            renderer.draw(native, motion, unit)
            native.restore()
        }
    }
}

@Composable
private fun WatchView(motion: SeatMotion, frame: LongState, forecast: Hint, modifier: Modifier) {
    val active = isForecastActive(forecast)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            frame.longValue
            drawWatch(motion)
        }
        Text(
            if (active) "강도 ${strengthPercent(forecast)}" else "대기",
            color = if (active) Amber else TextDim,
            fontSize = 18.sp,
            maxLines = 1,
        )
    }
}

private fun DrawScope.drawBrackets() {
    val length = BracketLength.toPx()
    val stroke = BracketStroke.toPx()
    val w = size.width
    val h = size.height
    listOf(Offset(0f, 0f) to Offset(1f, 1f), Offset(w, 0f) to Offset(-1f, 1f), Offset(0f, h) to Offset(1f, -1f), Offset(w, h) to Offset(-1f, -1f))
        .forEach { (corner, inward) ->
            drawLine(BracketColor, corner, Offset(corner.x + inward.x * length, corner.y), stroke)
            drawLine(BracketColor, corner, Offset(corner.x, corner.y + inward.y * length), stroke)
        }
}

private fun DrawScope.drawGrid() {
    val step = 24f * min(size.width / SEAT_VIEW_W, size.height / SEAT_VIEW_H)
    var x = 0f
    while (x < size.width) {
        drawLine(Grid, Offset(x, 0f), Offset(x, size.height), 1f)
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(Grid, Offset(0f, y), Offset(size.width, y), 1f)
        y += step
    }
}

private fun DrawScope.drawWatch(motion: SeatMotion) {
    val unit = min(size.width / 112f, size.height / 100f)
    val c = Offset(size.width / 2, size.height / 2)
    val v = motion.watch
    // Direction-free: strength sets how many rings swell outward and how bright they are.
    val rings = if (v > 0.03f) ceil(v * 3).toInt() else 0
    for (i in 0 until rings) {
        val swell = max(0f, sin(motion.timeSec * 2.4f - i * 0.7f)).pow(2)
        val alpha = (0.35f + 0.65f * v) * swell * (0.85f - i * 0.2f)
        drawCircle(WatchRing, (31 + i * 7) * unit, c, alpha = alpha.coerceIn(0f, 1f), style = Stroke(1.5f * unit))
    }
    val strap = Stroke(1.4f * unit)
    fun u(x: Float, y: Float) = Offset(c.x + (x - 56) * unit, c.y + (y - 50) * unit)
    listOf(u(44f, 28f) to u(47f, 3f), u(47f, 3f) to u(65f, 3f), u(65f, 3f) to u(68f, 28f), u(44f, 72f) to u(47f, 97f), u(47f, 97f) to u(65f, 97f), u(65f, 97f) to u(68f, 72f))
        .forEach { (a, b) -> drawLine(WatchInk, a, b, strap.width) }
    drawCircle(WatchInk, 25 * unit, c, style = strap)
    drawCircle(WatchInk, 20 * unit, c, alpha = 0.5f, style = Stroke(0.8f * unit))
    drawLine(TextBright, c, u(56f, 36f), 1.5f * unit)
    drawLine(TextBright, c, u(65f, 50f), 1.5f * unit)
}

/** Line segments collected for one native drawLines call. */
private class Lines(capacity: Int) {
    private val points = FloatArray(capacity * 4)
    private var used = 0

    fun clear() {
        used = 0
    }

    fun add(ax: Float, ay: Float, bx: Float, by: Float) {
        if (used + 4 > points.size) return
        points[used++] = ax
        points[used++] = ay
        points[used++] = bx
        points[used++] = by
    }

    fun draw(canvas: NativeCanvas, paint: Paint) = canvas.drawLines(points, 0, used, paint)
}

/** Points of one brightness, collected for one native drawPoints call. */
private class Points(capacity: Int) {
    private val points = FloatArray(capacity * 2)
    private var used = 0

    fun clear() {
        used = 0
    }

    fun add(x: Float, y: Float) {
        if (used + 2 > points.size) return
        points[used++] = x
        points[used++] = y
    }

    fun draw(canvas: NativeCanvas, paint: Paint) = canvas.drawPoints(points, 0, used / 2, paint)
}

private const val SKIN_LEVELS = 10
private const val POSE_REDRAW_DEG = 0.01f
private const val DOT_LEVELS = 8
private const val DOT_CAPACITY = 8000

/** Draws one view of the seat in [SEAT_VIEW_W] × [SEAT_VIEW_H] units. Buffers are reused between frames. */
private class SeatRenderer(
    private val camera: SeatCamera,
    private val tiltSteps: Int,
    private val tiltRatio: (SeatMotion) -> Float,
) {
    private val screenX = SEAT_PARTS.map { FloatArray(it.vertices.size / 3) }
    private val screenY = SEAT_PARTS.map { FloatArray(it.vertices.size / 3) }
    private val faces = SEAT_PARTS.map { FloatArray(it.vertices.size / 3) }
    private val out = FloatArray(2)

    private val skin = List(SKIN_LEVELS) { Path() }
    private val skinPaint = List(SKIN_LEVELS) { level ->
        fill(Color(0xFF3AA0FF).copy(alpha = 0.04f + 0.34f * level / SKIN_LEVELS))
    }
    private val edge = Lines(6000)
    private val rim = Lines(6000)
    private val seamFront = Lines(600)
    private val seamBack = Lines(600)
    private val dots = List(DOT_LEVELS * 2) { Points(DOT_CAPACITY) }
    private val halos = Points(DOT_CAPACITY)
    private val dotPaint = List(DOT_LEVELS * 2) { i ->
        val level = i % DOT_LEVELS
        round((if (i < DOT_LEVELS) Color(0xFFFFB454) else Color(0xFFFFE7B8)).copy(alpha = (level + 1f) / DOT_LEVELS), 1f + 0.8f * level / (DOT_LEVELS - 1))
    }
    private val haloPaint = round(Color(0xFFFFE7B8).copy(alpha = 0.08f), 4.8f)
    private val edgeGlow = line(Glow, 3.5f)
    private val edgeLine = line(Ink, 1.2f)
    private val rimLine = line(Color(0xFF6FC0FF).copy(alpha = 0.22f), 0.7f)
    private val seamFrontLine = line(Ink.copy(alpha = 0.5f), 0.8f)
    private val seamBackLine = line(Color(0xFF6FC0FF).copy(alpha = 0.06f), 0.7f)
    private val gaugeLines = Lines(64)
    private val triangle = Path()
    private val pointerFill = fill(GaugePointer)
    private val idleFill = fill(GaugeIdle)
    private val zeroLine = line(GaugeIdle, 0.9f)
    private val dimTick = line(GaugeRest, 0.8f)
    private val litTick = line(GaugeLit, 1.2f)
    private val arcBack = line(GaugeDim, 0.8f)
    private val arcGlow = line(GaugeArc.copy(alpha = 0.25f), 4f)
    private val arcLine = line(GaugeArc, 1.5f)

    private val rest: android.graphics.Picture by lazy { recordRest() }

    // The seat surface is the expensive part and holds still while it vibrates, so it stays in a bitmap.
    private var meshLayer: Bitmap? = null
    private val meshPose = FloatArray(3) { Float.NaN }
    private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val meshBounds = RectF(0f, 0f, SEAT_VIEW_W, SEAT_VIEW_H)

    fun draw(canvas: NativeCanvas, motion: SeatMotion, unit: Float) {
        val rotation = seatRotation(motion.rollDeg * DISPLAY_SCALE, motion.pitchDeg * DISPLAY_SCALE, motion.yawDeg * DISPLAY_SCALE)
        canvas.drawBitmap(meshBitmap(motion, rotation, unit), null, meshBounds, meshPaint)
        drawDots(canvas, rotation, motion)
        drawTilt(canvas, tiltRatio(motion))
        drawTurntable(canvas, motion.yawDeg * DISPLAY_SCALE)
    }

    private fun meshBitmap(motion: SeatMotion, rotation: FloatArray, unit: Float): Bitmap {
        val width = (SEAT_VIEW_W * unit).roundToInt().coerceAtLeast(1)
        val height = (SEAT_VIEW_H * unit).roundToInt().coerceAtLeast(1)
        val layer = meshLayer?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                meshLayer = it
                meshPose[0] = Float.NaN
            }
        val pose = floatArrayOf(motion.rollDeg, motion.pitchDeg, motion.yawDeg)
        if (pose.indices.all { abs(pose[it] - meshPose[it]) < POSE_REDRAW_DEG }) return layer
        pose.copyInto(meshPose)
        layer.eraseColor(android.graphics.Color.TRANSPARENT)
        val canvas = NativeCanvas(layer)
        canvas.scale(width / SEAT_VIEW_W, height / SEAT_VIEW_H)
        canvas.drawPicture(rest)
        drawMesh(canvas, rotation, 1f)
        drawPosts(canvas, rotation)
        return layer
    }

    private fun recordRest(): android.graphics.Picture {
        val picture = android.graphics.Picture()
        val canvas = picture.beginRecording(SEAT_VIEW_W.toInt(), SEAT_VIEW_H.toInt())
        drawMesh(canvas, seatRotation(0f, 0f, 0f), 0.16f)
        picture.endRecording()
        return picture
    }

    /** Places a model point at the given rotation. */
    private fun place(rotation: FloatArray, x: Float, y: Float, z: Float) {
        val z0 = z - SEAT_PIVOT_Z
        camera.project(
            rotation[0] * x + rotation[1] * y + rotation[2] * z0,
            rotation[3] * x + rotation[4] * y + rotation[5] * z0,
            rotation[6] * x + rotation[7] * y + rotation[8] * z0 + SEAT_PIVOT_Z,
            out,
        )
    }

    private fun facing(rotation: FloatArray, x: Float, y: Float, z: Float) = camera.facing(
        rotation[0] * x + rotation[1] * y + rotation[2] * z,
        rotation[3] * x + rotation[4] * y + rotation[5] * z,
        rotation[6] * x + rotation[7] * y + rotation[8] * z,
    )

    /** A faint rim-lit skin, a smooth silhouette and a few curved seams instead of a wire grid. */
    private fun drawMesh(canvas: NativeCanvas, rotation: FloatArray, opacity: Float) {
        skin.forEach { it.rewind() }
        edge.clear()
        rim.clear()
        seamFront.clear()
        seamBack.clear()
        SEAT_PARTS.forEachIndexed { index, part ->
            val sx = screenX[index]
            val sy = screenY[index]
            val nd = faces[index]
            for (i in sx.indices) {
                place(rotation, part.vertices[i * 3], part.vertices[i * 3 + 1], part.vertices[i * 3 + 2])
                sx[i] = out[0]
                sy[i] = out[1]
                nd[i] = facing(rotation, part.normals[i * 3], part.normals[i * 3 + 1], part.normals[i * 3 + 2])
            }
            for (r in 0 until part.rows - 1) {
                for (c in 0 until part.cols) {
                    val a = r * part.cols + c
                    val b = r * part.cols + (c + 1) % part.cols
                    val d = (r + 1) * part.cols + (c + 1) % part.cols
                    val e = (r + 1) * part.cols + c
                    contour(edge, sx, sy, nd, a, b, d, e, 0f)
                    contour(rim, sx, sy, nd, a, b, d, e, 0.22f)
                    val mean = (nd[a] + nd[b] + nd[d] + nd[e]) / 4
                    if (mean <= 0) continue
                    val level = min(SKIN_LEVELS - 1, floor((1 - mean).pow(2) * SKIN_LEVELS).toInt())
                    skin[level].apply {
                        moveTo(sx[a], sy[a])
                        lineTo(sx[b], sy[b])
                        lineTo(sx[d], sy[d])
                        lineTo(sx[e], sy[e])
                        close()
                    }
                }
            }
            part.seams.forEach { seam ->
                val count = seam.points.size / 3
                var px = 0f
                var py = 0f
                for (i in 0 until count) {
                    place(rotation, seam.points[i * 3], seam.points[i * 3 + 1], seam.points[i * 3 + 2])
                    if (i > 0) {
                        val target = if (facing(rotation, seam.normals[i * 3], seam.normals[i * 3 + 1], seam.normals[i * 3 + 2]) >= 0) seamFront else seamBack
                        target.add(px, py, out[0], out[1])
                    }
                    px = out[0]
                    py = out[1]
                }
            }
        }
        val layer = opacity < 1f
        if (layer) canvas.saveLayerAlpha(0f, 0f, SEAT_VIEW_W, SEAT_VIEW_H, (opacity * 255).toInt())
        skin.forEachIndexed { i, path -> canvas.drawPath(path, skinPaint[i]) }
        seamBack.draw(canvas, seamBackLine)
        seamFront.draw(canvas, seamFrontLine)
        rim.draw(canvas, rimLine)
        edge.draw(canvas, edgeGlow)
        edge.draw(canvas, edgeLine)
        if (layer) canvas.restore()
    }

    /** The line where the surface crosses a facing value inside one quad. */
    private fun contour(lines: Lines, sx: FloatArray, sy: FloatArray, nd: FloatArray, a: Int, b: Int, c: Int, d: Int, threshold: Float) {
        var hx = 0f
        var hy = 0f
        var hits = 0
        fun crossing(p: Int, q: Int) {
            val dp = nd[p] - threshold
            val dq = nd[q] - threshold
            if ((dp < 0) == (dq < 0)) return
            val k = dp / (dp - dq)
            val x = sx[p] + (sx[q] - sx[p]) * k
            val y = sy[p] + (sy[q] - sy[p]) * k
            if (hits == 0) {
                hx = x
                hy = y
            } else if (hits == 1) {
                lines.add(hx, hy, x, y)
            }
            hits++
        }
        crossing(a, b)
        crossing(b, c)
        crossing(c, d)
        crossing(d, a)
    }

    private fun drawPosts(canvas: NativeCanvas, rotation: FloatArray) {
        gaugeLines.clear()
        for (x in floatArrayOf(-6f, 6f)) {
            place(rotation, x, 62f, -23f)
            val ax = out[0]
            val ay = out[1]
            place(rotation, x, 70f, -21f)
            gaugeLines.add(ax, ay, out[0], out[1])
        }
        gaugeLines.draw(canvas, edgeLine)
    }

    /** Dots sit on the surface, so every zone reads in both views; far-side dots show through dimly. */
    private fun drawDots(canvas: NativeCanvas, rotation: FloatArray, motion: SeatMotion) {
        dots.forEach { it.clear() }
        halos.clear()
        SEAT_PARTS.forEachIndexed { index, part ->
            val strengths = motion.vibration[index]
            val phases = motion.phase[index]
            if (strengths.all { it < ZONE_ACTIVE }) return@forEachIndexed
            val d = part.dots
            for (i in 0 until d.count) {
                val sideZone = d.sideZone[i]
                val lengthZone = d.lengthZone[i]
                val a = if (d.rank[i] <= 0.3f + 0.7f * strengths[sideZone]) swell(strengths[sideZone], phases[sideZone], d.sideReach[i]) else -1f
                val b = if (d.rank[i] <= 0.3f + 0.7f * strengths[lengthZone]) swell(strengths[lengthZone], phases[lengthZone], d.lengthReach[i]) else -1f
                if (a < 0 && b < 0) continue
                val sideWins = a >= b
                val e = if (sideWins) a else b
                val v = if (sideWins) strengths[sideZone] else strengths[lengthZone]
                val push = (0.1f + 0.45f * v) * e
                val px = d.positions[i * 3] + if (!sideWins) 0f else if (sideZone == ZONE_LEFT) -push else push
                val pz = d.positions[i * 3 + 2] + if (sideWins) 0f else if (lengthZone == ZONE_BACK) -push else push
                place(rotation, px, d.positions[i * 3 + 1], pz)
                val seen = facing(rotation, d.normals[i * 3], d.normals[i * 3 + 1], d.normals[i * 3 + 2])
                val level = (0.45f + 0.55f * v) * (if (seen < 0) 0.6f else 1f)
                val alpha = (0.1f + 0.9f * e) * level
                val bucket = min(DOT_LEVELS - 1, (alpha * DOT_LEVELS).toInt())
                dots[if (e > 0.6f) bucket + DOT_LEVELS else bucket].add(out[0], out[1])
                if (e > 0.55f && seen >= 0) halos.add(out[0], out[1])
            }
        }
        halos.draw(canvas, haloPaint)
        dots.forEachIndexed { i, points -> points.draw(canvas, dotPaint[i]) }
    }

    /** One tilt gauge above the seat. One step is 0.5° real, drawn at the seat's own display scale. */
    private fun drawTilt(canvas: NativeCanvas, ratio: Float) {
        val cx = 145f
        val cy = 354f
        val r = 296f
        val sweep = tiltSteps * 2 * PI.toFloat() / 180
        fun angle(x: Float) = -PI.toFloat() / 2 + x * sweep
        fun px(a: Float, q: Float) = cx + q * cos(a)
        fun py(a: Float, q: Float) = cy + q * sin(a)
        val moved = abs(ratio) > 0.02f
        gaugeLines.clear()
        for (i in 0 until 48) {
            val a0 = angle(-1 + 2 * i / 48f)
            val a1 = angle(-1 + 2 * (i + 1) / 48f)
            gaugeLines.add(px(a0, r), py(a0, r), px(a1, r), py(a1, r))
        }
        gaugeLines.draw(canvas, arcBack)
        drawTicks(canvas, tiltSteps, ratio) { d, major ->
            val a = angle(d)
            val tip = r + if (major) 8 else 4
            floatArrayOf(px(a, r), py(a, r), px(a, tip), py(a, tip))
        }
        if (moved) {
            gaugeLines.clear()
            for (i in 0 until 24) {
                val a0 = angle(ratio * i / 24)
                val a1 = angle(ratio * (i + 1) / 24)
                gaugeLines.add(px(a0, r), py(a0, r), px(a1, r), py(a1, r))
            }
            gaugeLines.draw(canvas, arcGlow)
            gaugeLines.draw(canvas, arcLine)
        }
        val spread = 3.5f / r
        val zero = angle(0f)
        triangle(px(zero, r + 10), py(zero, r + 10), px(zero - spread, r + 16), py(zero - spread, r + 16), px(zero + spread, r + 16), py(zero + spread, r + 16))
        canvas.drawPath(triangle, zeroLine)
        val now = angle(ratio)
        triangle(px(now, r - 2), py(now, r - 2), px(now - spread, r - 10), py(now - spread, r - 10), px(now + spread, r - 10), py(now + spread, r - 10))
        canvas.drawPath(triangle, if (moved) pointerFill else idleFill)
    }

    /** One turntable on the floor under the seat, a true circle in both views. 0 points straight ahead. */
    private fun drawTurntable(canvas: NativeCanvas, yawDeg: Float) {
        val yaw = yawDeg * PI.toFloat() / 180
        val step = DISPLAY_SCALE * PI.toFloat() / 180
        val steps = HardwareMapping.YAW_DEG.toInt()
        val full = steps * step
        val radius = 42f
        fun at(a: Float, r: Float): Pair<Float, Float> {
            camera.project(-r * sin(a), -24f, SEAT_PIVOT_Z + r * cos(a), out)
            return out[0] to out[1]
        }
        gaugeLines.clear()
        var previous = at(0f, radius)
        for (i in 1..48) {
            val next = at(i / 48f * 2 * PI.toFloat(), radius)
            gaugeLines.add(previous.first, previous.second, next.first, next.second)
            previous = next
        }
        gaugeLines.draw(canvas, arcBack)
        drawTicks(canvas, steps, yaw / full) { d, major ->
            val a = d * full
            val (ax, ay) = at(a, radius)
            val (bx, by) = at(a, radius + if (major) 3.4f else 1.8f)
            floatArrayOf(ax, ay, bx, by)
        }
        val moved = abs(yaw) > 0.01f
        if (moved) {
            gaugeLines.clear()
            previous = at(0f, radius)
            for (i in 1..16) {
                val next = at(yaw * i / 16, radius)
                gaugeLines.add(previous.first, previous.second, next.first, next.second)
                previous = next
            }
            gaugeLines.draw(canvas, arcGlow)
            gaugeLines.draw(canvas, arcLine)
        }
        val spread = 1.5f / radius
        val (zx, zy) = at(0f, radius + 4.2f)
        val (z1x, z1y) = at(-spread, radius + 7)
        val (z2x, z2y) = at(spread, radius + 7)
        triangle(zx, zy, z1x, z1y, z2x, z2y)
        canvas.drawPath(triangle, zeroLine)
        val (px, py) = at(yaw, radius - 1)
        val (p1x, p1y) = at(yaw - spread, radius - 4.5f)
        val (p2x, p2y) = at(yaw + spread, radius - 4.5f)
        triangle(px, py, p1x, p1y, p2x, p2y)
        canvas.drawPath(triangle, if (moved) pointerFill else idleFill)
    }

    /** Ticks from -1 to 1 in [steps] per side. Every second one is a major tick; those up to [ratio] light up. */
    private inline fun drawTicks(canvas: NativeCanvas, steps: Int, ratio: Float, segment: (Float, Boolean) -> FloatArray) {
        for (i in -steps..steps) {
            val d = i.toFloat() / steps
            val lit = abs(ratio) > 0.02f && d * ratio >= 0 && abs(d) <= abs(ratio) + 0.001f
            val s = segment(d, i % 2 == 0)
            canvas.drawLine(s[0], s[1], s[2], s[3], if (lit) litTick else dimTick)
        }
    }

    private fun triangle(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) {
        triangle.rewind()
        triangle.moveTo(ax, ay)
        triangle.lineTo(bx, by)
        triangle.lineTo(cx, cy)
        triangle.close()
    }
}

private fun fill(color: Color) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color.toArgb()
    style = Paint.Style.FILL
}

private fun line(color: Color, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color.toArgb()
    style = Paint.Style.STROKE
    strokeWidth = width
    strokeCap = Paint.Cap.ROUND
}

private fun round(color: Color, width: Float) = line(color, width)
