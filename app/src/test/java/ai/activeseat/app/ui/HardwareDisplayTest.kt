package ai.activeseat.app.ui

import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.hint.LongitudinalCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareDisplayTest {
    @Test
    fun `no cue reads as holding`() {
        assertEquals("유지", forecastText(Hint()))
        assertFalse(isForecastActive(Hint()))
    }

    @Test
    fun `corners read as turns`() {
        assertEquals("좌회전", forecastText(Hint(lateral = LateralCue.LEFT, lateralStrength = 0.4)))
        assertEquals("우회전", forecastText(Hint(lateral = LateralCue.RIGHT, lateralStrength = 1.0)))
    }

    @Test
    fun `longitudinal wording follows strength`() {
        assertEquals("감속", forecastText(Hint(longitudinal = LongitudinalCue.BRAKE, longitudinalStrength = 0.69)))
        assertEquals("급정거", forecastText(Hint(longitudinal = LongitudinalCue.BRAKE, longitudinalStrength = 0.7)))
        assertEquals("가속", forecastText(Hint(longitudinal = LongitudinalCue.ACCELERATE, longitudinalStrength = 0.4)))
        assertEquals("급출발", forecastText(Hint(longitudinal = LongitudinalCue.ACCELERATE, longitudinalStrength = 0.9)))
    }

    @Test
    fun `both axes join`() {
        val hint = Hint(LateralCue.RIGHT, 0.5, LongitudinalCue.BRAKE, 0.8)
        assertEquals("우회전 + 급정거", forecastText(hint))
        assertTrue(isForecastActive(hint))
    }

    @Test
    fun `strength is the stronger axis as a percentage`() {
        assertEquals(0, strengthPercent(Hint()))
        assertEquals(80, strengthPercent(Hint(LateralCue.RIGHT, 0.5, LongitudinalCue.BRAKE, 0.8)))
    }

    @Test
    fun `probability shows as a whole percentage`() {
        assertEquals("88%", percentText(0.876))
        assertEquals("0%", percentText(0.0))
    }
}
