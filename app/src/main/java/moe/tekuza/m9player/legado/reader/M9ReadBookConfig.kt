package moe.tekuza.m9player.legado.reader

import android.os.Build
import android.graphics.Typeface
import android.text.TextPaint

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
    typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Typeface.create(baseTypeface ?: Typeface.DEFAULT, weight.androidWeight, false)
    } else {
        baseTypeface
    }
    isFakeBoldText = Build.VERSION.SDK_INT < Build.VERSION_CODES.P && weight == M9TextWeight.BOLD
}
