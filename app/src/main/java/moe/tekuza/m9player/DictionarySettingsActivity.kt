package moe.tekuza.m9player

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.tekuza.m9player.ui.theme.TsetTheme

class DictionarySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TsetTheme {
                DictionarySettingsScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun DictionarySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dictionaryController = rememberDictionaryManagementController(
        context = context,
        contentResolver = context.contentResolver,
        scope = scope
    )

    val loadedDictionaries = dictionaryController.loadedDictionaries
    val dictionaryRefs = dictionaryController.dictionaryRefs
    val dictionaryLoading = dictionaryController.dictionaryLoading
    val dictionaryProgressText = dictionaryController.dictionaryProgressText
    val dictionaryProgressValue = dictionaryController.dictionaryProgressValue
    val dictionaryError = dictionaryController.dictionaryError
    val dictionaryOrderIds = dictionaryController.dictionaryOrderIds
    val mdxMountState = dictionaryController.mdxMountState

    var showDictionaryManager by remember { mutableStateOf(true) }
    var showDictionaryDeleteActions by remember { mutableStateOf(false) }
    var uiConfig by remember { mutableStateOf(loadDictionaryUiConfig(context)) }

    fun persistDictionaryRefs(refs: List<PersistedDictionaryRef>) {
        val persisted = loadPersistedImports(context)
        savePersistedImports(context, persisted.copy(dictionaries = refs))
    }

    fun refreshLookupData() {
        invalidateDictionaryLookupCaches()
        bumpDictionaryDataVersion(context)
    }

    val pickDictionaryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        dictionaryController.importDictionaries(
            uris = uris,
            onPersistDictionaryRefs = ::persistDictionaryRefs,
            onLookupDataChanged = ::refreshLookupData
        )
    }

    LaunchedEffect(Unit) {
        uiConfig = loadDictionaryUiConfig(context)
        dictionaryController.reloadExternalState()
        dictionaryController.restorePersistedDictionaries(
            persistedRefs = loadPersistedImports(context).dictionaries,
            onPersistDictionaryRefs = ::persistDictionaryRefs
        )
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_dictionary_title),
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = hoshiCardBackgroundColor())
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.dictionary_home_rich_mode_label),
                        )
                        Switch(
                            checked = uiConfig.showRichHomeDictionary,
                            onCheckedChange = { checked ->
                                uiConfig = uiConfig.copy(showRichHomeDictionary = checked)
                                saveDictionaryUiConfig(context, uiConfig)
                            }
                        )
                    }
                }
            }

            DictionaryManagementCard(
                context = context,
                dictionaryCount = loadedDictionaries.size + mdxMountState.entries.count { it.enabled },
                totalDictionaryEntries = loadedDictionaries.sumOf { it.entryCount },
                showHeader = false,
                containerColor = hoshiPanelBackgroundColor(),
                itemContainerColor = hoshiCardBackgroundColor(),
                dictionaryLoading = dictionaryLoading,
                dictionaryProgressText = dictionaryProgressText,
                dictionaryProgressValue = dictionaryProgressValue,
                dictionaryError = dictionaryError,
                showDictionaryManager = showDictionaryManager,
                showDictionaryDeleteActions = showDictionaryDeleteActions,
                dictionaryRefs = dictionaryRefs,
                loadedDictionaries = loadedDictionaries,
                dictionaryOrderIds = dictionaryOrderIds,
                mdxMountState = mdxMountState,
                onImportClick = { pickDictionaryLauncher.launch(arrayOf("application/zip", "*/*")) },
                onShowDictionaryManagerToggle = { showDictionaryManager = !showDictionaryManager },
                onShowDictionaryDeleteActionsToggle = { showDictionaryDeleteActions = !showDictionaryDeleteActions },
                onOpenMdxClick = {
                    context.startActivity(Intent(context, MdxMountSettingsActivity::class.java))
                },
                onMoveCombinedDictionary = { fromIndex, toIndex ->
                    dictionaryController.moveCombinedDictionary(fromIndex, toIndex, ::refreshLookupData)
                },
                onRemoveImportedDictionary = { index ->
                    dictionaryController.removeImportedDictionary(index, ::persistDictionaryRefs, ::refreshLookupData)
                },
                onRemoveMountedDictionary = { cacheKey ->
                    dictionaryController.removeMountedDictionary(cacheKey, ::refreshLookupData)
                },
                onSetImportedDictionaryEnabled = { dictionaryId, enabled ->
                    dictionaryController.setImportedDictionaryEnabled(
                        dictionaryId = dictionaryId,
                        enabled = enabled,
                        onPersistDictionaryRefs = ::persistDictionaryRefs,
                        onLookupDataChanged = ::refreshLookupData
                    )
                },
                onSetMountedDictionaryEnabled = { cacheKey, enabled ->
                    dictionaryController.setMountedDictionaryEnabled(cacheKey, enabled, ::refreshLookupData)
                }
            )
        }
    }
}
