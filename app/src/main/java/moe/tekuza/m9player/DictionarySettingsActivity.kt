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
import androidx.compose.runtime.DisposableEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    var persistedImports by remember { mutableStateOf(loadPersistedImports(context)) }
    var loadedDictionaries by remember { mutableStateOf<List<LoadedDictionary>>(emptyList()) }
    var dictionaryRefs by remember { mutableStateOf<List<PersistedDictionaryRef>>(emptyList()) }
    var dictionaryLoading by remember { mutableStateOf(false) }
    var dictionaryError by remember { mutableStateOf<String?>(null) }
    var dictionaryOrderIds by remember { mutableStateOf(loadDictionaryOrderIds(context)) }
    var mdxMountState by remember { mutableStateOf(loadMdxMountState(context)) }
    var dictionaryProgressText by remember { mutableStateOf<String?>(null) }
    var dictionaryProgressValue by remember { mutableStateOf<Float?>(null) }
    var showDictionaryManager by remember { mutableStateOf(true) }
    var showDictionaryDeleteActions by remember { mutableStateOf(false) }
    var uiConfig by remember { mutableStateOf(loadDictionaryUiConfig(context)) }

    fun persistImportState(nextRefs: List<PersistedDictionaryRef>, nextLoaded: List<LoadedDictionary>) {
        dictionaryRefs = nextRefs
        loadedDictionaries = nextLoaded
        persistedImports = persistedImports.copy(dictionaries = nextRefs)
        savePersistedImports(context, persistedImports)
    }

    fun updateDictionaryProgress(progress: DictionaryImportProgress) {
        val (text, value) = formatDictionaryImportProgress(context, progress)
        dictionaryProgressText = text
        dictionaryProgressValue = value
    }

    fun clearDictionaryProgress() {
        dictionaryProgressText = null
        dictionaryProgressValue = null
    }

    fun refreshLookupIfNeeded() {
        invalidateDictionaryLookupCaches()
        bumpDictionaryDataVersion(context)
    }

    fun removeImportedDictionaryAt(index: Int) {
        val ref = dictionaryRefs.getOrNull(index) ?: return
        val removedId = importedDictionaryId(ref)
        loadedDictionaries = loadedDictionaries.filterIndexed { i, _ -> i != index }
        dictionaryRefs = dictionaryRefs.filterIndexed { i, _ -> i != index }
        bumpDictionaryDataVersion(context)
        ref.cacheKey?.let { cacheKey ->
            scope.launch(Dispatchers.IO) {
                deleteDictionaryStorage(context, cacheKey)
            }
        }
        dictionaryOrderIds = removeDictionaryOrderId(dictionaryOrderIds, removedId)
        saveDictionaryOrderIds(context, dictionaryOrderIds)
        persistedImports = persistedImports.copy(dictionaries = dictionaryRefs)
        savePersistedImports(context, persistedImports)
        refreshLookupIfNeeded()
    }

    fun removeMountedDictionaryByCacheKey(cacheKey: String) {
        if (cacheKey.isBlank()) return
        mdxMountState = mdxMountState.copy(entries = mdxMountState.entries.filterNot { it.cacheKey == cacheKey })
        saveMdxMountState(context, mdxMountState)
        dictionaryOrderIds = removeDictionaryOrderId(dictionaryOrderIds, "mnt:$cacheKey")
        saveDictionaryOrderIds(context, dictionaryOrderIds)
        refreshLookupIfNeeded()
    }

    fun setImportedDictionaryEnabled(dictionaryId: String, enabled: Boolean) {
        val nextRefs = setImportedDictionaryEnabled(dictionaryRefs, dictionaryId, enabled)
        if (nextRefs === dictionaryRefs || nextRefs == dictionaryRefs) return
        dictionaryRefs = nextRefs
        persistedImports = persistedImports.copy(dictionaries = dictionaryRefs)
        savePersistedImports(context, persistedImports)
        refreshLookupIfNeeded()
    }

    fun setMountedDictionaryEnabled(cacheKey: String, enabled: Boolean) {
        val nextState = setMountedDictionaryEnabled(mdxMountState, cacheKey, enabled)
        if (nextState == mdxMountState) return
        mdxMountState = nextState
        saveMdxMountState(context, mdxMountState)
        refreshLookupIfNeeded()
    }

    fun moveCombinedDictionary(fromIndex: Int, toIndex: Int) {
        val combinedItems = buildCombinedDictionaryItems(
            context = context,
            dictionaryRefs = dictionaryRefs,
            loadedDictionaries = loadedDictionaries,
            dictionaryOrderIds = dictionaryOrderIds,
            mdxMountState = mdxMountState
        )
        val ids = moveDictionaryOrder(
            orderIds = dictionaryOrderIds,
            currentIds = combinedItems.map { it.id },
            fromIndex = fromIndex,
            toIndex = toIndex
        )
        if (ids == dictionaryOrderIds) return
        dictionaryOrderIds = ids
        saveDictionaryOrderIds(context, dictionaryOrderIds)
        refreshLookupIfNeeded()
    }

    val pickDictionaryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val selectedUris = uris.distinctBy { it.toString() }
        if (selectedUris.isEmpty()) return@rememberLauncherForActivityResult

        scope.launch {
            dictionaryLoading = true
            dictionaryError = null
            var nextLoadedDictionaries = loadedDictionaries
            var nextDictionaryRefs = dictionaryRefs
            val importErrors = mutableListOf<String>()
            val importTargets = selectedUris.filter { uri ->
                val name = queryDisplayName(context.contentResolver, uri).lowercase(java.util.Locale.US)
                name.endsWith(".zip")
            }
            if (importTargets.isEmpty()) {
                dictionaryLoading = false
                dictionaryError = context.getString(R.string.dictionary_error_pick_zip_only)
                clearDictionaryProgress()
                return@launch
            }

            importTargets.forEachIndexed { index, uri ->
                keepReadPermission(context, uri)
                val displayName = queryDisplayName(context.contentResolver, uri)
                val uriValue = uri.toString()
                if (nextDictionaryRefs.any { it.uri == uriValue }) {
                    importErrors += context.getString(R.string.status_duplicate_dictionary)
                    return@forEachIndexed
                }
                val cacheKey = buildDictionaryCacheKey(uriValue, displayName)

                updateDictionaryProgress(
                    DictionaryImportProgress(
                        stage = context.getString(R.string.dictionary_import_batch, index + 1, importTargets.size, displayName),
                        current = 0,
                        total = 0
                    )
                )

                val parseResult = withContext(Dispatchers.IO) {
                    runCatching {
                        importDictionaryFromZip(
                            context = context,
                            contentResolver = context.contentResolver,
                            uri = uri,
                            displayName = displayName,
                            cacheKey = cacheKey
                        ) { progress ->
                            scope.launch(Dispatchers.Main.immediate) {
                                updateDictionaryProgress(
                                    progress.copy(
                                        stage = context.getString(
                                            R.string.dictionary_import_batch_suffix,
                                            localizeDictionaryImportStage(context, progress.stage),
                                            index + 1,
                                            importTargets.size
                                        )
                                    )
                                )
                            }
                        }
                    }
                }

                val parsedDictionary = parseResult.getOrNull()
                if (parsedDictionary == null) {
                    importErrors += parseResult.exceptionOrNull()?.message ?: "Failed to import dictionary"
                    return@forEachIndexed
                }

                val duplicateByName = nextLoadedDictionaries.any {
                    it.name.equals(parsedDictionary.name, ignoreCase = true) &&
                        it.entryCount > 0 &&
                        parsedDictionary.entryCount > 0 &&
                        it.entryCount == parsedDictionary.entryCount
                }
                if (duplicateByName) {
                    parsedDictionary.cacheKey
                        .takeIf { it.isNotBlank() }
                        ?.let { key ->
                            scope.launch(Dispatchers.IO) { deleteDictionaryStorage(context, key) }
                        }
                    importErrors += context.getString(R.string.status_duplicate_dictionary_detected, parsedDictionary.name)
                    return@forEachIndexed
                }

                nextLoadedDictionaries = nextLoadedDictionaries + parsedDictionary
                nextDictionaryRefs = (nextDictionaryRefs + PersistedDictionaryRef(
                    uri = uriValue,
                    name = displayName,
                    cacheKey = cacheKey,
                    dictionaryType = parsedDictionary.dictionaryType,
                    enabled = true
                )).distinctBy { it.uri }
            }

            val hadDictionaryListChange = nextDictionaryRefs != dictionaryRefs
            loadedDictionaries = nextLoadedDictionaries
            dictionaryRefs = nextDictionaryRefs
            if (hadDictionaryListChange) {
                bumpDictionaryDataVersion(context)
            }
            persistedImports = persistedImports.copy(dictionaries = nextDictionaryRefs)
            savePersistedImports(context, persistedImports)
            clearDictionaryProgress()
            dictionaryLoading = false
            dictionaryError = importErrors.takeIf { it.isNotEmpty() }?.joinToString("\n")
            refreshLookupIfNeeded()
        }
    }

    LaunchedEffect(Unit) {
        persistedImports = loadPersistedImports(context)
        uiConfig = loadDictionaryUiConfig(context)
        dictionaryOrderIds = loadDictionaryOrderIds(context)
        mdxMountState = loadMdxMountState(context)

        dictionaryLoading = true
        dictionaryError = null
        val restoredDictionaryList = mutableListOf<LoadedDictionary>()
        val restoredRefs = mutableListOf<PersistedDictionaryRef>()
        val distinctRefs = persistedImports.dictionaries.distinctBy { it.uri }
        val total = distinctRefs.size

        val missingNames = mutableListOf<String>()
        distinctRefs.forEachIndexed { index, ref ->
            val displayName = ref.name.ifBlank { "Dictionary ${index + 1}" }
            updateDictionaryProgress(
                DictionaryImportProgress(
                    stage = context.getString(R.string.dictionary_loading),
                    current = index + 1,
                    total = total
                )
            )

            val restoredPair = withContext(Dispatchers.IO) {
                loadPersistedDictionaryFromStorage(
                    context = context,
                    ref = ref,
                    fallbackDisplayName = displayName
                )
            }
            if (restoredPair != null) {
                restoredRefs += restoredPair.first
                restoredDictionaryList += restoredPair.second
            } else {
                missingNames += displayName
            }
        }

        loadedDictionaries = restoredDictionaryList
        dictionaryRefs = restoredRefs
        dictionaryLoading = false
        clearDictionaryProgress()
        if (missingNames.isNotEmpty()) {
            dictionaryError = context.getString(
                R.string.dictionary_error_missing_local_files,
                missingNames.joinToString(", ")
            )
        }
        if (missingNames.isEmpty() && restoredRefs != persistedImports.dictionaries) {
            persistedImports = persistedImports.copy(dictionaries = restoredRefs)
            savePersistedImports(context, persistedImports)
        }
    }

    DisposableEffect(context) {
        onDispose {
            clearDictionaryProgress()
        }
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
                onMoveCombinedDictionary = { fromIndex, toIndex -> moveCombinedDictionary(fromIndex, toIndex) },
                onRemoveImportedDictionary = ::removeImportedDictionaryAt,
                onRemoveMountedDictionary = ::removeMountedDictionaryByCacheKey,
                onSetImportedDictionaryEnabled = ::setImportedDictionaryEnabled,
                onSetMountedDictionaryEnabled = ::setMountedDictionaryEnabled
            )
        }
    }
}
