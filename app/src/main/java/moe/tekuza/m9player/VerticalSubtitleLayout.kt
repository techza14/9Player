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
            columnCount = cells.maxOf { it.column } + 1,
            maxRows = cells.maxOf { it.row } + 1,
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
        val contentWidth = layout.contentWidth()
        val rightEdge = if (contentWidth < viewWidth) {
            viewWidth - ((viewWidth - contentWidth) * 0.5f)
        } else {
            viewWidth.toFloat()
        }
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
        for (cell in layout.cells) {
            val rect = cellRect(viewWidth, viewHeight, layout, cell)
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
        for (cell in layout.cells) {
            if (cell.sourceOffset !in start..end) continue
            val cellRect = cellRect(viewWidth, viewHeight, layout, cell)
            val rect = paint?.let { inkRectForCell(it, cell.char, cellRect) } ?: cellRect
            selectedRectsByColumn.getOrPut(cell.column) { ArrayList(4) }.add(cell.row to rect)
        }
        if (selectedRectsByColumn.isEmpty()) return emptyList()

        val rects = ArrayList<RectF>(selectedRectsByColumn.size)
        selectedRectsByColumn.forEach { (_, rowsInColumn) ->
            val sorted = rowsInColumn
                .distinctBy { it.first }
                .sortedBy { it.first }
            if (sorted.isEmpty()) return@forEach
            var runStartRow = sorted.first().first
            var previousRow = runStartRow
            val runRects = ArrayList<RectF>()
            runRects += sorted.first().second

            fun flushRun() {
                if (runRects.isEmpty()) return
                rects += mergeRectFs(runRects)
                runRects.clear()
            }

            for (i in 1 until sorted.size) {
                val (row, rect) = sorted[i]
                if (row == previousRow + 1) {
                    previousRow = row
                    runRects += rect
                } else {
                    flushRun()
                    runStartRow = row
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
        for (cell in layout.cells) {
            val rect = cellRect(viewWidth, viewHeight, layout, cell)
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
        return RectF(
            rects.minOf { it.left },
            rects.minOf { it.top },
            rects.maxOf { it.right },
            rects.maxOf { it.bottom }
        )
    }
}
