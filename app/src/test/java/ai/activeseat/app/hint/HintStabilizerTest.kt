package ai.activeseat.app.hint

import ai.activeseat.app.prediction.AxisPrediction
import ai.activeseat.app.prediction.PredictResult
import ai.activeseat.app.prediction.Prediction
import org.junit.Assert.assertEquals
import org.junit.Test

class HintStabilizerTest {
    private var now = 0L
    private val stabilizer = HintStabilizer(HintConfig(onProbability = 0.6), { now }, RttTracker())

    private fun prediction(
        lateral: String = "straight",
        lateralP: Double = 0.9,
        longitudinal: String = "steady",
        longitudinalP: Double = 0.9,
        horizonSec: Double = 2.0,
        lateralStrength: Double = 0.7,
        longitudinalStrength: Double = 0.7,
    ) = Prediction(
        horizonSec,
        AxisPrediction(lateral, lateralP, lateralStrength, mapOf(lateral to lateralP)),
        AxisPrediction(longitudinal, longitudinalP, longitudinalStrength, mapOf(longitudinal to longitudinalP)),
        0.0,
    )

    private fun success(vararg predictions: Prediction, rttMs: Long = 500) =
        PredictResult.Success(rttMs, 0, predictions.toList())

    private fun lateralResult(value: String, p: Double) = stabilizer.onResult(success(prediction(value, p)))

    private fun longitudinalResult(value: String, p: Double) =
        stabilizer.onResult(success(prediction(longitudinal = value, longitudinalP = p)))

    @Test fun neutralStaysOff() = assertEquals(Hint(), lateralResult("straight", 0.95))

    @Test fun staysOffJustBelowOnThreshold() = assertEquals(LateralCue.NONE, lateralResult("left", 0.599).lateral)

    @Test fun turnsOnAtOnThreshold() = assertEquals(LateralCue.LEFT, lateralResult("left", 0.6).lateral)

    @Test fun hysteresisBandDoesNotTurnOn() = assertEquals(LateralCue.NONE, lateralResult("left", 0.5).lateral)

    @Test fun longitudinalTurnsOnAtOnThreshold() {
        assertEquals(LongitudinalCue.BRAKE, longitudinalResult("brake", 0.6).longitudinal)
    }

    @Test fun strengthComesFromThePredictionNotTheProbability() {
        val hint = stabilizer.onResult(success(prediction("left", 0.6, "brake", 0.95, lateralStrength = 1.0, longitudinalStrength = 0.4)))
        assertEquals(1.0, hint.lateralStrength, 1e-9)
        assertEquals(0.4, hint.longitudinalStrength, 1e-9)
    }

    @Test fun strengthFollowsEachNewPredictionWhileOn() {
        stabilizer.onResult(success(prediction("left", 0.8, lateralStrength = 0.5)))
        now += 100
        assertEquals(0.9, stabilizer.onResult(success(prediction("left", 0.5, lateralStrength = 0.9))).lateralStrength, 1e-9)
    }

    @Test fun hysteresisBandKeepsCueOn() {
        lateralResult("left", 0.7)
        now += 1000
        assertEquals(LateralCue.LEFT, lateralResult("left", 0.5).lateral)
    }

    @Test fun releasesBelowOffThresholdAfterHold() {
        lateralResult("left", 0.7)
        now += 1000
        assertEquals(Hint(), lateralResult("left", 0.449))
    }

    @Test fun releaseWaitsForMinHoldAndKeepsStrength() {
        val on = lateralResult("left", 0.8)
        now += 999
        assertEquals(on, lateralResult("straight", 0.9))
        now += 1
        assertEquals(Hint(), lateralResult("straight", 0.9))
    }

    @Test fun lowProbabilityDuringHoldKeepsStrength() {
        val on = lateralResult("left", 0.8)
        now += 500
        assertEquals(on, lateralResult("left", 0.1))
    }

    @Test fun oppositeBelowOnThresholdIsARelease() {
        val on = lateralResult("left", 0.8)
        now += 500
        assertEquals(on, lateralResult("right", 0.59))
        now += 500
        assertEquals(Hint(), lateralResult("right", 0.59))
    }

    @Test fun directReversalIgnoresMinHold() {
        lateralResult("left", 0.8)
        now += 1
        val hint = lateralResult("right", 0.6)
        assertEquals(LateralCue.RIGHT, hint.lateral)
    }

    @Test fun directLongitudinalReversalIgnoresMinHold() {
        longitudinalResult("accelerate", 0.8)
        now += 1
        assertEquals(LongitudinalCue.BRAKE, longitudinalResult("brake", 0.6).longitudinal)
    }

    @Test fun reversalRestartsMinHold() {
        lateralResult("left", 0.8)
        now += 900
        val reversed = lateralResult("right", 0.8)
        now += 900
        assertEquals(reversed, lateralResult("straight", 0.9))
    }

    @Test fun axesAreIndependent() {
        stabilizer.onResult(success(prediction("left", 0.8, "steady", 0.9)))
        now += 500
        val hint = stabilizer.onResult(success(prediction("straight", 0.9, "brake", 0.8)))
        assertEquals(LateralCue.LEFT, hint.lateral)
        assertEquals(LongitudinalCue.BRAKE, hint.longitudinal)
        now += 500
        val later = stabilizer.onResult(success(prediction("straight", 0.9, "brake", 0.8)))
        assertEquals(LateralCue.NONE, later.lateral)
        assertEquals(LongitudinalCue.BRAKE, later.longitudinal)
    }

    @Test fun unknownValuesAreNeutral() {
        assertEquals(Hint(), stabilizer.onResult(success(prediction("LEFT", 0.9, "boost", 0.9))))
    }

    @Test fun unknownValueReleasesAfterHold() {
        lateralResult("left", 0.8)
        now += 1000
        assertEquals(Hint(), lateralResult("sideways", 0.9))
    }

    @Test fun nullTurnsOffDuringHold() = assertOffImmediately(null)

    @Test fun notConfiguredTurnsOffDuringHold() = assertOffImmediately(PredictResult.NotConfigured)

    @Test fun failureTurnsOffDuringHold() = assertOffImmediately(PredictResult.Failure("down"))

    @Test fun emptyPredictionsTurnOffDuringHold() = assertOffImmediately(success())

    private fun assertOffImmediately(result: PredictResult?) {
        stabilizer.onResult(success(prediction("left", 0.8, "brake", 0.8)))
        now += 1
        assertEquals(Hint(), stabilizer.onResult(result))
    }

    @Test fun emptyPredictionsDoNotRecordRtt() {
        val rtt = RttTracker()
        HintStabilizer(HintConfig(onProbability = 0.6), { now }, rtt).onResult(success(rttMs = 100))
        assertEquals(500L, rtt.estimateMs())
    }

    @Test fun tickKeepsHintUntilStale() {
        val on = lateralResult("left", 0.8)
        now += 2000
        assertEquals(on, stabilizer.tick())
    }

    @Test fun tickTurnsOffWhenStale() {
        lateralResult("left", 0.8)
        now += 2001
        assertEquals(Hint(), stabilizer.tick())
    }

    @Test fun staleOffIgnoresMinHold() {
        val longHold = HintStabilizer(HintConfig(onProbability = 0.6, minHoldMs = 10_000), { now }, RttTracker())
        longHold.onResult(success(prediction("left", 0.8)))
        now += 2001
        assertEquals(Hint(), longHold.tick())
    }

    @Test fun tickDoesNotReleaseOnMinHold() {
        // Only a new result can release: a tick has no fresh probability to judge.
        val on = lateralResult("left", 0.8)
        now += 500
        lateralResult("straight", 0.9)
        now += 1000
        assertEquals(on, stabilizer.tick())
    }

    @Test fun tickBeforeAnyResultIsOff() {
        now = 100_000
        assertEquals(Hint(), stabilizer.tick())
    }

    @Test fun cueReturnsAfterStaleOff() {
        lateralResult("left", 0.8)
        now += 2001
        stabilizer.tick()
        assertEquals(LateralCue.LEFT, lateralResult("left", 0.6).lateral)
    }

    @Test fun cueReturnsAfterFailure() {
        lateralResult("left", 0.8)
        stabilizer.onResult(PredictResult.Failure("down"))
        assertEquals(LateralCue.LEFT, lateralResult("left", 0.6).lateral)
    }

    @Test fun probabilitiesComeFromTheChosenHorizon() {
        fun axis(vararg p: Pair<String, Double>) = AxisPrediction("straight", 0.5, 0.0, mapOf(*p))
        val near = Prediction(2.0, axis("left" to 0.1, "right" to 0.2), axis("accelerate" to 0.3, "brake" to 0.4), 0.0)
        val far = Prediction(9.0, axis("left" to 0.9), axis("brake" to 0.9), 0.0)
        stabilizer.onResult(success(near, far))
        assertEquals(MotionProbabilities(forward = 0.3, back = 0.4, left = 0.1, right = 0.2), stabilizer.probabilities())
    }

    @Test fun probabilitiesClearOnFailureAndWhenStale() {
        stabilizer.onResult(success(prediction("left", 0.8).copy(lateral = AxisPrediction("left", 0.8, 0.5, mapOf("left" to 0.8)))))
        stabilizer.onResult(PredictResult.Failure("down"))
        assertEquals(MotionProbabilities(), stabilizer.probabilities())

        stabilizer.onResult(success(prediction("left", 0.8).copy(lateral = AxisPrediction("left", 0.8, 0.5, mapOf("left" to 0.8)))))
        now += 2001
        stabilizer.tick()
        assertEquals(MotionProbabilities(), stabilizer.probabilities())
    }

    private val horizons = arrayOf(
        prediction("left", horizonSec = 1.0),
        prediction("right", horizonSec = 2.0),
        prediction(longitudinal = "brake", horizonSec = 3.0),
    )

    private fun settledAt(rttMs: Long, config: HintConfig = HintConfig(onProbability = 0.6)) =
        HintStabilizer(config, { now }, RttTracker().apply { repeat(5) { record(rttMs) } })

    @Test fun picksNearestHorizonAtLowRtt() {
        // 1.5 + 0.1 = 1.6, nearest 2.0.
        assertEquals(LateralCue.RIGHT, settledAt(100).onResult(success(*horizons, rttMs = 100)).lateral)
    }

    @Test fun picksNearestHorizonAtHighRtt() {
        // 1.5 + 1.2 = 2.7, nearest 3.0.
        val hint = settledAt(1200).onResult(success(*horizons, rttMs = 1200))
        assertEquals(LongitudinalCue.BRAKE, hint.longitudinal)
        assertEquals(LateralCue.NONE, hint.lateral)
    }

    @Test fun picksShortHorizonWhenLeadIsShort() {
        val hint = settledAt(0, HintConfig(onProbability = 0.6, displayLeadSec = 0.5)).onResult(success(*horizons, rttMs = 0))
        assertEquals(LateralCue.LEFT, hint.lateral)
    }

    @Test fun targetIncludesThisResultsRtt() {
        // The estimate is 500 before this result and 1200 after it, so only the latter reaches 3.0.
        val rtt = RttTracker().apply { record(1200) }
        val hint = HintStabilizer(HintConfig(onProbability = 0.6), { now }, rtt).onResult(success(*horizons, rttMs = 1200))
        assertEquals(LongitudinalCue.BRAKE, hint.longitudinal)
    }

    @Test fun coldStartOutlierDoesNotChangeHorizon() {
        // The median keeps 500, so the target is 2.0 and not 5.5.
        assertEquals(LateralCue.RIGHT, stabilizer.onResult(success(*horizons, rttMs = 4000)).lateral)
    }

    @Test fun tieKeepsFirstListed() {
        val hint = stabilizer.onResult(
            success(prediction("left", horizonSec = 1.5), prediction("right", horizonSec = 2.5)),
        )
        assertEquals(LateralCue.LEFT, hint.lateral)
    }

    @Test fun singleHorizonIsUsedWhateverItsDistance() {
        assertEquals(LateralCue.LEFT, stabilizer.onResult(success(prediction("left", horizonSec = 9.0))).lateral)
    }
}
