package moe.tekuza.m9player.legado.reader.entities

import java.text.DecimalFormat
import kotlin.math.min

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
    private val textLines: ArrayList<TextLine> = arrayListOf()
) {
    val lines: List<TextLine> get() = textLines
    val lineSize: Int get() = textLines.size
    val charSize: Int get() = text.length.coerceAtLeast(1)
    val chapterPosition: Int get() = charStart
    var textChapter: TextChapter? = null
    var isCompleted: Boolean = true
    var hasReadAloudSpan: Boolean = false

    val readProgress: String
        get() {
            if (totalPages <= 0) return "0.0%"
            val value = (globalIndex + 1).toDouble() / totalPages.toDouble()
            return readProgressFormatter.format(value.coerceIn(0.0, 1.0))
        }

    fun addLine(line: TextLine) {
        textLines += line
    }

    fun getLine(index: Int): TextLine = textLines.getOrElse(index) { textLines.last() }

    fun containPos(chapterPos: Int): Boolean = chapterPos in charStart..<charEnd

    fun getPosByLineColumn(lineIndex: Int, columnIndex: Int): Int {
        var length = 0
        val maxIndex = min(lineIndex, lineSize - 1)
        for (index in 0 until maxIndex) {
            length += textLines[index].charSize
            if (textLines[index].isParagraphEnd) length++
        }
        val columns = textLines[maxIndex].columns
        for (index in 0 until columnIndex.coerceAtMost(columns.size)) {
            val column = columns[index]
            if (column is TextColumn) length += column.charData.length
        }
        return length
    }

    fun upPageAloudSpan(aloudSpanStart: Int) {
        removePageAloudSpan()
        var lineStart = 0
        lines.forEach { line ->
            val lineEnd = lineStart + line.text.length + if (line.isParagraphEnd) 1 else 0
            if (aloudSpanStart in lineStart until lineEnd) {
                line.isReadAloud = true
                hasReadAloudSpan = true
            }
            lineStart = lineEnd
        }
    }

    fun removePageAloudSpan(): TextPage {
        if (!hasReadAloudSpan) return this
        lines.forEach { it.isReadAloud = false }
        hasReadAloudSpan = false
        return this
    }

    companion object {
        private val readProgressFormatter = DecimalFormat("0.0%")
    }
}
