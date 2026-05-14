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
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Html
import android.text.TextPaint
import android.util.Base64
import android.util.Log
import android.app.Activity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlin.math.ceil
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import moe.tekuza.m9player.ui.theme.TsetTheme
import moe.tekuza.m9player.hoshi.features.dictionary.DictionarySettings
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionData
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupItem
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupOptions
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupStackView
import kotlinx.coroutines.CancellationException
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.Locale
import org.json.JSONObject
import kotlin.math.abs

private const val BOOK_READER_PERMISSION_REQUEST_CODE = 21_001
private const val BOOK_READER_PENDING_INTENT_REQUEST_CODE = 21_002
private const val BOOK_READER_SLEEP_OPTIONS_PREFS = "book_reader_sleep_options_prefs"
private const val BOOK_READER_UI_TEST_PREFS = "book_reader_ui_test_prefs"
private const val BOOK_READER_UI_TEST_LAYOUT_HORIZONTAL_KEY = "layout_horizontal"
private const val BOOK_READER_UI_TEST_LAYOUT_VERTICAL_KEY = "layout_vertical"
private const val BOOK_READER_UI_SWAP_PREV_NEXT_HORIZONTAL_KEY = "ui_swap_prev_next_horizontal"
private const val BOOK_READER_UI_SWAP_PREV_NEXT_VERTICAL_KEY = "ui_swap_prev_next_vertical"
private const val BOOK_READER_UI_CHAPTER_VISIBLE_KEY = "ui_chapter_visible"
private const val BOOK_READER_SLEEP_EXIT_CONTROL_KEY = "sleep_exit_control"
private const val BOOK_READER_SLEEP_DISCONNECT_BT_KEY = "sleep_disconnect_bt"
private const val BOOK_LOOKUP_ANCHOR_LOG_TAG = "BookLookupAnchor"
private const val BOOK_LOOKUP_SELECTION_LOG_TAG = "BookLookupSelection"
private const val BOOK_READER_BACK_LOG_TAG = "BookReaderBack"
private const val BOOK_UI_MODE_LOG_TAG = "BookUiMode"
private const val BOOK_VERTICAL_TAP_DEBUG_OVERLAY = false
private const val BOOK_CUE_LOOP_LOG_TAG = "BookCueLoop"
private const val BOOK_VERTICAL_COLUMN_WIDTH_FACTOR = 1.0f
private val BOOK_VERTICAL_CUE_EDGE_PADDING = 28.dp
private val BOOK_VERTICAL_CUE_ITEM_HORIZONTAL_PADDING = 0.dp
private val BOOK_VERTICAL_CUE_GLYPH_SAFETY_WIDTH = 12.dp

class BookReaderActivity : AppCompatActivity() {
    private var gamepadKeyHandler: ((KeyEvent) -> Boolean)? = null
    private var lastMotionHorizontalKeyCode: Int? = null
    private var lastMotionVerticalKeyCode: Int? = null
    private var lastControllerBluetoothAddress: String? = null
    private var floatingOverlayStartJob: Job? = null
    private var currentAudioUriForBridge: String? = null
    private var isUiTestMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestPostNotificationsPermission()
        enableEdgeToEdge()

        val audioUri = intent.getStringExtra(EXTRA_AUDIO_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val srtUri = intent.getStringExtra(EXTRA_SRT_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val coverUri = intent.getStringExtra(EXTRA_COVER_URI)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val uiTestMode = intent.getBooleanExtra(EXTRA_UI_TEST_MODE, false)
        isUiTestMode = uiTestMode
        BookReaderFloatingBridge.setUiTestModeActive(uiTestMode)
        val title = intent.getStringExtra(EXTRA_BOOK_TITLE).orEmpty()
        currentAudioUriForBridge = audioUri?.toString()
        if (!uiTestMode) {
            activeReaderRef = WeakReference(this)
            BookReaderFloatingBridge.setCurrentAudioUri(currentAudioUriForBridge)
        }

        setContent {
            TsetTheme {
                BookReaderScreen(
                    title = title.ifBlank { "Book" },
                    audioUri = audioUri,
                    srtUri = srtUri,
                    coverUri = coverUri,
                    uiTestMode = uiTestMode,
                    contentResolver = contentResolver,
                    registerGamepadKeyHandler = { handler -> gamepadKeyHandler = handler },
                    latestControllerAddressProvider = {
                        lastControllerBluetoothAddress
                            ?: loadTargetControllerInfo(this)?.address
                            ?: detectConnectedControllerInfo(this)?.address
                    },
                    onBack = { currentPositionMs, currentDurationMs ->
                        if (uiTestMode) {
                            finish()
                        } else {
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
                                putExtra(EXTRA_RETURN_SRT_URI, srtUri?.toString())
                                putExtra(EXTRA_RETURN_POSITION_MS, normalized)
                                putExtra(EXTRA_RETURN_DURATION_MS, currentDurationMs.coerceAtLeast(0L))
                            }
                            if (loadAudiobookSettingsConfig(this).floatingOverlayShowOnReaderExit) {
                                startAudiobookFloatingOverlayService(this)
                            }
                            startActivity(intent)
                        }
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
        if (isUiTestMode) return
        val settings = loadAudiobookSettingsConfig(this)
        floatingOverlayStartJob?.cancel()
        val overlayEnabled = settings.floatingOverlayEnabled || settings.floatingOverlaySubtitleEnabled
        if (isChangingConfigurations || !overlayEnabled || !BookReaderFloatingBridge.isPlaying()) return

        floatingOverlayStartJob = lifecycleScope.launch {
            delay(150L)
            if (
                !isAppProcessInForeground(this@BookReaderActivity) &&
                run {
                    val refreshed = loadAudiobookSettingsConfig(this@BookReaderActivity)
                    refreshed.floatingOverlayEnabled || refreshed.floatingOverlaySubtitleEnabled
                } &&
                BookReaderFloatingBridge.isPlaying()
            ) {
                startAudiobookFloatingOverlayService(this@BookReaderActivity)
            }
        }
    }

    override fun onDestroy() {
        floatingOverlayStartJob?.cancel()
        floatingOverlayStartJob = null
        if (isUiTestMode) {
            BookReaderFloatingBridge.setUiTestModeActive(false)
        }
        if (!isUiTestMode) {
            if (activeReaderRef?.get() === this) {
                activeReaderRef = null
            }
            if (BookReaderFloatingBridge.currentAudioUri() == currentAudioUriForBridge) {
                BookReaderFloatingBridge.setCurrentAudioUri(null)
            }
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
        const val EXTRA_UI_TEST_MODE = "extra_ui_test_mode"
        const val EXTRA_RETURN_AUDIO_URI = "extra_return_audio_uri"
        const val EXTRA_RETURN_SRT_URI = "extra_return_srt_uri"
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

internal data class ReaderSubtitleCue(
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
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun BookReaderScreen(
    title: String,
    audioUri: Uri?,
    srtUri: Uri?,
    coverUri: Uri?,
    uiTestMode: Boolean,
    contentResolver: ContentResolver,
    registerGamepadKeyHandler: (((KeyEvent) -> Boolean)?) -> Unit,
    latestControllerAddressProvider: () -> String?,
    onBack: (Long, Long) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val view = LocalView.current
    val rootDensity = LocalDensity.current
    val isDarkTheme = isSystemInDarkTheme()
    val navigationBarBottomInsetDp = with(rootDensity) {
        WindowInsets.navigationBars.getBottom(this).toDp().value.toDouble()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var cues by remember { mutableStateOf<List<ReaderSubtitleCue>>(emptyList()) }
    var audioChapters by remember { mutableStateOf<List<ReaderAudioChapter>>(emptyList()) }
    var srtLoading by remember { mutableStateOf(false) }
    var srtError by remember { mutableStateOf<String?>(null) }

    var loadedDictionaries by remember { mutableStateOf<List<LoadedDictionary>>(emptyList()) }
    var dictionaryDataVersion by remember { mutableStateOf(loadDictionaryDataVersion(context)) }

    val hoshiLookupPopups = remember { mutableStateListOf<LookupPopupItem>() }
    var hoshiLookupPopupTemporarilyHidden by remember { mutableStateOf(false) }
    var reopenHoshiLookupPopupAfterCueRangeSelection by remember { mutableStateOf(false) }
    var resumePlaybackAfterLookupDismiss by remember { mutableStateOf(false) }
    var audiobookSettings by remember { mutableStateOf(loadAudiobookSettingsConfig(context)) }

    var lyricsMode by remember { mutableStateOf(true) }
    var controlModeEnabled by remember { mutableStateOf(false) }
    var controlModeStatus by remember { mutableStateOf<String?>(null) }
    var cueRangeSelectionMode by remember { mutableStateOf(false) }
    var cueRangeStartIndex by remember { mutableStateOf<Int?>(null) }
    var cueRangeEndIndex by remember { mutableStateOf<Int?>(null) }
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
    var uiTestChapterVisible by remember { mutableStateOf(loadUiChapterVisible(context)) }
    var readerUiWritingMode by remember { mutableStateOf(audiobookSettings.bookSubtitleWritingMode) }
    var uiTestSwapPrevNextHorizontal by remember { mutableStateOf(loadUiSwapPrevNextHorizontal(context)) }
    var uiTestSwapPrevNextVertical by remember { mutableStateOf(loadUiSwapPrevNextVertical(context)) }
    val uiTestSwapPrevNext = if (readerUiWritingMode == FloatingSubtitleWritingMode.VERTICAL_RTL) {
        uiTestSwapPrevNextVertical
    } else {
        uiTestSwapPrevNextHorizontal
    }
    var uiTestLayoutModeHorizontal by remember { mutableStateOf(loadUiTestLayoutModeHorizontal(context)) }
    var uiTestLayoutModeVertical by remember { mutableStateOf(loadUiTestLayoutModeVertical(context)) }
    val uiTestLayoutMode = if (readerUiWritingMode == FloatingSubtitleWritingMode.VERTICAL_RTL) {
        uiTestLayoutModeVertical
    } else {
        uiTestLayoutModeHorizontal
    }
    val chapterRowVisible = !uiTestMode || uiTestChapterVisible
    var coverModeEnabled by remember(srtUri) { mutableStateOf(srtUri == null) }
    val hasSubtitleFile = srtUri != null
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
    var liveSelectedRangeAnchor by remember { mutableStateOf<ReaderLookupAnchor?>(null) }
    var hoshiLookupSelectionCueIndex by remember { mutableStateOf<Int?>(null) }
    var hoshiLookupSelectionRange by remember { mutableStateOf<IntRange?>(null) }

    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var cueLoopEnabled by remember { mutableStateOf(false) }
    var cueLoopWindow by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var dragPreviewPositionMs by remember { mutableStateOf<Long?>(null) }
    val replaceSrtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pickedUri = uri ?: return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        val persistedFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(pickedUri, persistedFlags) }

        val currentSrtUri = srtUri
        val targetSrtUri = runCatching {
            if (currentSrtUri != null && currentSrtUri != pickedUri) {
                runCatching { resolver.takePersistableUriPermission(currentSrtUri, persistedFlags) }
                if (swapSrtDocumentContents(resolver, currentSrtUri, pickedUri)) {
                    currentSrtUri
                } else {
                    pickedUri
                }
            } else if (currentSrtUri == null) {
                movePickedSrtToBookFolder(
                    context = context,
                    resolver = resolver,
                    pickedSrtUri = pickedUri,
                    title = title,
                    audioUri = audioUri
                ) ?: pickedUri
            } else {
                pickedUri
            }
        }.getOrElse {
            Toast.makeText(
                context,
                "更换 SRT 失败：${it.message ?: "unknown"}",
                Toast.LENGTH_SHORT
            ).show()
            pickedUri
        }
        persistReplacedSrtToImportState(
            context = context,
            resolver = resolver,
            audioUri = audioUri,
            targetSrtUri = targetSrtUri
        )

        saveBookReaderPlaybackPosition(
            context = context,
            bookKey = buildBookReaderPlaybackKey(title, audioUri, targetSrtUri),
            positionMs = normalizeBookReaderPlaybackPosition(positionMs, durationMs),
            durationMs = durationMs.coerceAtLeast(0L)
        )
        val intent = Intent(context, BookReaderActivity::class.java).apply {
            putExtra(BookReaderActivity.EXTRA_BOOK_TITLE, title)
            putExtra(BookReaderActivity.EXTRA_AUDIO_URI, audioUri?.toString())
            putExtra(BookReaderActivity.EXTRA_SRT_URI, targetSrtUri.toString())
            putExtra(BookReaderActivity.EXTRA_COVER_URI, coverUri?.toString())
        }
        context.startActivity(intent)
        activity?.finish()
    }

    val playbackPositionKey = remember(title, audioUri, srtUri) {
        buildBookReaderPlaybackKey(title, audioUri, srtUri)
    }
    var playbackRestoreCompleted by remember(playbackPositionKey) { mutableStateOf(false) }
    var playbackCompleted by remember(playbackPositionKey) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var playbackFadeJob by remember { mutableStateOf<Job?>(null) }
    val player = remember(context) {
        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build()
    }
    fun setLookupPlaybackState(play: Boolean) {
        playbackFadeJob?.cancel()
        player.volume = 1f
        if (play) player.play() else player.pause()
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

    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                val updated = loadAudiobookSettingsConfig(context)
                if (updated != audiobookSettings) {
                    Log.d(
                        BOOK_UI_MODE_LOG_TAG,
                        "settings refreshed: writingMode=${updated.bookSubtitleWritingMode}, activeTop=${updated.activeCueDisplayAtTop}, globalFont=${updated.subtitleGlobalFontEnabled}, customFont=${updated.subtitleCustomFontUri != null}"
                    )
                }
                audiobookSettings = updated
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(audiobookSettings.bookSubtitleWritingMode, uiTestMode) {
        // Real reader should follow Settings immediately; UI test page remains isolated.
        if (!uiTestMode) {
            readerUiWritingMode = audiobookSettings.bookSubtitleWritingMode
        }
    }
    LaunchedEffect(uiTestMode) {
        if (!uiTestMode) return@LaunchedEffect
        val demoText = "吾輩は猫である。名前はまだ無い。どこで生れたかとんと見當がつかぬ。何でも薄暗いじめじめした所でニャーニャー泣いていた事だけは記憶している。吾輩はここで始めて人間というものを見た。しかもあとで聞くとそれは書生という人間中で一番獰悪な種族であったそうだ。この書生というのは時々我々を捕えて煮て食うという話である。しかしその當時は何という考もなかったから別段恐しいとも思わなかった。ただ彼の掌に載せられてスーと持ち上げられた時何だかフワフワした感じがあったばかりである。掌の上で少し落ちついて書生の顔を見たのがいわゆる人間というものの見始であろう。この時妙なものだと思った感じが今でも殘っている。第一毛をもって裝飾されべきはずの顔がつるつるしてまるで薬缶だ。その後猫にもだいぶ逢ったがこんな片輪には一度も出會わした事がない。のみならず顔の真中があまりに突起している。そうしてその穴の中から時々ぷうぷうと煙を吹く。どうも咽せぽくて実に弱った。これが人間の飲む煙草というものである事はようやくこの頃知った。"
        val sentenceDurationMs = 14_000L
        cues = demoText
            .split("。")
            .mapNotNull { it.trim().takeIf { text -> text.isNotEmpty() } }
            .mapIndexed { index, sentence ->
                val startMs = index * sentenceDurationMs
                ReaderSubtitleCue(
                    startMs = startMs,
                    endMs = startMs + sentenceDurationMs,
                    text = "$sentence。"
                )
            }
        audioChapters = listOf(
            ReaderAudioChapter(0L, "1.第一章"),
            ReaderAudioChapter(2_200_000L, "第二章"),
            ReaderAudioChapter(3_800_000L, "第三章")
        )
        positionMs = 18_000L
        durationMs = (cues.size * sentenceDurationMs).coerceAtLeast(1L)
        srtLoading = false
        srtError = null
        chapterOptionsVisible = false
        coverModeEnabled = false
    }

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
        val restoreBrightness = applyControlModeScreenBrightness(context, dimToMinimum = shouldDimToMinimum)
        onDispose {
            restoreBrightness()
        }
    }

    LaunchedEffect(hasSubtitleFile, uiTestMode) {
        if (!uiTestMode) {
            BookReaderFloatingBridge.setSubtitleTrackAvailable(hasSubtitleFile)
        }
        if (!hasSubtitleFile && controlModeEnabled) {
            controlModeEnabled = false
            controlModeStatus = null
        }
        if ((!hasSubtitleFile || !audiobookSettings.lookupRangeSelectionEnabled) && cueRangeSelectionMode) {
            cueRangeSelectionMode = false
            cueRangeStartIndex = null
            cueRangeEndIndex = null
        }
        if (!hasSubtitleFile) {
            hoshiLookupPopupTemporarilyHidden = false
        }
    }

    LaunchedEffect(audiobookSettings.lookupRangeSelectionEnabled) {
        if (!audiobookSettings.lookupRangeSelectionEnabled) {
            cueRangeSelectionMode = false
            cueRangeStartIndex = null
            cueRangeEndIndex = null
            reopenHoshiLookupPopupAfterCueRangeSelection = false
            hoshiLookupPopupTemporarilyHidden = false
        }
    }

    if (!uiTestMode) DisposableEffect(player) {
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
                    val endedDurationMs = if (player.duration > 0L) player.duration else 0L
                    scope.launch(Dispatchers.IO) {
                        saveBookReaderPlaybackPosition(
                            context = context,
                            bookKey = playbackPositionKey,
                            positionMs = 0L,
                            durationMs = endedDurationMs,
                            allowZeroPositionWrite = true
                        )
                        recordStatisticsBookCompleted(context, playbackPositionKey)
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

    if (!uiTestMode) LaunchedEffect(player, isPlaying, cueLoopEnabled, cueLoopWindow) {
        if (!isPlaying) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = if (player.duration > 0L) player.duration else 0L
            return@LaunchedEffect
        }
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = if (player.duration > 0L) player.duration else 0L
            if (cueLoopEnabled) {
                val window = cueLoopWindow
                if (window != null) {
                    val startMs = window.first.coerceAtLeast(0L)
                    val endMs = window.second.coerceAtLeast(startMs + 1L)
                    val loopAt = (endMs - 40L).coerceAtLeast(startMs)
                    if (positionMs >= loopAt || positionMs < startMs) {
                        player.seekTo(startMs)
                    }
                }
            }
            delay(250L)
        }
    }

    if (!uiTestMode) LaunchedEffect(audioUri, playbackPositionKey) {
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

    if (!uiTestMode) LaunchedEffect(audioUri) {
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

    if (!uiTestMode) LaunchedEffect(srtUri) {
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

    suspend fun loadReaderDictionariesSnapshot(): List<LoadedDictionary> {
        return withContext(Dispatchers.IO) {
            loadAvailableDictionaries(context)
        }
    }

    LaunchedEffect(dictionaryDataVersion) {
        loadedDictionaries = loadReaderDictionariesSnapshot()
    }

    val dictionaryCssByName = remember(loadedDictionaries) {
        loadedDictionaries.associate { it.name to it.stylesCss }
    }
    val dictionaryPriorityByName = remember(loadedDictionaries) {
        loadedDictionaries.mapIndexed { index, dictionary -> dictionary.name to index }.toMap()
    }
    val bookHoshiLookupSession = remember(context, loadedDictionaries) {
        HoshiLookupSession(context, dictionariesProvider = { loadedDictionaries })
    }
    val hoshiLookupPopupVisible = hoshiLookupPopups.isNotEmpty()
    val lyricsFollowTopPaddingPx = with(LocalDensity.current) { 72.dp.toPx() }

    DisposableEffect(context) {
        val listener = registerDictionaryDataVersionListener(context) { version ->
            dictionaryDataVersion = version
        }
        onDispose {
            unregisterDictionaryDataVersionListener(context, listener)
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
    val visibleSelectedRange = remember(
        activeCueIndex,
        hoshiLookupSelectionCueIndex,
        hoshiLookupSelectionRange
    ) {
        if (hoshiLookupSelectionCueIndex == activeCueIndex) {
            hoshiLookupSelectionRange
        } else {
            null
        }
    }
    LaunchedEffect(visibleSelectedRange, activeCueIndex, readerUiWritingMode, lyricsMode) {
        Log.d(
            BOOK_LOOKUP_SELECTION_LOG_TAG,
            "visibleRange activeCue=$activeCueIndex mode=$readerUiWritingMode lyrics=$lyricsMode range=${formatRangeForLog(visibleSelectedRange)}"
        )
    }
    LaunchedEffect(activeCueIndex, visibleSelectedRange, lyricsMode) {
        if (visibleSelectedRange == null) {
            liveSelectedRangeAnchor = null
        }
    }
    LaunchedEffect(liveSelectedRangeAnchor, visibleSelectedRange, hoshiLookupPopups.size) {
        val range = visibleSelectedRange ?: return@LaunchedEffect
        val avoidRects = liveSelectedRangeAnchor.toSelectionRects(rootDensity.density)
        if (avoidRects.isEmpty()) return@LaunchedEffect
        val popupIndex = hoshiLookupPopups.indexOfFirst {
            it.state.selection.sentenceOffset?.let { offset -> offset in range } == true
        }.takeIf { it >= 0 } ?: 0.takeIf { hoshiLookupPopups.isNotEmpty() } ?: return@LaunchedEffect
        val current = hoshiLookupPopups.getOrNull(popupIndex) ?: return@LaunchedEffect
        if (current.state.avoidRects == avoidRects) return@LaunchedEffect
        hoshiLookupPopups[popupIndex] = current.copy(
            state = current.state.copy(
                avoidRects = avoidRects
            )
        )
    }
    val activeCue = cues.getOrNull(activeCueIndex)
    val activeCueScrollProgress = remember(activeCue, previewPositionMs) {
        activeCue?.let { cue ->
            val duration = (cue.endMs - cue.startMs).coerceAtLeast(1L)
            mapTimedSubtitleScrollProgress(
                ((previewPositionMs - cue.startMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            )
        } ?: 0f
    }
    val selectedCueIndexRange = remember(cueRangeStartIndex, cueRangeEndIndex) {
        val start = cueRangeStartIndex ?: return@remember null
        val end = cueRangeEndIndex ?: start
        minOf(start, end)..maxOf(start, end)
    }
    val backToMain = remember(onBack, player, durationMs) {
        { source: String ->
            val current = player.currentPosition.coerceAtLeast(0L)
            val total = if (player.duration > 0L) player.duration else durationMs.coerceAtLeast(0L)
            Log.d(
                BOOK_READER_BACK_LOG_TAG,
                "backToMain source=$source hoshiLookupVisible=${hoshiLookupPopups.isNotEmpty()} hoshiLookupLayers=${hoshiLookupPopups.size} playing=${player.isPlaying} positionMs=$current durationMs=$total"
            )
            onBack(current, total)
        }
    }
    val subtitleFontFamily = remember(
        audiobookSettings.subtitleCustomFontUri
    ) {
        resolveSubtitleTypeface(context, audiobookSettings.subtitleCustomFontUri)?.let { typeface ->
            runCatching { FontFamily(typeface) }.getOrNull()
        }
    }
    val activeSubtitleStyle = MaterialTheme.typography.headlineMedium.copy(
        fontSize = 34.sp,
        lineHeight = 42.sp,
        fontFamily = subtitleFontFamily,
        color = MaterialTheme.colorScheme.onSurface
    )

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

    LaunchedEffect(playbackSpeed, uiTestMode) {
        player.playbackParameters = PlaybackParameters(playbackSpeed)
        if (!uiTestMode) {
            BookReaderFloatingBridge.notifyPlaybackSpeed(playbackSpeed)
        }
    }

    LaunchedEffect(isPlaying, uiTestMode) {
        if (!uiTestMode) {
            BookReaderFloatingBridge.notifyPlaybackState(isPlaying)
        }
    }
    LaunchedEffect(context, playbackPositionKey, uiTestMode) {
        if (!uiTestMode) {
            BookReaderFloatingBridge.setCurrentBookKey(context, playbackPositionKey)
        }
    }
    LaunchedEffect(activeCue?.text, uiTestMode) {
        if (!uiTestMode) {
            BookReaderFloatingBridge.notifySubtitle(activeCue?.text)
        }
    }
    LaunchedEffect(positionMs, uiTestMode) {
        if (!uiTestMode) {
            BookReaderFloatingBridge.notifyPlaybackPosition(positionMs)
        }
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
        if (!uiTestMode) {
            BookReaderFloatingBridge.notifyFavoriteState(favoriteCueCollected)
        }
    }

    LaunchedEffect(audioChapters.size) {
        if (audioChapters.isEmpty()) {
            chapterOptionsVisible = false
        }
    }

    LaunchedEffect(playbackPositionKey, player, isPlaying, playbackRestoreCompleted) {
        if (uiTestMode) return@LaunchedEffect
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
            if (uiTestMode) return@onDispose
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
                    controlModeStatus = context.getString(R.string.status_timer_finished_with_parts, statusParts.joinToString(", "))
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
        if (uiTestMode) return
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
        resumePlaybackAfterLookupDismiss = false
        player.seekTo(cue.startMs)
        player.play()
        controlTargetCueIndex = if (controlModeEnabled) index else null
        if (showStatus) {
            controlModeStatus = context.getString(R.string.status_jump_to_cue, index + 1, cues.size)
        }
    }

    fun clearCueRangeSelection() {
        cueRangeSelectionMode = false
        cueRangeStartIndex = null
        cueRangeEndIndex = null
        reopenHoshiLookupPopupAfterCueRangeSelection = false
        hoshiLookupPopupTemporarilyHidden = false
    }

    fun beginHoshiCueRangeSelection(reopenLookupPopupAfterSelection: Boolean) {
        if (!audiobookSettings.lookupRangeSelectionEnabled) return
        cueRangeSelectionMode = true
        cueRangeStartIndex = null
        cueRangeEndIndex = null
        reopenHoshiLookupPopupAfterCueRangeSelection = reopenLookupPopupAfterSelection
        hoshiLookupPopupTemporarilyHidden = reopenLookupPopupAfterSelection
        if (!lyricsMode) lyricsMode = true
        if (coverModeEnabled) coverModeEnabled = false
        controlModeStatus = context.getString(R.string.bookreader_range_select_start)
    }

    fun consumeCueRangeSelection() {
        if (selectedCueIndexRange != null) {
            clearCueRangeSelection()
        }
    }

    fun handleCueRangeTap(index: Int) {
        if (!cueRangeSelectionMode || cues.isEmpty()) return
        val normalizedIndex = index.coerceIn(0, cues.lastIndex)
        val start = cueRangeStartIndex
        val end = cueRangeEndIndex
        when {
            start == null || end != null -> {
                cueRangeStartIndex = normalizedIndex
                cueRangeEndIndex = null
                controlModeStatus = context.getString(
                    R.string.bookreader_range_start_selected,
                    normalizedIndex + 1
                )
            }
            else -> {
                cueRangeEndIndex = normalizedIndex
                val range = minOf(start, normalizedIndex)..maxOf(start, normalizedIndex)
                controlModeStatus = context.getString(
                    R.string.bookreader_range_end_selected,
                    range.first + 1,
                    range.last + 1,
                    range.count()
                )
                cueRangeSelectionMode = false
                if (reopenHoshiLookupPopupAfterCueRangeSelection) {
                    reopenHoshiLookupPopupAfterCueRangeSelection = false
                    hoshiLookupPopupTemporarilyHidden = false
                }
            }
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
    fun jumpToAdjacentCueByUi(step: Int) {
        val effectiveStep = if (uiTestSwapPrevNext) -step else step
        jumpToAdjacentCue(effectiveStep)
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

    fun clearHoshiLookupSelection() {
        hoshiLookupSelectionCueIndex = null
        hoshiLookupSelectionRange = null
    }

    fun triggerHoshiPopupLookup(selection: ReaderSelectionData, cue: ReaderSubtitleCue?) {
        if (uiTestMode) return
        val resolvedCue = cue ?: return
        val resolvedCueIndex = cues.indexOf(resolvedCue).takeIf { it >= 0 }
        val selectionStart = selection.sentenceOffset
            ?.coerceIn(0, resolvedCue.text.length)
            ?: 0
        val initialSelectionEndExclusive = (selectionStart + selection.text.length.coerceAtLeast(1))
            .coerceIn(selectionStart, resolvedCue.text.length)
        hoshiLookupSelectionCueIndex = resolvedCueIndex
        hoshiLookupSelectionRange = if (initialSelectionEndExclusive > selectionStart) {
            selectionStart until initialSelectionEndExclusive
        } else {
            null
        }
        val popupStartNs = SystemClock.elapsedRealtimeNanos()
        consumeCueRangeSelection()
        if (audiobookSettings.pausePlaybackOnLookup && player.isPlaying) {
            setLookupPlaybackState(play = false)
            resumePlaybackAfterLookupDismiss = true
        } else {
            resumePlaybackAfterLookupDismiss = false
        }
        val rect = selection.rect
        Log.d(
            BOOK_LOOKUP_SELECTION_LOG_TAG,
            "hoshi popup start cueIndex=$resolvedCueIndex textLen=${selection.text.length} rect=${rect.x.toInt()},${rect.y.toInt()} ${rect.width.toInt()}x${rect.height.toInt()} normalizedOffset=${selection.normalizedOffset} sentenceOffset=${selection.sentenceOffset}"
        )
        val preparedDictionaryCount = bookHoshiLookupSession.ensurePrepared().size
        Log.d(
            BOOK_LOOKUP_SELECTION_LOG_TAG,
            "hoshi popup prepared dictCount=$preparedDictionaryCount query='${selection.text.take(32)}'"
        )
        val options = LookupPopupOptions(
            isVertical = false,
            isFullWidth = audiobookSettings.lookupRootFullWidthEnabled,
            width = 320,
            height = 250,
            swipeToDismiss = true,
            swipeThreshold = 40,
            topInset = 0.0,
            bottomInset = navigationBarBottomInsetDp,
            dictionarySettings = DictionarySettings(),
            darkMode = isDarkTheme,
            eInkMode = false,
                    audioSettings = audiobookSettings,
                    showRangeSelection = hasSubtitleFile && audiobookSettings.lookupRangeSelectionEnabled,
                    showPlayAudio = audiobookSettings.lookupPlaybackAudioEnabled,
                    popupActionBar = true,
                )
        val popup = bookHoshiLookupSession.createPopup(
            selection = selection,
            options = options,
        )
        if (popup == null) {
            hoshiLookupPopups.clear()
            clearHoshiLookupSelection()
            Log.d(
                BOOK_LOOKUP_SELECTION_LOG_TAG,
                "hoshi popup empty cueIndex=$resolvedCueIndex query='${selection.text.take(32)}' sentenceOffset=${selection.sentenceOffset} elapsedMs=${(SystemClock.elapsedRealtimeNanos() - popupStartNs) / 1_000_000L}"
            )
            return
        }
        Log.d(
            "HoshiLookupPopup",
            "book root popup built query='${selection.text.take(32)}' results=${popup.first.state.results.size} cueIndex=$resolvedCueIndex"
        )
        hoshiLookupPopups.clear()
        val popupSelection = popup.first.state.selection
        val popupSelectionStart = popupSelection.sentenceOffset
            ?.coerceIn(0, resolvedCue.text.length)
            ?: selectionStart
        val matchedLength = popup.first.state.results.firstOrNull()
            ?.matched
            ?.length
            ?.coerceAtLeast(1)
            ?: 1
        val selectionEndExclusive = (popupSelectionStart + matchedLength).coerceIn(popupSelectionStart, resolvedCue.text.length)
        val matchedAnchorRect = popupSelection.anchorRectForSourceRange(popupSelectionStart, selectionEndExclusive)
        val anchoredPopup = popup.first.copy(
            state = popup.first.state.copy(
                selection = popupSelection.copy(rect = matchedAnchorRect)
            )
        )
        hoshiLookupPopups.add(anchoredPopup)
        recordStatisticsLookup(context, playbackPositionKey)
        hoshiLookupSelectionCueIndex = resolvedCueIndex
        hoshiLookupSelectionRange = if (selectionEndExclusive > popupSelectionStart) {
            popupSelectionStart until selectionEndExclusive
        } else {
            null
        }
        Log.d(
            BOOK_LOOKUP_SELECTION_LOG_TAG,
            "hoshi popup ready cueIndex=$resolvedCueIndex elapsedMs=${(SystemClock.elapsedRealtimeNanos() - popupStartNs) / 1_000_000L}"
        )
    }

    fun triggerPopupLookup(cue: ReaderSubtitleCue, offset: Int, anchor: ReaderLookupAnchor?) {
        if (uiTestMode) return
        val cueIndex = cues.indexOf(cue).takeIf { it >= 0 }
        val anchorBounds = anchor.boundingRectOrNull()
        Log.d(
            BOOK_LOOKUP_SELECTION_LOG_TAG,
            "hoshi tap redirect cueIndex=$cueIndex offset=$offset anchor=${formatRectForLog(anchorBounds)}"
        )
        val selection = createHoshiReaderSelectionFromCueTap(
            cueText = cue.text,
            cueIndex = cueIndex ?: 0,
            cues = cues,
            offset = offset,
            anchorRect = anchorBounds ?: Rect(
                left = view.width * 0.5f,
                top = view.height * 0.5f,
                right = view.width * 0.5f + 1f,
                bottom = view.height * 0.5f + 1f
            ),
            density = rootDensity.density
        )
        triggerHoshiPopupLookup(selection, cue)
    }

    fun triggerPopupLookup(selection: ReaderSelectionData, cue: ReaderSubtitleCue?) {
        triggerHoshiPopupLookup(selection, cue)
    }

    fun closeHoshiLookupPopup() {
        hoshiLookupPopups.clear()
        hoshiLookupSelectionCueIndex = null
        hoshiLookupSelectionRange = null
        hoshiLookupPopupTemporarilyHidden = false
        reopenHoshiLookupPopupAfterCueRangeSelection = false
        clearCueRangeSelection()
    }

    fun exportBookHoshiLookupEntryToAnki(content: String): Boolean {
        Log.d(
            "AnkiExportDebug",
            "bookHoshiExport rawContentLen=${content.length} rawPrefix=${content.take(120)}"
        )
        val payload = runCatching { JSONObject(content) }.getOrNull() ?: run {
            Log.d("AnkiExportDebug", "bookHoshiExport payloadParseFailed")
            return false
        }
        val expression = payload.optString("expression").trim().ifBlank {
            payload.optString("matched").trim()
        }
        if (expression.isBlank()) {
            Log.d(
                "AnkiExportDebug",
                "bookHoshiExport expressionBlank payloadKeys=${payload.keys().asSequence().joinToString(",")}"
            )
            return false
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
        val cueIndex = hoshiLookupSelectionCueIndex ?: activeCueIndex
        val sourceCue = cueIndex.takeIf { it in cues.indices }?.let { cues[it] }
        val cueText = sourceCue?.text?.trim()?.takeIf { it.isNotBlank() }
            ?: title.trim().ifBlank { expression }
        val cue = sourceCue ?: ReaderSubtitleCue(startMs = 0L, endMs = 0L, text = cueText)
        val popupSelectionText = payload.optString("popupSelectionText").trim().takeIf { it.isNotBlank() }
            ?: hoshiLookupSelectionRange?.let { range ->
                val start = range.first.coerceIn(0, cue.text.length)
                val endExclusive = (range.last + 1).coerceIn(start, cue.text.length)
                if (endExclusive > start) cue.text.substring(start, endExclusive) else null
            }?.trim()?.takeIf { it.isNotBlank() }
        Log.d(
            "AnkiExportDebug",
            "bookHoshiExport payload expression=$expression reading=${reading.orEmpty()} dict=$primaryDictionaryName " +
                "glossaryLen=${glossary.length} frequencyLen=${frequency.length} pitchLen=${pitch.length} " +
                "popupSelectionLen=${popupSelectionText.orEmpty().length} cue=${cue.text.take(48)}"
        )
        val exportResult = runBlocking {
            withContext(Dispatchers.IO) {
                val preparedLookupAudio = prepareLookupAudioForAnkiExport(
                    context = context,
                    term = expression,
                    reading = reading,
                    settings = audiobookSettings
                )
                try {
                    addLookupDefinitionToAnki(
                        context = context,
                        cue = cue,
                        audioUri = audioUri,
                        lookupAudioUri = preparedLookupAudio?.uri,
                        bookTitle = title,
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
                        sentenceOverride = cue.text
                    )
                } finally {
                    preparedLookupAudio?.cleanup?.invoke()
                }
            }
        }
        val message = ankiExportResultMessage(context, exportResult)
        Log.d(
            "AnkiExportDebug",
            "bookHoshiExport result=${exportResult.javaClass.simpleName} message=${message.take(220)}"
        )
        if (message.isNotBlank() && exportResult !is AnkiExportResult.DuplicateSkipped) {
            Toast.makeText(
                context,
                message.take(220),
                if (exportResult == AnkiExportResult.Added) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
            ).show()
        }
        return exportResult == AnkiExportResult.Added ||
            exportResult is AnkiExportResult.DuplicateSkipped
    }

    fun checkBookAnkiDuplicate(expression: String): AnkiDuplicateCheckResult {
        return runBlocking {
            checkAnkiDuplicateByFirstFieldAsync(context, expression)
        }
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
        if (cueLoopEnabled) {
            val targetIndex = when {
                activeCueIndex > 0 -> activeCueIndex - 1
                cues.isNotEmpty() -> 0
                else -> -1
            }
            val targetCue = cues.getOrNull(targetIndex)
            if (targetCue != null && targetCue.endMs > targetCue.startMs) {
                cueLoopWindow = targetCue.startMs to targetCue.endMs
                if (!uiTestMode) {
                    BookReaderFloatingBridge.notifyCueLoopState(true)
                }
                Log.d(
                    BOOK_CUE_LOOP_LOG_TAG,
                    "seekPrevious update loop window=${targetCue.startMs}-${targetCue.endMs}"
                )
            }
        }
        jumpToAdjacentCue(-1)
    })
    val latestSeekNext by rememberUpdatedState<() -> Unit>({
        if (cueLoopEnabled) {
            val lastIndex = cues.lastIndex
            val targetIndex = when {
                activeCueIndex in 0 until lastIndex -> activeCueIndex + 1
                activeCueIndex == -1 && cues.isNotEmpty() -> 0
                else -> lastIndex
            }
            val targetCue = cues.getOrNull(targetIndex)
            if (targetCue != null && targetCue.endMs > targetCue.startMs) {
                cueLoopWindow = targetCue.startMs to targetCue.endMs
                if (!uiTestMode) {
                    BookReaderFloatingBridge.notifyCueLoopState(true)
                }
                Log.d(
                    BOOK_CUE_LOOP_LOG_TAG,
                    "seekNext update loop window=${targetCue.startMs}-${targetCue.endMs}"
                )
            }
        }
        jumpToAdjacentCue(1)
    })
    val latestReplayCurrentCue by rememberUpdatedState<() -> Unit>({
        val cue = activeCue
        if (cue != null) {
            player.seekTo(cue.startMs.coerceAtLeast(0L))
            if (!player.isPlaying) {
                player.play()
            }
        }
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

    if (!uiTestMode) DisposableEffect(Unit) {
        val controller = object : BookReaderFloatingBridge.Controller {
            override fun isPlaying(): Boolean = latestIsPlaying
            override fun isFavorite(): Boolean = favoriteCueCollected
            override fun isCueLoopEnabled(): Boolean = cueLoopEnabled

            override fun togglePlayPause() {
                latestTogglePlayPause()
            }

            override fun setPlaying(play: Boolean) {
                setLookupPlaybackState(play)
            }

            override fun seekPrevious() {
                latestSeekPrevious()
            }

            override fun seekNext() {
                latestSeekNext()
            }

            override fun replayCurrentCue() {
                latestReplayCurrentCue()
            }

            override fun toggleCueLoop() {
                Log.d(BOOK_CUE_LOOP_LOG_TAG, "toggle requested enabled=$cueLoopEnabled cue=${activeCue?.startMs}-${activeCue?.endMs}")
                if (cueLoopEnabled) {
                    cueLoopEnabled = false
                    cueLoopWindow = null
                    BookReaderFloatingBridge.notifyCueLoopState(false)
                    Log.d(BOOK_CUE_LOOP_LOG_TAG, "toggle applied enabled=false window=null")
                } else {
                    val cue = activeCue ?: run {
                        val fallbackIndex = findBookCueIndexAtTime(cues, player.currentPosition.coerceAtLeast(0L))
                        cues.getOrNull(fallbackIndex)
                    }
                    if (cue == null || cue.endMs <= cue.startMs) {
                        Log.d(
                            BOOK_CUE_LOOP_LOG_TAG,
                            "toggle ignored no-valid-cue active=${activeCue != null} pos=${player.currentPosition} cues=${cues.size}"
                        )
                        return
                    }
                    cueLoopWindow = cue.startMs to cue.endMs
                    cueLoopEnabled = true
                    BookReaderFloatingBridge.notifyCueLoopState(true)
                    Log.d(BOOK_CUE_LOOP_LOG_TAG, "toggle applied enabled=true window=${cue.startMs}-${cue.endMs}")
                    if (!player.isPlaying) {
                        player.play()
                    }
                }
            }

            override fun toggleFavorite() {
                latestToggleFavorite()
            }

            override fun showReader() {
                val intent = Intent(context, BookReaderActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(BookReaderActivity.EXTRA_BOOK_TITLE, title)
                    putExtra(BookReaderActivity.EXTRA_AUDIO_URI, audioUri?.toString())
                    putExtra(BookReaderActivity.EXTRA_SRT_URI, srtUri?.toString())
                    putExtra(BookReaderActivity.EXTRA_COVER_URI, coverUri?.toString())
                }
                context.startActivity(intent)
            }

            override fun lookupCurrentSubtitleAt(offset: Int) {
                val cue = activeCue ?: return
                triggerPopupLookup(
                    cue = cue,
                    offset = offset.coerceIn(0, cue.text.length.coerceAtLeast(1) - 1),
                    anchor = null
                )
            }
        }
        BookReaderFloatingBridge.attach(controller)
        onDispose {
            BookReaderFloatingBridge.notifySubtitle(null)
            BookReaderFloatingBridge.setCurrentCue(null, null, null, null, null, null, null, null)
            BookReaderFloatingBridge.detach(controller)
        }
    }

    if (!uiTestMode) LaunchedEffect(activeCue, activeCueIndex, cues, title, audioUri) {
        val fullSentenceSelection = if (activeCueIndex in cues.indices) {
            extractFullSentenceLikeHoshiFromCues(
                cues = cues,
                cueIndex = activeCueIndex,
                anchorText = null,
                selectedRangeInCue = null,
                rawAnchorOffsetInCue = null
            )
        } else {
            null
        }
        val fullSentence = fullSentenceSelection?.text?.trim()?.takeIf { it.isNotBlank() }
        val fullSentenceStartMs = fullSentenceSelection
            ?.cueRange
            ?.first
            ?.takeIf { it in cues.indices }
            ?.let { cues[it].startMs }
        val fullSentenceEndMs = fullSentenceSelection
            ?.cueRange
            ?.last
            ?.takeIf { it in cues.indices }
            ?.let { cues[it].endMs }
        BookReaderFloatingBridge.setCurrentCue(
            text = activeCue?.text,
            startMs = activeCue?.startMs,
            endMs = activeCue?.endMs,
            bookTitle = title,
            audioUri = audioUri?.toString(),
            fullSentenceText = fullSentence,
            fullSentenceStartMs = fullSentenceStartMs,
            fullSentenceEndMs = fullSentenceEndMs
        )
    }

    DisposableEffect(Unit) {
        registerGamepadKeyHandler { event -> latestHandleGamepadKeyEvent(event) }
        onDispose { registerGamepadKeyHandler(null) }
    }

        BackHandler {
            when {
                hoshiLookupPopupVisible -> {
                    hoshiLookupPopups.clear()
                    hoshiLookupSelectionCueIndex = null
                    hoshiLookupSelectionRange = null
                }
                sleepTimerOptionsVisible -> sleepTimerOptionsVisible = false
                topActionsExpanded -> topActionsExpanded = false
                speedMenuExpanded -> speedMenuExpanded = false
                chapterOptionsVisible -> chapterOptionsVisible = false
                else -> backToMain("system_back")
            }
        }

    Box(modifier = Modifier.fillMaxSize()) {
        val useLeftVerticalLayout = uiTestLayoutMode == 2
        val density = LocalDensity.current
        var leftControlsWidthDp by remember { mutableStateOf(0.dp) }
        val leftRailGap = 0.dp
        val leftRailContentGap = 8.dp
        var topBarBottomDp by remember { mutableStateOf(0.dp) }
        var chapterRowBottomDp by remember { mutableStateOf(0.dp) }
        var contentContainerTopDp by remember { mutableStateOf(0.dp) }
        var contentContainerHeightDp by remember { mutableStateOf(0.dp) }
        val fallbackLeftRailTopDp = if (chapterRowBottomDp > topBarBottomDp) {
            chapterRowBottomDp + 2.dp
        } else {
            topBarBottomDp + 2.dp
        }
        val leftRailTopDp = contentContainerTopDp.takeIf { it > 0.dp } ?: fallbackLeftRailTopDp
        val contentStartPadding: Dp = if (useLeftVerticalLayout && bottomControlsVisible) {
            leftControlsWidthDp + leftRailContentGap
        } else {
            0.dp
        }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (bottomControlsVisible && !useLeftVerticalLayout) {
                    Surface(tonalElevation = 4.dp) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (audioChapters.isNotEmpty() && chapterRowVisible) {
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
                            if (audioChapters.isNotEmpty() && chapterOptionsVisible && chapterRowVisible) {
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
                                IconButton(onClick = { jumpToAdjacentCue(-1) }) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_overlay_previous),
                                        contentDescription = "Previous"
                                    )
                                }
                                IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                                    Icon(
                                        painter = painterResource(
                                            id = if (isPlaying) R.drawable.ic_overlay_pause else R.drawable.ic_overlay_play
                                        ),
                                        contentDescription = if (isPlaying) "Pause" else "Play"
                                    )
                                }
                                IconButton(onClick = { jumpToAdjacentCue(1) }) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_overlay_next),
                                        contentDescription = "Next"
                                    )
                                }
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
                if (bottomControlsVisible && useLeftVerticalLayout) {
                    Surface(tonalElevation = 4.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
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
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            topBarBottomDp = with(density) {
                                (coordinates.positionInRoot().y + coordinates.size.height).toDp()
                            }
                        },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { backToMain("top_bar_button") }) {
                        Text(stringResource(R.string.bookreader_back))
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { if (favoriteCue != null) toggleFavoriteCue() },
                        enabled = favoriteCue != null && !uiTestMode
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
                                    text = { Text((if (isCurrent) "> " else "") + "${speed}x") },
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
                            if (hasSubtitleFile) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.bookreader_control_mode)) },
                                    onClick = {
                                        controlModeEnabled = true
                                        topActionsExpanded = false
                                        controlModeStatus = context.getString(R.string.bookreader_control_mode_entered)
                                    }
                                )
                            }
                            if (uiTestMode) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (uiTestChapterVisible) {
                                                stringResource(R.string.bookreader_ui_test_chapter_off)
                                            } else {
                                                stringResource(R.string.bookreader_ui_test_chapter_on)
                                            }
                                        )
                                    },
                                    onClick = {
                                        uiTestChapterVisible = !uiTestChapterVisible
                                        saveUiChapterVisible(context, uiTestChapterVisible)
                                        chapterOptionsVisible = false
                                        topActionsExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.bookreader_ui_test_layout_mode, uiTestLayoutMode)) },
                                    onClick = {
                                        val next = if (uiTestLayoutMode == 1) 2 else 1
                                        if (readerUiWritingMode == FloatingSubtitleWritingMode.VERTICAL_RTL) {
                                            uiTestLayoutModeVertical = next
                                            saveUiTestLayoutModeVertical(context, next)
                                            Log.d(BOOK_UI_MODE_LOG_TAG, "vertical layout mode -> $next")
                                        } else {
                                            uiTestLayoutModeHorizontal = next
                                            saveUiTestLayoutModeHorizontal(context, next)
                                            Log.d(BOOK_UI_MODE_LOG_TAG, "horizontal layout mode -> $next")
                                        }
                                        topActionsExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (uiTestSwapPrevNext) {
                                                stringResource(R.string.bookreader_ui_test_swap_swapped)
                                            } else {
                                                stringResource(R.string.bookreader_ui_test_swap_normal)
                                            }
                                        )
                                    },
                                    onClick = {
                                        val next = !uiTestSwapPrevNext
                                        if (readerUiWritingMode == FloatingSubtitleWritingMode.VERTICAL_RTL) {
                                            uiTestSwapPrevNextVertical = next
                                            saveUiSwapPrevNextVertical(context, next)
                                        } else {
                                            uiTestSwapPrevNextHorizontal = next
                                            saveUiSwapPrevNextHorizontal(context, next)
                                        }
                                        topActionsExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.audiobook_overlay_subtitle_writing_mode_horizontal)) },
                                    onClick = {
                                        readerUiWritingMode = FloatingSubtitleWritingMode.HORIZONTAL
                                        Log.d(BOOK_UI_MODE_LOG_TAG, "writing mode -> HORIZONTAL")
                                        topActionsExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.audiobook_overlay_subtitle_writing_mode_vertical_rtl)) },
                                    onClick = {
                                        readerUiWritingMode = FloatingSubtitleWritingMode.VERTICAL_RTL
                                        Log.d(BOOK_UI_MODE_LOG_TAG, "writing mode -> VERTICAL_RTL")
                                        topActionsExpanded = false
                                    }
                                )
                            } else {
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
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.bookreader_replace_srt)) },
                                    onClick = {
                                        topActionsExpanded = false
                                        if (player.isPlaying) player.pause()
                                        replaceSrtLauncher.launch(arrayOf("application/x-subrip"))
                                    }
                                )
                            }
                        }
                    }
                }
                if (useLeftVerticalLayout && bottomControlsVisible && audioChapters.isNotEmpty() && chapterRowVisible) {
                    val activeChapterTitle = audioChapters
                        .getOrNull(activeChapterIndex)
                        ?.title
                        ?.takeIf { it.isNotBlank() }
                    Surface(
                        tonalElevation = 1.dp,
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            chapterRowBottomDp = with(density) {
                                (coordinates.positionInRoot().y + coordinates.size.height).toDp()
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box {
                                OutlinedButton(onClick = { chapterOptionsVisible = !chapterOptionsVisible }) {
                                    Text(stringResource(R.string.bookreader_chapters_collapsed))
                                }
                                DropdownMenu(
                                    expanded = chapterOptionsVisible,
                                    onDismissRequest = { chapterOptionsVisible = false }
                                ) {
                                    audioChapters.forEachIndexed { index, chapter ->
                                        DropdownMenuItem(
                                            text = { Text("${index + 1}. ${chapter.title}") },
                                            onClick = {
                                                jumpToChapter(chapter)
                                                chapterOptionsVisible = false
                                            }
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "Now: ${activeChapterTitle ?: "--"}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = contentStartPadding)
                            .padding(
                                top = if (coverModeEnabled) 18.dp else 0.dp,
                                bottom = if (coverModeEnabled) 20.dp else 0.dp
                            )
                            .onGloballyPositioned { coordinates ->
                                if (useLeftVerticalLayout && bottomControlsVisible) {
                                    contentContainerTopDp = with(density) { coordinates.positionInRoot().y.toDp() }
                                    contentContainerHeightDp = with(density) { coordinates.size.height.toDp() }
                                }
                            },
                        tonalElevation = 1.dp,
                        shape = MaterialTheme.shapes.large
                    ) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(if (coverModeEnabled) 0.dp else 16.dp)
                        ) {
                        val density = LocalDensity.current
                        val bookVerticalWriting = readerUiWritingMode == FloatingSubtitleWritingMode.VERTICAL_RTL
                        val verticalRowsPerColumn = remember(
                            bookVerticalWriting,
                            density,
                            maxHeight,
                            activeSubtitleStyle.fontSize,
                            activeSubtitleStyle.lineHeight
                        ) {
                            if (!bookVerticalWriting) {
                                BOOK_VERTICAL_ROWS_PER_COLUMN
                            } else {
                                val availableHeightPx = with(density) { maxHeight.toPx() }
                                val glyphStepPx = with(density) {
                                    activeSubtitleStyle.fontSize.toPx().coerceAtLeast(1f)
                                }
                                (availableHeightPx / glyphStepPx)
                                    .toInt()
                                    .coerceAtLeast(2)
                            }
                        }
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
                                            color = MaterialTheme.colorScheme.onSurface,
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
                                        Text(
                                            text = title,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                        )
                                        Box(modifier = Modifier.fillMaxSize()) {
                                            Text(
                                                text = "No cover",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.align(Alignment.Center)
                                            )
                                        }
                                    }
                                } 
                            }
                                cues.isEmpty() -> Text(stringResource(R.string.bookreader_no_subtitles))
                            lyricsMode -> {
                                if (bookVerticalWriting) {
                                    LazyRow(
                                        modifier = Modifier.fillMaxSize(),
                                        state = lyricsListState,
                                        reverseLayout = true,
                                        contentPadding = PaddingValues(horizontal = BOOK_VERTICAL_CUE_EDGE_PADDING),
                                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        itemsIndexed(
                                            items = cues,
                                            key = { _, cue -> "${cue.startMs}:${cue.endMs}:${cue.text.hashCode()}" },
                                            contentType = { _, _ -> "verticalCue" }
                                        ) { index, cue ->
                                            val isActive = index == activeCueIndex
                                            val inSelectedRange = selectedCueIndexRange?.contains(index) == true
                                            val cueDisplay = remember(
                                                cue.text,
                                                readerUiWritingMode,
                                                verticalRowsPerColumn
                                            ) {
                                                transformSubtitleForWritingMode(
                                                    cue.text,
                                                    readerUiWritingMode,
                                                    verticalRowsPerColumn
                                                )
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .fillParentMaxHeight()
                                                    .background(
                                                        if (inSelectedRange) {
                                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                                        } else {
                                                            Color.Transparent
                                                    },
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                    .padding(
                                                        horizontal = BOOK_VERTICAL_CUE_ITEM_HORIZONTAL_PADDING,
                                                        vertical = 6.dp
                                                    )
                                            ) {
                                                val cueStyle = if (isActive) {
                                                    activeSubtitleStyle
                                                } else {
                                                    MaterialTheme.typography.titleLarge.copy(
                                                        fontFamily = subtitleFontFamily,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                if (bookVerticalWriting) {
                                                    if (isActive) {
                                                        val cueWidth = rememberVerticalCueWidth(
                                                            text = cue.text,
                                                            style = cueStyle,
                                                            rowsPerColumn = verticalRowsPerColumn,
                                                            compact = true
                                                        )
                                                        VerticalLookupClickableSubtitle(
                                                            sourceText = cue.text,
                                                            style = cueStyle,
                                                            rowsPerColumn = verticalRowsPerColumn,
                                                            selectedSourceRange = visibleSelectedRange,
                                                            compactVerticalLayout = true,
                                                            lookupEnabled = !cueRangeSelectionMode,
                                                            modifier = Modifier
                                                                .fillParentMaxHeight()
                                                                .width(cueWidth),
                                                            onDisplayTap = {
                                                                if (cueRangeSelectionMode) {
                                                                    handleCueRangeTap(index)
                                                                }
                                                            },
                                                            onSelectedRangeAnchorChanged = { anchor ->
                                                                liveSelectedRangeAnchor = anchor
                                                            },
                                                            onTextTap = { offset, anchor ->
                                                                if (cueRangeSelectionMode) return@VerticalLookupClickableSubtitle
                                                                Log.d(
                                                                    BOOK_LOOKUP_SELECTION_LOG_TAG,
                                                                    "verticalNativeTap(list) cueIndex=$index offset=$offset range=${formatRangeForLog(visibleSelectedRange)} anchor=${anchor.boundingRectOrNull()?.let { "${it.left.toInt()},${it.top.toInt()},${it.right.toInt()},${it.bottom.toInt()}" } ?: "null"}"
                                                                )
                                                                triggerPopupLookup(cue, offset, anchor)
                                                            }
                                                        )
                                                    } else {
                                                        val cueWidth = rememberVerticalCueWidth(
                                                            text = cue.text,
                                                            style = cueStyle,
                                                            rowsPerColumn = verticalRowsPerColumn,
                                                            compact = true
                                                        )
                                                        VerticalSubtitleText(
                                                            text = cue.text,
                                                            style = cueStyle,
                                                            rowsPerColumn = verticalRowsPerColumn,
                                                            compactVerticalLayout = true,
                                                            onClick = {
                                                                if (cueRangeSelectionMode) {
                                                                    handleCueRangeTap(index)
                                                                } else {
                                                                    jumpToCue(index)
                                                                }
                                                            },
                                                            modifier = Modifier
                                                                .fillParentMaxHeight()
                                                                .width(cueWidth)
                                                        )
                                                    }
                                                } else if (!isActive && !cueRangeSelectionMode) {
                                                    val cueWidth = rememberVerticalCueWidth(
                                                        text = cue.text,
                                                        style = cueStyle,
                                                        rowsPerColumn = verticalRowsPerColumn,
                                                        compact = true
                                                    )
                                                    VerticalSubtitleText(
                                                        text = cue.text,
                                                        style = cueStyle,
                                                        rowsPerColumn = verticalRowsPerColumn,
                                                        compactVerticalLayout = true,
                                                        onClick = { jumpToCue(index) },
                                                        modifier = Modifier
                                                            .fillParentMaxHeight()
                                                            .width(cueWidth)
                                                    )
                                                } else {
                                                    ReaderLookupClickableSubtitle(
                                                        text = buildHighlightedText(
                                                            cueDisplay.text,
                                                            if (isActive) {
                                                                mapSourceRangeToDisplayRange(
                                                                    visibleSelectedRange,
                                                                    cueDisplay.sourceToDisplay
                                                                )
                                                            } else {
                                                                null
                                                            }
                                                        ),
                                                        selectedRange = if (isActive) {
                                                            mapSourceRangeToDisplayRange(
                                                                visibleSelectedRange,
                                                                cueDisplay.sourceToDisplay
                                                            )
                                                        } else {
                                                            null
                                                        },
                                                        style = cueStyle,
                                                        onSelectedRangeAnchorChanged = if (isActive) {
                                                            { anchor ->
                                                                liveSelectedRangeAnchor = anchor
                                                            }
                                                        } else null,
                                                        onTextTap = { offset, anchor ->
                                                            if (cueRangeSelectionMode) {
                                                                handleCueRangeTap(index)
                                                            } else if (isActive) {
                                                                val sourceOffset = cueDisplay.displayToSource
                                                                    .getOrElse(offset) { 0 }
                                                                    .coerceIn(0, cue.text.length.coerceAtLeast(1) - 1)
                                                                triggerPopupLookup(cue, sourceOffset, anchor)
                                                            } else {
                                                                jumpToCue(index)
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        state = lyricsListState,
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        itemsIndexed(
                                            items = cues,
                                            key = { _, cue -> "${cue.startMs}:${cue.endMs}:${cue.text.hashCode()}" },
                                            contentType = { _, _ -> "horizontalCue" }
                                        ) { index, cue ->
                                            val isActive = index == activeCueIndex
                                            val inSelectedRange = selectedCueIndexRange?.contains(index) == true
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(
                                                        if (inSelectedRange) {
                                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                                        } else {
                                                            Color.Transparent
                                                        },
                                                        shape = RoundedCornerShape(12.dp)
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                            ) {
                                                ReaderLookupClickableSubtitle(
                                                    text = buildHighlightedText(
                                                        cue.text,
                                                        if (isActive) visibleSelectedRange else null
                                                    ),
                                                    selectedRange = if (isActive) {
                                                        visibleSelectedRange
                                                    } else {
                                                        null
                                                    },
                                                    style = if (isActive) {
                                                        activeSubtitleStyle
                                                    } else {
                                                        MaterialTheme.typography.titleLarge.copy(
                                                            fontFamily = subtitleFontFamily,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    },
                                                    onSelectedRangeAnchorChanged = if (isActive) {
                                                        { anchor ->
                                                            liveSelectedRangeAnchor = anchor
                                                        }
                                                    } else null,
                                                    onTextTap = { offset, anchor ->
                                                        if (cueRangeSelectionMode) {
                                                            handleCueRangeTap(index)
                                                        } else if (isActive) {
                                                            triggerPopupLookup(cue, offset, anchor)
                                                        } else {
                                                            jumpToCue(index)
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                                activeCue == null -> Text(stringResource(R.string.bookreader_waiting_for_subtitle))
                            else -> {
                                val activeCueDisplay = remember(
                                    activeCue.text,
                                    readerUiWritingMode,
                                    verticalRowsPerColumn
                                ) {
                                    transformSubtitleForWritingMode(
                                        activeCue.text,
                                        readerUiWritingMode,
                                        verticalRowsPerColumn
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(
                                            top = if (audiobookSettings.activeCueDisplayAtTop) 36.dp else 0.dp
                                        ),
                                    contentAlignment = when {
                                        bookVerticalWriting && audiobookSettings.activeCueDisplayAtTop -> Alignment.TopEnd
                                        bookVerticalWriting -> Alignment.CenterEnd
                                        audiobookSettings.activeCueDisplayAtTop -> Alignment.TopCenter
                                        else -> Alignment.Center
                                    }
                                ) {
                                    if (bookVerticalWriting) {
                                        VerticalLookupClickableSubtitle(
                                            sourceText = activeCue.text,
                                            style = activeSubtitleStyle,
                                            rowsPerColumn = verticalRowsPerColumn,
                                            selectedSourceRange = visibleSelectedRange,
                                            lookupEnabled = !cueRangeSelectionMode,
                                            modifier = Modifier.fillMaxSize(),
                                            onDisplayTap = {
                                                if (cueRangeSelectionMode) {
                                                    handleCueRangeTap(activeCueIndex)
                                                }
                                            },
                                            onSelectedRangeAnchorChanged = { anchor ->
                                                liveSelectedRangeAnchor = anchor
                                            },
                                            onTextTap = { offset, anchor ->
                                                if (cueRangeSelectionMode) return@VerticalLookupClickableSubtitle
                                                Log.d(
                                                    BOOK_LOOKUP_SELECTION_LOG_TAG,
                                                    "verticalNativeTap(active) cueIndex=$activeCueIndex offset=$offset range=${formatRangeForLog(visibleSelectedRange)} anchor=${anchor.boundingRectOrNull()?.let { "${it.left.toInt()},${it.top.toInt()},${it.right.toInt()},${it.bottom.toInt()}" } ?: "null"}"
                                                )
                                                triggerPopupLookup(activeCue, offset, anchor)
                                            }
                                        )
                                    } else {
                                        ReaderLookupClickableSubtitle(
                                            text = buildHighlightedText(
                                                activeCue.text,
                                                visibleSelectedRange
                                            ),
                                            selectedRange = visibleSelectedRange,
                                            style = activeSubtitleStyle,
                                            onSelectedRangeAnchorChanged = { anchor ->
                                                liveSelectedRangeAnchor = anchor
                                            },
                                            onTextTap = { offset, anchor ->
                                                if (cueRangeSelectionMode) {
                                                    handleCueRangeTap(activeCueIndex)
                                                } else {
                                                    triggerPopupLookup(activeCue, offset, anchor)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        }
                    }
                if (useLeftVerticalLayout && bottomControlsVisible) {
                    LeftVerticalControlRail(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = leftRailGap)
                            .fillMaxHeight(),
                        displayedRightDurationTimeMs = displayedRightDurationTimeMs,
                        displayedLeftTimeMs = displayedLeftTimeMs,
                        sliderMax = sliderMax,
                        sliderValue = sliderValue,
                        displayedDurationTimeMs = displayedDurationTimeMs,
                        useChapterTimeline = useChapterTimeline,
                        activeChapterStartMs = activeChapterStartMs,
                        timelineRangeMs = timelineRangeMs,
                        durationMs = durationMs,
                        isPlaying = player.isPlaying,
                        onToggleDurationMode = { showOverallDuration = !showOverallDuration },
                        onLeftRailMeasured = { coordinates ->
                            leftControlsWidthDp = with(density) { coordinates.size.width.toDp() }
                        },
                        onPreviewPositionChanged = { dragPreviewPositionMs = it },
                        onSeekManual = { targetMs -> seekToManual(targetMs) },
                        onRequestTimeEdit = {
                            timeEditInput = formatBookTime(displayedLeftTimeMs)
                            timeEditError = null
                            timeEditDialogVisible = true
                        },
                        onPrevious = { jumpToAdjacentCue(-1) },
                        onPlayPause = {
                            if (player.isPlaying) player.pause() else player.play()
                        },
                        onNext = { jumpToAdjacentCue(1) }
                    )
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
                            Text(
                                text = stringResource(R.string.bookreader_sleep_exit_control),
                                modifier = Modifier.weight(1f).padding(end = 12.dp)
                            )
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
                            Text(
                                text = stringResource(R.string.bookreader_sleep_disconnect_bluetooth),
                                modifier = Modifier.weight(1f).padding(end = 12.dp)
                            )
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

    if (hoshiLookupPopups.isNotEmpty() && !hoshiLookupPopupTemporarilyHidden) LookupPopupStackView(
        popups = hoshiLookupPopups,
        onPopupsChange = { next ->
            hoshiLookupPopups.clear()
            hoshiLookupPopups.addAll(next)
            if (next.isEmpty()) {
                hoshiLookupSelectionCueIndex = null
                hoshiLookupSelectionRange = null
                hoshiLookupPopupTemporarilyHidden = false
                reopenHoshiLookupPopupAfterCueRangeSelection = false
            }
        },
        lookupChildPopup = { selection ->
            Log.d(
                "AnkiExportDebug",
                "bookHoshi lookupChildPopup request text='${selection.text.take(24)}' sentenceOffset=${selection.sentenceOffset} hasResults=${hoshiLookupPopups.isNotEmpty()} stackSize=${hoshiLookupPopups.size}"
            )
            val popup = bookHoshiLookupSession.createPopup(
                selection = selection,
                options = LookupPopupOptions(
                    isVertical = false,
                    isFullWidth = audiobookSettings.lookupRootFullWidthEnabled,
                    width = 320,
                    height = 250,
                    swipeToDismiss = true,
                    swipeThreshold = 40,
                    topInset = 0.0,
                    bottomInset = navigationBarBottomInsetDp,
                    dictionarySettings = DictionarySettings(),
                    darkMode = isDarkTheme,
                    eInkMode = false,
                    audioSettings = audiobookSettings,
                    showRangeSelection = false,
                    showPlayAudio = audiobookSettings.lookupPlaybackAudioEnabled,
                    popupActionBar = true,
                ),
            )
            if (popup == null) {
                Log.d(
                    "AnkiExportDebug",
                    "bookHoshi lookupChildPopup empty text='${selection.text.take(24)}'"
                )
            }
            popup
        },
        onLookupRedirect = { query ->
            bookHoshiLookupSession.lookup(
                query,
                DictionarySettings().maxResults,
                DictionarySettings().scanLength,
            )
        },
        onRangeSelection = {
            beginHoshiCueRangeSelection(reopenLookupPopupAfterSelection = true)
        },
        onMineEntry = { content ->
            Log.d(
                "AnkiExportDebug",
                "bookHoshi onMineEntry contentSize=${content.length} selectionCueIndex=$hoshiLookupSelectionCueIndex activeCueIndex=$activeCueIndex"
            )
            exportBookHoshiLookupEntryToAnki(content)
        },
        onDuplicateCheck = { expression -> checkBookAnkiDuplicate(expression) },
        onViewDuplicate = { noteIds -> openAnkiDuplicateNotesInBrowser(context, noteIds) },
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
        onCloseAll = {
            Log.d(
                "AnkiExportDebug",
                "bookHoshi onCloseAll stackSize=${hoshiLookupPopups.size} topIndex=${hoshiLookupPopups.lastIndex}"
            )
            closeHoshiLookupPopup()
        },
        modifier = Modifier.fillMaxSize(),
        onRootPopupDismissed = {
            hoshiLookupPopups.clear()
            hoshiLookupSelectionCueIndex = null
            hoshiLookupSelectionRange = null
            hoshiLookupPopupTemporarilyHidden = false
            reopenHoshiLookupPopupAfterCueRangeSelection = false
            clearCueRangeSelection()
        },
    )

}
}

@Composable
private fun LeftVerticalControlRail(
    modifier: Modifier = Modifier,
    displayedRightDurationTimeMs: Long,
    displayedLeftTimeMs: Long,
    sliderMax: Float,
    sliderValue: Float,
    displayedDurationTimeMs: Long,
    useChapterTimeline: Boolean,
    activeChapterStartMs: Long,
    timelineRangeMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onToggleDurationMode: () -> Unit,
    onLeftRailMeasured: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit,
    onPreviewPositionChanged: (Long?) -> Unit,
    onSeekManual: (Long) -> Unit,
    onRequestTimeEdit: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit
) {
    var verticalTimelineHeightPx by remember { mutableStateOf(220f) }
    var pendingSeekTargetMs by remember { mutableStateOf<Long?>(null) }
    fun resolveVerticalTimelineTarget(y: Float): Long? {
        if (displayedDurationTimeMs <= 0L) return null
        val ratio = 1f - (y.coerceIn(0f, verticalTimelineHeightPx) / verticalTimelineHeightPx)
        val clamped = (ratio * timelineRangeMs.toFloat()).toLong()
            .coerceIn(0L, timelineRangeMs)
        return if (useChapterTimeline) {
            activeChapterStartMs + clamped
        } else {
            clamped.coerceIn(0L, durationMs.coerceAtLeast(0L))
        }
    }
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
        modifier = modifier
            .width(60.dp)
            .onGloballyPositioned(onLeftRailMeasured)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 8.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = formatBookTime(displayedRightDurationTimeMs),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                maxLines = 1,
                modifier = if (useChapterTimeline) {
                    Modifier.clickable(onClick = onToggleDurationMode)
                } else {
                    Modifier
                }
            )
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .heightIn(min = 180.dp, max = 520.dp)
                    .weight(1f, fill = false)
                    .background(Color(0xFFD4E0F2), RoundedCornerShape(10.dp))
                    .onGloballyPositioned { coordinates ->
                        verticalTimelineHeightPx = coordinates.size.height.toFloat().coerceAtLeast(1f)
                    }
                    .pointerInput(
                        displayedDurationTimeMs,
                        useChapterTimeline,
                        activeChapterStartMs,
                        timelineRangeMs,
                        durationMs,
                        verticalTimelineHeightPx
                    ) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                val target = resolveVerticalTimelineTarget(offset.y)
                                pendingSeekTargetMs = target
                                onPreviewPositionChanged(target)
                            },
                            onVerticalDrag = { change, _ ->
                                val target = resolveVerticalTimelineTarget(change.position.y)
                                pendingSeekTargetMs = target
                                onPreviewPositionChanged(target)
                                change.consume()
                            },
                            onDragEnd = {
                                pendingSeekTargetMs?.let(onSeekManual)
                                pendingSeekTargetMs = null
                                onPreviewPositionChanged(null)
                            },
                            onDragCancel = {
                                pendingSeekTargetMs = null
                                onPreviewPositionChanged(null)
                            }
                        )
                    }
            ) {
                val ratio = if (sliderMax > 0f) (sliderValue / sliderMax).coerceIn(0f, 1f) else 0f
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(ratio)
                        .background(Color(0xFF3E6E9C), RoundedCornerShape(10.dp))
                )
            }
            Text(
                text = formatBookTime(displayedLeftTimeMs),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier.clickable(onClick = onRequestTimeEdit)
            )
            Spacer(modifier = Modifier.weight(0.9f))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_overlay_previous),
                        contentDescription = stringResource(R.string.controller_previous)
                    )
                }
                IconButton(onClick = onPlayPause, modifier = Modifier.size(44.dp)) {
                    Icon(
                        painter = painterResource(
                            id = if (isPlaying) R.drawable.ic_overlay_pause else R.drawable.ic_overlay_play
                        ),
                        contentDescription = if (isPlaying) {
                            stringResource(R.string.common_pause)
                        } else {
                            stringResource(R.string.common_play)
                        }
                    )
                }
                IconButton(onClick = onNext, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_overlay_next),
                        contentDescription = stringResource(R.string.controller_next)
                    )
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
        addStyle(
            SpanStyle(
                background = Color(0x66A0A0A0)
            ),
            start,
            endExclusive
        )
    }
}

private data class SubtitleDisplayTransform(
    val text: String,
    val sourceToDisplay: IntArray,
    val displayToSource: IntArray
)

private const val BOOK_VERTICAL_ROWS_PER_COLUMN = 12

private fun transformSubtitleForWritingMode(
    sourceText: String,
    mode: FloatingSubtitleWritingMode,
    rowsPerColumnHint: Int = BOOK_VERTICAL_ROWS_PER_COLUMN
): SubtitleDisplayTransform {
    if (sourceText.isEmpty()) {
        return SubtitleDisplayTransform(
            text = "",
            sourceToDisplay = IntArray(0),
            displayToSource = IntArray(0)
        )
    }
    if (mode == FloatingSubtitleWritingMode.HORIZONTAL) {
        val identity = IntArray(sourceText.length) { it }
        return SubtitleDisplayTransform(
            text = sourceText,
            sourceToDisplay = identity,
            displayToSource = identity.copyOf()
        )
    }

    val rowsPerColumn = rowsPerColumnHint.coerceAtLeast(2)
    val display = StringBuilder(sourceText.length * 2)
    val sourceToDisplay = IntArray(sourceText.length) { 0 }
    val displayToSource = ArrayList<Int>(sourceText.length * 2)

    val paragraphs = ArrayList<List<Int>>()
    val newlineSourceIndices = ArrayList<Int>()
    var current = ArrayList<Int>()
    for (index in sourceText.indices) {
        val ch = sourceText[index]
        if (ch == '\n') {
            paragraphs.add(current)
            newlineSourceIndices.add(index)
            current = ArrayList()
        } else {
            current.add(index)
        }
    }
    paragraphs.add(current)

    var fallbackSource = 0
    paragraphs.forEachIndexed { paragraphIndex, indices ->
        if (indices.isEmpty()) {
            if (display.isNotEmpty()) {
                display.append('\n')
                displayToSource.add(fallbackSource)
            }
            return@forEachIndexed
        }

        val columns = ArrayList<List<Int>>()
        var start = 0
        while (start < indices.size) {
            val end = (start + rowsPerColumn).coerceAtMost(indices.size)
            columns.add(indices.subList(start, end))
            start = end
        }

        for (row in 0 until rowsPerColumn) {
            var rowHasGlyph = false
            val rowStart = display.length

            for (columnIndex in columns.lastIndex downTo 0) {
                val column = columns[columnIndex]
                if (row < column.size) {
                    val sourceIndex = column[row]
                    val normalized = when (sourceText[sourceIndex]) {
                        ' ', '\t' -> '\u3000'
                        else -> sourceText[sourceIndex]
                    }
                    sourceToDisplay[sourceIndex] = display.length
                    display.append(normalized)
                    displayToSource.add(sourceIndex)
                    fallbackSource = sourceIndex
                    rowHasGlyph = true
                } else {
                    display.append('\u3000')
                    displayToSource.add(fallbackSource)
                }
            }

            if (rowHasGlyph) {
                display.append('\n')
                displayToSource.add(fallbackSource)
            } else {
                display.setLength(rowStart)
                while (displayToSource.size > rowStart) {
                    displayToSource.removeAt(displayToSource.lastIndex)
                }
                break
            }
        }

        if (display.isNotEmpty() && display.last() == '\n') {
            display.setLength(display.length - 1)
            displayToSource.removeAt(displayToSource.lastIndex)
        }

        if (paragraphIndex < paragraphs.lastIndex) {
            val sourceNewlineIndex = newlineSourceIndices.getOrNull(paragraphIndex)
            if (sourceNewlineIndex != null) {
                sourceToDisplay[sourceNewlineIndex] = display.length
            }
            display.append('\n')
            displayToSource.add(fallbackSource)
        }
    }

    return SubtitleDisplayTransform(
        text = display.toString(),
        sourceToDisplay = sourceToDisplay,
        displayToSource = displayToSource.toIntArray()
    )
}

private fun mapSourceRangeToDisplayRange(
    sourceRange: IntRange?,
    sourceToDisplay: IntArray
): IntRange? {
    val range = sourceRange ?: return null
    if (sourceToDisplay.isEmpty()) return null
    val maxIndex = sourceToDisplay.size - 1
    val start = range.first.coerceIn(0, maxIndex)
    val end = range.last.coerceIn(start, maxIndex)
    val displayStart = sourceToDisplay[start]
    val displayEnd = sourceToDisplay[end]
    return if (displayStart <= displayEnd) {
        displayStart..displayEnd
    } else {
        displayEnd..displayStart
    }
}

@Composable
private fun ReaderLookupClickableSubtitle(
    text: AnnotatedString,
    selectedRange: IntRange?,
    style: androidx.compose.ui.text.TextStyle,
    autoScrollProgress: Float? = null,
    modifier: Modifier = Modifier,
    onSelectedRangeAnchorChanged: ((ReaderLookupAnchor?) -> Unit)? = null,
    onTextTap: (offset: Int, anchor: ReaderLookupAnchor) -> Unit
) {
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    var textWindowOrigin by remember { mutableStateOf(Offset.Zero) }
    var viewportWidthPx by remember { mutableStateOf(0) }
    val scrollState = rememberScrollState()
    val lineWidthPx = remember(textLayoutResult) {
        textLayoutResult?.let { layout ->
            if (layout.lineCount > 0) {
                (layout.getLineRight(0) - layout.getLineLeft(0)).coerceAtLeast(0f)
            } else {
                0f
            }
        } ?: 0f
    }
    LaunchedEffect(autoScrollProgress, viewportWidthPx, lineWidthPx, text.text) {
        val progress = autoScrollProgress ?: return@LaunchedEffect
        val maxScroll = (lineWidthPx - viewportWidthPx.toFloat()).coerceAtLeast(0f)
        val target = (maxScroll * progress.coerceIn(0f, 1f)).roundToInt()
        scrollState.scrollTo(target)
    }

    LaunchedEffect(selectedRange, textLayoutResult, textWindowOrigin, scrollState.value, text.text) {
        val callback = onSelectedRangeAnchorChanged ?: return@LaunchedEffect
        val layout = textLayoutResult ?: run {
            callback(null)
            return@LaunchedEffect
        }
        val range = selectedRange ?: run {
            callback(null)
            return@LaunchedEffect
        }
        val textLength = text.length
        if (textLength <= 0) {
            callback(null)
            return@LaunchedEffect
        }
        val start = range.first.coerceIn(0, textLength - 1)
        val end = range.last.coerceIn(start, textLength - 1)
        val localRects = buildList {
            for (i in start..end) {
                val box = layout.getBoundingBox(i)
                if (!box.isEmpty) {
                    add(
                        Rect(
                            left = textWindowOrigin.x + box.left - scrollState.value,
                            top = textWindowOrigin.y + box.top,
                            right = textWindowOrigin.x + box.right - scrollState.value,
                            bottom = textWindowOrigin.y + box.bottom
                        )
                    )
                }
            }
        }
        callback(
            if (localRects.isEmpty()) null else ReaderLookupAnchor(rects = mergeRectsByLineShared(localRects))
        )
    }

    Text(
        text = text,
        style = style,
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                textWindowOrigin = bounds.topLeft
                viewportWidthPx = coordinates.size.width
            }
            .then(
                if (autoScrollProgress != null) {
                    Modifier.horizontalScroll(scrollState, enabled = false)
                } else {
                    Modifier
                }
            )
            .pointerInput(onTextTap) {
                detectTapGestures { tapOffset ->
                    val layout = textLayoutResult ?: return@detectTapGestures
                    val textLength = text.length
                    if (textLength <= 0) return@detectTapGestures
                    val contentTapOffset = Offset(
                        x = tapOffset.x + scrollState.value.toFloat(),
                        y = tapOffset.y
                    )
                    var offset = layout.getOffsetForPosition(contentTapOffset)
                        .coerceIn(0, textLength - 1)
                    if (offset > 0) {
                        val previousBox = layout.getBoundingBox(offset - 1)
                        if (
                            contentTapOffset.x >= previousBox.left &&
                            contentTapOffset.x <= previousBox.right &&
                            contentTapOffset.y >= previousBox.top &&
                            contentTapOffset.y <= previousBox.bottom
                        ) {
                            offset -= 1
                        }
                    }
                    val box = layout.getBoundingBox(offset)
                    val line = layout.getLineForOffset(offset)
                    val lineTop = layout.getLineTop(line).toFloat()
                    val lineBottom = layout.getLineBottom(line).toFloat()
                    val anchor = ReaderLookupAnchor(
                        rects = listOf(
                            Rect(
                                left = textWindowOrigin.x + box.left - scrollState.value,
                                right = textWindowOrigin.x + box.right - scrollState.value,
                                top = textWindowOrigin.y + lineTop,
                                bottom = textWindowOrigin.y + lineBottom
                            )
                        )
                    )
                    Log.d(
                        BOOK_LOOKUP_ANCHOR_LOG_TAG,
                        "tap offset=$offset tap=${tapOffset.x.roundToInt()},${tapOffset.y.roundToInt()} box=${formatRectForLog(anchor.boundingRectOrNull())} scrollX=${scrollState.value}"
                    )
                    onTextTap(offset, anchor)
                }
            },
        softWrap = autoScrollProgress == null,
        maxLines = if (autoScrollProgress != null) 1 else Int.MAX_VALUE,
        overflow = TextOverflow.Clip,
        onTextLayout = { textLayoutResult = it }
    )
}

@Composable
private fun VerticalSubtitleText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    rowsPerColumn: Int,
    compactVerticalLayout: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textColor = if (style.color == Color.Unspecified) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        style.color
    }
    val textSizePx = with(density) {
        if (style.fontSize.isSpecified) style.fontSize.toPx() else 22.sp.toPx()
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            VerticalSubtitleView(context).apply {
                isClickable = onClick != null
                setOnClickListener { onClick?.invoke() }
            }
        },
        update = { view ->
            view.isClickable = onClick != null
            view.setOnClickListener { onClick?.invoke() }
            view.bind(text, textColor.toArgb(), textSizePx)
        }
    )
}

@Composable
private fun rememberVerticalCueWidth(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    rowsPerColumn: Int,
    compact: Boolean = false
): Dp {
    val density = LocalDensity.current
    return remember(text, style.fontSize, rowsPerColumn, compact, density) {
        val fontSizeDp = with(density) {
            if (style.fontSize.isSpecified) style.fontSize.toDp() else 22.sp.toDp()
        }
        val columns = ceil(text.length.toFloat() / rowsPerColumn.coerceAtLeast(1).toFloat())
            .toInt()
            .coerceAtLeast(1)
        if (compact) {
            (fontSizeDp * (columns * BOOK_VERTICAL_COLUMN_WIDTH_FACTOR) + 8.dp + BOOK_VERTICAL_CUE_GLYPH_SAFETY_WIDTH)
                .coerceIn(36.dp, 420.dp)
        } else {
            (fontSizeDp * (columns * BOOK_VERTICAL_COLUMN_WIDTH_FACTOR) + 40.dp + BOOK_VERTICAL_CUE_GLYPH_SAFETY_WIDTH)
                .coerceIn(72.dp, 420.dp)
        }
    }
}

@Composable
private fun VerticalLookupClickableSubtitle(
    sourceText: String,
    style: androidx.compose.ui.text.TextStyle,
    rowsPerColumn: Int,
    selectedSourceRange: IntRange? = null,
    compactVerticalLayout: Boolean = false,
    lookupEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    onDisplayTap: (() -> Unit)? = null,
    onSelectedRangeAnchorChanged: ((ReaderLookupAnchor?) -> Unit)? = null,
    onTextTap: (sourceOffset: Int, anchor: ReaderLookupAnchor?) -> Unit
) {
    val density = LocalDensity.current
    val textColor = if (style.color == Color.Unspecified) {
        MaterialTheme.colorScheme.onSurface
    } else {
        style.color
    }
    val textSizePx = with(density) {
        if (style.fontSize.isSpecified) style.fontSize.toPx() else 28.sp.toPx()
    }
    val lineHeightPx = with(density) {
        if (style.lineHeight.isSpecified) style.lineHeight.toPx() else textSizePx
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            VerticalLookupSubtitleView(context).apply {
                isClickable = true
            }
        },
        update = { view ->
            view.bind(
                newText = sourceText,
                color = textColor.toArgb(),
                sizePx = textSizePx,
                lineHeightPx = lineHeightPx,
                rowsPerColumn = rowsPerColumn,
                selectedSourceRange = selectedSourceRange,
                onSelectionAnchorChanged = onSelectedRangeAnchorChanged,
                onTap = { sourceOffset, rectInWindow ->
                    if (!lookupEnabled) {
                        onDisplayTap?.invoke()
                    } else {
                        onTextTap(
                            sourceOffset,
                            ReaderLookupAnchor(
                                rects = listOf(
                                    Rect(
                                        left = rectInWindow.left,
                                        top = rectInWindow.top,
                                        right = rectInWindow.right,
                                        bottom = rectInWindow.bottom
                                    )
                                )
                            )
                        )
                    }
                }
            )
        }
    )
}

private class VerticalSubtitleView(context: Context) : android.view.View(context) {
    private var content: String = ""
    private val paint = TextPaint().apply {
        isAntiAlias = true
    }
    private var cachedLayout: VerticalSubtitleLayout? = null
    private var cachedHeight: Int = -1
    private var cachedTextSize: Float = Float.NaN

    fun bind(newText: String, color: Int, sizePx: Float) {
        val normalizedText = normalizeVerticalPunctuation(newText)
        val changed = content != normalizedText ||
            paint.color != color ||
            paint.textSize != sizePx
        if (!changed) return
        content = normalizedText
        paint.color = color
        paint.textSize = sizePx
        cachedLayout = null
        cachedHeight = -1
        requestLayout()
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val layout = obtainLayout(height) ?: return
        VerticalSubtitleLayoutEngine.draw(canvas, paint, layout, width, height)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val measuredHeight = if (heightSize > 0) {
            heightSize
        } else {
            (paint.textSize * 12f).roundToInt().coerceAtLeast(1)
        }
        val layout = obtainLayout(measuredHeight)
        val desiredWidth = layout?.contentWidth()?.let { ceil(it.toDouble()).toInt() }?.coerceAtLeast(1) ?: 1
        val measuredWidth = resolveSize(desiredWidth, widthMeasureSpec)
        setMeasuredDimension(measuredWidth, resolveSize(measuredHeight, heightMeasureSpec))
    }

    private fun obtainLayout(targetHeight: Int): VerticalSubtitleLayout? {
        if (content.isBlank() || targetHeight <= 0) return null
        if (cachedLayout != null && cachedHeight == targetHeight && cachedTextSize == paint.textSize) {
            return cachedLayout
        }
        cachedLayout = VerticalSubtitleLayoutEngine.build(
            content,
            paint,
            targetHeight,
            paint.textSize.coerceAtLeast(1f)
        )
        cachedHeight = targetHeight
        cachedTextSize = paint.textSize
        return cachedLayout
    }
}

private class VerticalLookupSubtitleView(context: Context) : android.view.View(context) {
    private var content: String = ""
    private val paint = TextPaint().apply {
        isAntiAlias = true
        textAlign = android.graphics.Paint.Align.LEFT
    }
    private var lineHeightPx: Float = 1f
    private var rowsPerColumn: Int = BOOK_VERTICAL_ROWS_PER_COLUMN
    private var selectedSourceRange: IntRange? = null
    private var onSelectionAnchorChanged: ((ReaderLookupAnchor?) -> Unit)? = null
    private var onTap: ((sourceOffset: Int, rectInWindow: android.graphics.RectF) -> Unit)? = null
    private var downEventTimeMs: Long = 0L
    private var lastSelectionDebugSignature: String? = null
    private var lastSelectionAnchorSignature: String? = null
    private var lastLayoutMetricsSignature: String? = null
    private var debugLastTap: VerticalTapResolved? = null
    private var preferredAnchorRectInWindow: Rect? = null
    private var cachedGridModel: VerticalGridModel? = null
    private var cachedGridHeight: Int = -1
    private var cachedGridText: String = ""
    private var cachedGridTextSize: Float = Float.NaN
    private var cachedVerticalLayout: VerticalSubtitleLayout? = null
    private var cachedVerticalLayoutHeight: Int = -1

    fun bind(
        newText: String,
        color: Int,
        sizePx: Float,
        lineHeightPx: Float,
        rowsPerColumn: Int,
        selectedSourceRange: IntRange?,
        onSelectionAnchorChanged: ((ReaderLookupAnchor?) -> Unit)?,
        onTap: (sourceOffset: Int, rectInWindow: android.graphics.RectF) -> Unit
    ) {
        val normalizedText = normalizeVerticalPunctuation(newText)
        val changed = content != normalizedText ||
            paint.color != color ||
            paint.textSize != sizePx ||
            this.lineHeightPx != lineHeightPx ||
            this.rowsPerColumn != rowsPerColumn ||
            this.selectedSourceRange != selectedSourceRange
        this.onTap = onTap
        this.selectedSourceRange = selectedSourceRange
        this.onSelectionAnchorChanged = onSelectionAnchorChanged
        if (!changed) return
        content = normalizedText
        paint.color = color
        paint.textSize = sizePx
        this.lineHeightPx = lineHeightPx.coerceAtLeast(1f)
        this.rowsPerColumn = rowsPerColumn.coerceAtLeast(2)
        lastSelectionDebugSignature = null
        lastSelectionAnchorSignature = null
        lastLayoutMetricsSignature = null
        preferredAnchorRectInWindow = null
        clearGridCache()
        clearVerticalLayoutCache()
        requestLayout()
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        logLayoutMetricsIfNeeded()
        drawSelectionBackground(canvas)
        drawVerticalLayoutText(canvas)
        if (BOOK_VERTICAL_TAP_DEBUG_OVERLAY) {
            drawTapDebugOverlay(canvas)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val measuredHeight = if (heightSize > 0) {
            heightSize
        } else {
            (effectiveCellHeightPx() * rowsPerColumn).roundToInt().coerceAtLeast(1)
        }
        val model = buildGridModel(measuredHeight)
        val desiredWidth = model?.let {
            ceil((it.columnCount * it.cellWidth).toDouble()).toInt().coerceAtLeast(1)
        } ?: 1
        val measuredWidth = resolveSize(desiredWidth, widthMeasureSpec)
        setMeasuredDimension(measuredWidth, resolveSize(measuredHeight, heightMeasureSpec))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val model = buildGridModel(height) ?: return true
        if (model.cells.isEmpty()) return true
        val resolved = resolveOffsetForEvent(event.x, event.y, model) ?: return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downEventTimeMs = event.eventTime
                return true
            }
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_CANCEL -> return true
            MotionEvent.ACTION_UP -> {
                val pressDuration = (event.eventTime - downEventTimeMs).coerceAtLeast(0L)
                val tapTimeout = ViewConfiguration.getTapTimeout().toLong()
                val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
                if (pressDuration > tapTimeout && pressDuration >= longPressTimeout) {
                    return true
                }
                val finalOffset = resolved.sourceOffset
                val rectInWindow = resolved.rectInWindow
                val tappedChar = content.getOrNull(finalOffset)?.toString().orEmpty()
                Log.d(
                    BOOK_LOOKUP_SELECTION_LOG_TAG,
                    "verticalViewTap x=${event.x.roundToInt()} y=${event.y.roundToInt()} row=${resolved.row} col=${resolved.column} logical=${resolved.logical} displayOffset=$finalOffset char='$tappedChar' rect=${rectInWindow.left.roundToInt()},${rectInWindow.top.roundToInt()},${rectInWindow.right.roundToInt()},${rectInWindow.bottom.roundToInt()}"
                )
                if (BOOK_VERTICAL_TAP_DEBUG_OVERLAY) {
                    debugLastTap = resolved
                    invalidate()
                }
                preferredAnchorRectInWindow = Rect(
                    left = rectInWindow.left,
                    top = rectInWindow.top,
                    right = rectInWindow.right,
                    bottom = rectInWindow.bottom
                )
                onTap?.invoke(finalOffset, rectInWindow)
                return true
            }
        }
        return true
    }

    private data class VerticalTapResolved(
        val sourceOffset: Int,
        val logical: Int,
        val row: Int,
        val column: Int,
        val rectInWindow: android.graphics.RectF
    )

    private data class VerticalGridCell(
        val sourceOffset: Int,
        val logical: Int,
        val row: Int,
        val column: Int
    )

    private data class VerticalGridModel(
        val cells: List<VerticalGridCell>,
        val columnCount: Int,
        val maxRows: Int,
        val cellWidth: Float,
        val cellHeight: Float
    )

    private fun clearGridCache() {
        cachedGridModel = null
        cachedGridHeight = -1
        cachedGridText = ""
        cachedGridTextSize = Float.NaN
    }

    private fun clearVerticalLayoutCache() {
        cachedVerticalLayout = null
        cachedVerticalLayoutHeight = -1
    }

    private fun obtainVerticalLayout(targetHeight: Int): VerticalSubtitleLayout? {
        if (content.isBlank() || targetHeight <= 0) return null
        if (cachedVerticalLayout != null &&
            cachedVerticalLayoutHeight == targetHeight
        ) {
            return cachedVerticalLayout
        }
        cachedVerticalLayout = VerticalSubtitleLayoutEngine.build(
            content,
            paint,
            targetHeight,
            effectiveCellHeightPx()
        )
        cachedVerticalLayoutHeight = targetHeight
        return cachedVerticalLayout
    }

    private fun resolveOffsetForEvent(x: Float, y: Float, model: VerticalGridModel): VerticalTapResolved? {
        val layout = obtainVerticalLayout(height) ?: return null
        val hit = VerticalSubtitleLayoutEngine.hitTest(
            x = x,
            y = y,
            viewWidth = width,
            viewHeight = height,
            layout = layout,
            paint = paint
        ) ?: return null
        val location = IntArray(2)
        getLocationInWindow(location)
        val rectInWindow = android.graphics.RectF(
            location[0] + hit.rect.left,
            location[1] + hit.rect.top,
            location[0] + hit.rect.right,
            location[1] + hit.rect.bottom
        )
        return VerticalTapResolved(
            sourceOffset = hit.sourceOffset,
            logical = hit.logical,
            row = hit.row,
            column = hit.column,
            rectInWindow = rectInWindow
        )
    }

    private fun buildGridModel(viewHeight: Int): VerticalGridModel? {
        if (content.isBlank() || viewHeight <= 0) return null
        if (cachedGridModel != null &&
            cachedGridHeight == viewHeight &&
            cachedGridText == content &&
            cachedGridTextSize == paint.textSize
        ) {
            return cachedGridModel
        }

        val layout = VerticalSubtitleLayoutEngine.build(
            content,
            paint,
            viewHeight,
            effectiveCellHeightPx()
        ) ?: return null
        val computed = VerticalGridModel(
            cells = layout.cells.map { cell ->
                VerticalGridCell(
                    sourceOffset = cell.sourceOffset,
                    logical = cell.logical,
                    row = cell.row,
                    column = cell.column
                )
            },
            columnCount = layout.columnCount,
            maxRows = layout.maxRows,
            cellWidth = layout.cellWidth,
            cellHeight = layout.cellHeight
        )

        cachedGridModel = computed
        cachedGridHeight = viewHeight
        cachedGridText = content
        cachedGridTextSize = paint.textSize
        return computed
    }

    private fun drawSelectionBackground(canvas: android.graphics.Canvas) {
        val range = selectedSourceRange ?: return
        val model = buildGridModel(height) ?: return
        if (model.cells.isEmpty()) return
        val highlightPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(0x66, 0xA0, 0xA0, 0xA0)
            style = android.graphics.Paint.Style.FILL
            isAntiAlias = true
        }
        val start = range.first.coerceAtMost(range.last)
        val end = range.last.coerceAtLeast(range.first)
        val selectedCellsForDebug = ArrayList<String>(8)
        val selectedRectsInWindow = ArrayList<Rect>(8)
        val layout = obtainVerticalLayout(height) ?: return
        val selectionRects = VerticalSubtitleLayoutEngine.selectionRects(start..end, width, height, layout, paint)
        val location = IntArray(2)
        getLocationInWindow(location)
        for (cell in model.cells) {
            val sourceIndex = cell.sourceOffset
            if (sourceIndex < start || sourceIndex > end) continue
            if (selectedCellsForDebug.size < 24) {
                val ch = content.getOrNull(sourceIndex)?.toString().orEmpty()
                selectedCellsForDebug.add(
                    "s=$sourceIndex('$ch')->L${cell.logical}(r${cell.row},c${cell.column})"
                )
            }
        }

        selectionRects.forEach { rect ->
            canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom, highlightPaint)
            if (selectedRectsInWindow.size < 128) {
                selectedRectsInWindow.add(
                    Rect(
                        left = location[0] + rect.left,
                        top = location[1] + rect.top,
                        right = location[0] + rect.right,
                        bottom = location[1] + rect.bottom
                    )
                )
            }
        }

        publishSelectionAnchor(selectedRectsInWindow, start..end)
        logSelectionDebugIfNeeded(
            range = start..end,
            model = model,
            selectedCells = selectedCellsForDebug
        )
    }

    private fun drawTapDebugOverlay(canvas: android.graphics.Canvas) {
        val model = buildGridModel(height) ?: return
        if (model.cells.isEmpty()) return

        val gridPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(70, 40, 120, 220)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1f
            isAntiAlias = true
        }
        val tapPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(220, 230, 60, 60)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
        val labelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(200, 30, 30, 30)
            textSize = (paint.textSize * 0.24f).coerceAtLeast(10f)
            isAntiAlias = true
        }

        for (cell in model.cells) {
            val left = (width - (cell.column + 1) * model.cellWidth).coerceAtLeast(0f)
            val top = (cell.row * model.cellHeight).coerceAtLeast(0f)
            val right = (left + model.cellWidth).coerceAtMost(width.toFloat())
            val bottom = (top + model.cellHeight).coerceAtMost(height.toFloat())
            canvas.drawRect(left, top, right, bottom, gridPaint)
        }

        debugLastTap?.let { tap ->
            val left = (width - (tap.column + 1) * model.cellWidth).coerceAtLeast(0f)
            val top = (tap.row * model.cellHeight).coerceAtLeast(0f)
            val right = (left + model.cellWidth).coerceAtMost(width.toFloat())
            val bottom = (top + model.cellHeight).coerceAtMost(height.toFloat())
            canvas.drawRect(left, top, right, bottom, tapPaint)
            val ch = content.getOrNull(tap.sourceOffset)?.toString().orEmpty()
            val label = "L${tap.logical} S${tap.sourceOffset} '$ch'"
            canvas.drawText(label, 8f, (height - 8f).coerceAtLeast(labelPaint.textSize + 2f), labelPaint)
        }
    }

    private fun drawVerticalLayoutText(canvas: android.graphics.Canvas) {
        val layout = obtainVerticalLayout(height) ?: return
        VerticalSubtitleLayoutEngine.draw(canvas, paint, layout, width, height)
    }

    private fun publishSelectionAnchor(rects: List<Rect>, range: IntRange) {
        val callback = onSelectionAnchorChanged ?: return
        val orderedRects = reorderRectsWithPreferredFirst(rects, preferredAnchorRectInWindow)
        val signature = buildString {
            append(range.first).append('-').append(range.last).append('|').append(orderedRects.size)
            orderedRects.take(6).forEach {
                append('|')
                    .append(it.left.roundToInt()).append(',')
                    .append(it.top.roundToInt()).append(',')
                    .append(it.right.roundToInt()).append(',')
                    .append(it.bottom.roundToInt())
            }
        }
        if (signature == lastSelectionAnchorSignature) return
        lastSelectionAnchorSignature = signature
        callback(if (orderedRects.isEmpty()) null else ReaderLookupAnchor(rects = orderedRects))
    }

    private fun reorderRectsWithPreferredFirst(rects: List<Rect>, preferred: Rect?): List<Rect> {
        if (rects.isEmpty() || preferred == null) return rects
        val bestIndex = rects.indices.maxByOrNull { idx ->
            val rect = rects[idx]
            val overlap = overlapArea(rect, preferred)
            if (overlap > 0f) {
                overlap + 10_000_000f
            } else {
                -centerDistance(rect, preferred)
            }
        } ?: return rects
        if (bestIndex == 0) return rects
        return buildList(rects.size) {
            add(rects[bestIndex])
            rects.forEachIndexed { idx, rect ->
                if (idx != bestIndex) add(rect)
            }
        }
    }

    private fun overlapArea(a: Rect, b: Rect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val w = (right - left).coerceAtLeast(0f)
        val h = (bottom - top).coerceAtLeast(0f)
        return w * h
    }

    private fun centerDistance(a: Rect, b: Rect): Float {
        val ax = (a.left + a.right) * 0.5f
        val ay = (a.top + a.bottom) * 0.5f
        val bx = (b.left + b.right) * 0.5f
        val by = (b.top + b.bottom) * 0.5f
        val dx = ax - bx
        val dy = ay - by
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun effectiveCellHeightPx(): Float {
        return paint.textSize.coerceAtLeast(1f)
    }

    private fun logLayoutMetricsIfNeeded() {
        val model = buildGridModel(height)
        val dynamicRows = model?.maxRows ?: rowsPerColumn.coerceAtLeast(2)
        val dynamicColumns = model?.columnCount ?: 0
        val signature = "${width}x$height|${paint.textSize.roundToInt()}|$rowsPerColumn|$dynamicRows|$dynamicColumns|${content.length}"
        if (signature == lastLayoutMetricsSignature) return
        lastLayoutMetricsSignature = signature
        Log.d(
            BOOK_LOOKUP_SELECTION_LOG_TAG,
            "verticalLayoutMetrics view=${width}x$height textSize=${paint.textSize.roundToInt()} hintRows=$rowsPerColumn dynamicRows=$dynamicRows columns=$dynamicColumns contentLen=${content.length}"
        )
    }

    private fun logSelectionDebugIfNeeded(
        range: IntRange,
        model: VerticalGridModel,
        selectedCells: List<String>
    ) {
        val signature = "${range.first}-${range.last}|${model.maxRows}|${model.columnCount}|${content.length}"
        if (signature == lastSelectionDebugSignature) return
        lastSelectionDebugSignature = signature
        val preview = content
            .replace("\n", "↩")
            .let { if (it.length > 120) it.take(120) + "…" else it }
        Log.d(
            BOOK_LOOKUP_SELECTION_LOG_TAG,
            "verticalSelectionDebug range=${range.first}..${range.last} rows=${model.maxRows} cols=${model.columnCount} view=${width}x$height cell=${model.cellWidth.roundToInt()}x${model.cellHeight.roundToInt()} mapperLen=${model.cells.size} contentLen=${content.length} preview='$preview' cells=${selectedCells.joinToString(" | ")}"
        )
    }
}

private fun normalizeVerticalPunctuation(text: String): String {
    if (text.isEmpty()) return text
    var changed = false
    val out = StringBuilder(text.length)
    text.forEach { ch ->
        val mapped = when (ch) {
            '\u3001' -> '\uFE11' // ︑
            '\u3002' -> '\uFE12' // ︒
            ',' -> '\uFE10' // ︐
            '.' -> '\uFE12' // ︒
            ':' -> '\uFE13' // ︓
            ';' -> '\uFE14' // ︔
            '!' -> '\uFE15' // ︕
            '?' -> '\uFE16' // ︖
            '(' -> '\uFE35' // ︵
            ')' -> '\uFE36' // ︶
            '[' -> '\uFE39' // ︹
            ']' -> '\uFE3A' // ︺
            '{' -> '\uFE37' // ︷
            '}' -> '\uFE38' // ︸
            '<' -> '\uFE3F' // ︿
            '>' -> '\uFE40' // ﹀
            '\u2025' // ‥
            -> '\uFE30' // ︰
            '\u2026', // …
            '\u22EF'  // ⋯
            -> '\uFE19' // ︙ Vertical ellipsis
            '\u203B' // ※
            -> '\uFE61' // ﹡
            else -> ch
        }
        if (mapped != ch) changed = true
        out.append(mapped)
    }
    return if (changed) out.toString() else text
}


private fun mergeRects(rects: List<Rect>): Rect {
    return Rect(
        left = rects.minOf { it.left },
        top = rects.minOf { it.top },
        right = rects.maxOf { it.right },
        bottom = rects.maxOf { it.bottom }
    )
}

private fun formatRectForLog(rect: Rect?): String {
    if (rect == null) return "null"
    return "${rect.left.roundToInt()},${rect.top.roundToInt()},${rect.right.roundToInt()},${rect.bottom.roundToInt()}"
}

private fun formatRangeForLog(range: IntRange?): String {
    return range?.let { "${it.first}..${it.last}" } ?: "null"
}

private fun ReaderSelectionData.anchorRectForSourceRange(
    startOffset: Int,
    endExclusive: Int,
): ReaderSelectionRect {
    val matchingRects = textRects
        .filter { it.endOffset > startOffset && it.startOffset < endExclusive }
        .map { it.rect }
        .filter { it.width > 0.0 && it.height > 0.0 }
    if (matchingRects.isEmpty()) return rect

    val clickedCenterX = rect.x + rect.width / 2.0
    val clickedCenterY = rect.y + rect.height / 2.0
    val verticalGroups = matchingRects
        .sortedWith(compareBy<ReaderSelectionRect> { it.x }.thenBy { it.y })
        .fold(mutableListOf<MutableList<ReaderSelectionRect>>()) { groups, item ->
            val group = groups.firstOrNull { existing ->
                val first = existing.first()
                abs(first.x - item.x) < 2.0 && abs(first.width - item.width) < 3.0
            }
            if (group != null) {
                group.add(item)
            } else {
                groups.add(mutableListOf(item))
            }
            groups
        }
    val clickedGroup = verticalGroups.firstOrNull { group ->
        group.any { item ->
            clickedCenterX >= item.x - 1.0 &&
                clickedCenterX <= item.x + item.width + 1.0 &&
                clickedCenterY >= item.y - 1.0 &&
                clickedCenterY <= item.y + item.height + 1.0
        }
    } ?: verticalGroups.minByOrNull { group ->
        val first = group.first()
        abs((first.x + first.width / 2.0) - clickedCenterX)
    } ?: return rect
    return clickedGroup.mergeSelectionRects()
}

private fun List<ReaderSelectionRect>.mergeSelectionRects(): ReaderSelectionRect {
    val left = minOf { it.x }
    val top = minOf { it.y }
    val right = maxOf { it.x + it.width }
    val bottom = maxOf { it.y + it.height }
    return ReaderSelectionRect(
        x = left,
        y = top,
        width = right - left,
        height = bottom - top,
    )
}

private fun ReaderLookupAnchor?.boundingRectOrNull(): Rect? {
    val rects = this?.rects?.filter { !it.isEmpty } ?: return null
    if (rects.isEmpty()) return null
    return mergeRects(rects)
}

private fun ReaderLookupAnchor?.toSelectionRects(density: Float): List<ReaderSelectionRect> {
    val densityScale = density.coerceAtLeast(0.1f)
    return this?.rects
        ?.filter { !it.isEmpty }
        ?.map { rect ->
            ReaderSelectionRect(
                x = (rect.left / densityScale).toDouble(),
                y = (rect.top / densityScale).toDouble(),
                width = ((rect.right - rect.left) / densityScale).coerceAtLeast(1f).toDouble(),
                height = ((rect.bottom - rect.top) / densityScale).coerceAtLeast(1f).toDouble()
            )
        }
        .orEmpty()
}

private fun ReaderLookupAnchor?.expandForSelectionText(
    selectionText: String?,
    writingMode: FloatingSubtitleWritingMode? = null
): ReaderLookupAnchor? {
    val anchor = this ?: return null
    if (writingMode != FloatingSubtitleWritingMode.VERTICAL_RTL) return anchor
    val rects = anchor.rects.filter { !it.isEmpty }
    if (rects.size != 1) return anchor
    val text = selectionText?.trim().orEmpty()
    val textLength = text.length.coerceAtMost(12)
    if (textLength <= 1) return anchor
    val rect = rects.first()
    val charHeight = (rect.bottom - rect.top).coerceAtLeast(1f)
    val extra = (textLength - 1).coerceAtLeast(0)
    val verticalExtra = (extra * charHeight * 0.5f).coerceAtLeast(0f)
    val expanded = Rect(
        left = rect.left - 3f,
        top = rect.top - verticalExtra - 4f,
        right = rect.right + 3f,
        bottom = rect.bottom + verticalExtra + 4f
    )
    return ReaderLookupAnchor(rects = listOf(expanded))
}

private val HOSHI_SENTENCE_DELIMITERS = setOf('\u3002', '\uFF01', '\uFF1F', '.', '!', '?', '\n', '\r')
private data class SentenceBounds(val start: Int, val endExclusive: Int)

private fun findSentenceBoundsLikeHoshi(
    text: String,
    anchorIndex: Int
): SentenceBounds {
    if (text.isEmpty()) return SentenceBounds(0, 0)
    val normalizedAnchor = anchorIndex.coerceIn(0, text.lastIndex)
    var start = 0
    for (index in (normalizedAnchor - 1) downTo 0) {
        if (text[index] in HOSHI_SENTENCE_DELIMITERS) {
            start = index + 1
            break
        }
    }

    var endExclusive = text.length
    for (index in normalizedAnchor until text.length) {
        if (text[index] in HOSHI_SENTENCE_DELIMITERS) {
            endExclusive = index + 1
            while (endExclusive < text.length) {
                val next = text[endExclusive]
                if (next.isLetterOrDigit() || Character.getType(next) == Character.OTHER_LETTER.toInt()) {
                    break
                }
                if (next !in setOf('\u300D', '\u300F', '\uFF09', '\u3011', '!', '?', '\uFF01', '\uFF1F')) {
                    break
                }
                endExclusive += 1
            }
            break
        }
    }
    return SentenceBounds(start, endExclusive)
}

private fun extractFullSentenceLikeHoshiFromCues(
    cues: List<ReaderSubtitleCue>,
    cueIndex: Int,
    anchorText: String?,
    selectedRangeInCue: IntRange?,
    rawAnchorOffsetInCue: Int?
): ReaderSentenceSelection {
    if (cues.isEmpty() || cueIndex !in cues.indices) return ReaderSentenceSelection("", 0..0)
    val cueStarts = IntArray(cues.size)
    val combined = buildString {
        cues.forEachIndexed { index, cue ->
            cueStarts[index] = length
            append(cue.text)
        }
    }.trim()
    if (combined.isBlank()) return ReaderSentenceSelection("", cueIndex..cueIndex)

    val localCueText = cues[cueIndex].text
    val localAnchor = anchorText?.trim().orEmpty()
    val localAnchorIndex = when {
        rawAnchorOffsetInCue != null -> rawAnchorOffsetInCue.coerceIn(0, localCueText.lastIndex.coerceAtLeast(0))
        selectedRangeInCue != null -> selectedRangeInCue.first.coerceIn(0, localCueText.lastIndex.coerceAtLeast(0))
        localAnchor.isNotBlank() -> localCueText.indexOf(localAnchor).takeIf { it >= 0 } ?: 0
        else -> 0
    }
    val globalAnchor = (cueStarts[cueIndex] + localAnchorIndex).coerceIn(0, combined.lastIndex)
    val bounds = findSentenceBoundsLikeHoshi(combined, globalAnchor)
    val sentenceText = combined.substring(bounds.start, bounds.endExclusive).trim()
    val leadingTrim = combined.substring(bounds.start, bounds.endExclusive).indexOf(sentenceText).takeIf { it >= 0 } ?: 0
    val sentenceStart = bounds.start + leadingTrim
    val sentenceEndExclusive = sentenceStart + sentenceText.length
    var firstCue = cueIndex
    var lastCue = cueIndex
    for (index in cues.indices) {
        val cueStart = cueStarts[index]
        val cueEndExclusive = cueStart + cues[index].text.length
        if (cueEndExclusive > sentenceStart) {
            firstCue = index
            break
        }
    }
    for (index in cues.indices.reversed()) {
        val cueStart = cueStarts[index]
        if (cueStart < sentenceEndExclusive) {
            lastCue = index
            break
        }
    }
    return ReaderSentenceSelection(
        text = sentenceText,
        cueRange = firstCue..lastCue
    )
}

internal fun createHoshiReaderSelectionFromCueTap(
    cueText: String,
    cueIndex: Int,
    cues: List<ReaderSubtitleCue>,
    offset: Int,
    anchorRect: Rect,
    density: Float = 1f
): ReaderSelectionData {
    val safeOffset = offset.coerceIn(0, cueText.lastIndex.coerceAtLeast(0))
    val scanSelection = selectLookupScanText(
        text = cueText,
        charOffset = safeOffset,
        maxLength = HOSHI_LOOKUP_SCAN_MAX_LENGTH
    )
    val scanStart = scanSelection?.range?.first ?: safeOffset
    val scanEnd = scanSelection?.range?.last?.plus(1) ?: (scanStart + HOSHI_LOOKUP_SCAN_MAX_LENGTH).coerceAtMost(cueText.length)
    val densityScale = density.coerceAtLeast(0.1f)
    val selectedText = cueText
        .substring(scanStart, scanEnd)
        .trim()
        .ifBlank { cueText.trim() }
    val sentenceSelection = extractFullSentenceLikeHoshiFromCues(
        cues = cues,
        cueIndex = cueIndex.coerceIn(0, cues.lastIndex.coerceAtLeast(0)),
        anchorText = null,
        selectedRangeInCue = null,
        rawAnchorOffsetInCue = safeOffset
    )
    return ReaderSelectionData(
        text = selectedText,
        sentence = sentenceSelection.text.ifBlank { cueText.trim() },
        rect = ReaderSelectionRect(
            x = (anchorRect.left / densityScale).toDouble(),
            y = (anchorRect.top / densityScale).toDouble(),
            width = ((anchorRect.right - anchorRect.left) / densityScale).coerceAtLeast(1f).toDouble(),
            height = ((anchorRect.bottom - anchorRect.top) / densityScale).coerceAtLeast(1f).toDouble()
        ),
        normalizedOffset = 0,
        sentenceOffset = scanSelection?.range?.first ?: scanStart
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
    glossaryFirstHtml: String? = null,
    dictionaryCss: String?,
    groupedDictionaries: List<GroupedLookupDictionary> = emptyList(),
    popupSelectionText: String? = null,
    sentenceOverride: String? = null
): AnkiExportResult {
    return addLookupDefinitionToAnkiShared(
        context = context,
        cueText = cue.text,
        cueStartMs = cue.startMs,
        cueEndMs = cue.endMs,
        audioUri = audioUri,
        lookupAudioUri = lookupAudioUri,
        bookTitle = bookTitle,
        entry = entry,
        definition = definition,
        glossaryFirstHtml = glossaryFirstHtml,
        dictionaryCss = dictionaryCss,
        groupedDictionaries = groupedDictionaries,
        popupSelectionText = popupSelectionText,
        sentenceOverride = sentenceOverride
    )
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

internal inline fun <T> withAnkiStep(step: String, block: () -> T): T {
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

private fun normalizeBookReaderPlaybackPosition(positionMs: Long, durationMs: Long): Long {
    return positionMs.coerceAtLeast(0L)
}

private fun swapSrtDocumentContents(
    resolver: ContentResolver,
    currentSrtUri: Uri,
    pickedSrtUri: Uri
): Boolean {
    return runCatching {
        val currentBytes = resolver.openInputStream(currentSrtUri)?.use { it.readBytes() }
            ?: error("read current srt failed")
        val pickedBytes = resolver.openInputStream(pickedSrtUri)?.use { it.readBytes() }
            ?: error("read picked srt failed")

        resolver.openOutputStream(currentSrtUri, "wt")?.use { it.write(pickedBytes) }
            ?: error("write current srt failed")
        resolver.openOutputStream(pickedSrtUri, "wt")?.use { it.write(currentBytes) }
            ?: error("write picked srt failed")
        true
    }.getOrElse {
        Log.e("BookReaderSrtReplace", "swap srt failed", it)
        false
    }
}

private fun persistReplacedSrtToImportState(
    context: Context,
    resolver: ContentResolver,
    audioUri: Uri?,
    targetSrtUri: Uri?
) {
    val audioKey = audioUri?.toString()?.trim().orEmpty()
    val srtKey = targetSrtUri?.toString()?.trim().orEmpty()
    if (audioKey.isBlank() || srtKey.isBlank()) return

    val state = loadPersistedImports(context)
    val srtName = runCatching { queryBookDisplayName(resolver, targetSrtUri!!) }.getOrNull()

    var matched = false
    val updatedBooks = state.books.map { book ->
        if (book.audioUri == audioKey) {
            matched = true
            book.copy(
                srtUri = srtKey,
                srtName = srtName ?: book.srtName
            )
        } else {
            book
        }
    }
    val updatedState = state.copy(
        srtUri = if (state.audioUri == audioKey) srtKey else state.srtUri,
        srtName = if (state.audioUri == audioKey) (srtName ?: state.srtName) else state.srtName,
        books = updatedBooks
    )
    savePersistedImports(context, updatedState)
}

private fun movePickedSrtToBookFolder(
    context: Context,
    resolver: ContentResolver,
    pickedSrtUri: Uri,
    title: String,
    audioUri: Uri?
): Uri? {
    return runCatching {
        val sourceName = queryBookDisplayName(resolver, pickedSrtUri)
            .trim()
            .ifBlank { "${title.ifBlank { "book" }}.srt" }
        val normalizedSourceName = if (sourceName.lowercase(Locale.ROOT).endsWith(".srt")) {
            sourceName
        } else {
            "$sourceName.srt"
        }

        val targetUri = when (audioUri?.scheme?.lowercase(Locale.ROOT)) {
            "file" -> {
                val audioPath = audioUri.path ?: return@runCatching null
                val parent = File(audioPath).parentFile ?: return@runCatching null
                if (!parent.exists()) return@runCatching null
                val targetFile = resolveUniqueFileName(parent, normalizedSourceName)
                openReaderInputStream(resolver, pickedSrtUri)?.use { src ->
                    targetFile.outputStream().use { out ->
                        src.copyTo(out)
                        out.flush()
                    }
                } ?: return@runCatching null
                Uri.fromFile(targetFile)
            }
            "content" -> {
                val parentFolder = resolveAudioParentFolder(context, audioUri) ?: return@runCatching null
                val targetName = resolveUniqueDocumentNameLocal(parentFolder, normalizedSourceName)
                val created = parentFolder.createFile("application/x-subrip", targetName)
                    ?: return@runCatching null
                openReaderInputStream(resolver, pickedSrtUri)?.use { src ->
                    resolver.openOutputStream(created.uri, "w")?.use { out ->
                        src.copyTo(out)
                        out.flush()
                    } ?: return@runCatching null
                } ?: return@runCatching null
                created.uri
            }
            else -> return@runCatching null
        }
        if (targetUri.toString() != pickedSrtUri.toString() && !deleteSourceSrtUri(context, resolver, pickedSrtUri)) {
            Log.w("BookReaderSrtReplace", "move source delete failed uri=$pickedSrtUri")
        }
        targetUri
    }.getOrNull()
}

private fun resolveAudioParentFolder(context: Context, audioUri: Uri): DocumentFile? {
    return runCatching {
        val docId = DocumentsContract.getDocumentId(audioUri)
        val parentDocId = docId.substringBeforeLast('/', "")
        if (parentDocId.isBlank() || parentDocId == docId) return@runCatching null
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(audioUri, parentDocId)
        DocumentFile.fromTreeUri(context, parentDocUri)
            ?: DocumentFile.fromSingleUri(context, parentDocUri)
    }.getOrNull()
}

private fun resolveUniqueDocumentNameLocal(folder: DocumentFile, originalName: String): String {
    val cleaned = originalName.trim().ifBlank { "subtitle.srt" }
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

private fun resolveUniqueFileName(parent: File, originalName: String): File {
    val cleaned = originalName.trim().ifBlank { "subtitle.srt" }
    val first = File(parent, cleaned)
    if (!first.exists()) return first

    val dot = cleaned.lastIndexOf('.')
    val hasExtension = dot > 0 && dot < cleaned.lastIndex
    val base = if (hasExtension) cleaned.substring(0, dot) else cleaned
    val ext = if (hasExtension) cleaned.substring(dot) else ""
    var index = 2
    while (index <= 9999) {
        val candidate = File(parent, "$base ($index)$ext")
        if (!candidate.exists()) return candidate
        index += 1
    }
    return File(parent, "$base-${System.currentTimeMillis()}$ext")
}

private fun deleteSourceSrtUri(
    context: Context,
    resolver: ContentResolver,
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
    return runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
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

private fun applyControlModeScreenBrightness(context: Context, dimToMinimum: Boolean): () -> Unit {
    val activity = context.findHostActivity() ?: return {}
    val window = activity.window ?: return {}

    if (Settings.System.canWrite(context)) {
        val resolver = context.contentResolver
        val previousMode = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        }.getOrNull()
        val previousBrightness = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull()

        if (dimToMinimum) {
            runCatching {
                Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    1
                )
            }
        }

        return {
            previousMode?.let {
                runCatching {
                    Settings.System.putInt(
                        resolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        it
                    )
                }
            }
            previousBrightness?.let {
                runCatching {
                    Settings.System.putInt(
                        resolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        it
                    )
                }
            }
            val attrs = window.attributes
            if (attrs.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
                attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = attrs
            }
        }
    }

    val attrs = window.attributes
    val previousBrightness = attrs.screenBrightness
    val targetBrightness = if (dimToMinimum) {
        0.01f
    } else {
        WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
    if (attrs.screenBrightness != targetBrightness) {
        attrs.screenBrightness = targetBrightness
        window.attributes = attrs
    }
    return {
        val restoreAttrs = window.attributes
        if (restoreAttrs.screenBrightness != previousBrightness) {
            restoreAttrs.screenBrightness = previousBrightness
            window.attributes = restoreAttrs
        }
    }
}

private tailrec fun Context.findHostActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findHostActivity()
        else -> null
    }
}

private fun loadUiTestLayoutModeHorizontal(context: Context): Int {
    return context
        .getSharedPreferences(BOOK_READER_UI_TEST_PREFS, Context.MODE_PRIVATE)
        .getInt(BOOK_READER_UI_TEST_LAYOUT_HORIZONTAL_KEY, 1)
        .coerceIn(1, 2)
}

private fun loadUiTestLayoutModeVertical(context: Context): Int {
    return context
        .getSharedPreferences(BOOK_READER_UI_TEST_PREFS, Context.MODE_PRIVATE)
        .getInt(BOOK_READER_UI_TEST_LAYOUT_VERTICAL_KEY, 2)
        .coerceIn(1, 2)
}

private fun saveUiTestLayoutModeHorizontal(context: Context, mode: Int) {
    context
        .getSharedPreferences(BOOK_READER_UI_TEST_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putInt(BOOK_READER_UI_TEST_LAYOUT_HORIZONTAL_KEY, mode.coerceIn(1, 2))
        .apply()
}

private fun saveUiTestLayoutModeVertical(context: Context, mode: Int) {
    context
        .getSharedPreferences(BOOK_READER_UI_TEST_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putInt(BOOK_READER_UI_TEST_LAYOUT_VERTICAL_KEY, mode.coerceIn(1, 2))
        .apply()
}

private fun loadUiSwapPrevNextHorizontal(context: Context): Boolean {
    return context
        .getSharedPreferences(BOOK_READER_UI_TEST_PREFS, Context.MODE_PRIVATE)
        .getBoolean(BOOK_READER_UI_SWAP_PREV_NEXT_HORIZONTAL_KEY, false)
}

private fun saveUiSwapPrevNextHorizontal(context: Context, enabled: Boolean) {
    context
        .getSharedPreferences(BOOK_READER_UI_TEST_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(BOOK_READER_UI_SWAP_PREV_NEXT_HORIZONTAL_KEY, enabled)
        .apply()
}

private fun loadUiSwapPrevNextVertical(context: Context): Boolean {
    return context
        .getSharedPreferences(BOOK_READER_UI_TEST_PREFS, Context.MODE_PRIVATE)
        .getBoolean(BOOK_READER_UI_SWAP_PREV_NEXT_VERTICAL_KEY, false)
}

private fun saveUiSwapPrevNextVertical(context: Context, enabled: Boolean) {
    context
        .getSharedPreferences(BOOK_READER_UI_TEST_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(BOOK_READER_UI_SWAP_PREV_NEXT_VERTICAL_KEY, enabled)
        .apply()
}

private fun loadUiChapterVisible(context: Context): Boolean {
    return context
        .getSharedPreferences(BOOK_READER_UI_TEST_PREFS, Context.MODE_PRIVATE)
        .getBoolean(BOOK_READER_UI_CHAPTER_VISIBLE_KEY, true)
}

private fun saveUiChapterVisible(context: Context, visible: Boolean) {
    context
        .getSharedPreferences(BOOK_READER_UI_TEST_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(BOOK_READER_UI_CHAPTER_VISIBLE_KEY, visible)
        .apply()
}
