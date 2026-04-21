package moe.tekuza.m9player.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import moe.tekuza.m9player.loadAudiobookSettingsConfig
import moe.tekuza.m9player.resolveSubtitleTypeface
import moe.tekuza.m9player.SubtitleFontUiRefreshTicker

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun TsetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    SubtitleFontUiRefreshTicker.version
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val settings = loadAudiobookSettingsConfig(context)
    val globalFontFamily = if (settings.subtitleGlobalFontEnabled) {
        resolveSubtitleTypeface(context, settings.subtitleCustomFontUri)?.let { FontFamily(it) }
    } else {
        null
    }
    val typography = globalFontFamily?.let { family ->
        Typography.copy(
            displayLarge = Typography.displayLarge.copy(fontFamily = family),
            displayMedium = Typography.displayMedium.copy(fontFamily = family),
            displaySmall = Typography.displaySmall.copy(fontFamily = family),
            headlineLarge = Typography.headlineLarge.copy(fontFamily = family),
            headlineMedium = Typography.headlineMedium.copy(fontFamily = family),
            headlineSmall = Typography.headlineSmall.copy(fontFamily = family),
            titleLarge = Typography.titleLarge.copy(fontFamily = family),
            titleMedium = Typography.titleMedium.copy(fontFamily = family),
            titleSmall = Typography.titleSmall.copy(fontFamily = family),
            bodyLarge = Typography.bodyLarge.copy(fontFamily = family),
            bodyMedium = Typography.bodyMedium.copy(fontFamily = family),
            bodySmall = Typography.bodySmall.copy(fontFamily = family),
            labelLarge = Typography.labelLarge.copy(fontFamily = family),
            labelMedium = Typography.labelMedium.copy(fontFamily = family),
            labelSmall = Typography.labelSmall.copy(fontFamily = family)
        )
    } ?: Typography

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}
