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

    @Test
    fun samplesTheLatestSourceValue() {
        val source = FakeSource()
        val sampler = SignalSampler(source) { 0L }

        source.signal = VehicleSignal(speedKph = 42.0, steeringDeg = 12.34, brake = true, turnSignal = TurnSignal.LEFT)

        assertEquals(source.signal, sampler.sample())
    }
}
