package moe.tekuza.m9player.legado.reader.provider

import android.graphics.Paint
import android.text.TextPaint
import android.util.Log
import moe.tekuza.m9player.EBOOK_IMAGE_MARKER
import moe.tekuza.m9player.EbookRubySpan
import moe.tekuza.m9player.VerticalTextGlyphEngine
import moe.tekuza.m9player.decodeBitmapBounds
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.legado.reader.M9ReadBookConfig
import moe.tekuza.m9player.legado.reader.applyM9TextWeight
import moe.tekuza.m9player.legado.reader.entities.ImageColumn
import moe.tekuza.m9player.legado.reader.entities.TextChapter
import moe.tekuza.m9player.legado.reader.entities.TextColumn
import moe.tekuza.m9player.legado.reader.entities.TextLine
import moe.tekuza.m9player.legado.reader.entities.TextPage
import kotlin.math.ceil
import kotlin.math.max

private const val READER_LATIN_LOG_TAG = "M9ReaderLatin"

internal class VerticalTextChapterLayout(
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
    private val glyphWidth = VerticalTextGlyphEngine.estimateCellWidth(contentPaint)
    private val baseColumnWidth = (glyphWidth + config.lineSpacingPx).coerceAtLeast(1f)
    private val glyphHeight = (fontMetrics.descent - fontMetrics.ascent + config.letterSpacingPx).coerceAtLeast(1f)
    private val paragraphSpacing = (config.paragraphSpacingPx * 0.35f).coerceAtLeast(0f)
    private var columnWidth = baseColumnWidth
    private var rubyReservePx = 0f
    private var rubyByStart: Map<Int, EbookRubySpan> = emptyMap()

    fun layout(chapter: TextChapter): TextChapter {
        rubyReservePx = if (chapter.rubySpans.isNotEmpty()) {
            (config.textSizePx * 0.58f).coerceAtLeast(8f)
        } else {
            0f
        }
        columnWidth = baseColumnWidth + rubyReservePx
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

        val pageColumns = mutableListOf<MutableList<TextLine>>()
        val pageStarts = mutableListOf<Int>()
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
                    pageStarts += pageStart
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
                    pageStarts += pageStart
                    pageStart = lineStart
                    currentColumns = mutableListOf()
                    x = (visibleWidth - columnWidth).coerceAtLeast(0f)
                    y = if (lineStart == paragraphStart && config.paragraphIndent.isNotEmpty()) {
                        glyphHeight * config.paragraphIndent.length
                    } else {
                        0f
                    }
                }
                val firstToken = nextToken(text, lineStart, paragraphEnd, splitLatinWords = true)
                if (y + tokenHeight(firstToken) > visibleHeight && y > 0f) {
                    x -= columnWidth
                    y = 0f
                }
                if (currentColumns.isNotEmpty() && x < 0f) {
                    pageColumns += currentColumns
                    pageStarts += pageStart
                    pageStart = lineStart
                    currentColumns = mutableListOf()
                    x = (visibleWidth - columnWidth).coerceAtLeast(0f)
                    y = 0f
                }
                val lineEnd = buildVerticalLineEnd(text, lineStart, paragraphEnd, visibleHeight - y)
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
                while (
                    lineStart < paragraphEnd &&
                    VerticalTextGlyphEngine.isAsciiRunSpace(text[lineStart]) &&
                    text.getOrNull(lineStart + 1)?.let(VerticalTextGlyphEngine::isAsciiWordChar) == true
                ) {
                    lineStart += 1
                }
            }
            if (paragraphEnd == text.length) break
            x -= paragraphSpacing
            paragraphStart = paragraphEnd + 1
        }
        if (currentColumns.isNotEmpty()) {
            pageColumns += currentColumns
            pageStarts += pageStart
        }

        pageColumns.forEachIndexed { index, columns ->
            val rangeStart = pageStarts.getOrElse(index) { 0 }.coerceIn(0, text.length)
            val rangeEnd = pageStarts.getOrNull(index + 1)
                ?.coerceIn(rangeStart, text.length)
                ?: text.length
            val page = TextPage(
                index = index,
                pageInChapter = index,
                chapterPageCount = pageColumns.size,
                chapterIndex = chapter.chapterIndex,
                chapterSize = chapter.chaptersSize,
                charStart = rangeStart,
                charEnd = rangeEnd,
                title = chapter.title,
                text = text.substring(rangeStart, rangeEnd.coerceIn(rangeStart, text.length))
            )
            columns.forEach { line ->
                val rebased = line.copy(pagePosition = line.chapterPosition - page.charStart)
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
            page.height = visibleHeight.toFloat()
            page.width = page.lines.minOfOrNull { it.lineTop }
                ?.let { minLeft -> (visibleWidth - minLeft).coerceAtLeast(columnWidth) }
                ?: visibleWidth.toFloat()
            chapter.addPage(page)
        }
        return chapter
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
            layoutMode = M9LayoutMode.VERTICAL,
            rubyReservePx = rubyReservePx
        )
        var y = startY
        var local = 0
        while (local < text.length) {
            val sourceStart = chapterPosition + local
            val token = nextToken(text, local, text.length, splitLatinWords = true)
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
                val tokenHeight = if (token.isLatinRun) {
                    token.heightPx.coerceAtLeast(glyphHeight)
                } else {
                    glyphHeight * token.heightUnits.coerceAtLeast(1)
                }
                val ruby = rubyByStart[sourceStart]
                line.addColumn(
                    TextColumn(
                        start = y,
                        end = y + tokenHeight,
                        charData = tokenText,
                        sourceStart = sourceStart,
                        sourceEnd = sourceEnd,
                        rubyText = ruby?.text,
                        rubySourceStart = ruby?.start ?: sourceStart,
                        rubySourceEnd = ruby?.end ?: sourceEnd
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

    private fun nextToken(text: String, start: Int, end: Int, splitLatinWords: Boolean): VerticalToken {
        if (start >= end) return VerticalToken("", 0, 0, 0f, false)
        val first = text[start]
        if (VerticalTextGlyphEngine.isAsciiWordChar(first)) {
            var index = start + 1
            while (index < end) {
                val char = text[index]
                when {
                    VerticalTextGlyphEngine.isAsciiWordChar(char) -> index += 1
                    splitLatinWords && VerticalTextGlyphEngine.isAsciiRunSpace(char) -> {
                        index += 1
                        while (index < end && VerticalTextGlyphEngine.isAsciiRunSpace(text[index])) {
                            index += 1
                        }
                        break
                    }
                    VerticalTextGlyphEngine.isAsciiRunSpace(char) -> {
                        var next = index + 1
                        while (next < end && VerticalTextGlyphEngine.isAsciiRunSpace(text[next])) {
                            next += 1
                        }
                        if (next < end && VerticalTextGlyphEngine.isAsciiWordChar(text[next])) {
                            index = next + 1
                        } else {
                            break
                        }
                    }
                    else -> break
                }
            }
            val tokenText = text.substring(start, index)
            val heightPx = latinRunHeight(tokenText)
            val measuredUnits = ceil((heightPx / glyphHeight).coerceAtLeast(1f).toDouble()).toInt()
            return VerticalToken(
                text = tokenText,
                length = index - start,
                heightUnits = measuredUnits.coerceAtLeast(1),
                heightPx = heightPx,
                isLatinRun = true
            )
        }
        return VerticalToken(
            text = first.toString(),
            length = 1,
            heightUnits = 1,
            heightPx = glyphHeight,
            isLatinRun = false
        )
    }

    private fun latinRunHeight(text: String): Float {
        return (contentPaint.measureText(text) + config.letterSpacingPx + config.textSizePx * 0.22f)
            .coerceAtLeast(glyphHeight)
    }

    private fun tokenHeight(token: VerticalToken): Float {
        return if (token.isLatinRun) {
            token.heightPx
        } else {
            glyphHeight * token.heightUnits.coerceAtLeast(1)
        }
    }

    private fun buildVerticalLineEnd(text: String, start: Int, paragraphEnd: Int, availableHeight: Float): Int {
        val maxHeight = availableHeight.coerceAtLeast(glyphHeight)
        var cursor = start
        var usedHeight = 0f
        var lastEnd = start
        while (cursor < paragraphEnd) {
            val token = nextToken(text, cursor, paragraphEnd, splitLatinWords = true)
            if (token.length <= 0) break
            val height = tokenHeight(token)
            if (usedHeight > 0f && usedHeight + height > maxHeight) {
                break
            }
            if (usedHeight == 0f && height > maxHeight) {
                val count = if (token.isLatinRun) {
                    fitLatinRunLength(token.text, maxHeight)
                } else {
                    1
                }
                return (cursor + count).coerceIn(cursor + 1, paragraphEnd)
            }
            if (token.isLatinRun) {
                Log.d(
                    READER_LATIN_LOG_TAG,
                    "layout latin token start=$cursor yUsed=$usedHeight remaining=${maxHeight - usedHeight} " +
                        "heightPx=${token.heightPx} text='${token.text}'"
                )
            }
            cursor += token.length
            usedHeight += height
            lastEnd = cursor
        }
        return if (lastEnd > start) lastEnd else (start + 1).coerceAtMost(paragraphEnd)
    }

    private fun fitLatinRunLength(text: String, maxHeightPx: Float): Int {
        val width = maxHeightPx.coerceAtLeast(glyphHeight)
        val count = contentPaint.breakText(text, true, width, null).coerceAtLeast(1)
        if (count >= text.length) return count
        val lastSpaceBeforeBreak = text
            .substring(0, count)
            .indexOfLast(VerticalTextGlyphEngine::isAsciiRunSpace)
        if (lastSpaceBeforeBreak >= 0) {
            val result = (lastSpaceBeforeBreak + 1).coerceAtLeast(1)
            Log.d(
                READER_LATIN_LOG_TAG,
                "fit latin by space maxHeight=$maxHeightPx breakText=$count result=$result " +
                    "part='${text.take(result)}' rest='${text.drop(result)}'"
            )
            return result
        }
        Log.d(
            READER_LATIN_LOG_TAG,
            "fit latin hard maxHeight=$maxHeightPx breakText=$count " +
                "part='${text.take(count)}' rest='${text.drop(count)}'"
        )
        return count.coerceIn(1, text.length)
    }

    private data class VerticalToken(
        val text: String,
        val length: Int,
        val heightUnits: Int,
        val heightPx: Float,
        val isLatinRun: Boolean
    )

    private data class ImageBlockSize(
        val width: Float,
        val height: Float
    )
}
