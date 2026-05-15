package moe.tekuza.m9player

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.text.Html
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLDecoder
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.max

internal data class EbookDocument(
    val title: String,
    val format: String,
    val chapters: List<EbookChapter>
)

internal data class EbookChapter(
    val title: String,
    val text: String,
    val sourcePath: String? = null
)

internal data class EbookPage(
    val chapterIndex: Int,
    val pageInChapter: Int,
    val chapterPageCount: Int,
    val globalIndex: Int,
    val totalPages: Int,
    val charStart: Int,
    val charEnd: Int,
    val title: String,
    val text: String
) {
    val progressText: String
        get() {
            if (totalPages <= 0) return "0.0%"
            val percent = ((globalIndex + 1).toDouble() / totalPages.toDouble() * 100.0)
                .coerceIn(0.0, 100.0)
            return String.format(Locale.US, "%.1f%%", percent)
        }
}

internal data class EbookReaderLayout(
    val contentWidthPx: Int,
    val contentHeightPx: Int,
    val textSizePx: Float,
    val lineSpacingPx: Float,
    val paragraphSpacingPx: Float
)

internal data class EbookSrtCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

internal data class EbookCueMatch(
    val cueIndex: Int,
    val chapterIndex: Int,
    val rawStart: Int,
    val rawEnd: Int
)

internal data class EbookMatchData(
    val matches: List<EbookCueMatch>,
    val unmatched: Int,
    val totalCues: Int
) {
    val matchRateText: String
        get() {
            if (totalCues <= 0) return "0%"
            val rate = matches.size.toDouble() / totalCues.toDouble() * 100.0
            return String.format(Locale.US, "%.1f%%", rate)
        }
}

internal suspend fun loadEbookDocument(
    context: Context,
    book: LocalReaderBook,
    preferredCharsetName: String? = null
): EbookDocument = withContext(Dispatchers.IO) {
    val displayTitle = book.title.ifBlank { "Untitled Book" }
    when (book.format.uppercase(Locale.US)) {
        "EPUB" -> loadEpubDocument(context.contentResolver, book.uri, displayTitle, preferredCharsetName)
        "TXT" -> loadTxtDocument(context.contentResolver, book.uri, displayTitle, preferredCharsetName)
        else -> {
            val mimeFormat = inferLocalReaderBookFormat(displayTitle, context.contentResolver.getType(book.uri))
            if (mimeFormat == "EPUB") {
                loadEpubDocument(context.contentResolver, book.uri, displayTitle, preferredCharsetName)
            } else {
                loadTxtDocument(context.contentResolver, book.uri, displayTitle, preferredCharsetName)
            }
        }
    }
}

internal fun paginateEbookDocument(
    document: EbookDocument,
    layout: EbookReaderLayout
): List<EbookPage> {
    val safeWidth = layout.contentWidthPx.coerceAtLeast(1)
    val safeHeight = layout.contentHeightPx.coerceAtLeast(1)
    val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = layout.textSizePx
    }

    val pending = mutableListOf<EbookPage>()
    document.chapters.forEachIndexed { chapterIndex, chapter ->
        val text = chapter.text.normalizeReaderWhitespace()
        val ranges = splitChapterIntoMeasuredPageRanges(
            text = text,
            paint = paint,
            widthPx = safeWidth,
            heightPx = safeHeight,
            lineSpacingPx = layout.lineSpacingPx
        )
        ranges.forEachIndexed { pageInChapter, range ->
            val start = range.first.coerceIn(0, text.length)
            val endExclusive = (range.last + 1).coerceIn(start, text.length)
            pending += EbookPage(
                chapterIndex = chapterIndex,
                pageInChapter = pageInChapter,
                chapterPageCount = ranges.size,
                globalIndex = pending.size,
                totalPages = 0,
                charStart = start,
                charEnd = endExclusive,
                title = chapter.title,
                text = text.substring(start, endExclusive)
            )
        }
    }
    val total = pending.size.coerceAtLeast(1)
    return pending.mapIndexed { index, page ->
        page.copy(globalIndex = index, totalPages = total)
    }.ifEmpty {
        listOf(
            EbookPage(
                chapterIndex = 0,
                pageInChapter = 0,
                chapterPageCount = 1,
                globalIndex = 0,
                totalPages = 1,
                charStart = 0,
                charEnd = 0,
                title = document.title,
                text = "没有可显示的文本。"
            )
        )
    }
}

internal suspend fun parseEbookSrt(
    contentResolver: ContentResolver,
    uri: Uri
): List<EbookSrtCue> = withContext(Dispatchers.IO) {
    val raw = contentResolver.openInputStream(uri)?.use { input ->
        input.readBytes().decodeTextFile()
    }.orEmpty()
    parseEbookSrtText(raw)
}

internal fun matchEbookCues(
    document: EbookDocument,
    cues: List<EbookSrtCue>,
    searchWindow: Int = 200
): List<EbookCueMatch> {
    return matchEbookCuesData(document, cues, searchWindow).matches
}

internal fun matchEbookCuesData(
    document: EbookDocument,
    cues: List<EbookSrtCue>,
    searchWindow: Int = 200
): EbookMatchData {
    val index = buildMatchingIndex(document)
    val source = index.source.codePoints().toArray().toList()
    val chapterRanges = index.chapterRanges
    val chapterMaps = index.chapterMaps

    var cursor = 0
    var minStart: Int? = null
    cues.take(15).forEach { cue ->
        if (cue.text.startsWith("＊")) return@forEach
        val text = cue.text.filteredReaderCodePoints()
        if (text.size < 6) return@forEach
        val found = findCodePointText(source, text, start = 0, end = source.size) ?: return@forEach
        minStart = minOf(minStart ?: found, found)
    }
    minStart?.let { cursor = it }

    val matches = mutableListOf<EbookCueMatch>()
    var unmatched = 0
    cues.forEachIndexed { cueIndex, cue ->
        val text = cue.text.filteredReaderCodePoints()
        if (text.isEmpty()) {
            unmatched += 1
            return@forEachIndexed
        }
        val start = findCodePointText(
            source = source,
            text = text,
            start = cursor,
            end = minOf(source.size, cursor + text.size + searchWindow)
        )
        if (start == null) {
            unmatched += 1
            return@forEachIndexed
        }
        val end = start + text.size
        val match = resolveCueMatch(
            document = document,
            cueIndex = cueIndex,
            filteredStart = start,
            filteredEndExclusive = end,
            chapterRanges = chapterRanges,
            chapterMaps = chapterMaps
        )
        if (match == null) {
            unmatched += 1
            return@forEachIndexed
        }
        matches += match
        cursor = end
    }
    return EbookMatchData(
        matches = matches,
        unmatched = unmatched,
        totalCues = cues.size
    )
}

private data class EbookMatchingIndex(
    val source: String,
    val chapterMaps: List<FilteredTextMap>,
    val chapterRanges: List<IntRange>
) {
    val sourceCodePointSize: Int get() = source.codePointCount(0, source.length)
}

private fun buildMatchingIndex(document: EbookDocument): EbookMatchingIndex {
    val chapterMaps = document.chapters.map { chapter ->
        buildFilteredTextMap(chapter.text)
    }
    val source = StringBuilder()
    val chapterRanges = mutableListOf<IntRange>()
    var codePointStart = 0
    chapterMaps.forEach { map ->
        source.append(map.filtered)
        val length = map.filtered.codePointCount(0, map.filtered.length)
        chapterRanges += codePointStart until (codePointStart + length)
        codePointStart += length
    }
    return EbookMatchingIndex(
        source = source.toString(),
        chapterMaps = chapterMaps,
        chapterRanges = chapterRanges
    )
}

private fun resolveCueMatch(
    document: EbookDocument,
    cueIndex: Int,
    filteredStart: Int,
    filteredEndExclusive: Int,
    chapterRanges: List<IntRange>,
    chapterMaps: List<FilteredTextMap>
): EbookCueMatch? {
    val chapterIndex = chapterRanges.indexOfFirst { filteredStart in it }
    if (chapterIndex < 0 || filteredEndExclusive > chapterRanges[chapterIndex].last + 1) return null
    val localStart = filteredStart - chapterRanges[chapterIndex].first
    val localEnd = (filteredEndExclusive - chapterRanges[chapterIndex].first - 1).coerceAtLeast(localStart)
    val map = chapterMaps[chapterIndex]
    val rawStart = map.rawIndices.getOrNull(localStart) ?: return null
    val rawEnd = (map.rawIndices.getOrNull(localEnd)?.let { index ->
        document.chapters[chapterIndex].text.offsetByCodePoints(index, 1)
    } ?: rawStart).coerceAtLeast(rawStart)
    return EbookCueMatch(
        cueIndex = cueIndex,
        chapterIndex = chapterIndex,
        rawStart = rawStart,
        rawEnd = rawEnd
    )
}

internal fun findEbookPageForMatch(pages: List<EbookPage>, match: EbookCueMatch): Int {
    return pages.indexOfFirst { page ->
        page.chapterIndex == match.chapterIndex &&
            match.rawStart >= page.charStart &&
            match.rawStart < page.charEnd
    }.takeIf { it >= 0 } ?: pages.indexOfFirst { it.chapterIndex == match.chapterIndex }.coerceAtLeast(0)
}

internal fun findEbookCueIndexAtTime(cues: List<EbookSrtCue>, timeMs: Long): Int {
    if (cues.isEmpty()) return -1
    var low = 0
    var high = cues.lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        val cue = cues[mid]
        when {
            timeMs < cue.startMs -> high = mid - 1
            timeMs > cue.endMs -> low = mid + 1
            else -> return mid
        }
    }
    return (low - 1).coerceIn(-1, cues.lastIndex)
}

internal fun highlightPageText(page: EbookPage, match: EbookCueMatch?): Pair<Int, Int>? {
    if (match == null || match.chapterIndex != page.chapterIndex) return null
    val start = (match.rawStart - page.charStart).coerceIn(0, page.text.length)
    val end = (match.rawEnd - page.charStart).coerceIn(start, page.text.length)
    if (end <= start) return null
    return start to end
}

private fun loadTxtDocument(
    contentResolver: ContentResolver,
    uri: Uri,
    fallbackTitle: String,
    preferredCharsetName: String?
): EbookDocument {
    val raw = contentResolver.openInputStream(uri)?.use { input ->
        input.readBytes().decodeTextFile(preferredCharsetName)
    }.orEmpty()
    val chapters = splitTxtChapters(raw)
    return EbookDocument(
        title = fallbackTitle,
        format = "TXT",
        chapters = chapters.ifEmpty { listOf(EbookChapter(fallbackTitle, raw)) }
    )
}

private fun loadEpubDocument(
    contentResolver: ContentResolver,
    uri: Uri,
    fallbackTitle: String,
    preferredCharsetName: String?
): EbookDocument {
    val entries = linkedMapOf<String, ByteArray>()
    contentResolver.openInputStream(uri)?.use { input ->
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    entries[entry.name.normalizeZipPath()] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }
    }
    if (entries.isEmpty()) {
        return EbookDocument(fallbackTitle, "EPUB", listOf(EbookChapter(fallbackTitle, "")))
    }
    val container = entries["META-INF/container.xml"]?.toString(StandardCharsets.UTF_8)
    val opfPath = container?.let(::parseContainerRootFile)
        ?: entries.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
        ?: return fallbackHtmlEpub(entries, fallbackTitle, preferredCharsetName)
    val opfText = entries[opfPath]?.toString(StandardCharsets.UTF_8)
        ?: return fallbackHtmlEpub(entries, fallbackTitle, preferredCharsetName)
    val opf = parseOpf(opfText)
    val basePath = opfPath.substringBeforeLast('/', missingDelimiterValue = "")
    val title = opf.title.ifBlank { fallbackTitle }
    val chapters = opf.spineIds.mapNotNull { id ->
        val item = opf.manifest[id] ?: return@mapNotNull null
        val path = resolveEpubPath(basePath, item.href)
        val bytes = entries[path] ?: return@mapNotNull null
        val html = bytes.decodeTextFile(preferredCharsetName)
        val text = htmlToReaderText(html)
        if (text.isBlank()) return@mapNotNull null
        EbookChapter(
            title = extractHtmlTitle(html).ifBlank { title },
            text = text,
            sourcePath = path
        )
    }.ifEmpty {
        htmlEntries(entries).map { (path, bytes) ->
            val html = bytes.decodeTextFile(preferredCharsetName)
            EbookChapter(
                title = extractHtmlTitle(html).ifBlank { File(path).nameWithoutExtension },
                text = htmlToReaderText(html),
                sourcePath = path
            )
        }.filter { it.text.isNotBlank() }
    }
    return EbookDocument(
        title = title,
        format = "EPUB",
        chapters = chapters.ifEmpty { listOf(EbookChapter(title, "")) }
    )
}

private fun fallbackHtmlEpub(
    entries: Map<String, ByteArray>,
    fallbackTitle: String,
    preferredCharsetName: String?
): EbookDocument {
    val chapters = htmlEntries(entries).map { (path, bytes) ->
        val html = bytes.decodeTextFile(preferredCharsetName)
        EbookChapter(
            title = extractHtmlTitle(html).ifBlank { File(path).nameWithoutExtension },
            text = htmlToReaderText(html),
            sourcePath = path
        )
    }.filter { it.text.isNotBlank() }
    return EbookDocument(fallbackTitle, "EPUB", chapters.ifEmpty { listOf(EbookChapter(fallbackTitle, "")) })
}

private fun htmlEntries(entries: Map<String, ByteArray>): List<Pair<String, ByteArray>> {
    return entries
        .filterKeys { path ->
            path.endsWith(".xhtml", true) ||
                path.endsWith(".html", true) ||
                path.endsWith(".htm", true)
        }
        .toList()
        .sortedBy { it.first }
}

private fun parseContainerRootFile(xml: String): String? {
    val parser = Xml.newPullParser()
    parser.setInput(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)), "UTF-8")
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
            return parser.getAttributeValue(null, "full-path")?.normalizeZipPath()
        }
    }
    return null
}

private data class OpfItem(val href: String, val mediaType: String?)
private data class OpfData(
    val title: String,
    val manifest: Map<String, OpfItem>,
    val spineIds: List<String>
)

private fun parseOpf(xml: String): OpfData {
    val parser = Xml.newPullParser()
    parser.setInput(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)), "UTF-8")
    val manifest = linkedMapOf<String, OpfItem>()
    val spine = mutableListOf<String>()
    var title = ""
    var currentTag = ""
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                currentTag = parser.name.orEmpty()
                when (currentTag) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id").orEmpty()
                        val href = parser.getAttributeValue(null, "href").orEmpty()
                        if (id.isNotBlank() && href.isNotBlank()) {
                            manifest[id] = OpfItem(
                                href = href,
                                mediaType = parser.getAttributeValue(null, "media-type")
                            )
                        }
                    }
                    "itemref" -> {
                        val idRef = parser.getAttributeValue(null, "idref").orEmpty()
                        if (idRef.isNotBlank()) spine += idRef
                    }
                }
            }
            XmlPullParser.TEXT -> {
                if (currentTag.endsWith("title") && title.isBlank()) {
                    title = parser.text.orEmpty().trim()
                }
            }
            XmlPullParser.END_TAG -> currentTag = ""
        }
    }
    val readableSpine = spine.filter { id ->
        val type = manifest[id]?.mediaType.orEmpty()
        type.contains("html", ignoreCase = true) || type.contains("xhtml", ignoreCase = true) || type.isBlank()
    }
    return OpfData(title = title, manifest = manifest, spineIds = readableSpine.ifEmpty { spine })
}

private fun splitTxtChapters(text: String): List<EbookChapter> {
    val normalized = text.normalizeReaderWhitespace()
    if (normalized.isBlank()) return emptyList()
    val chapterRegex = Regex(
        pattern = """(?m)^\s*((第[0-9０-９一二三四五六七八九十百千万〇零两]{1,8}[章节章回].{0,32})|(Chapter\s+\d+.{0,32}))\s*$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )
    val matches = chapterRegex.findAll(normalized).toList()
    if (matches.size < 2) {
        return listOf(EbookChapter("正文", normalized))
    }
    val chapters = mutableListOf<EbookChapter>()
    matches.forEachIndexed { index, match ->
        val start = match.range.first
        val end = matches.getOrNull(index + 1)?.range?.first ?: normalized.length
        val body = normalized.substring(start, end).trim()
        if (body.isNotBlank()) {
            chapters += EbookChapter(match.value.trim(), body)
        }
    }
    return chapters
}

private fun splitChapterIntoMeasuredPageRanges(
    text: String,
    paint: TextPaint,
    widthPx: Int,
    heightPx: Int,
    lineSpacingPx: Float
): List<IntRange> {
    if (text.isEmpty()) return listOf(0 until 0)
    val staticLayout = StaticLayout.Builder
        .obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(true)
        .setLineSpacing(lineSpacingPx, 1f)
        .build()
    val ranges = mutableListOf<IntRange>()
    var line = 0
    while (line < staticLayout.lineCount) {
        val startOffset = staticLayout.getLineStart(line)
        val pageTop = staticLayout.getLineTop(line)
        val pageBottom = pageTop + heightPx
        var endLine = line
        while (
            endLine < staticLayout.lineCount &&
            staticLayout.getLineBottom(endLine) <= pageBottom
        ) {
            endLine += 1
        }
        if (endLine == line) endLine = (line + 1).coerceAtMost(staticLayout.lineCount)
        var endOffset = staticLayout.getLineEnd(endLine - 1).coerceAtLeast(startOffset)
        if (endOffset == startOffset && endOffset < text.length) endOffset += 1
        ranges += startOffset until endOffset
        line = endLine
    }
    return ranges
}

private fun parseEbookSrtText(raw: String): List<EbookSrtCue> {
    val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
    val blocks = normalized.split(Regex("\n{2,}"))
    val cues = mutableListOf<EbookSrtCue>()
    blocks.forEach { block ->
        val lines = block.lines().map { it.trim() }.filter { it.isNotBlank() }
        val timeIndex = lines.indexOfFirst { it.contains("-->") }
        if (timeIndex < 0) return@forEach
        val parts = lines[timeIndex].split("-->")
        if (parts.size < 2) return@forEach
        val start = parseSrtTimestamp(parts[0].trim()) ?: return@forEach
        val end = parseSrtTimestamp(parts[1].trim().substringBefore(' ')) ?: return@forEach
        val text = lines.drop(timeIndex + 1)
            .joinToString("\n")
            .let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
            .trim()
        if (text.isNotBlank()) cues += EbookSrtCue(start, end, text)
    }
    return cues.sortedBy { it.startMs }
}

private fun parseSrtTimestamp(raw: String): Long? {
    val normalized = raw.replace(',', '.')
    val parts = normalized.split(':')
    if (parts.size != 3) return null
    val secondsParts = parts[2].split('.')
    val hours = parts[0].toLongOrNull() ?: return null
    val minutes = parts[1].toLongOrNull() ?: return null
    val seconds = secondsParts.getOrNull(0)?.toLongOrNull() ?: return null
    val millis = secondsParts.getOrNull(1)
        ?.padEnd(3, '0')
        ?.take(3)
        ?.toLongOrNull()
        ?: 0L
    return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
}

private data class FilteredTextMap(
    val filtered: String,
    val rawIndices: List<Int>
)

private fun buildFilteredTextMap(raw: String): FilteredTextMap {
    val filtered = StringBuilder()
    val rawIndices = mutableListOf<Int>()
    var offset = 0
    while (offset < raw.length) {
        val codePoint = raw.codePointAt(offset)
        if (codePoint.isReaderMatchableCodePoint()) {
            filtered.appendCodePoint(codePoint)
            rawIndices += offset
        }
        offset += Character.charCount(codePoint)
    }
    return FilteredTextMap(filtered.toString(), rawIndices)
}

private fun String.filteredReaderMatchText(): String {
    val builder = StringBuilder()
    var offset = 0
    while (offset < length) {
        val codePoint = codePointAt(offset)
        if (codePoint.isReaderMatchableCodePoint()) {
            builder.appendCodePoint(codePoint)
        }
        offset += Character.charCount(codePoint)
    }
    return builder.toString()
}

private fun Int.isReaderMatchableCodePoint(): Boolean =
    when (this) {
        in '0'.code..'9'.code,
        in 'A'.code..'Z'.code,
        in 'a'.code..'z'.code,
        '○'.code,
        '◯'.code,
        in '々'.code..'〇'.code,
        '〻'.code,
        in 'ぁ'.code..'ゖ'.code,
        in 'ゝ'.code..'ゞ'.code,
        in 'ァ'.code..'ヺ'.code,
        'ー'.code,
        in '０'.code..'９'.code,
        in 'Ａ'.code..'Ｚ'.code,
        in 'ａ'.code..'ｚ'.code,
        in 'ｦ'.code..'ﾝ'.code,
        in 0x2E80..0x2FDF,
        in 0x3400..0x4DBF,
        in 0x4E00..0x9FFF,
        in 0x20000..0x2A6DF,
        in 0x2A700..0x2B73F,
        in 0x2B740..0x2B81F,
        in 0x2B820..0x2CEAF,
        in 0x2CEB0..0x2EBEF,
        in 0x30000..0x3134F,
        in 0x31350..0x323AF -> true
        else -> false
    }

private fun htmlToReaderText(html: String): String {
    var body = Regex("(?is)<body[^>]*>(.*?)</body>").find(html)?.groupValues?.getOrNull(1) ?: html
    body = body
        .replace(Regex("(?is)<rt[^>]*>.*?</rt>"), "")
        .replace(Regex("(?is)<rp[^>]*>.*?</rp>"), "")
        .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p\\s*>"), "\n\n")
        .replace(Regex("(?i)</h[1-6]\\s*>"), "\n\n")
        .replace(Regex("(?i)</div\\s*>"), "\n")
    return Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY)
        .toString()
        .normalizeReaderWhitespace()
}

private fun extractHtmlTitle(html: String): String {
    val heading = Regex("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>").find(html)?.groupValues?.getOrNull(1)
    val title = heading ?: Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.getOrNull(1)
    return title
        ?.replace(Regex("<[^>]+>"), "")
        ?.trim()
        .orEmpty()
}

private fun ByteArray.decodeTextFile(preferredCharsetName: String? = null): String {
    preferredCharsetName
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { charsetName ->
            runCatching { return toString(Charset.forName(charsetName)) }
        }
    if (size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()) {
        return copyOfRange(3, size).toString(StandardCharsets.UTF_8)
    }
    if (size >= 2 && this[0] == 0xFF.toByte() && this[1] == 0xFE.toByte()) {
        return copyOfRange(2, size).toString(Charset.forName("UTF-16LE"))
    }
    if (size >= 2 && this[0] == 0xFE.toByte() && this[1] == 0xFF.toByte()) {
        return copyOfRange(2, size).toString(Charset.forName("UTF-16BE"))
    }
    val utf8 = toString(StandardCharsets.UTF_8)
    if (utf8.count { it == '\uFFFD' } <= max(2, utf8.length / 100)) return utf8
    return runCatching { toString(Charset.forName("Shift_JIS")) }.getOrElse { utf8 }
}

private fun String.normalizeReaderWhitespace(): String {
    return replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace('\u00A0', ' ')
        .lines()
        .joinToString("\n") { line -> line.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

private fun String.normalizeZipPath(): String {
    return trim()
        .replace('\\', '/')
        .removePrefix("/")
}

private fun resolveEpubPath(basePath: String, href: String): String {
    val decoded = runCatching { URLDecoder.decode(href.substringBefore('#'), "UTF-8") }
        .getOrElse { href.substringBefore('#') }
    val combined = if (basePath.isBlank()) decoded else "$basePath/$decoded"
    val out = ArrayDeque<String>()
    combined.normalizeZipPath().split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeLast()
            else -> out.addLast(part)
        }
    }
    return out.joinToString("/")
}

private fun String.filteredReaderCodePoints(): List<Int> =
    filteredReaderMatchText().codePoints().toArray().toList()

private fun findCodePointText(source: List<Int>, text: List<Int>, start: Int, end: Int): Int? {
    if (text.isEmpty()) return null
    var index = start.coerceAtLeast(0)
    val last = end.coerceAtMost(source.size) - text.size
    while (index <= last) {
        var matched = true
        for (i in text.indices) {
            if (source[index + i] != text[i]) {
                matched = false
                break
            }
        }
        if (matched) return index
        index += 1
    }
    return null
}
