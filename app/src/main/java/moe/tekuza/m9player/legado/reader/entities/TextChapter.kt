package moe.tekuza.m9player.legado.reader.entities

import moe.tekuza.m9player.EbookImageRef
import moe.tekuza.m9player.EbookRubySpan
import kotlin.math.abs
import kotlin.math.min

internal data class TextChapter(
    val chapterIndex: Int,
    val title: String,
    val text: String,
    val chaptersSize: Int,
    val images: Map<Int, EbookImageRef> = emptyMap(),
    val rubySpans: List<EbookRubySpan> = emptyList()
) {
    private val textPages = arrayListOf<TextPage>()
    val pages: List<TextPage> get() = textPages
    val pageSize: Int get() = textPages.size
    val lastIndex: Int get() = textPages.lastIndex
    val isCompleted: Boolean get() = true

    fun addPage(page: TextPage) {
        page.textChapter = this
        textPages += page
    }

    fun getPage(index: Int): TextPage? = pages.getOrNull(index)

    fun getPageByReadPos(readPos: Int): TextPage? = getPage(getPageIndexByCharIndex(readPos))

    fun getReadLength(pageIndex: Int): Int {
        if (pageIndex < 0 || pages.isEmpty()) return 0
        return pages[min(pageIndex, lastIndex)].chapterPosition
    }

    fun getPageIndexByCharIndex(charIndex: Int): Int {
        if (pages.isEmpty()) return -1
        val index = pages.binarySearchBy(charIndex) { it.chapterPosition }
        return abs(index + 1) - 1
    }

    fun clearSearchResult() {
        pages.forEach { page ->
            page.lines.forEach { line ->
                line.columns.forEach { column ->
                    if (column is TextColumn) {
                        column.selected = false
                        column.isSearchResult = false
                    }
                }
            }
        }
    }
}
