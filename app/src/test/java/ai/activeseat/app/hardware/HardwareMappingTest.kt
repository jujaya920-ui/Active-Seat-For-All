package ai.activeseat.app.hardware

import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.hint.LongitudinalCue
import org.junit.Assert.assertEquals
import org.junit.Test

class HardwareMappingTest {
    private fun assertModule(expected: ModuleCommand, actual: ModuleCommand) {
        assertEquals(expected.vibration, actual.vibration, 1e-9)
        assertEquals(expected.rollDeg, actual.rollDeg, 1e-9)
        assertEquals(expected.pitchDeg, actual.pitchDeg, 1e-9)
        assertEquals(expected.yawDeg, actual.yawDeg, 1e-9)
    }

    @Test fun noCueIsNeutral() = assertEquals(HardwareCommand(), Hint().toHardwareCommand())

    @Test fun leftLeansAndTurnsLeft() {
        val command = Hint(lateral = LateralCue.LEFT, lateralStrength = 1.0).toHardwareCommand()
        assertModule(ModuleCommand(1.0, 3.0, 0.0, 6.0), command.headrest)
        assertModule(ModuleCommand(1.0, 2.0, 0.0, 0.0), command.backrest)
        assertModule(ModuleCommand(1.0, 2.0, 0.0, 0.0), command.cushion)
        assertEquals(WatchPattern.LEFT, command.watch)
        assertEquals(1.0, command.watchStrength, 1e-9)
    }

    @Test fun rightLeansAndTurnsRight() {
        val command = Hint(lateral = LateralCue.RIGHT, lateralStrength = 1.0).toHardwareCommand()
        assertModule(ModuleCommand(1.0, -3.0, 0.0, -6.0), command.headrest)
        assertModule(ModuleCommand(1.0, -2.0, 0.0, 0.0), command.backrest)
        assertModule(ModuleCommand(1.0, -2.0, 0.0, 0.0), command.cushion)
        assertEquals(WatchPattern.RIGHT, command.watch)
    }

    @Test fun brakeLeansBack() {
        val command = Hint(longitudinal = LongitudinalCue.BRAKE, longitudinalStrength = 1.0).toHardwareCommand()
        assertModule(ModuleCommand(1.0, 0.0, -2.0, 0.0), command.headrest)
        assertModule(ModuleCommand(1.0, 0.0, -2.0, 0.0), command.backrest)
        assertModule(ModuleCommand(1.0, 0.0, -1.5, 0.0), command.cushion)
        assertEquals(WatchPattern.BRAKE, command.watch)
    }

    @Test fun accelerateLeansForward() {
        val command = Hint(longitudinal = LongitudinalCue.ACCELERATE, longitudinalStrength = 1.0).toHardwareCommand()
        assertModule(ModuleCommand(1.0, 0.0, 2.0, 0.0), command.headrest)
        assertModule(ModuleCommand(1.0, 0.0, 2.0, 0.0), command.backrest)
        assertModule(ModuleCommand(1.0, 0.0, 1.5, 0.0), command.cushion)
        assertEquals(WatchPattern.ACCELERATE, command.watch)
    }

    @Test fun anglesScaleWithStrength() {
        val command = Hint(lateral = LateralCue.LEFT, lateralStrength = 0.5).toHardwareCommand()
        assertModule(ModuleCommand(0.5, 1.5, 0.0, 3.0), command.headrest)
    }

    @Test fun cornerAndBrakeCombine() {
        val command = Hint(LateralCue.LEFT, 0.5, LongitudinalCue.BRAKE, 0.25).toHardwareCommand()
        assertModule(ModuleCommand(0.5, 1.5, -0.5, 3.0), command.headrest)
        assertModule(ModuleCommand(0.5, 1.0, -0.5, 0.0), command.backrest)
        assertModule(ModuleCommand(0.5, 1.0, -0.375, 0.0), command.cushion)
    }

    @Test fun cornerAndAccelerateCombine() {
        val command = Hint(LateralCue.RIGHT, 0.5, LongitudinalCue.ACCELERATE, 0.5).toHardwareCommand()
        assertModule(ModuleCommand(0.5, -1.5, 1.0, -3.0), command.headrest)
    }

    @Test fun vibrationTakesTheStrongerAxis() {
        val command = Hint(LateralCue.LEFT, 0.25, LongitudinalCue.BRAKE, 0.75).toHardwareCommand()
        assertEquals(0.75, command.headrest.vibration, 1e-9)
        assertEquals(0.75, command.backrest.vibration, 1e-9)
        assertEquals(0.75, command.cushion.vibration, 1e-9)
    }

    @Test fun watchPrefersLateralEvenWhenWeaker() {
        val command = Hint(LateralCue.RIGHT, 0.25, LongitudinalCue.BRAKE, 0.75).toHardwareCommand()
        assertEquals(WatchPattern.RIGHT, command.watch)
        assertEquals(0.25, command.watchStrength, 1e-9)
    }

    @Test fun watchUsesLongitudinalStrengthWithoutLateral() {
        val command = Hint(longitudinal = LongitudinalCue.ACCELERATE, longitudinalStrength = 0.75).toHardwareCommand()
        assertEquals(0.75, command.watchStrength, 1e-9)
    }
}
