package ai.activeseat.app.hint

import org.junit.Assert.assertEquals
import org.junit.Test

class RttTrackerTest {
    private fun trackerWith(vararg rtts: Long) = RttTracker().apply { rtts.forEach { record(it) } }

    @Test fun startsAt500() = assertEquals(500L, RttTracker().estimateMs())

    @Test fun firstCallOutlierIsDamped() = assertEquals(500L, trackerWith(4000).estimateMs())

    @Test fun firstLowSampleTakesLowerMiddle() = assertEquals(300L, trackerWith(300).estimateMs())

    @Test fun medianIgnoresOneSpike() =
        assertEquals(310L, trackerWith(300, 310, 5000, 320, 305).estimateMs())

    @Test fun initialEstimateLeavesAfterFiveSamples() =
        assertEquals(800L, trackerWith(800, 800, 800, 800, 800).estimateMs())

    @Test fun onlyLastFiveCount() =
        assertEquals(100L, trackerWith(9000, 9000, 9000, 100, 100, 100, 100, 100).estimateMs())
}
