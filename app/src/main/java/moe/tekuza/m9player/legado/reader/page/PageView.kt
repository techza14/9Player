package moe.tekuza.m9player.legado.reader.page

import android.content.Context
import android.os.BatteryManager
import android.view.Gravity
import android.view.View
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.Typeface
import android.graphics.RectF
import android.net.Uri
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue
import moe.tekuza.m9player.EbookImageRef
import moe.tekuza.m9player.ReaderBodyTitleMode
import moe.tekuza.m9player.ReaderFooterMode
import moe.tekuza.m9player.ReaderHeaderMode
import moe.tekuza.m9player.ReaderTipContent
import moe.tekuza.m9player.legado.reader.M9TextWeight
import moe.tekuza.m9player.legado.reader.entities.TextPage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class PageView(context: Context) : LinearLayout(context) {
    private val headerLeftView = TextView(context)
    private val headerMiddleView = TextView(context)
    private val headerRightView = TextView(context)
    private val bodyTitleView = TextView(context)
    val contentView = ContentTextView(context)
    private val footerLeftView = TextView(context)
    private val footerMiddleView = TextView(context)
    private val footerRightView = TextView(context)
    private val headerDividerView = View(context)
    private val footerDividerView = View(context)
    var solidBackgroundColor: Int? = null
        private set
    var currentPage: TextPage? = null
        private set
    private lateinit var headerView: LinearLayout
    private lateinit var footerView: LinearLayout
    private var showHeaderFooter: Boolean = true
    private var bookTitle: String = ""
    private var displayedChapterTitle: String? = null
    private var headerMode: ReaderHeaderMode = ReaderHeaderMode.HIDE_WHEN_STATUS_BAR_SHOW
    private var footerMode: ReaderFooterMode = ReaderFooterMode.SHOW
    private var bodyTitleMode: ReaderBodyTitleMode = ReaderBodyTitleMode.LEFT
    private var bodyTitleSizeAddSp: Int = 0
    private var bodyTitleTopSpacingDp: Int = 0
    private var bodyTitleBottomSpacingDp: Int = 0
    private var contentTextSizeSp: Float = 20f
    private var tipHeaderLeft: ReaderTipContent = ReaderTipContent.CHAPTER_TITLE
    private var tipHeaderMiddle: ReaderTipContent = ReaderTipContent.NONE
    private var tipHeaderRight: ReaderTipContent = ReaderTipContent.TIME
    private var tipFooterLeft: ReaderTipContent = ReaderTipContent.BOOK_NAME
    private var tipFooterMiddle: ReaderTipContent = ReaderTipContent.NONE
    private var tipFooterRight: ReaderTipContent = ReaderTipContent.PAGE_AND_TOTAL
    private var statusBarHidden: Boolean = false
    private var dividerColor: Int? = null
    private var showHeaderLine: Boolean = false
    private var showFooterLine: Boolean = true

    init {
        orientation = VERTICAL
        setReaderPadding(dp(22), dp(34), dp(22), dp(22))
        addView(LinearLayout(context).apply {
            headerView = this
            gravity = Gravity.CENTER_VERTICAL
            addTipView(headerLeftView, Gravity.START or Gravity.CENTER_VERTICAL)
            addTipView(headerMiddleView, Gravity.CENTER)
            addTipView(headerRightView, Gravity.END or Gravity.CENTER_VERTICAL)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(headerDividerView, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))
        addView(bodyTitleView.apply {
            includeFontPadding = true
            maxLines = 2
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(contentView.apply {
            setPadding(0, dp(18), 0, dp(18))
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(footerDividerView, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))
        addView(LinearLayout(context).apply {
            footerView = this
            gravity = Gravity.CENTER_VERTICAL
            addTipView(footerLeftView, Gravity.START or Gravity.CENTER_VERTICAL)
            addTipView(footerMiddleView, Gravity.CENTER)
            addTipView(footerRightView, Gravity.END or Gravity.CENTER_VERTICAL)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun LinearLayout.addTipView(view: TextView, viewGravity: Int) {
        addView(view.apply {
            textSize = 12f
            maxLines = 1
            gravity = viewGravity
            includeFontPadding = true
        }, LayoutParams(0, dp(36), 1f))
    }

    fun setPage(page: TextPage, highlight: IntRange?, search: IntRange?) {
        currentPage = page
        contentView.setPage(page, highlight, search)
        updateTipText()
    }

    fun setHighlight(highlight: IntRange?) {
        contentView.setHighlight(highlight)
    }

    fun setScrollContext(
        pages: List<TextPage?>,
        centerIndex: Int,
        offset: Float,
        horizontal: Boolean,
        reverse: Boolean
    ) {
        contentView.setScrollContext(pages, centerIndex, offset, horizontal, reverse)
    }

    fun clearScrollContext() {
        contentView.clearScrollContext()
    }

    fun clickScrollDistance(horizontal: Boolean, direction: Int, fallback: Float): Float {
        return contentView.clickScrollDistance(horizontal, direction, fallback)
    }

    fun pageScrollExtent(page: TextPage?, horizontal: Boolean): Float {
        return contentView.pageScrollExtent(page, horizontal)
    }

    fun findImageAt(x: Float, y: Float): EbookImageRef? {
        return contentView.findImageAt(
            x = x - contentView.left,
            y = y - contentView.top
        )
    }

    fun findAssistTokenAt(x: Float, y: Float): ContentTextView.AssistToken? {
        val token = contentView.findAssistTokenAt(
            x = x - contentView.left,
            y = y - contentView.top
        ) ?: return null
        return token.copy(
            rect = RectF(token.rect).apply {
                offset(contentView.left.toFloat(), contentView.top.toFloat())
            }
        )
    }

    fun beginTextSelectionAt(x: Float, y: Float): Boolean {
        return contentView.beginSelectionAt(
            x = x - contentView.left,
            y = y - contentView.top
        )
    }

    fun updateSelectionStartAt(x: Float, y: Float): Boolean {
        return contentView.updateSelectionStartAt(
            x = x - contentView.left,
            y = y - contentView.top
        )
    }

    fun updateSelectionEndAt(x: Float, y: Float): Boolean {
        return contentView.updateSelectionEndAt(
            x = x - contentView.left,
            y = y - contentView.top
        )
    }

    fun clearTextSelection() {
        contentView.clearSelection()
    }

    fun selectedText(): String = contentView.selectedText()

    val selectionBackgroundInsetPx: Float
        get() = contentView.selectionBackgroundInsetPx

    fun consumePendingSelectionHandleRole(): ContentTextView.SelectionHandleRole? {
        return contentView.consumePendingSelectionHandleRole()
    }

    fun selectionBounds(): ContentTextView.SelectionBounds? {
        val bounds = contentView.selectionBounds() ?: return null
        val contentLeft = contentView.left.toFloat()
        val contentTop = contentView.top.toFloat()
        bounds.startRect.offset(contentLeft, contentTop)
        bounds.endRect.offset(contentLeft, contentTop)
        return bounds.copy(
            startLineTop = bounds.startLineTop + contentTop
        )
    }

    fun rangeBounds(range: IntRange): RectF? {
        return contentView.rangeBounds(range)?.apply {
            offset(contentView.left.toFloat(), contentView.top.toFloat())
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
        solidBackgroundColor = bg
        background = readerBackground(bg, bgAssetName, bgImageUri, bgAlpha)
        contentView.setTextColor(text)
        contentView.setHighlightTextColor(text)
        listOf(
            headerLeftView,
            headerMiddleView,
            headerRightView,
            bodyTitleView,
            footerLeftView,
            footerMiddleView,
            footerRightView
        ).forEach { it.setTextColor(tip) }
        headerDividerView.setBackgroundColor(dividerColor ?: withAlpha(text, 0.12f))
        footerDividerView.setBackgroundColor(dividerColor ?: withAlpha(text, 0.12f))
    }

    fun setCueHighlightColor(color: Int) {
        contentView.setHighlightBackgroundColor(color)
    }

    private fun readerBackground(bg: Int, bgAssetName: String?, bgImageUri: String?, bgAlpha: Int): Drawable {
        val alpha = (bgAlpha.coerceIn(0, 100) * 255 / 100)
        val drawable = when {
            !bgImageUri.isNullOrBlank() -> runCatching {
                context.contentResolver.openInputStream(Uri.parse(bgImageUri))?.use { input ->
                    Drawable.createFromStream(input, bgImageUri)
                }
            }.getOrNull() ?: ColorDrawable(bg)
            !bgAssetName.isNullOrBlank() -> runCatching {
                context.assets.open("legado_bg/$bgAssetName").use { input ->
                    Drawable.createFromStream(input, bgAssetName)
                }
            }.getOrNull() ?: ColorDrawable(bg)
            else -> ColorDrawable(bg)
        }
        drawable.alpha = alpha
        return drawable
    }

    fun setTextSizeSp(sizeSp: Float) {
        contentTextSizeSp = sizeSp
        contentView.setTextSizePx(
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)
        )
        applyBodyTitleStyle()
    }

    fun setTextWeight(weight: M9TextWeight) {
        contentView.setTextWeight(weight)
    }

    fun setTextUnderline(enabled: Boolean) {
        contentView.setTextUnderline(enabled)
    }

    fun setReaderTypeface(typeface: Typeface?) {
        contentView.setReaderTypeface(typeface)
        bodyTitleView.typeface = typeface ?: Typeface.DEFAULT
    }

    fun setReaderPadding(left: Int, top: Int, right: Int, bottom: Int) {
        setPadding(left, top, right, bottom)
    }

    fun setShowHeaderFooter(show: Boolean) {
        showHeaderFooter = show
        applyHeaderFooterVisibility()
    }

    fun setBookTitle(title: String) {
        bookTitle = title
        updateTipText()
    }

    fun setDisplayedChapterTitle(title: String?) {
        val nextTitle = title?.takeIf { it.isNotBlank() }
        if (displayedChapterTitle == nextTitle) return
        displayedChapterTitle = nextTitle
        updateTipText()
    }

    fun setReaderInfoConfig(
        bodyTitleMode: ReaderBodyTitleMode,
        bodyTitleSizeAddSp: Int,
        bodyTitleTopSpacingDp: Int,
        bodyTitleBottomSpacingDp: Int,
        headerMode: ReaderHeaderMode,
        footerMode: ReaderFooterMode,
        headerLeft: ReaderTipContent,
        headerMiddle: ReaderTipContent,
        headerRight: ReaderTipContent,
        footerLeft: ReaderTipContent,
        footerMiddle: ReaderTipContent,
        footerRight: ReaderTipContent,
        statusBarHidden: Boolean,
        dividerColor: Int?,
        headerPaddingTopDp: Int,
        headerPaddingBottomDp: Int,
        headerPaddingLeftDp: Int,
        headerPaddingRightDp: Int,
        footerPaddingTopDp: Int,
        footerPaddingBottomDp: Int,
        footerPaddingLeftDp: Int,
        footerPaddingRightDp: Int,
        showHeaderLine: Boolean,
        showFooterLine: Boolean
    ) {
        this.bodyTitleMode = bodyTitleMode
        this.bodyTitleSizeAddSp = bodyTitleSizeAddSp
        this.bodyTitleTopSpacingDp = bodyTitleTopSpacingDp
        this.bodyTitleBottomSpacingDp = bodyTitleBottomSpacingDp
        this.headerMode = headerMode
        this.footerMode = footerMode
        tipHeaderLeft = headerLeft
        tipHeaderMiddle = headerMiddle
        tipHeaderRight = headerRight
        tipFooterLeft = footerLeft
        tipFooterMiddle = footerMiddle
        tipFooterRight = footerRight
        this.statusBarHidden = statusBarHidden
        this.dividerColor = dividerColor
        this.showHeaderLine = showHeaderLine
        this.showFooterLine = showFooterLine
        headerView.setPadding(
            dp(headerPaddingLeftDp),
            dp(headerPaddingTopDp),
            dp(headerPaddingRightDp),
            dp(headerPaddingBottomDp)
        )
        footerView.setPadding(
            dp(footerPaddingLeftDp),
            dp(footerPaddingTopDp),
            dp(footerPaddingRightDp),
            dp(footerPaddingBottomDp)
        )
        updateTipText()
        applyHeaderFooterVisibility()
        applyBodyTitleStyle()
    }

    private fun applyHeaderFooterVisibility() {
        val showHeader = showHeaderFooter && when (headerMode) {
            ReaderHeaderMode.SHOW -> true
            ReaderHeaderMode.HIDE -> false
            ReaderHeaderMode.HIDE_WHEN_STATUS_BAR_SHOW -> statusBarHidden
        }
        val showFooter = showHeaderFooter && footerMode == ReaderFooterMode.SHOW
        headerLeftView.visibility = if (showHeader && tipHeaderLeft != ReaderTipContent.NONE) View.VISIBLE else View.INVISIBLE
        headerMiddleView.visibility = if (showHeader && tipHeaderMiddle != ReaderTipContent.NONE) View.VISIBLE else View.INVISIBLE
        headerRightView.visibility = if (showHeader && tipHeaderRight != ReaderTipContent.NONE) View.VISIBLE else View.INVISIBLE
        footerLeftView.visibility = if (showFooter && tipFooterLeft != ReaderTipContent.NONE) View.VISIBLE else View.INVISIBLE
        footerMiddleView.visibility = if (showFooter && tipFooterMiddle != ReaderTipContent.NONE) View.VISIBLE else View.INVISIBLE
        footerRightView.visibility = if (showFooter && tipFooterRight != ReaderTipContent.NONE) View.VISIBLE else View.INVISIBLE
        headerView.visibility = if (showHeader) View.VISIBLE else View.GONE
        footerView.visibility = if (showFooter) View.VISIBLE else View.GONE
        headerDividerView.visibility = if (showHeader && showHeaderLine && dividerColor != null) View.VISIBLE else View.GONE
        footerDividerView.visibility = if (showFooter && showFooterLine && dividerColor != null) View.VISIBLE else View.GONE
    }

    private fun updateTipText() {
        val page = currentPage
        headerLeftView.text = tipText(tipHeaderLeft, page)
        headerMiddleView.text = tipText(tipHeaderMiddle, page)
        headerRightView.text = tipText(tipHeaderRight, page)
        footerLeftView.text = tipText(tipFooterLeft, page)
        footerMiddleView.text = tipText(tipFooterMiddle, page)
        footerRightView.text = tipText(tipFooterRight, page)
        bodyTitleView.text = page?.title.orEmpty()
        applyBodyTitleStyle()
    }

    private fun applyBodyTitleStyle() {
        val page = currentPage
        val show = page != null && page.pageInChapter == 0 && bodyTitleMode != ReaderBodyTitleMode.HIDE
        bodyTitleView.visibility = if (show) View.VISIBLE else View.GONE
        bodyTitleView.gravity = when (bodyTitleMode) {
            ReaderBodyTitleMode.LEFT -> Gravity.START
            ReaderBodyTitleMode.CENTER -> Gravity.CENTER
            ReaderBodyTitleMode.HIDE -> Gravity.START
        }
        bodyTitleView.textSize = contentTextSizeSp + bodyTitleSizeAddSp
        (bodyTitleView.layoutParams as? LayoutParams)?.let { params ->
            params.topMargin = dp(bodyTitleTopSpacingDp)
            params.bottomMargin = dp(bodyTitleBottomSpacingDp)
            bodyTitleView.layoutParams = params
        }
    }

    private fun tipText(content: ReaderTipContent, page: TextPage?): String {
        if (page == null) return ""
        return when (content) {
            ReaderTipContent.NONE -> ""
            ReaderTipContent.BOOK_NAME -> bookTitle.ifBlank { page.title }
            ReaderTipContent.CHAPTER_TITLE -> displayedChapterTitle ?: page.title
            ReaderTipContent.TIME -> currentClockText()
            ReaderTipContent.BATTERY_PERCENTAGE -> "${batteryPercent()}%"
            ReaderTipContent.PAGE -> "${page.globalIndex + 1} / ${page.totalPages}"
            ReaderTipContent.TOTAL_PROGRESS -> page.readProgress
            ReaderTipContent.CHAPTER_PROGRESS -> "${page.pageInChapter + 1}/${page.chapterPageCount}"
            ReaderTipContent.PAGE_AND_TOTAL -> "${page.globalIndex + 1} / ${page.totalPages}  ${page.readProgress}"
            ReaderTipContent.TIME_BATTERY_PERCENTAGE -> "${currentClockText()} ${batteryPercent()}%"
        }
    }

    private fun batteryPercent(): Int {
        return runCatching {
            context.getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it >= 0 }
                ?: 100
        }.getOrDefault(100)
    }

    private fun currentClockText(): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val safeAlpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return (color and 0x00FFFFFF) or (safeAlpha shl 24)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
