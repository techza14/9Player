package com.tekuza.p9player

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

internal data class M4bChapter(
    val startMs: Long,
    val title: String
)

private val MP4_CONTAINER_ATOMS = setOf(
    "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "meta", "ilst", "moof", "traf", "mvex"
)

internal fun loadM4bChapters(
    contentResolver: ContentResolver,
    audioUri: Uri?
): List<M4bChapter> {
    val uri = audioUri ?: return emptyList()
    val scheme = uri.scheme?.lowercase().orEmpty()

    return when (scheme) {
        "file", "" -> {
            val path = uri.path ?: return emptyList()
            if (!path.endsWith(".m4b", ignoreCase = true)) return emptyList()
            val file = File(path)
            if (!file.exists() || !file.isFile) return emptyList()
            runCatching { parseM4bChaptersFromFile(file) }.getOrDefault(emptyList())
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
                runCatching { parseM4bChaptersFromFile(tempFile) }.getOrDefault(emptyList())
            } finally {
                runCatching { tempFile.delete() }
            }
        }

        else -> emptyList()
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

