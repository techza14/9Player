package moe.tekuza.m9player.legado.reader.entities

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import moe.tekuza.m9player.EbookImageRef
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.legado.reader.page.ContentTextView

internal data class ImageColumn(
    override var start: Float,
    override var end: Float,
    val image: EbookImageRef,
    val width: Float,
    val height: Float,
    override var sourceStart: Int,
    override var sourceEnd: Int
) : BaseColumn {
    private var bitmap: Bitmap? = null

    override fun draw(view: ContentTextView, canvas: Canvas, line: TextLine, selected: Boolean) {
        val decoded = bitmap ?: BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)?.also {
            bitmap = it
        }
        val rect = when (line.layoutMode) {
            M9LayoutMode.HORIZONTAL -> RectF(start, line.crossStart, end, line.crossEnd)
            M9LayoutMode.VERTICAL -> RectF(line.lineTop, start, line.lineBottom, end)
        }
        val paint = view.contentPaint
        if (decoded != null) {
            val imageWidth = decoded.width.toFloat().coerceAtLeast(1f)
            val imageHeight = decoded.height.toFloat().coerceAtLeast(1f)
            val scale = minOf(rect.width() / imageWidth, rect.height() / imageHeight)
            val drawWidth = imageWidth * scale
            val drawHeight = imageHeight * scale
            val left = rect.left + (rect.width() - drawWidth) / 2f
            val top = rect.top + (rect.height() - drawHeight) / 2f
            canvas.drawBitmap(decoded, null, RectF(left, top, left + drawWidth, top + drawHeight), null)
            return
        }
        paint.style = Paint.Style.STROKE
        paint.color = view.textColor
        canvas.drawRect(rect.left, rect.top + 2f, rect.right, rect.bottom - 2f, paint)
        paint.style = Paint.Style.FILL
        val fallback = image.altText.ifBlank { "[image]" }
        val fontMetrics = paint.fontMetrics
        val baseline = rect.centerY() - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(fallback, rect.left + 8f, baseline, paint)
    }
}
