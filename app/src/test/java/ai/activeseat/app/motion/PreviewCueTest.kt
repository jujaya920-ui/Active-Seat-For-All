package ai.activeseat.app.motion

import ai.activeseat.app.hardware.HardwareSettings
import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.hint.LongitudinalCue
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewCueTest {
    private fun config(sensitivity: Int) = MotionConfig().scaled(HardwareSettings.thresholdScale(sensitivity)!!)

    private fun hint(maneuver: PreviewManeuver, sensitivity: Int) = config(sensitivity).previewHint(maneuver)

    @Test fun scaledMultipliesEveryAccelerationThreshold() {
        val scaled = MotionConfig().scaled(2.0)
        assertEquals(1.6, scaled.lateralOnMps2, 1e-9)
        assertEquals(2.0, scaled.brakeOnMps2, 1e-9)
        assertEquals(2.0, scaled.accelOnMps2, 1e-9)
        assertEquals(2.6, scaled.requiredDecelMps2, 1e-9)
        assertEquals(listOf(3.0, 5.0, 8.0), scaled.levelBoundsMps2)
    }

    @Test fun scaledLeavesEverythingElse() {
        val base = MotionConfig()
        val scaled = base.scaled(0.5)
        assertEquals(
            base,
            scaled.copy(
                lateralOnMps2 = base.lateralOnMps2,
                brakeOnMps2 = base.brakeOnMps2,
                accelOnMps2 = base.accelOnMps2,
                requiredDecelMps2 = base.requiredDecelMps2,
                levelBoundsMps2 = base.levelBoundsMps2,
            ),
        )
    }

    @Test fun defaultSensitivityKeepsTheConfig() = assertEquals(MotionConfig(), config(3))

    @Test fun gentleLeftTurnShowsTheSensitivity() {
        assertEquals(Hint(), hint(PreviewManeuver.LEFT, 1))
        assertEquals(Hint(lateral = LateralCue.LEFT, lateralStrength = 0.25), hint(PreviewManeuver.LEFT, 3))
        assertEquals(Hint(lateral = LateralCue.LEFT, lateralStrength = 0.5), hint(PreviewManeuver.LEFT, 5))
    }

    @Test fun firmerRightTurnShowsTheSensitivity() {
        assertEquals(Hint(lateral = LateralCue.RIGHT, lateralStrength = 0.25), hint(PreviewManeuver.RIGHT, 1))
        assertEquals(Hint(lateral = LateralCue.RIGHT, lateralStrength = 0.5), hint(PreviewManeuver.RIGHT, 3))
        assertEquals(Hint(lateral = LateralCue.RIGHT, lateralStrength = 1.0), hint(PreviewManeuver.RIGHT, 5))
    }

    @Test fun brakeShowsTheSensitivity() {
        assertEquals(Hint(), hint(PreviewManeuver.BRAKE, 1))
        assertEquals(Hint(longitudinal = LongitudinalCue.BRAKE, longitudinalStrength = 0.25), hint(PreviewManeuver.BRAKE, 3))
        assertEquals(Hint(longitudinal = LongitudinalCue.BRAKE, longitudinalStrength = 0.75), hint(PreviewManeuver.BRAKE, 5))
    }

    @Test fun onThresholdItselfTurnsTheCueOn() {
        val config = MotionConfig(lateralOnMps2 = 1.2)
        assertEquals(Hint(lateral = LateralCue.LEFT, lateralStrength = 0.25), config.previewHint(PreviewManeuver.LEFT))
    }

    @Test fun levelStopsAtFour() {
        val config = MotionConfig(lateralOnMps2 = 0.1, levelBoundsMps2 = listOf(0.2, 0.3, 0.4, 0.5))
        assertEquals(1.0, config.previewHint(PreviewManeuver.RIGHT).lateralStrength, 1e-9)
    }
}
