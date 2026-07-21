package moe.tekuza.m9player

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.tekuza.m9player.ui.theme.TsetTheme

class FontSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TsetTheme {
                FontSettingsScreen(onBack = { finish() })
            }
        }
    }
}
@Composable
private fun FontSettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var settings by remember { mutableStateOf(loadAudiobookSettingsConfig(context)) }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val displayName = queryDisplayName(context.contentResolver, uri)
            val privateFontUri = importSubtitleCustomFontToPrivateStorage(context, uri)
            if (privateFontUri != null) {
                saveSubtitleCustomFont(context, privateFontUri, displayName)
                SubtitleFontUiRefreshTicker.bump()
                settings = loadAudiobookSettingsConfig(context)
                Toast.makeText(context, context.getString(R.string.settings_font_imported), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.settings_font_import_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }
    val fontLabel = remember(settings.subtitleCustomFontUri, settings.subtitleCustomFontName) {
        settings.subtitleCustomFontName
            ?: settings.subtitleCustomFontUri?.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast(':')
                ?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.settings_font_none)
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_font_title),
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            SettingsListItem(
                icon = Icons.Outlined.FontDownload,
                title = stringResource(R.string.settings_font_import),
                subtitle = fontLabel,
                iconSize = 24.dp,
                onClick = { fontPicker.launch(arrayOf("font/*", "application/octet-stream")) }
            )
            SettingsLikeInfo(text = stringResource(R.string.settings_font_default_scope))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_font_apply_subtitles),
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = settings.subtitleGlobalFontEnabled,
                    onCheckedChange = { enabled ->
                        saveSubtitleGlobalFontEnabled(context, enabled)
                        SubtitleFontUiRefreshTicker.bump()
                        settings = loadAudiobookSettingsConfig(context)
                    }
                )
            }
            SettingsListItem(
                icon = Icons.Outlined.Delete,
                title = stringResource(R.string.common_clear),
                titleColor = MaterialTheme.colorScheme.error,
                iconSize = 24.dp,
                onClick = {
                    deleteImportedSubtitleCustomFont(context)
                    saveSubtitleCustomFontUri(context, null)
                    SubtitleFontUiRefreshTicker.bump()
                    settings = loadAudiobookSettingsConfig(context)
                },
                showDivider = false
            )
            SettingsListItem(
                icon = Icons.Outlined.AutoStories,
                title = stringResource(R.string.settings_audiobook_ui_title),
                subtitle = stringResource(R.string.settings_audiobook_ui_subtitle),
                iconSize = 24.dp,
                onClick = { context.startActivity(Intent(context, AudiobookUiSettingsActivity::class.java)) },
                showDivider = false
            )
        }
    }
}

@Composable
private fun SettingsLikeInfo(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
