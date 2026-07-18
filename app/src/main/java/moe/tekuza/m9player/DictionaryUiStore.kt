package moe.tekuza.m9player

import android.content.Context

internal data class DictionaryUiConfig(
    val showRichHomeDictionary: Boolean = false
)

private const val DICTIONARY_UI_PREFS = "dictionary_ui_prefs"
private const val KEY_SHOW_RICH_HOME_DICTIONARY = "show_rich_home_dictionary"

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

internal fun importedDictionaryId(ref: PersistedDictionaryRef): String {
    val base = ref.cacheKey?.takeIf { it.isNotBlank() }
        ?: buildDictionaryCacheKey(ref.uri, ref.name.ifBlank { "dictionary" })
    return "imp:$base"
}

internal data class CombinedDictionaryItem(
    val id: String,
    val title: String,
    val enabled: Boolean = true,
    val usesLegacyMediaFormat: Boolean = false,
)

internal fun buildCombinedDictionaryItems(
    context: Context,
    dictionaryRefs: List<PersistedDictionaryRef>
): List<CombinedDictionaryItem> {
    return dictionaryRefs.mapIndexed { index, ref ->
        CombinedDictionaryItem(
            id = importedDictionaryId(ref),
            title = ref.name.ifBlank { context.getString(R.string.dictionary_default_name, index + 1) },
            enabled = ref.enabled,
            usesLegacyMediaFormat = usesLegacyDictionaryMediaFormat(
                context = context,
                cacheKey = ref.cacheKey ?: buildDictionaryCacheKey(ref.uri, ref.name.ifBlank { "dictionary" }),
            ),
        )
    }
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

internal fun moveImportedDictionaryRefs(
    dictionaryRefs: List<PersistedDictionaryRef>,
    dictionaryId: String,
    toIndex: Int
): List<PersistedDictionaryRef> {
    val fromIndex = dictionaryRefs.indexOfFirst { importedDictionaryId(it) == dictionaryId }
    if (fromIndex !in dictionaryRefs.indices) return dictionaryRefs
    val boundedToIndex = toIndex.coerceIn(0, dictionaryRefs.lastIndex)
    if (fromIndex == boundedToIndex) return dictionaryRefs
    return dictionaryRefs.toMutableList().also { refs ->
        val moved = refs.removeAt(fromIndex)
        refs.add(boundedToIndex, moved)
    }
}
