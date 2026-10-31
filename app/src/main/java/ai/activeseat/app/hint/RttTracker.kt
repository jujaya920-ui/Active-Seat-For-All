package ai.activeseat.app.hint

private const val WINDOW_SIZE = 5
private const val INITIAL_ESTIMATE_MS = 500L

/**
 * Rolling median of the last 5 RTTs. The initial estimate holds a window slot until five real samples
 * push it out, and even counts take the lower middle, so a slow first call alone moves nothing.
 */
class RttTracker {
    private val window = ArrayDeque(listOf(INITIAL_ESTIMATE_MS))

    fun record(rttMs: Long) {
        window.addLast(rttMs)
        if (window.size > WINDOW_SIZE) window.removeFirst()
    }

    fun estimateMs(): Long = window.sorted()[(window.size - 1) / 2]
}
