package ai.activeseat.app.vehicle

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
    fun seatNamesMatchTheSdk() {
        val sdk = ai.pleos.playground.vehicle.constant.area.SeatValue.entries.map { it.name }.toSet()
        assertTrue(SeatPosition.entries.all { it.name in sdk })
    }
}
