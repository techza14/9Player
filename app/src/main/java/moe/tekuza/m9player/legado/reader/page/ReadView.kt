package moe.tekuza.m9player.legado.reader.page

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.Scroller
import android.widget.TextView
import android.widget.Toast
import android.view.VelocityTracker
import android.view.ViewGroup
import moe.tekuza.m9player.EbookImageRef
import moe.tekuza.m9player.R
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.legado.reader.M9PageAnim
import moe.tekuza.m9player.legado.reader.M9TextWeight
import moe.tekuza.m9player.legado.reader.entities.TextPage
import kotlin.math.abs

internal class ReadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val targetPageView = PageView(context).apply {
        visibility = GONE
    }
    private val pageView = PageView(context)
    private val assistOverlay = TextView(context)
    private val selectionStartHandle = View(context)
    private val selectionEndHandle = View(context)
    private val selectionActionMenu = SelectionActionMenu(
        context = context,
        onAction = { action -> performSelectionAction(action) },
        onProcessText = { intent -> performSelectionProcessText(intent) }
    )
    var onPrevPage: (() -> Unit)? = null
    var onNextPage: (() -> Unit)? = null
    var onMovePages: ((Int) -> Unit)? = null
    var onMenu: (() -> Unit)? = null
    var onTapAction: ((TapAction) -> Unit)? = null
    var onSelectionAction: ((SelectionAction, String) -> Unit)? = null
    var onSelectionProcessText: ((Intent, String) -> Unit)? = null
    var onImageClick: ((EbookImageRef) -> Unit)? = null
    var onPagePreview: ((Int) -> TextPage?)? = null
    private var layoutMode: M9LayoutMode = M9LayoutMode.HORIZONTAL
    private var pageAnim: M9PageAnim = M9PageAnim.NONE
    private var clickRegionActions: List<TapAction> = defaultClickRegionActions()
    private var assistToken: ContentTextView.AssistToken? = null
    private var overlayTextColor: Int = 0xFFFFFFFF.toInt()
    private var overlayBgColor: Int = 0xCC1E1E1E.toInt()
    private var noAnimScrollPage: Boolean = false
    private var suppressNextSetAnimation: Boolean = false
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var dragDirection: Int = 0
    private var isDraggingPage: Boolean = false
    private var isGestureAnimating: Boolean = false
    private var longPressTriggered: Boolean = false
    private var isTextSelected: Boolean = false
    private var activeSelectionHandle: SelectionHandle = SelectionHandle.NONE
    private val longPressRunnable = Runnable {
        longPressTriggered = true
        handleLongPressSelection()
    }
    private var consumedByDragProbe: Boolean = false
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private val pageInterpolator = DecelerateInterpolator(1.45f)
    private val linearInterpolator = LinearInterpolator()
    private val scrollScroller = Scroller(context, linearInterpolator)
    private val velocityTracker: VelocityTracker = VelocityTracker.obtain()
    private var scrollLastScrollerX: Int = 0
    private var scrollLastScrollerY: Int = 0
    private var scrollContentOffset: Float = 0f
    private var scrollStartOffset: Float = 0f
    private var scrollPageStep: Float = 1f
    private var scrollAnchorDelta: Int = 0

    val contentWidth: Int get() = pageView.contentView.width
    val contentHeight: Int
        get() = (
            pageView.contentView.height -
                pageView.contentView.paddingTop -
                pageView.contentView.paddingBottom
            ).coerceAtLeast(0)
    val textSizePx: Float get() = pageView.contentView.textSizePx

    init {
        addView(targetPageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(pageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(assistOverlay.apply {
            visibility = GONE
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { hideAssistOverlay() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(selectionStartHandle.apply {
            visibility = GONE
            background = selectionHandleDrawable()
        }, LayoutParams(dp(18), dp(18)))
        addView(selectionEndHandle.apply {
            visibility = GONE
            background = selectionHandleDrawable()
        }, LayoutParams(dp(18), dp(18)))
        updateAssistOverlayStyle()
    }

    fun setPage(page: TextPage, highlight: IntRange? = null, search: IntRange? = null, forward: Boolean = true) {
        hideAssistOverlay()
        pageView.setPage(page, highlight, search)
        if (suppressNextSetAnimation) {
            suppressNextSetAnimation = false
            resetPageLayers()
        } else {
            runPageAnimation(forward)
        }
    }

    fun setReaderColors(
        bg: Int,
        text: Int,
        tip: Int,
        bgAssetName: String? = null,
        bgImageUri: String? = null,
        bgAlpha: Int = 100
    ) {
        setBackgroundColor(bg)
        forEachPageView { it.setReaderColors(bg, text, tip, bgAssetName, bgImageUri, bgAlpha) }
        overlayTextColor = text
        overlayBgColor = if (isDarkColor(bg)) 0xCC2D2D2D.toInt() else 0xF4FFF8EC.toInt()
        updateAssistOverlayStyle()
    }

    fun setCueHighlightColor(color: Int) {
        forEachPageView { it.setCueHighlightColor(color) }
    }

    fun setTextSizeSp(sizeSp: Float) {
        forEachPageView { it.setTextSizeSp(sizeSp) }
        assistOverlay.textSize = sizeSp
    }

    fun setTextWeight(weight: M9TextWeight) {
        forEachPageView { it.setTextWeight(weight) }
    }

    fun setTextUnderline(enabled: Boolean) {
        forEachPageView { it.setTextUnderline(enabled) }
    }

    fun setReaderTypeface(typeface: Typeface?) {
        forEachPageView { it.setReaderTypeface(typeface) }
        assistOverlay.typeface = typeface ?: Typeface.DEFAULT
    }

    fun setReaderPadding(left: Int, top: Int, right: Int, bottom: Int) {
        forEachPageView { it.setReaderPadding(left, top, right, bottom) }
        requestLayout()
    }

    fun setShowHeaderFooter(show: Boolean) {
        forEachPageView { it.setShowHeaderFooter(show) }
        requestLayout()
    }

    fun setPageAnim(anim: M9PageAnim) {
        pageAnim = anim
        resetPageLayers()
    }

    fun setLayoutMode(mode: M9LayoutMode) {
        layoutMode = mode
        resetPageLayers()
    }

    fun setNoAnimScrollPage(enabled: Boolean) {
        noAnimScrollPage = enabled
    }

    fun setClickRegionActions(actions: List<TapAction>) {
        clickRegionActions = actions.takeIf { it.size == CLICK_REGION_COUNT } ?: defaultClickRegionActions()
    }

    override fun computeScroll() {
        if (!scrollScroller.computeScrollOffset()) {
            if (isGestureAnimating && pageAnim == M9PageAnim.SCROLL) {
                finishScrollMotion()
            }
            return
        }
        val deltaX = scrollScroller.currX - scrollLastScrollerX
        val deltaY = scrollScroller.currY - scrollLastScrollerY
        scrollLastScrollerX = scrollScroller.currX
        scrollLastScrollerY = scrollScroller.currY
        if (isDraggingPage || isGestureAnimating) {
            if (pageAnim == M9PageAnim.SCROLL) {
                updateScrollDragFromScroller(deltaX.toFloat(), deltaY.toFloat())
            }
            postInvalidateOnAnimation()
        }
    }

    private fun runPageAnimation(forward: Boolean) {
        resetPageLayers()
        if (pageAnim == M9PageAnim.NONE || width <= 0 || height <= 0) return
        val direction = if (forward) 1 else -1
        when (pageAnim) {
            M9PageAnim.COVER -> {
                pageView.translationX = horizontalPageSide(direction) * width.toFloat()
                pageView.animate()
                    .translationX(0f)
                    .setInterpolator(pageInterpolator)
                    .setDuration(PROGRAMMATIC_PAGE_ANIM_MS)
                    .start()
            }
            M9PageAnim.SLIDE -> {
                pageView.translationX = horizontalPageSide(direction) * width.toFloat()
                pageView.animate()
                    .translationX(0f)
                    .setInterpolator(pageInterpolator)
                    .setDuration(PROGRAMMATIC_PAGE_ANIM_MS)
                    .start()
            }
            M9PageAnim.SCROLL -> {
                val distance = clickScrollDistance(direction)
                if (isScrollHorizontalAxis()) {
                    val start = horizontalPageSide(direction) * distance
                    pageView.translationX = start
                } else {
                    val start = direction * distance
                    pageView.translationY = start
                }
                pageView.animate()
                    .translationX(0f)
                    .translationY(0f)
                    .setInterpolator(pageInterpolator)
                    .setDuration(if (noAnimScrollPage) 1L else PROGRAMMATIC_PAGE_ANIM_MS)
                    .start()
            }
            M9PageAnim.NONE -> Unit
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                hideAssistOverlay()
                abortGestureAnimation()
                velocityTracker.clear()
                velocityTracker.addMovement(event)
                if (!scrollScroller.isFinished) scrollScroller.abortAnimation()
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                dragDirection = 0
                isDraggingPage = false
                longPressTriggered = false
                activeSelectionHandle = selectionHandleAt(event.x, event.y)
                if (activeSelectionHandle != SelectionHandle.NONE) {
                    hideSelectionMenu()
                    return true
                }
                consumedByDragProbe = false
                if (!isTextSelected) {
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                downX = event.getX(index)
                downY = event.getY(index)
                lastTouchX = downX
                lastTouchY = downY
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                downX = event.x
                downY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker.addMovement(event)
                if (activeSelectionHandle != SelectionHandle.NONE) {
                    updateSelectionHandle(event.x, event.y)
                    return true
                }
                if (longPressTriggered) return true
                handleMove(event)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                activeSelectionHandle = SelectionHandle.NONE
                if (isDraggingPage) {
                    finishDrag(commit = false)
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                velocityTracker.addMovement(event)
                velocityTracker.computeCurrentVelocity(1000)
                if (activeSelectionHandle != SelectionHandle.NONE) {
                    activeSelectionHandle = SelectionHandle.NONE
                    showSelectionMenu()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                if (longPressTriggered) {
                    longPressTriggered = false
                    if (isTextSelected) {
                        showSelectionMenu()
                    }
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                if (isDraggingPage) {
                    finishDrag(commit = if (pageAnim == M9PageAnim.SCROLL) true else shouldCommitDrag())
                } else if (consumedByDragProbe && dragDirection != 0) {
                    if (shouldCommitPreviewlessTurn(event)) {
                        invokeTurnCallback(direction = dragDirection)
                    }
                    resetPageLayers()
                } else if (!consumedByDragProbe) {
                    handleTap(event)
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun handleMove(event: MotionEvent) {
        if (isGestureAnimating) return
        val dx = event.x - downX
        val dy = event.y - downY
        if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
            removeCallbacks(longPressRunnable)
        }
        if (!isDraggingPage) {
            val direction = dragDirectionFor(dx, dy) ?: return
            val preview = onPagePreview?.invoke(direction)
            if (preview == null) {
                dragDirection = direction
                consumedByDragProbe = true
                return
            }
            beginDrag(direction, preview)
        }
        if (pageAnim == M9PageAnim.SCROLL) {
            val axisDelta = if (isScrollHorizontalAxis()) {
                event.x - lastTouchX
            } else {
                event.y - lastTouchY
            }
            lastTouchX = event.x
            lastTouchY = event.y
            updateScrollDrag(axisDelta)
            return
        }
        updateDrag(dx, dy)
    }

    private fun dragDirectionFor(dx: Float, dy: Float): Int? {
        return if (pageAnim == M9PageAnim.SCROLL) {
            if (isScrollHorizontalAxis()) {
                if (abs(dx) <= touchSlop || abs(dx) < abs(dy) * 0.65f) return null
                horizontalDragDirection(dx)
            } else {
                if (abs(dy) <= touchSlop || abs(dy) < abs(dx) * 0.65f) return null
                if (dy < 0f) 1 else -1
            }
        } else {
            if (abs(dx) <= touchSlop || abs(dx) < abs(dy) * 0.65f) return null
            horizontalDragDirection(dx)
        }
    }

    private fun beginDrag(direction: Int, preview: TextPage) {
        removeCallbacks(longPressRunnable)
        clearTextSelection()
        dragDirection = direction
        isDraggingPage = true
        consumedByDragProbe = true
        if (pageAnim == M9PageAnim.SCROLL) {
            targetPageView.visibility = GONE
            targetPageView.translationX = 0f
            targetPageView.translationY = 0f
            pageView.bringToFront()
            assistOverlay.bringToFront()
            scrollStartOffset = scrollContentOffset
            lastTouchX = downX
            lastTouchY = downY
            scrollPageStep = currentScrollExtent()
            updateScrollContentContext(scrollContentOffset)
            return
        }
        targetPageView.setPage(preview, null, null)
        targetPageView.visibility = VISIBLE
        targetPageView.alpha = 1f
        targetPageView.rotationY = 0f
        targetPageView.bringToFront()
        pageView.bringToFront()
        assistOverlay.bringToFront()
        when (pageAnim) {
            M9PageAnim.SLIDE -> {
                targetPageView.translationX = horizontalPageSide(direction) * width.toFloat()
                targetPageView.translationY = 0f
            }
            else -> {
                targetPageView.translationX = 0f
                targetPageView.translationY = 0f
            }
        }
    }

    private fun updateDrag(dx: Float, dy: Float) {
        when (pageAnim) {
            M9PageAnim.SCROLL -> updateScrollDrag(if (isScrollHorizontalAxis()) dx else dy)
            M9PageAnim.COVER -> updateCoverDrag(dx)
            M9PageAnim.SLIDE -> updateSlideDrag(dx)
            M9PageAnim.NONE -> Unit
        }
    }

    private fun updateCoverDrag(dx: Float) {
        val offset = clampPageOffset(dx, horizontalPageSide(dragDirection), width.toFloat())
        pageView.translationX = offset
        pageView.alpha = 1f - (abs(offset) / width.coerceAtLeast(1).toFloat()) * 0.08f
    }

    private fun updateSlideDrag(dx: Float) {
        val side = horizontalPageSide(dragDirection)
        val offset = clampPageOffset(dx, side, width.toFloat())
        pageView.translationX = offset
        targetPageView.translationX = side * width.toFloat() + offset
    }

    private fun updateScrollDrag(axisDelta: Float) {
        scrollPageStep = currentScrollExtent()
        updateScrollContentContext(scrollContentOffset + axisDelta)
    }

    private fun updateScrollDragFromScroller(deltaX: Float, deltaY: Float) {
        if (isScrollHorizontalAxis()) {
            updateScrollContentContext(scrollContentOffset + deltaX)
        } else {
            updateScrollContentContext(scrollContentOffset + deltaY)
        }
    }

    private fun clampPageOffset(value: Float, pageSide: Int, size: Float): Float {
        val safeSize = size.coerceAtLeast(1f)
        return if (pageSide > 0) {
            value.coerceIn(-safeSize, 0f)
        } else {
            value.coerceIn(0f, safeSize)
        }
    }

    private fun shouldCommitDrag(): Boolean {
        val distance = if (pageAnim == M9PageAnim.SCROLL && !isScrollHorizontalAxis()) {
            abs(scrollContentOffset)
        } else if (pageAnim == M9PageAnim.SCROLL) {
            abs(scrollContentOffset)
        } else {
            abs(pageView.translationX)
        }
        val size = if (pageAnim == M9PageAnim.SCROLL) {
            clickScrollDistance(dragDirection).toInt()
        } else {
            width
        }
        val velocity = if (pageAnim == M9PageAnim.SCROLL && !isScrollHorizontalAxis()) {
            abs(velocityTracker.yVelocity)
        } else {
            abs(velocityTracker.xVelocity)
        }
        return shouldCommitTurn(distance, size, velocity)
    }

    private fun shouldCommitPreviewlessTurn(event: MotionEvent): Boolean {
        val distance = if (pageAnim == M9PageAnim.SCROLL && !isScrollHorizontalAxis()) {
            abs(event.y - downY)
        } else {
            abs(event.x - downX)
        }
        val size = if (pageAnim == M9PageAnim.SCROLL && !isScrollHorizontalAxis()) height else width
        val velocity = if (pageAnim == M9PageAnim.SCROLL && !isScrollHorizontalAxis()) {
            abs(velocityTracker.yVelocity)
        } else {
            abs(velocityTracker.xVelocity)
        }
        return shouldCommitTurn(distance, size, velocity)
    }

    private fun shouldCommitTurn(distance: Float, size: Int, velocity: Float): Boolean {
        return distance >= size.coerceAtLeast(1) * 0.22f || velocity > MIN_FLING_VELOCITY
    }

    private fun finishDrag(commit: Boolean) {
        if (pageAnim == M9PageAnim.NONE) {
            if (commit) {
                suppressNextSetAnimation = true
                invokeTurnCallback()
            }
            resetPageLayers()
            return
        }
        if (pageAnim == M9PageAnim.SCROLL && noAnimScrollPage) {
            if (commit && abs(scrollContentOffset) <= 1f) {
                scrollPageStep = currentScrollExtent()
                updateScrollContentContext(
                    scrollContentOffset + scrollOffsetForDirection(dragDirection) * clickScrollDistance(dragDirection)
                )
            }
            finishScrollMotion()
            return
        }
        isGestureAnimating = true
        if (pageAnim == M9PageAnim.SCROLL) {
            finishScrollDrag(commit)
            return
        }
        val size = width.toFloat()
        val currentOffset = pageView.translationX
        val progress = abs(currentOffset) / size.coerceAtLeast(1f)
        val duration = (PAGE_DRAG_ANIM_MS * (1f - progress).coerceIn(0.25f, 1f)).toLong()
        val side = horizontalPageSide(dragDirection)
        val currentEnd = if (commit) -side * size else 0f
        val targetEnd = if (commit) 0f else side * size
        animateDragLayer(pageView, x = currentEnd, y = 0f, duration = duration)
        animateDragLayer(targetPageView, x = targetEnd, y = 0f, duration = duration) {
            if (commit) {
                suppressNextSetAnimation = true
                invokeTurnCallback()
            }
            resetPageLayers()
        }
    }

    private fun finishScrollDrag(commit: Boolean) {
        val clickDistance = clickScrollDistance(dragDirection).coerceAtLeast(1f)
        scrollPageStep = currentScrollExtent()
        if (!commit) {
            startScrollSettle(scrollStartOffset, PAGE_DRAG_ANIM_MS)
            return
        }
        val velocity = if (isScrollHorizontalAxis()) velocityTracker.xVelocity else velocityTracker.yVelocity
        if (abs(velocity) < MIN_FLING_VELOCITY) {
            if (abs(scrollContentOffset) <= 1f) {
                startScrollSettle(
                    scrollContentOffset + scrollOffsetForDirection(dragDirection) * clickDistance,
                    if (noAnimScrollPage) 1L else PROGRAMMATIC_PAGE_ANIM_MS
                )
                return
            }
            finishScrollMotion()
            return
        }
        val flingRange = if (isScrollHorizontalAxis()) width else height
        val maxOffset = flingRange.coerceAtLeast(1) * 10
        if (isScrollHorizontalAxis()) {
            scrollLastScrollerX = scrollContentOffset.toInt()
            scrollLastScrollerY = 0
            scrollScroller.fling(
                scrollLastScrollerX,
                0,
                velocity.toInt(),
                0,
                -maxOffset,
                maxOffset,
                0,
                0
            )
        } else {
            scrollLastScrollerX = 0
            scrollLastScrollerY = scrollContentOffset.toInt()
            scrollScroller.fling(
                0,
                scrollLastScrollerY,
                0,
                velocity.toInt(),
                0,
                0,
                -maxOffset,
                maxOffset
            )
        }
        postInvalidateOnAnimation()
    }

    private fun startScrollSettle(targetOffset: Float, duration: Long) {
        if (isScrollHorizontalAxis()) {
            scrollLastScrollerX = scrollContentOffset.toInt()
            scrollLastScrollerY = 0
            scrollScroller.startScroll(
                scrollLastScrollerX,
                0,
                (targetOffset - scrollContentOffset).toInt(),
                0,
                duration.toInt()
            )
        } else {
            scrollLastScrollerX = 0
            scrollLastScrollerY = scrollContentOffset.toInt()
            scrollScroller.startScroll(
                0,
                scrollLastScrollerY,
                0,
                (targetOffset - scrollContentOffset).toInt(),
                duration.toInt()
            )
        }
        postInvalidateOnAnimation()
    }

    private fun finishScrollMotion() {
        val committedDelta = scrollAnchorDelta
        val remainder = scrollContentOffset
        if (committedDelta != 0) {
            val committedDirection = if (committedDelta > 0) 1 else -1
            suppressNextSetAnimation = true
            invokeTurnCallback(abs(committedDelta), committedDirection)
            scrollAnchorDelta = 0
            scrollContentOffset = remainder
            scrollStartOffset = remainder
            scrollPageStep = currentScrollExtent()
            if (abs(remainder) > 1f) {
                updateScrollContentContext(remainder)
            } else {
                pageView.clearScrollContext()
            }
        } else if (abs(scrollContentOffset) > 1f) {
            scrollStartOffset = scrollContentOffset
            updateScrollContentContext(scrollContentOffset)
        } else {
            pageView.clearScrollContext()
            scrollContentOffset = 0f
            scrollStartOffset = 0f
        }
        isGestureAnimating = false
        isDraggingPage = false
        dragDirection = 0
        targetPageView.visibility = GONE
    }

    private fun scrollDirectionForOffset(offset: Float): Int {
        if (abs(offset) <= 1f) return 0
        return if (isScrollHorizontalAxis()) {
            if (offset > 0f) 1 else -1
        } else {
            if (offset < 0f) 1 else -1
        }
    }

    private fun scrollOffsetForDirection(direction: Int): Int {
        return if (isScrollHorizontalAxis()) {
            if (direction > 0) 1 else -1
        } else {
            if (direction > 0) -1 else 1
        }
    }

    private fun currentReaderMeanColor(): Int {
        return pageView.solidBackgroundColor ?: Color.TRANSPARENT
    }

    private fun animateDragLayer(
        view: View,
        x: Float,
        y: Float,
        duration: Long,
        endAction: (() -> Unit)? = null
    ) {
        view.animate()
            .translationX(x)
            .translationY(y)
            .rotationY(0f)
            .alpha(1f)
            .setInterpolator(pageInterpolator)
            .setDuration(duration)
            .withEndAction { endAction?.invoke() }
            .start()
    }

    private fun invokeTurnCallback(pageCount: Int = 1, direction: Int = dragDirection) {
        val count = pageCount.coerceAtLeast(1)
        onMovePages?.let { move ->
            move(direction * count)
            return
        }
        repeat(count) {
            if (direction > 0) {
                onNextPage?.invoke()
            } else {
                onPrevPage?.invoke()
            }
        }
    }

    private fun clickScrollDistance(direction: Int): Float {
        val retain = retainedScrollOverlap()
        val fallback = if (isScrollHorizontalAxis()) {
            (width - retain).coerceAtLeast(width * 0.55f)
        } else {
            (height - retain).coerceAtLeast(height * 0.55f)
        }
        return pageView.clickScrollDistance(
            horizontal = isScrollHorizontalAxis(),
            direction = direction,
            fallback = fallback
        )
    }

    private fun currentScrollExtent(): Float {
        return pageView.pageScrollExtent(pageForScrollDelta(scrollAnchorDelta), isScrollHorizontalAxis())
            .coerceAtLeast(1f)
    }

    private fun retainedScrollOverlap(): Float {
        return (textSizePx * if (isScrollHorizontalAxis()) 1.8f else 2.2f)
            .coerceIn(dp(24).toFloat(), if (isScrollHorizontalAxis()) width * 0.28f else height * 0.22f)
    }

    private fun abortGestureAnimation() {
        pageView.animate().cancel()
        targetPageView.animate().cancel()
        if (!scrollScroller.isFinished) scrollScroller.abortAnimation()
        isGestureAnimating = false
    }

    private fun resetPageLayers() {
        abortGestureAnimation()
        isDraggingPage = false
        dragDirection = 0
        targetPageView.visibility = GONE
        targetPageView.alpha = 1f
        targetPageView.translationX = 0f
        targetPageView.translationY = 0f
        targetPageView.rotationY = 0f
        pageView.clearScrollContext()
        scrollContentOffset = 0f
        scrollStartOffset = 0f
        scrollPageStep = 1f
        scrollAnchorDelta = 0
        pageView.visibility = VISIBLE
        pageView.alpha = 1f
        pageView.translationX = 0f
        pageView.translationY = 0f
        pageView.rotationY = 0f
    }

    private fun handleTap(event: MotionEvent) {
        if (isTextSelected) {
            clearTextSelection()
            return
        }
        pageView.findImageAt(event.x, event.y)?.let { image ->
            onImageClick?.invoke(image)
            if (onImageClick != null) return
        }
        pageView.findAssistTokenAt(event.x, event.y)?.let { token ->
            toggleAssistOverlay(token)
            return
        }
        if (assistOverlay.visibility == VISIBLE) {
            hideAssistOverlay()
            return
        }
        val regionIndex = tapRegionIndex(event.x, event.y)
        performTapAction(clickRegionActions.getOrElse(regionIndex) { TapAction.MENU })
    }

    private fun handleLongPressSelection() {
        if (isDraggingPage || isGestureAnimating) return
        if (!pageView.beginTextSelectionAt(downX, downY)) return
        isTextSelected = true
        updateSelectionOverlays()
    }

    private fun copySelectedTextAndClear() {
        val text = pageView.selectedText().trim()
        if (text.isBlank()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("reader_text", text))
        Toast.makeText(context, context.getString(R.string.reader_selection_copied), Toast.LENGTH_SHORT).show()
        clearTextSelection()
    }

    private fun performSelectionAction(action: SelectionAction) {
        when (action) {
            SelectionAction.COPY -> copySelectedTextAndClear()
            else -> {
                val text = pageView.selectedText().trim()
                if (text.isBlank()) return
                onSelectionAction?.invoke(action, text)
                clearTextSelection()
            }
        }
    }

    private fun performSelectionProcessText(intent: Intent) {
        val text = pageView.selectedText().trim()
        if (text.isBlank()) return
        onSelectionProcessText?.invoke(intent, text)
        clearTextSelection()
    }

    private fun updateSelectionHandle(x: Float, y: Float) {
        val changed = when (activeSelectionHandle) {
            SelectionHandle.START -> pageView.updateSelectionStartAt(x, y)
            SelectionHandle.END -> pageView.updateSelectionEndAt(x, y)
            SelectionHandle.NONE -> false
        }
        if (changed) {
            updateSelectionOverlays()
        }
    }

    private fun updateSelectionOverlays() {
        val bounds = pageView.selectionHandleBounds() ?: return
        positionHandle(selectionStartHandle, bounds.first, start = true)
        positionHandle(selectionEndHandle, bounds.second, start = false)
        selectionStartHandle.visibility = VISIBLE
        selectionEndHandle.visibility = VISIBLE
        selectionStartHandle.bringToFront()
        selectionEndHandle.bringToFront()
    }

    private fun positionHandle(handle: View, rect: RectF, start: Boolean) {
        val params = handle.layoutParams as LayoutParams
        val size = dp(18)
        params.leftMargin = (rect.centerX() - size / 2f).toInt().coerceIn(0, (width - size).coerceAtLeast(0))
        params.topMargin = if (start) {
            (rect.top - size).toInt()
        } else {
            rect.bottom.toInt()
        }.coerceIn(0, (height - size).coerceAtLeast(0))
        handle.layoutParams = params
    }

    private fun showSelectionMenu() {
        updateSelectionOverlays()
        val bounds = pageView.selectionHandleBounds() ?: return
        val startRect = bounds.first
        val endRect = bounds.second
        selectionActionMenu.show(
            anchor = this,
            startX = minOf(startRect.centerX(), endRect.centerX()).toInt(),
            startTopY = minOf(startRect.top, endRect.top).toInt(),
            startBottomY = minOf(startRect.bottom, endRect.bottom).toInt(),
            endX = maxOf(startRect.centerX(), endRect.centerX()).toInt(),
            endBottomY = maxOf(startRect.bottom, endRect.bottom).toInt()
        )
    }

    private fun hideSelectionMenu() {
        selectionActionMenu.dismiss()
    }

    private fun clearTextSelection() {
        if (!isTextSelected) return
        pageView.clearTextSelection()
        isTextSelected = false
        activeSelectionHandle = SelectionHandle.NONE
        selectionActionMenu.dismiss()
        selectionStartHandle.visibility = GONE
        selectionEndHandle.visibility = GONE
    }

    private fun selectionHandleAt(x: Float, y: Float): SelectionHandle {
        if (!isTextSelected) return SelectionHandle.NONE
        val touch = dp(28).toFloat()
        fun hit(view: View): Boolean {
            if (view.visibility != VISIBLE) return false
            val cx = view.left + view.width / 2f
            val cy = view.top + view.height / 2f
            return abs(x - cx) <= touch && abs(y - cy) <= touch
        }
        return when {
            hit(selectionStartHandle) -> SelectionHandle.START
            hit(selectionEndHandle) -> SelectionHandle.END
            else -> SelectionHandle.NONE
        }
    }

    private fun selectionHandleDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF858585.toInt())
            setStroke(dp(2), 0xFFFFFFFF.toInt())
        }
    }

    private fun turnPageByTap(direction: Int) {
        if (isGestureAnimating || width <= 0 || height <= 0) return
        val preview = onPagePreview?.invoke(direction)
        if (preview == null) {
            invokeTurnCallback(direction = direction)
            return
        }
        abortGestureAnimation()
        beginDrag(direction, preview)
        if (pageAnim == M9PageAnim.SCROLL) {
            isGestureAnimating = true
            startScrollSettle(
                scrollContentOffset + scrollOffsetForDirection(direction) * clickScrollDistance(direction),
                if (noAnimScrollPage) 1L else PROGRAMMATIC_PAGE_ANIM_MS
            )
            return
        }
        finishDrag(commit = true)
    }

    private fun tapRegionIndex(x: Float, y: Float): Int {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val column = (x / (safeWidth / 3f)).toInt().coerceIn(0, 2)
        val row = (y / (safeHeight / 3f)).toInt().coerceIn(0, 2)
        return row * 3 + column
    }

    private fun performTapAction(action: TapAction) {
        when (action) {
            TapAction.NONE -> Unit
            TapAction.MENU -> onMenu?.invoke()
            TapAction.NEXT_PAGE -> turnPageByTap(1)
            TapAction.PREV_PAGE -> turnPageByTap(-1)
            else -> onTapAction?.invoke(action)
        }
    }

    enum class TapAction {
        NONE,
        MENU,
        NEXT_PAGE,
        PREV_PAGE,
        NEXT_CHAPTER,
        PREV_CHAPTER,
        PREV_AUDIO_CUE,
        NEXT_AUDIO_CUE,
        ADD_BOOKMARK,
        TOGGLE_CONVERT,
        CATALOG,
        SEARCH
    }

    enum class SelectionAction {
        COPY,
        SHARE,
        SEARCH,
        ADD_BOOKMARK,
        BROWSER
    }

    private enum class SelectionHandle {
        NONE,
        START,
        END
    }

    private fun toggleAssistOverlay(token: ContentTextView.AssistToken) {
        val current = assistToken
        if (current != null &&
            current.sourceStart == token.sourceStart &&
            current.sourceEnd == token.sourceEnd &&
            current.text == token.text
        ) {
            hideAssistOverlay()
            return
        }
        assistToken = token
        assistOverlay.text = token.text
        updateAssistOverlayStyle()
        assistOverlay.post {
            assistOverlay.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
            )
            val target = computeOverlayBounds(
                anchor = token.rect,
                overlayWidth = assistOverlay.measuredWidth,
                overlayHeight = assistOverlay.measuredHeight
            )
            val params = assistOverlay.layoutParams as LayoutParams
            params.leftMargin = target.left.toInt()
            params.topMargin = target.top.toInt()
            assistOverlay.layoutParams = params
            assistOverlay.visibility = VISIBLE
            assistOverlay.bringToFront()
        }
    }

    private fun hideAssistOverlay() {
        assistToken = null
        assistOverlay.visibility = GONE
    }

    private fun computeOverlayBounds(anchor: RectF, overlayWidth: Int, overlayHeight: Int): RectF {
        val margin = dp(12).toFloat()
        val maxLeft = (width - overlayWidth - margin).coerceAtLeast(margin)
        val maxTop = (height - overlayHeight - margin).coerceAtLeast(margin)
        val left = when {
            anchor.left - overlayWidth - margin >= margin -> anchor.left - overlayWidth - margin
            anchor.right + overlayWidth + margin <= width - margin -> anchor.right + margin
            else -> (anchor.centerX() - overlayWidth / 2f).coerceIn(margin, maxLeft)
        }
        val top = (anchor.centerY() - overlayHeight / 2f).coerceIn(margin, maxTop)
        return RectF(left, top, left + overlayWidth, top + overlayHeight)
    }

    private fun updateAssistOverlayStyle() {
        assistOverlay.setTextColor(overlayTextColor)
        assistOverlay.background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(overlayBgColor)
            setStroke(dp(1), if (isDarkColor(overlayBgColor)) 0x33FFFFFF else 0x22000000)
        }
    }

    private fun isDarkColor(color: Int): Boolean {
        val darkness = 1.0 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
        return darkness >= 0.45
    }

    private inline fun forEachPageView(block: (PageView) -> Unit) {
        block(pageView)
        block(targetPageView)
    }

    private fun horizontalDragDirection(dx: Float): Int {
        return if (layoutMode == M9LayoutMode.VERTICAL) {
            if (dx > 0f) 1 else -1
        } else {
            if (dx < 0f) 1 else -1
        }
    }

    private fun horizontalPageSide(direction: Int): Int {
        return if (layoutMode == M9LayoutMode.VERTICAL) {
            -direction
        } else {
            direction
        }
    }

    private fun isScrollHorizontalAxis(): Boolean {
        return pageAnim == M9PageAnim.SCROLL && layoutMode == M9LayoutMode.VERTICAL
    }

    private fun updateScrollContentContext(offset: Float) {
        val normalizedOffset = normalizeScrollOffset(offset)
        scrollContentOffset = normalizedOffset
        val radius = SCROLL_CONTEXT_RADIUS
        val pages = (-radius..radius).map { delta ->
            pageForScrollDelta(scrollAnchorDelta + delta)
        }
        pageView.translationX = 0f
        pageView.translationY = 0f
        targetPageView.visibility = GONE
        pageView.setScrollContext(
            pages = pages,
            centerIndex = radius,
            offset = normalizedOffset,
            horizontal = isScrollHorizontalAxis(),
            reverse = isScrollHorizontalAxis()
        )
    }

    private fun normalizeScrollOffset(rawOffset: Float): Float {
        var offset = rawOffset
        var step = currentScrollExtent()
        scrollPageStep = step
        while (abs(offset) >= step) {
            val direction = scrollDirectionForOffset(offset)
            if (direction == 0) return 0f
            val nextAnchor = scrollAnchorDelta + direction
            val offsetSign = scrollOffsetForDirection(direction)
            if (pageForScrollDelta(nextAnchor) == null) {
                if (!scrollScroller.isFinished) {
                    scrollScroller.abortAnimation()
                }
                return 0f
            }
            scrollAnchorDelta = nextAnchor
            offset -= offsetSign * step
            step = currentScrollExtent()
            scrollPageStep = step
        }
        return offset
    }

    private fun pageForScrollDelta(delta: Int): TextPage? {
        return if (delta == 0) pageView.currentPage else onPagePreview?.invoke(delta)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val CLICK_REGION_COUNT: Int = 9
        private const val MIN_FLING_VELOCITY = 900f
        private const val PAGE_DRAG_ANIM_MS = 260L
        private const val PROGRAMMATIC_PAGE_ANIM_MS = 220L
        private const val SCROLL_CONTEXT_RADIUS = 4

        fun defaultClickRegionActions(layoutMode: M9LayoutMode = M9LayoutMode.HORIZONTAL): List<TapAction> {
            return if (layoutMode == M9LayoutMode.VERTICAL) {
                listOf(
                    TapAction.NEXT_PAGE,
                    TapAction.NEXT_PAGE,
                    TapAction.PREV_PAGE,
                    TapAction.NEXT_PAGE,
                    TapAction.MENU,
                    TapAction.PREV_PAGE,
                    TapAction.NEXT_PAGE,
                    TapAction.PREV_PAGE,
                    TapAction.PREV_PAGE
                )
            } else {
                listOf(
                    TapAction.PREV_PAGE,
                    TapAction.PREV_PAGE,
                    TapAction.NEXT_PAGE,
                    TapAction.PREV_PAGE,
                    TapAction.MENU,
                    TapAction.NEXT_PAGE,
                    TapAction.PREV_PAGE,
                    TapAction.NEXT_PAGE,
                    TapAction.NEXT_PAGE
                )
            }
        }
    }

    private class SelectionActionMenu(
        context: Context,
        private val onAction: (SelectionAction) -> Unit,
        private val onProcessText: (Intent) -> Unit
    ) {
        private val popupWindow: PopupWindow
        private val rootView: LinearLayout
        private val primaryRow: LinearLayout
        private val morePage: LinearLayout
        private val moreList: LinearLayout
        private val moreScrollView: ScrollView
        private val moreButton: TextView
        private var expanded = false
        private var lastAnchor: View? = null
        private var lastStartX: Int = 0
        private var lastStartTopY: Int = 0
        private var lastStartBottomY: Int = 0
        private var lastEndX: Int = 0
        private var lastEndBottomY: Int = 0

        init {
            primaryRow = row(context)
            moreList = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            morePage = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                addView(row(context).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    addView(backButton(context) { toggleExpanded() })
                })
                moreScrollView = ScrollView(context).apply {
                    isFillViewport = false
                    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                    addView(moreList)
                }
                addView(moreScrollView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(context, 240)))
            }
            moreButton = overflowButton(context) {
                toggleExpanded()
            }
            primaryRow.addView(actionButton(context, context.getString(android.R.string.copy)) { onAction(SelectionAction.COPY) })
            primaryRow.addView(actionButton(context, context.getString(R.string.reader_menu_add_bookmark)) { onAction(SelectionAction.ADD_BOOKMARK) })
            primaryRow.addView(actionButton(context, context.getString(R.string.search_content)) { onAction(SelectionAction.SEARCH) })
            primaryRow.addView(moreButton)

            moreList.addView(moreActionButton(context, context.getString(R.string.reader_selection_share)) { onAction(SelectionAction.SHARE) })
            moreList.addView(moreActionButton(context, context.getString(R.string.reader_selection_browser_search)) { onAction(SelectionAction.BROWSER) })
            processTextIntents(context).forEach { item ->
                moreList.addView(moreActionButton(context, item.label) { onProcessText(item.intent) })
            }

            rootView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 6), dp(context, 5), dp(context, 6), dp(context, 5))
                background = GradientDrawable().apply {
                    cornerRadius = dp(context, 12).toFloat()
                    setColor(0xFFF7F0E2.toInt())
                    setStroke(dp(context, 1), 0x1F000000)
                }
                elevation = dp(context, 8).toFloat()
                addView(primaryRow)
                addView(morePage)
            }
            popupWindow = PopupWindow(
                rootView,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                false
            ).apply {
                isTouchable = true
                isFocusable = false
                isOutsideTouchable = false
                setBackgroundDrawable(null)
            }
        }

        fun show(
            anchor: View,
            startX: Int,
            startTopY: Int,
            startBottomY: Int,
            endX: Int,
            endBottomY: Int,
            resetToDefault: Boolean = true
        ) {
            if (anchor.width <= 0 || anchor.height <= 0) return
            if (resetToDefault) {
                restoreDefaultPage()
            }
            lastAnchor = anchor
            lastStartX = startX
            lastStartTopY = startTopY
            lastStartBottomY = startBottomY
            lastEndX = endX
            lastEndBottomY = endBottomY
            rootView.requestLayout()
            rootView.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(anchor.height, View.MeasureSpec.AT_MOST)
            )
            val margin = dp(anchor.context, 8)
            val popupWidth = rootView.measuredWidth.coerceAtMost(anchor.width - margin * 2)
            val popupHeight = rootView.measuredHeight
            val openSpaceThreshold = dp(anchor.context, 160)
            val (targetX, targetY) = when {
                startBottomY > openSpaceThreshold -> {
                    startX to (startTopY - popupHeight).coerceAtLeast(margin)
                }
                endBottomY - startBottomY > openSpaceThreshold -> {
                    startX to startBottomY
                }
                else -> {
                    endX to endBottomY
                }
            }
            val safeX = targetX.coerceIn(margin, (anchor.width - popupWidth - margin).coerceAtLeast(margin))
            if (popupWindow.isShowing) {
                popupWindow.update(safeX, targetY, popupWidth, popupHeight)
            } else {
                popupWindow.width = popupWidth
                popupWindow.height = popupHeight
                popupWindow.showAtLocation(anchor, Gravity.TOP or Gravity.START, safeX, targetY)
            }
        }

        fun dismiss() {
            restoreDefaultPage()
            popupWindow.dismiss()
        }

        private fun restoreDefaultPage() {
            expanded = false
            primaryRow.visibility = View.VISIBLE
            morePage.visibility = View.GONE
            moreScrollView.scrollTo(0, 0)
        }

        private fun toggleExpanded() {
            expanded = !expanded
            primaryRow.visibility = if (expanded) View.GONE else View.VISIBLE
            morePage.visibility = if (expanded) View.VISIBLE else View.GONE
            val anchor = lastAnchor ?: return
            show(
                anchor = anchor,
                startX = lastStartX,
                startTopY = lastStartTopY,
                startBottomY = lastStartBottomY,
                endX = lastEndX,
                endBottomY = lastEndBottomY,
                resetToDefault = false
            )
        }

        private fun row(context: Context): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
        }

        private fun actionButton(context: Context, label: String, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setSingleLine(true)
                maxLines = 1
                setTextColor(0xFF2C241B.toInt())
                textSize = 13f
                minWidth = dp(context, 56)
                setPadding(dp(context, 10), dp(context, 7), dp(context, 10), dp(context, 7))
                setOnClickListener { onClick() }
            }
        }

        private fun moreActionButton(context: Context, label: String, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = label
                gravity = Gravity.CENTER_VERTICAL
                setSingleLine(true)
                setTextColor(0xFF2C241B.toInt())
                textSize = 14f
                minWidth = dp(context, 160)
                setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
                setOnClickListener { onClick() }
            }
        }

        private fun backButton(context: Context, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = "‹"
                gravity = Gravity.CENTER
                setTextColor(0xFF2C241B.toInt())
                textSize = 22f
                setPadding(dp(context, 10), dp(context, 2), dp(context, 10), dp(context, 2))
                contentDescription = context.getString(R.string.reader_selection_more)
                setOnClickListener { onClick() }
            }
        }

        private fun overflowButton(context: Context, onClick: () -> Unit): TextView {
            return TextView(context).apply {
                text = "⋮"
                gravity = Gravity.CENTER
                setSingleLine(true)
                setTextColor(0xFF2C241B.toInt())
                textSize = 16f
                setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
                contentDescription = context.getString(R.string.reader_selection_more)
                setOnClickListener { onClick() }
            }
        }

        private fun dp(context: Context, value: Int): Int {
            return (value * context.resources.displayMetrics.density).toInt()
        }

        private fun processTextIntents(context: Context): List<ProcessTextItem> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
            val baseIntent = Intent()
                .setAction(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
            val packageManager = context.packageManager
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    baseIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(baseIntent, PackageManager.MATCH_ALL)
            }
            return resolveInfos
                .mapNotNull { info ->
                    val activity = info.activityInfo ?: return@mapNotNull null
                    val label = info.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    ProcessTextItem(
                        label = label,
                        intent = Intent(baseIntent).setClassName(activity.packageName, activity.name)
                    )
                }
                .distinctBy { item -> item.intent.component?.flattenToString() }
        }

        private data class ProcessTextItem(
            val label: String,
            val intent: Intent
        )
    }
}
