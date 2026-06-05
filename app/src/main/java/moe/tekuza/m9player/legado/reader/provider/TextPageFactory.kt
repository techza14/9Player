package moe.tekuza.m9player.legado.reader.provider

import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.EbookDocument
import moe.tekuza.m9player.legado.reader.M9ReadBookConfig
import moe.tekuza.m9player.legado.reader.entities.TextChapter
import moe.tekuza.m9player.legado.reader.entities.TextPage

internal class TextPageFactory(
    private val config: M9ReadBookConfig
) {
    fun createPages(
        document: EbookDocument,
        contentWidthPx: Int,
        contentHeightPx: Int
    ): List<TextPage> {
        val chapters = document.chapters.mapIndexed { index, chapter ->
            layoutChapter(document, index, contentWidthPx, contentHeightPx)
        }
        val allPages = chapters.flatMap { it.pages }.ifEmpty {
            listOf(
                TextPage(
                    index = 0,
                    pageInChapter = 0,
                    chapterPageCount = 1,
                    globalIndex = 0,
                    totalPages = 1,
                    chapterIndex = 0,
                    chapterSize = 1,
                    title = document.title,
                    text = "没有可显示的文本。",
                    charStart = 0,
                    charEnd = 0
                )
            )
        }
        return assignGlobalPageNumbers(allPages, document, contentWidthPx, contentHeightPx)
    }

    fun createChapterPages(
        document: EbookDocument,
        chapterIndex: Int,
        contentWidthPx: Int,
        contentHeightPx: Int
    ): List<TextPage> {
        val safeIndex = chapterIndex.coerceIn(0, document.chapters.lastIndex.coerceAtLeast(0))
        val pages = document.chapters.getOrNull(safeIndex)
            ?.let { layoutChapter(document, safeIndex, contentWidthPx, contentHeightPx).pages }
            .orEmpty()
        return assignGlobalPageNumbers(
            pages.ifEmpty {
                listOf(
                    TextPage(
                        index = 0,
                        pageInChapter = 0,
                        chapterPageCount = 1,
                        globalIndex = 0,
                        totalPages = 1,
                        chapterIndex = safeIndex,
                        chapterSize = document.chapters.size.coerceAtLeast(1),
                        title = document.title,
                        text = "没有可显示的文本。",
                        charStart = 0,
                        charEnd = 0
                    )
                )
            },
            document,
            contentWidthPx,
            contentHeightPx
        )
    }

    private fun layoutChapter(
        document: EbookDocument,
        index: Int,
        contentWidthPx: Int,
        contentHeightPx: Int
    ): TextChapter {
        val chapter = document.chapters[index]
        val textChapter = TextChapter(
            chapterIndex = index,
            title = chapter.title,
            text = chapter.text,
            chaptersSize = document.chapters.size,
            images = chapter.images,
            rubySpans = if (config.showRubyText) chapter.rubySpans else emptyList()
        )
        return when (config.layoutMode) {
            M9LayoutMode.HORIZONTAL -> TextChapterLayout(config, contentWidthPx, contentHeightPx).layout(textChapter)
            M9LayoutMode.VERTICAL -> VerticalTextChapterLayout(config, contentWidthPx, contentHeightPx).layout(textChapter)
        }
    }

    private fun assignGlobalPageNumbers(
        pages: List<TextPage>,
        document: EbookDocument,
        contentWidthPx: Int,
        contentHeightPx: Int
    ): List<TextPage> {
        val total = pages.size.coerceAtLeast(1)
        val chapterOffsets = IntArray(document.chapters.size)
        var charTotal = 0
        document.chapters.forEachIndexed { index, chapter ->
            chapterOffsets[index] = charTotal
            charTotal += chapter.text.length.coerceAtLeast(1)
        }
        return pages.mapIndexed { index, page ->
            page.globalIndex = index
            page.totalPages = total
            val chapterOffset = chapterOffsets.getOrElse(page.chapterIndex) { 0 }
            page.documentCharStart = (chapterOffset + page.charStart).coerceAtLeast(0)
            page.documentCharEnd = (chapterOffset + page.charEnd).coerceAtLeast(page.documentCharStart + 1)
            page.documentCharCount = charTotal.coerceAtLeast(1)
            if (page.height <= 0f) page.height = contentHeightPx.toFloat()
            if (page.width <= 0f) page.width = contentWidthPx.toFloat()
            page
        }
    }
}
