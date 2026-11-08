package ai.activeseat.app.ui

import ai.activeseat.app.hardware.HardwareCommand
import ai.activeseat.app.hardware.ModuleCommand
import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.hint.LongitudinalCue
import ai.activeseat.app.hardware.Vibration
import ai.activeseat.app.hardware.toHardwareCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeatModelTest {
    private val out = FloatArray(2)

    private fun screenX(camera: SeatCamera, rotation: FloatArray, x: Float, y: Float, z: Float): Float {
        val z0 = z - SEAT_PIVOT_Z
        camera.project(
            rotation[0] * x + rotation[1] * y + rotation[2] * z0,
            rotation[3] * x + rotation[4] * y + rotation[5] * z0,
            rotation[6] * x + rotation[7] * y + rotation[8] * z0 + SEAT_PIVOT_Z,
            out,
        )
        return out[0]
    }

    private fun shift(rollDeg: Float, yawDeg: Float, x: Float, y: Float, z: Float): Float =
        screenX(SeatCamera.FRONT, seatRotation(rollDeg, 0f, yawDeg), x, y, z) - screenX(SeatCamera.FRONT, seatRotation(0f, 0f, 0f), x, y, z)

    @Test
    fun `front view faces the passenger so their left is the screen's right`() {
        val rest = seatRotation(0f, 0f, 0f)
        assertTrue(screenX(SeatCamera.FRONT, rest, -20f, 0f, 45f) > screenX(SeatCamera.FRONT, rest, 20f, 0f, 45f))
    }

    @Test
    fun `a left turn moves every height of the seat the same way on screen`() {
        val roll = 3 * DISPLAY_SCALE
        val yaw = 6 * DISPLAY_SCALE
        assertTrue(shift(roll, yaw, 0f, 94f, -21f) > 0)
        assertTrue(shift(roll, yaw, 0f, 50f, -20f) > 0)
        assertTrue(shift(roll, yaw, 0f, -4f, 48f) > 0)
    }

    @Test
    fun `a right turn mirrors a left turn`() {
        val roll = 3 * DISPLAY_SCALE
        val yaw = 6 * DISPLAY_SCALE
        assertEquals(shift(roll, yaw, 0f, 94f, -21f), -shift(-roll, -yaw, 0f, 94f, -21f), 0.01f)
    }

    @Test
    fun `side view shows forward on the right`() {
        val rest = seatRotation(0f, 0f, 0f)
        assertTrue(screenX(SeatCamera.SIDE, rest, 0f, 0f, 45f) > screenX(SeatCamera.SIDE, rest, 0f, 0f, -20f))
    }

    @Test
    fun `leaning forward moves the top of the seat forward in the side view`() {
        val tilted = seatRotation(0f, 2 * DISPLAY_SCALE, 0f)
        val rest = seatRotation(0f, 0f, 0f)
        assertTrue(screenX(SeatCamera.SIDE, tilted, 0f, 90f, -21f) > screenX(SeatCamera.SIDE, rest, 0f, 90f, -21f))
    }

    @Test
    fun `every part has dots in both side zones and the seat stays in view at rest`() {
        for (part in SEAT_PARTS) {
            assertTrue(part.dots.sideZone.any { it == ZONE_LEFT })
            assertTrue(part.dots.sideZone.any { it == ZONE_RIGHT })
            assertTrue(part.dots.lengthZone.any { it == ZONE_FRONT })
            assertTrue(part.dots.lengthZone.any { it == ZONE_BACK })
            assertTrue(part.dots.sideReach.all { it in 0f..1f })
        }
    }

    @Test
    fun `a swell reaches the centre before the edge`() {
        assertTrue(swell(1f, 0.1f, 0f) >= 0)
        assertEquals(-1f, swell(1f, 0.1f, 1f), 0f)
        assertTrue(swell(1f, 0.7f, 1f) >= 0)
    }

    @Test
    fun `no swell without vibration`() = assertEquals(-1f, swell(0f, 0.5f, 0f), 0f)

    @Test
    fun `motion eases to the command and only the cushion gets front and rear vibration`() {
        val motion = SeatMotion()
        val brake = Hint(longitudinal = LongitudinalCue.BRAKE, longitudinalStrength = 1.0, longitudinalMps2 = 4.0)
            .toHardwareCommand()
        repeat(400) { motion.step(0.016f, brake) }
        assertEquals(brake.headrest.pitchDeg.toFloat(), motion.pitchDeg, 0.01f)
        assertTrue(motion.pitchDeg < 0)
        assertEquals(1f, motion.vibration[PART_CUSHION][ZONE_BACK], 0.01f)
        assertEquals(0f, motion.vibration[PART_BACKREST][ZONE_BACK], 0f)
        assertEquals(0f, motion.vibration[PART_HEADREST][ZONE_BACK], 0f)
    }

    @Test
    fun `a settled idle seat stops asking for frames`() {
        val motion = SeatMotion()
        assertFalse(motion.step(0.016f, HardwareCommand()))
    }

    @Test
    fun `vibration restarts from the centre for each event`() {
        val motion = SeatMotion()
        val left = Hint(lateral = LateralCue.LEFT, lateralStrength = 1.0).toHardwareCommand()
        repeat(100) { motion.step(0.016f, left) }
        assertTrue(motion.phase[PART_BACKREST][ZONE_LEFT] > 0)
        repeat(300) { motion.step(0.016f, HardwareCommand()) }
        assertEquals(0f, motion.phase[PART_BACKREST][ZONE_LEFT], 0f)
    }

    private fun leanLeft(leanSec: Double) = HardwareCommand(headrest = ModuleCommand(rollDeg = 2.0), leanSec = leanSec)

    @Test
    fun `the lean reaches 95 percent at its lean time without overshooting`() {
        for (leanSec in doubleArrayOf(0.5, 2.0, 8.0)) {
            val motion = SeatMotion()
            val steps = Math.round(leanSec / 0.01).toInt()
            repeat(steps) { motion.step(0.01f, leanLeft(leanSec)) }
            assertTrue("$leanSec s: ${motion.rollDeg}", motion.rollDeg >= 2f * 0.95f)
            assertTrue("$leanSec s: ${motion.rollDeg}", motion.rollDeg <= 2f)
        }
    }

    @Test
    fun `the lean starts slowly`() {
        val motion = SeatMotion()
        repeat(50) { motion.step(0.01f, leanLeft(2.0)) }
        // A single first-order lag with the same 95% time would be past half way here; four stages are near 14%.
        assertTrue(motion.rollDeg < 2f * 0.25f)
        assertTrue(motion.rollDeg > 0f)
    }

    @Test
    fun `a long frame does not overshoot the lean`() {
        val motion = SeatMotion()
        motion.step(0.25f, leanLeft(0.5))
        motion.step(0.25f, leanLeft(0.5))
        assertTrue(motion.rollDeg <= 2f)
    }

    @Test
    fun `the seat keeps asking for frames while the lean is still settling`() {
        val motion = SeatMotion()
        repeat(150) { motion.step(0.01f, leanLeft(2.0)) }
        assertTrue(motion.step(0.01f, leanLeft(2.0)))
    }

    @Test
    fun `reset puts everything back at rest at once`() {
        val motion = SeatMotion()
        val busy = HardwareCommand(
            headrest = ModuleCommand(vibration = Vibration(left = 1.0), rollDeg = 2.0, pitchDeg = 1.0, yawDeg = 3.0),
            watchStrength = 1.0,
        )
        repeat(100) { motion.step(0.016f, busy) }
        motion.reset()
        assertEquals(0f, motion.rollDeg, 0f)
        assertEquals(0f, motion.pitchDeg, 0f)
        assertEquals(0f, motion.yawDeg, 0f)
        assertEquals(0f, motion.watch, 0f)
        assertEquals(0f, motion.vibration[PART_HEADREST][ZONE_LEFT], 0f)
        assertEquals(0f, motion.phase[PART_HEADREST][ZONE_LEFT], 0f)
        assertFalse(motion.step(0.016f, HardwareCommand()))
    }
}
