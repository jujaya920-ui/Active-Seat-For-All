package ai.activeseat.app.ui

import ai.activeseat.app.hardware.CabinPlace
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * One mesh of a seat. [place] is the seat it belongs to, null for a fixed part of the car. [role] is
 * the PART_* module that moves and vibrates it, or [PART_FIXED]. It tilts about its own seat's centre
 * at [pivotX]; a rear headrest turns about the upright axis through ([axisX], [axisZ]).
 */
class ShapePart(
    val part: SeatPart,
    val place: CabinPlace?,
    val role: Int,
    val pivotX: Float = 0f,
    val axisX: Float = 0f,
    val axisZ: Float = HEADREST_AXIS_Z,
)

/** A headrest post, drawn as a line between two points, moving with [partIndex] of [SeatShape.parts]. */
class SeatPost(val partIndex: Int, val x: Float, val y0: Float, val z0: Float, val y1: Float, val z1: Float)

/** What one view shows, and how its parts move ([kind]). */
class SeatShape(val kind: SeatKind, val parts: List<ShapePart>, val posts: List<SeatPost>) {
    /** The headrests, for the turn dials drawn above them. */
    val headrests: List<ShapePart> get() = parts.filter { it.role == PART_HEADREST }
}

val PASSENGER_SHAPE: SeatShape by lazy {
    SeatShape(
        SeatKind.PASSENGER,
        SEAT_PARTS.mapIndexed { role, part -> ShapePart(part, CabinPlace.PASSENGER, role) },
        listOf(SeatPost(PART_HEADREST, -6f, 62f, -23f, 70f, -21f), SeatPost(PART_HEADREST, 6f, 62f, -23f, 70f, -21f)),
    )
}

private const val PI_F = PI.toFloat()

// Provisional proportions of an individual rear seat beside a centre console, in cm from the hip.
private const val DOOR_HALF_WIDTH = 32f
private const val INNER_HALF_WIDTH = 24f
private const val DOOR_POWER = 0.56f
private const val INNER_POWER = 0.46f
private const val SEAM_AT = 0.55f
private const val REAR_HEADREST_Z = -33f

/** The cushion is this share of the backrest's width: as wide as the backrest, reaching from the door to the console. */
private const val CUSHION_WIDTH = 1.04f

/** Each rear seat's centre is this far from the console in the middle of the row. */
const val REAR_SEAT_X = INNER_HALF_WIDTH + 10.5f

/**
 * The rear row from the front: two individual seats with the centre console between them. The
 * console leaves neither seat room to turn, so only the headrests turn. Each seat follows its own command.
 */
val REAR_ROW_SHAPE: SeatShape by lazy {
    var seed = 13L
    val random = {
        seed = seed * 16807 % 2147483647
        seed / 2147483647f
    }
    val left = rearSeat(-1f, CabinPlace.REAR_LEFT, random)
    val right = rearSeat(1f, CabinPlace.REAR_RIGHT, random)
    val console = consoleParts(random).map { ShapePart(it, null, PART_FIXED) }
    // The console first: it sits behind the seats' inner sides.
    val parts = console + left.parts + right.parts
    val posts = listOf(left, right).flatMapIndexed { seat, built ->
        val headrestIndex = console.size + seat * 3 + PART_HEADREST
        listOf(-6f, 6f).map { dx -> SeatPost(headrestIndex, built.centerX + dx, 57f, -29.5f, 65f, -32f) }
    }
    SeatShape(SeatKind.REAR, parts, posts)
}

private class RearSeat(val centerX: Float, val parts: List<ShapePart>)

/** [doorSide] is the sign of model x toward the door; x runs to the passenger's right. */
private fun rearSeat(doorSide: Float, place: CabinPlace, random: () -> Float): RearSeat {
    val cx = doorSide * REAR_SEAT_X
    fun isDoor(c: Float) = c * doorSide > 0
    fun power(door: Boolean) = if (door) DOOR_POWER else INNER_POWER

    val cushion: Surface = { u, a ->
        val t = taper(u, 7f)
        val c = cos(a)
        val s = sin(a)
        val door = isDoor(c)
        val w = (if (door) DOOR_HALF_WIDTH else INNER_HALF_WIDTH) * CUSHION_WIDTH * taper(u, 10f) * (1 - 0.04f * u)
        val x = w * sup(c, power(door))
        val safe = if (w > 0) w else 1f
        val top = if (s > 0) t * s.pow(0.5f) * (pad(x / safe, u, 1f) * (if (door) 1.25f else 0.85f) - dish(x, safe, 0.9f)) else 0f
        floatArrayOf(cx + x, -7 + 5 * u + 7 * t * sup(s, 0.7f) + top, -8 + 58 * u)
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
        floatArrayOf(cx + x, 2 + 59 * u, rearBackCentre(u) + 6.8f * t * sup(s, 0.7f) + top)
    }
    val headrest: Surface = { u, a ->
        val t = taper(u, 6f)
        val s = sin(a)
        floatArrayOf(
            cx + 15.8f * taper(u, 8f) * sup(cos(a), 0.62f),
            65 + 21 * u,
            REAR_HEADREST_Z + 6.2f * t * sup(s, 0.7f) + if (s > 0) 1.4f * t else 0f,
        )
    }

    fun angleOf(f: Float): Float = acos((f.sign * abs(f).pow(1 / power(isDoor(f)))).coerceIn(-1f, 1f))
    fun line(steps: Int, u0: Float, f0: Float, u1: Float, f1: Float) =
        SeamCurve(steps) { step -> u0 + (u1 - u0) * step to angleOf(f0 + (f1 - f0) * step) }

    // The same quiet stitching as the passenger seat: two side seams and a few cross seams.
    fun stitching(crossSeams: List<Float>) = buildList {
        for (f in listOf(-SEAM_AT, SEAM_AT)) add(line(30, 0.06f, f, 0.94f, f))
        for (u in crossSeams) add(line(20, u, -SEAM_AT, u, SEAM_AT))
    }
    val local = { x: Float -> x - cx }
    val specs = listOf(
        PartSpec(26, 60, 58f, 0.5f, floatArrayOf(), floatArrayOf(), { 21f }, local, stitching(listOf(1 / 3f, 2 / 3f)), cushion),
        PartSpec(36, 60, 59f, 0.5f, floatArrayOf(), floatArrayOf(), ::rearBackCentre, local, stitching(listOf(0.2f, 0.4f, 0.6f, 0.8f)), backrest),
        PartSpec(20, 44, 21f, 0.62f, floatArrayOf(-0.6f, 0.6f), floatArrayOf(), { REAR_HEADREST_Z }, local, emptyList(), headrest),
    )
    val parts = specs.mapIndexed { role, spec ->
        ShapePart(buildPart(spec, random), place, role, pivotX = cx, axisX = cx, axisZ = REAR_HEADREST_Z)
    }
    return RearSeat(cx, parts)
}

private fun rearBackCentre(u: Float) = -10 - 17 * u - 2 * sin(PI_F * u)

/** The centre console between the rear seats, at x = 0: back panel, its headrest, the bin and the armrest. */
private fun consoleParts(random: () -> Float): List<SeatPart> {
    fun box(hw: Float, hh: Float, yc: Float, z0: Float, length: Float, p: Float, rows: Int) =
        PartSpec(rows, 32, length, p, floatArrayOf(), floatArrayOf(), { z0 + length / 2 }) { u, a ->
            val k = taper(u, 14f)
            floatArrayOf(hw * k * sup(cos(a), p), yc + hh * k * sup(sin(a), p), z0 + length * u)
        }
    val backPanel = PartSpec(18, 30, 41f, 0.3f, floatArrayOf(), floatArrayOf(), { -20f }) { u, a ->
        val k = taper(u, 14f)
        floatArrayOf(8 * k * sup(cos(a), 0.3f), 15 + 44 * u, -14 - 12 * u + 3 * k * sup(sin(a), 0.3f))
    }
    val headrest = PartSpec(14, 30, 15f, 0.62f, floatArrayOf(), floatArrayOf(), { -32f }) { u, a ->
        floatArrayOf(8.6f * taper(u, 8f) * sup(cos(a), 0.62f), 63 + 15 * u, -32 + 4.6f * taper(u, 6f) * sup(sin(a), 0.7f))
    }
    // Back to front, since each part hides what lies behind it.
    return listOf(backPanel, headrest, box(9.5f, 13f, -3f, 12f, 34f, 0.3f, 10), box(9.5f, 3.2f, 15.5f, -20f, 58f, 0.34f, 14))
        .map { buildPart(it, random) }
}
