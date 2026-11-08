package ai.activeseat.app.nav

import ai.activeseat.app.prediction.NavContext
import ai.activeseat.app.prediction.NavItem
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The route the navigation guided at first, before the driver left it. Both routes are the same
 * up to `leaveAtM`, and distances along each coincide there.
 */
data class PlannedRoute(val route: NavRoute, val leaveAtM: Double)

enum class GuidancePhase {
    /** Following the planned route; after leaving it the navigation holds the car at `leaveAtM`. */
    GUIDING,

    /** Searching for a new route: the navigation reports nothing. */
    REROUTING,

    /** Guiding the new route, the driven one from where the search ended. */
    REROUTED,
}

/** The route the navigation reports from and the car's position on it. */
data class ActiveGuide(val route: NavRoute, val progressM: Double)

/**
 * One guidance session on a scripted drive. The car moves along the driven route by dead
 * reckoning; the navigation guides the planned route until the car leaves it, searches, then
 * guides the driven route from there. Without a planned route it guides the driven route
 * throughout, as before rerouting existed.
 */
class Guidance(private val driven: NavRoute, planned: PlannedRoute? = null) : NavContext {
    private val planned = planned ?: PlannedRoute(driven, Double.POSITIVE_INFINITY)
    private val navigator = RouteNavigator(driven)

    @Volatile
    var phase = GuidancePhase.GUIDING
        private set

    private var leftAtMs: Long? = null
    private var reroutingSinceMs = 0L
    private var rerouteEndedUnsent = false

    @Synchronized
    fun onSpeed(speedKph: Double, atMs: Long) {
        navigator.onSpeed(speedKph, atMs)
        val progressM = navigator.state().progressM
        if (phase == GuidancePhase.GUIDING && progressM > planned.leaveAtM) {
            val since = leftAtMs ?: atMs.also { leftAtMs = it }
            // Judging the departure: the planned guidance stays on screen until it is certain.
            if (atMs - since >= OFF_ROUTE_DETECT_MS) {
                phase = GuidancePhase.REROUTING
                reroutingSinceMs = atMs
            }
        }
        if (phase == GuidancePhase.REROUTING && atMs - reroutingSinceMs >= REROUTE_SEARCH_MS) {
            phase = GuidancePhase.REROUTED
            rerouteEndedUnsent = true
        }
    }

    /** A start already past the leave point begins on the new route, with no search. */
    @Synchronized
    fun reset(startM: Double = 0.0) {
        navigator.reset(startM)
        phase = if (startM > planned.leaveAtM) GuidancePhase.REROUTED else GuidancePhase.GUIDING
        leftAtMs = null
        rerouteEndedUnsent = false
    }

    /** True once after the search ends: the navigation then announces the new route. */
    @Synchronized
    fun takeRerouteEnded(): Boolean = rerouteEndedUnsent.also { rerouteEndedUnsent = false }

    /** Null while searching. */
    @Synchronized
    fun active(): ActiveGuide? {
        val progressM = navigator.state().progressM
        return when (phase) {
            GuidancePhase.GUIDING -> ActiveGuide(planned.route, min(progressM, planned.leaveAtM))
            GuidancePhase.REROUTING -> null
            GuidancePhase.REROUTED -> ActiveGuide(driven, progressM)
        }
    }

    @Synchronized
    fun state(): NavState {
        val physical = navigator.state()
        val guide = active()
        val turn = guide?.route?.upcomingTurn(guide.progressM)
        val event = guide?.route?.upcomingFrontEvent(guide.progressM)
        val guideM = guide?.progressM ?: 0.0
        // While searching the screen keeps the planned route and its last remaining distance.
        val shown = guide ?: ActiveGuide(planned.route, min(physical.progressM, planned.leaveAtM))
        return physical.copy(
            remainingM = if (shown.route === driven) physical.remainingM else shown.route.lengthM - shown.progressM,
            nextTurn = turn,
            nextTurnDistanceM = turn?.let { (it.atM - guideM).roundToInt() },
            frontEvent = event,
            frontEventDistanceM = event?.let { (it.atM - guideM).roundToInt() },
            shownRoute = shown.route,
            shownFraction = shown.route.fractionAt(shown.progressM),
            rerouting = guide == null,
        )
    }

    @Synchronized
    override fun nextTurn(): NavItem? = active()?.let { g ->
        g.route.upcomingTurn(g.progressM)?.let { NavItem(it.type, (it.atM - g.progressM).roundToInt()) }
    }

    @Synchronized
    override fun frontEvent(): NavItem? = active()?.let { g ->
        g.route.upcomingFrontEvent(g.progressM)?.let { NavItem(it.type, (it.atM - g.progressM).roundToInt()) }
    }

    companion object {
        /**
         * How long after leaving the planned route the navigation takes to decide the car is off it,
         * still guiding the old route meanwhile. From the planner's experience of real navigation,
         * not measured on Pleos.
         */
        const val OFF_ROUTE_DETECT_MS = 2000L

        /**
         * How long the search for a new route takes. From the planner's experience of real
         * navigation, not measured on Pleos, nor is what the real NaviHelper reports meanwhile (the
         * SDK has only the end event).
         */
        const val REROUTE_SEARCH_MS = 2000L
    }
}
