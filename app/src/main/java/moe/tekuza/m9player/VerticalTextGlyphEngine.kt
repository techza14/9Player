package moe.tekuza.m9player

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect as AndroidRect
import android.graphics.RectF
import android.text.TextPaint
import kotlin.math.ceil

internal data class VerticalTextToken(
    val sourceOffset: Int,
    val sourceEndExclusive: Int,
    val text: String
)

internal object VerticalTextGlyphEngine {
    private data class RotationStyle(
        val degrees: Float,
        val dxEm: Float = 0f,
        val dyEm: Float = 0f,
        val mirrorAfterRotation: Boolean = false
    )

    private val scratchBounds = ThreadLocal.withInitial { AndroidRect() }

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
        '〖', '〗', '＜', '＞', '〜', '～', '…', '‥', '-', '_', '~',
        '／', '/', '｜', '|', '＝', '=', '÷', '：', ':', '；', ';'
    )

    private val vjapDashRotation = mapOf(
        'ー' to RotationStyle(degrees = -90f),
        '─' to RotationStyle(degrees = 90f, dxEm = -0.08f, dyEm = -0.02f),
        '—' to RotationStyle(degrees = 90f, dxEm = -0.08f, dyEm = -0.02f),
        '―' to RotationStyle(degrees = 90f, dxEm = -0.08f, dyEm = -0.02f),
        '−' to RotationStyle(degrees = 90f, dxEm = -0.08f, dyEm = -0.02f)
    )

    private val verticalPresentationForms = setOf(
        '︵', '︶', '︷', '︸', '︹', '︺', '︿', '﹀', '︽', '︾', '︻', '︼',
        '﹁', '﹂', '﹃', '﹄', '︙'
    )

    private val mirrorAfterRotation = setOf('〜', '～')

    private val noColumnStartChars: Set<Char> = setOf(
        '、', '。', '，', '．', '.', ',', '：', '；', ':', ';',
        '！', '？', '）', ')', ']', '】', '}', '』', '」', '”', '’', '》', '〉',
        '…', '—', '―', '─', '−', '～', '〜',
        '︑', '︒', '︐', '︓', '︔', '︕', '︖', '︶', '︺', '︸', '﹀',
        '︙', '︰', '﹂', '﹄', '﹡'
    )

    private val noColumnEndChars: Set<Char> = setOf(
        '「', '『', '“', '‘', '（', '(', '[', '{', '【', '〔', '〈', '《', '＜',
        '︵', '︹', '︷', '︿', '﹁', '﹃'
    )

    private fun presentationChar(ch: Char): Char = when (ch) {
        '「', '“' -> '﹁'
        '」', '”' -> '﹂'
        '『', '‘' -> '﹃'
        '』', '’' -> '﹄'
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

    fun isAsciiWordChar(ch: Char): Boolean {
        return ch.code in 0x21..0x7E && !ch.isWhitespace()
    }

    fun isAsciiRunSpace(ch: Char): Boolean {
        return ch == ' ' || ch == '\u00A0'
    }

    fun isSidewaysAsciiToken(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length <= 1) return false
        val compact = trimmed.filterNot(::isAsciiRunSpace)
        return trimmed.any { it.isLetterOrDigit() } &&
            trimmed.all { isAsciiWordChar(it) || isAsciiRunSpace(it) }
    }

    fun nextVerticalTextToken(
        text: String,
        start: Int,
        endExclusive: Int = text.length
    ): VerticalTextToken {
        val endLimit = endExclusive.coerceIn(0, text.length)
        if (start >= endLimit) return VerticalTextToken(start, start, "")
        val first = text[start]
        if (first.isDigit()) {
            var end = start + 1
            while (end < endLimit && text[end].isDigit()) end += 1
            return VerticalTextToken(start, end, text.substring(start, end))
        }
        if (isAsciiWordChar(first)) {
            var end = start + 1
            while (end < endLimit && isAsciiWordChar(text[end])) end += 1
            while (end < endLimit && isAsciiRunSpace(text[end])) {
                end += 1
                while (end < endLimit && isAsciiRunSpace(text[end])) end += 1
                break
            }
            return VerticalTextToken(start, end, text.substring(start, end))
        }
        return VerticalTextToken(start, start + 1, text.substring(start, start + 1))
    }

    fun isTateChuYokoToken(text: String): Boolean {
        val compact = text.trim().filterNot(::isAsciiRunSpace)
        if (compact.length !in 2..4 || !compact.all { it.isLetterOrDigit() }) return false
        return compact.all { it.isDigit() } || compact.length <= 3
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
        withPaint(sourcePaint, Paint.Align.CENTER) { paint ->
            val baselineAdjust = -(paint.ascent() + paint.descent()) * 0.5f
            when (val ch = displayText.first()) {
                in topRightPunctuation -> drawTopRightPunctuation(canvas, paint, displayText, rect)
                in smallKana -> drawSmallKana(canvas, paint, displayText, rect)
                in centerPunctuation -> drawOffsetText(canvas, paint, displayText, rect, baselineAdjust, 0f, -paint.textSize * 0.04f)
                else -> drawRotatableText(canvas, paint, displayText, rect, baselineAdjust)
            }
        }
    }

    fun drawLatinRun(canvas: Canvas, sourcePaint: TextPaint, text: String, rect: RectF) {
        val displayText = text.trim()
        if (displayText.isEmpty()) return
        withPaint(sourcePaint, Paint.Align.LEFT) { paint ->
            val baselineAdjust = -(paint.ascent() + paint.descent()) * 0.5f
            val cx = rect.centerX()
            val cy = rect.centerY()
            val topPadding = (paint.textSize * 0.08f).coerceAtMost(rect.height() * 0.18f)
            val drawX = cx + (rect.top + topPadding - cy)
            val drawY = cy + baselineAdjust
            canvas.save()
            canvas.rotate(90f, cx, cy)
            canvas.drawText(displayText, drawX, drawY, paint)
            canvas.restore()
        }
    }

    fun drawTateChuYoko(canvas: Canvas, sourcePaint: TextPaint, text: String, rect: RectF) {
        val displayText = text.trim()
        if (displayText.isEmpty()) return
        withPaint(sourcePaint, Paint.Align.CENTER) { paint ->
            val oldSize = paint.textSize
            val baselineAdjust = -(paint.ascent() + paint.descent()) * 0.5f
            val maxWidth = rect.width() * 0.92f
            val measured = paint.measureText(displayText).coerceAtLeast(1f)
            if (measured > maxWidth) {
                paint.textSize = (oldSize * maxWidth / measured).coerceAtLeast(oldSize * 0.72f)
            }
            canvas.drawText(displayText, rect.centerX(), rect.centerY() + baselineAdjust, paint)
            paint.textSize = oldSize
        }
    }

    fun inkRect(sourcePaint: TextPaint, text: String, rect: RectF): RectF {
        val displayText = presentationText(text)
        if (displayText.isEmpty()) return rect
        return withPaint(sourcePaint, Paint.Align.CENTER) { paint ->
            val baselineAdjust = -(paint.ascent() + paint.descent()) * 0.5f
            val rawRect = when (val ch = displayText.first()) {
                in topRightPunctuation -> topRightPunctuationInkRect(paint, displayText, rect)
                in smallKana -> smallKanaInkRect(paint, displayText, rect)
                in centerPunctuation -> offsetInkRect(paint, displayText, rect, baselineAdjust, 0f, -paint.textSize * 0.04f)
                else -> rotatableInkRect(paint, displayText, rect, baselineAdjust)
            }
            clampAndPadInkRect(rawRect, rect, paint.textSize)
        }
    }

    fun rotationFor(text: String): Float {
        val ch = text.firstOrNull()?.let(::presentationChar) ?: return 0f
        return when {
            ch in vjapDashRotation -> vjapDashRotation.getValue(ch).degrees
            text.trim().length == 1 && (ch in 'A'..'Z' || ch in 'a'..'z') -> 0f
            ch in 'A'..'Z' || ch in 'a'..'z' -> 90f
            ch in '0'..'9' -> 0f
            ch in verticalPresentationForms -> 0f
            ch in rotateClockwise -> 90f
            else -> 0f
        }
    }

    fun shouldMirrorAfterRotation(text: String): Boolean {
        val ch = text.firstOrNull()?.let(::presentationChar) ?: return false
        return vjapDashRotation[ch]?.mirrorAfterRotation == true || ch in mirrorAfterRotation
    }

    private fun rotationStyleFor(text: String): RotationStyle {
        val ch = text.firstOrNull()?.let(::presentationChar) ?: return RotationStyle(0f)
        vjapDashRotation[ch]?.let { return it }
        return RotationStyle(
            degrees = rotationFor(text),
            mirrorAfterRotation = ch in mirrorAfterRotation
        )
    }

    private inline fun <T> withPaint(
        paint: TextPaint,
        align: Paint.Align,
        block: (TextPaint) -> T
    ): T {
        val previousAlign = paint.textAlign
        val previousAntiAlias = paint.isAntiAlias
        paint.textAlign = align
        paint.isAntiAlias = true
        return try {
            block(paint)
        } finally {
            paint.textAlign = previousAlign
            paint.isAntiAlias = previousAntiAlias
        }
    }

    private fun measureBounds(paint: TextPaint, text: String): AndroidRect {
        val bounds = scratchBounds.get() ?: AndroidRect().also(scratchBounds::set)
        bounds.setEmpty()
        paint.getTextBounds(text, 0, text.length, bounds)
        return bounds
    }

    private fun offsetInkRect(
        paint: TextPaint,
        text: String,
        rect: RectF,
        baselineAdjust: Float,
        dx: Float,
        dy: Float
    ): RectF {
        val bounds = measureBounds(paint, text)
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
        return withPaint(paint, Paint.Align.LEFT) { markPaint ->
            val bounds = measureBounds(markPaint, text)
            val targetRight = rect.right - rect.width() * 0.10f
            val targetTop = rect.top + rect.height() * 0.08f
            val x = targetRight - bounds.right
            val y = targetTop - bounds.top
            RectF(x + bounds.left, y + bounds.top, x + bounds.right, y + bounds.bottom)
        }
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
        val style = rotationStyleFor(text)
        val base = offsetInkRect(
            paint = paint,
            text = text,
            rect = rect,
            baselineAdjust = baselineAdjust,
            dx = paint.fontSpacing * style.dxEm,
            dy = paint.fontSpacing * style.dyEm
        )
        val cx = rect.centerX()
        val cy = rect.centerY()
        return rotatedBounds(base, cx, cy, rotation)
    }

    private fun smallKanaInkRect(
        paint: TextPaint,
        text: String,
        rect: RectF
    ): RectF {
        return withPaint(paint, Paint.Align.LEFT) { markPaint ->
            val bounds = measureBounds(markPaint, text)
            val targetRight = rect.right - rect.width() * 0.18f
            val targetTop = rect.top + rect.height() * 0.18f
            val x = targetRight - bounds.right
            val y = targetTop - bounds.top
            RectF(x + bounds.left, y + bounds.top, x + bounds.right, y + bounds.bottom)
        }
    }

    private fun rotatedBounds(rect: RectF, cx: Float, cy: Float, degrees: Float): RectF {
        val radians = Math.toRadians(degrees.toDouble())
        val cos = kotlin.math.cos(radians).toFloat()
        val sin = kotlin.math.sin(radians).toFloat()
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY

        fun include(x: Float, y: Float) {
            val dx = x - cx
            val dy = y - cy
            val rotatedX = cx + dx * cos - dy * sin
            val rotatedY = cy + dx * sin + dy * cos
            minX = minOf(minX, rotatedX)
            minY = minOf(minY, rotatedY)
            maxX = maxOf(maxX, rotatedX)
            maxY = maxOf(maxY, rotatedY)
        }

        include(rect.left, rect.top)
        include(rect.right, rect.top)
        include(rect.right, rect.bottom)
        include(rect.left, rect.bottom)
        return RectF(minX, minY, maxX, maxY)
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
        withPaint(paint, Paint.Align.LEFT) { markPaint ->
            val bounds = measureBounds(markPaint, text)
            val targetRight = rect.right - rect.width() * 0.10f
            val targetTop = rect.top + rect.height() * 0.08f
            val x = targetRight - bounds.right
            val y = targetTop - bounds.top
            canvas.drawText(text, x, y, markPaint)
        }
    }

    private fun drawSmallKana(
        canvas: Canvas,
        paint: TextPaint,
        text: String,
        rect: RectF
    ) {
        withPaint(paint, Paint.Align.LEFT) { markPaint ->
            val bounds = measureBounds(markPaint, text)
            val targetRight = rect.right - rect.width() * 0.18f
            val targetTop = rect.top + rect.height() * 0.18f
            val x = targetRight - bounds.right
            val y = targetTop - bounds.top
            canvas.drawText(text, x, y, markPaint)
        }
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
        val style = rotationStyleFor(text)
        if (style.degrees == 0f) {
            canvas.drawText(text, cx, cy + baselineAdjust, paint)
            return
        }
        val drawX = cx + paint.fontSpacing * style.dxEm
        val drawY = cy + baselineAdjust + paint.fontSpacing * style.dyEm
        canvas.save()
        canvas.rotate(style.degrees, cx, cy)
        if (style.mirrorAfterRotation) {
            canvas.scale(1f, -1f, cx, cy)
        }
        canvas.drawText(text, drawX, drawY, paint)
        canvas.restore()
    }
}
