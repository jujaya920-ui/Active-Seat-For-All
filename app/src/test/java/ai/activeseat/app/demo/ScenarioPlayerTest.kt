package ai.activeseat.app.demo

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioPlayerTest {
    private class FakeConsole(val now: () -> Long) : ConsoleApi {
        val sessions = mutableListOf<Long>()
        val controls = mutableListOf<Pair<Long, ConsoleControls>>()
        val turns = mutableListOf<String>()
        val gears = mutableListOf<String>()
        val seats = mutableListOf<Pair<String, Boolean>>()
        var failControls = false

        override suspend fun session() { sessions += now() }
        override suspend fun controls(controls: ConsoleControls) {
            if (failControls) throw IOException("down")
            this.controls += now() to controls
        }
        override suspend fun turnSignal(mode: String) { turns += mode }
        override suspend fun gear(gear: String) { gears += gear }
        override suspend fun seatOccupied(area: String, occupied: Boolean) { seats += area to occupied }
    }

    private fun scenario() = Scenario(
        id = "t", title = "t", mapImage = "", routePx = emptyList(), routeLengthM = 0.0,
        turns = emptyList(), frontEvents = emptyList(),
        timeline = listOf(
            ScenarioFrame(0.0, 10.0, 30.0, 0.0, 5.0, "BlinkLeft"),
            ScenarioFrame(0.2, 20.0, 30.0, 0.0, 5.0, "BlinkLeft"),
            ScenarioFrame(0.4, 30.0, -50.0, 0.0, 5.0, "Off"),
            ScenarioFrame(0.6, 30.0, -50.0, 40.0, 0.0, "BlinkRight"),
        ),
    )

    private fun TestScope.console() = FakeConsole { currentTime }

    @Test
    fun playsAtFiveHzWithNegatedSteeringThenResets() = runTest {
        val console = console()
        val player = ScenarioPlayer(console, { currentTime })

        player.play(scenario())

        val zero = ConsoleControls(0, 0, 0, 0)
        assertEquals(
            listOf(
                BRIDGE_WARMUP_MS to ConsoleControls(10, 5, 0, -30),
                BRIDGE_WARMUP_MS + 200 to ConsoleControls(20, 5, 0, -30),
                BRIDGE_WARMUP_MS + 400 to ConsoleControls(30, 5, 0, 50),
                BRIDGE_WARMUP_MS + 600 to ConsoleControls(30, 0, 40, 50),
                BRIDGE_WARMUP_MS + 800 to zero,
            ),
            console.controls,
        )
        assertEquals(listOf("BlinkLeft", "Off", "BlinkRight", "Off"), console.turns)
        assertEquals(listOf("Drive"), console.gears)
        assertEquals(PlayerState.Finished(0), player.state.value)
        assertTrue("no occupiedSeats leaves the console alone", console.seats.isEmpty())
    }

    @Test
    fun seatsTheScenarioRidersAndEmptiesTheRest() = runTest {
        val console = console()

        ScenarioPlayer(console, { currentTime }).play(scenario().copy(occupiedSeats = listOf("driver", "passenger")))

        assertEquals(
            listOf("driver" to true, "passenger" to true, "rear_left" to false, "rear_right" to false),
            console.seats,
        )
    }

    @Test
    fun heartbeatStaysUnderOneSecondUntilResetSettles() = runTest {
        val console = console()

        ScenarioPlayer(console, { currentTime }).play(scenario())

        assertEquals(0L, console.sessions.first())
        assertTrue(console.sessions.zipWithNext().all { (a, b) -> b - a <= 1000 })
        val resetAt = console.controls.last().first
        assertTrue(console.sessions.last() + HEARTBEAT_INTERVAL_MS >= resetAt + RESET_SETTLE_MS)
    }

    @Test
    fun cancellationStillResetsConsole() = runTest {
        val console = console()
        val player = ScenarioPlayer(console, { currentTime })

        val job = launch { player.play(scenario()) }
        delay(BRIDGE_WARMUP_MS + 250)
        job.cancelAndJoin()

        assertEquals(ConsoleControls(0, 0, 0, 0), console.controls.last().second)
        assertEquals("Off", console.turns.last())
        assertEquals(PlayerState.Finished(0), player.state.value)
    }

    @Test
    fun failedWritesAreCountedAndDoNotStopPlayback() = runTest {
        val console = console().apply { failControls = true }
        val player = ScenarioPlayer(console, { currentTime })

        player.play(scenario())

        assertEquals(PlayerState.Finished(5), player.state.value)
    }
}
