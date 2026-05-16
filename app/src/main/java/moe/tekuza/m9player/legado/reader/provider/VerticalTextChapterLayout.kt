package moe.tekuza.m9player.legado.reader.provider

import android.graphics.Paint
import android.text.TextPaint
import moe.tekuza.m9player.EBOOK_IMAGE_MARKER
import moe.tekuza.m9player.VerticalTextGlyphEngine
import moe.tekuza.m9player.decodeBitmapBounds
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.legado.reader.M9ReadBookConfig
import moe.tekuza.m9player.legado.reader.entities.ImageColumn
import moe.tekuza.m9player.legado.reader.entities.TextChapter
import moe.tekuza.m9player.legado.reader.entities.TextColumn
import moe.tekuza.m9player.legado.reader.entities.TextLine
import moe.tekuza.m9player.legado.reader.entities.TextPage
import kotlin.math.max

internal class VerticalTextChapterLayout(
    private val config: M9ReadBookConfig,
    private val visibleWidth: Int,
    private val visibleHeight: Int
) {
    private val contentPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = config.textSizePx
        color = config.textColor
        isFakeBoldText = config.textBold
        typeface = config.typeface
    }
    private val fontMetrics = contentPaint.fontMetrics
    private val glyphWidth = VerticalTextGlyphEngine.estimateCellWidth(contentPaint)
    private val columnWidth = (glyphWidth + config.lineSpacingPx).coerceAtLeast(1f)
    private val glyphHeight = (fontMetrics.descent - fontMetrics.ascent + config.letterSpacingPx).coerceAtLeast(1f)
    private val paragraphSpacing = (config.paragraphSpacingPx * 0.35f).coerceAtLeast(0f)

    fun layout(chapter: TextChapter): TextChapter {
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

        val pageColumns = mutableListOf<MutableList<TextLine>>()
        val pageRanges = mutableListOf<IntRange>()
        var currentColumns = mutableListOf<TextLine>()
        var pageStart = 0
        var x = (visibleWidth - columnWidth).coerceAtLeast(0f)
        var paragraphNum = 0
        var paragraphStart = 0
        while (paragraphStart <= text.length) {
            val paragraphEnd = text.indexOf('\n', paragraphStart).let { if (it < 0) text.length else it }
            paragraphNum += 1
            var y = if (config.paragraphIndent.isNotEmpty()) glyphHeight * config.paragraphIndent.length else 0f
            var lineStart = paragraphStart
            if (paragraphEnd - paragraphStart == 1 && text[paragraphStart] == EBOOK_IMAGE_MARKER) {
                val imageSize = imageBlockSize(chapter, paragraphStart)
                val imageLeft = x + columnWidth - imageSize.width
                if (currentColumns.isNotEmpty() && imageLeft < 0f) {
                    pageColumns += currentColumns
                    pageRanges += pageStart until currentColumns.last().chapterPosition + currentColumns.last().text.length
                    pageStart = paragraphStart
                    currentColumns = mutableListOf()
                    x = (visibleWidth - columnWidth).coerceAtLeast(0f)
                }
                currentColumns += createImageColumnLine(
                    chapter = chapter,
                    chapterPosition = paragraphStart,
                    pagePosition = max(0, paragraphStart - pageStart),
                    x = x,
                    width = imageSize.width,
                    height = imageSize.height
                )
                x = x + columnWidth - imageSize.width - columnWidth - paragraphSpacing
                if (paragraphEnd == text.length) break
                paragraphStart = paragraphEnd + 1
                continue
            }
            while (lineStart < paragraphEnd) {
                if (y + glyphHeight > visibleHeight) {
                    x -= columnWidth
                    y = 0f
                }
                if (currentColumns.isNotEmpty() && x < 0f) {
                    pageColumns += currentColumns
                    pageRanges += pageStart until currentColumns.last().chapterPosition + currentColumns.last().text.length
                    pageStart = lineStart
                    currentColumns = mutableListOf()
                    x = (visibleWidth - columnWidth).coerceAtLeast(0f)
                    y = if (lineStart == paragraphStart && config.paragraphIndent.isNotEmpty()) {
                        glyphHeight * config.paragraphIndent.length
                    } else {
                        0f
                    }
                }
                val capacity = ((visibleHeight - y) / glyphHeight).toInt().coerceAtLeast(1)
                val token = nextToken(text, lineStart, paragraphEnd)
                val tokenUnits = token.heightUnits.coerceAtLeast(1)
                if (y + tokenUnits * glyphHeight > visibleHeight && y > 0f) {
                    x -= columnWidth
                    y = 0f
                }
                if (currentColumns.isNotEmpty() && x < 0f) {
                    pageColumns += currentColumns
                    pageRanges += pageStart until currentColumns.last().chapterPosition + currentColumns.last().text.length
                    pageStart = lineStart
                    currentColumns = mutableListOf()
                    x = (visibleWidth - columnWidth).coerceAtLeast(0f)
                    y = 0f
                }
                val count = if (token.length == 1) {
                    adjustVerticalBreakCount(text, lineStart, paragraphEnd, capacity)
                } else {
                    token.length
                }
                val lineEnd = (lineStart + count).coerceAtMost(paragraphEnd)
                val lineText = text.substring(lineStart, lineEnd)
                val line = createVerticalLine(
                    text = lineText,
                    chapter = chapter,
                    paragraphNum = paragraphNum,
                    chapterPosition = lineStart,
                    pagePosition = max(0, lineStart - pageStart),
                    x = x,
                    startY = y,
                    isParagraphEnd = lineEnd >= paragraphEnd
                )
                currentColumns += line
                x -= columnWidth
                y = 0f
                lineStart = lineEnd
            }
            if (paragraphEnd == text.length) break
            x -= paragraphSpacing
            paragraphStart = paragraphEnd + 1
        }
        if (currentColumns.isNotEmpty()) {
            pageColumns += currentColumns
            pageRanges += pageStart until currentColumns.last().chapterPosition + currentColumns.last().text.length
        }

        pageColumns.forEachIndexed { index, columns ->
            val range = pageRanges[index]
            val page = TextPage(
                index = index,
                pageInChapter = index,
                chapterPageCount = pageColumns.size,
                chapterIndex = chapter.chapterIndex,
                chapterSize = chapter.chaptersSize,
                charStart = range.first,
                charEnd = range.last + 1,
                title = chapter.title,
                text = text.substring(range.first, (range.last + 1).coerceIn(range.first, text.length))
            )
            columns.forEach { line ->
                val rebased = line.copy(pagePosition = line.chapterPosition - page.charStart)
                rebased.columns.forEach { column ->
                    when (column) {
                        is TextColumn -> {
                            column.sourceStart -= page.charStart
                            column.sourceEnd -= page.charStart
                        }
                        is ImageColumn -> {
                            column.sourceStart -= page.charStart
                            column.sourceEnd -= page.charStart
                        }
                    }
                }
                page.addLine(rebased)
            }
            chapter.addPage(page)
        }
        return chapter
    }

    private fun adjustVerticalBreakCount(text: String, start: Int, paragraphEnd: Int, capacity: Int): Int {
        if (capacity <= 1) return 1
        var count = minOf(capacity, paragraphEnd - start)
        if (!config.useZhLayout) return count
        while (count > 1) {
            val nextIndex = start + count
            val prevChar = text[start + count - 1]
            val nextChar = text.getOrNull(nextIndex)
            val hitsNoStart = nextChar != null && VerticalTextGlyphEngine.isNoColumnStart(nextChar)
            val hitsNoEnd = VerticalTextGlyphEngine.isNoColumnEnd(prevChar)
            if (!hitsNoStart && !hitsNoEnd) {
                break
            }
            count -= 1
        }
        return count.coerceAtLeast(1)
    }

    private fun createVerticalLine(
        text: String,
        chapter: TextChapter,
        paragraphNum: Int,
        chapterPosition: Int,
        pagePosition: Int,
        x: Float,
        startY: Float,
        isParagraphEnd: Boolean
    ): TextLine {
        val line = TextLine(
            text = text,
            lineTop = x,
            lineBase = x + glyphWidth / 2f,
            lineBottom = x + columnWidth,
            crossStart = startY,
            crossEnd = startY + text.length.coerceAtLeast(1) * glyphHeight,
            paragraphNum = paragraphNum,
            chapterPosition = chapterPosition,
            pagePosition = pagePosition,
            isParagraphEnd = isParagraphEnd,
            layoutMode = M9LayoutMode.VERTICAL
        )
        var y = startY
        var local = 0
        while (local < text.length) {
            val sourceStart = chapterPosition + local
            val token = nextToken(text, local, text.length)
            val tokenText = token.text
            val sourceEnd = sourceStart + token.length
            if (tokenText.length == 1 && tokenText.first().code == EBOOK_IMAGE_MARKER.code) {
                chapter.images[sourceStart]?.let { image ->
                    line.addColumn(
                        ImageColumn(
                            start = y,
                            end = y + glyphHeight * 4f,
                            image = image,
                            width = line.width,
                            height = glyphHeight * 4f,
                            sourceStart = sourceStart,
                            sourceEnd = sourceEnd
                        )
                    )
                    y += glyphHeight * 4f
                }
            } else {
                val tokenHeight = glyphHeight * token.heightUnits.coerceAtLeast(1)
                line.addColumn(
                    TextColumn(
                        start = y,
                        end = y + tokenHeight,
                        charData = tokenText,
                        sourceStart = sourceStart,
                        sourceEnd = sourceEnd
                    )
                )
                y += tokenHeight
            }
            local += token.length
        }
        line.crossEnd = y
        return line
    }

    private fun createImageColumnLine(
        chapter: TextChapter,
        chapterPosition: Int,
        pagePosition: Int,
        x: Float,
        width: Float,
        height: Float
    ): TextLine {
        val right = (x + columnWidth).coerceAtMost(visibleWidth.toFloat())
        val left = (right - width).coerceAtLeast(0f)
        val line = TextLine(
            text = EBOOK_IMAGE_MARKER.toString(),
            lineTop = left,
            lineBase = left + (right - left) / 2f,
            lineBottom = right,
            crossStart = 0f,
            crossEnd = height,
            chapterPosition = chapterPosition,
            pagePosition = pagePosition,
            isParagraphEnd = true,
            layoutMode = M9LayoutMode.VERTICAL
        )
        chapter.images[chapterPosition]?.let { image ->
            line.addColumn(
                ImageColumn(
                    start = 0f,
                    end = height,
                    image = image,
                    width = line.width,
                    height = height,
                    sourceStart = chapterPosition,
                    sourceEnd = chapterPosition + 1
                )
            )
        }
        return line
    }

    private fun imageBlockSize(chapter: TextChapter, chapterPosition: Int): ImageBlockSize {
        val bounds = chapter.images[chapterPosition]?.let { decodeBitmapBounds(it.bytes) }
        val sourceWidth = bounds?.width?.toFloat()?.coerceAtLeast(1f) ?: visibleWidth.toFloat()
        val sourceHeight = bounds?.height?.toFloat()?.coerceAtLeast(1f) ?: visibleHeight.toFloat()
        val maxWidth = visibleWidth.toFloat().coerceAtLeast(columnWidth)
        val maxHeight = visibleHeight.toFloat().coerceAtLeast(glyphHeight * 6f)
        val scale = minOf(maxWidth / sourceWidth, maxHeight / sourceHeight)
            .takeIf { it.isFinite() && it > 0f }
            ?: 1f
        val width = (sourceWidth * scale)
            .coerceIn(columnWidth * 4f, maxWidth)
        val height = (sourceHeight * scale)
            .coerceIn(glyphHeight * 6f, maxHeight)
        return ImageBlockSize(width = width, height = height)
    }

    private fun nextToken(text: String, start: Int, end: Int): VerticalToken {
        if (start >= end) return VerticalToken("", 0, 0)
        val first = text[start]
        if (VerticalTextGlyphEngine.isAsciiWordChar(first)) {
            var index = start + 1
            while (index < end && VerticalTextGlyphEngine.isAsciiWordChar(text[index])) {
                index += 1
            }
            return VerticalToken(
                text = text.substring(start, index),
                length = index - start,
                heightUnits = 2
            )
        }
        return VerticalToken(
            text = first.toString(),
            length = 1,
            heightUnits = 1
        )
    }

    private data class VerticalToken(
        val text: String,
        val length: Int,
        val heightUnits: Int
    )

    private data class ImageBlockSize(
        val width: Float,
        val height: Float
    )
}
