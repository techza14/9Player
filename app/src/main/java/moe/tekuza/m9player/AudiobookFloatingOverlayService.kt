package moe.tekuza.m9player

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.view.View.MeasureSpec
import android.view.Choreographer
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.core.text.HtmlCompat
import java.util.Locale
import android.webkit.WebView
import android.webkit.WebResourceResponse
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun hasOverlayPermission(context: Context): Boolean {
    return Settings.canDrawOverlays(context)
}

internal fun startAudiobookFloatingOverlayService(context: Context) {
    if (!hasOverlayPermission(context)) return
    val settings = loadAudiobookSettingsConfig(context)
    if (!settings.floatingOverlayEnabled && !settings.floatingOverlaySubtitleEnabled) return
    val intent = Intent(context, AudiobookFloatingOverlayService::class.java).apply {
        action = AudiobookFloatingOverlayService.ACTION_SHOW
    }
    context.startService(intent)
}

internal fun refreshAudiobookFloatingOverlayService(context: Context) {
    if (!hasOverlayPermission(context)) return
    val intent = Intent(context, AudiobookFloatingOverlayService::class.java).apply {
        action = AudiobookFloatingOverlayService.ACTION_REFRESH
    }
    context.startService(intent)
}

internal fun stopAudiobookFloatingOverlayService(context: Context) {
    val intent = Intent(context, AudiobookFloatingOverlayService::class.java).apply {
        action = AudiobookFloatingOverlayService.ACTION_HIDE
    }
    context.startService(intent)
}

class AudiobookFloatingOverlayService : Service() {
companion object {
        const val ACTION_SHOW = "moe.tekuza.m9player.action.SHOW_FLOATING_OVERLAY"
        const val ACTION_HIDE = "moe.tekuza.m9player.action.HIDE_FLOATING_OVERLAY"
        const val ACTION_REFRESH = "moe.tekuza.m9player.action.REFRESH_FLOATING_OVERLAY"
        private const val FLOATING_LOOKUP_LOG_TAG = "FloatingLookupPos"
        private const val FLOATING_LOOKUP_HIGHLIGHT_LOG_TAG = "FloatingLookupHighlight"
        private const val FLOATING_LOOKUP_TAP_LOG_TAG = "FloatingLookupTap"
        private const val FLOATING_BUBBLE_LOG_TAG = "FloatingBubblePos"
        private const val FLOATING_SUBTITLE_SCROLL_LOG_TAG = "FloatingSubtitleScroll"
        private const val SINGLE_FLOATING_HOST_KEY = -1
}

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var bubbleRootView: FrameLayout? = null
    private var subtitlePanelView: LinearLayout? = null
    private var bubbleRow: LinearLayout? = null
    private var bubbleButton: ImageButton? = null
    private var bubbleControlsRow: LinearLayout? = null
    private var bubbleFavoriteButton: ImageButton? = null
    private var bubbleLockButton: ImageButton? = null
    private var subtitleTextView: TextView? = null
    private var subtitleOutlineTextView: TextView? = null
    private var subtitleControlsRow: LinearLayout? = null
    private var subtitleSettingsPanel: LinearLayout? = null
    private var subtitleLockButton: ImageButton? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null
    private var bubbleWindowLayoutParams: WindowManager.LayoutParams? = null
    private var subtitleControlsVisible: Boolean = true
    private var subtitleSettingsExpanded: Boolean = false
    private var bubbleControlsVisible: Boolean = false
    private var subtitleOverlayLocked: Boolean = false
    private var bubbleOverlayLocked: Boolean = false
    private var playbackPausedByFloatingLookup: Boolean = false
    private var lastSubtitleScrollLogAtMs: Long = 0L
    private var subtitleTickerRunning: Boolean = false
    private var subtitleTickerBasePositionMs: Long = 0L
    private var subtitleTickerBaseRealtimeMs: Long = 0L
    private var subtitlePlaybackSpeed: Float = 1f
    private var lookupRequestNonce: Long = 0L
    private var cachedLookupDictionaries: List<LoadedDictionary>? = null
    private val floatingLookupSession = ReaderLookupSession()
    private val floatingLookupCardPositions = mutableMapOf<Int, IntOffset>()
    private val floatingLookupHostViews = mutableMapOf<Int, View>()
    private val floatingLookupHostSignatures = mutableMapOf<Int, Int>()
    private val floatingLookupRepositionJobs = mutableMapOf<Int, Job>()
    private val floatingLookupHostSizeListeners = mutableMapOf<Int, View.OnLayoutChangeListener>()

    private data class FloatingDefinitionWebViewTag(
        val bridge: DefinitionLookupBridge,
        val definitionKey: String
    )

    private class FloatingLookupHostLayout(context: Context) : FrameLayout(context) {
        var interceptAllTouches: Boolean = false

        override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
            return interceptAllTouches || super.onInterceptTouchEvent(ev)
        }
    }

    private val playbackListener = object : BookReaderFloatingBridge.PlaybackStateListener {
        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            updateBubbleIcon(isPlaying)
            updatePlayPauseIcon(isPlaying)
            updateSubtitleAutoScroll()
            if (isPlaying) startSubtitleTicker() else stopSubtitleTicker()
        }
    }

    private val playbackPositionListener = object : BookReaderFloatingBridge.PlaybackPositionListener {
        override fun onPlaybackPositionChanged(positionMs: Long) {
            subtitleTickerBasePositionMs = positionMs
            subtitleTickerBaseRealtimeMs = SystemClock.uptimeMillis()
            updateSubtitleAutoScroll(positionMs)
        }
    }

    private val playbackSpeedListener = object : BookReaderFloatingBridge.PlaybackSpeedListener {
        override fun onPlaybackSpeedChanged(speed: Float) {
            subtitlePlaybackSpeed = if (speed.isFinite() && speed > 0f) speed else 1f
        }
    }

    private val subtitleFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!subtitleTickerRunning) return
            if (!BookReaderFloatingBridge.isPlaying()) {
                subtitleTickerRunning = false
                return
            }
            val now = SystemClock.uptimeMillis()
            val elapsed = (now - subtitleTickerBaseRealtimeMs).coerceAtLeast(0L)
            val extrapolated = subtitleTickerBasePositionMs + (elapsed * subtitlePlaybackSpeed).toLong()
            updateSubtitleAutoScroll(extrapolated)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val favoriteListener = object : BookReaderFloatingBridge.FavoriteStateListener {
        override fun onFavoriteStateChanged(isFavorite: Boolean) {
            updateBubbleFavoriteIcon(isFavorite)
        }
    }

    private val subtitleListener = object : BookReaderFloatingBridge.SubtitleStateListener {
        override fun onSubtitleChanged(text: String?) {
            updateSubtitleText(text)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        BookReaderFloatingBridge.addPlaybackStateListener(playbackListener)
        BookReaderFloatingBridge.addFavoriteStateListener(favoriteListener)
        BookReaderFloatingBridge.addSubtitleStateListener(subtitleListener)
        BookReaderFloatingBridge.addPlaybackPositionListener(playbackPositionListener)
        BookReaderFloatingBridge.addPlaybackSpeedListener(playbackSpeedListener)
        subtitlePlaybackSpeed = BookReaderFloatingBridge.currentPlaybackSpeed()
        subtitleTickerBasePositionMs = BookReaderFloatingBridge.currentPlaybackPositionMs()
        subtitleTickerBaseRealtimeMs = SystemClock.uptimeMillis()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW, null -> {
                ensureOverlayVisible()
                return START_STICKY
            }
            ACTION_REFRESH -> {
                rebuildOverlay()
                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        BookReaderFloatingBridge.removePlaybackStateListener(playbackListener)
        BookReaderFloatingBridge.removeFavoriteStateListener(favoriteListener)
        BookReaderFloatingBridge.removeSubtitleStateListener(subtitleListener)
        BookReaderFloatingBridge.removePlaybackPositionListener(playbackPositionListener)
        BookReaderFloatingBridge.removePlaybackSpeedListener(playbackSpeedListener)
        stopSubtitleTicker()
        serviceScope.cancel()
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasSubtitleTimeline(): Boolean = BookReaderFloatingBridge.hasSubtitleTrack()

    private fun ensureOverlayVisible() {
        if (!hasOverlayPermission(this)) {
            stopSelf()
            return
        }
        val settings = loadAudiobookSettingsConfig(this)
        if (!settings.floatingOverlayEnabled && !settings.floatingOverlaySubtitleEnabled) {
            stopSelf()
            return
        }
        val wm = windowManager ?: run {
            stopSelf()
            return
        }
        val subtitleEnabledByData = settings.floatingOverlaySubtitleEnabled && hasSubtitleTimeline()
        if (!settings.floatingOverlayEnabled && !subtitleEnabledByData) {
            stopSelf()
            return
        }
        val density = resources.displayMetrics.density
        val bubbleSizeDp = settings.floatingOverlaySizeDp
        val bubbleSizePx = (bubbleSizeDp * density).toInt()
        if (subtitleEnabledByData) {
            if (rootView == null) {
                val container = FrameLayout(this).apply {
                    clipChildren = false
                    clipToPadding = false
                    setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
                    addView(buildSubtitlePanel(settings))
                }
                val params = createOverlayLayoutParams(
                    x = 0,
                    y = settings.floatingOverlaySubtitleY.coerceAtLeast(0)
                )
                wm.addView(container, params)
                rootView = container
                windowLayoutParams = params
                container.post { alignOverlayWindow(force = true) }
            } else {
                applySubtitleTypography(settings)
                updateSubtitleText(BookReaderFloatingBridge.currentSubtitle())
                updateSubtitleControlsVisibility(settings)
            }
        } else {
            removeSubtitleOverlay()
        }
        if (settings.floatingOverlayEnabled) {
            if (bubbleRootView == null) {
                val bubblePanel = buildBubblePanel(bubbleSizePx)
                val params = createOverlayLayoutParams(
                    x = settings.floatingOverlayBubbleX.coerceAtLeast(0),
                    y = settings.floatingOverlayBubbleY.coerceAtLeast(0)
                )
                wm.addView(bubblePanel, params)
                bubbleRootView = bubblePanel
                bubbleWindowLayoutParams = params
            }
        } else {
            removeBubbleOverlay()
        }
        updateBubbleIcon(BookReaderFloatingBridge.isPlaying())
        updatePlayPauseIcon(BookReaderFloatingBridge.isPlaying())
        if (subtitleEnabledByData) {
            updateSubtitleText(BookReaderFloatingBridge.currentSubtitle())
            updateSubtitleControlsVisibility(settings)
        }
        updateFloatingLookupPanelPosition()
    }

    private fun createOverlayLayoutParams(x: Int, y: Int): WindowManager.LayoutParams {
        val windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            this.x = x
            this.y = y
        }
    }

    private fun buildSubtitlePanel(settings: AudiobookSettingsConfig): LinearLayout {
        val density = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        subtitlePanelView = panel

        val subtitleGestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    handleSubtitleSingleTap(e)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    subtitleControlsVisible = !subtitleControlsVisible
                    subtitleSettingsExpanded = false
                    updateSubtitleControlsVisibility(loadAudiobookSettingsConfig(this@AudiobookFloatingOverlayService))
                    return true
                }

                override fun onDown(e: MotionEvent): Boolean = true
            }
        )

        val subtitleOutlineText = TextView(this).apply {
            setLineSpacing(0f, 1.08f)
            maxLines = 3
            ellipsize = null
            gravity = Gravity.CENTER_HORIZONTAL
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                (320 * density).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = false
            isFocusable = false
        }
        subtitleOutlineTextView = subtitleOutlineText

        val subtitleText = TextView(this).apply {
            setLineSpacing(0f, 1.08f)
            maxLines = 3
            ellipsize = null
            gravity = Gravity.CENTER_HORIZONTAL
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                (320 * density).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnTouchListener(
                OverlayDragTouchListener(
                    gestureDetector = subtitleGestureDetector,
                    dragAllowed = { !subtitleOverlayLocked },
                    verticalOnly = true,
                    layoutParamsProvider = { windowLayoutParams },
                    hostViewProvider = { rootView },
                    onPersistPosition = { _, y ->
                        saveAudiobookFloatingOverlaySubtitlePosition(this@AudiobookFloatingOverlayService, y)
                    }
                )
            )
        }
        subtitleTextView = subtitleText
        applySubtitleTypography(settings)
        panel.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (320 * density).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(
                subtitleOutlineText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                subtitleText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6 * density).toInt()
            }
            background = createRoundedBackground(0x33151515, cornerDp = 18f, strokeColor = 0x22FFFFFF)
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            setOnTouchListener(
                OverlayDragTouchListener(
                    gestureDetector = null,
                    dragAllowed = { !subtitleOverlayLocked },
                    verticalOnly = true,
                    layoutParamsProvider = { windowLayoutParams },
                    hostViewProvider = { rootView },
                    onPersistPosition = { _, y ->
                        saveAudiobookFloatingOverlaySubtitlePosition(this@AudiobookFloatingOverlayService, y)
                    }
                )
            )
        }
        subtitleControlsRow = controls

        val lockButton = createControlButton(R.drawable.ic_overlay_lock) {
            subtitleOverlayLocked = !subtitleOverlayLocked
            if (subtitleOverlayLocked) {
                subtitleControlsVisible = false
                subtitleSettingsExpanded = false
            }
            updateSubtitleLockIcon()
            updateSubtitleControlsVisibility(loadAudiobookSettingsConfig(this))
        }
        subtitleLockButton = lockButton
        controls.addView(lockButton)
        controls.addView(createControlButton(R.drawable.ic_overlay_previous) {
            BookReaderFloatingBridge.seekPrevious()
        })
        controls.addView(createControlButton(R.drawable.ic_overlay_pause) {
            BookReaderFloatingBridge.togglePlayPause()
            updateBubbleIcon(BookReaderFloatingBridge.isPlaying())
            updatePlayPauseIcon(BookReaderFloatingBridge.isPlaying())
        }.also { it.tag = "playPause" })
        controls.addView(createControlButton(R.drawable.ic_overlay_next) {
            BookReaderFloatingBridge.seekNext()
        })
        controls.addView(createControlButton(R.drawable.ic_overlay_settings) {
            subtitleSettingsExpanded = !subtitleSettingsExpanded
            subtitleSettingsPanel?.visibility = if (subtitleSettingsExpanded) View.VISIBLE else View.GONE
        })
        updateSubtitleLockIcon()
        panel.addView(controls)

        val settingsPanel = buildSubtitleSettingsPanel(settings)
        subtitleSettingsPanel = settingsPanel
        panel.addView(settingsPanel)

        return panel
    }

    private fun buildSubtitleSettingsPanel(settings: AudiobookSettingsConfig): LinearLayout {
        val density = resources.displayMetrics.density
        val customColorHolder = intArrayOf(settings.floatingOverlaySubtitleCustomColor)
        var currentR = Color.red(customColorHolder[0])
        var currentG = Color.green(customColorHolder[0])
        var currentB = Color.blue(customColorHolder[0])
        val presetColors = listOf(
            FLOATING_OVERLAY_SUBTITLE_COLOR_WHITE,
            FLOATING_OVERLAY_SUBTITLE_COLOR_YELLOW,
            FLOATING_OVERLAY_SUBTITLE_COLOR_GREEN,
            FLOATING_OVERLAY_SUBTITLE_COLOR_CYAN
        )
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (6 * density).toInt()
            }
            background = createRoundedBackground(0xCC151515.toInt(), cornerDp = 18f, strokeColor = 0x33FFFFFF)
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            addView(TextView(this@AudiobookFloatingOverlayService).apply {
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 12f
                text = getOverlayLocalizedString(R.string.audiobook_overlay_subtitle_style)
            })
            val customInfo = TextView(this@AudiobookFloatingOverlayService).apply {
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 11f
                text = "RGB($currentR,$currentG,$currentB)"
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
            }

            lateinit var customColorButton: View
            val presetColorButtons = linkedMapOf<Int, View>()
            val customPanel = LinearLayout(this@AudiobookFloatingOverlayService).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
            }

            val refreshCustomUi = {
                customInfo.text = "RGB($currentR,$currentG,$currentB)"
                val selectedColor = loadAudiobookSettingsConfig(context).floatingOverlaySubtitleColor
                presetColorButtons.forEach { (color, button) ->
                    button.background = createSubtitleColorSwatchDrawable(
                        color = color,
                        selected = selectedColor == color,
                        density = density
                    )
                }
                val selected = selectedColor == customColorHolder[0]
                customColorButton.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(customColorHolder[0])
                    setStroke(
                        (if (selected) 3 else 1) * density.toInt().coerceAtLeast(1),
                        if (selected) 0xFFFFFFFF.toInt() else 0x44FFFFFF
                    )
                }
            }

            addView(LinearLayout(this@AudiobookFloatingOverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (6 * density).toInt() }
                presetColors.forEach { color ->
                    val button = createColorButton(
                        color = color,
                        currentColor = settings.floatingOverlaySubtitleColor,
                        onColorSelected = { refreshCustomUi() }
                    )
                    presetColorButtons[color] = button
                    addView(button)
                }
                customColorButton = createCustomColorButton(
                    color = customColorHolder[0],
                    selected = settings.floatingOverlaySubtitleColor == customColorHolder[0]
                ) {
                    subtitleSettingsExpanded = true
                    customPanel.visibility = if (customPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    customInfo.visibility = customPanel.visibility
                    saveAudiobookFloatingOverlaySubtitleColor(context, customColorHolder[0])
                    applySubtitleTypography(loadAudiobookSettingsConfig(context))
                    refreshCustomUi()
                }
                addView(customColorButton)
            })
            customPanel.addView(createRgbSliderRow(
                label = "R",
                initial = currentR,
                onLiveChange = { v ->
                    currentR = v
                    customColorHolder[0] = Color.rgb(currentR, currentG, currentB)
                    saveAudiobookFloatingOverlaySubtitleColor(context, customColorHolder[0])
                    applySubtitleTypography(loadAudiobookSettingsConfig(context))
                    refreshCustomUi()
                },
                onFinalChange = {
                    saveAudiobookFloatingOverlaySubtitleCustomColor(context, customColorHolder[0])
                    saveAudiobookFloatingOverlaySubtitleColor(context, customColorHolder[0])
                    refreshCustomUi()
                }
            ))
            customPanel.addView(createRgbSliderRow(
                label = "G",
                initial = currentG,
                onLiveChange = { v ->
                    currentG = v
                    customColorHolder[0] = Color.rgb(currentR, currentG, currentB)
                    saveAudiobookFloatingOverlaySubtitleColor(context, customColorHolder[0])
                    applySubtitleTypography(loadAudiobookSettingsConfig(context))
                    refreshCustomUi()
                },
                onFinalChange = {
                    saveAudiobookFloatingOverlaySubtitleCustomColor(context, customColorHolder[0])
                    saveAudiobookFloatingOverlaySubtitleColor(context, customColorHolder[0])
                    refreshCustomUi()
                }
            ))
            customPanel.addView(createRgbSliderRow(
                label = "B",
                initial = currentB,
                onLiveChange = { v ->
                    currentB = v
                    customColorHolder[0] = Color.rgb(currentR, currentG, currentB)
                    saveAudiobookFloatingOverlaySubtitleColor(context, customColorHolder[0])
                    applySubtitleTypography(loadAudiobookSettingsConfig(context))
                    refreshCustomUi()
                },
                onFinalChange = {
                    saveAudiobookFloatingOverlaySubtitleCustomColor(context, customColorHolder[0])
                    saveAudiobookFloatingOverlaySubtitleColor(context, customColorHolder[0])
                    refreshCustomUi()
                }
            ))
            addView(customInfo)
            addView(customPanel)
            addView(LinearLayout(this@AudiobookFloatingOverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * density).toInt() }
                addView(TextView(this@AudiobookFloatingOverlayService).apply {
                    text = getOverlayLocalizedString(R.string.audiobook_overlay_subtitle_scroll)
                    setTextColor(0xCCFFFFFF.toInt())
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                })
                addView(Switch(this@AudiobookFloatingOverlayService).apply {
                    isChecked = settings.floatingOverlaySubtitleScrollEnabled
                    setOnCheckedChangeListener { _, checked ->
                        saveAudiobookFloatingOverlaySubtitleScrollEnabled(context, checked)
                        updateSubtitleAutoScroll()
                    }
                })
            })
            addView(LinearLayout(this@AudiobookFloatingOverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (8 * density).toInt() }
                addView(createTextButton("A-") {
                    val next = (loadAudiobookSettingsConfig(context).floatingOverlaySubtitleSizeSp - 2)
                        .coerceAtLeast(MIN_FLOATING_OVERLAY_SUBTITLE_SIZE_SP)
                    saveAudiobookFloatingOverlaySubtitleSizeSp(context, next)
                    applySubtitleTypography(loadAudiobookSettingsConfig(context))
                    rootView?.post { alignOverlayWindow(force = true) }
                })
                addView(createTextButton("A+") {
                    val next = (loadAudiobookSettingsConfig(context).floatingOverlaySubtitleSizeSp + 2)
                        .coerceAtMost(MAX_FLOATING_OVERLAY_SUBTITLE_SIZE_SP)
                    saveAudiobookFloatingOverlaySubtitleSizeSp(context, next)
                    applySubtitleTypography(loadAudiobookSettingsConfig(context))
                    rootView?.post { alignOverlayWindow(force = true) }
                }.apply {
                    (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0
                })
            })
            refreshCustomUi()
        }
    }

    private fun buildBubblePanel(bubbleSizePx: Int): FrameLayout {
        val density = resources.displayMetrics.density
        val bubbleScale = (bubbleSizePx.toFloat() / (DEFAULT_FLOATING_OVERLAY_SIZE_DP * density))
            .coerceIn(0.62f, 1.4f)
        val host = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        bubbleRow = row

        val bubble = ImageButton(this).apply {
            setImageResource(R.drawable.ic_overlay_pause)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xDD151515.toInt())
            }
            setColorFilter(0xFFFFFFFF.toInt())
            layoutParams = FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(bubbleSizePx / 4, bubbleSizePx / 4, bubbleSizePx / 4, bubbleSizePx / 4)
            setOnTouchListener(
                OverlayDragTouchListener(
                    gestureDetector = GestureDetector(
                        this@AudiobookFloatingOverlayService,
                        object : GestureDetector.SimpleOnGestureListener() {
                            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                                BookReaderFloatingBridge.togglePlayPause()
                                updateBubbleIcon(BookReaderFloatingBridge.isPlaying())
                                updatePlayPauseIcon(BookReaderFloatingBridge.isPlaying())
                                return true
                            }

                            override fun onDoubleTap(e: MotionEvent): Boolean {
                                toggleBubbleControlsVisibility(animated = true)
                                return true
                            }

                            override fun onDown(e: MotionEvent): Boolean = true
                        }
                    ),
                    dragAllowed = { !bubbleOverlayLocked },
                    layoutParamsProvider = { bubbleWindowLayoutParams },
                    hostViewProvider = { bubbleRootView },
                    horizontalBoundsProvider = {
                        val screenWidth = resources.displayMetrics.widthPixels
                        val bubbleWidth = bubbleButton?.width
                            ?.takeIf { it > 0 }
                            ?: (bubbleButton?.layoutParams?.width ?: bubbleSizePx)
                        0..(screenWidth - bubbleWidth).coerceAtLeast(0)
                    },
                    onPersistPosition = { x, y ->
                        saveAudiobookFloatingOverlayBubblePosition(
                            this@AudiobookFloatingOverlayService,
                            x,
                            y
                        )
                    }
                )
            )
        }
        bubbleButton = bubble

        val controlsTemplate = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = 0
            background = createRoundedBackground(
                0x55151515,
                cornerDp = 24f * bubbleScale,
                strokeColor = 0x22FFFFFF
            )
            setPadding(
                (10 * density * bubbleScale).toInt(),
                (5 * density * bubbleScale).toInt(),
                (16 * density * bubbleScale).toInt(),
                (5 * density * bubbleScale).toInt()
            )
            addView(createControlButton(R.drawable.ic_overlay_lock, bubbleScale) {
                bubbleOverlayLocked = !bubbleOverlayLocked
                if (bubbleOverlayLocked) {
                    bubbleControlsVisible = false
                    bubbleControlsRow?.animate()?.cancel()
                    bubbleControlsRow?.visibility = View.GONE
                    bubbleControlsRow?.alpha = 0f
                }
                updateBubbleLockIcon()
            }.also { bubbleLockButton = it })
            addView(createControlButton(R.drawable.ic_overlay_previous, bubbleScale) {
                BookReaderFloatingBridge.seekPrevious()
            })
            addView(createControlButton(R.drawable.ic_overlay_next, bubbleScale) {
                BookReaderFloatingBridge.seekNext()
            })
            addView(createControlButton(R.drawable.ic_overlay_favorite, bubbleScale) {
                BookReaderFloatingBridge.toggleFavorite()
            }.also { bubbleFavoriteButton = it })
        }
        val bubbleParams = LinearLayout.LayoutParams(bubbleSizePx, bubbleSizePx).apply {
            marginEnd = (8 * density * bubbleScale).toInt()
        }
        bubble.layoutParams = bubbleParams

        val controls = controlsTemplate.apply {
            visibility = View.GONE
            alpha = 1f
        }
        bubbleControlsRow = controls
        updateBubbleLockIcon()
        row.addView(bubble)
        row.addView(controls)
        host.addView(row)
        return host
    }

    private fun toggleBubbleControlsVisibility(animated: Boolean) {
        val controls = bubbleControlsRow ?: return
        val targetVisible = !bubbleControlsVisible
        Log.d(
            FLOATING_BUBBLE_LOG_TAG,
            "toggle targetVisible=$targetVisible"
        )
        bubbleControlsVisible = targetVisible
        if (!animated) {
            controls.animate().cancel()
            controls.visibility = if (bubbleControlsVisible) View.VISIBLE else View.GONE
            controls.alpha = if (bubbleControlsVisible) 1f else 0f
            return
        }
        controls.animate().cancel()
        if (bubbleControlsVisible) {
            controls.visibility = View.VISIBLE
            controls.alpha = 0f
            controls.animate()
                .alpha(1f)
                .setDuration(160L)
                .start()
        } else {
            controls.animate()
                .alpha(0f)
                .setDuration(140L)
                .withEndAction {
                    controls.visibility = View.GONE
                }
                .start()
        }
    }

    private fun updateSubtitleControlsVisibility(settings: AudiobookSettingsConfig) {
        val controls = subtitleControlsRow ?: return
        val panel = subtitleSettingsPanel ?: return
        if (!settings.floatingOverlaySubtitleEnabled) {
            controls.visibility = View.GONE
            panel.visibility = View.GONE
            return
        }
        controls.visibility = if (subtitleControlsVisible) View.VISIBLE else View.GONE
        panel.visibility = if (subtitleControlsVisible && subtitleSettingsExpanded) View.VISIBLE else View.GONE
    }

    private fun handleSubtitleSingleTap(event: MotionEvent) {
        val subtitle = subtitleTextView ?: return
        val layout = subtitle.layout ?: return
        val x = (event.x - subtitle.totalPaddingLeft).coerceAtLeast(0f)
        val y = (event.y - subtitle.totalPaddingTop + subtitle.scrollY).coerceAtLeast(0f)
        val line = layout.getLineForVertical(y.toInt().coerceAtLeast(0))
        val offset = layout.getOffsetForHorizontal(line, x)
        val initialRange = IntRange(offset, offset)
        val initialAnchorRect = computeSubtitleAnchorRects(subtitle, initialRange).firstOrNull()
        performFloatingLookup(offset, initialAnchorRect)
    }

    private fun setSubtitleTextWidthMode(matchParent: Boolean) {
        val widthMode = if (matchParent) FrameLayout.LayoutParams.MATCH_PARENT else FrameLayout.LayoutParams.WRAP_CONTENT
        listOfNotNull(subtitleTextView, subtitleOutlineTextView).forEach { tv ->
            val lp = (tv.layoutParams as? FrameLayout.LayoutParams) ?: return@forEach
            if (lp.width != widthMode) {
                lp.width = widthMode
                tv.layoutParams = lp
            }
        }
    }

    private fun setSubtitleTextExactWidth(widthPx: Int) {
        val safeWidth = widthPx.coerceAtLeast(1)
        listOfNotNull(subtitleTextView, subtitleOutlineTextView).forEach { tv ->
            val lp = (tv.layoutParams as? FrameLayout.LayoutParams) ?: return@forEach
            if (lp.width != safeWidth) {
                lp.width = safeWidth
                tv.layoutParams = lp
            }
        }
    }

    private fun setSubtitleTranslationX(dx: Float) {
        subtitleTextView?.translationX = dx
        subtitleOutlineTextView?.translationX = dx
    }

    private fun applyPunctuationPause(linear: Float, text: String): Float {
        val normalized = linear.coerceIn(0f, 1f)
        if (normalized <= 0f) return 0f
        val compact = text.filter { it != '\n' && it != '\r' }
        if (compact.length <= 1) return normalized

        val marks = compact.withIndex()
            .filter { it.value == '、' }
            .map { it.index.toFloat() / (compact.length - 1).toFloat() }
        if (marks.isEmpty()) return normalized

        // Smooth pause model:
        // Each punctuation contributes a cumulative delay via sigmoid, creating
        // smooth deceleration near punctuation and smooth recovery afterwards.
        val perHold = 0.06f
        val totalHold = (marks.size * perHold).coerceAtMost(0.45f)
        val holdEach = (totalHold / marks.size).coerceAtLeast(0.01f)
        val sigma = 0.03f
        val startDelay = 0.02f

        var delay = 0f
        for (p in marks) {
            val center = (p + startDelay).coerceIn(0f, 1f)
            val z = ((normalized - center) / sigma).coerceIn(-12f, 12f)
            val s = (1f / (1f + kotlin.math.exp(-z)))
            delay += holdEach * s
        }
        // Re-normalize to keep fixed endpoints: f(0)=0, f(1)=1.
        fun rawAt(t: Float): Float {
            var d = 0f
            for (p in marks) {
                val center = (p + startDelay).coerceIn(0f, 1f)
                val z = ((t - center) / sigma).coerceIn(-12f, 12f)
                val s = (1f / (1f + kotlin.math.exp(-z)))
                d += holdEach * s
            }
            return (t - d)
        }

        val raw0 = rawAt(0f)
        val raw1 = rawAt(1f)
        val denom = (raw1 - raw0)
        if (denom <= 1e-5f) return normalized
        val raw = (normalized - delay)
        return ((raw - raw0) / denom).coerceIn(0f, 1f)
    }

    private fun updateSubtitleText(text: String?) {
        val subtitle = subtitleTextView ?: return
        val outline = subtitleOutlineTextView
        val settings = loadAudiobookSettingsConfig(this)
        val subtitleEnabledByData = settings.floatingOverlaySubtitleEnabled && hasSubtitleTimeline()
        val normalized = text?.trim()?.takeIf { it.isNotEmpty() }
        if (!subtitleEnabledByData || normalized == null) {
            subtitle.animate().cancel()
            subtitle.text = ""
            subtitle.visibility = View.GONE
            subtitle.scrollTo(0, 0)
            subtitle.translationX = 0f
            outline?.text = ""
            outline?.visibility = View.GONE
            outline?.scrollTo(0, 0)
            outline?.translationX = 0f
            stopSubtitleTicker()
            hideFloatingLookup()
            rootView?.post { alignOverlayWindow(force = true) }
            return
        }
        if (subtitle.text?.toString() == normalized && subtitle.visibility == View.VISIBLE) return
        subtitle.animate().cancel()
        subtitle.alpha = 1f
        subtitle.text = normalized
        subtitle.visibility = View.VISIBLE
        subtitle.scrollTo(0, 0)
        subtitle.translationX = 0f
        outline?.text = normalized
        outline?.visibility = View.VISIBLE
        outline?.scrollTo(0, 0)
        outline?.translationX = 0f
        subtitleTickerBasePositionMs = BookReaderFloatingBridge.currentPlaybackPositionMs()
        subtitleTickerBaseRealtimeMs = SystemClock.uptimeMillis()
        subtitle.post {
            alignOverlayWindow(force = true)
            updateSubtitleAutoScroll()
            if (BookReaderFloatingBridge.isPlaying()) {
                startSubtitleTicker()
            }
        }
    }

    private fun updateSubtitleAutoScroll(positionMs: Long = BookReaderFloatingBridge.currentPlaybackPositionMs()) {
        val subtitle = subtitleTextView ?: return
        val outline = subtitleOutlineTextView
        if (subtitle.visibility != View.VISIBLE) return
        val text = subtitle.text?.toString().orEmpty()
        if (text.isBlank()) return
        val settings = loadAudiobookSettingsConfig(this)
        if (!settings.floatingOverlaySubtitleEnabled) return
        val now = System.currentTimeMillis()
        val shouldLog = now - lastSubtitleScrollLogAtMs >= 300L

        if (!settings.floatingOverlaySubtitleScrollEnabled) {
            setSubtitleTextWidthMode(matchParent = true)
            subtitle.setSingleLine(false)
            subtitle.maxLines = Int.MAX_VALUE
            subtitle.ellipsize = null
            subtitle.setHorizontallyScrolling(false)
            subtitle.gravity = Gravity.CENTER_HORIZONTAL
            subtitle.textAlignment = View.TEXT_ALIGNMENT_CENTER
            outline?.setSingleLine(false)
            outline?.maxLines = Int.MAX_VALUE
            outline?.ellipsize = null
            outline?.setHorizontallyScrolling(false)
            outline?.gravity = Gravity.CENTER_HORIZONTAL
            outline?.textAlignment = View.TEXT_ALIGNMENT_CENTER
            if (subtitle.scrollX != 0) subtitle.scrollTo(0, 0)
            if (outline != null && outline.scrollX != 0) outline.scrollTo(0, 0)
            setSubtitleTranslationX(0f)
            if (shouldLog) {
                Log.d(
                    FLOATING_SUBTITLE_SCROLL_LOG_TAG,
                    "off pos=$positionMs textLen=${text.length} scrollX=${subtitle.scrollX}"
                )
                lastSubtitleScrollLogAtMs = now
            }
            return
        }

        setSubtitleTextWidthMode(matchParent = false)
        val viewportWidth = ((subtitle.parent as? View)?.width ?: subtitle.width).toFloat()
        if (viewportWidth <= 1f) return

        subtitle.setSingleLine(true)
        subtitle.maxLines = 1
        subtitle.ellipsize = null
        subtitle.setHorizontallyScrolling(true)
        outline?.setSingleLine(true)
        outline?.maxLines = 1
        outline?.ellipsize = null
        outline?.setHorizontallyScrolling(true)
        val textWidth = subtitle.paint.measureText(text.ifEmpty { " " })
        val contentWidth = textWidth + subtitle.totalPaddingLeft + subtitle.totalPaddingRight
        val maxScroll = (contentWidth - viewportWidth).coerceAtLeast(0f)
        if (maxScroll <= 1f) {
            setSubtitleTextWidthMode(matchParent = true)
            subtitle.gravity = Gravity.START
            subtitle.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            outline?.gravity = Gravity.START
            outline?.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            if (subtitle.scrollX != 0) subtitle.scrollTo(0, 0)
            if (outline != null && outline.scrollX != 0) outline.scrollTo(0, 0)
            val centeredDx = ((viewportWidth - contentWidth) / 2f).coerceAtLeast(0f)
            setSubtitleTranslationX(centeredDx)
            if (shouldLog) {
                Log.d(
                    FLOATING_SUBTITLE_SCROLL_LOG_TAG,
                    "fit pos=$positionMs viewportWidth=${"%.1f".format(viewportWidth)} textWidth=${"%.1f".format(textWidth)} dx=${"%.1f".format(centeredDx)} max=${"%.1f".format(maxScroll)}"
                )
                lastSubtitleScrollLogAtMs = now
            }
            return
        }

        val contentWidthPx = kotlin.math.ceil(contentWidth.toDouble()).toInt()
        setSubtitleTextExactWidth(contentWidthPx)

        subtitle.gravity = Gravity.START
        subtitle.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        outline?.gravity = Gravity.START
        outline?.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        if (!BookReaderFloatingBridge.isPlaying()) {
            if (shouldLog) {
                Log.d(
                    FLOATING_SUBTITLE_SCROLL_LOG_TAG,
                    "pause pos=$positionMs width=${"%.1f".format(textWidth)} viewport=${"%.1f".format(viewportWidth)} max=${"%.1f".format(maxScroll)} scrollX=${subtitle.scrollX}"
                )
                lastSubtitleScrollLogAtMs = now
            }
            return
        }
        val cue = BookReaderFloatingBridge.currentCue() ?: return
        val duration = (cue.endMs - cue.startMs).coerceAtLeast(1L)
        val linear = ((positionMs - cue.startMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        // Slow down near punctuation while preserving exact end progress.
        val mapped = applyPunctuationPause(linear, text)
        val minDx = viewportWidth - contentWidth
        val dx = (minDx * mapped).coerceIn(minDx, 0f)
        setSubtitleTranslationX(dx)
        val target = (-dx).toInt().coerceAtLeast(0)
        if (shouldLog) {
            Log.d(
                FLOATING_SUBTITLE_SCROLL_LOG_TAG,
                "tick pos=$positionMs linear=${"%.3f".format(linear)} mapped=${"%.3f".format(mapped)} viewportWidth=${"%.1f".format(viewportWidth)} textWidth=${"%.1f".format(textWidth)} dx=${"%.1f".format(dx)} max=${"%.1f".format(maxScroll)} target=$target scrollX=${subtitle.scrollX} transX=${"%.1f".format(subtitle.translationX)} contentW=$contentWidthPx playing=${BookReaderFloatingBridge.isPlaying()} speed=${"%.2f".format(subtitlePlaybackSpeed)}"
            )
            lastSubtitleScrollLogAtMs = now
        }
    }

    private fun startSubtitleTicker() {
        if (subtitleTickerRunning) return
        subtitleTickerRunning = true
        subtitleTickerBasePositionMs = BookReaderFloatingBridge.currentPlaybackPositionMs()
        subtitleTickerBaseRealtimeMs = SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(subtitleFrameCallback)
    }

    private fun stopSubtitleTicker() {
        if (!subtitleTickerRunning) return
        subtitleTickerRunning = false
        Choreographer.getInstance().removeFrameCallback(subtitleFrameCallback)
    }

    private fun applySubtitleSelectionHighlight(selectedRange: IntRange?) {
        val subtitle = subtitleTextView ?: return
        val outline = subtitleOutlineTextView
        val baseText = BookReaderFloatingBridge.currentSubtitle()?.trim().orEmpty()
        if (baseText.isBlank()) {
            subtitle.text = ""
            outline?.text = ""
            return
        }
        if (selectedRange == null || selectedRange.first !in baseText.indices) {
            subtitle.text = baseText
            outline?.text = baseText
            return
        }
        val endExclusive = (selectedRange.last + 1).coerceAtMost(baseText.length)
        if (endExclusive <= selectedRange.first) {
            subtitle.text = baseText
            outline?.text = baseText
            return
        }
        val spannable = SpannableString(baseText)
        spannable.setSpan(
            BackgroundColorSpan(0x33A1A1AA),
            selectedRange.first,
            endExclusive,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        subtitle.text = spannable
        outline?.text = baseText
    }

    private fun updateFloatingLookupPanelPosition() {
        rootView?.post { alignOverlayWindow(force = false) }
    }

    private fun alignOverlayWindow(force: Boolean) {
        val root = rootView ?: return
        val params = windowLayoutParams ?: return
        val wm = windowManager ?: return
        val settings = loadAudiobookSettingsConfig(this)
        if (!settings.floatingOverlaySubtitleEnabled) return
        if (root.measuredWidth <= 0 || root.measuredHeight <= 0) {
            root.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
        }
        val centeredX = ((resources.displayMetrics.widthPixels - root.measuredWidth) / 2).coerceAtLeast(0)
        if (force || params.x != centeredX) {
            params.x = centeredX
            runCatching { wm.updateViewLayout(root, params) }
            saveAudiobookFloatingOverlaySubtitlePosition(this, params.y.coerceAtLeast(0))
        }
    }

    private fun computeSubtitleAnchorRects(subtitle: TextView, range: IntRange): List<Rect> {
        val layout = subtitle.layout ?: return emptyList()
        val text = subtitle.text ?: return emptyList()
        if (text.isEmpty()) return emptyList()
        val safeStart = range.first.coerceIn(0, text.length - 1)
        val safeEndExclusive = (range.last + 1).coerceIn(safeStart + 1, text.length)
        val startLine = layout.getLineForOffset(safeStart)
        val endLine = layout.getLineForOffset((safeEndExclusive - 1).coerceAtLeast(safeStart))
        val location = IntArray(2)
        subtitle.getLocationOnScreen(location)
        val leftPadding = location[0].toFloat() + subtitle.totalPaddingLeft
        val topPadding = location[1].toFloat() + subtitle.totalPaddingTop
        return buildList {
            for (line in startLine..endLine) {
                val lineStart = maxOf(safeStart, layout.getLineStart(line))
                val lineEndExclusive = minOf(safeEndExclusive, layout.getLineEnd(line))
                if (lineEndExclusive <= lineStart) continue
                val left = layout.getPrimaryHorizontal(lineStart)
                val right = if (lineEndExclusive < text.length) {
                    layout.getPrimaryHorizontal(lineEndExclusive)
                } else {
                    val lastChar = text[(lineEndExclusive - 1).coerceAtLeast(lineStart)]
                    layout.getPrimaryHorizontal(lineEndExclusive - 1) + subtitle.paint.measureText(lastChar.toString())
                }
                add(
                    Rect(
                        left = leftPadding + minOf(left, right),
                        top = topPadding + layout.getLineTop(line),
                        right = leftPadding + maxOf(left, right),
                        bottom = topPadding + layout.getLineBottom(line)
                    )
                )
            }
        }
    }

    private fun performFloatingLookup(offset: Int, initialAnchorRect: Rect?) {
        val subtitleText = BookReaderFloatingBridge.currentSubtitle()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val settings = loadAudiobookSettingsConfig(this)
        pausePlaybackForFloatingLookupIfNeeded(settings)
        val selection = selectLookupScanText(subtitleText, offset) ?: run {
            hideFloatingLookup()
            return
        }
        val term = selection.text.trim().takeIf { it.isNotBlank() } ?: run {
            hideFloatingLookup()
            return
        }
        val requestNonce = lookupRequestNonce + 1L
        lookupRequestNonce = requestNonce
        serviceScope.launch {
            val dictionaries = withContext(Dispatchers.IO) {
                cachedLookupDictionaries ?: loadAvailableDictionaries(this@AudiobookFloatingOverlayService).also {
                    cachedLookupDictionaries = it
                }
            }
            if (lookupRequestNonce != requestNonce) return@launch
            if (dictionaries.isEmpty()) {
                hideFloatingLookup()
                return@launch
            }
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    computeTapLookupResultsWithWinningCandidate(
                        context = this@AudiobookFloatingOverlayService,
                        dictionaries = dictionaries,
                        query = term
                    )
                }
            }
            if (lookupRequestNonce != requestNonce) return@launch
            result.onSuccess { computed ->
                val hits = computed?.hits.orEmpty()
                if (hits.isEmpty()) {
                    hideFloatingLookup()
                    return@onSuccess
                }
                val matchedLength = hits.firstOrNull { it.matchedLength > 0 }?.matchedLength
                    ?: computed?.query?.length
                    ?: term.length
                    ?: 1
                val trimmedRange = trimSelectionRangeByMatchedLength(selection.range, matchedLength) ?: selection.range
                val finalSelectionText = subtitleText.substring(trimmedRange.first, trimmedRange.last + 1)
                val anchorRects = subtitleTextView
                    ?.let { computeSubtitleAnchorRects(it, trimmedRange) }
                    ?.takeIf { it.isNotEmpty() }
                    ?: initialAnchorRect?.let { listOf(it) }
                    ?: emptyList()
                val groupedResults = groupLookupResultsByTerm(
                    results = hits,
                    dictionaryCssByName = dictionaries.associate { it.name to it.stylesCss },
                    dictionaryPriorityByName = dictionaries.mapIndexed { index, dictionary -> dictionary.name to index }.toMap()
                ).take(3)
                val estimatedAnchorY = anchorRects.maxOfOrNull { it.bottom } ?: (resources.displayMetrics.heightPixels * 0.56f)
                val shouldPlaceBelow = estimatedAnchorY <= (resources.displayMetrics.heightPixels / 2f)
                val layer = buildFloatingLookupLayer(
                    term = finalSelectionText,
                    popupSentence = BookReaderFloatingBridge.currentSubtitle(),
                    sourceTerm = null,
                    groupedResults = groupedResults,
                    selectedRange = trimmedRange,
                    anchor = anchorRects.takeIf { it.isNotEmpty() }?.let { ReaderLookupAnchor(rects = it) },
                    placeBelow = shouldPlaceBelow,
                    preferSidePlacement = false
                )
                // New root lookup after scrolling should not reuse stale card positions.
                floatingLookupCardPositions.clear()
                floatingLookupSession.clear()
                floatingLookupSession.push(layer)
                applySubtitleSelectionHighlight(trimmedRange)
                renderFloatingLookupResults(layer)
            }.onFailure {
                renderFloatingLookupError(it.message ?: getString(R.string.bookreader_lookup_failed))
            }
        }
    }

    private fun renderFloatingLookupLoading(term: String) {
        applySubtitleSelectionHighlight(floatingLookupSession.getOrNull(0)?.selectedRange)
        clearFloatingLookupHosts()
        val layerIndex = floatingLookupSession.lastIndex.coerceAtLeast(0)
        val layer = buildFloatingLookupLayer(term = term, popupSentence = null, groupedResults = emptyList())
        val sizeSpec = computeFloatingLookupPopupSizeSpec(
            windowSize = IntSize(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels),
            anchor = layer.anchor,
            placeBelow = layer.placeBelow,
            preferSidePlacement = layer.preferSidePlacement
        )
        val card = createFloatingLookupCard(layerIndex, layer, FloatingCardMode.Loading, sizeSpec.contentMaxHeightPx).apply {
            layoutParams = FrameLayout.LayoutParams(sizeSpec.widthPx, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        showFloatingLookupHost(layerIndex, layer, card)
    }

    private fun renderFloatingLookupError(message: String) {
        applySubtitleSelectionHighlight(floatingLookupSession.getOrNull(0)?.selectedRange)
        clearFloatingLookupHosts()
        val layerIndex = floatingLookupSession.lastIndex.coerceAtLeast(0)
        val layer = buildFloatingLookupLayer(term = "", popupSentence = null, groupedResults = emptyList())
        val sizeSpec = computeFloatingLookupPopupSizeSpec(
            windowSize = IntSize(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels),
            anchor = layer.anchor,
            placeBelow = layer.placeBelow,
            preferSidePlacement = layer.preferSidePlacement
        )
        val card = createFloatingLookupCard(layerIndex, layer, FloatingCardMode.Error(message), sizeSpec.contentMaxHeightPx).apply {
            layoutParams = FrameLayout.LayoutParams(sizeSpec.widthPx, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        showFloatingLookupHost(layerIndex, layer, card)
    }

    private fun renderFloatingLookupResults(layer: ReaderLookupLayer) {
        applySubtitleSelectionHighlight(floatingLookupSession.getOrNull(0)?.selectedRange)
        if (floatingLookupSession.size == 0) {
            clearFloatingLookupHosts()
            return
        }
        val windowSize = IntSize(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val activeIndex = floatingLookupSession.lastIndex
        val activeLayer = floatingLookupSession.getOrNull(activeIndex) ?: return
        val cards = floatingLookupSession.layers.mapIndexed { index, item ->
            val sizeSpec = computeFloatingLookupPopupSizeSpec(
                windowSize = windowSize,
                anchor = item.anchor,
                placeBelow = item.placeBelow,
                preferSidePlacement = item.preferSidePlacement
            )
            val card = createFloatingLookupCard(
                layerIndex = index,
                layer = item,
                mode = FloatingCardMode.Results,
                maxLookupHeightPx = sizeSpec.contentMaxHeightPx
            ).apply {
                layoutParams = FrameLayout.LayoutParams(
                    sizeSpec.widthPx,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }
            Triple(index, item, card)
        }
        renderFloatingLookupWindows(cards)
        maybeAutoPlayFloatingLookup(activeIndex, activeLayer)
    }

    private fun renderFloatingLookupWindows(
        cards: List<Triple<Int, ReaderLookupLayer, View>>
    ) {
        if (cards.isEmpty()) return
        val layout = floatingLayoutConfig()
        val activeIndex = floatingLookupSession.lastIndex
        val orderedCards = cards.sortedBy { it.first }
        val visibleLayers = orderedCards.map { it.first }.toSet()
        floatingLookupHostViews.keys
            .filterNot { it in visibleLayers }
            .toList()
            .forEach { removeFloatingLookupHost(it) }

        orderedCards.forEach { (layerIndex, layer, card) ->
            val signature = floatingHostSignature(layer)
            val existingHost = floatingLookupHostViews[layerIndex]
            val existingSignature = floatingLookupHostSignatures[layerIndex]
            val reuseHost = existingHost != null && existingSignature == signature
            val host = if (reuseHost) existingHost else {
                FloatingLookupHostLayout(this).apply {
                    clipChildren = false
                    clipToPadding = false
                    addView(card)
                }
            }
            updateFloatingLookupHostInteraction(host, layerIndex, allowDefinitionLookup = true)
            val preferredWidth = card.layoutParams?.width?.takeIf { it > 0 }
                ?: (320 * resources.displayMetrics.density).toInt()
            ensureFloatingHostMeasured(host, preferredWidth)
            val popupSize = IntSize(host.measuredWidth, host.measuredHeight)
            val sourceRects = layerAnchorRects(layer)
            val avoidRects = buildFloatingAvoidRects(layerIndex, layout.gapPx)
            Log.d(FLOATING_LOOKUP_LOG_TAG, "avoid layer=$layerIndex rects=${formatFloatingRectsForLog(avoidRects)}")
            val sizeSpec = computeFloatingLookupPopupSizeSpec(
                windowSize = layout.panelSize,
                anchor = layer.anchor,
                placeBelow = layer.placeBelow,
                preferSidePlacement = layer.preferSidePlacement
            )
            val candidate = calculateFloatingCardPosition(
                sourceRects = sourceRects,
                avoidRects = avoidRects,
                windowSize = layout.panelSize,
                popupContentSize = popupSize,
                placeBelow = layer.placeBelow,
                preferSidePlacement = layer.preferSidePlacement,
                preferredDirection = sizeSpec.preferredDirection,
                strictNoOverlap = layerIndex > 0,
                gapPx = layout.gapPx,
                screenPaddingPx = layout.screenPaddingPx
            ) ?: run {
                Log.d(
                    FLOATING_LOOKUP_LOG_TAG,
                    "rejectShow layer=$layerIndex reason=no_candidate"
                )
                if (layerIndex == activeIndex && activeIndex > 0) {
                    truncateFloatingLookupLayersTo(activeIndex - 1)
                }
                return@forEach
            }
            val shown = showFloatingLookupHost(
                layerIndex = layerIndex,
                layer = layer,
                host = host,
                position = candidate,
                signature = signature,
                sourceRectsOverride = sourceRects,
                avoidRectsOverride = avoidRects
            )
            if (!shown && layerIndex == activeIndex) {
                if (activeIndex > 0) {
                    truncateFloatingLookupLayersTo(activeIndex - 1)
                }
                return
            }
            host.visibility = View.VISIBLE
            if (reuseHost) {
                refreshFloatingLookupHostHighlight(layerIndex, host, layer)
            }
        }
    }

    private fun floatingHostSignature(layer: ReaderLookupLayer): Int {
        return layer.copy(
            anchor = null,
            selectedRange = null,
            highlightedDefinitionKey = null,
            highlightedDefinitionRects = emptyList(),
            highlightedDefinitionNodePathJson = null,
            highlightedDefinitionOffset = null,
            highlightedDefinitionLength = null,
            autoPlayNonce = 0L,
            autoPlayedKey = null
        ).hashCode()
    }

    private fun updateFloatingLookupHostInteraction(host: View, layerIndex: Int, allowDefinitionLookup: Boolean) {
        val returnAction = View.OnClickListener {
            Log.d(FLOATING_LOOKUP_LOG_TAG, "tapReturn layer=$layerIndex")
            truncateFloatingLookupLayersTo(layerIndex)
        }
        (host as? FloatingLookupHostLayout)?.apply {
            interceptAllTouches = !allowDefinitionLookup
            if (layerIndex != floatingLookupSession.lastIndex) {
                isClickable = true
                setOnClickListener(returnAction)
            } else {
                isClickable = false
                setOnClickListener(null)
            }
        }
    }

    private fun showFloatingLookupHost(
        layerIndex: Int,
        layer: ReaderLookupLayer,
        host: View,
        position: IntOffset? = null,
        signature: Int? = null,
        sourceRectsOverride: List<IntRect>? = null,
        avoidRectsOverride: List<IntRect>? = null
    ): Boolean {
        val wm = windowManager ?: return false
        val layout = floatingLayoutConfig()
        val storageKey = layerIndex
        val hostPosition = position ?: floatingLookupCardPositions[storageKey]
            ?: IntOffset((24 * resources.displayMetrics.density).toInt(), (120 * resources.displayMetrics.density).toInt())
        val sourceRects = sourceRectsOverride ?: layerAnchorRects(layer)
        val avoidRects = avoidRectsOverride ?: buildFloatingAvoidRects(layerIndex, layout.gapPx)
        val showEvaluation = evaluateFloatingPlacement(
            layerIndex = layerIndex,
            sourceRects = sourceRects,
            avoidRects = avoidRects,
            popupPosition = hostPosition,
            popupSize = IntSize(host.measuredWidth, host.measuredHeight),
            gapPx = layout.gapPx,
            screenPaddingPx = layout.screenPaddingPx
        )
        if (!showEvaluation.acceptable) {
            Log.d(
                FLOATING_LOOKUP_LOG_TAG,
                "rejectShow layer=$layerIndex pos=${hostPosition.x},${hostPosition.y} guardOverlap=${showEvaluation.metrics.guardOverlap} avoidOverlap=${showEvaluation.metrics.avoidOverlap} distance=${showEvaluation.sourceDistance} maxDistance=${showEvaluation.maxAcceptDistancePx}"
            )
            return false
        }
        val overlapMetrics = computeFloatingLookupOverlapMetrics(
            sourceRects = sourceRects,
            avoidRects = avoidRects,
            popupPosition = hostPosition,
            popupSize = IntSize(host.measuredWidth, host.measuredHeight),
            gapPx = layout.gapPx,
            screenPaddingPx = layout.screenPaddingPx
        )
        val params = createFloatingLookupWindowLayoutParams(hostPosition)
        val currentHost = floatingLookupHostViews[storageKey]
        val isExistingHost = currentHost === host
        if (isExistingHost) {
            runCatching { wm.updateViewLayout(host, params) }
        } else {
            if (currentHost != null) {
                floatingLookupHostSizeListeners.remove(storageKey)?.let { listener ->
                    currentHost.removeOnLayoutChangeListener(listener)
                }
                runCatching { wm.removeView(currentHost) }
            }
            runCatching { wm.addView(host, params) }
            floatingLookupHostViews[storageKey] = host
        }
        attachFloatingLookupHostSizeObserver(layerIndex, host)
        floatingLookupCardPositions[storageKey] = hostPosition
        if (signature != null) {
            floatingLookupHostSignatures[storageKey] = signature
        }
        if (!isExistingHost) {
            // WebView content can expand after first paint; do one delayed reposition pass.
            scheduleFloatingLookupHostReposition(layerIndex, delayMs = 120L, reason = "initialShow")
        }
        val popupRect = IntRect(
            left = hostPosition.x,
            top = hostPosition.y,
            right = hostPosition.x + host.measuredWidth,
            bottom = hostPosition.y + host.measuredHeight
        )
        val sourceBounds = sourceRects.takeIf { it.isNotEmpty() }?.let {
            IntRect(
                left = it.minOf { rect -> rect.left },
                top = it.minOf { rect -> rect.top },
                right = it.maxOf { rect -> rect.right },
                bottom = it.maxOf { rect -> rect.bottom }
            )
        }
        val guardPx = computeFloatingLookupGuardPadding(layout.gapPx, layout.screenPaddingPx)
        val guardBounds = sourceRects.takeIf { it.isNotEmpty() }?.map {
            expandFloatingRect(it, guardPx)
        }?.let {
            IntRect(
                left = it.minOf { rect -> rect.left },
                top = it.minOf { rect -> rect.top },
                right = it.maxOf { rect -> rect.right },
                bottom = it.maxOf { rect -> rect.bottom }
            )
        }
        Log.d(
            FLOATING_LOOKUP_LOG_TAG,
            "show layer=$layerIndex pos=${hostPosition.x},${hostPosition.y} source=${layer.sourceTerm.orEmpty()} placeBelow=${layer.placeBelow} side=${layer.preferSidePlacement} sourceOverlap=${overlapMetrics.sourceOverlap} guardOverlap=${overlapMetrics.guardOverlap} avoidOverlap=${overlapMetrics.avoidOverlap}"
        )
        Log.d(
            FLOATING_LOOKUP_LOG_TAG,
            "rects layer=$layerIndex sourceRects=${formatFloatingRectsForLog(sourceRects)} sourceBounds=${formatFloatingRectForLog(sourceBounds)} guardBounds=${formatFloatingRectForLog(guardBounds)} popupRect=${formatFloatingRectForLog(popupRect)}"
        )
        return true
    }

    private fun formatFloatingRectsForLog(rects: List<IntRect>): String {
        if (rects.isEmpty()) return "[]"
        return rects.joinToString(prefix = "[", postfix = "]") { rect ->
            "${rect.left},${rect.top},${rect.right},${rect.bottom}"
        }
    }

    private fun layerAnchorRects(layer: ReaderLookupLayer): List<IntRect> {
        return layer.anchor
            ?.rects
            ?.filter { !it.isEmpty }
            ?.map { rect ->
                IntRect(
                    left = rect.left.toInt(),
                    top = rect.top.toInt(),
                    right = rect.right.toInt(),
                    bottom = rect.bottom.toInt()
                )
            }
            .orEmpty()
    }

    private fun formatFloatingRectForLog(rect: IntRect?): String {
        if (rect == null) return "null"
        return "${rect.left},${rect.top},${rect.right},${rect.bottom}"
    }

    private fun computeFloatingPopupSourceDistance(
        sourceRects: List<IntRect>,
        popupPosition: IntOffset,
        popupSize: IntSize
    ): Int {
        if (sourceRects.isEmpty()) return 0
        val popupRect = IntRect(
            left = popupPosition.x,
            top = popupPosition.y,
            right = popupPosition.x + popupSize.width,
            bottom = popupPosition.y + popupSize.height
        )
        return sourceRects.minOf { sourceRect -> floatingRectDistance(sourceRect, popupRect) }
    }

    private fun computeFloatingLookupOverlapMetrics(
        sourceRects: List<IntRect>,
        avoidRects: List<IntRect>,
        popupPosition: IntOffset,
        popupSize: IntSize,
        gapPx: Int,
        screenPaddingPx: Int
    ): FloatingLookupOverlapMetrics {
        val popupRect = IntRect(
            left = popupPosition.x,
            top = popupPosition.y,
            right = popupPosition.x + popupSize.width,
            bottom = popupPosition.y + popupSize.height
        )
        val sourceOverlap = sourceRects.maxOfOrNull { rect ->
            floatingRectOverlapArea(rect, popupRect)
        } ?: 0
        val guardPadding = computeFloatingLookupGuardPadding(gapPx, screenPaddingPx)
        val guardOverlap = sourceRects.maxOfOrNull { rect ->
            floatingRectOverlapArea(expandFloatingRect(rect, guardPadding), popupRect)
        } ?: 0
        val avoidOverlap = avoidRects.maxOfOrNull { rect ->
            floatingRectOverlapArea(rect, popupRect)
        } ?: 0
        return FloatingLookupOverlapMetrics(
            sourceOverlap = sourceOverlap,
            guardOverlap = guardOverlap,
            avoidOverlap = avoidOverlap
        )
    }

    private data class FloatingLookupOverlapMetrics(
        val sourceOverlap: Int,
        val guardOverlap: Int,
        val avoidOverlap: Int
    )

    private data class FloatingLayoutConfig(
        val panelSize: IntSize,
        val gapPx: Int,
        val screenPaddingPx: Int
    )

    private data class FloatingPlacementEvaluation(
        val acceptable: Boolean,
        val metrics: FloatingLookupOverlapMetrics,
        val sourceDistance: Int,
        val maxAcceptDistancePx: Int
    )

    private fun floatingLayoutConfig(): FloatingLayoutConfig {
        val density = resources.displayMetrics.density
        return FloatingLayoutConfig(
            panelSize = IntSize(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels),
            gapPx = (10 * density).toInt(),
            screenPaddingPx = (12 * density).toInt()
        )
    }

    private fun rectBounds(rects: List<IntRect>): IntRect? {
        if (rects.isEmpty()) return null
        return IntRect(
            left = rects.minOf { it.left },
            top = rects.minOf { it.top },
            right = rects.maxOf { it.right },
            bottom = rects.maxOf { it.bottom }
        )
    }

    private fun buildFloatingAvoidRects(layerIndex: Int, gapPx: Int): List<IntRect> {
        // Single-layer lookup mode: no cross-layer avoidance needed.
        return emptyList()
    }

    private fun evaluateFloatingPlacement(
        layerIndex: Int,
        sourceRects: List<IntRect>,
        avoidRects: List<IntRect>,
        popupPosition: IntOffset,
        popupSize: IntSize,
        gapPx: Int,
        screenPaddingPx: Int
    ): FloatingPlacementEvaluation {
        val metrics = computeFloatingLookupOverlapMetrics(
            sourceRects = sourceRects,
            avoidRects = avoidRects,
            popupPosition = popupPosition,
            popupSize = popupSize,
            gapPx = gapPx,
            screenPaddingPx = screenPaddingPx
        )
        val sourceDistance = computeFloatingPopupSourceDistance(
            sourceRects = sourceRects,
            popupPosition = popupPosition,
            popupSize = popupSize
        )
        val maxAcceptDistancePx = (resources.displayMetrics.density * 220f).toInt()
        val acceptable = layerIndex <= 0 || (
            metrics.guardOverlap <= 0 &&
                metrics.avoidOverlap <= 0
            )
        return FloatingPlacementEvaluation(
            acceptable = acceptable,
            metrics = metrics,
            sourceDistance = sourceDistance,
            maxAcceptDistancePx = maxAcceptDistancePx
        )
    }

    private fun createFloatingLookupWindowLayoutParams(position: IntOffset): WindowManager.LayoutParams {
        val windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = position.x
            y = position.y
        }
    }

    private fun clearFloatingLookupHosts() {
        val wm = windowManager
        if (wm != null) {
            floatingLookupHostViews.values.forEach { host ->
                runCatching { wm.removeView(host) }
            }
        }
        floatingLookupHostViews.forEach { (layerIndex, host) ->
            floatingLookupHostSizeListeners.remove(layerIndex)?.let { listener ->
                host.removeOnLayoutChangeListener(listener)
            }
        }
        floatingLookupHostViews.clear()
        floatingLookupHostSignatures.clear()
        floatingLookupHostSizeListeners.clear()
    }

    private fun removeFloatingLookupHost(layerIndex: Int) {
        val targetKey = if (floatingLookupHostViews.containsKey(layerIndex)) layerIndex else SINGLE_FLOATING_HOST_KEY
        floatingLookupRepositionJobs.remove(targetKey)?.cancel()
        val wm = windowManager
        val host = floatingLookupHostViews.remove(targetKey)
        if (host != null) {
            floatingLookupHostSizeListeners.remove(targetKey)?.let { listener ->
                host.removeOnLayoutChangeListener(listener)
            }
        }
        if (wm != null && host != null) {
            runCatching { wm.removeView(host) }
        }
        floatingLookupHostSignatures.remove(targetKey)
    }

    private fun attachFloatingLookupHostSizeObserver(layerIndex: Int, host: View) {
        val storageKey = layerIndex
        floatingLookupHostSizeListeners.remove(storageKey)?.let { existing ->
            host.removeOnLayoutChangeListener(existing)
        }
        val listener = View.OnLayoutChangeListener { _, _, _, right, bottom, _, _, oldRight, oldBottom ->
            val newWidth = right.coerceAtLeast(0)
            val newHeight = bottom.coerceAtLeast(0)
            val oldWidth = oldRight.coerceAtLeast(0)
            val oldHeight = oldBottom.coerceAtLeast(0)
            if (newWidth != oldWidth || newHeight != oldHeight) {
                Log.d(
                    FLOATING_LOOKUP_LOG_TAG,
                    "sizeChanged layer=$layerIndex old=${oldWidth}x$oldHeight new=${newWidth}x$newHeight"
                )
                scheduleFloatingLookupHostReposition(layerIndex, reason = "sizeChanged")
            }
        }
        host.addOnLayoutChangeListener(listener)
        floatingLookupHostSizeListeners[storageKey] = listener
    }

    private fun scheduleFloatingLookupHostReposition(
        layerIndex: Int,
        delayMs: Long = 48L,
        reason: String = "unspecified"
    ) {
        if (layerIndex < 0) return
        val storageKey = layerIndex
        floatingLookupRepositionJobs.remove(storageKey)?.cancel()
        floatingLookupRepositionJobs[storageKey] = serviceScope.launch {
            delay(delayMs)
            Log.d(FLOATING_LOOKUP_LOG_TAG, "repositionRequest layer=$layerIndex reason=$reason delayMs=$delayMs")
            repositionFloatingLookupHost(layerIndex)
            floatingLookupRepositionJobs.remove(storageKey)
        }
    }

    private fun repositionFloatingLookupHost(layerIndex: Int) {
        val wm = windowManager ?: return
        val layer = floatingLookupSession.getOrNull(layerIndex) ?: return
        val storageKey = layerIndex
        val host = floatingLookupHostViews[storageKey] ?: return
        val layout = floatingLayoutConfig()
        val density = resources.displayMetrics.density
        val measureWidth = host.measuredWidth.takeIf { it > 0 }
            ?: host.width.takeIf { it > 0 }
            ?: ((320 * density).toInt())
        ensureFloatingHostMeasured(host, measureWidth, force = true)
        val popupSize = IntSize(host.measuredWidth, host.measuredHeight)
        val sourceRects = layerAnchorRects(layer)
        val finalAvoidRects = buildFloatingAvoidRects(layerIndex, layout.gapPx)
        val candidate = calculateFloatingCardPosition(
            sourceRects = sourceRects,
            avoidRects = finalAvoidRects,
            windowSize = layout.panelSize,
            popupContentSize = popupSize,
            placeBelow = layer.placeBelow,
            preferSidePlacement = layer.preferSidePlacement,
            preferredDirection = computeFloatingLookupPopupSizeSpec(
                windowSize = layout.panelSize,
                anchor = layer.anchor,
                placeBelow = layer.placeBelow,
                preferSidePlacement = layer.preferSidePlacement
            ).preferredDirection,
            strictNoOverlap = layerIndex > 0,
            gapPx = layout.gapPx,
            screenPaddingPx = layout.screenPaddingPx
        ) ?: run {
            Log.d(
                FLOATING_LOOKUP_LOG_TAG,
                "skipRelayout layer=$layerIndex reason=no_candidate_keep_current"
            )
            return
        }
        val relayoutEvaluation = evaluateFloatingPlacement(
            layerIndex = layerIndex,
            sourceRects = sourceRects,
            avoidRects = finalAvoidRects,
            popupPosition = candidate,
            popupSize = popupSize,
            gapPx = layout.gapPx,
            screenPaddingPx = layout.screenPaddingPx
        )
        if (!relayoutEvaluation.acceptable) {
            Log.d(
                FLOATING_LOOKUP_LOG_TAG,
                "skipRelayout layer=$layerIndex pos=${candidate.x},${candidate.y} guardOverlap=${relayoutEvaluation.metrics.guardOverlap} avoidOverlap=${relayoutEvaluation.metrics.avoidOverlap} distance=${relayoutEvaluation.sourceDistance} maxDistance=${relayoutEvaluation.maxAcceptDistancePx}"
            )
            return
        }
        val currentPosition = floatingLookupCardPositions[storageKey]
        if (currentPosition == candidate) return
        runCatching { wm.updateViewLayout(host, createFloatingLookupWindowLayoutParams(candidate)) }
        floatingLookupCardPositions[storageKey] = candidate
        Log.d(FLOATING_LOOKUP_LOG_TAG, "relayout layer=$layerIndex pos=${candidate.x},${candidate.y}")
        refreshFloatingLookupHostHighlight(layerIndex, host, layer)
    }

    private fun ensureFloatingHostMeasured(host: View, width: Int, force: Boolean = false) {
        if (!force && host.measuredWidth > 0 && host.measuredHeight > 0) return
        host.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
    }

    private fun calculateFloatingCardPosition(
        sourceRects: List<IntRect>,
        avoidRects: List<IntRect>,
        windowSize: IntSize,
        popupContentSize: IntSize,
        placeBelow: Boolean,
        preferSidePlacement: Boolean,
        preferredDirection: FloatingPopupDirection,
        strictNoOverlap: Boolean = false,
        gapPx: Int,
        screenPaddingPx: Int
    ): IntOffset? {
        val safeSourceRects = sourceRects.takeIf { it.isNotEmpty() } ?: listOf(
            IntRect(
                left = (windowSize.width * 0.4f).toInt(),
                top = (windowSize.height * 0.36f).toInt(),
                right = (windowSize.width * 0.6f).toInt(),
                bottom = (windowSize.height * 0.46f).toInt()
            )
        )
        val guardPx = computeFloatingLookupGuardPadding(gapPx, screenPaddingPx)
        val guardRects = safeSourceRects.map { rect -> expandFloatingRect(rect, guardPx) }
        val sourceBounds = IntRect(
            left = safeSourceRects.minOf { it.left },
            top = safeSourceRects.minOf { it.top },
            right = safeSourceRects.maxOf { it.right },
            bottom = safeSourceRects.maxOf { it.bottom }
        )
        val guardBounds = IntRect(
            left = guardRects.minOf { it.left },
            top = guardRects.minOf { it.top },
            right = guardRects.maxOf { it.right },
            bottom = guardRects.maxOf { it.bottom }
        )
        val maxX = (windowSize.width - popupContentSize.width - screenPaddingPx).coerceAtLeast(screenPaddingPx)
        val maxY = (windowSize.height - popupContentSize.height - screenPaddingPx).coerceAtLeast(screenPaddingPx)
        val preferredX = ((sourceBounds.left + sourceBounds.right) / 2 - popupContentSize.width / 2)
            .coerceIn(screenPaddingPx, maxX)
        val lowerY = (guardBounds.bottom + gapPx).coerceIn(screenPaddingPx, maxY)
        val upperY = (guardBounds.top - popupContentSize.height - gapPx).coerceIn(screenPaddingPx, maxY)
        val yOrder = if (placeBelow) listOf(lowerY, upperY) else listOf(upperY, lowerY)
        val forbiddenRects = (guardRects + avoidRects).distinctBy { rect ->
            "${rect.left},${rect.top},${rect.right},${rect.bottom}"
        }

        yOrder.forEach { y ->
            findFloatingNonOverlappingXAtY(
                y = y,
                preferredX = preferredX,
                minX = screenPaddingPx,
                maxX = maxX,
                popupWidth = popupContentSize.width,
                popupHeight = popupContentSize.height,
                forbiddenRects = forbiddenRects
            )?.let { x ->
                return IntOffset(x, y)
            }
        }

        if (strictNoOverlap) return null

        return yOrder.firstOrNull()?.let { y ->
            IntOffset(preferredX, y)
        }
    }

    private data class FloatingXSegment(
        val start: Int,
        val end: Int
    )

    private fun findFloatingNonOverlappingXAtY(
        y: Int,
        preferredX: Int,
        minX: Int,
        maxX: Int,
        popupWidth: Int,
        popupHeight: Int,
        forbiddenRects: List<IntRect>
    ): Int? {
        if (minX > maxX) return null
        var segments = mutableListOf(FloatingXSegment(minX, maxX))
        val popupTop = y
        val popupBottom = y + popupHeight

        forbiddenRects.forEach { rect ->
            val verticalOverlap = popupBottom > rect.top && popupTop < rect.bottom
            if (!verticalOverlap) return@forEach
            val blockStart = rect.left - popupWidth + 1
            val blockEnd = rect.right - 1
            segments = subtractFloatingBlockedX(
                segments = segments,
                blockStart = blockStart,
                blockEnd = blockEnd
            ).toMutableList()
            if (segments.isEmpty()) return null
        }

        var bestX: Int? = null
        var bestDistance = Int.MAX_VALUE
        segments.forEach { seg ->
            val candidate = preferredX.coerceIn(seg.start, seg.end)
            val distance = kotlin.math.abs(candidate - preferredX)
            if (distance < bestDistance) {
                bestDistance = distance
                bestX = candidate
            }
        }
        return bestX
    }

    private fun subtractFloatingBlockedX(
        segments: List<FloatingXSegment>,
        blockStart: Int,
        blockEnd: Int
    ): List<FloatingXSegment> {
        if (blockStart > blockEnd) return segments
        val result = mutableListOf<FloatingXSegment>()
        segments.forEach { seg ->
            if (blockEnd < seg.start || blockStart > seg.end) {
                result += seg
                return@forEach
            }
            if (blockStart > seg.start) {
                result += FloatingXSegment(seg.start, blockStart - 1)
            }
            if (blockEnd < seg.end) {
                result += FloatingXSegment(blockEnd + 1, seg.end)
            }
        }
        return result.filter { it.start <= it.end }
    }

    private fun buildFloatingPopupCandidates(
        anchorLeft: Int,
        anchorRight: Int,
        anchorTop: Int,
        anchorBottom: Int,
        popupContentSize: IntSize,
        maxX: Int,
        maxY: Int,
        preferSidePlacement: Boolean,
        placeBelow: Boolean,
        gapPx: Int,
        screenPaddingPx: Int,
        requireFit: Boolean
    ): List<IntOffset> {
        fun candidate(offset: IntOffset): IntOffset? {
            if (!requireFit) return offset
            return offset.takeIf {
                it.x in screenPaddingPx..maxX && it.y in screenPaddingPx..maxY
            }
        }

        val anchorCenterX = (anchorLeft + anchorRight) / 2
        val anchorCenterY = (anchorTop + anchorBottom) / 2
        val alignedX = anchorLeft.coerceIn(screenPaddingPx, maxX)
        val alignedY = anchorTop.coerceIn(screenPaddingPx, maxY)

        fun clampX(value: Int): Int = value.coerceIn(screenPaddingPx, maxX)
        fun clampY(value: Int): Int = value.coerceIn(screenPaddingPx, maxY)

        val verticalXOffsets = listOf(
            anchorLeft,
            anchorCenterX - (popupContentSize.width / 2),
            anchorRight - popupContentSize.width,
            anchorLeft - (popupContentSize.width / 4),
            anchorRight - ((popupContentSize.width * 3) / 4),
            anchorCenterX - (popupContentSize.width / 3),
            anchorCenterX - ((popupContentSize.width * 2) / 3)
        ).map(::clampX).distinct()

        val sideYOffsets = listOf(
            anchorTop,
            anchorCenterY - (popupContentSize.height / 2),
            anchorBottom - popupContentSize.height,
            anchorTop - (popupContentSize.height / 4),
            anchorBottom - ((popupContentSize.height * 3) / 4),
            anchorCenterY - (popupContentSize.height / 3),
            anchorCenterY - ((popupContentSize.height * 2) / 3)
        ).map(::clampY).distinct()

        val belowY = anchorBottom + gapPx
        val aboveY = anchorTop - popupContentSize.height - gapPx
        val rightX = anchorRight + gapPx
        val leftX = anchorLeft - popupContentSize.width - gapPx

        val verticalCandidates = if (placeBelow) {
            verticalXOffsets.flatMap { x ->
                listOf(
                    IntOffset(x, belowY),
                    IntOffset(x, aboveY)
                )
            }
        } else {
            verticalXOffsets.flatMap { x ->
                listOf(
                    IntOffset(x, aboveY),
                    IntOffset(x, belowY)
                )
            }
        }
        val sideCandidates = sideYOffsets.flatMap { y ->
            listOf(
                IntOffset(rightX, y),
                IntOffset(leftX, y)
            )
        }
        val ordered = if (preferSidePlacement) {
            sideCandidates + verticalCandidates
        } else {
            verticalCandidates + sideCandidates
        }
        return ordered.mapNotNull(::candidate)
    }

    private fun computeFloatingLookupGuardPadding(
        gapPx: Int,
        screenPaddingPx: Int
    ): Int {
        return maxOf(gapPx * 2, screenPaddingPx)
    }

    private fun floatingCandidateDirection(
        sourceRect: IntRect,
        candidate: IntOffset,
        popupContentSize: IntSize
    ): FloatingPopupDirection {
        val candidateRect = IntRect(
            left = candidate.x,
            top = candidate.y,
            right = candidate.x + popupContentSize.width,
            bottom = candidate.y + popupContentSize.height
        )
        return when {
            candidateRect.left >= sourceRect.right -> FloatingPopupDirection.RIGHT
            candidateRect.right <= sourceRect.left -> FloatingPopupDirection.LEFT
            candidateRect.top >= sourceRect.bottom -> FloatingPopupDirection.BELOW
            else -> FloatingPopupDirection.ABOVE
        }
    }

    private fun expandFloatingRect(rect: IntRect, padding: Int): IntRect {
        return IntRect(
            left = rect.left - padding,
            top = rect.top - padding,
            right = rect.right + padding,
            bottom = rect.bottom + padding
        )
    }

    private fun floatingRectsOverlap(a: IntRect, b: IntRect): Boolean {
        return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
    }

    private fun floatingRectDistance(a: IntRect, b: IntRect): Int {
        val dx = when {
            b.left >= a.right -> b.left - a.right
            a.left >= b.right -> a.left - b.right
            else -> 0
        }
        val dy = when {
            b.top >= a.bottom -> b.top - a.bottom
            a.top >= b.bottom -> a.top - b.bottom
            else -> 0
        }
        return dx + dy
    }

    private fun floatingRectOverlapArea(a: IntRect, b: IntRect): Int {
        val overlapWidth = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
        val overlapHeight = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        return overlapWidth * overlapHeight
    }

    private fun computeFloatingLookupPopupSizeSpec(
        windowSize: IntSize,
        anchor: ReaderLookupAnchor?,
        placeBelow: Boolean,
        preferSidePlacement: Boolean
    ): FloatingLookupPopupSizeSpec {
        val density = resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val screenWidthDp = (windowSize.width / density).coerceAtLeast(1f)
        val screenHeightDp = (windowSize.height / density).coerceAtLeast(1f)
        val anchorBounds = anchor?.rects?.filter { !it.isEmpty }?.let {
            Rect(
                left = it.minOf { rect -> rect.left },
                top = it.minOf { rect -> rect.top },
                right = it.maxOf { rect -> rect.right },
                bottom = it.maxOf { rect -> rect.bottom }
            )
        }
        val anchorLeftDp = ((anchorBounds?.left ?: windowSize.width * 0.4f) / density).coerceIn(0f, screenWidthDp)
        val anchorRightDp = ((anchorBounds?.right ?: windowSize.width * 0.6f) / density).coerceIn(0f, screenWidthDp)
        val anchorTopDp = ((anchorBounds?.top ?: windowSize.height * 0.46f) / density).coerceIn(0f, screenHeightDp)
        val anchorBottomDp = ((anchorBounds?.bottom ?: windowSize.height * 0.56f) / density).coerceIn(0f, screenHeightDp)
        val screenPaddingDp = 12f
        val guardDp = 24f
        val preferredMinWidthDp = 220f
        val maxWidthDp = 320f
        val preferredMinContentHeightDp = 96f
        val maxContentHeightDp = 260f
        val chromeReserveDp = 112f

        data class DirectionCap(val direction: FloatingPopupDirection, val widthCap: Float, val contentHeightCap: Float)

        val fullWidthCap = (screenWidthDp - screenPaddingDp * 2f).coerceAtLeast(0f)
        val fullHeightCap = (screenHeightDp - screenPaddingDp * 2f - chromeReserveDp).coerceAtLeast(0f)
        val belowCap = DirectionCap(
            direction = FloatingPopupDirection.BELOW,
            widthCap = fullWidthCap,
            contentHeightCap = (screenHeightDp - anchorBottomDp - guardDp - screenPaddingDp - chromeReserveDp).coerceAtLeast(0f)
        )
        val aboveCap = DirectionCap(
            direction = FloatingPopupDirection.ABOVE,
            widthCap = fullWidthCap,
            contentHeightCap = (anchorTopDp - guardDp - screenPaddingDp - chromeReserveDp).coerceAtLeast(0f)
        )
        val rightCap = DirectionCap(
            direction = FloatingPopupDirection.RIGHT,
            widthCap = (screenWidthDp - anchorRightDp - guardDp - screenPaddingDp).coerceAtLeast(0f),
            contentHeightCap = fullHeightCap
        )
        val leftCap = DirectionCap(
            direction = FloatingPopupDirection.LEFT,
            widthCap = (anchorLeftDp - guardDp - screenPaddingDp).coerceAtLeast(0f),
            contentHeightCap = fullHeightCap
        )

        val verticalCaps = if (placeBelow) listOf(belowCap, aboveCap) else listOf(aboveCap, belowCap)
        val sideCaps = if (preferSidePlacement) listOf(rightCap, leftCap) else listOf(leftCap, rightCap)
        val bestCap =
            verticalCaps.firstOrNull { it.widthCap >= preferredMinWidthDp && it.contentHeightCap >= preferredMinContentHeightDp }
                ?: sideCaps.firstOrNull { it.widthCap >= preferredMinWidthDp && it.contentHeightCap >= preferredMinContentHeightDp }
                ?: (verticalCaps + sideCaps).maxByOrNull { it.widthCap * it.contentHeightCap }

        return FloatingLookupPopupSizeSpec(
            widthPx = ((bestCap?.widthCap ?: maxWidthDp).coerceIn(1f, maxWidthDp) * density).toInt().coerceAtLeast(1),
            contentMaxHeightPx = ((bestCap?.contentHeightCap ?: maxContentHeightDp).coerceIn(1f, maxContentHeightDp) * density).toInt().coerceAtLeast(1),
            preferredDirection = bestCap?.direction ?: if (placeBelow) FloatingPopupDirection.BELOW else FloatingPopupDirection.ABOVE
        )
    }

    private fun hideFloatingLookup() {
        lookupRequestNonce += 1L
        floatingLookupSession.clear()
        floatingLookupCardPositions.clear()
        applySubtitleSelectionHighlight(null)
        clearFloatingLookupHosts()
        updateFloatingLookupPanelPosition()
        resumePlaybackIfPausedByFloatingLookup()
    }

    private fun pausePlaybackForFloatingLookupIfNeeded(settings: AudiobookSettingsConfig) {
        if (settings.pausePlaybackOnLookup && BookReaderFloatingBridge.isPlaying()) {
            BookReaderFloatingBridge.setPlaying(play = false)
            playbackPausedByFloatingLookup = true
        }
    }

    private fun resumePlaybackIfPausedByFloatingLookup() {
        if (!playbackPausedByFloatingLookup) return
        playbackPausedByFloatingLookup = false
        BookReaderFloatingBridge.setPlaying(play = true)
    }

    private sealed interface FloatingCardMode {
        data object Results : FloatingCardMode
        data object Loading : FloatingCardMode
        data class Error(val message: String) : FloatingCardMode
    }

    private data class FloatingLookupPopupSizeSpec(
        val widthPx: Int,
        val contentMaxHeightPx: Int,
        val preferredDirection: FloatingPopupDirection
    )

    private enum class FloatingPopupDirection {
        ABOVE,
        BELOW,
        LEFT,
        RIGHT
    }

    private data class FloatingLookupPalette(
        val cardFill: Int,
        val cardStroke: Int,
        val divider: Int,
        val title: Int,
        val reading: Int,
        val body: Int,
        val bodyCss: String,
        val footerText: Int,
        val dictionaryFill: Int,
        val dictionaryText: Int
    )

    private fun floatingLookupPalette(): FloatingLookupPalette {
        val darkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (darkMode) {
            FloatingLookupPalette(
                cardFill = 0xEE121826.toInt(),
                cardStroke = 0x334B5563,
                divider = 0x1FFFFFFF,
                title = 0xFFF8FAFC.toInt(),
                reading = 0xFF94A3B8.toInt(),
                body = 0xFFE5E7EB.toInt(),
                bodyCss = "#E5E7EB",
                footerText = 0xFFCBD5E1.toInt(),
                dictionaryFill = 0xFF5B4B8A.toInt(),
                dictionaryText = 0xFFFFFFFF.toInt()
            )
        } else {
            FloatingLookupPalette(
                cardFill = 0xECF5F7FB.toInt(),
                cardStroke = 0x332A3442,
                divider = 0x12000000,
                title = 0xFF111827.toInt(),
                reading = 0xFF6B7280.toInt(),
                body = 0xFF1F2937.toInt(),
                bodyCss = "#1F2937",
                footerText = 0xFF4B5563.toInt(),
                dictionaryFill = 0xFFC09AE8.toInt(),
                dictionaryText = 0xFFFFFFFF.toInt()
            )
        }
    }

    private fun createFloatingLookupCard(
        layerIndex: Int,
        layer: ReaderLookupLayer,
        mode: FloatingCardMode,
        maxLookupHeightPx: Int
    ): View {
        val density = resources.displayMetrics.density
        val palette = floatingLookupPalette()
        val isTopLayer = layerIndex == floatingLookupSession.lastIndex
        val isPreviousLayer = layerIndex == floatingLookupSession.lastIndex - 1
        val allowDefinitionLookup = isTopLayer || isPreviousLayer
        val allowCardTapReturn = !isTopLayer
        val actionState = buildLookupCardActionState(
            sourceTerm = layer.sourceTerm,
            layerIndex = layerIndex,
            sessionSize = floatingLookupSession.size,
            showRangeSelection = false,
            showPlayAudio = loadAudiobookSettingsConfig(this).lookupPlaybackAudioEnabled,
            showAddToAnki = true
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
        }
        content.addView(createLookupHeader(layerIndex))
        when (mode) {
            FloatingCardMode.Loading -> {
                content.addView(createLookupTitle(layer.selectionText.orEmpty(), reading = null, palette = palette))
                content.addView(createLookupBodyText(getString(R.string.common_querying), topMarginDp = 8f, palette = palette))
            }
            is FloatingCardMode.Error -> {
                content.addView(createLookupBodyText(mode.message, topMarginDp = 0f, palette = palette))
            }
            FloatingCardMode.Results -> {
                val presentation = buildLookupPresentation(layer)
                presentation.forEachIndexed { index, groupedPresentation ->
                    val grouped = groupedPresentation.groupedResult
                    if (index > 0) {
                        content.addView(View(this).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                (1 * density).toInt().coerceAtLeast(1)
                            ).apply {
                                topMargin = (10 * density).toInt()
                                bottomMargin = (10 * density).toInt()
                            }
                            setBackgroundColor(palette.divider)
                        })
                    }
                    content.addView(
                        LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.TOP
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                topMargin = ((if (index == 0) 6f else 0f) * density).toInt()
                            }
                            addView(
                                createLookupTitle(grouped.term, grouped.reading, palette = palette).apply {
                                    layoutParams = LinearLayout.LayoutParams(
                                        0,
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                        1f
                                    ).apply {
                                        marginEnd = (8 * density).toInt()
                                    }
                                }
                            )
                            addView(createLookupActionRow(grouped, layerIndex, layer, actionState, inline = true))
                        }
                    )
                    groupedPresentation.dictionaries.forEachIndexed { dictIndex, dictionaryPresentation ->
                        content.addView(
                            createFloatingDictionaryHeader(
                                dictionaryName = dictionaryPresentation.dictionaryName,
                                expanded = dictionaryPresentation.expanded,
                                topMarginDp = if (dictIndex == 0) 8f else 10f,
                                palette = palette,
                                onToggle = {
                                    floatingLookupSession.toggleCollapsedSection(
                                        layerIndex,
                                        dictionaryPresentation.sectionKey,
                                        dictionaryPresentation.expanded
                                    )
                                    floatingLookupSession.getOrNull(layerIndex)?.let(::renderFloatingLookupResults)
                                }
                            )
                        )
                        if (dictionaryPresentation.expanded) {
                            dictionaryPresentation.definitions.forEach { definitionPresentation ->
                                content.addView(
                                    createLookupDefinitionWebView(
                                        definition = definitionPresentation.definitionHtml,
                                        dictionaryCss = definitionPresentation.dictionaryCss,
                                        topMarginDp = 6f,
                                        palette = palette,
                                        layerIndex = layerIndex,
                                        layer = layer,
                                        definitionKey = definitionPresentation.definitionKey,
                                        enableLookupTap = allowDefinitionLookup
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        val scrollView = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(
                    widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(maxLookupHeightPx, MeasureSpec.AT_MOST)
                )
            }
        }.apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(content)
        }

        val cardContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scrollView)
            addView(createLookupFooter(palette))
        }

        return FloatingLookupHostLayout(this).apply {
            background = createRoundedBackground(palette.cardFill, cornerDp = 18f, strokeColor = palette.cardStroke)
            elevation = 8 * density
            if (allowCardTapReturn) {
                isClickable = true
                setOnClickListener {
                    Log.d(FLOATING_LOOKUP_LOG_TAG, "tapReturn layer=$layerIndex")
                    truncateFloatingLookupLayersTo(layerIndex)
                }
            }
            interceptAllTouches = !allowDefinitionLookup
            addView(
                cardContent,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun createLookupHeader(layerIndex: Int): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(createTextButton("←") {
                closeFloatingLookupLayer(layerIndex)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    (36 * density).toInt(),
                    (32 * density).toInt()
                ).apply { marginEnd = (8 * density).toInt() }
                alpha = 1f
            })
            addView(View(this@AudiobookFloatingOverlayService).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
        }
    }

    private fun closeFloatingLookupLayer(layerIndex: Int) {
        applyCloseLookupAction(floatingLookupSession.closeLayerOrClear(layerIndex))
    }

    private fun applyCloseLookupAction(closeAction: CloseLookupAction) {
        when (closeAction) {
            CloseLookupAction.ClearAll -> hideFloatingLookup()
            is CloseLookupAction.ShowLayer -> truncateFloatingLookupLayersTo(closeAction.index)
        }
    }

    private fun createLookupFooter(palette: FloatingLookupPalette): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (4 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            addView(createFooterActionButton(getString(R.string.common_close_all), palette) {
                hideFloatingLookup()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            addView(View(this@AudiobookFloatingOverlayService).apply {
                layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
            })
        }
    }

    private fun createFooterActionButton(label: String, palette: FloatingLookupPalette, onClick: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(palette.footerText)
            minHeight = (32 * density).toInt()
            background = null
            setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            setOnClickListener { onClick() }
        }
    }

    private fun createFloatingDictionaryHeader(
        dictionaryName: String,
        expanded: Boolean,
        topMarginDp: Float,
        palette: FloatingLookupPalette,
        onToggle: () -> Unit
    ): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = if (expanded) "$dictionaryName ▾" else "$dictionaryName ▸"
            textSize = 12f
            setTextColor(palette.dictionaryText)
            background = createRoundedBackground(palette.dictionaryFill, cornerDp = 6f, strokeColor = 0x00FFFFFF)
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (topMarginDp * density).toInt()
            }
            setOnClickListener { onToggle() }
        }
    }

    private fun truncateFloatingLookupLayersTo(index: Int, render: Boolean = true) {
        if (index !in floatingLookupSession.layers.indices) return
        floatingLookupSession.truncateTo(index)
        val removedIndices = floatingLookupCardPositions.keys.filter { it > index }
        removedIndices.forEach(::removeFloatingLookupHost)
        floatingLookupCardPositions.keys.removeAll { it > index }
        if (render) {
            floatingLookupSession.getOrNull(index)?.let(::renderFloatingLookupResults)
        }
    }

    private fun buildFloatingLookupLayer(
        term: String,
        popupSentence: String?,
        groupedResults: List<GroupedLookupResult>,
        sourceTerm: String? = null,
        anchor: ReaderLookupAnchor? = null,
        placeBelow: Boolean = true,
        preferSidePlacement: Boolean = false,
        selectedRange: IntRange? = null,
        highlightedDefinitionKey: String? = null,
        highlightedDefinitionRects: List<Rect> = emptyList(),
        highlightedDefinitionNodePathJson: String? = null,
        highlightedDefinitionOffset: Int? = null,
        highlightedDefinitionLength: Int? = null
    ): ReaderLookupLayer {
        return buildLookupLayerFromGroupedResults(
            groupedResults = groupedResults,
            loading = false,
            error = null,
            sourceTerm = sourceTerm,
            cue = null,
            cueIndex = null,
            anchorOffset = null,
            anchor = anchor,
            placeBelow = placeBelow,
            preferSidePlacement = preferSidePlacement,
            selectedRange = selectedRange,
            selectionText = term.takeIf { it.isNotBlank() },
            popupSentence = popupSentence,
            highlightedDefinitionKey = highlightedDefinitionKey,
            highlightedDefinitionRects = highlightedDefinitionRects,
            highlightedDefinitionNodePathJson = highlightedDefinitionNodePathJson,
            highlightedDefinitionOffset = highlightedDefinitionOffset,
            highlightedDefinitionLength = highlightedDefinitionLength,
            collapsedSections = emptyMap(),
            autoPlayNonce = System.nanoTime(),
            autoPlayedKey = null
        )
    }

    private fun createLookupTitle(
        term: String,
        reading: String?,
        topMarginDp: Float = 0f,
        palette: FloatingLookupPalette
    ): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (topMarginDp * density).toInt()
            }
            addView(TextView(this@AudiobookFloatingOverlayService).apply {
                text = term
                textSize = 24f
                setTextColor(palette.title)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            if (!reading.isNullOrBlank()) {
                addView(TextView(this@AudiobookFloatingOverlayService).apply {
                    text = reading
                    textSize = 13f
                    setTextColor(palette.reading)
                    setPadding(0, (2 * density).toInt(), 0, 0)
                })
            }
        }
    }

    private fun createLookupActionRow(
        grouped: GroupedLookupResult,
        layerIndex: Int,
        layer: ReaderLookupLayer,
        actionState: LookupCardActionState,
        inline: Boolean = false
    ): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or if (inline) Gravity.TOP else Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                if (inline) LinearLayout.LayoutParams.WRAP_CONTENT else LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = if (inline) 0 else (8 * density).toInt()
            }
            if (actionState.showPlayAudio) {
                addView(createTextButton("\u266B") {
                    playLookupAudioForTerm(
                        context = this@AudiobookFloatingOverlayService,
                        term = grouped.term,
                        reading = grouped.reading,
                        settings = loadAudiobookSettingsConfig(this@AudiobookFloatingOverlayService)
                    ) { error ->
                        Toast.makeText(this@AudiobookFloatingOverlayService, error.take(160), Toast.LENGTH_SHORT).show()
                    }
                })
            }
            if (actionState.showAddToAnki) {
                addView(createTextButton("+") {
                    exportFloatingLookupToAnki(grouped, layerIndex, layer)
                }.apply {
                    (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 0
                })
            }
        }
    }

    private fun createLookupDefinitionWebView(
        definition: String,
        dictionaryCss: String?,
        topMarginDp: Float,
        palette: FloatingLookupPalette,
        layerIndex: Int,
        layer: ReaderLookupLayer,
        definitionKey: String,
        enableLookupTap: Boolean
    ): View {
        val density = resources.displayMetrics.density
        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (topMarginDp * density).toInt()
            }
        }
        val bridge = DefinitionLookupBridge(onLookupTap = { tapData ->
            Log.d(
                FLOATING_LOOKUP_TAP_LOG_TAG,
                "bridge onTap layer=$layerIndex key=$definitionKey scanLen=${tapData.scanText.length} textLen=${tapData.text.length} screenChars=${tapData.screenCharRects.size}"
            )
            performFloatingRecursiveLookup(layerIndex, definitionKey, tapData)
        })
        val html = buildDefinitionHtml(
            definitionHtml = definition.trim(),
            indexLabel = "",
            dictionaryName = null,
            dictionaryCss = dictionaryCss,
            bodyTextColorCss = palette.bodyCss,
            enableLookupTap = enableLookupTap
        )
        val webView = WebView(this).apply {
            tag = FloatingDefinitionWebViewTag(bridge, definitionKey)
            setBackgroundColor(0x00000000)
            overScrollMode = WebView.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isHapticFeedbackEnabled = false
            isSoundEffectsEnabled = false
            isLongClickable = false
            isClickable = enableLookupTap
            isFocusable = false
            isFocusableInTouchMode = false
            settings.javaScriptEnabled = enableLookupTap
            settings.domStorageEnabled = false
            settings.allowFileAccess = true
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = true
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.blockNetworkImage = false
            settings.loadsImagesAutomatically = true
            settings.offscreenPreRaster = true
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.setSupportZoom(false)
            setOnLongClickListener { true }
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setOnTouchListener(object : View.OnTouchListener {
                private var downX = 0f
                private var downY = 0f
                private var moved = false
                private var swipeCloseTriggered = false
                override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                    if (!enableLookupTap || event == null) return false
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.x
                            downY = event.y
                            moved = false
                            swipeCloseTriggered = false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.x - downX
                            val dy = event.y - downY
                            if (
                                !swipeCloseTriggered &&
                                kotlin.math.abs(dx) > 56f &&
                                kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f
                            ) {
                                swipeCloseTriggered = true
                                closeFloatingLookupLayer(layerIndex)
                                return true
                            }
                            if (
                                kotlin.math.abs(dx) > 14f ||
                                kotlin.math.abs(dy) > 14f
                            ) {
                                moved = true
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            if (swipeCloseTriggered) return true
                            if (!moved) {
                                val effectiveScale = this@apply.scale.takeIf { it.isFinite() && it > 0f } ?: 1f
                                val clientX = event.x / effectiveScale
                                val clientY = event.y / effectiveScale
                                evaluateJavascript(
                                    "(function(){try{if(!window.__nineLookupHandleTap){return 'missing_handle';} window.__nineLookupHandleTap($clientX,$clientY,true); return 'ok';}catch(e){return 'error:' + (e && e.message ? e.message : 'unknown');}})();"
                                ) { result ->
                                    val normalized = (result ?: "").trim('"')
                                    Log.d(
                                        FLOATING_LOOKUP_TAP_LOG_TAG,
                                        "native jsResult=$normalized x=$clientX y=$clientY"
                                    )
                                }
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            swipeCloseTriggered = false
                        }
                    }
                    return true
                }
            })
            webViewClient = object : android.webkit.WebViewClient() {
                fun dispatchLookupUrlTap(raw: String, host: WebView?): Boolean {
                    val parsed = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
                    val scheme = parsed.scheme?.lowercase(Locale.ROOT).orEmpty()
                    if (scheme !in setOf("entry", "dictres")) return false
                    val target = resolveLookupTargetFromCustomUrl(raw) ?: return false
                    val safeHost = host ?: this@apply
                    val right = safeHost.width.toFloat().takeIf { it > 0f } ?: 1f
                    val bottom = safeHost.height.toFloat().takeIf { it > 0f } ?: 1f
                    val localRect = Rect(0f, 0f, right, bottom)
                    val callback = bridge.onLookupTap
                    if (callback == null) {
                        Log.d(FLOATING_LOOKUP_TAP_LOG_TAG, "native link dispatch skipped callback_null scheme=$scheme target=$target")
                        return true
                    }
                    callback.invoke(
                        DefinitionLookupTapData(
                            text = target,
                            scanText = target,
                            tapSource = "entry",
                            sentence = target,
                            offset = 0,
                            nodeText = target,
                            nodePathJson = "[]",
                            hostView = safeHost,
                            screenRect = null,
                            localRects = listOf(localRect),
                            localCharRects = listOf(localRect),
                            screenCharRects = emptyList()
                        )
                    )
                    Log.d(FLOATING_LOOKUP_TAP_LOG_TAG, "native link dispatch scheme=$scheme target=$target")
                    return true
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?
                ): WebResourceResponse? {
                    val uri = request?.url ?: return null
                    val payload = loadDictionaryMediaPayload(this@AudiobookFloatingOverlayService, uri) ?: return null
                    return buildDictionaryWebResourceResponse(payload)
                }

                override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                    val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull() ?: return null
                    val payload = loadDictionaryMediaPayload(this@AudiobookFloatingOverlayService, uri) ?: return null
                    return buildDictionaryWebResourceResponse(payload)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
                    if (scheme in setOf("entry", "dictres")) {
                        Log.d(FLOATING_LOOKUP_TAP_LOG_TAG, "block lookup navigation uri=$uri")
                        dispatchLookupUrlTap(uri.toString(), view)
                        return true
                    }
                    return false
                }

                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    val raw = url?.trim().orEmpty()
                    val scheme = runCatching { Uri.parse(raw).scheme?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
                    if (scheme in setOf("entry", "dictres")) {
                        Log.d(FLOATING_LOOKUP_TAP_LOG_TAG, "block lookup navigation uri=$raw")
                        dispatchLookupUrlTap(raw, view)
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (
                        layer.highlightedDefinitionKey == definitionKey &&
                        !layer.highlightedDefinitionNodePathJson.isNullOrBlank() &&
                        layer.highlightedDefinitionOffset != null &&
                        layer.highlightedDefinitionLength != null
                    ) {
                        post {
                            serviceScope.launch {
                                val applied = applyDefinitionMatchedHighlight(
                                    DefinitionLookupTapData(
                                        text = "",
                                        scanText = "",
                                        tapSource = "text",
                                        sentence = "",
                                        offset = layer.highlightedDefinitionOffset,
                                        nodeText = "",
                                        nodePathJson = layer.highlightedDefinitionNodePathJson,
                                        hostView = this@apply,
                                        screenRect = null,
                                        localRects = emptyList(),
                                        localCharRects = emptyList(),
                                        screenCharRects = emptyList()
                                    ),
                                    layer.highlightedDefinitionLength
                                )
                                Log.d(
                                    FLOATING_LOOKUP_HIGHLIGHT_LOG_TAG,
                                    "onPageFinished key=$definitionKey len=${layer.highlightedDefinitionLength} applied=$applied"
                                )
                            }
                        }
                    }
                }
            }
            bridge.hostView = this
            addJavascriptInterface(bridge, "NineLookup")
            loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
        container.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        return container
    }

    private fun refreshFloatingLookupHostHighlight(layerIndex: Int, host: View, layer: ReaderLookupLayer) {
        val webViews = collectFloatingLookupWebViews(host)
        val targetKey = layer.highlightedDefinitionKey ?: return
        val offset = layer.highlightedDefinitionOffset ?: return
        val length = layer.highlightedDefinitionLength ?: return
        val nodePathJson = layer.highlightedDefinitionNodePathJson?.takeIf { it.isNotBlank() } ?: return
        Log.d(
            FLOATING_LOOKUP_HIGHLIGHT_LOG_TAG,
            "refresh start key=$targetKey len=$length webViews=${webViews.size}"
        )
        webViews.forEach(::clearDefinitionMatchedHighlight)
        val target = webViews.firstOrNull { view ->
            (view.tag as? FloatingDefinitionWebViewTag)?.definitionKey == targetKey
        } ?: run {
            Log.d(
                FLOATING_LOOKUP_HIGHLIGHT_LOG_TAG,
                "refresh missingTarget key=$targetKey"
            )
            return
        }
        serviceScope.launch {
            webViews.filter { it !== target }.forEach { clearDefinitionMatchedHighlightAwait(it) }
            clearDefinitionMatchedHighlightAwait(target)
            val applied = applyDefinitionMatchedHighlight(
                DefinitionLookupTapData(
                    text = "",
                    scanText = "",
                    tapSource = "text",
                    sentence = "",
                    offset = offset,
                    nodeText = "",
                    nodePathJson = nodePathJson,
                    hostView = target,
                    screenRect = null,
                    localRects = emptyList(),
                    localCharRects = emptyList(),
                    screenCharRects = emptyList()
                ),
                length
            )
            if (applied) {
                val resolvedRects = resolveDefinitionMatchedRects(
                    DefinitionLookupTapData(
                        text = "",
                        scanText = "",
                        tapSource = "text",
                        sentence = "",
                        offset = offset,
                        nodeText = "",
                        nodePathJson = nodePathJson,
                        hostView = target,
                        screenRect = null,
                        localRects = emptyList(),
                        localCharRects = emptyList(),
                        screenCharRects = emptyList()
                    ),
                    length
                )
                val rebuiltScreenRects = resolvedRects?.screenCharRects
                    ?.let { rebuildRectsFromCharacterRectsShared(it, length) }
                    .orEmpty()
                val fallbackScreenRects = resolvedRects?.screenRects.orEmpty()
                val finalScreenRects = if (rebuiltScreenRects.isNotEmpty()) rebuiltScreenRects else fallbackScreenRects
                if (finalScreenRects.isNotEmpty()) {
                    floatingLookupSession.replaceAt(layerIndex) { current ->
                        current.copy(highlightedDefinitionRects = finalScreenRects)
                    }
                    Log.d(
                        FLOATING_LOOKUP_HIGHLIGHT_LOG_TAG,
                        "refresh rectSync layer=$layerIndex key=$targetKey rects=${finalScreenRects.size}"
                    )
                }
            }
            Log.d(
                FLOATING_LOOKUP_HIGHLIGHT_LOG_TAG,
                "refresh apply key=$targetKey len=$length applied=$applied"
            )
        }
    }

    private fun clearDefinitionMatchedHighlight(webView: WebView) {
        webView.post {
            serviceScope.launch {
                clearDefinitionMatchedHighlightAwait(webView)
            }
        }
    }

    private suspend fun clearDefinitionMatchedHighlightAwait(webView: WebView): Boolean {
        val js = """
            (function() {
                if (!window.__nineLookupClearMatchedHighlight) return false;
                return !!window.__nineLookupClearMatchedHighlight();
            })();
        """.trimIndent()
        val raw = withContext(Dispatchers.Main.immediate) {
            kotlinx.coroutines.suspendCancellableCoroutine<String?> { continuation ->
                webView.evaluateJavascript(js) { value ->
                    if (continuation.isActive) continuation.resume(value) {}
                }
            }
        }
        val decoded = raw
            ?.trim()
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.replace("\\\"", "\"")
        return decoded?.toBooleanStrictOrNull() ?: false
    }

    private fun collectFloatingLookupWebViews(root: View): List<WebView> {
        val result = mutableListOf<WebView>()
        fun walk(view: View) {
            when (view) {
                is WebView -> result += view
                is android.view.ViewGroup -> {
                    for (index in 0 until view.childCount) {
                        walk(view.getChildAt(index))
                    }
                }
            }
        }
        walk(root)
        return result
    }

    private fun createLookupBodyText(textValue: String, topMarginDp: Float, palette: FloatingLookupPalette): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = textValue
            textSize = 15f
            setTextColor(palette.body)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (topMarginDp * density).toInt()
            }
        }
    }

    private fun applySubtitleTypography(settings: AudiobookSettingsConfig) {
        val customTypeface = resolveSubtitleTypeface(this, settings.subtitleCustomFontUri)
        subtitleTextView?.apply {
            textSize = settings.floatingOverlaySubtitleSizeSp.toFloat()
            setTextColor(settings.floatingOverlaySubtitleColor)
            typeface = customTypeface ?: Typeface.DEFAULT
            setShadowLayer(0f, 0f, 0f, 0)
        }
        subtitleOutlineTextView?.apply {
            textSize = settings.floatingOverlaySubtitleSizeSp.toFloat()
            setTextColor(settings.floatingOverlaySubtitleColor)
            typeface = customTypeface ?: Typeface.DEFAULT
            val density = resources.displayMetrics.density
            val radius = (2.8f * density).coerceAtLeast(2f)
            setShadowLayer(radius, 0f, 0f, 0xCC000000.toInt())
        }
    }

    private fun updateBubbleIcon(isPlaying: Boolean) {
        bubbleButton?.setImageResource(
            if (isPlaying) R.drawable.ic_overlay_pause else R.drawable.ic_overlay_play
        )
    }

    private fun updateBubbleFavoriteIcon(isFavorite: Boolean) {
        bubbleFavoriteButton?.setColorFilter(
            if (isFavorite) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt()
        )
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        val controls = subtitleControlsRow ?: return
        val playPause = controls.findViewWithTag<ImageButton>("playPause") ?: return
        playPause.setImageResource(
            if (isPlaying) R.drawable.ic_overlay_pause else R.drawable.ic_overlay_play
        )
    }

    private fun updateSubtitleLockIcon() {
        subtitleLockButton?.setImageResource(
            if (subtitleOverlayLocked) R.drawable.ic_overlay_lock else R.drawable.ic_overlay_unlock
        )
    }

    private fun updateBubbleLockIcon() {
        bubbleLockButton?.setImageResource(
            if (bubbleOverlayLocked) R.drawable.ic_overlay_lock else R.drawable.ic_overlay_unlock
        )
    }

    private fun createControlButton(iconRes: Int, onClick: () -> Unit): ImageButton {
        return createControlButton(iconRes, 1f, onClick)
    }

    private fun createControlButton(iconRes: Int, scale: Float, onClick: () -> Unit): ImageButton {
        val density = resources.displayMetrics.density
        val safeScale = scale.coerceIn(0.62f, 1.4f)
        val sizePx = (42 * density * safeScale).toInt()
        return ImageButton(this).apply {
            setImageResource(iconRes)
            background = null
            setColorFilter(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(sizePx / 5, sizePx / 5, sizePx / 5, sizePx / 5)
            setOnClickListener { onClick() }
        }
    }

    private fun createTextButton(label: String, onClick: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            background = createRoundedBackground(0x22151515, cornerDp = 14f, strokeColor = 0x22FFFFFF)
            layoutParams = LinearLayout.LayoutParams(
                (44 * density).toInt(),
                (34 * density).toInt()
            ).apply { marginEnd = (8 * density).toInt() }
            setOnClickListener { onClick() }
        }
    }

    private fun createColorButton(
        color: Int,
        currentColor: Int,
        onColorSelected: () -> Unit = {}
    ): View {
        val density = resources.displayMetrics.density
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (28 * density).toInt(),
                (28 * density).toInt()
            ).apply { marginEnd = (8 * density).toInt() }
            background = createSubtitleColorSwatchDrawable(
                color = color,
                selected = currentColor == color,
                density = density
            )
            setOnClickListener {
                saveAudiobookFloatingOverlaySubtitleColor(context, color)
                applySubtitleTypography(loadAudiobookSettingsConfig(context))
                onColorSelected()
            }
        }
    }

    private fun createCustomColorButton(
        color: Int,
        selected: Boolean,
        onClick: () -> Unit
    ): View {
        val density = resources.displayMetrics.density
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (28 * density).toInt(),
                (28 * density).toInt()
            ).apply { marginEnd = (8 * density).toInt() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(
                    (if (selected) 3 else 1) * density.toInt().coerceAtLeast(1),
                    if (selected) 0xFFFFFFFF.toInt() else 0x44FFFFFF
                )
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createSubtitleColorSwatchDrawable(
        color: Int,
        selected: Boolean,
        density: Float
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(
                (if (selected) 3 else 1) * density.toInt().coerceAtLeast(1),
                if (selected) 0xFFFFFFFF.toInt() else 0x44FFFFFF
            )
        }
    }

    private fun getOverlayLocalizedString(resId: Int): String {
        val option = loadAppLanguageOption(this)
        if (option == AppLanguageOption.SYSTEM) return getString(resId)
        return try {
            val locale = Locale.forLanguageTag(option.value)
            val localizedConfig = Configuration(resources.configuration).apply {
                setLocale(locale)
                setLayoutDirection(locale)
            }
            createConfigurationContext(localizedConfig).resources.getString(resId)
        } catch (_: Throwable) {
            getString(resId)
        }
    }

    private fun createRgbSliderRow(
        label: String,
        initial: Int,
        onLiveChange: (Int) -> Unit,
        onFinalChange: () -> Unit
    ): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (4 * density).toInt() }
            addView(TextView(this@AudiobookFloatingOverlayService).apply {
                text = label
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    (14 * density).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            })
            addView(SeekBar(this@AudiobookFloatingOverlayService).apply {
                max = 255
                progress = initial.coerceIn(0, 255)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        onLiveChange(progress.coerceIn(0, 255))
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                    override fun onStopTrackingTouch(seekBar: SeekBar?) {
                        onFinalChange()
                    }
                })
            })
        }
    }

    private fun createRoundedBackground(fillColor: Int, cornerDp: Float, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerDp * resources.displayMetrics.density
            setColor(fillColor)
            setStroke((1 * resources.displayMetrics.density).toInt().coerceAtLeast(1), strokeColor)
        }
    }

    private fun removeOverlay() {
        clearFloatingLookupHosts()
        removeSubtitleOverlay()
        removeBubbleOverlay()
    }

    private fun removeSubtitleOverlay() {
        val wm = windowManager
        val view = rootView
        if (wm != null && view != null) {
            runCatching { wm.removeView(view) }
        }
        rootView = null
        windowLayoutParams = null
        subtitlePanelView = null
        subtitleTextView = null
        subtitleOutlineTextView = null
        subtitleControlsRow = null
        subtitleSettingsPanel = null
        subtitleLockButton = null
    }

    private fun removeBubbleOverlay() {
        val wm = windowManager
        val view = bubbleRootView
        if (wm != null && view != null) {
            runCatching { wm.removeView(view) }
        }
        bubbleRootView = null
        bubbleWindowLayoutParams = null
        bubbleRow = null
        bubbleButton = null
        bubbleControlsRow = null
        bubbleFavoriteButton = null
        bubbleLockButton = null
        bubbleControlsVisible = false
    }

    private fun performFloatingRecursiveLookup(
        sourceLayerIndex: Int,
        definitionKey: String,
        tapData: DefinitionLookupTapData
    ) {
        serviceScope.launch {
            Log.d(
                FLOATING_LOOKUP_TAP_LOG_TAG,
                "recursive start sourceLayer=$sourceLayerIndex key=$definitionKey tapSource=${tapData.tapSource} scan='${tapData.scanText.take(32)}' text='${tapData.text.take(32)}'"
            )
            val settings = loadAudiobookSettingsConfig(this@AudiobookFloatingOverlayService)
            pausePlaybackForFloatingLookupIfNeeded(settings)
            val term = if (tapData.tapSource.equals("entry", ignoreCase = true)) {
                tapData.scanText.trim().ifBlank { tapData.text.trim() }
            } else {
                selectLookupScanText(tapData.scanText.ifBlank { tapData.text }, 0)?.text?.trim().orEmpty()
            }.takeIf { it.isNotBlank() } ?: run {
                if (tapData.tapSource.equals("entry", ignoreCase = true)) {
                    Log.d(
                        FLOATING_LOOKUP_TAP_LOG_TAG,
                        "recursive keep layer on entry empty_term sourceLayer=$sourceLayerIndex"
                    )
                } else {
                    truncateFloatingLookupLayersTo(sourceLayerIndex)
                }
                return@launch
            }
            val requestNonce = lookupRequestNonce + 1L
            lookupRequestNonce = requestNonce
            val currentLayer = floatingLookupSession.getOrNull(sourceLayerIndex) ?: return@launch
            val dictionaries = withContext(Dispatchers.IO) {
                cachedLookupDictionaries ?: loadAvailableDictionaries(this@AudiobookFloatingOverlayService).also {
                    cachedLookupDictionaries = it
                }
            }
            Log.d(
                FLOATING_LOOKUP_TAP_LOG_TAG,
                "recursive query term=$term dicts=${dictionaries.size} tapSource=${tapData.tapSource}"
            )
            if (lookupRequestNonce != requestNonce) return@launch
            if (dictionaries.isEmpty()) {
                hideFloatingLookup()
                return@launch
            }
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    executeRecursiveLookupQuery(
                        context = this@AudiobookFloatingOverlayService,
                        dictionaries = dictionaries,
                        term = term,
                        tapSource = tapData.tapSource,
                        sourceDictionaryName = lookupDictionaryNameFromDefinitionKey(definitionKey)
                    )
                }
            }
            if (lookupRequestNonce != requestNonce) return@launch
            result.onSuccess { queryResult ->
                val hits = queryResult.hits
                val matchedLength = hits.firstOrNull { it.matchedLength > 0 }?.matchedLength
                    ?: queryResult.term.length
                    ?: term.length
                    ?: 1
                Log.d(
                    FLOATING_LOOKUP_TAP_LOG_TAG,
                    "recursive result hits=${hits.size} term=$term matched=$matchedLength tapSource=${tapData.tapSource}"
                )
                if (hits.isEmpty()) {
                    if (tapData.tapSource.equals("entry", ignoreCase = true)) {
                        Log.d(
                            FLOATING_LOOKUP_TAP_LOG_TAG,
                            "recursive keep layer on entry no_hits sourceLayer=$sourceLayerIndex term=$term"
                        )
                    } else {
                        truncateFloatingLookupLayersTo(sourceLayerIndex)
                    }
                    Toast.makeText(
                        this@AudiobookFloatingOverlayService,
                        getString(R.string.bookreader_lookup_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@onSuccess
                }
                val resolvedRects = resolveDefinitionMatchedRects(tapData, matchedLength)
                val rebuiltLocalRects = resolvedRects?.localCharRects
                    ?.let { rebuildRectsFromCharacterRectsShared(it, matchedLength) }
                    .orEmpty()
                val fallbackLocalRects = resolvedRects?.localRects.orEmpty()
                val finalLocalHighlightRects = sanitizeResolvedHighlightRectsShared(
                    rebuiltRects = if (rebuiltLocalRects.isNotEmpty()) rebuiltLocalRects else fallbackLocalRects,
                    sourceRects = tapData.localRects
                )
                val rebuiltScreenRects = resolvedRects?.screenCharRects
                    ?.let { rebuildRectsFromCharacterRectsShared(it, matchedLength) }
                    .orEmpty()
                val fallbackScreenRects = resolvedRects?.screenRects.orEmpty()
                val finalScreenHighlightRects =
                    if (rebuiltScreenRects.isNotEmpty()) rebuiltScreenRects
                    else if (fallbackScreenRects.isNotEmpty()) fallbackScreenRects
                    else tapData.screenCharRects
                        .takeIf { it.isNotEmpty() }
                        ?.let { rebuildRectsFromCharacterRectsShared(it, matchedLength) }
                        ?: tapData.screenRect?.let { listOf(it) }
                        .orEmpty()
                val preflightApplied = applyDefinitionMatchedHighlight(tapData, matchedLength)
                Log.d(
                    FLOATING_LOOKUP_HIGHLIGHT_LOG_TAG,
                    "preflight key=$definitionKey len=$matchedLength applied=$preflightApplied"
                )
                if (!preflightApplied && !tapData.tapSource.equals("entry", ignoreCase = true)) {
                    return@onSuccess
                }
                // Keep recursive popup anchor tied to the exact tap location.
                // Rebuilding by matchedLength can shift anchors in dictionary web content.
                val tapAnchorFromChars = tapData.screenCharRects
                    .takeIf { it.isNotEmpty() }
                val tapAnchorFromRect = tapData.screenRect?.let { listOf(it) }?.takeIf { it.isNotEmpty() }
                val resolvedAnchorFromChars = resolvedRects?.screenCharRects
                    ?.let { rebuildRectsFromCharacterRectsShared(it, matchedLength) }
                    ?.takeIf { it.isNotEmpty() }
                val resolvedAnchorFromRects = resolvedRects?.screenRects?.takeIf { it.isNotEmpty() }
                val resolvedAnchorRects = tapAnchorFromChars
                    ?: tapAnchorFromRect
                    ?: resolvedAnchorFromChars
                    ?: resolvedAnchorFromRects
                val anchorSource = when {
                    tapAnchorFromChars != null -> "tapChars"
                    tapAnchorFromRect != null -> "tapRect"
                    resolvedAnchorFromChars != null -> "resolvedChars"
                    resolvedAnchorFromRects != null -> "resolvedRects"
                    else -> "none"
                }
                val groupedResults = groupLookupResultsByTerm(
                    results = hits,
                    dictionaryCssByName = dictionaries.associate { it.name to it.stylesCss },
                    dictionaryPriorityByName = dictionaries.mapIndexed { index, dictionary -> dictionary.name to index }.toMap()
                ).take(3)
                truncateFloatingLookupLayersTo(sourceLayerIndex, render = false)
                Log.d(
                    FLOATING_LOOKUP_HIGHLIGHT_LOG_TAG,
                    "setHighlight layer=$sourceLayerIndex key=$definitionKey len=$matchedLength localRects=${finalLocalHighlightRects.size} screenRects=${finalScreenHighlightRects.size}"
                )
                val layerAnchorRects = currentLayer.anchor?.rects?.takeIf { it.isNotEmpty() }
                // Book-like recursive behavior: always prefer the current tap-derived anchor.
                val popupAnchorRects = resolvedAnchorRects ?: layerAnchorRects
                val popupAnchorSource = when {
                    resolvedAnchorRects != null -> anchorSource
                    layerAnchorRects != null -> "sourceLayer"
                    else -> "none"
                }
                Log.d(
                    FLOATING_LOOKUP_LOG_TAG,
                    "anchor layer=$sourceLayerIndex source=$popupAnchorSource rects=${popupAnchorRects?.size ?: 0}"
                )
                val estimatedAnchorY = popupAnchorRects
                    ?.minOfOrNull { it.bottom }
                    ?: (resources.displayMetrics.heightPixels * 0.56f)
                val shouldPlaceBelow = estimatedAnchorY <= (resources.displayMetrics.heightPixels / 2f)
                val finalSelectionText = term.take(matchedLength.coerceAtLeast(1)).trim().ifBlank { term }
                val layer = buildFloatingLookupLayer(
                    term = finalSelectionText,
                    popupSentence = tapData.sentence.trim().ifBlank { null },
                    sourceTerm = currentLayer.selectionText,
                    groupedResults = groupedResults,
                    anchor = popupAnchorRects?.let { ReaderLookupAnchor(it) },
                    placeBelow = shouldPlaceBelow,
                    preferSidePlacement = true,
                    selectedRange = currentLayer.selectedRange
                )
                // Single-visible-card mode with history stack: keep previous layers for back navigation.
                floatingLookupSession.push(layer)
                renderFloatingLookupResults(layer)
            }.onFailure {
                truncateFloatingLookupLayersTo(sourceLayerIndex)
                Toast.makeText(
                    this@AudiobookFloatingOverlayService,
                    (it.message ?: getString(R.string.bookreader_lookup_failed)).take(160),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun exportFloatingLookupToAnki(grouped: GroupedLookupResult, layerIndex: Int, layer: ReaderLookupLayer) {
        val dictionaryGroup = grouped.dictionaries.firstOrNull() ?: return
        val cueSnapshot = BookReaderFloatingBridge.currentCue() ?: return
        val settings = loadAudiobookSettingsConfig(this)
        val closeAction = floatingLookupSession.afterAddToAnki(layerIndex)
        applyCloseLookupAction(closeAction)
        val audioUri = cueSnapshot.audioUri
            ?.takeIf { layer.sourceTerm == null }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val exportStartMs = if (settings.lookupExportFullSentence) {
            cueSnapshot.fullSentenceStartMs ?: cueSnapshot.startMs
        } else {
            cueSnapshot.startMs
        }
        val exportEndMs = if (settings.lookupExportFullSentence) {
            cueSnapshot.fullSentenceEndMs ?: cueSnapshot.endMs
        } else {
            cueSnapshot.endMs
        }
        val sentence = if (settings.lookupExportFullSentence) {
            cueSnapshot.fullSentenceText ?: cueSnapshot.text
        } else {
            layer.popupSentence?.trim().takeIf { !it.isNullOrBlank() } ?: cueSnapshot.text
        }
        Log.d(
            FLOATING_LOOKUP_LOG_TAG,
            "ankiExport fullSentence=${settings.lookupExportFullSentence} sentenceLen=${sentence.length} popupLen=${layer.popupSentence?.length ?: 0} cueLen=${cueSnapshot.text.length} fullCueLen=${cueSnapshot.fullSentenceText?.length ?: 0} start=$exportStartMs end=$exportEndMs"
        )
        val popupSelectionText = layer.selectionText?.trim()?.takeIf { it.isNotBlank() }
        val exportDefinitionHtml = dictionaryGroup.definitions
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("<br>")
            .ifBlank { grouped.term }
        serviceScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val preparedLookupAudio = prepareLookupAudioForAnkiExport(
                        context = this@AudiobookFloatingOverlayService,
                        term = grouped.term,
                        reading = grouped.reading,
                        settings = loadAudiobookSettingsConfig(this@AudiobookFloatingOverlayService)
                    )
                    try {
                        addLookupDefinitionToAnkiShared(
                            context = this@AudiobookFloatingOverlayService,
                            cueText = cueSnapshot.text,
                            cueStartMs = exportStartMs,
                            cueEndMs = exportEndMs,
                            audioUri = audioUri,
                            lookupAudioUri = preparedLookupAudio?.uri,
                            bookTitle = cueSnapshot.bookTitle,
                            entry = dictionaryGroup.entry,
                            definition = exportDefinitionHtml,
                            dictionaryCss = dictionaryGroup.css,
                            groupedDictionaries = grouped.dictionaries,
                            popupSelectionText = popupSelectionText,
                            sentenceOverride = sentence
                        )
                    } finally {
                        preparedLookupAudio?.cleanup?.invoke()
                    }
                }
            }
            result.fold(
                onSuccess = { exportResult ->
                    val message = ankiExportResultMessage(this@AudiobookFloatingOverlayService, exportResult)
                    Toast.makeText(
                        this@AudiobookFloatingOverlayService,
                        message.take(220),
                        if (exportResult == AnkiExportResult.Added) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                    ).show()
                },
                onFailure = {
                    Toast.makeText(
                        this@AudiobookFloatingOverlayService,
                        explainFloatingAnkiFailure(it).take(220),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun explainFloatingAnkiFailure(error: Throwable): String {
        return ankiExportResultMessage(this, classifyAnkiExportFailure(this, error))
    }

    private fun maybeAutoPlayFloatingLookup(layerIndex: Int, layer: ReaderLookupLayer) {
        val settings = loadAudiobookSettingsConfig(this)
        if (!settings.lookupPlaybackAudioEnabled || !settings.lookupPlaybackAudioAutoPlay) return
        val target = layer.groupedResults.firstOrNull() ?: return
        val key = "${layer.autoPlayNonce}|${target.term}|${target.reading.orEmpty()}"
        if (layer.autoPlayedKey == key) return
        floatingLookupSession.replaceAt(layerIndex) { current ->
            current.copy(autoPlayedKey = key)
        }
        playLookupAudioForTerm(
            context = this,
            term = target.term,
            reading = target.reading,
            settings = settings
        ) { error ->
            Toast.makeText(this, error.take(160), Toast.LENGTH_SHORT).show()
        }
    }

    private fun rebuildOverlay() {
        val settings = loadAudiobookSettingsConfig(this)
        val subtitleY = windowLayoutParams?.y ?: settings.floatingOverlaySubtitleY
        val bubbleX = bubbleWindowLayoutParams?.x ?: settings.floatingOverlayBubbleX
        val bubbleY = bubbleWindowLayoutParams?.y ?: settings.floatingOverlayBubbleY
        removeOverlay()
        saveAudiobookFloatingOverlaySubtitlePosition(this, subtitleY)
        saveAudiobookFloatingOverlayBubblePosition(this, bubbleX, bubbleY)
        ensureOverlayVisible()
    }

    private inner class OverlayDragTouchListener(
        private val gestureDetector: GestureDetector?,
        private val dragAllowed: () -> Boolean,
        private val verticalOnly: Boolean = false,
        private val layoutParamsProvider: () -> WindowManager.LayoutParams?,
        private val hostViewProvider: () -> View?,
        private val horizontalBoundsProvider: (() -> IntRange)? = null,
        private val onPersistPosition: (x: Int, y: Int) -> Unit
    ) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var isDragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = layoutParamsProvider() ?: return false
            val wm = windowManager ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    isDragging = false
                    gestureDetector?.onTouchEvent(event)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - downRawX).toInt()
                    val deltaY = (event.rawY - downRawY).toInt()
                    if (!isDragging && dragAllowed() && (kotlin.math.abs(deltaX) > 10 || kotlin.math.abs(deltaY) > 10)) {
                        isDragging = true
                        if (floatingLookupSession.size > 0) {
                            hideFloatingLookup()
                        }
                    }
                    if (isDragging) {
                        if (!verticalOnly) {
                            val targetX = startX + deltaX
                            val bounds = horizontalBoundsProvider?.invoke()
                            params.x = bounds?.let { targetX.coerceIn(it.first, it.last) } ?: targetX.coerceAtLeast(0)
                        } else {
                            params.x = ((resources.displayMetrics.widthPixels - (hostViewProvider()?.width ?: 0)) / 2).coerceAtLeast(0)
                        }
                        params.y = (startY + deltaY).coerceAtLeast(0)
                        hostViewProvider()?.let { runCatching { wm.updateViewLayout(it, params) } }
                    } else {
                        gestureDetector?.onTouchEvent(event)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        onPersistPosition(params.x, params.y.coerceAtLeast(0))
                    } else {
                        if (gestureDetector != null) {
                            gestureDetector.onTouchEvent(event)
                        } else {
                            view.performClick()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    gestureDetector?.onTouchEvent(event)
                    return true
                }
            }
            return false
        }
    }
}
