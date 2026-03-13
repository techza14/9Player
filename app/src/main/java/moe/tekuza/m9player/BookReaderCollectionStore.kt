package moe.tekuza.m9player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class BookReaderCollectedCue(
    val id: String,
    val bookTitle: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val savedAtMs: Long,
    val chapterIndex: Int? = null,
    val chapterTitle: String? = null,
    val chapterStartMs: Long? = null,
    val chapterStartOffsetMs: Long? = null,
    val chapterEndOffsetMs: Long? = null
)

private const val BOOK_READER_COLLECTION_PREFS = "book_reader_collections"
private const val BOOK_READER_COLLECTION_KEY = "items_json"

internal fun loadBookReaderCollectedCues(context: Context): List<BookReaderCollectedCue> {
    val prefs = context.getSharedPreferences(BOOK_READER_COLLECTION_PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString(BOOK_READER_COLLECTION_KEY, null) ?: return emptyList()
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
    val array = root.optJSONArray("items") ?: JSONArray()

    val output = mutableListOf<BookReaderCollectedCue>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val id = item.optString("id").trim()
        val bookTitle = item.optString("bookTitle").trim()
        val text = item.optString("text").trim()
        if (id.isBlank() || text.isBlank()) continue

        output += BookReaderCollectedCue(
            id = id,
            bookTitle = bookTitle,
            text = text,
            startMs = item.optLong("startMs", 0L),
            endMs = item.optLong("endMs", 0L),
            savedAtMs = item.optLong("savedAtMs", 0L),
            chapterIndex = item.optInt("chapterIndex", -1).takeIf { it >= 0 },
            chapterTitle = item.optString("chapterTitle").trim().ifBlank { null },
            chapterStartMs = item.optLong("chapterStartMs", -1L).takeIf { it >= 0L },
            chapterStartOffsetMs = item.optLong("chapterStartOffsetMs", -1L).takeIf { it >= 0L },
            chapterEndOffsetMs = item.optLong("chapterEndOffsetMs", -1L).takeIf { it >= 0L }
        )
    }
    return output.sortedByDescending { it.savedAtMs }
}

internal fun appendBookReaderCollectedCue(context: Context, cue: BookReaderCollectedCue): Boolean {
    val existing = loadBookReaderCollectedCues(context).toMutableList()
    val duplicated = existing.any {
        it.bookTitle == cue.bookTitle &&
            it.startMs == cue.startMs &&
            it.endMs == cue.endMs &&
            it.text == cue.text
    }
    if (duplicated) return false

    existing += cue
    saveBookReaderCollectedCues(context, existing.sortedByDescending { it.savedAtMs }.take(1200))
    return true
}

internal fun removeBookReaderCollectedCue(context: Context, id: String) {
    if (id.isBlank()) return
    val remaining = loadBookReaderCollectedCues(context).filterNot { it.id == id }
    saveBookReaderCollectedCues(context, remaining)
}

internal fun clearBookReaderCollectedCues(context: Context) {
    saveBookReaderCollectedCues(context, emptyList())
}

private fun saveBookReaderCollectedCues(context: Context, cues: List<BookReaderCollectedCue>) {
    val root = JSONObject().apply {
        put(
            "items",
            JSONArray().apply {
                cues.forEach { cue ->
                    put(JSONObject().apply {
                        put("id", cue.id)
                        put("bookTitle", cue.bookTitle)
                        put("text", cue.text)
                        put("startMs", cue.startMs)
                        put("endMs", cue.endMs)
                        put("savedAtMs", cue.savedAtMs)
                        put("chapterIndex", cue.chapterIndex ?: -1)
                        put("chapterTitle", cue.chapterTitle.orEmpty())
                        put("chapterStartMs", cue.chapterStartMs ?: -1L)
                        put("chapterStartOffsetMs", cue.chapterStartOffsetMs ?: -1L)
                        put("chapterEndOffsetMs", cue.chapterEndOffsetMs ?: -1L)
                    })
                }
            }
        )
    }

    context.getSharedPreferences(BOOK_READER_COLLECTION_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(BOOK_READER_COLLECTION_KEY, root.toString())
        .apply()
}

