package moe.tekuza.m9player.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
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
        resolveSubtitleTypeface(context, settings.subtitleCustomFontUri)?.let { typeface ->
            runCatching { FontFamily(typeface) }.getOrNull()
        }
    } else {
        null
    }
    val defaultTypography = Typography()
    val typography = globalFontFamily?.let { family ->
        defaultTypography.copy(
            displayLarge = defaultTypography.displayLarge.copy(fontFamily = family),
            displayMedium = defaultTypography.displayMedium.copy(fontFamily = family),
            displaySmall = defaultTypography.displaySmall.copy(fontFamily = family),
            headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = family),
            headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = family),
            headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = family),
            titleLarge = defaultTypography.titleLarge.copy(fontFamily = family),
            titleMedium = defaultTypography.titleMedium.copy(fontFamily = family),
            titleSmall = defaultTypography.titleSmall.copy(fontFamily = family),
            bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = family),
            bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = family),
            bodySmall = defaultTypography.bodySmall.copy(fontFamily = family),
            labelLarge = defaultTypography.labelLarge.copy(fontFamily = family),
            labelMedium = defaultTypography.labelMedium.copy(fontFamily = family),
            labelSmall = defaultTypography.labelSmall.copy(fontFamily = family)
        )
    } ?: defaultTypography

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        content = content
    )
}
