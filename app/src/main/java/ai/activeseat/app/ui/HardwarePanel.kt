package ai.activeseat.app.ui

import ai.activeseat.app.hardware.HardwareCommand
import ai.activeseat.app.hardware.ModuleCommand
import ai.activeseat.app.hardware.WatchPattern
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private const val SMOOTHING_MS = 250
private const val RIPPLE_PERIOD_MS = 900
private const val RIPPLE_RINGS = 3

private val CardColor = Color(0xFF1B2126)
private val CardEdge = Color(0xFF333D44)
private val Accent = Color(0xFFFFB300)
private val SeatIdle = Color(0xFF6E7B84)
private val SeatFixed = Color(0xFF3A444B)
private val TextIdle = Color(0xFFB7C1C8)

// Widths in sketch units. Sketches are scaled to fit, so one object serves every size.
private val GhostStroke = Stroke(2.5f)
private val RingStroke = Stroke(3f)
private val YawTrackStroke = Stroke(4f)
private val YawArrowStroke = Stroke(7f, cap = StrokeCap.Round)

// Seat sketches: arrows use y 0..16, the seat sits below.
private const val SEAT_BOX_W = 100f
private const val SEAT_BOX_H = 118f
private const val ARROW_Y = 8f
private const val ARROW_WIDTH = 7f
private const val RECLINE_DEG = -10f

private const val YAW_BOX_W = 200f
private const val YAW_BOX_H = 80f
private const val YAW_RESTING_RADIUS = 46f
private const val YAW_ARROW_RADIUS = 62f
private const val YAW_ARROW_SWEEP = 50f
private val YawHead = Offset(100f, 68f)

private enum class SeatPart { HEADREST, BACKREST, CUSHION }

/** Smoothed real values. Read them inside draw lambdas so a change only redraws. */
private class ModuleMotion(
    val roll: State<Float>,
    val pitch: State<Float>,
    val yaw: State<Float>,
    val vibration: State<Float>,
    val activeLevel: State<Float>,
)

@Composable
fun HardwarePanel(command: HardwareCommand, modifier: Modifier) {
    val ripple = rememberInfiniteTransition(label = "ripple").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(RIPPLE_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "ripplePhase",
    )
    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "시연용 확대 표시 ×${DISPLAY_SCALE.roundToInt()}",
            color = TextIdle,
            fontSize = 20.sp,
            modifier = Modifier.align(Alignment.End),
        )
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HeadrestCard(command.headrest, ripple, Modifier.weight(1f))
            SeatCard("등받이", SeatPart.BACKREST, command.backrest, ripple, Modifier.weight(1f))
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SeatCard("쿠션", SeatPart.CUSHION, command.cushion, ripple, Modifier.weight(1f))
            WatchCard(command.watch, command.watchStrength, Modifier.weight(1f))
        }
    }
}

@Composable
private fun rememberMotion(module: ModuleCommand) = ModuleMotion(
    roll = smoothed(module.rollDeg.toFloat(), "roll"),
    pitch = smoothed(module.pitchDeg.toFloat(), "pitch"),
    yaw = smoothed(module.yawDeg.toFloat(), "yaw"),
    vibration = smoothed(module.vibration.toFloat().coerceIn(0f, 1f), "vibration"),
    activeLevel = smoothed(if (isModuleActive(module)) 1f else 0f, "active"),
)

@Composable
private fun smoothed(target: Float, label: String): State<Float> =
    animateFloatAsState(target, tween(SMOOTHING_MS), label = label)

@Composable
private fun HeadrestCard(module: ModuleCommand, ripple: State<Float>, modifier: Modifier) {
    val motion = rememberMotion(module)
    ModuleCard("헤드레스트", isModuleActive(module), motion.activeLevel, vibrationText(module), modifier) {
        SeatViews(SeatPart.HEADREST, module, motion, ripple, Modifier.weight(1f))
        Canvas(Modifier.fillMaxWidth().height(100.dp)) {
            drawYaw(motion.yaw.value, motion.activeLevel.value)
        }
        ValueText("시야 유도 ${lateralText(module.yawDeg)}", Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun SeatCard(title: String, part: SeatPart, module: ModuleCommand, ripple: State<Float>, modifier: Modifier) {
    val motion = rememberMotion(module)
    ModuleCard(title, isModuleActive(module), motion.activeLevel, vibrationText(module), modifier) {
        SeatViews(part, module, motion, ripple, Modifier.weight(1f))
    }
}

/** Side view for pitch next to rear view for roll. */
@Composable
private fun SeatViews(
    part: SeatPart,
    module: ModuleCommand,
    motion: ModuleMotion,
    ripple: State<Float>,
    modifier: Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SeatView("옆에서 · 앞쪽 →", "기울기 ${pitchText(module.pitchDeg)}", Modifier.weight(1f)) {
            drawSeatView(
                part = part,
                rear = false,
                deg = screenPitchDeg(motion.pitch.value),
                vibration = motion.vibration.value,
                ripple = ripple,
                activeLevel = motion.activeLevel.value,
            )
            when (pitchArrow(motion.pitch.value)) {
                PitchArrow.FORWARD -> drawArrow(1f)
                PitchArrow.BACK -> drawArrow(-1f)
                PitchArrow.NONE -> Unit
            }
        }
        SeatView("뒤에서", "기울기 ${lateralText(module.rollDeg)}", Modifier.weight(1f)) {
            drawSeatView(
                part = part,
                rear = true,
                deg = screenLateralDeg(motion.roll.value),
                vibration = motion.vibration.value,
                ripple = ripple,
                activeLevel = motion.activeLevel.value,
            )
            when (lateralArrow(motion.roll.value)) {
                LateralArrow.LEFT -> drawArrow(-1f)
                LateralArrow.RIGHT -> drawArrow(1f)
                LateralArrow.NONE -> Unit
            }
        }
    }
}

@Composable
private fun SeatView(label: String, value: String, modifier: Modifier, draw: DrawScope.() -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextIdle, fontSize = 17.sp, maxLines = 1)
        Canvas(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            inUnitBox(SEAT_BOX_W, SEAT_BOX_H) { draw() }
        }
        ValueText(value)
    }
}

@Composable
private fun WatchCard(pattern: WatchPattern, strength: Double, modifier: Modifier) {
    val active = pattern != WatchPattern.NONE
    val activeLevel = smoothed(if (active) 1f else 0f, "active")
    val elapsedMs = remember { mutableLongStateOf(0L) }
    LaunchedEffect(pattern) {
        elapsedMs.longValue = 0L
        if (pattern == WatchPattern.NONE) return@LaunchedEffect
        val startMs = withFrameMillis { it }
        while (true) withFrameMillis { elapsedMs.longValue = it - startMs }
    }
    ModuleCard("워치", active, activeLevel, null, modifier) {
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            inUnitBox(100f, 100f) { drawWatch(pattern, strength.toFloat(), elapsedMs, activeLevel.value) }
        }
        Text(
            watchPatternText(pattern),
            color = if (active) Accent else TextIdle,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun ModuleCard(
    title: String,
    active: Boolean,
    activeLevel: State<Float>,
    status: String?,
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .drawBehind {
                val corner = CornerRadius(20.dp.toPx())
                drawRoundRect(CardColor, cornerRadius = corner)
                drawRoundRect(
                    lerp(CardEdge, Accent, activeLevel.value),
                    cornerRadius = corner,
                    style = Stroke(3.dp.toPx()),
                )
            }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                color = if (active) Accent else Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (status != null) ValueText(status)
        }
        content()
    }
}

@Composable
private fun ValueText(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = modifier)
}

private fun vibrationText(module: ModuleCommand): String =
    "진동 ${(module.vibration.coerceIn(0.0, 1.0) * 100).roundToInt()}%"

/** Runs [block] in a [width] by [height] unit space centered in the canvas. */
private inline fun DrawScope.inUnitBox(width: Float, height: Float, block: DrawScope.() -> Unit) {
    val unit = min(size.width / width, size.height / height)
    translate((size.width - width * unit) / 2f, (size.height - height * unit) / 2f) {
        scale(unit, unit, Offset.Zero) { block() }
    }
}

private fun DrawScope.piece(color: Color, x: Float, y: Float, w: Float, h: Float, r: Float) {
    drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(r))
}

private fun DrawScope.ghost(x: Float, y: Float, w: Float, h: Float, r: Float) {
    drawRoundRect(SeatIdle, Offset(x, y), Size(w, h), CornerRadius(r), style = GhostStroke)
}

/** The moving [part] leans [deg] clockwise; the outline stays at the resting pose as a reference. */
private fun DrawScope.drawSeatView(
    part: SeatPart,
    rear: Boolean,
    deg: Float,
    vibration: Float,
    ripple: State<Float>,
    activeLevel: Float,
) {
    val color = lerp(SeatIdle, Accent, activeLevel)
    when (part) {
        SeatPart.HEADREST -> if (rear) {
            drawRipples(50f, 54f, vibration, ripple)
            drawRearHeadrest(deg, color)
        } else {
            drawRipples(37f, 54f, vibration, ripple)
            drawSideHeadrest(deg, color)
        }
        SeatPart.BACKREST -> if (rear) {
            drawRipples(50f, 66f, vibration, ripple)
            drawRearSeat(part, deg, color)
        } else {
            drawRipples(36f, 64f, vibration, ripple)
            drawSideSeat(part, deg, color)
        }
        SeatPart.CUSHION -> if (rear) {
            drawRipples(50f, 103f, vibration, ripple)
            drawRearSeat(part, deg, color)
        } else {
            drawRipples(62f, 96f, vibration, ripple)
            drawSideSeat(part, deg, color)
        }
    }
}

private fun DrawScope.drawRearHeadrest(deg: Float, color: Color) {
    val pivot = Offset(50f, 90f)
    piece(SeatFixed, 20f, 88f, 60f, 30f, 10f)
    ghost(14f, 30f, 72f, 48f, 16f)
    rotate(deg, pivot) {
        drawLine(color, Offset(50f, 74f), pivot, 8f)
        piece(color, 14f, 30f, 72f, 48f, 16f)
    }
}

private fun DrawScope.drawSideHeadrest(deg: Float, color: Color) {
    val pivot = Offset(37f, 90f)
    piece(SeatFixed, 16f, 88f, 44f, 30f, 10f)
    ghost(18f, 24f, 38f, 54f, 14f)
    rotate(deg, pivot) {
        drawLine(color, Offset(37f, 74f), pivot, 8f)
        piece(color, 18f, 24f, 38f, 54f, 14f)
    }
}

private fun DrawScope.drawRearSeat(part: SeatPart, deg: Float, color: Color) {
    val hip = Offset(50f, 96f)
    val cushionCenter = Offset(50f, 103f)
    val moving = part == SeatPart.BACKREST
    if (moving) ghost(24f, 42f, 52f, 54f, 10f) else ghost(14f, 94f, 72f, 18f, 8f)
    rotate(if (moving) deg else 0f, hip) {
        drawLine(SeatFixed, Offset(50f, 36f), Offset(50f, 44f), 6f)
        piece(SeatFixed, 34f, 22f, 32f, 16f, 7f)
        piece(if (moving) color else SeatFixed, 24f, 42f, 52f, 54f, 10f)
    }
    rotate(if (moving) 0f else deg, cushionCenter) {
        piece(if (moving) SeatFixed else color, 14f, 94f, 72f, 18f, 8f)
    }
}

private fun DrawScope.drawSideSeat(part: SeatPart, deg: Float, color: Color) {
    val hip = Offset(35f, 92f)
    val cushionCenter = Offset(62f, 96f)
    val moving = part == SeatPart.BACKREST
    if (moving) {
        rotate(RECLINE_DEG, hip) { ghost(28f, 38f, 15f, 56f, 7f) }
    } else {
        ghost(34f, 88f, 56f, 16f, 7f)
    }
    rotate(RECLINE_DEG + if (moving) deg else 0f, hip) {
        drawLine(SeatFixed, Offset(35f, 33f), Offset(35f, 40f), 5f)
        piece(SeatFixed, 29f, 20f, 13f, 15f, 5f)
        piece(if (moving) color else SeatFixed, 28f, 38f, 15f, 56f, 7f)
    }
    rotate(if (moving) 0f else deg, cushionCenter) {
        piece(if (moving) SeatFixed else color, 34f, 88f, 56f, 16f, 7f)
    }
}

/** Expanding rings. The phase is only read while vibrating, so idle cards do not redraw. */
private fun DrawScope.drawRipples(cx: Float, cy: Float, vibration: Float, ripple: State<Float>) {
    if (vibration < 0.01f) return
    val phase = ripple.value
    for (i in 0 until RIPPLE_RINGS) {
        val p = (phase + i.toFloat() / RIPPLE_RINGS) % 1f
        drawCircle(
            Accent,
            radius = 14f + p * 34f * vibration,
            center = Offset(cx, cy),
            alpha = (1f - p) * (0.25f + 0.75f * vibration),
            style = RingStroke,
        )
    }
}

/** Bold arrow in the band above a seat. [direction] is -1 for screen left, 1 for screen right. */
private fun DrawScope.drawArrow(direction: Float) {
    val tail = Offset(50f - direction * 30f, ARROW_Y)
    val tip = Offset(50f + direction * 30f, ARROW_Y)
    val barbX = tip.x - direction * 9f
    drawLine(Accent, tail, tip, ARROW_WIDTH, StrokeCap.Round)
    drawLine(Accent, tip, Offset(barbX, ARROW_Y - 6.5f), ARROW_WIDTH, StrokeCap.Round)
    drawLine(Accent, tip, Offset(barbX, ARROW_Y + 6.5f), ARROW_WIDTH, StrokeCap.Round)
}

/** Top view: forward is up, and the needle shows where the head is guided to look. */
private fun DrawScope.drawYaw(realDeg: Float, activeLevel: Float) {
    val color = lerp(SeatIdle, Accent, activeLevel)
    inUnitBox(YAW_BOX_W, YAW_BOX_H) {
        val rest = Offset(YawHead.x, YawHead.y - YAW_RESTING_RADIUS)
        drawArc(
            SeatFixed,
            startAngle = -150f,
            sweepAngle = 120f,
            useCenter = false,
            topLeft = Offset(YawHead.x - YAW_RESTING_RADIUS, YawHead.y - YAW_RESTING_RADIUS),
            size = Size(YAW_RESTING_RADIUS * 2f, YAW_RESTING_RADIUS * 2f),
            style = YawTrackStroke,
        )
        drawLine(SeatFixed, YawHead, rest, 3f)
        rotate(screenLateralDeg(realDeg), YawHead) {
            drawLine(color, YawHead, rest, 6f, StrokeCap.Round)
            drawCircle(color, 7f, rest)
        }
        drawCircle(color, 10f, YawHead)
        when (lateralArrow(realDeg)) {
            LateralArrow.LEFT -> drawYawArrow(-1f)
            LateralArrow.RIGHT -> drawYawArrow(1f)
            LateralArrow.NONE -> Unit
        }
    }
}

private fun DrawScope.drawYawArrow(direction: Float) {
    val r = YAW_ARROW_RADIUS
    val sweep = direction * YAW_ARROW_SWEEP
    drawArc(
        Accent,
        startAngle = -90f,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(YawHead.x - r, YawHead.y - r),
        size = Size(r * 2f, r * 2f),
        style = YawArrowStroke,
    )
    val angle = (-90f + sweep) * (PI.toFloat() / 180f)
    val outX = cos(angle)
    val outY = sin(angle)
    val tangentX = -outY * direction
    val tangentY = outX * direction
    val tip = Offset(YawHead.x + outX * r, YawHead.y + outY * r)
    val backX = tip.x - tangentX * 10f
    val backY = tip.y - tangentY * 10f
    drawLine(Accent, tip, Offset(backX + outX * 7f, backY + outY * 7f), 7f, StrokeCap.Round)
    drawLine(Accent, tip, Offset(backX - outX * 7f, backY - outY * 7f), 7f, StrokeCap.Round)
}

private fun DrawScope.drawWatch(pattern: WatchPattern, strength: Float, elapsedMs: MutableLongState, activeLevel: Float) {
    val center = Offset(50f, 50f)
    val ringColor = lerp(SeatFixed, Accent, activeLevel)
    drawRoundRect(SeatFixed, Offset(36f, 0f), Size(28f, 100f), CornerRadius(6f))
    // elapsedMs is only read while a pattern plays, so the idle watch does not redraw.
    if (pattern != WatchPattern.NONE && isWatchPulseOn(pattern.pulsesMs, elapsedMs.longValue)) {
        val brightness = 0.35f + 0.65f * strength.coerceIn(0f, 1f)
        drawCircle(Accent, 31f, center, alpha = brightness, style = RingStroke)
        drawCircle(Accent, 38f, center, alpha = brightness * 0.75f, style = RingStroke)
        drawCircle(Accent, 45f, center, alpha = brightness * 0.5f, style = RingStroke)
        drawCircle(Accent, 24f, center, alpha = brightness * 0.5f)
    }
    drawCircle(ringColor, 25f, center, style = YawTrackStroke)
    drawLine(TextIdle, center, Offset(50f, 34f), 3f, StrokeCap.Round)
    drawLine(TextIdle, center, Offset(61f, 50f), 3f, StrokeCap.Round)
}
