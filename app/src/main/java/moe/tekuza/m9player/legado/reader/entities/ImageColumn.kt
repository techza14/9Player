package moe.tekuza.m9player.legado.reader.entities

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import moe.tekuza.m9player.decodeSampledBitmap
import moe.tekuza.m9player.EbookImageRef
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.legado.reader.page.ContentTextView
import kotlin.math.roundToInt

internal data class ImageColumn(
    override var start: Float,
    override var end: Float,
    val image: EbookImageRef,
    val width: Float,
    val height: Float,
    override var sourceStart: Int,
    override var sourceEnd: Int
) : BaseColumn {
    override fun draw(view: ContentTextView, canvas: Canvas, line: TextLine, selected: Boolean) {
        val rect = when (line.layoutMode) {
            M9LayoutMode.HORIZONTAL -> RectF(start, line.crossStart, end, line.crossEnd)
            M9LayoutMode.VERTICAL -> RectF(line.lineTop, start, line.lineBottom, end)
        }
        val decoded = ReaderImageBitmapCache.get(
            image = image,
            targetWidth = rect.width().roundToInt().coerceAtLeast(1),
            targetHeight = rect.height().roundToInt().coerceAtLeast(1)
        )
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

    private object ReaderImageBitmapCache {
        private const val MAX_CACHE_KIB = 12 * 1024

        private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_KIB) {
            override fun sizeOf(key: String, value: Bitmap): Int {
                return (value.allocationByteCount / 1024).coerceAtLeast(1)
            }
        }

        @Synchronized
        fun get(image: EbookImageRef, targetWidth: Int, targetHeight: Int): Bitmap? {
            val key = "${image.cacheIdentity()}:$targetWidth:$targetHeight"
            cache.get(key)?.let { return it }
            val bytes = image.readBytes() ?: return null
            val decoded = decodeSampledBitmap(
                bytes = bytes,
                targetWidthPx = targetWidth,
                targetHeightPx = targetHeight
            ) ?: return null
            cache.put(key, decoded)
            return decoded
        }
    }
}
