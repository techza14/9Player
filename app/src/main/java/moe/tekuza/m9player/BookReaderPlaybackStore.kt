package moe.tekuza.m9player

import android.content.Context

private const val BOOK_READER_PLAYBACK_PREFS = "book_reader_playback_prefs"
private const val BOOK_READER_PLAYBACK_POSITION_KEY_PREFIX = "position_"
private const val BOOK_READER_PLAYBACK_DURATION_KEY_PREFIX = "duration_"

internal data class BookReaderPlaybackSnapshot(
    val positionMs: Long,
    val durationMs: Long
) {
    val progressPercent: Int
        get() {
            if (durationMs <= 0L) return 0
            return ((positionMs.coerceIn(0L, durationMs) * 100L) / durationMs)
                .toInt()
                .coerceIn(0, 100)
        }
}

internal fun loadBookReaderPlaybackSnapshot(context: Context, bookKey: String): BookReaderPlaybackSnapshot {
    if (bookKey.isBlank()) {
        return BookReaderPlaybackSnapshot(positionMs = 0L, durationMs = 0L)
    }
    val prefs = context.getSharedPreferences(BOOK_READER_PLAYBACK_PREFS, Context.MODE_PRIVATE)
    val positionKey = buildPlaybackPreferenceKey(BOOK_READER_PLAYBACK_POSITION_KEY_PREFIX, bookKey)
    val durationKey = buildPlaybackPreferenceKey(BOOK_READER_PLAYBACK_DURATION_KEY_PREFIX, bookKey)
    return BookReaderPlaybackSnapshot(
        positionMs = prefs.getLong(positionKey, 0L).coerceAtLeast(0L),
        durationMs = prefs.getLong(durationKey, 0L).coerceAtLeast(0L)
    )
}

internal fun hasBookReaderPlaybackSnapshot(context: Context, bookKey: String): Boolean {
    if (bookKey.isBlank()) return false
    val prefs = context.getSharedPreferences(BOOK_READER_PLAYBACK_PREFS, Context.MODE_PRIVATE)
    val positionKey = buildPlaybackPreferenceKey(BOOK_READER_PLAYBACK_POSITION_KEY_PREFIX, bookKey)
    val durationKey = buildPlaybackPreferenceKey(BOOK_READER_PLAYBACK_DURATION_KEY_PREFIX, bookKey)
    return prefs.contains(positionKey) || prefs.contains(durationKey)
}

internal fun loadBookReaderPlaybackPosition(context: Context, bookKey: String): Long {
    return loadBookReaderPlaybackSnapshot(context, bookKey).positionMs
}

internal fun saveBookReaderPlaybackPosition(
    context: Context,
    bookKey: String,
    positionMs: Long,
    durationMs: Long = 0L
) {
    if (bookKey.isBlank()) return
    val prefs = context.getSharedPreferences(BOOK_READER_PLAYBACK_PREFS, Context.MODE_PRIVATE)
    val positionKey = buildPlaybackPreferenceKey(BOOK_READER_PLAYBACK_POSITION_KEY_PREFIX, bookKey)
    val durationKey = buildPlaybackPreferenceKey(BOOK_READER_PLAYBACK_DURATION_KEY_PREFIX, bookKey)
    prefs.edit()
        .putLong(positionKey, positionMs.coerceAtLeast(0L))
        .putLong(durationKey, durationMs.coerceAtLeast(0L))
        .apply()
}

private fun buildPlaybackPreferenceKey(prefix: String, bookKey: String): String {
    return prefix + buildDictionaryCacheKey(bookKey, bookKey)
}

