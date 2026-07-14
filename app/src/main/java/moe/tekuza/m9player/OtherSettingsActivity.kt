package moe.tekuza.m9player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayLesson
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import moe.tekuza.m9player.ui.theme.TsetTheme

class OtherSettingsActivity : ComponentActivity() {
    private val wearablePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && loadWearableFeatureEnabled(this) && BookReaderPlaybackSession.currentAudioUri() != null) {
            startWearableBridgeService(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TsetTheme {
                OtherSettingsScreen(
                    onBack = { finish() },
                    onWearableEnabled = ::startWearableBridgeWhenPermitted
                )
            }
        }
    }

    private fun startWearableBridgeWhenPermitted() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            wearablePermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else if (BookReaderPlaybackSession.currentAudioUri() != null) {
            startWearableBridgeService(this)
        }
    }
}

@Composable
private fun OtherSettingsScreen(
    onBack: () -> Unit,
    onWearableEnabled: () -> Unit
) {
    val context = LocalContext.current
    var ebookEnabled by remember { mutableStateOf(loadEbookFeatureEnabled(context)) }
    var ebookDefaultToReader by remember { mutableStateOf(loadEbookDefaultToReader(context)) }
    var ebookOnlyImportEnabled by remember { mutableStateOf(loadEbookOnlyImportEnabled(context)) }
    var wearableEnabled by remember { mutableStateOf(loadWearableFeatureEnabled(context)) }
    var showEbookSettings by remember { mutableStateOf(false) }
    SettingsScaffold(
        title = if (showEbookSettings) "" else stringResource(R.string.settings_other_title),
        onBack = {
            if (showEbookSettings) {
                showEbookSettings = false
            } else {
                onBack()
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (showEbookSettings) {
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_ebook_enabled_title),
                    checked = ebookEnabled,
                    onCheckedChange = { enabled ->
                        ebookEnabled = enabled
                        saveEbookFeatureEnabled(context, enabled)
                        if (!enabled) {
                            ebookDefaultToReader = false
                            ebookOnlyImportEnabled = false
                            saveEbookDefaultToReader(context, false)
                            saveEbookOnlyImportEnabled(context, false)
                            saveEbookImageSpoilerEnabled(context, false)
                        }
                    },
                    showDivider = true
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_ebook_default_reader_title),
                    checked = ebookDefaultToReader,
                    enabled = ebookEnabled,
                    onCheckedChange = { enabled ->
                        ebookDefaultToReader = enabled
                        saveEbookDefaultToReader(context, enabled)
                    },
                    showDivider = true
                )
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_ebook_only_import_title),
                    checked = ebookOnlyImportEnabled,
                    enabled = ebookEnabled,
                    onCheckedChange = { enabled ->
                        ebookOnlyImportEnabled = enabled
                        saveEbookOnlyImportEnabled(context, enabled)
                    },
                    showDivider = false
                )
            } else {
                SettingsSwitchItem(
                    title = stringResource(R.string.settings_wearable_enabled_title),
                    checked = wearableEnabled,
                    iconPainter = painterResource(R.drawable.ic_fitness_tracker_24dp),
                    onCheckedChange = { enabled ->
                        wearableEnabled = enabled
                        saveWearableFeatureEnabled(context, enabled)
                        if (enabled) onWearableEnabled()
                    },
                    showDivider = true
                )
                SettingsLikeItem(
                    icon = Icons.Outlined.PlayLesson,
                    title = stringResource(R.string.settings_ebook_title),
                    onClick = { showEbookSettings = true },
                    showDivider = false
                )
            }
        }
    }
}

@Composable
private fun SettingsLikeItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
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
                if (title.isNotBlank()) {
                    Text(text = title, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp),
                color = androidx.compose.ui.graphics.Color.Transparent
            )
        }
    }
}

@Composable
private fun SettingsSwitchItem(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    enabled: Boolean = true,
    showDivider: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (iconPainter != null) {
                Icon(
                    painter = iconPainter,
                    contentDescription = null,
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    }
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    }
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (title.isNotBlank()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp),
                color = androidx.compose.ui.graphics.Color.Transparent
            )
        }
    }
}
