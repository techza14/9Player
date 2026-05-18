package moe.tekuza.m9player.legado.reader.entities

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import moe.tekuza.m9player.VerticalTextGlyphEngine
import moe.tekuza.m9player.legado.reader.page.ContentTextView
import moe.tekuza.m9player.legado.reader.M9LayoutMode

internal data class TextColumn(
    override var start: Float,
    override var end: Float,
    val charData: String,
    override var sourceStart: Int,
    override var sourceEnd: Int,
    val rubyText: String? = null,
    var rubySourceStart: Int = sourceStart,
    var rubySourceEnd: Int = sourceEnd
) : BaseColumn {
    var selected: Boolean = false
    var isSearchResult: Boolean = false

    override fun draw(view: ContentTextView, canvas: Canvas, line: TextLine, selected: Boolean) {
        val paint = view.contentPaint
        paint.color = when {
            selected || this.selected || isSearchResult -> view.highlightTextColor
            line.isReadAloud -> view.highlightTextColor
            else -> view.textColor
        }
        when (line.layoutMode) {
            M9LayoutMode.HORIZONTAL -> {
                canvas.drawText(charData, start, line.lineBase, paint)
                drawHorizontalRuby(view, canvas, line)
            }
            M9LayoutMode.VERTICAL -> {
                val glyphRight = (line.lineBottom - line.rubyReservePx).coerceAtLeast(line.lineTop)
                val rect = RectF(line.lineTop, start, glyphRight, end)
                if (VerticalTextGlyphEngine.isAsciiAssistToken(charData)) {
                    VerticalTextGlyphEngine.drawLatinRun(
                        canvas = canvas,
                        sourcePaint = paint,
                        text = charData,
                        rect = rect
                    )
                } else {
                    VerticalTextGlyphEngine.draw(
                        canvas = canvas,
                        sourcePaint = paint,
                        text = charData,
                        rect = rect
                    )
                }
                drawVerticalRuby(view, canvas, line)
            }
        }
    }

    private fun drawHorizontalRuby(view: ContentTextView, canvas: Canvas, line: TextLine) {
        val annotation = rubyText?.takeIf { it.isNotBlank() } ?: return
        if (sourceStart != rubySourceStart) return
        val columns = rubyGroupColumns(line).takeIf { it.isNotEmpty() } ?: return
        val left = columns.minOf { it.start }
        val right = columns.maxOf { it.end }
        val paint = view.contentPaint
        val oldSize = paint.textSize
        val oldAlign = paint.textAlign
        val oldBold = paint.isFakeBoldText
        paint.textSize = (oldSize * RUBY_TEXT_RATIO).coerceAtLeast(8f)
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = false
        val baseline = line.lineTop + line.rubyReservePx.coerceAtLeast(paint.textSize) -
            (paint.ascent() + paint.descent()) * 0.5f - paint.textSize * 0.18f
        canvas.drawText(annotation, (left + right) / 2f, baseline, paint)
        paint.textSize = oldSize
        paint.textAlign = oldAlign
        paint.isFakeBoldText = oldBold
    }

    private fun drawVerticalRuby(view: ContentTextView, canvas: Canvas, line: TextLine) {
        val annotation = rubyText?.takeIf { it.isNotBlank() } ?: return
        if (sourceStart != rubySourceStart) return
        val columns = rubyGroupColumns(line).takeIf { it.isNotEmpty() } ?: return
        val top = columns.minOf { it.start }
        val bottom = columns.maxOf { it.end }
        val paint = view.contentPaint
        val oldSize = paint.textSize
        val oldAlign = paint.textAlign
        val oldBold = paint.isFakeBoldText
        paint.textSize = (oldSize * RUBY_TEXT_RATIO).coerceAtLeast(8f)
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = false
        val stripWidth = line.rubyReservePx.coerceAtLeast(paint.textSize)
        val left = line.lineBottom - stripWidth
        val right = line.lineBottom
        val unitHeight = ((bottom - top) / annotation.length.coerceAtLeast(1))
            .coerceAtLeast(paint.textSize * 0.82f)
        var y = top
        annotation.forEach { char ->
            VerticalTextGlyphEngine.draw(
                canvas = canvas,
                sourcePaint = paint,
                text = char.toString(),
                rect = RectF(left, y, right, y + unitHeight)
            )
            y += unitHeight
        }
        paint.textSize = oldSize
        paint.textAlign = oldAlign
        paint.isFakeBoldText = oldBold
    }

    private fun rubyGroupColumns(line: TextLine): List<TextColumn> {
        return line.columns
            .filterIsInstance<TextColumn>()
            .filter { column ->
                column.sourceStart >= rubySourceStart && column.sourceEnd <= rubySourceEnd
            }
    }

    private companion object {
        private const val RUBY_TEXT_RATIO = 0.42f
    }
}
