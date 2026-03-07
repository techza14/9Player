package com.tekuza.p9player

import java.util.Locale

internal data class GroupedLookupDictionary(
    val dictionary: String,
    val score: Int,
    val pitch: String?,
    val frequency: String?,
    val definitions: List<String>,
    val css: String?,
    val entry: DictionaryEntry
)

internal data class GroupedLookupResult(
    val term: String,
    val reading: String?,
    val score: Int,
    val dictionaries: List<GroupedLookupDictionary>
)

private data class MutableGroupedLookupDictionary(
    val dictionary: String,
    var score: Int,
    var pitch: String?,
    var frequency: String?,
    var css: String?,
    val definitions: LinkedHashSet<String>
)

private data class MutableGroupedLookupResult(
    val term: String,
    var reading: String?,
    var score: Int,
    val dictionaries: LinkedHashMap<String, MutableGroupedLookupDictionary>
)

internal fun groupLookupResultsByTerm(
    results: List<DictionarySearchResult>,
    dictionaryCssByName: Map<String, String?> = emptyMap(),
    dictionaryPriorityByName: Map<String, Int> = emptyMap()
): List<GroupedLookupResult> {
    if (results.isEmpty()) return emptyList()

    val grouped = linkedMapOf<String, MutableGroupedLookupResult>()

    results.forEach { hit ->
        val entry = hit.entry
        val term = entry.term.trim()
        if (term.isBlank()) return@forEach

        val termKey = term.lowercase(Locale.ROOT)
        val termGroup = grouped.getOrPut(termKey) {
            MutableGroupedLookupResult(
                term = term,
                reading = entry.reading?.trim()?.takeIf { it.isNotBlank() },
                score = hit.score,
                dictionaries = linkedMapOf()
            )
        }
        if (termGroup.reading.isNullOrBlank()) {
            termGroup.reading = entry.reading?.trim()?.takeIf { it.isNotBlank() }
        }
        termGroup.score = maxOf(termGroup.score, hit.score)

        val dictionaryName = entry.dictionary.trim().ifBlank { "Dictionary" }
        val dictionaryGroup = termGroup.dictionaries.getOrPut(dictionaryName) {
            MutableGroupedLookupDictionary(
                dictionary = dictionaryName,
                score = hit.score,
                pitch = entry.pitch,
                frequency = entry.frequency,
                css = dictionaryCssByName[dictionaryName],
                definitions = linkedSetOf()
            )
        }
        dictionaryGroup.score = maxOf(dictionaryGroup.score, hit.score)
        if (dictionaryGroup.pitch.isNullOrBlank() && !entry.pitch.isNullOrBlank()) {
            dictionaryGroup.pitch = entry.pitch
        }
        if (dictionaryGroup.frequency.isNullOrBlank() && !entry.frequency.isNullOrBlank()) {
            dictionaryGroup.frequency = entry.frequency
        }
        if (dictionaryGroup.css.isNullOrBlank()) {
            dictionaryGroup.css = dictionaryCssByName[dictionaryName]
        }
        entry.definitions
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { dictionaryGroup.definitions += it }
    }

    return grouped.values
        .map { termGroup ->
            val dictionaryGroups = termGroup.dictionaries.values
                .map { dictionaryGroup ->
                    val definitions = dictionaryGroup.definitions.toList()
                    GroupedLookupDictionary(
                        dictionary = dictionaryGroup.dictionary,
                        score = dictionaryGroup.score,
                        pitch = dictionaryGroup.pitch,
                        frequency = dictionaryGroup.frequency,
                        definitions = definitions,
                        css = dictionaryGroup.css,
                        entry = DictionaryEntry(
                            term = termGroup.term,
                            reading = termGroup.reading,
                            definitions = definitions,
                            pitch = dictionaryGroup.pitch,
                            frequency = dictionaryGroup.frequency,
                            dictionary = dictionaryGroup.dictionary
                        )
                    )
                }
                .sortedWith(
                    compareBy<GroupedLookupDictionary> { dictionaryPriorityByName[it.dictionary] ?: Int.MAX_VALUE }
                        .thenByDescending { it.score }
                        .thenBy { it.dictionary }
                )

            GroupedLookupResult(
                term = termGroup.term,
                reading = termGroup.reading,
                score = termGroup.score,
                dictionaries = dictionaryGroups
            )
        }
        .sortedWith(
            compareByDescending<GroupedLookupResult> { it.score }
                .thenBy { it.term.length }
                .thenBy { it.term }
        )
}

internal fun buildGroupedGlossarySections(result: GroupedLookupResult): List<String> {
    return result.dictionaries.mapNotNull { dictionaryGroup ->
        val defs = dictionaryGroup.definitions
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (defs.isEmpty()) return@mapNotNull null

        val label = escapeGlossaryHtmlText(dictionaryGroup.dictionary)
        val labelAttr = escapeGlossaryHtmlAttribute(dictionaryGroup.dictionary)
        val body = defs.joinToString("<br>")
        val scopedCss = dictionaryGroup.css
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { rawCss ->
                scopeDictionaryCssForGlossary(
                    rawCss = rawCss,
                    dictionaryName = dictionaryGroup.dictionary
                ).trim()
            }
            ?.takeIf { it.isNotBlank() }
        val styleBlock = scopedCss?.let { "<style>$it</style>" }.orEmpty()
        "<li data-dictionary=\"$labelAttr\"><i>($label)</i> <span>$body</span></li>$styleBlock"
    }
}

private fun escapeGlossaryHtmlText(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private fun escapeGlossaryHtmlAttribute(value: String): String {
    return escapeGlossaryHtmlText(value).replace("\"", "&quot;")
}

private fun escapeGlossaryCssString(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

private fun scopeDictionaryCssForGlossary(rawCss: String, dictionaryName: String): String {
    val trimmed = rawCss.trim()
    if (trimmed.isBlank()) return ""
    if (dictionaryName.isBlank()) return trimmed

    val dictionaryAttr = escapeGlossaryCssString(dictionaryName)
    val prefix = ".yomitan-glossary [data-dictionary=\"$dictionaryAttr\"]"
    val ruleRegex = Regex("([^{}]+)\\{([^}]*)\\}")
    return ruleRegex.replace(trimmed) { match ->
        val selectors = match.groupValues[1]
        val body = match.groupValues[2]
        if (selectors.trim().startsWith("@")) return@replace match.value
        val prefixed = selectors
            .split(',')
            .map { selector ->
                val s = selector.trim()
                if (s.isBlank()) s else "$prefix $s"
            }
            .joinToString(", ")
        "$prefixed {$body}"
    }
}

