package moe.tekuza.m9player

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

internal fun loadAvailableDictionaries(
    context: Context,
    contentResolver: ContentResolver = context.contentResolver
): List<LoadedDictionary> {
    val persisted = loadPersistedImports(context)
    val refs = persisted.dictionaries.distinctBy { it.uri }
    return refs.mapNotNull { ref ->
        val restoredUri = runCatching { Uri.parse(ref.uri) }.getOrNull()
        val displayName = ref.name.ifBlank {
            restoredUri?.let { queryLookupSourceDisplayName(contentResolver, it) }.orEmpty()
        }
        val cacheKey = ref.cacheKey ?: buildDictionaryCacheKey(ref.uri, displayName)
        loadDictionaryFromSqlite(context, cacheKey)
    }
}

private fun queryLookupSourceDisplayName(contentResolver: ContentResolver, uri: Uri): String {
    return runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else {
                    null
                }
            }
    }.getOrNull()?.trim().orEmpty()
}

internal fun computeLookupResults(
    context: Context,
    dictionaries: List<LoadedDictionary>,
    candidates: List<String>
): List<DictionarySearchResult> {
    if (dictionaries.isEmpty() || candidates.isEmpty()) return emptyList()
    val query = candidates
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        ?: return emptyList()
    return searchDictionarySql(
        context = context,
        dictionaries = dictionaries,
        query = query,
        maxResults = MAX_LOOKUP_RESULTS,
        profile = DictionaryQueryProfile.FULL
    )
}
