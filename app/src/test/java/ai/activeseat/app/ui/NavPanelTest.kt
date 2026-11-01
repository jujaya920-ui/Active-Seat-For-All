package ai.activeseat.app.ui

import ai.activeseat.app.nav.NavRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavPanelTest {
    private val route = NavRoute("t", listOf(1000 to 2000, 3000 to 6000), 100.0, emptyList(), emptyList())

    @Test
    fun theCameraPositionLandsOnTheCanvasCentreInAnyHeading() {
        val camera = MapCamera(500f, 700f, 1f, bearingDeg = 73f)

        val screen = camera.toScreen(500f, 700f, 840f, 1100f)

        assertEquals(420f, screen.x, 1e-3f)
        assertEquals(550f, screen.y, 1e-3f)
    }

    @Test
    fun aHeadingUpMapPutsThePointAheadOfTheCarAboveIt() {
        val headingEast = MapCamera(500f, 500f, 1f, bearingDeg = 90f)

        val ahead = headingEast.toScreen(600f, 500f, 800f, 800f)

        assertEquals(400f, ahead.x, 1e-3f)
        assertEquals(300f, ahead.y, 1e-3f)
    }

    @Test
    fun overviewFitsTheRouteBetweenTheInsets() {
        val camera = overviewCamera(route, 840f, 1200f, topInset = 400f, bottomInset = 0f, padding = 0f, maxScale = 1f)

        assertEquals(0.2f, camera.scale, 1e-5f)
        val top = camera.toScreen(1000f, 2000f, 840f, 1200f)
        val bottom = camera.toScreen(3000f, 6000f, 840f, 1200f)
        assertEquals(400f, top.y, 1e-2f)
        assertEquals(1200f, bottom.y, 1e-2f)
        assertEquals(220f, top.x, 1e-2f)
        assertEquals(620f, bottom.x, 1e-2f)
    }

    @Test
    fun tilesAroundCoverTheRadiusAndStopAtTheMapEdge() {
        val tiles = tilesAround(100f, 1100f, 900f)

        assertEquals(setOf(0), tiles.map { it.col }.toSet())
        assertEquals(setOf(0, 1), tiles.map { it.row }.toSet())
        assertTrue(MapTile(0, 1) in tiles)
    }

    @Test
    fun nearestAngleTurnsTheShortWay() {
        assertEquals(370f, nearestAngle(350f, 10f), 1e-3f)
        assertEquals(-10f, nearestAngle(10f, 350f), 1e-3f)
        assertEquals(725f, nearestAngle(720f, 5f), 1e-3f)
    }

    @Test
    fun distancesAndDurationsReadLikeANavigation() {
        assertEquals("850m", formatDistance(850.0))
        assertEquals("15.6km", formatDistance(15616.0))
        assertEquals("3분", formatDuration(150.0))
        assertEquals("1분", formatDuration(10.0))
    }

    @Test
    fun turnTypesReadInKoreanAndUnknownsPassThrough() {
        assertEquals("좌회전", turnLabel("TURN_LEFT"))
        assertEquals("우회전", turnLabel("TURN_RIGHT"))
        assertEquals("U_TURN", turnLabel("U_TURN"))
    }
}
