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
    var rubyReservePx: Float = 0f,
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
        selection: IntRange?,
        highlight: IntRange?,
        search: IntRange?
    ) {
        drawRangeBackground(canvas, view.selectionPaint, selection)
        drawRangeBackground(canvas, view.highlightPaint, highlight)
        drawRangeBackground(canvas, view.searchPaint, search)
        columns.forEach { column ->
            val selected = column.intersects(selection) || column.intersects(highlight) || column.intersects(search)
            column.draw(view, canvas, this, selected)
        }
    }

    private fun drawRangeBackground(canvas: Canvas, paint: Paint, range: IntRange?) {
        if (range == null || columns.isEmpty()) return
        val selectedColumns = columns.filter { it.intersects(range) }.sortedBy { it.start }
        if (selectedColumns.isEmpty()) return
        val mergeGap = when (layoutMode) {
            M9LayoutMode.HORIZONTAL -> max(1f, height * 0.25f)
            M9LayoutMode.VERTICAL -> max(1f, width * 0.25f)
        }
        var groupStart = selectedColumns.first()
        var groupEnd = groupStart
        selectedColumns.drop(1).forEach { column ->
            val visualContinuous = column.start - groupEnd.end <= mergeGap
            val sourceContinuous = column.sourceStart <= groupEnd.sourceEnd
            if (visualContinuous || sourceContinuous) {
                groupEnd = column
            } else {
                drawRangeSegment(canvas, paint, groupStart, groupEnd)
                groupStart = column
                groupEnd = column
            }
        }
        drawRangeSegment(canvas, paint, groupStart, groupEnd)
    }

    private fun drawRangeSegment(
        canvas: Canvas,
        paint: Paint,
        start: BaseColumn,
        end: BaseColumn
    ) {
        when (layoutMode) {
            M9LayoutMode.HORIZONTAL -> {
                canvas.drawRect(start.start, lineTop, end.end, lineBottom, paint)
            }
            M9LayoutMode.VERTICAL -> {
                canvas.drawRect(lineTop, start.start, lineBottom, end.end, paint)
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
