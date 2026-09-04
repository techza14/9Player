package moe.tekuza.m9player.legado.reader.entities

import java.text.DecimalFormat

internal data class TextPage(
    var index: Int = 0,
    var text: String = "",
    var title: String = "",
    var chapterSize: Int = 0,
    var chapterIndex: Int = 0,
    var pageInChapter: Int = 0,
    var chapterPageCount: Int = 1,
    var globalIndex: Int = 0,
    var totalPages: Int = 1,
    var charStart: Int = 0,
    var charEnd: Int = 0,
    var documentCharStart: Int = 0,
    var documentCharEnd: Int = 0,
    var documentCharCount: Int = 0,
    var isVolume: Boolean = false,
    var height: Float = 0f,
    var width: Float = 0f,
    private val textLines: ArrayList<TextLine> = arrayListOf()
) {
    val lines: List<TextLine> get() = textLines
    val lineSize: Int get() = textLines.size

    val readProgress: String
        get() {
            if (documentCharCount > 0 && documentCharEnd > 0) {
                val value = documentCharEnd.toDouble() / documentCharCount.toDouble()
                return readProgressFormatter.format(value.coerceIn(0.0, 1.0))
            }
            if (totalPages <= 0) return "0.0%"
            val value = (globalIndex + 1).toDouble() / totalPages.toDouble()
            return readProgressFormatter.format(value.coerceIn(0.0, 1.0))
        }

    fun addLine(line: TextLine) {
        textLines += line
    }

    fun getLine(index: Int): TextLine = textLines.getOrElse(index) { textLines.last() }

    fun containPos(chapterPos: Int): Boolean = chapterPos in charStart..<charEnd

    companion object {
        private val readProgressFormatter = DecimalFormat("0.0%")
    }
}
