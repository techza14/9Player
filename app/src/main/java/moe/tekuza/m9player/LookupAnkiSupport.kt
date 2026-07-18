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
    glossaryFirstHtml: String? = null,
    dictionaryCss: String?,
    popupSelectionText: String? = null,
    sentenceOverride: String? = null,
    lookupTermOverride: String? = null
): AnkiExportResult {
    logDebug("AnkiExportDebug") {
        "sharedExport start termLength=${entry.term.length} dictionaryLength=${entry.dictionary.length}"
    }
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

    val exportWord = resolveLookupExportWord(
        popupSelectionText = popupSelectionText,
        lookupTermOverride = lookupTermOverride,
        entryTerm = entry.term
    )
    val card = MinedCard(
        word = exportWord,
        popupSelectionText = popupSelectionText,
        sentence = sentenceOverride ?: cueText,
        bookTitle = bookTitle,
        reading = entry.reading,
        definitions = listOf(definition),
        dictionaryName = entry.dictionary,
        dictionaryCss = dictionaryCss,
        glossaryFirstHtml = glossaryFirstHtml,
        pitch = entry.pitch,
        frequency = entry.frequency,
        cueStartMs = cueStartMs,
        cueEndMs = cueEndMs,
        audioUri = audioUri,
        lookupAudioUri = lookupAudioUri,
        audioTagOnly = true,
        requireCueAudioClip = audioUri != null
    )
    logDebug("AnkiExportDebug") {
        "sharedExport card wordLength=${card.word.length} primaryDictionaryLength=${card.dictionaryName?.length ?: 0} " +
            "glossaryDictionaryCount=${card.glossaryByDictionary.size} glossaryDefinitionCount=${card.glossaryByDictionary.sumOf { it.definitions.size }}"
    }

    return withAnkiStep("export-note") {
        exportToAnkiDroidApiResult(context, card, preparedExport.config)
    }
}

internal fun resolveLookupExportWord(
    popupSelectionText: String?,
    lookupTermOverride: String?,
    entryTerm: String
): String {
    return lookupTermOverride?.trim()?.takeIf { it.isNotBlank() }
        ?: popupSelectionText?.trim()?.takeIf { it.isNotBlank() }
        ?: entryTerm
}
