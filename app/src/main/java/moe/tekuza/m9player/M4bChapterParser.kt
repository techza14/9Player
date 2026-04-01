package moe.tekuza.m9player

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

internal data class M4bChapter(
    val startMs: Long,
    val title: String
)

private val MP4_CONTAINER_ATOMS = setOf(
    "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "meta", "ilst", "moof", "traf", "mvex"
)
private const val MAX_CHAPTER_NAME_BYTES = 4096

internal fun loadM4bChapters(
    context: Context,
    contentResolver: ContentResolver,
    audioUri: Uri?
): List<M4bChapter> {
    val uri = audioUri ?: return emptyList()
    val cacheFile = resolveM4bChapterCacheFile(context, uri)
    readM4bChapterCache(cacheFile)?.let { return it }

    val parsed = loadM4bChaptersNoCache(contentResolver, uri)
    writeM4bChapterCache(cacheFile, parsed)
    return parsed
}

private fun loadM4bChaptersNoCache(
    contentResolver: ContentResolver,
    uri: Uri
): List<M4bChapter> {
    val scheme = uri.scheme?.lowercase().orEmpty()

    val chplChapters = when (scheme) {
        "file", "" -> {
            val path = uri.path ?: return emptyList()
            if (!path.endsWith(".m4b", ignoreCase = true)) return emptyList()
            val file = File(path)
            if (!file.exists() || !file.isFile) return emptyList()
            runCatching {
                parseM4bChaptersFromFile(file).ifEmpty {
                    parseM4bChapTrackFromFile(file)
                }
            }.getOrDefault(emptyList())
        }

        "content" -> {
            val tempFile = runCatching {
                val tmp = File.createTempFile("chapter-", ".m4b")
                contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                } ?: return emptyList()
                tmp
            }.getOrNull() ?: return emptyList()

            try {
                runCatching {
                    parseM4bChaptersFromFile(tempFile).ifEmpty {
                        parseM4bChapTrackFromFile(tempFile)
                    }
                }.getOrDefault(emptyList())
            } finally {
                runCatching { tempFile.delete() }
            }
        }

        else -> emptyList()
    }
    return chplChapters
}

private fun resolveM4bChapterCacheFile(context: Context, uri: Uri): File {
    val dir = File(context.cacheDir, "m4b_chapter_cache")
    if (!dir.exists()) {
        runCatching { dir.mkdirs() }
    }
    val key = uri.toString()
        .toByteArray(StandardCharsets.UTF_8)
        .fold(1469598103934665603L) { acc, byte ->
            (acc xor (byte.toLong() and 0xFFL)) * 1099511628211L
        }
        .toULong()
        .toString(16)
    return File(dir, "$key.json")
}

private fun readM4bChapterCache(file: File): List<M4bChapter>? {
    if (!file.isFile || file.length() <= 0L) return null
    return runCatching {
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val chapters = root.optJSONArray("chapters") ?: JSONArray()
        val out = mutableListOf<M4bChapter>()
        for (index in 0 until chapters.length()) {
            val item = chapters.optJSONObject(index) ?: continue
            val startMs = item.optLong("startMs", -1L)
            val title = item.optString("title").trim()
            if (startMs < 0L) continue
            out += M4bChapter(startMs = startMs, title = title.ifBlank { "Chapter ${index + 1}" })
        }
        val normalized = out
            .distinctBy { it.startMs }
            .sortedBy { it.startMs }
        if (normalized.isEmpty()) {
            null
        } else {
            normalized
        }
    }.getOrNull()
}

private fun writeM4bChapterCache(file: File, chapters: List<M4bChapter>) {
    if (chapters.isEmpty()) return
    runCatching {
        val root = JSONObject()
        val array = JSONArray()
        chapters.forEach { chapter ->
            array.put(
                JSONObject().apply {
                    put("startMs", chapter.startMs)
                    put("title", chapter.title)
                }
            )
        }
        root.put("chapters", array)
        file.writeText(root.toString(), Charsets.UTF_8)
    }
}

private data class TrackSampleToChunk(
    val firstChunk: Long,
    val samplesPerChunk: Int
)

private data class TrackTimeToSample(
    val sampleCount: Long,
    val sampleDelta: Long
)

private data class Mp4TrackParseState(
    var trackId: Int? = null,
    var chapterRefTrackId: Int? = null,
    var timeScale: Long? = null,
    val timeToSamples: MutableList<TrackTimeToSample> = mutableListOf(),
    val sampleToChunk: MutableList<TrackSampleToChunk> = mutableListOf(),
    val chunkOffsets: MutableList<Long> = mutableListOf()
)

private fun decodeChapterSampleTitle(sampleBytes: ByteArray): String {
    if (sampleBytes.isEmpty()) return ""

    val payload = if (sampleBytes.size >= 2) {
        val length = ((sampleBytes[0].toInt() and 0xFF) shl 8) or (sampleBytes[1].toInt() and 0xFF)
        if (length > 0 && length <= sampleBytes.size - 2) {
            sampleBytes.copyOfRange(2, 2 + length)
        } else {
            sampleBytes
        }
    } else {
        sampleBytes
    }

    val text = when {
        payload.size >= 2 && payload[0] == 0xFE.toByte() && payload[1] == 0xFF.toByte() -> {
            String(payload, 2, payload.size - 2, Charsets.UTF_16BE)
        }
        payload.size >= 2 && payload[0] == 0xFF.toByte() && payload[1] == 0xFE.toByte() -> {
            String(payload, 2, payload.size - 2, Charsets.UTF_16LE)
        }
        else -> String(payload, Charsets.UTF_8)
    }

    return text
        .replace("\u0000", "")
        .replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), "")
        .trim()
}

private fun parseM4bChapTrackFromFile(file: File): List<M4bChapter> {
    RandomAccessFile(file, "r").use { raf ->
        val rootStart = 0L
        val rootEnd = raf.length().coerceAtLeast(0L)
        val moovAtom = findAtomByType(raf, rootStart, rootEnd, "moov") ?: return emptyList()
        val moovPayloadStart = moovAtom.first
        val moovPayloadEnd = moovPayloadStart + moovAtom.second

        val tracks = mutableListOf<Mp4TrackParseState>()
        forEachChildAtom(raf, moovPayloadStart, moovPayloadEnd) { atomType, payloadStart, payloadSize ->
            if (atomType != "trak") return@forEachChildAtom
            val track = parseTrakAtom(raf, payloadStart, payloadStart + payloadSize)
            if (track.trackId != null) {
                tracks += track
            }
        }

        val chapterTrackId = tracks.firstNotNullOfOrNull { it.chapterRefTrackId } ?: return emptyList()
        val chapterTrack = tracks.firstOrNull { it.trackId == chapterTrackId } ?: return emptyList()
        if (chapterTrack.chunkOffsets.isEmpty() || chapterTrack.timeScale == null) return emptyList()

        val sampleDurations = chapterTrack.timeToSamples.toList()
        if (sampleDurations.isEmpty()) return emptyList()

        var sttsEntryIndex = 0
        var sttsEntryRemaining = sampleDurations.first().sampleCount
        var currentPositionInTimeScale = 0L
        var chapterIndex = 1

        val chapters = mutableListOf<M4bChapter>()
        val sortedOffsets = chapterTrack.chunkOffsets
            .distinct()
            .sorted()

        sortedOffsets.forEachIndexed { chunkIndex, chunkOffset ->
            val name = readChapChapterName(raf, chunkOffset, file.length())
                .ifBlank { "Chapter $chapterIndex" }
            chapterIndex += 1

            chapters += M4bChapter(
                startMs = (currentPositionInTimeScale * 1000L / chapterTrack.timeScale!!).coerceAtLeast(0L),
                title = name
            )

            val samplesInChunk = samplesPerChunkForChunk(chapterTrack.sampleToChunk, chunkIndex + 1)
            var samplesToConsume = samplesInChunk.toLong().coerceAtLeast(1L)
            while (samplesToConsume > 0 && sttsEntryIndex < sampleDurations.size) {
                if (sttsEntryRemaining <= 0L) {
                    sttsEntryIndex += 1
                    if (sttsEntryIndex >= sampleDurations.size) break
                    sttsEntryRemaining = sampleDurations[sttsEntryIndex].sampleCount
                    continue
                }
                val take = minOf(samplesToConsume, sttsEntryRemaining)
                currentPositionInTimeScale += take * sampleDurations[sttsEntryIndex].sampleDelta
                samplesToConsume -= take
                sttsEntryRemaining -= take
            }
        }

        return chapters
            .distinctBy { it.startMs }
            .sortedBy { it.startMs }
    }
}

private fun parseTrakAtom(
    raf: RandomAccessFile,
    trakPayloadStart: Long,
    trakPayloadEnd: Long
): Mp4TrackParseState {
    val state = Mp4TrackParseState()
    forEachChildAtom(raf, trakPayloadStart, trakPayloadEnd) { atomType, payloadStart, payloadSize ->
        when (atomType) {
            "tkhd" -> state.trackId = parseTkhdTrackId(raf, payloadStart, payloadSize)
            "tref" -> state.chapterRefTrackId = parseTrefChapTrackId(raf, payloadStart, payloadStart + payloadSize)
            "mdia" -> parseMdiaForChapterData(raf, payloadStart, payloadStart + payloadSize, state)
        }
    }
    return state
}

private fun parseMdiaForChapterData(
    raf: RandomAccessFile,
    mdiaPayloadStart: Long,
    mdiaPayloadEnd: Long,
    state: Mp4TrackParseState
) {
    forEachChildAtom(raf, mdiaPayloadStart, mdiaPayloadEnd) { atomType, payloadStart, payloadSize ->
        when (atomType) {
            "mdhd" -> state.timeScale = parseMdhdTimescale(raf, payloadStart, payloadSize)
            "minf" -> parseMinfForChapterData(raf, payloadStart, payloadStart + payloadSize, state)
        }
    }
}

private fun parseMinfForChapterData(
    raf: RandomAccessFile,
    minfPayloadStart: Long,
    minfPayloadEnd: Long,
    state: Mp4TrackParseState
) {
    forEachChildAtom(raf, minfPayloadStart, minfPayloadEnd) { atomType, payloadStart, payloadSize ->
        if (atomType == "stbl") {
            parseStblForChapterData(raf, payloadStart, payloadStart + payloadSize, state)
        }
    }
}

private fun parseStblForChapterData(
    raf: RandomAccessFile,
    stblPayloadStart: Long,
    stblPayloadEnd: Long,
    state: Mp4TrackParseState
) {
    forEachChildAtom(raf, stblPayloadStart, stblPayloadEnd) { atomType, payloadStart, payloadSize ->
        when (atomType) {
            "stts" -> state.timeToSamples += parseSttsEntries(raf, payloadStart, payloadSize)
            "stsc" -> state.sampleToChunk += parseStscEntries(raf, payloadStart, payloadSize)
            "stco" -> state.chunkOffsets += parseStcoOffsets(raf, payloadStart, payloadSize)
            "co64" -> state.chunkOffsets += parseCo64Offsets(raf, payloadStart, payloadSize)
        }
    }
}

private fun parseTkhdTrackId(
    raf: RandomAccessFile,
    payloadStart: Long,
    payloadSize: Long
): Int? {
    if (payloadSize < 24L) return null
    raf.seek(payloadStart)
    val version = raf.readUnsignedByte()
    raf.skipBytes(3) // flags
    return if (version == 1) {
        if (payloadSize < 32L) return null
        raf.skipBytes(8 + 8) // creation + modification
        readUInt32(raf).toInt()
    } else {
        raf.skipBytes(4 + 4) // creation + modification
        readUInt32(raf).toInt()
    }
}

private fun parseTrefChapTrackId(
    raf: RandomAccessFile,
    trefPayloadStart: Long,
    trefPayloadEnd: Long
): Int? {
    var result: Int? = null
    forEachChildAtom(raf, trefPayloadStart, trefPayloadEnd) { atomType, payloadStart, payloadSize ->
        if (result != null || atomType != "chap" || payloadSize < 4L) return@forEachChildAtom
        raf.seek(payloadStart)
        result = readUInt32(raf).toInt()
    }
    return result
}

private fun parseMdhdTimescale(
    raf: RandomAccessFile,
    payloadStart: Long,
    payloadSize: Long
): Long? {
    if (payloadSize < 16L) return null
    raf.seek(payloadStart)
    val version = raf.readUnsignedByte()
    raf.skipBytes(3) // flags
    return if (version == 1) {
        if (payloadSize < 32L) return null
        raf.skipBytes(8 + 8) // creation + modification
        readUInt32(raf)
    } else {
        raf.skipBytes(4 + 4) // creation + modification
        readUInt32(raf)
    }
}

private fun parseSttsEntries(
    raf: RandomAccessFile,
    payloadStart: Long,
    payloadSize: Long
): List<TrackTimeToSample> {
    if (payloadSize < 8L) return emptyList()
    raf.seek(payloadStart)
    val version = raf.readUnsignedByte()
    raf.skipBytes(3) // flags
    if (version != 0) return emptyList()
    val entryCount = readUInt32(raf).toInt()
    val out = mutableListOf<TrackTimeToSample>()
    repeat(entryCount) {
        if (raf.filePointer + 8L > payloadStart + payloadSize) return@repeat
        val count = readUInt32(raf)
        val delta = readUInt32(raf)
        if (count > 0L && delta >= 0L) {
            out += TrackTimeToSample(sampleCount = count, sampleDelta = delta)
        }
    }
    return out
}

private fun parseStscEntries(
    raf: RandomAccessFile,
    payloadStart: Long,
    payloadSize: Long
): List<TrackSampleToChunk> {
    if (payloadSize < 8L) return emptyList()
    raf.seek(payloadStart)
    val version = raf.readUnsignedByte()
    raf.skipBytes(3) // flags
    if (version != 0) return emptyList()
    val entryCount = readUInt32(raf).toInt()
    val out = mutableListOf<TrackSampleToChunk>()
    repeat(entryCount) {
        if (raf.filePointer + 12L > payloadStart + payloadSize) return@repeat
        val firstChunk = readUInt32(raf)
        val samplesPerChunk = readUInt32(raf).toInt()
        readUInt32(raf) // sample description index
        if (firstChunk > 0L && samplesPerChunk > 0) {
            out += TrackSampleToChunk(firstChunk = firstChunk, samplesPerChunk = samplesPerChunk)
        }
    }
    return out
}

private fun parseStcoOffsets(
    raf: RandomAccessFile,
    payloadStart: Long,
    payloadSize: Long
): List<Long> {
    if (payloadSize < 8L) return emptyList()
    raf.seek(payloadStart)
    val version = raf.readUnsignedByte()
    raf.skipBytes(3) // flags
    if (version != 0) return emptyList()
    val entryCount = readUInt32(raf).toInt()
    val out = mutableListOf<Long>()
    repeat(entryCount) {
        if (raf.filePointer + 4L > payloadStart + payloadSize) return@repeat
        out += readUInt32(raf)
    }
    return out
}

private fun parseCo64Offsets(
    raf: RandomAccessFile,
    payloadStart: Long,
    payloadSize: Long
): List<Long> {
    if (payloadSize < 8L) return emptyList()
    raf.seek(payloadStart)
    val version = raf.readUnsignedByte()
    raf.skipBytes(3) // flags
    if (version != 0) return emptyList()
    val entryCount = readUInt32(raf).toInt()
    val out = mutableListOf<Long>()
    repeat(entryCount) {
        if (raf.filePointer + 8L > payloadStart + payloadSize) return@repeat
        out += readUInt64(raf).coerceAtLeast(0L)
    }
    return out
}

private fun samplesPerChunkForChunk(
    entries: List<TrackSampleToChunk>,
    oneBasedChunkIndex: Int
): Int {
    if (entries.isEmpty()) return 1
    entries.forEachIndexed { index, entry ->
        val next = entries.getOrNull(index + 1)
        if (oneBasedChunkIndex.toLong() >= entry.firstChunk) {
            if (next == null || oneBasedChunkIndex.toLong() < next.firstChunk) {
                return entry.samplesPerChunk
            }
        }
    }
    return entries.last().samplesPerChunk.coerceAtLeast(1)
}

private fun readChapChapterName(
    raf: RandomAccessFile,
    chunkOffset: Long,
    fileLength: Long
): String {
    if (chunkOffset < 0L || chunkOffset + 2L > fileLength) return ""
    raf.seek(chunkOffset)
    val b0 = raf.read()
    val b1 = raf.read()
    if (b0 < 0 || b1 < 0) return ""
    val textLength = ((b0 and 0xFF) shl 8) or (b1 and 0xFF)
    if (textLength <= 0 || textLength > MAX_CHAPTER_NAME_BYTES) return ""
    if (chunkOffset + 2L + textLength > fileLength) return ""

    val raw = ByteArray(textLength)
    raf.readFully(raw)
    return decodeChapterSampleTitle(raw)
}

private fun findAtomByType(
    raf: RandomAccessFile,
    start: Long,
    end: Long,
    targetType: String
): Pair<Long, Long>? {
    var result: Pair<Long, Long>? = null
    forEachChildAtom(raf, start, end) { atomType, payloadStart, payloadSize ->
        if (result == null && atomType == targetType) {
            result = payloadStart to payloadSize
        }
    }
    return result
}

private fun forEachChildAtom(
    raf: RandomAccessFile,
    start: Long,
    end: Long,
    block: (type: String, payloadStart: Long, payloadSize: Long) -> Unit
) {
    var pos = start
    while (pos + 8L <= end) {
        raf.seek(pos)
        val size32 = readUInt32(raf)
        val type = readType(raf)

        var atomSize = size32
        var headerSize = 8L
        if (size32 == 1L) {
            atomSize = readUInt64(raf)
            headerSize = 16L
        } else if (size32 == 0L) {
            atomSize = end - pos
        }

        if (atomSize <= 0L || atomSize < headerSize || pos + atomSize > end) {
            break
        }

        val payloadStart = pos + headerSize
        val payloadSize = atomSize - headerSize
        block(type, payloadStart, payloadSize)
        pos += atomSize
    }
}

private fun parseM4bChaptersFromFile(file: File): List<M4bChapter> {
    RandomAccessFile(file, "r").use { raf ->
        val chplAtom = findChplAtom(
            raf = raf,
            start = 0L,
            end = raf.length().coerceAtLeast(0L)
        ) ?: return emptyList()
        val payloadOffset = chplAtom.first
        val payloadSize = chplAtom.second
        if (payloadSize <= 0L || payloadSize > Int.MAX_VALUE.toLong()) return emptyList()
        val bytes = ByteArray(payloadSize.toInt())
        raf.seek(payloadOffset)
        raf.readFully(bytes)
        return parseChplPayload(bytes)
    }
}

private fun findChplAtom(
    raf: RandomAccessFile,
    start: Long,
    end: Long
): Pair<Long, Long>? {
    var pos = start
    while (pos + 8L <= end) {
        raf.seek(pos)
        val size32 = readUInt32(raf)
        val type = readType(raf)

        var atomSize = size32
        var headerSize = 8L
        if (size32 == 1L) {
            atomSize = readUInt64(raf)
            headerSize = 16L
        } else if (size32 == 0L) {
            atomSize = end - pos
        }
        if (atomSize <= 0L || atomSize < headerSize || pos + atomSize > end) {
            break
        }

        if (type == "chpl") {
            return (pos + headerSize) to (atomSize - headerSize)
        }

        if (type in MP4_CONTAINER_ATOMS) {
            val childStart = if (type == "meta") {
                (pos + headerSize + 4L).coerceAtMost(pos + atomSize)
            } else {
                pos + headerSize
            }
            val childEnd = pos + atomSize
            if (childStart < childEnd) {
                val nested = findChplAtom(raf, childStart, childEnd)
                if (nested != null) return nested
            }
        }

        pos += atomSize
    }
    return null
}

private fun parseChplPayload(payload: ByteArray): List<M4bChapter> {
    if (payload.size < 9) return emptyList()

    var index = 0
    index += 4 // FullBox version/flags
    if (index + 4 > payload.size) return emptyList()
    index += 4 // reserved
    if (index >= payload.size) return emptyList()

    val chapterCount = payload[index].toInt() and 0xFF
    index += 1

    val output = mutableListOf<M4bChapter>()
    for (i in 0 until chapterCount) {
        if (index + 8 > payload.size) break
        val startRaw = readUInt64(payload, index)
        index += 8
        if (index >= payload.size) break
        val titleLength = payload[index].toInt() and 0xFF
        index += 1
        if (index + titleLength > payload.size) break
        val rawTitle = String(payload, index, titleLength, StandardCharsets.UTF_8).trim()
        index += titleLength

        val startMs = (startRaw / 10_000L).coerceAtLeast(0L)
        val title = if (rawTitle.isBlank()) "Chapter ${i + 1}" else rawTitle
        output += M4bChapter(startMs = startMs, title = title)
    }

    return output
        .distinctBy { it.startMs }
        .sortedBy { it.startMs }
}

private fun readType(raf: RandomAccessFile): String {
    val bytes = ByteArray(4)
    raf.readFully(bytes)
    return String(bytes, StandardCharsets.US_ASCII)
}

private fun readUInt32(raf: RandomAccessFile): Long {
    val bytes = ByteArray(4)
    raf.readFully(bytes)
    return ((bytes[0].toLong() and 0xFFL) shl 24) or
        ((bytes[1].toLong() and 0xFFL) shl 16) or
        ((bytes[2].toLong() and 0xFFL) shl 8) or
        (bytes[3].toLong() and 0xFFL)
}

private fun readUInt64(raf: RandomAccessFile): Long {
    val bytes = ByteArray(8)
    raf.readFully(bytes)
    return readUInt64(bytes, 0)
}

private fun readUInt64(bytes: ByteArray, offset: Int): Long {
    var value = 0L
    for (i in 0 until 8) {
        value = (value shl 8) or (bytes[offset + i].toLong() and 0xFFL)
    }
    return value
}

