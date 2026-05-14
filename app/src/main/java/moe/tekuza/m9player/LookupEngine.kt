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
    val imported = refs.mapIndexedNotNull { index, ref ->
        loadPersistedDictionaryFromStorage(
            context = context,
            ref = ref,
            fallbackDisplayName = ref.name.ifBlank { "Dictionary ${index + 1}" }
        )?.second
    }
    return includeMountedMdxDictionary(context, imported)
}
