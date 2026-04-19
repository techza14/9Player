package moe.tekuza.m9player

internal const val HOSHI_LOOKUP_SCAN_MAX_LENGTH = 16
private const val HOSHI_LOOKUP_SCAN_DELIMITERS =
    "。、「」『』【】〔〕（）()［］[]｛｝{}〈〉《》＜＞…？！!?：:；;，,．。/\\\n\r"

internal data class LookupScanSelection(
    val text: String,
    val range: IntRange
)

internal fun selectLookupScanText(
    text: String,
    charOffset: Int,
    maxLength: Int = HOSHI_LOOKUP_SCAN_MAX_LENGTH
): LookupScanSelection? {
    if (text.isBlank() || maxLength <= 0) return null

    val maxIndex = (text.length - 1).coerceAtLeast(0)
    val anchor = charOffset.coerceIn(0, maxIndex)
    if (isLookupScanBoundary(text[anchor])) return null

    var endExclusive = anchor
    while (endExclusive < text.length && (endExclusive - anchor) < maxLength) {
        val ch = text[endExclusive]
        if (isLookupScanBoundary(ch)) break
        if (endExclusive > anchor && isParticleBoundary(text, endExclusive)) break
        endExclusive += 1
    }
    if (endExclusive <= anchor) return null

    return LookupScanSelection(
        text = text.substring(anchor, endExclusive),
        range = anchor until endExclusive
    )
}

internal fun trimSelectionRangeByMatchedLength(
    baseRange: IntRange?,
    matchedLength: Int
): IntRange? {
    val range = baseRange ?: return null
    val start = range.first
    val fullLength = range.last - range.first + 1
    if (fullLength <= 0) return null

    val length = matchedLength.coerceAtLeast(1).coerceAtMost(fullLength)
    return start until (start + length)
}

private fun isParticleBoundary(text: String, index: Int): Boolean {
    val current = text.getOrNull(index) ?: return false
    if (current != 'の') return false
    val next = text.getOrNull(index + 1) ?: return false
    // Keep lookup focused on the clicked headword for patterns like:
    // 世界の七不思議 / 国の王族 -> 世界 / 国
    return isKanjiLike(next)
}

private fun isKanjiLike(ch: Char): Boolean {
    return ch in '\u4E00'..'\u9FFF' ||
        ch in '\u3400'..'\u4DBF' ||
        ch in '\uF900'..'\uFAFF' ||
        ch == '々' ||
        ch == '〆' ||
        ch == 'ヶ'
}

private fun isLookupScanBoundary(ch: Char): Boolean {
    return ch.isWhitespace() || HOSHI_LOOKUP_SCAN_DELIMITERS.contains(ch)
}
