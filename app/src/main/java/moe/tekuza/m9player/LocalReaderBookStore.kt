package moe.tekuza.m9player

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.util.Locale

internal data class LocalReaderBook(
    val title: String,
    val uri: Uri,
    val format: String,
    val importedAtMs: Long
)

private const val LOCAL_READER_BOOK_PREFS = "local_reader_books"
private const val LOCAL_READER_LAST_BOOK_KEY = "last_book_json"

internal fun loadLastLocalReaderBook(context: Context): LocalReaderBook? {
    val raw = context
        .getSharedPreferences(LOCAL_READER_BOOK_PREFS, Context.MODE_PRIVATE)
        .getString(LOCAL_READER_LAST_BOOK_KEY, null)
        ?: return null
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val title = json.optString("title").trim()
    val uriText = json.optString("uri").trim()
    val format = json.optString("format").trim()
    if (title.isBlank() || uriText.isBlank() || format.isBlank()) return null
    return LocalReaderBook(
        title = title,
        uri = Uri.parse(uriText),
        format = format,
        importedAtMs = json.optLong("importedAtMs", 0L)
    )
}

internal fun inferLocalReaderBookFormat(displayName: String, mimeType: String?): String? {
    val lowerName = displayName.lowercase(Locale.US)
    val lowerMime = mimeType.orEmpty().lowercase(Locale.US)
    return when {
        lowerName.endsWith(".epub") || lowerMime == "application/epub+zip" -> "EPUB"
        lowerName.endsWith(".txt") || lowerMime.startsWith("text/") -> "TXT"
        else -> null
    }
}

internal fun localReaderBookTitleFromDisplayName(displayName: String): String {
    return displayName
        .substringBeforeLast('.')
        .trim()
        .ifBlank { displayName.trim() }
        .ifBlank { "Untitled Book" }
}
