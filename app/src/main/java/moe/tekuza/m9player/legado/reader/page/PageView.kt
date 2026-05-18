package moe.tekuza.m9player.legado.reader.page

import android.content.Context
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
import moe.tekuza.m9player.legado.reader.M9TextWeight
import moe.tekuza.m9player.legado.reader.entities.TextPage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class PageView(context: Context) : LinearLayout(context) {
    private val titleView = TextView(context)
    val contentView = ContentTextView(context)
    private val pageView = TextView(context)
    private val progressView = TextView(context)
    private val clockView = TextView(context)
    var solidBackgroundColor: Int? = null
        private set
    var currentPage: TextPage? = null
        private set
    private lateinit var headerView: LinearLayout
    private lateinit var footerView: LinearLayout
    private var showHeaderFooter: Boolean = true

    init {
        orientation = VERTICAL
        setReaderPadding(dp(22), dp(34), dp(22), dp(22))
        addView(LinearLayout(context).apply {
            headerView = this
            gravity = Gravity.CENTER_VERTICAL
            addView(titleView.apply {
                textSize = 12f
                maxLines = 1
            }, LayoutParams(0, dp(36), 1f))
            addView(clockView.apply {
                text = currentClockText()
                textSize = 12f
                gravity = Gravity.CENTER_VERTICAL
            }, LayoutParams(LayoutParams.WRAP_CONTENT, dp(36)))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(contentView.apply {
            setPadding(0, dp(18), 0, dp(18))
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(LinearLayout(context).apply {
            footerView = this
            gravity = Gravity.CENTER_VERTICAL
            addView(pageView.apply {
                textSize = 12f
                gravity = Gravity.CENTER_VERTICAL
            }, LayoutParams(0, dp(36), 1f))
            addView(progressView.apply {
                textSize = 12f
                gravity = Gravity.CENTER_VERTICAL
            }, LayoutParams(LayoutParams.WRAP_CONTENT, dp(36)))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setPage(page: TextPage, highlight: IntRange?, search: IntRange?) {
        currentPage = page
        titleView.text = page.title
        clockView.text = currentClockText()
        contentView.setPage(page, highlight, search)
        pageView.text = "${page.globalIndex + 1} / ${page.totalPages}"
        progressView.text = page.readProgress
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

    fun selectionHandleBounds(): Pair<RectF, RectF>? {
        val bounds = contentView.selectionHandleBounds() ?: return null
        bounds.first.offset(contentView.left.toFloat(), contentView.top.toFloat())
        bounds.second.offset(contentView.left.toFloat(), contentView.top.toFloat())
        return bounds
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
        titleView.setTextColor(tip)
        clockView.setTextColor(tip)
        pageView.setTextColor(tip)
        progressView.setTextColor(tip)
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
        contentView.setTextSizePx(
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)
        )
    }

    fun setTextWeight(weight: M9TextWeight) {
        contentView.setTextWeight(weight)
    }

    fun setTextUnderline(enabled: Boolean) {
        contentView.setTextUnderline(enabled)
    }

    fun setReaderTypeface(typeface: Typeface?) {
        contentView.setReaderTypeface(typeface)
    }

    fun setReaderPadding(left: Int, top: Int, right: Int, bottom: Int) {
        setPadding(left, top, right, bottom)
    }

    fun setShowHeaderFooter(show: Boolean) {
        showHeaderFooter = show
        headerView.visibility = if (show) View.VISIBLE else View.GONE
        footerView.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun currentClockText(): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
