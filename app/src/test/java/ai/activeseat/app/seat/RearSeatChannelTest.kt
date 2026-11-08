package ai.activeseat.app.seat

import ai.activeseat.app.hardware.HardwareSettings
import ai.activeseat.app.hint.Hint
import ai.activeseat.app.hint.LateralCue
import ai.activeseat.app.motion.NavSnapshot
import ai.activeseat.app.ui.CabinSeat
import ai.activeseat.app.ui.SeatOccupancy
import ai.activeseat.app.ui.firstOccupiedSeat
import ai.activeseat.app.ui.occupancy
import ai.activeseat.app.ui.seatStatusText
import ai.activeseat.app.vehicle.Gear
import ai.activeseat.app.vehicle.SeatPosition
import ai.activeseat.app.vehicle.VehicleSignal
import ai.activeseat.app.vehicle.runsMotionFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

class RearSeatChannelTest {
    private class MemoryStore(var value: HardwareSettings = HardwareSettings()) : SeatSettingsStore {
        override fun load() = value
        override fun save(settings: HardwareSettings) {
            value = settings
        }
    }

    private fun channel(position: SeatPosition, store: SeatSettingsStore = MemoryStore()) =
        RearSeatChannel(position, store, CoroutineScope(Dispatchers.Unconfined))

    private fun cornering(seats: Map<SeatPosition, Boolean>) =
        VehicleSignal(speedKph = 40.0, steeringDeg = 60.0, gear = Gear.DRIVE, seats = seats)

    @Test
    fun `a rear seat gets a hint only when someone sits in that very seat`() {
        val left = channel(SeatPosition.ROW_2_LEFT)
        val right = channel(SeatPosition.ROW_2_RIGHT)
        val signal = cornering(mapOf(SeatPosition.ROW_2_LEFT to true, SeatPosition.ROW_2_RIGHT to false))
        left.step(signal, 1000, NavSnapshot())
        right.step(signal, 1000, NavSnapshot())
        assertEquals(LateralCue.LEFT, left.state.value.forecast.lateral)
        assertEquals(LateralCue.NONE, right.state.value.forecast.lateral)
    }

    @Test
    fun `settings change and save for one seat only`() {
        val leftStore = MemoryStore()
        val rightStore = MemoryStore()
        val left = channel(SeatPosition.ROW_2_LEFT, leftStore)
        val right = channel(SeatPosition.ROW_2_RIGHT, rightStore)
        left.updateSettings(HardwareSettings(seatSensitivity = 5))
        assertEquals(5, leftStore.value.seatSensitivity)
        assertEquals(3, right.state.value.settings.seatSensitivity)
        assertEquals(3, rightStore.value.seatSensitivity)
    }

    @Test
    fun `a saved setting comes back`() {
        val left = channel(SeatPosition.ROW_2_LEFT, MemoryStore(HardwareSettings(watchVibration = 1)))
        assertEquals(1, left.state.value.settings.watchVibration)
    }

    @Test
    fun `sensitivity off means no hint for that seat`() {
        val left = channel(SeatPosition.ROW_2_LEFT, MemoryStore(HardwareSettings(seatSensitivity = 0, watchSensitivity = 0)))
        left.step(cornering(mapOf(SeatPosition.ROW_2_LEFT to true)), 1000, NavSnapshot())
        assertEquals(LateralCue.NONE, left.state.value.forecast.lateral)
    }

    @Test
    fun `the app opens on the first seat someone is in`() {
        assertEquals(CabinSeat.REAR_RIGHT, firstOccupiedSeat(mapOf(SeatPosition.ROW_1_RIGHT to false, SeatPosition.ROW_2_RIGHT to true)))
    }

    @Test
    fun `with nobody aboard the app opens on the passenger seat`() {
        assertEquals(CabinSeat.PASSENGER, firstOccupiedSeat(emptyMap()))
        assertEquals(CabinSeat.PASSENGER, firstOccupiedSeat(mapOf(SeatPosition.ROW_2_LEFT to false)))
    }

    @Test
    fun `a seat that never reported is unconfirmed, not empty`() {
        assertEquals(SeatOccupancy.UNCONFIRMED, CabinSeat.REAR_LEFT.occupancy(mapOf(SeatPosition.ROW_1_RIGHT to true)))
        assertEquals(SeatOccupancy.EMPTY, CabinSeat.REAR_LEFT.occupancy(mapOf(SeatPosition.ROW_2_LEFT to false)))
    }

    @Test
    fun `the passenger seat follows only its own occupancy`() {
        val rearOnly = cornering(mapOf(SeatPosition.ROW_1_RIGHT to false, SeatPosition.ROW_2_LEFT to true))
        assertEquals(false, rearOnly.runsMotionFor(CabinSeat.PASSENGER.position))
        assertEquals(true, rearOnly.runsMotionFor(CabinSeat.REAR_LEFT.position))
    }

    @Test
    fun `with no passenger seat reported every seat is assumed taken`() {
        assertEquals(true, cornering(emptyMap()).runsMotionFor(CabinSeat.REAR_RIGHT.position))
    }

    @Test
    fun `an empty seat titles itself instead of reading as a calm drive`() {
        assertEquals("미탑승", seatStatusText(Hint(), SeatOccupancy.EMPTY))
        assertEquals("유지", seatStatusText(Hint(), SeatOccupancy.OCCUPIED))
        assertEquals("좌회전", seatStatusText(Hint(lateral = LateralCue.LEFT, lateralStrength = 0.5), SeatOccupancy.EMPTY))
    }
}
