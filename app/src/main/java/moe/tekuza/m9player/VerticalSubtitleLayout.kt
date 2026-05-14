package moe.tekuza.m9player

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect as AndroidRect
import android.graphics.RectF
import android.text.TextPaint
import kotlin.math.ceil
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
    private val TopRightPunctuation = setOf(
        '。', '、', '︒', '︑', '︐', '︔', '，', '．'
    )

    private val CenterPunctuation = setOf(
        '・', '：', '︓', '︰', '︙'
    )

    private val SmallKana = setOf(
        'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ', 'っ', 'ゃ', 'ゅ', 'ょ', 'ゎ',
        'ァ', 'ィ', 'ゥ', 'ェ', 'ォ', 'ッ', 'ャ', 'ュ', 'ョ', 'ヮ', 'ヶ'
    )

    private val RotateClockwise = setOf(
        '「', '」', '『', '』', '（', '）', '(', ')', '［', '］', '[', ']',
        '｛', '｝', '{', '}', '〔', '〕', '【', '】', '〈', '〉', '《', '》',
        '〖', '〗', 'ー', '〜', '～', '…', '‥', '-', '_', '~'
    )

    private val VerticalPresentationForms = setOf(
        '︵', '︶', '︷', '︸', '︹', '︺', '︿', '﹀', '︽', '︾', '︻', '︼',
        '﹁', '﹂', '﹃', '﹄', '︙'
    )

    fun build(
        text: String,
        paint: TextPaint,
        viewHeight: Int,
        lineHeightPx: Float = paint.textSize
    ): VerticalSubtitleLayout? {
        if (text.isBlank() || viewHeight <= 0) return null

        val cellHeight = lineHeightPx.coerceAtLeast(paint.textSize).coerceAtLeast(1f)
        val cellWidth = estimateCellWidth(paint)
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
        val drawPaint = TextPaint(textPaint).apply {
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val baselineAdjust = -(drawPaint.ascent() + drawPaint.descent()) * 0.5f
        for (cell in layout.cells) {
            val rect = cellRect(viewWidth, viewHeight, layout, cell)
            drawOne(canvas, drawPaint, cell.char, rect, baselineAdjust)
        }
    }

    private fun drawOne(
        canvas: Canvas,
        paint: TextPaint,
        text: String,
        rect: RectF,
        baselineAdjust: Float
    ) {
        val ch = text.firstOrNull() ?: return
        when {
            ch in TopRightPunctuation -> drawTopRightPunctuation(canvas, paint, text, rect)
            ch in SmallKana -> drawOffsetText(canvas, paint, text, rect, baselineAdjust, rect.width() * 0.16f, -rect.height() * 0.10f)
            ch in CenterPunctuation -> drawOffsetText(canvas, paint, text, rect, baselineAdjust, 0f, -paint.textSize * 0.04f)
            else -> drawRotatableText(canvas, paint, text, rect, baselineAdjust)
        }
    }

    private fun inkRectForCell(
        paint: TextPaint,
        text: String,
        rect: RectF
    ): RectF {
        val ch = text.firstOrNull() ?: return rect
        val baselineAdjust = -(paint.ascent() + paint.descent()) * 0.5f
        return when {
            ch in TopRightPunctuation -> topRightPunctuationInkRect(paint, text, rect)
            ch in SmallKana -> offsetInkRect(paint, text, rect, baselineAdjust, rect.width() * 0.16f, -rect.height() * 0.10f)
            ch in CenterPunctuation -> offsetInkRect(paint, text, rect, baselineAdjust, 0f, -paint.textSize * 0.04f)
            else -> rotatableInkRect(paint, text, rect, baselineAdjust)
        }.let { clampAndPadInkRect(it, rect, paint.textSize) }
    }

    private fun offsetInkRect(
        paint: TextPaint,
        text: String,
        rect: RectF,
        baselineAdjust: Float,
        dx: Float,
        dy: Float
    ): RectF {
        val bounds = AndroidRect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val measuredWidth = paint.measureText(text).coerceAtLeast(bounds.width().toFloat())
        val cx = rect.centerX() + dx
        val baseline = rect.centerY() + dy + baselineAdjust
        return RectF(
            cx - measuredWidth / 2f,
            baseline + bounds.top,
            cx + measuredWidth / 2f,
            baseline + bounds.bottom
        )
    }

    private fun topRightPunctuationInkRect(
        paint: TextPaint,
        text: String,
        rect: RectF
    ): RectF {
        val markPaint = TextPaint(paint).apply {
            textAlign = Paint.Align.LEFT
        }
        val bounds = AndroidRect()
        markPaint.getTextBounds(text, 0, text.length, bounds)
        val targetRight = rect.right - rect.width() * 0.10f
        val targetTop = rect.top + rect.height() * 0.08f
        val x = targetRight - bounds.right
        val y = targetTop - bounds.top
        return RectF(
            x + bounds.left,
            y + bounds.top,
            x + bounds.right,
            y + bounds.bottom
        )
    }

    private fun rotatableInkRect(
        paint: TextPaint,
        text: String,
        rect: RectF,
        baselineAdjust: Float
    ): RectF {
        val rotation = rotationFor(text)
        if (rotation == 0f) {
            return offsetInkRect(paint, text, rect, baselineAdjust, 0f, 0f)
        }
        val base = offsetInkRect(paint, text, rect, baselineAdjust, 0f, 0f)
        val cx = rect.centerX()
        val cy = rect.centerY()
        val corners = listOf(
            base.left to base.top,
            base.right to base.top,
            base.right to base.bottom,
            base.left to base.bottom
        ).map { (x, y) -> rotatePoint(x, y, cx, cy, rotation) }
        return RectF(
            corners.minOf { it.first },
            corners.minOf { it.second },
            corners.maxOf { it.first },
            corners.maxOf { it.second }
        )
    }

    private fun rotatePoint(x: Float, y: Float, cx: Float, cy: Float, degrees: Float): Pair<Float, Float> {
        val radians = Math.toRadians(degrees.toDouble())
        val cos = kotlin.math.cos(radians).toFloat()
        val sin = kotlin.math.sin(radians).toFloat()
        val dx = x - cx
        val dy = y - cy
        return (cx + dx * cos - dy * sin) to (cy + dx * sin + dy * cos)
    }

    private fun clampAndPadInkRect(rect: RectF, cellRect: RectF, textSize: Float): RectF {
        val horizontalPad = (textSize * 0.08f).coerceAtMost(cellRect.width() * 0.12f)
        val verticalPad = (textSize * 0.04f).coerceAtMost(cellRect.height() * 0.08f)
        val minWidth = (textSize * 0.70f).coerceAtMost(cellRect.width())
        val minHeight = (textSize * 0.78f).coerceAtMost(cellRect.height())
        val expanded = RectF(
            rect.left - horizontalPad,
            rect.top - verticalPad,
            rect.right + horizontalPad,
            rect.bottom + verticalPad
        )
        if (expanded.width() < minWidth) {
            val extra = (minWidth - expanded.width()) / 2f
            expanded.left -= extra
            expanded.right += extra
        }
        if (expanded.height() < minHeight) {
            val extra = (minHeight - expanded.height()) / 2f
            expanded.top -= extra
            expanded.bottom += extra
        }
        return RectF(
            expanded.left.coerceAtLeast(cellRect.left),
            expanded.top.coerceAtLeast(cellRect.top),
            expanded.right.coerceAtMost(cellRect.right),
            expanded.bottom.coerceAtMost(cellRect.bottom)
        )
    }

    private fun mergeRectFs(rects: List<RectF>): RectF {
        return RectF(
            rects.minOf { it.left },
            rects.minOf { it.top },
            rects.maxOf { it.right },
            rects.maxOf { it.bottom }
        )
    }

    private fun drawTopRightPunctuation(
        canvas: Canvas,
        paint: TextPaint,
        text: String,
        rect: RectF
    ) {
        val markPaint = TextPaint(paint).apply {
            textAlign = Paint.Align.LEFT
        }
        val bounds = AndroidRect()
        markPaint.getTextBounds(text, 0, text.length, bounds)
        val targetRight = rect.right - rect.width() * 0.10f
        val targetTop = rect.top + rect.height() * 0.08f
        val x = targetRight - bounds.right
        val y = targetTop - bounds.top
        canvas.drawText(text, x, y, markPaint)
    }

    private fun drawOffsetText(
        canvas: Canvas,
        paint: TextPaint,
        text: String,
        rect: RectF,
        baselineAdjust: Float,
        dx: Float,
        dy: Float
    ) {
        val cx = rect.centerX() + dx
        val cy = rect.centerY() + dy
        canvas.drawText(text, cx, cy + baselineAdjust, paint)
    }

    private fun drawRotatableText(
        canvas: Canvas,
        paint: TextPaint,
        text: String,
        rect: RectF,
        baselineAdjust: Float
    ) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val rotation = rotationFor(text)
        if (rotation == 0f) {
            canvas.drawText(text, cx, cy + baselineAdjust, paint)
            return
        }
        canvas.save()
        canvas.rotate(rotation, cx, cy)
        if (shouldMirrorAfterRotation(text)) {
            canvas.scale(1f, -1f, cx, cy)
        }
        canvas.drawText(text, cx, cy + baselineAdjust, paint)
        canvas.restore()
    }

    private fun estimateCellWidth(paint: TextPaint): Float {
        val sampleWidth = maxOf(
            paint.measureText("国"),
            paint.measureText("あ"),
            paint.textSize
        )
        return ceil((sampleWidth * 1.12f).toDouble()).toFloat().coerceAtLeast(1f)
    }

    private fun rotationFor(text: String): Float {
        val ch = text.firstOrNull() ?: return 0f
        return when {
            ch in 'A'..'Z' || ch in 'a'..'z' -> 90f
            ch in '0'..'9' -> 0f
            ch in VerticalPresentationForms -> 0f
            ch in RotateClockwise -> 90f
            else -> 0f
        }
    }

    private fun shouldMirrorAfterRotation(text: String): Boolean {
        return text.firstOrNull() in setOf('ー', '〜', '～')
    }
}
