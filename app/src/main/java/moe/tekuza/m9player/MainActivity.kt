package moe.tekuza.m9player

import android.app.Activity
import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.kyant.taglib.TagLib
import moe.tekuza.m9player.ui.theme.TsetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private var floatingOverlayStartJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedAppLanguage(this)
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
        val overlayEnabled = settings.floatingOverlayEnabled || settings.floatingOverlaySubtitleEnabled
        if (isChangingConfigurations || !overlayEnabled || !BookReaderFloatingBridge.isPlaying()) return

        floatingOverlayStartJob = lifecycleScope.launch {
            delay(150L)
            if (
                !isAppProcessInForeground() &&
                run {
                    val refreshed = loadAudiobookSettingsConfig(this@MainActivity)
                    refreshed.floatingOverlayEnabled || refreshed.floatingOverlaySubtitleEnabled
                } &&
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

private val miningSectionSaver = Saver<MiningSection, String>(
    save = { it.name },
    restore = { runCatching { MiningSection.valueOf(it) }.getOrDefault(MiningSection.MAIN) }
)

private val FIELD_VARIABLE_CHOICES = listOf(
    "",
    "{audio}",
    "{cut-audio}",
    "{expression}",
    "{reading}",
    "{furigana-plain}",
    "{glossary}",
    "{glossary-first}",
    "{single-glossary}",
    "{definitions}",
    "{popup-selection-text}",
    "{sentence}",
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
    "{book-title}",
    "{search-query}"
)

internal data class ReaderBook(
    val id: String,
    val title: String,
    val audioUri: Uri,
    val audioName: String,
    val srtUri: Uri?,
    val srtName: String?,
    val coverUri: Uri?
)

private data class ReturnedBookProgress(
    val audioUri: String,
    val positionMs: Long,
    val durationMs: Long
)

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ReaderSyncScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
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
    var dictionaryLookupAutoPlayedKey by remember { mutableStateOf<String?>(null) }
    val dictionaryLookupCollapsedSections = remember { mutableStateMapOf<String, Boolean>() }
    val mainLookupCollapsedSections = remember { mutableStateMapOf<String, Boolean>() }

    var exportStatus by remember { mutableStateOf<String?>(null) }
    var pendingAnkiCard by remember { mutableStateOf<MinedCard?>(null) }
    var awaitingExternalAnkiPermission by remember { mutableStateOf(false) }
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
    var activeSection by rememberSaveable(stateSaver = miningSectionSaver) {
        mutableStateOf(MiningSection.MAIN)
    }
    var languageDialogVisible by remember { mutableStateOf(false) }
    var selectedAppLanguage by remember { mutableStateOf(loadAppLanguageOption(context)) }
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
    var mainLookupAutoPlayNonce by remember { mutableStateOf(0L) }
    var mainLookupAutoPlayedKey by remember { mutableStateOf<String?>(null) }
    var audiobookSettings by remember { mutableStateOf(loadAudiobookSettingsConfig(context)) }
    var versionTapCount by remember { mutableStateOf(0) }
    var showVersionEasterGif by remember { mutableStateOf(false) }

    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    var pendingCollectionPlayMs by remember { mutableStateOf<Long?>(null) }
    var pendingCollectionStopMs by remember { mutableStateOf<Long?>(null) }
    var collectionPlayRequestNonce by remember { mutableStateOf(0L) }

    BackHandler {
        when {
            mainLookupPopupVisible -> mainLookupPopupVisible = false
            addBookDialogVisible -> addBookDialogVisible = false
            importGuideVisible -> importGuideVisible = false
            clearCollectionsConfirmVisible -> clearCollectionsConfirmVisible = false
            deleteBooksConfirmVisible -> deleteBooksConfirmVisible = false
            activeSection != MiningSection.MAIN -> activeSection = MiningSection.MAIN
            BookReaderFloatingBridge.currentAudioUri() != null -> activity?.moveTaskToBack(true)
            else -> activity?.finish()
        }
    }

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
                audiobookSettings = loadAudiobookSettingsConfig(context)
                scope.launch {
                    var loadedSnapshots = loadReaderBookPlaybackSnapshotsForBooks(
                        context = context,
                        books = readerBooks
                    )
                    val returnedProgress = consumeReturnedBookProgress(activity?.intent)
                    if (returnedProgress != null && returnedProgress.durationMs > 0L) {
                        val targetBook = readerBooks.firstOrNull {
                            it.audioUri.toString() == returnedProgress.audioUri
                        }
                        if (targetBook != null) {
                            val immediate = BookReaderPlaybackSnapshot(
                                positionMs = returnedProgress.positionMs.coerceAtLeast(0L),
                                durationMs = returnedProgress.durationMs.coerceAtLeast(0L)
                            )
                            loadedSnapshots = loadedSnapshots + (targetBook.id to immediate)
                            withContext(Dispatchers.IO) {
                                saveBookReaderPlaybackPosition(
                                    context = context,
                                    bookKey = buildReaderBookPlaybackKey(targetBook),
                                    positionMs = immediate.positionMs,
                                    durationMs = immediate.durationMs
                                )
                            }
                        }
                    }
                    readerBookPlaybackSnapshots = loadedSnapshots
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
    val requestExternalAnkiPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            awaitingExternalAnkiPermission = false
            ankiPermissionGranted = hasAnkiReadWritePermission(context)
            if (ankiPermissionGranted) {
                exportStatus = "Anki access permission granted."
            } else {
                pendingAnkiCard = null
                exportStatus = ankiAvailabilityUiMessage(context, requirePermission = true)
                    ?: "Anki access permission was denied."
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
            config = buildPersistedAnkiConfig(
                deckName = ankiDeckName,
                modelName = ankiModelName,
                tags = ankiTagsInput,
                fieldTemplates = ankiFieldTemplates.toMap()
            )
        )
    }

    fun syncTemplatesWithModelFields(fields: List<String>, clearExisting: Boolean = false) {
        ankiModelFields = fields
        syncAnkiFieldTemplates(
            target = ankiFieldTemplates,
            fields = fields,
            clearExisting = clearExisting
        )
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
        ankiAvailabilityUiMessage(context, requirePermission = true)?.let { availabilityMessage ->
            ankiError = availabilityMessage
            ankiDecks = emptyList()
            ankiModels = emptyList()
            ankiModelFields = emptyList()
            return
        }

        scope.launch {
            ankiLoading = true
            ankiError = null
            val result = withContext(Dispatchers.IO) {
                loadResolvedAnkiCatalogResult(
                    context = context,
                    currentDeckName = ankiDeckName,
                    currentModelName = ankiModelName,
                    defaultDeckName = "Default"
                )
            }
            ankiLoading = false

            when (result) {
                is AnkiCatalogLoadResult.Success -> {
                    val resolvedCatalog = result.data
                    ankiDecks = resolvedCatalog.decks
                    ankiModels = resolvedCatalog.models
                    ankiDeckName = resolvedCatalog.selection.deckName
                    selectAnkiModel(resolvedCatalog.selection.modelName)
                    persistAnkiConfig()
                }
                is AnkiCatalogLoadResult.Failure -> {
                    ankiError = result.message
                }
            }
        }
    }

    fun currentAnkiExportConfigOrNull(): AnkiExportConfig? {
        return buildCurrentAnkiExportConfigOrNull(
            deckName = ankiDeckName,
            modelName = ankiModelName,
            tags = ankiTagsInput,
            models = ankiModels,
            fieldTemplates = ankiFieldTemplates
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
        srtDisplayName: String?
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
            coverUri = coverUri
        )
    }

    fun activateReaderBook(book: ReaderBook, persist: Boolean = true) {
        selectedBookId = book.id
        audioUri = book.audioUri
        audioName = book.audioName
        srtUri = book.srtUri
        srtName = book.srtName
        srtCues = emptyList()
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
        val deleteResults = deletingBooks.map { book ->
            deleteBookStorage(
                context = context,
                contentResolver = contentResolver,
                book = book,
                audiobookFolderUri = addBookFolderUri
            )
        }
        val folderDeleteFailures = deleteResults.count { it.folderDeleteAttempted && !it.folderDeleteSucceeded }
        val fileDeleteFailures = deleteResults.sumOf { it.fileDeleteFailures }
        val deletedFolders = deleteResults.count { it.folderDeleteSucceeded }
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
        exportStatus = if (folderDeleteFailures == 0 && fileDeleteFailures == 0) {
            if (deletedFolders > 0) {
                context.getString(R.string.status_books_deleted_with_folder, removeIds.size)
            } else {
                context.getString(R.string.status_books_deleted_files_only, removeIds.size)
            }
        } else {
            context.getString(
                R.string.status_books_deleted_with_failures,
                removeIds.size,
                fileDeleteFailures,
                folderDeleteFailures
            )
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
            exportStatus = context.getString(R.string.collection_play_missing_book)
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
            exportStatus = context.getString(R.string.status_pick_audiobook_folder_first)
            return
        }
        val pickedAudio = addBookAudioUri
        if (pickedAudio == null) {
            exportStatus = context.getString(R.string.status_pick_audio_first)
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
                            rootFolderUri = selectedFolder ?: error(context.getString(R.string.error_audiobook_folder_required)),
                            audioSourceUri = pickedAudio,
                            audioSourceName = pickedAudioName,
                            srtSourceUri = pickedSrt,
                            srtSourceName = pickedSrtName
                        )
                        val book = buildReaderBook(
                            audio = relocated.audioUri,
                            audioDisplayName = relocated.audioName,
                            srt = relocated.srtUri,
                            srtDisplayName = relocated.srtName
                        )
                        val warning = relocated.moveWarnings.takeIf { it.isNotEmpty() }?.joinToString(" ")
                        Triple(book, relocated.folderName, warning)
                    } else {
                        val book = buildReaderBook(
                            audio = pickedAudio,
                            audioDisplayName = pickedAudioName,
                            srt = pickedSrt,
                            srtDisplayName = pickedSrtName
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
                    append(context.getString(R.string.status_book_added, book.title))
                    if (!folderName.isNullOrBlank()) {
                        append(' ')
                        append(context.getString(R.string.status_book_saved_to, folderName))
                    } else {
                        append(' ')
                        append(context.getString(R.string.status_book_keep_original))
                    }
                    if (!warning.isNullOrBlank()) {
                        append(' ')
                        append(warning)
                    }
                }
            }.onFailure { error ->
                srtError = error.message ?: context.getString(R.string.status_add_book_failed)
                exportStatus = context.getString(R.string.status_add_book_failed_short)
            }
        }
    }

    fun refreshBookshelfFromFolder() {
        val selectedFolder = addBookFolderUri
        if (selectedFolder == null) {
            exportStatus = context.getString(R.string.status_pick_audiobook_folder_first)
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
                    scanResult.books.forEach { candidate ->
                        runCatching {
                            buildReaderBook(
                                audio = candidate.audioUri,
                                audioDisplayName = candidate.audioName,
                                srt = candidate.srtUri,
                                srtDisplayName = candidate.srtName
                            )
                        }.onSuccess { refreshedBooks += it }
                    }
                    refreshedBooks to scanResult.skippedFolders
                }
            }
            srtLoading = false
            refreshResult.onSuccess { (books, skippedFolders) ->
                if (books.isEmpty()) {
                    srtError = context.getString(R.string.status_no_books_found_to_import)
                    exportStatus = context.getString(R.string.status_refresh_done_zero)
                    return@onSuccess
                }
                readerBooks = books
                clearBookSelection()
                val selected = books.firstOrNull { it.id == previousSelectedId } ?: books.first()
                activateReaderBook(selected, persist = false)
                selectedBookId = selected.id
                persistImportState()

                exportStatus = buildString {
                    append(context.getString(R.string.status_refresh_done, books.size))
                    if (skippedFolders.isNotEmpty()) {
                        append(' ')
                        append(context.getString(R.string.status_refresh_skipped_missing_audio, skippedFolders.size))
                    }
                }
            }.onFailure { error ->
                srtError = error.message ?: context.getString(R.string.status_refresh_failed)
                exportStatus = context.getString(R.string.status_refresh_failed_short)
            }
        }
    }

    fun tryExportCardToAnki(card: MinedCard) {
        when (detectAnkiAvailability(context, requirePermission = true)) {
            AnkiAvailabilityState.PERMISSION_MISSING -> {
                pendingAnkiCard = card
                exportStatus = "Requesting Anki access permission..."
                val launchedIntent = createAnkiPermissionRequestIntent(context)
                if (launchedIntent != null) {
                    awaitingExternalAnkiPermission = true
                    requestExternalAnkiPermissionLauncher.launch(launchedIntent)
                } else {
                    requestAnkiPermissionLauncher.launch(ANKI_READ_WRITE_PERMISSION)
                }
                return
            }
            AnkiAvailabilityState.NOT_INSTALLED,
            AnkiAvailabilityState.API_UNAVAILABLE -> {
                exportStatus = ankiAvailabilityErrorMessage(context, requirePermission = true)
                    ?: context.getString(R.string.error_anki_not_installed)
                return
            }
            AnkiAvailabilityState.READY -> Unit
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
            exportStatus = context.getString(R.string.status_anki_fields_empty)
            return
        }
        exportStatus = ankiExportResultMessage(context, exportToAnkiDroidApiResult(context, card, config))
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

    DisposableEffect(context, awaitingExternalAnkiPermission) {
        val activity = context as? ComponentActivity
        val observer = activity?.let {
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && awaitingExternalAnkiPermission) {
                    awaitingExternalAnkiPermission = false
                    ankiPermissionGranted = hasAnkiReadWritePermission(context)
                    if (ankiPermissionGranted) {
                        exportStatus = "Anki access permission granted."
                    }
                }
            }
        }
        if (observer != null) {
            activity.lifecycle.addObserver(observer)
        }
        onDispose {
            if (observer != null) {
                activity.lifecycle.removeObserver(observer)
            }
        }
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
                            val rebuilt = buildReaderBook(
                                audio = audio,
                                audioDisplayName = savedBook.audioName,
                                srt = srt,
                                srtDisplayName = savedBook.srtName
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
                    exportStatus = context.getString(R.string.status_restore_books_failed_count, failedBooks.size)
                }
            }.onFailure { error ->
                readerBooks = emptyList()
                selectedBookId = null
                audioUri = null
                audioName = null
                srtUri = null
                srtName = null
                srtCues = emptyList()
                srtError = context.getString(R.string.status_restore_book_failed, error.message ?: "unknown error")
                exportStatus = context.getString(R.string.status_restore_bookshelf_failed)
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
                        Triple(restoredAudioRaw, restoredAudioName, Pair(restoredSrtRaw, restoredSrtName))
                    }
                }
                srtLoading = false
                restoreResult.onSuccess { restored ->
                    val (audio, audioDisplay, srtPair) = restored
                    val (srt, srtDisplay) = srtPair
                    audioUri = audio
                    audioName = audioDisplay
                    srtUri = srt
                    srtName = srtDisplay
                    srtCues = emptyList()
                    val restoredBook = buildReaderBook(
                        audio = audio,
                        audioDisplayName = audioDisplay,
                        srt = srt,
                        srtDisplayName = srtDisplay
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
            exportStatus = context.getString(R.string.bookreader_lookup_no_dict)
            return
        }

        val selection = findMainLookupSelection(cue.text, offset)
        val selectionRange = selection?.range
        mainLookupPopupSelectedRange = null
        mainLookupPopupCue = cue

        val selectedToken = selection?.text?.trim()?.takeIf { it.isNotBlank() }
        val candidates = listOfNotNull(selectedToken)

        if (candidates.isEmpty()) return

        mainLookupPopupVisible = true
        mainLookupAutoPlayNonce += 1L
        mainLookupAutoPlayedKey = null
        mainLookupPopupTitle = candidates.firstOrNull() ?: selectedToken.orEmpty()
        mainLookupPopupResults = emptyList()
        mainLookupPopupSelectedKey = null
        mainLookupPopupError = null
        mainLookupPopupLoading = true
        mainLookupPopupCue = cue
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
                mainLookupPopupError = error.message ?: context.getString(R.string.bookreader_lookup_failed)
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
            exportStatus = context.getString(R.string.bookreader_lookup_no_dict)
            return
        }

        val candidates = normalizeLookupCandidates(rawCandidates)
        if (candidates.isEmpty()) return

        mainLookupPopupVisible = true
        mainLookupAutoPlayNonce += 1L
        mainLookupAutoPlayedKey = null
        mainLookupPopupTitle = candidates.first()
        mainLookupPopupResults = emptyList()
        mainLookupPopupSelectedKey = null
        mainLookupPopupError = null
        mainLookupPopupLoading = true
        mainLookupPopupCue = null
        mainLookupPopupSelectedRange = null
        mainLookupPopupAudioUri = null
        triggerLookupCandidates(candidates) { result ->
            result.onSuccess { hits ->
                mainLookupPopupResults = hits
                mainLookupPopupSelectedKey = hits.firstOrNull()?.let { entryStableKey(it.entry) }
                mainLookupPopupLoading = false
            }.onFailure { error ->
                mainLookupPopupError = error.message ?: context.getString(R.string.bookreader_lookup_failed)
                mainLookupPopupLoading = false
            }
        }
    }

    fun closeMainLookupPopup() {
        mainLookupPopupVisible = false
        mainLookupPopupCue = null
        mainLookupPopupSelectedRange = null
        mainLookupPopupAudioUri = null
    }

    fun exportLookupGroupToAnki(
        groupedResult: GroupedLookupResult,
        sourceCue: SubtitleCue?,
        lookupTitle: String
    ) {
        val dictionaryGroup = groupedResult.dictionaries.firstOrNull() ?: return
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
        val exportDefinitions = dictionaryGroup.definitions
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val exportDefinitionHtml = exportDefinitions.joinToString("<br>").ifBlank { groupedResult.term }
        val settingsSnapshot = audiobookSettings
        closeMainLookupPopup()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val preparedLookupAudio = prepareLookupAudioForAnkiExport(
                        context = context,
                        term = groupedResult.term,
                        reading = groupedResult.reading,
                        settings = settingsSnapshot
                    )
                    try {
                        addLookupDefinitionToAnkiMain(
                            context = context,
                            cue = cue,
                            audioUri = mainLookupPopupAudioUri,
                            lookupAudioUri = preparedLookupAudio?.uri,
                            bookTitle = readerBooks.firstOrNull { it.audioUri == mainLookupPopupAudioUri }?.title,
                            entry = dictionaryGroup.entry,
                            definition = exportDefinitionHtml,
                            dictionaryCss = dictionaryGroup.css,
                            popupSelectionText = popupSelectionText
                        )
                    } finally {
                        preparedLookupAudio?.cleanup?.invoke()
                    }
                }
            }
            val status = result.fold(
                onSuccess = {
                    Toast.makeText(
                        context,
                        context.getString(R.string.anki_toast_added),
                        Toast.LENGTH_SHORT
                    ).show()
                    ""
                },
                onFailure = {
                    val message = it.message ?: context.getString(R.string.bookreader_anki_export_failed)
                    Toast.makeText(context, message.take(200), Toast.LENGTH_LONG).show()
                    message
                }
            )
            exportStatus = status.ifBlank { null }
        }
    }

    fun playLookupGroupAudio(groupedResult: GroupedLookupResult) {
        playLookupAudioForTerm(
            context = context,
            term = groupedResult.term,
            reading = groupedResult.reading,
            settings = audiobookSettings
        ) { error ->
            exportStatus = error
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

    val pickDictionaryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val selectedUris = uris.distinctBy { it.toString() }
        if (selectedUris.isEmpty()) return@rememberLauncherForActivityResult

        scope.launch {
            dictionaryLoading = true
            dictionaryError = null
            var nextLoadedDictionaries = loadedDictionaries
            var nextDictionaryRefs = dictionaryRefs
            val importErrors = mutableListOf<String>()

            selectedUris.forEachIndexed { index, uri ->
                keepReadPermission(context, uri)
                val displayName = queryDisplayName(contentResolver, uri)
                val uriValue = uri.toString()
                if (nextDictionaryRefs.any { it.uri == uriValue }) {
                    importErrors += context.getString(R.string.status_duplicate_dictionary)
                    return@forEachIndexed
                }
                val cacheKey = buildDictionaryCacheKey(uriValue, displayName)

                updateDictionaryProgress(
                    DictionaryImportProgress(
                        stage = "Importing ${index + 1}/${selectedUris.size}: $displayName",
                        current = 0,
                        total = 0
                    )
                )

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
                                updateDictionaryProgress(
                                    progress.copy(stage = "${progress.stage} (${index + 1}/${selectedUris.size})")
                                )
                            }
                        }
                    }
                }

                val parsedDictionary = parseResult.getOrNull()
                if (parsedDictionary == null) {
                    importErrors += parseResult.exceptionOrNull()?.message ?: "Failed to import dictionary"
                    return@forEachIndexed
                }

                val duplicateByName = nextLoadedDictionaries.any {
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
                    importErrors += context.getString(R.string.status_duplicate_dictionary_detected, parsedDictionary.name)
                    return@forEachIndexed
                }

                nextLoadedDictionaries = nextLoadedDictionaries + parsedDictionary
                nextDictionaryRefs = (nextDictionaryRefs + PersistedDictionaryRef(
                    uri = uriValue,
                    name = displayName,
                    cacheKey = cacheKey
                )).distinctBy { it.uri }
            }

            loadedDictionaries = nextLoadedDictionaries
            dictionaryRefs = nextDictionaryRefs
            persistImportState()
            clearDictionaryProgress()
            dictionaryLoading = false
            dictionaryError = importErrors.takeIf { it.isNotEmpty() }?.joinToString("\n")
            if (lookupQuery.isNotBlank()) {
                triggerLookupCandidates(listOf(lookupQuery))
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
    LaunchedEffect(
        activeSection,
        lookupLoading,
        lookupQuery,
        groupedLookupResults,
        audiobookSettings.lookupPlaybackAudioEnabled,
        audiobookSettings.lookupPlaybackAudioAutoPlay
    ) {
        if (activeSection != MiningSection.DICTIONARY || lookupLoading) return@LaunchedEffect
        if (!audiobookSettings.lookupPlaybackAudioEnabled || !audiobookSettings.lookupPlaybackAudioAutoPlay) {
            return@LaunchedEffect
        }
        val normalizedQuery = lookupQuery.trim()
        if (normalizedQuery.isBlank()) {
            dictionaryLookupAutoPlayedKey = null
            return@LaunchedEffect
        }
        val target = groupedLookupResults.firstOrNull() ?: return@LaunchedEffect
        val key = "$normalizedQuery|${target.term}|${target.reading.orEmpty()}"
        if (dictionaryLookupAutoPlayedKey == key) return@LaunchedEffect
        dictionaryLookupAutoPlayedKey = key
        playLookupGroupAudio(target)
    }
    val groupedMainLookupPopupResults = remember(mainLookupPopupResults, dictionaryCssByName, dictionaryPriorityByName) {
        groupLookupResultsByTerm(
            results = mainLookupPopupResults,
            dictionaryCssByName = dictionaryCssByName,
            dictionaryPriorityByName = dictionaryPriorityByName
        ).take(10)
    }
    LaunchedEffect(
        mainLookupPopupVisible,
        mainLookupPopupLoading,
        mainLookupPopupError,
        groupedMainLookupPopupResults,
        audiobookSettings.lookupPlaybackAudioEnabled,
        audiobookSettings.lookupPlaybackAudioAutoPlay,
        mainLookupAutoPlayNonce
    ) {
        if (!mainLookupPopupVisible || mainLookupPopupLoading || mainLookupPopupError != null) return@LaunchedEffect
        if (!audiobookSettings.lookupPlaybackAudioEnabled || !audiobookSettings.lookupPlaybackAudioAutoPlay) {
            return@LaunchedEffect
        }
        val target = groupedMainLookupPopupResults.firstOrNull() ?: return@LaunchedEffect
        val key = "${mainLookupAutoPlayNonce}|${target.term}|${target.reading.orEmpty()}"
        if (mainLookupAutoPlayedKey == key) return@LaunchedEffect
        mainLookupAutoPlayedKey = key
        playLookupGroupAudio(target)
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
                            Text(stringResource(R.string.home_refresh))
                        }
                        FloatingActionButton(
                            onClick = {
                                addBookDialogVisible = true
                            }
                        ) {
                            Text(stringResource(R.string.home_add_book))
                        }
                    }
                }

                activeSection == MiningSection.COLLECTIONS && collectedCues.isNotEmpty() -> {
                    FloatingActionButton(
                        onClick = { clearCollectionsConfirmVisible = true }
                    ) {
                        Text(stringResource(R.string.home_clear))
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = activeSection == MiningSection.MAIN,
                    onClick = { activeSection = MiningSection.MAIN },
                    icon = { Text("本") },
                    label = { Text(stringResource(R.string.nav_home)) }
                )
                NavigationBarItem(
                    selected = activeSection == MiningSection.DICTIONARY,
                    onClick = { activeSection = MiningSection.DICTIONARY },
                    icon = { Text("辞") },
                    label = { Text(stringResource(R.string.nav_dictionary)) }
                )
                NavigationBarItem(
                    selected = activeSection == MiningSection.COLLECTIONS,
                    onClick = { activeSection = MiningSection.COLLECTIONS },
                    icon = { Text("蔵") },
                    label = { Text(stringResource(R.string.collections_title)) }
                )
                NavigationBarItem(
                    selected = activeSection == MiningSection.SETTINGS,
                    onClick = { activeSection = MiningSection.SETTINGS },
                    icon = { Text("設") },
                    label = { Text(stringResource(R.string.nav_settings)) }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        bottom = 16.dp
                    )
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
                    Text(stringResource(R.string.home_bookshelf_title), style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (selectedBookIds.isNotEmpty()) {
                            OutlinedButton(onClick = { requestDeleteSelectedBooks() }) {
                                Text(stringResource(R.string.home_delete_selected, selectedBookIds.size))
                            }
                            OutlinedButton(onClick = { clearBookSelection() }) {
                                Text(stringResource(R.string.home_cancel_selection))
                            }
                        }
                        if (selectedBookIds.isEmpty()) {
                            OutlinedButton(
                                onClick = { activeSection = MiningSection.SETTINGS }
                            ) {
                                Text(stringResource(R.string.nav_settings))
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
                                        stringResource(R.string.home_switch_to_list)
                                    } else {
                                        stringResource(R.string.home_switch_to_shelf)
                                    }
                                )
                            }
                        }
                    }
                }

                if (readerBooks.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(stringResource(R.string.home_no_books))
                            Text(stringResource(R.string.home_no_books_hint))
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
                                        Text(if (book.srtUri != null) stringResource(R.string.home_has_subtitle) else stringResource(R.string.home_no_subtitle))
                                        Text("$playbackPercent%")
                                        if (multiSelected) {
                                            Text(stringResource(R.string.home_selected), color = MaterialTheme.colorScheme.primary)
                                        }
                                        if (selected) {
                                            Text(stringResource(R.string.home_opened), color = MaterialTheme.colorScheme.primary)
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
                                    Text(if (book.srtUri != null) stringResource(R.string.home_has_subtitle) else stringResource(R.string.home_no_subtitle))
                                    Text("$playbackPercent%")
                                    if (multiSelected) {
                                        Text(stringResource(R.string.home_selected), color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                if (selectedBookIds.isEmpty()) {
                                    OutlinedButton(onClick = { openReaderBook(book, persist = true) }) {
                                        Text(if (selected) stringResource(R.string.home_opened) else stringResource(R.string.common_open))
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
                            if (srtLoading) Text(stringResource(R.string.bookreader_parsing_srt))
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
                        Text(stringResource(R.string.dictionary_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.dictionary_summary, dictionaryCount, totalDictionaryEntries))
                        if (dictionaryLoading) {
                            Text(dictionaryProgressText ?: stringResource(R.string.dictionary_importing))
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
                            Text(stringResource(R.string.dictionary_error, dictionaryError.orEmpty()), color = MaterialTheme.colorScheme.error)
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { pickDictionaryLauncher.launch(arrayOf("application/zip", "*/*")) }) {
                                Text(stringResource(R.string.dictionary_import))
                            }
                            OutlinedButton(
                                onClick = { showDictionaryManager = !showDictionaryManager }
                            ) {
                                Text(if (showDictionaryManager) stringResource(R.string.dictionary_hide_list) else stringResource(R.string.dictionary_show_list))
                            }
                        }

                        if (showDictionaryManager) {
                            if (dictionaryRefs.isEmpty()) {
                                Text(stringResource(R.string.dictionary_empty))
                            } else {
                                dictionaryRefs.forEachIndexed { index, ref ->
                                    val loaded = loadedDictionaries.getOrNull(index)
                                    val countText = loaded?.entryCount?.let { context.getString(R.string.dictionary_count, it) } ?: stringResource(R.string.dictionary_unloaded)
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(ref.name.ifBlank { context.getString(R.string.dictionary_default_name, index + 1) })
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
                                                    Text(stringResource(R.string.common_delete))
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
                            label = { Text(stringResource(R.string.dictionary_query_label)) },
                            singleLine = true
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { triggerLookupCandidates(listOf(lookupQuery)) },
                                enabled = loadedDictionaries.isNotEmpty() && lookupQuery.isNotBlank()
                            ) {
                                Text(stringResource(R.string.dictionary_query_button))
                            }
                            OutlinedButton(
                                onClick = {
                                    lookupQuery = ""
                                    lookupResults = emptyList()
                                    selectedEntryKey = null
                                }
                            ) {
                                Text(stringResource(R.string.common_clear))
                            }
                        }

                        if (lookupLoading) {
                            Text(stringResource(R.string.dictionary_querying))
                        }

                        groupedLookupResults.forEach { groupedResult ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        LookupHeadwordWithReading(
                                            term = groupedResult.term,
                                            reading = groupedResult.reading,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 8.dp),
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (audiobookSettings.lookupPlaybackAudioEnabled) {
                                                OutlinedButton(
                                                    onClick = { playLookupGroupAudio(groupedResult) }
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Audiotrack,
                                                        contentDescription = stringResource(R.string.common_audio),
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
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
                                    }
                                    val frequencyLabel = stringResource(R.string.bookreader_meta_frequency)
                                    val pitchLabel = stringResource(R.string.bookreader_meta_pitch)
                                    val topFrequencyBadges = groupedResult.dictionaries
                                        .asSequence()
                                        .map { dictionaryGroup ->
                                            parseMetaBadges(
                                                dictionaryGroup.frequency,
                                                frequencyLabel
                                            )
                                        }
                                        .firstOrNull { it.isNotEmpty() }
                                        .orEmpty()
                                    if (topFrequencyBadges.isNotEmpty()) {
                                        MetaBadgeRow(
                                            badges = topFrequencyBadges,
                                            labelColor = Color(0xFFDDF0DD),
                                            labelTextColor = Color(0xFF305E33)
                                        )
                                    }
                                    val topPitchBadges = groupedResult.dictionaries
                                        .asSequence()
                                        .map { dictionaryGroup ->
                                            parsePitchBadgeGroups(
                                                raw = dictionaryGroup.pitch,
                                                reading = groupedResult.reading,
                                                defaultLabel = pitchLabel
                                            )
                                        }
                                        .firstOrNull { it.isNotEmpty() }
                                        .orEmpty()
                                    if (topPitchBadges.isNotEmpty()) {
                                        topPitchBadges.forEach { group ->
                                            PitchBadgeRow(
                                                group = group,
                                                labelColor = Color(0xFFE7DDF8),
                                                labelTextColor = Color(0xFF4E3A74)
                                            )
                                        }
                                    }
                                    groupedResult.dictionaries.forEach { dictionaryGroup ->
                                        val sectionKey = "dictionary|${groupedResult.term}|${dictionaryGroup.dictionary}"
                                        val expanded = !(dictionaryLookupCollapsedSections[sectionKey] ?: false)
                                        Card(modifier = Modifier.fillMaxWidth()) {
                                            Column(
                                                modifier = Modifier.padding(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                DictionaryEntryHeader(
                                                    dictionaryName = dictionaryGroup.dictionary,
                                                    expanded = expanded,
                                                    onToggleExpanded = {
                                                        dictionaryLookupCollapsedSections[sectionKey] = expanded
                                                    }
                                                )
                                                if (expanded) {
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
            }

            if (activeSection == MiningSection.COLLECTIONS) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(stringResource(R.string.collections_title), style = MaterialTheme.typography.titleMedium)
                        Text(context.getString(R.string.collections_count, collectedCues.size))
                        if (collectedCues.isEmpty()) {
                            Text(stringResource(R.string.collections_empty))
                        } else {
                            collectedCues.forEach { item ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(item.text)
                                        Text(
                                            formatCollectedCueMeta(context, item),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = { playCollectedCue(item) }
                                            ) {
                                                Text(stringResource(R.string.common_play))
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
                                                Text(stringResource(R.string.common_lookup))
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    removeBookReaderCollectedCue(context, item.id)
                                                    refreshCollectedCues()
                                                }
                                            ) {
                                                Text(stringResource(R.string.common_delete))
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
                SettingsPanel(
                    selectedAppLanguageLabel = selectedAppLanguage.displayLabel(context),
                    versionName = resolveAppVersionName(context),
                    onAudiobookClick = { context.startActivity(Intent(context, AudiobookSettingsActivity::class.java)) },
                    onControlModeClick = { context.startActivity(Intent(context, ControlModeSettingsActivity::class.java)) },
                    onControllerClick = { context.startActivity(Intent(context, ControllerSettingsActivity::class.java)) },
                    onAnkiClick = { context.startActivity(Intent(context, AnkiSettingsActivity::class.java)) },
                    onLanguageClick = { languageDialogVisible = true },
                    onGuideClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/techza14/9Player")
                        )
                        runCatching { context.startActivity(intent) }
                            .onFailure { Toast.makeText(context, context.getString(R.string.settings_open_link_failed), Toast.LENGTH_SHORT).show() }
                    },
                    onExportDiagnosticsClick = {
                        runCatching { shareDiagnosticsReport(context) }
                            .onFailure {
                                Toast.makeText(context, context.getString(R.string.settings_export_diagnostics_failed), Toast.LENGTH_SHORT).show()
                            }
                    },
                    onVersionClick = {
                        val version = resolveAppVersionName(context)
                        Toast.makeText(context, context.getString(R.string.settings_version_toast, version), Toast.LENGTH_SHORT).show()
                        versionTapCount += 1
                        if (versionTapCount >= 5) {
                            versionTapCount = 0
                            showVersionEasterGif = true
                        }
                    }
                )
            }
        }

        if (languageDialogVisible) {
            AppLanguageDialog(
                selectedAppLanguage = selectedAppLanguage,
                onDismiss = { languageDialogVisible = false },
                onSelectLanguage = { option ->
                    selectedAppLanguage = option
                    saveAppLanguageOption(context, option)
                    applyAppLanguage(option)
                    languageDialogVisible = false
                    val activity = context as? Activity
                    if (activity != null) {
                        val restartIntent = Intent(activity, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        }
                        activity.startActivity(restartIntent)
                        activity.overridePendingTransition(0, 0)
                        activity.finish()
                    }
                }
            )
        }

        if (importGuideVisible) {
            ImportGuideDialog(
                onKeepOriginal = {
                    autoMoveToAudiobookFolder = false
                    importOnboardingCompleted = true
                    importGuideVisible = false
                    persistImportState()
                },
                onAutoMove = {
                    autoMoveToAudiobookFolder = true
                    importOnboardingCompleted = true
                    importGuideVisible = false
                    persistImportState()
                }
            )
        }

        if (clearCollectionsConfirmVisible) {
            ClearCollectionsDialog(
                onDismiss = { clearCollectionsConfirmVisible = false },
                onConfirm = {
                    clearBookReaderCollectedCues(context)
                    refreshCollectedCues()
                    clearCollectionsConfirmVisible = false
                }
            )
        }

        if (deleteBooksConfirmVisible) {
            DeleteBooksConfirmDialog(
                deleteBooksDontAskAgain = deleteBooksDontAskAgain,
                onDontAskAgainChange = { checked -> deleteBooksDontAskAgain = checked },
                onDismiss = {
                    deleteBooksConfirmVisible = false
                    pendingDeleteBookIds = emptySet()
                },
                onConfirm = {
                    if (deleteBooksDontAskAgain) {
                        skipDeleteBookConfirm = true
                        saveSkipDeleteBookConfirm(context, true)
                    }
                    val removeIds = pendingDeleteBookIds
                    deleteBooksConfirmVisible = false
                    pendingDeleteBookIds = emptySet()
                    deleteSelectedBooks(removeIds)
                }
            )
        }

        if (addBookDialogVisible) {
            AddBookDialog(
                folderName = addBookFolderName,
                folderUri = addBookFolderUri,
                audioName = addBookAudioName,
                audioUri = addBookAudioUri,
                srtName = addBookSrtName,
                autoMoveToAudiobookFolder = autoMoveToAudiobookFolder,
                srtLoading = srtLoading,
                onPickFolder = { pickBookFolderLauncher.launch(null) },
                onClearFolderSelection = {
                    addBookFolderUri = null
                    addBookFolderName = null
                    addBookAudioUri = null
                    addBookAudioName = null
                    addBookSrtUri = null
                    addBookSrtName = null
                    persistImportState()
                },
                onPickAudio = {
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
                onPickSrt = {
                    pickBookSrtLauncher.launch(
                        arrayOf("application/x-subrip", "text/plain", "*/*")
                    )
                },
                onDismiss = { addBookDialogVisible = false },
                onConfirm = { confirmAddBookFromDialog() }
            )
        }

        if (mainLookupPopupVisible) {
            Popup(
                alignment = Alignment.TopCenter,
                onDismissRequest = {
                    closeMainLookupPopup()
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
                            var popupCueLayout by remember(popupCue.text, mainLookupPopupSelectedRange) { mutableStateOf<TextLayoutResult?>(null) }
                            Text(
                                text = buildMainHighlightedText(popupCue.text, mainLookupPopupSelectedRange),
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.pointerInput(popupCue) {
                                    detectTapGestures { tapOffset ->
                                        val layout = popupCueLayout ?: return@detectTapGestures
                                        triggerMainCueLookup(popupCue, layout.getOffsetForPosition(tapOffset))
                                    }
                                },
                                onTextLayout = { popupCueLayout = it }
                            )
                            Text(
                                "${formatTime(popupCue.startMs)} - ${formatTime(popupCue.endMs)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 520.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (mainLookupPopupLoading) {
                                Text(stringResource(R.string.common_querying))
                            }
                            if (mainLookupPopupError != null) {
                                Text(
                                    context.getString(R.string.lookup_error_prefix, mainLookupPopupError.orEmpty()),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            groupedMainLookupPopupResults.forEach { groupedResult ->
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
                                            LookupHeadwordWithReading(
                                                term = groupedResult.term,
                                                reading = groupedResult.reading,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(end = 8.dp),
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                if (audiobookSettings.lookupPlaybackAudioEnabled) {
                                                    OutlinedButton(
                                                        onClick = { playLookupGroupAudio(groupedResult) }
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Outlined.Audiotrack,
                                                            contentDescription = stringResource(R.string.common_audio),
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
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
                                    }
                                    val frequencyLabel = stringResource(R.string.bookreader_meta_frequency)
                                    val pitchLabel = stringResource(R.string.bookreader_meta_pitch)
                                    val topFrequencyBadges = groupedResult.dictionaries
                                        .asSequence()
                                        .map { dictionaryGroup ->
                                            parseMetaBadges(
                                                dictionaryGroup.frequency,
                                                frequencyLabel
                                            )
                                        }
                                        .firstOrNull { it.isNotEmpty() }
                                        .orEmpty()
                                    if (topFrequencyBadges.isNotEmpty()) {
                                        MetaBadgeRow(
                                            badges = topFrequencyBadges,
                                            labelColor = Color(0xFFDDF0DD),
                                            labelTextColor = Color(0xFF305E33)
                                        )
                                    }
                                    val topPitchBadges = groupedResult.dictionaries
                                        .asSequence()
                                        .map { dictionaryGroup ->
                                            parsePitchBadgeGroups(
                                                raw = dictionaryGroup.pitch,
                                                reading = groupedResult.reading,
                                                defaultLabel = pitchLabel
                                            )
                                        }
                                        .firstOrNull { it.isNotEmpty() }
                                        .orEmpty()
                                    if (topPitchBadges.isNotEmpty()) {
                                        topPitchBadges.forEach { group ->
                                            PitchBadgeRow(
                                                group = group,
                                                labelColor = Color(0xFFE7DDF8),
                                                labelTextColor = Color(0xFF4E3A74)
                                            )
                                        }
                                    }
                                        groupedResult.dictionaries.forEach { dictionaryGroup ->
                                            val sectionKey = "mainPopup|${groupedResult.term}|${dictionaryGroup.dictionary}"
                                            val expanded = !(mainLookupCollapsedSections[sectionKey] ?: false)
                                            Card(modifier = Modifier.fillMaxWidth()) {
                                                Column(
                                                    modifier = Modifier.padding(8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    DictionaryEntryHeader(
                                                        dictionaryName = dictionaryGroup.dictionary,
                                                        expanded = expanded,
                                                        onToggleExpanded = {
                                                            mainLookupCollapsedSections[sectionKey] = expanded
                                                        }
                                                    )
                                                    if (expanded) {
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

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    closeMainLookupPopup()
                                }
                            ) {
                        Text(stringResource(R.string.common_close))
                            }
                        }
                    }
                }
            }
            }

            VersionEasterGifPopup(
                visible = showVersionEasterGif && activeSection == MiningSection.SETTINGS,
                bottomPadding = innerPadding.calculateBottomPadding(),
                onDismiss = { showVersionEasterGif = false }
            )
        }
    }
}

@Composable
private fun VersionEasterGifPopup(
    visible: Boolean,
    bottomPadding: Dp,
    onDismiss: () -> Unit
) {
    if (!visible) return
    val density = LocalDensity.current
    val horizontalOffsetPx = with(density) { (-14).dp.roundToPx() }
    val verticalOffsetPx = with(density) { -(bottomPadding + 8.dp).roundToPx() }
    Popup(
        alignment = Alignment.BottomEnd,
        offset = IntOffset(horizontalOffsetPx, verticalOffsetPx),
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = false
        )
    ) {
        Box(
            modifier = Modifier
                .size(90.dp)
                .clickable { onDismiss() }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { popupContext ->
                    ImageView(popupContext).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        adjustViewBounds = true
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val animated = runCatching {
                                ImageDecoder.decodeDrawable(
                                    ImageDecoder.createSource(resources, R.raw.easter_chibi)
                                )
                            }.getOrNull()
                            if (animated != null) {
                                setImageDrawable(animated)
                                (animated as? AnimatedImageDrawable)?.apply {
                                    repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                                    start()
                                }
                            } else {
                                setImageResource(R.mipmap.ic_launcher_foreground)
                            }
                        } else {
                            setImageResource(R.mipmap.ic_launcher_foreground)
                        }
                    }
                }
            )
        }
    }
}

private fun resolveAppVersionName(context: Context): String {
    return runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
            .versionName
            ?.trim()
            .orEmpty()
            .ifBlank { "unknown" }
    }.getOrDefault("unknown")
}

private fun resolveAppVersionCode(context: Context): Long {
    return runCatching {
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
    }.getOrDefault(-1L)
}

private fun shareDiagnosticsReport(context: Context) {
    val report = buildDiagnosticsReport(context)
    val reportFile = writeDiagnosticsReportFile(context, report)
    val reportUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        reportFile
    )
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_export_diagnostics_subject))
        putExtra(Intent.EXTRA_TEXT, report)
        putExtra(Intent.EXTRA_STREAM, reportUri)
        clipData = android.content.ClipData.newUri(context.contentResolver, reportFile.name, reportUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(
        shareIntent,
        context.getString(R.string.settings_export_diagnostics_title)
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

private fun writeDiagnosticsReportFile(context: Context, report: String): File {
    val diagnosticsDir = File(context.cacheDir, "anki_media").apply { mkdirs() }
    diagnosticsDir.listFiles()
        ?.filter { it.name.startsWith("9player-diagnostics-") && it.extension.equals("txt", ignoreCase = true) }
        ?.forEach { runCatching { it.delete() } }

    return File(diagnosticsDir, "9player-diagnostics-${System.currentTimeMillis()}.txt").apply {
        writeText(report, Charsets.UTF_8)
    }
}

private fun buildDiagnosticsReport(context: Context): String {
    val versionName = resolveAppVersionName(context)
    val versionCode = resolveAppVersionCode(context)
    val appLanguage = loadAppLanguageOption(context).displayLabel(context)
    val audiobookSettings = loadAudiobookSettingsConfig(context)
    val persistedImports = loadPersistedImports(context)
    val persistedAnki = loadPersistedAnkiConfig(context)
    val ankiResolvedPackage = resolveAnkiPackageName(context)
    val recentLogs = loadRecentProcessLogs()

    return buildString {
        appendLine("9Player Diagnostics")
        appendLine()
        appendLine("[App]")
        appendLine("VersionName=$versionName")
        appendLine("VersionCode=$versionCode")
        appendLine("Package=${context.packageName}")
        appendLine()
        appendLine("[Device]")
        appendLine("Android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Brand=${Build.BRAND}")
        appendLine("Manufacturer=${Build.MANUFACTURER}")
        appendLine("Model=${Build.MODEL}")
        appendLine("Device=${Build.DEVICE}")
        appendLine()
        appendLine("[Settings Summary]")
        appendLine("AppLanguage=$appLanguage")
        appendLine("ImportedBooks=${persistedImports.books.size}")
        appendLine("ImportedDictionaries=${persistedImports.dictionaries.size}")
        appendLine("AutoMoveToAudiobookFolder=${persistedImports.autoMoveToAudiobookFolder}")
        appendLine("HomeLibraryView=${persistedImports.homeLibraryView}")
        appendLine("FloatingOverlayEnabled=${audiobookSettings.floatingOverlayEnabled}")
        appendLine("FloatingOverlaySubtitleEnabled=${audiobookSettings.floatingOverlaySubtitleEnabled}")
        appendLine("FloatingOverlaySubtitleY=${audiobookSettings.floatingOverlaySubtitleY}")
        appendLine("FloatingOverlayBubbleX=${audiobookSettings.floatingOverlayBubbleX}")
        appendLine("FloatingOverlayBubbleY=${audiobookSettings.floatingOverlayBubbleY}")
        appendLine("FloatingOverlaySizeDp=${audiobookSettings.floatingOverlaySizeDp}")
        appendLine("FloatingSubtitleSizeSp=${audiobookSettings.floatingOverlaySubtitleSizeSp}")
        appendLine("PausePlaybackOnLookup=${audiobookSettings.pausePlaybackOnLookup}")
        appendLine("LookupAudioEnabled=${audiobookSettings.lookupPlaybackAudioEnabled}")
        appendLine("LookupAudioAutoPlay=${audiobookSettings.lookupPlaybackAudioAutoPlay}")
        appendLine("LookupAudioMode=${audiobookSettings.lookupAudioMode.storageValue}")
        appendLine("LookupFullSentence=${audiobookSettings.lookupExportFullSentence}")
        appendLine("LookupRangeSelection=${audiobookSettings.lookupRangeSelectionEnabled}")
        appendLine()
        appendLine("[Anki Diagnostics]")
        appendLine("AvailabilityState=${detectAnkiAvailability(context, requirePermission = true)}")
        appendLine("ResolvedPackage=${ankiResolvedPackage ?: "(null)"}")
        appendLine("Installed=${isAnkiInstalled(context)}")
        appendLine("ReadWritePermission=${hasAnkiReadWritePermission(context)}")
        appendLine("Deck=${persistedAnki.deckName}")
        appendLine("Model=${persistedAnki.modelName.ifBlank { "(blank)" }}")
        appendLine("Tags=${persistedAnki.tags.ifBlank { "(blank)" }}")
        appendLine("FieldTemplateCount=${persistedAnki.fieldTemplates.size}")
        appendLine()
        appendLine("[Recent Logs]")
        appendLine(recentLogs.ifBlank { "(no recent logs captured)" })
    }
}

private fun loadRecentProcessLogs(maxLines: Int = 200): String {
    return runCatching {
        val process = ProcessBuilder(
            "logcat",
            "-d",
            "-t",
            maxLines.toString(),
            "--pid=${android.os.Process.myPid()}",
            "*:V"
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        process.waitFor()
        output
    }.getOrDefault("")
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
                .menuAnchor(type = MenuAnchorType.PrimaryNotEditable),
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
        val picture = retriever.embeddedPicture
        if (picture != null) {
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
                return Uri.fromFile(outFile)
            }
        }

        val attachedFrame = runCatching {
            retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }.getOrNull()
        saveBitmapCoverIfPresent(
            bitmap = attachedFrame,
            coverDir = coverDir,
            cacheKey = cacheKey
        )?.let { return it }

        extractAttachedPicCoverWithMediaExtractor(
            context = context,
            audioUri = audioUri,
            coverDir = coverDir,
            cacheKey = cacheKey
        )?.let { return it }

        extractCoverWithTagLib(context, audioUri, coverDir, cacheKey)
    } catch (_: Throwable) {
        extractCoverWithTagLib(context, audioUri, coverDir, cacheKey)
    } finally {
        runCatching { retriever.release() }
    }
}

private fun saveBitmapCoverIfPresent(
    bitmap: Bitmap?,
    coverDir: File,
    cacheKey: String
): Uri? {
    val target = bitmap ?: return null
    val outFile = File(coverDir, "cover-$cacheKey.jpg")
    return runCatching {
        outFile.outputStream().use { output ->
            target.compress(Bitmap.CompressFormat.JPEG, 95, output)
        }
        if (outFile.exists() && outFile.length() > 0L) {
            Uri.fromFile(outFile)
        } else {
            null
        }
    }.getOrNull()
}

private fun extractAttachedPicCoverWithMediaExtractor(
    context: Context,
    audioUri: Uri,
    coverDir: File,
    cacheKey: String
): Uri? {
    val extractor = MediaExtractor()
    return try {
        val dataSourceSet = if (audioUri.scheme.equals("file", ignoreCase = true)) {
            val path = audioUri.path ?: return null
            runCatching { extractor.setDataSource(path) }.isSuccess
        } else {
            runCatching { extractor.setDataSource(context, audioUri, null) }.isSuccess
        }
        if (!dataSourceSet) return null

        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            mime.startsWith("image/", ignoreCase = true) ||
                mime.startsWith("video/", ignoreCase = true)
        } ?: return null

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty().lowercase(Locale.ROOT)
        val maxSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024)
        } else {
            2 * 1024 * 1024
        }
        val buffer = java.nio.ByteBuffer.allocateDirect(maxSize)
        val size = extractor.readSampleData(buffer, 0)
        if (size <= 0) return null

        val bytes = ByteArray(size)
        buffer.position(0)
        buffer.get(bytes, 0, size)

        val ext = when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            else -> "jpg"
        }
        val outFile = File(coverDir, "cover-$cacheKey.$ext")
        outFile.writeBytes(bytes)
        if (outFile.exists() && outFile.length() > 0L) {
            Uri.fromFile(outFile)
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { extractor.release() }
    }
}

private fun extractCoverWithTagLib(
    context: Context,
    audioUri: Uri,
    coverDir: File,
    cacheKey: String
): Uri? {
    val descriptor = if (audioUri.scheme.equals("file", ignoreCase = true)) {
        val path = audioUri.path ?: return null
        runCatching {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull()
    } else {
        runCatching {
            context.contentResolver.openFileDescriptor(audioUri, "r")
        }.getOrNull()
    } ?: return null

    return descriptor.use { pfd ->
        val detachedFd = runCatching { pfd.detachFd() }.getOrNull() ?: return@use null
        runCatching {
            val picture = TagLib.getFrontCover(detachedFd) ?: return@runCatching null
            saveTagLibCoverBytes(
                bytes = picture.data,
                mimeType = picture.mimeType,
                coverDir = coverDir,
                cacheKey = cacheKey
            )
        }.getOrNull()
    }
}

private fun saveTagLibCoverBytes(
    bytes: ByteArray,
    mimeType: String?,
    coverDir: File,
    cacheKey: String
): Uri? {
    if (bytes.isEmpty()) return null
    val normalizedMime = mimeType.orEmpty().lowercase(Locale.ROOT)
    val ext = when {
        normalizedMime.contains("png") -> "png"
        normalizedMime.contains("webp") -> "webp"
        normalizedMime.contains("bmp") -> "bmp"
        normalizedMime.contains("gif") -> "gif"
        else -> detectTagLibCoverFileExtension(bytes)
    }
    val outFile = File(coverDir, "cover-$cacheKey.$ext")
    return runCatching {
        outFile.writeBytes(bytes)
        if (outFile.exists() && outFile.length() > 0L) {
            Uri.fromFile(outFile)
        } else {
            null
        }
    }.getOrNull()
}

private fun detectTagLibCoverFileExtension(bytes: ByteArray): String {
    return when {
        bytes.size >= 4 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() -> "png"
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte() -> "jpg"
        bytes.size >= 4 &&
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() -> "webp"
        else -> "jpg"
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
        ?: error(context.getString(R.string.error_audiobook_folder_inaccessible))
    if (!root.isDirectory) error(context.getString(R.string.error_audiobook_folder_not_directory))

    val audFolder = createNextAudFolder(context, root)
    val audioDisplayName = audioSourceName?.trim().takeUnless { it.isNullOrBlank() }
        ?: queryDisplayName(contentResolver, audioSourceUri)

    val copiedAudio = copyUriIntoFolder(
        context = context,
        contentResolver = contentResolver,
        parentFolder = audFolder,
        sourceUri = audioSourceUri,
        preferredDisplayName = audioDisplayName
    )
    val copiedSrt = srtSourceUri?.let { sourceUri ->
        val srtDisplayName = srtSourceName?.trim().takeUnless { it.isNullOrBlank() }
            ?: queryDisplayName(contentResolver, sourceUri)
        copyUriIntoFolder(
            context = context,
            contentResolver = contentResolver,
            parentFolder = audFolder,
            sourceUri = sourceUri,
            preferredDisplayName = srtDisplayName
        )
    }

    val warnings = mutableListOf<String>()
    if (!deleteSourceUri(context, contentResolver, audioSourceUri)) {
        warnings += context.getString(R.string.error_audio_delete_failed)
    }
    if (srtSourceUri != null && !deleteSourceUri(context, contentResolver, srtSourceUri)) {
        warnings += context.getString(R.string.error_srt_delete_failed)
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

private fun createNextAudFolder(context: Context, rootFolder: DocumentFile): DocumentFile {
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
            return rootFolder.createDirectory(candidate) ?: error(context.getString(R.string.error_create_folder_failed, candidate))
        }
        next += 1
    }
    error(context.getString(R.string.error_create_aud_folder_failed))
}

private fun copyUriIntoFolder(
    context: Context,
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
        ?: error(context.getString(R.string.error_create_target_file_failed, uniqueName))

    val input = openBookInputStream(contentResolver, sourceUri)
        ?: error(context.getString(R.string.error_read_source_file_failed, normalizedName))
    input.use { src ->
        contentResolver.openOutputStream(created.uri, "w")?.use { output ->
            src.copyTo(output)
        } ?: error(context.getString(R.string.error_write_target_file_failed, uniqueName))
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
        ?: error(context.getString(R.string.error_audiobook_folder_inaccessible))
    if (!root.isDirectory) error(context.getString(R.string.error_audiobook_folder_not_directory))

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

private data class DeleteBookStorageResult(
    val folderDeleteAttempted: Boolean,
    val folderDeleteSucceeded: Boolean,
    val fileDeleteFailures: Int
)

private fun deleteBookStorage(
    context: Context,
    contentResolver: ContentResolver,
    book: ReaderBook,
    audiobookFolderUri: Uri?
): DeleteBookStorageResult {
    val isInsideAudiobookFolder = isUriInsideAudiobookFolder(
        context = context,
        fileUri = book.audioUri,
        audiobookFolderUri = audiobookFolderUri
    )

    if (isInsideAudiobookFolder) {
        val folderDeleted = deleteAudParentFolder(context, book.audioUri)
        if (folderDeleted) {
            return DeleteBookStorageResult(
                folderDeleteAttempted = true,
                folderDeleteSucceeded = true,
                fileDeleteFailures = 0
            )
        }
    }

    var fileDeleteFailures = 0
    if (!deleteSourceUri(context, contentResolver, book.audioUri)) {
        fileDeleteFailures += 1
    }
    val srt = book.srtUri
    if (srt != null && !deleteSourceUri(context, contentResolver, srt)) {
        fileDeleteFailures += 1
    }

    return DeleteBookStorageResult(
        folderDeleteAttempted = isInsideAudiobookFolder,
        folderDeleteSucceeded = false,
        fileDeleteFailures = fileDeleteFailures
    )
}

private fun isUriInsideAudiobookFolder(
    context: Context,
    fileUri: Uri,
    audiobookFolderUri: Uri?
): Boolean {
    val rootUri = audiobookFolderUri ?: return false
    if (fileUri.scheme.equals("file", ignoreCase = true) && rootUri.scheme.equals("file", ignoreCase = true)) {
        val filePath = runCatching { File(fileUri.path ?: return false).canonicalPath }.getOrNull() ?: return false
        val rootPath = runCatching { File(rootUri.path ?: return false).canonicalPath }.getOrNull() ?: return false
        if (filePath == rootPath) return true
        return filePath.startsWith("$rootPath${File.separator}")
    }

    if (!fileUri.scheme.equals("content", ignoreCase = true) || !rootUri.scheme.equals("content", ignoreCase = true)) {
        return false
    }

    val rootDocumentId = runCatching { DocumentsContract.getTreeDocumentId(rootUri) }
        .getOrElse {
            if (DocumentsContract.isDocumentUri(context, rootUri)) {
                runCatching { DocumentsContract.getDocumentId(rootUri) }.getOrNull()
            } else {
                null
            }
        } ?: return false

    val fileDocumentId = if (DocumentsContract.isDocumentUri(context, fileUri)) {
        runCatching { DocumentsContract.getDocumentId(fileUri) }.getOrNull()
    } else {
        runCatching { DocumentsContract.getTreeDocumentId(fileUri) }.getOrNull()
    } ?: return false

    return fileDocumentId == rootDocumentId || fileDocumentId.startsWith("$rootDocumentId/")
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
    lookupAudioUri: Uri?,
    bookTitle: String?,
    entry: DictionaryEntry,
    definition: String,
    dictionaryCss: String?,
    popupSelectionText: String? = null
) {
    val persistedConfig = loadPersistedAnkiConfig(context)
    val preparedExport = prepareAnkiExport(
        context = context,
        persistedConfig = persistedConfig,
        audioUri = audioUri,
        lookupAudioUri = lookupAudioUri
    )

    val card = MinedCard(
        word = entry.term,
        popupSelectionText = popupSelectionText,
        sentence = cue.text,
        bookTitle = bookTitle,
        reading = entry.reading,
        definitions = listOf(definition),
        dictionaryName = entry.dictionary,
        dictionaryCss = dictionaryCss,
        pitch = entry.pitch,
        frequency = entry.frequency,
        cueStartMs = cue.startMs,
        cueEndMs = cue.endMs,
        audioUri = audioUri,
        lookupAudioUri = lookupAudioUri,
        audioTagOnly = true,
        requireCueAudioClip = audioUri != null
    )

    exportToAnkiDroidApi(context, card, preparedExport.config)
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

private fun consumeReturnedBookProgress(intent: Intent?): ReturnedBookProgress? {
    val sourceIntent = intent ?: return null
    val audioUri = sourceIntent
        .getStringExtra(BookReaderActivity.EXTRA_RETURN_AUDIO_URI)
        ?.trim()
        .orEmpty()
    val positionMs = sourceIntent.getLongExtra(BookReaderActivity.EXTRA_RETURN_POSITION_MS, -1L)
    val durationMs = sourceIntent.getLongExtra(BookReaderActivity.EXTRA_RETURN_DURATION_MS, -1L)
    sourceIntent.removeExtra(BookReaderActivity.EXTRA_RETURN_AUDIO_URI)
    sourceIntent.removeExtra(BookReaderActivity.EXTRA_RETURN_POSITION_MS)
    sourceIntent.removeExtra(BookReaderActivity.EXTRA_RETURN_DURATION_MS)
    if (audioUri.isBlank() || positionMs < 0L || durationMs <= 0L) return null
    return ReturnedBookProgress(
        audioUri = audioUri,
        positionMs = positionMs,
        durationMs = durationMs
    )
}

private fun formatCollectedCueMeta(context: Context, item: BookReaderCollectedCue): String {
    val chapterLabel = item.chapterTitle?.takeIf { it.isNotBlank() }
        ?: item.chapterIndex?.let { context.getString(R.string.chapter_label_number, it + 1) }
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










