package moe.tekuza.m9player.hoshi.features.dictionary

import android.content.Context

data class DictionarySettings(
    val dictionaryTabDefault: Boolean = false,
    val maxResults: Int = 16,
    val scanLength: Int = 16,
    val collapsedDictionaries: Set<String> = emptySet(),
    val compactGlossaries: Boolean = true,
    val showExpressionTags: Boolean = false,
    val harmonicFrequency: Boolean = false,
    val deduplicatePitchAccents: Boolean = false,
    val compactPitchAccents: Boolean = true,
    val customCSS: String = "",
) {
    fun normalized(): DictionarySettings = copy(
        maxResults = maxResults.coerceIn(MIN_MAX_RESULTS, MAX_MAX_RESULTS),
        scanLength = scanLength.coerceIn(MIN_SCAN_LENGTH, MAX_SCAN_LENGTH),
        collapsedDictionaries = collapsedDictionaries
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet(),
    )

    companion object {
        const val MIN_MAX_RESULTS = 1
        const val MAX_MAX_RESULTS = 50
        const val MIN_SCAN_LENGTH = 1
        const val MAX_SCAN_LENGTH = 64
    }
}

private const val DICTIONARY_SETTINGS_PREFS = "dictionary-settings"
private const val KEY_DICTIONARY_TAB_DEFAULT = "dictionaryTabDefault"
private const val KEY_MAX_RESULTS = "maxResults"
private const val KEY_SCAN_LENGTH = "scanLength"
private const val KEY_COLLAPSED_DICTIONARIES = "collapsedDictionaries"
private const val KEY_COMPACT_GLOSSARIES = "compactGlossaries"
private const val KEY_SHOW_EXPRESSION_TAGS = "showExpressionTags"
private const val KEY_HARMONIC_FREQUENCY = "harmonicFrequency"
private const val KEY_DEDUPLICATE_PITCH_ACCENTS = "deduplicatePitchAccents"
private const val KEY_COMPACT_PITCH_ACCENTS = "compactPitchAccents"
private const val KEY_CUSTOM_CSS = "customCSS"

internal fun cleanupCollapsedDictionaries(
    collapsedDictionaries: Set<String>,
    availableDictionaryNames: Collection<String>
): Set<String> {
    val available = availableDictionaryNames
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
    if (available.isEmpty()) return emptySet()
    return collapsedDictionaries
        .map { it.trim() }
        .filter { it.isNotBlank() && it in available }
        .toSet()
}

internal fun loadDictionarySettings(context: Context): DictionarySettings {
    val preferences = context.getSharedPreferences(DICTIONARY_SETTINGS_PREFS, Context.MODE_PRIVATE)
    return DictionarySettings(
        dictionaryTabDefault = preferences.getBoolean(KEY_DICTIONARY_TAB_DEFAULT, false),
        maxResults = preferences.getInt(KEY_MAX_RESULTS, 16),
        scanLength = preferences.getInt(KEY_SCAN_LENGTH, 16),
        collapsedDictionaries = preferences
            .getStringSet(KEY_COLLAPSED_DICTIONARIES, emptySet())
            .orEmpty(),
        compactGlossaries = preferences.getBoolean(KEY_COMPACT_GLOSSARIES, true),
        showExpressionTags = preferences.getBoolean(KEY_SHOW_EXPRESSION_TAGS, false),
        harmonicFrequency = preferences.getBoolean(KEY_HARMONIC_FREQUENCY, false),
        deduplicatePitchAccents = preferences.getBoolean(KEY_DEDUPLICATE_PITCH_ACCENTS, false),
        compactPitchAccents = preferences.getBoolean(KEY_COMPACT_PITCH_ACCENTS, true),
        customCSS = preferences.getString(KEY_CUSTOM_CSS, "").orEmpty(),
    ).normalized()
}

internal fun saveDictionarySettings(context: Context, settings: DictionarySettings) {
    val normalized = settings.normalized()
    context.getSharedPreferences(DICTIONARY_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_DICTIONARY_TAB_DEFAULT, normalized.dictionaryTabDefault)
        .putInt(KEY_MAX_RESULTS, normalized.maxResults)
        .putInt(KEY_SCAN_LENGTH, normalized.scanLength)
        .putStringSet(KEY_COLLAPSED_DICTIONARIES, normalized.collapsedDictionaries)
        .putBoolean(KEY_COMPACT_GLOSSARIES, normalized.compactGlossaries)
        .putBoolean(KEY_SHOW_EXPRESSION_TAGS, normalized.showExpressionTags)
        .putBoolean(KEY_HARMONIC_FREQUENCY, normalized.harmonicFrequency)
        .putBoolean(KEY_DEDUPLICATE_PITCH_ACCENTS, normalized.deduplicatePitchAccents)
        .putBoolean(KEY_COMPACT_PITCH_ACCENTS, normalized.compactPitchAccents)
        .putString(KEY_CUSTOM_CSS, normalized.customCSS)
        .apply()
}

internal fun updateDictionarySettings(
    context: Context,
    transform: (DictionarySettings) -> DictionarySettings
): DictionarySettings {
    val next = transform(loadDictionarySettings(context)).normalized()
    saveDictionarySettings(context, next)
    return next
}

internal fun removeCollapsedDictionaryName(context: Context, dictionaryName: String) {
    val name = dictionaryName.trim()
    if (name.isBlank()) return
    updateDictionarySettings(context) { settings ->
        settings.copy(collapsedDictionaries = settings.collapsedDictionaries - name)
    }
}
