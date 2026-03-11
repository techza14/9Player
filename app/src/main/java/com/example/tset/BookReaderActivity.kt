package com.tekuza.p9player

import android.Manifest
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Html
import android.app.Activity
import android.widget.Toast
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tekuza.p9player.ui.theme.TsetTheme
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

private const val BOOK_READER_PERMISSION_REQUEST_CODE = 21_001
private const val BOOK_READER_PENDING_INTENT_REQUEST_CODE = 21_002
private const val BOOK_READER_SLEEP_OPTIONS_PREFS = "book_reader_sleep_options_prefs"
private const val BOOK_READER_SLEEP_EXIT_CONTROL_KEY = "sleep_exit_control"
private const val BOOK_READER_SLEEP_DISCONNECT_BT_KEY = "sleep_disconnect_bt"

class BookReaderActivity : ComponentActivity() {
    private var gamepadKeyHandler: ((KeyEvent) -> Boolean)? = null
    private var lastMotionHorizontalKeyCode: Int? = null
    private var lastMotionVerticalKeyCode: Int? = null
    private var lastControllerBluetoothAddress: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestPostNotificationsPermission()
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
                    registerGamepadKeyHandler = { handler -> gamepadKeyHandler = handler },
                    latestControllerAddressProvider = {
                        lastControllerBluetoothAddress
                            ?: loadTargetControllerInfo(this)?.address
                            ?: detectConnectedControllerInfo(this)?.address
                    },
                    onBack = { finish() }
                )
            }
        }
    }

    private fun maybeRequestPostNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            BOOK_READER_PERMISSION_REQUEST_CODE
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isLikelyControllerEvent(event)) {
            rememberControllerAddress(event.device)
            if (gamepadKeyHandler?.invoke(event) == true) {
                return true
            }
            // Swallow all other gamepad keys so only mapped settings keys have effects.
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (isFromControllerSource(event.source)) {
            rememberControllerAddress(event.device)
            handleControllerMotionAsDpad(event)
            // Always swallow controller motion to prevent focus navigation to UI controls.
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun isFromGamepad(event: KeyEvent): Boolean {
        val source = event.source
        return isFromControllerSource(source)
    }

    private fun isFromControllerSource(source: Int): Boolean {
        return (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    private fun isLikelyControllerEvent(event: KeyEvent): Boolean {
        if (isFromGamepad(event)) return true
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR -> true
            else -> false
        }
    }

    private fun handleControllerMotionAsDpad(event: MotionEvent) {
        if (event.action != MotionEvent.ACTION_MOVE) return

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val stickX = event.getAxisValue(MotionEvent.AXIS_X)
        val stickY = event.getAxisValue(MotionEvent.AXIS_Y)

        val x = when {
            kotlin.math.abs(hatX) >= 0.5f -> hatX
            kotlin.math.abs(stickX) >= 0.8f -> stickX
            else -> 0f
        }
        val y = when {
            kotlin.math.abs(hatY) >= 0.5f -> hatY
            kotlin.math.abs(stickY) >= 0.8f -> stickY
            else -> 0f
        }

        val horizontalKeyCode = when {
            x <= -0.5f -> KeyEvent.KEYCODE_DPAD_LEFT
            x >= 0.5f -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> null
        }
        val verticalKeyCode = when {
            y <= -0.5f -> KeyEvent.KEYCODE_DPAD_UP
            y >= 0.5f -> KeyEvent.KEYCODE_DPAD_DOWN
            else -> null
        }

        if (horizontalKeyCode == null) {
            lastMotionHorizontalKeyCode = null
        } else if (horizontalKeyCode != lastMotionHorizontalKeyCode) {
            gamepadKeyHandler?.invoke(KeyEvent(KeyEvent.ACTION_DOWN, horizontalKeyCode))
            lastMotionHorizontalKeyCode = horizontalKeyCode
        }

        if (verticalKeyCode == null) {
            lastMotionVerticalKeyCode = null
        } else if (verticalKeyCode != lastMotionVerticalKeyCode) {
            gamepadKeyHandler?.invoke(KeyEvent(KeyEvent.ACTION_DOWN, verticalKeyCode))
            lastMotionVerticalKeyCode = verticalKeyCode
        }
    }

    private fun rememberControllerAddress(device: InputDevice?) {
        val inputDevice = device ?: return
        val deviceName = inputDevice.name?.trim()?.takeIf { it.isNotBlank() }
        val address = runCatching {
            val method = InputDevice::class.java.methods
                .firstOrNull { it.name == "getBluetoothAddress" && it.parameterCount == 0 }
            method?.invoke(inputDevice) as? String
        }.getOrNull()
        if (!address.isNullOrBlank() && address != "00:00:00:00:00:00") {
            val normalized = address.uppercase(Locale.US)
            lastControllerBluetoothAddress = normalized
            saveTargetControllerInfo(
                context = this,
                info = TargetControllerInfo(
                    address = normalized,
                    name = deviceName
                )
            )
            return
        }
        if (lastControllerBluetoothAddress == null) {
            val detected = detectConnectedControllerInfo(this)
            if (detected != null) {
                lastControllerBluetoothAddress = detected.address
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

private data class ReaderAudioChapter(
    val startMs: Long,
    val title: String
)

private enum class AdjacentJumpMode {
    CUE,
    DURATION
}

@Composable
private fun BookReaderScreen(
    title: String,
    audioUri: Uri?,
    srtUri: Uri?,
    contentResolver: ContentResolver,
    registerGamepadKeyHandler: (((KeyEvent) -> Boolean)?) -> Unit,
    latestControllerAddressProvider: () -> String?,
    onBack: () -> Unit
) {
    var cues by remember { mutableStateOf<List<ReaderSubtitleCue>>(emptyList()) }
    var audioChapters by remember { mutableStateOf<List<ReaderAudioChapter>>(emptyList()) }
    var srtLoading by remember { mutableStateOf(false) }
    var srtError by remember { mutableStateOf<String?>(null) }

    var loadedDictionaries by remember { mutableStateOf<List<LoadedDictionary>>(emptyList()) }

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
    var bottomControlsVisible by remember { mutableStateOf(true) }
    var topActionsExpanded by remember { mutableStateOf(false) }
    var speedMenuExpanded by remember { mutableStateOf(false) }
    var sleepTimerDeadlineMs by remember { mutableStateOf<Long?>(null) }
    var sleepTimerOptionsVisible by remember { mutableStateOf(false) }
    var sleepCustomMinutesInput by remember { mutableStateOf("") }
    var sleepExitControlModeWhenDone by remember { mutableStateOf(false) }
    var sleepDisconnectControllerBluetoothWhenDone by remember { mutableStateOf(false) }
    var sleepOptionsReady by remember { mutableStateOf(false) }
    var adjacentJumpMode by remember { mutableStateOf(AdjacentJumpMode.CUE) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    var chapterOptionsVisible by remember { mutableStateOf(false) }
    var lastOverlayTapAtMs by remember { mutableStateOf(0L) }
    var lastGamepadCollectTapAtMs by remember { mutableStateOf(0L) }
    var lastGamepadCollectCueIndex by remember { mutableStateOf<Int?>(null) }
    var pendingSingleTapBaseCueIndex by remember { mutableStateOf<Int?>(null) }
    var pendingSingleTapJob by remember { mutableStateOf<Job?>(null) }

    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var dragPreviewPositionMs by remember { mutableStateOf<Long?>(null) }

    val context = LocalContext.current
    val view = LocalView.current
    val playbackPositionKey = remember(title, audioUri, srtUri) {
        buildBookReaderPlaybackKey(title, audioUri, srtUri)
    }
    val scope = rememberCoroutineScope()
    val player = remember(context) { ExoPlayer.Builder(context).build() }
    val notificationController = remember(context, player, title, audioUri, srtUri) {
        PlaybackNotificationController(
            context = context,
            player = player,
            title = title,
            contentIntent = buildBookReaderNotificationPendingIntent(
                context = context,
                title = title,
                audioUri = audioUri,
                srtUri = srtUri
            )
        )
    }
    val lyricsListState = rememberLazyListState()
    val collectedCueKeys = remember { hashSetOf<String>() }
    var collectedCueUiVersion by remember { mutableStateOf(0) }

    DisposableEffect(player) {
        onDispose { player.release() }
    }
    DisposableEffect(notificationController) {
        onDispose { notificationController.release() }
    }
    DisposableEffect(Unit) {
        onDispose { pendingSingleTapJob?.cancel() }
    }
    DisposableEffect(controlModeEnabled, view) {
        view.keepScreenOn = controlModeEnabled
        onDispose {
            view.keepScreenOn = false
        }
    }
    DisposableEffect(controlModeEnabled, context) {
        val shouldDimToMinimum = controlModeEnabled && loadGamepadControlConfig(context).dimScreenInControlMode
        applyControlModeScreenBrightness(context, dimToMinimum = shouldDimToMinimum)
        onDispose {
            applyControlModeScreenBrightness(context, dimToMinimum = false)
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
            delay(250L)
        }
    }

    LaunchedEffect(audioUri, playbackPositionKey) {
        val selectedAudio = audioUri ?: return@LaunchedEffect
        val savedPositionMs = withContext(Dispatchers.IO) {
            loadBookReaderPlaybackPosition(context, playbackPositionKey)
        }
        val restoredPositionMs = savedPositionMs.coerceAtLeast(0L)
        player.setMediaItem(MediaItem.fromUri(selectedAudio))
        player.prepare()
        player.pause()
        player.seekTo(restoredPositionMs)
        positionMs = restoredPositionMs
    }

    LaunchedEffect(audioUri) {
        val selectedAudio = audioUri ?: run {
            audioChapters = emptyList()
            return@LaunchedEffect
        }
        val loadedChapters = withContext(Dispatchers.IO) {
            loadM4bChapters(
                contentResolver = contentResolver,
                audioUri = selectedAudio
            )
                .map { chapter ->
                    ReaderAudioChapter(
                        startMs = chapter.startMs,
                        title = chapter.title
                    )
                }
        }
        audioChapters = loadedChapters
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
    }

    val dictionaryCssByName = remember(loadedDictionaries) {
        loadedDictionaries.associate { it.name to it.stylesCss }
    }
    val dictionaryPriorityByName = remember(loadedDictionaries) {
        loadedDictionaries.mapIndexed { index, dictionary -> dictionary.name to index }.toMap()
    }
    val groupedLookupPopupResults = remember(lookupPopupResults, dictionaryCssByName, dictionaryPriorityByName) {
        groupLookupResultsByTerm(
            results = lookupPopupResults,
            dictionaryCssByName = dictionaryCssByName,
            dictionaryPriorityByName = dictionaryPriorityByName
        ).take(10)
    }

    val playbackCueIndex = remember(positionMs, cues) { findBookCueIndexAtTime(cues, positionMs) }
    val effectiveAdjacentJumpMode = remember(cues, adjacentJumpMode) {
        if (cues.isEmpty()) AdjacentJumpMode.DURATION else adjacentJumpMode
    }
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

    LaunchedEffect(controlModeStatus) {
        val message = controlModeStatus?.trim().orEmpty()
        if (message.isNotEmpty()) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(context) {
        val options = loadBookReaderSleepOptions(context)
        sleepExitControlModeWhenDone = options.exitControlModeWhenDone
        sleepDisconnectControllerBluetoothWhenDone = options.disconnectBluetoothWhenDone
        sleepOptionsReady = true
    }

    LaunchedEffect(
        sleepExitControlModeWhenDone,
        sleepDisconnectControllerBluetoothWhenDone,
        sleepOptionsReady
    ) {
        if (!sleepOptionsReady) return@LaunchedEffect
        saveBookReaderSleepOptions(
            context = context,
            exitControlModeWhenDone = sleepExitControlModeWhenDone,
            disconnectBluetoothWhenDone = sleepDisconnectControllerBluetoothWhenDone
        )
    }

    LaunchedEffect(playbackSpeed) {
        player.playbackParameters = PlaybackParameters(playbackSpeed)
    }

    val sliderMax = if (durationMs > 0L) durationMs.toFloat() else 1f
    val sliderValue = when {
        durationMs <= 0L -> 0f
        dragPreviewPositionMs != null -> dragPreviewPositionMs!!.coerceIn(0L, durationMs).toFloat()
        else -> positionMs.coerceIn(0L, durationMs).toFloat()
    }
    val progressPercent = remember(previewPositionMs, durationMs) {
        if (durationMs <= 0L) {
            0
        } else {
            ((previewPositionMs.coerceIn(0L, durationMs) * 100L) / durationMs)
                .toInt()
                .coerceIn(0, 100)
        }
    }
    val sleepRemainingLabel = remember(sleepTimerDeadlineMs, positionMs) {
        val deadline = sleepTimerDeadlineMs ?: return@remember null
        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
        formatBookTime(remaining)
    }
    val activeChapterIndex = remember(previewPositionMs, audioChapters) {
        findBookChapterIndexAtTime(audioChapters, previewPositionMs)
    }
    val favoriteCue = remember(playbackCueIndex, activeCueIndex, cues) {
        when {
            playbackCueIndex in cues.indices -> cues[playbackCueIndex]
            activeCueIndex in cues.indices -> cues[activeCueIndex]
            else -> null
        }
    }
    val favoriteCueKey = remember(favoriteCue) {
        favoriteCue?.let { cueCollectionKey(it.startMs, it.endMs, it.text) }
    }
    val favoriteCueCollected = remember(favoriteCueKey, collectedCueUiVersion) {
        favoriteCueKey?.let { collectedCueKeys.contains(it) } == true
    }

    LaunchedEffect(audioChapters.size) {
        if (audioChapters.isEmpty()) {
            chapterOptionsVisible = false
        }
    }

    LaunchedEffect(playbackPositionKey, player, isPlaying) {
        if (playbackPositionKey.isBlank()) return@LaunchedEffect
        var lastSavedPosition = Long.MIN_VALUE
        suspend fun saveSnapshotIfChanged() {
            val current = player.currentPosition.coerceAtLeast(0L)
            val total = if (player.duration > 0L) player.duration else 0L
            val normalized = normalizeBookReaderPlaybackPosition(current, total)
            if (normalized == lastSavedPosition) return
            lastSavedPosition = normalized
            withContext(Dispatchers.IO) {
                saveBookReaderPlaybackPosition(
                    context = context,
                    bookKey = playbackPositionKey,
                    positionMs = normalized,
                    durationMs = total
                )
            }
        }
        if (!isPlaying) {
            saveSnapshotIfChanged()
            return@LaunchedEffect
        }
        while (true) {
            delay(2_500L)
            saveSnapshotIfChanged()
        }
    }

    DisposableEffect(playbackPositionKey, player) {
        onDispose {
            val current = player.currentPosition.coerceAtLeast(0L)
            val total = if (player.duration > 0L) player.duration else 0L
            val normalized = normalizeBookReaderPlaybackPosition(current, total)
            saveBookReaderPlaybackPosition(
                context = context,
                bookKey = playbackPositionKey,
                positionMs = normalized,
                durationMs = total
            )
        }
    }

    LaunchedEffect(title) {
        val existing = withContext(Dispatchers.IO) { loadBookReaderCollectedCues(context) }
        collectedCueKeys.clear()
        existing
            .filter { it.bookTitle == title }
            .forEach { cue ->
                collectedCueKeys += cueCollectionKey(cue.startMs, cue.endMs, cue.text)
            }
        collectedCueUiVersion += 1
    }

    LaunchedEffect(
        sleepTimerDeadlineMs,
        sleepExitControlModeWhenDone,
        sleepDisconnectControllerBluetoothWhenDone
    ) {
        val deadline = sleepTimerDeadlineMs ?: return@LaunchedEffect
        while (sleepTimerDeadlineMs == deadline) {
            if (System.currentTimeMillis() >= deadline) {
                player.pause()
                sleepTimerDeadlineMs = null
                val statusParts = mutableListOf<String>()
                if (sleepDisconnectControllerBluetoothWhenDone) {
                    val address = latestControllerAddressProvider()
                    val behavior = loadControllerBluetoothBehaviorConfig(context)
                    val bluetoothResult = withContext(Dispatchers.IO) {
                        tryDisconnectTargetControllerThenDisableBluetooth(
                            context = context,
                            targetAddress = address,
                            allowDisableBluetoothFallback = behavior.disableBluetoothIfControllerMissing
                        )
                    }
                    when (bluetoothResult.outcome) {
                        SleepBluetoothOutcome.TARGET_DISCONNECTED -> {
                            statusParts += "手柄已断开"
                        }
                        SleepBluetoothOutcome.BLUETOOTH_DISABLED -> {
                            statusParts += "兜底：已关闭蓝牙"
                        }
                        SleepBluetoothOutcome.FAILED -> {
                            statusParts += "蓝牙操作失败（${bluetoothResult.detail}）"
                        }
                    }
                }
                if (sleepExitControlModeWhenDone) {
                    controlModeEnabled = false
                    view.keepScreenOn = false
                    statusParts += "已退出控制模式"
                }
                if (statusParts.isEmpty()) {
                    controlModeStatus = "计时结束。"
                } else {
                    controlModeStatus = "计时结束：${statusParts.joinToString("，")}"
                }
                break
            }
            delay(250L)
        }
    }

    LaunchedEffect(positionMs, controlTargetCueIndex, cues) {
        val targetIndex = controlTargetCueIndex ?: return@LaunchedEffect
        val cue = cues.getOrNull(targetIndex) ?: run {
            controlTargetCueIndex = null
            return@LaunchedEffect
        }
        if (positionMs >= cue.endMs) {
            controlTargetCueIndex = null
            val key = cueCollectionKey(cue.startMs, cue.endMs, cue.text)
            if (collectedCueKeys.add(key)) {
                collectedCueUiVersion += 1
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
                    controlModeStatus = "已收藏第 ${targetIndex + 1}/${cues.size} 句，继续播放。"
                }
            } else {
                controlModeStatus = "第 ${targetIndex + 1}/${cues.size} 句已收藏，继续播放。"
            }
        }
    }

    fun toggleFavoriteCue() {
        val cue = favoriteCue ?: return
        val key = cueCollectionKey(cue.startMs, cue.endMs, cue.text)
        val cueIndexLabel = when {
            playbackCueIndex in cues.indices -> "${playbackCueIndex + 1}/${cues.size}"
            activeCueIndex in cues.indices -> "${activeCueIndex + 1}/${cues.size}"
            else -> null
        }
        scope.launch {
            if (collectedCueKeys.contains(key)) {
                val removed = withContext(Dispatchers.IO) {
                    val existing = loadBookReaderCollectedCues(context)
                    val matched = existing.filter {
                        it.bookTitle == title &&
                            it.startMs == cue.startMs &&
                            it.endMs == cue.endMs &&
                            it.text == cue.text
                    }
                    matched.forEach { item ->
                        removeBookReaderCollectedCue(context, item.id)
                    }
                    matched.isNotEmpty()
                }
                if (removed) {
                    collectedCueKeys.remove(key)
                    collectedCueUiVersion += 1
                    controlModeStatus = cueIndexLabel?.let { "已取消收藏第 $it 句。" } ?: "已取消收藏。"
                } else {
                    controlModeStatus = "未找到该收藏记录。"
                }
            } else {
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
                    collectedCueKeys.add(key)
                    collectedCueUiVersion += 1
                    controlModeStatus = cueIndexLabel?.let { "已收藏第 $it 句。" } ?: "已收藏。"
                } else {
                    controlModeStatus = "该句已在收藏中。"
                    collectedCueKeys.add(key)
                    collectedCueUiVersion += 1
                }
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

    fun jumpToCue(index: Int, showStatus: Boolean = true) {
        val cue = cues.getOrNull(index) ?: return
        selectedLookupRange = null
        lookupPopupVisible = false
        player.seekTo(cue.startMs)
        player.play()
        controlTargetCueIndex = if (controlModeEnabled) index else null
        if (showStatus) {
            controlModeStatus = "已跳转到第 ${index + 1}/${cues.size} 句"
        }
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
            controlModeStatus = "手动拖动进度。"
        }
    }

    fun jumpToAdjacentCue(step: Int) {
        if (effectiveAdjacentJumpMode == AdjacentJumpMode.DURATION) {
            val stepMillis = loadAudiobookSettingsConfig(context).seekStepMillis
            val delta = if (step < 0) -stepMillis else stepMillis
            seekToManual(positionMs + delta)
            player.play()
            return
        }
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
        jumpToCue(targetIndex.coerceIn(0, lastIndex), showStatus = false)
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerDeadlineMs = if (minutes <= 0) {
            null
        } else {
            System.currentTimeMillis() + (minutes * 60_000L)
        }
        sleepTimerOptionsVisible = false
        controlModeStatus = if (minutes <= 0) "计时已清除。" else "已设置计时：${minutes} 分钟"
    }

    fun applyCustomSleepTimer() {
        val minutes = sleepCustomMinutesInput.trim().toIntOrNull()
        if (minutes == null || minutes <= 0) {
            controlModeStatus = "自定义分钟无效。"
            return
        }
        setSleepTimer(minutes)
    }

    fun playCueForControl(index: Int) {
        val cue = cues.getOrNull(index) ?: return
        player.seekTo(cue.startMs)
        player.play()
        controlTargetCueIndex = index
        controlModeStatus = "播放第 ${index + 1}/${cues.size} 句"
    }

    fun jumpToChapter(chapter: ReaderAudioChapter) {
        seekToManual(chapter.startMs)
        player.play()
        controlModeStatus = "已跳转章节：${chapter.title}"
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
            controlModeStatus = "重播中双击：播放上一句并收藏。"
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
                controlModeStatus = "单击：重播当前句并收藏。"
            }
        }
    }

    fun handleGamepadCollect(doubleTapEnabled: Boolean) {
        if (cues.isEmpty()) return
        val baseIndex = when {
            playbackCueIndex >= 0 -> playbackCueIndex
            else -> findCueIndexAtOrBeforeTime(cues, positionMs).coerceAtLeast(0)
        }
        val now = System.currentTimeMillis()
        val isDoubleTap = doubleTapEnabled &&
            lastGamepadCollectCueIndex == baseIndex &&
            now - lastGamepadCollectTapAtMs <= 320L
        val targetIndex = if (isDoubleTap) {
            (baseIndex - 1).coerceAtLeast(0)
        } else {
            baseIndex
        }
        lastGamepadCollectCueIndex = baseIndex
        lastGamepadCollectTapAtMs = now
        playCueForControl(targetIndex)
        controlModeStatus = if (isDoubleTap) {
            "手柄：收藏上一句。"
        } else {
            "手柄：收藏当前句。"
        }
    }

    fun handleGamepadKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount > 0) return true
        val config = loadGamepadControlConfig(context)

        return when (event.keyCode) {
            config.previousKeyCode -> {
                jumpToAdjacentCue(-1)
                true
            }
            config.nextKeyCode -> {
                jumpToAdjacentCue(1)
                true
            }
            config.collectKeyCode -> {
                handleGamepadCollect(doubleTapEnabled = config.doubleTapCollectPrevious)
                true
            }
            else -> false
        }
    }

    fun triggerPopupLookup(cue: ReaderSubtitleCue, offset: Int) {
        val dictionariesSnapshot = loadedDictionaries
        if (dictionariesSnapshot.isEmpty()) {
            lookupPopupVisible = true
            lookupPopupLoading = false
            lookupPopupResults = emptyList()
            lookupPopupTitle = ""
            lookupPopupError = "未加载辞典，请先在主页导入辞典。"
            lookupPopupCue = cue
            return
        }

        val selection = findLookupSelection(cue.text, offset)
        val selectionRange = selection?.range
        selectedLookupRange = selectionRange
        val selectedToken = selection?.text?.trim()?.takeIf { it.isNotBlank() }

        val candidates = buildList {
            selectedToken?.let { add(it) }
            selectedToken?.let { addAll(extractLookupCandidates(it)) }
        }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(16)

        if (candidates.isEmpty()) {
            lookupPopupVisible = true
            lookupPopupLoading = false
            lookupPopupResults = emptyList()
            lookupPopupTitle = selectedToken.orEmpty()
            lookupPopupError = "当前位置没有可查询词。"
            lookupPopupCue = cue
            return
        }

        lookupPopupVisible = true
        lookupPopupLoading = true
        lookupPopupResults = emptyList()
        lookupPopupError = null
        lookupPopupTitle = candidates.firstOrNull() ?: selectedToken.orEmpty()
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
                    lookupPopupError = "无查询结果。"
                    selectedLookupRange = null
                } else {
                    selectedLookupRange = trimSelectionRangeByMatchedLength(
                        selectionRange,
                        hits.first().matchedLength
                    )
                }
            }.onFailure {
                lookupPopupResults = emptyList()
                lookupPopupError = it.message ?: "查询失败"
                selectedLookupRange = null
            }
            lookupPopupLoading = false
        }
    }

    fun addLookupGroupToAnki(groupedResult: GroupedLookupResult) {
        val dictionaryGroup = groupedResult.dictionaries.firstOrNull() ?: run {
            ankiActionStatus = "没有可导出的辞典内容。"
            return
        }
        val cue = lookupPopupCue ?: return
        val popupSelectionText = selectedLookupRange?.let { range ->
            val start = range.first.coerceIn(0, cue.text.length)
            val endExclusive = (range.last + 1).coerceIn(start, cue.text.length)
            if (endExclusive > start) cue.text.substring(start, endExclusive) else ""
        }?.trim()?.takeIf { it.isNotBlank() }
        val glossarySections = buildGroupedGlossarySections(groupedResult)
        val primaryDefinition = dictionaryGroup.definitions
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
        scope.launch {
            ankiActionStatus = "正在添加到 Anki..."
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    addLookupDefinitionToAnki(
                        context = context,
                        cue = cue,
                        audioUri = audioUri,
                        entry = dictionaryGroup.entry,
                        definition = primaryDefinition.ifBlank { groupedResult.term },
                        dictionaryCss = null,
                        glossarySections = glossarySections,
                        popupSelectionText = popupSelectionText
                    )
                }
            }
            ankiActionStatus = result.fold(
                onSuccess = { "已添加到 Anki。" },
                onFailure = { formatAnkiFailure(it) }
            )
        }
    }

    fun handleControlOverlaySwipe(step: Int) {
        if (step < 0) {
            jumpToAdjacentCue(-1)
            controlModeStatus = "左滑：上一句。"
        } else {
            jumpToAdjacentCue(1)
            controlModeStatus = "右滑：下一句。"
        }
    }

    fun exitControlModeByTwoFingerLongPress() {
        pendingSingleTapJob?.cancel()
        pendingSingleTapJob = null
        pendingSingleTapBaseCueIndex = null
        controlTargetCueIndex = null
        controlModeEnabled = false
        controlModeStatus = "已退出控制模式。"
    }

    DisposableEffect(Unit) {
        registerGamepadKeyHandler { event -> handleGamepadKeyEvent(event) }
        onDispose { registerGamepadKeyHandler(null) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (bottomControlsVisible) {
                    Surface(tonalElevation = 4.dp) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (audioChapters.isNotEmpty()) {
                                val activeChapterTitle = audioChapters
                                    .getOrNull(activeChapterIndex)
                                    ?.title
                                    ?.takeIf { it.isNotBlank() }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { chapterOptionsVisible = !chapterOptionsVisible }
                                    ) {
                                    Text(if (chapterOptionsVisible) "章节 ▲" else "章节 ▼")
                                    }
                                    if (activeChapterTitle != null) {
                                        Text(
                                            "Now: ${
                                                if (activeChapterTitle.length > 26) {
                                                    activeChapterTitle.take(26) + "..."
                                                } else {
                                                    activeChapterTitle
                                                }
                                            }"
                                        )
                                    }
                                }
                            }
                            if (audioChapters.isNotEmpty() && chapterOptionsVisible) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                        .verticalScroll(rememberScrollState()),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    audioChapters.forEachIndexed { index, chapter ->
                                        val label = if (chapter.title.length > 16) {
                                            chapter.title.take(16) + "..."
                                        } else {
                                            chapter.title
                                        }
                                        val indexedLabel = "${index + 1}. $label"
                                        if (index == activeChapterIndex) {
                                            Button(
                                                modifier = Modifier.fillMaxWidth(),
                                                onClick = { jumpToChapter(chapter) }
                                            ) {
                                                Text(indexedLabel)
                                            }
                                        } else {
                                            OutlinedButton(
                                                modifier = Modifier.fillMaxWidth(),
                                                onClick = { jumpToChapter(chapter) }
                                            ) {
                                                Text(indexedLabel)
                                            }
                                        }
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(formatBookTime(previewPositionMs))
                                Slider(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp),
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
                                Text(formatBookTime(durationMs))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(onClick = { jumpToAdjacentCue(-1) }) { Text("<<") }
                                Button(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                                    Text(if (isPlaying) "||" else ">")
                                }
                                OutlinedButton(onClick = { jumpToAdjacentCue(1) }) { Text(">>") }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("进度：$progressPercent%")
                                val stepSeconds = (loadAudiobookSettingsConfig(context).seekStepMillis / 1000L)
                                OutlinedButton(
                                    enabled = cues.isNotEmpty(),
                                    onClick = {
                                        adjacentJumpMode = if (adjacentJumpMode == AdjacentJumpMode.CUE) {
                                            AdjacentJumpMode.DURATION
                                        } else {
                                            AdjacentJumpMode.CUE
                                        }
                                    }
                                ) {
                                    val label = when (effectiveAdjacentJumpMode) {
                                        AdjacentJumpMode.CUE -> "按台词"
                                        AdjacentJumpMode.DURATION -> "按时长(${stepSeconds}秒)"
                                    }
                                    Text(label)
                                }
                            }
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
                        Text("< 返回")
                    }
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { if (favoriteCue != null) toggleFavoriteCue() },
                        enabled = favoriteCue != null
                    ) {
                        Text(if (favoriteCueCollected) "★" else "☆")
                    }
                    TextButton(onClick = { sleepTimerOptionsVisible = !sleepTimerOptionsVisible }) {
                        Text(
                            if (sleepRemainingLabel != null) {
                                "计时 ${sleepRemainingLabel}"
                            } else {
                                "计时"
                            }
                        )
                    }
                    Box {
                        TextButton(onClick = { speedMenuExpanded = true }) {
                            Text("${playbackSpeed}x")
                        }
                        DropdownMenu(
                            expanded = speedMenuExpanded,
                            onDismissRequest = { speedMenuExpanded = false }
                        ) {
                            listOf(0.8f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                                val isCurrent = abs(speed - playbackSpeed) < 0.001f
                                DropdownMenuItem(
                                    text = { Text((if (isCurrent) "✓ " else "") + "${speed}x") },
                                    onClick = {
                                        playbackSpeed = speed
                                        speedMenuExpanded = false
                                        controlModeStatus = "播放速度：${speed}x"
                                    }
                                )
                            }
                        }
                    }
                    Box {
                        TextButton(onClick = { topActionsExpanded = true }) {
                            Text("...")
                        }
                        DropdownMenu(
                            expanded = topActionsExpanded,
                            onDismissRequest = { topActionsExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (bottomControlsVisible) "隐藏控制条" else "显示控制条") },
                                onClick = {
                                    bottomControlsVisible = !bottomControlsVisible
                                    topActionsExpanded = false
                                    controlModeStatus = if (bottomControlsVisible) "已显示控制条。" else "已隐藏控制条。"
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (controlModeEnabled) "退出控制模式" else "进入控制模式") },
                                onClick = {
                                    controlModeEnabled = !controlModeEnabled
                                    topActionsExpanded = false
                                    controlModeStatus = if (controlModeEnabled) "已进入控制模式。" else "已退出控制模式。"
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (lyricsMode) "切换到字幕模式" else "切换到单句模式") },
                                onClick = {
                                    lyricsMode = !lyricsMode
                                    topActionsExpanded = false
                                    controlModeStatus = if (lyricsMode) "已切换到单句模式。" else "已切换到字幕模式。"
                                }
                            )
                        }
                    }
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
                            srtLoading -> Text("正在解析 SRT...")
                            srtError != null -> Text("SRT 错误：$srtError", color = MaterialTheme.colorScheme.error)
                            cues.isEmpty() -> Text("没有可用字幕。")
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
                            activeCue == null -> Text("等待播放进入字幕范围...")
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

        if (sleepTimerOptionsVisible) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(-16, 84),
                onDismissRequest = { sleepTimerOptionsVisible = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                Card {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (sleepRemainingLabel != null) {
                            Text("计时剩余：$sleepRemainingLabel")
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(onClick = { setSleepTimer(15) }) { Text("15m") }
                            OutlinedButton(onClick = { setSleepTimer(30) }) { Text("30m") }
                            OutlinedButton(onClick = { setSleepTimer(60) }) { Text("60m") }
                            TextButton(onClick = { setSleepTimer(0) }) { Text("关闭") }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = sleepCustomMinutesInput,
                                onValueChange = { value ->
                                    sleepCustomMinutesInput = value.filter { it.isDigit() }.take(4)
                                },
                                modifier = Modifier.weight(1f),
                                label = { Text("自定义分钟") },
                                singleLine = true
                            )
                            Button(onClick = { applyCustomSleepTimer() }) {
                                Text("设置")
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("计时完关闭控制模式")
                            Switch(
                                checked = sleepExitControlModeWhenDone,
                                onCheckedChange = { checked ->
                                    sleepExitControlModeWhenDone = checked
                                }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("计时完断开手柄蓝牙(Shizuku)")
                            Switch(
                                checked = sleepDisconnectControllerBluetoothWhenDone,
                                onCheckedChange = { checked ->
                                    sleepDisconnectControllerBluetoothWhenDone = checked
                                }
                            )
                        }
                        val targetControllerAddress = latestControllerAddressProvider()
                        Text(
                            "目标手柄：${
                                targetControllerAddress?.ifBlank { null } ?: "暂无"
                            }"
                        )
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
                    }
                    .pointerInput(controlModeEnabled, playbackCueIndex, cues.size) {
                        awaitPointerEventScope {
                            while (true) {
                                val first = awaitPointerEvent()
                                val pressedCount = first.changes.count { it.pressed }
                                if (pressedCount < 2) continue

                                val startAt = System.currentTimeMillis()
                                var cancelled = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val nowPressed = event.changes.count { it.pressed }
                                    if (nowPressed < 2) {
                                        cancelled = true
                                        break
                                    }
                                    val moved = event.changes.any { change ->
                                        val delta = change.positionChange()
                                        abs(delta.x) > 10f || abs(delta.y) > 10f
                                    }
                                    if (moved) {
                                        cancelled = true
                                        break
                                    }
                                    if (System.currentTimeMillis() - startAt >= 650L) {
                                        exitControlModeByTwoFingerLongPress()
                                        break
                                    }
                                }
                                if (!controlModeEnabled) break
                                if (cancelled) continue
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "控制模式\n屏幕常亮\n可开启计时结束自动退出\n单击：重播当前句并收藏\n重播中双击：播放上一句并收藏\n左滑：上一句\n右滑：下一句\n双指长按：退出",
                    color = Color.White
                )
            }
        }
    }

    if (lookupPopupVisible) {
        val popupOffsetY = with(LocalDensity.current) { 180.dp.roundToPx() }
        Popup(
            alignment = Alignment.Center,
            offset = IntOffset(0, popupOffsetY),
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
                    .padding(horizontal = 8.dp),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
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
                            Text("查询中...")
                        }
                        if (lookupPopupError != null) {
                            Text(lookupPopupError!!, color = MaterialTheme.colorScheme.error)
                        }
                        if (!lookupPopupLoading && groupedLookupPopupResults.isEmpty() && lookupPopupError == null) {
                            Text("无查询结果。")
                        }
                        groupedLookupPopupResults.forEach { groupedResult ->
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
                                            onClick = { addLookupGroupToAnki(groupedResult) }
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
                                                if (!dictionaryGroup.pitch.isNullOrBlank()) Text("音调：${dictionaryGroup.pitch}")
                                                if (!dictionaryGroup.frequency.isNullOrBlank()) Text("词频：${dictionaryGroup.frequency}")

                                                dictionaryGroup.definitions.forEach { definition ->
                                                    Card(modifier = Modifier.fillMaxWidth()) {
                                                        Column(
                                                            modifier = Modifier.padding(8.dp),
                                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                                        ) {
                                                            RichDefinitionViewBook(
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
                        TextButton(onClick = { lookupPopupVisible = false }) {
                            Text("关闭")
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
        addStyle(SpanStyle(background = Color(0x66A0A0A0)), start, endExclusive)
    }
}

private fun findLookupSelection(
    text: String,
    offset: Int
): LookupScanSelection? {
    return selectLookupScanText(
        text = text,
        charOffset = offset
    )
}

private fun computeBookLookupResults(
    context: Context,
    dictionaries: List<LoadedDictionary>,
    candidates: List<String>
): List<DictionarySearchResult> {
    if (dictionaries.isEmpty() || candidates.isEmpty()) return emptyList()
    val prioritizedCandidates = candidates
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(16)
    if (prioritizedCandidates.isEmpty()) return emptyList()

    val merged = linkedMapOf<String, DictionarySearchResult>()
    val candidateOrder = prioritizedCandidates.withIndex().associate { it.value to it.index }

    fun mergeProfileHits(
        profile: DictionaryQueryProfile,
        sourceCandidates: List<String>,
        perCandidateLimit: Int
    ) {
        sourceCandidates.forEach { candidate ->
            val index = candidateOrder[candidate] ?: return@forEach
            val candidateBoost = (prioritizedCandidates.size - index).coerceAtLeast(1) * 2
            searchDictionarySql(
                context = context,
                dictionaries = dictionaries,
                query = candidate,
                maxResults = perCandidateLimit,
                profile = profile
            ).forEach { hit ->
                val key = entryStableKey(hit.entry)
                val boosted = hit.copy(score = hit.score + candidateBoost)
                val existing = merged[key]
                if (existing == null || boosted.score > existing.score) {
                    merged[key] = boosted
                }
            }
        }
    }

    mergeProfileHits(
        profile = DictionaryQueryProfile.FAST,
        sourceCandidates = prioritizedCandidates.take(8),
        perCandidateLimit = (MAX_LOOKUP_RESULTS / 2).coerceAtLeast(12)
    )

    if (merged.size < 8) {
        mergeProfileHits(
            profile = DictionaryQueryProfile.FULL,
            sourceCandidates = prioritizedCandidates.take(6),
            perCandidateLimit = MAX_LOOKUP_RESULTS
        )
    }

    return merged.values
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
    dictionaryCss: String?,
    glossarySections: List<String> = emptyList(),
    popupSelectionText: String? = null
) {
    if (!isAnkiInstalled(context)) error("AnkiDroid is not installed.")
    if (!hasAnkiReadWritePermission(context)) {
        error("Anki permission not granted. Authorize in 设置 > Anki first.")
    }

    val persistedConfig = withAnkiStep("load-config") {
        loadPersistedAnkiConfig(context)
    }
    if (persistedConfig.modelName.isBlank()) {
        error("No Anki model configured. Set model in 设置 > Anki.")
    }

    val catalog = withAnkiStep("load-catalog") {
        loadAnkiCatalog(context)
    }
    val model = catalog.models.firstOrNull { it.name == persistedConfig.modelName }
        ?: error("Configured model not found: ${persistedConfig.modelName}")

    val templates = model.fields.associateWith { field ->
        persistedConfig.fieldTemplates[field].orEmpty()
    }
    if (!hasAnyAnkiFieldTemplate(templates)) {
        error("All field variables are empty. Configure at least one marker in 设置 > Anki.")
    }
    if (audioUri != null && !templates.values.any { templateUsesVariable(it, "cut-audio") }) {
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

private data class BookReaderSleepOptions(
    val exitControlModeWhenDone: Boolean,
    val disconnectBluetoothWhenDone: Boolean
)

private fun loadBookReaderSleepOptions(context: Context): BookReaderSleepOptions {
    val prefs = context.getSharedPreferences(BOOK_READER_SLEEP_OPTIONS_PREFS, Context.MODE_PRIVATE)
    return BookReaderSleepOptions(
        exitControlModeWhenDone = prefs.getBoolean(BOOK_READER_SLEEP_EXIT_CONTROL_KEY, false),
        disconnectBluetoothWhenDone = prefs.getBoolean(BOOK_READER_SLEEP_DISCONNECT_BT_KEY, false)
    )
}

private fun saveBookReaderSleepOptions(
    context: Context,
    exitControlModeWhenDone: Boolean,
    disconnectBluetoothWhenDone: Boolean
) {
    context.getSharedPreferences(BOOK_READER_SLEEP_OPTIONS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(BOOK_READER_SLEEP_EXIT_CONTROL_KEY, exitControlModeWhenDone)
        .putBoolean(BOOK_READER_SLEEP_DISCONNECT_BT_KEY, disconnectBluetoothWhenDone)
        .apply()
}

private fun cueCollectionKey(startMs: Long, endMs: Long, text: String): String {
    return "$startMs|$endMs|${text.trim()}"
}

private fun buildBookReaderNotificationPendingIntent(
    context: Context,
    title: String,
    audioUri: Uri?,
    srtUri: Uri?
): PendingIntent {
    val intent = Intent(context, BookReaderActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(BookReaderActivity.EXTRA_BOOK_TITLE, title)
        putExtra(BookReaderActivity.EXTRA_AUDIO_URI, audioUri?.toString())
        putExtra(BookReaderActivity.EXTRA_SRT_URI, srtUri?.toString())
    }
    val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    return PendingIntent.getActivity(
        context,
        BOOK_READER_PENDING_INTENT_REQUEST_CODE,
        intent,
        pendingIntentFlags
    )
}

private fun buildBookReaderPlaybackKey(
    title: String,
    audioUri: Uri?,
    srtUri: Uri?
): String {
    val raw = "title=$title|audio=${audioUri?.toString().orEmpty()}|srt=${srtUri?.toString().orEmpty()}"
    return buildDictionaryCacheKey(raw, title.ifBlank { "book" })
}

private fun normalizeBookReaderPlaybackPosition(positionMs: Long, durationMs: Long): Long {
    val safe = positionMs.coerceAtLeast(0L)
    if (durationMs <= 0L) return safe
    return if (safe >= durationMs - 1_500L) 0L else safe
}

private fun findBookChapterIndexAtTime(chapters: List<ReaderAudioChapter>, timeMs: Long): Int {
    if (chapters.isEmpty()) return -1
    var low = 0
    var high = chapters.lastIndex
    var candidate = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val chapter = chapters[mid]
        if (chapter.startMs <= timeMs) {
            candidate = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return candidate
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
                    <i>($safeDictionaryLabel)</i> $definitionHtml
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

private fun applyControlModeScreenBrightness(context: Context, dimToMinimum: Boolean) {
    val activity = context.findHostActivity() ?: return
    val window = activity.window ?: return
    val attrs = window.attributes
    val targetBrightness = if (dimToMinimum) {
        0.01f
    } else {
        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
    if (attrs.screenBrightness != targetBrightness) {
        attrs.screenBrightness = targetBrightness
        window.attributes = attrs
    }
}

private tailrec fun Context.findHostActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findHostActivity()
        else -> null
    }
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




