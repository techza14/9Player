package moe.tekuza.m9player

import android.content.Context

internal fun loadAvailableDictionaries(
    context: Context
): List<LoadedDictionary> {
    // Prewarm mounted MDX runtime/cache at startup to reduce first-query latency.
    prebuildMountedMdxIndexesAsync(context.applicationContext)
    val persisted = loadPersistedImports(context)
    val refs = persisted.dictionaries
        .asSequence()
        .filter { it.enabled }
        .distinctBy { it.uri }
        .toList()
    val importedById = refs.mapIndexedNotNull { index, ref ->
        val loaded = loadPersistedDictionaryFromStorage(
            context = context,
            ref = ref,
            fallbackDisplayName = ref.name.ifBlank { "Dictionary ${index + 1}" }
        )?.second ?: return@mapIndexedNotNull null
        importedDictionaryId(ref) to loaded
    }
    val mountedById = mountedMdxDictionariesFromState(context)
        .map { dictionary -> "mnt:${dictionary.cacheKey}" to dictionary }
    val dictionariesById = LinkedHashMap<String, LoadedDictionary>().apply {
        importedById.forEach { (id, dictionary) -> put(id, dictionary) }
        mountedById.forEach { (id, dictionary) -> put(id, dictionary) }
    }
    val orderedIds = normalizeDictionaryOrderIds(
        orderIds = loadDictionaryOrderIds(context),
        currentIds = dictionariesById.keys.toList()
    )
    return orderedIds.mapNotNull { dictionariesById[it] }
}
