package ai.activeseat.app.ui

import ai.activeseat.app.hardware.CabinPlace
import ai.activeseat.app.hardware.CabinSeat
import ai.activeseat.app.hardware.HardwareSettings
import ai.activeseat.app.hardware.LEVEL_MAX
import ai.activeseat.app.hardware.LEVEL_OFF
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

class SettingsActions(
    val onClose: () -> Unit,
    val onChange: (HardwareSettings) -> Unit,
    val onReset: () -> Unit,
    val onPreview: () -> Unit,
    /** Picks which rear seat's settings the panel edits. */
    val onSelectRear: (CabinPlace) -> Unit,
)

/** Alpha of a slider that does nothing while its module is off. */
private const val OFF_ALPHA = 0.35f

/** Alpha of a button that cannot be pressed. */
private const val DISABLED_ALPHA = 0.5f

private val TrackInset = 16.dp
private val TrackHeight = 60.dp

/**
 * The settings of the seat in view, in the left half while open. The seat panel on the right stays in view,
 * so a preview shows the effect of each setting. See the plan's 사용자 설정.
 */
@Composable
fun SettingsPanel(
    settings: HardwareSettings,
    seat: CabinPlace,
    previewLabel: String?,
    hintsOff: Boolean,
    actions: SettingsActions,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .background(PanelColor)
            .drawBehind {
                val x = size.width - 0.5.dp.toPx()
                drawLine(BracketColor, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            }
            .padding(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsTitle(seat, actions.onClose)
        // The rear tab shows both rear seats, each with its own settings: pick the one to edit.
        if (seat.tab == CabinSeat.REAR) RearSeatChoice(seat, actions.onSelectRear)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(56.dp),
        ) {
            SettingsSection("시트") {
                LevelSlider("시트", "민감도", settings.seatSensitivity, true) {
                    actions.onChange(settings.copy(seatSensitivity = it))
                }
                LevelSlider("시트", "진동 세기", settings.seatVibration, settings.seatOn) {
                    actions.onChange(settings.copy(seatVibration = it))
                }
                LevelSlider("시트", "회전 속도", settings.leanSpeed, settings.seatOn) {
                    actions.onChange(settings.copy(leanSpeed = it))
                }
            }
            SettingsSection("워치") {
                LevelSlider("워치", "민감도", settings.watchSensitivity, true) {
                    actions.onChange(settings.copy(watchSensitivity = it))
                }
                LevelSlider("워치", "진동 세기", settings.watchVibration, settings.watchOn) {
                    actions.onChange(settings.copy(watchVibration = it))
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PanelButton(
                text = "초기화",
                enabled = true,
                background = Color.Transparent,
                border = BracketColor,
                onClick = actions.onReset,
                modifier = Modifier.width(240.dp),
            )
            PanelButton(
                text = previewLabel?.let { "$it 재생 중" } ?: "미리보기",
                enabled = previewLabel == null && !hintsOff,
                background = BarTrack,
                border = BarTrack,
                onClick = actions.onPreview,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Two buttons, 왼쪽 and 오른쪽, the chosen one filled amber. */
@Composable
private fun RearSeatChoice(chosen: CabinPlace, onSelect: (CabinPlace) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(CabinPlace.REAR_LEFT to "왼쪽", CabinPlace.REAR_RIGHT to "오른쪽").forEach { (place, label) ->
            val on = place == chosen
            Box(
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .background(if (on) Amber else Color.Transparent)
                    .border(1.dp, if (on) Amber else BracketColor)
                    .clickable { onSelect(place) }
                    .semantics { contentDescription = "${place.label} 설정" },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) PanelColor else TextBright, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SettingsTitle(seat: CabinPlace, onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(132.dp).drawBehind { drawBrackets() }) {
        Box(Modifier.matchParentSize().padding(top = 10.dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
            // Each seat keeps its own settings; the tab in view picks which.
            PanelText("${seat.label} 설정", 40.sp, FontWeight.Bold, TextBright)
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 27.dp)
                .size(64.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onClose,
                )
                .semantics { contentDescription = "설정 닫기" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(28.dp)) {
                val u = size.width / 24f
                val stroke = 1.8f * u
                drawLine(TextBright, Offset(6 * u, 6 * u), Offset(18 * u, 18 * u), stroke, StrokeCap.Round)
                drawLine(TextBright, Offset(18 * u, 6 * u), Offset(6 * u, 18 * u), stroke, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelText(title, 32.sp, FontWeight.Bold, TextBright)
            Box(Modifier.weight(1f).height(1.dp).background(BracketColor))
        }
        content()
    }
}

/** One setting from off to [LEVEL_MAX]. A tap or a drag anywhere on the track snaps to the nearest level. */
@Composable
private fun LevelSlider(section: String, label: String, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val inset = with(LocalDensity.current) { TrackInset.toPx() }
    fun pick(x: Float, width: Int) {
        val level = nearestLevel((x - inset) / (width - 2 * inset), LEVEL_MAX)
        if (level != current) change(level)
    }
    val glow = rememberGlowPaints()
    val text = levelText(value)
    Column(
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else OFF_ALPHA),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PanelText(label, 26.sp, FontWeight.Medium, TextBright, Modifier.weight(1f).alignByBaseline())
            PanelText(
                text,
                34.sp,
                FontWeight.Bold,
                if (value > LEVEL_OFF) Amber else TextDim,
                Modifier.widthIn(min = 64.dp).alignByBaseline(),
                TextAlign.End,
            )
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(TrackHeight)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { pick(it.x, size.width) }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(onDragStart = { pick(it.x, size.width) }) { pointer, _ ->
                        pointer.consume()
                        pick(pointer.position.x, size.width)
                    }
                }
                .semantics {
                    contentDescription = "$section $label"
                    stateDescription = text
                    progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), LEVEL_OFF.toFloat()..LEVEL_MAX.toFloat(), LEVEL_MAX - LEVEL_OFF - 1)
                    if (enabled) {
                        setProgress { target ->
                            val level = target.roundToInt().coerceIn(LEVEL_OFF, LEVEL_MAX)
                            if (level != current) change(level)
                            true
                        }
                    } else {
                        disabled()
                    }
                },
        ) {
            drawLevelTrack(value, glow)
        }
    }
}

/** The track as the mock-up draws it, in a [TrackHeight] tall area with the bar centred. */
private fun DrawScope.drawLevelTrack(value: Int, glow: GlowPaints) {
    val inset = TrackInset.toPx()
    val left = inset
    val width = size.width - 2 * inset
    val cy = size.height / 2
    val on = value > LEVEL_OFF
    val x = left + width * value / LEVEL_MAX
    val bar = 6.dp.toPx()
    drawRect(GaugeRest, Offset(left, cy - bar / 2), Size(width, bar))
    val fill = 16.dp.toPx()
    if (x > left) {
        drawIntoCanvas { it.nativeCanvas.drawRect(left, cy - fill / 2, x, cy + fill / 2, glow.fill) }
        drawRect(GaugeArc, Offset(left, cy - fill / 2), Size(x - left, fill))
    }
    val tickW = 2.dp.toPx()
    val tickH = 30.dp.toPx()
    val outline = 1.dp.toPx()
    for (i in LEVEL_OFF..LEVEL_MAX) {
        val tx = left + width * i / LEVEL_MAX
        drawRect(PanelColor, Offset(tx - tickW / 2 - outline, cy - tickH / 2 - outline), Size(tickW + 2 * outline, tickH + 2 * outline))
        drawRect(if (on && i <= value) GaugeLit else GaugeIdle, Offset(tx - tickW / 2, cy - tickH / 2), Size(tickW, tickH))
    }
    val radius = 23.dp.toPx()
    val ring = 3.dp.toPx()
    val center = Offset(x, cy)
    drawIntoCanvas { it.nativeCanvas.drawCircle(center.x, center.y, radius, glow.thumb) }
    drawCircle(PanelColor, radius, center)
    drawCircle(if (on) GaugePointer else GaugeIdle, radius - ring / 2, center, style = Stroke(ring))
    drawCircle(if (on) Amber else TextDim, 8.dp.toPx(), center)
}

/** Soft halos like the mock-up's box shadows: 10dp around the fill, 12dp around the thumb. */
private class GlowPaints(val fill: Paint, val thumb: Paint)

@Composable
private fun rememberGlowPaints(): GlowPaints {
    val density = LocalDensity.current
    return remember(density) {
        fun glow(color: Color, blur: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            maskFilter = BlurMaskFilter(with(density) { blur.dp.toPx() }, BlurMaskFilter.Blur.NORMAL)
        }
        GlowPaints(glow(GaugeIdle.copy(alpha = 0.9f), 10f), glow(GaugeIdle.copy(alpha = 0.7f), 12f))
    }
}

@Composable
private fun PanelButton(
    text: String,
    enabled: Boolean,
    background: Color,
    border: Color,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .height(80.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .background(background)
            .border(1.dp, border)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        PanelText(text, 24.sp, FontWeight.Normal, TextBright)
    }
}

/** Text with a line height that fits its size, instead of the theme's body line height. */
@Composable
private fun PanelText(
    text: String,
    size: TextUnit,
    weight: FontWeight,
    color: Color,
    modifier: Modifier = Modifier,
    align: TextAlign? = null,
) {
    Text(
        text,
        modifier = modifier,
        color = color,
        fontSize = size,
        fontWeight = weight,
        lineHeight = size * 1.3f,
        maxLines = 1,
        textAlign = align,
    )
}
