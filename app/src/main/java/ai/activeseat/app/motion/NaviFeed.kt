package ai.activeseat.app.motion

import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/** The latest navigation reports. Distances are as of the moment the report arrived. */
data class NavSnapshot(
    val tbt: List<TBTInfo> = emptyList(),
    val cameras: List<CameraAlert> = emptyList(),
    val cautionPoints: List<CautionPoint> = emptyList(),
)

/**
 * Keeps the last of each NaviHelper report for the engine. Registered with the real NaviHelper or
 * the fake one alike; listener calls may come from any thread.
 */
class NaviFeed : NaviHelperEventListener {
    @Volatile
    var snapshot = NavSnapshot()
        private set

    override fun onTBTInfo(p0: List<TBTInfo>) {
        snapshot = snapshot.copy(tbt = p0)
    }

    override fun onCameraAlert(p0: List<CameraAlert>) {
        snapshot = snapshot.copy(cameras = p0)
    }

    override fun onCautionPointInfo(p0: List<CautionPoint>) {
        snapshot = snapshot.copy(cautionPoints = p0)
    }

    fun clear() {
        snapshot = NavSnapshot()
    }
}
