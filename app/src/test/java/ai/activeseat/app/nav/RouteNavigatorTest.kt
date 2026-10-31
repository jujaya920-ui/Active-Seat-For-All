package ai.activeseat.app.nav

import ai.activeseat.app.prediction.NavItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HUNDRED_MPS_IN_KPH = 360.0

private fun route(
    lengthM: Double = 10_000.0,
    turns: List<NavMark> = emptyList(),
    frontEvents: List<NavMark> = emptyList(),
    routePx: List<Pair<Int, Int>> = listOf(0 to 0, 1000 to 0),
) = NavRoute("test", routePx, lengthM, turns, frontEvents)

/** Advances exactly `seconds * 100` metres in whole-second steps. */
private fun RouteNavigator.drive(seconds: Int, startMs: Long = 0L): Long {
    onSpeed(HUNDRED_MPS_IN_KPH, startMs)
    var at = startMs
    repeat(seconds) {
        at += 1000
        onSpeed(HUNDRED_MPS_IN_KPH, at)
    }
    return at
}

class RouteNavigatorTest {
    @Test
    fun integratesConstantSpeed() {
        val nav = RouteNavigator(route())

        for (i in 0..50) nav.onSpeed(36.0, i * 200L)

        assertEquals(100.0, nav.state().progressM, 1e-9)
    }

    @Test
    fun integratesRampByTrapezoid() {
        val nav = RouteNavigator(route())

        for (i in 0..50) nav.onSpeed(36.0 * i / 50, i * 200L)

        assertEquals(50.0, nav.state().progressM, 1e-9)
    }

    @Test
    fun firstSampleOnlySetsBaseline() {
        val nav = RouteNavigator(route())

        nav.onSpeed(100.0, 5_000L)

        assertEquals(0.0, nav.state().progressM, 0.0)
    }

    @Test
    fun ignoresNonIncreasingTime() {
        val nav = RouteNavigator(route())
        nav.onSpeed(36.0, 1_000L)
        nav.onSpeed(36.0, 1_200L)

        nav.onSpeed(360.0, 1_200L)
        nav.onSpeed(360.0, 1_100L)
        nav.onSpeed(36.0, 1_400L)

        assertEquals(4.0, nav.state().progressM, 1e-9)
    }

    @Test
    fun longGapCountsOneSecondAtLatestSpeed() {
        val nav = RouteNavigator(route())
        nav.onSpeed(0.0, 0L)

        nav.onSpeed(36.0, 5_000L)

        assertEquals(10.0, nav.state().progressM, 1e-9)
    }

    @Test
    fun oneSecondGapIsStillTrapezoidal() {
        val nav = RouteNavigator(route())
        nav.onSpeed(0.0, 0L)

        nav.onSpeed(36.0, 1_000L)

        assertEquals(5.0, nav.state().progressM, 1e-9)
    }

    @Test
    fun clampsAtEndOfRoute() {
        val nav = RouteNavigator(route(lengthM = 250.0))

        nav.drive(seconds = 5)

        val state = nav.state()
        assertEquals(250.0, state.progressM, 0.0)
        assertEquals(0.0, state.remainingM, 0.0)
        assertEquals(1.0, state.fraction, 0.0)
        assertTrue(state.arrived)
    }

    @Test
    fun arrivesWithinToleranceOfTheEnd() {
        val nav = RouteNavigator(route(lengthM = 104.0))
        assertFalse(nav.state().arrived)

        nav.drive(seconds = 1)

        assertTrue(nav.state().arrived)
        assertEquals(4.0, nav.state().remainingM, 1e-9)
    }

    @Test
    fun resetReturnsToStartAndForgetsBaseline() {
        val nav = RouteNavigator(route())
        val end = nav.drive(seconds = 3)

        nav.reset()
        assertEquals(0.0, nav.state().progressM, 0.0)

        nav.onSpeed(36.0, end + 60_000L)
        assertEquals(0.0, nav.state().progressM, 0.0)
    }

    @Test
    fun nextTurnAdvancesPastEachTurnAndKeepsType() {
        val turns = listOf(NavMark(100.0, "TURN_LEFT", "a"), NavMark(300.0, "SOME_NAVI_VALUE", "b"))
        val nav = RouteNavigator(route(turns = turns))
        assertEquals(NavItem("TURN_LEFT", 100), nav.nextTurn())

        val at = nav.drive(seconds = 1)
        assertEquals(NavItem("SOME_NAVI_VALUE", 200), nav.nextTurn())
        assertEquals(turns[1], nav.state().nextTurn)
        assertEquals(200, nav.state().nextTurnDistanceM)

        nav.drive(seconds = 2, startMs = at)
        assertNull(nav.nextTurn())
        assertNull(nav.state().nextTurnDistanceM)
    }

    @Test
    fun distanceRoundsToNearestMetre() {
        val nav = RouteNavigator(route(turns = listOf(NavMark(99.6, "TURN_LEFT", "a"))))

        assertEquals(100, nav.nextTurn()?.distanceM)
    }

    @Test
    fun frontEventAppearsExactlyAtRange() {
        val events = listOf(NavMark(1000.0, "CONGESTION", "jam"))
        val nav = RouteNavigator(route(frontEvents = events))

        val at = nav.drive(seconds = 6)
        assertNull(nav.frontEvent())
        assertNull(nav.state().frontEvent)

        val later = nav.drive(seconds = 1, startMs = at)
        assertEquals(NavItem("CONGESTION", 300), nav.frontEvent())
        assertEquals(events[0], nav.state().frontEvent)
        assertEquals(300, nav.state().frontEventDistanceM)

        nav.drive(seconds = 3, startMs = later)
        assertNull(nav.frontEvent())
    }

    @Test
    fun frontEventJustBeyondRangeStaysHidden() {
        val nav = RouteNavigator(route(frontEvents = listOf(NavMark(1000.5, "CONGESTION", "jam"))))

        nav.drive(seconds = 7)

        assertNull(nav.frontEvent())
    }

    @Test
    fun carPositionFollowsProgress() {
        val nav = RouteNavigator(route(lengthM = 700.0, routePx = listOf(0 to 0, 300 to 0, 300 to 400)))

        val at = nav.drive(seconds = 1)
        assertEquals(100f, nav.state().carX, 1e-3f)
        assertEquals(0f, nav.state().carY, 1e-3f)
        assertEquals(90f, nav.state().headingDeg, 1e-3f)

        nav.drive(seconds = 4, startMs = at)
        assertEquals(300f, nav.state().carX, 1e-3f)
        assertEquals(200f, nav.state().carY, 1e-3f)
        assertEquals(180f, nav.state().headingDeg, 1e-3f)
    }
}

class NavRouteTest {
    private val uShape = route(routePx = listOf(0 to 0, 300 to 0, 300 to 400, 0 to 400))

    @Test
    fun reachesEachVertexAtItsLengthFraction() {
        listOf(0.0 to (0 to 0), 0.3 to (300 to 0), 0.7 to (300 to 400), 1.0 to (0 to 400))
            .forEach { (fraction, vertex) ->
                val point = uShape.pointAt(fraction)
                assertEquals(vertex.first.toFloat(), point.x, 1e-3f)
                assertEquals(vertex.second.toFloat(), point.y, 1e-3f)
            }
    }

    @Test
    fun vertexBelongsToTheLegLeavingIt() {
        val point = uShape.pointAt(300.0 / 1000.0)

        assertEquals(1, point.segment)
        assertEquals(180f, point.headingDeg, 1e-3f)
    }

    @Test
    fun headingIsClockwiseFromUp() {
        fun heading(dx: Int, dy: Int) =
            route(routePx = listOf(100 to 100, 100 + dx to 100 + dy)).pointAt(0.5).headingDeg

        assertEquals(0f, heading(0, -10), 1e-3f)
        assertEquals(90f, heading(10, 0), 1e-3f)
        assertEquals(180f, heading(0, 10), 1e-3f)
        assertEquals(270f, heading(-10, 0), 1e-3f)
        assertEquals(45f, heading(10, -10), 1e-3f)
    }

    @Test
    fun endKeepsTheLastLegHeading() {
        val end = uShape.pointAt(1.0)

        assertEquals(2, end.segment)
        assertEquals(270f, end.headingDeg, 1e-3f)
    }

    @Test
    fun skipsRepeatedVertices() {
        val point = route(routePx = listOf(0 to 0, 100 to 0, 100 to 0, 100 to 100)).pointAt(0.5)

        assertEquals(2, point.segment)
        assertEquals(180f, point.headingDeg, 1e-3f)
    }

    @Test
    fun toleratesDegenerateRoutes() {
        assertEquals(RoutePoint(0f, 0f, 0f, 0), route(routePx = emptyList()).pointAt(0.5))
        assertEquals(RoutePoint(7f, 9f, 0f, 0), route(routePx = listOf(7 to 9)).pointAt(0.5))
    }
}
