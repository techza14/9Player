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
                title = chapter.title.ifBlank { document.title },
                text = chapter.text,
                chaptersSize = document.chapters.size,
                images = chapter.images
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
            page
        }
    }
}

private fun String.normalizeLayoutWhitespace(): String {
    return replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace('\u00A0', ' ')
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}
