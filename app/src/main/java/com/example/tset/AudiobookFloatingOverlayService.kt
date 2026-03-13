package com.tekuza.p9player

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout

private fun hasOverlayPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
}

internal fun startAudiobookFloatingOverlayService(context: Context) {
    if (!hasOverlayPermission(context)) return
    val intent = Intent(context, AudiobookFloatingOverlayService::class.java).apply {
        action = AudiobookFloatingOverlayService.ACTION_SHOW
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
    private enum class PanelSide {
        LEFT,
        RIGHT
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var bubbleButton: ImageButton? = null
    private var favoriteButton: ImageButton? = null
    private var panelView: LinearLayout? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null
    private var controlsExpanded: Boolean = false
    private var expandedPanelSide: PanelSide = PanelSide.LEFT

    private val playbackListener = object : BookReaderFloatingBridge.PlaybackStateListener {
        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            updateBubbleIcon(isPlaying)
        }
    }
    private val favoriteListener = object : BookReaderFloatingBridge.FavoriteStateListener {
        override fun onFavoriteStateChanged(isFavorite: Boolean) {
            updateFavoriteIcon(isFavorite)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as? WindowManager
        BookReaderFloatingBridge.addPlaybackStateListener(playbackListener)
        BookReaderFloatingBridge.addFavoriteStateListener(favoriteListener)
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
            else -> return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        BookReaderFloatingBridge.removePlaybackStateListener(playbackListener)
        BookReaderFloatingBridge.removeFavoriteStateListener(favoriteListener)
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureOverlayVisible() {
        if (!hasOverlayPermission(this)) {
            stopSelf()
            return
        }
        if (rootView != null) {
            updateBubbleIcon(BookReaderFloatingBridge.isPlaying())
            updateFavoriteIcon(BookReaderFloatingBridge.isFavorite())
            return
        }

        val wm = windowManager ?: run {
            stopSelf()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 8)
        }

        val controlsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        fun circleBackground(fillColor: Int): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fillColor)
            }
        }

        fun createActionButton(
            iconRes: Int,
            sizeDp: Int,
            fillColor: Int,
            withBackground: Boolean,
            onClick: () -> Unit
        ): ImageButton {
            val sizePx = (sizeDp * resources.displayMetrics.density).toInt()
            return ImageButton(this).apply {
                setImageResource(iconRes)
                background = if (withBackground) circleBackground(fillColor) else null
                setColorFilter(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    marginEnd = (6 * resources.displayMetrics.density).toInt()
                }
                scaleType = ImageView.ScaleType.CENTER
                setPadding(
                    if (withBackground) sizePx / 4 else sizePx / 6,
                    if (withBackground) sizePx / 4 else sizePx / 6,
                    if (withBackground) sizePx / 4 else sizePx / 6,
                    if (withBackground) sizePx / 4 else sizePx / 6
                )
                setOnClickListener { onClick() }
            }
        }

        val prevButton = createActionButton(
            iconRes = android.R.drawable.ic_media_rew,
            sizeDp = 46,
            fillColor = 0x00000000,
            withBackground = false
        ) {
            BookReaderFloatingBridge.seekPrevious()
        }
        val nextButton = createActionButton(
            iconRes = android.R.drawable.ic_media_ff,
            sizeDp = 46,
            fillColor = 0x00000000,
            withBackground = false
        ) {
            BookReaderFloatingBridge.seekNext()
        }
        val favoriteButton = createActionButton(
            iconRes = android.R.drawable.btn_star_big_on,
            sizeDp = 46,
            fillColor = 0x00000000,
            withBackground = false
        ) {
            BookReaderFloatingBridge.toggleFavorite()
        }
        controls.addView(prevButton)
        controls.addView(nextButton)
        controls.addView(favoriteButton)

        val underlineWidthPx = (136 * resources.displayMetrics.density).toInt()
        val underline = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                underlineWidthPx,
                (3 * resources.displayMetrics.density).toInt().coerceAtLeast(2)
            ).apply {
                topMargin = (4 * resources.displayMetrics.density).toInt()
                marginEnd = (6 * resources.displayMetrics.density).toInt()
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setBackgroundColor(0x80FFFFFF.toInt())
        }
        controlsContainer.addView(controls)
        controlsContainer.addView(underline)

        val bubble = createActionButton(
            iconRes = android.R.drawable.ic_media_pause,
            sizeDp = 58,
            fillColor = 0xDD151515.toInt(),
            withBackground = true
        ) {}
        val gestureDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    BookReaderFloatingBridge.togglePlayPause()
                    updateBubbleIcon(BookReaderFloatingBridge.isPlaying())
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    toggleControlsVisibility(controlsContainer)
                    return true
                }

                override fun onDown(e: MotionEvent): Boolean = true
            }
        )
        bubble.setOnTouchListener(
            FloatingBubbleDragListener(
                controlsContainer = controlsContainer,
                gestureDetector = gestureDetector
            )
        )

        arrangeChildren(
            container = container,
            controlsContainer = controlsContainer,
            bubble = bubble,
            panelSide = expandedPanelSide
        )

        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        val savedPosition = loadAudiobookSettingsConfig(this)
        params.gravity = Gravity.START or Gravity.TOP
        params.x = savedPosition.floatingOverlayX.coerceAtLeast(0)
        params.y = savedPosition.floatingOverlayY.coerceAtLeast(0)
        windowLayoutParams = params

        wm.addView(container, params)
        rootView = container
        bubbleButton = bubble
        this.favoriteButton = favoriteButton
        panelView = controlsContainer
        updateBubbleIcon(BookReaderFloatingBridge.isPlaying())
        updateFavoriteIcon(BookReaderFloatingBridge.isFavorite())
    }

    private fun removeOverlay() {
        val wm = windowManager
        val view = rootView
        if (wm != null && view != null) {
            runCatching { wm.removeView(view) }
        }
        rootView = null
        bubbleButton = null
        favoriteButton = null
        panelView = null
    }

    private fun updateBubbleIcon(isPlaying: Boolean) {
        val bubble = bubbleButton ?: return
        bubble.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    private fun updateFavoriteIcon(isFavorite: Boolean) {
        favoriteButton?.setColorFilter(
            if (isFavorite) 0xFFFFD54F.toInt() else 0xFFFFFFFF.toInt()
        )
    }

    private fun toggleControlsVisibility(controlsContainer: LinearLayout) {
        val params = windowLayoutParams ?: run {
            controlsContainer.visibility = if (controlsContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            controlsExpanded = controlsContainer.visibility == View.VISIBLE
            return
        }
        val wm = windowManager ?: run {
            controlsContainer.visibility = if (controlsContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            controlsExpanded = controlsContainer.visibility == View.VISIBLE
            return
        }
        if (!controlsExpanded) {
            val controlsWidth = ensureMeasuredWidth(controlsContainer)
            val bubbleWidth = ensureMeasuredWidth(bubbleButton)
            val bubbleLeft = params.x.coerceAtLeast(0)
            val screenWidth = resources.displayMetrics.widthPixels
            val leftSpace = bubbleLeft
            val rightSpace = (screenWidth - bubbleLeft - bubbleWidth).coerceAtLeast(0)
            expandedPanelSide = if (leftSpace >= controlsWidth || leftSpace >= rightSpace) {
                PanelSide.LEFT
            } else {
                PanelSide.RIGHT
            }
            arrangeChildren(
                container = rootView,
                controlsContainer = controlsContainer,
                bubble = bubbleButton,
                panelSide = expandedPanelSide
            )
            controlsContainer.visibility = View.VISIBLE
            controlsExpanded = true
            if (expandedPanelSide == PanelSide.LEFT) {
                params.x = (bubbleLeft - controlsWidth).coerceAtLeast(0)
            } else {
                params.x = bubbleLeft
            }
        } else {
            val controlsWidth = ensureMeasuredWidth(controlsContainer)
            val bubbleLeft = when (expandedPanelSide) {
                PanelSide.LEFT -> params.x + controlsWidth
                PanelSide.RIGHT -> params.x
            }
            params.x = bubbleLeft.coerceAtLeast(0)
            controlsContainer.visibility = View.GONE
            controlsExpanded = false
        }
        rootView?.let { wm.updateViewLayout(it, params) }
    }

    private fun saveBubbleAnchorPosition(params: WindowManager.LayoutParams, controlsContainer: LinearLayout?) {
        val controlsWidth = if (controlsExpanded) {
            controlsContainer?.width?.takeIf { it > 0 } ?: controlsContainer?.measuredWidth ?: 0
        } else {
            0
        }
        saveAudiobookFloatingOverlayPosition(
            this,
            x = when {
                !controlsExpanded -> params.x
                expandedPanelSide == PanelSide.LEFT -> params.x + controlsWidth
                else -> params.x
            }.coerceAtLeast(0),
            y = params.y.coerceAtLeast(0)
        )
    }

    private fun arrangeChildren(
        container: LinearLayout?,
        controlsContainer: LinearLayout?,
        bubble: ImageButton?,
        panelSide: PanelSide
    ) {
        val parent = container ?: return
        val controls = controlsContainer ?: return
        val bubbleButton = bubble ?: return
        controls.setPadding(
            if (panelSide == PanelSide.RIGHT) (10 * resources.displayMetrics.density).toInt() else 0,
            0,
            if (panelSide == PanelSide.LEFT) (10 * resources.displayMetrics.density).toInt() else 0,
            0
        )
        parent.removeAllViews()
        if (panelSide == PanelSide.LEFT) {
            parent.addView(controls)
            parent.addView(bubbleButton)
        } else {
            parent.addView(bubbleButton)
            parent.addView(controls)
        }
    }

    private fun ensureMeasuredWidth(view: View?): Int {
        if (view == null) return 0
        if (view.width > 0) return view.width
        val widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        return view.measuredWidth
    }

    private inner class FloatingBubbleDragListener(
        private val controlsContainer: LinearLayout,
        private val gestureDetector: GestureDetector
    ) : View.OnTouchListener {
        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var isDragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = windowLayoutParams ?: return false
            val wm = windowManager ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    isDragging = false
                    gestureDetector.onTouchEvent(event)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - downRawX).toInt()
                    val deltaY = (event.rawY - downRawY).toInt()
                    if (!isDragging && (kotlin.math.abs(deltaX) > 10 || kotlin.math.abs(deltaY) > 10)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = (startX + deltaX).coerceAtLeast(0)
                        params.y = (startY + deltaY).coerceAtLeast(0)
                        rootView?.let { wm.updateViewLayout(it, params) }
                    } else {
                        gestureDetector.onTouchEvent(event)
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        saveBubbleAnchorPosition(params, controlsContainer)
                    } else {
                        view.performClick()
                        gestureDetector.onTouchEvent(event)
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    gestureDetector.onTouchEvent(event)
                    return true
                }
            }
            return false
        }
    }

    companion object {
        const val ACTION_SHOW = "com.tekuza.p9player.action.SHOW_FLOATING_OVERLAY"
        const val ACTION_HIDE = "com.tekuza.p9player.action.HIDE_FLOATING_OVERLAY"
    }
}
