package moe.tekuza.m9player.legado.reader.page

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
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
    var onPrevPage: (() -> Unit)? = null
    var onNextPage: (() -> Unit)? = null
    var onMenu: (() -> Unit)? = null
    var onImageClick: ((EbookImageRef) -> Unit)? = null
    private var pageDelegate: PageDelegate = NoAnimPageDelegate()
    private var clickMode: ClickMode = ClickMode.LEFT_CENTER_RIGHT

    val contentWidth: Int get() = pageView.contentView.width
    val contentHeight: Int get() = pageView.contentView.height
    val textSizePx: Float get() = pageView.contentView.textSizePx

    init {
        addView(pageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setPage(page: TextPage, highlight: IntRange? = null, search: IntRange? = null, forward: Boolean = true) {
        pageView.setPage(page, highlight, search)
        runPageAnimation(forward)
    }

    fun setReaderColors(bg: Int, text: Int, tip: Int) {
        pageView.setReaderColors(bg, text, tip)
    }

    fun setTextSizeSp(sizeSp: Float) {
        pageView.setTextSizeSp(sizeSp)
    }

    fun setFakeBoldText(enabled: Boolean) {
        pageView.setFakeBoldText(enabled)
    }

    fun setReaderTypeface(typeface: Typeface?) {
        pageView.setReaderTypeface(typeface)
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
}
