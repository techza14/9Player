package moe.tekuza.m9player

import androidx.compose.ui.geometry.Rect

private const val LOOKUP_GROUPED_CACHE_MAX = 64

private data class LookupGroupedCacheKey(
    val fingerprint: String,
    val dictionaryCssFingerprint: Int,
    val dictionaryPriorityFingerprint: Int,
    val dictionaryTypeFingerprint: Int
)

private val lookupGroupedResultsCache =
    object : LinkedHashMap<LookupGroupedCacheKey, List<GroupedLookupResult>>(LOOKUP_GROUPED_CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<LookupGroupedCacheKey, List<GroupedLookupResult>>
        ): Boolean = size > LOOKUP_GROUPED_CACHE_MAX
    }

private fun buildLookupResultsFingerprint(results: List<DictionarySearchResult>): String {
    if (results.isEmpty()) return "empty"
    val out = StringBuilder(results.size * 48)
    results.forEach { hit ->
        val entry = hit.entry
        out.append(entry.dictionary)
            .append('\u0001')
            .append(entry.term)
            .append('\u0001')
            .append(entry.reading.orEmpty())
            .append('\u0001')
            .append(hit.score)
            .append('\u0001')
            .append(hit.matchedLength)
            .append('\u0001')
            .append(entry.definitions.joinToString(separator = "\u0002"))
            .append('\u0003')
    }
    return out.toString()
}

private fun groupedResultsFromCacheOrBuild(
    rawResults: List<DictionarySearchResult>,
    dictionaryCssByName: Map<String, String?>,
    dictionaryPriorityByName: Map<String, Int>,
    dictionaryTypeByName: Map<String, LookupDictionaryType>
): List<GroupedLookupResult> {
    val key = LookupGroupedCacheKey(
        fingerprint = buildLookupResultsFingerprint(rawResults),
        dictionaryCssFingerprint = dictionaryCssByName.hashCode(),
        dictionaryPriorityFingerprint = dictionaryPriorityByName.hashCode(),
        dictionaryTypeFingerprint = dictionaryTypeByName.hashCode()
    )
    synchronized(lookupGroupedResultsCache) {
        lookupGroupedResultsCache[key]?.let { return it }
    }
    val built = groupLookupResultsByTerm(
        results = rawResults,
        dictionaryCssByName = dictionaryCssByName,
        dictionaryPriorityByName = dictionaryPriorityByName,
        dictionaryTypeByName = dictionaryTypeByName
    ).take(10)
    synchronized(lookupGroupedResultsCache) {
        lookupGroupedResultsCache[key] = built
    }
    return built
}

internal fun buildLookupLayerFromRawResults(
    rawResults: List<DictionarySearchResult>,
    dictionaryCssByName: Map<String, String?>,
    dictionaryPriorityByName: Map<String, Int>,
    dictionaryTypeByName: Map<String, LookupDictionaryType> = emptyMap(),
    loading: Boolean,
    error: String?,
    sourceTerm: String? = null,
    cue: ReaderSubtitleCue?,
    cueIndex: Int?,
    anchorOffset: Int?,
    anchor: ReaderLookupAnchor?,
    placeBelow: Boolean,
    preferSidePlacement: Boolean = false,
    selectedRange: IntRange?,
    selectionText: String?,
    popupSentence: String? = null,
    highlightedDefinitionKey: String? = null,
    highlightedDefinitionRects: List<Rect> = emptyList(),
    highlightedDefinitionNodePathJson: String? = null,
    highlightedDefinitionOffset: Int? = null,
    highlightedDefinitionLength: Int? = null,
    collapsedSections: Map<String, Boolean> = emptyMap(),
    autoPlayNonce: Long = System.nanoTime(),
    autoPlayedKey: String? = null
): ReaderLookupLayer {
    val groupedResults = groupedResultsFromCacheOrBuild(
        rawResults = rawResults,
        dictionaryCssByName = dictionaryCssByName,
        dictionaryPriorityByName = dictionaryPriorityByName,
        dictionaryTypeByName = dictionaryTypeByName
    )
    return buildLookupLayerFromGroupedResults(
        groupedResults = groupedResults,
        loading = loading,
        error = error,
        sourceTerm = sourceTerm,
        cue = cue,
        cueIndex = cueIndex,
        anchorOffset = anchorOffset,
        anchor = anchor,
        placeBelow = placeBelow,
        preferSidePlacement = preferSidePlacement,
        selectedRange = selectedRange,
        selectionText = selectionText,
        popupSentence = popupSentence,
        highlightedDefinitionKey = highlightedDefinitionKey,
        highlightedDefinitionRects = highlightedDefinitionRects,
        highlightedDefinitionNodePathJson = highlightedDefinitionNodePathJson,
        highlightedDefinitionOffset = highlightedDefinitionOffset,
        highlightedDefinitionLength = highlightedDefinitionLength,
        collapsedSections = collapsedSections,
        autoPlayNonce = autoPlayNonce,
        autoPlayedKey = autoPlayedKey
    )
}

internal fun buildLookupLayerFromGroupedResults(
    groupedResults: List<GroupedLookupResult>,
    loading: Boolean,
    error: String?,
    sourceTerm: String? = null,
    cue: ReaderSubtitleCue?,
    cueIndex: Int?,
    anchorOffset: Int?,
    anchor: ReaderLookupAnchor?,
    placeBelow: Boolean,
    preferSidePlacement: Boolean = false,
    selectedRange: IntRange?,
    selectionText: String?,
    popupSentence: String? = null,
    highlightedDefinitionKey: String? = null,
    highlightedDefinitionRects: List<Rect> = emptyList(),
    highlightedDefinitionNodePathJson: String? = null,
    highlightedDefinitionOffset: Int? = null,
    highlightedDefinitionLength: Int? = null,
    collapsedSections: Map<String, Boolean> = emptyMap(),
    autoPlayNonce: Long = System.nanoTime(),
    autoPlayedKey: String? = null
): ReaderLookupLayer {
    return ReaderLookupLayer(
        loading = loading,
        error = error,
        groupedResults = groupedResults,
        sourceTerm = sourceTerm,
        cue = cue,
        cueIndex = cueIndex,
        anchorOffset = anchorOffset,
        anchor = anchor,
        placeBelow = placeBelow,
        preferSidePlacement = preferSidePlacement,
        selectedRange = selectedRange,
        selectionText = selectionText,
        popupSentence = popupSentence,
        highlightedDefinitionKey = highlightedDefinitionKey,
        highlightedDefinitionRects = highlightedDefinitionRects,
        highlightedDefinitionNodePathJson = highlightedDefinitionNodePathJson,
        highlightedDefinitionOffset = highlightedDefinitionOffset,
        highlightedDefinitionLength = highlightedDefinitionLength,
        collapsedSections = collapsedSections,
        autoPlayNonce = autoPlayNonce,
        autoPlayedKey = autoPlayedKey
    )
}
