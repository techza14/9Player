package moe.tekuza.m9player

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import org.json.JSONArray

internal data class MetaBadge(val label: String, val value: String)
internal data class PitchBadgeGroup(val label: String, val reading: String?, val values: List<String>)
private data class RubyPart(val base: String, val ruby: String?)
internal data class DefinitionLookupTapData(
    val text: String,
    val sentence: String,
    val offset: Int,
    val nodeText: String,
    val screenRect: Rect?,
    val localRects: List<Rect>,
    val localCharRects: List<Rect>,
    val screenCharRects: List<Rect>
)

private class DefinitionLookupViewTag(
    val bridge: DefinitionLookupBridge
) {
    var lastHtml: String? = null
    var lastLookupEnabled: Boolean? = null
}

private const val LOOKUP_TAP_LOG_TAG = "LookupTap"

@Composable
internal fun DictionaryEntryHeader(
    dictionaryName: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpanded),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            color = Color(0xFFC09AE8),
            contentColor = Color.White,
            shape = RoundedCornerShape(6.dp)
        ) {
            Text(
                text = dictionaryName,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }
        Text(
            text = if (expanded) "▾" else "▸",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

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
    dictionaryCss: String? = null,
    onLookupTap: ((DefinitionLookupTapData) -> Unit)? = null,
    highlightedRects: List<Rect> = emptyList()
) {
    val trimmed = definition.trim()
    if (trimmed.isBlank()) return

    if (looksLikeHtmlDefinition(trimmed)) {
        val bodyTextColor = MaterialTheme.colorScheme.onSurface
        val bodyTextColorCss = remember(bodyTextColor) { colorToCssHex(bodyTextColor) }
        val lookupEnabled = onLookupTap != null
        val html = remember(trimmed, indexLabel, dictionaryName, dictionaryCss, bodyTextColorCss, lookupEnabled) {
            buildDefinitionHtml(
                definitionHtml = trimmed,
                indexLabel = indexLabel,
                dictionaryName = dictionaryName,
                dictionaryCss = dictionaryCss,
                bodyTextColorCss = bodyTextColorCss,
                enableLookupTap = lookupEnabled
            )
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { context ->
                    val bridge = DefinitionLookupBridge(onLookupTap)
                    val viewTag = DefinitionLookupViewTag(bridge)
                    WebView(context).apply {
                        tag = viewTag
                        bridge.hostView = this
                        setBackgroundColor(0x00000000)
                        overScrollMode = WebView.OVER_SCROLL_NEVER
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        isHapticFeedbackEnabled = false
                        isSoundEffectsEnabled = false
                        isLongClickable = false
                        isClickable = lookupEnabled
                        isFocusable = false
                        isFocusableInTouchMode = false
                        settings.javaScriptEnabled = lookupEnabled
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.blockNetworkLoads = true
                        settings.builtInZoomControls = false
                        settings.displayZoomControls = false
                        settings.setSupportZoom(false)
                        setOnLongClickListener { true }
                        setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        addJavascriptInterface(bridge, "NineLookup")
                    }
                },
                update = { webView ->
                    (webView.tag as? DefinitionLookupViewTag)?.apply {
                        bridge.onLookupTap = onLookupTap
                        bridge.hostView = webView
                        if (lastLookupEnabled != lookupEnabled) {
                            webView.settings.javaScriptEnabled = lookupEnabled
                            webView.isClickable = lookupEnabled
                            lastLookupEnabled = lookupEnabled
                        }
                        if (lastHtml != html) {
                            webView.loadDataWithBaseURL(
                                null,
                                html,
                                "text/html",
                                "utf-8",
                                null
                            )
                            lastHtml = html
                        }
                    }
                }
            )
            highlightedRects.forEach { rect ->
                val width = (rect.right - rect.left).coerceAtLeast(0f)
                val height = (rect.bottom - rect.top).coerceAtLeast(0f)
                if (width > 0f && height > 0f) {
                    Box(
                        modifier = Modifier
                            .absoluteOffset(
                                x = rect.left.dp,
                                y = rect.top.dp
                            )
                            .size(width.dp, height.dp)
                            .border(
                                width = 1.dp,
                                color = Color(0x8C7A7A7A),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .background(
                                color = Color(0x247A7A7A),
                                shape = RoundedCornerShape(4.dp)
                            )
                    )
                }
            }
        }
    } else {
        Text("$indexLabel${trimmed}")
    }
}

private fun buildDefinitionHtml(
    definitionHtml: String,
    indexLabel: String,
    dictionaryName: String?,
    dictionaryCss: String?,
    bodyTextColorCss: String,
    enableLookupTap: Boolean
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
    val lookupTapScript = if (!enableLookupTap) {
        ""
    } else {
        """
        <script>
        (function() {
            if (window.__nineLookupInstalled) return;
            window.__nineLookupInstalled = true;
            const scanDelimiters = '。、「」『』【】〔〕（）()［］[]｛｝{}〈〉《》＜＞…？！!?：:；;，,．。/\\\\\\n\\r';
            const maxScanLength = 16;
            function resolveCaretRange(x, y) {
                if (document.caretRangeFromPoint) {
                    return document.caretRangeFromPoint(x, y);
                }
                if (document.caretPositionFromPoint) {
                    const position = document.caretPositionFromPoint(x, y);
                    if (!position) return null;
                    const range = document.createRange();
                    range.setStart(position.offsetNode, position.offset);
                    range.setEnd(position.offsetNode, position.offset);
                    return range;
                }
                return null;
            }
            function isScanBoundary(ch) {
                return !ch || /\\s/.test(ch) || scanDelimiters.includes(ch);
            }
            function resolveSelectionEnd(text, startOffset) {
                const safeOffset = Math.max(0, Math.min(startOffset || 0, Math.max(0, text.length - 1)));
                if (!text.length || isScanBoundary(text[safeOffset])) return safeOffset;
                let endExclusive = safeOffset;
                while (endExclusive < text.length && (endExclusive - safeOffset) < maxScanLength) {
                    const ch = text[endExclusive];
                    if (isScanBoundary(ch)) break;
                    endExclusive += 1;
                }
                return endExclusive;
            }
            function pickRectForPoint(rectList, x, y) {
                if (!rectList || rectList.length === 0) return null;
                for (const rect of rectList) {
                    if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) {
                        return rect;
                    }
                }
                return rectList[0];
            }
            const sentenceDelimiters = '。！？.!?\\n\\r';
            const trailingSentenceChars = '」』）】!?！？…';
            function extractSentence(text, anchorOffset) {
                if (!text) return '';
                const safeOffset = Math.max(0, Math.min(anchorOffset || 0, Math.max(0, text.length - 1)));
                let start = 0;
                for (let i = safeOffset - 1; i >= 0; i -= 1) {
                    if (sentenceDelimiters.includes(text[i])) {
                        start = i + 1;
                        break;
                    }
                }
                let end = text.length;
                for (let i = safeOffset; i < text.length; i += 1) {
                    if (sentenceDelimiters.includes(text[i])) {
                        end = i + 1;
                        while (end < text.length && trailingSentenceChars.includes(text[end])) {
                            end += 1;
                        }
                        break;
                    }
                }
                const sentence = text.slice(start, end).trim();
                return sentence || text.trim();
            }
            document.addEventListener('click', function(e) {
                if (!window.NineLookup || !window.NineLookup.onTap) return;
                const range = resolveCaretRange(e.clientX, e.clientY);
                if (!range) return;
                const node = range.startContainer;
                if (!node || node.nodeType !== Node.TEXT_NODE) return;
                const text = node.textContent || '';
                if (!text.trim()) return;
                let targetRect = null;
                let safeOffset = 0;
                let selectionEndExclusive = 0;
                const textLength = text.length;
                if (textLength > 0) {
                    safeOffset = Math.max(0, Math.min(range.startOffset || 0, textLength - 1));
                    if (safeOffset > 0) {
                        const previousCharRange = document.createRange();
                        previousCharRange.setStart(node, safeOffset - 1);
                        previousCharRange.setEnd(node, safeOffset);
                        const previousCharRect = previousCharRange.getBoundingClientRect();
                        if (
                            previousCharRect &&
                            e.clientX >= previousCharRect.left &&
                            e.clientX <= previousCharRect.right &&
                            e.clientY >= previousCharRect.top &&
                            e.clientY <= previousCharRect.bottom
                        ) {
                            safeOffset -= 1;
                        }
                    }
                    selectionEndExclusive = resolveSelectionEnd(text, safeOffset);
                    if (selectionEndExclusive > safeOffset) {
                        const selectionRange = document.createRange();
                        selectionRange.setStart(node, safeOffset);
                        selectionRange.setEnd(node, selectionEndExclusive);
                        const rects = Array.from(selectionRange.getClientRects() || []).filter(r => r.width > 0 || r.height > 0);
                        const rect = pickRectForPoint(rects, e.clientX, e.clientY) || selectionRange.getBoundingClientRect();
                        if (rect && (rect.width > 0 || rect.height > 0)) {
                            targetRect = rect;
                        }
                    }
                    if (!targetRect) {
                        const charRange = document.createRange();
                        charRange.setStart(node, safeOffset);
                        charRange.setEnd(node, safeOffset + 1);
                        const rect = charRange.getBoundingClientRect();
                        if (rect && (rect.width > 0 || rect.height > 0)) {
                            targetRect = rect;
                        }
                    }
                }
                if (!targetRect) {
                    const rect = range.getBoundingClientRect();
                    if (rect && (rect.width > 0 || rect.height > 0)) {
                        targetRect = rect;
                    }
                }
                if (!targetRect) {
                    targetRect = {left: e.clientX || 0, top: e.clientY || 0, right: e.clientX || 0, bottom: e.clientY || 0};
                }
                const localRects = (() => {
                    if (selectionEndExclusive > safeOffset) {
                        const selectionRange = document.createRange();
                        selectionRange.setStart(node, safeOffset);
                        selectionRange.setEnd(node, selectionEndExclusive);
                        return Array.from(selectionRange.getClientRects() || [])
                            .filter(r => r.width > 0 || r.height > 0)
                            .map(r => ({ left: r.left || 0, top: r.top || 0, right: r.right || 0, bottom: r.bottom || 0 }));
                    }
                    if (targetRect) {
                        return [{ left: targetRect.left || 0, top: targetRect.top || 0, right: targetRect.right || 0, bottom: targetRect.bottom || 0 }];
                    }
                    return [];
                })();
                const charRects = (() => {
                    if (!(selectionEndExclusive > safeOffset)) return [];
                    const result = [];
                    for (let i = safeOffset; i < selectionEndExclusive; i += 1) {
                        const charRange = document.createRange();
                        charRange.setStart(node, i);
                        charRange.setEnd(node, i + 1);
                        const rect = charRange.getBoundingClientRect();
                        if (rect && (rect.width > 0 || rect.height > 0)) {
                            result.push({
                                left: rect.left || 0,
                                top: rect.top || 0,
                                right: rect.right || 0,
                                bottom: rect.bottom || 0
                            });
                        }
                    }
                    return result;
                })();
                window.NineLookup.onTap(
                    text,
                    extractSentence(text, safeOffset),
                    safeOffset,
                    node.textContent || '',
                    JSON.stringify(localRects),
                    JSON.stringify(charRects),
                    targetRect.left || 0,
                    targetRect.top || 0,
                    targetRect.right || 0,
                    targetRect.bottom || 0
                );
            }, true);
        })();
        </script>
        """.trimIndent()
    }
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
            $lookupTapScript
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

private class DefinitionLookupBridge(
    var onLookupTap: ((DefinitionLookupTapData) -> Unit)?
) {
    var hostView: WebView? = null

    @JavascriptInterface
    fun onTap(text: String?, sentence: String?, offset: Int, nodeText: String?, localRectsJson: String?, localCharRectsJson: String?, left: Float, top: Float, right: Float, bottom: Float) {
        val value = text?.trim().orEmpty()
        if (value.isBlank()) return
        val localRect = Rect(left, top, right, bottom)
        val localRects = parseRectListJson(localRectsJson).ifEmpty { listOf(localRect) }
        val localCharRects = parseRectListJson(localCharRectsJson)
        val location = IntArray(2)
        val screenRect = hostView?.let { view ->
            view.getLocationOnScreen(location)
            Rect(
                left = location[0].toFloat() + left,
                top = location[1].toFloat() + top,
                right = location[0].toFloat() + right,
                bottom = location[1].toFloat() + bottom
            )
        }
        val screenCharRects = hostView?.let { view ->
            view.getLocationOnScreen(location)
            localCharRects.map { rect ->
                Rect(
                    left = location[0].toFloat() + rect.left,
                    top = location[1].toFloat() + rect.top,
                    right = location[0].toFloat() + rect.right,
                    bottom = location[1].toFloat() + rect.bottom
                )
            }
        }.orEmpty()
        Log.d(
            LOOKUP_TAP_LOG_TAG,
            "bridge onTap text=${value.take(20)} offset=$offset hasHandler=${onLookupTap != null} localRects=${localRects.size} charRects=${localCharRects.size}"
        )
        onLookupTap?.invoke(
            DefinitionLookupTapData(
                text = value,
                sentence = sentence?.trim().orEmpty(),
                offset = offset,
                nodeText = nodeText.orEmpty(),
                screenRect = screenRect,
                localRects = localRects,
                localCharRects = localCharRects,
                screenCharRects = screenCharRects
            )
        )
    }
}

private fun parseRectListJson(json: String?): List<Rect> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    Rect(
                        left = item.optDouble("left", 0.0).toFloat(),
                        top = item.optDouble("top", 0.0).toFloat(),
                        right = item.optDouble("right", 0.0).toFloat(),
                        bottom = item.optDouble("bottom", 0.0).toFloat()
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}
