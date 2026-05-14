package moe.tekuza.m9player

import android.content.Context

internal data class DictionaryUiConfig(
    val showRichHomeDictionary: Boolean = false
)

private const val DICTIONARY_UI_PREFS = "dictionary_ui_prefs"
private const val KEY_SHOW_RICH_HOME_DICTIONARY = "show_rich_home_dictionary"
private const val DICTIONARY_ORDER_PREFS = "dictionary_order_prefs"
private const val KEY_DICTIONARY_ORDER_IDS = "dictionary_order_ids"

internal fun loadDictionaryUiConfig(context: Context): DictionaryUiConfig {
    val prefs = context.getSharedPreferences(DICTIONARY_UI_PREFS, Context.MODE_PRIVATE)
    return DictionaryUiConfig(
        showRichHomeDictionary = prefs.getBoolean(KEY_SHOW_RICH_HOME_DICTIONARY, false)
    )
}

internal fun saveDictionaryUiConfig(context: Context, config: DictionaryUiConfig) {
    context.getSharedPreferences(DICTIONARY_UI_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_SHOW_RICH_HOME_DICTIONARY, config.showRichHomeDictionary)
        .apply()
}

internal fun loadDictionaryOrderIds(context: Context): List<String> {
    val raw = context.getSharedPreferences(DICTIONARY_ORDER_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_DICTIONARY_ORDER_IDS, null)
        ?.trim()
        .orEmpty()
    if (raw.isBlank()) return emptyList()
    return raw.split('\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}

internal fun saveDictionaryOrderIds(context: Context, ids: List<String>) {
    val normalized = ids.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    context.getSharedPreferences(DICTIONARY_ORDER_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_DICTIONARY_ORDER_IDS, normalized.joinToString("\n"))
        .commit()
}

internal fun moveDictionaryOrder(orderIds: List<String>, currentIds: List<String>, fromIndex: Int, toIndex: Int): List<String> {
    val normalized = normalizeDictionaryOrderIds(orderIds, currentIds)
    if (normalized.isEmpty()) return normalized
    if (fromIndex == toIndex) return normalized
    if (fromIndex !in normalized.indices || toIndex !in normalized.indices) return normalized
    return normalized.toMutableList().also { ids ->
        val moved = ids.removeAt(fromIndex)
        ids.add(toIndex, moved)
    }
}

internal fun removeDictionaryOrderId(orderIds: List<String>, removedId: String): List<String> {
    if (removedId.isBlank()) return orderIds
    return orderIds.filterNot { it == removedId }
}

internal fun normalizeDictionaryOrderIds(orderIds: List<String>, currentIds: List<String>): List<String> {
    val current = currentIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    if (current.isEmpty()) return emptyList()
    return buildList {
        orderIds.forEach { id -> if (id in current && id !in this) add(id) }
        current.forEach { id -> if (id !in this) add(id) }
    }
}

internal fun importedDictionaryId(ref: PersistedDictionaryRef): String {
    val base = ref.cacheKey?.takeIf { it.isNotBlank() }
        ?: buildDictionaryCacheKey(ref.uri, ref.name.ifBlank { "dictionary" })
    return "imp:$base"
}

internal enum class CombinedDictionaryType {
    IMPORTED,
    MOUNTED
}

internal data class CombinedDictionaryItem(
    val id: String,
    val type: CombinedDictionaryType,
    val title: String,
    val countText: String,
    val enabled: Boolean = true
)

internal fun orderedCombinedDictionaryItems(
    importedItems: List<CombinedDictionaryItem>,
    mountedItems: List<CombinedDictionaryItem>,
    dictionaryOrderIds: List<String>
): List<CombinedDictionaryItem> {
    val combinedById = (importedItems + mountedItems).associateBy { it.id }
    val orderedIds = normalizeDictionaryOrderIds(dictionaryOrderIds, combinedById.keys.toList())
    return orderedIds.mapNotNull { combinedById[it] }
}

internal fun buildCombinedDictionaryItems(
    context: Context,
    dictionaryRefs: List<PersistedDictionaryRef>,
    loadedDictionaries: List<LoadedDictionary>,
    dictionaryOrderIds: List<String>,
    mdxMountState: MdxMountState
): List<CombinedDictionaryItem> {
    val importedItems = dictionaryRefs.mapIndexed { index, ref ->
        val loaded = loadedDictionaries.getOrNull(index)
        CombinedDictionaryItem(
            id = importedDictionaryId(ref),
            type = CombinedDictionaryType.IMPORTED,
            title = ref.name.ifBlank { context.getString(R.string.dictionary_default_name, index + 1) },
            countText = loaded?.entryCount?.let { context.getString(R.string.dictionary_count, it) }
                ?: context.getString(R.string.dictionary_unloaded),
            enabled = ref.enabled
        )
    }
    val mountedItems = if (mdxMountState.enabled) {
        mdxMountState.entries.map { entry ->
            CombinedDictionaryItem(
                id = "mnt:${entry.cacheKey}",
                type = CombinedDictionaryType.MOUNTED,
                title = entry.displayName.ifBlank { "mounted.mdx" },
                countText = if (entry.enabled) {
                    context.getString(R.string.mdx_dict_enabled)
                } else {
                    context.getString(R.string.mdx_dict_disabled)
                },
                enabled = entry.enabled
            )
        }
    } else {
        emptyList()
    }
    return orderedCombinedDictionaryItems(
        importedItems = importedItems,
        mountedItems = mountedItems,
        dictionaryOrderIds = dictionaryOrderIds
    )
}

internal fun setImportedDictionaryEnabled(
    dictionaryRefs: List<PersistedDictionaryRef>,
    dictionaryId: String,
    enabled: Boolean
): List<PersistedDictionaryRef> {
    val targetIndex = dictionaryRefs.indexOfFirst { importedDictionaryId(it) == dictionaryId }
    if (targetIndex < 0) return dictionaryRefs
    val current = dictionaryRefs[targetIndex]
    if (current.enabled == enabled) return dictionaryRefs
    return dictionaryRefs.toMutableList().also { refs ->
        refs[targetIndex] = current.copy(enabled = enabled)
    }
}

internal fun setMountedDictionaryEnabled(
    mdxMountState: MdxMountState,
    cacheKey: String,
    enabled: Boolean
): MdxMountState {
    val currentEntries = mdxMountState.entries
    val targetIndex = currentEntries.indexOfFirst { it.cacheKey == cacheKey }
    if (targetIndex < 0) return mdxMountState
    val current = currentEntries[targetIndex]
    if (current.enabled == enabled) return mdxMountState
    return mdxMountState.copy(
        entries = currentEntries.toMutableList().also { entries ->
            entries[targetIndex] = current.copy(enabled = enabled)
        }
    )
}
