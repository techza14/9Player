package moe.tekuza.m9player.legado.reader

import android.graphics.Typeface

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
    var textBold: Boolean = false,
    var typeface: Typeface? = null,
    var paddingLeftPx: Int = 22,
    var paddingTopPx: Int = 34,
    var paddingRightPx: Int = 22,
    var paddingBottomPx: Int = 22,
    var showHeaderFooter: Boolean = true,
    var layoutMode: M9LayoutMode = M9LayoutMode.HORIZONTAL,
    var pageAnim: M9PageAnim = M9PageAnim.NONE
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
