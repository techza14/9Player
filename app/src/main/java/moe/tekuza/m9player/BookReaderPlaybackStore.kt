package moe.tekuza.m9player

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

private const val PLAYBACK_DB_NAME = "reader_playback.db"
private const val PLAYBACK_DB_VERSION = 1
private const val PLAYBACK_TABLE = "reader_progress"
private const val COL_BOOK_KEY = "book_key"
private const val COL_POSITION_MS = "position_ms"
private const val COL_DURATION_MS = "duration_ms"
private const val COL_UPDATED_AT = "updated_at"

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

internal fun loadBookReaderPlaybackSnapshotOrNull(context: Context, bookKey: String): BookReaderPlaybackSnapshot? {
    if (bookKey.isBlank()) {
        return null
    }
    val snapshot = readerPlaybackDb(context).readSnapshot(bookKey) ?: return null
    if (snapshot.durationMs <= 0L) return null
    return snapshot
}

internal fun saveBookReaderPlaybackPosition(
    context: Context,
    bookKey: String,
    positionMs: Long,
    durationMs: Long = 0L,
    allowZeroPositionWrite: Boolean = false
) {
    if (bookKey.isBlank()) return
    val safePosition = positionMs.coerceAtLeast(0L)
    val safeDuration = durationMs.coerceAtLeast(0L)
    if (safeDuration <= 0L) return
    val existing = loadBookReaderPlaybackSnapshotOrNull(context, bookKey)
    if (!allowZeroPositionWrite && safePosition <= 0L && existing != null && existing.positionMs > 0L) {
        return
    }
    readerPlaybackDb(context).saveSnapshot(
        bookKey = bookKey,
        positionMs = safePosition,
        durationMs = safeDuration
    )
}

private fun readerPlaybackDb(context: Context): ReaderPlaybackDbHelper {
    return ReaderPlaybackDbHelper.get(context.applicationContext)
}

private class ReaderPlaybackDbHelper private constructor(context: Context) :
    SQLiteOpenHelper(context, PLAYBACK_DB_NAME, null, PLAYBACK_DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $PLAYBACK_TABLE (
                $COL_BOOK_KEY TEXT PRIMARY KEY,
                $COL_POSITION_MS INTEGER NOT NULL DEFAULT 0,
                $COL_DURATION_MS INTEGER NOT NULL DEFAULT 0,
                $COL_UPDATED_AT INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun readSnapshot(bookKey: String): BookReaderPlaybackSnapshot? {
        readableDatabase.query(
            PLAYBACK_TABLE,
            arrayOf(COL_POSITION_MS, COL_DURATION_MS),
            "$COL_BOOK_KEY = ?",
            arrayOf(bookKey),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return BookReaderPlaybackSnapshot(
                positionMs = cursor.getLong(0).coerceAtLeast(0L),
                durationMs = cursor.getLong(1).coerceAtLeast(0L)
            )
        }
    }

    fun saveSnapshot(bookKey: String, positionMs: Long, durationMs: Long) {
        val values = ContentValues().apply {
            put(COL_BOOK_KEY, bookKey)
            put(COL_POSITION_MS, positionMs)
            put(COL_DURATION_MS, durationMs)
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            PLAYBACK_TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    companion object {
        @Volatile
        private var instance: ReaderPlaybackDbHelper? = null

        fun get(context: Context): ReaderPlaybackDbHelper {
            return instance ?: synchronized(this) {
                instance ?: ReaderPlaybackDbHelper(context).also { instance = it }
            }
        }
    }
}
