package moe.tekuza.m9player

import android.content.Context
import android.net.Uri
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val STATISTICS_PREFS = "reader_statistics"
private const val KEY_ENABLED = "enabled"
private const val KEY_TOTAL_LISTENED_MS = "total_listened_ms"
private const val KEY_TOTAL_LOOKUP_COUNT = "total_lookup_count"
private const val KEY_COMPLETED_PREFIX = "completed_"
private const val KEY_LISTENED_PREFIX = "listened_"
private const val KEY_LOOKUP_PREFIX = "lookup_"
private const val KEY_DAY_LISTENED_PREFIX = "day_listened_"
private const val KEY_DAY_LOOKUP_PREFIX = "day_lookup_"
private const val KEY_WEEK_LISTENED_PREFIX = "week_listened_"
private const val KEY_WEEK_LOOKUP_PREFIX = "week_lookup_"
private val StatisticsDayFormat = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US)
private val StatisticsWeekFormat = DateTimeFormatter.ofPattern("YYYYww", Locale.US)

internal data class StatisticsSummarySnapshot(
    val todayListeningMs: Long,
    val weekListeningMs: Long,
    val totalListeningMs: Long,
    val completedBookCount: Int,
    val lookupCount: Long,
    val listeningSpeedCharsPerHour: Long
)

internal data class BookStatisticsSnapshot(
    val id: String,
    val title: String,
    val bookKey: String,
    val progress: Float,
    val listenedMs: Long,
    val durationMs: Long,
    val totalChars: Int,
    val lookupCount: Long,
    val completed: Boolean
)

internal data class StatisticsReport(
    val enabled: Boolean,
    val summary: StatisticsSummarySnapshot,
    val currentBook: BookStatisticsSnapshot?,
    val books: List<BookStatisticsSnapshot>
)

internal fun isStatisticsEnabled(context: Context): Boolean {
    return statisticsPrefs(context).getBoolean(KEY_ENABLED, false)
}

internal fun saveStatisticsEnabled(context: Context, enabled: Boolean) {
    statisticsPrefs(context)
        .edit()
        .putBoolean(KEY_ENABLED, enabled)
        .apply()
}

internal fun recordStatisticsListening(context: Context, bookKey: String, elapsedMs: Long) {
    if (!isStatisticsEnabled(context)) return
    if (bookKey.isBlank() || elapsedMs <= 0L) return
    val prefs = statisticsPrefs(context)
    val safeElapsed = elapsedMs.coerceAtMost(30_000L)
    prefs.edit()
        .putLong(KEY_TOTAL_LISTENED_MS, prefs.getLong(KEY_TOTAL_LISTENED_MS, 0L) + safeElapsed)
        .putLong(bookListenedKey(bookKey), prefs.getLong(bookListenedKey(bookKey), 0L) + safeElapsed)
        .putLong(dayListenedKey(), prefs.getLong(dayListenedKey(), 0L) + safeElapsed)
        .putLong(weekListenedKey(), prefs.getLong(weekListenedKey(), 0L) + safeElapsed)
        .apply()
}

internal fun recordStatisticsLookup(context: Context, bookKey: String?) {
    if (!isStatisticsEnabled(context)) return
    val prefs = statisticsPrefs(context)
    val editor = prefs.edit()
        .putLong(KEY_TOTAL_LOOKUP_COUNT, prefs.getLong(KEY_TOTAL_LOOKUP_COUNT, 0L) + 1L)
        .putLong(dayLookupKey(), prefs.getLong(dayLookupKey(), 0L) + 1L)
        .putLong(weekLookupKey(), prefs.getLong(weekLookupKey(), 0L) + 1L)
    if (!bookKey.isNullOrBlank()) {
        editor.putLong(bookLookupKey(bookKey), prefs.getLong(bookLookupKey(bookKey), 0L) + 1L)
    }
    editor.apply()
}

internal fun recordStatisticsBookCompleted(context: Context, bookKey: String) {
    if (!isStatisticsEnabled(context)) return
    if (bookKey.isBlank()) return
    statisticsPrefs(context)
        .edit()
        .putBoolean(bookCompletedKey(bookKey), true)
        .apply()
}

internal fun loadStatisticsReport(context: Context): StatisticsReport {
    val prefs = statisticsPrefs(context)
    val enabled = prefs.getBoolean(KEY_ENABLED, false)
    val persisted = loadPersistedImports(context)
    val books = persisted.books.mapNotNull { persistedBook ->
        val audioUri = persistedBook.audioUri?.trim()?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return@mapNotNull null
        val srtUri = persistedBook.srtUri?.trim()?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val readerBook = ReaderBook(
            id = persistedBook.id,
            title = persistedBook.title,
            audioUri = audioUri,
            audioName = persistedBook.audioName,
            srtUri = srtUri,
            srtName = persistedBook.srtName,
            ebookUri = persistedBook.ebookUri?.trim()?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Uri.parse(it) }.getOrNull() },
            ebookName = persistedBook.ebookName,
            ebookFormat = persistedBook.ebookFormat,
            coverUri = null,
            coverSource = null
        )
        val bookKey = buildReaderBookPlaybackKey(readerBook)
        val legacyBookKey = buildLegacyReaderAudioPlaybackKey(
            title = readerBook.title,
            audioUri = readerBook.audioUri,
            srtUri = readerBook.srtUri
        )
        val statisticsKeys = listOf(bookKey, legacyBookKey).distinct()
        val playback = loadBestReaderBookPlaybackSnapshotCandidate(context, readerBook)?.snapshot
        val durationMs = playback?.durationMs ?: 0L
        val positionMs = playback?.positionMs ?: 0L
        val totalChars = srtUri?.let { countSrtTextChars(context, it) } ?: 0
        BookStatisticsSnapshot(
            id = persistedBook.id,
            title = persistedBook.title.ifBlank { persistedBook.audioName ?: persistedBook.ebookName.orEmpty() },
            bookKey = bookKey,
            progress = if (durationMs > 0L) positionMs.coerceIn(0L, durationMs).toFloat() / durationMs.toFloat() else 0f,
            listenedMs = statisticsKeys.sumOf { key -> prefs.getLong(bookListenedKey(key), 0L) },
            durationMs = durationMs,
            totalChars = totalChars,
            lookupCount = statisticsKeys.sumOf { key -> prefs.getLong(bookLookupKey(key), 0L) },
            completed = statisticsKeys.any { key -> prefs.getBoolean(bookCompletedKey(key), false) }
        )
    }
    val selected = persisted.selectedBookId?.let { selectedId -> books.firstOrNull { it.id == selectedId } }
        ?: books.firstOrNull()
    val totalListenedMs = prefs.getLong(KEY_TOTAL_LISTENED_MS, 0L)
    val totalProgressChars = books.sumOf { (it.totalChars * it.progress).toLong() }
    val speed = charsPerHour(totalProgressChars, totalListenedMs)
    val summary = StatisticsSummarySnapshot(
        todayListeningMs = prefs.getLong(dayListenedKey(), 0L),
        weekListeningMs = prefs.getLong(weekListenedKey(), 0L),
        totalListeningMs = totalListenedMs,
        completedBookCount = books.count { it.completed || it.progress >= 0.995f },
        lookupCount = prefs.getLong(KEY_TOTAL_LOOKUP_COUNT, 0L),
        listeningSpeedCharsPerHour = speed
    )
    return StatisticsReport(
        enabled = enabled,
        summary = summary,
        currentBook = selected,
        books = books
    )
}

private fun countSrtTextChars(context: Context, uri: Uri): Int {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
            lines
                .map { it.trim() }
                .filter { line -> line.isNotBlank() && !line.all { it.isDigit() } && !line.contains("-->") }
                .joinToString("")
                .replace(Regex("<[^>]+>"), "")
                .count { !it.isWhitespace() }
        } ?: 0
    }.getOrDefault(0)
}

internal fun formatStatisticsNumber(value: Long): String {
    return "%,d".format(Locale.US, value)
}

internal fun charsPerHour(chars: Long, listenedMs: Long): Long {
    if (chars <= 0L || listenedMs <= 0L) return 0L
    return (chars * 3_600_000L / listenedMs).coerceAtLeast(0L)
}

private fun statisticsPrefs(context: Context) =
    context.getSharedPreferences(STATISTICS_PREFS, Context.MODE_PRIVATE)

private fun bookListenedKey(bookKey: String) = "$KEY_LISTENED_PREFIX$bookKey"
private fun bookLookupKey(bookKey: String) = "$KEY_LOOKUP_PREFIX$bookKey"
private fun bookCompletedKey(bookKey: String) = "$KEY_COMPLETED_PREFIX$bookKey"
private fun statisticsToday(): LocalDate = LocalDate.now()
private fun dayListenedKey() = "$KEY_DAY_LISTENED_PREFIX${StatisticsDayFormat.format(statisticsToday())}"
private fun dayLookupKey() = "$KEY_DAY_LOOKUP_PREFIX${StatisticsDayFormat.format(statisticsToday())}"
private fun weekListenedKey() = "$KEY_WEEK_LISTENED_PREFIX${StatisticsWeekFormat.format(statisticsToday())}"
private fun weekLookupKey() = "$KEY_WEEK_LOOKUP_PREFIX${StatisticsWeekFormat.format(statisticsToday())}"
