package ai.activeseat.app.ui

import ai.activeseat.app.hardware.ModuleCommand
import ai.activeseat.app.hardware.WatchPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareDisplayTest {
    @Test
    fun `lateral arrow follows positive left sign and ignores small values`() {
        assertEquals(LateralArrow.LEFT, lateralArrow(3f))
        assertEquals(LateralArrow.RIGHT, lateralArrow(-3f))
        assertEquals(LateralArrow.NONE, lateralArrow(0.2f))
        assertEquals(LateralArrow.NONE, lateralArrow(-0.2f))
    }

    @Test
    fun `pitch arrow follows positive forward sign and ignores small values`() {
        assertEquals(PitchArrow.FORWARD, pitchArrow(2f))
        assertEquals(PitchArrow.BACK, pitchArrow(-2f))
        assertEquals(PitchArrow.NONE, pitchArrow(0.2f))
    }

    @Test
    fun `left leans counterclockwise and forward leans clockwise at four times real angle`() {
        assertEquals(-12f, screenLateralDeg(3f), 0f)
        assertEquals(8f, screenPitchDeg(2f), 0f)
    }

    @Test
    fun `module is active on movement or vibration only`() {
        assertFalse(isModuleActive(ModuleCommand()))
        assertFalse(isModuleActive(ModuleCommand(rollDeg = 0.1, pitchDeg = -0.1, yawDeg = 0.1)))
        assertTrue(isModuleActive(ModuleCommand(vibration = 0.3)))
        assertTrue(isModuleActive(ModuleCommand(yawDeg = -6.0)))
    }

    @Test
    fun `text shows the real value with its direction`() {
        assertEquals("왼쪽 3.0°", lateralText(3.0))
        assertEquals("오른쪽 2.0°", lateralText(-2.0))
        assertEquals("0.0°", lateralText(0.0))
        assertEquals("앞으로 2.0°", pitchText(2.0))
        assertEquals("뒤로 1.5°", pitchText(-1.5))
    }

    @Test
    fun `watch pattern text names each pattern`() {
        assertEquals("왼쪽", watchPatternText(WatchPattern.LEFT))
        assertEquals("오른쪽", watchPatternText(WatchPattern.RIGHT))
        assertEquals("제동", watchPatternText(WatchPattern.BRAKE))
        assertEquals("가속", watchPatternText(WatchPattern.ACCELERATE))
    }

    @Test
    fun `watch pulses follow pattern with gap then repeat after rest`() {
        val pulses = WatchPattern.LEFT.pulsesMs // 120, gap 150, 120, rest 800: cycle 1190
        assertTrue(isWatchPulseOn(pulses, 0))
        assertTrue(isWatchPulseOn(pulses, 119))
        assertFalse(isWatchPulseOn(pulses, 120))
        assertFalse(isWatchPulseOn(pulses, 269))
        assertTrue(isWatchPulseOn(pulses, 270))
        assertFalse(isWatchPulseOn(pulses, 390))
        assertFalse(isWatchPulseOn(pulses, 1189))
        assertTrue(isWatchPulseOn(pulses, 1190))
    }

    @Test
    fun `no pulses for none pattern`() {
        assertFalse(isWatchPulseOn(WatchPattern.NONE.pulsesMs, 0))
    }
}
