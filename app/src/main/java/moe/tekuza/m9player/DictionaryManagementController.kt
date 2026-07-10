package moe.tekuza.m9player

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.tekuza.m9player.hoshi.features.dictionary.removeCollapsedDictionaryName
import java.util.Locale

internal class DictionaryManagementController(
    private val context: Context,
    private val contentResolver: ContentResolver,
    private val scope: CoroutineScope
) {
    var loadedDictionaries by mutableStateOf<List<LoadedDictionary>>(emptyList())
        private set
    var dictionaryRefs by mutableStateOf<List<PersistedDictionaryRef>>(emptyList())
        private set
    var dictionaryLoading by mutableStateOf(false)
        private set
    var dictionaryProgressText by mutableStateOf<String?>(null)
        private set
    var dictionaryProgressValue by mutableStateOf<Float?>(null)
        private set
    var dictionaryError by mutableStateOf<String?>(null)
        private set

    fun reloadExternalState() = Unit

    fun setPersistedDictionaryRefs(persistedRefs: List<PersistedDictionaryRef>) {
        dictionaryError = null
        dictionaryRefs = persistedRefs.distinctBy { it.uri }
        loadedDictionaries = emptyList()
    }

    suspend fun syncPersistedDictionaries(persistedRefs: List<PersistedDictionaryRef>) {
        if (persistedRefs == dictionaryRefs) return
        val loadedById = dictionaryRefs.mapIndexedNotNull { index, ref ->
            loadedDictionaries.getOrNull(index)?.let { loaded ->
                importedDictionaryId(ref) to loaded
            }
        }.toMap()
        val restoredPairs = persistedRefs
            .distinctBy { it.uri }
            .mapIndexedNotNull { index, ref ->
                val cached = loadedById[importedDictionaryId(ref)]
                if (cached != null) {
                    ref.copy(
                        name = cached.name.ifBlank { ref.name },
                        dictionaryType = cached.dictionaryType
                    ) to cached
                } else {
                    withContext(Dispatchers.IO) {
                        loadPersistedDictionaryFromStorage(
                            context = context,
                            ref = ref,
                            fallbackDisplayName = ref.name.ifBlank { "Dictionary ${index + 1}" }
                        )
                    }
                }
        }
        dictionaryRefs = restoredPairs.map { it.first }
        loadedDictionaries = restoredPairs.map { it.second }
    }

    suspend fun restorePersistedDictionaries(
        persistedRefs: List<PersistedDictionaryRef>,
        onPersistDictionaryRefs: (List<PersistedDictionaryRef>) -> Unit
    ) {
        dictionaryError = null
        if (persistedRefs.isEmpty()) {
            loadedDictionaries = emptyList()
            dictionaryRefs = emptyList()
            dictionaryLoading = false
            clearDictionaryProgress()
            return
        }

        val distinctRefs = persistedRefs.distinctBy { it.uri }
        dictionaryRefs = distinctRefs
        val restoredDictionaryList = mutableListOf<LoadedDictionary>()
        val restoredRefs = mutableListOf<PersistedDictionaryRef>()
        val missingNames = mutableListOf<String>()

        distinctRefs.forEachIndexed { index, ref ->
            val displayName = ref.name.ifBlank { "Dictionary ${index + 1}" }
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
        if (missingNames.isNotEmpty()) {
            dictionaryError = context.getString(
                R.string.dictionary_error_missing_local_files,
                missingNames.joinToString(", ")
            )
        } else {
            onPersistDictionaryRefs(dictionaryRefs)
        }
    }

    fun removeImportedDictionary(
        index: Int,
        onPersistDictionaryRefs: (List<PersistedDictionaryRef>) -> Unit,
        onLookupDataChanged: () -> Unit
    ) {
        val ref = dictionaryRefs.getOrNull(index) ?: return
        val removedName = loadedDictionaries.getOrNull(index)?.name ?: ref.name

        dictionaryRefs = dictionaryRefs.filterIndexed { i, _ -> i != index }
        loadedDictionaries = loadedDictionaries.filterIndexed { i, _ -> i != index }
        removeCollapsedDictionaryName(context, removedName)
        ref.cacheKey?.let { cacheKey ->
            scope.launch(Dispatchers.IO) {
                deleteDictionaryStorage(context, cacheKey)
            }
        }
        onPersistDictionaryRefs(dictionaryRefs)
        onLookupDataChanged()
    }

    fun setImportedDictionaryEnabled(
        dictionaryId: String,
        enabled: Boolean,
        onPersistDictionaryRefs: (List<PersistedDictionaryRef>) -> Unit,
        onLookupDataChanged: () -> Unit
    ) {
        val nextRefs = setImportedDictionaryEnabled(dictionaryRefs, dictionaryId, enabled)
        if (nextRefs === dictionaryRefs || nextRefs == dictionaryRefs) return
        dictionaryRefs = nextRefs
        onPersistDictionaryRefs(dictionaryRefs)
        onLookupDataChanged()
    }

    fun moveImportedDictionary(
        dictionaryId: String,
        toIndex: Int,
        onPersistDictionaryRefs: (List<PersistedDictionaryRef>) -> Unit,
        onLookupDataChanged: () -> Unit
    ) {
        val nextRefs = moveImportedDictionaryRefs(
            dictionaryRefs = dictionaryRefs,
            dictionaryId = dictionaryId,
            toIndex = toIndex
        )
        if (nextRefs === dictionaryRefs || nextRefs == dictionaryRefs) return
        dictionaryRefs = nextRefs
        onPersistDictionaryRefs(dictionaryRefs)
        onLookupDataChanged()
    }

    fun importDictionaries(
        uris: List<Uri>,
        onPersistDictionaryRefs: (List<PersistedDictionaryRef>) -> Unit,
        onLookupDataChanged: () -> Unit
    ) {
        val selectedUris = uris.distinctBy { it.toString() }
        if (selectedUris.isEmpty()) return

        scope.launch {
            dictionaryLoading = true
            dictionaryError = null
            var nextLoadedDictionaries = loadedDictionaries
            var nextDictionaryRefs = dictionaryRefs
            val importErrors = mutableListOf<String>()
            val importTargets = selectedUris.filter { uri ->
                queryDisplayName(contentResolver, uri).lowercase(Locale.US).endsWith(".zip")
            }
            if (importTargets.isEmpty()) {
                dictionaryLoading = false
                dictionaryError = context.getString(R.string.dictionary_error_pick_zip_only)
                clearDictionaryProgress()
                return@launch
            }

            importTargets.forEachIndexed { index, uri ->
                keepReadPermission(context, uri)
                val displayName = queryDisplayName(contentResolver, uri)
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
                            contentResolver = contentResolver,
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

                val duplicateByLoadedDictionary = nextLoadedDictionaries.any {
                    it.name.equals(parsedDictionary.name, ignoreCase = true) &&
                        it.entryCount > 0 &&
                        parsedDictionary.entryCount > 0 &&
                        it.entryCount == parsedDictionary.entryCount
                }
                if (duplicateByLoadedDictionary) {
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
                    name = parsedDictionary.name.ifBlank { displayName },
                    cacheKey = cacheKey,
                    dictionaryType = parsedDictionary.dictionaryType,
                    enabled = true
                )).distinctBy { it.uri }
            }

            val hadDictionaryListChange = nextDictionaryRefs != dictionaryRefs
            loadedDictionaries = nextLoadedDictionaries
            dictionaryRefs = nextDictionaryRefs
            if (hadDictionaryListChange) {
                onLookupDataChanged()
            }
            onPersistDictionaryRefs(dictionaryRefs)
            clearDictionaryProgress()
            dictionaryLoading = false
            dictionaryError = importErrors.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }
    }

    private fun updateDictionaryProgress(progress: DictionaryImportProgress) {
        val (text, value) = formatDictionaryImportProgress(context, progress)
        dictionaryProgressText = text
        dictionaryProgressValue = value
    }

    private fun clearDictionaryProgress() {
        dictionaryProgressText = null
        dictionaryProgressValue = null
    }
}

@Composable
internal fun rememberDictionaryManagementController(
    context: Context,
    contentResolver: ContentResolver,
    scope: CoroutineScope
): DictionaryManagementController =
    remember(context, contentResolver, scope) {
        DictionaryManagementController(
            context = context,
            contentResolver = contentResolver,
            scope = scope
        )
    }
