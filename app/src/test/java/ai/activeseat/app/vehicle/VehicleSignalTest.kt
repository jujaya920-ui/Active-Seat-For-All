package ai.activeseat.app.vehicle

import ai.activeseat.app.hardware.Occupancy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleSignalTest {
    @Test
    fun onlyAForwardGearRunsTheModels() {
        assertTrue(Gear.DRIVE.runsMotion())
        assertFalse(Gear.PARK.runsMotion())
        assertFalse(Gear.NEUTRAL.runsMotion())
        assertFalse(Gear.REVERSE.runsMotion())
    }

    @Test
    fun anUnreportedGearCountsAsDrive() {
        assertTrue(Gear.UNKNOWN.runsMotion())
    }

    @Test
    fun theDriverAloneIsNoPassenger() {
        assertFalse(mapOf(SeatPosition.ROW_1_LEFT to true, SeatPosition.ROW_1_RIGHT to false).hasPassenger())
    }

    @Test
    fun anyOccupiedPassengerSeatCounts() {
        assertTrue(mapOf(SeatPosition.ROW_1_RIGHT to false, SeatPosition.ROW_2_LEFT to true).hasPassenger())
    }

    @Test
    fun noPassengerSeatReportedIsAssumedOccupied() {
        assertTrue(emptyMap<SeatPosition, Boolean>().hasPassenger())
        assertTrue(mapOf(SeatPosition.ROW_1_LEFT to true).hasPassenger())
    }

    @Test
    fun theModelsRunOnlyInAForwardGearWithAPassenger() {
        val passenger = mapOf(SeatPosition.ROW_1_RIGHT to true)
        assertTrue(VehicleSignal(gear = Gear.DRIVE, seats = passenger).runsMotion())
        assertTrue(VehicleSignal(gear = Gear.UNKNOWN, seats = passenger).runsMotion())
        assertFalse(VehicleSignal(gear = Gear.PARK, seats = passenger).runsMotion())
        assertFalse(VehicleSignal(gear = Gear.DRIVE, seats = mapOf(SeatPosition.ROW_1_RIGHT to false)).runsMotion())
        assertTrue(VehicleSignal(gear = Gear.DRIVE).runsMotion())
    }

    @Test
    fun eachSeatRunsOnlyWithSomeoneInIt() {
        val leftOnly = mapOf(SeatPosition.ROW_1_RIGHT to true, SeatPosition.ROW_2_LEFT to true, SeatPosition.ROW_2_RIGHT to false)
        val drive = VehicleSignal(gear = Gear.DRIVE, seats = leftOnly)
        assertTrue(drive.runsMotionFor(SeatPosition.ROW_1_RIGHT))
        assertTrue(drive.runsMotionFor(SeatPosition.ROW_2_LEFT))
        assertFalse(drive.runsMotionFor(SeatPosition.ROW_2_RIGHT))
        assertFalse(drive.copy(gear = Gear.PARK).runsMotionFor(SeatPosition.ROW_2_LEFT))
    }

    @Test
    fun anUnreportedSeatCountsOnlyWhenNoSeatReported() {
        // The car reports some seats: one it never reported is not assumed taken.
        val someReported = mapOf(SeatPosition.ROW_1_RIGHT to true)
        assertFalse(someReported.isTaken(SeatPosition.ROW_2_RIGHT))
        assertEquals(Occupancy(passenger = true, rearLeft = false, rearRight = false), someReported.occupancy())
        // Nothing reported at all: the car cannot tell, so every seat counts.
        assertTrue(emptyMap<SeatPosition, Boolean>().isTaken(SeatPosition.ROW_2_RIGHT))
        assertEquals(Occupancy(), emptyMap<SeatPosition, Boolean>().occupancy())
    }

    @Test
    fun seatNamesMatchTheSdk() {
        val sdk = ai.pleos.playground.vehicle.constant.area.SeatValue.entries.map { it.name }.toSet()
        assertTrue(SeatPosition.entries.all { it.name in sdk })
    }
}
