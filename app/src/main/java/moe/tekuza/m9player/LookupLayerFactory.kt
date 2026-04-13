package moe.tekuza.m9player

import androidx.compose.ui.geometry.Rect

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
    val groupedResults = groupLookupResultsByTerm(
        results = rawResults,
        dictionaryCssByName = dictionaryCssByName,
        dictionaryPriorityByName = dictionaryPriorityByName,
        dictionaryTypeByName = dictionaryTypeByName
    ).take(10)
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
