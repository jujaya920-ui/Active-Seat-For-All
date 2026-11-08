package ai.activeseat.app.hardware

import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.hint.LongitudinalCue
import ai.activeseat.app.seat.YawContext
import ai.activeseat.app.vehicle.SeatPosition
import kotlin.math.atan
import org.junit.Assert.assertEquals
import org.junit.Test

class HardwareMappingTest {
    private val at36 = YawContext(36.0, SeatPosition.ROW_1_RIGHT)

    private fun assertModule(vibration: Vibration, roll: Double, pitch: Double, yaw: Double, actual: ModuleCommand) {
        assertEquals(vibration, actual.vibration)
        assertEquals(roll, actual.rollDeg, 1e-9)
        assertEquals(pitch, actual.pitchDeg, 1e-9)
        assertEquals(yaw, actual.yawDeg, 1e-9)
    }

    /** The angle that cancels [mps2], worked out here independently of the mapping. */
    private fun cancel(mps2: Double) = Math.toDegrees(atan(mps2 / 9.81))

    /** At 36 km/h, 10 m/s: the heading change over the 2.2 s look-ahead. */
    private fun yaw(mps2: Double) = minOf(Math.toDegrees(mps2 / 10.0 * 2.2), 20.0)

    private fun left(strength: Double, mps2: Double) = Hint(lateral = LateralCue.LEFT, lateralStrength = strength, lateralMps2 = mps2)

    @Test fun noCueIsNeutral() = assertEquals(HardwareCommand(), Hint().toHardwareCommand(at36))

    @Test fun leftLeansTurnsAndVibratesLeftOnEveryModule() {
        val command = left(0.5, 2.0).toHardwareCommand(at36)
        for (module in listOf(command.headrest, command.backrest, command.cushion)) {
            assertModule(Vibration(left = 0.5), cancel(2.0), 0.0, yaw(2.0), module)
        }
    }

    @Test fun rightLeansTurnsAndVibratesRightOnEveryModule() {
        val command = Hint(lateral = LateralCue.RIGHT, lateralStrength = 0.5, lateralMps2 = 2.0).toHardwareCommand(at36)
        for (module in listOf(command.headrest, command.backrest, command.cushion)) {
            assertModule(Vibration(right = 0.5), -cancel(2.0), 0.0, -yaw(2.0), module)
        }
    }

    @Test fun brakeLeansBackAndVibratesOnlyTheCushionRear() {
        val command = Hint(longitudinal = LongitudinalCue.BRAKE, longitudinalStrength = 0.25, longitudinalMps2 = 1.0)
            .toHardwareCommand(at36)
        assertModule(Vibration(), 0.0, -cancel(1.0), 0.0, command.headrest)
        assertModule(Vibration(), 0.0, -cancel(1.0), 0.0, command.backrest)
        assertModule(Vibration(back = 0.25), 0.0, -cancel(1.0), 0.0, command.cushion)
    }

    @Test fun accelerateLeansForwardAndVibratesOnlyTheCushionFront() {
        val command = Hint(longitudinal = LongitudinalCue.ACCELERATE, longitudinalStrength = 0.25, longitudinalMps2 = 1.0)
            .toHardwareCommand(at36)
        assertModule(Vibration(), 0.0, cancel(1.0), 0.0, command.headrest)
        assertModule(Vibration(front = 0.25), 0.0, cancel(1.0), 0.0, command.cushion)
    }

    @Test fun theAngleFollowsTheAccelerationAndTheVibrationTheStrength() {
        // Same acceleration, different strength: the angle stays, the vibration changes.
        val soft = left(0.25, 2.0).toHardwareCommand(at36).headrest
        val hard = left(1.0, 2.0).toHardwareCommand(at36).headrest
        assertEquals(soft.rollDeg, hard.rollDeg, 1e-9)
        assertEquals(0.25, soft.vibration.left, 1e-9)
        assertEquals(1.0, hard.vibration.left, 1e-9)
    }

    @Test fun theLeanStopsAtItsLimit() {
        val command = left(1.0, 9.81).toHardwareCommand(at36)
        assertModule(Vibration(left = 1.0), 15.0, 0.0, 20.0, command.headrest)
    }

    @Test fun cornerAndBrakeCombine() {
        val command = Hint(LateralCue.LEFT, 0.5, LongitudinalCue.BRAKE, 0.25, lateralMps2 = 2.0, longitudinalMps2 = 1.0)
            .toHardwareCommand(at36)
        assertModule(Vibration(left = 0.5), cancel(2.0), -cancel(1.0), yaw(2.0), command.headrest)
        assertModule(Vibration(left = 0.5, back = 0.25), cancel(2.0), -cancel(1.0), yaw(2.0), command.cushion)
    }

    @Test fun watchTakesTheStrongerAxisWithoutDirection() {
        assertEquals(0.75, Hint(LateralCue.RIGHT, 0.25, LongitudinalCue.BRAKE, 0.75).toHardwareCommand(at36).watchStrength, 1e-9)
        assertEquals(0.75, Hint(LateralCue.LEFT, 0.75, LongitudinalCue.ACCELERATE, 0.25).toHardwareCommand(at36).watchStrength, 1e-9)
    }

    private val defaults = HardwareSettings()

    @Test fun defaultSettingsScaleVibrationToThreeFifths() {
        val hint = left(1.0, 2.0)
        val command = toHardwareCommand(hint, hint, defaults, at36)
        for (module in listOf(command.headrest, command.backrest, command.cushion)) {
            assertModule(Vibration(left = 0.6), cancel(2.0), 0.0, yaw(2.0), module)
        }
        assertEquals(0.6, command.watchStrength, 1e-9)
        assertEquals(2.0, command.leanSec, 1e-9)
    }

    @Test fun seatVibrationScalesEveryZoneButNotTheLean() {
        val hint = Hint(LateralCue.RIGHT, 0.5, LongitudinalCue.BRAKE, 1.0, lateralMps2 = 2.0, longitudinalMps2 = 2.5)
        val command = toHardwareCommand(hint, Hint(), defaults.copy(seatVibration = 5), at36)
        assertModule(Vibration(right = 0.5, back = 1.0), -cancel(2.0), -cancel(2.5), -yaw(2.0), command.cushion)
        val quiet = toHardwareCommand(hint, Hint(), defaults.copy(seatVibration = 0), at36)
        assertModule(Vibration(), -cancel(2.0), -cancel(2.5), -yaw(2.0), quiet.cushion)
    }

    @Test fun leanSpeedSetsOnlyTheTimeNotTheAngle() {
        val hint = left(0.5, 2.0)
        val slow = toHardwareCommand(hint, Hint(), defaults.copy(leanSpeed = 1), at36)
        val fast = toHardwareCommand(hint, Hint(), defaults.copy(leanSpeed = 5), at36)
        assertEquals(slow.headrest, fast.headrest)
        assertEquals(8.0, slow.leanSec, 1e-9)
        assertEquals(0.5, fast.leanSec, 1e-9)
    }

    @Test fun leanOffKeepsTheSeatUprightButVibrating() {
        val hint = Hint(LateralCue.LEFT, 1.0, LongitudinalCue.ACCELERATE, 1.0, lateralMps2 = 4.0, longitudinalMps2 = 4.0)
        val command = toHardwareCommand(hint, Hint(), defaults.copy(seatVibration = 5, leanSpeed = 0), at36)
        assertModule(Vibration(left = 1.0), 0.0, 0.0, 0.0, command.headrest)
        assertModule(Vibration(left = 1.0, front = 1.0), 0.0, 0.0, 0.0, command.cushion)
        assertEquals(HardwareCommand.DEFAULT_LEAN_SEC, command.leanSec, 1e-9)
    }

    @Test fun seatAndWatchFollowTheirOwnHints() {
        val seat = left(1.0, 2.5)
        val watch = Hint(longitudinal = LongitudinalCue.BRAKE, longitudinalStrength = 0.5, longitudinalMps2 = 2.0)
        val command = toHardwareCommand(seat, watch, defaults.copy(seatVibration = 5, watchVibration = 5), at36)
        assertModule(Vibration(left = 1.0), cancel(2.5), 0.0, yaw(2.5), command.cushion)
        assertEquals(0.5, command.watchStrength, 1e-9)
    }

    @Test fun seatOffRestsTheSeat() {
        val hint = left(1.0, 4.0)
        val command = toHardwareCommand(hint, hint, defaults.copy(seatSensitivity = 0, watchVibration = 5), at36)
        assertEquals(ModuleCommand(), command.headrest)
        assertEquals(ModuleCommand(), command.backrest)
        assertEquals(ModuleCommand(), command.cushion)
        assertEquals(1.0, command.watchStrength, 1e-9)
    }

    @Test fun watchOffRestsTheWatch() {
        val hint = left(1.0, 2.5)
        val command = toHardwareCommand(hint, hint, defaults.copy(watchSensitivity = 0, watchVibration = 5), at36)
        assertEquals(0.0, command.watchStrength, 1e-9)
        assertModule(Vibration(left = 0.6), cancel(2.5), 0.0, yaw(2.5), command.headrest)
    }
}
