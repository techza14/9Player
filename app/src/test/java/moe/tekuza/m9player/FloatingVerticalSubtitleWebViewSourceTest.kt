package moe.tekuza.m9player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FloatingVerticalSubtitleWebViewSourceTest {
    @Test
    fun verticalSubtitleColorAndOutlineAreAppliedThroughWebView() {
        val source = File("src/main/java/moe/tekuza/m9player/AudiobookFloatingOverlayService.kt").readText()

        assertTrue(source.contains("outlineCss = floatingSubtitleOutlineCss()"))
        assertTrue(source.contains("text-shadow: \$outlineCss;"))
        assertTrue(source.contains("fun applyTypography(color: Int, sizeSp: Float)"))
        assertTrue(source.contains("subtitleVerticalWebView?.apply"))
        assertTrue(source.contains("applyTypography("))
        assertTrue(source.contains("settings.floatingOverlaySubtitleColor,"))
        assertTrue(source.contains("buildFloatingVerticalSubtitleHtml(text, color, sizeSp)"))
    }
}
