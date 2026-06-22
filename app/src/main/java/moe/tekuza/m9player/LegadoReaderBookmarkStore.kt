package moe.tekuza.m9player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class ReaderBookmark(
    val chapterIndex: Int,
    val chapterPosition: Int,
    val chapterTitle: String,
    val excerpt: String,
    val note: String = "",
    val createdAtMs: Long
) {
    val preview: String get() = excerpt
}

private const val READER_BOOKMARK_PREFS = "legado_reader_bookmarks"

internal fun loadReaderBookmarks(context: Context, bookKey: String): List<ReaderBookmark> {
    val raw = context
        .getSharedPreferences(READER_BOOKMARK_PREFS, Context.MODE_PRIVATE)
        .getString(bookKey, null)
        ?: return emptyList()
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    val items = ArrayList<ReaderBookmark>(array.length())
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        val excerpt = item.optString("excerpt").ifBlank { item.optString("preview") }
        items += ReaderBookmark(
            chapterIndex = item.optInt("chapterIndex", 0),
            chapterPosition = item.optInt("chapterPosition", 0),
            chapterTitle = item.optString("chapterTitle"),
            excerpt = excerpt,
            note = item.optString("note"),
            createdAtMs = item.optLong("createdAtMs", 0L)
        )
    }
    return items.sortedBy { it.chapterIndex * 1_000_000L + it.chapterPosition }
}

internal fun saveReaderBookmarks(context: Context, bookKey: String, bookmarks: List<ReaderBookmark>) {
    val array = JSONArray()
    bookmarks.forEach { bookmark ->
        array.put(JSONObject().apply {
            put("chapterIndex", bookmark.chapterIndex)
            put("chapterPosition", bookmark.chapterPosition)
            put("chapterTitle", bookmark.chapterTitle)
            put("excerpt", bookmark.excerpt)
            put("preview", bookmark.excerpt)
            put("note", bookmark.note)
            put("createdAtMs", bookmark.createdAtMs)
        })
    }
    context
        .getSharedPreferences(READER_BOOKMARK_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(bookKey, array.toString())
        .apply()
}
