package com.tekuza.p9player

private const val HOSHI_LOOKUP_SCAN_MAX_LENGTH = 16

private val HOSHI_LOOKUP_SCAN_DELIMITERS = setOf(
    '。', '、', '！', '？', '…', '‥', '「', '」', '『', '』',
    '（', '）', '(', ')', '【', '】', '[', ']', '〈', '〉',
    '《', '》', '〔', '〕', '｛', '｝', '{', '}', '：', ':',
    '；', ';', '，', ',', '．', '.', '・', '／', '/', '＼', '\\',
    '〜', '～', 'ー', '―', '—'
)

internal data class LookupScanSelection(
    val text: String,
    val range: IntRange
)

internal fun selectLookupScanText(
    text: String,
    charOffset: Int,
    maxLength: Int = HOSHI_LOOKUP_SCAN_MAX_LENGTH
): LookupScanSelection? {
    if (text.isBlank()) return null
    if (maxLength <= 0) return null

    val maxIndex = (text.length - 1).coerceAtLeast(0)
    val start = charOffset.coerceIn(0, maxIndex)
    if (isLookupScanBoundary(text[start])) return null

    var endExclusive = start
    var remaining = maxLength
    while (endExclusive < text.length && remaining > 0) {
        val ch = text[endExclusive]
        if (isLookupScanBoundary(ch)) break
        endExclusive += 1
        remaining -= 1
    }
    if (endExclusive <= start) return null

    return LookupScanSelection(
        text = text.substring(start, endExclusive),
        range = start until endExclusive
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

private fun isLookupScanBoundary(ch: Char): Boolean {
    return ch.isWhitespace() || HOSHI_LOOKUP_SCAN_DELIMITERS.contains(ch)
}

