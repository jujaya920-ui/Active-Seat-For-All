package ai.activeseat.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

/** The holographic panels' color tokens. New UI on these panels uses only these. */
internal val PanelColor = Color(0xFF05080F)
internal val TextBright = Color(0xFFCFE6F7)
internal val TextDim = Color(0xFFA9CDE6)
internal val Amber = Color(0xFFFFC247)
internal val BracketColor = Color(0x803AA0FF)
internal val BarTrack = Color(0xFF12283A)
internal val Grid = Color(0x123AA0FF)
internal val Ink = Color(0xFF8FD0FF)
internal val Glow = Color(0x293AA0FF)
internal val GaugeDim = Color(0x477FE7FF)
internal val GaugeRest = Color(0x577FE7FF)
internal val GaugeLit = Color(0xFFD6F7FF)
internal val GaugeArc = Color(0xFFBFF3FF)
internal val GaugePointer = Color(0xFFEFFFFF)
internal val GaugeIdle = Color(0x997FE7FF)
internal val WatchRing = Color(0xFFFFB454)
internal val WatchInk = Color(0xFF6FC0FF)

internal val BracketLength = 20.dp
internal val BracketStroke = 1.dp

/** Corner brackets around the whole draw area. */
internal fun DrawScope.drawBrackets() {
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
