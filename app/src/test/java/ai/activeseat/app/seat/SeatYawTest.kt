package ai.activeseat.app.seat

import ai.activeseat.app.vehicle.SeatPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class SeatYawTest {
    private fun yaw(leftSign: Double, mps2: Double, kph: Double, position: SeatPosition) = yawDeg(leftSign, mps2, YawContext(kph, position))

    private val expected36 = Math.toDegrees(1.5 / 10.0 * 2.2)

    @Test
    fun `the seat faces where the car heads after the look-ahead`() {
        assertEquals(expected36, yaw(1.0, 1.5, 36.0, SeatPosition.ROW_1_RIGHT), 1e-9)
        assertEquals(-expected36, yaw(-1.0, 1.5, 36.0, SeatPosition.ROW_1_RIGHT), 1e-9)
    }

    @Test
    fun `at twice the speed the same push turns the seat half as far`() {
        assertEquals(expected36 / 2, yaw(1.0, 1.5, 72.0, SeatPosition.ROW_1_RIGHT), 1e-9)
    }

    @Test
    fun `a rear seat turns half as far toward its door`() {
        assertEquals(expected36 / 2, yaw(1.0, 1.5, 36.0, SeatPosition.ROW_2_LEFT), 1e-9)
        assertEquals(-expected36, yaw(-1.0, 1.5, 36.0, SeatPosition.ROW_2_LEFT), 1e-9)
        assertEquals(expected36, yaw(1.0, 1.5, 36.0, SeatPosition.ROW_2_RIGHT), 1e-9)
        assertEquals(-expected36 / 2, yaw(-1.0, 1.5, 36.0, SeatPosition.ROW_2_RIGHT), 1e-9)
    }

    @Test
    fun `a stopped car does not spin the seat past the 18 kmh rate`() {
        assertEquals(yaw(1.0, 1.0, 18.0, SeatPosition.ROW_1_RIGHT), yaw(1.0, 1.0, 0.0, SeatPosition.ROW_1_RIGHT), 1e-9)
    }

    @Test
    fun `the turn stops at its limit`() {
        assertEquals(MAX_YAW_DEG, yaw(1.0, 9.0, 36.0, SeatPosition.ROW_1_RIGHT), 1e-9)
    }

    @Test
    fun `the door side stays smaller even at the limit`() {
        assertEquals(MAX_YAW_DEG / 2, yaw(1.0, 9.0, 36.0, SeatPosition.ROW_2_LEFT), 1e-9)
        assertEquals(-MAX_YAW_DEG, yaw(-1.0, 9.0, 36.0, SeatPosition.ROW_2_LEFT), 1e-9)
    }

    @Test
    fun `no turn means no yaw`() {
        assertEquals(0.0, yaw(0.0, 0.0, 36.0, SeatPosition.ROW_2_LEFT), 1e-9)
    }
}
