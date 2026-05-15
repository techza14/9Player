package moe.tekuza.m9player.legado.reader.entities

import android.graphics.Canvas
import android.graphics.Paint
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import kotlin.math.max
import kotlin.math.min

internal data class TextLine(
    var text: String = "",
    var lineTop: Float = 0f,
    var lineBase: Float = 0f,
    var lineBottom: Float = 0f,
    var crossStart: Float = 0f,
    var crossEnd: Float = 0f,
    var paragraphNum: Int = 0,
    var chapterPosition: Int = 0,
    var pagePosition: Int = 0,
    var isTitle: Boolean = false,
    var isParagraphEnd: Boolean = false,
    var layoutMode: M9LayoutMode = M9LayoutMode.HORIZONTAL,
    var startX: Float = 0f,
    private val textColumns: ArrayList<BaseColumn> = arrayListOf()
) {
    val columns: List<BaseColumn> get() = textColumns
    val charSize: Int get() = text.length
    val lineStart: Float get() = textColumns.firstOrNull()?.start ?: startX
    val lineEnd: Float get() = textColumns.lastOrNull()?.end ?: startX
    val height: Float get() = crossEnd - crossStart
    val width: Float get() = lineBottom - lineTop
    var isReadAloud: Boolean = false

    fun addColumn(column: BaseColumn) {
        textColumns += column
    }

    fun draw(
        view: moe.tekuza.m9player.legado.reader.page.ContentTextView,
        canvas: Canvas,
        highlight: IntRange?,
        search: IntRange?
    ) {
        drawRangeBackground(canvas, view.highlightPaint, highlight)
        drawRangeBackground(canvas, view.searchPaint, search)
        columns.forEach { column ->
            val selected = column.intersects(highlight) || column.intersects(search)
            column.draw(view, canvas, this, selected)
        }
    }

    private fun drawRangeBackground(canvas: Canvas, paint: Paint, range: IntRange?) {
        if (range == null || columns.isEmpty()) return
        val selectedColumns = columns.filter { it.intersects(range) }
        if (selectedColumns.isEmpty()) return
        when (layoutMode) {
            M9LayoutMode.HORIZONTAL -> {
                val left = selectedColumns.minOf { it.start }
                val right = selectedColumns.maxOf { it.end }
                canvas.drawRect(left, crossStart, right, crossEnd, paint)
            }
            M9LayoutMode.VERTICAL -> {
                val top = selectedColumns.minOf { it.start }
                val bottom = selectedColumns.maxOf { it.end }
                canvas.drawRect(lineTop, top, lineBottom, bottom, paint)
            }
        }
    }

    private fun BaseColumn.intersects(range: IntRange?): Boolean {
        if (range == null) return false
        val start = max(sourceStart, range.first)
        val end = min(sourceEnd, range.last + 1)
        return start < end
    }
}
