package moe.tekuza.m9player.legado.reader.entities

import moe.tekuza.m9player.EbookImageRef
import moe.tekuza.m9player.EbookRubySpan

internal data class TextChapter(
    val chapterIndex: Int,
    val title: String,
    val text: String,
    val chaptersSize: Int,
    val images: Map<Int, EbookImageRef> = emptyMap(),
    val rubySpans: List<EbookRubySpan> = emptyList(),
    val isVolume: Boolean = false,
    /** 句尾处理（句子不跨页）：章节坐标的句子起点/结尾集合（来自音频 cue 匹配） */
    val sentenceStarts: Set<Int> = emptySet(),
    val sentenceEnds: Set<Int> = emptySet()
) {
    private val textPages = arrayListOf<TextPage>()
    val pages: List<TextPage> get() = textPages

    fun addPage(page: TextPage) {
        textPages += page
    }
}
