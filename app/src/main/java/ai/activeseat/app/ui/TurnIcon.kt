package ai.activeseat.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.cos
import kotlin.math.sin

/** The maneuver pictograms of the guidance bar. */
enum class TurnIcon { LEFT, RIGHT, KEEP_LEFT, KEEP_RIGHT, STRAIGHT, ARRIVE, REROUTE }

fun turnIcon(type: String): TurnIcon = when (type) {
    "TURN_LEFT", "TURN_SHARP_L" -> TurnIcon.LEFT
    "TURN_RIGHT", "TURN_SHARP_R" -> TurnIcon.RIGHT
    "TURN_KEEP_L", "TURN_ENTER_IC_L", "TURN_EXIT_IC_L" -> TurnIcon.KEEP_LEFT
    "TURN_KEEP_R", "TURN_EXIT_IC_R", "TURN_ENTER_IC_R", "TURN_ENTER_IC_C" -> TurnIcon.KEEP_RIGHT
    "TURN_ARRIVE" -> TurnIcon.ARRIVE
    else -> TurnIcon.STRAIGHT
}

// Drawn on a 24-unit grid. One stroke weight, round ends and a filled head for every icon, and
// each shape is placed so its whole outline, head included, is centred in the grid.
private const val GRID = 24f
private const val STROKE = 3.2f

@Composable
fun TurnIconView(icon: TurnIcon, description: String, modifier: Modifier, color: Color = Color.White) {
    // The search icon turns while the search runs.
    val spin = if (icon == TurnIcon.REROUTE) {
        val turning by rememberInfiniteTransition(label = "reroute").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
            label = "reroute",
        )
        Modifier.rotate(turning)
    } else {
        Modifier
    }
    Canvas(modifier.then(spin).semantics { contentDescription = description }) {
        val unit = size.minDimension / GRID
        translate((size.width - GRID * unit) / 2, (size.height - GRID * unit) / 2) {
            scale(unit, unit, pivot = Offset.Zero) { drawIcon(icon, color) }
        }
    }
}

private fun DrawScope.drawIcon(icon: TurnIcon, color: Color) {
    val line = Stroke(width = STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (icon) {
        TurnIcon.RIGHT -> turn(color, line, mirror = false)
        TurnIcon.LEFT -> turn(color, line, mirror = true)
        TurnIcon.KEEP_RIGHT -> keep(color, line, mirror = false)
        TurnIcon.KEEP_LEFT -> keep(color, line, mirror = true)
        TurnIcon.STRAIGHT -> {
            drawPath(Path().apply { moveTo(12f, 21f); lineTo(12f, 9.5f) }, color, style = line)
            head(color, tip = Offset(12f, 2.5f), baseCentre = Offset(12f, 9.5f), halfWidth = 4.5f)
        }
        TurnIcon.ARRIVE -> {
            drawPath(Path().apply { moveTo(7.5f, 21.5f); lineTo(7.5f, 3f) }, color, style = line)
            val flag = Path().apply {
                moveTo(7.5f, 3f); lineTo(18.5f, 7.5f); lineTo(7.5f, 12f); close()
            }
            drawPath(flag, color, style = Fill)
            drawPath(flag, color, style = Stroke(width = 1.2f, join = StrokeJoin.Round))
        }
        TurnIcon.REROUTE -> {
            val centre = Offset(12f, 12.5f)
            val radius = 7.5f
            // Clockwise from the top right round to the top: the head closes the gap.
            val start = -50f
            val sweep = 270f
            drawArc(color, start, sweep, useCenter = false, topLeft = centre - Offset(radius, radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2), style = line)
            val end = Math.toRadians((start + sweep).toDouble())
            val at = Offset(centre.x + radius * cos(end).toFloat(), centre.y + radius * sin(end).toFloat())
            val ahead = Offset(-sin(end).toFloat(), cos(end).toFloat())
            head(color, tip = at + ahead * 5f, baseCentre = at - ahead * 0.5f, halfWidth = 4f)
        }
    }
}

/** A right-angle turn: up the stem, a round corner, then the head. Left is its mirror image. */
private fun DrawScope.turn(color: Color, line: Stroke, mirror: Boolean) = mirrored(mirror) {
    val stem = Path().apply {
        moveTo(6f, 21f)
        lineTo(6f, 10.5f)
        arcTo(Rect(6f, 6.5f, 14f, 14.5f), 180f, 90f, false)
        lineTo(13.5f, 6.5f)
    }
    drawPath(stem, color, style = line)
    head(color, tip = Offset(20f, 6.5f), baseCentre = Offset(13.5f, 6.5f), halfWidth = 4.5f)
}

/** Bear right: up the stem, then 45 degrees to the right with the head. */
private fun DrawScope.keep(color: Color, line: Stroke, mirror: Boolean) = mirrored(mirror) {
    val stem = Path().apply {
        moveTo(7.5f, 21.5f)
        lineTo(7.5f, 13.5f)
        lineTo(13f, 8f)
    }
    drawPath(stem, color, style = line)
    head(color, tip = Offset(18.5f, 2.5f), baseCentre = Offset(13.5f, 7.5f), halfWidth = 4.5f)
}

private inline fun DrawScope.mirrored(mirror: Boolean, block: DrawScope.() -> Unit) {
    if (mirror) scale(-1f, 1f, pivot = Offset(GRID / 2, GRID / 2)) { block() } else block()
}

/** A filled triangle from its base centre to its tip, with slightly rounded corners. */
private fun DrawScope.head(color: Color, tip: Offset, baseCentre: Offset, halfWidth: Float) {
    val axis = tip - baseCentre
    val length = axis.getDistance()
    val across = Offset(-axis.y / length, axis.x / length) * halfWidth
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(baseCentre.x + across.x, baseCentre.y + across.y)
        lineTo(baseCentre.x - across.x, baseCentre.y - across.y)
        close()
    }
    drawPath(path, color, style = Fill)
    drawPath(path, color, style = Stroke(width = 1.2f, join = StrokeJoin.Round))
}
