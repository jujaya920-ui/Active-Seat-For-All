package ai.activeseat.app.nav

import ai.pleos.playground.navi.constants.CameraType
import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import kotlin.math.roundToInt

/**
 * Stands in for NaviHelper on a scripted route: it calls the SDK's own listener with the SDK's own
 * data classes, so whatever listens cannot tell it from the built-in navigation.
 *
 * Provisional, not checked against the real navigation (stage 0): how many maneuvers onTBTInfo
 * lists, the alert ranges, that `status` means "in range" and that `isOverSpeed` compares the
 * current speed with the limit.
 */
class FakeNaviHelper(private val route: NavRoute) {
    /** Sends what the navigation would report at `progressM` while driving at `speedKph`. */
    fun emit(progressM: Double, speedKph: Double, listener: NaviHelperEventListener) {
        listener.onTBTInfo(tbtInfo(progressM))
        listener.onCameraAlert(cameraAlerts(progressM, speedKph))
        listener.onCautionPointInfo(cautionPoints(progressM))
    }

    fun tbtInfo(progressM: Double): List<TBTInfo> = route.turns
        .filter { it.atM > progressM }
        .take(TBT_COUNT)
        .map { TBTInfo(TurnType.valueOf(it.type), it.atM - progressM, it.description) }

    fun cameraAlerts(progressM: Double, speedKph: Double): List<CameraAlert> = route.cameras
        .filter { it.atM > progressM && it.atM - progressM <= CAMERA_ALERT_RANGE_M }
        .map {
            CameraAlert(
                it.id,
                CameraType.valueOf(it.type),
                true,
                it.speedLimit,
                (it.atM - progressM).roundToInt(),
                it.sectionAvrSpeed,
                speedKph > it.speedLimit,
                false,
            )
        }

    fun cautionPoints(progressM: Double): List<CautionPoint> = route.cautionPoints
        .filter { it.atM > progressM && it.atM - progressM <= CAUTION_ALERT_RANGE_M }
        .map { CautionPoint(it.id, CautionPointType.valueOf(it.type), (it.atM - progressM).roundToInt()) }

    companion object {
        /** Assumed: the speed limit is the only event documented as sent every second. */
        const val EMIT_INTERVAL_MS = 1000L
        const val TBT_COUNT = 2
        const val CAMERA_ALERT_RANGE_M = 600.0
        const val CAUTION_ALERT_RANGE_M = 300.0
    }
}
