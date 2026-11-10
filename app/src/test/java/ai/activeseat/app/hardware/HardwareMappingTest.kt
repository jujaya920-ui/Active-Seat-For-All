package ai.activeseat.app.hardware

import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.hint.LongitudinalCue
import kotlin.math.atan
import org.junit.Assert.assertEquals
import org.junit.Test

class HardwareMappingTest {
    private fun assertModule(vibration: Vibration, roll: Double, pitch: Double, yaw: Double, actual: ModuleCommand) {
        assertEquals(vibration, actual.vibration)
        assertEquals(roll, actual.rollDeg, 1e-9)
        assertEquals(pitch, actual.pitchDeg, 1e-9)
        assertEquals(yaw, actual.yawDeg, 1e-9)
    }

    /** The angle that cancels [mps2], worked out here independently of the mapping. */
    private fun cancel(mps2: Double) = Math.toDegrees(atan(mps2 / 9.81))

    private fun left(strength: Double, mps2: Double, wheelDeg: Double = 12.0) =
        Hint(lateral = LateralCue.LEFT, lateralStrength = strength, lateralMps2 = mps2, wheelDeg = wheelDeg)

    @Test fun noCueIsNeutral() = assertEquals(HardwareCommand(), Hint().toHardwareCommand())

    @Test fun leftLeansTurnsAndVibratesLeftOnEveryModule() {
        val command = left(0.5, 2.0).toHardwareCommand()
        assertModule(Vibration(), cancel(2.0), 0.0, 12.0, command.passenger.headrest)
        for (module in listOf(command.passenger.backrest, command.passenger.cushion)) {
            assertModule(Vibration(left = 0.5), cancel(2.0), 0.0, 12.0, module)
        }
    }

    @Test fun rightLeansTurnsAndVibratesRightOnEveryModule() {
        val command = Hint(lateral = LateralCue.RIGHT, lateralStrength = 0.5, lateralMps2 = 2.0, wheelDeg = 12.0).toHardwareCommand()
        assertModule(Vibration(), -cancel(2.0), 0.0, -12.0, command.passenger.headrest)
        for (module in listOf(command.passenger.backrest, command.passenger.cushion)) {
            assertModule(Vibration(right = 0.5), -cancel(2.0), 0.0, -12.0, module)
        }
    }

    @Test fun brakeLeansBackAndVibratesOnlyTheCushionRear() {
        val command = Hint(longitudinal = LongitudinalCue.BRAKE, longitudinalStrength = 0.25, longitudinalMps2 = 1.0)
            .toHardwareCommand()
        assertModule(Vibration(), 0.0, -cancel(1.0), 0.0, command.passenger.headrest)
        assertModule(Vibration(), 0.0, -cancel(1.0), 0.0, command.passenger.backrest)
        assertModule(Vibration(back = 0.25), 0.0, -cancel(1.0), 0.0, command.passenger.cushion)
    }

    @Test fun accelerateLeansForwardAndVibratesOnlyTheCushionFront() {
        val command = Hint(longitudinal = LongitudinalCue.ACCELERATE, longitudinalStrength = 0.25, longitudinalMps2 = 1.0)
            .toHardwareCommand()
        assertModule(Vibration(), 0.0, cancel(1.0), 0.0, command.passenger.headrest)
        assertModule(Vibration(front = 0.25), 0.0, cancel(1.0), 0.0, command.passenger.cushion)
    }

    @Test fun theAngleFollowsTheAccelerationAndTheVibrationTheStrength() {
        // Same acceleration, different strength: the angle stays, the vibration changes.
        val soft = left(0.25, 2.0).toHardwareCommand().passenger.backrest
        val hard = left(1.0, 2.0).toHardwareCommand().passenger.backrest
        assertEquals(soft.rollDeg, hard.rollDeg, 1e-9)
        assertEquals(0.25, soft.vibration.left, 1e-9)
        assertEquals(1.0, hard.vibration.left, 1e-9)
    }

    @Test fun theLeanAndTurnStopAtTheirLimits() {
        val command = left(1.0, 9.81, wheelDeg = 40.0).toHardwareCommand()
        assertModule(Vibration(left = 1.0), 15.0, 0.0, 30.0, command.passenger.backrest)
    }

    @Test fun theTurnFollowsTheWheelNotTheAcceleration() {
        // Same wheel angle at two speeds: very different push, the same line of sight.
        val slow = left(0.25, 1.0, wheelDeg = 16.0).toHardwareCommand()
        val fast = left(1.0, 4.0, wheelDeg = 16.0).toHardwareCommand()
        assertEquals(16.0, slow.passenger.headrest.yawDeg, 1e-9)
        assertEquals(16.0, fast.passenger.headrest.yawDeg, 1e-9)
    }

    @Test fun cornerAndBrakeCombine() {
        val command = Hint(LateralCue.LEFT, 0.5, LongitudinalCue.BRAKE, 0.25, lateralMps2 = 2.0, longitudinalMps2 = 1.0, wheelDeg = 12.0)
            .toHardwareCommand()
        assertModule(Vibration(left = 0.5), cancel(2.0), -cancel(1.0), 12.0, command.passenger.backrest)
        assertModule(Vibration(left = 0.5, back = 0.25), cancel(2.0), -cancel(1.0), 12.0, command.passenger.cushion)
    }

    @Test fun watchTakesTheStrongerAxisWithoutDirection() {
        assertEquals(0.75, Hint(LateralCue.RIGHT, 0.25, LongitudinalCue.BRAKE, 0.75).toHardwareCommand().passenger.watchStrength, 1e-9)
        assertEquals(0.75, Hint(LateralCue.LEFT, 0.75, LongitudinalCue.ACCELERATE, 0.25).toHardwareCommand().passenger.watchStrength, 1e-9)
    }

    private val defaults = HardwareSettings()

    @Test fun defaultSettingsScaleVibrationToThreeFifths() {
        val hint = left(1.0, 2.0)
        val command = toHardwareCommand(hint, hint, defaults)
        for (module in listOf(command.passenger.backrest, command.passenger.cushion)) {
            assertModule(Vibration(left = 0.6), cancel(2.0), 0.0, 12.0, module)
        }
        assertEquals(0.6, command.passenger.watchStrength, 1e-9)
        assertEquals(2.0, command.passenger.leanSec, 1e-9)
    }

    @Test fun seatVibrationScalesEveryZoneButNotTheLean() {
        val hint = Hint(LateralCue.RIGHT, 0.5, LongitudinalCue.BRAKE, 1.0, lateralMps2 = 2.0, longitudinalMps2 = 2.5, wheelDeg = 12.0)
        val command = toHardwareCommand(hint, Hint(), defaults.copy(seatVibration = 5))
        assertModule(Vibration(right = 0.5, back = 1.0), -cancel(2.0), -cancel(2.5), -12.0, command.passenger.cushion)
        val quiet = toHardwareCommand(hint, Hint(), defaults.copy(seatVibration = 0))
        assertModule(Vibration(), -cancel(2.0), -cancel(2.5), -12.0, quiet.passenger.cushion)
    }

    @Test fun leanSpeedSetsOnlyTheTimeNotTheAngle() {
        val hint = left(0.5, 2.0)
        val slow = toHardwareCommand(hint, Hint(), defaults.copy(leanSpeed = 1))
        val fast = toHardwareCommand(hint, Hint(), defaults.copy(leanSpeed = 5))
        assertEquals(slow.passenger.headrest, fast.passenger.headrest)
        assertEquals(8.0, slow.passenger.leanSec, 1e-9)
        assertEquals(0.5, fast.passenger.leanSec, 1e-9)
    }

    @Test fun leanOffKeepsTheSeatUprightButVibrating() {
        val hint = Hint(LateralCue.LEFT, 1.0, LongitudinalCue.ACCELERATE, 1.0, lateralMps2 = 4.0, longitudinalMps2 = 4.0)
        val command = toHardwareCommand(hint, Hint(), defaults.copy(seatVibration = 5, leanSpeed = 0))
        assertModule(Vibration(left = 1.0), 0.0, 0.0, 0.0, command.passenger.backrest)
        assertModule(Vibration(left = 1.0, front = 1.0), 0.0, 0.0, 0.0, command.passenger.cushion)
        assertEquals(SeatCommand(), command.rearLeft.copy(backrest = ModuleCommand(), cushion = ModuleCommand()))
        assertEquals(HardwareCommand.DEFAULT_LEAN_SEC, command.passenger.leanSec, 1e-9)
    }

    @Test fun seatAndWatchFollowTheirOwnHints() {
        val seat = left(1.0, 2.5)
        val watch = Hint(longitudinal = LongitudinalCue.BRAKE, longitudinalStrength = 0.5, longitudinalMps2 = 2.0)
        val command = toHardwareCommand(seat, watch, defaults.copy(seatVibration = 5, watchVibration = 5))
        assertModule(Vibration(left = 1.0), cancel(2.5), 0.0, 12.0, command.passenger.cushion)
        assertEquals(0.5, command.passenger.watchStrength, 1e-9)
    }

    @Test fun seatOffRestsTheSeat() {
        val hint = left(1.0, 4.0)
        val command = toHardwareCommand(hint, hint, defaults.copy(seatSensitivity = 0, watchVibration = 5))
        // The seat rests; that passenger's watch still vibrates.
        assertEquals(SeatCommand(watchStrength = 1.0), command.passenger)
        assertEquals(SeatCommand(watchStrength = 1.0), command.rearLeft)
        assertEquals(SeatCommand(watchStrength = 1.0), command.rearRight)
    }

    @Test fun watchOffRestsTheWatch() {
        val hint = left(1.0, 2.5)
        val command = toHardwareCommand(hint, hint, defaults.copy(watchSensitivity = 0, watchVibration = 5))
        assertEquals(0.0, command.passenger.watchStrength, 1e-9)
        assertModule(Vibration(left = 0.6), cancel(2.5), 0.0, 12.0, command.passenger.backrest)
    }

    @Test fun aRearSeatMovesEachModuleOnItsOwn() {
        val command = Hint(LateralCue.LEFT, 0.5, LongitudinalCue.BRAKE, 0.25, lateralMps2 = 2.0, longitudinalMps2 = 1.0, wheelDeg = 12.0)
            .toHardwareCommand()
        for (rear in listOf(command.rearLeft, command.rearRight)) {
            // Headrest: turns only, no vibration.
            assertModule(Vibration(), 0.0, 0.0, 12.0, rear.headrest)
            // Backrest: pitches, vibrates for the corner.
            assertModule(Vibration(left = 0.5), 0.0, -cancel(1.0), 0.0, rear.backrest)
            // Cushion: rolls and pitches, vibrates for the corner and the brake.
            assertModule(Vibration(left = 0.5, back = 0.25), cancel(2.0), -cancel(1.0), 0.0, rear.cushion)
        }
    }

    @Test fun anEmptySeatRests() {
        val hint = left(1.0, 2.5)
        val nobodyInFront = toHardwareCommand(hint, hint, defaults, Occupancy(passenger = false))
        assertEquals(SeatCommand(), nobodyInFront.passenger)
        assertEquals(0.6, nobodyInFront.rearLeft.watchStrength, 1e-9)
        assertEquals(12.0, nobodyInFront.rearLeft.headrest.yawDeg, 1e-9)

        // Each rear seat on its own: the empty right seat stays still beside a taken left seat.
        val leftOnly = toHardwareCommand(hint, hint, defaults, Occupancy(rearRight = false))
        assertEquals(12.0, leftOnly.rearLeft.headrest.yawDeg, 1e-9)
        assertEquals(SeatCommand(), leftOnly.rearRight)

        val rightOnly = toHardwareCommand(hint, hint, defaults, Occupancy(rearLeft = false))
        assertEquals(SeatCommand(), rightOnly.rearLeft)
        assertEquals(12.0, rightOnly.rearRight.headrest.yawDeg, 1e-9)

        val nobodyInTheBack = toHardwareCommand(hint, hint, defaults, Occupancy(rearLeft = false, rearRight = false))
        assertEquals(SeatCommand(), nobodyInTheBack.rearLeft)
        assertEquals(SeatCommand(), nobodyInTheBack.rearRight)
        assertEquals(12.0, nobodyInTheBack.passenger.headrest.yawDeg, 1e-9)
    }
}
