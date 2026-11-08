package ai.activeseat.app.nav

import kotlin.math.atan2
import kotlin.math.hypot

/** A point of interest on the route. `type` carries NaviHelper's value unchanged. */
data class NavMark(val atM: Double, val type: String, val description: String)

/** A speed camera. `type` and the speeds carry NaviHelper's CameraAlert values unchanged. */
data class NavCamera(val atM: Double, val id: String, val type: String, val speedLimit: Int, val sectionAvrSpeed: Int)

/** A caution point. `type` carries NaviHelper's CautionPointType name unchanged. */
data class NavCaution(val atM: Double, val id: String, val type: String)

/** `routePx` is in map pixels: distance along it is proportional to distance on the road. */
data class NavRoute(
    val title: String,
    val routePx: List<Pair<Int, Int>>,
    val lengthM: Double,
    val turns: List<NavMark>,
    val frontEvents: List<NavMark>,
    val cameras: List<NavCamera> = emptyList(),
    val cautionPoints: List<NavCaution> = emptyList(),
) {
    /** `segment` indexes the polyline leg holding the point; a vertex belongs to the leg leaving it. */
    fun pointAt(fraction: Double): RoutePoint {
        val legs = routePx.zipWithNext()
            .mapIndexed { index, (a, b) -> Leg(index, a, b) }
            .filter { it.lengthPx > 0.0 }
        if (legs.isEmpty()) {
            val (x, y) = routePx.firstOrNull() ?: (0 to 0)
            return RoutePoint(x.toFloat(), y.toFloat(), 0f, 0)
        }
        var remainingPx = fraction.coerceIn(0.0, 1.0) * legs.sumOf { it.lengthPx }
        for (leg in legs.dropLast(1)) {
            if (remainingPx < leg.lengthPx) return leg.pointAfter(remainingPx)
            remainingPx -= leg.lengthPx
        }
        return legs.last().pointAfter(remainingPx)
    }

    fun fractionAt(progressM: Double): Double = if (lengthM > 0) (progressM / lengthM).coerceIn(0.0, 1.0) else 1.0

    fun upcomingTurn(progressM: Double): NavMark? = turns.firstOrNull { it.atM > progressM }

    fun upcomingFrontEvent(progressM: Double): NavMark? = frontEvents.firstOrNull {
        it.atM > progressM && it.atM - progressM <= FRONT_EVENT_RANGE_M
    }

    private class Leg(val index: Int, val from: Pair<Int, Int>, to: Pair<Int, Int>) {
        val dx = (to.first - from.first).toDouble()
        val dy = (to.second - from.second).toDouble()
        val lengthPx = hypot(dx, dy)

        fun pointAfter(px: Double): RoutePoint {
            val f = (px / lengthPx).coerceAtMost(1.0)
            // Screen y grows downward, so "up" is -dy.
            val heading = Math.toDegrees(atan2(dx, -dy)).let { if (it < 0) it + 360 else it }
            return RoutePoint((from.first + dx * f).toFloat(), (from.second + dy * f).toFloat(), heading.toFloat(), index)
        }
    }
}

/** A position on the polyline, heading in degrees clockwise from up. */
data class RoutePoint(val x: Float, val y: Float, val headingDeg: Float, val segment: Int)

/**
 * What the screen shows. `progressM`, `fraction`, the car and `arrived` are physical, on the route
 * actually driven. `shownRoute` is the route the guidance draws, passed up to `shownFraction`; it
 * is the driven route except before a reroute, when the car can leave it. The turn, front event and
 * remaining distance follow the guidance.
 */
data class NavState(
    val progressM: Double,
    val remainingM: Double,
    val fraction: Double,
    val carX: Float,
    val carY: Float,
    val headingDeg: Float,
    val nextTurn: NavMark?,
    val nextTurnDistanceM: Int?,
    val frontEvent: NavMark?,
    val frontEventDistanceM: Int?,
    val arrived: Boolean,
    val shownRoute: NavRoute,
    val shownFraction: Double,
    /** The navigation is searching for a new route and guides nothing. */
    val rerouting: Boolean = false,
)
