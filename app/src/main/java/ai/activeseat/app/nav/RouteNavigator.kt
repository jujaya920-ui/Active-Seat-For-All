package ai.activeseat.app.nav

import ai.activeseat.app.prediction.NavContext
import ai.activeseat.app.prediction.NavItem
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val FRONT_EVENT_RANGE_M = 300.0

private const val MAX_TRAPEZOID_GAP_MS = 1000L
private const val KPH_TO_M_PER_MS = 1.0 / 3600.0

// Sampled speed can fall a little short of the route's full length; arrival must still fire.
private const val ARRIVAL_TOLERANCE_M = 5.0

/** Dead-reckons the car along a route from speed alone; steering does not affect position. */
class RouteNavigator(private val route: NavRoute) : NavContext {
    private var progressM = 0.0
    private var lastAtMs: Long? = null
    private var lastKph = 0.0

    @Synchronized
    fun onSpeed(speedKph: Double, atMs: Long) {
        val kph = max(speedKph, 0.0)
        val previousMs = lastAtMs
        if (previousMs != null) {
            if (atMs <= previousMs) return
            val gapMs = atMs - previousMs
            val distanceM = if (gapMs <= MAX_TRAPEZOID_GAP_MS) {
                (lastKph + kph) / 2 * gapMs * KPH_TO_M_PER_MS
            } else {
                // The sampler ticks every 200 ms, so a long gap is a stall and the speed during
                // it is unknown. Counting one second keeps the car moving without jumping it
                // past turns the real car may not have reached.
                kph * MAX_TRAPEZOID_GAP_MS * KPH_TO_M_PER_MS
            }
            progressM = min(progressM + distanceM, route.lengthM)
        }
        lastAtMs = atMs
        lastKph = kph
    }

    @Synchronized
    fun reset(startM: Double = 0.0) {
        progressM = startM.coerceIn(0.0, route.lengthM)
        lastAtMs = null
        lastKph = 0.0
    }

    @Synchronized
    fun state(): NavState {
        val fraction = route.fractionAt(progressM)
        val point = route.pointAt(fraction)
        val turn = upcomingTurn()
        val event = upcomingFrontEvent()
        val remainingM = route.lengthM - progressM
        return NavState(
            progressM = progressM,
            remainingM = remainingM,
            fraction = fraction,
            carX = point.x,
            carY = point.y,
            headingDeg = point.headingDeg,
            nextTurn = turn,
            nextTurnDistanceM = turn?.distanceM(),
            frontEvent = event,
            frontEventDistanceM = event?.distanceM(),
            arrived = remainingM <= ARRIVAL_TOLERANCE_M,
            shownRoute = route,
            shownFraction = fraction,
        )
    }

    @Synchronized
    override fun nextTurn(): NavItem? = upcomingTurn()?.toNavItem()

    @Synchronized
    override fun frontEvent(): NavItem? = upcomingFrontEvent()?.toNavItem()

    private fun upcomingTurn() = route.upcomingTurn(progressM)

    private fun upcomingFrontEvent() = route.upcomingFrontEvent(progressM)

    private fun NavMark.distanceM() = (atM - progressM).roundToInt()

    private fun NavMark.toNavItem() = NavItem(type, distanceM())
}
