package ai.activeseat.app.motion

import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Prints replay scores for tuning. Skipped unless REPLAY_REPORT is set, so the normal test run
 * stays quiet: `REPLAY_REPORT=1 gradlew :app:testDebugUnitTest --tests '*ReplayReport*' -i`.
 * REPLAY_SWEEP=1 also varies one threshold at a time around the defaults.
 */
class ReplayReport {
    /** Every tick where a truth motion or a cue is present, for reading one route closely. */
    private fun trace(scenario: ai.activeseat.app.demo.Scenario, config: MotionConfig) {
        println("== trace ${scenario.id}")
        for (tick in replay(scenario, config)) {
            val o = tick.out
            val busy = kotlin.math.abs(tick.truthLongitudinal) >= FEEL_MPS2 || kotlin.math.abs(tick.truthLateral) >= FEEL_MPS2 ||
                o.lateralSource != CueSource.NONE || o.longitudinalSource != CueSource.NONE
            if (!busy) continue
            val f = scenario.frameAt(tick.ms / 1000.0)
            println(
                "%6.1f v=%5.1f truthLat=%+5.2f truthLong=%+5.2f lat=%-5s %-9s long=%-10s %-9s mLong=%+5.2f".format(
                    tick.ms / 1000.0, f.speedKph, tick.truthLateral, tick.truthLongitudinal,
                    o.hint.lateral, o.lateralSource, o.hint.longitudinal, o.longitudinalSource, o.longitudinalMps2,
                ),
            )
        }
    }

    @Test
    fun report() {
        assumeTrue(System.getenv("REPLAY_REPORT") != null)
        val scenarios = loadScenarios()
        val base = MotionConfig()
        println("== per scenario, defaults")
        for (s in scenarios) {
            val ticks = replay(s, base)
            println("${s.id} cameras=${s.cameras.size} caution=${s.cautionPoints.size}")
            println("  lateral      ${lateralScore(ticks).line()}")
            println("  longitudinal ${longitudinalScore(ticks).line()}")
            println("  brake        ${brakeScore(ticks).line()}")
        }
        System.getenv("REPLAY_TRACE")?.let { id -> trace(scenarios.first { it.id == id }, base) }
        if (System.getenv("REPLAY_SWEEP") == null) return

        val variants = listOf<Pair<String, (Double) -> MotionConfig>>(
            "lateralOnMps2" to { x -> base.copy(lateralOnMps2 = x) },
            "brakeOnMps2" to { x -> base.copy(brakeOnMps2 = x) },
            "accelOnMps2" to { x -> base.copy(accelOnMps2 = x) },
            "offRatio" to { x -> base.copy(offRatio = x) },
            "slopeSamples" to { x -> base.copy(slopeSamples = x.toInt()) },
            "approachSec" to { x -> base.copy(approachSec = x) },
            "requiredDecelMps2" to { x -> base.copy(requiredDecelMps2 = x) },
            "graceMs" to { x -> base.copy(graceMs = x.toLong()) },
            "minHoldMs" to { x -> base.copy(minHoldMs = x.toLong()) },
        )
        val values = mapOf(
            "lateralOnMps2" to listOf(0.6, 0.8, 1.0, 1.2, 1.5),
            "brakeOnMps2" to listOf(0.6, 0.8, 1.0, 1.2, 1.5),
            "accelOnMps2" to listOf(0.6, 0.8, 1.0, 1.2, 1.5),
            "offRatio" to listOf(0.5, 0.6, 0.7, 0.8, 0.9),
            "slopeSamples" to listOf(3.0, 4.0, 5.0, 6.0, 8.0),
            "approachSec" to listOf(0.5, 1.0, 1.5, 2.0, 2.5, 3.0),
            "requiredDecelMps2" to listOf(0.5, 0.8, 1.0, 1.5, 2.0),
            "graceMs" to listOf(0.0, 500.0, 1000.0, 2000.0, 3000.0),
            "minHoldMs" to listOf(0.0, 400.0, 600.0, 1000.0),
        )
        for ((name, make) in variants) {
            println("== sweep $name")
            for (x in values.getValue(name)) {
                val config = make(x)
                var lat = AxisScore.ZERO
                var lon = AxisScore.ZERO
                var brake = AxisScore.ZERO
                for (s in scenarios) {
                    val ticks = replay(s, config)
                    lat += lateralScore(ticks)
                    lon += longitudinalScore(ticks)
                    brake += brakeScore(ticks)
                }
                println("  %-6s lat   %s".format(x, lat.line()))
                println("  %-6s long  %s".format(x, lon.line()))
                println("  %-6s brake %s".format(x, brake.line()))
            }
        }
    }
}
