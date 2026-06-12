package moe.tekuza.m9player

import android.content.Context
import org.json.JSONObject

private const val EBOOK_READING_PROGRESS_PREFS = "ebook_reading_progress"
private const val KEY_EBOOK_READING_PROGRESS_JSON = "ebook_reading_progress_json"

internal fun saveEbookReadingProgressPercent(
    context: Context,
    bookUri: String?,
    progressPercent: Int
) {
    val key = bookUri?.trim()?.takeIf { it.isNotBlank() } ?: return
    val prefs = context.getSharedPreferences(EBOOK_READING_PROGRESS_PREFS, Context.MODE_PRIVATE)
    val root = runCatching {
        JSONObject(prefs.getString(KEY_EBOOK_READING_PROGRESS_JSON, null).orEmpty())
    }.getOrElse { JSONObject() }
    root.put(
        key,
        JSONObject().apply {
            put("progressPercent", progressPercent.coerceIn(0, 100))
            put("updatedAt", System.currentTimeMillis())
        }
    )
    prefs.edit().putString(KEY_EBOOK_READING_PROGRESS_JSON, root.toString()).apply()
}

internal fun loadEbookReadingProgressSnapshot(
    context: Context,
    bookUri: String?
): BookReaderPlaybackSnapshot? {
    val key = bookUri?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val raw = context
        .getSharedPreferences(EBOOK_READING_PROGRESS_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_EBOOK_READING_PROGRESS_JSON, null)
        ?: return null
    val item = runCatching { JSONObject(raw).optJSONObject(key) }.getOrNull() ?: return null
    val percent = item.optInt("progressPercent", 0).coerceIn(0, 100)
    return BookReaderPlaybackSnapshot(
        positionMs = percent.toLong(),
        durationMs = 100L,
        updatedAtMs = item.optLong("updatedAt", 0L).coerceAtLeast(0L)
    )
}
