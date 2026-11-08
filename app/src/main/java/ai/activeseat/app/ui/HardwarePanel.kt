package ai.activeseat.app.ui

import ai.activeseat.app.hardware.HardwareCommand
import ai.activeseat.app.hardware.HardwareMapping
import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.MotionProbabilities
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** What one seat's page shows. [restKey] changes when that seat must snap back to rest at once. */
class SeatDisplay(
    val command: HardwareCommand,
    val forecast: Hint,
    val probabilities: MotionProbabilities,
    val hintsOff: Boolean,
    val restKey: Int,
    val occupancy: SeatOccupancy,
)

/** One page per seat, each following its own command, under the current seat's forecast. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HardwarePanel(
    displays: Map<CabinSeat, SeatDisplay>,
    settingsOpen: Boolean,
    onToggleSettings: () -> Unit,
    modifier: Modifier,
    seatPager: PagerState,
) {
    val motions = remember { CabinSeat.entries.associateWith { SeatMotion() } }
    val frame = remember { mutableLongStateOf(0L) }
    val latest = rememberUpdatedState(displays)
    CabinSeat.entries.forEach { seat ->
        key(seat) {
            LaunchedEffect(displays.getValue(seat).restKey) {
                motions.getValue(seat).reset()
                frame.longValue++
            }
        }
    }
    LaunchedEffect(Unit) {
        var lastNs = 0L
        while (true) {
            withFrameNanos { now ->
                val dtSec = if (lastNs == 0L) 0f else min(0.25f, (now - lastNs) / 1e9f)
                lastNs = now
                var moved = false
                motions.forEach { (seat, motion) -> if (motion.step(dtSec, latest.value.getValue(seat).command)) moved = true }
                if (moved) frame.longValue++
            }
        }
    }
    val seat = CabinSeat.entries[seatPager.currentPage]
    val current = displays.getValue(seat)
    Column(
        modifier = modifier.background(PanelColor).padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ForecastTitle(current.forecast, current.hintsOff, current.occupancy, settingsOpen, onToggleSettings)
        HorizontalPager(state = seatPager, modifier = Modifier.weight(1f).fillMaxWidth(), beyondBoundsPageCount = 1) { page ->
            val shown = CabinSeat.entries[page]
            SeatPage(shown, displays.getValue(shown), motions.getValue(shown), frame)
        }
        Row(Modifier.fillMaxWidth().height(116.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ProbabilityBars(current.probabilities, Modifier.weight(1f).fillMaxHeight())
            WatchView(motions.getValue(seat), frame, current.forecast, Modifier.width(130.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun ForecastTitle(forecast: Hint, hintsOff: Boolean, occupancy: SeatOccupancy, settingsOpen: Boolean, onToggleSettings: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(132.dp).drawBehind { drawBrackets() }) {
        Box(Modifier.matchParentSize().padding(top = 10.dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("차량 움직임 예측", color = TextDim, fontSize = 21.sp, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    Text(
                        if (hintsOff) "힌트 꺼짐" else seatStatusText(forecast, occupancy),
                        color = if (!hintsOff && isForecastActive(forecast)) Amber else TextBright,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Text("강도 ${strengthPercent(forecast)}", color = TextBright, fontSize = 28.sp, maxLines = 1, modifier = Modifier.alignByBaseline())
                }
            }
        }
        SettingsButton(settingsOpen, onToggleSettings, Modifier.align(Alignment.TopEnd).padding(top = 27.dp, end = 16.dp))
    }
}

/** Opens and closes the settings panel; filled amber while it is open. */
@Composable
private fun SettingsButton(open: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    val ink = if (open) PanelColor else TextBright
    val knob = if (open) Amber else PanelColor
    Box(
        modifier = modifier
            .size(64.dp)
            .background(if (open) Amber else Color.Transparent)
            .border(1.dp, if (open) Amber else BracketColor)
            .toggleable(
                value = open,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onValueChange = { onToggle() },
            )
            .semantics { contentDescription = "설정" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(32.dp)) {
            val u = size.width / 24f
            val stroke = 1.6f * u
            drawLine(ink, Offset(4 * u, 7 * u), Offset(20 * u, 7 * u), stroke, StrokeCap.Round)
            drawLine(ink, Offset(4 * u, 17 * u), Offset(20 * u, 17 * u), stroke, StrokeCap.Round)
            for (center in listOf(Offset(9 * u, 7 * u), Offset(15 * u, 17 * u))) {
                drawCircle(knob, 2.4f * u, center)
                drawCircle(ink, 2.4f * u, center, style = Stroke(stroke))
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

private const val EMPTY_SEAT_ALPHA = 0.45f

/** One seat's front and side views. */
@Composable
private fun SeatPage(seat: CabinSeat, display: SeatDisplay, motion: SeatMotion, frame: LongState) {
    val front = remember(seat) { SeatRenderer(seat.shape, SeatCamera.FRONT, tiltSteps = 6, tiltRatio = { it.rollDeg / HardwareMapping.MAX_LEAN_DEG.toFloat() }, showFixture = true) }
    val side = remember(seat) { SeatRenderer(seat.shape, seat.sideCamera, tiltSteps = 4, tiltRatio = { seat.sideDirection * it.pitchDeg / HardwareMapping.MAX_LEAN_DEG.toFloat() }) }
    // A preview still plays on an empty seat, so it keeps full brightness.
    val resting = display.occupancy == SeatOccupancy.EMPTY && !isForecastActive(display.forecast)
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().alpha(if (resting) EMPTY_SEAT_ALPHA else 1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SeatView(front, motion, frame)
            SeatView(side, motion, frame)
        }
        occupancyNotice(display.occupancy)?.let {
            Text(it, color = TextBright, fontSize = 22.sp, maxLines = 1, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp))
        }
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
