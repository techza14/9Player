package moe.tekuza.m9player

import android.content.Context
import de.manhhao.hoshi.FrequencyEntry
import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.PitchEntry
import de.manhhao.hoshi.TermResult
import moe.tekuza.m9player.hoshi.dictionary.LookupEngine
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupItem
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupOptions
import moe.tekuza.m9player.hoshi.features.dictionary.createLookupPopupItem
import moe.tekuza.m9player.hoshi.features.dictionary.currentDictionaryStyles
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionData

internal class HoshiLookupSession(
    context: Context,
    private val dictionariesProvider: () -> List<LoadedDictionary>,
    private val versionProvider: () -> Long = { loadDictionaryDataVersion(context) }
) {
    private val appContext = context.applicationContext
    private var preparedVersion: Long = Long.MIN_VALUE
    private var preparedFingerprint: String = ""

    private fun fingerprint(dictionaries: List<LoadedDictionary>): String =
        dictionaries.joinToString(separator = "\n") { dictionary ->
            "${dictionary.cacheKey.trim()}\u0001${dictionary.name.trim()}\u0001${dictionary.dictionaryType.trim()}"
        }

    fun ensurePrepared(): List<LoadedDictionary> {
        val dictionaries = dictionariesProvider()
        val version = versionProvider()
        val nextFingerprint = fingerprint(dictionaries)
        if (preparedVersion != version || preparedFingerprint != nextFingerprint) {
            prepareHoshiLookupForDictionaries(appContext, dictionaries)
            preparedVersion = version
            preparedFingerprint = nextFingerprint
        }
        return dictionaries
    }

    fun lookup(query: String, maxResults: Int, scanLength: Int): List<LookupResult> {
        val dictionaries = ensurePrepared()
        if (dictionaries.isEmpty()) return emptyList()
        return orderLookupResultsByDictionaryPriority(
            results = LookupEngine.lookup(query, maxResults, scanLength),
            dictionaryPriorityByName = dictionaries.mapIndexed { index, dictionary ->
                dictionary.name to index
            }.toMap()
        )
    }

    fun dictionaryStyles(): Map<String, String> {
        if (ensurePrepared().isEmpty()) return emptyMap()
        return currentDictionaryStyles()
    }

    fun createPopup(
        selection: ReaderSelectionData,
        options: LookupPopupOptions
    ): Pair<LookupPopupItem, Int>? {
        if (ensurePrepared().isEmpty()) return null
        return createLookupPopupItem(
            selection = selection,
            options = options,
            dictionaryStyles = currentDictionaryStyles(),
            lookup = ::lookup
        )
    }
}

internal fun orderLookupResultsByDictionaryPriority(
    results: List<LookupResult>,
    dictionaryPriorityByName: Map<String, Int>
): List<LookupResult> {
    if (results.isEmpty() || dictionaryPriorityByName.isEmpty()) return results

    val ordered = results.map { result -> result.copyWithOrderedDictionaries(dictionaryPriorityByName) }
    val reordered = ArrayList<LookupResult>(ordered.size)
    var start = 0
    while (start < ordered.size) {
        val key = ordered[start].sameLookupBucketKey()
        var end = start + 1
        while (end < ordered.size && ordered[end].sameLookupBucketKey() == key) {
            end += 1
        }
        reordered += ordered.subList(start, end).sortedBy { result ->
            result.firstDictionaryPriority(dictionaryPriorityByName)
        }
        start = end
    }
    return reordered
}

private fun LookupResult.copyWithOrderedDictionaries(
    dictionaryPriorityByName: Map<String, Int>
): LookupResult {
    val sortedTerm = TermResult(
        term.expression,
        term.reading,
        term.rules,
        term.glossaries.sortedBy { glossary -> glossary.dictionaryPriority(dictionaryPriorityByName) }.toTypedArray(),
        term.frequencies.sortedBy { frequency -> frequency.dictionaryPriority(dictionaryPriorityByName) }.toTypedArray(),
        term.pitches.sortedBy { pitch -> pitch.dictionaryPriority(dictionaryPriorityByName) }.toTypedArray(),
    )
    return LookupResult(
        matched = matched,
        deinflected = deinflected,
        process = process,
        term = sortedTerm,
        preprocessorSteps = preprocessorSteps,
    )
}

private fun LookupResult.sameLookupBucketKey(): String =
    listOf(matched, deinflected, term.expression, term.reading, term.rules).joinToString(separator = "\u0001")

private fun LookupResult.firstDictionaryPriority(dictionaryPriorityByName: Map<String, Int>): Int =
    sequenceOf(
        term.glossaries.asSequence().map { it.dictionaryPriority(dictionaryPriorityByName) },
        term.frequencies.asSequence().map { it.dictionaryPriority(dictionaryPriorityByName) },
        term.pitches.asSequence().map { it.dictionaryPriority(dictionaryPriorityByName) },
    ).flatten().minOrNull() ?: Int.MAX_VALUE

private fun GlossaryEntry.dictionaryPriority(dictionaryPriorityByName: Map<String, Int>): Int =
    dictionaryPriorityByName[dictName] ?: Int.MAX_VALUE

private fun FrequencyEntry.dictionaryPriority(dictionaryPriorityByName: Map<String, Int>): Int =
    dictionaryPriorityByName[dictName] ?: Int.MAX_VALUE

private fun PitchEntry.dictionaryPriority(dictionaryPriorityByName: Map<String, Int>): Int =
    dictionaryPriorityByName[dictName] ?: Int.MAX_VALUE
