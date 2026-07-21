package moe.tekuza.m9player

import android.content.Context

internal data class DictionaryImportProgress(
    val stage: String,
    val current: Int,
    val total: Int
)

internal fun localizeDictionaryImportStage(context: Context, stage: String): String {
    return when (stage) {
        "准备导入" -> context.getString(R.string.dictionary_import_stage_prepare)
        "读取辞典文件" -> context.getString(R.string.dictionary_import_stage_read)
        "分析辞典" -> context.getString(R.string.dictionary_import_stage_analyze)
        "导入辞典，可能需要几分钟" -> context.getString(R.string.dictionary_import_stage_import)
        "整理辞典" -> context.getString(R.string.dictionary_import_stage_finalize)
        "完成" -> context.getString(R.string.dictionary_import_stage_done)
        else -> stage
    }
}

internal fun formatDictionaryImportProgress(context: Context, progress: DictionaryImportProgress): Pair<String, Float?> {
    val stage = localizeDictionaryImportStage(context, progress.stage)
    val text = when {
        progress.total > 0 -> "$stage (${progress.current}/${progress.total})"
        progress.current > 0 -> "$stage (${progress.current})"
        else -> stage
    }
    val value = if (progress.total > 0 && progress.current >= 0) {
        (progress.current.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }
    return text to value
}

private enum class LookupTokenType {
    KANJI,
    HIRAGANA,
    KATAKANA,
    LATIN_DIGIT
}

internal fun tokenizeLookupTerms(text: String): List<String> {
    return buildLookupTokens(text).distinct()
}

private fun buildLookupTokens(text: String): List<String> {
    if (text.isBlank()) return emptyList()

    val tokens = mutableListOf<String>()
    var index = 0

    while (index < text.length) {
        val firstChar = text[index]
        val type = classifyLookupChar(firstChar)
        if (type == null) {
            index += 1
            continue
        }

        var end = index + 1
        while (end < text.length) {
            val nextType = classifyLookupChar(text[end]) ?: break
            if (nextType != type) break
            end += 1
        }

        val token = text.substring(index, end).trim()
        if (token.isNotBlank()) {
            tokens += token
        }
        index = end
    }

    return tokens
}

private fun classifyLookupChar(ch: Char): LookupTokenType? {
    if (ch.isWhitespace()) return null
    if (!ch.isLetterOrDigit() && ch != '々' && ch != '〆' && ch != 'ヶ' && ch != 'ー') return null

    return when {
        ch in '\u4E00'..'\u9FFF' ||
            ch in '\u3400'..'\u4DBF' ||
            ch in '\uF900'..'\uFAFF' ||
            ch == '々' ||
            ch == '〆' ||
            ch == 'ヶ' -> LookupTokenType.KANJI

        ch in '\u3040'..'\u309F' -> LookupTokenType.HIRAGANA

        ch in '\u30A0'..'\u30FF' ||
            ch in '\u31F0'..'\u31FF' ||
            ch in '\uFF66'..'\uFF9F' ||
            ch == 'ー' -> LookupTokenType.KATAKANA

        else -> LookupTokenType.LATIN_DIGIT
    }
}

