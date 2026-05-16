package moe.tekuza.m9player

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect as AndroidRect
import android.graphics.RectF
import android.text.TextPaint
import kotlin.math.ceil

internal object VerticalTextGlyphEngine {
    private val topRightPunctuation = setOf(
        '。', '、', '︒', '︑', '︐', '︔', '，', '．', '.', ','
    )

    private val centerPunctuation = setOf(
        '・', '：', '︓', '︰', '︙'
    )

    private val smallKana = setOf(
        'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ', 'っ', 'ゃ', 'ゅ', 'ょ', 'ゎ',
        'ァ', 'ィ', 'ゥ', 'ェ', 'ォ', 'ッ', 'ャ', 'ュ', 'ョ', 'ヮ', 'ヶ'
    )

    private val rotateClockwise = setOf(
        '「', '」', '『', '』', '（', '）', '(', ')', '［', '］', '[', ']',
        '｛', '｝', '{', '}', '〔', '〕', '【', '】', '〈', '〉', '《', '》',
        '〖', '〗', '＜', '＞', 'ー', '〜', '～', '…', '‥', '-', '_', '~',
        '／', '/', '｜', '|', '＝', '=', '÷', '：', ':', '；', ';'
    )

    private val verticalPresentationForms = setOf(
        '︵', '︶', '︷', '︸', '︹', '︺', '︿', '﹀', '︽', '︾', '︻', '︼',
        '﹁', '﹂', '﹃', '﹄', '︙'
    )

    private val noColumnStartChars: Set<Char> = setOf(
        '、', '。', '，', '．', '.', ',', '：', '；', ':', ';',
        '！', '？', '）', ')', ']', '】', '}', '』', '」', '》', '〉',
        '…', '—', '―', '～', '〜',
        '︑', '︒', '︐', '︓', '︔', '︕', '︖', '︶', '︺', '︸', '﹀',
        '︙', '︰', '﹡'
    )

    private val noColumnEndChars: Set<Char> = setOf(
        '「', '『', '（', '(', '[', '{', '【', '〔', '〈', '《', '＜',
        '︵', '︹', '︷', '︿'
    )

    private fun presentationChar(ch: Char): Char = when (ch) {
        '\u3001' -> '\uFE11'
        '\u3002' -> '\uFE12'
        ',' -> '\uFE10'
        '.' -> '\uFE12'
        ':' -> '\uFE13'
        ';' -> '\uFE14'
        '!' -> '\uFE15'
        '?' -> '\uFE16'
        '(' -> '\uFE35'
        ')' -> '\uFE36'
        '[' -> '\uFE39'
        ']' -> '\uFE3A'
        '{' -> '\uFE37'
        '}' -> '\uFE38'
        '<' -> '\uFE3F'
        '>' -> '\uFE40'
        '\u2025' -> '\uFE30'
        '\u2026', '\u22EF' -> '\uFE19'
        '\u203B' -> '\uFE61'
        else -> ch
    }

    private fun presentationText(text: String): String {
        if (text.isEmpty()) return text
        if (text.length == 1) {
            val mapped = presentationChar(text[0])
            return if (mapped == text[0]) text else mapped.toString()
        }
        var changed = false
        val out = StringBuilder(text.length)
        text.forEach { ch ->
            val mapped = presentationChar(ch)
            if (mapped != ch) changed = true
            out.append(mapped)
        }
        return if (changed) out.toString() else text
    }

    fun isNoColumnStart(ch: Char): Boolean {
        return ch in noColumnStartChars || presentationChar(ch) in noColumnStartChars
    }

    fun isNoColumnEnd(ch: Char): Boolean {
        return ch in noColumnEndChars || presentationChar(ch) in noColumnEndChars
    }

    fun estimateCellWidth(paint: TextPaint): Float {
        val sampleWidth = maxOf(
            paint.measureText("国"),
            paint.measureText("あ"),
            paint.textSize
        )
        return ceil((sampleWidth * 1.12f).toDouble()).toFloat().coerceAtLeast(1f)
    }

    fun draw(canvas: Canvas, sourcePaint: TextPaint, text: String, rect: RectF) {
        val displayText = presentationText(text)
        if (displayText.isEmpty()) return
        val paint = TextPaint(sourcePaint).apply {
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val baselineAdjust = -(paint.ascent() + paint.descent()) * 0.5f
        when (val ch = displayText.first()) {
            in topRightPunctuation -> drawTopRightPunctuation(canvas, paint, displayText, rect)
            in smallKana -> drawSmallKana(canvas, paint, displayText, rect)
            in centerPunctuation -> drawOffsetText(canvas, paint, displayText, rect, baselineAdjust, 0f, -paint.textSize * 0.04f)
            else -> drawRotatableText(canvas, paint, displayText, rect, baselineAdjust)
        }
    }

    fun inkRect(sourcePaint: TextPaint, text: String, rect: RectF): RectF {
        val displayText = presentationText(text)
        if (displayText.isEmpty()) return rect
        val paint = TextPaint(sourcePaint).apply {
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val baselineAdjust = -(paint.ascent() + paint.descent()) * 0.5f
        val rawRect = when (val ch = displayText.first()) {
            in topRightPunctuation -> topRightPunctuationInkRect(paint, displayText, rect)
            in smallKana -> smallKanaInkRect(paint, displayText, rect)
            in centerPunctuation -> offsetInkRect(paint, displayText, rect, baselineAdjust, 0f, -paint.textSize * 0.04f)
            else -> rotatableInkRect(paint, displayText, rect, baselineAdjust)
        }
        return clampAndPadInkRect(rawRect, rect, paint.textSize)
    }

    fun rotationFor(text: String): Float {
        val ch = text.firstOrNull()?.let(::presentationChar) ?: return 0f
        return when {
            ch in 'A'..'Z' || ch in 'a'..'z' -> 90f
            ch in '0'..'9' -> 0f
            ch in verticalPresentationForms -> 0f
            ch in rotateClockwise -> 90f
            else -> 0f
        }
    }

    fun shouldMirrorAfterRotation(text: String): Boolean {
        return text.firstOrNull()?.let(::presentationChar) in setOf('ー', '〜', '～')
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
        return RectF(x + bounds.left, y + bounds.top, x + bounds.right, y + bounds.bottom)
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

    private fun smallKanaInkRect(
        paint: TextPaint,
        text: String,
        rect: RectF
    ): RectF {
        val markPaint = TextPaint(paint).apply {
            textAlign = Paint.Align.LEFT
        }
        val bounds = AndroidRect()
        markPaint.getTextBounds(text, 0, text.length, bounds)
        val targetRight = rect.right - rect.width() * 0.18f
        val targetTop = rect.top + rect.height() * 0.18f
        val x = targetRight - bounds.right
        val y = targetTop - bounds.top
        return RectF(x + bounds.left, y + bounds.top, x + bounds.right, y + bounds.bottom)
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

    private fun drawTopRightPunctuation(
        canvas: Canvas,
        paint: TextPaint,
        text: String,
        rect: RectF
    ) {
        val markPaint = TextPaint(paint).apply { textAlign = Paint.Align.LEFT }
        val bounds = AndroidRect()
        markPaint.getTextBounds(text, 0, text.length, bounds)
        val targetRight = rect.right - rect.width() * 0.10f
        val targetTop = rect.top + rect.height() * 0.08f
        val x = targetRight - bounds.right
        val y = targetTop - bounds.top
        canvas.drawText(text, x, y, markPaint)
    }

    private fun drawSmallKana(
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
        val targetRight = rect.right - rect.width() * 0.18f
        val targetTop = rect.top + rect.height() * 0.18f
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
}
