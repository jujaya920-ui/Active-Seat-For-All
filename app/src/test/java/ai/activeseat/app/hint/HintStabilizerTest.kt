package ai.activeseat.app.hint

import ai.activeseat.app.prediction.PredictResult
import ai.activeseat.app.prediction.PushForecast
import ai.activeseat.app.prediction.PushPrediction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HintStabilizerTest {
    private var now = 0L
    private val stabilizer = HintStabilizer(HintConfig(), { now })

    private val quiet = PushPrediction(0.05, 1, 1.0)

    private fun push(pOn: Double, level: Int = 2) = PushPrediction(pOn, level, level.toDouble())

    private fun forecast(
        forward: PushPrediction = quiet,
        backward: PushPrediction = quiet,
        left: PushPrediction = quiet,
        right: PushPrediction = quiet,
    ) = PushForecast(2.0, forward, backward, left, right)

    private fun success(forecast: PushForecast, rttMs: Long = 300) = PredictResult.Success(rttMs, 0, forecast)

    /** Sends a request at `now`, answers it after `rttMs`, then waits until the apply moment and ticks. */
    private fun applied(forecast: PushForecast, rttMs: Long = 300): Hint {
        val start = now
        now += rttMs
        stabilizer.onResult(success(forecast, rttMs), start)
        now = maxOf(now, start + 1000)
        return stabilizer.tick()
    }

    private fun brake(pOn: Double, level: Int = 2) = applied(forecast(forward = push(pOn, level)))

    // Thresholds.

    @Test fun quietForecastStaysOff() = assertEquals(Hint(), applied(forecast()))

    @Test fun staysOffJustBelowOnThreshold() = assertEquals(LongitudinalCue.NONE, brake(0.549).longitudinal)

    @Test fun turnsOnAtOnThreshold() = assertEquals(LongitudinalCue.BRAKE, brake(0.55).longitudinal)

    @Test fun hysteresisBandDoesNotTurnOn() = assertEquals(LongitudinalCue.NONE, brake(0.5).longitudinal)

    @Test fun hysteresisBandKeepsCueOn() {
        brake(0.8)
        assertEquals(LongitudinalCue.BRAKE, brake(0.45).longitudinal)
    }

    @Test fun releasesBelowOffThresholdAfterHold() {
        brake(0.8)
        // Applied exactly 1000 ms after turning on, so the hold has run out.
        assertEquals(Hint(), brake(0.449))
    }

    @Test fun levelFollowsEachResultWhileOn() {
        brake(0.8, level = 1)
        assertEquals(0.75, brake(0.5, level = 3).longitudinalStrength, 1e-9)
    }

    // Minimum hold.

    @Test fun minHoldKeepsCueAndLevel() {
        val config = HintConfig(minHoldMs = 1500)
        val held = HintStabilizer(config, { now })
        held.onResult(success(forecast(forward = push(0.8, level = 3))), now)
        now += 1000
        val on = held.tick()
        held.onResult(success(forecast()), now)
        now += 1000
        // Applied 1000 ms after turning on, inside the 1500 ms hold.
        assertEquals(on, held.tick())
        held.onResult(success(forecast()), now)
        now += 1000
        assertEquals(Hint(), held.tick())
    }

    // Timing.

    @Test fun appliesAtRequestStartPlusOneSecondNotEarlier() {
        val start = now
        now += 200
        assertEquals(Hint(), stabilizer.onResult(success(forecast(forward = push(0.9)), 200), start))
        assertEquals(start + 1000, stabilizer.pendingApplyAtMs())
        now = start + 999
        assertEquals(Hint(), stabilizer.tick())
        now = start + 1000
        assertEquals(LongitudinalCue.BRAKE, stabilizer.tick().longitudinal)
        assertNull(stabilizer.pendingApplyAtMs())
    }

    @Test fun lateTickStillApplies() {
        val start = now
        stabilizer.onResult(success(forecast(forward = push(0.9))), start)
        now = start + 1150
        assertEquals(LongitudinalCue.BRAKE, stabilizer.tick().longitudinal)
    }

    @Test fun dueOlderResultIsAppliedBeforeTheNextArrives() {
        val first = now
        stabilizer.onResult(success(forecast(forward = push(0.9))), first)
        // No tick between: the next answer arrives after the first one's apply moment.
        now = first + 1100
        stabilizer.onResult(success(forecast()), first + 1000)
        assertEquals(LongitudinalCue.BRAKE, stabilizer.tick().longitudinal)
    }

    @Test fun roundTripAtLimitIsApplied() {
        assertEquals(LongitudinalCue.BRAKE, applied(forecast(forward = push(0.9)), rttMs = 1000).longitudinal)
    }

    @Test fun roundTripOverLimitIsDropped() {
        assertEquals(Hint(), applied(forecast(forward = push(0.9)), rttMs = 1001))
        assertNull(stabilizer.pendingApplyAtMs())
    }

    @Test fun timeBeforeTheCallCountsTowardTheLimit() {
        // The call itself reports 300 ms, but the window was read 1200 ms ago.
        val start = now
        now += 1200
        stabilizer.onResult(success(forecast(forward = push(0.9)), rttMs = 300), start)
        assertEquals(Hint(), stabilizer.tick())
    }

    @Test fun droppedResultDoesNotTurnOff() {
        val on = brake(0.9)
        assertEquals(on, applied(forecast(), rttMs = 1500))
    }

    @Test fun holdsUntilStale() {
        val on = brake(0.9)
        now += 2000
        assertEquals(on, stabilizer.tick())
        now += 1
        assertEquals(Hint(), stabilizer.tick())
    }

    @Test fun staleOffIgnoresMinHold() {
        val longHold = HintStabilizer(HintConfig(minHoldMs = 10_000), { now })
        longHold.onResult(success(forecast(forward = push(0.9))), now)
        now += 1000
        assertEquals(LongitudinalCue.BRAKE, longHold.tick().longitudinal)
        now += 2001
        assertEquals(Hint(), longHold.tick())
    }

    @Test fun tickBeforeAnyResultIsOff() {
        now = 100_000
        assertEquals(Hint(), stabilizer.tick())
    }

    @Test fun failureTurnsOffAtOnceAndDropsPending() {
        brake(0.9)
        stabilizer.onResult(success(forecast(forward = push(0.9))), now)
        now += 1
        assertEquals(Hint(), stabilizer.onResult(PredictResult.Failure("down"), now))
        assertNull(stabilizer.pendingApplyAtMs())
        now += 1000
        assertEquals(Hint(), stabilizer.tick())
    }

    @Test fun nullTurnsOffAtOnce() {
        brake(0.9)
        now += 1
        assertEquals(Hint(), stabilizer.onResult(null, now))
    }

    @Test fun notConfiguredTurnsOffAtOnce() {
        brake(0.9)
        now += 1
        assertEquals(Hint(), stabilizer.onResult(PredictResult.NotConfigured, now))
    }

    @Test fun cueReturnsAfterFailure() {
        brake(0.9)
        stabilizer.onResult(PredictResult.Failure("down"), now)
        assertEquals(LongitudinalCue.BRAKE, brake(0.6).longitudinal)
    }

    // Conflicts and mapping.

    @Test fun higherProbabilityWinsAnAxisOverHigherLevel() {
        // Jev often raises both sides of a turn; the real side ranks higher.
        val hint = applied(forecast(left = push(0.6, level = 3), right = push(0.8, level = 2)))
        assertEquals(LateralCue.LEFT, hint.lateral)
        assertEquals(0.5, hint.lateralStrength, 1e-9)
    }

    @Test fun equalProbabilityFallsBackToLevel() {
        val hint = applied(forecast(forward = push(0.9, level = 2), backward = push(0.9, level = 3)))
        assertEquals(LongitudinalCue.ACCELERATE, hint.longitudinal)
        assertEquals(0.75, hint.longitudinalStrength, 1e-9)
    }

    @Test fun fullTieCancelsTheAxisOnly() {
        val hint = applied(forecast(left = push(0.9, level = 2), right = push(0.9, level = 2), forward = push(0.9)))
        assertEquals(LateralCue.NONE, hint.lateral)
        assertEquals(0.0, hint.lateralStrength, 0.0)
        assertEquals(LongitudinalCue.BRAKE, hint.longitudinal)
    }

    @Test fun heldSideComparesItsLatestProbability() {
        // Left turned on first; next, right is the more likely side while left still holds.
        applied(forecast(left = push(0.9)))
        val hint = applied(forecast(left = push(0.5), right = push(0.7)))
        assertEquals(LateralCue.LEFT, hint.lateral)
    }

    @Test fun levelMapsToQuarterSteps() {
        assertEquals(
            listOf(0.25, 0.5, 0.75, 1.0),
            (1..4).map { level -> HintStabilizer(HintConfig(), { now }).run {
                onResult(success(forecast(forward = push(0.9, level))), now)
                now += 1000
                tick().longitudinalStrength
            } },
        )
    }

    @Test fun bodyPushMapsToCarManeuver() {
        fun first(forecast: PushForecast) = HintStabilizer(HintConfig(), { now }).run {
            onResult(success(forecast), now)
            now += 1000
            tick()
        }
        assertEquals(Hint(longitudinal = LongitudinalCue.BRAKE, longitudinalStrength = 0.5), first(forecast(forward = push(0.9))))
        assertEquals(LongitudinalCue.ACCELERATE, first(forecast(backward = push(0.9))).longitudinal)
        assertEquals(Hint(lateral = LateralCue.LEFT, lateralStrength = 0.5), first(forecast(right = push(0.9))))
        assertEquals(LateralCue.RIGHT, first(forecast(left = push(0.9))).lateral)
    }

    @Test fun probabilitiesShowTheCarManeuver() {
        applied(forecast(forward = push(0.1), backward = push(0.2), left = push(0.3), right = push(0.4)))
        assertEquals(MotionProbabilities(forward = 0.2, back = 0.1, left = 0.4, right = 0.3), stabilizer.probabilities())
    }

    @Test fun probabilitiesWaitForTheApplyMoment() {
        stabilizer.onResult(success(forecast(forward = push(0.9))), now)
        assertEquals(MotionProbabilities(), stabilizer.probabilities())
    }

    @Test fun probabilitiesClearOnFailureAndWhenStale() {
        brake(0.8)
        stabilizer.onResult(PredictResult.Failure("down"), now)
        assertEquals(MotionProbabilities(), stabilizer.probabilities())

        brake(0.8)
        now += 2001
        stabilizer.tick()
        assertEquals(MotionProbabilities(), stabilizer.probabilities())
    }
}
