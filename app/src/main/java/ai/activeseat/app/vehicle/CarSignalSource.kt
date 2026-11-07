package ai.activeseat.app.vehicle

import ai.activeseat.app.BuildConfig
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehicleGear
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

/**
 * Vehicle SDK 2.3.2 reports every seat's occupancy inverted: on the emulator, setting a seat
 * occupied in the Virtual Console reads false and empty reads true (checked 2026-11-07 for the
 * driver, passenger and both rear seats). Still to check on a real car.
 */
private const val SEAT_OCCUPANCY_INVERTED = true
private const val MPS_TO_KPH = 3.6

// Listeners registered right after initialize() do not work; the SDK needs time to bind.
private const val SDK_REGISTER_DELAY_MS = 1500L

/**
 * Reads the car through the same APIs on the emulator and a real vehicle: speed and gear from
 * CarPropertyManager (PERF_VEHICLE_SPEED in m/s, GEAR_SELECTION), the rest from Vehicle SDK
 * listeners. The SDK's own gear API is deprecated in favour of GEAR_SELECTION.
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

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val gear = value.value as? Int ?: return
            update("gear") { it.copy(gear = gear.toGear()) }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "gear property error $propertyId")
        }
    }

    // One listener per seat: the SDK reports a seat's value without saying which seat it is.
    private val seatListeners = SeatPosition.entries.associateWith { seat ->
        object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(p0: Boolean?) {
                val reported = p0 ?: return
                val occupied = if (SEAT_OCCUPANCY_INVERTED) !reported else reported
                update("seat $seat") { it.copy(seats = it.seats + (seat to occupied)) }
            }

            override fun onFailed(p0: Exception) {
                Log.w(TAG, "seat $seat occupancy failed", p0)
            }
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
            // A car may lack some seats or their sensors; each registers on its own.
            seatListeners.forEach { (seat, listener) ->
                runCatching { sdk.getSeat().registerSeatOccupancy(seat.area(), listener) }
                    .onFailure { Log.w(TAG, "seat $seat occupancy unavailable", it) }
            }
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
            val gearConnected = manager.registerCallback(gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            Log.i(TAG, "gear callback registered=$gearConnected")
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
        runCatching { propertyManager?.unregisterCallback(gearCallback) }
        runCatching { car?.disconnect() }
        runCatching {
            vehicle?.let { sdk ->
                if (sdkRegistered) {
                    sdk.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener)
                    sdk.getBrake().unregisterBrakePedalStatus(brakeListener)
                    sdk.getTurnSignal().unregisterTurnSignalState(turnListener)
                    seatListeners.forEach { (seat, listener) ->
                        runCatching { sdk.getSeat().unregisterSeatOccupancy(seat.area(), listener) }
                    }
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

private fun SeatPosition.area() = SeatArea(SeatValue.valueOf(name))

/** AAOS VehicleGear values; manual gears count as a forward gear. */
private fun Int.toGear(): Gear = when (this) {
    VehicleGear.GEAR_PARK -> Gear.PARK
    VehicleGear.GEAR_REVERSE -> Gear.REVERSE
    VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
    VehicleGear.GEAR_DRIVE, VehicleGear.GEAR_FIRST, VehicleGear.GEAR_SECOND, VehicleGear.GEAR_THIRD,
    VehicleGear.GEAR_FOURTH, VehicleGear.GEAR_FIFTH, VehicleGear.GEAR_SIXTH, VehicleGear.GEAR_SEVENTH,
    VehicleGear.GEAR_EIGHTH, VehicleGear.GEAR_NINTH,
    -> Gear.DRIVE
    else -> Gear.UNKNOWN
}
