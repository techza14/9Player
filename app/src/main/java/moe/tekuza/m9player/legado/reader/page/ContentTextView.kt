package moe.tekuza.m9player.legado.reader.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import moe.tekuza.m9player.EbookImageRef
import moe.tekuza.m9player.VerticalTextGlyphEngine
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.legado.reader.entities.ImageColumn
import moe.tekuza.m9player.legado.reader.entities.TextColumn
import moe.tekuza.m9player.legado.reader.entities.TextPage

internal class ContentTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    internal data class AssistToken(
        val text: String,
        val rect: RectF,
        val sourceStart: Int,
        val sourceEnd: Int
    )

    val contentPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    val searchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    var textColor: Int = 0xFF2C241B.toInt()
        private set
    var highlightTextColor: Int = 0xFF2C241B.toInt()
        private set
    private var textSizePxValue: Float = spToPx(20f)
    private var page: TextPage? = null
    private var highlightRange: IntRange? = null
    private var searchRange: IntRange? = null

    val textSizePx: Float get() = textSizePxValue

    init {
        highlightPaint.color = 0x66E53935
        searchPaint.color = 0xAAFFD54F.toInt()
    }

    fun setTextColor(color: Int) {
        textColor = color
        invalidate()
    }

    fun setHighlightTextColor(color: Int) {
        highlightTextColor = color
        invalidate()
    }

    fun setTextSizePx(sizePx: Float) {
        textSizePxValue = sizePx
        contentPaint.textSize = sizePx
        invalidate()
    }

    fun setFakeBoldText(enabled: Boolean) {
        contentPaint.isFakeBoldText = enabled
        invalidate()
    }

    fun setReaderTypeface(typeface: Typeface?) {
        contentPaint.typeface = typeface
        invalidate()
    }

    fun setPage(page: TextPage?, highlight: IntRange?, search: IntRange?) {
        this.page = page
        highlightRange = highlight
        searchRange = search
        invalidate()
    }

    fun findImageAt(x: Float, y: Float): EbookImageRef? {
        val current = page ?: return null
        val localX = x - paddingLeft
        val localY = y - paddingTop
        current.lines.forEach { line ->
            val inLineBounds = when (line.layoutMode) {
                M9LayoutMode.HORIZONTAL -> localY >= line.crossStart && localY <= line.crossEnd
                M9LayoutMode.VERTICAL -> localX >= line.lineTop && localX <= line.lineBottom
            }
            if (!inLineBounds) return@forEach
            line.columns.forEach { column ->
                if (column !is ImageColumn) return@forEach
                val hit = when (line.layoutMode) {
                    M9LayoutMode.HORIZONTAL -> localX >= column.start && localX <= column.end
                    M9LayoutMode.VERTICAL -> localY >= column.start && localY <= column.end
                }
                if (hit) {
                    return column.image
                }
            }
        }
        return null
    }

    fun findAssistTokenAt(x: Float, y: Float): AssistToken? {
        val current = page ?: return null
        val localX = x - paddingLeft
        val localY = y - paddingTop
        current.lines.forEach { line ->
            if (line.layoutMode != M9LayoutMode.VERTICAL) return@forEach
            val inLineBounds = localX >= line.lineTop && localX <= line.lineBottom
            if (!inLineBounds) return@forEach
            line.columns.forEach { column ->
                if (column !is TextColumn) return@forEach
                if (!VerticalTextGlyphEngine.isAsciiAssistToken(column.charData)) return@forEach
                if (localY < column.start || localY > column.end) return@forEach
                return AssistToken(
                    text = column.charData,
                    rect = RectF(
                        line.lineTop + paddingLeft,
                        column.start + paddingTop,
                        line.lineBottom + paddingLeft,
                        column.end + paddingTop
                    ),
                    sourceStart = column.sourceStart,
                    sourceEnd = column.sourceEnd
                )
            }
        }
        return null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        contentPaint.textSize = textSizePxValue
        val current = page ?: return
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        if (current.lines.isEmpty()) {
            drawFallbackText(canvas, current.text)
            canvas.restore()
            return
        }
        current.lines.forEach { line ->
            when (line.layoutMode) {
                M9LayoutMode.HORIZONTAL -> {
                    if (line.crossStart > height - paddingBottom) return@forEach
                    line.draw(this, canvas, highlightRange, searchRange)
                }
                M9LayoutMode.VERTICAL -> {
                    if (line.lineTop > width - paddingRight) return@forEach
                    line.draw(this, canvas, highlightRange, searchRange)
                }
            }
        }
        canvas.restore()
    }

    private fun drawFallbackText(canvas: Canvas, text: String) {
        if (text.isBlank()) return
        contentPaint.color = textColor
        val fontMetrics = contentPaint.fontMetrics
        val lineHeight = (fontMetrics.descent - fontMetrics.ascent) * 1.4f
        var start = 0
        var y = -fontMetrics.ascent
        val maxWidth = (width - paddingLeft - paddingRight).coerceAtLeast(1).toFloat()
        while (start < text.length && y < height - paddingBottom) {
            val count = contentPaint.breakText(text, start, text.length, true, maxWidth, null)
                .coerceAtLeast(1)
            val end = (start + count).coerceAtMost(text.length)
            canvas.drawText(text, start, end, 0f, y, contentPaint)
            y += lineHeight
            start = end
        }
    }

    private fun spToPx(value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    }
}
