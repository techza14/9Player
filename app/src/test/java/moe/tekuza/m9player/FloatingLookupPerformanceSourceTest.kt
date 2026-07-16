package moe.tekuza.m9player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FloatingLookupPerformanceSourceTest {
    @Test
    fun floatingLookupKeepsItsWarmShellAndSkipsLegacyRegrouping() {
        val source = File("src/main/java/moe/tekuza/m9player/AudiobookFloatingOverlayService.kt").readText()

        assertTrue(source.contains("prewarmFloatingLookup()"))
        assertTrue(source.contains("createFloatingLookupWindowLayoutParams(position, touchable = false)"))
        assertTrue(source.contains("enableLookupTap = true"))
        assertFalse(source.contains("interceptAllTouches"))
        assertFalse(source.contains("tapReturn"))
        assertFalse(source.contains("fun groupFloatingHoshiResults("))
    }

    @Test
    fun floatingLookupStreamsTheFirstEntryAndHandlesFirstPaintOnce() {
        val source = File("src/main/java/moe/tekuza/m9player/AudiobookFloatingOverlayService.kt").readText()

        assertTrue(source.contains("LookupPopupHtml::entryJsonString"))
        assertTrue(source.contains("replacePopupResults(\${pendingResults.size}, \$initialEntries)"))
        assertTrue(source.contains("window.hoshiSelection && window.hoshiSelection.clearSelection();"))
        assertTrue(source.contains("if (webViewTag.contentReady) return@contentReady"))
        assertTrue(source.contains("MeasureSpec.makeMeasureSpec(maxLookupHeightPx, MeasureSpec.EXACTLY)"))
    }
}
