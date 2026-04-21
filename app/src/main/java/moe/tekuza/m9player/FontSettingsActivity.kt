package moe.tekuza.m9player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
            val ok = persistReadPermission(context, uri)
            if (ok) {
                saveSubtitleCustomFontUri(context, uri)
                SubtitleFontUiRefreshTicker.bump()
                settings = loadAudiobookSettingsConfig(context)
                Toast.makeText(context, context.getString(R.string.settings_font_imported), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, context.getString(R.string.settings_font_import_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }
    val fontLabel = remember(settings.subtitleCustomFontUri) {
        settings.subtitleCustomFontUri?.let { queryDisplayName(context, it) } ?: context.getString(R.string.settings_font_none)
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
            SettingsLikeItem(
                icon = Icons.Outlined.FontDownload,
                title = stringResource(R.string.settings_font_import),
                subtitle = fontLabel,
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
            SettingsLikeItem(
                icon = Icons.Outlined.Delete,
                title = stringResource(R.string.common_clear),
                titleColor = MaterialTheme.colorScheme.error,
                onClick = {
                    saveSubtitleCustomFontUri(context, null)
                    SubtitleFontUiRefreshTicker.bump()
                    settings = loadAudiobookSettingsConfig(context)
                },
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

@Composable
private fun SettingsLikeItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    showDivider: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp),
                color = Color.Transparent
            )
        }
    }
}

private fun persistReadPermission(context: Context, uri: Uri): Boolean {
    return runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        true
    }.getOrDefault(false)
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty().ifBlank { context.getString(R.string.settings_font_none) }
}
