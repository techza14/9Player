package moe.tekuza.m9player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsPanel(
    selectedAppLanguageLabel: String,
    versionName: String,
    onAudiobookClick: () -> Unit,
    onControlModeClick: () -> Unit,
    onControllerClick: () -> Unit,
    onAnkiClick: () -> Unit,
    onAdvancedOverlayClick: () -> Unit,
    onAdvancedOtherClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onGuideClick: () -> Unit,
    onExportDiagnosticsClick: () -> Unit,
    onVersionClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsSection(
            title = stringResource(R.string.settings_section_reading)
        ) {
            SettingsListItem(
                icon = Icons.Outlined.AutoStories,
                title = stringResource(R.string.settings_audiobook_title),
                onClick = onAudiobookClick
            )
            SettingsListItem(
                icon = Icons.Outlined.MenuBook,
                title = stringResource(R.string.settings_control_mode_title),
                onClick = onControlModeClick
            )
            SettingsListItem(
                icon = Icons.Outlined.SportsEsports,
                title = stringResource(R.string.settings_controller_title),
                onClick = onControllerClick,
                showDivider = false
            )
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_learning)
        ) {
            SettingsListItem(
                icon = Icons.Outlined.LibraryAdd,
                title = stringResource(R.string.settings_anki_title),
                onClick = onAnkiClick,
                showDivider = false
            )
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_system)
        ) {
            SettingsListItem(
                icon = Icons.Outlined.Language,
                title = stringResource(R.string.settings_language_title),
                value = selectedAppLanguageLabel,
                onClick = onLanguageClick,
                showDivider = false
            )
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_advanced)
        ) {
            SettingsListItem(
                icon = Icons.Outlined.AutoStories,
                title = stringResource(R.string.audiobook_overlay_title),
                onClick = onAdvancedOverlayClick
            )
            SettingsListItem(
                icon = Icons.Outlined.MoreHoriz,
                title = stringResource(R.string.settings_other_title),
                onClick = onAdvancedOtherClick,
                showDivider = false
            )
        }

        SettingsSection(
            title = stringResource(R.string.settings_section_about)
        ) {
            SettingsListItem(
                icon = Icons.Outlined.Link,
                title = stringResource(R.string.settings_guide_title),
                onClick = onGuideClick
            )
            SettingsListItem(
                icon = Icons.Outlined.Description,
                title = stringResource(R.string.settings_export_diagnostics_title),
                subtitle = stringResource(R.string.settings_export_diagnostics_subtitle),
                onClick = onExportDiagnosticsClick
            )
            SettingsListItem(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.settings_version_title),
                value = versionName,
                onClick = onVersionClick,
                showDivider = false
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsListItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    value: String? = null,
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
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (!value.isNullOrBlank()) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
