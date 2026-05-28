package moe.tekuza.m9player.legado.reader.entities

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.Cap
import android.graphics.Paint.Style
import android.graphics.RectF
import moe.tekuza.m9player.EbookRubyKind
import moe.tekuza.m9player.EbookRubySpan
import moe.tekuza.m9player.VerticalTextGlyphEngine
import moe.tekuza.m9player.legado.reader.page.ContentTextView
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import kotlin.math.max
import kotlin.math.min

internal data class TextColumn(
    override var start: Float,
    override var end: Float,
    val charData: String,
    override var sourceStart: Int,
    override var sourceEnd: Int,
    val rubyText: String? = null,
    var rubySourceStart: Int = sourceStart,
    var rubySourceEnd: Int = sourceEnd,
    val rubySpan: EbookRubySpan? = null
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
                if (isVerticalDash(charData)) {
                    if (isFirstVerticalDashInRun(line)) {
                        drawVerticalDashRun(canvas, line, glyphRight, paint)
                    }
                } else if (VerticalTextGlyphEngine.isTwoDigitToken(charData)) {
                    VerticalTextGlyphEngine.drawTateChuYoko(
                        canvas = canvas,
                        sourcePaint = paint,
                        text = charData,
                        rect = rect
                    )
                } else if (VerticalTextGlyphEngine.isSidewaysAsciiToken(charData)) {
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

    private fun isFirstVerticalDashInRun(line: TextLine): Boolean {
        val previous = line.columns
            .filterIsInstance<TextColumn>()
            .lastOrNull { it !== this && it.end <= start }
            ?: return true
        if (!isVerticalDash(previous.charData)) return true
        if (previous.sourceEnd != sourceStart) return true
        val gap = (start - previous.end).coerceAtLeast(0f)
        return gap > max(1f, (end - start) * 0.25f)
    }

    private fun verticalDashRunColumns(line: TextLine): List<TextColumn> {
        val columns = line.columns.filterIsInstance<TextColumn>()
        val startIndex = columns.indexOfFirst { it === this }
        if (startIndex < 0) return listOf(this)
        val run = arrayListOf<TextColumn>()
        var previous: TextColumn? = null
        for (index in startIndex until columns.size) {
            val column = columns[index]
            if (!isVerticalDash(column.charData)) break
            val previousColumn = previous
            if (previousColumn != null) {
                val sourceContinuous = previousColumn.sourceEnd == column.sourceStart
                val visualGap = (column.start - previousColumn.end).coerceAtLeast(0f)
                val visualContinuous = visualGap <= max(1f, (column.end - column.start) * 0.25f)
                if (!sourceContinuous || !visualContinuous) break
            }
            run += column
            previous = column
        }
        return run.takeIf { it.isNotEmpty() } ?: listOf(this)
    }

    private fun drawVerticalDashRun(
        canvas: Canvas,
        line: TextLine,
        glyphRight: Float,
        paint: Paint
    ) {
        val run = verticalDashRunColumns(line)
        val top = run.minOf { it.start }
        val bottom = run.maxOf { it.end }
        val x = (line.lineTop + glyphRight) * 0.5f
        val strokeWidth = min(
            (glyphRight - line.lineTop).coerceAtLeast(1f) * 0.12f,
            paint.textSize * 0.08f
        ).coerceAtLeast(1f)
        val oldStyle = paint.style
        val oldStrokeWidth = paint.strokeWidth
        val oldStrokeCap = paint.strokeCap
        paint.style = Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.strokeCap = Cap.SQUARE
        canvas.drawLine(x, top - strokeWidth * 0.5f, x, bottom + strokeWidth * 0.5f, paint)
        paint.style = oldStyle
        paint.strokeWidth = oldStrokeWidth
        paint.strokeCap = oldStrokeCap
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
        paint.textSize = RubyLayoutEngine.rubyTextSize(oldSize)
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = false
        RubyLayoutEngine.fitHorizontalRubyText(
            paint = paint,
            annotation = annotation,
            baseWidth = (right - left).coerceAtLeast(1f),
            beforeOverhang = rubyOverhangBefore(line, paint.textSize),
            afterOverhang = rubyOverhangAfter(line, paint.textSize),
            originalSize = oldSize
        )
        val baseline = line.lineTop + line.rubyReservePx.coerceAtLeast(paint.textSize) -
            (paint.ascent() + paint.descent()) * 0.5f - paint.textSize * 0.18f
        if (RubyLayoutEngine.shouldDistributeHorizontal(annotation, (right - left).coerceAtLeast(1f), paint)) {
            drawDistributedHorizontalRuby(canvas, paint, annotation, left, right, baseline)
        } else {
            canvas.drawText(annotation, (left + right) / 2f, baseline, paint)
        }
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
        paint.textSize = RubyLayoutEngine.rubyTextSize(oldSize)
        paint.textAlign = Paint.Align.CENTER
        paint.isFakeBoldText = false
        val stripWidth = line.rubyReservePx.coerceAtLeast(paint.textSize)
        val glyphRight = (line.lineBottom - line.rubyReservePx).coerceAtLeast(line.lineTop)
        val gap = (paint.textSize * RubyLayoutEngine.GAP_EM).coerceAtLeast(1f)
        val left = (glyphRight + gap).coerceAtMost(line.lineBottom - paint.textSize)
        val right = (left + stripWidth).coerceAtMost(line.lineBottom)
        val boxes = RubyLayoutEngine.verticalGlyphBoxes(
            annotation = annotation,
            baseColumns = columns,
            top = top,
            bottom = bottom,
            rubySize = paint.textSize,
            beforeOverhang = rubyOverhangBefore(line, paint.textSize),
            afterOverhang = rubyOverhangAfter(line, paint.textSize),
            rubyKind = rubySpan?.kind ?: EbookRubyKind.UNKNOWN,
            segmented = rubySpan?.segments?.isNotEmpty() == true
        )
        boxes.forEach { box ->
            VerticalTextGlyphEngine.draw(
                canvas = canvas,
                sourcePaint = paint,
                text = box.text,
                rect = RectF(left, box.start, right, box.end)
            )
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

    private fun drawDistributedHorizontalRuby(
        canvas: Canvas,
        paint: Paint,
        annotation: String,
        left: Float,
        right: Float,
        baseline: Float
    ) {
        val chars = RubyLayoutEngine.codePointStrings(annotation)
        if (chars.isEmpty()) return
        val width = (right - left).coerceAtLeast(1f)
        chars.forEachIndexed { index, char ->
            val x = left + width * (index + 0.5f) / chars.size
            canvas.drawText(char, x, baseline, paint)
        }
    }

    private fun rubyOverhangBefore(line: TextLine, rubySize: Float): Float {
        if (rubySpan?.segments?.isNotEmpty() == true) return rubySize * RubyLayoutEngine.SEGMENT_OVERHANG_EM
        val previous = line.columns
            .filterIsInstance<TextColumn>()
            .lastOrNull { it !== this && it.end <= start }
            ?: return rubySize * RubyLayoutEngine.EDGE_OVERHANG_EM
        return allowedRubyOverhang(previous.charData, rubySize)
    }

    private fun rubyOverhangAfter(line: TextLine, rubySize: Float): Float {
        if (rubySpan?.segments?.isNotEmpty() == true) return rubySize * RubyLayoutEngine.SEGMENT_OVERHANG_EM
        val next = line.columns
            .filterIsInstance<TextColumn>()
            .firstOrNull { it !== this && it.start >= end }
            ?: return rubySize * RubyLayoutEngine.EDGE_OVERHANG_EM
        return allowedRubyOverhang(next.charData, rubySize)
    }

    private fun allowedRubyOverhang(adjacent: String, rubySize: Float): Float {
        val first = adjacent.firstOrNull() ?: return 0f
        return when {
            isJapaneseIdeograph(first) -> 0f
            first in RUBY_FULL_OVERHANG_CHARS -> rubySize
            first in RUBY_PUNCTUATION_OVERHANG_CHARS -> rubySize * 0.5f
            else -> rubySize * RubyLayoutEngine.EDGE_OVERHANG_EM
        }
    }

    private companion object {
        private fun isVerticalDash(value: String): Boolean {
            return value.length == 1 && value[0] in VERTICAL_DASH_CHARS
        }

        private fun isJapaneseIdeograph(char: Char): Boolean =
            Character.UnicodeBlock.of(char) in setOf(
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B,
                Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
            )

        private val VERTICAL_DASH_CHARS = setOf(
            '\u2014',
            '\u2015',
            '\u2212',
            '\u2500',
            '\u2501',
            '\u2E3A',
            '\u2E3B'
        )

        private val RUBY_FULL_OVERHANG_CHARS = setOf(
            'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ', 'っ', 'ゃ', 'ゅ', 'ょ', 'ゎ',
            'ァ', 'ィ', 'ゥ', 'ェ', 'ォ', 'ッ', 'ャ', 'ュ', 'ョ', 'ヮ',
            'ー'
        ) + ('ぁ'..'ん') + ('ァ'..'ヶ')

        private val RUBY_PUNCTUATION_OVERHANG_CHARS = setOf(
            '、', '。', '，', '．', '・', '：', '；', '！', '？',
            '「', '『', '（', '《', '〈', '［', '〔',
            '」', '』', '）', '》', '〉', '］', '〕'
        )
    }
}
