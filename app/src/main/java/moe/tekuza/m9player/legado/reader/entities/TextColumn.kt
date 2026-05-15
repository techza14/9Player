package moe.tekuza.m9player.legado.reader.entities

import android.graphics.Canvas
import android.graphics.RectF
import moe.tekuza.m9player.VerticalTextGlyphEngine
import moe.tekuza.m9player.legado.reader.page.ContentTextView
import moe.tekuza.m9player.legado.reader.M9LayoutMode

internal data class TextColumn(
    override var start: Float,
    override var end: Float,
    val charData: String,
    override var sourceStart: Int,
    override var sourceEnd: Int
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
            }
            M9LayoutMode.VERTICAL -> {
                VerticalTextGlyphEngine.draw(
                    canvas = canvas,
                    sourcePaint = paint,
                    text = charData,
                    rect = RectF(line.lineTop, start, line.lineBottom, end)
                )
            }
        }
    }
}
