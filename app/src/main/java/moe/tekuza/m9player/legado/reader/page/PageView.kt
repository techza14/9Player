package moe.tekuza.m9player.legado.reader.page

import android.content.Context
import android.view.Gravity
import android.view.View
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView
import android.util.TypedValue
import moe.tekuza.m9player.EbookImageRef
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
        titleView.text = page.title
        clockView.text = currentClockText()
        contentView.setPage(page, highlight, search)
        pageView.text = "${page.globalIndex + 1} / ${page.totalPages}"
        progressView.text = page.readProgress
    }

    fun findImageAt(x: Float, y: Float): EbookImageRef? {
        return contentView.findImageAt(
            x = x - contentView.left,
            y = y - contentView.top
        )
    }

    fun setReaderColors(bg: Int, text: Int, tip: Int) {
        setBackgroundColor(bg)
        contentView.setTextColor(text)
        contentView.setHighlightTextColor(text)
        titleView.setTextColor(tip)
        clockView.setTextColor(tip)
        pageView.setTextColor(tip)
        progressView.setTextColor(tip)
    }

    fun setTextSizeSp(sizeSp: Float) {
        contentView.setTextSizePx(
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)
        )
    }

    fun setFakeBoldText(enabled: Boolean) {
        contentView.setFakeBoldText(enabled)
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
