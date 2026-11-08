package ai.activeseat.app.vehicle

import kotlin.math.max
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

const val SAMPLE_INTERVAL_MS = 200L

/** Reads the latest source value on a fixed 0.2 s grid of the app's own clock. */
class SignalSampler(
    private val source: SignalSource,
    private val clockMs: () -> Long,
) {
    fun sample(): VehicleSignal = source.latest()

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
}
