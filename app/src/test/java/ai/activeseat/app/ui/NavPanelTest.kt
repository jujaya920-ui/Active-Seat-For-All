package ai.activeseat.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NavPanelTest {
    @Test
    fun portraitCanvasFitsWidthAndAlignsToTheTop() {
        val fit = fitMap(840f, 1100f)

        assertEquals(0.84f, fit.scale, 1e-5f)
        assertEquals(0f, fit.offsetX, 1e-3f)
        assertEquals(0f, fit.offsetY, 1e-3f)
    }

    @Test
    fun wideCanvasCoversTheWidth() {
        val fit = fitMap(2000f, 500f)

        assertEquals(2f, fit.scale, 1e-5f)
        assertEquals(0f, fit.offsetX, 1e-3f)
        assertEquals(0f, fit.offsetY, 1e-3f)
    }

    @Test
    fun turnTypesReadInKoreanAndUnknownsPassThrough() {
        assertEquals("좌회전", turnLabel("TURN_LEFT"))
        assertEquals("우회전", turnLabel("TURN_RIGHT"))
        assertEquals("U_TURN", turnLabel("U_TURN"))
    }
}
