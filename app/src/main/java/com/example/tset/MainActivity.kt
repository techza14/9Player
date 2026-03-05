package com.example.tset

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Html
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.tset.ui.theme.TsetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TsetTheme {
                ReaderSyncScreen()
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

private val FIELD_VARIABLE_CHOICES = listOf(
    "",
    "{expression}",
    "{word}",
    "{reading}",
    "{furigana-plain}",
    "{audio}",
    "{audioTag}",
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
    "{book-cover}",
    "{search-query}"
)

private data class ReaderBook(
    val id: String,
    val title: String,
    val audioUri: Uri,
    val audioName: String,
    val srtUri: Uri,
    val srtName: String,
    val cues: List<SubtitleCue>
)

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ReaderSyncScreen() {
    val context = LocalContext.current
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
    var selectedBookId by remember { mutableStateOf<String?>(null) }
    var homeLibraryView by remember { mutableStateOf(HomeLibraryView.BOOKSHELF) }
    var addBookDialogVisible by remember { mutableStateOf(false) }
    var addBookAudioUri by remember { mutableStateOf<Uri?>(null) }
    var addBookAudioName by remember { mutableStateOf<String?>(null) }
    var addBookSrtUri by remember { mutableStateOf<Uri?>(null) }
    var addBookSrtName by remember { mutableStateOf<String?>(null) }
    val selectedBookIds = remember { mutableStateListOf<String>() }

    var loadedDictionaries by remember { mutableStateOf<List<LoadedDictionary>>(emptyList()) }
    var dictionaryRefs by remember { mutableStateOf<List<PersistedDictionaryRef>>(emptyList()) }
    var dictionaryLoading by remember { mutableStateOf(false) }
    var dictionaryProgressText by remember { mutableStateOf<String?>(null) }
    var dictionaryProgressValue by remember { mutableStateOf<Float?>(null) }
    var dictionaryError by remember { mutableStateOf<String?>(null) }
    var mecabDictionaryRef by remember { mutableStateOf<PersistedMecabDictionaryRef?>(null) }
    var mecabTokenizer by remember { mutableStateOf<MecabTokenizer?>(null) }

    var lookupQuery by remember { mutableStateOf("") }
    var lookupResults by remember { mutableStateOf<List<DictionarySearchResult>>(emptyList()) }
    var lookupLoading by remember { mutableStateOf(false) }
    var selectedEntryKey by remember { mutableStateOf<String?>(null) }
    val expandedResultKeys = remember { mutableStateMapOf<String, Boolean>() }

    var exportStatus by remember { mutableStateOf<String?>(null) }
    var pendingAnkiCard by remember { mutableStateOf<MinedCard?>(null) }
    var ankiPermissionGranted by remember { mutableStateOf(hasAnkiReadWritePermission(context)) }
    var ankiDeckName by remember { mutableStateOf("Default") }
    var ankiModelName by remember { mutableStateOf("") }
    var ankiTagsInput by remember { mutableStateOf("tset") }
    var ankiDecks by remember { mutableStateOf<List<String>>(emptyList()) }
    var ankiModels by remember { mutableStateOf<List<AnkiModelTemplate>>(emptyList()) }
    var ankiModelFields by remember { mutableStateOf<List<String>>(emptyList()) }
    val ankiFieldTemplates = remember { mutableStateMapOf<String, String>() }
    var ankiLoading by remember { mutableStateOf(false) }
    var ankiError by remember { mutableStateOf<String?>(null) }

    var showDictionaryManager by remember { mutableStateOf(false) }
    var activeSection by remember { mutableStateOf(MiningSection.MAIN) }
    var collectedCues by remember { mutableStateOf<List<BookReaderCollectedCue>>(emptyList()) }
    var mainLookupPopupVisible by remember { mutableStateOf(false) }
    var mainLookupPopupTitle by remember { mutableStateOf("") }
    var mainLookupPopupResults by remember { mutableStateOf<List<DictionarySearchResult>>(emptyList()) }
    var mainLookupPopupSelectedKey by remember { mutableStateOf<String?>(null) }
    var mainLookupPopupLoading by remember { mutableStateOf(false) }
    var mainLookupPopupError by remember { mutableStateOf<String?>(null) }
    var mainLookupPopupCue by remember { mutableStateOf<SubtitleCue?>(null) }
    var mainLookupPopupSelectedRange by remember { mutableStateOf<IntRange?>(null) }

    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableStateOf<Float?>(null) }

    val player = remember(context) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = if (player.duration > 0L) player.duration else 0L
            delay(200L)
        }
    }

    LaunchedEffect(audioUri) {
        val selectedAudio = audioUri ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(selectedAudio))
        player.prepare()
        player.pause()
        player.seekTo(0L)
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
        savePersistedImports(
            context = context,
            state = PersistedImports(
                audioUri = audioUri?.toString(),
                audioName = audioName,
                srtUri = srtUri?.toString(),
                srtName = srtName,
                dictionaries = dictionaryRefs,
                mecabDictionary = mecabDictionaryRef
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

    fun replaceTokenizer(next: MecabTokenizer?) {
        val previous = mecabTokenizer
        if (previous === next) return
        previous?.close()
        mecabTokenizer = next
    }

    fun buildReaderBook(
        audio: Uri,
        audioDisplayName: String?,
        srt: Uri,
        srtDisplayName: String?,
        cues: List<SubtitleCue>
    ): ReaderBook {
        val resolvedAudioName = audioDisplayName?.takeIf { it.isNotBlank() }
            ?: queryDisplayName(contentResolver, audio)
        val resolvedSrtName = srtDisplayName?.takeIf { it.isNotBlank() }
            ?: queryDisplayName(contentResolver, srt)
        val title = buildBookTitle(resolvedAudioName, resolvedSrtName)
        val id = buildDictionaryCacheKey(
            uri = "book|${audio}|${srt}",
            displayName = "$resolvedAudioName|$resolvedSrtName"
        )
        return ReaderBook(
            id = id,
            title = title,
            audioUri = audio,
            audioName = resolvedAudioName,
            srtUri = srt,
            srtName = resolvedSrtName,
            cues = cues
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
        if (persist) {
            persistImportState()
        }
    }

    fun openReaderBook(book: ReaderBook, persist: Boolean = true) {
        activateReaderBook(book, persist = persist)
        val intent = Intent(context, BookReaderActivity::class.java).apply {
            putExtra(BookReaderActivity.EXTRA_BOOK_TITLE, book.title)
            putExtra(BookReaderActivity.EXTRA_AUDIO_URI, book.audioUri.toString())
            putExtra(BookReaderActivity.EXTRA_SRT_URI, book.srtUri.toString())
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

    fun deleteSelectedBooks() {
        if (selectedBookIds.isEmpty()) return
        val removeIds = selectedBookIds.toSet()
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
                persistImportState()
            }
        }
        clearBookSelection()
        exportStatus = "Deleted selected books."
    }

    fun confirmAddBookFromDialog() {
        val pickedAudio = addBookAudioUri
        val pickedSrt = addBookSrtUri
        if (pickedAudio == null || pickedSrt == null) {
            exportStatus = "Please select both audio and SRT."
            return
        }
        val pickedAudioName = addBookAudioName
        val pickedSrtName = addBookSrtName
        scope.launch {
            srtLoading = true
            srtError = null
            val importResult = withContext(Dispatchers.IO) {
                runCatching {
                    val importedAudio = importBookFileToLocalStorage(
                        context = context,
                        contentResolver = contentResolver,
                        sourceUri = pickedAudio,
                        sourceDisplayName = pickedAudioName,
                        typeLabel = "audio",
                        fallbackExtension = "m4a"
                    )
                    val importedSrt = importBookFileToLocalStorage(
                        context = context,
                        contentResolver = contentResolver,
                        sourceUri = pickedSrt,
                        sourceDisplayName = pickedSrtName,
                        typeLabel = "srt",
                        fallbackExtension = "srt"
                    )
                    val cues = parseSrt(contentResolver, importedSrt.uri)
                    Triple(importedAudio, importedSrt, cues)
                }
            }
            srtLoading = false
            importResult.onSuccess { (importedAudio, importedSrt, cues) ->
                val book = buildReaderBook(
                    audio = importedAudio.uri,
                    audioDisplayName = importedAudio.displayName,
                    srt = importedSrt.uri,
                    srtDisplayName = importedSrt.displayName,
                    cues = cues
                )
                upsertReaderBook(book, activate = true)
                addBookDialogVisible = false
                addBookAudioUri = null
                addBookAudioName = null
                addBookSrtUri = null
                addBookSrtName = null
                exportStatus = "Added book: ${book.title}"
            }.onFailure { error ->
                srtError = error.message ?: "Failed to parse SRT"
                exportStatus = "Failed to add new book."
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

        val restoredAudioRaw = persisted.audioUri?.let { rawUri ->
            runCatching { Uri.parse(rawUri) }.getOrNull()
        }
        val restoredSrtRaw = persisted.srtUri?.let { rawUri ->
            runCatching { Uri.parse(rawUri) }.getOrNull()
        }

        if (restoredAudioRaw != null && restoredSrtRaw != null) {
            srtLoading = true
            srtError = null
            val restoreResult = withContext(Dispatchers.IO) {
                runCatching {
                    val localBookFiles = ensureBookFilesInLocalStorage(
                        context = context,
                        contentResolver = contentResolver,
                        audioUri = restoredAudioRaw,
                        audioDisplayName = persisted.audioName,
                        srtUri = restoredSrtRaw,
                        srtDisplayName = persisted.srtName
                    )
                    val cues = parseSrt(contentResolver, localBookFiles.srt.uri)
                    localBookFiles to cues
                }
            }
            srtLoading = false
            restoreResult.onSuccess { (localFiles, cues) ->
                audioUri = localFiles.audio.uri
                audioName = localFiles.audio.displayName
                srtUri = localFiles.srt.uri
                srtName = localFiles.srt.displayName
                srtCues = cues
                persistImportState()
            }.onFailure { error ->
                audioUri = null
                audioName = null
                srtUri = null
                srtName = null
                srtCues = emptyList()
                srtError = "Failed to restore book files. Please re-add audio and SRT. ${error.message ?: "unknown error"}"
                exportStatus = "Saved book file permission expired. Re-add audio and SRT."
            }
        }

        val restoredAudio = audioUri
        val restoredSrt = srtUri
        if (restoredAudio != null && restoredSrt != null && srtCues.isNotEmpty()) {
            val restoredBook = buildReaderBook(
                audio = restoredAudio,
                audioDisplayName = audioName,
                srt = restoredSrt,
                srtDisplayName = srtName,
                cues = srtCues
            )
            readerBooks = listOf(restoredBook)
            selectedBookId = restoredBook.id
        }

        persisted.mecabDictionary?.let { ref ->
            val cachedTokenizer = withContext(Dispatchers.IO) {
                loadInstalledMecabTokenizer(context, ref.name, ref.cacheKey)
            }
            if (cachedTokenizer != null) {
                replaceTokenizer(cachedTokenizer)
                mecabDictionaryRef = ref
            } else {
                val restoredUri = runCatching { Uri.parse(ref.uri) }.getOrNull()
                if (restoredUri != null) {
                    val installResult = withContext(Dispatchers.IO) {
                        runCatching {
                            installMecabDictionaryZip(
                                context = context,
                                contentResolver = contentResolver,
                                uri = restoredUri,
                                displayName = ref.name,
                                cacheKey = ref.cacheKey
                            )
                        }
                    }
                    installResult.onSuccess { installed ->
                        replaceTokenizer(installed.tokenizer)
                        mecabDictionaryRef = PersistedMecabDictionaryRef(
                            uri = ref.uri,
                            name = installed.name,
                            cacheKey = installed.cacheKey
                        )
                    }.onFailure { error ->
                        dictionaryError = "Failed to restore Sudachi dictionary ${ref.name}: ${error.message ?: "unknown error"}"
                    }
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
            .flatMap { extractLookupCandidatesWithMecab(it, mecabTokenizer) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(6)
    }

    fun computeLookupResults(
        dictionaries: List<LoadedDictionary>,
        candidates: List<String>
    ): List<DictionarySearchResult> {
        val merged = linkedMapOf<String, Pair<DictionaryEntry, Int>>()
        candidates.forEachIndexed { index, candidate ->
            val candidateBoost = (candidates.size - index).coerceAtLeast(1) * 2
            searchDictionarySql(context, dictionaries, candidate, MAX_LOOKUP_RESULTS).forEach { hit ->
                val key = entryStableKey(hit.entry)
                val boostedScore = hit.score + candidateBoost
                val existing = merged[key]
                if (existing == null || boostedScore > existing.second) {
                    merged[key] = hit.entry to boostedScore
                }
            }
        }

        return merged.values
            .map { (entry, score) -> DictionarySearchResult(entry = entry, score = score) }
            .sortedWith(
                compareByDescending<DictionarySearchResult> { it.score }
                    .thenBy { it.entry.term.length }
                    .thenBy { it.entry.term }
            )
            .take(MAX_LOOKUP_RESULTS)
    }

    fun triggerLookupCandidates(rawCandidates: List<String>) {
        val candidates = normalizeLookupCandidates(rawCandidates)
        if (candidates.isEmpty()) {
            lookupResults = emptyList()
            selectedEntryKey = null
            lookupLoading = false
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
        }
    }

    fun triggerMainCueLookup(cue: SubtitleCue, offset: Int) {
        if (loadedDictionaries.isEmpty()) {
            exportStatus = "Import dictionary first."
            return
        }

        val selectionRange = findMainLookupSelectionRange(cue.text, offset, mecabTokenizer)
        mainLookupPopupSelectedRange = selectionRange
        mainLookupPopupCue = cue

        val selectedToken = selectionRange?.let { range ->
            val start = range.first.coerceIn(0, cue.text.length)
            val endExclusive = (range.last + 1).coerceIn(start, cue.text.length)
            cue.text.substring(start, endExclusive).trim()
        }?.takeIf { it.isNotBlank() }

        val candidates = buildList {
            selectedToken?.let { add(it) }
            addAll(
                extractLookupCandidatesAtWithMecab(
                    text = cue.text,
                    charOffset = offset,
                    tokenizer = mecabTokenizer
                )
            )
        }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(6)

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
        mainLookupPopupTitle = selectedToken ?: candidates.first()
        mainLookupPopupResults = emptyList()
        mainLookupPopupSelectedKey = null
        mainLookupPopupError = null
        mainLookupPopupLoading = true

        triggerLookupCandidates(candidates)

        val dictionariesSnapshot = loadedDictionaries
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { computeLookupResults(dictionariesSnapshot, candidates) }
            }
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

    fun openMainLookupPopup(cue: SubtitleCue) {
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

        triggerLookupCandidates(candidates)

        val dictionariesSnapshot = loadedDictionaries
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    computeLookupResults(dictionariesSnapshot, candidates)
                }
            }
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

    fun removeMecabTokenizer() {
        val ref = mecabDictionaryRef ?: return
        mecabDictionaryRef = null
        replaceTokenizer(null)
        scope.launch(Dispatchers.IO) {
            deleteInstalledMecabTokenizer(context, ref.cacheKey)
        }
        persistImportState()
        if (lookupQuery.isNotBlank()) {
            triggerLookupCandidates(listOf(lookupQuery))
        }
    }

    fun importMecabTokenizer(uri: Uri, displayName: String) {
        val uriValue = uri.toString()
        val cacheKey = buildDictionaryCacheKey("mecab|$uriValue", displayName)

        scope.launch {
            dictionaryLoading = true
            dictionaryError = null
            updateDictionaryProgress(
                DictionaryImportProgress(stage = "Scanning Sudachi dictionary", current = 0, total = 0)
            )
            val installResult = withContext(Dispatchers.IO) {
                runCatching {
                    installMecabDictionaryZip(
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
            installResult.onSuccess { installed ->
                mecabDictionaryRef = PersistedMecabDictionaryRef(
                    uri = uriValue,
                    name = installed.name,
                    cacheKey = installed.cacheKey
                )
                replaceTokenizer(installed.tokenizer)
                exportStatus = "Sudachi tokenizer loaded: ${installed.name}"
                persistImportState()
                clearDictionaryProgress()
                if (lookupQuery.isNotBlank()) {
                    triggerLookupCandidates(listOf(lookupQuery))
                }
            }.onFailure { error ->
                dictionaryError = error.message ?: "Failed to import Sudachi dictionary"
                clearDictionaryProgress()
            }
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

    val pickMecabDictionaryLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            keepReadPermission(context, uri)
            val displayName = queryDisplayName(contentResolver, uri)
            importMecabTokenizer(uri, displayName)
        }

    val pickDictionaryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        keepReadPermission(context, uri)
        val displayName = queryDisplayName(contentResolver, uri)
        val uriValue = uri.toString()
        val cacheKey = buildDictionaryCacheKey(uriValue, displayName)

        scope.launch {
            dictionaryLoading = true
            updateDictionaryProgress(DictionaryImportProgress(stage = "Scanning archive", current = 0, total = 0))
            dictionaryError = null
            val mecabArchive = withContext(Dispatchers.IO) {
                runCatching { isMecabDictionaryArchive(contentResolver, uri) }.getOrDefault(false)
            }
            if (mecabArchive) {
                dictionaryLoading = false
                importMecabTokenizer(uri, displayName)
                return@launch
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
            dictionaryLoading = false
            val parsedDictionary = parseResult.getOrNull()
            if (parsedDictionary != null) {
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
    val cueLookupTokens = remember(activeCue?.text, mecabDictionaryRef?.cacheKey) {
        activeCue?.let { tokenizeLookupTermsWithMecab(it.text, mecabTokenizer).take(12) } ?: emptyList()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            if (activeSection == MiningSection.MAIN) {
                FloatingActionButton(
                    onClick = {
                        addBookDialogVisible = true
                    }
                ) {
                    Text("+Book")
                }
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = activeSection == MiningSection.MAIN,
                    onClick = { activeSection = MiningSection.MAIN },
                    icon = { Text("M") },
                    label = { Text("Main") }
                )
                NavigationBarItem(
                    selected = activeSection == MiningSection.DICTIONARY,
                    onClick = { activeSection = MiningSection.DICTIONARY },
                    icon = { Text("D") },
                    label = { Text("Dictionary & Lookup") }
                )
                NavigationBarItem(
                    selected = activeSection == MiningSection.COLLECTIONS,
                    onClick = { activeSection = MiningSection.COLLECTIONS },
                    icon = { Text("C") },
                    label = { Text("收藏") }
                )
                NavigationBarItem(
                    selected = activeSection == MiningSection.SETTINGS,
                    onClick = { activeSection = MiningSection.SETTINGS },
                    icon = { Text("S") },
                    label = { Text("設定") }
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
            Text("Audio + SRT + Dictionary Mining", style = MaterialTheme.typography.titleLarge)

            if (activeSection == MiningSection.MAIN) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("My Shelf", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (selectedBookIds.isNotEmpty()) {
                            OutlinedButton(onClick = { deleteSelectedBooks() }) {
                                Text("删除(${selectedBookIds.size})")
                            }
                            OutlinedButton(onClick = { clearBookSelection() }) {
                                Text("取消选择")
                            }
                        }
                        OutlinedButton(
                            onClick = { activeSection = MiningSection.SETTINGS }
                        ) {
                            Text("設定")
                        }
                        OutlinedButton(
                            onClick = {
                                homeLibraryView = if (homeLibraryView == HomeLibraryView.BOOKSHELF) {
                                    HomeLibraryView.LIST
                                } else {
                                    HomeLibraryView.BOOKSHELF
                                }
                            }
                        ) {
                            Text(
                                if (homeLibraryView == HomeLibraryView.BOOKSHELF) {
                                    "Switch to List"
                                } else {
                                    "Switch to Shelf"
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
                            Text("No books yet.")
                            Text("Tap +Book, then select audio and SRT in the dialog.")
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
                                        Text(book.title, style = MaterialTheme.typography.titleSmall)
                                        Text(book.audioName, maxLines = 1)
                                        Text("${book.cues.size} cues")
                                        if (multiSelected) {
                                            Text("已选中", color = MaterialTheme.colorScheme.primary)
                                        }
                                        if (selected) {
                                            Text("Opened", color = MaterialTheme.colorScheme.primary)
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
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(book.title, style = MaterialTheme.typography.titleSmall)
                                    Text("Audio: ${book.audioName}", maxLines = 1)
                                    Text("SRT: ${book.srtName}", maxLines = 1)
                                    Text("${book.cues.size} cues")
                                    if (multiSelected) {
                                        Text("已选中", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                if (selectedBookIds.isEmpty()) {
                                    OutlinedButton(onClick = { openReaderBook(book, persist = true) }) {
                                        Text(if (selected) "Opened" else "Open")
                                    }
                                }
                            }
                        }
                    }
                }

                if (srtLoading || dictionaryLoading || srtError != null || dictionaryError != null || exportStatus != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (srtLoading) Text("Parsing SRT...")
                            if (dictionaryLoading) {
                                Text(dictionaryProgressText ?: "Importing dictionary...")
                                if (dictionaryProgressValue != null) {
                                    LinearProgressIndicator(
                                        progress = { dictionaryProgressValue ?: 0f },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                            if (srtError != null) Text("SRT error: $srtError", color = MaterialTheme.colorScheme.error)
                            if (dictionaryError != null) Text("Dictionary error: $dictionaryError", color = MaterialTheme.colorScheme.error)
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
                        Text("Dictionary & Lookup", style = MaterialTheme.typography.titleMedium)
                        Text("Dictionaries: $dictionaryCount ($totalDictionaryEntries entries)")
                        Text("Tokenizer: ${mecabDictionaryRef?.name ?: "Default regex tokenization"}")
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { pickDictionaryLauncher.launch(arrayOf("application/zip", "application/json", "text/plain", "*/*")) }) {
                                Text("Import Dictionary")
                            }
                            OutlinedButton(
                                onClick = { pickMecabDictionaryLauncher.launch(arrayOf("application/zip", "*/*")) }
                            ) {
                                Text("Import Sudachi")
                            }
                            if (mecabDictionaryRef != null) {
                                OutlinedButton(
                                    onClick = { removeMecabTokenizer() },
                                    enabled = !dictionaryLoading
                                ) {
                                    Text("Remove Sudachi")
                                }
                            }
                            OutlinedButton(
                                onClick = { showDictionaryManager = !showDictionaryManager }
                            ) {
                                Text(if (showDictionaryManager) "Hide Dictionaries" else "View Dictionaries")
                            }
                            OutlinedButton(
                                onClick = {
                                    activeCue?.let {
                                        triggerLookupCandidates(
                                            extractLookupCandidatesWithMecab(it.text, mecabTokenizer)
                                        )
                                    }
                                },
                                enabled = activeCue != null && loadedDictionaries.isNotEmpty()
                            ) {
                                Text("Use Current Cue")
                            }
                        }

                        if (showDictionaryManager) {
                            if (dictionaryRefs.isEmpty()) {
                                Text("No imported dictionaries.")
                            } else {
                                dictionaryRefs.forEachIndexed { index, ref ->
                                    val loaded = loadedDictionaries.getOrNull(index)
                                    val countText = loaded?.entryCount?.let { "$it entries" } ?: "not loaded"
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(ref.name.ifBlank { "Dictionary ${index + 1}" })
                                            Text(countText)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(
                                                    onClick = { removeDictionaryAt(index) },
                                                    enabled = !dictionaryLoading
                                                ) {
                                                    Text("Delete")
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
                            label = { Text("Lookup term") },
                            singleLine = true
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { triggerLookupCandidates(listOf(lookupQuery)) },
                                enabled = loadedDictionaries.isNotEmpty() && lookupQuery.isNotBlank()
                            ) {
                                Text("Search")
                            }
                            OutlinedButton(
                                onClick = {
                                    lookupQuery = ""
                                    lookupResults = emptyList()
                                    selectedEntryKey = null
                                }
                            ) {
                                Text("Clear")
                            }
                        }

                        if (lookupLoading) {
                            Text("Searching dictionary...")
                        } else if (lookupQuery.isNotBlank() && lookupResults.isEmpty()) {
                            Text("No lookup result.")
                        }

                        lookupResults.forEach { result ->
                            val entry = result.entry
                            val key = entryStableKey(entry)
                            val expanded = expandedResultKeys[key] ?: false
                            val definitionList = if (expanded) entry.definitions else entry.definitions.take(1)

                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val readingText = entry.reading?.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""
                                    Text("${entry.term}$readingText")
                                    Text("${entry.dictionary} | score ${result.score}")
                                    if (!entry.pitch.isNullOrBlank()) Text("Pitch: ${entry.pitch}")
                                    if (!entry.frequency.isNullOrBlank()) Text("Frequency: ${entry.frequency}")
                                    definitionList.forEachIndexed { index, definition ->
                                        RichDefinitionView(
                                            definition = definition,
                                            indexLabel = "${index + 1}. ",
                                            dictionaryName = entry.dictionary,
                                            dictionaryCss = dictionaryCssByName[entry.dictionary]
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { expandedResultKeys[key] = !expanded }) {
                                            Text(if (expanded) "Collapse" else "Expand")
                                        }
                                        Button(onClick = { selectedEntryKey = key }) {
                                            Text(if (selectedEntryKey == key) "Selected" else "Use for Mining")
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
                        Text("BookReader 收藏", style = MaterialTheme.typography.titleMedium)
                        Text("共 ${collectedCues.size} 条")
                        if (collectedCues.isEmpty()) {
                            Text("暂无收藏。请在 BookReader 控制模式下播放字幕后自动收藏。")
                        } else {
                            collectedCues.forEach { item ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(item.text)
                                        Text(
                                            "${item.bookTitle} | ${formatTime(item.startMs)} - ${formatTime(item.endMs)}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = {
                                                    openMainLookupPopup(
                                                        SubtitleCue(
                                                            startMs = item.startMs,
                                                            endMs = item.endMs,
                                                            text = item.text
                                                        )
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
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("設定", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { context.startActivity(Intent(context, InfoActivity::class.java)) }
                            ) {
                                Text("Info")
                            }
                            OutlinedButton(
                                onClick = { refreshCollectedCues() }
                            ) {
                                Text("刷新收藏")
                            }
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Flashcard Creation (Sentence Mining)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (ankiPermissionGranted) {
                            "Anki access: granted"
                        } else {
                            "Anki access: not granted"
                        }
                    )
                    OutlinedButton(
                        onClick = {
                            if (!isAnkiInstalled(context)) {
                                exportStatus = "AnkiDroid is not installed."
                            } else if (ankiPermissionGranted) {
                                exportStatus = "Anki access already granted."
                            } else {
                                exportStatus = "Requesting Anki access permission..."
                                requestAnkiPermissionLauncher.launch(ANKI_READ_WRITE_PERMISSION)
                            }
                        }
                    ) {
                        Text(if (ankiPermissionGranted) "Anki Access Granted" else "Authorize Anki Access")
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { refreshAnkiCatalog() },
                            enabled = ankiPermissionGranted && !ankiLoading
                        ) {
                            Text(if (ankiLoading) "Loading..." else "Refresh Decks/Models")
                        }
                    }
                    if (ankiError != null) {
                        Text("Anki error: $ankiError", color = MaterialTheme.colorScheme.error)
                    }

                    OutlinedTextField(
                        value = ankiDeckName,
                        onValueChange = {
                            ankiDeckName = it
                            persistAnkiConfig()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Deck") },
                        singleLine = true
                    )
                    if (ankiDecks.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ankiDecks.take(20).forEach { deck ->
                                OutlinedButton(
                                    onClick = {
                                        ankiDeckName = deck
                                        persistAnkiConfig()
                                    }
                                ) {
                                    Text(deck)
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = ankiModelName,
                        onValueChange = {
                            val previousModelName = ankiModelName
                            ankiModelName = it
                            val model = ankiModels.firstOrNull { candidate -> candidate.name == it }
                            if (model != null) {
                                syncTemplatesWithModelFields(
                                    fields = model.fields,
                                    clearExisting = previousModelName != it
                                )
                            } else {
                                syncTemplatesWithModelFields(
                                    fields = emptyList(),
                                    clearExisting = previousModelName != it
                                )
                            }
                            persistAnkiConfig()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Note model/template") },
                        singleLine = true
                    )
                    if (ankiModels.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ankiModels.take(20).forEach { model ->
                                OutlinedButton(
                                    onClick = { selectAnkiModel(model.name) }
                                ) {
                                    Text(model.name)
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = ankiTagsInput,
                        onValueChange = {
                            ankiTagsInput = it
                            persistAnkiConfig()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Tags (space/comma separated)") },
                        singleLine = true
                    )

                    if (ankiModelFields.isNotEmpty()) {
                        Text("Field Variables", style = MaterialTheme.typography.titleSmall)
                        OutlinedButton(
                            onClick = { clearCurrentFieldTemplates() }
                        ) {
                            Text("Clear Field Variables")
                        }
                        Text("Available: {expression} {word} {reading} {furigana-plain} {audio} {glossary} {glossary-first} {single-glossary} {popup-selection-text} {sentence} {frequencies} {frequency-harmonic-rank} {pitch-accent-positions} {pitch-accent-categories} {document-title} {book-cover}")
                        if (dictionarySpecificVariableChoices.isNotEmpty()) {
                            Text("Dictionary-specific: ${dictionarySpecificVariableChoices.joinToString(" ")}")
                        }
                        ankiModelFields.forEach { field ->
                            val selectedValue = ankiFieldTemplates[field].orEmpty()
                            val options = if (selectedValue.isNotBlank() && selectedValue !in fieldVariableChoices) {
                                fieldVariableChoices + selectedValue
                            } else {
                                fieldVariableChoices
                            }
                            FieldVariableDropdown(
                                fieldName = field,
                                selectedValue = selectedValue,
                                options = options,
                                onSelect = { value ->
                                    ankiFieldTemplates[field] = value
                                    persistAnkiConfig()
                                }
                            )
                        }
                    }

                    Text("Sentence: ${activeCue?.text ?: "No active subtitle"}")
                    Text("Word: ${selectedEntry?.term ?: extractLookupToken(lookupQuery).ifBlank { "-" }}")
                    if (!selectedEntry?.reading.isNullOrBlank()) {
                        Text("Reading: ${selectedEntry?.reading}")
                    }
                    if (!selectedEntry?.pitch.isNullOrBlank()) {
                        Text("Pitch: ${selectedEntry?.pitch}")
                    }
                    if (!selectedEntry?.frequency.isNullOrBlank()) {
                        Text("Frequency: ${selectedEntry?.frequency}")
                    }
                    if (selectedEntry != null) {
                        Text("Definitions:")
                        selectedEntry.definitions.take(3).forEachIndexed { index, definition ->
                            Text("${index + 1}. $definition")
                        }
                    } else {
                        Text("Definitions: (none selected)")
                    }
                    Text(
                        if (audioUri != null) {
                            "Audio source is attached. Clip range uses current subtitle timestamps."
                        } else {
                            "No audio attached. Card will be text only."
                        }
                    )
                }
            }
            }
        }

        if (addBookDialogVisible) {
            AlertDialog(
                onDismissRequest = { addBookDialogVisible = false },
                title = { Text("添加 Book") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("音频: ${addBookAudioName ?: "未选择"}")
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
                            }
                        ) {
                            Text("选择音频")
                        }
                        Text("SRT: ${addBookSrtName ?: "未选择"}")
                        OutlinedButton(
                            onClick = {
                                pickBookSrtLauncher.launch(
                                    arrayOf("application/x-subrip", "text/plain", "*/*")
                                )
                            }
                        ) {
                            Text("选择 SRT")
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
                        enabled = addBookAudioUri != null && addBookSrtUri != null && !srtLoading
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
                        Text(
                            text = "Lookup: ${mainLookupPopupTitle.ifBlank { "-" }}",
                            style = MaterialTheme.typography.titleMedium
                        )
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

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 520.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (mainLookupPopupLoading) {
                                Text("Searching...")
                            }
                            if (mainLookupPopupError != null) {
                                Text(
                                    "Lookup error: $mainLookupPopupError",
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            if (!mainLookupPopupLoading && mainLookupPopupResults.isEmpty()) {
                                Text("No lookup result.")
                            }
                            mainLookupPopupResults.take(10).forEach { result ->
                                val entry = result.entry
                                val key = entryStableKey(entry)
                                val reading = entry.reading?.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text("${entry.term}$reading")
                                        Text("${entry.dictionary} | score ${result.score}")
                                        if (!entry.pitch.isNullOrBlank()) Text("Pitch: ${entry.pitch}")
                                        if (!entry.frequency.isNullOrBlank()) Text("Frequency: ${entry.frequency}")
                                        if (entry.definitions.isNotEmpty()) {
                                            RichDefinitionView(
                                                definition = entry.definitions.first(),
                                                dictionaryName = entry.dictionary,
                                                dictionaryCss = dictionaryCssByName[entry.dictionary]
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(
                                                onClick = {
                                                    mainLookupPopupSelectedKey = key
                                                    selectedEntryKey = key
                                                }
                                            ) {
                                                Text(if (mainLookupPopupSelectedKey == key) "Selected" else "Select")
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
                                }
                            ) {
                                Text("Close")
                            }
                            Button(
                                onClick = {
                                    val cue = mainLookupPopupCue ?: activeCue
                                    val entry = mainPopupSelectedEntry
                                    if (cue == null || entry == null) {
                                        exportStatus = "Need selected lookup result and a subtitle."
                                        return@Button
                                    }
                                    val card = buildMinedCard(
                                        cue = cue,
                                        selectedEntry = entry,
                                        lookupQuery = mainLookupPopupTitle,
                                        audioUri = audioUri,
                                        dictionaryCss = mainPopupSelectedEntryCss
                                    )
                                    tryExportCardToAnki(card)
                                    mainLookupPopupVisible = false
                                    mainLookupPopupCue = null
                                    mainLookupPopupSelectedRange = null
                                },
                                enabled = (mainLookupPopupCue != null || activeCue != null) && mainPopupSelectedEntry != null
                            ) {
                                Text("Mine")
                            }
                        }
                    }
                }
            }
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
                    <i>($safeDictionaryLabel)</i> <span>$definitionHtml</span>
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

private data class LocalBookFile(
    val uri: Uri,
    val displayName: String
)

private data class LocalBookFiles(
    val audio: LocalBookFile,
    val srt: LocalBookFile
)

private fun ensureBookFilesInLocalStorage(
    context: Context,
    contentResolver: ContentResolver,
    audioUri: Uri,
    audioDisplayName: String?,
    srtUri: Uri,
    srtDisplayName: String?
): LocalBookFiles {
    val localAudio = if (isAppLocalBookFileUri(context, audioUri)) {
        LocalBookFile(
            uri = audioUri,
            displayName = audioDisplayName?.ifBlank { null }
                ?: queryDisplayName(contentResolver, audioUri)
        )
    } else {
        importBookFileToLocalStorage(
            context = context,
            contentResolver = contentResolver,
            sourceUri = audioUri,
            sourceDisplayName = audioDisplayName,
            typeLabel = "audio",
            fallbackExtension = "m4a"
        )
    }

    val localSrt = if (isAppLocalBookFileUri(context, srtUri)) {
        LocalBookFile(
            uri = srtUri,
            displayName = srtDisplayName?.ifBlank { null }
                ?: queryDisplayName(contentResolver, srtUri)
        )
    } else {
        importBookFileToLocalStorage(
            context = context,
            contentResolver = contentResolver,
            sourceUri = srtUri,
            sourceDisplayName = srtDisplayName,
            typeLabel = "srt",
            fallbackExtension = "srt"
        )
    }

    return LocalBookFiles(
        audio = localAudio,
        srt = localSrt
    )
}

private fun importBookFileToLocalStorage(
    context: Context,
    contentResolver: ContentResolver,
    sourceUri: Uri,
    sourceDisplayName: String?,
    typeLabel: String,
    fallbackExtension: String
): LocalBookFile {
    val displayName = sourceDisplayName
        ?.trim()
        ?.ifBlank { null }
        ?: queryDisplayName(contentResolver, sourceUri)
    val ext = detectBookFileExtension(
        contentResolver = contentResolver,
        uri = sourceUri,
        fallback = fallbackExtension
    )
    val base = sanitizeBookFileBase(displayName.substringBeforeLast('.', missingDelimiterValue = displayName))
    val booksDir = File(context.filesDir, "books")
    if (!booksDir.exists()) {
        booksDir.mkdirs()
    }
    val file = File(booksDir, "${typeLabel}-${System.currentTimeMillis()}-$base.$ext")

    openBookInputStream(contentResolver, sourceUri)?.use { input ->
        file.outputStream().use { output -> input.copyTo(output) }
    } ?: error("Cannot read $typeLabel file from source URI.")

    if (!file.exists() || file.length() <= 0L) {
        file.delete()
        error("Imported $typeLabel file is empty.")
    }

    return LocalBookFile(
        uri = Uri.fromFile(file),
        displayName = displayName.ifBlank { file.name }
    )
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

private fun isAppLocalBookFileUri(context: Context, uri: Uri): Boolean {
    if (!uri.scheme.equals("file", ignoreCase = true)) return false
    val path = uri.path ?: return false
    val filesRoot = context.filesDir.absolutePath
    return path.startsWith(filesRoot, ignoreCase = true) && File(path).exists()
}

private fun detectBookFileExtension(
    contentResolver: ContentResolver,
    uri: Uri,
    fallback: String
): String {
    fun extFromName(name: String?): String? {
        val raw = name
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.trim()
            ?.trimStart('.')
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return raw.takeIf { it.isNotBlank() }
    }

    val fromPath = extFromName(uri.lastPathSegment)
    if (!fromPath.isNullOrBlank()) return fromPath

    val fromDisplayName = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) null else extFromName(cursor.getString(index))
        }
    }.getOrNull()
    if (!fromDisplayName.isNullOrBlank()) return fromDisplayName

    return fallback.trim().trimStart('.').ifBlank { "bin" }
}

private fun sanitizeBookFileBase(value: String): String {
    val normalized = value
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return normalized.ifBlank { "book" }.take(80)
}

private fun keepReadPermission(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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

private fun buildBookTitle(audioName: String, srtName: String): String {
    val audioBase = audioName.substringBeforeLast('.').trim().ifBlank { audioName.trim() }
    if (audioBase.isNotBlank()) return audioBase
    val srtBase = srtName.substringBeforeLast('.').trim().ifBlank { srtName.trim() }
    if (srtBase.isNotBlank()) return srtBase
    return "Untitled Book"
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
        addStyle(SpanStyle(background = Color(0xFFDADADA)), start, endExclusive)
    }
}

private fun findMainLookupSelectionRange(
    text: String,
    offset: Int,
    tokenizer: MecabTokenizer?
): IntRange? {
    if (text.isBlank()) return null
    val maxIndex = (text.length - 1).coerceAtLeast(0)
    val clamped = offset.coerceIn(0, maxIndex)

    val span = tokenizer
        ?.tokenize(text)
        ?.firstOrNull { clamped in it.startChar until it.endChar }
    if (span != null && span.endChar > span.startChar) {
        return span.startChar until span.endChar
    }

    val charRegex = Regex("[\\p{L}\\p{N}\\u3400-\\u9FFF\\u3040-\\u30FF\\u3005\\u3006\\u30F6\\u30FC]")
    if (!charRegex.matches(text[clamped].toString())) return null

    var start = clamped
    while (start > 0 && charRegex.matches(text[start - 1].toString())) {
        start -= 1
    }
    var endExclusive = clamped + 1
    while (endExclusive < text.length && charRegex.matches(text[endExclusive].toString())) {
        endExclusive += 1
    }
    return start until endExclusive
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

