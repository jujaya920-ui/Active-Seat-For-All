package ai.activeseat.app.prediction

import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val PREDICT_INTERVAL_MS = 1000L

/** Time left in the interval once a call took `elapsedMs`. A slow call starts the next one at once. */
internal fun nextCallDelayMs(elapsedMs: Long): Long = max(0L, PREDICT_INTERVAL_MS - elapsedMs)

/**
 * Starts a call once a second, measured from call start, one request at a time.
 * `onResult` gets null while warming up or disabled. `enabled` keeps an idle app from paying for calls.
 */
class PredictionLoop(
    private val assembler: PredictionAssembler,
    private val client: PredictClient,
    private val enabled: () -> Boolean,
    private val onResult: (PredictResult?) -> Unit,
) {
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            val startedNs = System.nanoTime()
            val input = if (enabled()) assembler.assemble() else null
            onResult(input?.let { withContext(Dispatchers.IO) { client.predict(it) } })
            delay(nextCallDelayMs((System.nanoTime() - startedNs) / 1_000_000))
        }
    }
}
