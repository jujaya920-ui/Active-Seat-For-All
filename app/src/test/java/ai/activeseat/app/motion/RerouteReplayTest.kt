package ai.activeseat.app.motion

import ai.activeseat.app.demo.Scenario
import ai.activeseat.app.demo.parseScenario
import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.nav.GuidancePhase
import ai.pleos.playground.navi.constants.TurnType
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The driver ignores the navigation's right turn, goes straight and turns right later on a road
 * the navigation only learns after rerouting. With the model unchanged, the seat must not cue the
 * missed turn, must predict nothing while the navigation searches, and must predict the new turn.
 */
class RerouteReplayTest {
    private lateinit var scenario: Scenario
    private lateinit var ticks: List<Tick>

    @Before
    fun load() {
        val file = File(SCENARIO_DIR, "seohyeon-yanghyeon.json")
        assumeTrue(file.exists())
        scenario = parseScenario(file.readText())
        assumeTrue(scenario.plannedRoute != null)
        ticks = replay(scenario, MotionConfig())
    }

    private fun rerouteEndMs() = ticks.first { it.phase == GuidancePhase.REROUTED }.ms

    @Test
    fun theReplayReroutes() {
        val phases = ticks.map { it.phase }.distinct()
        assertEquals(listOf(GuidancePhase.GUIDING, GuidancePhase.REROUTING, GuidancePhase.REROUTED), phases)
        val leaveAtM = scenario.plannedRoute!!.leaveAtM
        val leftMs = ticks.first { it.progressM >= leaveAtM }.ms
        val searchMs = ticks.first { it.phase == GuidancePhase.REROUTING }.ms
        val predictedRight = ticks.first { it.ms >= rerouteEndMs() && it.out.lateralSource == CueSource.PREDICTED && it.out.hint.lateral == LateralCue.RIGHT }
        println("REROUTE leave=${leftMs}ms search=${searchMs}ms rerouted=${rerouteEndMs()}ms predictedRight=${predictedRight.ms}ms at ${"%.1f".format(predictedRight.progressM)}m")
    }

    @Test
    fun noPredictedCueTowardTheMissedTurnUntilTheRerouteEnds() {
        val planned = scenario.plannedRoute!!
        val missed = planned.turns.minBy { abs(it.atM - planned.leaveAtM) }
        val side = when (TurnType.valueOf(missed.type).side()) {
            1 -> LateralCue.LEFT
            -1 -> LateralCue.RIGHT
            else -> error("the missed maneuver ${missed.type} is not a turn")
        }

        val wrong = ticks.filter {
            it.ms < rerouteEndMs() && it.out.lateralSource == CueSource.PREDICTED && it.out.hint.lateral == side
        }

        assertTrue("predicted $side at ${wrong.map { it.ms }}", wrong.isEmpty())
    }

    /**
     * The road itself bends about 10 degrees at the ignored junction, so the measured model may push
     * there. Whatever moves the seat around that point must come from the motion, never the guidance.
     */
    @Test
    fun onlyMeasuredMotionMovesTheSeatAtTheIgnoredJunction() {
        val leaveAtM = scenario.plannedRoute!!.leaveAtM
        val near = ticks.filter { abs(it.progressM - leaveAtM) <= 50.0 }

        val guided = near.filter { it.out.hint.lateral != LateralCue.NONE && it.out.lateralSource != CueSource.MEASURED }

        assertTrue(near.isNotEmpty())
        assertTrue("non-measured lateral cue at ${guided.map { it.ms }}", guided.isEmpty())
    }

    @Test
    fun nothingIsPredictedWhileRerouting() {
        val predicted = ticks.filter {
            it.phase == GuidancePhase.REROUTING &&
                (it.out.lateralSource == CueSource.PREDICTED || it.out.longitudinalSource == CueSource.PREDICTED)
        }

        assertTrue("predicted at ${predicted.map { it.ms }}", predicted.isEmpty())
    }

    @Test
    fun theMeasuredModelIgnoresNavigation() {
        val blind = replay(scenario, MotionConfig(), navigation = false)

        assertEquals(blind.size, ticks.size)
        val differ = ticks.zip(blind).filter { (a, b) -> a.out.measuredPart() != b.out.measuredPart() }
        assertTrue("measured output differs at ${differ.map { it.first.ms }}", differ.isEmpty())
    }

    @Test
    fun theNewRoutesRightTurnIsPredictedBeforeItShows() {
        val endMs = rerouteEndMs()
        val cue = ticks.firstOrNull {
            it.ms >= endMs && it.out.lateralSource == CueSource.PREDICTED && it.out.hint.lateral == LateralCue.RIGHT
        }
        assertNotNull("no predicted right cue after the reroute", cue)
        // Lateral is positive to the left: the first right push felt after the reroute.
        val turn = truthSpans(ticks) { it.truthLateral }.first { it.dir == -1 && it.fromMs >= endMs }

        assertTrue("predicted at ${cue!!.ms}, turn felt at ${turn.fromMs}", cue.ms < turn.fromMs)
    }

    /** The cue on each axis where the measured model holds it. */
    private fun MotionOutput.measuredPart() = listOf(
        if (lateralSource == CueSource.MEASURED) hint.lateral to hint.lateralStrength else null,
        if (longitudinalSource == CueSource.MEASURED) hint.longitudinal to hint.longitudinalStrength else null,
    )
}
