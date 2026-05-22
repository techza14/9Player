package moe.tekuza.m9player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun DictionaryManagementCard(
    context: android.content.Context = LocalContext.current,
    dictionaryCount: Int,
    showHeader: Boolean = true,
    containerColor: Color = HoshiPanelBackground,
    itemContainerColor: Color = HoshiSoftCardBackground,
    dictionaryLoading: Boolean,
    dictionaryProgressText: String?,
    dictionaryProgressValue: Float?,
    dictionaryError: String?,
    showDictionaryManager: Boolean,
    showDictionaryDeleteActions: Boolean,
    dictionaryRefs: List<PersistedDictionaryRef>,
    dictionaryOrderIds: List<String>,
    mdxMountState: MdxMountState,
    onImportClick: () -> Unit,
    onShowDictionaryManagerToggle: () -> Unit,
    onShowDictionaryDeleteActionsToggle: () -> Unit,
    onOpenMdxClick: (() -> Unit)?,
    onMoveCombinedDictionary: (fromIndex: Int, toIndex: Int) -> Unit,
    onRemoveImportedDictionary: (index: Int) -> Unit,
    onRemoveMountedDictionary: (cacheKey: String) -> Unit,
    onSetImportedDictionaryEnabled: (dictionaryId: String, enabled: Boolean) -> Unit,
    onSetMountedDictionaryEnabled: (cacheKey: String, enabled: Boolean) -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = containerColor)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
            if (showHeader) {
                Text(stringResource(R.string.dictionary_title))
                Text(stringResource(R.string.dictionary_summary, dictionaryCount))
            }

            if (dictionaryLoading) {
                val progressPercent = dictionaryProgressValue?.let { (it.coerceIn(0f, 1f) * 100).toInt() }
                Text(
                    if (progressPercent != null) {
                        "${dictionaryProgressText ?: stringResource(R.string.dictionary_importing)} · $progressPercent%"
                    } else {
                        dictionaryProgressText ?: stringResource(R.string.dictionary_importing)
                    }
                )
                if (dictionaryProgressValue != null) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { dictionaryProgressValue.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (dictionaryError != null) {
                Text(stringResource(R.string.dictionary_error, dictionaryError), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onImportClick,
                    enabled = !dictionaryLoading
                ) {
                    Text(stringResource(R.string.dictionary_import))
                }
                if (onOpenMdxClick != null && mdxMountState.enabled) {
                    OutlinedButton(onClick = onOpenMdxClick) {
                        Text(stringResource(R.string.settings_mdx_title))
                    }
                }
                OutlinedButton(onClick = onShowDictionaryManagerToggle) {
                    Text(
                        if (showDictionaryManager) {
                            stringResource(R.string.dictionary_hide_list)
                        } else {
                            stringResource(R.string.dictionary_show_list)
                        }
                    )
                }
                OutlinedButton(onClick = onShowDictionaryDeleteActionsToggle) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.common_delete),
                        tint = if (showDictionaryDeleteActions) androidx.compose.material3.MaterialTheme.colorScheme.error else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (showDictionaryManager) {
                val combinedItems = buildCombinedDictionaryItems(
                    context = context,
                    dictionaryRefs = dictionaryRefs,
                    dictionaryOrderIds = dictionaryOrderIds,
                    mdxMountState = mdxMountState
                )

                if (combinedItems.isEmpty()) {
                    Text(stringResource(R.string.dictionary_empty))
                } else {
                    combinedItems.forEachIndexed { index, item ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = itemContainerColor)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = item.title,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        item.detailText?.let { detailText ->
                                            Text(
                                                if (item.type == CombinedDictionaryType.MOUNTED) {
                                                    stringResource(R.string.mdx_dict_prefix, detailText)
                                                } else {
                                                    detailText
                                                },
                                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.width(72.dp),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Switch(
                                            checked = item.enabled,
                                            onCheckedChange = { checked ->
                                                when (item.type) {
                                                    CombinedDictionaryType.IMPORTED -> onSetImportedDictionaryEnabled(item.id, checked)
                                                    CombinedDictionaryType.MOUNTED -> onSetMountedDictionaryEnabled(item.id.removePrefix("mnt:"), checked)
                                                }
                                            },
                                            enabled = !dictionaryLoading
                                        )
                                    }
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { onMoveCombinedDictionary(index, index - 1) },
                                        enabled = !dictionaryLoading && index > 0
                                    ) {
                                        Text("↑")
                                    }
                                    OutlinedButton(
                                        onClick = { onMoveCombinedDictionary(index, index + 1) },
                                        enabled = !dictionaryLoading && index < combinedItems.lastIndex
                                    ) {
                                        Text("↓")
                                    }
                                    if (showDictionaryDeleteActions) {
                                        OutlinedButton(
                                            onClick = {
                                                when (item.type) {
                                                    CombinedDictionaryType.IMPORTED -> {
                                                        val targetIndex = dictionaryRefs.indexOfFirst { importedDictionaryId(it) == item.id }
                                                        if (targetIndex >= 0) onRemoveImportedDictionary(targetIndex)
                                                    }
                                                    CombinedDictionaryType.MOUNTED -> {
                                                        onRemoveMountedDictionary(item.id.removePrefix("mnt:"))
                                                    }
                                                }
                                            },
                                            enabled = !dictionaryLoading,
                                            contentPadding = ButtonDefaults.ContentPadding
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Delete,
                                                contentDescription = stringResource(R.string.common_delete),
                                                tint = androidx.compose.material3.MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
