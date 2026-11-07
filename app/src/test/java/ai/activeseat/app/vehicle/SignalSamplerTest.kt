package ai.activeseat.app.vehicle

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalSamplerTest {
    private class FakeSource : SignalSource {
        var signal = VehicleSignal()
        override fun start() = Unit
        override fun stop() = Unit
        override fun latest() = signal
    }

    private var now = 10_000L
    private val source = FakeSource()
    private val sampler = SignalSampler(source) { now }

    @Test
    fun keepsLastWindowOldestFirstWithAges() {
        repeat(35) { i ->
            source.signal = VehicleSignal(speedKph = i.toDouble())
            sampler.sample()
            if (i < 34) now += 200
        }

        val window = sampler.window()

        assertEquals(SAMPLE_WINDOW_SIZE, window.size)
        assertEquals((2..34).map { it.toDouble() }, window.map { it.speedKph })
        assertEquals((32 downTo 0).map { it * 200L }, window.map { it.ageMs })
    }

    @Test
    fun agesGrowWithTimeSinceSampling() {
        sampler.sample()
        now += 150
        assertEquals(150L, sampler.window().single().ageMs)
    }

    @Test
    fun convertsToContractForm() {
        source.signal = VehicleSignal(
            speedKph = 16.67f * 3.6,
            steeringDeg = 12.34,
            brake = true,
            turnSignal = TurnSignal.LEFT,
        )
        sampler.sample()

        val sample = sampler.window().single()

        assertEquals(60.0, sample.speedKph, 0.0)
        assertEquals(12.3, sample.steeringDeg, 0.0)
        assertEquals(true, sample.brake)
        assertEquals("left", sample.turnSignal)
    }
}
