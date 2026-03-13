package moe.tekuza.m9player

import android.net.Uri

internal data class LoadedDictionary(
    val cacheKey: String = "",
    val name: String,
    val format: String,
    val entries: List<DictionaryEntry>,
    val stylesCss: String? = null,
    val entryCount: Int = entries.size
)

internal data class DictionaryEntry(
    val term: String,
    val reading: String?,
    val definitions: List<String>,
    val pitch: String?,
    val frequency: String?,
    val dictionary: String
)

internal data class DictionarySearchResult(
    val entry: DictionaryEntry,
    val score: Int,
    val matchedLength: Int = 0
)

internal data class MinedCard(
    val word: String,
    val popupSelectionText: String? = null,
    val sentence: String,
    val bookTitle: String? = null,
    val reading: String?,
    val definitions: List<String>,
    val dictionaryName: String?,
    val dictionaryCss: String?,
    val pitch: String?,
    val frequency: String?,
    val cueStartMs: Long,
    val cueEndMs: Long,
    val audioUri: Uri?,
    val audioTagOnly: Boolean = false,
    val requireCueAudioClip: Boolean = false
)


