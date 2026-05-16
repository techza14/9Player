package moe.tekuza.m9player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookReaderVerticalCueSourceTest {
    @Test
    fun verticalCueRenderingUsesSharedNativeGlyphEngine() {
        val readerSource = File("src/main/java/moe/tekuza/m9player/BookReaderActivity.kt").readText()
        val glyphEngineSource = File("src/main/java/moe/tekuza/m9player/VerticalTextGlyphEngine.kt").readText()

        assertTrue(readerSource.contains("VerticalSubtitleView"))
        assertTrue(readerSource.contains("VerticalLookupSubtitleView"))
        assertTrue(readerSource.contains("VerticalSubtitleLayoutEngine.build("))
        assertTrue(glyphEngineSource.contains("private fun presentationChar"))
        assertTrue(glyphEngineSource.contains("fun draw(canvas: Canvas"))
        assertTrue(glyphEngineSource.contains("fun inkRect("))
    }

    @Test
    fun verticalLyricsCueItemsUseCompactOuterSpacing() {
        val source = File("src/main/java/moe/tekuza/m9player/BookReaderActivity.kt").readText()

        assertTrue(source.contains("horizontalArrangement = Arrangement.spacedBy(0.dp)"))
        assertTrue(source.contains("contentPadding = PaddingValues(horizontal = BOOK_VERTICAL_CUE_EDGE_PADDING)"))
        assertTrue(source.contains("BOOK_VERTICAL_CUE_ITEM_HORIZONTAL_PADDING = 0.dp"))
        assertTrue(source.contains("BOOK_VERTICAL_CUE_EDGE_PADDING = 28.dp"))
        assertTrue(source.contains("BOOK_VERTICAL_COLUMN_WIDTH_FACTOR"))
        assertTrue(source.contains("BOOK_VERTICAL_CUE_GLYPH_SAFETY_WIDTH"))
        assertTrue(source.contains("onClick = { jumpToCue(index) }"))
        assertTrue(source.contains("onDisplayTap = {"))
        assertTrue(source.contains("hoshiLookupSelectionCueIndex = resolvedCueIndex"))
        assertTrue(source.contains("initialSelectionEndExclusive"))
        assertTrue(source.contains("isVertical = false"))
    }
}
