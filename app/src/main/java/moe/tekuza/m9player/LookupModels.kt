package moe.tekuza.m9player

import androidx.compose.ui.geometry.Rect

internal data class ReaderSentenceSelection(
    val text: String,
    val cueRange: IntRange
)

internal data class ReaderLookupAnchor(
    val rects: List<Rect>
)

internal data class ReaderLookupLayer(
    val loading: Boolean,
    val error: String?,
    val groupedResults: List<GroupedLookupResult>,
    val sourceTerm: String?,
    val cue: ReaderSubtitleCue?,
    val cueIndex: Int?,
    val anchorOffset: Int?,
    val anchor: ReaderLookupAnchor?,
    val placeBelow: Boolean,
    val preferSidePlacement: Boolean,
    val selectedRange: IntRange?,
    val selectionText: String?,
    val popupSentence: String?,
    val highlightedDefinitionKey: String?,
    val highlightedDefinitionRects: List<Rect>,
    val highlightedDefinitionNodePathJson: String?,
    val highlightedDefinitionOffset: Int?,
    val highlightedDefinitionLength: Int?,
    val collapsedSections: Map<String, Boolean>,
    val autoPlayNonce: Long,
    val autoPlayedKey: String?
)
