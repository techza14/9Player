package com.example.tset

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Html
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.tset.ui.theme.TsetTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlin.math.abs

class BookReaderActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val audioUri = intent.getStringExtra(EXTRA_AUDIO_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val srtUri = intent.getStringExtra(EXTRA_SRT_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val title = intent.getStringExtra(EXTRA_BOOK_TITLE).orEmpty()

        setContent {
            TsetTheme {
                BookReaderScreen(
                    title = title.ifBlank { "Book" },
                    audioUri = audioUri,
                    srtUri = srtUri,
                    contentResolver = contentResolver,
                    onBack = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_BOOK_TITLE = "extra_book_title"
        const val EXTRA_AUDIO_URI = "extra_audio_uri"
        const val EXTRA_SRT_URI = "extra_srt_uri"
    }
}

private data class ReaderSubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

@Composable
private fun BookReaderScreen(
    title: String,
    audioUri: Uri?,
    srtUri: Uri?,
    contentResolver: ContentResolver,
    onBack: () -> Unit
) {
    var cues by remember { mutableStateOf<List<ReaderSubtitleCue>>(emptyList()) }
    var srtLoading by remember { mutableStateOf(false) }
    var srtError by remember { mutableStateOf<String?>(null) }

    var loadedDictionaries by remember { mutableStateOf<List<LoadedDictionary>>(emptyList()) }
    var mecabTokenizer by remember { mutableStateOf<MecabTokenizer?>(null) }

    var lookupPopupVisible by remember { mutableStateOf(false) }
    var lookupPopupLoading by remember { mutableStateOf(false) }
    var lookupPopupError by remember { mutableStateOf<String?>(null) }
    var lookupPopupTitle by remember { mutableStateOf("") }
    var lookupPopupResults by remember { mutableStateOf<List<DictionarySearchResult>>(emptyList()) }
    var lookupPopupCue by remember { mutableStateOf<ReaderSubtitleCue?>(null) }
    var selectedLookupRange by remember { mutableStateOf<IntRange?>(null) }
    var ankiActionStatus by remember { mutableStateOf<String?>(null) }

    var lyricsMode by remember { mutableStateOf(true) }
    var controlModeEnabled by remember { mutableStateOf(false) }
    var controlModeStatus by remember { mutableStateOf<String?>(null) }
    var controlTargetCueIndex by remember { mutableStateOf<Int?>(null) }
    var sleepTimerDeadlineMs by remember { mutableStateOf<Long?>(null) }
    var lastOverlayTapAtMs by remember { mutableStateOf(0L) }
    var pendingSingleTapBaseCueIndex by remember { mutableStateOf<Int?>(null) }
    var pendingSingleTapJob by remember { mutableStateOf<Job?>(null) }

    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var dragPreviewPositionMs by remember { mutableStateOf<Long?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember(context) { ExoPlayer.Builder(context).build() }
    val lyricsListState = rememberLazyListState()
    val collectedCueKeys = remember { hashSetOf<String>() }

    DisposableEffect(player) {
        onDispose { player.release() }
    }
    DisposableEffect(Unit) {
        onDispose { mecabTokenizer?.close() }
    }
    DisposableEffect(Unit) {
        onDispose { pendingSingleTapJob?.cancel() }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
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

    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = if (player.duration > 0L) player.duration else 0L
            delay(50L)
        }
    }

    LaunchedEffect(audioUri) {
        val selectedAudio = audioUri ?: return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(selectedAudio))
        player.prepare()
        player.pause()
        player.seekTo(0L)
    }

    LaunchedEffect(srtUri) {
        val uri = srtUri ?: return@LaunchedEffect
        srtLoading = true
        srtError = null
        val result = withContext(Dispatchers.IO) {
            runCatching { parseBookSrt(contentResolver, uri) }
        }
        srtLoading = false
        result.onSuccess { cues = it }
            .onFailure {
                cues = emptyList()
                srtError = it.message ?: "Failed to parse SRT"
            }
    }

    LaunchedEffect(Unit) {
        val persisted = loadPersistedImports(context)
        val refs = persisted.dictionaries.distinctBy { it.uri }
        val restored = refs.mapNotNull { ref ->
            val restoredUri = runCatching { Uri.parse(ref.uri) }.getOrNull()
            val displayName = ref.name.ifBlank {
                restoredUri?.let { queryBookDisplayName(contentResolver, it) }.orEmpty()
            }
            val cacheKey = ref.cacheKey ?: buildDictionaryCacheKey(ref.uri, displayName)
            loadDictionaryFromSqlite(context, cacheKey)
        }
        loadedDictionaries = restored

        mecabTokenizer = persisted.mecabDictionary?.let { ref ->
            loadInstalledMecabTokenizer(context, ref.name, ref.cacheKey)
        }
    }

    val dictionaryCssByName = remember(loadedDictionaries) {
        loadedDictionaries.associate { it.name to it.stylesCss }
    }

    val playbackCueIndex = remember(positionMs, cues) { findBookCueIndexAtTime(cues, positionMs) }
    val previewPositionMs = remember(positionMs, dragPreviewPositionMs) {
        dragPreviewPositionMs ?: positionMs
    }
    val activeCueIndex = remember(previewPositionMs, cues) { findBookDisplayCueIndexAtTime(cues, previewPositionMs) }
    val activeCue = cues.getOrNull(activeCueIndex)
    val activeSubtitleStyle = MaterialTheme.typography.headlineMedium.copy(
        fontSize = 34.sp,
        lineHeight = 42.sp,
        color = MaterialTheme.colorScheme.onSurface
    )

    LaunchedEffect(activeCue?.text) {
        selectedLookupRange = null
    }

    val sliderMax = if (durationMs > 0L) durationMs.toFloat() else 1f
    val sliderValue = when {
        durationMs <= 0L -> 0f
        dragPreviewPositionMs != null -> dragPreviewPositionMs!!.coerceIn(0L, durationMs).toFloat()
        else -> positionMs.coerceIn(0L, durationMs).toFloat()
    }
    val sleepRemainingLabel = remember(sleepTimerDeadlineMs, positionMs) {
        val deadline = sleepTimerDeadlineMs ?: return@remember null
        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
        formatBookTime(remaining)
    }

    LaunchedEffect(title) {
        val existing = withContext(Dispatchers.IO) { loadBookReaderCollectedCues(context) }
        collectedCueKeys.clear()
        existing
            .filter { it.bookTitle == title }
            .forEach { cue ->
                collectedCueKeys += cueCollectionKey(cue.startMs, cue.endMs, cue.text)
            }
    }

    LaunchedEffect(sleepTimerDeadlineMs) {
        val deadline = sleepTimerDeadlineMs ?: return@LaunchedEffect
        while (sleepTimerDeadlineMs == deadline) {
            if (System.currentTimeMillis() >= deadline) {
                player.pause()
                sleepTimerDeadlineMs = null
                controlModeStatus = "Sleep timer reached."
                break
            }
            delay(250L)
        }
    }

    LaunchedEffect(positionMs, controlModeEnabled, controlTargetCueIndex, cues) {
        if (!controlModeEnabled) return@LaunchedEffect
        val targetIndex = controlTargetCueIndex ?: return@LaunchedEffect
        val cue = cues.getOrNull(targetIndex) ?: run {
            controlTargetCueIndex = null
            return@LaunchedEffect
        }
        if (positionMs >= cue.endMs) {
            controlTargetCueIndex = null
            val key = cueCollectionKey(cue.startMs, cue.endMs, cue.text)
            if (collectedCueKeys.add(key)) {
                val added = withContext(Dispatchers.IO) {
                    appendBookReaderCollectedCue(
                        context,
                        BookReaderCollectedCue(
                            id = "${System.currentTimeMillis()}-${cue.startMs}-${cue.endMs}-${cue.text.hashCode()}",
                            bookTitle = title,
                            text = cue.text,
                            startMs = cue.startMs,
                            endMs = cue.endMs,
                            savedAtMs = System.currentTimeMillis()
                        )
                    )
                }
                if (added) {
                    controlModeStatus = "Collected cue ${targetIndex + 1}/${cues.size}. Continue playback."
                }
            } else {
                controlModeStatus = "Cue ${targetIndex + 1}/${cues.size} already collected. Continue playback."
            }
        }
    }

    LaunchedEffect(activeCueIndex, lyricsMode, cues.size, dragPreviewPositionMs) {
        if (!lyricsMode || activeCueIndex < 0 || cues.isEmpty()) return@LaunchedEffect
        if (dragPreviewPositionMs != null) return@LaunchedEffect
        if (lyricsListState.isScrollInProgress) return@LaunchedEffect
        if (lyricsListState.layoutInfo.visibleItemsInfo.none { it.index == activeCueIndex }) {
            lyricsListState.scrollToItem(activeCueIndex)
        }
        val activeItem = lyricsListState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeCueIndex }
            ?: return@LaunchedEffect
        val layoutInfo = lyricsListState.layoutInfo
        val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
        val itemCenter = activeItem.offset + (activeItem.size / 2f)
        val delta = itemCenter - viewportCenter
        if (abs(delta) > 1f) {
            lyricsListState.scrollBy(delta)
        }
    }

    fun jumpToCue(index: Int) {
        val cue = cues.getOrNull(index) ?: return
        selectedLookupRange = null
        lookupPopupVisible = false
        player.seekTo(cue.startMs)
        player.play()
        controlTargetCueIndex = if (controlModeEnabled) index else null
        controlModeStatus = "Jumped to cue ${index + 1}/${cues.size}"
    }

    fun seekToManual(targetMs: Long) {
        val target = if (durationMs > 0L) {
            targetMs.coerceAtLeast(0L).coerceAtMost(durationMs)
        } else {
            targetMs.coerceAtLeast(0L)
        }
        pendingSingleTapJob?.cancel()
        pendingSingleTapJob = null
        pendingSingleTapBaseCueIndex = null
        controlTargetCueIndex = null
        selectedLookupRange = null
        lookupPopupVisible = false
        player.seekTo(target)
        if (controlModeEnabled) {
            controlModeStatus = "Manual seek."
        }
    }

    fun jumpToAdjacentCue(step: Int) {
        if (cues.isEmpty()) return
        val lastIndex = cues.lastIndex
        val targetIndex = if (step < 0) {
            when {
                playbackCueIndex > 0 -> playbackCueIndex - 1
                playbackCueIndex == 0 -> 0
                else -> {
                    val before = findCueIndexAtOrBeforeTime(cues, positionMs)
                    if (before <= 0) 0 else before - 1
                }
            }
        } else {
            when {
                playbackCueIndex in 0 until lastIndex -> playbackCueIndex + 1
                playbackCueIndex == lastIndex -> lastIndex
                else -> {
                    val after = findCueIndexAtOrAfterTime(cues, positionMs)
                    if (after < 0) lastIndex else after
                }
            }
        }
        jumpToCue(targetIndex.coerceIn(0, lastIndex))
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerDeadlineMs = if (minutes <= 0) {
            null
        } else {
            System.currentTimeMillis() + (minutes * 60_000L)
        }
        controlModeStatus = if (minutes <= 0) "Sleep timer cleared." else "Sleep timer set: ${minutes}m"
    }

    fun playCueForControl(index: Int) {
        val cue = cues.getOrNull(index) ?: return
        player.seekTo(cue.startMs)
        player.play()
        controlTargetCueIndex = index
        controlModeStatus = "Playing cue ${index + 1}/${cues.size}"
    }

    fun handleControlOverlayTap() {
        val currentIndex = playbackCueIndex.takeIf { it >= 0 } ?: return
        val currentCue = cues.getOrNull(currentIndex) ?: return
        val now = System.currentTimeMillis()
        val doubleTapWindowMs = 280L
        val isDoubleTap = pendingSingleTapBaseCueIndex == currentIndex &&
            now - lastOverlayTapAtMs <= doubleTapWindowMs

        if (isDoubleTap && isPlaying && positionMs < currentCue.endMs) {
            pendingSingleTapJob?.cancel()
            pendingSingleTapJob = null
            pendingSingleTapBaseCueIndex = null
            playCueForControl((currentIndex - 1).coerceAtLeast(0))
            controlModeStatus = "Double tap: replay previous subtitle."
            return
        }

        pendingSingleTapJob?.cancel()
        pendingSingleTapBaseCueIndex = currentIndex
        lastOverlayTapAtMs = now
        pendingSingleTapJob = scope.launch {
            delay(doubleTapWindowMs)
            if (pendingSingleTapBaseCueIndex == currentIndex) {
                pendingSingleTapBaseCueIndex = null
                playCueForControl(currentIndex)
                controlModeStatus = "Single tap: replay current subtitle."
            }
        }
    }

    fun triggerPopupLookup(cue: ReaderSubtitleCue, offset: Int) {
        val dictionariesSnapshot = loadedDictionaries
        if (dictionariesSnapshot.isEmpty()) {
            lookupPopupVisible = true
            lookupPopupLoading = false
            lookupPopupResults = emptyList()
            lookupPopupTitle = ""
            lookupPopupError = "No dictionary loaded. Import dictionary in Main first."
            lookupPopupCue = cue
            return
        }

        val selectionRange = findLookupSelectionRange(cue.text, offset, mecabTokenizer)
        selectedLookupRange = selectionRange
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
            .take(8)

        if (candidates.isEmpty()) {
            lookupPopupVisible = true
            lookupPopupLoading = false
            lookupPopupResults = emptyList()
            lookupPopupTitle = selectedToken.orEmpty()
            lookupPopupError = "No lookup candidate for this position."
            lookupPopupCue = cue
            return
        }

        lookupPopupVisible = true
        lookupPopupLoading = true
        lookupPopupResults = emptyList()
        lookupPopupError = null
        lookupPopupTitle = selectedToken ?: candidates.first()
        lookupPopupCue = cue
        ankiActionStatus = null

        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    computeBookLookupResults(
                        context = context,
                        dictionaries = dictionariesSnapshot,
                        candidates = candidates
                    )
                }
            }
            result.onSuccess { hits ->
                lookupPopupResults = hits
                if (hits.isEmpty()) {
                    lookupPopupError = "No lookup result."
                }
            }.onFailure {
                lookupPopupResults = emptyList()
                lookupPopupError = it.message ?: "Lookup failed"
            }
            lookupPopupLoading = false
        }
    }

    fun addDefinitionToAnki(entry: DictionaryEntry, definition: String) {
        val cue = lookupPopupCue ?: return
        val css = dictionaryCssByName[entry.dictionary]
        scope.launch {
            ankiActionStatus = "Adding to Anki..."
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    addLookupDefinitionToAnki(
                        context = context,
                        cue = cue,
                        audioUri = audioUri,
                        entry = entry,
                        definition = definition,
                        dictionaryCss = css
                    )
                }
            }
            ankiActionStatus = result.fold(
                onSuccess = { "Added to Anki." },
                onFailure = { formatAnkiFailure(it) }
            )
        }
    }

    fun handleControlOverlaySwipe(step: Int) {
        if (step < 0) {
            jumpToAdjacentCue(-1)
            controlModeStatus = "Swipe left: previous subtitle."
        } else {
            jumpToAdjacentCue(1)
            controlModeStatus = "Swipe right: next subtitle."
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            floatingActionButton = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    FloatingActionButton(onClick = { controlModeEnabled = !controlModeEnabled }) {
                        Text(if (controlModeEnabled) "Ctrl On" else "Ctrl")
                    }
                    FloatingActionButton(onClick = { lyricsMode = !lyricsMode }) {
                        Text(if (lyricsMode) "Line" else "Lyrics")
                    }
                }
            },
            bottomBar = {
                Surface(tonalElevation = 4.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Slider(
                            value = sliderValue,
                            valueRange = 0f..sliderMax,
                            enabled = durationMs > 0L,
                            onValueChange = { raw ->
                                if (durationMs > 0L) {
                                    dragPreviewPositionMs = raw.toLong().coerceIn(0L, durationMs)
                                }
                            },
                            onValueChangeFinished = {
                                val target = dragPreviewPositionMs
                                if (target != null) {
                                    seekToManual(target)
                                }
                                dragPreviewPositionMs = null
                            }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                                    Text(if (isPlaying) "Pause" else "Play")
                                }
                                OutlinedButton(onClick = { jumpToAdjacentCue(-1) }) { Text("上一句") }
                                OutlinedButton(onClick = { jumpToAdjacentCue(1) }) { Text("下一句") }
                            }
                            Text("${formatBookTime(previewPositionMs)} / ${formatBookTime(durationMs)}")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Sleep")
                            OutlinedButton(onClick = { setSleepTimer(10) }) { Text("10m") }
                            OutlinedButton(onClick = { setSleepTimer(20) }) { Text("20m") }
                            OutlinedButton(onClick = { setSleepTimer(30) }) { Text("30m") }
                            TextButton(onClick = { setSleepTimer(0) }) { Text("Off") }
                            if (sleepRemainingLabel != null) {
                                Text("Left: $sleepRemainingLabel")
                            }
                        }
                        if (controlModeStatus != null) {
                            Text(controlModeStatus!!)
                        }
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) {
                        Text("< Back")
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge)
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    tonalElevation = 1.dp,
                    shape = MaterialTheme.shapes.large
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        when {
                            srtLoading -> Text("Parsing SRT...")
                            srtError != null -> Text("SRT error: $srtError", color = MaterialTheme.colorScheme.error)
                            cues.isEmpty() -> Text("No subtitles available.")
                            lyricsMode -> {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    state = lyricsListState,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    itemsIndexed(cues) { index, cue ->
                                        val isActive = index == activeCueIndex
                                        ClickableText(
                                            text = buildHighlightedText(
                                                cue.text,
                                                if (isActive) selectedLookupRange else null
                                            ),
                                            style = if (isActive) {
                                                activeSubtitleStyle
                                            } else {
                                                MaterialTheme.typography.titleLarge.copy(
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            },
                                            onClick = { offset ->
                                                if (isActive) {
                                                    triggerPopupLookup(cue, offset)
                                                } else {
                                                    jumpToCue(index)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                            activeCue == null -> Text("Waiting for playback to enter subtitle range...")
                            else -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    ClickableText(
                                        text = buildHighlightedText(activeCue.text, selectedLookupRange),
                                        style = activeSubtitleStyle,
                                        onClick = { offset -> triggerPopupLookup(activeCue, offset) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (controlModeEnabled && cues.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Gray.copy(alpha = 0.38f))
                    .pointerInput(playbackCueIndex, isPlaying, cues.size) {
                        detectTapGestures(onTap = { handleControlOverlayTap() })
                    }
                    .pointerInput(playbackCueIndex, cues.size) {
                        var totalDrag = 0f
                        var handled = false
                        detectHorizontalDragGestures(
                            onDragStart = {
                                totalDrag = 0f
                                handled = false
                            },
                            onHorizontalDrag = { _, dragAmount ->
                                if (handled) return@detectHorizontalDragGestures
                                totalDrag += dragAmount
                                if (totalDrag <= -80f) {
                                    handleControlOverlaySwipe(-1)
                                    handled = true
                                } else if (totalDrag >= 80f) {
                                    handleControlOverlaySwipe(1)
                                    handled = true
                                }
                            },
                            onDragEnd = {
                                totalDrag = 0f
                                handled = false
                            },
                            onDragCancel = {
                                totalDrag = 0f
                                handled = false
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Control Mode\nTap: replay current\nDouble tap while playing: previous\nSwipe left: previous\nSwipe right: next",
                    color = Color.White
                )
            }
        }
    }

    if (lookupPopupVisible) {
        Popup(
            alignment = Alignment.BottomCenter,
            onDismissRequest = { lookupPopupVisible = false },
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
                    .padding(bottom = 24.dp),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Lookup: ${lookupPopupTitle.ifBlank { "-" }}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (ankiActionStatus != null) {
                        Text(ankiActionStatus!!)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (lookupPopupLoading) {
                            Text("Searching...")
                        }
                        if (lookupPopupError != null) {
                            Text(lookupPopupError!!, color = MaterialTheme.colorScheme.error)
                        }
                        if (!lookupPopupLoading && lookupPopupResults.isEmpty() && lookupPopupError == null) {
                            Text("No lookup result.")
                        }
                        lookupPopupResults.take(10).forEach { result ->
                            val entry = result.entry
                            val reading = entry.reading?.takeIf { it.isNotBlank() }?.let { " [$it]" } ?: ""
                            val css = dictionaryCssByName[entry.dictionary]
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("${entry.term}$reading")
                                    Text("${entry.dictionary} | score ${result.score}")
                                    if (!entry.pitch.isNullOrBlank()) Text("Pitch: ${entry.pitch}")
                                    if (!entry.frequency.isNullOrBlank()) Text("Frequency: ${entry.frequency}")

                                    entry.definitions.forEachIndexed { index, definition ->
                                        Card(modifier = Modifier.fillMaxWidth()) {
                                            Column(
                                                modifier = Modifier.padding(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text("${index + 1}.")
                                                    OutlinedButton(onClick = { addDefinitionToAnki(entry, definition) }) {
                                                        Text("+")
                                                    }
                                                }
                                                RichDefinitionViewBook(
                                                    definition = definition,
                                                    dictionaryName = entry.dictionary,
                                                    dictionaryCss = css
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { lookupPopupVisible = false }) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }
}

private fun buildHighlightedText(text: String, selectedRange: IntRange?): AnnotatedString {
    return buildAnnotatedString {
        append(text)
        val range = selectedRange ?: return@buildAnnotatedString
        val start = range.first.coerceIn(0, text.length)
        val endExclusive = (range.last + 1).coerceIn(start, text.length)
        if (endExclusive <= start) return@buildAnnotatedString
        addStyle(SpanStyle(background = Color(0xFFDADADA)), start, endExclusive)
    }
}

private fun findLookupSelectionRange(
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

private fun computeBookLookupResults(
    context: Context,
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

private fun addLookupDefinitionToAnki(
    context: Context,
    cue: ReaderSubtitleCue,
    audioUri: Uri?,
    entry: DictionaryEntry,
    definition: String,
    dictionaryCss: String?
) {
    if (!isAnkiInstalled(context)) error("AnkiDroid is not installed.")
    if (!hasAnkiReadWritePermission(context)) {
        error("Anki permission not granted. Authorize in Main > Flashcard Creation first.")
    }

    val persistedConfig = withAnkiStep("load-config") {
        loadPersistedAnkiConfig(context)
    }
    if (persistedConfig.modelName.isBlank()) {
        error("No Anki model configured. Set model in Main > Flashcard Creation.")
    }

    val catalog = withAnkiStep("load-catalog") {
        loadAnkiCatalog(context)
    }
    val model = catalog.models.firstOrNull { it.name == persistedConfig.modelName }
        ?: error("Configured model not found: ${persistedConfig.modelName}")

    val templates = model.fields.associateWith { field ->
        val saved = persistedConfig.fieldTemplates[field].orEmpty()
        if (saved.isNotBlank()) saved else defaultFieldTemplate(field)
    }
    if (audioUri != null && !templates.values.any { templateUsesVariable(it, "audioTag") }) {
        error("Current model templates do not include {audioTag}. Set audio field to {audioTag} in Main > Flashcard Creation.")
    }

    val config = AnkiExportConfig(
        deckName = persistedConfig.deckName.ifBlank { "Default" },
        modelName = model.name,
        fieldTemplates = templates,
        tags = parseAnkiTags(persistedConfig.tags)
    )

    val card = MinedCard(
        word = entry.term,
        sentence = cue.text,
        reading = entry.reading,
        definitions = listOf(definition),
        dictionaryName = entry.dictionary,
        dictionaryCss = dictionaryCss,
        pitch = entry.pitch,
        frequency = entry.frequency,
        cueStartMs = cue.startMs,
        cueEndMs = cue.endMs,
        audioUri = audioUri,
        audioTagOnly = true,
        requireCueAudioClip = true
    )

    withAnkiStep("export-note") {
        exportToAnkiDroidApi(context, card, config)
    }
}

private fun parseBookSrt(contentResolver: ContentResolver, uri: Uri): List<ReaderSubtitleCue> {
    val rawText = openReaderInputStream(contentResolver, uri)?.use { input ->
        input.bufferedReader(Charsets.UTF_8).readText()
    } ?: error("Unable to read SRT file")

    val normalizedText = rawText
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')

    val blocks = normalizedText.split(Regex("\n\\s*\n"))
    val cues = mutableListOf<ReaderSubtitleCue>()

    blocks.forEach { block ->
        val lines = block.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return@forEach

        val timingLineIndex = if (lines.first().all { it.isDigit() } && lines.size >= 2) 1 else 0
        val timingLine = lines.getOrNull(timingLineIndex) ?: return@forEach
        if (!timingLine.contains("-->")) return@forEach

        val parts = timingLine.split("-->")
        if (parts.size < 2) return@forEach

        val start = parseBookSrtTimestamp(parts[0].trim()) ?: return@forEach
        val endToken = parts[1].trim().substringBefore(' ')
        val end = parseBookSrtTimestamp(endToken) ?: return@forEach

        val cueTextRaw = lines.drop(timingLineIndex + 1).joinToString("\n").trim()
        val cueText = Html.fromHtml(cueTextRaw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        if (cueText.isBlank()) return@forEach

        cues += ReaderSubtitleCue(startMs = start, endMs = end, text = cueText)
    }

    if (cues.isEmpty()) error("No valid subtitle cues found in SRT")
    return cues.sortedBy { it.startMs }
}

private fun templateUsesVariable(template: String, variableName: String): Boolean {
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

private fun formatAnkiFailure(error: Throwable): String {
    val head = error.javaClass.simpleName.ifBlank { "Error" }
    val message = error.message?.trim().orEmpty()
    val cause = error.cause
    val causeText = if (cause == null) {
        ""
    } else {
        val causeMessage = cause.message?.trim().orEmpty()
        if (causeMessage.isBlank()) {
            " | cause=${cause.javaClass.simpleName}"
        } else {
            " | cause=${cause.javaClass.simpleName}: $causeMessage"
        }
    }
    val topFrame = error.stackTrace.firstOrNull()
    val frameText = if (topFrame == null) {
        ""
    } else {
        " @${topFrame.fileName ?: "Unknown"}:${topFrame.lineNumber}"
    }
    return if (message.isBlank()) {
        "$head$frameText$causeText"
    } else {
        "$head: $message$causeText"
    }
}

private inline fun <T> withAnkiStep(step: String, block: () -> T): T {
    return try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        val message = error.message?.trim().orEmpty()
        if (error is IllegalStateException && message.startsWith("Anki step failed [")) {
            throw error
        }
        val detail = if (message.isBlank()) {
            error.javaClass.simpleName
        } else {
            "${error.javaClass.simpleName}: $message"
        }
        throw IllegalStateException("Anki step failed [$step]. $detail", error)
    }
}

private fun openReaderInputStream(contentResolver: ContentResolver, uri: Uri): InputStream? {
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

private fun parseBookSrtTimestamp(raw: String): Long? {
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

private fun findBookCueIndexAtTime(cues: List<ReaderSubtitleCue>, timeMs: Long): Int {
    if (cues.isEmpty()) return -1
    var low = 0
    var high = cues.lastIndex
    while (low <= high) {
        val mid = (low + high) ushr 1
        val cue = cues[mid]
        when {
            timeMs < cue.startMs -> {
                high = mid - 1
            }
            timeMs >= cue.endMs -> {
                low = mid + 1
            }
            else -> {
                return mid
            }
        }
    }
    return -1
}

private fun findBookDisplayCueIndexAtTime(cues: List<ReaderSubtitleCue>, timeMs: Long): Int {
    val current = findBookCueIndexAtTime(cues, timeMs)
    if (current >= 0) return current
    return findCueIndexAtOrBeforeTime(cues, timeMs)
}

private fun findCueIndexAtOrBeforeTime(cues: List<ReaderSubtitleCue>, timeMs: Long): Int {
    if (cues.isEmpty()) return -1
    var low = 0
    var high = cues.lastIndex
    var candidate = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val cue = cues[mid]
        if (cue.startMs <= timeMs) {
            candidate = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return candidate
}

private fun findCueIndexAtOrAfterTime(cues: List<ReaderSubtitleCue>, timeMs: Long): Int {
    if (cues.isEmpty()) return -1
    var low = 0
    var high = cues.lastIndex
    var candidate = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val cue = cues[mid]
        if (cue.startMs >= timeMs) {
            candidate = mid
            high = mid - 1
        } else {
            low = mid + 1
        }
    }
    return candidate
}

private fun cueCollectionKey(startMs: Long, endMs: Long, text: String): String {
    return "$startMs|$endMs|${text.trim()}"
}

private fun formatBookTime(ms: Long): String {
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

private fun queryBookDisplayName(contentResolver: ContentResolver, uri: Uri): String {
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

@Composable
private fun RichDefinitionViewBook(
    definition: String,
    dictionaryName: String? = null,
    dictionaryCss: String? = null
) {
    val trimmed = definition.trim()
    if (trimmed.isBlank()) return

    if (looksLikeHtmlDefinitionBook(trimmed)) {
        val html = buildDefinitionHtmlBook(
            definitionHtml = trimmed,
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
        Text(trimmed)
    }
}

private fun buildDefinitionHtmlBook(
    definitionHtml: String,
    dictionaryName: String?,
    dictionaryCss: String?
): String {
    val dictionaryLabel = dictionaryName?.trim().orEmpty()
    val wrappedBody = if (dictionaryLabel.isBlank()) {
        definitionHtml
    } else {
        val safeDictionaryLabel = escapeHtmlTextBook(dictionaryLabel)
        val safeDictionaryAttr = escapeHtmlAttributeBook(dictionaryLabel)
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

    val customCss = buildScopedDictionaryCssBook(
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
            $wrappedBody
        </body>
        </html>
    """.trimIndent()
}

private fun looksLikeHtmlDefinitionBook(text: String): Boolean {
    return Regex("<\\s*/?\\s*[a-zA-Z][^>]*>").containsMatchIn(text)
}

private fun escapeHtmlTextBook(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private fun escapeHtmlAttributeBook(value: String): String {
    return escapeHtmlTextBook(value).replace("\"", "&quot;")
}

private fun escapeCssStringBook(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

private fun buildScopedDictionaryCssBook(rawCss: String, dictionaryName: String): String {
    val trimmed = rawCss.trim()
    if (trimmed.isBlank()) return ""
    if (dictionaryName.isBlank()) return trimmed

    val dictionaryAttr = escapeCssStringBook(dictionaryName)
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
