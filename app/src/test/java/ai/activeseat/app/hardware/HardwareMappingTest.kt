package ai.activeseat.app.hardware

import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.hint.LongitudinalCue
import org.junit.Assert.assertEquals
import org.junit.Test

class HardwareMappingTest {
    private fun assertModule(vibration: Vibration, roll: Double, pitch: Double, yaw: Double, actual: ModuleCommand) {
        assertEquals(vibration, actual.vibration)
        assertEquals(roll, actual.rollDeg, 1e-9)
        assertEquals(pitch, actual.pitchDeg, 1e-9)
        assertEquals(yaw, actual.yawDeg, 1e-9)
    }

    @Test fun noCueIsNeutral() = assertEquals(HardwareCommand(), Hint().toHardwareCommand())

    @Test fun leftLeansTurnsAndVibratesLeftOnEveryModule() {
        val command = Hint(lateral = LateralCue.LEFT, lateralStrength = 1.0).toHardwareCommand()
        for (module in listOf(command.headrest, command.backrest, command.cushion)) {
            assertModule(Vibration(left = 1.0), 3.0, 0.0, 6.0, module)
        }
    }

    @Test fun rightLeansTurnsAndVibratesRightOnEveryModule() {
        val command = Hint(lateral = LateralCue.RIGHT, lateralStrength = 1.0).toHardwareCommand()
        for (module in listOf(command.headrest, command.backrest, command.cushion)) {
            assertModule(Vibration(right = 1.0), -3.0, 0.0, -6.0, module)
        }
    }

    @Test fun brakeLeansBackAndVibratesOnlyTheCushionRear() {
        val command = Hint(longitudinal = LongitudinalCue.BRAKE, longitudinalStrength = 1.0).toHardwareCommand()
        assertModule(Vibration(), 0.0, -2.0, 0.0, command.headrest)
        assertModule(Vibration(), 0.0, -2.0, 0.0, command.backrest)
        assertModule(Vibration(back = 1.0), 0.0, -2.0, 0.0, command.cushion)
    }

    @Test fun accelerateLeansForwardAndVibratesOnlyTheCushionFront() {
        val command = Hint(longitudinal = LongitudinalCue.ACCELERATE, longitudinalStrength = 1.0).toHardwareCommand()
        assertModule(Vibration(), 0.0, 2.0, 0.0, command.headrest)
        assertModule(Vibration(front = 1.0), 0.0, 2.0, 0.0, command.cushion)
    }

    @Test fun anglesAndVibrationScaleWithStrength() {
        val command = Hint(lateral = LateralCue.LEFT, lateralStrength = 0.5).toHardwareCommand()
        assertModule(Vibration(left = 0.5), 1.5, 0.0, 3.0, command.headrest)
    }

    @Test fun cornerAndBrakeCombine() {
        val command = Hint(LateralCue.LEFT, 0.5, LongitudinalCue.BRAKE, 0.25).toHardwareCommand()
        assertModule(Vibration(left = 0.5), 1.5, -0.5, 3.0, command.headrest)
        assertModule(Vibration(left = 0.5, back = 0.25), 1.5, -0.5, 3.0, command.cushion)
    }

    @Test fun watchTakesTheStrongerAxisWithoutDirection() {
        assertEquals(0.75, Hint(LateralCue.RIGHT, 0.25, LongitudinalCue.BRAKE, 0.75).toHardwareCommand().watchStrength, 1e-9)
        assertEquals(0.75, Hint(LateralCue.LEFT, 0.75, LongitudinalCue.ACCELERATE, 0.25).toHardwareCommand().watchStrength, 1e-9)
    }

    private val defaults = HardwareSettings()

    @Test fun defaultSettingsScaleVibrationToThreeFifths() {
        val left = Hint(lateral = LateralCue.LEFT, lateralStrength = 1.0)
        val command = toHardwareCommand(left, left, defaults)
        for (module in listOf(command.headrest, command.backrest, command.cushion)) {
            assertModule(Vibration(left = 0.6), 3.0, 0.0, 6.0, module)
        }
        assertEquals(0.6, command.watchStrength, 1e-9)
        assertEquals(2.0, command.leanSec, 1e-9)
    }

    @Test fun seatVibrationScalesEveryZoneButNotTheLean() {
        val hint = Hint(LateralCue.RIGHT, 0.5, LongitudinalCue.BRAKE, 1.0)
        val command = toHardwareCommand(hint, Hint(), defaults.copy(seatVibration = 5))
        assertModule(Vibration(right = 0.5, back = 1.0), -1.5, -2.0, -3.0, command.cushion)
        val quiet = toHardwareCommand(hint, Hint(), defaults.copy(seatVibration = 0))
        assertModule(Vibration(), -1.5, -2.0, -3.0, quiet.cushion)
    }

    @Test fun leanOffKeepsTheSeatUprightButVibrating() {
        val hint = Hint(LateralCue.LEFT, 1.0, LongitudinalCue.ACCELERATE, 1.0)
        val command = toHardwareCommand(hint, Hint(), defaults.copy(seatVibration = 5, leanSpeed = 0))
        assertModule(Vibration(left = 1.0), 0.0, 0.0, 0.0, command.headrest)
        assertModule(Vibration(left = 1.0, front = 1.0), 0.0, 0.0, 0.0, command.cushion)
        assertEquals(HardwareCommand.DEFAULT_LEAN_SEC, command.leanSec, 1e-9)
    }

    @Test fun leanSpeedSetsLeanSec() {
        assertEquals(8.0, toHardwareCommand(Hint(), Hint(), defaults.copy(leanSpeed = 1)).leanSec, 1e-9)
        assertEquals(0.5, toHardwareCommand(Hint(), Hint(), defaults.copy(leanSpeed = 5)).leanSec, 1e-9)
    }

    @Test fun seatAndWatchFollowTheirOwnHints() {
        val seat = Hint(lateral = LateralCue.LEFT, lateralStrength = 1.0)
        val watch = Hint(longitudinal = LongitudinalCue.BRAKE, longitudinalStrength = 0.5)
        val command = toHardwareCommand(seat, watch, defaults.copy(seatVibration = 5, watchVibration = 5))
        assertModule(Vibration(left = 1.0), 3.0, 0.0, 6.0, command.cushion)
        assertEquals(0.5, command.watchStrength, 1e-9)
    }

    @Test fun seatOffRestsTheSeat() {
        val left = Hint(lateral = LateralCue.LEFT, lateralStrength = 1.0)
        val command = toHardwareCommand(left, left, defaults.copy(seatSensitivity = 0, watchVibration = 5))
        assertEquals(ModuleCommand(), command.headrest)
        assertEquals(ModuleCommand(), command.backrest)
        assertEquals(ModuleCommand(), command.cushion)
        assertEquals(1.0, command.watchStrength, 1e-9)
    }

    @Test fun watchOffRestsTheWatch() {
        val left = Hint(lateral = LateralCue.LEFT, lateralStrength = 1.0)
        val command = toHardwareCommand(left, left, defaults.copy(watchSensitivity = 0, watchVibration = 5))
        assertEquals(0.0, command.watchStrength, 1e-9)
        assertModule(Vibration(left = 0.6), 3.0, 0.0, 6.0, command.headrest)
    }
}
