package ai.activeseat.app.vehicle

import ai.activeseat.app.BuildConfig
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Runtime permission: without it speed registration fails and no event ever arrives. */
const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"

private const val TAG = "ActiveSeat"
private const val SPEED_RATE_HZ = 10f
private const val MPS_TO_KPH = 3.6

// Listeners registered right after initialize() do not work; the SDK needs time to bind.
private const val SDK_REGISTER_DELAY_MS = 1500L

/**
 * Reads the car through the same APIs on the emulator and a real vehicle: speed from
 * CarPropertyManager (PERF_VEHICLE_SPEED, m/s), the rest from Vehicle SDK listeners.
 */
class CarSignalSource(context: Context) : SignalSource {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var sdkRegistered = false

    /** False means speed reads a meaningless 0, so callers must surface it. */
    var speedConnected = false
        private set

    @Volatile private var state = VehicleSignal()

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val mps = value.value as? Float ?: return
            update("speed") { it.copy(speedKph = mps * MPS_TO_KPH) }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "speed property error $propertyId")
        }
    }

    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(p0: Float?) {
            val degrees = p0 ?: return
            update("steering") { it.copy(steeringDeg = degrees.toDouble()) }
        }

        override fun onFailed(p0: Exception) {
            Log.w(TAG, "steering listener failed", p0)
        }
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(p0: Boolean?) {
            val pressed = p0 ?: return
            update("brake") { it.copy(brake = pressed) }
        }

        override fun onFailed(p0: Exception) {
            Log.w(TAG, "brake listener failed", p0)
        }
    }

    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(p0: TurnSignalValue?) {
            val value = p0 ?: return
            update("turn") { it.copy(turnSignal = value.toTurnSignal()) }
        }

        override fun onFailed(p0: Exception) {
            Log.w(TAG, "turn signal listener failed", p0)
        }
    }

    private val registerSdk = Runnable {
        runCatching {
            val sdk = checkNotNull(vehicle)
            sdk.getSteeringWheel().registerSteeringWheelAngle(steeringListener)
            sdk.getBrake().registerBrakePedalStatus(brakeListener)
            sdk.getTurnSignal().registerTurnSignalState(turnListener)
            sdkRegistered = true
            Log.i(TAG, "vehicle sdk listeners registered")
        }.onFailure { Log.e(TAG, "vehicle sdk registration failed", it) }
    }

    override fun start() {
        runCatching {
            vehicle = Vehicle(appContext).also { it.initialize() }
            handler.postDelayed(registerSdk, SDK_REGISTER_DELAY_MS)
        }.onFailure { Log.e(TAG, "vehicle sdk setup failed", it) }

        runCatching {
            val connected = checkNotNull(Car.createCar(appContext))
            val manager = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            car = connected
            propertyManager = manager
            connectSpeed()
        }.onFailure { Log.e(TAG, "car property setup failed", it) }
    }

    /** Safe to call repeatedly; retries after the speed permission has been granted. */
    fun connectSpeed() {
        val manager = propertyManager ?: return
        if (speedConnected) return
        if (appContext.checkSelfPermission(SPEED_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "speed permission not granted")
            return
        }
        speedConnected = manager.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, SPEED_RATE_HZ)
        Log.i(TAG, "speed callback registered=$speedConnected")
    }

    override fun stop() {
        handler.removeCallbacks(registerSdk)
        runCatching { propertyManager?.unregisterCallback(speedCallback) }
        runCatching { car?.disconnect() }
        runCatching {
            vehicle?.let { sdk ->
                if (sdkRegistered) {
                    sdk.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener)
                    sdk.getBrake().unregisterBrakePedalStatus(brakeListener)
                    sdk.getTurnSignal().unregisterTurnSignalState(turnListener)
                }
                sdk.release()
            }
        }
        sdkRegistered = false
        speedConnected = false
        propertyManager = null
        car = null
        vehicle = null
    }

    override fun latest(): VehicleSignal = state

    // Listeners may arrive on different threads, so the read-modify-write must be atomic.
    @Synchronized
    private fun update(name: String, change: (VehicleSignal) -> VehicleSignal) {
        state = change(state)
        if (BuildConfig.DEBUG) Log.d(TAG, "RX $name $state")
    }
}

private fun TurnSignalValue.toTurnSignal(): TurnSignal = when (this) {
    TurnSignalValue.LEFT -> TurnSignal.LEFT
    TurnSignalValue.RIGHT -> TurnSignal.RIGHT
    TurnSignalValue.OFF -> TurnSignal.OFF
}
