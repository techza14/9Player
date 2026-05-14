package moe.tekuza.m9player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookReaderHoshiCueWebViewSourceTest {
    @Test
    fun verticalCueTypographyIsConfigurableAndTighterThanHorizontal() {
        val source = File("src/main/java/moe/tekuza/m9player/BookReaderHoshiCueWebView.kt").readText()

        assertTrue(source.contains("data class BookCueTypography"))
        assertTrue(source.contains("fontSizeScale: Float = 1f"))
        assertTrue(source.contains("verticalLineHeight: Float = 1.08f"))
        assertTrue(source.contains("verticalColumnWidthFactor: Float = 1.0f"))
        assertTrue(source.contains("horizontalLineHeight: Float = 1.45f"))
        assertTrue(source.contains("verticalLetterSpacingEm: Float = -0.03f"))
        assertTrue(source.contains("sentenceGapEm: Float = 0f"))
        assertTrue(source.contains("resolvedFontSizeCssPx"))
        assertTrue(source.contains("resolvedLineHeight"))
        assertTrue(source.contains("resolvedLetterSpacing"))
        assertTrue(source.contains("resolvedSentenceGap"))
        assertTrue(source.contains("renderBookCueSegments(text)"))
        assertTrue(source.contains("class=\"cue-segment\""))
        assertTrue(source.contains("data-source-start"))
        assertTrue(source.contains("getSourceOffset"))
        assertTrue(source.contains("getVisualCharacterAtPoint"))
        assertTrue(source.contains("var hit = window.hoshiReader.getVisualCharacterAtPoint(x, y);"))
        assertTrue(source.contains("collectHighlightRects"))
        assertTrue(source.contains("collectTextRectsForRanges"))
        assertTrue(source.contains("textRects: textRects"))
        assertTrue(source.contains("renderHighlightOverlay"))
        assertTrue(source.contains("rgba(161, 161, 170, 0.35)"))
        assertTrue(source.contains("display: inline;"))
        assertTrue(source.contains("val rootAlignItems = \"center\""))
        assertTrue(source.contains("\"12px 0px 16px 0px\""))
        assertTrue(source.contains("letter-spacing: \$resolvedLetterSpacing;"))
        assertTrue(source.contains("line-height: \$resolvedLineHeight;"))
        assertTrue(source.contains("margin-inline-end: \$resolvedSentenceGap;"))
        assertTrue(source.contains("webViewOriginInWindow.x.toDouble() / density.density"))
        assertTrue(source.contains("webViewOriginInWindow.y.toDouble() / density.density"))
    }

    @Test
    fun verticalLyricsCueItemsUseCompactOuterSpacing() {
        val source = File("src/main/java/moe/tekuza/m9player/BookReaderActivity.kt").readText()

        assertTrue(source.contains("horizontalArrangement = Arrangement.spacedBy(0.dp)"))
        assertTrue(source.contains("contentPadding = PaddingValues(horizontal = BOOK_VERTICAL_CUE_EDGE_PADDING)"))
        assertTrue(source.contains("BOOK_VERTICAL_CUE_ITEM_HORIZONTAL_PADDING = 0.dp"))
        assertTrue(source.contains("BOOK_VERTICAL_CUE_EDGE_PADDING = 28.dp"))
        assertTrue(source.contains("DefaultBookCueTypography.verticalColumnWidthFactor"))
        assertTrue(source.contains("BOOK_VERTICAL_CUE_GLYPH_SAFETY_WIDTH"))
        assertTrue(source.contains("onClick = { jumpToCue(index) }"))
        assertTrue(source.contains("onDisplayTap = onClick"))
        assertTrue(source.contains("hoshiLookupSelectionCueIndex = resolvedCueIndex"))
        assertTrue(source.contains("initialSelectionEndExclusive"))
        assertTrue(source.contains("isVertical = false"))
    }
}
