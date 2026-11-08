package ai.activeseat.app.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

private const val PI_F = PI.toFloat()

// Provisional proportions of an individual rear seat with a centre console, in cm from the hip.
private const val DOOR_HALF_WIDTH = 26f
private const val INNER_HALF_WIDTH = 24f
private const val DOOR_POWER = 0.56f
private const val INNER_POWER = 0.46f
private const val CONSOLE_OFFSET = INNER_HALF_WIDTH + 10.5f
private const val SEAM_AT = 0.55f

val REAR_LEFT_SHAPE: SeatShape by lazy { rearSeat(doorSide = -1f) }
val REAR_RIGHT_SHAPE: SeatShape by lazy { rearSeat(doorSide = 1f) }

/** [doorSide] is the sign of model x toward the door; x runs to the passenger's right. */
private fun rearSeat(doorSide: Float): SeatShape {
    fun isDoor(c: Float) = c * doorSide > 0
    fun power(door: Boolean) = if (door) DOOR_POWER else INNER_POWER

    val cushion: Surface = { u, a ->
        val t = taper(u, 7f)
        val c = cos(a)
        val s = sin(a)
        val door = isDoor(c)
        val w = (if (door) DOOR_HALF_WIDTH else INNER_HALF_WIDTH) * taper(u, 10f) * (1 - 0.04f * u)
        val x = w * sup(c, power(door))
        val safe = if (w > 0) w else 1f
        val top = if (s > 0) t * s.pow(0.5f) * (pad(x / safe, u, 1f) * (if (door) 1.25f else 0.85f) - dish(x, safe, 0.9f)) else 0f
        floatArrayOf(x, -7 + 5 * u + 7 * t * sup(s, 0.7f) + top, -8 + 58 * u)
    }
    val backrest: Surface = { u, a ->
        val t = taper(u, 9f)
        val c = cos(a)
        val s = sin(a)
        val door = isDoor(c)
        val base = if (door) DOOR_HALF_WIDTH + 2 * sin(PI_F * min(1f, u * 1.2f)) - 6 * u * u else INNER_HALF_WIDTH - 2.5f * u * u
        val w = base * taper(u, 14f)
        val x = w * sup(c, power(door))
        val safe = if (w > 0) w else 1f
        val top = if (s > 0) t * s.pow(0.5f) * pad(x / safe, 0.25f, 1f) * (if (door) 1.1f else 0.9f) else 0f
        floatArrayOf(x, 2 + 59 * u, backCentre(u) + 6.8f * t * sup(s, 0.7f) + top)
    }
    val headrest: Surface = { u, a ->
        val t = taper(u, 6f)
        val s = sin(a)
        floatArrayOf(15.8f * taper(u, 8f) * sup(cos(a), 0.62f), 65 + 21 * u, -33 + 6.2f * t * sup(s, 0.7f) + if (s > 0) 1.4f * t else 0f)
    }

    fun angleOf(f: Float): Float = acos((f.sign * abs(f).pow(1 / power(isDoor(f)))).coerceIn(-1f, 1f))
    fun line(steps: Int, u0: Float, f0: Float, u1: Float, f1: Float) =
        SeamCurve(steps) { step -> u0 + (u1 - u0) * step to angleOf(f0 + (f1 - f0) * step) }

    // The same quiet stitching as the passenger seat: two side seams and a few cross seams.
    fun stitching(crossSeams: List<Float>) = buildList {
        for (f in listOf(-SEAM_AT, SEAM_AT)) add(line(30, 0.06f, f, 0.94f, f))
        for (u in crossSeams) add(line(20, u, -SEAM_AT, u, SEAM_AT))
    }
    val backCurves = stitching(listOf(0.2f, 0.4f, 0.6f, 0.8f))
    val cushionCurves = stitching(listOf(1 / 3f, 2 / 3f))

    var seed = if (doorSide > 0) 11L else 13L
    val random = {
        seed = seed * 16807 % 2147483647
        seed / 2147483647f
    }
    val parts = listOf(
        PartSpec(26, 60, 58f, 0.5f, floatArrayOf(), floatArrayOf(), { 21f }, cushionCurves, cushion),
        PartSpec(36, 60, 59f, 0.5f, floatArrayOf(), floatArrayOf(), ::backCentre, backCurves, backrest),
        PartSpec(20, 44, 21f, 0.62f, floatArrayOf(-0.6f, 0.6f), floatArrayOf(), { -33f }, emptyList(), headrest),
    ).map { buildPart(it, random) }

    return SeatShape(
        parts = parts,
        posts = listOf(SeatPost(-6f, 57f, -29.5f, 65f, -32f), SeatPost(6f, 57f, -29.5f, 65f, -32f)),
        fixture = consoleParts(-doorSide * CONSOLE_OFFSET, random),
    )
}

private fun backCentre(u: Float) = -10 - 17 * u - 2 * sin(PI_F * u)

private fun consoleParts(cx: Float, random: () -> Float): List<SeatPart> {
    fun box(hw: Float, hh: Float, yc: Float, z0: Float, length: Float, p: Float, rows: Int) =
        PartSpec(rows, 32, length, p, floatArrayOf(), floatArrayOf(), { z0 + length / 2 }) { u, a ->
            val k = taper(u, 14f)
            floatArrayOf(cx + hw * k * sup(cos(a), p), yc + hh * k * sup(sin(a), p), z0 + length * u)
        }
    val backPanel = PartSpec(18, 30, 41f, 0.3f, floatArrayOf(), floatArrayOf(), { -20f }) { u, a ->
        val k = taper(u, 14f)
        floatArrayOf(cx + 8 * k * sup(cos(a), 0.3f), 15 + 44 * u, -14 - 12 * u + 3 * k * sup(sin(a), 0.3f))
    }
    val headrest = PartSpec(14, 30, 15f, 0.62f, floatArrayOf(), floatArrayOf(), { -32f }) { u, a ->
        floatArrayOf(cx + 8.6f * taper(u, 8f) * sup(cos(a), 0.62f), 63 + 15 * u, -32 + 4.6f * taper(u, 6f) * sup(sin(a), 0.7f))
    }
    // Back to front, since each part hides what lies behind it.
    return listOf(backPanel, headrest, box(9.5f, 13f, -3f, 12f, 34f, 0.3f, 10), box(9.5f, 3.2f, 15.5f, -20f, 58f, 0.34f, 14)).map { buildPart(it, random) }
}
