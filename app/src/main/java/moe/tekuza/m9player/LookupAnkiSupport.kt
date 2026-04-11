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
    popupSelectionText: String? = null,
    sentenceOverride: String? = null
): AnkiExportResult {
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
        pitch = entry.pitch,
        frequency = entry.frequency,
        cueStartMs = cueStartMs,
        cueEndMs = cueEndMs,
        audioUri = audioUri,
        lookupAudioUri = lookupAudioUri,
        audioTagOnly = true,
        requireCueAudioClip = audioUri != null
    )

    return withAnkiStep("export-note") {
        exportToAnkiDroidApiResult(context, card, preparedExport.config)
    }
}
