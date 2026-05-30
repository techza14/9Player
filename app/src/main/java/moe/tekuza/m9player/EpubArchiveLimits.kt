package moe.tekuza.m9player

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

internal const val EPUB_ARCHIVE_MAX_ENTRIES = 4_000
internal const val EPUB_ARCHIVE_MAX_ENTRY_BYTES = 32L * 1024L * 1024L
internal const val EPUB_ARCHIVE_MAX_TOTAL_BYTES = 256L * 1024L * 1024L
internal const val EPUB_ARCHIVE_MAX_PATH_CHARS = 512
internal const val EPUB_READER_MEMORY_MAX_ENTRIES = 2_000
internal const val EPUB_READER_MEMORY_MAX_ENTRY_BYTES = 16L * 1024L * 1024L
internal const val EPUB_READER_MEMORY_MAX_TOTAL_BYTES = 96L * 1024L * 1024L

internal fun normalizeSafeEpubArchivePath(raw: String): String? {
    val normalized = raw.trim().replace('\\', '/')
    if (
        normalized.isBlank() ||
        normalized.length > EPUB_ARCHIVE_MAX_PATH_CHARS ||
        normalized.startsWith("/") ||
        normalized.matches(Regex("""^[A-Za-z]:.*"""))
    ) {
        return null
    }
    val parts = normalized.split('/')
    if (parts.any { it == ".." }) return null
    return parts
        .filter { part -> part.isNotBlank() && part != "." }
        .joinToString("/")
        .takeIf { it.isNotBlank() && it.length <= EPUB_ARCHIVE_MAX_PATH_CHARS }
}

internal fun requireEpubEntryBudget(entryCount: Int) {
    require(entryCount <= EPUB_ARCHIVE_MAX_ENTRIES) {
        "EPUB has too many entries: $entryCount"
    }
}

internal fun requireEpubReaderMemoryEntryBudget(entryCount: Int) {
    require(entryCount <= EPUB_READER_MEMORY_MAX_ENTRIES) {
        "EPUB reader fallback has too many entries: $entryCount"
    }
}

internal fun requireKnownEpubEntrySize(
    size: Long,
    maxEntryBytes: Long = EPUB_ARCHIVE_MAX_ENTRY_BYTES
) {
    if (size >= 0L) {
        require(size <= maxEntryBytes) {
            "EPUB entry too large: $size bytes"
        }
    }
}

internal fun InputStream.readBytesLimited(
    maxEntryBytes: Long = EPUB_ARCHIVE_MAX_ENTRY_BYTES,
    remainingTotalBytes: Long = EPUB_ARCHIVE_MAX_TOTAL_BYTES,
): ByteArray {
    val out = ByteArrayOutputStream()
    copyToLimited(out, maxEntryBytes, remainingTotalBytes)
    return out.toByteArray()
}

internal fun InputStream.copyToLimited(
    output: OutputStream,
    maxEntryBytes: Long = EPUB_ARCHIVE_MAX_ENTRY_BYTES,
    remainingTotalBytes: Long = EPUB_ARCHIVE_MAX_TOTAL_BYTES,
): Long {
    require(remainingTotalBytes >= 0L) { "EPUB total size limit exceeded" }
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var written = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        written += count.toLong()
        require(written <= maxEntryBytes) { "EPUB entry too large: $written bytes" }
        require(written <= remainingTotalBytes) { "EPUB total size limit exceeded" }
        output.write(buffer, 0, count)
    }
    return written
}
