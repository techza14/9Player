package moe.tekuza.m9player

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.tekuza.m9player.ui.theme.TsetTheme

private val StatisticsDemoBackground = Color(0xFFF5F7FB)
private val StatisticsDemoProgressBlue = Color(0xFF2563EB)
private val StatisticsDemoProgressTrack = Color(0xFFDCE7F5)
private val StatisticsDemoMutedText = Color(0xFF6B7280)
private val StatisticsDemoPrimaryText = Color(0xFF111827)
private val StatisticsDarkMutedText = Color(0xFFB7C3D1)
private val StatisticsDarkPrimaryText = Color(0xFFF2F6FB)
private val StatisticsDarkAccent = Color(0xFF8FBCE8)
private val StatisticsDarkProgressTrack = Color(0xFF334156)
private val StatisticsDarkCurrentSelection = Color(0xFF284461)

@Composable
private fun statisticsBackgroundColor(): Color =
    if (isSystemInDarkTheme()) HoshiDarkBackground else StatisticsDemoBackground

@Composable
private fun statisticsCardColor(): Color =
    if (isSystemInDarkTheme()) HoshiDarkCardBackground else Color.White

@Composable
private fun statisticsSoftColor(): Color =
    if (isSystemInDarkTheme()) HoshiDarkSoftCardBackground else StatisticsDemoBackground

@Composable
private fun statisticsCurrentSelectionColor(): Color =
    if (isSystemInDarkTheme()) StatisticsDarkCurrentSelection else StatisticsDemoProgressTrack

@Composable
private fun statisticsPrimaryTextColor(): Color =
    if (isSystemInDarkTheme()) StatisticsDarkPrimaryText else StatisticsDemoPrimaryText

@Composable
private fun statisticsMutedTextColor(): Color =
    if (isSystemInDarkTheme()) StatisticsDarkMutedText else StatisticsDemoMutedText

@Composable
private fun statisticsAccentColor(): Color =
    if (isSystemInDarkTheme()) StatisticsDarkAccent else StatisticsDemoProgressBlue

@Composable
private fun statisticsProgressTrackColor(): Color =
    if (isSystemInDarkTheme()) StatisticsDarkProgressTrack else StatisticsDemoProgressTrack

class StatisticsDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TsetTheme {
                StatisticsDemoScreen(onBack = { finish() })
            }
        }
    }
}

private data class StatisticsSummaryUi(
    val todayListening: String,
    val weekListening: String,
    val totalListening: String,
    val completedBooks: String,
    val lookupCount: String,
    val listeningSpeed: String,
)

private data class BookStatisticsUi(
    val title: String,
    val bookKey: String,
    val progress: Float,
    val listened: String,
    val characters: String,
    val remaining: String,
    val textDensity: String,
    val listeningSpeed: String,
    val progressSpeed: String,
    val lookupCount: String,
)

private data class BookComparisonSelection(
    val primary: BookStatisticsUi,
    val comparison: BookStatisticsUi,
)

private const val StatisticsSelectionPanelIndex = 5

private enum class ComparisonTarget {
    PRIMARY,
    COMPARISON,
}

@Composable
private fun StatisticsDemoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf<StatisticsReport?>(null) }
    var selectedBook by remember { mutableStateOf<BookStatisticsUi?>(null) }
    var comparisonBook by remember { mutableStateOf<BookStatisticsUi?>(null) }
    var selectionTarget by remember { mutableStateOf<ComparisonTarget?>(null) }
    var statisticsOpen by remember { mutableStateOf(isStatisticsEnabled(context)) }
    var selectionScrollRequest by remember { mutableStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(statisticsOpen) {
        val loaded = withContext(Dispatchers.IO) { loadStatisticsReport(context) }
        report = loaded
        val books = loaded.books.map { it.toUi(context) }
        selectedBook = selectedBook?.let { selected -> books.firstOrNull { it.bookKey == selected.bookKey } }
        comparisonBook = comparisonBook?.let { selected -> books.firstOrNull { it.bookKey == selected.bookKey } }
    }

    LaunchedEffect(selectionScrollRequest) {
        if (selectionScrollRequest > 0 && statisticsOpen) {
            listState.animateScrollToItem(StatisticsSelectionPanelIndex)
            val selectionItem = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == StatisticsSelectionPanelIndex }
            val overflow = selectionItem
                ?.let { it.offset + it.size - listState.layoutInfo.viewportEndOffset }
                ?: 0
            if (overflow > 0) {
                listState.animateScrollBy(overflow.toFloat())
            }
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_statistics_title),
        onBack = onBack
    ) { padding ->
        StatisticsDemoContent(
            summary = report?.summary?.toUi(context) ?: emptyStatisticsSummaryUi(context),
            currentBook = report?.currentBook?.toUi(context),
            books = report?.books.orEmpty().map { it.toUi(context) },
            selectedBook = selectedBook,
            comparisonBook = comparisonBook,
            statisticsOpen = statisticsOpen,
            onStatisticsEnabledChange = { enabled ->
                saveStatisticsEnabled(context, enabled)
                statisticsOpen = enabled
            },
            onPrimaryBookClick = { selectionTarget = ComparisonTarget.PRIMARY },
            onComparisonBookClick = { selectionTarget = ComparisonTarget.COMPARISON },
            onExportClick = {
                report?.let { exportStatisticsReport(context, it) }
            },
            listState = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }

    selectionTarget?.let { target ->
        val books = report?.books.orEmpty().map { it.toUi(context) }
        BookSelectionDialog(
            title = stringResource(
                if (target == ComparisonTarget.PRIMARY) {
                    R.string.statistics_select_audiobook
                } else {
                    R.string.statistics_compare_target
                }
            ),
            books = books,
            current = if (target == ComparisonTarget.PRIMARY) selectedBook else comparisonBook,
            onDismiss = { selectionTarget = null },
            onSelect = { book ->
                if (target == ComparisonTarget.PRIMARY) {
                    selectedBook = book
                    if (comparisonBook == book) {
                        comparisonBook = books.firstOrNull { it != book }
                    }
                } else {
                    comparisonBook = if (selectedBook == book) books.firstOrNull { it != book } else book
                }
                selectionTarget = null
                selectionScrollRequest += 1
            }
        )
    }
}

@Composable
private fun StatisticsDemoContent(
    summary: StatisticsSummaryUi,
    currentBook: BookStatisticsUi?,
    books: List<BookStatisticsUi>,
    selectedBook: BookStatisticsUi?,
    comparisonBook: BookStatisticsUi?,
    statisticsOpen: Boolean,
    onStatisticsEnabledChange: (Boolean) -> Unit,
    onPrimaryBookClick: () -> Unit,
    onComparisonBookClick: () -> Unit,
    onExportClick: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier.background(statisticsBackgroundColor()),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        if (!statisticsOpen) {
            item { StatisticsToggleOption(enabled = false, onClick = { onStatisticsEnabledChange(true) }) }
            return@LazyColumn
        }
        item {
            StatisticsToggleOption(enabled = true, onClick = { onStatisticsEnabledChange(false) })
        }
        item { SummaryGrid(summary) }
        currentBook?.let { book ->
            item {
                SectionTitle(
                    title = stringResource(R.string.statistics_current_book)
                )
            }
            item { CurrentBookCard(book) }
        }
        item {
            SectionTitle(
                title = stringResource(R.string.statistics_view_audiobook)
            )
        }
        if (selectedBook == null) {
            item { EmptyComparisonSelectionCard(onClick = onPrimaryBookClick) }
        } else if (comparisonBook == null) {
            item {
                SelectedBookOnlyPanel(
                    book = selectedBook,
                    onBookClick = onPrimaryBookClick,
                    onComparisonClick = onComparisonBookClick
                )
            }
        } else {
            item {
                BookComparisonPanel(
                    selection = BookComparisonSelection(primary = selectedBook, comparison = comparisonBook),
                    onPrimaryBookClick = onPrimaryBookClick,
                    onComparisonBookClick = onComparisonBookClick
                )
            }
        }
        item { ExportStatisticsButton(onClick = onExportClick) }
    }
}

@Composable
private fun StatisticsToggleOption(enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 2.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.statistics_enable),
            color = statisticsPrimaryTextColor(),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        Switch(
            checked = enabled,
            onCheckedChange = { onClick() }
        )
    }
}

@Composable
private fun SummaryGrid(summary: StatisticsSummaryUi) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(bottom = 40.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            SummaryMetric(stringResource(R.string.statistics_today), summary.todayListening, Modifier.weight(1f))
            SummaryMetric(stringResource(R.string.statistics_week), summary.weekListening, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            SummaryMetric(stringResource(R.string.statistics_total), summary.totalListening, Modifier.weight(1f))
            SummaryMetric(stringResource(R.string.statistics_completed), summary.completedBooks, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = statisticsCardColor(),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(label, color = statisticsMutedTextColor(), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(value, color = statisticsPrimaryTextColor(), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 18.dp)
    ) {
        Text(title, color = statisticsPrimaryTextColor(), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, color = statisticsMutedTextColor(), lineHeight = 22.sp, fontSize = 15.sp)
        }
    }
}

@Composable
private fun CurrentBookCard(book: BookStatisticsUi) {
    Card(
        colors = CardDefaults.cardColors(containerColor = statisticsCardColor()),
        shape = RoundedCornerShape(28.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.padding(bottom = 36.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(book.title, color = statisticsPrimaryTextColor(), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            ProgressLine(book.progress)
            StatRow(stringResource(R.string.statistics_listened), book.listened)
            StatRow(stringResource(R.string.statistics_read_chars), book.characters)
            StatRow(stringResource(R.string.statistics_remaining), book.remaining)
            StatRow(stringResource(R.string.statistics_lookup_count), book.lookupCount)
        }
    }
}

@Composable
private fun SelectedBookOnlyPanel(
    book: BookStatisticsUi,
    onBookClick: () -> Unit,
    onComparisonClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = statisticsCardColor()),
        shape = RoundedCornerShape(28.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.padding(bottom = 18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            ComparisonSelectionCard(
                label = stringResource(R.string.statistics_select_audiobook),
                book = book,
                onClick = onBookClick,
                modifier = Modifier.fillMaxWidth()
            )
            SelectedBookStats(book)
            TextButton(onClick = onComparisonClick) {
                Text(stringResource(R.string.statistics_compare))
            }
        }
    }
}

@Composable
private fun SelectedBookStats(book: BookStatisticsUi) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatRow(stringResource(R.string.statistics_listened), book.listened)
        StatRow(stringResource(R.string.statistics_read_chars), book.characters)
        StatRow(stringResource(R.string.statistics_remaining), book.remaining)
        StatRow(stringResource(R.string.statistics_text_density), book.textDensity)
        StatRow(stringResource(R.string.statistics_listening_speed), book.listeningSpeed)
        StatRow(stringResource(R.string.statistics_progress_speed), book.progressSpeed)
        StatRow(stringResource(R.string.statistics_lookup_count), book.lookupCount)
    }
}

@Composable
private fun BookComparisonPanel(
    selection: BookComparisonSelection,
    onPrimaryBookClick: () -> Unit,
    onComparisonBookClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = statisticsCardColor()),
        shape = RoundedCornerShape(28.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.padding(bottom = 18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                ComparisonSelectionCard(
                    label = stringResource(R.string.statistics_select_audiobook),
                    book = selection.primary,
                    onClick = onPrimaryBookClick,
                    modifier = Modifier.weight(1f),
                    compact = true
                )
                ComparisonSelectionCard(
                    label = stringResource(R.string.statistics_compare_target),
                    book = selection.comparison,
                    onClick = onComparisonBookClick,
                    modifier = Modifier.weight(1f),
                    compact = true
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ComparisonMetricRow(stringResource(R.string.statistics_listened), selection.primary.listened, selection.comparison.listened)
                ComparisonMetricRow(stringResource(R.string.statistics_read_chars), selection.primary.characters, selection.comparison.characters)
                ComparisonMetricRow(stringResource(R.string.statistics_text_density), selection.primary.textDensity, selection.comparison.textDensity)
                ComparisonMetricRow(stringResource(R.string.statistics_listening_speed), selection.primary.listeningSpeed, selection.comparison.listeningSpeed)
                ComparisonMetricRow(stringResource(R.string.statistics_progress_speed), selection.primary.progressSpeed, selection.comparison.progressSpeed)
                ComparisonMetricRow(stringResource(R.string.statistics_lookup_count), selection.primary.lookupCount, selection.comparison.lookupCount)
            }
        }
    }
}

@Composable
private fun ExportStatisticsButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 8.dp)
    ) {
        Text(stringResource(R.string.statistics_export))
    }
}

@Composable
private fun ComparisonSelectionCard(
    label: String,
    book: BookStatisticsUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Surface(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        color = statisticsSoftColor()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(label, color = statisticsMutedTextColor(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                book.title,
                color = statisticsPrimaryTextColor(),
                fontSize = if (compact) 15.sp else 18.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                "${(book.progress * 100).toInt()}%",
                color = statisticsAccentColor(),
                fontSize = if (compact) 18.sp else 22.sp,
                fontWeight = FontWeight.ExtraBold
            )
            ProgressLine(book.progress)
        }
    }
}

@Composable
private fun EmptyComparisonSelectionCard(onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = statisticsCardColor()),
        shape = RoundedCornerShape(28.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .padding(bottom = 18.dp)
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(stringResource(R.string.statistics_view_audiobook), color = statisticsPrimaryTextColor(), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun ComparisonMetricRow(label: String, left: String, right: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = statisticsMutedTextColor(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                left,
                modifier = Modifier.weight(1f),
                color = statisticsPrimaryTextColor(),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                right,
                modifier = Modifier.weight(1f),
                color = statisticsPrimaryTextColor(),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun BookSelectionDialog(
    title: String,
    books: List<BookStatisticsUi>,
    current: BookStatisticsUi?,
    onDismiss: () -> Unit,
    onSelect: (BookStatisticsUi) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                books.forEach { book ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(book) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (book == current) {
                            statisticsCurrentSelectionColor()
                        } else {
                            statisticsSoftColor()
                        }
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(book.title, color = statisticsPrimaryTextColor(), fontWeight = FontWeight.Bold)
                            Text(
                                "${(book.progress * 100).toInt()}% · ${book.listened}",
                                color = statisticsMutedTextColor(),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}

@Composable
private fun ProgressLine(progress: Float) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth(),
        color = statisticsAccentColor(),
        trackColor = statisticsProgressTrackColor()
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = statisticsMutedTextColor(), fontSize = 16.sp)
        Text(value, color = statisticsPrimaryTextColor(), fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

private fun StatisticsSummarySnapshot.toUi(context: android.content.Context): StatisticsSummaryUi {
    return StatisticsSummaryUi(
        todayListening = formatStatisticsDurationText(context, todayListeningMs),
        weekListening = formatStatisticsDurationText(context, weekListeningMs),
        totalListening = formatStatisticsDurationText(context, totalListeningMs),
        completedBooks = context.getString(R.string.statistics_unit_books, completedBookCount),
        lookupCount = context.getString(R.string.statistics_unit_times, formatStatisticsNumber(lookupCount)),
        listeningSpeed = context.getString(R.string.statistics_unit_chars_per_hour, formatStatisticsNumber(listeningSpeedCharsPerHour))
    )
}

private fun emptyStatisticsSummaryUi(context: android.content.Context): StatisticsSummaryUi {
    return StatisticsSummaryUi(
        todayListening = context.getString(R.string.statistics_zero_minutes),
        weekListening = context.getString(R.string.statistics_zero_minutes),
        totalListening = context.getString(R.string.statistics_zero_minutes),
        completedBooks = context.getString(R.string.statistics_unit_books, 0),
        lookupCount = context.getString(R.string.statistics_unit_times, "0"),
        listeningSpeed = context.getString(R.string.statistics_unit_chars_per_hour, "0")
    )
}

private fun BookStatisticsSnapshot.toUi(context: android.content.Context): BookStatisticsUi {
    val progressChars = (totalChars * progress).toLong()
    val remainingMs = (durationMs - (durationMs * progress).toLong()).coerceAtLeast(0L)
    val density = charsPerHour(totalChars.toLong(), durationMs)
    val speed = charsPerHour(progressChars, listenedMs)
    val progressPerHour = if (listenedMs > 0L) {
        progress * 100f * 3_600_000f / listenedMs.toFloat()
    } else {
        0f
    }
    return BookStatisticsUi(
        title = title,
        bookKey = bookKey,
        progress = progress,
        listened = "${formatStatisticsDurationText(context, listenedMs)} / ${formatStatisticsDurationText(context, durationMs)}",
        characters = context.getString(
            R.string.statistics_unit_chars,
            "${formatStatisticsNumber(progressChars)} / ${formatStatisticsNumber(totalChars.toLong())}"
        ),
        remaining = context.getString(R.string.statistics_about_duration, formatStatisticsDurationText(context, remainingMs)),
        textDensity = context.getString(R.string.statistics_unit_chars_per_hour, formatStatisticsNumber(density)),
        listeningSpeed = context.getString(R.string.statistics_unit_chars_per_hour, formatStatisticsNumber(speed)),
        progressSpeed = String.format(java.util.Locale.US, "%.1f%%/h", progressPerHour),
        lookupCount = context.getString(R.string.statistics_unit_times, formatStatisticsNumber(lookupCount))
    )
}

private fun exportStatisticsReport(context: android.content.Context, report: StatisticsReport) {
    val summary = report.summary.toUi(context)
    val text = buildString {
        appendLine(context.getString(R.string.statistics_export_title))
        appendLine(context.getString(R.string.statistics_export_today, summary.todayListening))
        appendLine(context.getString(R.string.statistics_export_week, summary.weekListening))
        appendLine(context.getString(R.string.statistics_export_total, summary.totalListening))
        appendLine(context.getString(R.string.statistics_export_completed, summary.completedBooks))
        appendLine(context.getString(R.string.statistics_export_lookup, summary.lookupCount))
        appendLine(context.getString(R.string.statistics_export_listening_speed, summary.listeningSpeed))
        appendLine()
        report.books.forEach { book ->
            val ui = book.toUi(context)
            appendLine(ui.title)
            appendLine(context.getString(R.string.statistics_export_progress, (ui.progress * 100).toInt()))
            appendLine(context.getString(R.string.statistics_export_listened, ui.listened))
            appendLine(context.getString(R.string.statistics_export_characters, ui.characters))
            appendLine(context.getString(R.string.statistics_export_lookup, ui.lookupCount))
            appendLine()
        }
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.statistics_export)))
}

private fun formatStatisticsDurationText(context: android.content.Context, ms: Long): String {
    val minutes = (ms / 60_000L).coerceAtLeast(0L)
    if (minutes < 60L) {
        return context.getString(R.string.statistics_minutes, minutes)
    }
    val hours = minutes / 60L
    val rest = minutes % 60L
    val hourText = if (rest == 0L) {
        hours.toString()
    } else {
        "${hours}.${(rest * 10L / 60L)}"
    }
    return context.getString(R.string.statistics_hours, hourText)
}
