package ai.activeseat.app.ui

import ai.activeseat.app.hardware.HardwareCommand
import ai.activeseat.app.hardware.ModuleCommand
import ai.activeseat.app.hardware.SeatCommand
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
        assertEquals(brake.passenger.cushion.pitchDeg.toFloat(), motion.pitchDeg, 0.01f)
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

    private fun leanLeft(leanSec: Double) = HardwareCommand(passenger = SeatCommand(cushion = ModuleCommand(rollDeg = 2.0), leanSec = leanSec))

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
            passenger = SeatCommand(
                headrest = ModuleCommand(vibration = Vibration(left = 1.0), rollDeg = 2.0, pitchDeg = 1.0, yawDeg = 3.0),
                watchStrength = 1.0,
            ),
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

    @Test
    fun `the rear motion follows the rear seat's own modules`() {
        val rear = SeatMotion { it.rearLeft }
        val left = Hint(lateral = LateralCue.LEFT, lateralStrength = 1.0, lateralMps2 = 2.0, wheelDeg = 16.0).toHardwareCommand()
        repeat(400) { rear.step(0.016f, left) }
        assertEquals(16f, rear.yaw[PART_HEADREST], 0.01f)
        assertEquals(0f, rear.yaw[PART_BACKREST], 0f)
        assertEquals(0f, rear.yaw[PART_CUSHION], 0f)
        assertTrue(rear.roll[PART_CUSHION] > 0)
        assertEquals(0f, rear.roll[PART_BACKREST], 0f)
    }

    private fun placed(tf: FloatArray, x: Float, y: Float, z: Float) = floatArrayOf(
        tf[0] * x + tf[1] * y + tf[2] * z + tf[9],
        tf[3] * x + tf[4] * y + tf[5] * z + tf[10],
        tf[6] * x + tf[7] * y + tf[8] * z + tf[11],
    )

    @Test
    fun `the passenger transform matches the whole-seat rotation`() {
        val roll = floatArrayOf(3f, 3f, 3f)
        val pitch = floatArrayOf(-2f, -2f, -2f)
        val yaw = floatArrayOf(10f, 10f, 10f)
        val tf = partTransform(SeatKind.PASSENGER, PART_HEADREST, roll, pitch, yaw, 1f)
        val r = seatRotation(3f, -2f, 10f)
        val z0 = -21f - SEAT_PIVOT_Z
        val expected = floatArrayOf(r[0] * 5 + r[1] * 80 + r[2] * z0, r[3] * 5 + r[4] * 80 + r[5] * z0, r[6] * 5 + r[7] * 80 + r[8] * z0 + SEAT_PIVOT_Z)
        val actual = placed(tf, 5f, 80f, -21f)
        for (i in 0..2) assertEquals(expected[i], actual[i], 1e-3f)
    }

    @Test
    fun `the rear headrest turns about its own axis and the cushion stays put`() {
        val yaw = floatArrayOf(0f, 0f, 20f)
        val zero = FloatArray(3)
        val head = partTransform(SeatKind.REAR, PART_HEADREST, zero, zero, yaw, 1f)
        val axis = placed(head, 0f, 80f, HEADREST_AXIS_Z)
        assertEquals(0f, axis[0], 1e-4f)
        assertEquals(HEADREST_AXIS_Z, axis[2], 1e-4f)
        // x is to the right, so the front of the headrest swings to -x for a left turn.
        assertTrue(placed(head, 0f, 80f, HEADREST_AXIS_Z + 5f)[0] < 0)
        val cushion = partTransform(SeatKind.REAR, PART_CUSHION, zero, zero, yaw, 1f)
        val p = placed(cushion, 10f, 0f, 30f)
        assertEquals(10f, p[0], 1e-4f)
        assertEquals(30f, p[2], 1e-4f)
    }

    @Test
    fun `the rear headrest rides on the backrest's pitch`() {
        val pitch = floatArrayOf(0f, 5f, 0f)
        val zero = FloatArray(3)
        val head = partTransform(SeatKind.REAR, PART_HEADREST, zero, pitch, zero, 1f)
        val back = partTransform(SeatKind.REAR, PART_BACKREST, zero, pitch, zero, 1f)
        val a = placed(head, 0f, 70f, -21f)
        val b = placed(back, 0f, 70f, -21f)
        for (i in 0..2) assertEquals(b[i], a[i], 1e-4f)
    }

    @Test
    fun `the rear row is two seats with a fixed console between them`() {
        val parts = REAR_ROW_SHAPE.parts
        val seats = parts.filter { it.role != PART_FIXED }
        assertEquals(listOf(PART_CUSHION, PART_BACKREST, PART_HEADREST, PART_CUSHION, PART_BACKREST, PART_HEADREST), seats.map { it.role })
        assertTrue(parts.any { it.role == PART_FIXED })
        assertEquals(listOf(-REAR_SEAT_X, REAR_SEAT_X), REAR_ROW_SHAPE.headrests.map { it.axisX })
        // Each cushion stays on its own side of the console.
        val cushions = seats.filter { it.role == PART_CUSHION }.map { c -> c.part.vertices.filterIndexed { i, _ -> i % 3 == 0 } }
        assertTrue(cushions[0].max() < -5f)
        assertTrue(cushions[1].min() > 5f)
    }

    @Test
    fun `each rear seat vibrates on its own left and right halves`() {
        for (shapePart in REAR_ROW_SHAPE.parts.filter { it.role != PART_FIXED }) {
            val d = shapePart.part.dots
            assertTrue(d.sideZone.any { it == ZONE_LEFT })
            assertTrue(d.sideZone.any { it == ZONE_RIGHT })
            assertTrue(d.sideReach.all { it in 0f..1f })
        }
    }

    @Test
    fun `each rear cushion rolls about its own centre`() {
        val roll = floatArrayOf(10f, 0f, 0f)
        val zero = FloatArray(3)
        for (cushion in REAR_ROW_SHAPE.parts.filter { it.role == PART_CUSHION }) {
            val tf = partTransform(SeatKind.REAR, PART_CUSHION, roll, zero, zero, 1f, cushion.axisX, cushion.axisZ, cushion.pivotX)
            val centre = placed(tf, cushion.pivotX, 0f, 20f)
            assertEquals(cushion.pivotX, centre[0], 1e-3f)
            assertEquals(0f, centre[1], 1e-3f)
        }
    }

    @Test
    fun `a rear headrest turns about its own seat`() {
        val yaw = floatArrayOf(0f, 0f, 20f)
        val zero = FloatArray(3)
        for (head in REAR_ROW_SHAPE.headrests) {
            val tf = partTransform(SeatKind.REAR, PART_HEADREST, zero, zero, yaw, 1f, head.axisX, head.axisZ, head.pivotX)
            val axis = placed(tf, head.axisX, 75f, head.axisZ)
            assertEquals(head.axisX, axis[0], 1e-3f)
            assertEquals(head.axisZ, axis[2], 1e-3f)
        }
    }
}
