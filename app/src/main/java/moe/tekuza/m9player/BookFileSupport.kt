package moe.tekuza.m9player

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val READER_PLAYBACK_MIGRATION_LOG_TAG = "ReaderPlaybackMigration"
private const val READER_PLAYBACK_DIAGNOSTIC_LOG_TAG = "ReaderPlaybackDiagnostic"

internal fun keepReadPermission(context: Context, uri: Uri) {
    val resolver = context.contentResolver
    val readWriteFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    try {
        resolver.takePersistableUriPermission(uri, readWriteFlags)
        return
    } catch (_: SecurityException) {
        // Fallback to read-only permission.
    }
    try {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: SecurityException) {
        // Some providers do not support persistable permission.
    }
}
internal fun queryDisplayName(contentResolver: ContentResolver, uri: Uri): String {
    val fallback = uri.lastPathSegment ?: "Unknown"
    return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else fallback
        } else {
            fallback
        }
    } ?: fallback
}

internal fun queryTreeDisplayName(
    context: Context,
    contentResolver: ContentResolver,
    treeUri: Uri
): String {
    val fromDocument = runCatching {
        DocumentFile.fromTreeUri(context, treeUri)?.name?.trim()
    }.getOrNull().orEmpty()
    if (fromDocument.isNotBlank()) return fromDocument
    return queryDisplayName(contentResolver, treeUri)
}

internal fun buildBookTitle(audioName: String, srtName: String?): String {
    val audioBase = audioName.substringBeforeLast('.').trim().ifBlank { audioName.trim() }
    if (audioBase.isNotBlank()) return audioBase
    val srtBase = srtName?.let { name ->
        name.substringBeforeLast('.').trim().ifBlank { name.trim() }
    }.orEmpty()
    if (srtBase.isNotBlank()) return srtBase
    return "Untitled Book"
}

internal fun buildEbookTitle(ebookName: String?): String {
    val ebookBase = ebookName
        ?.substringBeforeLast('.')
        ?.trim()
        ?.ifBlank { ebookName.trim() }
        .orEmpty()
    return ebookBase.ifBlank { "Untitled Book" }
}

internal fun buildReaderBookPlaybackKey(book: ReaderBook): String {
    return buildReaderAudioPlaybackKey(
        title = book.title,
        audioUri = book.audioUri,
        srtUri = book.srtUri
    )
}

internal data class ReaderBookPlaybackSnapshotCandidate(
    val source: String,
    val snapshot: BookReaderPlaybackSnapshot
)

internal fun buildReaderAudioPlaybackKey(
    title: String,
    audioUri: Uri?,
    srtUri: Uri?
): String {
    val stableSource = audioUri?.toString().orEmpty().ifBlank {
        "title=$title|srt=${srtUri?.toString().orEmpty()}"
    }
    val stableName = audioUri?.toString()?.takeIf { it.isNotBlank() }
        ?: title.ifBlank { "book" }
    return buildDictionaryCacheKey(stableSource, stableName)
}

internal fun buildLegacyReaderAudioPlaybackKey(
    title: String,
    audioUri: Uri?,
    srtUri: Uri?
): String {
    val stableSource = audioUri?.toString().orEmpty().ifBlank {
        "title=$title|srt=${srtUri?.toString().orEmpty()}"
    }
    return buildDictionaryCacheKey(stableSource, title.ifBlank { "book" })
}

internal fun saveReaderAudioPlaybackProgress(
    context: Context,
    title: String,
    audioUri: Uri?,
    srtUri: Uri?,
    positionMs: Long,
    durationMs: Long,
    allowZeroPositionWrite: Boolean = false
) {
    saveBookReaderPlaybackPosition(
        context = context,
        bookKey = buildReaderAudioPlaybackKey(
            title = title,
            audioUri = audioUri,
            srtUri = srtUri
        ),
        positionMs = positionMs,
        durationMs = durationMs,
        allowZeroPositionWrite = allowZeroPositionWrite
    )
}

internal suspend fun loadReaderBookPlaybackSnapshotsForBooks(
    context: Context,
    books: List<ReaderBook>
): Map<String, BookReaderPlaybackSnapshot> {
    return withContext(Dispatchers.IO) {
        books.mapNotNull { book ->
            val stored = if (book.audioUri != null) {
                migrateBestReaderBookPlaybackSnapshotIfNeeded(
                    context = context,
                    book = book,
                    reason = "bookshelf"
                )?.snapshot
            } else {
                book.ebookUri?.let { loadEbookReadingProgressSnapshot(context, it.toString()) }
            }
            stored?.let { book.id to it }
        }
            .toMap()
    }
}

internal fun migrateBestReaderBookPlaybackSnapshotIfNeeded(
    context: Context,
    book: ReaderBook,
    reason: String
): ReaderBookPlaybackSnapshotCandidate? {
    if (book.audioUri == null) return null
    val candidates = loadReaderBookPlaybackSnapshotCandidates(context, book)
    val best = selectBestReaderBookPlaybackSnapshotCandidate(candidates)
    logReaderPlaybackCandidateSelection(reason, book, candidates, best)
    best ?: return null
    if (best.source == "shared") return best
    val sharedKey = buildReaderBookPlaybackKey(book)
    saveBookReaderPlaybackPosition(
        context = context,
        bookKey = sharedKey,
        positionMs = best.snapshot.positionMs,
        durationMs = best.snapshot.durationMs
    )
    Log.d(
        READER_PLAYBACK_MIGRATION_LOG_TAG,
        "migrated reason=$reason source=${best.source} title=${book.title.take(48)} " +
            "positionMs=${best.snapshot.positionMs} durationMs=${best.snapshot.durationMs} " +
            "updatedAt=${best.snapshot.updatedAtMs}"
    )
    return best
}

internal fun loadBestReaderBookPlaybackSnapshotCandidate(
    context: Context,
    book: ReaderBook
): ReaderBookPlaybackSnapshotCandidate? {
    return selectBestReaderBookPlaybackSnapshotCandidate(
        loadReaderBookPlaybackSnapshotCandidates(context, book)
    )
}

internal fun selectBestReaderBookPlaybackSnapshotCandidate(
    candidates: List<ReaderBookPlaybackSnapshotCandidate>
): ReaderBookPlaybackSnapshotCandidate? {
    return candidates.maxWithOrNull(
        compareBy<ReaderBookPlaybackSnapshotCandidate> { it.snapshot.updatedAtMs }
            .thenBy { if (it.source == "shared") 1 else 0 }
    )
}

internal fun hasSuspiciousZeroSharedPlaybackCandidate(
    candidates: List<ReaderBookPlaybackSnapshotCandidate>,
    best: ReaderBookPlaybackSnapshotCandidate?
): Boolean {
    return best?.source == "shared" &&
        best.snapshot.positionMs <= 0L &&
        candidates.any { it.source != "shared" && it.snapshot.positionMs > 0L }
}

internal fun formatReaderBookPlaybackCandidates(
    candidates: List<ReaderBookPlaybackSnapshotCandidate>
): String {
    return candidates.joinToString(separator = ",") { candidate ->
        "${candidate.source}:${candidate.snapshot.positionMs}/${candidate.snapshot.durationMs}@${candidate.snapshot.updatedAtMs}"
    }.ifBlank { "none" }
}

private fun logReaderPlaybackCandidateSelection(
    reason: String,
    book: ReaderBook,
    candidates: List<ReaderBookPlaybackSnapshotCandidate>,
    best: ReaderBookPlaybackSnapshotCandidate?
) {
    val suspiciousZeroShared = hasSuspiciousZeroSharedPlaybackCandidate(candidates, best)
    if (candidates.size <= 1 && !suspiciousZeroShared) return
    Log.d(
        READER_PLAYBACK_DIAGNOSTIC_LOG_TAG,
        "select reason=$reason title=${book.title.take(48)} " +
            "best=${best?.source ?: "none"}:${best?.snapshot?.positionMs ?: 0L}/" +
            "${best?.snapshot?.durationMs ?: 0L}@${best?.snapshot?.updatedAtMs ?: 0L} " +
            "suspiciousZeroShared=$suspiciousZeroShared " +
            "candidates=${formatReaderBookPlaybackCandidates(candidates)}"
    )
}

internal fun loadReaderBookPlaybackSnapshotCandidates(
    context: Context,
    book: ReaderBook
): List<ReaderBookPlaybackSnapshotCandidate> {
    val addedKeys = mutableSetOf<String>()
    return buildList {
        fun addCandidate(source: String, key: String) {
            if (!addedKeys.add(key)) return
            loadBookReaderPlaybackSnapshotOrNull(context, key)?.let { snapshot ->
                add(ReaderBookPlaybackSnapshotCandidate(source, snapshot))
            }
        }
        addCandidate("shared", buildReaderBookPlaybackKey(book))
        addCandidate(
            "sharedLegacy",
            buildLegacyReaderAudioPlaybackKey(
                title = book.title,
                audioUri = book.audioUri,
                srtUri = book.srtUri
            )
        )
        book.ebookName?.trim()?.takeIf { it.isNotBlank() }?.let { ebookTitle ->
            addCandidate(
                "sharedLegacyEbookName",
                buildLegacyReaderAudioPlaybackKey(
                    title = ebookTitle,
                    audioUri = book.audioUri,
                    srtUri = book.srtUri
                )
            )
        }
        book.ebookUri?.toString()?.takeIf { it.isNotBlank() }?.let { ebookUri ->
            addCandidate(
                "legado",
                buildLegadoReaderPlaybackKey(
                    title = book.title,
                    audioUri = book.audioUri,
                    srtUri = book.srtUri,
                    bookUri = ebookUri
                )
            )
        }
    }
}
