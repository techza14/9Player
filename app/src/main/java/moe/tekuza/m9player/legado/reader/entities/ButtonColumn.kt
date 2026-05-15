package moe.tekuza.m9player.legado.reader.entities

import android.graphics.Canvas
import android.graphics.Paint
import moe.tekuza.m9player.legado.reader.page.ContentTextView

internal data class ButtonColumn(
    override var start: Float,
    override var end: Float,
    val label: String,
    override var sourceStart: Int,
    override var sourceEnd: Int
) : BaseColumn {
    override fun draw(view: ContentTextView, canvas: Canvas, line: TextLine, selected: Boolean) {
        val paint = view.contentPaint
        paint.style = Paint.Style.FILL
        paint.color = 0x1F000000
        canvas.drawRoundRect(start, 0f, end, line.height, 10f, 10f, paint)
        paint.color = view.textColor
        canvas.drawText(label, start + 10f, line.lineBase - line.lineTop, paint)
    }
}
