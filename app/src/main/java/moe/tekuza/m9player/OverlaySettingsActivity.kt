package moe.tekuza.m9player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.tekuza.m9player.ui.theme.TsetTheme

class OverlaySettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TsetTheme {
                OverlaySettingsScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun OverlaySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(loadAudiobookSettingsConfig(context)) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var overlaySizeDraft by remember { mutableStateOf(config.floatingOverlaySizeDp.toFloat()) }
    val overlayGranted = remember(
        config.floatingOverlayEnabled,
        config.floatingOverlaySubtitleEnabled,
        statusText
    ) {
        Settings.canDrawOverlays(context)
    }

    val refreshConfig = {
        config = loadAudiobookSettingsConfig(context)
    }

    val previewOverlayLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val modeName = result.data?.getStringExtra(FloatingOverlayPreviewActivity.EXTRA_RESULT_MODE)
            val mode = modeName
                ?.let { runCatching { FloatingOverlayMode.valueOf(it) }.getOrNull() }
                ?: return@rememberLauncherForActivityResult
            saveAudiobookFloatingOverlayMode(context, mode)
            refreshConfig()
            refreshAudiobookFloatingOverlayService(context)
            statusText = context.getString(
                R.string.audiobook_overlay_mode_changed,
                overlayModeLabel(context, mode)
            )
        }

    SettingsScaffold(
        title = stringResource(R.string.audiobook_overlay_title),
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val currentOverlayMode = config.floatingOverlayMode
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.audiobook_overlay_enable),
                            modifier = Modifier.weight(1f).padding(end = 12.dp)
                        )
                        Switch(
                            checked = currentOverlayMode != FloatingOverlayMode.OFF,
                            onCheckedChange = { checked ->
                                val nextMode = if (checked) {
                                    currentOverlayMode.takeIf { it != FloatingOverlayMode.OFF }
                                        ?: FloatingOverlayMode.SUBTITLE
                                } else {
                                    FloatingOverlayMode.OFF
                                }
                                saveAudiobookFloatingOverlayMode(context, nextMode)
                                refreshConfig()
                                refreshAudiobookFloatingOverlayService(context)
                                statusText = if (checked) {
                                    context.getString(
                                        R.string.audiobook_overlay_mode_changed,
                                        overlayModeLabel(context, nextMode)
                                    )
                                } else {
                                    context.getString(R.string.audiobook_overlay_disabled)
                                }
                            }
                        )
                    }
                    Text(
                        stringResource(
                            R.string.audiobook_overlay_mode_value,
                            overlayModeLabel(context, currentOverlayMode)
                        )
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                previewOverlayLauncher.launch(
                                    Intent(context, FloatingOverlayPreviewActivity::class.java).apply {
                                        putExtra(
                                            FloatingOverlayPreviewActivity.EXTRA_INITIAL_MODE,
                                            currentOverlayMode.takeIf { it != FloatingOverlayMode.OFF }?.name
                                                ?: FloatingOverlayMode.SUBTITLE.name
                                        )
                                    }
                                )
                            }
                        ) {
                            Text(stringResource(R.string.audiobook_overlay_test_button))
                        }
                    }
                    if (currentOverlayMode != FloatingOverlayMode.OFF) {
                        Text(
                            if (overlayGranted) {
                                stringResource(R.string.audiobook_overlay_permission_granted)
                            } else {
                                stringResource(R.string.audiobook_overlay_permission_denied)
                            }
                        )
                        if (!overlayGranted) {
                            Button(
                                onClick = {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                }
                            ) {
                                Text(stringResource(R.string.audiobook_overlay_permission_button))
                            }
                        }
                    }
                    if (currentOverlayMode.showsBubble) {
                        Text(
                            stringResource(
                                R.string.audiobook_overlay_size_value,
                                overlaySizeDraft.toInt()
                            )
                        )
                        Slider(
                            value = overlaySizeDraft,
                            onValueChange = { value ->
                                overlaySizeDraft = value.coerceIn(
                                    MIN_FLOATING_OVERLAY_SIZE_DP.toFloat(),
                                    MAX_FLOATING_OVERLAY_SIZE_DP.toFloat()
                                )
                            },
                            onValueChangeFinished = {
                                val size = overlaySizeDraft.toInt().coerceIn(
                                    MIN_FLOATING_OVERLAY_SIZE_DP,
                                    MAX_FLOATING_OVERLAY_SIZE_DP
                                )
                                if (size != config.floatingOverlaySizeDp) {
                                    saveAudiobookFloatingOverlaySizeDp(context, size)
                                    refreshConfig()
                                    refreshAudiobookFloatingOverlayService(context)
                                }
                            },
                            valueRange = MIN_FLOATING_OVERLAY_SIZE_DP.toFloat()..MAX_FLOATING_OVERLAY_SIZE_DP.toFloat()
                        )
                        OutlinedButton(
                            onClick = {
                                saveAudiobookFloatingOverlaySizeDp(context, DEFAULT_FLOATING_OVERLAY_SIZE_DP)
                                refreshConfig()
                                refreshAudiobookFloatingOverlayService(context)
                                statusText = context.getString(R.string.audiobook_overlay_size_reset)
                            }
                        ) {
                            Text(stringResource(R.string.audiobook_overlay_size_reset_button))
                        }
                        Text(stringResource(R.string.audiobook_overlay_help))
                    }
                    if (currentOverlayMode.showsSubtitle) {
                        Text(stringResource(R.string.audiobook_overlay_subtitle_help))
                        OverlaySubtitleWritingModeDropdown(
                            selected = config.floatingOverlaySubtitleWritingMode,
                            onSelected = { mode ->
                                if (mode != config.floatingOverlaySubtitleWritingMode) {
                                    saveAudiobookFloatingOverlaySubtitleWritingMode(context, mode)
                                    refreshConfig()
                                    refreshAudiobookFloatingOverlayService(context)
                                }
                            }
                        )
                    }
                }
            }

            statusText?.let {
                Text(it)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun OverlaySubtitleWritingModeDropdown(
    selected: FloatingSubtitleWritingMode,
    onSelected: (FloatingSubtitleWritingMode) -> Unit
) {
    val options = FloatingSubtitleWritingMode.entries
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = floatingSubtitleWritingModeLabel(context, selected),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
            label = { Text(stringResource(R.string.audiobook_overlay_subtitle_writing_mode)) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(floatingSubtitleWritingModeLabel(context, option)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    }
                )
            }
        }
    }
}

private fun floatingSubtitleWritingModeLabel(
    context: android.content.Context,
    mode: FloatingSubtitleWritingMode
): String {
    return when (mode) {
        FloatingSubtitleWritingMode.HORIZONTAL ->
            context.getString(R.string.audiobook_overlay_subtitle_writing_mode_horizontal)
        FloatingSubtitleWritingMode.VERTICAL_RTL ->
            context.getString(R.string.audiobook_overlay_subtitle_writing_mode_vertical_rtl)
    }
}
