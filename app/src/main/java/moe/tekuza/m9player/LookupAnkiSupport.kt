package moe.tekuza.m9player

import android.content.Context
import android.net.Uri

internal fun addLookupDefinitionToAnkiShared(
    context: Context,
    cueText: String,
    cueStartMs: Long,
    cueEndMs: Long,
    audioUri: Uri?,
    lookupAudioUri: Uri?,
    bookTitle: String?,
    entry: DictionaryEntry,
    definition: String,
    dictionaryCss: String?,
    groupedDictionaries: List<GroupedLookupDictionary> = emptyList(),
    popupSelectionText: String? = null,
    sentenceOverride: String? = null
): AnkiExportResult {
    android.util.Log.d(
        "AnkiExportDebug",
        "sharedExport start term=${entry.term} dict=${entry.dictionary} groupedCount=${groupedDictionaries.size} grouped=${groupedDictionaries.joinToString("|") { it.dictionary }}"
    )
    val persistedConfig = withAnkiStep("load-config") {
        loadPersistedAnkiConfig(context)
    }
    val preparedExport = withAnkiStep("prepare-export") {
        prepareAnkiExportResult(
            context = context,
            persistedConfig = persistedConfig,
            audioUri = audioUri,
            lookupAudioUri = lookupAudioUri
        )
    }.getOrElse { error ->
        return classifyAnkiExportFailure(context, error)
    }

    val card = MinedCard(
        word = entry.term,
        popupSelectionText = popupSelectionText,
        sentence = sentenceOverride ?: cueText,
        bookTitle = bookTitle,
        reading = entry.reading,
        definitions = listOf(definition),
        dictionaryName = entry.dictionary,
        dictionaryCss = dictionaryCss,
        glossaryByDictionary = groupedDictionaries
            .map { dictionaryGroup ->
                MinedDictionaryGlossary(
                    dictionaryName = dictionaryGroup.dictionary,
                    definitions = dictionaryGroup.definitions,
                    dictionaryCss = dictionaryGroup.css
                )
            }
            .filter { it.dictionaryName.isNotBlank() && it.definitions.isNotEmpty() },
        pitch = entry.pitch,
        frequency = entry.frequency,
        cueStartMs = cueStartMs,
        cueEndMs = cueEndMs,
        audioUri = audioUri,
        lookupAudioUri = lookupAudioUri,
        audioTagOnly = true,
        requireCueAudioClip = audioUri != null
    )
    android.util.Log.d(
        "AnkiExportDebug",
        "sharedExport card word=${card.word} primaryDict=${card.dictionaryName.orEmpty()} glossaryByDict=${card.glossaryByDictionary.joinToString("|") { "${it.dictionaryName}:${it.definitions.size}" }}"
    )

    return withAnkiStep("export-note") {
        exportToAnkiDroidApiResult(context, card, preparedExport.config)
    }
}
