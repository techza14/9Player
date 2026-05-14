package moe.tekuza.m9player.hoshi.features.dictionary

data class DictionarySettings(
    val dictionaryTabDefault: Boolean = false,
    val maxResults: Int = 16,
    val scanLength: Int = 16,
    val collapseDictionaries: Boolean = false,
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
    )

    companion object {
        const val MIN_MAX_RESULTS = 1
        const val MAX_MAX_RESULTS = 50
        const val MIN_SCAN_LENGTH = 1
        const val MAX_SCAN_LENGTH = 64
    }
}