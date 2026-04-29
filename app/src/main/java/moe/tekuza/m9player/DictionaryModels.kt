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
    val matchedLength: Int = 0,
    val sourceCacheKey: String = ""
)

internal fun compareDictionarySearchPriority(
    a: DictionarySearchResult,
    b: DictionarySearchResult
): Int {
    val byMatchedLength = a.matchedLength.compareTo(b.matchedLength)
    if (byMatchedLength != 0) return byMatchedLength
    val byScore = a.score.compareTo(b.score)
    if (byScore != 0) return byScore
    val byTermLength = a.entry.term.length.compareTo(b.entry.term.length)
    if (byTermLength != 0) return byTermLength
    return b.entry.term.compareTo(a.entry.term)
}

internal fun DictionarySearchResult.isHigherPriorityThan(other: DictionarySearchResult): Boolean {
    return compareDictionarySearchPriority(this, other) > 0
}

internal data class MinedCard(
    val word: String,
    val popupSelectionText: String? = null,
    val sentence: String,
    val bookTitle: String? = null,
    val reading: String?,
    val definitions: List<String>,
    val dictionaryName: String?,
    val dictionaryCss: String?,
    val glossaryByDictionary: List<MinedDictionaryGlossary> = emptyList(),
    val pitch: String?,
    val frequency: String?,
    val cueStartMs: Long,
    val cueEndMs: Long,
    val audioUri: Uri?,
    val lookupAudioUri: Uri? = null,
    val audioTagOnly: Boolean = false,
    val requireCueAudioClip: Boolean = false
)

internal data class MinedDictionaryGlossary(
    val dictionaryName: String,
    val definitions: List<String>,
    val dictionaryCss: String? = null
)

