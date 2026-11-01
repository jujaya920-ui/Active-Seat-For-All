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
}
