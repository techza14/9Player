package moe.tekuza.m9player

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

private const val EPUB_READER_CACHE_DIR = "reader_epub_cache"
private const val EPUB_READER_INDEX_FILE = "reader_index.json"

internal fun ensureEpubReaderCache(
    context: Context,
    uri: Uri,
    displayName: String
): File {
    val cacheKey = buildDictionaryCacheKey(uri.toString(), displayName)
    val root = File(context.filesDir, "$EPUB_READER_CACHE_DIR/$cacheKey").canonicalFile
    val sourceStamp = buildReaderSourceStamp(context, uri)
    val indexFile = root.resolve(EPUB_READER_INDEX_FILE)
    val cachedStamp = runCatching {
        JSONObject(indexFile.readText()).optString("sourceStamp")
    }.getOrNull()
    if (root.isDirectory && indexFile.isFile && cachedStamp == sourceStamp) {
        return root
    }

    root.deleteRecursively()
    root.mkdirs()
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entryCount = 0
                var totalBytes = 0L
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount += 1
                    requireEpubEntryBudget(entryCount)
                    requireKnownEpubEntrySize(entry.size)
                    val normalized = normalizeSafeEpubArchivePath(entry.name)
                    if (normalized.isNullOrBlank()) {
                        zip.closeEntry()
                        continue
                    }
                    val output = root.resolve(normalized).canonicalFile
                    require(output.path == root.path || output.path.startsWith(root.path + File.separator)) {
                        "Unsafe EPUB entry: ${entry.name}"
                    }
                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else {
                        output.parentFile?.mkdirs()
                        output.outputStream().use {
                            totalBytes += zip.copyToLimited(
                                output = it,
                                remainingTotalBytes = EPUB_ARCHIVE_MAX_TOTAL_BYTES - totalBytes
                            )
                        }
                    }
                    zip.closeEntry()
                }
            }
        } ?: error("Unable to open EPUB stream: $uri")
        indexFile.writeText(
            JSONObject()
                .put("sourceUri", uri.toString())
                .put("sourceStamp", sourceStamp)
                .put("displayName", displayName)
                .put("createdAtMs", System.currentTimeMillis())
                .toString()
        )
    }.onFailure { error ->
        root.deleteRecursively()
        throw error
    }
    return root
}
