package ai.activeseat.app.nav

import ai.activeseat.app.motion.NaviFeed
import ai.pleos.playground.navi.constants.CameraType
import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.TurnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val route = NavRoute(
    title = "t",
    routePx = listOf(0 to 0, 1000 to 0),
    lengthM = 3000.0,
    turns = listOf(NavMark(500.0, "TURN_LEFT", "A 방면"), NavMark(900.0, "TURN_RIGHT", "B 방면"), NavMark(2000.0, "TURN_ARRIVE", "도착")),
    frontEvents = emptyList(),
    cameras = listOf(NavCamera(800.0, "cam-1", "SPEED_CAMERA_FIX", 50, 0)),
    cautionPoints = listOf(NavCaution(600.0, "cp-1", "SPEED_BUMP"), NavCaution(1500.0, "cp-2", "SCHOOL_ZONE")),
)

class FakeNaviHelperTest {
    private val navi = FakeNaviHelper(route)

    @Test
    fun listsTheNextManeuversWithTheirRemainingDistance() {
        val tbt = navi.tbtInfo(400.0)

        assertEquals(listOf(TurnType.TURN_LEFT, TurnType.TURN_RIGHT), tbt.map { it.type })
        assertEquals(listOf(100.0, 500.0), tbt.map { it.distance })
        assertEquals("A 방면", tbt.first().description)
    }

    @Test
    fun alertsCamerasInRangeAndComparesSpeedWithTheLimit() {
        assertEquals(emptyList<Any>(), navi.cameraAlerts(100.0, 60.0))

        val alert = navi.cameraAlerts(300.0, 60.0).single()

        assertEquals("cam-1", alert.id)
        assertEquals(CameraType.SPEED_CAMERA_FIX, alert.type)
        assertEquals(50, alert.speedLimit)
        assertEquals(500, alert.remainDistance)
        assertTrue(alert.isOverSpeed)
        assertFalse(navi.cameraAlerts(300.0, 50.0).single().isOverSpeed)
    }

    @Test
    fun reportsCautionPointsInRangeOnly() {
        val points = navi.cautionPoints(400.0)

        assertEquals(listOf("cp-1"), points.map { it.id })
        assertEquals(CautionPointType.SPEED_BUMP, points.single().type)
        assertEquals(200, points.single().remainDistance)
    }

    @Test
    fun dropsPointsBehindTheCar() {
        assertEquals(emptyList<Any>(), navi.cameraAlerts(850.0, 40.0))
        assertEquals(listOf("cp-2"), navi.cautionPoints(1300.0).map { it.id })
    }

    @Test
    fun deliversThroughTheSdkListener() {
        val feed = NaviFeed()

        navi.emit(300.0, 60.0, feed)

        assertEquals(2, feed.snapshot.tbt.size)
        assertEquals(1, feed.snapshot.cameras.size)
        assertEquals(1, feed.snapshot.cautionPoints.size)
    }
}
