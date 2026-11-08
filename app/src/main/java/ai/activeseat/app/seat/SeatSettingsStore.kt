package ai.activeseat.app.seat

import ai.activeseat.app.hardware.HardwareSettings
import ai.activeseat.app.hardware.LEVEL_MAX
import ai.activeseat.app.hardware.LEVEL_OFF
import ai.activeseat.app.vehicle.SeatPosition
import android.content.Context

/** Where one seat's settings are kept. */
interface SeatSettingsStore {
    fun load(): HardwareSettings
    fun save(settings: HardwareSettings)
}

/** Each seat has its own preferences file. The passenger seat keeps using the app's original one. */
class PreferencesSeatSettingsStore(context: Context, position: SeatPosition) : SeatSettingsStore {
    private val prefs = context.getSharedPreferences("settings_$position", Context.MODE_PRIVATE)

    override fun load(): HardwareSettings {
        val default = HardwareSettings()
        fun level(key: String, fallback: Int) = prefs.getInt(key, fallback).coerceIn(LEVEL_OFF, LEVEL_MAX)
        return HardwareSettings(
            seatSensitivity = level(KEY_SEAT_SENSITIVITY, default.seatSensitivity),
            seatVibration = level(KEY_SEAT_VIBRATION, default.seatVibration),
            leanSpeed = level(KEY_LEAN_SPEED, default.leanSpeed),
            watchSensitivity = level(KEY_WATCH_SENSITIVITY, default.watchSensitivity),
            watchVibration = level(KEY_WATCH_VIBRATION, default.watchVibration),
        )
    }

    override fun save(settings: HardwareSettings) {
        prefs.edit()
            .putInt(KEY_SEAT_SENSITIVITY, settings.seatSensitivity)
            .putInt(KEY_SEAT_VIBRATION, settings.seatVibration)
            .putInt(KEY_LEAN_SPEED, settings.leanSpeed)
            .putInt(KEY_WATCH_SENSITIVITY, settings.watchSensitivity)
            .putInt(KEY_WATCH_VIBRATION, settings.watchVibration)
            .apply()
    }

    private companion object {
        const val KEY_SEAT_SENSITIVITY = "seatSensitivity"
        const val KEY_SEAT_VIBRATION = "seatVibration"
        const val KEY_LEAN_SPEED = "leanSpeed"
        const val KEY_WATCH_SENSITIVITY = "watchSensitivity"
        const val KEY_WATCH_VIBRATION = "watchVibration"
    }
}
