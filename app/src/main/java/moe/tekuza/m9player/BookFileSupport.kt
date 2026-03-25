package moe.tekuza.m9player

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val BOOK_DELETE_PREFS = "book_delete_prefs"
private const val KEY_SKIP_DELETE_CONFIRM = "skip_delete_confirm"

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

internal fun buildReaderBookPlaybackKey(book: ReaderBook): String {
    val raw = "title=${book.title}|audio=${book.audioUri}|srt=${book.srtUri?.toString().orEmpty()}"
    return buildDictionaryCacheKey(raw, book.title.ifBlank { "book" })
}

internal suspend fun loadReaderBookPlaybackSnapshotsForBooks(
    context: Context,
    books: List<ReaderBook>
): Map<String, BookReaderPlaybackSnapshot> {
    return withContext(Dispatchers.IO) {
        books.associate { book ->
            val playbackKey = buildReaderBookPlaybackKey(book)
            val stored = loadBookReaderPlaybackSnapshot(context, playbackKey)
            book.id to stored
        }
    }
}

internal fun loadSkipDeleteBookConfirm(context: Context): Boolean {
    return context
        .getSharedPreferences(BOOK_DELETE_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_SKIP_DELETE_CONFIRM, false)
}

internal fun saveSkipDeleteBookConfirm(context: Context, skip: Boolean) {
    context
        .getSharedPreferences(BOOK_DELETE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_SKIP_DELETE_CONFIRM, skip)
        .apply()
}
