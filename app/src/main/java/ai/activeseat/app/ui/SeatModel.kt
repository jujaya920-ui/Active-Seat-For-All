package ai.activeseat.app.ui

import ai.activeseat.app.hardware.HardwareCommand
import ai.activeseat.app.hardware.ModuleCommand
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** Vibration zones, in the order of [SeatMotion.vibration]'s inner arrays. */
const val ZONE_LEFT = 0
const val ZONE_RIGHT = 1
const val ZONE_FRONT = 2
const val ZONE_BACK = 3

const val PART_CUSHION = 0
const val PART_BACKREST = 1
const val PART_HEADREST = 2

/** The seat turns and tilts about this point, in model units. */
const val SEAT_PIVOT_Z = -5f

/** The canvas the two seat views are drawn in, in the units [SeatCamera.project] returns. */
const val SEAT_VIEW_W = 291f
const val SEAT_VIEW_H = 532f

private const val TAU = (2 * PI).toFloat()
private const val MOTION_TAU_SEC = 0.18f

/** Four equal first-order stages in a row reach 95% at this many of their time constants. */
private const val LEAN_STAGE_RATE = 7.754f
private const val LEAN_STAGES = 4
private const val WAVE_SLOWEST_SEC = 2.6f
private const val WAVE_STRENGTH_SPEEDUP_SEC = 0.9f
private const val SETTLED = 0.002f
const val ZONE_ACTIVE = 0.02f

internal typealias Surface = (u: Float, a: Float) -> FloatArray

/** A stitched line on a part: the surface position (u, angle) at 0..1 along it. */
internal class SeamCurve(val steps: Int, val at: (Float) -> Pair<Float, Float>)

internal class PartSpec(
    val rows: Int,
    val cols: Int,
    val spanCm: Float,
    val power: Float,
    val seams: FloatArray,
    val crossSeams: FloatArray,
    val center: (Float) -> Float,
    val curves: List<SeamCurve> = emptyList(),
    val surface: Surface,
)

class SeamLine(val points: FloatArray, val normals: FloatArray)

/** Dots on one part's surface. Each belongs to one left/right and one front/back zone. */
class SeatDots(
    val positions: FloatArray,
    val normals: FloatArray,
    val sideZone: IntArray,
    val lengthZone: IntArray,
    val sideReach: FloatArray,
    val lengthReach: FloatArray,
    val rank: FloatArray,
) {
    val count: Int get() = sideZone.size
}

class SeatPart(
    val rows: Int,
    val cols: Int,
    val vertices: FloatArray,
    val normals: FloatArray,
    val seams: List<SeamLine>,
    val dots: SeatDots,
)

internal fun sup(c: Float, power: Float) = c.sign * abs(c).pow(power)
internal fun taper(u: Float, power: Float) = sqrt(maxOf(0f, 1f - abs(2 * u - 1).pow(power)))
internal fun dish(x: Float, w: Float, depth: Float) = depth * (1 - min(1f, x * x / (w * w)))

/** Soft padding: a raised centre panel and side bolsters with a stitched groove between them. */
internal fun pad(t: Float, u: Float, bands: Float): Float {
    val m = abs(t)
    val core = 0.55f
    if (m < core) {
        val band = abs(sin(PI.toFloat() * u * bands))
        return 2.2f * cos(PI.toFloat() * m / (2 * core)).coerceAtLeast(0f).pow(0.6f) * (0.72f + 0.28f * band.pow(0.5f))
    }
    return 3f * sin(PI.toFloat() * min(1f, (m - core) / (1 - core))).coerceAtLeast(0f).pow(0.6f)
}

private fun backCenter(u: Float) = -10f - 13f * u - 2.5f * sin(PI.toFloat() * u)

private val CUSHION = PartSpec(30, 56, 58f, 0.6f, floatArrayOf(-0.55f, 0.55f), floatArrayOf(1 / 3f, 2 / 3f), { 21f }) { u, a ->
    val t = taper(u, 6f)
    val w = (27 - 2 * u) * taper(u, 10f)
    val sa = sin(a)
    val x = w * sup(cos(a), 0.6f)
    val safeW = if (w > 0) w else 1f
    val top = if (sa > 0) t * sa.pow(0.5f) * (pad(x / safeW, u, 3f) - dish(x, safeW, 1.5f)) else 0f
    floatArrayOf(x, -7 + 5 * u + 7 * t * sup(sa, 0.7f) + top, -8 + 58 * u)
}

private val BACKREST = PartSpec(36, 56, 64f, 0.6f, floatArrayOf(-0.55f, 0.55f), floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f), ::backCenter) { u, a ->
    val t = taper(u, 8f)
    val w = (25 + 3 * sin(PI.toFloat() * min(1f, u * 1.25f)) - 6 * u * u) * taper(u, 12f)
    val sa = sin(a)
    val x = w * sup(cos(a), 0.6f)
    val safeW = if (w > 0) w else 1f
    val top = if (sa > 0) t * sa.pow(0.5f) * (pad(x / safeW, u, 5f) * 1.3f - dish(x, safeW, 2f)) else 0f
    floatArrayOf(x, 2 + 62 * u, backCenter(u) + 8.5f * t * sup(sa, 0.7f) + top)
}

private val HEADREST = PartSpec(20, 40, 26f, 0.65f, floatArrayOf(-0.6f, 0.6f), floatArrayOf(), { -21f }) { u, a ->
    val t = taper(u, 5f)
    val sa = sin(a)
    floatArrayOf(14.5f * taper(u, 6f) * sup(cos(a), 0.65f), 69 + 25 * u, -21 + 7 * t * sup(sa, 0.7f) + if (sa > 0) 1.4f * t else 0f)
}

/** Outward surface normal from finite differences, flipped to point away from the section's centre. */
private fun normalAt(surface: Surface, u: Float, a: Float): FloatArray {
    val e = 0.01f
    val p = surface(u, a)
    val pu = surface(min(0.995f, u + e), a)
    val qu = surface(maxOf(0.005f, u - e), a)
    val pa = surface(u, a + e * 4)
    val qa = surface(u, a - e * 4)
    val du = FloatArray(3) { pu[it] - qu[it] }
    val da = FloatArray(3) { pa[it] - qa[it] }
    val n = floatArrayOf(du[1] * da[2] - du[2] * da[1], du[2] * da[0] - du[0] * da[2], du[0] * da[1] - du[1] * da[0])
    val size = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).let { if (it > 0) it else 1f }
    val c0 = surface(u, 0f)
    val c1 = surface(u, PI.toFloat())
    val out = FloatArray(3) { p[it] - (c0[it] + c1[it]) / 2 }
    val flip = if (n[0] * out[0] + n[1] * out[1] + n[2] * out[2] < 0) -1f else 1f
    return FloatArray(3) { n[it] / size * flip }
}

private fun FloatArray.put(index: Int, values: FloatArray) = values.copyInto(this, index * 3)

private fun seamLine(spec: PartSpec, steps: Int, at: (Float) -> Pair<Float, Float>): SeamLine {
    val points = FloatArray((steps + 1) * 3)
    val normals = FloatArray((steps + 1) * 3)
    for (i in 0..steps) {
        val (u, a) = at(i.toFloat() / steps)
        points.put(i, spec.surface(u, a))
        normals.put(i, normalAt(spec.surface, u, a))
    }
    return SeamLine(points, normals)
}

private fun buildDots(spec: PartSpec, random: () -> Float): SeatDots {
    val pitch = 2.5f
    val rows = round(spec.spanCm / pitch).toInt()
    val points = ArrayList<FloatArray>()
    val normals = ArrayList<FloatArray>()
    val fore = ArrayList<Float>()
    for (r in 0 until rows) {
        val u = 0.03f + 0.94f * r / (rows - 1)
        var walked = (r % 2) * pitch / 2
        var previous = spec.surface(u, 0f)
        for (i in 1..240) {
            val a = i / 240f * TAU
            val p = spec.surface(u, a)
            walked += sqrt((p[0] - previous[0]).pow(2) + (p[1] - previous[1]).pow(2) + (p[2] - previous[2]).pow(2))
            previous = p
            if (walked < pitch) continue
            walked -= pitch
            points += p
            normals += normalAt(spec.surface, u, a)
            fore += p[2] - spec.center(u)
        }
    }
    val reachX = points.maxOf { abs(it[0]) }
    val reachZ = fore.maxOf { abs(it) }
    return SeatDots(
        positions = FloatArray(points.size * 3).also { out -> points.forEachIndexed { i, p -> out.put(i, p) } },
        normals = FloatArray(points.size * 3).also { out -> normals.forEachIndexed { i, n -> out.put(i, n) } },
        sideZone = IntArray(points.size) { if (points[it][0] < 0) ZONE_LEFT else ZONE_RIGHT },
        lengthZone = IntArray(points.size) { if (fore[it] < 0) ZONE_BACK else ZONE_FRONT },
        sideReach = FloatArray(points.size) { abs(points[it][0]) / reachX },
        lengthReach = FloatArray(points.size) { abs(fore[it]) / reachZ },
        rank = FloatArray(points.size) { random() },
    )
}

internal fun buildPart(spec: PartSpec, random: () -> Float): SeatPart {
    val count = spec.rows * spec.cols
    val vertices = FloatArray(count * 3)
    val normals = FloatArray(count * 3)
    for (r in 0 until spec.rows) {
        val u = 0.015f + 0.97f * r / (spec.rows - 1)
        for (c in 0 until spec.cols) {
            val a = c.toFloat() / spec.cols * TAU
            vertices.put(r * spec.cols + c, spec.surface(u, a))
            normals.put(r * spec.cols + c, normalAt(spec.surface, u, a))
        }
    }
    val angle = { t: Float -> acos(t.sign * abs(t).pow(1 / spec.power)) }
    val seams = spec.seams.map { t -> seamLine(spec, 40) { step -> 0.06f + 0.88f * step to angle(t) } } +
        spec.crossSeams.map { u -> seamLine(spec, 24) { step -> u to angle(-0.55f + 1.1f * step) } } +
        spec.curves.map { curve -> seamLine(spec, curve.steps, curve.at) }
    return SeatPart(spec.rows, spec.cols, vertices, normals, seams, buildDots(spec, random))
}

/** The whole seat, indexed by PART_*. Built once, on first use. x right, y up, z forward, in cm from the hip. */
val SEAT_PARTS: List<SeatPart> by lazy {
    var seed = 7L
    val random = {
        seed = seed * 16807 % 2147483647
        seed / 2147483647f
    }
    listOf(CUSHION, BACKREST, HEADREST).map { buildPart(it, random) }
}

/** Rotation of the whole seat for display angles in degrees: left, forward and left are positive. */
fun seatRotation(rollDeg: Float, pitchDeg: Float, yawDeg: Float): FloatArray {
    val r = rollDeg * PI.toFloat() / 180
    val p = pitchDeg * PI.toFloat() / 180
    val y = yawDeg * PI.toFloat() / 180
    val roll = floatArrayOf(cos(r), -sin(r), 0f, sin(r), cos(r), 0f, 0f, 0f, 1f)
    val tilt = floatArrayOf(1f, 0f, 0f, 0f, cos(p), -sin(p), 0f, sin(p), cos(p))
    val turn = floatArrayOf(cos(y), 0f, -sin(y), 0f, 1f, 0f, sin(y), 0f, cos(y))
    return multiply(turn, multiply(tilt, roll))
}

private fun multiply(a: FloatArray, b: FloatArray) = FloatArray(9) { i ->
    val row = i / 3
    val col = i % 3
    a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col]
}

/**
 * Both views look at the same seat. Each sits on the side it shows, so x is mirrored: the front view
 * faces the passenger, whose left is the screen's right.
 */
class SeatCamera private constructor(turnDeg: Float, tipDeg: Float, private val shift: Float) {
    private val cosTurn = cos(turnDeg * PI.toFloat() / 180)
    private val sinTurn = sin(turnDeg * PI.toFloat() / 180)
    private val cosTip = cos(tipDeg * PI.toFloat() / 180)
    private val sinTip = sin(tipDeg * PI.toFloat() / 180)

    /** Writes screen x, screen y into `out`. */
    fun project(x: Float, y: Float, z: Float, out: FloatArray) {
        val wx = -x
        val wy = y - 28
        val wz = z - 8
        val x1 = wx * cosTurn + wz * sinTurn
        val z1 = -wx * sinTurn + wz * cosTurn
        val y2 = wy * cosTip - z1 * sinTip
        val z2 = wy * sinTip + z1 * cosTip
        val k = 430 / (430 - z2) * 2.7f
        out[0] = 145 + shift + x1 * k
        out[1] = 305 - y2 * k
    }

    /** How much a world-space normal faces the camera, -1..1. */
    fun facing(x: Float, y: Float, z: Float): Float {
        val z1 = x * sinTurn + z * cosTurn
        return y * sinTip + z1 * cosTip
    }

    companion object {
        val FRONT = SeatCamera(0f, 20f, 0f)
        val SIDE = SeatCamera(88f, 14f, 16f)

        /** The side view from the seat's other side, for a seat whose door is on the passenger's left. */
        val SIDE_MIRRORED = SeatCamera(-88f, 14f, -16f)
    }
}

/** Height of a vibration swell at [reach] from the zone's centre line, or -1 before it arrives. */
fun swell(strength: Float, phase: Float, reach: Float): Float {
    val raw = phase - reach * 0.62f
    if (strength < ZONE_ACTIVE || raw < 0) return -1f
    val p = raw % 1f
    val s = when {
        p < 0.16f -> sin(p / 0.16f * PI.toFloat() / 2)
        p < 0.8f -> cos((p - 0.16f) / 0.64f * PI.toFloat() / 2)
        else -> 0f
    }
    return s * s
}

/**
 * Eases the panel toward the latest command and runs the vibration swells. The pose follows the
 * command through four first-order stages, so it starts very slowly, runs fast in the middle and
 * settles very slowly, reaching 95% at [HardwareCommand.leanSec]. Vibration and the watch ease as before.
 */
class SeatMotion {
    var rollDeg = 0f
        private set
    var pitchDeg = 0f
        private set
    var yawDeg = 0f
        private set
    var watch = 0f
        private set
    var timeSec = 0f
        private set

    /** By PART_* then ZONE_*. */
    val vibration = Array(3) { FloatArray(4) }
    val phase = Array(3) { FloatArray(4) }

    // Roll, pitch and yaw, each through LEAN_STAGES stages; the last stage is the pose.
    private val stages = Array(3) { FloatArray(LEAN_STAGES) }

    /** Puts the seat, vibration and watch back at rest at once. */
    fun reset() {
        rollDeg = 0f
        pitchDeg = 0f
        yawDeg = 0f
        watch = 0f
        stages.forEach { it.fill(0f) }
        vibration.forEach { it.fill(0f) }
        phase.forEach { it.fill(0f) }
    }

    /** Returns whether anything on screen changed. */
    fun step(dtSec: Float, command: HardwareCommand): Boolean {
        val a = 1 - exp(-dtSec / MOTION_TAU_SEC)
        // Exact per stage, so a long frame cannot overshoot. A zero time snaps.
        val leanSec = command.leanSec.toFloat()
        val aLean = if (leanSec > 0f) 1 - exp(-dtSec * LEAN_STAGE_RATE / leanSec) else 1f
        timeSec += dtSec
        var moving = false
        fun ease(current: Float, target: Float): Float {
            if (abs(target - current) > SETTLED) moving = true
            return current + (target - current) * a
        }
        fun lean(chain: FloatArray, target: Float): Float {
            var input = target
            for (i in chain.indices) {
                if (abs(input - chain[i]) > SETTLED) moving = true
                chain[i] += (input - chain[i]) * aLean
                input = chain[i]
            }
            return input
        }
        val pose = command.headrest
        rollDeg = lean(stages[0], pose.rollDeg.toFloat())
        pitchDeg = lean(stages[1], pose.pitchDeg.toFloat())
        yawDeg = lean(stages[2], pose.yawDeg.toFloat())
        watch = ease(watch, command.watchStrength.toFloat())
        listOf(command.cushion, command.backrest, command.headrest).forEachIndexed { part, module ->
            val targets = zoneTargets(module)
            for (zone in 0..3) {
                val v = ease(vibration[part][zone], targets[zone])
                vibration[part][zone] = v
                if (v < ZONE_ACTIVE) {
                    // Each event restarts from the centre; a stronger one travels a little quicker.
                    phase[part][zone] = 0f
                } else {
                    phase[part][zone] += dtSec / (WAVE_SLOWEST_SEC - WAVE_STRENGTH_SPEEDUP_SEC * v)
                    moving = true
                }
            }
        }
        return moving || watch > ZONE_ACTIVE
    }

    private fun zoneTargets(module: ModuleCommand) = with(module.vibration) {
        floatArrayOf(left.toFloat(), right.toFloat(), front.toFloat(), back.toFloat())
    }
}

/** Length of a screen segment, for tests. */
fun segmentLength(ax: Float, ay: Float, bx: Float, by: Float): Float = hypot(bx - ax, by - ay)
