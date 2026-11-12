package ai.activeseat.app.motion

import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.hint.LongitudinalCue
import ai.activeseat.app.vehicle.TurnSignal
import ai.activeseat.app.vehicle.VehicleSignal
import ai.pleos.playground.navi.constants.CameraType
import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.TBTInfo
import org.junit.Assert.assertEquals
import org.junit.Test

private fun camera(remainM: Int, limit: Int = 50) =
    CameraAlert("cam-1", CameraType.SPEED_CAMERA_FIX, true, limit, remainM, 0, false, false)

class MotionEngineTest {
    private val engine = MotionEngine(MotionConfig())
    private var ms = 0L

    private fun step(
        kph: Double,
        steeringDeg: Double = 0.0,
        signal: TurnSignal = TurnSignal.OFF,
        nav: NavSnapshot = NavSnapshot(),
    ): MotionOutput {
        val out = engine.step(VehicleSignal(kph, steeringDeg, false, signal), ms, nav)
        ms += STEP_MS
        return out
    }

    @Test
    fun measuredCornerTurnsOnInItsDirection() {
        repeat(5) { step(30.0) }
        // 30 km/h with 200 degrees left: about 6 m/s².
        val out = step(30.0, steeringDeg = 200.0)

        assertEquals(LateralCue.LEFT, out.hint.lateral)
        assertEquals(CueSource.MEASURED, out.lateralSource)
        // The seat turns by the road-wheel angle: steering over the ratio.
        assertEquals(200.0 / MotionConfig().steeringRatio, out.hint.wheelDeg, 1e-9)
    }

    @Test
    fun steadyDrivingGivesNoCue() {
        repeat(20) { step(50.0, steeringDeg = 3.0) }
        val out = step(50.0, steeringDeg = 3.0)

        assertEquals(LateralCue.NONE, out.hint.lateral)
        assertEquals(LongitudinalCue.NONE, out.hint.longitudinal)
    }

    @Test
    fun measuredBrakingTurnsOnTheBrakeCue() {
        repeat(5) { step(60.0) }
        var kph = 60.0
        var out = MotionOutput()
        repeat(6) {
            kph -= 0.2 * 2.5 * 3.6
            out = step(kph)
        }

        assertEquals(LongitudinalCue.BRAKE, out.hint.longitudinal)
        assertEquals(CueSource.MEASURED, out.longitudinalSource)
    }

    @Test
    fun speedingTowardACameraPredictsBraking() {
        repeat(5) { step(65.0, nav = NavSnapshot(cameras = listOf(camera(300)))) }
        // 65 to 50 km/h within 60 m needs about 1.1 m/s²; within 50 m about 1.3 m/s².
        val out = step(65.0, nav = NavSnapshot(cameras = listOf(camera(45))))

        assertEquals(LongitudinalCue.BRAKE, out.hint.longitudinal)
        assertEquals(CueSource.PREDICTED, out.longitudinalSource)
    }

    @Test
    fun aCameraPassedWithinTheLimitPredictsNothing() {
        repeat(5) { step(48.0, nav = NavSnapshot(cameras = listOf(camera(300)))) }
        val out = step(48.0, nav = NavSnapshot(cameras = listOf(camera(20))))

        assertEquals(LongitudinalCue.NONE, out.hint.longitudinal)
    }

    @Test
    fun aSpeedBumpPredictsBraking() {
        val bump = { m: Int -> NavSnapshot(cautionPoints = listOf(CautionPoint("cp-1", CautionPointType.SPEED_BUMP, m))) }
        repeat(5) { step(45.0, nav = bump(200)) }
        val out = step(45.0, nav = bump(40))

        assertEquals(LongitudinalCue.BRAKE, out.hint.longitudinal)
        assertEquals(CueSource.PREDICTED, out.longitudinalSource)
    }

    @Test
    fun aCautionPointThatAsksNoSlowdownIsIgnored() {
        val fog = NavSnapshot(cautionPoints = listOf(CautionPoint("cp-1", CautionPointType.FOG_AREA, 20)))
        repeat(5) { step(80.0, nav = fog) }

        assertEquals(LongitudinalCue.NONE, step(80.0, nav = fog).hint.longitudinal)
    }

    @Test
    fun aTurnIsPredictedOnlyWithTheMatchingTurnSignal() {
        val turn = NavSnapshot(tbt = listOf(TBTInfo(TurnType.TURN_LEFT, 8.0, "A")))
        repeat(5) { step(20.0, nav = turn) }

        assertEquals(LateralCue.NONE, step(20.0, nav = turn).hint.lateral)
        val out = step(20.0, signal = TurnSignal.LEFT, nav = turn)
        assertEquals(LateralCue.LEFT, out.hint.lateral)
        assertEquals(CueSource.PREDICTED, out.lateralSource)
        assertEquals(MotionConfig().turnWheelDeg, out.hint.wheelDeg, 1e-9)
    }

    @Test
    fun aTurnFarAheadIsNotPredictedYet() {
        val turn = NavSnapshot(tbt = listOf(TBTInfo(TurnType.TURN_RIGHT, 200.0, "A")))
        repeat(5) { step(30.0, signal = TurnSignal.RIGHT, nav = turn) }

        assertEquals(LateralCue.NONE, step(30.0, signal = TurnSignal.RIGHT, nav = turn).hint.lateral)
    }

    @Test
    fun anExpiredTurnPredictionDoesNotComeBackForTheSameTurn() {
        val turn = { m: Double -> NavSnapshot(tbt = listOf(TBTInfo(TurnType.TURN_LEFT, m, "A"))) }
        val first = turn(8.0)
        assertEquals(LateralCue.LEFT, step(20.0, signal = TurnSignal.LEFT, nav = first).hint.lateral)
        // The turn happens: measured motion takes over and uses the prediction up.
        repeat(5) { step(20.0, steeringDeg = 200.0, signal = TurnSignal.LEFT, nav = first) }
        repeat(10) { step(20.0, signal = TurnSignal.LEFT, nav = first) }
        // The navigation still sends the same turn for a moment after it.
        val out = step(20.0, signal = TurnSignal.LEFT, nav = turn(1.0))

        assertEquals(LateralCue.NONE, out.hint.lateral)
    }

    @Test
    fun stoppingReleasesATurnPredictionForAfterThePullAway() {
        val turn = NavSnapshot(tbt = listOf(TBTInfo(TurnType.TURN_LEFT, 5.0, "A")))
        step(20.0, signal = TurnSignal.LEFT, nav = turn)
        repeat(10) { step(0.0, signal = TurnSignal.LEFT, nav = turn) }
        assertEquals(LateralCue.NONE, step(0.0, signal = TurnSignal.LEFT, nav = turn).hint.lateral)

        val out = step(10.0, signal = TurnSignal.LEFT, nav = NavSnapshot(tbt = listOf(TBTInfo(TurnType.TURN_LEFT, 4.0, "A"))))

        assertEquals(LateralCue.LEFT, out.hint.lateral)
    }

    @Test
    fun sensitivityDecidesOnlyWhetherACornerTurnsOn() {
        // 30 km/h with 70 degrees left: about 2.1 m/s², above the on threshold at either sensitivity.
        val outs = listOf(1.0, 2.0).map { factor ->
            val scaled = MotionEngine(MotionConfig().scaled(factor))
            var t = 0L
            repeat(5) { scaled.step(VehicleSignal(30.0, 0.0, false, TurnSignal.OFF), t, NavSnapshot()); t += STEP_MS }
            scaled.step(VehicleSignal(30.0, 70.0, false, TurnSignal.OFF), t, NavSnapshot())
        }

        assertEquals(outs[0].hint, outs[1].hint)
        assertEquals(outs[0].lateralMps2, outs[0].hint.lateralMps2, 1e-9)
        assertEquals(0.5, outs[0].hint.lateralStrength, 1e-9)
    }

    @Test
    fun aPredictedTurnCarriesItsExpectedAcceleration() {
        val turn = NavSnapshot(tbt = listOf(TBTInfo(TurnType.TURN_LEFT, 8.0, "A")))
        val out = step(20.0, signal = TurnSignal.LEFT, nav = turn)

        assertEquals(MotionConfig().turnMps2, out.hint.lateralMps2, 1e-9)
        assertEquals(0.5, out.hint.lateralStrength, 1e-9)
    }

    @Test
    fun parkOrNeutralTurnsEverythingOff() {
        repeat(5) { step(30.0) }
        step(30.0, steeringDeg = 200.0)

        val out = engine.step(VehicleSignal(30.0, 200.0, false, TurnSignal.OFF), ms, NavSnapshot(), active = false)

        assertEquals(LateralCue.NONE, out.hint.lateral)
    }
}
