package ai.activeseat.app.vehicle

/** Where vehicle signals come from. Implementations must not know what drives the car. */
interface SignalSource {
    fun start()
    fun stop()
    fun latest(): VehicleSignal
}
