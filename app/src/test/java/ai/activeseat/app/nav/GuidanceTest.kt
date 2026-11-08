package ai.activeseat.app.nav

import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEN_MPS_IN_KPH = 36.0

// Driven east along y = 0; the planned route turns left (north) at 200 m, where the driver goes on.
private val driven = NavRoute(
    title = "driven",
    routePx = listOf(0 to 0, 1000 to 0),
    lengthM = 1000.0,
    turns = listOf(NavMark(600.0, "TURN_RIGHT", "new right"), NavMark(1000.0, "TURN_ARRIVE", "end")),
    frontEvents = listOf(NavMark(650.0, "CONGESTION", "jam")),
    cameras = listOf(NavCamera(150.0, "cam-shared", "SPEED_CAMERA_FIX", 50, 0), NavCamera(500.0, "cam-after", "SPEED_CAMERA_FIX", 50, 0)),
    cautionPoints = listOf(NavCaution(700.0, "cp-after", "SPEED_BUMP")),
)

private val planned = PlannedRoute(
    route = NavRoute(
        title = "planned",
        routePx = listOf(0 to 0, 200 to 0, 200 to -800),
        lengthM = 1000.0,
        turns = listOf(NavMark(200.0, "TURN_LEFT", "missed left"), NavMark(1000.0, "TURN_ARRIVE", "end")),
        frontEvents = emptyList(),
        cameras = listOf(NavCamera(150.0, "cam-shared", "SPEED_CAMERA_FIX", 50, 0)),
    ),
    leaveAtM = 200.0,
)

private class Recorder : NaviHelperEventListener {
    var rerouteEnded = 0
    var tbt: List<TBTInfo>? = null
    var cameras: List<CameraAlert>? = null
    var cautionPoints: List<CautionPoint>? = null

    override fun onReRouteEnded() {
        rerouteEnded++
    }

    override fun onTBTInfo(p0: List<TBTInfo>) {
        tbt = p0
    }

    override fun onCameraAlert(p0: List<CameraAlert>) {
        cameras = p0
    }

    override fun onCautionPointInfo(p0: List<CautionPoint>) {
        cautionPoints = p0
    }
}

/** Drives at 10 m/s in whole seconds until `progressM`; returns the time reached. */
private fun Guidance.driveTo(progressM: Double, fromMs: Long = 0L): Long {
    var ms = fromMs
    onSpeed(TEN_MPS_IN_KPH, ms)
    while (state().progressM < progressM - 1e-9) {
        ms += 1000
        onSpeed(TEN_MPS_IN_KPH, ms)
    }
    return ms
}

class GuidanceTest {
    @Test
    fun withoutAPlannedRouteItGuidesTheDrivenRouteThroughout() {
        val guidance = Guidance(driven)
        val navi = FakeNaviHelper(guidance)

        guidance.driveTo(550.0)
        val state = guidance.state()

        assertEquals(GuidancePhase.GUIDING, guidance.phase)
        assertSame(driven, state.shownRoute)
        assertEquals(state.fraction, state.shownFraction, 0.0)
        assertEquals(450.0, state.remainingM, 1e-9)
        assertEquals(50, state.nextTurnDistanceM)
        assertFalse(state.rerouting)
        assertEquals(listOf(50.0, 450.0), navi.tbtInfo().map { it.distance })
    }

    @Test
    fun guidesThePlannedRouteAndSharedPointsBeforeLeavingIt() {
        val guidance = Guidance(driven, planned)
        val navi = FakeNaviHelper(guidance)

        guidance.driveTo(100.0)
        val state = guidance.state()

        assertEquals(listOf(TurnType.TURN_LEFT, TurnType.TURN_ARRIVE), navi.tbtInfo().map { it.type })
        assertEquals(100.0, navi.tbtInfo().first().distance, 1e-9)
        assertEquals(listOf("cam-shared"), navi.cameraAlerts(50.0).map { it.id })
        assertSame(planned.route, state.shownRoute)
        assertEquals("missed left", state.nextTurn?.description)
        assertEquals(900.0, state.remainingM, 1e-9)
    }

    @Test
    fun holdsThePlannedProgressAtTheLeavePointWhileTheCarDrivesOn() {
        val guidance = Guidance(driven, planned)
        val navi = FakeNaviHelper(guidance)

        guidance.driveTo(220.0)
        val state = guidance.state()

        assertEquals(GuidancePhase.GUIDING, guidance.phase)
        // The missed turn is behind the held position; the next planned one stays as far as at the leave point.
        assertEquals(TurnType.TURN_ARRIVE, navi.tbtInfo().single().type)
        assertEquals(800.0, navi.tbtInfo().single().distance, 1e-9)
        assertEquals(800.0, state.remainingM, 1e-9)
        assertEquals(0.2, state.shownFraction, 1e-9)
        // The car is drawn where it really is, off the planned line.
        assertEquals(220.0, state.progressM, 1e-9)
        assertEquals(220f, state.carX, 1e-3f)
        assertEquals(0f, state.carY, 1e-3f)
        // A camera past the leave point is not on the planned route.
        assertTrue(navi.cameraAlerts(50.0).isEmpty())
    }

    @Test
    fun searchesWithEmptyReportsOnceTheCarIsOffRoute() {
        val guidance = Guidance(driven, planned)
        val navi = FakeNaviHelper(guidance)
        val recorder = Recorder()

        // Past the leave point at 210 m (21 s); judged off route the detection time later.
        guidance.driveTo(210.0 + 10.0 * Guidance.OFF_ROUTE_DETECT_MS / 1000)
        navi.emit(36.0, recorder)
        val state = guidance.state()

        assertEquals(GuidancePhase.REROUTING, guidance.phase)
        assertEquals(emptyList<TBTInfo>(), recorder.tbt)
        assertEquals(emptyList<CameraAlert>(), recorder.cameras)
        assertEquals(emptyList<CautionPoint>(), recorder.cautionPoints)
        assertEquals(0, recorder.rerouteEnded)
        assertTrue(state.rerouting)
        assertNull(state.nextTurn)
        assertNull(state.frontEvent)
        assertSame(planned.route, state.shownRoute)
        assertNull(guidance.nextTurn())
    }

    @Test
    fun guidesTheDrivenRouteFromWhereTheSearchEnds() {
        val guidance = Guidance(driven, planned)
        val navi = FakeNaviHelper(guidance)
        val recorder = Recorder()

        val detectedMs = guidance.driveTo(230.0)
        val endMs = guidance.driveTo(230.0 + 10.0 * Guidance.REROUTE_SEARCH_MS / 1000 - 10.0, detectedMs)
        assertEquals(GuidancePhase.REROUTING, guidance.phase)
        guidance.onSpeed(TEN_MPS_IN_KPH, endMs + 1000)
        assertEquals(detectedMs + Guidance.REROUTE_SEARCH_MS, endMs + 1000)
        assertEquals(GuidancePhase.REROUTED, guidance.phase)

        navi.emit(36.0, recorder)
        navi.emit(36.0, recorder)
        val state = guidance.state()

        assertEquals(1, recorder.rerouteEnded)
        assertEquals(250.0, state.progressM, 1e-9)
        assertEquals(TurnType.TURN_RIGHT, recorder.tbt!!.first().type)
        assertEquals(350.0, recorder.tbt!!.first().distance, 1e-9)
        assertEquals(listOf("cam-after"), recorder.cameras!!.map { it.id })
        assertSame(driven, state.shownRoute)
        assertEquals(state.fraction, state.shownFraction, 0.0)
        assertEquals(750.0, state.remainingM, 1e-9)
        assertEquals("new right", state.nextTurn?.description)
        assertFalse(state.rerouting)
    }

    @Test
    fun resetStartsOver() {
        val guidance = Guidance(driven, planned)
        guidance.driveTo(300.0)
        assertEquals(GuidancePhase.REROUTED, guidance.phase)

        guidance.reset()

        assertEquals(GuidancePhase.GUIDING, guidance.phase)
        assertFalse(guidance.takeRerouteEnded())
        assertSame(planned.route, guidance.state().shownRoute)
    }

    @Test
    fun aStartPastTheLeavePointBeginsOnTheNewRoute() {
        val guidance = Guidance(driven, planned)
        val recorder = Recorder()

        guidance.reset(400.0)
        FakeNaviHelper(guidance).emit(36.0, recorder)

        assertEquals(GuidancePhase.REROUTED, guidance.phase)
        assertEquals(0, recorder.rerouteEnded)
        assertEquals(200.0, recorder.tbt!!.first().distance, 1e-9)
    }
}
