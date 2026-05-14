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
        .apply()
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
