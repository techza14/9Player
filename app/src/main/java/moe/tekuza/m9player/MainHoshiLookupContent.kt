package moe.tekuza.m9player

internal fun <T> findFirstLookupScanHit(
    text: String,
    lookup: (String) -> List<T>
): Pair<LookupScanSelection, List<T>>? {
    val triedRanges = mutableSetOf<IntRange>()
    for (offset in text.indices) {
        val candidate = selectLookupScanText(text, offset) ?: continue
        if (!triedRanges.add(candidate.range)) continue
        val candidateText = candidate.text.trim()
        if (candidateText.isBlank()) continue
        val results = lookup(candidateText)
        if (results.isNotEmpty()) {
            return candidate.copy(text = candidateText) to results
        }
    }
    return null
}

internal fun matchedRangeFromSentenceOffset(
    sentence: String,
    sentenceOffset: Int?,
    matchedText: String
): IntRange? {
    val start = sentenceOffset?.coerceAtLeast(0) ?: return null
    if (matchedText.isBlank() || sentence.isEmpty() || start >= sentence.length) return null
    val matchedLength = matchedText.codePointCount(0, matchedText.length).coerceAtLeast(1)
    val endExclusive = (start + matchedLength).coerceAtMost(sentence.length)
    if (endExclusive <= start) return null
    return start until endExclusive
}
