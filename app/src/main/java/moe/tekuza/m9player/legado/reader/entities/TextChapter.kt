package moe.tekuza.m9player.legado.reader.entities

import moe.tekuza.m9player.EbookImageRef
import moe.tekuza.m9player.EbookRubySpan

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

    fun addPage(page: TextPage) {
        textPages += page
    }
}
