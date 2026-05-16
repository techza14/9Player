package moe.tekuza.m9player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FloatingVerticalSubtitleWebViewSourceTest {
    @Test
    fun verticalSubtitleColorAndOutlineAreAppliedThroughCanvas() {
        val source = File("src/main/java/moe/tekuza/m9player/AudiobookFloatingOverlayService.kt").readText()

        assertTrue(source.contains("FloatingVerticalSubtitleCanvasView"))
        assertTrue(source.contains("outlinePaint"))
        assertTrue(source.contains("VerticalSubtitleLayoutEngine.draw(canvas, outlinePaint"))
        assertTrue(source.contains("fun applyTypography(color: Int, sizeSp: Float, typeface: Typeface? = paint.typeface)"))
        assertTrue(source.contains("subtitleVerticalCanvasView?.apply"))
        assertTrue(source.contains("applyTypography("))
        assertTrue(source.contains("settings.floatingOverlaySubtitleColor,"))
    }
}
