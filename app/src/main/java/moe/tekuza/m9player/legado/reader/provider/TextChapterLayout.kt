package moe.tekuza.m9player.legado.reader.provider

import android.graphics.Paint
import android.text.TextPaint
import moe.tekuza.m9player.EBOOK_IMAGE_MARKER
import moe.tekuza.m9player.EbookRubySpan
import moe.tekuza.m9player.decodeBitmapBounds
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.legado.reader.M9ReadBookConfig
import moe.tekuza.m9player.legado.reader.applyM9TextWeight
import moe.tekuza.m9player.legado.reader.entities.ImageColumn
import moe.tekuza.m9player.legado.reader.entities.TextChapter
import moe.tekuza.m9player.legado.reader.entities.TextColumn
import moe.tekuza.m9player.legado.reader.entities.TextLine
import moe.tekuza.m9player.legado.reader.entities.TextPage
import kotlin.math.max

internal class TextChapterLayout(
    private val config: M9ReadBookConfig,
    private val visibleWidth: Int,
    private val visibleHeight: Int
) {
    private val contentPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = config.textSizePx
        color = config.textColor
        applyM9TextWeight(config.textWeight, config.typeface)
    }
    private val fontMetrics = contentPaint.fontMetrics
    private val baseLineHeight = (fontMetrics.descent - fontMetrics.ascent + config.lineSpacingPx)
        .coerceAtLeast(1f)
    private val paragraphSpacing = config.paragraphSpacingPx.coerceAtLeast(0f)
    private var lineHeight = baseLineHeight
    private var rubyReservePx = 0f
    private var rubyByStart: Map<Int, EbookRubySpan> = emptyMap()

    fun layout(chapter: TextChapter): TextChapter {
        rubyReservePx = if (chapter.rubySpans.isNotEmpty()) {
            (config.textSizePx * 0.58f).coerceAtLeast(8f)
        } else {
            0f
        }
        lineHeight = baseLineHeight + rubyReservePx
        rubyByStart = chapter.rubySpans.associateBy { it.start }
        val text = chapter.text
        if (text.isBlank()) {
            chapter.addPage(
                TextPage(
                    index = 0,
                    pageInChapter = 0,
                    chapterPageCount = 1,
                    chapterIndex = chapter.chapterIndex,
                    chapterSize = chapter.chaptersSize,
                    title = chapter.title,
                    text = "",
                    charStart = 0,
                    charEnd = 0
                )
            )
            return chapter
        }

        val pageLineGroups = mutableListOf<MutableList<TextLine>>()
        val pageStarts = mutableListOf<Int>()
        var currentLines = mutableListOf<TextLine>()
        var pageStart = 0
        var y = 0f
        var paragraphNum = 0
        var paragraphStart = 0
        while (paragraphStart <= text.length) {
            val paragraphEnd = text.indexOf('\n', paragraphStart).let {
                if (it < 0) text.length else it
            }
            paragraphNum += 1
            var lineStart = paragraphStart
            if (lineStart == paragraphEnd) {
                y += paragraphSpacing
            }
            if (paragraphEnd - paragraphStart == 1 && text[paragraphStart] == EBOOK_IMAGE_MARKER) {
                val imageHeight = imageHeight(chapter, paragraphStart)
                if (currentLines.isNotEmpty() && y + imageHeight > visibleHeight) {
                    pageLineGroups += currentLines
                    pageStarts += pageStart
                    pageStart = paragraphStart
                    currentLines = mutableListOf()
                    y = 0f
                }
                currentLines += createImageLine(
                    chapter = chapter,
                    chapterPosition = paragraphStart,
                    pagePosition = max(0, paragraphStart - pageStart),
                    y = y,
                    height = imageHeight
                )
                y += imageHeight + paragraphSpacing
                if (paragraphEnd == text.length) break
                paragraphStart = paragraphEnd + 1
                continue
            }
            while (lineStart < paragraphEnd) {
                val firstLine = lineStart == paragraphStart
                val indentWidth = if (firstLine) {
                    contentPaint.measureText(config.paragraphIndent)
                } else {
                    0f
                }
                val letterExtra = config.letterSpacingPx.coerceAtLeast(0f)
                val count = contentPaint.breakText(
                    text,
                    lineStart,
                    paragraphEnd,
                    true,
                    (visibleWidth - indentWidth - letterExtra * 8).coerceAtLeast(1f),
                    null
                ).coerceAtLeast(1).let { count ->
                    adjustBreakCountForZhLayout(text, lineStart, paragraphEnd, count)
                }
                val lineEnd = (lineStart + count).coerceAtMost(paragraphEnd)
                val lineText = text.substring(lineStart, lineEnd)
                if (currentLines.isNotEmpty() && y + lineHeight > visibleHeight) {
                    pageLineGroups += currentLines
                    pageStarts += pageStart
                    pageStart = lineStart
                    currentLines = mutableListOf()
                    y = 0f
                }
                val line = createLine(
                    text = lineText,
                    chapter = chapter,
                    paragraphNum = paragraphNum,
                    chapterPosition = lineStart,
                    pagePosition = max(0, lineStart - pageStart),
                    y = y,
                    isParagraphEnd = lineEnd >= paragraphEnd,
                    startX = indentWidth
                )
                if (config.textFullJustify && !line.isParagraphEnd) {
                    justifyTextLine(line)
                }
                currentLines += line
                y += lineHeight
                lineStart = lineEnd
            }
            if (paragraphEnd == text.length) break
            y += paragraphSpacing
            paragraphStart = paragraphEnd + 1
        }
        if (currentLines.isNotEmpty()) {
            pageLineGroups += currentLines
            pageStarts += pageStart
        }

        pageLineGroups.forEachIndexed { index, lines ->
            if (config.textBottomJustify) {
                justifyPageBottom(lines)
            }
            val rangeStart = pageStarts.getOrElse(index) { 0 }.coerceIn(0, text.length)
            val rangeEnd = pageStarts.getOrNull(index + 1)
                ?.coerceIn(rangeStart, text.length)
                ?: text.length
            val page = TextPage(
                index = index,
                pageInChapter = index,
                chapterPageCount = pageLineGroups.size,
                chapterIndex = chapter.chapterIndex,
                chapterSize = chapter.chaptersSize,
                charStart = rangeStart,
                charEnd = rangeEnd,
                title = chapter.title,
                text = text.substring(rangeStart, rangeEnd.coerceIn(rangeStart, text.length))
            )
            lines.forEach { line ->
                val rebased = line.copy(
                    pagePosition = line.chapterPosition - page.charStart
                )
                rebased.columns.forEach { column ->
                    when (column) {
                        is TextColumn -> {
                            column.sourceStart -= page.charStart
                            column.sourceEnd -= page.charStart
                            column.rubySourceStart -= page.charStart
                            column.rubySourceEnd -= page.charStart
                        }
                        is ImageColumn -> {
                            column.sourceStart -= page.charStart
                            column.sourceEnd -= page.charStart
                        }
                    }
                }
                page.addLine(rebased)
            }
            page.height = page.lines.maxOfOrNull { it.crossEnd } ?: visibleHeight.toFloat()
            page.width = visibleWidth.toFloat()
            chapter.addPage(page)
        }
        return chapter
    }

    private fun adjustBreakCountForZhLayout(text: String, start: Int, paragraphEnd: Int, count: Int): Int {
        if (!config.useZhLayout) return count
        if (count <= 1 || start + count >= paragraphEnd) return count
        val nextChar = text[start + count]
        return if (nextChar in noLineStartChars) count - 1 else count
    }

    private fun justifyTextLine(line: TextLine) {
        val textColumns = line.columns.filterIsInstance<TextColumn>()
        if (textColumns.size <= 1) return
        val extra = visibleWidth - line.lineEnd
        if (extra <= 0f) return
        val gap = extra / (textColumns.size - 1)
        var offset = 0f
        textColumns.forEachIndexed { index, column ->
            column.start += offset
            column.end += offset
            if (index < textColumns.lastIndex) offset += gap
        }
    }

    private fun justifyPageBottom(lines: MutableList<TextLine>) {
        if (lines.size <= 1) return
        val lastBottom = lines.last().lineBottom
        val extra = visibleHeight - lastBottom
        if (extra <= lineHeight) return
        val gap = (extra / (lines.size - 1)).coerceAtMost(lineHeight * 0.45f)
        var offset = 0f
        lines.forEachIndexed { index, line ->
            line.lineTop += offset
            line.lineBase += offset
            line.lineBottom += offset
            if (index < lines.lastIndex) offset += gap
        }
    }

    private fun createLine(
        text: String,
        chapter: TextChapter,
        paragraphNum: Int,
        chapterPosition: Int,
        pagePosition: Int,
        y: Float,
        isParagraphEnd: Boolean,
        startX: Float
    ): TextLine {
        val line = TextLine(
            text = text,
            lineTop = y,
            lineBase = y + rubyReservePx - fontMetrics.ascent,
            lineBottom = y + lineHeight,
            crossStart = y,
            crossEnd = y + lineHeight,
            paragraphNum = paragraphNum,
            chapterPosition = chapterPosition,
            pagePosition = pagePosition,
            isParagraphEnd = isParagraphEnd,
            layoutMode = M9LayoutMode.HORIZONTAL,
            startX = startX,
            rubyReservePx = rubyReservePx
        )
        var x = startX
        var local = 0
        while (local < text.length) {
            val codePoint = text.codePointAt(local)
            val char = String(Character.toChars(codePoint))
            val width = contentPaint.measureText(char) + config.letterSpacingPx
            val sourceStart = chapterPosition + local
            val sourceEnd = sourceStart + char.length
            if (codePoint == EBOOK_IMAGE_MARKER.code) {
                chapter.images[sourceStart]?.let { image ->
                    line.addColumn(
                        ImageColumn(
                            start = 0f,
                            end = visibleWidth.toFloat(),
                            image = image,
                            width = visibleWidth.toFloat(),
                            height = line.height,
                            sourceStart = sourceStart,
                            sourceEnd = sourceEnd
                        )
                    )
                }
            } else {
                val ruby = rubyByStart[sourceStart]
                line.addColumn(
                    TextColumn(
                        start = x,
                        end = x + width,
                        charData = char,
                        sourceStart = sourceStart,
                        sourceEnd = sourceEnd,
                        rubyText = ruby?.text,
                        rubySourceStart = ruby?.start ?: sourceStart,
                        rubySourceEnd = ruby?.end ?: sourceEnd
                    )
                )
            }
            x += width
            local += char.length
        }
        return line
    }

    private fun createImageLine(
        chapter: TextChapter,
        chapterPosition: Int,
        pagePosition: Int,
        y: Float,
        height: Float
    ): TextLine {
        val line = TextLine(
            text = EBOOK_IMAGE_MARKER.toString(),
            lineTop = y,
            lineBase = y + height,
            lineBottom = y + height,
            crossStart = y,
            crossEnd = y + height,
            paragraphNum = 0,
            chapterPosition = chapterPosition,
            pagePosition = pagePosition,
            isParagraphEnd = true,
            layoutMode = M9LayoutMode.HORIZONTAL,
            startX = 0f
        )
        chapter.images[chapterPosition]?.let { image ->
            line.addColumn(
                ImageColumn(
                    start = 0f,
                    end = visibleWidth.toFloat(),
                    image = image,
                    width = visibleWidth.toFloat(),
                    height = height,
                    sourceStart = chapterPosition,
                    sourceEnd = chapterPosition + 1
                )
            )
        }
        return line
    }

    private fun imageHeight(chapter: TextChapter, chapterPosition: Int): Float {
        val image = chapter.images[chapterPosition] ?: return lineHeight * 4f
        val bounds = image.readBytes()?.let(::decodeBitmapBounds)
        val sourceWidth = bounds?.width ?: visibleWidth
        val sourceHeight = bounds?.height ?: visibleWidth
        val ratio = sourceHeight.toFloat() / sourceWidth.toFloat().coerceAtLeast(1f)
        return (visibleWidth * ratio)
            .coerceIn(lineHeight * 4f, visibleHeight * 0.82f)
    }

    private companion object {
        private val noLineStartChars = setOf(
            '、', '。', '，', '．', '：', '；', '！', '？',
            '）', ')', ']', '】', '}', '』', '」', '》',
            '…', '—', '～'
        )
    }
}
