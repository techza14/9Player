package moe.tekuza.m9player

import androidx.compose.ui.geometry.Rect
import de.manhhao.hoshi.LookupResult

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
    val avoidAnchor: ReaderLookupAnchor? = null,
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
    val autoPlayedKey: String?,
    val hoshiResults: List<LookupResult> = emptyList(),
    val hoshiDictionaryStyles: Map<String, String> = emptyMap()
)

internal fun ReaderLookupAnchor?.boundingRectCoreOrNull(): Rect? {
    val rects = this?.rects?.filter { !it.isEmpty } ?: return null
    if (rects.isEmpty()) return null
    var left = rects.first().left
    var top = rects.first().top
    var right = rects.first().right
    var bottom = rects.first().bottom
    rects.drop(1).forEach { rect ->
        left = minOf(left, rect.left)
        top = minOf(top, rect.top)
        right = maxOf(right, rect.right)
        bottom = maxOf(bottom, rect.bottom)
    }
    return Rect(left = left, top = top, right = right, bottom = bottom)
}
