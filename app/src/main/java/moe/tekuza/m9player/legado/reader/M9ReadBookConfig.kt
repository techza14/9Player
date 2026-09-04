package moe.tekuza.m9player.legado.reader

import android.graphics.Typeface
import android.text.TextPaint

/** 卷/章节标题字号 = 正文字号 × 该比例（布局与绘制共用） */
internal const val READER_TITLE_SCALE = 1.2f

internal data class M9ReadBookConfig(
    var textSizePx: Float,
    var lineSpacingPx: Float,
    var paragraphSpacingPx: Float,
    var textColor: Int,
    var tipColor: Int,
    var backgroundColor: Int,
    var useZhLayout: Boolean = true,
    var textBottomJustify: Boolean = true,
    var textFullJustify: Boolean = true,
    var paragraphIndent: String = "",
    var letterSpacingPx: Float = 0f,
    var textWeight: M9TextWeight = M9TextWeight.NORMAL,
    var typeface: Typeface? = null,
    var paddingLeftPx: Int = 22,
    var paddingTopPx: Int = 34,
    var paddingRightPx: Int = 22,
    var paddingBottomPx: Int = 22,
    var showHeaderFooter: Boolean = true,
    var layoutMode: M9LayoutMode = M9LayoutMode.HORIZONTAL,
    var pageAnim: M9PageAnim = M9PageAnim.NONE,
    var showRubyText: Boolean = true
)

internal enum class M9LayoutMode {
    HORIZONTAL,
    VERTICAL
}

internal enum class M9PageAnim {
    COVER,
    SLIDE,
    SIMULATION,
    SCROLL,
    NONE
}

internal enum class M9TextWeight(val androidWeight: Int) {
    NORMAL(400),
    BOLD(700),
    LIGHT(300);

    companion object {
        fun fromIndex(index: Int): M9TextWeight = when (index) {
            1 -> BOLD
            2 -> LIGHT
            else -> NORMAL
        }
    }
}

internal fun TextPaint.applyM9TextWeight(weight: M9TextWeight, baseTypeface: Typeface?) {
    typeface = Typeface.create(baseTypeface ?: Typeface.DEFAULT, weight.androidWeight, false)
    isFakeBoldText = false
}
