package moe.tekuza.m9player

import androidx.compose.ui.geometry.Rect

internal data class LookupDefinitionPresentation(
    val definitionKey: String,
    val definitionHtml: String,
    val dictionaryCss: String?,
    val highlightedRects: List<Rect>
)

internal data class LookupDictionaryPresentation(
    val sectionKey: String,
    val dictionaryName: String,
    val expanded: Boolean,
    val definitions: List<LookupDefinitionPresentation>
)

internal data class LookupGroupedPresentation(
    val term: String,
    val reading: String?,
    val groupedResult: GroupedLookupResult,
    val dictionaries: List<LookupDictionaryPresentation>
)

internal fun lookupDictionarySectionKey(term: String, dictionaryName: String): String {
    return "lookup|$term|$dictionaryName"
}

internal fun lookupDefinitionKey(term: String, dictionaryName: String, definitionIndex: Int): String {
    return "${lookupDictionarySectionKey(term, dictionaryName)}|$definitionIndex"
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
                    definitions = if (expanded) {
                        dictionaryGroup.definitions.mapIndexed { definitionIndex, definition ->
                            val definitionKey = lookupDefinitionKey(
                                term = grouped.term,
                                dictionaryName = dictionaryGroup.dictionary,
                                definitionIndex = definitionIndex
                            )
                            LookupDefinitionPresentation(
                                definitionKey = definitionKey,
                                definitionHtml = definition,
                                dictionaryCss = dictionaryGroup.css,
                                highlightedRects = if (layer.highlightedDefinitionKey == definitionKey) {
                                    layer.highlightedDefinitionRects
                                } else {
                                    emptyList()
                                }
                            )
                        }
                    } else {
                        emptyList()
                    }
                )
            }
        )
    }
}
