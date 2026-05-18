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
            val textChapter = TextChapter(
                chapterIndex = index,
                title = chapter.title,
                text = chapter.text,
                chaptersSize = document.chapters.size,
                images = chapter.images,
                rubySpans = if (config.showRubyText) chapter.rubySpans else emptyList()
            )
            when (config.layoutMode) {
                M9LayoutMode.HORIZONTAL -> TextChapterLayout(config, contentWidthPx, contentHeightPx).layout(textChapter)
                M9LayoutMode.VERTICAL -> VerticalTextChapterLayout(config, contentWidthPx, contentHeightPx).layout(textChapter)
            }
            textChapter
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
        val total = allPages.size.coerceAtLeast(1)
        return allPages.mapIndexed { index, page ->
            page.globalIndex = index
            page.totalPages = total
            if (page.height <= 0f) page.height = contentHeightPx.toFloat()
            if (page.width <= 0f) page.width = contentWidthPx.toFloat()
            page
        }
    }
}
