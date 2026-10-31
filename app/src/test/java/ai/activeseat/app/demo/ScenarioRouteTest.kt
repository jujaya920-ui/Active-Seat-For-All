package ai.activeseat.app.demo

import ai.activeseat.app.nav.NavMark
import ai.activeseat.app.nav.NavState
import ai.activeseat.app.nav.RouteNavigator
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun asset(id: String) = parseScenario(File("src/main/assets/scenarios/$id.json").readText())

private fun Scenario.playStates(speed: (ScenarioFrame) -> Double = { it.speedKph }): List<Pair<ScenarioFrame, NavState>> {
    val nav = RouteNavigator(toNavRoute())
    return timeline.map { frame ->
        nav.onSpeed(speed(frame), (frame.t * 1000).roundToInt().toLong())
        frame to nav.state()
    }
}

private fun motionRow(t: Int, speed: Int) =
    """{"t":$t,"speedKph":$speed,"steeringDeg":0,"brakePct":0,"acceleratorPct":0,"turnSignal":"Off"}"""

class ScenarioRouteTest {
    @Test
    fun copiesRouteDataAndMarks() {
        val route = parseScenario(scenarioJson(listOf(motionRow(0, 36), motionRow(10, 36)))).toNavRoute()

        assertEquals("Test", route.title)
        assertEquals(listOf(120 to 800, 400 to 780), route.routePx)
        assertEquals(100.0, route.lengthM, 1e-9)
        assertEquals(listOf(NavMark(500.0, "TURN_RIGHT", "ramp")), route.turns)
        assertEquals(listOf(NavMark(1500.0, "CONGESTION", "jam")), route.frontEvents)
    }

    @Test
    fun motionlessTimelineFallsBackToDeclaredLength() {
        val route = parseScenario(scenarioJson(listOf(motionRow(0, 0), motionRow(1, 0)))).toNavRoute()

        assertEquals(2400.0, route.lengthM, 0.0)
    }

    @Test
    fun pangyoLengthStaysWithinOnePercentOfDeclared() {
        val scenario = asset("pangyo-gangnam")

        assertEquals(scenario.routeLengthM, scenario.toNavRoute().lengthM, scenario.routeLengthM * 0.01)
    }

    @Test
    fun pangyoTimelineReachesEachTurnAtItsVertex() {
        val scenario = asset("pangyo-gangnam")
        val states = scenario.playStates()

        scenario.turns.forEachIndexed { index, turn ->
            val state = states.first { (_, state) -> state.progressM >= turn.atM }.second
            val (vertexX, vertexY) = scenario.routePx[index + 1]

            // One 0.2 s step at corner speed covers under 3 m.
            assertEquals(turn.atM, state.progressM, 3.0)
            assertTrue(hypot(state.carX - vertexX, state.carY - vertexY) < 4f)
            assertEquals(scenario.turns.getOrNull(index + 1)?.type, state.nextTurn?.type)
        }
    }

    @Test
    fun pangyoAnnouncesEveryTurnInOrderThenArrivesOnlyNearTheEnd() {
        val scenario = asset("pangyo-gangnam")
        val states = scenario.playStates()

        val announced = states.mapNotNull { (_, state) -> state.nextTurn?.description }.distinct()
        assertEquals(scenario.turns.map { it.description }, announced)

        val firstArrived = states.first { (_, state) -> state.arrived }.first
        assertTrue("arrived at ${firstArrived.t}", firstArrived.t > scenario.durationSec - 15)
        assertEquals(0.0, states.last().second.remainingM, 1e-6)
        assertNull(states.last().second.nextTurn)
    }

    @Test
    fun pangyoShowsTheFrontEventOnlyInsideItsRange() {
        val scenario = asset("pangyo-gangnam")

        val shown = scenario.playStates().filter { (_, state) -> state.frontEvent != null }

        assertTrue(shown.isNotEmpty())
        shown.forEach { (_, state) ->
            val event = scenario.frontEvents.first { it.atM > state.progressM }
            assertEquals(event.type, state.frontEvent?.type)
            assertTrue(state.frontEventDistanceM!! in 0..300)
        }
        assertTrue(abs(shown.first().second.frontEventDistanceM!! - 300) <= 3)
    }

    @Test
    fun consoleRoundedSpeedsStillArrive() {
        val scenario = asset("pangyo-gangnam")

        val states = scenario.playStates { it.speedKph.roundToInt().toDouble() }

        assertTrue(states.last().second.arrived)
    }

    @Test
    fun smokeRouteArrivesOnlyAtTheEndOfItsTimeline() {
        val states = asset("smoke").playStates()

        assertFalse(states[states.size / 2].second.arrived)
        assertTrue(states.last().second.arrived)
    }
}
