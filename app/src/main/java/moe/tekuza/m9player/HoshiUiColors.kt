package moe.tekuza.m9player

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.util.Locale

internal val HoshiPanelBackground = Color(0xFFEDF3FA)
internal val HoshiBottomNavigationBackground = Color(0xFFEEF3F8)
internal val HoshiCardBackground = Color(0xFFFFFFFF)
internal val HoshiSoftCardBackground = Color(0xFFF8FBFF)
internal val HoshiDarkBackground = Color(0xFF10151D)
internal val HoshiDarkPanelBackground = Color(0xFF182231)
internal val HoshiDarkBottomNavigationBackground = Color(0xFF131B26)
internal val HoshiDarkCardBackground = Color(0xFF202C3A)
internal val HoshiDarkSoftCardBackground = Color(0xFF17202B)
internal val HoshiDarkPopupBorder = Color(0xFF3D4B5F)

@Composable
internal fun hoshiPanelBackgroundColor(): Color =
    if (isSystemInDarkTheme()) HoshiDarkPanelBackground else HoshiPanelBackground

@Composable
internal fun hoshiBottomNavigationBackgroundColor(): Color =
    if (isSystemInDarkTheme()) HoshiDarkBottomNavigationBackground else HoshiBottomNavigationBackground

@Composable
internal fun hoshiCardBackgroundColor(): Color =
    if (isSystemInDarkTheme()) HoshiDarkCardBackground else HoshiCardBackground

@Composable
internal fun hoshiSoftCardBackgroundColor(): Color =
    if (isSystemInDarkTheme()) HoshiDarkSoftCardBackground else HoshiSoftCardBackground

internal fun Color.toCssRgbHex(): String {
    val rgb = toArgb() and 0x00FFFFFF
    return String.format(Locale.US, "#%06X", rgb)
}
