package moe.tekuza.m9player

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp

internal data class MetaBadge(val label: String, val value: String)
internal data class PitchBadgeGroup(val label: String, val reading: String?, val values: List<String>)
private data class RubyPart(val base: String, val ruby: String?)

@Composable
internal fun LookupHeadwordWithReading(
    term: String,
    reading: String?,
    modifier: Modifier = Modifier
) {
    val normalizedReading = reading?.trim().orEmpty()
    if (normalizedReading.isBlank() || !containsKanji(term)) {
        Text(
            text = term,
            modifier = modifier,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        return
    }

    val parts = remember(term, normalizedReading) {
        buildRubyParts(term = term, reading = normalizedReading)
    }
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        parts.forEach { part ->
            if (!part.ruby.isNullOrBlank()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = part.ruby,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                    Text(
                        text = part.base,
                        maxLines = 1,
                        overflow = TextOverflow.Clip
                    )
                }
            } else {
                Text(
                    text = part.base,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

private fun containsKanji(text: String): Boolean {
    return text.any { ch ->
        ch in '\u4E00'..'\u9FFF' || // CJK Unified Ideographs
            ch in '\u3400'..'\u4DBF' || // CJK Unified Ideographs Extension A
            ch in '\uF900'..'\uFAFF' // CJK Compatibility Ideographs
    }
}

private fun buildRubyParts(term: String, reading: String): List<RubyPart> {
    val firstKanji = term.indexOfFirst(::isKanjiChar)
    val lastKanji = term.indexOfLast(::isKanjiChar)
    if (firstKanji < 0 || lastKanji < 0 || firstKanji > lastKanji) {
        return listOf(RubyPart(base = term, ruby = null))
    }

    val prefix = term.substring(0, firstKanji)
    val core = term.substring(firstKanji, lastKanji + 1)
    val suffix = term.substring(lastKanji + 1)

    var coreReading = reading
    if (prefix.isNotBlank() && coreReading.startsWith(prefix)) {
        coreReading = coreReading.removePrefix(prefix)
    }
    if (suffix.isNotBlank() && coreReading.endsWith(suffix)) {
        coreReading = coreReading.removeSuffix(suffix)
    }

    val result = mutableListOf<RubyPart>()
    if (prefix.isNotEmpty()) {
        result += RubyPart(base = prefix, ruby = null)
    }

    val groups = mutableListOf<Pair<String, Boolean>>() // text, isKanjiGroup
    var current = StringBuilder()
    var currentIsKanji: Boolean? = null
    core.forEach { ch ->
        val isKanji = isKanjiChar(ch)
        if (currentIsKanji == null || currentIsKanji == isKanji) {
            current.append(ch)
            currentIsKanji = isKanji
        } else {
            resultGroup(groups, current, currentIsKanji == true)
            current = StringBuilder().append(ch)
            currentIsKanji = isKanji
        }
    }
    if (current.isNotEmpty()) {
        resultGroup(groups, current, currentIsKanji == true)
    }

    var remaining = coreReading
    groups.forEachIndexed { index, (groupText, isKanjiGroup) ->
        if (!isKanjiGroup) {
            result += RubyPart(base = groupText, ruby = null)
            if (remaining.startsWith(groupText)) {
                remaining = remaining.removePrefix(groupText)
            }
            return@forEachIndexed
        }

        val nextLiteral = groups
            .drop(index + 1)
            .firstOrNull { !it.second }
            ?.first
            .orEmpty()
        val ruby = if (nextLiteral.isNotEmpty()) {
            val markerIndex = remaining.indexOf(nextLiteral)
            if (markerIndex >= 0) {
                val value = remaining.substring(0, markerIndex)
                remaining = remaining.substring(markerIndex)
                value
            } else {
                val value = remaining
                remaining = ""
                value
            }
        } else {
            val value = remaining
            remaining = ""
            value
        }
        result += RubyPart(base = groupText, ruby = ruby.ifBlank { null })
    }

    if (suffix.isNotEmpty()) {
        result += RubyPart(base = suffix, ruby = null)
    }
    return result
}

private fun resultGroup(
    groups: MutableList<Pair<String, Boolean>>,
    builder: StringBuilder,
    isKanjiGroup: Boolean
) {
    val text = builder.toString()
    if (text.isNotEmpty()) {
        groups += text to isKanjiGroup
    }
}

private fun isKanjiChar(ch: Char): Boolean {
    return ch in '\u4E00'..'\u9FFF' || // CJK Unified Ideographs
        ch in '\u3400'..'\u4DBF' || // CJK Unified Ideographs Extension A
        ch in '\uF900'..'\uFAFF' // CJK Compatibility Ideographs
}

internal fun parseMetaBadges(raw: String?, defaultLabel: String): List<MetaBadge> {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return emptyList()
    return text
        .split(';')
        .mapNotNull { segment ->
            val part = segment.trim()
            if (part.isBlank()) return@mapNotNull null
            val separator = part.indexOf(':')
            if (separator > 0 && separator < part.lastIndex) {
                val label = part.substring(0, separator).trim()
                val value = part.substring(separator + 1).trim()
                if (label.isBlank() || value.isBlank()) {
                    MetaBadge(defaultLabel, part)
                } else {
                    MetaBadge(label, value)
                }
            } else {
                MetaBadge(defaultLabel, part)
            }
        }
}

internal fun parsePitchBadgeGroups(raw: String?, reading: String?, defaultLabel: String): List<PitchBadgeGroup> {
    return parseMetaBadges(raw, defaultLabel).mapNotNull { badge ->
        val values = extractPitchNumbers(badge.value)
        if (values.isEmpty()) return@mapNotNull null
        PitchBadgeGroup(
            label = badge.label,
            reading = reading?.takeIf { it.isNotBlank() },
            values = values
        )
    }
}

private fun extractPitchNumbers(raw: String): List<String> {
    return Regex("-?\\d+")
        .findAll(raw)
        .map { it.value }
        .toList()
}

@Composable
internal fun MetaBadgeRow(
    badges: List<MetaBadge>,
    labelColor: Color,
    labelTextColor: Color
) {
    if (badges.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        badges.forEach { badge ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Surface(
                    color = labelColor,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = badge.label,
                        color = labelTextColor,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Surface(
                    color = Color(0xFFF2F2F2),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = badge.value,
                        color = Color.Black,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun PitchBadgeRow(
    group: PitchBadgeGroup,
    labelColor: Color,
    labelTextColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            color = labelColor,
            shape = RoundedCornerShape(4.dp)
        ) {
            Text(
                text = group.label,
                color = labelTextColor,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        group.values.forEach { number ->
            Surface(
                color = Color(0xFFF2F2F2),
                shape = RoundedCornerShape(4.dp)
            ) {
                PitchValueChipContent(
                    reading = group.reading,
                    number = number
                )
            }
        }
    }
}

@Composable
private fun PitchValueChipContent(reading: String?, number: String) {
    val normalized = number.trim()
    val pitchPart = if (normalized.startsWith("[") && normalized.endsWith("]")) normalized else "[$normalized]"
    val accent = normalized.trim('[', ']').toIntOrNull()
    Row(
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val kana = reading?.trim().orEmpty()
        if (kana.isNotBlank()) {
            PitchReadingWithAccent(reading = kana, accent = accent)
        }
        Text(
            text = pitchPart,
            color = Color.Black,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun PitchReadingWithAccent(reading: String, accent: Int?) {
    val moras = remember(reading) { splitIntoMoras(reading) }
    if (moras.isEmpty() || accent == null) {
        Text(
            text = reading,
            color = Color.Black,
            style = MaterialTheme.typography.labelSmall
        )
        return
    }

    val moraCount = moras.size
    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        moras.forEachIndexed { index, mora ->
            val moraIndex = index + 1
            val high = isHighMora(moraIndex = moraIndex, moraCount = moraCount, accent = accent)
            val dropAfter = isDropAfterMora(moraIndex = moraIndex, moraCount = moraCount, accent = accent)
            Text(
                text = mora,
                color = Color.Black,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .drawBehind {
                        if (!high) return@drawBehind
                        val stroke = 1.dp.toPx()
                        val y = 1.dp.toPx()
                        drawLine(
                            color = Color.Black.copy(alpha = 0.8f),
                            start = androidx.compose.ui.geometry.Offset(0f, y),
                            end = androidx.compose.ui.geometry.Offset(size.width, y),
                            strokeWidth = stroke
                        )
                        if (dropAfter) {
                            val x = size.width - stroke / 2f
                            drawLine(
                                color = Color.Black.copy(alpha = 0.8f),
                                start = androidx.compose.ui.geometry.Offset(x, y),
                                end = androidx.compose.ui.geometry.Offset(x, size.height * 0.36f),
                                strokeWidth = stroke
                            )
                        }
                    }
            )
        }
    }
}

private fun splitIntoMoras(reading: String): List<String> {
    if (reading.isBlank()) return emptyList()
    val smallKana = setOf(
        'ゃ', 'ゅ', 'ょ', 'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ', 'ゎ', 'ゕ', 'ゖ',
        'ャ', 'ュ', 'ョ', 'ァ', 'ィ', 'ゥ', 'ェ', 'ォ', 'ヮ', 'ヵ', 'ヶ'
    )
    val out = mutableListOf<String>()
    reading.forEach { ch ->
        when {
            ch == '\u3099' || ch == '\u309A' -> {
                if (out.isNotEmpty()) {
                    out[out.lastIndex] = out.last() + ch
                } else {
                    out += ch.toString()
                }
            }
            ch in smallKana && out.isNotEmpty() -> {
                out[out.lastIndex] = out.last() + ch
            }
            else -> out += ch.toString()
        }
    }
    return out
}

private fun isHighMora(moraIndex: Int, moraCount: Int, accent: Int): Boolean {
    if (moraIndex !in 1..moraCount) return false
    return when {
        accent <= 0 -> moraIndex >= 2
        accent == 1 -> moraIndex == 1
        accent <= moraCount -> moraIndex in 2..accent
        else -> moraIndex >= 2
    }
}

private fun isDropAfterMora(moraIndex: Int, moraCount: Int, accent: Int): Boolean {
    if (accent <= 0) return false
    return accent < moraCount && moraIndex == accent
}

@Composable
internal fun RichDefinitionView(
    definition: String,
    indexLabel: String = "",
    dictionaryName: String? = null,
    dictionaryCss: String? = null
) {
    val trimmed = definition.trim()
    if (trimmed.isBlank()) return

    if (looksLikeHtmlDefinition(trimmed)) {
        val bodyTextColor = MaterialTheme.colorScheme.onSurface
        val bodyTextColorCss = remember(bodyTextColor) { colorToCssHex(bodyTextColor) }
        val html = buildDefinitionHtml(
            definitionHtml = trimmed,
            indexLabel = indexLabel,
            dictionaryName = dictionaryName,
            dictionaryCss = dictionaryCss,
            bodyTextColorCss = bodyTextColorCss
        )
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                WebView(context).apply {
                    setBackgroundColor(0x00000000)
                    settings.javaScriptEnabled = false
                    settings.domStorageEnabled = false
                    settings.loadsImagesAutomatically = true
                    webViewClient = WebViewClient()
                }
            },
            update = { webView ->
                webView.loadDataWithBaseURL(
                    null,
                    html,
                    "text/html",
                    "utf-8",
                    null
                )
            }
        )
    } else {
        Text("$indexLabel${trimmed}")
    }
}

private fun buildDefinitionHtml(
    definitionHtml: String,
    indexLabel: String,
    dictionaryName: String?,
    dictionaryCss: String?,
    bodyTextColorCss: String
): String {
    val prefix = if (indexLabel.isBlank()) "" else "<div>${escapeHtmlText(indexLabel)}</div>"
    val dictionaryLabel = dictionaryName?.trim().orEmpty()
    val wrappedBody = if (dictionaryLabel.isBlank()) {
        definitionHtml
    } else {
        val safeDictionaryLabel = escapeHtmlText(dictionaryLabel)
        val safeDictionaryAttr = escapeHtmlAttributeForHtml(dictionaryLabel)
        """
        <div class="yomitan-glossary">
            <ol>
                <li data-dictionary="$safeDictionaryAttr">
                    <i>($safeDictionaryLabel)</i> $definitionHtml
                </li>
            </ol>
        </div>
        """.trimIndent()
    }
    val customCss = buildScopedDictionaryCss(
        rawCss = dictionaryCss.orEmpty(),
        dictionaryName = dictionaryLabel
    )
    return """
        <html>
        <head>
            <meta charset="utf-8"/>
            <style>
                body { margin: 0; padding: 0; font-size: 14px; line-height: 1.4; color: $bodyTextColorCss; }
                img { max-width: 100%; height: auto; }
                .yomitan-glossary { text-align: left; }
                .yomitan-glossary ol { margin: 0; padding-left: 1.1em; }
                .yomitan-glossary li { margin: 0; }
                $customCss
            </style>
        </head>
        <body>
            $prefix
            $wrappedBody
        </body>
        </html>
    """.trimIndent()
}

private fun looksLikeHtmlDefinition(text: String): Boolean {
    return Regex("<\\s*/?\\s*[a-zA-Z][^>]*>").containsMatchIn(text)
}

private fun escapeHtmlText(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private fun escapeHtmlAttributeForHtml(value: String): String {
    return escapeHtmlText(value).replace("\"", "&quot;")
}

private fun buildScopedDictionaryCss(rawCss: String, dictionaryName: String): String {
    val trimmed = rawCss.trim()
    if (trimmed.isBlank()) return ""
    if (dictionaryName.isBlank()) return trimmed

    val dictionaryAttr = escapeCssString(dictionaryName)
    val prefix = ".yomitan-glossary [data-dictionary=\"$dictionaryAttr\"]"
    val ruleRegex = Regex("([^{}]+)\\{([^}]*)\\}")
    val scoped = ruleRegex.replace(trimmed) { match ->
        val selectors = match.groupValues[1]
        val body = match.groupValues[2]
        if (selectors.trim().startsWith("@")) return@replace match.value
        val prefixed = selectors
            .split(',')
            .map { selector ->
                val s = selector.trim()
                if (s.isBlank()) s else "$prefix $s"
            }
            .joinToString(", ")
        "$prefixed {$body}"
    }
    return buildString {
        appendLine(scoped)
        appendLine(trimmed)
    }.trim()
}

private fun escapeCssString(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

private fun colorToCssHex(color: Color): String {
    val argb = color.toArgb()
    val rgb = argb and 0x00FFFFFF
    return String.format("#%06X", rgb)
}
