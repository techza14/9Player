package moe.tekuza.m9player

import androidx.compose.ui.geometry.Rect

internal data class LookupDictionaryPresentation(
    val sectionKey: String,
    val dictionaryName: String,
    val expanded: Boolean,
    val mergedContent: LookupMergedDictionaryContent?
)

internal data class LookupGroupedPresentation(
    val term: String,
    val reading: String?,
    val groupedResult: GroupedLookupResult,
    val dictionaries: List<LookupDictionaryPresentation>
)

internal data class LookupMergedDictionaryContent(
    val definitionHtml: String,
    val dictionaryCss: String?,
    val highlightedRects: List<Rect>,
    val firstDefinitionKey: String
)

internal fun lookupDictionarySectionKey(term: String, dictionaryName: String): String {
    return "lookup|$term|$dictionaryName"
}

internal fun lookupDefinitionKey(term: String, dictionaryName: String, definitionIndex: Int): String {
    return "${lookupDictionarySectionKey(term, dictionaryName)}|$definitionIndex"
}

internal fun lookupDictionaryNameFromDefinitionKey(definitionKey: String): String? {
    val parts = definitionKey.split("|")
    if (parts.size < 4) return null
    return parts[2].trim().takeIf { it.isNotBlank() }
}

internal fun buildLookupPresentation(layer: ReaderLookupLayer): List<LookupGroupedPresentation> {
    return layer.groupedResults.map { grouped ->
        LookupGroupedPresentation(
            term = grouped.term,
            reading = grouped.reading,
            groupedResult = grouped,
            dictionaries = grouped.dictionaries.map { dictionaryGroup ->
                val sectionKey = lookupDictionarySectionKey(grouped.term, dictionaryGroup.dictionary)
                val expanded = !(layer.collapsedSections[sectionKey] ?: false)
                LookupDictionaryPresentation(
                    sectionKey = sectionKey,
                    dictionaryName = dictionaryGroup.dictionary,
                    expanded = expanded,
                    mergedContent = if (expanded) {
                        buildLookupMergedDictionaryContent(
                            term = grouped.term,
                            dictionaryName = dictionaryGroup.dictionary,
                            definitions = dictionaryGroup.definitions,
                            dictionaryCss = dictionaryGroup.css,
                            highlightedDefinitionKey = layer.highlightedDefinitionKey,
                            highlightedDefinitionRects = layer.highlightedDefinitionRects
                        )
                    } else {
                        null
                    }
                )
            }
        )
    }
}

internal fun buildLookupMergedDictionaryContent(
    term: String,
    dictionaryName: String,
    definitions: List<String>,
    dictionaryCss: String?,
    highlightedDefinitionKey: String?,
    highlightedDefinitionRects: List<Rect>
): LookupMergedDictionaryContent? {
    val nonBlankDefinitions = definitions.filter { it.isNotBlank() }
    if (nonBlankDefinitions.isEmpty()) return null
    val resolvedDictionaryCss = dictionaryCss?.trim()?.takeIf { it.isNotBlank() }
    var resolvedHighlightedRects: List<Rect> = emptyList()
    val firstDefinitionKey = lookupDefinitionKey(
        term = term,
        dictionaryName = dictionaryName,
        definitionIndex = 0
    )
    val definitionHtml = buildString {
        nonBlankDefinitions.forEachIndexed { definitionIndex, definition ->
            if (definitionIndex > 0) append("<hr/>")
            val definitionKey = lookupDefinitionKey(
                term = term,
                dictionaryName = dictionaryName,
                definitionIndex = definitionIndex
            )
            if (resolvedHighlightedRects.isEmpty() && highlightedDefinitionKey == definitionKey) {
                resolvedHighlightedRects = highlightedDefinitionRects
            }
            append("<section data-definition-key=\"")
            append(definitionKey)
            append("\">")
            append(definition)
            append("</section>")
        }
    }
    return LookupMergedDictionaryContent(
        definitionHtml = definitionHtml,
        dictionaryCss = resolvedDictionaryCss,
        highlightedRects = resolvedHighlightedRects,
        firstDefinitionKey = firstDefinitionKey
    )
}
