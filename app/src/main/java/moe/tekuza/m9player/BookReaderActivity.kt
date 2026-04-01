package moe.tekuza.m9player

import android.Manifest
import android.app.ActivityManager
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
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Html
import android.util.Base64
import android.app.Activity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import moe.tekuza.m9player.ui.theme.TsetTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.abs

private const val BOOK_READER_PERMISSION_REQUEST_CODE = 21_001
private const val BOOK_READER_PENDING_INTENT_REQUEST_CODE = 21_002
private const val BOOK_READER_SLEEP_OPTIONS_PREFS = "book_reader_sleep_options_prefs"
private const val BOOK_READER_SLEEP_EXIT_CONTROL_KEY = "sleep_exit_control"
private const val BOOK_READER_SLEEP_DISCONNECT_BT_KEY = "sleep_disconnect_bt"

class BookReaderActivity : AppCompatActivity() {
    private var gamepadKeyHandler: ((KeyEvent) -> Boolean)? = null
    private var lastMotionHorizontalKeyCode: Int? = null
    private var lastMotionVerticalKeyCode: Int? = null
    private var lastControllerBluetoothAddress: String? = null
    private var floatingOverlayStartJob: Job? = null
    private var currentAudioUriForBridge: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestPostNotificationsPermission()
        enableEdgeToEdge()

        val audioUri = intent.getStringExtra(EXTRA_AUDIO_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val srtUri = intent.getStringExtra(EXTRA_SRT_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val coverUri = intent.getStringExtra(EXTRA_COVER_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val title = intent.getStringExtra(EXTRA_BOOK_TITLE).orEmpty()
        currentAudioUriForBridge = audioUri?.toString()
        activeReaderRef = WeakReference(this)
        BookReaderFloatingBridge.setCurrentAudioUri(currentAudioUriForBridge)

        setContent {
            TsetTheme {
                BookReaderScreen(
                    title = title.ifBlank { "Book" },
                    audioUri = audioUri,
                    srtUri = srtUri,
                    coverUri = coverUri,
                    contentResolver = contentResolver,
                    registerGamepadKeyHandler = { handler -> gamepadKeyHandler = handler },
                    latestControllerAddressProvider = {
                        lastControllerBluetoothAddress
                            ?: loadTargetControllerInfo(this)?.address
                            ?: detectConnectedControllerInfo(this)?.address
                    },
                    onBack = { currentPositionMs, currentDurationMs ->
                        val playbackKey = buildBookReaderPlaybackKey(title, audioUri, srtUri)
                        val normalized = normalizeBookReaderPlaybackPosition(
                            currentPositionMs,
                            currentDurationMs
                        )
                        saveBookReaderPlaybackPosition(
                            context = this,
                            bookKey = playbackKey,
                            positionMs = normalized,
                            durationMs = currentDurationMs.coerceAtLeast(0L)
                        )
                        val intent = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra(EXTRA_RETURN_AUDIO_URI, audioUri?.toString())
                            putExtra(EXTRA_RETURN_POSITION_MS, normalized)
                            putExtra(EXTRA_RETURN_DURATION_MS, currentDurationMs.coerceAtLeast(0L))
                        }
                        startActivity(intent)
                    }
                )
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
                !isAppProcessInForeground(this@BookReaderActivity) &&
                loadAudiobookSettingsConfig(this@BookReaderActivity).floatingOverlayEnabled &&
                BookReaderFloatingBridge.isPlaying()
            ) {
                startAudiobookFloatingOverlayService(this@BookReaderActivity)
            }
        }
    }

    override fun onDestroy() {
        floatingOverlayStartJob?.cancel()
        floatingOverlayStartJob = null
        if (activeReaderRef?.get() === this) {
            activeReaderRef = null
        }
        if (BookReaderFloatingBridge.currentAudioUri() == currentAudioUriForBridge) {
            BookReaderFloatingBridge.setCurrentAudioUri(null)
        }
        super.onDestroy()
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
        const val EXTRA_COVER_URI = "extra_cover_uri"
        const val EXTRA_RETURN_AUDIO_URI = "extra_return_audio_uri"
        const val EXTRA_RETURN_POSITION_MS = "extra_return_position_ms"
        const val EXTRA_RETURN_DURATION_MS = "extra_return_duration_ms"
        @Volatile
        private var activeReaderRef: WeakReference<BookReaderActivity>? = null

        fun stopActiveReaderIfDifferentAudio(targetAudioUri: String?) {
            val active = activeReaderRef?.get() ?: return
            val activeAudio = BookReaderFloatingBridge.currentAudioUri()
            if (!targetAudioUri.isNullOrBlank() && activeAudio == targetAudioUri) return
            active.runOnUiThread {
                if (BookReaderFloatingBridge.isPlaying()) {
                    BookReaderFloatingBridge.togglePlayPause()
                }
                active.finish()
            }
        }
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
    coverUri: Uri?,
    contentResolver: ContentResolver,
    registerGamepadKeyHandler: (((KeyEvent) -> Boolean)?) -> Unit,
    latestControllerAddressProvider: () -> String?,
    onBack: (Long, Long) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
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
    var lookupPopupSelectionText by remember { mutableStateOf<String?>(null) }
    var ankiActionStatus by remember { mutableStateOf<String?>(null) }
    var lookupPopupAutoPlayNonce by remember { mutableStateOf(0L) }
    var lookupPopupAutoPlayedKey by remember { mutableStateOf<String?>(null) }
    var resumePlaybackAfterLookupDismiss by remember { mutableStateOf(false) }
    var audiobookSettings by remember { mutableStateOf(loadAudiobookSettingsConfig(context)) }

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
    var coverModeEnabled by remember(srtUri) { mutableStateOf(srtUri == null) }
    var showOverallProgress by remember { mutableStateOf(false) }
    var showOverallDuration by remember { mutableStateOf(false) }
    var timeEditDialogVisible by remember { mutableStateOf(false) }
    var timeEditInput by remember { mutableStateOf("") }
    var timeEditError by remember { mutableStateOf<String?>(null) }
    var lastOverlayTapAtMs by remember { mutableStateOf(0L) }
    var lastGamepadCollectTapAtMs by remember { mutableStateOf(0L) }
    var lastGamepadCollectCueIndex by remember { mutableStateOf<Int?>(null) }
    var pendingSingleTapBaseCueIndex by remember { mutableStateOf<Int?>(null) }
    var pendingSingleTapJob by remember { mutableStateOf<Job?>(null) }

    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var dragPreviewPositionMs by remember { mutableStateOf<Long?>(null) }

    val playbackPositionKey = remember(title, audioUri, srtUri) {
        buildBookReaderPlaybackKey(title, audioUri, srtUri)
    }
    var playbackRestoreCompleted by remember(playbackPositionKey) { mutableStateOf(false) }
    var playbackCompleted by remember(playbackPositionKey) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val player = remember(context) {
        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()
    }
    val notificationController = remember(context, player, title, audioUri, srtUri, coverUri) {
        PlaybackNotificationController(
            context = context,
            player = player,
            title = title,
            contentIntent = buildBookReaderNotificationPendingIntent(
                context = context,
                title = title,
                audioUri = audioUri,
                srtUri = srtUri,
                coverUri = coverUri
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
                if (playbackState == Player.STATE_ENDED) {
                    playbackCompleted = true
                    scope.launch(Dispatchers.IO) {
                        saveBookReaderPlaybackPosition(
                            context = context,
                            bookKey = playbackPositionKey,
                            positionMs = 0L,
                            durationMs = if (player.duration > 0L) player.duration else 0L,
                            allowZeroPositionWrite = true
                        )
                        cleanupBookReaderSrtCache(context)
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                positionMs = newPosition.positionMs.coerceAtLeast(0L)
                val total = if (player.duration > 0L) player.duration else 0L
                if (total <= 0L || newPosition.positionMs < total - 1_500L) {
                    playbackCompleted = false
                }
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
        playbackRestoreCompleted = false
        playbackCompleted = false
        val selectedAudio = audioUri ?: run {
            playbackRestoreCompleted = true
            return@LaunchedEffect
        }
        val savedPositionMs = withContext(Dispatchers.IO) {
            loadBookReaderPlaybackSnapshotOrNull(context, playbackPositionKey)?.positionMs ?: 0L
        }
        val restoredPositionMs = savedPositionMs.coerceAtLeast(0L)
        player.setMediaItem(MediaItem.fromUri(selectedAudio))
        player.prepare()
        player.seekTo(restoredPositionMs)
        positionMs = restoredPositionMs
        playbackRestoreCompleted = true
    }

    LaunchedEffect(audioUri) {
        val selectedAudio = audioUri ?: run {
            audioChapters = emptyList()
            return@LaunchedEffect
        }
        // Delay chapter parsing slightly to avoid competing with first-play startup IO.
        delay(500L)
        val loadedChapters = withContext(Dispatchers.IO) {
            loadM4bChapters(
                context = context,
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
            runCatching { parseBookSrtWithCache(context, contentResolver, uri) }
        }
        srtLoading = false
        result.onSuccess { cues = it }
            .onFailure {
                cues = emptyList()
                srtError = it.message ?: "Failed to parse SRT"
            }
    }

    LaunchedEffect(Unit) {
        val restored = withContext(Dispatchers.IO) {
            val persisted = loadPersistedImports(context)
            val refs = persisted.dictionaries.distinctBy { it.uri }
            refs.mapNotNull { ref ->
                val restoredUri = runCatching { Uri.parse(ref.uri) }.getOrNull()
                val displayName = ref.name.ifBlank {
                    restoredUri?.let { queryBookDisplayName(contentResolver, it) }.orEmpty()
                }
                val cacheKey = ref.cacheKey ?: buildDictionaryCacheKey(ref.uri, displayName)
                loadDictionaryFromSqlite(context, cacheKey)
            }
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val lyricsFollowTopPaddingPx = with(LocalDensity.current) { 72.dp.toPx() }
    val lookupPopupOffsetY = with(LocalDensity.current) {
        if (audiobookSettings.activeCueDisplayAtTop) {
            -(120.dp.roundToPx())
        } else {
            0
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                audiobookSettings = loadAudiobookSettingsConfig(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
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
    val backToMain = remember(onBack, player, durationMs) {
        {
            val current = player.currentPosition.coerceAtLeast(0L)
            val total = if (player.duration > 0L) player.duration else durationMs.coerceAtLeast(0L)
            onBack(current, total)
        }
    }
    val activeSubtitleStyle = MaterialTheme.typography.headlineMedium.copy(
        fontSize = 34.sp,
        lineHeight = 42.sp,
        color = MaterialTheme.colorScheme.onSurface
    )

    LaunchedEffect(activeCue?.text) {
        selectedLookupRange = null
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

    LaunchedEffect(isPlaying) {
        BookReaderFloatingBridge.notifyPlaybackState(isPlaying)
    }

    val activeChapterIndex = remember(previewPositionMs, audioChapters) {
        findBookChapterIndexAtTime(audioChapters, previewPositionMs)
    }
    val useChapterTimeline = activeChapterIndex in audioChapters.indices
    val activeChapterStartMs = if (useChapterTimeline) {
        audioChapters[activeChapterIndex].startMs.coerceAtLeast(0L)
    } else {
        0L
    }
    val activeChapterEndMs = if (useChapterTimeline) {
        val nextChapterStart = audioChapters
            .getOrNull(activeChapterIndex + 1)
            ?.startMs
            ?.coerceAtLeast(activeChapterStartMs)
        when {
            nextChapterStart != null -> nextChapterStart
            durationMs > activeChapterStartMs -> durationMs
            else -> activeChapterStartMs + 1L
        }
    } else {
        durationMs
    }.coerceAtLeast(activeChapterStartMs + 1L)
    val timelineRangeMs = if (useChapterTimeline) {
        (activeChapterEndMs - activeChapterStartMs).coerceAtLeast(1L)
    } else {
        durationMs.coerceAtLeast(1L)
    }
    val sliderMax = timelineRangeMs.toFloat()
    val sliderValue = if (useChapterTimeline) {
        val preview = dragPreviewPositionMs ?: previewPositionMs
        (preview - activeChapterStartMs)
            .coerceIn(0L, timelineRangeMs)
            .toFloat()
    } else {
        when {
            durationMs <= 0L -> 0f
            dragPreviewPositionMs != null -> dragPreviewPositionMs!!.coerceIn(0L, durationMs).toFloat()
            else -> positionMs.coerceIn(0L, durationMs).toFloat()
        }
    }
    val displayedPreviewTimeMs = if (useChapterTimeline) {
        (previewPositionMs - activeChapterStartMs).coerceIn(0L, timelineRangeMs)
    } else {
        previewPositionMs.coerceAtLeast(0L)
    }
    val displayedLeftTimeMs = if (useChapterTimeline && showOverallDuration) {
        previewPositionMs.coerceAtLeast(0L)
    } else {
        displayedPreviewTimeMs
    }
    val displayedDurationTimeMs = if (useChapterTimeline) timelineRangeMs else durationMs.coerceAtLeast(0L)
    val displayedRightDurationTimeMs = if (useChapterTimeline && showOverallDuration) {
        durationMs.coerceAtLeast(0L)
    } else {
        displayedDurationTimeMs
    }
    val progressPercent = remember(displayedPreviewTimeMs, displayedDurationTimeMs) {
        if (displayedDurationTimeMs <= 0L) {
            0
        } else {
            ((displayedPreviewTimeMs.coerceIn(0L, displayedDurationTimeMs) * 100L) / displayedDurationTimeMs)
                .toInt()
                .coerceIn(0, 100)
        }
    }
    val totalProgressPercent = remember(previewPositionMs, durationMs) {
        val total = durationMs.coerceAtLeast(0L)
        if (total <= 0L) {
            0
        } else {
            ((previewPositionMs.coerceIn(0L, total) * 100L) / total)
                .toInt()
                .coerceIn(0, 100)
        }
    }
    LaunchedEffect(useChapterTimeline) {
        if (!useChapterTimeline) {
            showOverallProgress = false
            showOverallDuration = false
        }
    }
    val sleepRemainingLabel = remember(sleepTimerDeadlineMs, positionMs) {
        val deadline = sleepTimerDeadlineMs ?: return@remember null
        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
        formatBookTime(remaining)
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
    LaunchedEffect(favoriteCueCollected) {
        BookReaderFloatingBridge.notifyFavoriteState(favoriteCueCollected)
    }

    LaunchedEffect(audioChapters.size) {
        if (audioChapters.isEmpty()) {
            chapterOptionsVisible = false
        }
    }

    LaunchedEffect(playbackPositionKey, player, isPlaying, playbackRestoreCompleted) {
        if (playbackPositionKey.isBlank()) return@LaunchedEffect
        if (!playbackRestoreCompleted) return@LaunchedEffect
        var lastSavedPosition = Long.MIN_VALUE
        suspend fun saveSnapshotIfChanged() {
            val current = player.currentPosition.coerceAtLeast(0L)
            val total = if (player.duration > 0L) player.duration else 0L
            val normalized = if (playbackCompleted) 0L else normalizeBookReaderPlaybackPosition(current, total)
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

    DisposableEffect(playbackPositionKey, player, playbackRestoreCompleted) {
        onDispose {
            if (!playbackRestoreCompleted) return@onDispose
            val current = player.currentPosition.coerceAtLeast(0L)
            val total = if (player.duration > 0L) player.duration else 0L
            val normalized = if (playbackCompleted) 0L else normalizeBookReaderPlaybackPosition(current, total)
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
                            statusParts += context.getString(R.string.status_controller_disconnected)
                        }
                        SleepBluetoothOutcome.BLUETOOTH_DISABLED -> {
                            statusParts += context.getString(R.string.status_bluetooth_disabled_fallback)
                        }
                        SleepBluetoothOutcome.FAILED -> {
                            statusParts += context.getString(R.string.status_bluetooth_failed, bluetoothResult.detail)
                        }
                    }
                }
                if (sleepExitControlModeWhenDone) {
                    controlModeEnabled = false
                    view.keepScreenOn = false
                    statusParts += context.getString(R.string.status_control_mode_exited)
                }
                if (statusParts.isEmpty()) {
                    controlModeStatus = context.getString(R.string.status_timer_finished)
                } else {
                    controlModeStatus = context.getString(R.string.status_timer_finished_with_parts, statusParts.joinToString("，"))
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
                    val chapterMeta = buildCollectedCueChapterMeta(audioChapters, cue.startMs, cue.endMs)
                    appendBookReaderCollectedCue(
                        context,
                        BookReaderCollectedCue(
                            id = "${System.currentTimeMillis()}-${cue.startMs}-${cue.endMs}-${cue.text.hashCode()}",
                            bookTitle = title,
                            text = cue.text,
                            startMs = cue.startMs,
                            endMs = cue.endMs,
                            savedAtMs = System.currentTimeMillis(),
                            chapterIndex = chapterMeta?.chapterIndex,
                            chapterTitle = chapterMeta?.chapterTitle,
                            chapterStartMs = chapterMeta?.chapterStartMs,
                            chapterStartOffsetMs = chapterMeta?.startOffsetMs,
                            chapterEndOffsetMs = chapterMeta?.endOffsetMs
                        )
                    )
                }
                if (added) {
                    controlModeStatus = context.getString(R.string.status_bookmarked_continue, targetIndex + 1, cues.size)
                }
            } else {
                controlModeStatus = context.getString(R.string.status_already_bookmarked_continue, targetIndex + 1, cues.size)
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
                    controlModeStatus = cueIndexLabel?.let { context.getString(R.string.status_unbookmarked_cue, it) }
                        ?: context.getString(R.string.status_unbookmarked)
                } else {
                    controlModeStatus = context.getString(R.string.status_bookmark_not_found)
                }
            } else {
                val added = withContext(Dispatchers.IO) {
                    val chapterMeta = buildCollectedCueChapterMeta(audioChapters, cue.startMs, cue.endMs)
                    appendBookReaderCollectedCue(
                        context,
                        BookReaderCollectedCue(
                            id = "${System.currentTimeMillis()}-${cue.startMs}-${cue.endMs}-${cue.text.hashCode()}",
                            bookTitle = title,
                            text = cue.text,
                            startMs = cue.startMs,
                            endMs = cue.endMs,
                            savedAtMs = System.currentTimeMillis(),
                            chapterIndex = chapterMeta?.chapterIndex,
                            chapterTitle = chapterMeta?.chapterTitle,
                            chapterStartMs = chapterMeta?.chapterStartMs,
                            chapterStartOffsetMs = chapterMeta?.startOffsetMs,
                            chapterEndOffsetMs = chapterMeta?.endOffsetMs
                        )
                    )
                }
                if (added) {
                    collectedCueKeys.add(key)
                    collectedCueUiVersion += 1
                    controlModeStatus = cueIndexLabel?.let { context.getString(R.string.status_bookmarked_cue, it) }
                        ?: context.getString(R.string.status_bookmarked)
                } else {
                    controlModeStatus = context.getString(R.string.status_already_bookmarked)
                    collectedCueKeys.add(key)
                    collectedCueUiVersion += 1
                }
            }
        }
    }

    LaunchedEffect(activeCueIndex, lyricsMode, cues.size, dragPreviewPositionMs, audiobookSettings.activeCueDisplayAtTop) {
        if (!lyricsMode || activeCueIndex < 0 || cues.isEmpty()) return@LaunchedEffect
        if (dragPreviewPositionMs != null) return@LaunchedEffect
        if (lyricsListState.isScrollInProgress) return@LaunchedEffect
        if (lyricsListState.layoutInfo.visibleItemsInfo.none { it.index == activeCueIndex }) {
            lyricsListState.scrollToItem(activeCueIndex)
        }
        val activeItem = lyricsListState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeCueIndex }
            ?: return@LaunchedEffect
        val layoutInfo = lyricsListState.layoutInfo
        val itemCenter = activeItem.offset + (activeItem.size / 2f)
        val targetCenter = if (audiobookSettings.activeCueDisplayAtTop) {
            layoutInfo.viewportStartOffset + lyricsFollowTopPaddingPx + (activeItem.size / 2f)
        } else {
            (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
        }
        val delta = itemCenter - targetCenter
        if (abs(delta) > 1f) {
            lyricsListState.scrollBy(delta)
        }
    }

    fun jumpToCue(index: Int, showStatus: Boolean = true) {
        val cue = cues.getOrNull(index) ?: return
        selectedLookupRange = null
        lookupPopupVisible = false
        resumePlaybackAfterLookupDismiss = false
        player.seekTo(cue.startMs)
        player.play()
        controlTargetCueIndex = if (controlModeEnabled) index else null
        if (showStatus) {
            controlModeStatus = context.getString(R.string.status_jump_to_cue, index + 1, cues.size)
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
        resumePlaybackAfterLookupDismiss = false
        player.seekTo(target)
        if (controlModeEnabled) {
            controlModeStatus = context.getString(R.string.status_manual_seek)
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
        controlModeStatus = if (minutes <= 0) {
            context.getString(R.string.status_timer_cleared)
        } else {
            context.getString(R.string.status_timer_set, minutes)
        }
    }

    fun applyCustomSleepTimer() {
        val minutes = sleepCustomMinutesInput.trim().toIntOrNull()
        if (minutes == null || minutes <= 0) {
            controlModeStatus = context.getString(R.string.status_custom_minutes_invalid)
            return
        }
        setSleepTimer(minutes)
    }

    fun playCueForControl(index: Int) {
        val cue = cues.getOrNull(index) ?: return
        player.seekTo(cue.startMs)
        player.play()
        controlTargetCueIndex = index
        controlModeStatus = context.getString(R.string.status_play_cue, index + 1, cues.size)
    }

    fun jumpToChapter(chapter: ReaderAudioChapter) {
        seekToManual(chapter.startMs)
        player.play()
        chapterOptionsVisible = false
        controlModeStatus = context.getString(R.string.status_jump_chapter, chapter.title)
    }

    fun collectFavoriteCue() {
        val cue = favoriteCue ?: return
        val key = cueCollectionKey(cue.startMs, cue.endMs, cue.text)
        val cueIndexLabel = when {
            playbackCueIndex in cues.indices -> "${playbackCueIndex + 1}/${cues.size}"
            activeCueIndex in cues.indices -> "${activeCueIndex + 1}/${cues.size}"
            else -> null
        }
        if (collectedCueKeys.contains(key)) {
            controlModeStatus = cueIndexLabel?.let { context.getString(R.string.status_already_bookmarked_cue, it) }
                ?: context.getString(R.string.status_already_bookmarked)
            return
        }
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                val chapterMeta = buildCollectedCueChapterMeta(audioChapters, cue.startMs, cue.endMs)
                appendBookReaderCollectedCue(
                    context,
                    BookReaderCollectedCue(
                        id = "${System.currentTimeMillis()}-${cue.startMs}-${cue.endMs}-${cue.text.hashCode()}",
                        bookTitle = title,
                        text = cue.text,
                        startMs = cue.startMs,
                        endMs = cue.endMs,
                        savedAtMs = System.currentTimeMillis(),
                        chapterIndex = chapterMeta?.chapterIndex,
                        chapterTitle = chapterMeta?.chapterTitle,
                        chapterStartMs = chapterMeta?.chapterStartMs,
                        chapterStartOffsetMs = chapterMeta?.startOffsetMs,
                        chapterEndOffsetMs = chapterMeta?.endOffsetMs
                    )
                )
            }
            collectedCueKeys.add(key)
            collectedCueUiVersion += 1
            controlModeStatus = if (added) {
                cueIndexLabel?.let { context.getString(R.string.status_bookmarked_cue, it) }
                    ?: context.getString(R.string.status_bookmarked)
            } else {
                cueIndexLabel?.let { context.getString(R.string.status_already_bookmarked_cue, it) }
                    ?: context.getString(R.string.status_already_bookmarked)
            }
        }
    }

    fun handleControlOverlayTap() {
        val currentIndex = playbackCueIndex.takeIf { it >= 0 } ?: return
        val currentCue = cues.getOrNull(currentIndex) ?: return
        val controlConfig = loadGamepadControlConfig(context)
        val now = System.currentTimeMillis()
        val doubleTapWindowMs = 280L
        val isDoubleTap = pendingSingleTapBaseCueIndex == currentIndex &&
            now - lastOverlayTapAtMs <= doubleTapWindowMs

        if (isDoubleTap && isPlaying && positionMs < currentCue.endMs) {
            pendingSingleTapJob?.cancel()
            pendingSingleTapJob = null
            pendingSingleTapBaseCueIndex = null
            playCueForControl((currentIndex - 1).coerceAtLeast(0))
            controlModeStatus = context.getString(R.string.status_double_tap_replay_prev)
            return
        }

        pendingSingleTapJob?.cancel()
        pendingSingleTapBaseCueIndex = currentIndex
        lastOverlayTapAtMs = now
        pendingSingleTapJob = scope.launch {
            delay(doubleTapWindowMs)
            if (pendingSingleTapBaseCueIndex == currentIndex) {
                pendingSingleTapBaseCueIndex = null
                if (controlConfig.singleTapCollectOnlyInControlMode) {
                    collectFavoriteCue()
                    controlModeStatus = context.getString(R.string.status_single_tap_bookmark_direct)
                } else {
                    playCueForControl(currentIndex)
                    controlModeStatus = context.getString(R.string.status_single_tap_replay_bookmark)
                }
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
            context.getString(R.string.status_gamepad_bookmark_prev)
        } else {
            context.getString(R.string.status_gamepad_bookmark_current)
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
            lookupPopupAutoPlayNonce += 1L
            lookupPopupAutoPlayedKey = null
            lookupPopupVisible = true
            lookupPopupLoading = false
            lookupPopupResults = emptyList()
            lookupPopupTitle = ""
            lookupPopupError = context.getString(R.string.bookreader_lookup_no_dict)
            lookupPopupCue = cue
            lookupPopupSelectionText = null
            return
        }

        val selection = findLookupSelection(cue.text, offset)
        val selectionRange = selection?.range
        selectedLookupRange = null
        val selectedToken = selection?.text?.trim()?.takeIf { it.isNotBlank() }
        val candidates = listOfNotNull(selectedToken)

        if (candidates.isEmpty()) {
            lookupPopupAutoPlayNonce += 1L
            lookupPopupAutoPlayedKey = null
            lookupPopupVisible = true
            lookupPopupLoading = false
            lookupPopupResults = emptyList()
            lookupPopupTitle = selectedToken.orEmpty()
            lookupPopupError = context.getString(R.string.bookreader_lookup_no_term)
            lookupPopupCue = cue
            lookupPopupSelectionText = null
            return
        }

        lookupPopupVisible = true
        lookupPopupAutoPlayNonce += 1L
        lookupPopupAutoPlayedKey = null
        lookupPopupLoading = true
        lookupPopupResults = emptyList()
        lookupPopupError = null
        lookupPopupTitle = candidates.firstOrNull() ?: selectedToken.orEmpty()
        lookupPopupCue = cue
        lookupPopupSelectionText = selectedToken
        ankiActionStatus = null

        if (audiobookSettings.pausePlaybackOnLookup && player.isPlaying) {
            player.pause()
            resumePlaybackAfterLookupDismiss = true
        } else {
            resumePlaybackAfterLookupDismiss = false
        }

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
                    lookupPopupError = context.getString(R.string.common_no_results)
                    selectedLookupRange = null
                    lookupPopupSelectionText = selectedToken
                } else {
                    selectedLookupRange = trimSelectionRangeByMatchedLength(
                        selectionRange,
                        hits.first().matchedLength
                    )
                    lookupPopupSelectionText = selectedLookupRange?.let { range ->
                        val start = range.first.coerceIn(0, cue.text.length)
                        val endExclusive = (range.last + 1).coerceIn(start, cue.text.length)
                        if (endExclusive > start) cue.text.substring(start, endExclusive) else ""
                    }?.trim()?.takeIf { it.isNotBlank() } ?: selectedToken
                }
            }.onFailure {
                lookupPopupResults = emptyList()
                lookupPopupError = it.message ?: context.getString(R.string.bookreader_lookup_failed)
                selectedLookupRange = null
                lookupPopupSelectionText = selectedToken
            }
            lookupPopupLoading = false
        }
    }

    fun closeLookupPopup(resumePlayback: Boolean = true) {
        lookupPopupVisible = false
        lookupPopupSelectionText = null
        if (resumePlayback && resumePlaybackAfterLookupDismiss) {
            player.play()
        }
        resumePlaybackAfterLookupDismiss = false
    }

    fun addLookupGroupToAnki(groupedResult: GroupedLookupResult) {
        val dictionaryGroup = groupedResult.dictionaries.firstOrNull() ?: run {
            ankiActionStatus = context.getString(R.string.bookreader_anki_no_content)
            return
        }
        val cue = lookupPopupCue ?: return
        val popupSelectionText = lookupPopupSelectionText?.trim()?.takeIf { it.isNotBlank() }
        val glossarySections = buildGroupedGlossarySections(groupedResult)
        val primaryDefinition = dictionaryGroup.definitions
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
        val settingsSnapshot = audiobookSettings
        scope.launch {
        ankiActionStatus = context.getString(R.string.bookreader_anki_exporting)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val preparedLookupAudio = prepareLookupAudioForAnkiExport(
                        context = context,
                        term = groupedResult.term,
                        reading = groupedResult.reading,
                        settings = settingsSnapshot
                    )
                    try {
                        addLookupDefinitionToAnki(
                            context = context,
                            cue = cue,
                            audioUri = audioUri,
                            lookupAudioUri = preparedLookupAudio?.uri,
                            bookTitle = title,
                            entry = dictionaryGroup.entry,
                            definition = primaryDefinition.ifBlank { groupedResult.term },
                            dictionaryCss = null,
                            glossarySections = glossarySections,
                            popupSelectionText = popupSelectionText
                        )
                    } finally {
                        preparedLookupAudio?.cleanup?.invoke()
                    }
                }
            }
            ankiActionStatus = result.fold(
            onSuccess = { context.getString(R.string.bookreader_anki_exported) },
                onFailure = { formatAnkiFailure(it) }
            )
        }
    }

    fun playLookupGroupAudio(groupedResult: GroupedLookupResult) {
        playLookupAudioForTerm(
            context = context,
            term = groupedResult.term,
            reading = groupedResult.reading,
            settings = audiobookSettings
        ) { error ->
            ankiActionStatus = error
        }
    }

    LaunchedEffect(
        lookupPopupVisible,
        lookupPopupLoading,
        lookupPopupError,
        groupedLookupPopupResults,
        audiobookSettings.lookupPlaybackAudioEnabled,
        audiobookSettings.lookupPlaybackAudioAutoPlay,
        lookupPopupAutoPlayNonce
    ) {
        if (!lookupPopupVisible || lookupPopupLoading || lookupPopupError != null) return@LaunchedEffect
        if (!audiobookSettings.lookupPlaybackAudioEnabled || !audiobookSettings.lookupPlaybackAudioAutoPlay) {
            return@LaunchedEffect
        }
        val target = groupedLookupPopupResults.firstOrNull() ?: return@LaunchedEffect
        val key = "${lookupPopupAutoPlayNonce}|${target.term}|${target.reading.orEmpty()}"
        if (lookupPopupAutoPlayedKey == key) return@LaunchedEffect
        lookupPopupAutoPlayedKey = key
        playLookupGroupAudio(target)
    }

    fun handleControlOverlaySwipe(step: Int) {
        if (step < 0) {
            jumpToAdjacentCue(-1)
            controlModeStatus = context.getString(R.string.status_swipe_prev)
        } else {
            jumpToAdjacentCue(1)
            controlModeStatus = context.getString(R.string.status_swipe_next)
        }
    }

    fun exitControlModeByTwoFingerLongPress() {
        pendingSingleTapJob?.cancel()
        pendingSingleTapJob = null
        pendingSingleTapBaseCueIndex = null
        controlTargetCueIndex = null
        controlModeEnabled = false
        controlModeStatus = context.getString(R.string.status_control_mode_exited_full)
    }

    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val latestTogglePlayPause by rememberUpdatedState<() -> Unit>({
        if (player.isPlaying) player.pause() else player.play()
    })
    val latestSeekPrevious by rememberUpdatedState<() -> Unit>({
        jumpToAdjacentCue(-1)
    })
    val latestSeekNext by rememberUpdatedState<() -> Unit>({
        jumpToAdjacentCue(1)
    })
    val latestToggleFavorite by rememberUpdatedState<() -> Unit>({
        toggleFavoriteCue()
    })
    val latestHandleGamepadKeyEvent by rememberUpdatedState<(KeyEvent) -> Boolean>({ event ->
        handleGamepadKeyEvent(event)
    })
    val controlModeConfig = loadGamepadControlConfig(context)
    val controlModePowerSaveEnabled = controlModeEnabled && controlModeConfig.powerSaveBlackScreenInControlMode
    val controlModeHintText = remember(controlModeEnabled, controlModeConfig) {
        buildString {
            append(context.getString(R.string.status_control_hint_intro))
            append(
                if (controlModeConfig.singleTapCollectOnlyInControlMode) {
                    context.getString(R.string.status_control_hint_direct_bookmark)
                } else {
                    context.getString(R.string.status_control_hint_replay_bookmark)
                }
            )
            append(context.getString(R.string.status_control_hint_footer))
        }
    }

    DisposableEffect(Unit) {
        val controller = object : BookReaderFloatingBridge.Controller {
            override fun isPlaying(): Boolean = latestIsPlaying
            override fun isFavorite(): Boolean = favoriteCueCollected

            override fun togglePlayPause() {
                latestTogglePlayPause()
            }

            override fun seekPrevious() {
                latestSeekPrevious()
            }

            override fun seekNext() {
                latestSeekNext()
            }

            override fun toggleFavorite() {
                latestToggleFavorite()
            }
        }
        BookReaderFloatingBridge.attach(controller)
        onDispose {
            BookReaderFloatingBridge.detach(controller)
        }
    }

    DisposableEffect(Unit) {
        registerGamepadKeyHandler { event -> latestHandleGamepadKeyEvent(event) }
        onDispose { registerGamepadKeyHandler(null) }
    }

    BackHandler {
        when {
            lookupPopupVisible -> closeLookupPopup()
            sleepTimerOptionsVisible -> sleepTimerOptionsVisible = false
            topActionsExpanded -> topActionsExpanded = false
            speedMenuExpanded -> speedMenuExpanded = false
            chapterOptionsVisible -> chapterOptionsVisible = false
            else -> backToMain()
        }
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
                                    Text(
                                        if (chapterOptionsVisible) {
                                            stringResource(R.string.bookreader_chapters_expanded)
                                        } else {
                                            stringResource(R.string.bookreader_chapters_collapsed)
                                        }
                                    )
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
                                TextButton(
                                    onClick = {
                                        timeEditInput = formatBookTime(displayedLeftTimeMs)
                                        timeEditError = null
                                        timeEditDialogVisible = true
                                    }
                                ) {
                                    Text(formatBookTime(displayedLeftTimeMs))
                                }
                                Slider(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp),
                                    value = sliderValue,
                                    valueRange = 0f..sliderMax,
                                    enabled = displayedDurationTimeMs > 0L,
                                    onValueChange = { raw ->
                                        if (displayedDurationTimeMs > 0L) {
                                            val clamped = raw.toLong().coerceIn(0L, timelineRangeMs)
                                            dragPreviewPositionMs = if (useChapterTimeline) {
                                                activeChapterStartMs + clamped
                                            } else {
                                                clamped.coerceIn(0L, durationMs.coerceAtLeast(0L))
                                            }
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
                                Text(
                                    text = formatBookTime(displayedRightDurationTimeMs),
                                    modifier = if (useChapterTimeline) {
                                        Modifier.clickable { showOverallDuration = !showOverallDuration }
                                    } else {
                                        Modifier
                                    }
                                )
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
                                val progressLabel = when {
                                    useChapterTimeline && showOverallProgress -> stringResource(R.string.bookreader_progress_total, totalProgressPercent)
                                    useChapterTimeline -> stringResource(R.string.bookreader_progress_chapter, progressPercent)
                                    else -> stringResource(R.string.bookreader_progress_plain, progressPercent)
                                }
                                Text(
                                    text = progressLabel,
                                    modifier = if (useChapterTimeline) {
                                        Modifier.clickable { showOverallProgress = !showOverallProgress }
                                    } else {
                                        Modifier
                                    }
                                )
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
                                        AdjacentJumpMode.CUE -> stringResource(R.string.bookreader_jump_by_cue)
                                        AdjacentJumpMode.DURATION -> stringResource(R.string.bookreader_jump_by_duration, stepSeconds.toInt())
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
                    TextButton(onClick = backToMain) {
                        Text(stringResource(R.string.bookreader_back))
                    }
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
                                stringResource(R.string.bookreader_sleep_timer_running, sleepRemainingLabel)
                            } else {
                                stringResource(R.string.bookreader_sleep_timer)
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
                                        controlModeStatus = context.getString(R.string.status_playback_speed, speed.toString())
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
                                text = {
                                    Text(
                                        if (bottomControlsVisible) {
                                            stringResource(R.string.bookreader_hide_controls)
                                        } else {
                                            stringResource(R.string.bookreader_show_controls)
                                        }
                                    )
                                },
                                onClick = {
                                    bottomControlsVisible = !bottomControlsVisible
                                    topActionsExpanded = false
                                    controlModeStatus = if (bottomControlsVisible) {
                                        context.getString(R.string.bookreader_controls_shown)
                                    } else {
                                        context.getString(R.string.bookreader_controls_hidden)
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bookreader_control_mode)) },
                                onClick = {
                                    controlModeEnabled = true
                                    topActionsExpanded = false
                                    controlModeStatus = context.getString(R.string.bookreader_control_mode_entered)
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (lyricsMode) {
                                            stringResource(R.string.bookreader_subtitle_list)
                                        } else {
                                            stringResource(R.string.bookreader_subtitle_single)
                                        }
                                    )
                                },
                                onClick = {
                                    lyricsMode = !lyricsMode
                                    topActionsExpanded = false
                                    controlModeStatus = if (lyricsMode) {
                                        context.getString(R.string.bookreader_subtitle_list_enabled)
                                    } else {
                                        context.getString(R.string.bookreader_subtitle_single_enabled)
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (coverModeEnabled) {
                                            stringResource(R.string.bookreader_switch_to_subtitle)
                                        } else {
                                            stringResource(R.string.bookreader_switch_to_cover)
                                        }
                                    )
                                },
                                onClick = {
                                    coverModeEnabled = !coverModeEnabled
                                    topActionsExpanded = false
                                    controlModeStatus = if (coverModeEnabled) {
                                        context.getString(R.string.bookreader_switched_to_cover)
                                    } else {
                                        context.getString(R.string.bookreader_switched_to_subtitle)
                                    }
                                }
                            )
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(
                            top = if (coverModeEnabled) 18.dp else 0.dp,
                            bottom = if (coverModeEnabled) 20.dp else 0.dp
                        ),
                    tonalElevation = 1.dp,
                    shape = MaterialTheme.shapes.large
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (coverModeEnabled) 0.dp else 16.dp)
                    ) {
                        when {
                                srtLoading -> Text(stringResource(R.string.bookreader_parsing_srt))
                                srtError != null -> Text(
                                    stringResource(R.string.bookreader_srt_error, srtError.orEmpty()),
                                    color = MaterialTheme.colorScheme.error
                                )
                            coverModeEnabled -> {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    if (coverUri != null) {
                                        Text(
                                            text = title,
                                            color = Color.Black,
                                            style = MaterialTheme.typography.titleLarge,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f)
                                                .padding(top = 12.dp, bottom = 20.dp)
                                        ) {
                                            BookReaderCoverImage(
                                                coverUri = coverUri,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    } else {
                                        Text(text = title, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                                        Box(modifier = Modifier.fillMaxSize()) {
                                            Text(
                                                text = "No cover",
                                                modifier = Modifier.align(Alignment.Center)
                                            )
                                        }
                                    }
                                } 
                            }
                                cues.isEmpty() -> Text(stringResource(R.string.bookreader_no_subtitles))
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
                                activeCue == null -> Text(stringResource(R.string.bookreader_waiting_for_subtitle))
                            else -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(
                                            top = if (audiobookSettings.activeCueDisplayAtTop) 36.dp else 0.dp
                                        ),
                                    contentAlignment = if (audiobookSettings.activeCueDisplayAtTop) {
                                        Alignment.TopCenter
                                    } else {
                                        Alignment.Center
                                    }
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

        if (timeEditDialogVisible) {
            AlertDialog(
                onDismissRequest = {
                    timeEditDialogVisible = false
                    timeEditError = null
                },
                title = {
                    Text(
                        if (useChapterTimeline && !showOverallDuration) {
                            stringResource(R.string.bookreader_edit_chapter_time)
                        } else {
                            stringResource(R.string.bookreader_edit_playback_time)
                        }
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = timeEditInput,
                            onValueChange = { value ->
                                timeEditInput = value.filter { it.isDigit() || it == ':' }.take(12)
                                timeEditError = null
                            },
                            singleLine = true,
                            label = { Text(stringResource(R.string.bookreader_time_format_hint)) }
                        )
                        if (!timeEditError.isNullOrBlank()) {
                            Text(timeEditError!!, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            timeEditDialogVisible = false
                            timeEditError = null
                        }
                    ) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val targetOffsetMs = parseEditableTimeInputToMillis(timeEditInput)
                            if (targetOffsetMs == null) {
                                timeEditError = context.getString(R.string.bookreader_time_invalid)
                                return@Button
                            }
                            val absoluteTarget = if (useChapterTimeline && !showOverallDuration) {
                                activeChapterStartMs + targetOffsetMs.coerceIn(0L, timelineRangeMs)
                            } else {
                                targetOffsetMs.coerceAtLeast(0L)
                            }
                            seekToManual(absoluteTarget)
                            timeEditDialogVisible = false
                            timeEditError = null
                        }
                    ) {
                        Text(stringResource(R.string.bookreader_jump))
                    }
                }
            )
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
                            Text(stringResource(R.string.bookreader_sleep_remaining, sleepRemainingLabel))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(onClick = { setSleepTimer(15) }) { Text("15m") }
                            OutlinedButton(onClick = { setSleepTimer(30) }) { Text("30m") }
                            OutlinedButton(onClick = { setSleepTimer(60) }) { Text("60m") }
                            TextButton(onClick = { setSleepTimer(0) }) { Text(stringResource(R.string.common_close)) }
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
                                label = { Text(stringResource(R.string.bookreader_custom_minutes)) },
                                singleLine = true
                            )
                            Button(onClick = { applyCustomSleepTimer() }) {
                                Text(stringResource(R.string.bookreader_set))
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.bookreader_sleep_exit_control))
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
                            Text(stringResource(R.string.bookreader_sleep_disconnect_bluetooth))
                            Switch(
                                checked = sleepDisconnectControllerBluetoothWhenDone,
                                onCheckedChange = { checked ->
                                    sleepDisconnectControllerBluetoothWhenDone = checked
                                }
                            )
                        }
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                )
                            }
                        ) {
                            Text(stringResource(R.string.bookreader_open_bluetooth))
                        }
                    }
                }
            }
        }

        if (controlModeEnabled && cues.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (controlModePowerSaveEnabled) Color.Black else Color.Gray.copy(alpha = 0.38f))
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
                                        abs(delta.x) > 24f || abs(delta.y) > 24f
                                    }
                                    if (moved) {
                                        cancelled = true
                                        break
                                    }
                                    if (System.currentTimeMillis() - startAt >= 450L) {
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
                    if (controlModePowerSaveEnabled) {
                        stringResource(R.string.control_mode_overlay_hint)
                    } else {
                        controlModeHintText
                    },
                    color = Color.White
                )
            }
        }
    }

    if (lookupPopupVisible) {
        Popup(
            alignment = Alignment.BottomCenter,
            offset = IntOffset(0, lookupPopupOffsetY),
            onDismissRequest = { closeLookupPopup() },
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
                    .padding(horizontal = 8.dp, vertical = 12.dp),
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
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (lookupPopupLoading) {
                            Text(stringResource(R.string.common_querying))
                        }
                        if (lookupPopupError != null) {
                            Text(lookupPopupError!!, color = MaterialTheme.colorScheme.error)
                        }
                        if (!lookupPopupLoading && groupedLookupPopupResults.isEmpty() && lookupPopupError == null) {
                            Text(stringResource(R.string.common_no_results))
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
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (audiobookSettings.lookupPlaybackAudioEnabled) {
                                                OutlinedButton(
                                                    onClick = { playLookupGroupAudio(groupedResult) }
                                                ) {
                                                        Text(stringResource(R.string.common_audio))
                                                }
                                            }
                                            OutlinedButton(
                                                onClick = { addLookupGroupToAnki(groupedResult) }
                                            ) {
                                                Text("+")
                                            }
                                        }
                                    }

                                    groupedResult.dictionaries.forEach { dictionaryGroup ->
                                        Card(modifier = Modifier.fillMaxWidth()) {
                                            Column(
                                                modifier = Modifier.padding(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                val frequencyBadges = parseMetaBadges(
                                                    dictionaryGroup.frequency,
                                                    stringResource(R.string.bookreader_meta_frequency)
                                                )
                                                if (frequencyBadges.isNotEmpty()) {
                                                    MetaBadgeRow(
                                                        badges = frequencyBadges,
                                                        labelColor = Color(0xFFDDF0DD),
                                                        labelTextColor = Color(0xFF305E33)
                                                    )
                                                }
                                                val pitchBadges = parsePitchBadgeGroups(
                                                    raw = dictionaryGroup.pitch,
                                                    reading = groupedResult.reading,
                                                    defaultLabel = stringResource(R.string.bookreader_meta_pitch)
                                                )
                                                if (pitchBadges.isNotEmpty()) {
                                                    pitchBadges.forEach { group ->
                                                        PitchBadgeRow(
                                                            group = group,
                                                            labelColor = Color(0xFFE7DDF8),
                                                            labelTextColor = Color(0xFF4E3A74)
                                                        )
                                                    }
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
                        TextButton(onClick = { closeLookupPopup() }) {
                            Text(stringResource(R.string.common_close))
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

private fun addLookupDefinitionToAnki(
    context: Context,
    cue: ReaderSubtitleCue,
    audioUri: Uri?,
    lookupAudioUri: Uri?,
    bookTitle: String?,
    entry: DictionaryEntry,
    definition: String,
    dictionaryCss: String?,
    glossarySections: List<String> = emptyList(),
    popupSelectionText: String? = null
) {
    val persistedConfig = withAnkiStep("load-config") {
        loadPersistedAnkiConfig(context)
    }
    val preparedExport = withAnkiStep("prepare-export") {
        prepareAnkiExport(
            context = context,
            persistedConfig = persistedConfig,
            audioUri = audioUri,
            lookupAudioUri = lookupAudioUri
        )
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
        lookupAudioUri = lookupAudioUri,
        audioTagOnly = true,
        requireCueAudioClip = true
    )

    withAnkiStep("export-note") {
        exportToAnkiDroidApi(context, card, preparedExport.config)
    }
}

private const val BOOK_READER_SRT_CACHE_DIR = "book_reader_srt_cache"
private const val BOOK_READER_SRT_CACHE_MAX_FILES = 120
private const val BOOK_READER_SRT_CACHE_MAX_AGE_MS = 14L * 24L * 60L * 60L * 1000L

private fun parseBookSrtWithCache(
    context: Context,
    contentResolver: ContentResolver,
    uri: Uri
): List<ReaderSubtitleCue> {
    val cacheDir = File(context.cacheDir, BOOK_READER_SRT_CACHE_DIR)
    if (!cacheDir.exists()) {
        cacheDir.mkdirs()
    }
    val cacheKey = buildDictionaryCacheKey(uri.toString(), "srt")
    val cacheFile = File(cacheDir, "$cacheKey.cache")
    val sourceStamp = buildSrtSourceStamp(contentResolver, uri)

    readBookSrtCache(cacheFile, sourceStamp)?.let { cached ->
        cacheFile.setLastModified(System.currentTimeMillis())
        return cached
    }

            val parsed = parseBookSrt(context, contentResolver, uri)
    writeBookSrtCache(cacheFile, sourceStamp, parsed)
    return parsed
}

private fun buildSrtSourceStamp(contentResolver: ContentResolver, uri: Uri): String {
    val projection = arrayOf(OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
    return runCatching {
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use "size=-1|modified=-1"
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
            val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) else -1L
            "size=$size|modified=$modified"
        } ?: "size=-1|modified=-1"
    }.getOrDefault("size=-1|modified=-1")
}

private fun readBookSrtCache(cacheFile: File, expectedStamp: String): List<ReaderSubtitleCue>? {
    if (!cacheFile.exists() || !cacheFile.isFile) return null
    return runCatching {
        cacheFile.bufferedReader(Charsets.UTF_8).use { reader ->
            val header = reader.readLine() ?: return@use null
            if (!header.startsWith("#stamp\t")) return@use null
            val stamp = header.substringAfter('\t')
            if (stamp != expectedStamp) return@use null

            val cues = mutableListOf<ReaderSubtitleCue>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val parts = line.split('\t', limit = 3)
                if (parts.size < 3) return@use null
                val start = parts[0].toLongOrNull() ?: return@use null
                val end = parts[1].toLongOrNull() ?: return@use null
                val text = runCatching {
                    String(Base64.decode(parts[2], Base64.DEFAULT), Charsets.UTF_8)
                }.getOrNull() ?: return@use null
                cues += ReaderSubtitleCue(startMs = start, endMs = end, text = text)
            }
            cues.sortedBy { it.startMs }
        }
    }.getOrNull()
}

private fun writeBookSrtCache(cacheFile: File, sourceStamp: String, cues: List<ReaderSubtitleCue>) {
    val parent = cacheFile.parentFile
    if (parent != null && !parent.exists()) {
        parent.mkdirs()
    }
    val tempFile = File(parent, "${cacheFile.name}.tmp")
    runCatching {
        tempFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.append("#stamp\t").append(sourceStamp).append('\n')
            cues.forEach { cue ->
                val encoded = Base64.encodeToString(cue.text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                writer.append(cue.startMs.toString())
                    .append('\t')
                    .append(cue.endMs.toString())
                    .append('\t')
                    .append(encoded)
                    .append('\n')
            }
        }
        if (cacheFile.exists()) {
            cacheFile.delete()
        }
        tempFile.renameTo(cacheFile)
        cacheFile.setLastModified(System.currentTimeMillis())
    }.onFailure {
        tempFile.delete()
    }
}

private fun cleanupBookReaderSrtCache(context: Context) {
    val cacheDir = File(context.cacheDir, BOOK_READER_SRT_CACHE_DIR)
    if (!cacheDir.exists() || !cacheDir.isDirectory) return
    val files = cacheDir.listFiles()?.filter { it.isFile }?.toMutableList() ?: return
    if (files.isEmpty()) return

    val now = System.currentTimeMillis()
    files.forEach { file ->
        val ageMs = now - file.lastModified()
        if (ageMs > BOOK_READER_SRT_CACHE_MAX_AGE_MS) {
            file.delete()
        }
    }

    val remaining = cacheDir.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
    if (remaining.size <= BOOK_READER_SRT_CACHE_MAX_FILES) return
    remaining.drop(BOOK_READER_SRT_CACHE_MAX_FILES).forEach { it.delete() }
}

private fun parseBookSrt(context: Context, contentResolver: ContentResolver, uri: Uri): List<ReaderSubtitleCue> {
    val cues = mutableListOf<ReaderSubtitleCue>()
    val blockLines = mutableListOf<String>()

    openReaderInputStream(contentResolver, uri)?.use { input ->
        input.bufferedReader(Charsets.UTF_8).use { reader ->
            var isFirstLine = true
            while (true) {
                val rawLine = reader.readLine() ?: break
                val line = if (isFirstLine) {
                    isFirstLine = false
                    rawLine.removePrefix("\uFEFF")
                } else {
                    rawLine
                }
                if (line.isBlank()) {
                    appendParsedSrtBlock(blockLines, cues)
                    blockLines.clear()
                } else {
                    blockLines += line.trimEnd()
                }
            }
        }
    } ?: error(context.getString(R.string.error_srt_unreadable))

    appendParsedSrtBlock(blockLines, cues)

    if (cues.isEmpty()) error(context.getString(R.string.error_srt_no_valid_cues))
    return cues.sortedBy { it.startMs }
}

private fun appendParsedSrtBlock(
    blockLines: List<String>,
    out: MutableList<ReaderSubtitleCue>
) {
    val lines = blockLines.filter { it.isNotBlank() }
    if (lines.isEmpty()) return

    val timingLineIndex = if (lines.first().all { it.isDigit() } && lines.size >= 2) 1 else 0
    val timingLine = lines.getOrNull(timingLineIndex) ?: return
    if (!timingLine.contains("-->")) return

    val parts = timingLine.split("-->")
    if (parts.size < 2) return

    val start = parseBookSrtTimestamp(parts[0].trim()) ?: return
    val endToken = parts[1].trim().substringBefore(' ')
    val end = parseBookSrtTimestamp(endToken) ?: return

    val cueTextRaw = lines.drop(timingLineIndex + 1).joinToString("\n").trim()
    val cueText = Html.fromHtml(cueTextRaw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    if (cueText.isBlank()) return

    out += ReaderSubtitleCue(startMs = start, endMs = end, text = cueText)
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

private fun isAppProcessInForeground(context: Context): Boolean {
    val processInfo = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(processInfo)
    return processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
        processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
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

private data class CollectedCueChapterMeta(
    val chapterIndex: Int,
    val chapterTitle: String?,
    val chapterStartMs: Long,
    val startOffsetMs: Long,
    val endOffsetMs: Long
)

private fun buildCollectedCueChapterMeta(
    chapters: List<ReaderAudioChapter>,
    cueStartMs: Long,
    cueEndMs: Long
): CollectedCueChapterMeta? {
    val index = findBookChapterIndexAtTime(chapters, cueStartMs)
    if (index !in chapters.indices) return null
    val chapterStartMs = chapters[index].startMs.coerceAtLeast(0L)
    val startOffset = (cueStartMs - chapterStartMs).coerceAtLeast(0L)
    val endOffset = (cueEndMs - chapterStartMs).coerceAtLeast(startOffset)
    return CollectedCueChapterMeta(
        chapterIndex = index,
        chapterTitle = chapters[index].title.takeIf { it.isNotBlank() },
        chapterStartMs = chapterStartMs,
        startOffsetMs = startOffset,
        endOffsetMs = endOffset
    )
}

private fun buildBookReaderNotificationPendingIntent(
    context: Context,
    title: String,
    audioUri: Uri?,
    srtUri: Uri?,
    coverUri: Uri?
): PendingIntent {
    val intent = Intent(context, BookReaderActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(BookReaderActivity.EXTRA_BOOK_TITLE, title)
        putExtra(BookReaderActivity.EXTRA_AUDIO_URI, audioUri?.toString())
        putExtra(BookReaderActivity.EXTRA_SRT_URI, srtUri?.toString())
        putExtra(BookReaderActivity.EXTRA_COVER_URI, coverUri?.toString())
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
    val stableSource = audioUri?.toString().orEmpty().ifBlank {
        "title=$title|srt=${srtUri?.toString().orEmpty()}"
    }
    return buildDictionaryCacheKey(stableSource, title.ifBlank { "book" })
}

private fun buildLegacyBookReaderPlaybackKey(
    title: String,
    audioUri: Uri?,
    srtUri: Uri?
): String {
    val raw = "title=$title|audio=${audioUri?.toString().orEmpty()}|srt=${srtUri?.toString().orEmpty()}"
    return buildDictionaryCacheKey(raw, title.ifBlank { "book" })
}

private fun normalizeBookReaderPlaybackPosition(positionMs: Long, durationMs: Long): Long {
    return positionMs.coerceAtLeast(0L)
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

private fun parseEditableTimeInputToMillis(raw: String): Long? {
    val normalized = raw.trim()
    if (normalized.isBlank()) return null
    val parts = normalized.split(':').map { it.trim() }
    if (parts.isEmpty() || parts.any { it.isBlank() || !it.all(Char::isDigit) }) return null
    val values = parts.mapNotNull { it.toLongOrNull() }
    if (values.size != parts.size) return null
    val seconds = when (values.size) {
        1 -> values[0]
        2 -> {
            val (mm, ss) = values
            if (ss >= 60L) return null
            mm * 60L + ss
        }
        3 -> {
            val hh = values[0]
            val mm = values[1]
            val ss = values[2]
            if (mm >= 60L || ss >= 60L) return null
            hh * 3600L + mm * 60L + ss
        }
        else -> return null
    }
    return seconds.coerceAtLeast(0L) * 1000L
}

@Composable
private fun BookReaderCoverImage(
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
        update = { imageView ->
            imageView.setImageURI(coverUri)
        }
    )
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







