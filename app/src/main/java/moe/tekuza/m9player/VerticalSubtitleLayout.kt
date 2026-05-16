package moe.tekuza.m9player

import android.graphics.Canvas
import android.graphics.RectF
import android.text.TextPaint
import kotlin.math.floor

internal data class VerticalSubtitleTapResult(
    val sourceOffset: Int,
    val logical: Int,
    val row: Int,
    val column: Int,
    val rect: RectF
)

internal data class VerticalSubtitleCell(
    val sourceOffset: Int,
    val logical: Int,
    val row: Int,
    val column: Int,
    val char: String
)

internal data class VerticalSubtitleLayout(
    val cells: List<VerticalSubtitleCell>,
    val columnCount: Int,
    val maxRows: Int,
    val cellWidth: Float,
    val cellHeight: Float
) {
    fun contentWidth(): Float = columnCount * cellWidth
}

internal object VerticalSubtitleLayoutEngine {
    fun build(
        text: String,
        paint: TextPaint,
        viewHeight: Int,
        lineHeightPx: Float = paint.textSize
    ): VerticalSubtitleLayout? {
        if (text.isBlank() || viewHeight <= 0) return null

        val cellHeight = lineHeightPx.coerceAtLeast(paint.textSize).coerceAtLeast(1f)
        val cellWidth = VerticalTextGlyphEngine.estimateCellWidth(paint)
        val rows = floor(viewHeight / cellHeight).toInt().coerceAtLeast(1)
        val cells = ArrayList<VerticalSubtitleCell>(text.length)
        var row = 0
        var column = 0
        var logical = 0
        var maxColumn = 0
        var maxRow = 0

        text.forEachIndexed { index, ch ->
            if (ch == '\r') return@forEachIndexed
            if (ch == '\n') {
                if (row > 0 || cells.isNotEmpty()) {
                    column += 1
                    row = 0
                }
                return@forEachIndexed
            }
            if (row >= rows) {
                column += 1
                row = 0
            }
            maxColumn = maxOf(maxColumn, column)
            maxRow = maxOf(maxRow, row)
            cells += VerticalSubtitleCell(
                sourceOffset = index,
                logical = logical++,
                row = row,
                column = column,
                char = ch.toString()
            )
            row += 1
        }

        if (cells.isEmpty()) return null
        return VerticalSubtitleLayout(
            cells = cells,
            columnCount = maxColumn + 1,
            maxRows = maxRow + 1,
            cellWidth = cellWidth,
            cellHeight = cellHeight
        )
    }

    fun cellRect(
        viewWidth: Int,
        viewHeight: Int,
        layout: VerticalSubtitleLayout,
        cell: VerticalSubtitleCell
    ): RectF {
        return cellRect(viewWidth, viewHeight, layout, cell, layoutRightEdge(viewWidth, layout))
    }

    private fun layoutRightEdge(viewWidth: Int, layout: VerticalSubtitleLayout): Float {
        val contentWidth = layout.contentWidth()
        return if (contentWidth < viewWidth) {
            viewWidth - ((viewWidth - contentWidth) * 0.5f)
        } else {
            viewWidth.toFloat()
        }
    }

    private fun cellRect(
        viewWidth: Int,
        viewHeight: Int,
        layout: VerticalSubtitleLayout,
        cell: VerticalSubtitleCell,
        rightEdge: Float
    ): RectF {
        val left = (rightEdge - (cell.column + 1) * layout.cellWidth)
        val top = (cell.row * layout.cellHeight)
        return RectF(
            left.coerceAtLeast(0f),
            top.coerceAtLeast(0f),
            (left + layout.cellWidth).coerceAtMost(viewWidth.toFloat()),
            (top + layout.cellHeight).coerceAtMost(viewHeight.toFloat())
        )
    }

    fun hitTest(
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
        layout: VerticalSubtitleLayout,
        paint: TextPaint? = null
    ): VerticalSubtitleTapResult? {
        val rightEdge = layoutRightEdge(viewWidth, layout)
        for (cell in layout.cells) {
            val rect = cellRect(viewWidth, viewHeight, layout, cell, rightEdge)
            if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) {
                val resolvedRect = paint?.let { inkRectForCell(it, cell.char, rect) } ?: rect
                return VerticalSubtitleTapResult(
                    sourceOffset = cell.sourceOffset,
                    logical = cell.logical,
                    row = cell.row,
                    column = cell.column,
                    rect = resolvedRect
                )
            }
        }
        return null
    }

    fun selectionRects(
        range: IntRange,
        viewWidth: Int,
        viewHeight: Int,
        layout: VerticalSubtitleLayout,
        paint: TextPaint? = null
    ): List<RectF> {
        val start = minOf(range.first, range.last)
        val end = maxOf(range.first, range.last)
        val selectedRectsByColumn = linkedMapOf<Int, MutableList<Pair<Int, RectF>>>()
        val rightEdge = layoutRightEdge(viewWidth, layout)
        for (cell in layout.cells) {
            if (cell.sourceOffset !in start..end) continue
            val cellRect = cellRect(viewWidth, viewHeight, layout, cell, rightEdge)
            val rect = paint?.let { inkRectForCell(it, cell.char, cellRect) } ?: cellRect
            selectedRectsByColumn.getOrPut(cell.column) { ArrayList(4) }.add(cell.row to rect)
        }
        if (selectedRectsByColumn.isEmpty()) return emptyList()

        val rects = ArrayList<RectF>(selectedRectsByColumn.size)
        selectedRectsByColumn.forEach { (_, rowsInColumn) ->
            if (rowsInColumn.isEmpty()) return@forEach
            var previousRow = rowsInColumn.first().first
            val runRects = ArrayList<RectF>()
            runRects += rowsInColumn.first().second

            fun flushRun() {
                if (runRects.isEmpty()) return
                rects += mergeRectFs(runRects)
                runRects.clear()
            }

            for (i in 1 until rowsInColumn.size) {
                val (row, rect) = rowsInColumn[i]
                if (row == previousRow + 1) {
                    previousRow = row
                    runRects += rect
                } else {
                    flushRun()
                    previousRow = row
                    runRects += rect
                }
            }
            flushRun()
        }
        return rects
    }

    fun draw(
        canvas: Canvas,
        textPaint: TextPaint,
        layout: VerticalSubtitleLayout,
        viewWidth: Int,
        viewHeight: Int
    ) {
        val rightEdge = layoutRightEdge(viewWidth, layout)
        for (cell in layout.cells) {
            val rect = cellRect(viewWidth, viewHeight, layout, cell, rightEdge)
            VerticalTextGlyphEngine.draw(canvas, textPaint, cell.char, rect)
        }
    }

    private fun inkRectForCell(
        paint: TextPaint,
        text: String,
        rect: RectF
    ): RectF {
        return VerticalTextGlyphEngine.inkRect(paint, text, rect)
    }

    private fun mergeRectFs(rects: List<RectF>): RectF {
        val first = rects.first()
        var left = first.left
        var top = first.top
        var right = first.right
        var bottom = first.bottom
        for (i in 1 until rects.size) {
            val rect = rects[i]
            left = minOf(left, rect.left)
            top = minOf(top, rect.top)
            right = maxOf(right, rect.right)
            bottom = maxOf(bottom, rect.bottom)
        }
        return RectF(left, top, right, bottom)
    }
}
