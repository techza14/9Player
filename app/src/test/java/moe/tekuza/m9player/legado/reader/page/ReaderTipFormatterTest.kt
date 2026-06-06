package moe.tekuza.m9player.legado.reader.page

import moe.tekuza.m9player.ReaderTipContent
import moe.tekuza.m9player.legado.reader.entities.TextPage
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTipFormatterTest {

    @Test
    fun text_usesLegadoProgressDefaultsAndLimitedSlotAlternates() {
        val page = TextPage(
            chapterIndex = 2,
            chapterSize = 120,
            pageInChapter = 0,
            chapterPageCount = 10,
            globalIndex = 9,
            totalPages = 100,
            documentCharEnd = 85,
            documentCharCount = 1000,
            title = "Chapter"
        )

        assertEquals("1/10", tipText(ReaderTipContent.PAGE, page, alternate = false))
        assertEquals("1/10", tipText(ReaderTipContent.PAGE, page, alternate = true))
        assertEquals("1/10", tipText(ReaderTipContent.PAGE_OR_PROGRESS, page, alternate = false))
        assertEquals("3/120", tipText(ReaderTipContent.PAGE_OR_PROGRESS, page, alternate = true))
        assertEquals("8.5%", tipText(ReaderTipContent.TOTAL_PROGRESS, page, alternate = false))
        assertEquals("10.0%", tipText(ReaderTipContent.TOTAL_PROGRESS, page, alternate = true))
        assertEquals("3/120", tipText(ReaderTipContent.CHAPTER_PROGRESS, page, alternate = false))
        assertEquals("3/120", tipText(ReaderTipContent.CHAPTER_PROGRESS, page, alternate = true))
        assertEquals("1/10  8.5%", tipText(ReaderTipContent.PAGE_AND_TOTAL, page, alternate = false))
        assertEquals("1/10  8.5%", tipText(ReaderTipContent.PAGE_AND_TOTAL, page, alternate = true))
        assertEquals(false, ReaderTipFormatter.isProgressTip(ReaderTipContent.PAGE))
        assertEquals(true, ReaderTipFormatter.isProgressTip(ReaderTipContent.PAGE_OR_PROGRESS))
        assertEquals(true, ReaderTipFormatter.isProgressTip(ReaderTipContent.TOTAL_PROGRESS))
        assertEquals(false, ReaderTipFormatter.isProgressTip(ReaderTipContent.CHAPTER_PROGRESS))
        assertEquals(false, ReaderTipFormatter.isProgressTip(ReaderTipContent.PAGE_AND_TOTAL))
    }

    private fun tipText(content: ReaderTipContent, page: TextPage, alternate: Boolean): String {
        return ReaderTipFormatter.text(
            content = content,
            page = page,
            alternateProgress = alternate,
            bookTitle = "Book",
            chapterTitle = "Chapter",
            clockText = "12:34",
            batteryPercent = 88
        )
    }
}
