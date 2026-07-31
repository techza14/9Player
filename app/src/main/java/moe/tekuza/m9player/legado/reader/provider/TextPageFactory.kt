package moe.tekuza.m9player.legado.reader.provider

import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.EbookDocument
import moe.tekuza.m9player.legado.reader.M9ReadBookConfig
import moe.tekuza.m9player.legado.reader.entities.TextChapter
import moe.tekuza.m9player.legado.reader.entities.TextPage

/**
 * Counts "reader characters" (digits, Latin letters, kana and CJK ideographs) in the given range,
 * excluding punctuation, whitespace, ruby/HTML residue and other non-matchable code points.
 * Counts Unicode code points, so surrogate pairs (e.g. 𛿠) count as one character.
 */
internal fun String.readerCharCount(fromIndex: Int = 0, toIndex: Int = length): Int {
    var count = 0
    var index = fromIndex.coerceIn(0, length)
    val end = toIndex.coerceIn(index, length)
    while (index < end) {
        val codePoint = Character.codePointAt(this, index)
        if (codePoint.isReaderChar()) count += 1
        index += Character.charCount(codePoint)
    }
    return count
}

internal fun Int.isReaderChar(): Boolean = when (this) {
    in '0'.code..'9'.code,
    in 'A'.code..'Z'.code,
    in 'a'.code..'z'.code,
    '○'.code,
    '◯'.code,
    in '々'.code..'〇'.code,
    '〻'.code,
    in 'ぁ'.code..'ゖ'.code,
    in 'ゝ'.code..'ゞ'.code,
    in 'ァ'.code..'ヺ'.code,
    'ー'.code,
    in '０'.code..'９'.code,
    in 'Ａ'.code..'Ｚ'.code,
    in 'ａ'.code..'ｚ'.code,
    in 'ｦ'.code..'ﾝ'.code,
    in 0x2E80..0x2FDF,
    in 0x3400..0x4DBF,
    in 0x4E00..0x9FFF,
    in 0x20000..0x2A6DF,
    in 0x2A700..0x2B73F,
    in 0x2B740..0x2B81F,
    in 0x2B820..0x2CEAF,
    in 0x2CEB0..0x2EBEF,
    in 0x30000..0x3134F,
    in 0x31350..0x323AF,
    -> true
    else -> false
}

internal class TextPageFactory(
    private val config: M9ReadBookConfig,
    private val emptyPageText: String
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
                    text = emptyPageText,
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
                        text = emptyPageText,
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
        val chapterCount = document.chapters.size.coerceAtLeast(1)
        val chapterSemanticOffsets = IntArray(chapterCount)
        var semanticTotal = 0
        document.chapters.forEachIndexed { index, chapter ->
            chapterSemanticOffsets[index] = semanticTotal
            semanticTotal += chapter.text.readerCharCount()
        }
        val rawCursor = IntArray(chapterCount)
        val semanticCursor = IntArray(chapterCount)
        return pages.mapIndexed { index, page ->
            page.globalIndex = index
            page.totalPages = total
            val chapterIndex = page.chapterIndex.takeIf { it in 0 until chapterCount } ?: 0
            val chapterText = document.chapters.getOrNull(chapterIndex)?.text.orEmpty()
            val pageStart = page.charStart.coerceIn(0, chapterText.length)
            val pageEnd = page.charEnd.coerceIn(pageStart, chapterText.length)
            val gapChars = if (pageStart >= rawCursor[chapterIndex]) {
                chapterText.readerCharCount(rawCursor[chapterIndex], pageStart)
            } else {
                0
            }
            val pageChars = chapterText.readerCharCount(pageStart, pageEnd)
            val chapterBase = chapterSemanticOffsets[chapterIndex]
            page.documentCharStart = chapterBase + semanticCursor[chapterIndex] + gapChars
            page.documentCharEnd = page.documentCharStart + pageChars
            page.documentCharCount = semanticTotal
            rawCursor[chapterIndex] = pageEnd
            semanticCursor[chapterIndex] += gapChars + pageChars
            if (page.height <= 0f) page.height = contentHeightPx.toFloat()
            if (page.width <= 0f) page.width = contentWidthPx.toFloat()
            page
        }
    }
}
