package ai.activeseat.app.vehicle

import kotlin.math.max
import kotlin.math.roundToLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

const val SAMPLE_INTERVAL_MS = 200L
/** 6.6 s: three 2 s description windows plus the three samples their 0.6 s trailing average needs. */
const val SAMPLE_WINDOW_SIZE = 33

/**
 * Samples the latest source value on a fixed 0.2 s grid and keeps the last [SAMPLE_WINDOW_SIZE].
 * Ages are computed on read from the app's own clock, so no clock sync is needed.
 */
class SignalSampler(
    private val source: SignalSource,
    private val clockMs: () -> Long,
) {
    private class Stamped(val atMs: Long, val signal: VehicleSignal)

    private val samples = ArrayDeque<Stamped>()

    @Synchronized
    fun sample(): VehicleSignal {
        val signal = source.latest()
        samples.addLast(Stamped(clockMs(), signal))
        if (samples.size > SAMPLE_WINDOW_SIZE) samples.removeFirst()
        return signal
    }

    /** Oldest first. Holds fewer than [SAMPLE_WINDOW_SIZE] entries until the sampler has warmed up. */
    @Synchronized
    fun window(): List<SignalSample> {
        val now = clockMs()
        return samples.map { stamped ->
            SignalSample(
                ageMs = now - stamped.atMs,
                speedKph = roundToTenth(stamped.signal.speedKph),
                steeringDeg = roundToTenth(stamped.signal.steeringDeg),
                brake = stamped.signal.brake,
                turnSignal = stamped.signal.turnSignal.wire,
            )
        }
    }

    suspend fun run(onSample: (VehicleSignal) -> Unit = {}) {
        val start = clockMs()
        var tick = 0L
        while (currentCoroutineContext().isActive) {
            onSample(sample())
            // Stay on the grid after a stall instead of drifting.
            tick = max(tick + 1, (clockMs() - start) / SAMPLE_INTERVAL_MS + 1)
            delay(start + tick * SAMPLE_INTERVAL_MS - clockMs())
        }
    }

    private fun roundToTenth(value: Double): Double = (value * 10).roundToLong() / 10.0
}
