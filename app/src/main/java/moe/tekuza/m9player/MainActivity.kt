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
import android.util.Log
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Refresh
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.kyant.taglib.TagLib
import moe.tekuza.m9player.ui.theme.TsetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupHtml
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupItem
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupAssets
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupOptions
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupStackView
import moe.tekuza.m9player.hoshi.features.dictionary.PopupWebViewCallbacks
import moe.tekuza.m9player.hoshi.features.dictionary.loadDictionarySettings
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionData
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionRect
import de.manhhao.hoshi.LookupResult
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private var floatingOverlayStartJob: Job? = null
    private var autoUpdateCheckJob: Job? = null
    internal var launchUpdatePromptRelease by mutableStateOf<AppUpdateRelease?>(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedAppLanguage(this)
        super.onCreate(savedInstanceState)
        prebuildMountedMdxIndexesAsync(applicationContext)
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
        val settings = loadAudiobookSettingsConfig(this)
        val keepOverlay =
            (settings.floatingOverlayEnabled || settings.floatingOverlaySubtitleEnabled) &&
                BookReaderFloatingBridge.currentAudioUri() != null &&
                BookReaderFloatingBridge.isPlaying()
        Log.d(
            FLOATING_OVERLAY_EXIT_LOG_TAG,
            "Main onStart keepOverlay=$keepOverlay audio=${BookReaderFloatingBridge.currentAudioUri() != null} " +
                "playing=${BookReaderFloatingBridge.isPlaying()}"
        )
        if (!keepOverlay) {
            stopAudiobookFloatingOverlayService(this)
        }
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
        autoUpdateCheckJob?.cancel()
        autoUpdateCheckJob = null
        super.onDestroy()
    }

    fun checkAppUpdateInBackground(force: Boolean) {
        val config = loadAppUpdateConfig(this)
        val checkIntervalMs = 12L * 60L * 60L * 1000L
        if (!force && System.currentTimeMillis() - config.lastCheckedAtMs < checkIntervalMs) return
        autoUpdateCheckJob?.cancel()
        autoUpdateCheckJob = lifecycleScope.launch {
            val result = checkLatestAppUpdate(this@MainActivity)
            saveAppUpdateCheckedAt(this@MainActivity, System.currentTimeMillis())
            val release = (result as? AppUpdateCheckResult.UpdateAvailable)?.release ?: return@launch
            if (isFinishing || isDestroyed) return@launch
            launchUpdatePromptRelease = release
        }
    }

    internal fun dismissLaunchUpdatePrompt() {
        launchUpdatePromptRelease = null
    }

    internal fun downloadLaunchUpdatePrompt(release: AppUpdateRelease) {
        launchUpdatePromptRelease = null
        downloadUpdateFromLaunchPrompt(release)
    }

    private fun downloadUpdateFromLaunchPrompt(release: AppUpdateRelease) {
        autoUpdateCheckJob?.cancel()
        autoUpdateCheckJob = lifecycleScope.launch {
            Toast.makeText(this@MainActivity, getString(R.string.update_downloading), Toast.LENGTH_SHORT).show()
            val result = downloadAppUpdateApk(this@MainActivity, release) {}
            result
                .onSuccess { file ->
                    if (!launchAppUpdateInstall(this@MainActivity, file)) {
                        Toast.makeText(this@MainActivity, getString(R.string.update_install_failed), Toast.LENGTH_LONG).show()
                    }
                }
                .onFailure { error ->
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_failed, error.message ?: error.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
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

private sealed interface MainLookupRequest {
    data class Cue(
        val cue: SubtitleCue,
        val offset: Int,
        val sourceBookTitle: String? = null,
        val anchor: ReaderLookupAnchor? = null,
        val placeBelow: Boolean = true
    ) : MainLookupRequest
    data class Candidates(
        val rawCandidates: List<String>,
        val anchor: ReaderLookupAnchor? = null,
        val placeBelow: Boolean = true
    ) : MainLookupRequest
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
private const val ANKI_CONFIG_LOG_TAG = "AnkiConfig"
private const val FLOATING_OVERLAY_EXIT_LOG_TAG = "FloatingOverlayExit"
private const val MAIN_READER_RESTORE_LOG_TAG = "MainReaderRestore"

internal data class ReaderBook(
    val id: String,
    val title: String,
    val audioUri: Uri,
    val audioName: String,
    val srtUri: Uri?,
    val srtName: String?,
    val ebookUri: Uri?,
    val ebookName: String?,
    val ebookFormat: String?,
    val coverUri: Uri?
)

private data class ReturnedBookProgress(
    val audioUri: String,
    val srtUri: String?,
    val positionMs: Long,
    val durationMs: Long
)

@Composable
@OptIn(ExperimentalFoundationApi::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun ReaderSyncScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val mainActivity = activity as? MainActivity
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    val rootDensity = LocalDensity.current
    val navigationBarBottomInsetDp = with(rootDensity) {
        WindowInsets.navigationBars.getBottom(this).toDp().value.toDouble()
    }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val contentResolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val dictionaryPageBackground = MaterialTheme.colorScheme.background
    val dictionaryPageBackgroundCss = dictionaryPageBackground.toCssRgbHex()
    val isDarkTheme = isSystemInDarkTheme()

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
    var addBookEbookUri by remember { mutableStateOf<Uri?>(null) }
    var addBookEbookName by remember { mutableStateOf<String?>(null) }
    var addBookEbookFormat by remember { mutableStateOf<String?>(null) }
    var addBookFolderUri by remember { mutableStateOf<Uri?>(null) }
    var addBookFolderName by remember { mutableStateOf<String?>(null) }
    var ebookFeatureEnabled by remember { mutableStateOf(loadEbookFeatureEnabled(context)) }
    var autoMoveToAudiobookFolder by remember { mutableStateOf(true) }
    var importOnboardingCompleted by remember { mutableStateOf(false) }
    var importGuideVisible by remember { mutableStateOf(false) }
    var persistedImportsLoaded by remember { mutableStateOf(false) }
    var autoUpdatePromptVisible by remember { mutableStateOf(false) }
    var autoUpdateStartupHandled by remember { mutableStateOf(false) }
    val selectedBookIds = remember { mutableStateListOf<String>() }
    var isBookSelectionMode by remember { mutableStateOf(false) }

    val dictionaryController = rememberDictionaryManagementController(
        context = context,
        contentResolver = contentResolver,
        scope = scope
    )
    val loadedDictionaries = dictionaryController.loadedDictionaries
    val dictionaryRefs = dictionaryController.dictionaryRefs
    val dictionaryLoading = dictionaryController.dictionaryLoading
    val dictionaryProgressText = dictionaryController.dictionaryProgressText
    val dictionaryProgressValue = dictionaryController.dictionaryProgressValue
    val dictionaryError = dictionaryController.dictionaryError
    val dictionaryOrderIds = dictionaryController.dictionaryOrderIds
    val mdxMountState = dictionaryController.mdxMountState
    var dictionaryUiConfig by remember { mutableStateOf(loadDictionaryUiConfig(context)) }

    var lookupQuery by remember { mutableStateOf("") }
    var lookupLoading by remember { mutableStateOf(false) }
    var dictionaryFirstLayerHtml by remember { mutableStateOf("") }
    var dictionaryFirstLayerResults by remember { mutableStateOf<List<LookupResult>>(emptyList()) }
    var dictionaryFirstLayerClearSelectionSignal by remember { mutableStateOf(0) }
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
    var showDictionaryDeleteActions by remember { mutableStateOf(false) }
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
    var renameBookDialogVisible by remember { mutableStateOf(false) }
    var renameTargetBookId by remember { mutableStateOf<String?>(null) }
    var renameBookInput by remember { mutableStateOf("") }
    val mainHoshiLookupPopups = remember { mutableStateListOf<LookupPopupItem>() }
    var mainHoshiLookupCue by remember { mutableStateOf<SubtitleCue?>(null) }
    var mainHoshiLookupSelectedRange by remember { mutableStateOf<IntRange?>(null) }
    var mainHoshiLookupAudioUri by remember { mutableStateOf<Uri?>(null) }
    var mainHoshiLookupTitle by remember { mutableStateOf("") }
    var collectionLookupPreviewVisible by remember { mutableStateOf(false) }
    var collectionLookupPreviewSentence by remember { mutableStateOf("") }
    var collectionLookupPreviewCue by remember { mutableStateOf<SubtitleCue?>(null) }
    var collectionLookupPreviewSelectedRange by remember { mutableStateOf<IntRange?>(null) }
    var collectionLookupPreviewAudioUri by remember { mutableStateOf<Uri?>(null) }
    var collectionFirstLayerHtml by remember { mutableStateOf("") }
    var collectionFirstLayerResults by remember { mutableStateOf<List<LookupResult>>(emptyList()) }
    var collectionFirstLayerClearSelectionSignal by remember { mutableStateOf(0) }
    var audiobookSettings by remember { mutableStateOf(loadAudiobookSettingsConfig(context)) }
    var versionTapCount by remember { mutableStateOf(0) }
    var showVersionEasterGif by remember { mutableStateOf(false) }
    var mdxExperimentalUnlocked by remember { mutableStateOf(loadMdxExperimentalUnlocked(context)) }

    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var pendingCollectionPlayMs by remember { mutableStateOf<Long?>(null) }
    var pendingCollectionStopMs by remember { mutableStateOf<Long?>(null) }
    var collectionPlayRequestNonce by remember { mutableStateOf(0L) }

    val importedLookupById = remember(dictionaryRefs, loadedDictionaries) {
        dictionaryRefs.mapIndexedNotNull { index, ref ->
            if (!ref.enabled) return@mapIndexedNotNull null
            val loaded = loadedDictionaries.getOrNull(index) ?: return@mapIndexedNotNull null
            importedDictionaryId(ref) to loaded
        }.toMap(LinkedHashMap())
    }
    val mountedLookupById = remember(mdxMountState.enabled, mdxMountState.entries) {
        if (!mdxMountState.enabled) {
            emptyMap()
        } else {
            mdxMountState.entries
                .asSequence()
                .filter { it.enabled && it.cacheKey.isNotBlank() && it.mdxUri.isNotBlank() }
                .associate { entry ->
                    val displayName = entry.displayName.ifBlank { "MDX" }
                    "mnt:${entry.cacheKey}" to LoadedDictionary(
                        cacheKey = entry.cacheKey,
                        name = displayName.substringBeforeLast('.').ifBlank { displayName },
                        format = "MDX (mounted)",
                        entries = emptyList(),
                        stylesCss = null,
                        entryCount = 0
                    )
                }
        }
    }
    val effectiveLookupDictionaries = remember(importedLookupById, mountedLookupById, dictionaryOrderIds) {
        val all = LinkedHashMap<String, LoadedDictionary>()
        all.putAll(importedLookupById)
        all.putAll(mountedLookupById)
        if (all.isEmpty()) return@remember emptyList()
        val orderedIds = buildList {
            dictionaryOrderIds.forEach { id -> if (all.containsKey(id)) add(id) }
            all.keys.forEach { id -> if (!contains(id)) add(id) }
        }
        orderedIds.mapNotNull { all[it] }
    }
    val dictionaryCssByName = remember(effectiveLookupDictionaries) {
        effectiveLookupDictionaries.associate { it.name to it.stylesCss }
    }
    val mainHoshiLookupSession = remember(context, effectiveLookupDictionaries) {
        HoshiLookupSession(context, dictionariesProvider = { effectiveLookupDictionaries })
    }
    fun closeMainLookupPopup() {
        mainHoshiLookupPopups.clear()
        mainHoshiLookupCue = null
        mainHoshiLookupSelectedRange = null
        mainHoshiLookupAudioUri = null
        mainHoshiLookupTitle = ""
        dictionaryFirstLayerHtml = ""
        dictionaryFirstLayerResults = emptyList()
        dictionaryFirstLayerClearSelectionSignal += 1
        collectionLookupPreviewVisible = false
        collectionLookupPreviewSentence = ""
        collectionLookupPreviewCue = null
        collectionLookupPreviewSelectedRange = null
        collectionLookupPreviewAudioUri = null
        collectionFirstLayerHtml = ""
        collectionFirstLayerResults = emptyList()
        collectionFirstLayerClearSelectionSignal += 1
    }

    BackHandler {
        when {
            collectionLookupPreviewVisible -> closeMainLookupPopup()
            mainHoshiLookupPopups.isNotEmpty() -> closeMainLookupPopup()
            addBookDialogVisible -> addBookDialogVisible = false
            importGuideVisible -> importGuideVisible = false
            autoUpdatePromptVisible -> autoUpdatePromptVisible = false
            mainActivity?.launchUpdatePromptRelease != null -> mainActivity.dismissLaunchUpdatePrompt()
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

    LaunchedEffect(dictionaryRefs, mdxMountState.entries) {
        dictionaryController.normalizeOrderForCurrentDictionaries()
    }

    LaunchedEffect(persistedImportsLoaded, importGuideVisible) {
        if (!persistedImportsLoaded || importGuideVisible || autoUpdateStartupHandled) return@LaunchedEffect
        autoUpdateStartupHandled = true
        val updateConfig = loadAppUpdateConfig(context)
        if (!updateConfig.firstPromptShown) {
            autoUpdatePromptVisible = true
        } else if (updateConfig.autoUpdateEnabled) {
            mainActivity?.checkAppUpdateInBackground(force = false)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                audiobookSettings = loadAudiobookSettingsConfig(context)
                dictionaryUiConfig = loadDictionaryUiConfig(context)
                dictionaryController.reloadExternalState()
                scope.launch {
                    var loadedSnapshots = loadReaderBookPlaybackSnapshotsForBooks(
                        context = context,
                        books = readerBooks
                    )
                    run {
                        val persistedNow = loadPersistedImports(context)
                        dictionaryController.syncPersistedDictionaries(persistedNow.dictionaries)
                        if (persistedNow.books.isNotEmpty() && readerBooks.isNotEmpty()) {
                            val persistedByAudio = persistedNow.books.associateBy { it.audioUri }
                            var changed = false
                            val mergedBooks = readerBooks.map { book ->
                                val persistedBook = persistedByAudio[book.audioUri.toString()] ?: return@map book
                                val persistedSrtRaw = persistedBook.srtUri?.trim().orEmpty()
                                if (persistedSrtRaw.isBlank()) return@map book
                                val persistedSrt = runCatching { Uri.parse(persistedSrtRaw) }.getOrNull()
                                    ?: return@map book
                                if ((book.srtUri?.toString().orEmpty()) == persistedSrtRaw) return@map book
                                changed = true
                                val mergedSrtName = persistedBook.srtName?.ifBlank { null }
                                    ?: queryDisplayName(contentResolver, persistedSrt)
                                val mergedTitle = buildBookTitle(book.audioName, mergedSrtName)
                                val mergedId = buildDictionaryCacheKey(
                                    uri = "book|${book.audioUri}|$persistedSrtRaw",
                                    displayName = "${book.audioName}|${mergedSrtName.orEmpty()}"
                                )
                                book.copy(
                                    id = mergedId,
                                    title = mergedTitle,
                                    srtUri = persistedSrt,
                                    srtName = mergedSrtName
                                )
                            }
                            if (changed) {
                                readerBooks = mergedBooks
                                val selected = mergedBooks.firstOrNull { it.id == selectedBookId }
                                    ?: mergedBooks.firstOrNull { it.audioUri.toString() == audioUri?.toString().orEmpty() }
                                if (selected != null) {
                                    selectedBookId = selected.id
                                    audioUri = selected.audioUri
                                    audioName = selected.audioName
                                    srtUri = selected.srtUri
                                    srtName = selected.srtName
                                }
                            }
                        }
                    }
                    val returnedProgress = consumeReturnedBookProgress(activity?.intent)
                    if (returnedProgress != null) {
                        val targetBook = readerBooks.firstOrNull {
                            it.audioUri.toString() == returnedProgress.audioUri
                        }
                        if (targetBook != null) {
                            val returnedSrt = returnedProgress.srtUri
                                ?.trim()
                                ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                ?.let { raw -> runCatching { Uri.parse(raw) }.getOrNull() }
                            if ((targetBook.srtUri?.toString().orEmpty()) != (returnedSrt?.toString().orEmpty())) {
                                val updatedSrtName = returnedSrt?.let { queryDisplayName(contentResolver, it) }
                                val updatedTitle = buildBookTitle(targetBook.audioName, updatedSrtName)
                                val updatedId = buildDictionaryCacheKey(
                                    uri = "book|${targetBook.audioUri}|${returnedSrt?.toString().orEmpty()}",
                                    displayName = "${targetBook.audioName}|${updatedSrtName.orEmpty()}"
                                )
                                val updatedBook = ReaderBook(
                                    id = updatedId,
                                    title = updatedTitle,
                                    audioUri = targetBook.audioUri,
                                    audioName = targetBook.audioName,
                                    srtUri = returnedSrt,
                                    srtName = updatedSrtName,
                                    ebookUri = targetBook.ebookUri,
                                    ebookName = targetBook.ebookName,
                                    ebookFormat = targetBook.ebookFormat,
                                    coverUri = targetBook.coverUri
                                )
                                val wasSelected = selectedBookId == targetBook.id
                                readerBooks = listOf(updatedBook) + readerBooks.filterNot {
                                    it.audioUri.toString() == targetBook.audioUri.toString()
                                }
                                if (wasSelected) {
                                    selectedBookId = updatedBook.id
                                    audioUri = updatedBook.audioUri
                                    audioName = updatedBook.audioName
                                    srtUri = updatedBook.srtUri
                                    srtName = updatedBook.srtName
                                }
                                // Persist immediately so "no SRT -> replaced SRT" survives app restart.
                                val persistedBooks = readerBooks.map { book ->
                                    PersistedReaderBook(
                                        id = book.id,
                                        title = book.title,
                                        audioUri = book.audioUri.toString(),
                                        audioName = book.audioName,
                                        srtUri = book.srtUri?.toString(),
                                        srtName = book.srtName,
                                        ebookUri = book.ebookUri?.toString(),
                                        ebookName = book.ebookName,
                                        ebookFormat = book.ebookFormat
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
                            if (returnedProgress.positionMs >= 0L && returnedProgress.durationMs > 0L) {
                                val effectiveBook = readerBooks.firstOrNull {
                                    it.audioUri.toString() == targetBook.audioUri.toString()
                                } ?: targetBook
                                val immediate = BookReaderPlaybackSnapshot(
                                    positionMs = returnedProgress.positionMs.coerceAtLeast(0L),
                                    durationMs = returnedProgress.durationMs.coerceAtLeast(0L)
                                )
                                loadedSnapshots = loadedSnapshots + (effectiveBook.id to immediate)
                                withContext(Dispatchers.IO) {
                                    saveBookReaderPlaybackPosition(
                                        context = context,
                                        bookKey = buildReaderBookPlaybackKey(effectiveBook),
                                        positionMs = immediate.positionMs,
                                        durationMs = immediate.durationMs
                                    )
                                }
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

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                isPlaying = player.isPlaying
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
        val selectedReaderBook = selectedBookId?.let { id -> readerBooks.firstOrNull { it.id == id } }
            ?: readerBooks.firstOrNull { it.audioUri == selectedAudio && it.srtUri == srtUri }
        val shouldPrewarmLegadoReader =
            selectedReaderBook?.ebookUri != null &&
                loadEbookFeatureEnabled(context) &&
                loadEbookDefaultToReader(context)
        if (shouldPrewarmLegadoReader) {
            val restoredCandidate = migrateBestReaderBookPlaybackSnapshotIfNeeded(
                context = context,
                book = selectedReaderBook,
                reason = "mainPrewarm"
            )
            val restoredSnapshot = restoredCandidate?.snapshot
            val restorePositionMs = restoredSnapshot?.positionMs?.coerceAtLeast(0L) ?: 0L
            val alreadyPreparedForAudio = BookReaderPlaybackSession.currentAudioUri() == selectedReaderBook.audioUri.toString()
            Log.d(
                MAIN_READER_RESTORE_LOG_TAG,
                "prewarm source=${restoredCandidate?.source ?: "none"} positionMs=$restorePositionMs " +
                    "durationMs=${restoredSnapshot?.durationMs ?: 0L} updatedAt=${restoredSnapshot?.updatedAtMs ?: 0L} " +
                    "sameAudio=$alreadyPreparedForAudio"
            )
            BookReaderPlaybackSession.prepareAudioIfNeeded(
                context = context,
                audioUri = selectedReaderBook.audioUri,
                restorePositionMs = restorePositionMs,
                forceSeekOnSameAudio = false
            )
            return@LaunchedEffect
        }
        player.setMediaItem(MediaItem.fromUri(selectedAudio))
        player.prepare()
        player.pause()
        player.seekTo(0L)
    }

    LaunchedEffect(audioUri, pendingCollectionPlayMs, collectionPlayRequestNonce) {
        val targetMs = pendingCollectionPlayMs ?: return@LaunchedEffect
        val selectedAudio = audioUri ?: return@LaunchedEffect
        if (player.playbackState == Player.STATE_IDLE) {
            player.setMediaItem(MediaItem.fromUri(selectedAudio))
            player.prepare()
        }
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

    fun persistImportState(dictionaryRefsOverride: List<PersistedDictionaryRef> = dictionaryRefs) {
        val previous = loadPersistedImports(context)
        val previousByAudio = previous.books.associateBy { it.audioUri }
        val persistedBooks = readerBooks.map { book ->
            val audioKey = book.audioUri.toString()
            val previousBook = previousByAudio[audioKey]
            val currentSrt = book.srtUri?.toString()?.takeIf { it.isNotBlank() }
            val mergedSrt = currentSrt ?: previousBook?.srtUri?.takeIf { it.isNotBlank() }
            val mergedSrtName = if (currentSrt != null) {
                book.srtName
            } else {
                book.srtName ?: previousBook?.srtName
            }
            PersistedReaderBook(
                id = book.id,
                title = book.title,
                audioUri = audioKey,
                audioName = book.audioName,
                srtUri = mergedSrt,
                srtName = mergedSrtName,
                ebookUri = book.ebookUri?.toString(),
                ebookName = book.ebookName,
                ebookFormat = book.ebookFormat
            )
        }
        val previousSelectedSrt = previous.srtUri?.takeIf { it.isNotBlank() }
        val currentSelectedSrt = srtUri?.toString()?.takeIf { it.isNotBlank() }
        val mergedSelectedSrt = currentSelectedSrt ?: previousSelectedSrt
        val mergedSelectedSrtName = if (currentSelectedSrt != null) {
            srtName
        } else {
            srtName ?: previous.srtName
        }
        savePersistedImports(
            context = context,
            state = PersistedImports(
                audioUri = audioUri?.toString(),
                audioName = audioName,
                srtUri = mergedSelectedSrt,
                srtName = mergedSelectedSrtName,
                audiobookFolderUri = addBookFolderUri?.toString(),
                audiobookFolderName = addBookFolderName,
                autoMoveToAudiobookFolder = autoMoveToAudiobookFolder,
                importOnboardingCompleted = importOnboardingCompleted,
                books = persistedBooks,
                selectedBookId = selectedBookId,
                homeLibraryView = homeLibraryView.name,
                dictionaries = dictionaryRefsOverride
            )
        )
    }

    fun persistHomeLibraryView(nextView: HomeLibraryView) {
        scope.launch(Dispatchers.IO) {
            val previous = loadPersistedImports(context)
            savePersistedImports(
                context = context,
                state = previous.copy(homeLibraryView = nextView.name)
            )
        }
    }

    fun persistAnkiConfig() {
        Log.d(
            ANKI_CONFIG_LOG_TAG,
            "MainActivity persist request deck='$ankiDeckName' model='$ankiModelName' tagsLen=${ankiTagsInput.length} fieldCount=${ankiFieldTemplates.size}"
        )
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
        Log.d(
            ANKI_CONFIG_LOG_TAG,
            "MainActivity select model='$modelName' modelChanged=$modelChanged found=${model != null} fieldCount=${model?.fields?.size ?: 0}"
        )
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
                    val resolvedModelName = resolvedCatalog.selection.modelName
                    val modelInCatalog = isAnkiModelInCatalog(
                        catalog = AnkiCatalog(
                            decks = resolvedCatalog.decks,
                            models = resolvedCatalog.models
                        ),
                        modelName = resolvedModelName
                    )
                    Log.d(
                        ANKI_CONFIG_LOG_TAG,
                        "MainActivity refresh success currentDeck='$ankiDeckName' currentModel='$ankiModelName' resolvedDeck='${resolvedCatalog.selection.deckName}' resolvedModel='$resolvedModelName' modelInCatalog=$modelInCatalog modelCount=${resolvedCatalog.models.size}"
                    )
                    ankiDecks = resolvedCatalog.decks
                    ankiModels = resolvedCatalog.models
                    ankiDeckName = resolvedCatalog.selection.deckName
                    if (modelInCatalog) {
                        selectAnkiModel(resolvedModelName)
                    } else {
                        Log.d(
                            ANKI_CONFIG_LOG_TAG,
                            "MainActivity refresh kept existing model='$ankiModelName' because it is not in catalog; templates were not cleared"
                        )
                    }
                }
                is AnkiCatalogLoadResult.Failure -> {
                    ankiError = result.message
                    Log.d(
                        ANKI_CONFIG_LOG_TAG,
                        "MainActivity refresh failed message='${result.message}'"
                    )
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

    fun buildReaderBook(
        audio: Uri,
        audioDisplayName: String?,
        srt: Uri?,
        srtDisplayName: String?,
        ebook: Uri? = null,
        ebookDisplayName: String? = null,
        ebookFormat: String? = null
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
            ebookUri = ebook,
            ebookName = ebookDisplayName,
            ebookFormat = ebookFormat,
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

    fun createLegadoReaderIntent(context: Context, book: ReaderBook): Intent {
        return Intent(context, LegadoReaderActivity::class.java).apply {
            putExtra(LegadoReaderActivity.EXTRA_EBOOK_TITLE, book.title)
            book.ebookUri?.let { putExtra(LegadoReaderActivity.EXTRA_EBOOK_URI, it.toString()) }
            book.ebookName?.let { putExtra(LegadoReaderActivity.EXTRA_EBOOK_NAME, it) }
            book.ebookFormat?.let { putExtra(LegadoReaderActivity.EXTRA_EBOOK_FORMAT, it) }
            putExtra(LegadoReaderActivity.EXTRA_AUDIO_URI, book.audioUri.toString())
            book.srtUri?.let { putExtra(LegadoReaderActivity.EXTRA_SRT_URI, it.toString()) }
        }
    }

    fun openReaderBook(book: ReaderBook, persist: Boolean = true) {
        val targetAudioUri = book.audioUri.toString()
        if (
            loadEbookFeatureEnabled(context) &&
            loadEbookDefaultToReader(context) &&
            book.ebookUri != null
        ) {
            BookReaderActivity.stopActiveReaderIfDifferentAudio(targetAudioUri)
            activateReaderBook(book, persist = persist)
            context.startActivity(createLegadoReaderIntent(context, book))
            return
        }
        val isSameReaderBook =
            !BookReaderFloatingBridge.isUiTestModeActive() &&
                BookReaderFloatingBridge.currentAudioUri() == targetAudioUri
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
            book.ebookUri?.let { putExtra(BookReaderActivity.EXTRA_EBOOK_URI, it.toString()) }
            book.ebookName?.let { putExtra(BookReaderActivity.EXTRA_EBOOK_NAME, it) }
            book.ebookFormat?.let { putExtra(BookReaderActivity.EXTRA_EBOOK_FORMAT, it) }
            book.coverUri?.let { putExtra(BookReaderActivity.EXTRA_COVER_URI, it.toString()) }
        }
        context.startActivity(intent)
    }

    fun requestRenameBook(book: ReaderBook) {
        renameTargetBookId = book.id
        renameBookInput = book.title
        renameBookDialogVisible = true
    }

    fun applyRenameBook() {
        val targetId = renameTargetBookId ?: return
        val nextTitle = renameBookInput.trim()
        if (nextTitle.isBlank()) {
            renameBookDialogVisible = false
            renameTargetBookId = null
            return
        }
        readerBooks = readerBooks.map { book ->
            if (book.id == targetId) book.copy(title = nextTitle) else book
        }
        persistImportState()
        renameBookDialogVisible = false
        renameTargetBookId = null
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
        isBookSelectionMode = false
    }

    fun enterBookSelectionMode() {
        isBookSelectionMode = true
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
        val pickedEbook = addBookEbookUri
        val pickedEbookName = addBookEbookName
        val pickedEbookFormat = addBookEbookFormat
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
                            srtSourceName = pickedSrtName,
                            ebookSourceUri = pickedEbook,
                            ebookSourceName = pickedEbookName
                        )
                        val book = buildReaderBook(
                            audio = relocated.audioUri,
                            audioDisplayName = relocated.audioName,
                            srt = relocated.srtUri,
                            srtDisplayName = relocated.srtName,
                            ebook = relocated.ebookUri,
                            ebookDisplayName = relocated.ebookName,
                            ebookFormat = pickedEbookFormat
                        )
                        val warning = relocated.moveWarnings.takeIf { it.isNotEmpty() }?.joinToString(" ")
                        Triple(book, relocated.folderName, warning)
                    } else {
                        val book = buildReaderBook(
                            audio = pickedAudio,
                            audioDisplayName = pickedAudioName,
                            srt = pickedSrt,
                            srtDisplayName = pickedSrtName,
                            ebook = pickedEbook,
                            ebookDisplayName = pickedEbookName,
                            ebookFormat = pickedEbookFormat
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
                addBookEbookUri = null
                addBookEbookName = null
                addBookEbookFormat = null
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
        val persistedState = loadPersistedImports(context)
        val persistedTitleById = persistedState.books
            .associate { it.id to it.title.trim() }
            .filterValues { it.isNotBlank() }
        val persistedTitleByAudioUri = persistedState.books
            .associate { it.audioUri to it.title.trim() }
            .filterValues { it.isNotBlank() }
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
                            val rebuilt = buildReaderBook(
                                audio = candidate.audioUri,
                                audioDisplayName = candidate.audioName,
                                srt = candidate.srtUri,
                                srtDisplayName = candidate.srtName,
                                ebook = candidate.ebookUri,
                                ebookDisplayName = candidate.ebookName,
                                ebookFormat = candidate.ebookFormat
                            )
                            val persistedTitle = persistedTitleById[rebuilt.id]
                                ?: persistedTitleByAudioUri[rebuilt.audioUri.toString()]
                            if (!persistedTitle.isNullOrBlank()) {
                                rebuilt.copy(title = persistedTitle)
                            } else {
                                rebuilt
                            }
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
        scope.launch {
            val exportResult = withContext(Dispatchers.IO) {
                exportToAnkiDroidApiResult(context, card, config)
            }
            exportStatus = ankiExportResultMessage(context, exportResult)
        }
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
        closeMainLookupPopup()
        if (activeSection == MiningSection.COLLECTIONS) {
            refreshCollectedCues()
        }
        if (activeSection != MiningSection.DICTIONARY) {
            lookupQuery = ""
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
        ebookFeatureEnabled = loadEbookFeatureEnabled(context)
        autoMoveToAudiobookFolder = persisted.autoMoveToAudiobookFolder
        importOnboardingCompleted = persisted.importOnboardingCompleted
        importGuideVisible = !persisted.importOnboardingCompleted
        persistedImportsLoaded = true
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
                        val ebook = savedBook.ebookUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                        if (audio == null) {
                            failedBooks += savedBook.title.ifBlank { savedBook.audioName }
                            return@forEach
                        }
                        runCatching {
                            val rebuilt = buildReaderBook(
                                audio = audio,
                                audioDisplayName = savedBook.audioName,
                                srt = srt,
                                srtDisplayName = savedBook.srtName,
                                ebook = ebook,
                                ebookDisplayName = savedBook.ebookName,
                                ebookFormat = savedBook.ebookFormat
                            )
                            val persistedTitle = savedBook.title.trim()
                            if (persistedTitle.isNotBlank()) {
                                rebuilt.copy(title = persistedTitle)
                            } else {
                                rebuilt
                            }
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

        dictionaryController.restorePersistedDictionaries(
            persistedRefs = persisted.dictionaries,
            onPersistDictionaryRefs = ::persistImportState
        )

        if (ankiPermissionGranted) {
            refreshAnkiCatalog()
        }
    }

    fun mainHoshiLookupOptions(showRangeSelection: Boolean = false): LookupPopupOptions =
        LookupPopupOptions(
            isVertical = false,
            isFullWidth = false,
            width = 320,
            height = 250,
            swipeToDismiss = true,
            swipeThreshold = 40,
            topInset = 0.0,
            bottomInset = navigationBarBottomInsetDp,
            dictionarySettings = loadDictionarySettings(context),
            darkMode = isDarkTheme,
            eInkMode = false,
            audioSettings = audiobookSettings,
            showRangeSelection = showRangeSelection,
            showPlayAudio = audiobookSettings.lookupPlaybackAudioEnabled,
            popupActionBar = true,
        )
    val mainHoshiLookupAssets = remember(context) { LookupPopupAssets.load(context) }

    fun mainHoshiFallbackSelection(query: String, anchor: ReaderLookupAnchor?): ReaderSelectionData {
        val densityScale = rootDensity.density.coerceAtLeast(0.1f)
        val bounds = anchor.boundingRectCoreOrNull() ?: Rect(
            left = view.width * 0.5f,
            top = view.height * 0.45f,
            right = view.width * 0.5f + 1f,
            bottom = view.height * 0.45f + 1f
        )
        return ReaderSelectionData(
            text = query,
            sentence = query,
            rect = ReaderSelectionRect(
                x = (bounds.left / densityScale).toDouble(),
                y = (bounds.top / densityScale).toDouble(),
                width = ((bounds.right - bounds.left) / densityScale).coerceAtLeast(1f).toDouble(),
                height = ((bounds.bottom - bounds.top) / densityScale).coerceAtLeast(1f).toDouble()
            ),
            normalizedOffset = 0,
            sentenceOffset = 0
        )
    }

    fun clearMainHoshiChildPopups() {
        mainHoshiLookupPopups.clear()
        mainHoshiLookupCue = null
        mainHoshiLookupSelectedRange = null
        mainHoshiLookupAudioUri = null
        mainHoshiLookupTitle = ""
    }

    fun currentStatisticsBookKey(): String? {
        val selected = selectedBookId?.let { id -> readerBooks.firstOrNull { it.id == id } }
            ?: readerBooks.firstOrNull()
        return selected?.let { buildReaderBookPlaybackKey(it) }
    }

    fun renderMainHoshiFirstLayerHtml(
        popup: LookupPopupItem,
        backgroundColorCss: String? = null
    ): String =
        LookupPopupHtml.render(
            results = popup.state.results,
            assets = mainHoshiLookupAssets,
            dictionaryStyles = popup.state.dictionaryStyles,
            settings = popup.state.dictionarySettings,
            audioSettings = audiobookSettings,
            showPlayAudio = audiobookSettings.lookupPlaybackAudioEnabled,
            backgroundColorCss = backgroundColorCss,
            darkMode = isDarkTheme,
            eInkMode = false,
        )

    fun createMainHoshiFirstLayerPopup(
        selection: ReaderSelectionData,
        showRangeSelection: Boolean = false
    ): Pair<LookupPopupItem, Int>? {
        return mainHoshiLookupSession.createPopup(
            selection = selection,
            options = mainHoshiLookupOptions(showRangeSelection = showRangeSelection),
        )
    }

    fun showDictionaryFirstLayerLookup(rawQuery: String) {
        val query = rawQuery.trim()
        lookupQuery = query
        if (query.isBlank()) {
            lookupLoading = false
            dictionaryFirstLayerHtml = ""
            dictionaryFirstLayerResults = emptyList()
            dictionaryFirstLayerClearSelectionSignal += 1
            clearMainHoshiChildPopups()
            return
        }
        lookupLoading = true
        runCatching {
            val selection = mainHoshiFallbackSelection(query, anchor = null)
            val popup = createMainHoshiFirstLayerPopup(selection = selection)
            if (popup == null) {
                dictionaryFirstLayerHtml = ""
                dictionaryFirstLayerResults = emptyList()
                dictionaryFirstLayerClearSelectionSignal += 1
                clearMainHoshiChildPopups()
                Log.d("MainHoshiResultPopup", "dictionary first-layer empty query='${query.take(32)}'")
                return@runCatching
            }
            dictionaryFirstLayerResults = popup.first.state.results
            dictionaryFirstLayerHtml = renderMainHoshiFirstLayerHtml(
                popup = popup.first,
                backgroundColorCss = dictionaryPageBackgroundCss
            )
            dictionaryFirstLayerClearSelectionSignal += 1
            clearMainHoshiChildPopups()
            mainHoshiLookupCue = null
            mainHoshiLookupSelectedRange = null
            mainHoshiLookupAudioUri = audioUri
            mainHoshiLookupTitle = query
            recordStatisticsLookup(context, currentStatisticsBookKey())
            Log.d("MainHoshiResultPopup", "dictionary first-layer applied query='${query.take(32)}' results=${popup.first.state.results.size}")
        }.onFailure { error ->
            dictionaryFirstLayerHtml = ""
            dictionaryFirstLayerResults = emptyList()
            dictionaryFirstLayerClearSelectionSignal += 1
            exportStatus = "Lookup failed: ${error.message ?: "unknown error"}"
        }
        lookupLoading = false
    }

    fun showCollectionFirstLayerLookup(
        selection: ReaderSelectionData,
        sourceRange: IntRange,
        cue: SubtitleCue?,
        audioForExport: Uri?,
    ) {
        val query = selection.text.trim()
        if (query.isBlank()) return
        runCatching {
            val popup = createMainHoshiFirstLayerPopup(selection = selection)
            if (popup == null) {
                collectionFirstLayerHtml = ""
                collectionFirstLayerResults = emptyList()
                collectionFirstLayerClearSelectionSignal += 1
                exportStatus = context.getString(R.string.bookreader_lookup_failed)
                Log.d("MainHoshiResultPopup", "collection first-layer empty query='${query.take(32)}'")
                return@runCatching
            }
            val matchedText = popup.first.state.results.firstOrNull()?.matched ?: query
            val selectedRange = matchedRangeFromSentenceOffset(
                sentence = selection.sentence,
                sentenceOffset = selection.sentenceOffset,
                matchedText = matchedText
            ) ?: sourceRange
            collectionLookupPreviewSelectedRange = selectedRange
            collectionFirstLayerResults = popup.first.state.results
            collectionFirstLayerHtml = renderMainHoshiFirstLayerHtml(popup.first)
            collectionFirstLayerClearSelectionSignal += 1
            clearMainHoshiChildPopups()
            mainHoshiLookupCue = cue
            mainHoshiLookupSelectedRange = selectedRange
            mainHoshiLookupAudioUri = audioForExport
            mainHoshiLookupTitle = query
            recordStatisticsLookup(context, currentStatisticsBookKey())
            Log.d("MainHoshiResultPopup", "collection first-layer applied query='${query.take(32)}' results=${popup.first.state.results.size}")
        }.onFailure { error ->
            collectionFirstLayerHtml = ""
            collectionFirstLayerResults = emptyList()
            collectionFirstLayerClearSelectionSignal += 1
            exportStatus = "Lookup failed: ${error.message ?: "unknown error"}"
        }
    }

    fun showMainHoshiLookup(
        selection: ReaderSelectionData,
        cue: SubtitleCue?,
        selectedRange: IntRange?,
        audioForExport: Uri?,
        titleForExport: String,
        showRangeSelection: Boolean = false
    ): Boolean {
        Log.d(
            "MainHoshiResultPopup",
            "showMainHoshiLookup start text='${selection.text.take(32)}' range=${selectedRange ?: "null"} rect=${selection.rect.x},${selection.rect.y} ${selection.rect.width}x${selection.rect.height} showRange=$showRangeSelection"
        )
        val popup = mainHoshiLookupSession.createPopup(
            selection = selection,
            options = mainHoshiLookupOptions(showRangeSelection = showRangeSelection),
        )
        if (popup == null) {
            Log.d("MainHoshiResultPopup", "showMainHoshiLookup empty text='${selection.text.take(32)}'")
            return false
        }
        mainHoshiLookupPopups.clear()
        mainHoshiLookupPopups.add(popup.first)
        mainHoshiLookupCue = cue
        mainHoshiLookupSelectedRange = selectedRange
        mainHoshiLookupAudioUri = audioForExport
        mainHoshiLookupTitle = titleForExport.ifBlank { selection.text }
        recordStatisticsLookup(context, currentStatisticsBookKey())
        Log.d(
            "MainHoshiResultPopup",
            "showMainHoshiLookup applied text='${selection.text.take(32)}' popupCount=${mainHoshiLookupPopups.size}"
        )
        return true
    }

    fun pushMainHoshiRecursiveLookup(selection: ReaderSelectionData): Boolean {
        Log.d(
            "MainHoshiResultPopup",
            "pushRecursiveLookup start text='${selection.text.take(32)}' rect=${selection.rect.x},${selection.rect.y} ${selection.rect.width}x${selection.rect.height}"
        )
        val popup = mainHoshiLookupSession.createPopup(
            selection = selection,
            options = mainHoshiLookupOptions(showRangeSelection = false),
        )
        if (popup == null) {
            Log.d("MainHoshiResultPopup", "pushRecursiveLookup empty text='${selection.text.take(32)}'")
            return false
        }
        mainHoshiLookupPopups.clear()
        mainHoshiLookupPopups.add(popup.first)
        Log.d(
            "MainHoshiResultPopup",
            "pushRecursiveLookup applied text='${selection.text.take(32)}' popupCount=${mainHoshiLookupPopups.size}"
        )
        return true
    }

    fun startMainHoshiLookup(request: MainLookupRequest): Boolean {
        Log.d("MainHoshiResultPopup", "startMainHoshiLookup request=${request::class.simpleName}")
        return when (request) {
            is MainLookupRequest.Cue -> {
                val cue = request.cue
                val selection = findMainLookupSelection(cue.text, request.offset) ?: return true
                val selectedToken = selection.text.trim().takeIf { it.isNotBlank() } ?: return true
                val readerSelection = request.anchor?.boundingRectCoreOrNull()?.let { anchorRect ->
                    createHoshiReaderSelectionFromCueTap(
                        cueText = cue.text,
                        cueIndex = 0,
                        cues = listOf(cue.toReaderSubtitleCue()),
                        offset = request.offset,
                        anchorRect = anchorRect,
                        density = rootDensity.density
                    )
                } ?: mainHoshiFallbackSelection(selectedToken, request.anchor).copy(
                    sentence = cue.text,
                    sentenceOffset = selection.range.first
                )
                val exportAudioUri = if (request.sourceBookTitle.isNullOrBlank()) {
                    audioUri
                } else {
                    readerBooks.firstOrNull { it.title == request.sourceBookTitle }?.audioUri ?: audioUri
                }
                showMainHoshiLookup(
                    selection = readerSelection,
                    cue = cue,
                    selectedRange = selection.range,
                    audioForExport = exportAudioUri,
                    titleForExport = selectedToken,
                    showRangeSelection = false
                )
                true
            }
            is MainLookupRequest.Candidates -> {
                val query = request.rawCandidates.firstOrNull()?.trim().orEmpty()
                if (query.isBlank()) return true
                showMainHoshiLookup(
                    selection = mainHoshiFallbackSelection(query, request.anchor),
                    cue = null,
                    selectedRange = null,
                    audioForExport = audioUri,
                    titleForExport = query,
                    showRangeSelection = false
                )
                true
            }
        }
    }

    fun startMainLookup(request: MainLookupRequest) {
        if (effectiveLookupDictionaries.isEmpty()) {
            exportStatus = context.getString(R.string.bookreader_lookup_no_dict)
            return
        }
        startMainHoshiLookup(request)
    }

    fun triggerMainHoshiQueryLookup(rawQuery: String) {
        val query = rawQuery.trim()
        lookupQuery = query
        if (query.isBlank()) {
            lookupLoading = false
            closeMainLookupPopup()
            return
        }
        if (effectiveLookupDictionaries.isEmpty()) {
            exportStatus = context.getString(R.string.bookreader_lookup_no_dict)
            return
        }
        showDictionaryFirstLayerLookup(query)
    }

    fun openMainLookupCuePreview(cue: SubtitleCue, sourceBookTitle: String? = null) {
        if (effectiveLookupDictionaries.isEmpty()) {
            exportStatus = context.getString(R.string.bookreader_lookup_no_dict)
            return
        }
        val exportAudioUri = if (sourceBookTitle.isNullOrBlank()) {
            audioUri
        } else {
            readerBooks.firstOrNull { it.title == sourceBookTitle }?.audioUri ?: audioUri
        }
        mainHoshiLookupPopups.clear()
        mainHoshiLookupCue = null
        mainHoshiLookupSelectedRange = null
        mainHoshiLookupAudioUri = null
        mainHoshiLookupTitle = ""
        collectionLookupPreviewVisible = true
        collectionLookupPreviewSentence = cue.text
        collectionLookupPreviewCue = cue
        collectionLookupPreviewSelectedRange = null
        collectionLookupPreviewAudioUri = exportAudioUri
        collectionFirstLayerHtml = ""
        collectionFirstLayerResults = emptyList()
        collectionFirstLayerClearSelectionSignal += 1
    }

    fun exportMainHoshiLookupEntryToAnkiAsync(content: String, onComplete: (Boolean) -> Unit) {
        scope.launch {
            android.util.Log.d(
                "AnkiExportDebug",
                "mainHoshiExport rawContentLen=${content.length} rawPrefix=${content.take(120)}"
            )
            val success = runCatching {
                val payload = runCatching { JSONObject(content) }.getOrNull() ?: run {
                    android.util.Log.d("AnkiExportDebug", "mainHoshiExport payloadParseFailed")
                    return@runCatching false
                }
                val expression = payload.optString("expression").trim().ifBlank {
                    payload.optString("matched").trim()
                }
                if (expression.isBlank()) {
                    android.util.Log.d(
                        "AnkiExportDebug",
                        "mainHoshiExport expressionBlank payloadKeys=${payload.keys().asSequence().joinToString(",")}"
                    )
                    return@runCatching false
                }
                val reading = payload.optString("reading").trim().takeIf { it.isNotBlank() }
                val glossary = payload.optString("glossary").trim().ifBlank {
                    payload.optString("glossaryFirst").trim().ifBlank { expression }
                }
                val frequency = payload.optString("frequenciesHtml").trim().ifBlank {
                    payload.optString("freqHarmonicRank").trim()
                }
                val pitch = payload.optString("pitchCategories").trim().ifBlank {
                    payload.optString("pitchPositions").trim()
                }
                val primaryDictionaryName = payload.optString("selectedDictionary").trim()
                val sourceCue = mainHoshiLookupCue
                val cueText = sourceCue?.text?.trim()?.takeIf { it.isNotBlank() }
                    ?: mainHoshiLookupTitle.trim().ifBlank { expression }
                val cue = sourceCue ?: SubtitleCue(startMs = 0L, endMs = 0L, text = cueText)
                val popupSelectionText = payload.optString("popupSelectionText").trim().takeIf { it.isNotBlank() }
                    ?: mainHoshiLookupSelectedRange?.let { range ->
                        val start = range.first.coerceIn(0, cue.text.length)
                        val endExclusive = (range.last + 1).coerceIn(start, cue.text.length)
                        if (endExclusive > start) cue.text.substring(start, endExclusive) else null
                    }?.trim()?.takeIf { it.isNotBlank() }
                val exportAudioUri = mainHoshiLookupAudioUri
                android.util.Log.d(
                    "AnkiExportDebug",
                    "mainHoshiExport payload expression=$expression reading=${reading.orEmpty()} dict=$primaryDictionaryName " +
                        "glossaryLen=${glossary.length} frequencyLen=${frequency.length} pitchLen=${pitch.length} " +
                        "popupSelectionLen=${popupSelectionText.orEmpty().length} audioUri=${exportAudioUri?.toString().orEmpty()} " +
                        "lookupCue=${sourceCue?.text.orEmpty().take(48)}"
                )
                val exportResult = withContext(Dispatchers.IO) {
                    val preparedLookupAudio = prepareLookupAudioForAnkiExport(
                        context = context,
                        term = expression,
                        reading = reading,
                        settings = audiobookSettings
                    )
                    try {
                        addLookupDefinitionToAnkiMain(
                            context = context,
                            cue = cue,
                            audioUri = exportAudioUri,
                            lookupAudioUri = preparedLookupAudio?.uri,
                            bookTitle = readerBooks.firstOrNull { it.audioUri == exportAudioUri }?.title,
                            entry = DictionaryEntry(
                                term = expression,
                                reading = reading,
                                definitions = listOf(glossary),
                                pitch = pitch.ifBlank { null },
                                frequency = frequency.ifBlank { null },
                                dictionary = primaryDictionaryName.ifBlank { expression }
                            ),
                            definition = glossary,
                            glossaryFirstHtml = payload.optString("glossaryFirst").trim().takeIf { it.isNotBlank() },
                            dictionaryCss = dictionaryCssByName[primaryDictionaryName],
                            groupedDictionaries = emptyList(),
                            popupSelectionText = popupSelectionText,
                            lookupTermOverride = expression
                        )
                    } finally {
                        preparedLookupAudio?.cleanup?.invoke()
                    }
                }
                val message = ankiExportResultMessage(context, exportResult)
                android.util.Log.d(
                    "AnkiExportDebug",
                    "mainHoshiExport result=${exportResult.javaClass.simpleName} message=${message.take(220)}"
                )
                if (message.isNotBlank() && exportResult !is AnkiExportResult.DuplicateSkipped) {
                    Toast.makeText(
                        context,
                        message.take(220),
                        if (exportResult == AnkiExportResult.Added) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                    ).show()
                }
                exportResult == AnkiExportResult.Added ||
                    exportResult is AnkiExportResult.DuplicateSkipped
            }.getOrElse { error ->
                android.util.Log.w("AnkiExportDebug", "mainHoshiExport async failed", error)
                false
            }
            onComplete(success)
        }
    }

    fun checkMainAnkiDuplicateAsync(expression: String, onComplete: (AnkiDuplicateCheckResult) -> Unit) {
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    checkAnkiDuplicateByFirstFieldAsync(context, expression)
                }
            }.getOrElse { error ->
                android.util.Log.w("AnkiExportDebug", "main duplicate async failed expression=${expression.take(32)}", error)
                AnkiDuplicateCheckResult()
            }
            onComplete(result)
        }
    }

    fun refreshLookupIfNeeded() {
        invalidateDictionaryLookupCaches()
        bumpDictionaryDataVersion(context)
        if (lookupQuery.isNotBlank()) {
            triggerMainHoshiQueryLookup(lookupQuery)
        }
    }

    val pickBookAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        keepReadPermission(context, uri)
        addBookAudioUri = uri
        addBookAudioName = queryDisplayName(contentResolver, uri)
        addBookEbookUri = null
        addBookEbookName = null
        addBookEbookFormat = null
    }

    val pickBookSrtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        keepReadPermission(context, uri)
        addBookSrtUri = uri
        addBookSrtName = queryDisplayName(contentResolver, uri)
    }

    val pickBookEbookLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (addBookAudioUri == null || addBookSrtUri == null) {
            exportStatus = context.getString(R.string.status_pick_audio_srt_before_ebook)
            return@rememberLauncherForActivityResult
        }
        keepReadPermission(context, uri)
        val displayName = queryDisplayName(contentResolver, uri)
        val format = inferLocalReaderBookFormat(displayName, contentResolver.getType(uri))
        if (format == null) {
            exportStatus = context.getString(R.string.status_pick_ebook_file)
            return@rememberLauncherForActivityResult
        }
        addBookEbookUri = uri
        addBookEbookName = displayName
        addBookEbookFormat = format
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
        addBookEbookUri = null
        addBookEbookName = null
        addBookEbookFormat = null
        persistImportState()
    }

    val pickDictionaryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        dictionaryController.importDictionaries(
            uris = uris,
            onPersistDictionaryRefs = ::persistImportState,
            onLookupDataChanged = ::refreshLookupIfNeeded
        )
    }

    val activeCue = remember(positionMs, srtCues) { findCueAtTime(srtCues, positionMs) }
    val selectedReaderBook = remember(readerBooks, selectedBookId) {
        readerBooks.firstOrNull { it.id == selectedBookId }
    }

    val dictionarySpecificVariableChoices = remember(loadedDictionaries) {
        loadedDictionaries.map { "{single-glossary-${it.name}}" }.distinct()
    }
    val fieldVariableChoices = remember(dictionarySpecificVariableChoices) {
        (FIELD_VARIABLE_CHOICES + dictionarySpecificVariableChoices).distinct()
    }
    val sliderValue = when {
        durationMs <= 0L -> 0f
        else -> (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    val visibleMdxMountState = remember(mdxExperimentalUnlocked, mdxMountState) {
        if (mdxExperimentalUnlocked) mdxMountState else MdxMountState()
    }
    val mountedDictionaryCount = visibleMdxMountState.entries.count { it.enabled }
    val dictionaryCount = loadedDictionaries.size + mountedDictionaryCount
    val cueLookupTokens = remember(activeCue?.text) {
        activeCue?.let { tokenizeLookupTerms(it.text).take(12) } ?: emptyList()
    }
    val mainPageScrollState = rememberScrollState()
    val dictionaryManagerScrollState = rememberScrollState()

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
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.home_refresh),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        FloatingActionButton(
                            onClick = {
                                ebookFeatureEnabled = loadEbookFeatureEnabled(context)
                                addBookDialogVisible = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Add,
                                contentDescription = stringResource(R.string.home_add_book),
                                modifier = Modifier.size(20.dp)
                            )
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
            NavigationBar(
                containerColor = hoshiBottomNavigationBackgroundColor(),
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = activeSection == MiningSection.MAIN,
                    onClick = { activeSection = MiningSection.MAIN },
                    // Visual glyph (intentional): keep as a symbol and do not localize.
                    icon = { Text("本") },
                    label = { Text(stringResource(R.string.nav_home)) }
                )
                NavigationBarItem(
                    selected = activeSection == MiningSection.DICTIONARY,
                    onClick = { activeSection = MiningSection.DICTIONARY },
                    // Visual glyph (intentional): keep as a symbol and do not localize.
                    icon = { Text("調") },
                    label = { Text(stringResource(R.string.nav_dictionary)) }
                )
                NavigationBarItem(
                    selected = activeSection == MiningSection.COLLECTIONS,
                    onClick = { activeSection = MiningSection.COLLECTIONS },
                    // Visual glyph (intentional): keep as a symbol and do not localize.
                    icon = { Text("蔵") },
                    label = { Text(stringResource(R.string.collections_title)) }
                )
                NavigationBarItem(
                    selected = activeSection == MiningSection.SETTINGS,
                    onClick = { activeSection = MiningSection.SETTINGS },
                    // Visual glyph (intentional): keep as a symbol and do not localize.
                    icon = { Text("設") },
                    label = { Text(stringResource(R.string.nav_settings)) }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            val mainContentModifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom = 16.dp
                )
                .let { baseModifier ->
                    if (activeSection == MiningSection.DICTIONARY) {
                        baseModifier
                    } else {
                        baseModifier.verticalScroll(mainPageScrollState)
                    }
                }
            Column(
                modifier = mainContentModifier,
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
                        if (isBookSelectionMode) {
                            OutlinedButton(onClick = { requestDeleteSelectedBooks() }) {
                                Text(stringResource(R.string.home_delete_selected, selectedBookIds.size))
                            }
                            OutlinedButton(onClick = { clearBookSelection() }) {
                                Text(stringResource(R.string.home_cancel_selection))
                            }
                        }
                        if (!isBookSelectionMode) {
                            OutlinedButton(
                                onClick = { enterBookSelectionMode() }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Checklist,
                                    contentDescription = stringResource(R.string.home_selected)
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    val nextView = if (homeLibraryView == HomeLibraryView.BOOKSHELF) {
                                        HomeLibraryView.LIST
                                    } else {
                                        HomeLibraryView.BOOKSHELF
                                    }
                                    homeLibraryView = nextView
                                    persistHomeLibraryView(nextView)
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
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = hoshiPanelBackgroundColor())
                    ) {
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
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                                                if (isBookSelectionMode) {
                                                    toggleBookSelection(book.id)
                                                } else {
                                                    openReaderBook(book, persist = true)
                                                }
                                            },
                                            onLongClick = {
                                                requestRenameBook(book)
                                            }
                                        ),
                                    colors = CardDefaults.cardColors(
                                        containerColor = Color.Transparent
                                    )
                                ) {
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .aspectRatio(1f)
                                                    .clip(RoundedCornerShape(12.dp))
                                            ) {
                                                if (book.coverUri != null) {
                                                    BookCoverThumbnail(
                                                        coverUri = book.coverUri,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                } else {
                                                    Surface(
                                                        modifier = Modifier.fillMaxSize(),
                                                        shape = RoundedCornerShape(10.dp),
                                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                                    ) {}
                                                }
                                                if (selected) {
                                                    Surface(
                                                        modifier = Modifier
                                                            .align(Alignment.BottomEnd)
                                                            .padding(6.dp),
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
                                                    ) {
                                                        Text(
                                                            text = stringResource(R.string.home_opened),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                                BookAttachmentBadges(
                                                    hasSubtitle = book.srtUri != null,
                                                    hasEbook = book.ebookUri != null,
                                                    modifier = Modifier
                                                        .align(Alignment.BottomStart)
                                                        .padding(6.dp)
                                                )
                                            }
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                color = Color.Transparent,
                                                shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                                            ) {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(start = 4.dp, end = 4.dp, top = 0.dp, bottom = 6.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        LinearProgressIndicator(
                                                            progress = { (playbackPercent / 100f).coerceIn(0f, 1f) },
                                                            modifier = Modifier.weight(1f).height(4.dp),
                                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                                                            trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                                        )
                                                        Text(
                                                            text = "$playbackPercent%",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    Text(
                                                        book.title,
                                                        style = MaterialTheme.typography.titleMedium,
                                                        minLines = 2,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    if (multiSelected) {
                                                        Text(stringResource(R.string.home_selected), color = MaterialTheme.colorScheme.primary)
                                                    }
                                                }
                                            }
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
                                        if (isBookSelectionMode) {
                                            toggleBookSelection(book.id)
                                        } else {
                                            openReaderBook(book, persist = true)
                                        }
                                    },
                                    onLongClick = {
                                        requestRenameBook(book)
                                    }
                                ),
                            colors = CardDefaults.cardColors(containerColor = hoshiCardBackgroundColor())
                        ) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    if (book.coverUri != null) {
                                        Box(
                                            modifier = Modifier
                                                .size(92.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                        ) {
                                            BookCoverThumbnail(
                                                coverUri = book.coverUri,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            if (!isBookSelectionMode && selected) {
                                                Surface(
                                                    modifier = Modifier
                                                        .align(Alignment.BottomEnd)
                                                        .padding(4.dp),
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.home_opened),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .heightIn(min = 92.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            book.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            minLines = 2,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        LinearProgressIndicator(
                                            progress = { (playbackPercent / 100f).coerceIn(0f, 1f) },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                                            trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                        )
                                        Spacer(modifier = Modifier.weight(1f, fill = true))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("$playbackPercent%")
                                            BookAttachmentBadges(
                                                hasSubtitle = book.srtUri != null,
                                                hasEbook = book.ebookUri != null
                                            )
                                        }
                                        if (multiSelected) {
                                            Text(stringResource(R.string.home_selected), color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (srtLoading || srtError != null || exportStatus != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = hoshiPanelBackgroundColor())
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (srtLoading) Text(stringResource(R.string.bookreader_parsing_srt))
                            if (srtError != null) {
                                Text(
                                    stringResource(R.string.bookreader_srt_error, srtError.orEmpty()),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            if (exportStatus != null) Text(exportStatus!!)
                        }
                    }
                }
            }

            if (activeSection == MiningSection.DICTIONARY) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .let { baseModifier ->
                            if (showDictionaryManager) {
                                baseModifier.verticalScroll(dictionaryManagerScrollState)
                            } else {
                                baseModifier
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (dictionaryUiConfig.showRichHomeDictionary) {
                        DictionaryManagementCard(
                            context = context,
                            dictionaryCount = dictionaryCount,
                            containerColor = hoshiPanelBackgroundColor(),
                            itemContainerColor = hoshiCardBackgroundColor(),
                            dictionaryLoading = dictionaryLoading,
                            dictionaryProgressText = dictionaryProgressText,
                            dictionaryProgressValue = dictionaryProgressValue,
                            dictionaryError = dictionaryError,
                            showDictionaryManager = showDictionaryManager,
                            showDictionaryDeleteActions = showDictionaryDeleteActions,
                            dictionaryRefs = dictionaryRefs,
                            dictionaryOrderIds = dictionaryOrderIds,
                            mdxMountState = visibleMdxMountState,
                            onImportClick = { pickDictionaryLauncher.launch(arrayOf("application/zip", "*/*")) },
                            onShowDictionaryManagerToggle = {
                                val nextShowDictionaryManager = !showDictionaryManager
                                showDictionaryManager = nextShowDictionaryManager
                                if (nextShowDictionaryManager) {
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                    lookupLoading = false
                                    dictionaryFirstLayerHtml = ""
                                    dictionaryFirstLayerResults = emptyList()
                                    dictionaryFirstLayerClearSelectionSignal += 1
                                    clearMainHoshiChildPopups()
                                }
                            },
                            onShowDictionaryDeleteActionsToggle = { showDictionaryDeleteActions = !showDictionaryDeleteActions },
                            onOpenMdxClick = if (mdxExperimentalUnlocked) {
                                {
                                    context.startActivity(Intent(context, MdxMountSettingsActivity::class.java))
                                }
                            } else {
                                null
                            },
                            onMoveCombinedDictionary = { fromIndex, toIndex ->
                                dictionaryController.moveCombinedDictionary(
                                    fromIndex = fromIndex,
                                    toIndex = toIndex,
                                    onLookupDataChanged = ::refreshLookupIfNeeded
                                )
                            },
                            onRemoveImportedDictionary = { index ->
                                dictionaryController.removeImportedDictionary(
                                    index = index,
                                    onPersistDictionaryRefs = ::persistImportState,
                                    onLookupDataChanged = ::refreshLookupIfNeeded
                                )
                            },
                            onRemoveMountedDictionary = { cacheKey ->
                                dictionaryController.removeMountedDictionary(
                                    cacheKey = cacheKey,
                                    onLookupDataChanged = ::refreshLookupIfNeeded
                                )
                            },
                            onSetImportedDictionaryEnabled = { dictionaryId, enabled ->
                                dictionaryController.setImportedDictionaryEnabled(
                                    dictionaryId = dictionaryId,
                                    enabled = enabled,
                                    onPersistDictionaryRefs = ::persistImportState,
                                    onLookupDataChanged = ::refreshLookupIfNeeded
                                )
                            },
                            onSetMountedDictionaryEnabled = { cacheKey, enabled ->
                                dictionaryController.setMountedDictionaryEnabled(
                                    cacheKey = cacheKey,
                                    enabled = enabled,
                                    onLookupDataChanged = ::refreshLookupIfNeeded
                                )
                            }
                        )
                    }

                    if (!showDictionaryManager) {
                        OutlinedTextField(
                            value = lookupQuery,
                            onValueChange = { lookupQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.dictionary_query_label)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    if (effectiveLookupDictionaries.isNotEmpty() && lookupQuery.isNotBlank()) {
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                        triggerMainHoshiQueryLookup(lookupQuery)
                                    }
                                }
                            )
                        )

                        if (lookupLoading) {
                            Text(stringResource(R.string.dictionary_querying))
                        }

                        if (dictionaryFirstLayerHtml.isNotBlank()) {
                            var dictionaryFirstLayerOrigin by remember { mutableStateOf(Offset.Zero) }
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .onGloballyPositioned { coordinates ->
                                        val bounds = coordinates.boundsInWindow()
                                        val densityScale = rootDensity.density.coerceAtLeast(0.1f)
                                        dictionaryFirstLayerOrigin = Offset(
                                            x = bounds.left / densityScale,
                                            y = bounds.top / densityScale
                                        )
                                    },
                                color = dictionaryPageBackground,
                            ) {
                                MainHoshiResultWebView(
                                    html = dictionaryFirstLayerHtml,
                                    results = dictionaryFirstLayerResults,
                                    clearSelectionSignal = dictionaryFirstLayerClearSelectionSignal,
                                    selectionOffsetX = dictionaryFirstLayerOrigin.x.toDouble(),
                                    selectionOffsetY = dictionaryFirstLayerOrigin.y.toDouble(),
                                    callbacks = PopupWebViewCallbacks(
                                        onTapOutside = { dictionaryFirstLayerClearSelectionSignal += 1 },
                                        onSwipeDismiss = { dictionaryFirstLayerClearSelectionSignal += 1 },
                                        onMineEntryAsync = { content, onComplete ->
                                            exportMainHoshiLookupEntryToAnkiAsync(content, onComplete)
                                        },
                                        onDuplicateCheckAsync = { expression, onComplete ->
                                            checkMainAnkiDuplicateAsync(expression, onComplete)
                                        },
                                        onViewDuplicate = { noteIds -> openAnkiDuplicateNotesInBrowser(context, noteIds) },
                                        onTextSelected = { selection ->
                                            val popup = createMainHoshiFirstLayerPopup(selection = selection)
                                            if (popup == null) {
                                                null
                                            } else {
                                                mainHoshiLookupPopups.clear()
                                                mainHoshiLookupPopups.add(popup.first)
                                                popup.second
                                            }
                                        },
                                        onLookupRedirect = { query ->
                                            val dictionarySettings = loadDictionarySettings(context)
                                            mainHoshiLookupSession.lookup(
                                                query,
                                                dictionarySettings.maxResults,
                                                dictionarySettings.scanLength,
                                            )
                                        },
                                        onLookupRedirected = { selection, results ->
                                            if (!pushMainHoshiRecursiveLookup(selection)) {
                                                Log.w(
                                                    "MainHoshiResultPopup",
                                                    "dictionary redirect failed to open recursive popup query='${selection.text.take(32)}' resultCount=${results.size}"
                                                )
                                            }
                                        },
                                        onPlayWordAudio = { _url, term, reading ->
                                            if (!term.isNullOrBlank()) {
                                                playLookupAudioForTerm(
                                                    context = context,
                                                    term = term,
                                                    reading = reading,
                                                    settings = audiobookSettings
                                                )
                                            }
                                        },
                                    ),
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }

            if (activeSection == MiningSection.COLLECTIONS) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = hoshiPanelBackgroundColor())
                ) {
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
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = hoshiSoftCardBackgroundColor())
                                ) {
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
                                                    openMainLookupCuePreview(
                                                        cue = SubtitleCue(
                                                            startMs = item.startMs,
                                                            endMs = item.endMs,
                                                            text = item.text
                                                        ),
                                                        sourceBookTitle = item.bookTitle
                                                    )
                                                },
                                                enabled = effectiveLookupDictionaries.isNotEmpty()
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsPanel(
                        selectedAppLanguageLabel = selectedAppLanguage.displayLabel(context),
                        versionName = resolveAppVersionName(context),
                        onAudiobookClick = { context.startActivity(Intent(context, AudiobookSettingsActivity::class.java)) },
                        onControlModeClick = { context.startActivity(Intent(context, ControlModeSettingsActivity::class.java)) },
                        onAudiobookUiClick = { context.startActivity(Intent(context, AudiobookUiSettingsActivity::class.java)) },
                        onFontClick = { context.startActivity(Intent(context, FontSettingsActivity::class.java)) },
                        onControllerClick = { context.startActivity(Intent(context, ControllerSettingsActivity::class.java)) },
                        onAnkiClick = { context.startActivity(Intent(context, AnkiSettingsActivity::class.java)) },
                        onDictionaryClick = { context.startActivity(Intent(context, DictionarySettingsActivity::class.java)) },
                        onAdvancedOverlayClick = { context.startActivity(Intent(context, OverlaySettingsActivity::class.java)) },
                        onAdvancedStatisticsClick = { context.startActivity(Intent(context, StatisticsDemoActivity::class.java)) },
                        onAdvancedOtherClick = { context.startActivity(Intent(context, OtherSettingsActivity::class.java)) },
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
                        onUpdateClick = { context.startActivity(Intent(context, UpdateSettingsActivity::class.java)) },
                        onVersionClick = {
                            val version = resolveAppVersionName(context)
                            Toast.makeText(context, context.getString(R.string.settings_version_toast, version), Toast.LENGTH_SHORT).show()
                            versionTapCount += 1
                            if (versionTapCount >= 5) {
                                versionTapCount = 0
                                if (!mdxExperimentalUnlocked) {
                                    saveMdxExperimentalUnlocked(context, true)
                                    mdxExperimentalUnlocked = true
                                }
                                showVersionEasterGif = true
                            }
                        }
                    )
                }
            }
        }

        if (renameBookDialogVisible) {
            AlertDialog(
                onDismissRequest = {
                    renameBookDialogVisible = false
                    renameTargetBookId = null
                },
                title = { Text(stringResource(R.string.home_rename_book)) },
                text = {
                    OutlinedTextField(
                        value = renameBookInput,
                        onValueChange = { renameBookInput = it.take(80) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.home_book_name)) }
                    )
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            renameBookDialogVisible = false
                            renameTargetBookId = null
                        }
                    ) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
                confirmButton = {
                    Button(onClick = { applyRenameBook() }) {
                        Text(stringResource(R.string.common_confirm))
                    }
                }
            )
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

        if (autoUpdatePromptVisible) {
            AutoUpdateFirstPromptDialog(
                onSkip = {
                    markAutoUpdateFirstPromptShown(context)
                    saveAutoUpdateEnabled(context, false)
                    autoUpdatePromptVisible = false
                },
                onEnable = {
                    markAutoUpdateFirstPromptShown(context)
                    saveAutoUpdateEnabled(context, true)
                    autoUpdatePromptVisible = false
                    mainActivity?.checkAppUpdateInBackground(force = true)
                }
            )
        }

        mainActivity?.launchUpdatePromptRelease?.let { release ->
            AppUpdateLaunchPromptDialog(
                releaseDisplayName = release.displayName,
                onDismiss = { mainActivity.dismissLaunchUpdatePrompt() },
                onDownload = { mainActivity.downloadLaunchUpdatePrompt(release) }
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
                ebookEnabled = ebookFeatureEnabled,
                ebookName = addBookEbookName,
                ebookUri = addBookEbookUri,
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
                    addBookEbookUri = null
                    addBookEbookName = null
                    addBookEbookFormat = null
                    persistImportState()
                },
                onPickAudio = {
                    pickBookAudioLauncher.launch(
                        arrayOf(
                            "audio/*",
                            "video/*",
                            "audio/mp4",
                            "audio/x-m4a",
                            "audio/m4a",
                            "audio/x-m4b",
                            "audio/m4b",
                            "application/mp4",
                            "video/mp4",
                            "video/x-matroska",
                            "video/webm",
                            "video/quicktime",
                            "video/x-msvideo",
                            "video/3gpp"
                        )
                    )
                },
                onPickSrt = {
                    pickBookSrtLauncher.launch(
                        arrayOf("application/x-subrip")
                    )
                },
                onPickEbook = {
                    pickBookEbookLauncher.launch(
                        arrayOf("application/epub+zip", "text/plain", "application/octet-stream")
                    )
                },
                onDismiss = { addBookDialogVisible = false },
                onConfirm = { confirmAddBookFromDialog() }
            )
        }

        if (collectionLookupPreviewVisible) {
            MainCollectionsHoshiPopup(
                previewSentence = collectionLookupPreviewSentence,
                selectedRange = collectionLookupPreviewSelectedRange,
                html = collectionFirstLayerHtml,
                results = collectionFirstLayerResults,
                clearSelectionSignal = collectionFirstLayerClearSelectionSignal,
                onClose = { closeMainLookupPopup() },
                onPreviewLookup = { offset, layout, coords ->
                    val selection = selectLookupScanText(collectionLookupPreviewSentence, offset)
                        ?: return@MainCollectionsHoshiPopup
                    val query = selection.text.trim()
                    if (query.isBlank()) return@MainCollectionsHoshiPopup
                    val anchor = if (layout != null && coords != null) {
                        val charOffset = selection.range.first
                            .coerceIn(0, (collectionLookupPreviewSentence.length - 1).coerceAtLeast(0))
                        val localRect = layout.getBoundingBox(charOffset)
                        val topLeft = coords.localToWindow(localRect.topLeft)
                        val bottomRight = coords.localToWindow(localRect.bottomRight)
                        ReaderLookupAnchor(
                            rects = listOf(
                                Rect(
                                    topLeft.x,
                                    topLeft.y,
                                    bottomRight.x,
                                    bottomRight.y
                                )
                            )
                        )
                    } else null
                    if (anchor == null) {
                        exportStatus = context.getString(R.string.bookreader_lookup_failed)
                        Log.w(
                            "MainHoshiResultPopup",
                            "Missing anchor for collection first-layer lookup; refusing to open popup"
                        )
                        return@MainCollectionsHoshiPopup
                    }
                    Log.d(
                        "MainHoshiResultPopup",
                        "collection first-layer anchor=${anchor.boundingRectCoreOrNull()?.let { "${it.left},${it.top},${it.right},${it.bottom}" }} " +
                            "query='${query.take(32)}' selection='${selection.text.take(32)}' selectedRange=${selection.range}"
                    )
                    showCollectionFirstLayerLookup(
                        selection = mainHoshiFallbackSelection(query, anchor).copy(
                            sentence = collectionLookupPreviewSentence,
                            sentenceOffset = selection.range.first
                        ),
                        sourceRange = selection.range,
                        cue = collectionLookupPreviewCue,
                        audioForExport = collectionLookupPreviewAudioUri
                    )
                },
                onResultTextSelected = { selection ->
                    val popup = createMainHoshiFirstLayerPopup(selection = selection)
                    if (popup == null) {
                        null
                    } else {
                        mainHoshiLookupPopups.clear()
                        mainHoshiLookupPopups.add(popup.first)
                        popup.second
                    }
                },
                onLookupRedirect = { query ->
                    val dictionarySettings = loadDictionarySettings(context)
                    mainHoshiLookupSession.lookup(
                        query,
                        dictionarySettings.maxResults,
                        dictionarySettings.scanLength,
                    )
                },
                onResultRedirected = { selection, results ->
                    if (!pushMainHoshiRecursiveLookup(selection)) {
                        Log.w(
                            "MainHoshiResultPopup",
                            "collection redirect failed to open recursive popup query='${selection.text.take(32)}' resultCount=${results.size}"
                        )
                    }
                },
                onTapOutside = {
                    collectionFirstLayerClearSelectionSignal += 1
                },
                onMineEntry = { false },
                onMineEntryAsync = { content, onComplete ->
                    exportMainHoshiLookupEntryToAnkiAsync(content, onComplete)
                },
                onDuplicateCheck = { AnkiDuplicateCheckResult() },
                onDuplicateCheckAsync = { expression, onComplete ->
                    checkMainAnkiDuplicateAsync(expression, onComplete)
                },
                onViewDuplicate = { noteIds -> openAnkiDuplicateNotesInBrowser(context, noteIds) },
                onPlayWordAudio = { term, reading ->
                    if (!term.isNullOrBlank()) {
                        playLookupAudioForTerm(
                            context = context,
                            term = term,
                            reading = reading,
                            settings = audiobookSettings
                        )
                    }
                },
            )
        }

        LookupPopupStackView(
            popups = mainHoshiLookupPopups,
            onPopupsChange = { next ->
                mainHoshiLookupPopups.clear()
                mainHoshiLookupPopups.addAll(next)
                if (next.isEmpty()) {
                    mainHoshiLookupCue = null
                    mainHoshiLookupSelectedRange = null
                    mainHoshiLookupAudioUri = null
                    mainHoshiLookupTitle = ""
                }
            },
            lookupChildPopup = { selection ->
                mainHoshiLookupSession.createPopup(
                    selection = selection,
                    options = mainHoshiLookupOptions(showRangeSelection = false),
                )
            },
            onLookupRedirect = { query ->
                val dictionarySettings = loadDictionarySettings(context)
                mainHoshiLookupSession.lookup(
                    query,
                    dictionarySettings.maxResults,
                    dictionarySettings.scanLength,
                )
            },
            onPlayWordAudio = { _url, term, reading ->
                if (!term.isNullOrBlank()) {
                    playLookupAudioForTerm(
                        context = context,
                        term = term,
                        reading = reading,
                        settings = audiobookSettings
                    )
                }
            },
            onMineEntryAsync = { content, onComplete ->
                exportMainHoshiLookupEntryToAnkiAsync(content, onComplete)
            },
            onDuplicateCheckAsync = { expression, onComplete ->
                checkMainAnkiDuplicateAsync(expression, onComplete)
            },
            onViewDuplicate = { noteIds -> openAnkiDuplicateNotesInBrowser(context, noteIds) },
            onCloseAll = {
                clearMainHoshiChildPopups()
            },
            modifier = Modifier.fillMaxSize(),
            onRootPopupDismissed = {
                clearMainHoshiChildPopups()
            },
        )

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
                    }
                }
            )
        }
    }
}

internal fun resolveAppVersionName(context: Context): String {
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
        packageInfo.longVersionCode
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
        appendLine("[Reader Playback]")
        appendLine(buildReaderPlaybackDiagnostics(context, persistedImports))
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
        appendLine("[Recent Reader Logs]")
        appendLine(extractRecentReaderLogs(recentLogs))
        appendLine()
        appendLine("[Recent Logs]")
        appendLine(recentLogs.ifBlank { "(no recent logs captured)" })
    }
}

private fun buildReaderPlaybackDiagnostics(
    context: Context,
    persistedImports: PersistedImports
): String {
    if (persistedImports.books.isEmpty()) return "(no imported books)"
    return persistedImports.books.take(20).joinToString(separator = "\n") { persistedBook ->
        val readerBook = persistedBook.toReaderBookOrNull()
        if (readerBook == null) {
            "Book=${persistedBook.title.ifBlank { persistedBook.audioName }} source=invalid-audio-uri"
        } else {
            val candidates = loadReaderBookPlaybackSnapshotCandidates(context, readerBook)
            val best = candidates.maxWithOrNull(
                compareBy<ReaderBookPlaybackSnapshotCandidate> { it.snapshot.updatedAtMs }
                    .thenBy { if (it.source == "shared") 1 else 0 }
            )
            val candidateSummary = candidates.joinToString(separator = ",") { candidate ->
                "${candidate.source}:${candidate.snapshot.positionMs}/${candidate.snapshot.durationMs}@${candidate.snapshot.updatedAtMs}"
            }.ifBlank { "none" }
            "Book=${readerBook.title.ifBlank { readerBook.audioName }.take(48)} " +
                "bestSource=${best?.source ?: "none"} " +
                "bestPositionMs=${best?.snapshot?.positionMs ?: 0L} " +
                "bestDurationMs=${best?.snapshot?.durationMs ?: 0L} " +
                "candidates=$candidateSummary"
        }
    }
}

private fun PersistedReaderBook.toReaderBookOrNull(): ReaderBook? {
    val parsedAudioUri = audioUri.trim().takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        ?: return null
    val parsedSrtUri = srtUri?.trim()?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
    val parsedEbookUri = ebookUri?.trim()?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
    return ReaderBook(
        id = id,
        title = title,
        audioUri = parsedAudioUri,
        audioName = audioName,
        srtUri = parsedSrtUri,
        srtName = srtName,
        ebookUri = parsedEbookUri,
        ebookName = ebookName,
        ebookFormat = ebookFormat,
        coverUri = null
    )
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

private fun extractRecentReaderLogs(recentLogs: String): String {
    val interestingTags = listOf(
        "BookReaderBack",
        "BookReaderSeek",
        "MainReaderRestore",
        "LegadoAudioProgress",
        "LegadoMatch",
        "ReaderPausedSeek",
        "FloatingSubtitleScroll",
        "FloatingSubtitleRender",
        "BookLookupTap"
    )
    return recentLogs
        .lineSequence()
        .filter { line -> interestingTags.any { tag -> line.contains(tag) } }
        .joinToString(separator = "\n")
        .ifBlank { "(no recent reader/subtitle logs captured)" }
}

private data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

private fun SubtitleCue.toReaderSubtitleCue(): ReaderSubtitleCue {
    return ReaderSubtitleCue(
        startMs = startMs,
        endMs = endMs,
        text = text
    )
}

@Composable
private fun BookAttachmentBadges(
    hasSubtitle: Boolean,
    hasEbook: Boolean,
    modifier: Modifier = Modifier
) {
    if (!hasSubtitle && !hasEbook) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (hasSubtitle) {
            BookAttachmentBadge(
                icon = Icons.Outlined.ClosedCaption,
                contentDescription = stringResource(R.string.home_subtitle_attached)
            )
        }
        if (hasEbook) {
            BookAttachmentBadge(
                icon = Icons.Outlined.Book,
                contentDescription = stringResource(R.string.home_ebook_attached)
            )
        }
    }
}

@Composable
private fun BookAttachmentBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(4.dp)
                .size(16.dp)
        )
    }
}

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
    val ebookUri: Uri?,
    val ebookName: String?,
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
    val srtName: String?,
    val ebookUri: Uri?,
    val ebookName: String?,
    val ebookFormat: String?
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
    srtSourceName: String?,
    ebookSourceUri: Uri?,
    ebookSourceName: String?
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
    val copiedEbook = ebookSourceUri?.let { sourceUri ->
        val ebookDisplayName = ebookSourceName?.trim().takeUnless { it.isNullOrBlank() }
            ?: queryDisplayName(contentResolver, sourceUri)
        copyUriIntoFolder(
            context = context,
            contentResolver = contentResolver,
            parentFolder = audFolder,
            sourceUri = sourceUri,
            preferredDisplayName = ebookDisplayName
        )
    }

    val warnings = mutableListOf<String>()
    if (!deleteSourceUri(context, contentResolver, audioSourceUri)) {
        warnings += context.getString(R.string.error_audio_delete_failed)
    }
    if (srtSourceUri != null && !deleteSourceUri(context, contentResolver, srtSourceUri)) {
        warnings += context.getString(R.string.error_srt_delete_failed)
    }
    if (ebookSourceUri != null && !deleteSourceUri(context, contentResolver, ebookSourceUri)) {
        warnings += context.getString(R.string.error_ebook_delete_failed)
    }

    return RelocatedBookFiles(
        folderName = audFolder.name?.ifBlank { "Aud" } ?: "Aud",
        audioUri = copiedAudio.uri,
        audioName = copiedAudio.displayName,
        srtUri = copiedSrt?.uri,
        srtName = copiedSrt?.displayName,
        ebookUri = copiedEbook?.uri,
        ebookName = copiedEbook?.displayName,
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
            val ebookFile = files.firstOrNull { isEbookDocumentFile(it) }
            if (audioFile == null) {
                skippedFolders += folderName
                return@forEach
            }

            val audioName = audioFile.name?.trim().takeUnless { it.isNullOrBlank() }
                ?: queryDisplayName(contentResolver, audioFile.uri)
            val srtName = srtFile?.name?.trim()?.takeUnless { it.isNullOrBlank() }
                ?: srtFile?.let { queryDisplayName(contentResolver, it.uri) }
            val ebookName = ebookFile?.name?.trim()?.takeUnless { it.isNullOrBlank() }
                ?: ebookFile?.let { queryDisplayName(contentResolver, it.uri) }
            val ebookFormat = ebookName?.let { inferLocalReaderBookFormat(it, ebookFile?.type) }

            books += FolderBookCandidate(
                folderName = folderName,
                audioUri = audioFile.uri,
                audioName = audioName,
                srtUri = srtFile?.uri,
                srtName = srtName,
                ebookUri = ebookFile?.uri,
                ebookName = ebookName,
                ebookFormat = ebookFormat
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
    if (extension in setOf(
            "m4b", "m4a", "mp3", "aac", "flac", "wav", "ogg", "opus",
            "mp4", "m4v", "mkv", "webm", "mov", "avi", "3gp", "ts"
        )
    ) {
        return true
    }
    return mime.startsWith("audio/") || mime.startsWith("video/") || mime == "application/mp4"
}

private fun isSrtDocumentFile(file: DocumentFile): Boolean {
    val name = file.name?.trim().orEmpty().lowercase(Locale.ROOT)
    val mime = file.type?.lowercase(Locale.ROOT).orEmpty()
    if (name.endsWith(".srt")) return true
    return mime == "application/x-subrip" || mime.contains("subrip")
}

private fun isEbookDocumentFile(file: DocumentFile): Boolean {
    val displayName = file.name?.trim().orEmpty()
    if (displayName.endsWith(".srt", ignoreCase = true)) return false
    return inferLocalReaderBookFormat(displayName, file.type) != null
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
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "3gp" -> "video/3gpp"
        "ts" -> "video/mp2t"
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
    glossaryFirstHtml: String? = null,
    dictionaryCss: String?,
    groupedDictionaries: List<GroupedLookupDictionary> = emptyList(),
    popupSelectionText: String? = null,
    lookupTermOverride: String? = null
): AnkiExportResult {
    android.util.Log.d(
        "AnkiExportDebug",
        "mainExport start term=${entry.term} dict=${entry.dictionary} groupedCount=${groupedDictionaries.size} grouped=${groupedDictionaries.joinToString("|") { it.dictionary }}"
    )
    val persistedConfig = loadPersistedAnkiConfig(context)
    val preparedExport = prepareAnkiExport(
        context = context,
        persistedConfig = persistedConfig,
        audioUri = audioUri,
        lookupAudioUri = lookupAudioUri
    )

    val exportWord = popupSelectionText?.trim()?.takeIf { it.isNotBlank() }
        ?: lookupTermOverride?.trim()?.takeIf { it.isNotBlank() }
        ?: entry.term
    val card = MinedCard(
        word = exportWord,
        popupSelectionText = popupSelectionText,
        sentence = cue.text,
        bookTitle = bookTitle,
        reading = entry.reading,
        definitions = listOf(definition),
        dictionaryName = entry.dictionary,
        dictionaryCss = dictionaryCss,
        glossaryByDictionary = groupedDictionaries
            .map { dictionaryGroup ->
                MinedDictionaryGlossary(
                    dictionaryName = dictionaryGroup.dictionary,
                    definitions = dictionaryGroup.definitions,
                    dictionaryCss = dictionaryGroup.css
                )
            }
            .filter { it.dictionaryName.isNotBlank() && it.definitions.isNotEmpty() },
        pitch = entry.pitch,
        frequency = entry.frequency,
        cueStartMs = cue.startMs,
        cueEndMs = cue.endMs,
        audioUri = audioUri,
        lookupAudioUri = lookupAudioUri,
        audioTagOnly = true,
        requireCueAudioClip = audioUri != null
    )
    android.util.Log.d(
        "AnkiExportDebug",
        "mainExport card word=${card.word} primaryDict=${card.dictionaryName.orEmpty()} glossaryByDict=${card.glossaryByDictionary.joinToString("|") { "${it.dictionaryName}:${it.definitions.size}" }}"
    )

    return exportToAnkiDroidApiResult(context, card, preparedExport.config)
}

internal fun buildMainHighlightedText(text: String, selectedRange: IntRange?): AnnotatedString {
    return buildAnnotatedString {
        append(text)
        val range = selectedRange ?: return@buildAnnotatedString
        val start = range.first.coerceIn(0, text.length)
        val endExclusive = (range.last + 1).coerceIn(start, text.length)
        if (endExclusive <= start) return@buildAnnotatedString
        addStyle(SpanStyle(background = Color(0x1FA0A0A0)), start, endExclusive)
    }
}

@Composable
internal fun MainLookupClickableSentence(
    text: AnnotatedString,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    onTextTap: (offset: Int) -> Unit,
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = text,
        style = style,
        modifier = modifier.pointerInput(text, onTextTap) {
            detectTapGestures { tapOffset ->
                val layout = textLayoutResult ?: return@detectTapGestures
                val textLength = text.text.length
                if (textLength <= 0) return@detectTapGestures
                val offset = layout.getOffsetForPosition(tapOffset).coerceIn(0, textLength - 1)
                onTextTap(offset)
            }
        },
        onTextLayout = {
            textLayoutResult = it
            onTextLayout(it)
        }
    )
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
    val srtUri = sourceIntent
        .getStringExtra(BookReaderActivity.EXTRA_RETURN_SRT_URI)
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    val positionMs = sourceIntent.getLongExtra(BookReaderActivity.EXTRA_RETURN_POSITION_MS, -1L)
    val durationMs = sourceIntent.getLongExtra(BookReaderActivity.EXTRA_RETURN_DURATION_MS, -1L)
    sourceIntent.removeExtra(BookReaderActivity.EXTRA_RETURN_AUDIO_URI)
    sourceIntent.removeExtra(BookReaderActivity.EXTRA_RETURN_SRT_URI)
    sourceIntent.removeExtra(BookReaderActivity.EXTRA_RETURN_POSITION_MS)
    sourceIntent.removeExtra(BookReaderActivity.EXTRA_RETURN_DURATION_MS)
    if (audioUri.isBlank()) return null
    return ReturnedBookProgress(
        audioUri = audioUri,
        srtUri = srtUri,
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
