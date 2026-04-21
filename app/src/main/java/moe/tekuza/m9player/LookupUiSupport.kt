package moe.tekuza.m9player

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.view.MotionEvent
import android.view.View
import android.util.Log
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.util.Locale

internal data class MetaBadge(val label: String, val value: String)
internal data class PitchBadgeGroup(val label: String, val reading: String?, val values: List<String>)
private data class RubyPart(val base: String, val ruby: String?)
internal data class DefinitionLookupTapData(
    val text: String,
    val scanText: String,
    val tapSource: String,
    val sentence: String,
    val offset: Int,
    val nodeText: String,
    val nodePathJson: String,
    val hostView: WebView?,
    val screenRect: Rect?,
    val localRects: List<Rect>,
    val localCharRects: List<Rect>,
    val screenCharRects: List<Rect>
)

private const val BOOK_LOOKUP_TAP_LOG_TAG = "BookLookupTap"

private class DefinitionLookupViewTag(
    val bridge: DefinitionLookupBridge
) {
    var lastHtml: String? = null
    var lastLookupEnabled: Boolean? = null
}


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
    val context = LocalContext.current
    var previewImageSrc by remember(trimmed) { mutableStateOf<String?>(null) }

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
                    val bridge = DefinitionLookupBridge(
                        onLookupTap = onLookupTap,
                        onImageTap = { src -> previewImageSrc = src }
                    )
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
                        settings.allowFileAccess = true
                        settings.allowFileAccessFromFileURLs = true
                        settings.allowUniversalAccessFromFileURLs = true
                        settings.allowContentAccess = false
                        settings.blockNetworkLoads = true
                        settings.builtInZoomControls = false
                        settings.displayZoomControls = false
                        settings.setSupportZoom(false)
                        webViewClient = object : WebViewClient() {
                            fun openExternalUrl(raw: String): Boolean {
                                val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
                                val scheme = uri.scheme?.lowercase() ?: return false
                                if (scheme !in setOf("http", "https", "mailto", "tel")) return false
                                val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                return runCatching {
                                    context.startActivity(intent)
                                }.isSuccess
                            }

                            fun dispatchEntryUrlTap(raw: String, host: WebView?): Boolean {
                                val parsed = runCatching { Uri.parse(raw) }.getOrNull() ?: return false
                                if (!parsed.scheme.equals("entry", ignoreCase = true)) return false
                                val encoded = parsed.schemeSpecificPart.orEmpty().removePrefix("//")
                                val target = Uri.decode(encoded).trim().ifBlank { return false }
                                val safeHost = host ?: this@apply
                                val right = safeHost.width.toFloat().takeIf { it > 0f } ?: 1f
                                val bottom = safeHost.height.toFloat().takeIf { it > 0f } ?: 1f
                                val localRect = Rect(0f, 0f, right, bottom)
                                val callback = bridge.onLookupTap
                                if (callback == null) {
                                    Log.d(BOOK_LOOKUP_TAP_LOG_TAG, "native entry dispatch skipped callback_null target=$target")
                                    return true
                                }
                                callback.invoke(
                                    DefinitionLookupTapData(
                                        text = target,
                                        scanText = target,
                                        tapSource = "entry",
                                        sentence = target,
                                        offset = 0,
                                        nodeText = target,
                                        nodePathJson = "[]",
                                        hostView = safeHost,
                                        screenRect = null,
                                        localRects = listOf(localRect),
                                        localCharRects = listOf(localRect),
                                        screenCharRects = emptyList()
                                    )
                                )
                                Log.d(BOOK_LOOKUP_TAP_LOG_TAG, "native entry dispatch target=$target")
                                return true
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                val uri = request?.url ?: return null
                                val bundled = openBundledDictionaryResource(context, uri)
                                if (bundled != null) {
                                    return WebResourceResponse(bundled.mimeType, null, bundled.inputStream)
                                }
                                val resource = openMountedMdictResource(context, uri) ?: return null
                                return WebResourceResponse(resource.mimeType, null, resource.inputStream)
                            }

                            override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? {
                                val uri = runCatching { Uri.parse(url.orEmpty()) }.getOrNull() ?: return null
                                val bundled = openBundledDictionaryResource(context, uri)
                                if (bundled != null) {
                                    return WebResourceResponse(bundled.mimeType, null, bundled.inputStream)
                                }
                                val resource = openMountedMdictResource(context, uri) ?: return null
                                return WebResourceResponse(resource.mimeType, null, resource.inputStream)
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val uri = request?.url ?: return false
                                if (uri.scheme.equals("entry", ignoreCase = true)) {
                                    Log.d(BOOK_LOOKUP_TAP_LOG_TAG, "block entry navigation uri=$uri")
                                    dispatchEntryUrlTap(uri.toString(), view)
                                    return true
                                }
                                if (openExternalUrl(uri.toString())) return true
                                return false
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                val raw = url?.trim().orEmpty()
                                if (raw.startsWith("entry://", ignoreCase = true)) {
                                    Log.d(BOOK_LOOKUP_TAP_LOG_TAG, "block entry navigation uri=$raw")
                                    dispatchEntryUrlTap(raw, view)
                                    return true
                                }
                                if (openExternalUrl(raw)) return true
                                return false
                            }
                        }
                        setOnLongClickListener { true }
                        setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        setOnTouchListener(object : View.OnTouchListener {
                            private var downX = 0f
                            private var downY = 0f
                            private var moved = false
                            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                                if (!lookupEnabled || event == null) return false
                                when (event.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        downX = event.x
                                        downY = event.y
                                        moved = false
                                    }
                                    MotionEvent.ACTION_MOVE -> {
                                        if (
                                            kotlin.math.abs(event.x - downX) > 14f ||
                                            kotlin.math.abs(event.y - downY) > 14f
                                        ) {
                                            moved = true
                                        }
                                    }
                                    MotionEvent.ACTION_UP -> {
                                        if (!moved) {
                                            val effectiveScale = this@apply.scale.takeIf { it.isFinite() && it > 0f } ?: 1f
                                            val clientX = event.x / effectiveScale
                                            val clientY = event.y / effectiveScale
                                            evaluateJavascript(
                                                "(function(){try{if(!window.__nineLookupHandleTap){return 'missing_handle';} window.__nineLookupHandleTap($clientX,$clientY,true); return 'ok';}catch(e){return 'error:' + (e && e.message ? e.message : 'unknown');}})();"
                                            ) { result ->
                                                val decodedResult = decodeEvaluateJavascriptJson(result) ?: result.orEmpty()
                                                if (decodedResult != "ok") {
                                                    Log.d(
                                                        BOOK_LOOKUP_TAP_LOG_TAG,
                                                        "native jsResult=$decodedResult x=$clientX y=$clientY"
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                // Keep tap-to-lookup, but allow native WebView scroll/drag.
                                return false
                            }
                        })
                        addJavascriptInterface(bridge, "NineLookup")
                    }
                },
                update = { webView ->
                    (webView.tag as? DefinitionLookupViewTag)?.apply {
                        bridge.onLookupTap = onLookupTap
                        bridge.onImageTap = { src -> previewImageSrc = src }
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
        val activePreviewSrc = previewImageSrc
        if (!activePreviewSrc.isNullOrBlank()) {
            val previewBitmap = produceState<ImageBitmap?>(initialValue = null, key1 = activePreviewSrc) {
                value = withContext(Dispatchers.IO) {
                    decodePreviewImage(context, activePreviewSrc)
                }
            }.value
            Dialog(
                onDismissRequest = { previewImageSrc = null },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xE6000000))
                        .clickable { previewImageSrc = null },
                    contentAlignment = Alignment.Center
                ) {
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                                .clickable { previewImageSrc = null },
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }
    } else {
        Text("$indexLabel${trimmed}")
    }
}

internal fun buildDefinitionHtml(
    definitionHtml: String,
    indexLabel: String,
    dictionaryName: String?,
    dictionaryCss: String?,
    bodyTextColorCss: String,
    enableLookupTap: Boolean
): String {
    val prefix = if (indexLabel.isBlank()) "" else "<div>${escapeHtmlText(indexLabel)}</div>"
    val dictionaryLabel = dictionaryName?.trim().orEmpty()
    val resolvedDictionaryAttr = dictionaryLabel.ifBlank { "__default__" }
    val safeDictionaryLabel = escapeHtmlText(dictionaryLabel)
    val safeDictionaryAttr = escapeHtmlAttributeForHtml(resolvedDictionaryAttr)
    val leadingLabel = if (dictionaryLabel.isBlank()) "" else "<i>($safeDictionaryLabel)</i> "
    val wrappedBody = """
        <div class="yomitan-glossary">
            <ol>
                <li data-dictionary="$safeDictionaryAttr">
                    $leadingLabel$definitionHtml
                </li>
            </ol>
        </div>
    """.trimIndent()
    val customCss = buildScopedDictionaryCss(
        rawCss = dictionaryCss.orEmpty(),
        dictionaryName = resolvedDictionaryAttr
    )
    logLookupRenderDebug(
        dictionaryName = dictionaryLabel,
        definitionHtml = definitionHtml,
        customCss = customCss
    )
    val lookupTapScript = if (!enableLookupTap) {
        ""
    } else {
        """
        <script>
        (function() {
            if (window.__nineLookupInstalled) return;
            window.__nineLookupInstalled = true;
            if (window.NineLookup && window.NineLookup.onDebug) {
                window.NineLookup.onDebug('installed');
            }
            const scanDelimiters = '。、「」『』【】〔〕（）()［］[]｛｝{}〈〉《》＜＞…？！!?：:；;，,．。/\\\\\\n\\r';
            const maxScanLength = 16;
            function isFuriganaNode(node) {
                const el = node && node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
                return !!(el && el.closest && el.closest('rt, rp'));
            }
            function pointInsideRect(rect, x, y) {
                return rect && x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
            }
            function resolveCaretRange(x, y) {
                if (document.caretRangeFromPoint) {
                    const range = document.caretRangeFromPoint(x, y);
                    if (range) return range;
                }
                if (document.caretPositionFromPoint) {
                    const position = document.caretPositionFromPoint(x, y);
                    if (position) {
                        const range = document.createRange();
                        range.setStart(position.offsetNode, position.offset);
                        range.setEnd(position.offsetNode, position.offset);
                        return range;
                    }
                }
                const element = document.elementFromPoint(x, y);
                if (!element) return null;
                const root = element.closest('p, div, span, ruby, a, li, td, th, body') || document.body;
                const walker = document.createTreeWalker(
                    root,
                    NodeFilter.SHOW_TEXT,
                    {
                        acceptNode: function(n) {
                            if (!n || !n.textContent || !n.textContent.trim()) return NodeFilter.FILTER_REJECT;
                            return isFuriganaNode(n) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
                        }
                    }
                );
                const probe = document.createRange();
                let node = null;
                while ((node = walker.nextNode())) {
                    const text = node.textContent || '';
                    for (let i = 0; i < text.length; i += 1) {
                        probe.setStart(node, i);
                        probe.setEnd(node, Math.min(i + 1, text.length));
                        const rects = Array.from(probe.getClientRects() || []);
                        if (rects.some(r => pointInsideRect(r, x, y))) {
                            const range = document.createRange();
                            range.setStart(node, i);
                            range.setEnd(node, i);
                            return range;
                        }
                    }
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
            function resolveOffsetByPointInTextNode(node, fallbackOffset, x, y) {
                const text = node && node.textContent ? node.textContent : '';
                if (!text.length) return 0;
                const maxIndex = Math.max(0, text.length - 1);
                let bestOffset = Math.max(0, Math.min(fallbackOffset || 0, maxIndex));
                let bestDistance = Number.POSITIVE_INFINITY;
                for (let i = 0; i < text.length; i += 1) {
                    const charRange = document.createRange();
                    charRange.setStart(node, i);
                    charRange.setEnd(node, Math.min(i + 1, text.length));
                    const rect = charRange.getBoundingClientRect();
                    if (!rect || (!rect.width && !rect.height)) continue;
                    if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) {
                        return i;
                    }
                    const cx = (rect.left + rect.right) / 2;
                    const cy = (rect.top + rect.bottom) / 2;
                    const dx = cx - x;
                    const dy = cy - y;
                    const dist = dx * dx + dy * dy;
                    if (dist < bestDistance) {
                        bestDistance = dist;
                        bestOffset = i;
                    }
                }
                return bestOffset;
            }
            function getNodePath(node) {
                const path = [];
                let current = node;
                while (current && current !== document.body) {
                    const parent = current.parentNode;
                    if (!parent) break;
                    path.unshift(Array.prototype.indexOf.call(parent.childNodes, current));
                    current = parent;
                }
                return path;
            }
            function resolveNodePath(path) {
                let current = document.body;
                if (!Array.isArray(path)) return null;
                for (const index of path) {
                    if (!current || !current.childNodes || index < 0 || index >= current.childNodes.length) {
                        return null;
                    }
                    current = current.childNodes[index];
                }
                return current;
            }
            function createTextWalker() {
                return document.createTreeWalker(
                    document.body,
                    NodeFilter.SHOW_TEXT,
                    {
                        acceptNode(node) {
                            const text = node && node.textContent;
                            return text && text.length > 0
                                ? NodeFilter.FILTER_ACCEPT
                                : NodeFilter.FILTER_REJECT;
                        }
                    }
                );
            }
            function getNextTextNode(node) {
                const walker = createTextWalker();
                let current = walker.nextNode();
                let found = false;
                while (current) {
                    if (found) return current;
                    if (current === node) found = true;
                    current = walker.nextNode();
                }
                return null;
            }
            function collectLookupCharacters(startNode, startOffset, maxChars, stopAtBoundary) {
                if (!startNode || startNode.nodeType !== Node.TEXT_NODE) {
                    return { text: '', charRects: [], localRects: [] };
                }
                let node = startNode;
                let offset = startOffset;
                let remaining = Math.max(1, maxChars || 1);
                const textParts = [];
                const charRects = [];
                while (node && remaining > 0) {
                    const text = node.textContent || '';
                    for (let i = offset; i < text.length && remaining > 0; i += 1) {
                        const ch = text[i];
                        if (stopAtBoundary && isScanBoundary(ch)) {
                            remaining = 0;
                            break;
                        }
                        textParts.push(ch);
                        const charRange = document.createRange();
                        charRange.setStart(node, i);
                        charRange.setEnd(node, Math.min(i + 1, text.length));
                        const rect = charRange.getBoundingClientRect();
                        if (rect && (rect.width > 0 || rect.height > 0)) {
                            charRects.push({
                                left: rect.left || 0,
                                top: rect.top || 0,
                                right: rect.right || 0,
                                bottom: rect.bottom || 0
                            });
                        }
                        remaining -= 1;
                    }
                    node = getNextTextNode(node);
                    offset = 0;
                }
                return {
                    text: textParts.join(''),
                    charRects,
                    localRects: mergeRectsByLine(charRects)
                };
            }
            function mergeRectsByLine(rects) {
                if (!rects || rects.length === 0) return [];
                const sorted = rects.slice().sort((a, b) => {
                    if (a.top !== b.top) return a.top - b.top;
                    return a.left - b.left;
                });
                const result = [];
                const verticalTolerance = 2;
                let current = Object.assign({}, sorted[0]);
                for (let i = 1; i < sorted.length; i += 1) {
                    const rect = sorted[i];
                    const sameLine =
                        Math.abs(rect.top - current.top) <= verticalTolerance &&
                        Math.abs(rect.bottom - current.bottom) <= verticalTolerance;
                    if (sameLine) {
                        current = {
                            left: Math.min(current.left, rect.left),
                            top: Math.min(current.top, rect.top),
                            right: Math.max(current.right, rect.right),
                            bottom: Math.max(current.bottom, rect.bottom)
                        };
                    } else {
                        result.push(current);
                        current = Object.assign({}, rect);
                    }
                }
                result.push(current);
                return result;
            }
            function collectLookupSegments(startNode, startOffset, maxChars, stopAtBoundary) {
                if (!startNode || startNode.nodeType !== Node.TEXT_NODE) return [];
                let node = startNode;
                let offset = startOffset;
                let remaining = Math.max(1, maxChars || 1);
                const segments = [];
                while (node && remaining > 0) {
                    const text = node.textContent || '';
                    let segmentStart = -1;
                    let segmentEnd = -1;
                    for (let i = offset; i < text.length && remaining > 0; i += 1) {
                        const ch = text[i];
                        if (stopAtBoundary && isScanBoundary(ch)) {
                            remaining = 0;
                            break;
                        }
                        if (segmentStart < 0) segmentStart = i;
                        segmentEnd = i + 1;
                        remaining -= 1;
                    }
                    if (segmentStart >= 0 && segmentEnd > segmentStart) {
                        segments.push({
                            node,
                            start: segmentStart,
                            end: segmentEnd
                        });
                    }
                    node = getNextTextNode(node);
                    offset = 0;
                }
                return segments;
            }
            const sentenceDelimiters = '。！？.!?\\n\\r';
            const trailingSentenceChars = '」』）】!?！？…';
            let touchStartX = 0;
            let touchStartY = 0;
            let touchMoved = false;
            let suppressClickUntil = 0;
            const touchMoveThreshold = 18;
            const suppressClickDurationMs = 140;
            document.addEventListener('touchstart', function(e) {
                const t = e.touches && e.touches[0];
                if (!t) return;
                touchStartX = t.clientX;
                touchStartY = t.clientY;
                touchMoved = false;
            }, true);
            document.addEventListener('touchmove', function(e) {
                const t = e.touches && e.touches[0];
                if (!t) return;
                if (
                    Math.abs((t.clientX || 0) - touchStartX) > touchMoveThreshold ||
                    Math.abs((t.clientY || 0) - touchStartY) > touchMoveThreshold
                ) {
                    touchMoved = true;
                }
            }, { capture: true, passive: true });
            let lastTapX = -1;
            let lastTapY = -1;
            let lastTapTs = 0;
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
            function findAnchorWithHref(target) {
                if (!target || !target.closest) return null;
                const anchor = target.closest('a[href]');
                return anchor || null;
            }
            function findImageTarget(target) {
                if (!target || !target.closest) return null;
                const image = target.closest('img[src]');
                return image || null;
            }
            function dispatchImageTap(image) {
                if (!image || !window.NineLookup || !window.NineLookup.onImageTap) return false;
                const src = (image.getAttribute('src') || '').trim();
                if (!src) return false;
                window.NineLookup.onImageTap(src);
                if (window.NineLookup.onDebug) {
                    window.NineLookup.onDebug('image tap src=' + src);
                }
                return true;
            }
            function isEntryHref(href) {
                return /^entry:/i.test(String(href || '').trim());
            }
            function dispatchAnchorTap(anchor, clientX, clientY) {
                if (!anchor) return false;
                const href = (anchor.getAttribute('href') || '').trim();
                if (!href) return false;
                if (isEntryHref(href)) {
                    return dispatchEntryTap(anchor, clientX, clientY);
                }
                if (window.NineLookup && window.NineLookup.onOpenExternalUrl) {
                    window.NineLookup.onOpenExternalUrl(href);
                    if (window.NineLookup.onDebug) {
                        window.NineLookup.onDebug('external link tap=' + href);
                    }
                    return true;
                }
                return false;
            }
            function normalizeEntryScanText(raw) {
                if (!raw) return '';
                let text = String(raw).replace(/\s+/g, '').trim();
                if (!text) return '';
                const bracketMatch = text.match(/[（(【〖\[]([^）)】〗\]]+)[）)】〗\]]/);
                if (bracketMatch && bracketMatch[1]) {
                    const inside = String(bracketMatch[1]).trim();
                    const insideCjkRuns = inside.match(/[\u3040-\u30FF\u3400-\u9FFF々ー]+/g) || [];
                    if (insideCjkRuns.length > 0) {
                        insideCjkRuns.sort((a, b) => b.length - a.length);
                        const preferred = insideCjkRuns.find(v => /[\u3400-\u9FFF々]/.test(v)) || insideCjkRuns[0];
                        if (preferred) return preferred;
                    }
                }
                const hardStop = text.search(/[@#]/);
                if (hardStop > 0) {
                    text = text.slice(0, hardStop).trim();
                }
                const bracketStop = text.search(/[〖【\[\(（]/);
                if (bracketStop > 0) {
                    text = text.slice(0, bracketStop).trim();
                }
                const cjkRuns = text.match(/[\u3040-\u30FF\u3400-\u9FFF々ー]+/g) || [];
                if (cjkRuns.length > 0) {
                    cjkRuns.sort((a, b) => b.length - a.length);
                    return cjkRuns[0] || text;
                }
                return text;
            }
            function dispatchEntryTap(anchor, clientX, clientY) {
                if (!window.NineLookup || !window.NineLookup.onTap || !anchor) return false;
                const rect = anchor.getBoundingClientRect();
                if (!rect) return false;
                const left = rect.left || clientX || 0;
                const top = rect.top || clientY || 0;
                const right = rect.right || left;
                const bottom = rect.bottom || top;
                const nodeText = (anchor.textContent || anchor.innerText || '').trim();
                const scanText = normalizeEntryScanText(nodeText);
                const payloadRect = [{ left, top, right, bottom }];
                window.NineLookup.onTap(
                    nodeText,
                    scanText || nodeText,
                    'entry',
                    scanText || nodeText,
                    0,
                    nodeText,
                    '[]',
                    JSON.stringify(payloadRect),
                    JSON.stringify(payloadRect),
                    left,
                    top,
                    right,
                    bottom
                );
                if (window.NineLookup && window.NineLookup.onDebug) {
                    window.NineLookup.onDebug('entry tap scan=' + (scanText || nodeText));
                }
                return true;
            }
            function handleLookupTap(clientX, clientY, fromTouch) {
                if (!window.NineLookup || !window.NineLookup.onTap) return;
                if (Date.now() < suppressClickUntil) return;
                const directTarget = document.elementFromPoint(clientX || 0, clientY || 0);
                const directImage = findImageTarget(directTarget);
                if (directImage) {
                    if (dispatchImageTap(directImage)) {
                        suppressClickUntil = Date.now() + 260;
                        return;
                    }
                }
                const directAnchor = findAnchorWithHref(directTarget);
                if (directAnchor) {
                    if (dispatchAnchorTap(directAnchor, clientX || 0, clientY || 0)) {
                        suppressClickUntil = Date.now() + 260;
                        return;
                    }
                }
                const now = Date.now();
                if (
                    Math.abs(clientX - lastTapX) <= 2 &&
                    Math.abs(clientY - lastTapY) <= 2 &&
                    (now - lastTapTs) <= 320
                ) {
                    return;
                }
                lastTapX = clientX;
                lastTapY = clientY;
                lastTapTs = now;
                const range = resolveCaretRange(clientX, clientY);
                if (!range) {
                    if (window.NineLookup && window.NineLookup.onDebug) {
                        window.NineLookup.onDebug('range=null');
                    }
                    return;
                }
                const node = range.startContainer;
                if (!node || node.nodeType !== Node.TEXT_NODE) return;
                const text = node.textContent || '';
                if (!text.trim()) return;
                let targetRect = null;
                let safeOffset = 0;
                let selectionEndExclusive = 0;
                let scanData = null;
                const textLength = text.length;
                if (textLength > 0) {
                    safeOffset = Math.max(0, Math.min(range.startOffset || 0, textLength - 1));
                    safeOffset = resolveOffsetByPointInTextNode(node, safeOffset, clientX, clientY);
                    if (safeOffset > 0) {
                        const previousCharRange = document.createRange();
                        previousCharRange.setStart(node, safeOffset - 1);
                        previousCharRange.setEnd(node, safeOffset);
                        const previousCharRect = previousCharRange.getBoundingClientRect();
                        if (
                            previousCharRect &&
                            clientX >= previousCharRect.left &&
                            clientX <= previousCharRect.right &&
                            clientY >= previousCharRect.top &&
                            clientY <= previousCharRect.bottom
                        ) {
                            safeOffset -= 1;
                        }
                    }
                    selectionEndExclusive = resolveSelectionEnd(text, safeOffset);
                    scanData = collectLookupCharacters(node, safeOffset, 16, true);
                    if (selectionEndExclusive > safeOffset) {
                        const selectionRange = document.createRange();
                        selectionRange.setStart(node, safeOffset);
                        selectionRange.setEnd(node, selectionEndExclusive);
                        const rects = Array.from(selectionRange.getClientRects() || []).filter(r => r.width > 0 || r.height > 0);
                        const rect = pickRectForPoint(rects, clientX, clientY) || selectionRange.getBoundingClientRect();
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
                    targetRect = {left: clientX || 0, top: clientY || 0, right: clientX || 0, bottom: clientY || 0};
                }
                const localRects = scanData && scanData.localRects.length > 0
                    ? scanData.localRects
                    : (targetRect ? [{ left: targetRect.left || 0, top: targetRect.top || 0, right: targetRect.right || 0, bottom: targetRect.bottom || 0 }] : []);
                const charRects = scanData ? scanData.charRects : [];
                const scanText = scanData && scanData.text ? scanData.text : text.slice(safeOffset, selectionEndExclusive).trim();
                const nodePath = JSON.stringify(getNodePath(node));
                window.NineLookup.onTap(
                    text,
                    scanText,
                    'text',
                    extractSentence(text, safeOffset),
                    safeOffset,
                    node.textContent || '',
                    nodePath,
                    JSON.stringify(localRects),
                    JSON.stringify(charRects),
                    targetRect.left || 0,
                    targetRect.top || 0,
                    targetRect.right || 0,
                    targetRect.bottom || 0
                );
                if (fromTouch) {
                    suppressClickUntil = Date.now() + 260;
                }
            }
            window.__nineLookupHandleTap = handleLookupTap;
            document.addEventListener('touchend', function(e) {
                if (Date.now() < suppressClickUntil) return;
                if (touchMoved) {
                    suppressClickUntil = Date.now() + suppressClickDurationMs;
                    return;
                }
                const t = e.changedTouches && e.changedTouches[0];
                if (!t) return;
                const touched = document.elementFromPoint(t.clientX || 0, t.clientY || 0);
                const image = findImageTarget(touched);
                if (image) {
                    e.preventDefault();
                    e.stopPropagation();
                    dispatchImageTap(image);
                    suppressClickUntil = Date.now() + 260;
                    return;
                }
                const anchor = findAnchorWithHref(touched);
                if (anchor) {
                    e.preventDefault();
                    e.stopPropagation();
                    dispatchAnchorTap(anchor, t.clientX || 0, t.clientY || 0);
                    suppressClickUntil = Date.now() + 260;
                    return;
                }
                handleLookupTap(t.clientX || 0, t.clientY || 0, true);
            }, true);
            document.addEventListener('click', function(e) {
                if (Date.now() < suppressClickUntil) return;
                const image = findImageTarget(e.target);
                if (image) {
                    e.preventDefault();
                    e.stopPropagation();
                    dispatchImageTap(image);
                    suppressClickUntil = Date.now() + 260;
                    return;
                }
                const anchor = findAnchorWithHref(e.target);
                if (anchor) {
                    e.preventDefault();
                    e.stopPropagation();
                    dispatchAnchorTap(anchor, e.clientX || 0, e.clientY || 0);
                    suppressClickUntil = Date.now() + 260;
                    return;
                }
                handleLookupTap(e.clientX || 0, e.clientY || 0, false);
            }, true);
            window.__nineLookupResolveMatchedRects = function(nodePath, startOffset, matchedLength) {
                const node = resolveNodePath(nodePath);
                if (!node || node.nodeType !== Node.TEXT_NODE) return null;
                const text = node.textContent || '';
                if (!text.length) return null;
                const safeStart = Math.max(0, Math.min(startOffset || 0, text.length - 1));
                const collected = collectLookupCharacters(node, safeStart, Math.max(1, matchedLength || 1), false);
                return {
                    localRects: collected.localRects,
                    localCharRects: collected.charRects
                };
            };
            window.__nineLookupClearMatchedHighlight = function() {
                const highlights = Array.from(document.querySelectorAll('span.nine-lookup-highlight'));
                highlights.forEach(span => {
                    const parent = span.parentNode;
                    if (!parent) return;
                    while (span.firstChild) {
                        parent.insertBefore(span.firstChild, span);
                    }
                    parent.removeChild(span);
                    parent.normalize();
                });
                return true;
            };
            window.__nineLookupApplyMatchedHighlight = function(nodePath, startOffset, matchedLength) {
                window.__nineLookupClearMatchedHighlight();
                const node = resolveNodePath(nodePath);
                if (!node || node.nodeType !== Node.TEXT_NODE) return false;
                const text = node.textContent || '';
                if (!text.length) return false;
                const safeStart = Math.max(0, Math.min(startOffset || 0, text.length - 1));
                const segments = collectLookupSegments(node, safeStart, Math.max(1, matchedLength || 1), false);
                if (!segments.length) return false;
                for (let i = segments.length - 1; i >= 0; i -= 1) {
                    const segment = segments[i];
                    const range = document.createRange();
                    range.setStart(segment.node, segment.start);
                    range.setEnd(segment.node, segment.end);
                    const wrapper = document.createElement('span');
                    wrapper.className = 'nine-lookup-highlight';
                    wrapper.style.background = 'rgba(161, 161, 170, 0.22)';
                    wrapper.style.borderRadius = '4px';
                    wrapper.style.boxShadow = 'inset 0 0 0 1px rgba(161, 161, 170, 0.40)';
                    range.surroundContents(wrapper);
                }
                return true;
            };
        })();
        </script>
        """.trimIndent()
    }
    return """
        <html>
        <head>
            <meta charset="utf-8"/>
            <style>
                :root {
                    --text-color: $bodyTextColorCss;
                    --background-color-light: #ffffff;
                    --danger-color: #c62828;
                    --danger-color-lighter: #ef9a9a;
                    --oko12-light-red: #f6c5c5;
                    --oko12-red: #e57373;
                    --sidebar-button-danger-background-color-hover: #fce4e4;
                    --tag-archaism-background-color: #eeeeee;
                }
                body { margin: 0; padding: 0; font-size: 14px; line-height: 1.4; color: var(--text-color); }
                img { max-width: 100%; height: auto; cursor: zoom-in; }
                .yomitan-glossary { text-align: left; }
                .nine-lookup-highlight { background: rgba(161, 161, 170, 0.22); border-radius: 4px; box-shadow: inset 0 0 0 1px rgba(161, 161, 170, 0.40); }
                .yomitan-glossary ol { margin: 0; padding-left: 1.1em; }
                .yomitan-glossary li { margin: 0; }
                $customCss
                /* Keep 字義 in normal size under current scoped CSS behavior. */
                .yomitan-glossary [data-sc-div][data-sc字義],
                .yomitan-glossary [data-sc-div][data-sc-字義] {
                    font-size: 14px !important;
                    line-height: 1.4;
                }
                [data-sc筆順], [data-sc-筆順] { display: block; overflow: visible; }
                .nine-brushorder-scroll { overflow-x: auto; -webkit-overflow-scrolling: touch; margin-top: 0.5em; }
                .nine-brushorder-scroll > table {
                    border-collapse: collapse;
                    border-spacing: 0;
                    margin: 0;
                    border-top: 0.5px solid #444 !important;
                    border-left: 0.5px solid #444 !important;
                }
                .nine-brushorder-scroll > table td {
                    border: 0.5px solid #444 !important;
                }
            </style>
            $lookupTapScript
            <script>
                (function() {
                    function wrapBrushOrderTables() {
                        var roots = document.querySelectorAll('[data-sc筆順], [data-sc-筆順]');
                        roots.forEach(function(root) {
                            var children = Array.prototype.slice.call(root.children || []);
                            children.forEach(function(child) {
                                if (!child || !child.tagName) return;
                                if (child.tagName.toLowerCase() !== 'table') return;
                                if (child.parentElement && child.parentElement.classList.contains('nine-brushorder-scroll')) return;
                                var wrapper = document.createElement('div');
                                wrapper.className = 'nine-brushorder-scroll';
                                root.insertBefore(wrapper, child);
                                wrapper.appendChild(child);
                            });
                        });
                    }
                    if (document.readyState === 'loading') {
                        document.addEventListener('DOMContentLoaded', wrapBrushOrderTables, { once: true });
                    } else {
                        wrapBrushOrderTables();
                    }
                })();
            </script>
        </head>
        <body>
            $prefix
            $wrappedBody
        </body>
        </html>
    """.trimIndent()
}

private fun logLookupRenderDebug(
    dictionaryName: String,
    definitionHtml: String,
    customCss: String
) {
    val dict = dictionaryName.ifBlank { "(blank)" }
    val hasDataScNoDash = definitionHtml.contains("data-sc", ignoreCase = true)
    val hasDataScDash = definitionHtml.contains("data-sc-", ignoreCase = true)
    val hasBrushOrderAttr = definitionHtml.contains("data-sc筆順") || definitionHtml.contains("data-sc-筆順")
    val hasTableTag = definitionHtml.contains("<table", ignoreCase = true)
    val hasTrTag = definitionHtml.contains("<tr", ignoreCase = true)
    val hasTdTag = definitionHtml.contains("<td", ignoreCase = true)
    val trCount = Regex("<tr\\b", RegexOption.IGNORE_CASE).findAll(definitionHtml).count()
    val tdCount = Regex("<td\\b", RegexOption.IGNORE_CASE).findAll(definitionHtml).count()
    val hasCssBrushOrderSelector = customCss.contains("[data-sc筆順]") || customCss.contains("[data-sc-筆順]")
    val hasCssTableSelector = customCss.contains("table")
    val defSnippet = definitionHtml
        .replace("\n", " ")
        .replace(Regex("\\s+"), " ")
        .take(220)
    val cssSnippet = customCss
        .replace("\n", " ")
        .replace(Regex("\\s+"), " ")
        .take(220)
    Log.d(
        BOOK_LOOKUP_TAP_LOG_TAG,
        "render debug dict=$dict defLen=${definitionHtml.length} cssLen=${customCss.length} " +
            "hasDataSc=$hasDataScNoDash hasDataScDash=$hasDataScDash hasBrushOrderAttr=$hasBrushOrderAttr " +
            "hasTable=$hasTableTag hasTr=$hasTrTag hasTd=$hasTdTag trCount=$trCount tdCount=$tdCount " +
            "cssHasBrushOrderSelector=$hasCssBrushOrderSelector " +
            "cssHasTableSelector=$hasCssTableSelector defSnippet=$defSnippet cssSnippet=$cssSnippet"
    )
}

private fun decodePreviewImage(context: android.content.Context, rawSrc: String): ImageBitmap? {
    val src = rawSrc.trim()
    if (src.isBlank()) return null
    Log.d(BOOK_LOOKUP_TAP_LOG_TAG, "image preview decode src=$src")
    val uri = runCatching { Uri.parse(src) }.getOrNull() ?: return null
    return runCatching {
        val stream = when (uri.scheme?.lowercase(Locale.ROOT)) {
            "dictres" -> openBundledDictionaryResource(context, uri)?.inputStream
            "mdictres" -> openMountedMdictResource(context, uri)?.inputStream
            "content", "file", "android.resource" -> context.contentResolver.openInputStream(uri)
            else -> null
        } ?: return null
        stream.use { input ->
            BitmapFactory.decodeStream(input)?.asImageBitmap().also { bitmap ->
                Log.d(BOOK_LOOKUP_TAP_LOG_TAG, "image preview decode result=${bitmap != null} uri=$uri")
            }
        }
    }.getOrNull()
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
    val prefix = if (dictionaryName.isBlank()) {
        ".yomitan-glossary"
    } else {
        val dictionaryAttr = escapeCssString(dictionaryName)
        ".yomitan-glossary [data-dictionary=\"$dictionaryAttr\"]"
    }
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
    return scoped.trim()
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

internal class DefinitionLookupBridge(
    var onLookupTap: ((DefinitionLookupTapData) -> Unit)?,
    var onImageTap: ((String) -> Unit)? = null
) {
    var hostView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var lastEntryTapAtMs: Long = 0L

    @JavascriptInterface
    fun onDebug(message: String?) {
        Log.d(BOOK_LOOKUP_TAP_LOG_TAG, "js ${message.orEmpty()}")
    }

    @JavascriptInterface
    fun onOpenExternalUrl(rawUrl: String?) {
        val raw = rawUrl?.trim().orEmpty()
        if (raw.isBlank()) return
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
        val scheme = uri.scheme?.lowercase() ?: return
        if (scheme !in setOf("http", "https", "mailto", "tel")) return
        val ctx = hostView?.context ?: return
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
            .onFailure { Log.d(BOOK_LOOKUP_TAP_LOG_TAG, "openExternal failed url=$raw") }
    }

    @JavascriptInterface
    fun onImageTap(rawSrc: String?) {
        val src = rawSrc?.trim().orEmpty()
        if (src.isBlank()) return
        Log.d(BOOK_LOOKUP_TAP_LOG_TAG, "bridge onImageTap src=$src")
        val callback = onImageTap ?: return
        val dispatch = Runnable { callback.invoke(src) }
        if (Looper.myLooper() == Looper.getMainLooper()) dispatch.run() else mainHandler.post(dispatch)
    }

    @JavascriptInterface
    fun onTap(text: String?, scanText: String?, tapSource: String?, sentence: String?, offset: Int, nodeText: String?, nodePathJson: String?, localRectsJson: String?, localCharRectsJson: String?, left: Float, top: Float, right: Float, bottom: Float) {
        try {
            val value = text.orEmpty()
            val scanValue = scanText?.trim().orEmpty()
            val sourceValue = tapSource?.trim().orEmpty().ifBlank { "text" }
            val now = System.currentTimeMillis()
            if (sourceValue.equals("entry", ignoreCase = true)) {
                lastEntryTapAtMs = now
            } else if (sourceValue.equals("text", ignoreCase = true)) {
                val elapsed = now - lastEntryTapAtMs
                if (elapsed in 0..500) {
                    Log.d(BOOK_LOOKUP_TAP_LOG_TAG, "bridge onTap drop text-after-entry elapsedMs=$elapsed")
                    return
                }
            }
            if (value.isBlank()) return
            val localRect = Rect(left, top, right, bottom)
            val localRects = parseRectListJson(localRectsJson).ifEmpty { listOf(localRect) }
            val localCharRects = parseRectListJson(localCharRectsJson)
            Log.d(
                BOOK_LOOKUP_TAP_LOG_TAG,
                "bridge onTap source=$sourceValue textLen=${value.length} scanLen=${scanValue.length} offset=$offset localRects=${localRects.size} localChars=${localCharRects.size} onMain=${Looper.myLooper() == Looper.getMainLooper()}"
            )
            val dispatch = Runnable {
                try {
                    val host = hostView
                    val screenRect = host?.cssRectToScreenRect(localRect)
                    val screenCharRects = host?.cssRectsToScreenRects(localCharRects).orEmpty()
                    val data = DefinitionLookupTapData(
                        text = value,
                        scanText = scanValue,
                        tapSource = sourceValue,
                        sentence = sentence?.trim().orEmpty(),
                        offset = offset,
                        nodeText = nodeText.orEmpty(),
                        nodePathJson = nodePathJson.orEmpty(),
                        hostView = host,
                        screenRect = screenRect,
                        localRects = localRects,
                        localCharRects = localCharRects,
                        screenCharRects = screenCharRects
                    )
                    onLookupTap?.invoke(data)
                } catch (t: Throwable) {
                    Log.e(BOOK_LOOKUP_TAP_LOG_TAG, "bridge dispatch failed", t)
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                dispatch.run()
            } else {
                hostView?.post(dispatch) ?: mainHandler.post(dispatch)
            }
        } catch (t: Throwable) {
            Log.e(BOOK_LOOKUP_TAP_LOG_TAG, "bridge onTap failed", t)
        }
    }
}

internal data class DefinitionResolvedRects(
    val localRects: List<Rect>,
    val localCharRects: List<Rect>,
    val screenRects: List<Rect>,
    val screenCharRects: List<Rect>
)

private fun WebView.cssRectToScreenRect(rect: Rect, locationCache: IntArray? = null): Rect {
    val location = locationCache ?: IntArray(2).also { getLocationOnScreen(it) }
    val effectiveScale = scale.takeIf { it.isFinite() && it > 0f } ?: 1f
    return Rect(
        left = location[0].toFloat() + rect.left * effectiveScale,
        top = location[1].toFloat() + rect.top * effectiveScale,
        right = location[0].toFloat() + rect.right * effectiveScale,
        bottom = location[1].toFloat() + rect.bottom * effectiveScale
    )
}

private fun WebView.cssRectsToScreenRects(rects: List<Rect>): List<Rect> {
    if (rects.isEmpty()) return emptyList()
    val location = IntArray(2)
    getLocationOnScreen(location)
    return rects.map { cssRectToScreenRect(it, location) }
}

internal suspend fun resolveDefinitionMatchedRects(
    tapData: DefinitionLookupTapData,
    matchedLength: Int
): DefinitionResolvedRects? {
    val webView = tapData.hostView ?: return null
    val nodePathJson = tapData.nodePathJson.takeIf { it.isNotBlank() } ?: return null
    val js = """
        (function() {
            if (!window.__nineLookupResolveMatchedRects) return null;
            const result = window.__nineLookupResolveMatchedRects($nodePathJson, ${tapData.offset}, ${matchedLength.coerceAtLeast(1)});
            return result ? JSON.stringify(result) : null;
        })();
    """.trimIndent()
    val raw = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine<String?> { continuation ->
            webView.evaluateJavascript(js) { value ->
                if (continuation.isActive) continuation.resume(value)
            }
        }
    }
    val decoded = decodeEvaluateJavascriptJson(raw) ?: return null
    val json = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
    val localRects = parseRectListJson(json.optJSONArray("localRects")?.toString())
    val localCharRects = parseRectListJson(json.optJSONArray("localCharRects")?.toString())
    val screenRects = webView.cssRectsToScreenRects(localRects)
    val screenCharRects = webView.cssRectsToScreenRects(localCharRects)
    return DefinitionResolvedRects(
        localRects = localRects,
        localCharRects = localCharRects,
        screenRects = screenRects,
        screenCharRects = screenCharRects
    )
}

internal suspend fun applyDefinitionMatchedHighlight(
    tapData: DefinitionLookupTapData,
    matchedLength: Int
): Boolean {
    val webView = tapData.hostView ?: return false
    val nodePathJson = tapData.nodePathJson.takeIf { it.isNotBlank() } ?: return false
    val js = """
        (function() {
            if (!window.__nineLookupApplyMatchedHighlight) return false;
            return !!window.__nineLookupApplyMatchedHighlight($nodePathJson, ${tapData.offset}, ${matchedLength.coerceAtLeast(1)});
        })();
    """.trimIndent()
    val raw = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine<String?> { continuation ->
            webView.evaluateJavascript(js) { value ->
                if (continuation.isActive) continuation.resume(value)
            }
        }
    }
    return decodeEvaluateJavascriptJson(raw)?.toBooleanStrictOrNull() ?: false
}

internal fun rebuildRectsFromCharacterRectsShared(
    charRects: List<Rect>,
    matchedLength: Int
): List<Rect> {
    if (charRects.isEmpty()) return emptyList()
    val safeMatchedLength = matchedLength.coerceAtLeast(1).coerceAtMost(charRects.size)
    val selectedRects = charRects.take(safeMatchedLength).filter {
        (it.right - it.left) > 0f || (it.bottom - it.top) > 0f
    }
    if (selectedRects.isEmpty()) return emptyList()
    return mergeRectsByLineShared(selectedRects)
}

internal fun sanitizeResolvedHighlightRectsShared(
    rebuiltRects: List<Rect>,
    sourceRects: List<Rect>
): List<Rect> {
    if (rebuiltRects.isEmpty()) return emptyList()
    if (sourceRects.isEmpty()) return rebuiltRects
    val rebuiltBounds = mergeRectBoundsShared(rebuiltRects) ?: return emptyList()
    val sourceBounds = mergeRectBoundsShared(sourceRects) ?: return rebuiltRects
    val expandedSource = Rect(
        left = sourceBounds.left - 24f,
        top = sourceBounds.top - 24f,
        right = sourceBounds.right + 24f,
        bottom = sourceBounds.bottom + 24f
    )
    val intersects =
        rebuiltBounds.right >= expandedSource.left &&
            rebuiltBounds.left <= expandedSource.right &&
            rebuiltBounds.bottom >= expandedSource.top &&
            rebuiltBounds.top <= expandedSource.bottom
    return if (intersects) rebuiltRects else emptyList()
}

private fun mergeRectBoundsShared(rects: List<Rect>): Rect? {
    val validRects = rects.filter { !it.isEmpty }
    if (validRects.isEmpty()) return null
    return Rect(
        left = validRects.minOf { it.left },
        top = validRects.minOf { it.top },
        right = validRects.maxOf { it.right },
        bottom = validRects.maxOf { it.bottom }
    )
}

internal fun mergeRectsByLineShared(rects: List<Rect>): List<Rect> {
    if (rects.isEmpty()) return emptyList()
    val sorted = rects.sortedWith(compareBy<Rect> { it.top }.thenBy { it.left })
    val result = mutableListOf<Rect>()
    val verticalTolerance = 2f
    var current = sorted.first()
    for (index in 1 until sorted.size) {
        val rect = sorted[index]
        if (kotlin.math.abs(rect.top - current.top) <= verticalTolerance &&
            kotlin.math.abs(rect.bottom - current.bottom) <= verticalTolerance
        ) {
            current = Rect(
                left = minOf(current.left, rect.left),
                top = minOf(current.top, rect.top),
                right = maxOf(current.right, rect.right),
                bottom = maxOf(current.bottom, rect.bottom)
            )
        } else {
            result += current
            current = rect
        }
    }
    result += current
    return result
}

private fun decodeEvaluateJavascriptJson(raw: String?): String? {
    if (raw == null || raw == "null") return null
    return runCatching {
        if (raw.startsWith("\"")) {
            JSONArray("[$raw]").getString(0)
        } else {
            raw
        }
    }.getOrNull()
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
