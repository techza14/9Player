package moe.tekuza.m9player.legado.reader.page

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import moe.tekuza.m9player.EbookImageRef
import moe.tekuza.m9player.legado.reader.M9PageAnim
import moe.tekuza.m9player.legado.reader.entities.TextPage
import moe.tekuza.m9player.legado.reader.page.delegate.CoverPageDelegate
import moe.tekuza.m9player.legado.reader.page.delegate.NoAnimPageDelegate
import moe.tekuza.m9player.legado.reader.page.delegate.PageDelegate
import moe.tekuza.m9player.legado.reader.page.delegate.ScrollPageDelegate
import moe.tekuza.m9player.legado.reader.page.delegate.SimulationPageDelegate
import moe.tekuza.m9player.legado.reader.page.delegate.SlidePageDelegate

internal class ReadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val pageView = PageView(context)
    private val assistOverlay = TextView(context)
    var onPrevPage: (() -> Unit)? = null
    var onNextPage: (() -> Unit)? = null
    var onMenu: (() -> Unit)? = null
    var onImageClick: ((EbookImageRef) -> Unit)? = null
    private var pageDelegate: PageDelegate = NoAnimPageDelegate()
    private var clickMode: ClickMode = ClickMode.LEFT_CENTER_RIGHT
    private var assistToken: ContentTextView.AssistToken? = null
    private var overlayTextColor: Int = 0xFFFFFFFF.toInt()
    private var overlayBgColor: Int = 0xCC1E1E1E.toInt()

    val contentWidth: Int get() = pageView.contentView.width
    val contentHeight: Int get() = pageView.contentView.height
    val textSizePx: Float get() = pageView.contentView.textSizePx

    init {
        addView(pageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(assistOverlay.apply {
            visibility = GONE
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { hideAssistOverlay() }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        updateAssistOverlayStyle()
    }

    fun setPage(page: TextPage, highlight: IntRange? = null, search: IntRange? = null, forward: Boolean = true) {
        hideAssistOverlay()
        pageView.setPage(page, highlight, search)
        runPageAnimation(forward)
    }

    fun setReaderColors(bg: Int, text: Int, tip: Int) {
        pageView.setReaderColors(bg, text, tip)
        overlayTextColor = text
        overlayBgColor = if (isDarkColor(bg)) 0xCC2D2D2D.toInt() else 0xF4FFF8EC.toInt()
        updateAssistOverlayStyle()
    }

    fun setTextSizeSp(sizeSp: Float) {
        pageView.setTextSizeSp(sizeSp)
        assistOverlay.textSize = sizeSp
    }

    fun setFakeBoldText(enabled: Boolean) {
        pageView.setFakeBoldText(enabled)
    }

    fun setReaderTypeface(typeface: Typeface?) {
        pageView.setReaderTypeface(typeface)
        assistOverlay.typeface = typeface ?: Typeface.DEFAULT
    }

    fun setReaderPadding(left: Int, top: Int, right: Int, bottom: Int) {
        pageView.setReaderPadding(left, top, right, bottom)
        requestLayout()
    }

    fun setShowHeaderFooter(show: Boolean) {
        pageView.setShowHeaderFooter(show)
        requestLayout()
    }

    fun setPageAnim(anim: M9PageAnim) {
        pageDelegate = when (anim) {
            M9PageAnim.COVER -> CoverPageDelegate()
            M9PageAnim.SLIDE -> SlidePageDelegate()
            M9PageAnim.SIMULATION -> SimulationPageDelegate()
            M9PageAnim.SCROLL -> ScrollPageDelegate()
            M9PageAnim.NONE -> NoAnimPageDelegate()
        }
    }

    fun setClickMode(mode: ClickMode) {
        clickMode = mode
    }

    private fun runPageAnimation(forward: Boolean) {
        pageDelegate.onPageChanged(pageView, forward)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            pageView.findImageAt(event.x, event.y)?.let { image ->
                onImageClick?.invoke(image)
                if (onImageClick != null) return true
            }
            pageView.findAssistTokenAt(event.x, event.y)?.let { token ->
                toggleAssistOverlay(token)
                return true
            }
            if (assistOverlay.visibility == VISIBLE) {
                hideAssistOverlay()
                return true
            }
            when (clickMode) {
                ClickMode.LEFT_CENTER_RIGHT -> when {
                    event.x < width * 0.32f -> onPrevPage?.invoke()
                    event.x > width * 0.68f -> onNextPage?.invoke()
                    else -> onMenu?.invoke()
                }
                ClickMode.TOP_CENTER_BOTTOM -> when {
                    event.y < height * 0.32f -> onPrevPage?.invoke()
                    event.y > height * 0.68f -> onNextPage?.invoke()
                    else -> onMenu?.invoke()
                }
                ClickMode.FULL_NEXT -> onNextPage?.invoke()
            }
        }
        return true
    }

    enum class ClickMode {
        LEFT_CENTER_RIGHT,
        TOP_CENTER_BOTTOM,
        FULL_NEXT
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
        val left = when {
            anchor.left - overlayWidth - margin >= margin -> anchor.left - overlayWidth - margin
            anchor.right + overlayWidth + margin <= width - margin -> anchor.right + margin
            else -> (anchor.centerX() - overlayWidth / 2f).coerceIn(margin, width - overlayWidth - margin)
        }
        val top = (anchor.centerY() - overlayHeight / 2f).coerceIn(margin, height - overlayHeight - margin)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
