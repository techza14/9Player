package moe.tekuza.m9player

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Html
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import moe.tekuza.m9player.ui.theme.TsetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var floatingOverlayStartJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TsetTheme {
                ReaderSyncScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        floatingOverlayStartJob?.cancel()
        floatingOverlayStartJob = null
        stopAudiobookFloatingOverlayService(this)
    }

    override fun onStop() {
        super.onStop()
        val settings = loadAudiobookSettingsConfig(this)
        floatingOverlayStartJob?.cancel()
        if (isChangingConfigurations || !settings.floatingOverlayEnabled || !BookReaderFloatingBridge.isPlaying()) return

        floatingOverlayStartJob = lifecycleScope.launch {
            delay(150L)
            if (
                !isAppProcessInForeground() &&
                loadAudiobookSettingsConfig(this@MainActivity).floatingOverlayEnabled &&
                BookReaderFloatingBridge.isPlaying()
            ) {
                startAudiobookFloatingOverlayService(this@MainActivity)
            }
        }
    }

    override fun onDestroy() {
        floatingOverlayStartJob?.cancel()
        floatingOverlayStartJob = null
        super.onDestroy()
    }
}

private enum class MiningSection {
    MAIN,
    DICTIONARY,
    COLLECTIONS,
    SETTINGS
}

private enum class HomeLibraryView {
    BOOKSHELF,
    LIST
}

private val FIELD_VARIABLE_CHOICES = listOf(
    "",
    "{cut-audio}",
    "{expression}",
    "{word}",
    "{reading}",
    "{furigana-plain}",
    "{glossary}",
    "{glossary-first}",
    "{single-glossary}",
    "{definitions}",
    "{popup-selection-text}",
    "{sentence}",
    "{sentence-furigana}",
    "{sentence-furigana-plain}",
    "{cloze-prefix}",
    "{cloze-body}",
    "{cloze-body-kana}",
    "{cloze-suffix}",
    "{frequencies}",
    "{frequency-harmonic-rank}",
    "{frequency-average-rank}",
    "{frequency}",
    "{pitch}",
    "{pitch-accents}",
    "{pitch-accent-positions}",
    "{pitch-accent-categories}",
    "{document-title}",
    "{book-title}",
    "{book-cover}",
    "{search-query}"
)

private const val BOOK_DELETE_PREFS = "book_delete_prefs"
private const val KEY_SKIP_DELETE_CONFIRM = "skip_delete_confirm"

private data class ReaderBook(
    val id: String,
    val title: String,
    val audioUri: Uri,
    val audioName: String,
    val srtUri: Uri?,
    val srtName: String?,
    val cues: List<SubtitleCue>,
    val coverUri: Uri?
)

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ReaderSyncScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val contentResolver = context.contentResolver
    val scope = rememberCoroutineScope()

    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var audioName by remember { mutableStateOf<String?>(null) }
    var srtUri by remember { mutableStateOf<Uri?>(null) }
    var srtName by remember { mutableStateOf<String?>(null) }

    var srtCues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var srtLoading by remember { mutableStateOf(false) }
    var srtError by remember { mutableStateOf<String?>(null) }
    var readerBooks by remember { mutableStateOf<List<ReaderBook>>(emptyList()) }
    var readerBookPlaybackSnapshots by remember {
        mutableStateOf<Map<String, BookReaderPlaybackSnapshot>>(emptyMap())
    }
    var selectedBookId by remember { mutableStateOf<String?>(null) }
    var homeLibraryView by remember { mutableStateOf(HomeLibraryView.BOOKSHELF) }
    var addBookDialogVisible by remember { mutableStateOf(false) }
    var addBookAudioUri by remember { mutableStateOf<Uri?>(null) }
    var addBookAudioName by remember { mutableStateOf<String?>(null) }
    var addBookSrtUri by remember { mutableStateOf<Uri?>(null) }
    var addBookSrtName by remember { mutableStateOf<String?>(null) }
    var addBookFolderUri by remember { mutableStateOf<Uri?>(null) }
    var addBookFolderName by remember { mutableStateOf<String?>(null) }
    var autoMoveToAudiobookFolder by remember { mutableStateOf(true) }
    var importOnboardingCompleted by remember { mutableStateOf(false) }
    var importGuideVisible by remember { mutableStateOf(false) }
    val selectedBookIds = remember { mutableStateListOf<String>() }

    var loadedDictionaries by remember { mutableStateOf<List<LoadedDictionary>>(emptyList()) }
    var dictionaryRefs by remember { mutableStateOf<List<PersistedDictionaryRef>>(emptyList()) }
    var dictionaryLoading by remember { mutableStateOf(false) }
    var dictionaryProgressText by remember { mutableStateOf<String?>(null) }
    var dictionaryProgressValue by remember { mutableStateOf<Float?>(null) }
    var dictionaryError by remember { mutableStateOf<String?>(null) }

    var lookupQuery by remember { mutableStateOf("") }
    var lookupResults by remember { mutableStateOf<List<DictionarySearchResult>>(emptyList()) }
    var lookupLoading by remember { mutableStateOf(false) }
    var selectedEntryKey by remember { mutableStateOf<String?>(null) }

    var exportStatus by remember { mutableStateOf<String?>(null) }
    var pendingAnkiCard by remember { mutableStateOf<MinedCard?>(null) }
    var ankiPermissionGranted by remember { mutableStateOf(hasAnkiReadWritePermission(context)) }
    var ankiDeckName by remember { mutableStateOf("Default") }
    var ankiModelName by remember { mutableStateOf("") }
    var ankiTagsInput by remember { mutableStateOf("") }
    var ankiDecks by remember { mutableStateOf<List<String>>(emptyList()) }
    var ankiModels by remember { mutableStateOf<List<AnkiModelTemplate>>(emptyList()) }
    var ankiModelFields by remember { mutableStateOf<List<String>>(emptyList()) }
    val ankiFieldTemplates = remember { mutableStateMapOf<String, String>() }
    var ankiLoading by remember { mutableStateOf(false) }
    var ankiError by remember { mutableStateOf<String?>(null) }

    var showDictionaryManager by remember { mutableStateOf(false) }
    var activeSection by remember { mutableStateOf(MiningSection.MAIN) }
    var collectedCues by remember { mutableStateOf<List<BookReaderCollectedCue>>(emptyList()) }
    var clearCollectionsConfirmVisible by remember { mutableStateOf(false) }
    var deleteBooksConfirmVisible by remember { mutableStateOf(false) }
    var deleteBooksDontAskAgain by remember { mutableStateOf(false) }
    var pendingDeleteBookIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var skipDeleteBookConfirm by remember { mutableStateOf(loadSkipDeleteBookConfirm(context)) }
    var mainLookupPopupVisible by remember { mutableStateOf(false) }
    var mainLookupPopupTitle by remember { mutableStateOf("") }
    var mainLookupPopupResults by remember { mutableStateOf<List<DictionarySearchResult>>(emptyList()) }
    var mainLookupPopupSelectedKey by remember { mutableStateOf<String?>(null) }
    var mainLookupPopupLoading by remember { mutableStateOf(false) }
    var mainLookupPopupError by remember { mutableStateOf<String?>(null) }
    var mainLookupPopupCue by remember { mutableStateOf<SubtitleCue?>(null) }
    var mainLookupPopupSelectedRange by remember { mutableStateOf<IntRange?>(null) }
    var mainLookupPopupAudioUri by remember { mutableStateOf<Uri?>(null) }
    var mainLookupAnkiStatus by remember { mutableStateOf<String?>(null) }

    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    var pendingCollectionPlayMs by remember { mutableStateOf<Long?>(null) }
    var pendingCollectionStopMs by remember { mutableStateOf<Long?>(null) }
    var collectionPlayRequestNonce by remember { mutableStateOf(0L) }

    val player = remember(context) {
        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    DisposableEffect(dictionaryLoading, view) {
        view.keepScreenOn = dictionaryLoading
        onDispose {
            view.keepScreenOn = false
        }
    }

    LaunchedEffect(readerBooks) {
        readerBookPlaybackSnapshots = loadReaderBookPlaybackSnapshotsForBooks(
            context = context,
            books = readerBooks
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    readerBookPlaybackSnapshots = loadReaderBookPlaybackSnapshotsForBooks(
                        context = context,
                        books = readerBooks
                    )
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = if (player.duration > 0L) player.duration else 0L
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = if (player.duration > 0L) player.duration else 0L
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                positionMs = newPosition.positionMs.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player, isPlaying) {
        if (!isPlaying) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = if (player.duration > 0L) player.duration else 0L
            return@LaunchedEffect
        }
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = if (player.duration > 0L) player.duration else 0L
            delay(320L)
        }
    }

    LaunchedEffect(audioUri) {
        val selectedAudio = audioUri ?: return@LaunchedEffect
        if (BookReaderFloatingBridge.isPlaying()) {
            // Keep reader playback untouched when returning to home.
            return@LaunchedEffect
        }
        player.setMediaItem(MediaItem.fromUri(selectedAudio))
        player.prepare()
        player.pause()
        player.seekTo(0L)
    }

    LaunchedEffect(audioUri, pendingCollectionPlayMs, collectionPlayRequestNonce) {
        val targetMs = pendingCollectionPlayMs ?: return@LaunchedEffect
        if (audioUri == null) return@LaunchedEffect
        var waitedMs = 0L
        while (player.playbackState == Player.STATE_IDLE && waitedMs < 2_000L) {
            delay(50L)
            waitedMs += 50L
        }
        player.seekTo(targetMs.coerceAtLeast(0L))
        player.play()
        pendingCollectionPlayMs = null
    }

    LaunchedEffect(positionMs, isPlaying, pendingCollectionStopMs) {
        val stopMs = pendingCollectionStopMs ?: return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        if (positionMs >= stopMs) {
            player.pause()
            pendingCollectionStopMs = null
        }
    }

    val requestAnkiPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            ankiPermissionGranted = granted || hasAnkiReadWritePermission(context)
            if (ankiPermissionGranted) {
                exportStatus = "Anki access permission granted."
            } else {
                pendingAnkiCard = null
                exportStatus = "Anki access permission was denied."
            }
        }

    fun persistImportState() {
        val persistedBooks = readerBooks.map { book ->
            PersistedReaderBook(
                id = book.id,
                title = book.title,
                audioUri = book.audioUri.toString(),
                audioName = book.audioName,
                srtUri = book.srtUri?.toString(),
                srtName = book.srtName
            )
        }
        savePersistedImports(
            context = context,
            state = PersistedImports(
                audioUri = audioUri?.toString(),
                audioName = audioName,
                srtUri = srtUri?.toString(),
                srtName = srtName,
                audiobookFolderUri = addBookFolderUri?.toString(),
                audiobookFolderName = addBookFolderName,
                autoMoveToAudiobookFolder = autoMoveToAudiobookFolder,
                importOnboardingCompleted = importOnboardingCompleted,
                books = persistedBooks,
                selectedBookId = selectedBookId,
                homeLibraryView = homeLibraryView.name,
                dictionaries = dictionaryRefs
            )
        )
    }

    fun persistAnkiConfig() {
        savePersistedAnkiConfig(
            context = context,
            config = PersistedAnkiConfig(
                deckName = ankiDeckName,
                modelName = ankiModelName,
                tags = ankiTagsInput,
                fieldTemplates = ankiFieldTemplates.toMap()
            )
        )
    }

    fun syncTemplatesWithModelFields(fields: List<String>, clearExisting: Boolean = false) {
        if (clearExisting) {
            ankiFieldTemplates.clear()
        }
        ankiModelFields = fields
        val keep = fields.toSet()
        ankiFieldTemplates.keys
            .filter { it !in keep }
            .forEach { ankiFieldTemplates.remove(it) }
        fields.forEach { field ->
            if (!ankiFieldTemplates.containsKey(field)) {
                ankiFieldTemplates[field] = ""
            }
        }
    }

    fun selectAnkiModel(modelName: String) {
        val modelChanged = ankiModelName != modelName
        ankiModelName = modelName
        val model = ankiModels.firstOrNull { it.name == modelName }
        syncTemplatesWithModelFields(
            fields = model?.fields ?: emptyList(),
            clearExisting = modelChanged
        )
        persistAnkiConfig()
    }

    fun refreshAnkiCatalog() {
        if (!isAnkiInstalled(context)) {
            ankiError = "AnkiDroid is not installed."
            ankiDecks = emptyList()
            ankiModels = emptyList()
            ankiModelFields = emptyList()
            return
        }

        if (!ankiPermissionGranted) {
            ankiError = "Please grant Anki access first."
            return
        }

        scope.launch {
            ankiLoading = true
            ankiError = null
            val result = withContext(Dispatchers.IO) {
                runCatching { loadAnkiCatalog(context) }
            }
            ankiLoading = false

            result.onSuccess { catalog ->
                ankiDecks = catalog.decks
                ankiModels = catalog.models

                if (ankiDeckName.isBlank()) {
                    ankiDeckName = catalog.decks.firstOrNull() ?: "Default"
                }

                val resolvedModelName = when {
                    ankiModelName.isNotBlank() && catalog.models.any { it.name == ankiModelName } -> ankiModelName
                    catalog.models.isNotEmpty() -> catalog.models.first().name
                    else -> ""
                }
                selectAnkiModel(resolvedModelName)
                persistAnkiConfig()
            }.onFailure { error ->
                ankiError = error.message ?: "Failed to load Anki decks/models"
            }
        }
    }

    fun currentAnkiExportConfigOrNull(): AnkiExportConfig? {
        if (ankiModelName.isBlank()) return null
        val model = ankiModels.firstOrNull { it.name == ankiModelName } ?: return null
        val templates = model.fields.associateWith { field ->
            ankiFieldTemplates[field].orEmpty()
        }
        return AnkiExportConfig(
            deckName = ankiDeckName.ifBlank { "Default" },
            modelName = model.name,
            fieldTemplates = templates,
            tags = parseAnkiTags(ankiTagsInput)
        )
    }

    fun clearCurrentFieldTemplates() {
        ankiModelFields.forEach { field ->
            ankiFieldTemplates[field] = ""
        }
        persistAnkiConfig()
    }

    fun refreshCollectedCues() {
        collectedCues = loadBookReaderCollectedCues(context)
    }

    fun updateDictionaryProgress(progress: DictionaryImportProgress) {
        dictionaryProgressText = when {
            progress.total > 0 -> "${progress.stage} (${progress.current}/${progress.total})"
            progress.current > 0 -> "${progress.stage} (${progress.current})"
            else -> progress.stage
        }
        dictionaryProgressValue = if (progress.total > 0 && progress.current >= 0) {
            (progress.current.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f)
        } else {
            null
        }
    }

    fun clearDictionaryProgress() {
        dictionaryProgressText = null
        dictionaryProgressValue = null
    }

    fun buildReaderBook(
        audio: Uri,
        audioDisplayName: String?,
        srt: Uri?,
        srtDisplayName: String?,
        cues: List<SubtitleCue>
    ): ReaderBook {
        val resolvedAudioName = audioDisplayName?.takeIf { it.isNotBlank() }
            ?: queryDisplayName(contentResolver, audio)
        val resolvedSrtName = srt?.let {
            srtDisplayName?.takeIf { name -> name.isNotBlank() }
                ?: queryDisplayName(contentResolver, it)
        }
        val title = buildBookTitle(resolvedAudioName, resolvedSrtName)
        val coverUri = resolveEmbeddedCoverUriForM4b(
            context = context,
            audioUri = audio,
            audioDisplayName = resolvedAudioName
        )
        val srtIdPart = srt?.toString().orEmpty()
        val srtNamePart = resolvedSrtName.orEmpty()
        val id = buildDictionaryCacheKey(
            uri = "book|${audio}|$srtIdPart",
            displayName = "$resolvedAudioName|$srtNamePart"
        )
        return ReaderBook(
            id = id,
            title = title,
            audioUri = audio,
            audioName = resolvedAudioName,
            srtUri = srt,
            srtName = resolvedSrtName,
            cues = cues,
            coverUri = coverUri
        )
    }

    fun activateReaderBook(book: ReaderBook, persist: Boolean = true) {
        selectedBookId = book.id
        audioUri = book.audioUri
        audioName = book.audioName
        srtUri = book.srtUri
        srtName = book.srtName
        srtCues = book.cues
        srtError = null
        pendingCollectionPlayMs = null
        pendingCollectionStopMs = null
        if (persist) {
            persistImportState()
        }
    }

    fun openReaderBook(book: ReaderBook, persist: Boolean = true) {
        val targetAudioUri = book.audioUri.toString()
        val isSameReaderBook = BookReaderFloatingBridge.currentAudioUri() == targetAudioUri
        if (isSameReaderBook) {
            activateReaderBook(book, persist = persist)
            val intent = Intent(context, BookReaderActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(intent)
            return
        }
        BookReaderActivity.stopActiveReaderIfDifferentAudio(targetAudioUri)
        activateReaderBook(book, persist = persist)
        val intent = Intent(context, BookReaderActivity::class.java).apply {
            putExtra(BookReaderActivity.EXTRA_BOOK_TITLE, buildBookTitle(book.audioName, book.srtName))
            putExtra(BookReaderActivity.EXTRA_AUDIO_URI, book.audioUri.toString())
            book.srtUri?.let { putExtra(BookReaderActivity.EXTRA_SRT_URI, it.toString()) }
            book.coverUri?.let { putExtra(BookReaderActivity.EXTRA_COVER_URI, it.toString()) }
        }
        context.startActivity(intent)
    }

    fun upsertReaderBook(book: ReaderBook, activate: Boolean) {
        readerBooks = listOf(book) + readerBooks.filterNot { it.id == book.id }
        if (activate) {
            activateReaderBook(book, persist = true)
        }
    }

    fun toggleBookSelection(bookId: String) {
        if (selectedBookIds.contains(bookId)) {
            selectedBookIds.remove(bookId)
        } else {
            selectedBookIds.add(bookId)
        }
    }

    fun clearBookSelection() {
        selectedBookIds.clear()
    }

    fun deleteSelectedBooks(removeIds: Set<String>) {
        if (removeIds.isEmpty()) return
        val deletingBooks = readerBooks.filter { it.id in removeIds }
        val failedFolderDeletes = deletingBooks.filterNot { book ->
            deleteBookStorageFolder(context, book)
        }
        val remaining = readerBooks.filterNot { it.id in removeIds }
        readerBooks = remaining
        if (selectedBookId in removeIds) {
            val next = remaining.firstOrNull()
            if (next != null) {
                activateReaderBook(next, persist = true)
            } else {
                selectedBookId = null
                audioUri = null
                audioName = null
                srtUri = null
                srtName = null
                srtCues = emptyList()
            }
        }
        persistImportState()
        clearBookSelection()
        exportStatus = if (failedFolderDeletes.isEmpty()) {
            "已删除 ${removeIds.size} 本书（含文件夹）。"
        } else {
            "已删书 ${removeIds.size} 本；${failedFolderDeletes.size} 个文件夹删除失败。"
        }
    }

    fun requestDeleteSelectedBooks() {
        val removeIds = selectedBookIds.toSet()
        if (removeIds.isEmpty()) return
        if (skipDeleteBookConfirm) {
            deleteSelectedBooks(removeIds)
            return
        }
        pendingDeleteBookIds = removeIds
        deleteBooksDontAskAgain = false
        deleteBooksConfirmVisible = true
    }

    fun playCollectedCue(item: BookReaderCollectedCue) {
        val targetBook = readerBooks.firstOrNull { it.title == item.bookTitle }
        if (targetBook == null) {
            exportStatus = "No matched book audio for this collection item."
            return
        }
        activateReaderBook(targetBook, persist = true)
        pendingCollectionPlayMs = item.startMs
        pendingCollectionStopMs = item.endMs.takeIf { it > item.startMs }
        collectionPlayRequestNonce += 1L
    }

    fun confirmAddBookFromDialog() {
        val selectedFolder = addBookFolderUri
        val shouldAutoMove = autoMoveToAudiobookFolder
        if (shouldAutoMove && selectedFolder == null) {
            exportStatus = "请先选择有声书文件夹。"
            return
        }
        val pickedAudio = addBookAudioUri
        if (pickedAudio == null) {
            exportStatus = "请选择音频文件。"
            return
        }
        val pickedSrt = addBookSrtUri
        val pickedAudioName = addBookAudioName
        val pickedSrtName = addBookSrtName
        scope.launch {
            srtLoading = true
            srtError = null
            val importResult = withContext(Dispatchers.IO) {
                runCatching {
                    if (shouldAutoMove) {
                        val relocated = relocateSelectedBookFilesToAudFolder(
                            context = context,
                            contentResolver = contentResolver,
                            rootFolderUri = selectedFolder ?: error("Audiobook folder is required"),
                            audioSourceUri = pickedAudio,
                            audioSourceName = pickedAudioName,
                            srtSourceUri = pickedSrt,
                            srtSourceName = pickedSrtName
                        )
                        val cues = relocated.srtUri?.let { parseSrt(contentResolver, it) } ?: emptyList()
                        val book = buildReaderBook(
                            audio = relocated.audioUri,
                            audioDisplayName = relocated.audioName,
                            srt = relocated.srtUri,
                            srtDisplayName = relocated.srtName,
                            cues = cues
                        )
                        val warning = relocated.moveWarnings.takeIf { it.isNotEmpty() }?.joinToString(" ")
                        Triple(book, relocated.folderName, warning)
                    } else {
                        val cues = pickedSrt?.let { parseSrt(contentResolver, it) } ?: emptyList()
                        val book = buildReaderBook(
                            audio = pickedAudio,
                            audioDisplayName = pickedAudioName,
                            srt = pickedSrt,
                            srtDisplayName = pickedSrtName,
                            cues = cues
                        )
                        Triple(book, null, null)
                    }
                }
            }
            srtLoading = false
            importResult.onSuccess { (book, folderName, warning) ->
                upsertReaderBook(book, activate = true)
                addBookDialogVisible = false
                addBookAudioUri = null
                addBookAudioName = null
                addBookSrtUri = null
                addBookSrtName = null
                exportStatus = buildString {
                    append("已添加书籍：${book.title}。")
                    if (!folderName.isNullOrBlank()) {
                        append(" 已存入：$folderName。")
                    } else {
                        append(" 保留原文件位置。")
                    }
                    if (!warning.isNullOrBlank()) {
                        append(' ')
                        append(warning)
                    }
                }
            }.onFailure { error ->
                srtError = error.message ?: "添加书籍失败"
                exportStatus = "添加书籍失败。"
            }
        }
    }

    fun refreshBookshelfFromFolder() {
        val selectedFolder = addBookFolderUri
        if (selectedFolder == null) {
            exportStatus = "请先选择有声书文件夹。"
            return
        }
        val previousSelectedId = selectedBookId
        scope.launch {
            srtLoading = true
            srtError = null
            val refreshResult = withContext(Dispatchers.IO) {
                runCatching {
                    val scanResult = scanBooksFromRootFolder(
                        context = context,
                        contentResolver = contentResolver,
                        rootFolderUri = selectedFolder
                    )
                    val refreshedBooks = mutableListOf<ReaderBook>()
                    val parseFailed = mutableListOf<String>()
                    scanResult.books.forEach { candidate ->
                        runCatching {
                            val cues = candidate.srtUri?.let { parseSrt(contentResolver, it) } ?: emptyList()
                            val rebuilt = buildReaderBook(
                                audio = candidate.audioUri,
                                audioDisplayName = candidate.audioName,
                                srt = candidate.srtUri,
                                srtDisplayName = candidate.srtName,
                                cues = cues
                            )
                            rebuilt
                        }.onSuccess { refreshedBooks += it }
                            .onFailure {
                                parseFailed += candidate.folderName
                            }
                    }
                    Triple(refreshedBooks, scanResult.skippedFolders, parseFailed)
                }
            }
            srtLoading = false
            refreshResult.onSuccess { (books, skippedFolders, parseFailed) ->
                if (books.isEmpty()) {
                    srtError = "未找到可导入书籍。"
                    exportStatus = "刷新完成：0 本。"
                    return@onSuccess
                }
                readerBooks = books
                clearBookSelection()
                val selected = books.firstOrNull { it.id == previousSelectedId } ?: books.first()
                activateReaderBook(selected, persist = false)
                selectedBookId = selected.id
                persistImportState()

                exportStatus = buildString {
                    append("刷新完成：${books.size} 本。")
                    if (skippedFolders.isNotEmpty()) {
                        append(" 跳过 ${skippedFolders.size} 个文件夹（缺少音频）。")
                    }
                    if (parseFailed.isNotEmpty()) {
                        append(" 失败 ${parseFailed.size} 个文件夹（SRT无效）。")
                    }
                }
            }.onFailure { error ->
                srtError = error.message ?: "刷新书架失败"
                exportStatus = "刷新书架失败。"
            }
        }
    }

    fun tryExportCardToAnki(card: MinedCard) {
        if (!isAnkiInstalled(context)) {
            exportStatus = "AnkiDroid is not installed."
            return
        }

        if (!ankiPermissionGranted) {
            pendingAnkiCard = card
            exportStatus = "Requesting Anki access permission..."
            requestAnkiPermissionLauncher.launch(ANKI_READ_WRITE_PERMISSION)
            return
        }

        if (ankiModels.isEmpty()) {
            refreshAnkiCatalog()
            exportStatus = "Loading Anki models. Select a model/template, then export."
            return
        }

        val config = currentAnkiExportConfigOrNull()
        if (config == null) {
            exportStatus = "Select an Anki model/template first, then export."
            return
        }
        if (!hasAnyAnkiFieldTemplate(config.fieldTemplates)) {
            exportStatus = "All field variables are empty. Configure at least one marker in 设置 > Anki."
            return
        }
        val exportResult = runCatching {
            exportToAnkiDroidApi(context, card, config)
        }
        exportStatus = exportResult.fold(
            onSuccess = { "Sent to AnkiDroid." },
            onFailure = { it.message ?: "Failed to export to AnkiDroid" }
        )
    }

    LaunchedEffect(ankiPermissionGranted) {
        if (ankiPermissionGranted && ankiModels.isEmpty()) {
            refreshAnkiCatalog()
        }
    }

    LaunchedEffect(ankiPermissionGranted, pendingAnkiCard) {
        val card = pendingAnkiCard ?: return@LaunchedEffect
        if (!ankiPermissionGranted) return@LaunchedEffect
        pendingAnkiCard = null
        tryExportCardToAnki(card)
    }

    LaunchedEffect(activeSection) {
        if (activeSection == MiningSection.COLLECTIONS) {
            refreshCollectedCues()
        }
        if (activeSection != MiningSection.DICTIONARY) {
            lookupQuery = ""
            lookupResults = emptyList()
            selectedEntryKey = null
            lookupLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshCollectedCues()
        val persistedAnki = loadPersistedAnkiConfig(context)
        ankiDeckName = persistedAnki.deckName
        ankiModelName = persistedAnki.modelName
        ankiTagsInput = persistedAnki.tags
        ankiFieldTemplates.clear()
        persistedAnki.fieldTemplates.forEach { (field, template) ->
            ankiFieldTemplates[field] = template
        }

        val persisted = loadPersistedImports(context)
        addBookFolderUri = persisted.audiobookFolderUri
            ?.let { rawUri -> runCatching { Uri.parse(rawUri) }.getOrNull() }
        addBookFolderName = persisted.audiobookFolderName?.ifBlank { null }
            ?: addBookFolderUri?.let { uri ->
                queryTreeDisplayName(context, contentResolver, uri)
            }
        autoMoveToAudiobookFolder = persisted.autoMoveToAudiobookFolder
        importOnboardingCompleted = persisted.importOnboardingCompleted
        importGuideVisible = !persisted.importOnboardingCompleted
        homeLibraryView = when (persisted.homeLibraryView.uppercase(Locale.ROOT)) {
            HomeLibraryView.LIST.name -> HomeLibraryView.LIST
            else -> HomeLibraryView.BOOKSHELF
        }

        if (persisted.books.isNotEmpty()) {
            srtLoading = true
            srtError = null
            val restoreBooksResult = withContext(Dispatchers.IO) {
                runCatching {
                    val restoredBooks = mutableListOf<ReaderBook>()
                    val failedBooks = mutableListOf<String>()
                    persisted.books.forEach { savedBook ->
                        val audio = runCatching { Uri.parse(savedBook.audioUri) }.getOrNull()
                        val srt = savedBook.srtUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                        if (audio == null) {
                            failedBooks += savedBook.title.ifBlank { savedBook.audioName }
                            return@forEach
                        }
                        runCatching {
                            val cues = srt?.let { parseSrt(contentResolver, it) } ?: emptyList()
                            val rebuilt = buildReaderBook(
                                audio = audio,
                                audioDisplayName = savedBook.audioName,
                                srt = srt,
                                srtDisplayName = savedBook.srtName,
                                cues = cues
                            )
                            rebuilt
                        }.onSuccess { restoredBooks += it }
                            .onFailure {
                                failedBooks += savedBook.title.ifBlank { savedBook.audioName }
                            }
                    }
                    restoredBooks to failedBooks
                }
            }
            srtLoading = false
            restoreBooksResult.onSuccess { (restoredBooks, failedBooks) ->
                readerBooks = restoredBooks
                val restoredSelected = restoredBooks.firstOrNull { it.id == persisted.selectedBookId }
                    ?: restoredBooks.firstOrNull()
                if (restoredSelected != null) {
                    activateReaderBook(restoredSelected, persist = false)
                    selectedBookId = restoredSelected.id
                } else {
                    selectedBookId = null
                    audioUri = null
                    audioName = null
                    srtUri = null
                    srtName = null
                    srtCues = emptyList()
                }
                if (failedBooks.isNotEmpty()) {
                    exportStatus = "恢复书籍时有 ${failedBooks.size} 本失败。"
                }
            }.onFailure { error ->
                readerBooks = emptyList()
                selectedBookId = null
                audioUri = null
                audioName = null
                srtUri = null
                srtName = null
                srtCues = emptyList()
                srtError = "恢复书籍失败：${error.message ?: "unknown error"}"
                exportStatus = "恢复书架失败。"
            }
        } else {
            // Backward compatibility with older single-book persistence format.
            val restoredAudioRaw = persisted.audioUri?.let { rawUri ->
                runCatching { Uri.parse(rawUri) }.getOrNull()
            }
            val restoredSrtRaw = persisted.srtUri?.let { rawUri ->
                runCatching { Uri.parse(rawUri) }.getOrNull()
            }

            if (restoredAudioRaw != null) {
                srtLoading = true
                srtError = null
                val restoreResult = withContext(Dispatchers.IO) {
                    runCatching {
                        val restoredAudioName = persisted.audioName?.ifBlank { null }
                            ?: queryDisplayName(contentResolver, restoredAudioRaw)
                        val restoredSrtName = restoredSrtRaw?.let {
                            persisted.srtName?.ifBlank { null }
                                ?: queryDisplayName(contentResolver, it)
                        }
                        val cues = restoredSrtRaw?.let { parseSrt(contentResolver, it) } ?: emptyList()
                        Triple(restoredAudioRaw, restoredAudioName, Pair(restoredSrtRaw, restoredSrtName)) to cues
                    }
                }
                srtLoading = false
                restoreResult.onSuccess { (restored, cues) ->
                    val (audio, audioDisplay, srtPair) = restored
                    val (srt, srtDisplay) = srtPair
                    audioUri = audio
                    audioName = audioDisplay
                    srtUri = srt
                    srtName = srtDisplay
                    srtCues = cues
                    val restoredBook = buildReaderBook(
                        audio = audio,
                        audioDisplayName = audioDisplay,
                        srt = srt,
                        srtDisplayName = srtDisplay,
                        cues = cues
                    )
                    readerBooks = listOf(restoredBook)
                    selectedBookId = restoredBook.id
                    persistImportState()
                }.onFailure { error ->
                    audioUri = null
                    audioName = null
                    srtUri = null
                    srtName = null
                    srtCues = emptyList()
                    srtError = "Failed to restore book files. Please re-add audio. ${error.message ?: "unknown error"}"
                    exportStatus = "Saved book file permission expired. Re-add audio."
                }
            }
        }

        if (persisted.dictionaries.isNotEmpty()) {
            dictionaryLoading = true
            dictionaryError = null

            val restoredDictionaryList = mutableListOf<LoadedDictionary>()
            val restoredRefs = mutableListOf<PersistedDictionaryRef>()
            val distinctRefs = persisted.dictionaries.distinctBy { it.uri }
            val total = distinctRefs.size

            distinctRefs.forEachIndexed { index, ref ->
                val uri = runCatching { Uri.parse(ref.uri) }.getOrNull() ?: return@forEachIndexed
                val displayName = ref.name.ifBlank { queryDisplayName(contentResolver, uri) }
                val cacheKey = ref.cacheKey ?: buildDictionaryCacheKey(ref.uri, displayName)

                updateDictionaryProgress(
                    DictionaryImportProgress(
                        stage = "Restoring dictionaries",
                        current = index + 1,
                        total = total
                    )
                )

                val storedDictionary = withContext(Dispatchers.IO) {
                    loadDictionaryFromSqlite(context, cacheKey)
                }
                if (storedDictionary != null) {
                    restoredDictionaryList += storedDictionary
                    restoredRefs += PersistedDictionaryRef(
                        uri = ref.uri,
                        name = displayName,
                        cacheKey = cacheKey
                    )
                    return@forEachIndexed
                }

                val parseResult = withContext(Dispatchers.IO) {
                    runCatching {
                        importDictionaryToSqlite(
                            context = context,
                            contentResolver = contentResolver,
                            uri = uri,
                            displayName = displayName,
                            cacheKey = cacheKey
                        ) { progress ->
                            scope.launch(Dispatchers.Main.immediate) {
                                updateDictionaryProgress(progress)
                            }
                        }
                    }
                }

                val parsedDictionary = parseResult.getOrNull()
                if (parsedDictionary != null) {
                    restoredDictionaryList += parsedDictionary
                    restoredRefs += PersistedDictionaryRef(
                        uri = ref.uri,
                        name = displayName,
                        cacheKey = cacheKey
                    )
                } else {
                    val error = parseResult.exceptionOrNull()
                    dictionaryError = "Failed to restore dictionary $displayName: ${error?.message ?: "unknown error"}"
                }
            }

            loadedDictionaries = restoredDictionaryList
            dictionaryRefs = restoredRefs
            dictionaryLoading = false
            clearDictionaryProgress()
            persistImportState()
        }

        if (ankiPermissionGranted) {
            refreshAnkiCatalog()
        }
    }

    fun normalizeLookupCandidates(rawCandidates: List<String>): List<String> {
        return rawCandidates
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(1)
    }

    fun computeLookupResults(
        dictionaries: List<LoadedDictionary>,
        candidates: List<String>
    ): List<DictionarySearchResult> {
        if (dictionaries.isEmpty() || candidates.isEmpty()) return emptyList()
        val query = candidates
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: return emptyList()
        return searchDictionarySql(
            context = context,
            dictionaries = dictionaries,
            query = query,
            maxResults = MAX_LOOKUP_RESULTS,
            profile = DictionaryQueryProfile.FULL
        )
    }

    fun triggerLookupCandidates(
        rawCandidates: List<String>,
        onResult: ((Result<List<DictionarySearchResult>>) -> Unit)? = null
    ) {
        val candidates = normalizeLookupCandidates(rawCandidates)
        if (candidates.isEmpty()) {
            lookupResults = emptyList()
            selectedEntryKey = null
            lookupLoading = false
            onResult?.invoke(Result.success(emptyList()))
            return
        }

        lookupQuery = candidates.first()
        val dictionariesSnapshot = loadedDictionaries
        scope.launch {
            lookupLoading = true
            val result = withContext(Dispatchers.Default) {
                runCatching { computeLookupResults(dictionariesSnapshot, candidates) }
            }
            result.onSuccess { hits ->
                lookupResults = hits
                selectedEntryKey = when {
                    selectedEntryKey != null && hits.any { entryStableKey(it.entry) == selectedEntryKey } -> selectedEntryKey
                    hits.isNotEmpty() -> entryStableKey(hits.first().entry)
                    else -> null
                }
            }.onFailure { error ->
                lookupResults = emptyList()
                selectedEntryKey = null
                exportStatus = "Lookup failed: ${error.message ?: "unknown error"}"
            }
            lookupLoading = false
            onResult?.invoke(result)
        }
    }

    fun triggerMainCueLookup(cue: SubtitleCue, offset: Int) {
        if (loadedDictionaries.isEmpty()) {
            exportStatus = "Import dictionary first."
            return
        }

        val selection = findMainLookupSelection(cue.text, offset)
        val selectionRange = selection?.range
        mainLookupPopupSelectedRange = null
        mainLookupPopupCue = cue

        val selectedToken = selection?.text?.trim()?.takeIf { it.isNotBlank() }
        val candidates = listOfNotNull(selectedToken)

        if (candidates.isEmpty()) {
            mainLookupPopupVisible = true
            mainLookupPopupTitle = selectedToken.orEmpty()
            mainLookupPopupResults = emptyList()
            mainLookupPopupSelectedKey = null
            mainLookupPopupError = "No lookup candidate for this position."
            mainLookupPopupLoading = false
            return
        }

        mainLookupPopupVisible = true
        mainLookupPopupTitle = candidates.firstOrNull() ?: selectedToken.orEmpty()
        mainLookupPopupResults = emptyList()
        mainLookupPopupSelectedKey = null
        mainLookupPopupError = null
        mainLookupPopupLoading = true
        mainLookupAnkiStatus = null

        triggerLookupCandidates(candidates) { result ->
            result.onSuccess { hits ->
                mainLookupPopupResults = hits
                mainLookupPopupSelectedKey = hits.firstOrNull()?.let { entryStableKey(it.entry) }
                mainLookupPopupSelectedRange = if (hits.isNotEmpty()) {
                    trimSelectionRangeByMatchedLength(selectionRange, hits.first().matchedLength)
                } else {
                    null
                }
                mainLookupPopupLoading = false
            }.onFailure { error ->
                mainLookupPopupError = error.message ?: "Lookup failed"
                mainLookupPopupLoading = false
            }
        }
    }

    fun openMainLookupPopup(cue: SubtitleCue, sourceBookTitle: String? = null) {
        mainLookupPopupAudioUri = if (sourceBookTitle.isNullOrBlank()) {
            audioUri
        } else {
            readerBooks.firstOrNull { it.title == sourceBookTitle }?.audioUri ?: audioUri
        }
        triggerMainCueLookup(cue, 0)
    }

    fun openMainLookupPopup(rawCandidates: List<String>) {
        if (loadedDictionaries.isEmpty()) {
            exportStatus = "Import dictionary first."
            return
        }

        val candidates = normalizeLookupCandidates(rawCandidates)
        if (candidates.isEmpty()) return

        mainLookupPopupVisible = true
        mainLookupPopupTitle = candidates.first()
        mainLookupPopupResults = emptyList()
        mainLookupPopupSelectedKey = null
        mainLookupPopupError = null
        mainLookupPopupLoading = true
        mainLookupPopupCue = null
        mainLookupPopupSelectedRange = null
        mainLookupPopupAudioUri = null
        mainLookupAnkiStatus = null

        triggerLookupCandidates(candidates) { result ->
            result.onSuccess { hits ->
                mainLookupPopupResults = hits
                mainLookupPopupSelectedKey = hits.firstOrNull()?.let { entryStableKey(it.entry) }
                mainLookupPopupLoading = false
            }.onFailure { error ->
                mainLookupPopupError = error.message ?: "Lookup failed"
                mainLookupPopupLoading = false
            }
        }
    }

    fun exportLookupGroupToAnki(
        groupedResult: GroupedLookupResult,
        sourceCue: SubtitleCue?,
        lookupTitle: String
    ) {
        val dictionaryGroup = groupedResult.dictionaries.firstOrNull() ?: run {
            mainLookupAnkiStatus = "No dictionary content to export."
            return
        }
        val cueText = sourceCue?.text?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: lookupTitle.trim().ifBlank { groupedResult.term }
        val cue = sourceCue ?: SubtitleCue(
            startMs = 0L,
            endMs = 0L,
            text = cueText
        )
        val popupSelectionText = sourceCue?.let { cueItem ->
            mainLookupPopupSelectedRange?.let { range ->
                val start = range.first.coerceIn(0, cueItem.text.length)
                val endExclusive = (range.last + 1).coerceIn(start, cueItem.text.length)
                if (endExclusive > start) cueItem.text.substring(start, endExclusive) else ""
            }
        }?.trim()?.takeIf { it.isNotBlank() }
        val glossarySections = buildGroupedGlossarySections(groupedResult)
        val primaryDefinition = dictionaryGroup.definitions
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
        scope.launch {
            mainLookupAnkiStatus = "Adding to Anki..."
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    addLookupDefinitionToAnkiMain(
                        context = context,
                        cue = cue,
                        audioUri = mainLookupPopupAudioUri,
                        bookTitle = readerBooks.firstOrNull { it.audioUri == mainLookupPopupAudioUri }?.title,
                        entry = dictionaryGroup.entry,
                        definition = primaryDefinition.ifBlank { groupedResult.term },
                        dictionaryCss = null,
                        glossarySections = glossarySections,
                        popupSelectionText = popupSelectionText
                    )
                }
            }
            val status = result.fold(
                onSuccess = { "Added to Anki." },
                onFailure = { it.message ?: "Failed to add to Anki." }
            )
            mainLookupAnkiStatus = status
            exportStatus = status
        }
    }

    fun removeDictionaryAt(index: Int) {
        val ref = dictionaryRefs.getOrNull(index) ?: return

        dictionaryRefs = dictionaryRefs.filterIndexed { i, _ -> i != index }
        loadedDictionaries = loadedDictionaries.filterIndexed { i, _ -> i != index }
        ref.cacheKey?.let { cacheKey ->
            scope.launch(Dispatchers.IO) {
                deleteDictionaryFromSqlite(context, cacheKey)
            }
        }
        persistImportState()
        if (lookupQuery.isNotBlank()) {
            triggerLookupCandidates(listOf(lookupQuery))
        }
    }

    fun moveDictionary(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        if (fromIndex !in dictionaryRefs.indices || toIndex !in dictionaryRefs.indices) return

        val refs = dictionaryRefs.toMutableList()
        val movedRef = refs.removeAt(fromIndex)
        refs.add(toIndex, movedRef)
        dictionaryRefs = refs

        val dictionaries = loadedDictionaries.toMutableList()
        if (fromIndex in dictionaries.indices) {
            val movedDictionary = dictionaries.removeAt(fromIndex)
            val targetIndex = toIndex.coerceIn(0, dictionaries.size)
            dictionaries.add(targetIndex, movedDictionary)
            loadedDictionaries = dictionaries
        }

        persistImportState()
        if (lookupQuery.isNotBlank()) {
            triggerLookupCandidates(listOf(lookupQuery))
        }
    }

    val pickBookAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        keepReadPermission(context, uri)
        addBookAudioUri = uri
        addBookAudioName = queryDisplayName(contentResolver, uri)
    }

    val pickBookSrtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        keepReadPermission(context, uri)
        addBookSrtUri = uri
        addBookSrtName = queryDisplayName(contentResolver, uri)
    }

    val pickBookFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        keepReadPermission(context, uri)
        addBookFolderUri = uri
        addBookFolderName = queryTreeDisplayName(context, contentResolver, uri)
        addBookAudioUri = null
        addBookAudioName = null
        addBookSrtUri = null
        addBookSrtName = null
        persistImportState()
    }

    val pickDictionaryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        keepReadPermission(context, uri)
        val displayName = queryDisplayName(contentResolver, uri)
        val uriValue = uri.toString()
        if (dictionaryRefs.any { it.uri == uriValue }) {
            dictionaryError = "该辞典已导入，请勿重复导入。"
            return@rememberLauncherForActivityResult
        }
        val cacheKey = buildDictionaryCacheKey(uriValue, displayName)

        scope.launch {
            dictionaryLoading = true
            updateDictionaryProgress(DictionaryImportProgress(stage = "Scanning archive", current = 0, total = 0))
            dictionaryError = null
            val parseResult = withContext(Dispatchers.IO) {
                runCatching {
                    importDictionaryToSqlite(
                        context = context,
                        contentResolver = contentResolver,
                        uri = uri,
                        displayName = displayName,
                        cacheKey = cacheKey
                    ) { progress ->
                        scope.launch(Dispatchers.Main.immediate) {
                            updateDictionaryProgress(progress)
                        }
                    }
                }
            }
            dictionaryLoading = false
            val parsedDictionary = parseResult.getOrNull()
            if (parsedDictionary != null) {
                val duplicateByName = loadedDictionaries.any {
                    it.name.equals(parsedDictionary.name, ignoreCase = true) &&
                        it.entryCount > 0 &&
                        parsedDictionary.entryCount > 0 &&
                        it.entryCount == parsedDictionary.entryCount
                }
                if (duplicateByName) {
                    parsedDictionary.cacheKey
                        .takeIf { it.isNotBlank() }
                        ?.let { key ->
                            scope.launch(Dispatchers.IO) { deleteDictionaryFromSqlite(context, key) }
                        }
                    dictionaryError = "检测到重复辞典：${parsedDictionary.name}"
                    clearDictionaryProgress()
                    return@launch
                }
                loadedDictionaries = loadedDictionaries + parsedDictionary
                dictionaryRefs = (dictionaryRefs + PersistedDictionaryRef(
                    uri = uriValue,
                    name = displayName,
                    cacheKey = cacheKey
                ))
                    .distinctBy { it.uri }
                persistImportState()
                clearDictionaryProgress()
                if (lookupQuery.isNotBlank()) {
                    triggerLookupCandidates(listOf(lookupQuery))
                }
            } else {
                val error = parseResult.exceptionOrNull()
                dictionaryError = error?.message ?: "Failed to import dictionary"
                clearDictionaryProgress()
            }
        }
    }

    val activeCue = remember(positionMs, srtCues) { findCueAtTime(srtCues, positionMs) }
    val selectedReaderBook = remember(readerBooks, selectedBookId) {
        readerBooks.firstOrNull { it.id == selectedBookId }
    }

    val selectedEntry = remember(lookupResults, selectedEntryKey) {
        lookupResults.firstOrNull { entryStableKey(it.entry) == selectedEntryKey }?.entry
    }
    val mainPopupSelectedEntry = remember(mainLookupPopupResults, mainLookupPopupSelectedKey) {
        mainLookupPopupResults.firstOrNull { entryStableKey(it.entry) == mainLookupPopupSelectedKey }?.entry
    }
    val dictionaryCssByName = remember(loadedDictionaries) {
        loadedDictionaries.associate { it.name to it.stylesCss }
    }
    val dictionaryPriorityByName = remember(loadedDictionaries) {
        loadedDictionaries.mapIndexed { index, dictionary -> dictionary.name to index }.toMap()
    }
    val groupedLookupResults = remember(lookupResults, dictionaryCssByName, dictionaryPriorityByName) {
        groupLookupResultsByTerm(
            results = lookupResults,
            dictionaryCssByName = dictionaryCssByName,
            dictionaryPriorityByName = dictionaryPriorityByName
        )
    }
    val groupedMainLookupPopupResults = remember(mainLookupPopupResults, dictionaryCssByName, dictionaryPriorityByName) {
        groupLookupResultsByTerm(
            results = mainLookupPopupResults,
            dictionaryCssByName = dictionaryCssByName,
            dictionaryPriorityByName = dictionaryPriorityByName
        ).take(10)
    }
    val dictionarySpecificVariableChoices = remember(loadedDictionaries) {
        loadedDictionaries.map { "{single-glossary-${it.name}}" }.distinct()
    }
    val fieldVariableChoices = remember(dictionarySpecificVariableChoices) {
        (FIELD_VARIABLE_CHOICES + dictionarySpecificVariableChoices).distinct()
    }
    val selectedEntryDictionaryCss = selectedEntry?.dictionary?.let(dictionaryCssByName::get)
    val mainPopupSelectedEntryCss = mainPopupSelectedEntry?.dictionary?.let(dictionaryCssByName::get)

    val sliderValue = when {
        durationMs <= 0L -> 0f
        dragProgress != null -> dragProgress!!.coerceIn(0f, 1f)
        else -> (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    val dictionaryCount = loadedDictionaries.size
    val totalDictionaryEntries = loadedDictionaries.sumOf { it.entryCount }
    val cueLookupTokens = remember(activeCue?.text) {
        activeCue?.let { tokenizeLookupTerms(it.text).take(12) } ?: emptyList()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            when {
                activeSection == MiningSection.MAIN -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FloatingActionButton(
                            onClick = { refreshBookshelfFromFolder() },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text("刷新")
                        }
                        FloatingActionButton(
                            onClick = {
                                addBookDialogVisible = true
                            }
                        ) {
                            Text("+书籍")
                        }
                    }
                }

                activeSection == MiningSection.COLLECTIONS && collectedCues.isNotEmpty() -> {
                    FloatingActionButton(
                        onClick = { clearCollectionsConfirmVisible = true }
                    ) {
                        Text("清空")
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = activeSection == MiningSection.MAIN,
                    onClick = { activeSection = MiningSection.MAIN },
                    icon = { Text("主") },
                    label = { Text("主页") }
                )
                NavigationBarItem(
                    selected = activeSection == MiningSection.DICTIONARY,
                    onClick = { activeSection = MiningSection.DICTIONARY },
                    icon = { Text("词") },
                    label = { Text("辞典查询") }
                )
                NavigationBarItem(
                    selected = activeSection == MiningSection.COLLECTIONS,
                    onClick = { activeSection = MiningSection.COLLECTIONS },
                    icon = { Text("藏") },
                    label = { Text("收藏") }
                )
                NavigationBarItem(
                    selected = activeSection == MiningSection.SETTINGS,
                    onClick = { activeSection = MiningSection.SETTINGS },
                    icon = { Text("设") },
                    label = { Text("设置") }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("⑨Player", style = MaterialTheme.typography.titleLarge)

            if (activeSection == MiningSection.MAIN) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("我的书架", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (selectedBookIds.isNotEmpty()) {
                            OutlinedButton(onClick = { requestDeleteSelectedBooks() }) {
                                Text("删除(${selectedBookIds.size})")
                            }
                            OutlinedButton(onClick = { clearBookSelection() }) {
                                Text("取消选择")
                            }
                        }
                        OutlinedButton(
                            onClick = { activeSection = MiningSection.SETTINGS }
                        ) {
                            Text("设置")
                        }
                        OutlinedButton(
                            onClick = {
                                homeLibraryView = if (homeLibraryView == HomeLibraryView.BOOKSHELF) {
                                    HomeLibraryView.LIST
                                } else {
                                    HomeLibraryView.BOOKSHELF
                                }
                                persistImportState()
                            }
                        ) {
                            Text(
                                if (homeLibraryView == HomeLibraryView.BOOKSHELF) {
                                    "切换到列表"
                                } else {
                                    "切换到书架"
                                }
                            )
                        }
                    }
                }

                if (readerBooks.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("还没有书籍。")
                            Text("点击 +书籍，先选择有声书文件夹，再选择音频/m4b（SRT可选）。")
                        }
                    }
                } else if (homeLibraryView == HomeLibraryView.BOOKSHELF) {
                    readerBooks.chunked(2).forEach { rowBooks ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowBooks.forEach { book ->
                                val selected = selectedBookId == book.id
                                val multiSelected = selectedBookIds.contains(book.id)
                                val playbackSnapshot = readerBookPlaybackSnapshots[book.id]
                                val playbackPercent = playbackSnapshot?.progressPercent ?: 0
                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .combinedClickable(
                                            onClick = {
                                                if (selectedBookIds.isNotEmpty()) {
                                                    toggleBookSelection(book.id)
                                                } else {
                                                    openReaderBook(book, persist = true)
                                                }
                                            },
                                            onLongClick = {
                                                toggleBookSelection(book.id)
                                            }
                                        )
                                ) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        if (book.coverUri != null) {
                                            BookCoverThumbnail(
                                                coverUri = book.coverUri,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(132.dp)
                                            )
                                        }
                                        Text(book.title, style = MaterialTheme.typography.titleSmall)
                                        Text(book.audioName, maxLines = 1)
                                        Text("${book.cues.size} 条字幕")
                                        Text("$playbackPercent%")
                                        if (multiSelected) {
                                            Text("已选中", color = MaterialTheme.colorScheme.primary)
                                        }
                                        if (selected) {
                                            Text("已打开", color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                            if (rowBooks.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    readerBooks.forEach { book ->
                        val selected = selectedBookId == book.id
                        val multiSelected = selectedBookIds.contains(book.id)
                        val playbackSnapshot = readerBookPlaybackSnapshots[book.id]
                        val playbackPercent = playbackSnapshot?.progressPercent ?: 0
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (selectedBookIds.isNotEmpty()) {
                                            toggleBookSelection(book.id)
                                        } else {
                                            openReaderBook(book, persist = true)
                                        }
                                    },
                                    onLongClick = {
                                        toggleBookSelection(book.id)
                                    }
                                )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (book.coverUri != null) {
                                    BookCoverThumbnail(
                                        coverUri = book.coverUri,
                                        modifier = Modifier
                                            .width(72.dp)
                                            .height(96.dp)
                                    )
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(book.title, style = MaterialTheme.typography.titleSmall)
                                    Text("${book.cues.size} 条字幕")
                                    Text("$playbackPercent%")
                                    if (multiSelected) {
                                        Text("已选中", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                if (selectedBookIds.isEmpty()) {
                                    OutlinedButton(onClick = { openReaderBook(book, persist = true) }) {
                                        Text(if (selected) "已打开" else "打开")
                                    }
                                }
                            }
                        }
                    }
                }

                if (srtLoading || srtError != null || exportStatus != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (srtLoading) Text("正在解析 SRT...")
                            if (srtError != null) Text("SRT error: $srtError", color = MaterialTheme.colorScheme.error)
                            if (exportStatus != null) Text(exportStatus!!)
                        }
                    }
                }
            }

            if (activeSection == MiningSection.DICTIONARY) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("辞典与查询", style = MaterialTheme.typography.titleMedium)
                        Text("辞典：$dictionaryCount（共 $totalDictionaryEntries 条）")
                        Text("查词器：hoshidicts")
                        if (dictionaryLoading) {
                            Text(dictionaryProgressText ?: "正在导入辞典...")
                            if (dictionaryProgressValue != null) {
                                LinearProgressIndicator(
                                    progress = { dictionaryProgressValue ?: 0f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                        if (dictionaryError != null) {
                            Text("辞典错误：$dictionaryError", color = MaterialTheme.colorScheme.error)
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { pickDictionaryLauncher.launch(arrayOf("application/zip", "*/*")) }) {
                                Text("导入辞典")
                            }
                            OutlinedButton(
                                onClick = { showDictionaryManager = !showDictionaryManager }
                            ) {
                                Text(if (showDictionaryManager) "隐藏辞典列表" else "查看辞典列表")
                            }
                        }

                        if (showDictionaryManager) {
                            if (dictionaryRefs.isEmpty()) {
                                Text("暂无已导入辞典。")
                            } else {
                                dictionaryRefs.forEachIndexed { index, ref ->
                                    val loaded = loadedDictionaries.getOrNull(index)
                                    val countText = loaded?.entryCount?.let { "$it 条" } ?: "未加载"
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(ref.name.ifBlank { "辞典 ${index + 1}" })
                                            Text(countText)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(
                                                    onClick = { moveDictionary(index, index - 1) },
                                                    enabled = !dictionaryLoading && index > 0
                                                ) {
                                                    Text("↑")
                                                }
                                                OutlinedButton(
                                                    onClick = { moveDictionary(index, index + 1) },
                                                    enabled = !dictionaryLoading && index < dictionaryRefs.lastIndex
                                                ) {
                                                    Text("↓")
                                                }
                                                OutlinedButton(
                                                    onClick = { removeDictionaryAt(index) },
                                                    enabled = !dictionaryLoading
                                                ) {
                                                    Text("删除")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = lookupQuery,
                            onValueChange = { lookupQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("查词") },
                            singleLine = true
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { triggerLookupCandidates(listOf(lookupQuery)) },
                                enabled = loadedDictionaries.isNotEmpty() && lookupQuery.isNotBlank()
                            ) {
                                Text("查询")
                            }
                            OutlinedButton(
                                onClick = {
                                    lookupQuery = ""
                                    lookupResults = emptyList()
                                    selectedEntryKey = null
                                }
                            ) {
                                Text("清空")
                            }
                        }

                        if (lookupLoading) {
                            Text("正在查询辞典...")
                        } else if (lookupQuery.isNotBlank() && groupedLookupResults.isEmpty()) {
                            Text("无查询结果。")
                        }

                        groupedLookupResults.forEach { groupedResult ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val readingText = groupedResult.reading?.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("${groupedResult.term}$readingText")
                                        OutlinedButton(
                                            onClick = {
                                                val primary = groupedResult.dictionaries.firstOrNull() ?: return@OutlinedButton
                                                selectedEntryKey = entryStableKey(primary.entry)
                                                exportLookupGroupToAnki(
                                                    groupedResult = groupedResult,
                                                    sourceCue = null,
                                                    lookupTitle = lookupQuery
                                                )
                                            }
                                        ) {
                                            Text("+")
                                        }
                                    }
                                    groupedResult.dictionaries.forEach { dictionaryGroup ->
                                        Card(modifier = Modifier.fillMaxWidth()) {
                                            Column(
                                                modifier = Modifier.padding(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                val frequencyBadges = parseMetaBadges(dictionaryGroup.frequency, "词频")
                                                if (frequencyBadges.isNotEmpty()) {
                                                    MetaBadgeRow(
                                                        badges = frequencyBadges,
                                                        labelColor = Color(0xFFDDF0DD),
                                                        labelTextColor = Color(0xFF305E33)
                                                    )
                                                }
                                                val pitchBadges = parsePitchBadges(
                                                    raw = dictionaryGroup.pitch,
                                                    reading = groupedResult.reading,
                                                    defaultLabel = "音调"
                                                )
                                                if (pitchBadges.isNotEmpty()) {
                                                    MetaBadgeRow(
                                                        badges = pitchBadges,
                                                        labelColor = Color(0xFFE7DDF8),
                                                        labelTextColor = Color(0xFF4E3A74)
                                                    )
                                                }

                                                dictionaryGroup.definitions.forEach { definition ->
                                                    Card(modifier = Modifier.fillMaxWidth()) {
                                                        Column(
                                                            modifier = Modifier.padding(8.dp),
                                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                                        ) {
                                                            RichDefinitionView(
                                                                definition = definition,
                                                                dictionaryName = null,
                                                                dictionaryCss = dictionaryGroup.css
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (activeSection == MiningSection.COLLECTIONS) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("收藏", style = MaterialTheme.typography.titleMedium)
                        Text("共 ${collectedCues.size} 条")
                        if (collectedCues.isEmpty()) {
                            Text("暂无收藏。")
                        } else {
                            collectedCues.forEach { item ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(item.text)
                                        Text(
                                            formatCollectedCueMeta(item),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = { playCollectedCue(item) }
                                            ) {
                                                Text("播放")
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    openMainLookupPopup(
                                                        SubtitleCue(
                                                            startMs = item.startMs,
                                                            endMs = item.endMs,
                                                            text = item.text
                                                        ),
                                                        sourceBookTitle = item.bookTitle
                                                    )
                                                },
                                                enabled = loadedDictionaries.isNotEmpty()
                                            ) {
                                                Text("查词")
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    removeBookReaderCollectedCue(context, item.id)
                                                    refreshCollectedCues()
                                                }
                                            ) {
                                                Text("删除")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (activeSection == MiningSection.SETTINGS) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 1.dp,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "设置",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                        SettingsListItem(
                            title = "Anki",
                            onClick = { context.startActivity(Intent(context, AnkiSettingsActivity::class.java)) }
                        )
                        SettingsListItem(
                            title = "有声书",
                            onClick = { context.startActivity(Intent(context, AudiobookSettingsActivity::class.java)) }
                        )
                        SettingsListItem(
                            title = "控制模式",
                            onClick = { context.startActivity(Intent(context, ControlModeSettingsActivity::class.java)) }
                        )
                        SettingsListItem(
                            title = "手柄",
                            onClick = { context.startActivity(Intent(context, ControllerSettingsActivity::class.java)) }
                        )
                        SettingsListItem(
                            title = "手柄蓝牙",
                            onClick = { context.startActivity(Intent(context, ControllerBluetoothSettingsActivity::class.java)) },
                            showDivider = false
                        )
                    }
                }
            }
        }

        if (importGuideVisible) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("导入方式") },
                text = { Text("首次安装需要选择导入方式：是否自动把书移动到有声书文件夹。") },
                dismissButton = {
                    OutlinedButton(
                        onClick = {
                            autoMoveToAudiobookFolder = false
                            importOnboardingCompleted = true
                            importGuideVisible = false
                            persistImportState()
                        }
                    ) {
                        Text("不自动移动")
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            autoMoveToAudiobookFolder = true
                            importOnboardingCompleted = true
                            importGuideVisible = false
                            persistImportState()
                        }
                    ) {
                        Text("自动移动")
                    }
                }
            )
        }

        if (clearCollectionsConfirmVisible) {
            AlertDialog(
                onDismissRequest = { clearCollectionsConfirmVisible = false },
                title = { Text("清空收藏") },
                text = { Text("确认删除全部收藏吗？此操作不可恢复。") },
                dismissButton = {
                    TextButton(onClick = { clearCollectionsConfirmVisible = false }) {
                        Text("取消")
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            clearBookReaderCollectedCues(context)
                            refreshCollectedCues()
                            clearCollectionsConfirmVisible = false
                        }
                    ) {
                        Text("确认删除")
                    }
                }
            )
        }

        if (deleteBooksConfirmVisible) {
            AlertDialog(
                onDismissRequest = { deleteBooksConfirmVisible = false },
                title = { Text("删除书籍") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("确认删除所选书籍，并删除对应文件夹吗？")
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Checkbox(
                                checked = deleteBooksDontAskAgain,
                                onCheckedChange = { checked ->
                                    deleteBooksDontAskAgain = checked
                                }
                            )
                            Text("下次不再提醒")
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            deleteBooksConfirmVisible = false
                            pendingDeleteBookIds = emptySet()
                        }
                    ) {
                        Text("取消")
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (deleteBooksDontAskAgain) {
                                skipDeleteBookConfirm = true
                                saveSkipDeleteBookConfirm(context, true)
                            }
                            val removeIds = pendingDeleteBookIds
                            deleteBooksConfirmVisible = false
                            pendingDeleteBookIds = emptySet()
                            deleteSelectedBooks(removeIds)
                        }
                    ) {
                        Text("确认删除")
                    }
                }
            )
        }

        if (addBookDialogVisible) {
            AlertDialog(
                onDismissRequest = { addBookDialogVisible = false },
                title = { Text("添加书籍") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("有声书文件夹: ${addBookFolderName ?: "未选择"}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { pickBookFolderLauncher.launch(null) }
                            ) {
                                Text("选择文件夹")
                            }
                            OutlinedButton(
                                onClick = {
                                    addBookFolderUri = null
                                    addBookFolderName = null
                                    addBookAudioUri = null
                                    addBookAudioName = null
                                    addBookSrtUri = null
                                    addBookSrtName = null
                                    persistImportState()
                                },
                                enabled = addBookFolderUri != null
                            ) {
                                Text("清除")
                            }
                        }

                        Text("音频/m4b: ${addBookAudioName ?: "未选择"}")
                        OutlinedButton(
                            onClick = {
                                pickBookAudioLauncher.launch(
                                        arrayOf(
                                        "audio/*",
                                        "audio/mp4",
                                        "audio/x-m4a",
                                        "audio/m4a",
                                        "audio/x-m4b",
                                        "audio/m4b",
                                        "application/mp4"
                                    )
                                )
                            },
                            enabled = addBookFolderUri != null || !autoMoveToAudiobookFolder
                        ) {
                            Text("选择音频/m4b")
                        }
                        Text("SRT（可选）: ${addBookSrtName ?: "未选择"}")
                        OutlinedButton(
                            onClick = {
                                pickBookSrtLauncher.launch(
                                    arrayOf("application/x-subrip", "text/plain", "*/*")
                                )
                            },
                            enabled = addBookFolderUri != null || !autoMoveToAudiobookFolder
                        ) {
                            Text("选择字幕 SRT（可选）")
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { addBookDialogVisible = false }) {
                        Text("取消")
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { confirmAddBookFromDialog() },
                        enabled = (addBookFolderUri != null || !autoMoveToAudiobookFolder) &&
                            addBookAudioUri != null &&
                            !srtLoading
                    ) {
                        Text("确认")
                    }
                }
            )
        }

        if (mainLookupPopupVisible) {
            Popup(
                alignment = Alignment.TopCenter,
                onDismissRequest = {
                    mainLookupPopupVisible = false
                    mainLookupPopupCue = null
                    mainLookupPopupSelectedRange = null
                    mainLookupPopupAudioUri = null
                },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    clippingEnabled = false
                )
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.96f)
                        .padding(top = 72.dp),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val popupCue = mainLookupPopupCue
                        if (popupCue != null) {
                            ClickableText(
                                text = buildMainHighlightedText(popupCue.text, mainLookupPopupSelectedRange),
                                style = MaterialTheme.typography.bodyLarge,
                                onClick = { offset -> triggerMainCueLookup(popupCue, offset) }
                            )
                            Text(
                                "${formatTime(popupCue.startMs)} - ${formatTime(popupCue.endMs)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (mainLookupAnkiStatus != null) {
                            Text(mainLookupAnkiStatus!!)
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 520.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (mainLookupPopupLoading) {
                                Text("查询中...")
                            }
                            if (mainLookupPopupError != null) {
                                Text(
                                    "查询错误：$mainLookupPopupError",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            if (!mainLookupPopupLoading && groupedMainLookupPopupResults.isEmpty()) {
                                Text("无查询结果。")
                            }
                            groupedMainLookupPopupResults.forEach { groupedResult ->
                                val reading = groupedResult.reading?.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("${groupedResult.term}$reading")
                                            OutlinedButton(
                                                onClick = {
                                                    val primary = groupedResult.dictionaries.firstOrNull() ?: return@OutlinedButton
                                                    selectedEntryKey = entryStableKey(primary.entry)
                                                    mainLookupPopupSelectedKey = selectedEntryKey
                                                    exportLookupGroupToAnki(
                                                        groupedResult = groupedResult,
                                                        sourceCue = popupCue,
                                                        lookupTitle = mainLookupPopupTitle
                                                    )
                                                }
                                            ) {
                                                Text("+")
                                            }
                                        }
                                        groupedResult.dictionaries.forEach { dictionaryGroup ->
                                            Card(modifier = Modifier.fillMaxWidth()) {
                                                Column(
                                                    modifier = Modifier.padding(8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    val frequencyBadges = parseMetaBadges(dictionaryGroup.frequency, "词频")
                                                    if (frequencyBadges.isNotEmpty()) {
                                                        MetaBadgeRow(
                                                            badges = frequencyBadges,
                                                            labelColor = Color(0xFFDDF0DD),
                                                            labelTextColor = Color(0xFF305E33)
                                                        )
                                                    }
                                                    val pitchBadges = parsePitchBadges(
                                                        raw = dictionaryGroup.pitch,
                                                        reading = groupedResult.reading,
                                                        defaultLabel = "音调"
                                                    )
                                                    if (pitchBadges.isNotEmpty()) {
                                                        MetaBadgeRow(
                                                            badges = pitchBadges,
                                                            labelColor = Color(0xFFE7DDF8),
                                                            labelTextColor = Color(0xFF4E3A74)
                                                        )
                                                    }

                                                    dictionaryGroup.definitions.forEach { definition ->
                                                        Card(modifier = Modifier.fillMaxWidth()) {
                                                            Column(
                                                                modifier = Modifier.padding(8.dp),
                                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                                            ) {
                                                                RichDefinitionView(
                                                                    definition = definition,
                                                                    dictionaryName = null,
                                                                    dictionaryCss = dictionaryGroup.css
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    mainLookupPopupVisible = false
                                    mainLookupPopupCue = null
                                    mainLookupPopupSelectedRange = null
                                    mainLookupPopupAudioUri = null
                                    mainLookupAnkiStatus = null
                                }
                            ) {
                                Text("关闭")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsListItem(
    title: String,
    onClick: () -> Unit,
    showDivider: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(">")
        }
        if (showDivider) {
            HorizontalDivider(modifier = Modifier.padding(start = 12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldVariableDropdown(
    fieldName: String,
    selectedValue: String,
    options: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember(fieldName) { mutableStateOf(false) }
    val displayText = selectedValue.ifBlank { "(Empty)" }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            label = { Text(fieldName) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { choice ->
                val text = choice.ifBlank { "(Empty)" }
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(choice)
                    }
                )
            }
        }
    }
}

@Composable
private fun RichDefinitionView(
    definition: String,
    indexLabel: String = "",
    dictionaryName: String? = null,
    dictionaryCss: String? = null
) {
    val trimmed = definition.trim()
    if (trimmed.isBlank()) return

    if (looksLikeHtmlDefinition(trimmed)) {
        val html = buildDefinitionHtml(
            definitionHtml = trimmed,
            indexLabel = indexLabel,
            dictionaryName = dictionaryName,
            dictionaryCss = dictionaryCss
        )
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(0x00000000)
                    settings.javaScriptEnabled = false
                    settings.domStorageEnabled = false
                    settings.loadsImagesAutomatically = true
                    webViewClient = WebViewClient()
                }
            },
            update = { webView ->
                webView.loadDataWithBaseURL(
                    null,
                    html,
                    "text/html",
                    "utf-8",
                    null
                )
            }
        )
    } else {
        Text("$indexLabel${trimmed}")
    }
}

private fun buildDefinitionHtml(
    definitionHtml: String,
    indexLabel: String,
    dictionaryName: String?,
    dictionaryCss: String?
): String {
    val prefix = if (indexLabel.isBlank()) "" else "<div>${escapeHtmlText(indexLabel)}</div>"
    val dictionaryLabel = dictionaryName?.trim().orEmpty()
    val wrappedBody = if (dictionaryLabel.isBlank()) {
        definitionHtml
    } else {
        val safeDictionaryLabel = escapeHtmlText(dictionaryLabel)
        val safeDictionaryAttr = escapeHtmlAttributeForHtml(dictionaryLabel)
        """
        <div class="yomitan-glossary">
            <ol>
                <li data-dictionary="$safeDictionaryAttr">
                    <i>($safeDictionaryLabel)</i> $definitionHtml
                </li>
            </ol>
        </div>
        """.trimIndent()
    }
    val customCss = buildScopedDictionaryCss(
        rawCss = dictionaryCss.orEmpty(),
        dictionaryName = dictionaryLabel
    )
    return """
        <html>
        <head>
            <meta charset="utf-8"/>
            <style>
                body { margin: 0; padding: 0; font-size: 14px; line-height: 1.4; color: #1f1f1f; }
                img { max-width: 100%; height: auto; }
                .yomitan-glossary { text-align: left; }
                .yomitan-glossary ol { margin: 0; padding-left: 1.1em; }
                .yomitan-glossary li { margin: 0; }
                $customCss
            </style>
        </head>
        <body>
            $prefix
            $wrappedBody
        </body>
        </html>
    """.trimIndent()
}

private fun looksLikeHtmlDefinition(text: String): Boolean {
    return Regex("<\\s*/?\\s*[a-zA-Z][^>]*>").containsMatchIn(text)
}

private fun escapeHtmlText(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private fun escapeHtmlAttributeForHtml(value: String): String {
    return escapeHtmlText(value).replace("\"", "&quot;")
}

private fun buildScopedDictionaryCss(rawCss: String, dictionaryName: String): String {
    val trimmed = rawCss.trim()
    if (trimmed.isBlank()) return ""
    if (dictionaryName.isBlank()) return trimmed

    val dictionaryAttr = escapeCssString(dictionaryName)
    val prefix = ".yomitan-glossary [data-dictionary=\"$dictionaryAttr\"]"
    val ruleRegex = Regex("([^{}]+)\\{([^}]*)\\}")
    val scoped = ruleRegex.replace(trimmed) { match ->
        val selectors = match.groupValues[1]
        val body = match.groupValues[2]
        if (selectors.trim().startsWith("@")) return@replace match.value
        val prefixed = selectors
            .split(',')
            .map { selector ->
                val s = selector.trim()
                if (s.isBlank()) s else "$prefix $s"
            }
            .joinToString(", ")
        "$prefixed {$body}"
    }
    return buildString {
        appendLine(scoped)
        appendLine(trimmed)
    }.trim()
}

private fun escapeCssString(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

private data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

@Composable
private fun BookCoverThumbnail(
    coverUri: Uri,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                adjustViewBounds = true
            }
        },
        update = { view ->
            view.setImageURI(coverUri)
        }
    )
}

private fun resolveEmbeddedCoverUriForM4b(
    context: Context,
    audioUri: Uri,
    audioDisplayName: String
): Uri? {
    val isM4b = audioDisplayName.endsWith(".m4b", ignoreCase = true) ||
        audioUri.toString().contains(".m4b", ignoreCase = true)
    if (!isM4b) return null

    val coverDir = File(File(context.filesDir, "books"), "covers")
    if (!coverDir.exists()) {
        coverDir.mkdirs()
    }

    val cacheKey = buildDictionaryCacheKey(audioUri.toString(), audioDisplayName)
    val existing = coverDir.listFiles()
        ?.firstOrNull { it.nameWithoutExtension == "cover-$cacheKey" }
    if (existing != null && existing.exists() && existing.length() > 0L) {
        return Uri.fromFile(existing)
    }

    val retriever = MediaMetadataRetriever()
    return try {
        if (audioUri.scheme.equals("file", ignoreCase = true)) {
            val path = audioUri.path ?: return null
            retriever.setDataSource(path)
        } else {
            retriever.setDataSource(context, audioUri)
        }
        val picture = retriever.embeddedPicture ?: return null
        val ext = if (
            picture.size >= 4 &&
            picture[0] == 0x89.toByte() &&
            picture[1] == 0x50.toByte() &&
            picture[2] == 0x4E.toByte() &&
            picture[3] == 0x47.toByte()
        ) {
            "png"
        } else {
            "jpg"
        }
        val outFile = File(coverDir, "cover-$cacheKey.$ext")
        outFile.writeBytes(picture)
        if (outFile.exists() && outFile.length() > 0L) {
            Uri.fromFile(outFile)
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun openBookInputStream(contentResolver: ContentResolver, uri: Uri): InputStream? {
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme == "file") {
        return runCatching {
            val path = uri.path ?: return@runCatching null
            File(path).inputStream()
        }.getOrNull()
    }

    val direct = runCatching { contentResolver.openInputStream(uri) }.getOrNull()
    if (direct != null) return direct

    val pfd = runCatching { contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
    return ParcelFileDescriptor.AutoCloseInputStream(pfd)
}

private data class RelocatedBookFiles(
    val folderName: String,
    val audioUri: Uri,
    val audioName: String,
    val srtUri: Uri?,
    val srtName: String?,
    val moveWarnings: List<String>
)

private data class CopiedBookFile(
    val uri: Uri,
    val displayName: String
)

private data class FolderBookCandidate(
    val folderName: String,
    val audioUri: Uri,
    val audioName: String,
    val srtUri: Uri?,
    val srtName: String?
)

private data class FolderBookScanResult(
    val books: List<FolderBookCandidate>,
    val skippedFolders: List<String>
)

private fun relocateSelectedBookFilesToAudFolder(
    context: Context,
    contentResolver: ContentResolver,
    rootFolderUri: Uri,
    audioSourceUri: Uri,
    audioSourceName: String?,
    srtSourceUri: Uri?,
    srtSourceName: String?
): RelocatedBookFiles {
    val root = DocumentFile.fromTreeUri(context, rootFolderUri)
        ?: error("无法访问有声书文件夹。")
    if (!root.isDirectory) error("所选有声书路径不是文件夹。")

    val audFolder = createNextAudFolder(root)
    val audioDisplayName = audioSourceName?.trim().takeUnless { it.isNullOrBlank() }
        ?: queryDisplayName(contentResolver, audioSourceUri)

    val copiedAudio = copyUriIntoFolder(
        contentResolver = contentResolver,
        parentFolder = audFolder,
        sourceUri = audioSourceUri,
        preferredDisplayName = audioDisplayName
    )
    val copiedSrt = srtSourceUri?.let { sourceUri ->
        val srtDisplayName = srtSourceName?.trim().takeUnless { it.isNullOrBlank() }
            ?: queryDisplayName(contentResolver, sourceUri)
        copyUriIntoFolder(
            contentResolver = contentResolver,
            parentFolder = audFolder,
            sourceUri = sourceUri,
            preferredDisplayName = srtDisplayName
        )
    }

    val warnings = mutableListOf<String>()
    if (!deleteSourceUri(context, contentResolver, audioSourceUri)) {
        warnings += "音频原文件删除失败，已保留原文件。"
    }
    if (srtSourceUri != null && !deleteSourceUri(context, contentResolver, srtSourceUri)) {
        warnings += "SRT原文件删除失败，已保留原文件。"
    }

    return RelocatedBookFiles(
        folderName = audFolder.name?.ifBlank { "Aud" } ?: "Aud",
        audioUri = copiedAudio.uri,
        audioName = copiedAudio.displayName,
        srtUri = copiedSrt?.uri,
        srtName = copiedSrt?.displayName,
        moveWarnings = warnings
    )
}

private fun createNextAudFolder(rootFolder: DocumentFile): DocumentFile {
    val pattern = Regex("^Aud(\\d+)$", RegexOption.IGNORE_CASE)
    var next = rootFolder.listFiles()
        .filter { it.isDirectory }
        .mapNotNull { dir ->
            dir.name
                ?.trim()
                ?.let { pattern.matchEntire(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        }
        .maxOrNull()
        ?.plus(1)
        ?: 1

    repeat(1000) {
        val candidate = "Aud$next"
        if (rootFolder.findFile(candidate) == null) {
            return rootFolder.createDirectory(candidate) ?: error("无法创建文件夹：$candidate")
        }
        next += 1
    }
    error("无法创建新的 Aud 文件夹。")
}

private fun copyUriIntoFolder(
    contentResolver: ContentResolver,
    parentFolder: DocumentFile,
    sourceUri: Uri,
    preferredDisplayName: String
): CopiedBookFile {
    val normalizedName = preferredDisplayName.trim().ifBlank { "file" }
    val uniqueName = resolveUniqueDocumentName(parentFolder, normalizedName)
    val sourceMime = contentResolver.getType(sourceUri)
    val targetMime = resolveMimeTypeForDocument(uniqueName, sourceMime)
    val created = parentFolder.createFile(targetMime, uniqueName)
        ?: error("无法在目标文件夹创建文件：$uniqueName")

    val input = openBookInputStream(contentResolver, sourceUri)
        ?: error("无法读取源文件：$normalizedName")
    input.use { src ->
        contentResolver.openOutputStream(created.uri, "w")?.use { output ->
            src.copyTo(output)
        } ?: error("无法写入目标文件：$uniqueName")
    }

    return CopiedBookFile(
        uri = created.uri,
        displayName = uniqueName
    )
}

private fun resolveUniqueDocumentName(folder: DocumentFile, originalName: String): String {
    val cleaned = originalName.trim().ifBlank { "file" }
    if (folder.findFile(cleaned) == null) return cleaned

    val dot = cleaned.lastIndexOf('.')
    val hasExtension = dot > 0 && dot < cleaned.lastIndex
    val base = if (hasExtension) cleaned.substring(0, dot) else cleaned
    val ext = if (hasExtension) cleaned.substring(dot) else ""

    var index = 2
    while (index <= 9999) {
        val candidate = "$base ($index)$ext"
        if (folder.findFile(candidate) == null) return candidate
        index += 1
    }
    return "$base-${System.currentTimeMillis()}$ext"
}

private fun scanBooksFromRootFolder(
    context: Context,
    contentResolver: ContentResolver,
    rootFolderUri: Uri
): FolderBookScanResult {
    val root = DocumentFile.fromTreeUri(context, rootFolderUri)
        ?: error("无法访问有声书文件夹。")
    if (!root.isDirectory) error("所选有声书路径不是文件夹。")

    val books = mutableListOf<FolderBookCandidate>()
    val skippedFolders = mutableListOf<String>()

    root.listFiles()
        .filter { it.isDirectory }
        .sortedBy { it.name?.lowercase(Locale.ROOT) ?: it.uri.toString() }
        .forEach { folder ->
            val folderName = folder.name?.trim().takeUnless { it.isNullOrBlank() }
                ?: "Untitled"
            val files = folder.listFiles()
                .filter { it.isFile }
                .sortedBy { it.name?.lowercase(Locale.ROOT) ?: it.uri.toString() }

            val audioFile = files.firstOrNull { isAudioDocumentFile(it) }
            val srtFile = files.firstOrNull { isSrtDocumentFile(it) }
            if (audioFile == null) {
                skippedFolders += folderName
                return@forEach
            }

            val audioName = audioFile.name?.trim().takeUnless { it.isNullOrBlank() }
                ?: queryDisplayName(contentResolver, audioFile.uri)
            val srtName = srtFile?.name?.trim()?.takeUnless { it.isNullOrBlank() }
                ?: srtFile?.let { queryDisplayName(contentResolver, it.uri) }

            books += FolderBookCandidate(
                folderName = folderName,
                audioUri = audioFile.uri,
                audioName = audioName,
                srtUri = srtFile?.uri,
                srtName = srtName
            )
        }

    return FolderBookScanResult(
        books = books,
        skippedFolders = skippedFolders
    )
}

private fun isAudioDocumentFile(file: DocumentFile): Boolean {
    val name = file.name?.trim().orEmpty()
    val mime = file.type?.lowercase(Locale.ROOT).orEmpty()
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        .trim()
        .lowercase(Locale.ROOT)
    if (extension in setOf("m4b", "m4a", "mp3", "aac", "flac", "wav", "ogg", "opus")) {
        return true
    }
    return mime.startsWith("audio/") || mime == "application/mp4"
}

private fun isSrtDocumentFile(file: DocumentFile): Boolean {
    val name = file.name?.trim().orEmpty().lowercase(Locale.ROOT)
    val mime = file.type?.lowercase(Locale.ROOT).orEmpty()
    if (name.endsWith(".srt")) return true
    return mime == "application/x-subrip" || mime.contains("subrip") || mime == "text/plain"
}

private fun resolveMimeTypeForDocument(fileName: String, sourceMime: String?): String {
    if (!sourceMime.isNullOrBlank() && sourceMime != "application/octet-stream") {
        return sourceMime
    }
    val ext = fileName.substringAfterLast('.', missingDelimiterValue = "")
        .trim()
        .lowercase(Locale.ROOT)
    return when (ext) {
        "m4b", "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "srt" -> "application/x-subrip"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}

private fun deleteSourceUri(
    context: Context,
    contentResolver: ContentResolver,
    uri: Uri
): Boolean {
    if (uri.scheme.equals("file", ignoreCase = true)) {
        val path = uri.path ?: return false
        return runCatching { File(path).delete() }.getOrDefault(false)
    }

    val documentDeleted = runCatching {
        DocumentFile.fromSingleUri(context, uri)?.delete()
    }.getOrNull()
    if (documentDeleted == true) return true

    return runCatching {
        contentResolver.delete(uri, null, null) > 0
    }.getOrDefault(false)
}

private fun loadSkipDeleteBookConfirm(context: Context): Boolean {
    return context
        .getSharedPreferences(BOOK_DELETE_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_SKIP_DELETE_CONFIRM, false)
}

private fun saveSkipDeleteBookConfirm(context: Context, skip: Boolean) {
    context
        .getSharedPreferences(BOOK_DELETE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_SKIP_DELETE_CONFIRM, skip)
        .apply()
}

private fun deleteBookStorageFolder(context: Context, book: ReaderBook): Boolean {
    return deleteAudParentFolder(context, book.audioUri)
}

private fun deleteAudParentFolder(context: Context, fileUri: Uri): Boolean {
    if (fileUri.scheme.equals("file", ignoreCase = true)) {
        val file = fileUri.path?.let { File(it) } ?: return false
        val parent = file.parentFile ?: return false
        if (!Regex("^Aud\\d+$", RegexOption.IGNORE_CASE).matches(parent.name)) return false
        return runCatching { parent.deleteRecursively() }.getOrDefault(false)
    }

    if (!DocumentsContract.isDocumentUri(context, fileUri)) return false
    val documentId = runCatching { DocumentsContract.getDocumentId(fileUri) }.getOrNull() ?: return false
    val parentDocumentId = documentId.substringBeforeLast('/', missingDelimiterValue = "")
    if (parentDocumentId.isBlank()) return false
    val parentName = parentDocumentId.substringAfterLast('/')
    if (!Regex("^Aud\\d+$", RegexOption.IGNORE_CASE).matches(parentName)) return false

    val parentUri = runCatching {
        DocumentsContract.buildDocumentUriUsingTree(fileUri, parentDocumentId)
    }.getOrNull() ?: return false

    val parentDocument = DocumentFile.fromSingleUri(context, parentUri) ?: return false
    return runCatching { parentDocument.delete() }.getOrDefault(false)
}

private fun keepReadPermission(context: Context, uri: Uri) {
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

private fun queryDisplayName(contentResolver: ContentResolver, uri: Uri): String {
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

private fun queryTreeDisplayName(
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

private fun buildBookTitle(audioName: String, srtName: String?): String {
    val audioBase = audioName.substringBeforeLast('.').trim().ifBlank { audioName.trim() }
    if (audioBase.isNotBlank()) return audioBase
    val srtBase = srtName?.let { name ->
        name.substringBeforeLast('.').trim().ifBlank { name.trim() }
    }.orEmpty()
    if (srtBase.isNotBlank()) return srtBase
    return "Untitled Book"
}

private fun buildReaderBookPlaybackKeyMain(book: ReaderBook): String {
    val raw = "title=${book.title}|audio=${book.audioUri}|srt=${book.srtUri?.toString().orEmpty()}"
    return buildDictionaryCacheKey(raw, book.title.ifBlank { "book" })
}

private suspend fun loadReaderBookPlaybackSnapshotsForBooks(
    context: Context,
    books: List<ReaderBook>
): Map<String, BookReaderPlaybackSnapshot> {
    return withContext(Dispatchers.IO) {
        books.associate { book ->
            val playbackKey = buildReaderBookPlaybackKeyMain(book)
            val stored = loadBookReaderPlaybackSnapshot(context, playbackKey)
            val normalized = if (stored.durationMs > 0L) {
                stored
            } else {
                val fallbackDuration = book.cues.lastOrNull()?.endMs?.coerceAtLeast(0L) ?: 0L
                if (fallbackDuration > 0L) {
                    stored.copy(durationMs = fallbackDuration)
                } else {
                    stored
                }
            }
            book.id to normalized
        }
    }
}

private fun parseSrt(contentResolver: ContentResolver, uri: Uri): List<SubtitleCue> {
    val rawText = openBookInputStream(contentResolver, uri)?.use { input ->
        input.bufferedReader(Charsets.UTF_8).readText()
    } ?: error("Unable to read SRT file")

    val normalizedText = rawText
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')

    val blocks = normalizedText.split(Regex("\n\\s*\n"))
    val cues = mutableListOf<SubtitleCue>()

    blocks.forEach { block ->
        val lines = block.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return@forEach

        val timingLineIndex = if (lines.first().all { it.isDigit() } && lines.size >= 2) 1 else 0
        val timingLine = lines.getOrNull(timingLineIndex) ?: return@forEach
        if (!timingLine.contains("-->")) return@forEach

        val parts = timingLine.split("-->")
        if (parts.size < 2) return@forEach

        val start = parseSrtTimestamp(parts[0].trim()) ?: return@forEach
        val endToken = parts[1].trim().substringBefore(' ')
        val end = parseSrtTimestamp(endToken) ?: return@forEach

        val cueTextRaw = lines.drop(timingLineIndex + 1).joinToString("\n").trim()
        val cueText = Html.fromHtml(cueTextRaw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        if (cueText.isBlank()) return@forEach

        cues += SubtitleCue(startMs = start, endMs = end, text = cueText)
    }

    if (cues.isEmpty()) error("No valid subtitle cues found in SRT")
    return cues.sortedBy { it.startMs }
}

private fun parseSrtTimestamp(raw: String): Long? {
    val normalized = raw.trim().replace(',', '.')
    val parts = normalized.split(':')
    if (parts.size != 3) return null

    val hour = parts[0].toLongOrNull() ?: return null
    val minute = parts[1].toLongOrNull() ?: return null

    val secParts = parts[2].split('.')
    if (secParts.isEmpty()) return null

    val second = secParts[0].toLongOrNull() ?: return null
    val millisecondPart = secParts.getOrNull(1) ?: "0"
    val millisecond = millisecondPart.padEnd(3, '0').take(3).toLongOrNull() ?: return null

    return (((hour * 60 + minute) * 60) + second) * 1000 + millisecond
}

private fun findCueAtTime(cues: List<SubtitleCue>, timeMs: Long): SubtitleCue? {
    if (cues.isEmpty()) return null
    var low = 0
    var high = cues.lastIndex
    var candidateIndex = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val cue = cues[mid]
        if (cue.startMs <= timeMs) {
            candidateIndex = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    if (candidateIndex < 0) return null
    return cues[candidateIndex]
}

private fun addLookupDefinitionToAnkiMain(
    context: Context,
    cue: SubtitleCue,
    audioUri: Uri?,
    bookTitle: String?,
    entry: DictionaryEntry,
    definition: String,
    dictionaryCss: String?,
    glossarySections: List<String> = emptyList(),
    popupSelectionText: String? = null
) {
    if (!isAnkiInstalled(context)) error("AnkiDroid is not installed.")
    if (!hasAnkiReadWritePermission(context)) {
        error("Anki permission not granted. Authorize in 设置 > Anki.")
    }

    val persistedConfig = loadPersistedAnkiConfig(context)
    if (persistedConfig.modelName.isBlank()) {
        error("No Anki model configured. Open 设置 > Anki.")
    }

    val catalog = loadAnkiCatalog(context)
    val model = catalog.models.firstOrNull { it.name == persistedConfig.modelName }
        ?: error("Configured model not found: ${persistedConfig.modelName}")

    val templates = model.fields.associateWith { field ->
        persistedConfig.fieldTemplates[field].orEmpty()
    }
    if (!hasAnyAnkiFieldTemplate(templates)) {
        error("All field variables are empty. Configure at least one marker in 设置 > Anki.")
    }
    if (audioUri != null && !templates.values.any { templateUsesVariableMain(it, "cut-audio") }) {
        error("Current model templates do not include {cut-audio}. Set audio field to {cut-audio} in 设置 > Anki.")
    }

    val normalizedGlossarySections = glossarySections
        .map { it.trim() }
        .filter { it.isNotBlank() }
    val cardDefinitions = if (normalizedGlossarySections.isNotEmpty()) {
        normalizedGlossarySections
    } else {
        listOf(definition)
    }
    val cardDictionaryName = if (normalizedGlossarySections.isNotEmpty()) null else entry.dictionary
    val cardDictionaryCss = dictionaryCss

    val config = AnkiExportConfig(
        deckName = persistedConfig.deckName.ifBlank { "Default" },
        modelName = model.name,
        fieldTemplates = templates,
        tags = parseAnkiTags(persistedConfig.tags)
    )

    val card = MinedCard(
        word = entry.term,
        popupSelectionText = popupSelectionText,
        sentence = cue.text,
        bookTitle = bookTitle,
        reading = entry.reading,
        definitions = cardDefinitions,
        dictionaryName = cardDictionaryName,
        dictionaryCss = cardDictionaryCss,
        pitch = entry.pitch,
        frequency = entry.frequency,
        cueStartMs = cue.startMs,
        cueEndMs = cue.endMs,
        audioUri = audioUri,
        audioTagOnly = true,
        requireCueAudioClip = audioUri != null
    )

    exportToAnkiDroidApi(context, card, config)
}

private fun templateUsesVariableMain(template: String, variableName: String): Boolean {
    if (template.isBlank()) return false
    val target = variableName
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]"), "")
    if (target.isBlank()) return false

    return Regex("\\{([^{}]+)\\}")
        .findAll(template)
        .any { match ->
            match.groupValues
                .getOrNull(1)
                .orEmpty()
                .trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]"), "") == target
        }
}

private fun buildMinedCard(
    cue: SubtitleCue,
    selectedEntry: DictionaryEntry?,
    lookupQuery: String,
    audioUri: Uri?,
    dictionaryCss: String?
): MinedCard {
    val fallbackWord = extractLookupToken(lookupQuery).ifBlank { extractLookupToken(cue.text) }
    val word = selectedEntry?.term?.ifBlank { null } ?: fallbackWord.ifBlank { "Unknown" }
    return MinedCard(
        word = word,
        sentence = cue.text,
        reading = selectedEntry?.reading,
        definitions = selectedEntry?.definitions ?: emptyList(),
        dictionaryName = selectedEntry?.dictionary,
        dictionaryCss = dictionaryCss,
        pitch = selectedEntry?.pitch,
        frequency = selectedEntry?.frequency,
        cueStartMs = cue.startMs,
        cueEndMs = cue.endMs,
        audioUri = audioUri
    )
}

private fun buildMainHighlightedText(text: String, selectedRange: IntRange?): AnnotatedString {
    return buildAnnotatedString {
        append(text)
        val range = selectedRange ?: return@buildAnnotatedString
        val start = range.first.coerceIn(0, text.length)
        val endExclusive = (range.last + 1).coerceIn(start, text.length)
        if (endExclusive <= start) return@buildAnnotatedString
        addStyle(SpanStyle(background = Color(0x66A0A0A0)), start, endExclusive)
    }
}

private fun findMainLookupSelection(
    text: String,
    offset: Int
): LookupScanSelection? {
    return selectLookupScanText(
        text = text,
        charOffset = offset
    )
}

private fun isAppProcessInForeground(): Boolean {
    val processInfo = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(processInfo)
    return processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
        processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun formatCollectedCueMeta(item: BookReaderCollectedCue): String {
    val chapterLabel = item.chapterTitle?.takeIf { it.isNotBlank() }
        ?: item.chapterIndex?.let { "第${it + 1}章" }
    val startLabel = if (item.chapterStartOffsetMs != null) {
        formatTime(item.chapterStartOffsetMs)
    } else {
        formatTime(item.startMs)
    }
    val endLabel = if (item.chapterEndOffsetMs != null) {
        formatTime(item.chapterEndOffsetMs)
    } else {
        formatTime(item.endMs)
    }
    return buildString {
        append(item.bookTitle)
        if (!chapterLabel.isNullOrBlank()) {
            append(" | ")
            append(chapterLabel)
        }
        append(" | ")
        append(startLabel)
        append(" - ")
        append(endLabel)
    }
}

private data class MetaBadge(val label: String, val value: String)

private fun parseMetaBadges(raw: String?, defaultLabel: String): List<MetaBadge> {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return emptyList()
    return text
        .split(';')
        .mapNotNull { segment ->
            val part = segment.trim()
            if (part.isBlank()) return@mapNotNull null
            val separator = part.indexOf(':')
            if (separator > 0 && separator < part.lastIndex) {
                val label = part.substring(0, separator).trim()
                val value = part.substring(separator + 1).trim()
                if (label.isBlank() || value.isBlank()) {
                    MetaBadge(defaultLabel, part)
                } else {
                    MetaBadge(label, value)
                }
            } else {
                MetaBadge(defaultLabel, part)
            }
        }
}

private fun parsePitchBadges(raw: String?, reading: String?, defaultLabel: String): List<MetaBadge> {
    return parseMetaBadges(raw, defaultLabel).flatMap { badge ->
        val values = extractPitchNumbers(badge.value)
        if (values.isEmpty()) {
            listOf(badge.copy(value = formatPitchBadgeValue(badge.value, reading)))
        } else {
            values.map { number -> badge.copy(value = formatPitchBadgeValue(number, reading)) }
        }
    }
}

private fun formatPitchBadgeValue(value: String, reading: String?): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return trimmed
    val hasBracket = trimmed.contains('[') || trimmed.contains(']')
    val core = if (hasBracket) trimmed else "[$trimmed]"
    val readingDisplay = reading
        ?.takeIf { it.isNotBlank() }
        ?.let(::formatPitchReadingWithOverline)
        .orEmpty()
    return if (readingDisplay.isNotBlank()) "$readingDisplay $core" else core
}

private fun extractPitchNumbers(raw: String): List<String> {
    return Regex("-?\\d+")
        .findAll(raw)
        .map { it.value }
        .toList()
}

private fun formatPitchReadingWithOverline(reading: String): String {
    val source = reading.trim()
    if (source.isBlank()) return source
    return buildString(source.length * 2) {
        source.forEach { ch ->
            append(ch)
            if (!ch.isWhitespace()) {
                append('\u0305')
            }
        }
    }
}

@Composable
private fun MetaBadgeRow(
    badges: List<MetaBadge>,
    labelColor: Color,
    labelTextColor: Color
) {
    if (badges.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        badges.forEach { badge ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Surface(
                    color = labelColor,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = badge.label,
                        color = labelTextColor,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Surface(
                    color = Color(0xFFF2F2F2),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = badge.value,
                        color = Color.Black,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}









