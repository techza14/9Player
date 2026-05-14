package moe.tekuza.m9player

import androidx.compose.ui.geometry.Rect
import de.manhhao.hoshi.LookupResult

internal fun buildLookupLayerFromGroupedResults(
    groupedResults: List<GroupedLookupResult>,
    loading: Boolean,
    error: String?,
    sourceTerm: String? = null,
    cue: ReaderSubtitleCue?,
    cueIndex: Int?,
    anchorOffset: Int?,
    anchor: ReaderLookupAnchor?,
    avoidAnchor: ReaderLookupAnchor? = null,
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
    autoPlayedKey: String? = null,
    hoshiResults: List<LookupResult> = emptyList(),
    hoshiDictionaryStyles: Map<String, String> = emptyMap()
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
        avoidAnchor = avoidAnchor,
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
        autoPlayedKey = autoPlayedKey,
        hoshiResults = hoshiResults,
        hoshiDictionaryStyles = hoshiDictionaryStyles
    )
}
