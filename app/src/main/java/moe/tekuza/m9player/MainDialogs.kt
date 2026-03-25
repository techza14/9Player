package moe.tekuza.m9player

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun AppLanguageDialog(
    selectedAppLanguage: AppLanguageOption,
    onDismiss: () -> Unit,
    onSelectLanguage: (AppLanguageOption) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppLanguageOption.entries.forEach { option ->
                    OutlinedButton(
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                        onClick = { onSelectLanguage(option) }
                    ) {
                        val prefix = if (selectedAppLanguage == option) "✓ " else ""
                        Text(prefix + option.displayLabel(androidx.compose.ui.platform.LocalContext.current))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        }
    )
}

@Composable
internal fun ImportGuideDialog(
    onKeepOriginal: () -> Unit,
    onAutoMove: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.import_mode_title)) },
        text = { Text(stringResource(R.string.import_mode_first_launch_message)) },
        dismissButton = {
            OutlinedButton(onClick = onKeepOriginal) {
                Text(stringResource(R.string.import_mode_keep_original))
            }
        },
        confirmButton = {
            Button(onClick = onAutoMove) {
                Text(stringResource(R.string.import_mode_auto_move))
            }
        }
    )
}

@Composable
internal fun ClearCollectionsDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.collections_clear_title)) },
        text = { Text(stringResource(R.string.collections_clear_message)) },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.collections_clear_confirm))
            }
        }
    )
}

@Composable
internal fun DeleteBooksConfirmDialog(
    deleteBooksDontAskAgain: Boolean,
    onDontAskAgainChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_books_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.delete_books_message))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Checkbox(
                        checked = deleteBooksDontAskAgain,
                        onCheckedChange = onDontAskAgainChange
                    )
                    Text(stringResource(R.string.delete_books_skip_next_time))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.common_delete))
            }
        }
    )
}

@Composable
internal fun AddBookDialog(
    folderName: String?,
    folderUri: Uri?,
    audioName: String?,
    audioUri: Uri?,
    srtName: String?,
    autoMoveToAudiobookFolder: Boolean,
    srtLoading: Boolean,
    onPickFolder: () -> Unit,
    onClearFolderSelection: () -> Unit,
    onPickAudio: () -> Unit,
    onPickSrt: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_book_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    context.getString(
                        R.string.add_book_folder_label,
                        folderName ?: context.getString(R.string.common_not_selected)
                    )
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPickFolder) {
                        Text(stringResource(R.string.add_book_pick_folder))
                    }
                    OutlinedButton(
                        onClick = onClearFolderSelection,
                        enabled = folderUri != null
                    ) {
                        Text(stringResource(R.string.common_clear))
                    }
                }

                Text(
                    context.getString(
                        R.string.add_book_audio_label,
                        audioName ?: context.getString(R.string.common_not_selected)
                    )
                )
                OutlinedButton(
                    onClick = onPickAudio,
                    enabled = folderUri != null || !autoMoveToAudiobookFolder
                ) {
                    Text(stringResource(R.string.add_book_pick_audio))
                }

                Text(
                    context.getString(
                        R.string.add_book_srt_label,
                        srtName ?: context.getString(R.string.common_not_selected)
                    )
                )
                OutlinedButton(
                    onClick = onPickSrt,
                    enabled = folderUri != null || !autoMoveToAudiobookFolder
                ) {
                    Text(stringResource(R.string.add_book_pick_srt))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = (folderUri != null || !autoMoveToAudiobookFolder) &&
                    audioUri != null &&
                    !srtLoading
            ) {
                Text(stringResource(R.string.common_confirm))
            }
        }
    )
}
