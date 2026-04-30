package moe.tekuza.m9player

import androidx.compose.ui.geometry.Rect
import android.content.Context

internal data class RecursiveLookupStart(
    val term: String,
    val shouldPlaceBelow: Boolean,
    val tapAnchorBounds: Rect?,
    val currentAnchorBounds: Rect?
)

internal data class RecursiveLookupQueryResult(
    val term: String,
    val hits: List<DictionarySearchResult>
)

internal fun prepareRecursiveLookupStart(
    sourceLayer: ReaderLookupLayer,
    tapData: DefinitionLookupTapData,
    explicitAnchor: ReaderLookupAnchor?,
    viewportHeight: Int
): RecursiveLookupStart? {
    val isEntryLikeTap = tapData.tapSource.equals("entry", ignoreCase = true) ||
        tapData.tapSource.equals("entry-link", ignoreCase = true)
    val term = if (isEntryLikeTap) {
        tapData.scanText.trim().ifBlank { tapData.text.trim() }
    } else {
        val selection = selectLookupScanText(tapData.scanText.ifBlank { tapData.text }, 0) ?: return null
        selection.text.trim()
    }.takeIf { it.isNotBlank() } ?: return null
    val tapAnchorBounds = explicitAnchor.boundingRectCoreOrNull()
    val currentAnchorBounds = sourceLayer.anchor.boundingRectCoreOrNull()
    val estimatedAnchorY = tapAnchorBounds?.bottom
        ?: currentAnchorBounds?.bottom
        ?: (viewportHeight * 0.56f)
    val shouldPlaceBelow = estimatedAnchorY <= (viewportHeight / 2f)
    return RecursiveLookupStart(
        term = term,
        shouldPlaceBelow = shouldPlaceBelow,
        tapAnchorBounds = tapAnchorBounds,
        currentAnchorBounds = currentAnchorBounds
    )
}

internal suspend fun executeRecursiveLookupQuery(
    context: Context,
    dictionaries: List<LoadedDictionary>,
    term: String,
    tapSource: String,
    sourceDictionaryName: String?,
    sourceDictionaryCacheKey: String? = null
): RecursiveLookupQueryResult {
    val isEntryLinkTap = tapSource.equals("entry-link", ignoreCase = true)
    val isEntryTap = tapSource.equals("entry", ignoreCase = true) ||
        isEntryLinkTap
    val exactQuery = term.trim()
    fun filterExactOrKeep(base: List<DictionarySearchResult>, strictExact: Boolean): List<DictionarySearchResult> {
        if (exactQuery.isBlank()) return base
        val exact = base.filter { hit ->
            val entryTerm = hit.entry.term.trim()
            val entryReading = hit.entry.reading?.trim().orEmpty()
            entryTerm == exactQuery || entryReading == exactQuery
        }
        if (exact.isNotEmpty()) return exact
        return if (strictExact) emptyList() else base
    }
    val sourceDictName = sourceDictionaryName?.trim().orEmpty()
    val sourceDictCacheKey = sourceDictionaryCacheKey?.trim().orEmpty()
    val sourceDictionaries = if (sourceDictCacheKey.isNotBlank()) {
        dictionaries.filter { it.cacheKey == sourceDictCacheKey }
    } else if (sourceDictName.isBlank()) {
        emptyList()
    } else {
        dictionaries.filter { it.name.equals(sourceDictName, ignoreCase = true) }
    }
    if (isEntryLinkTap && sourceDictionaries.isEmpty()) {
        return RecursiveLookupQueryResult(
            term = exactQuery.ifBlank { term },
            hits = emptyList()
        )
    }
    if (sourceDictionaries.isNotEmpty()) {
        val sourceComputed = computeTapLookupResultsWithWinningCandidate(
            context = context,
            dictionaries = sourceDictionaries,
            query = term,
            profile = if (isEntryLinkTap) DictionaryQueryProfile.FULL else DictionaryQueryProfile.FAST
        )
        val sourceHitsRaw = if (isEntryTap) {
            filterExactOrKeep(
                sourceComputed?.hits.orEmpty(),
                strictExact = isEntryLinkTap
            )
        } else {
            sourceComputed?.hits.orEmpty()
        }
        val sourceHits = sourceHitsRaw.sortedWith(
            compareByDescending<DictionarySearchResult> {
                entryExactPrefixBoost(it.entry, exactQuery.ifBlank { term })
            }.thenByDescending { it.score }
        )
        val sourceCoversAllDictionaries =
            sourceDictionaries.size == dictionaries.size &&
                sourceDictionaries.all { source ->
                    dictionaries.any { it.name.equals(source.name, ignoreCase = true) }
                }
        if (sourceHits.isNotEmpty()) {
            return RecursiveLookupQueryResult(
                term = exactQuery.ifBlank { sourceComputed?.query ?: term },
                hits = sourceHits
            )
        }
        if (isEntryLinkTap) {
            return RecursiveLookupQueryResult(
                term = exactQuery.ifBlank { sourceComputed?.query ?: term },
                hits = emptyList()
            )
        }
        // Avoid duplicate same-query work when source dictionary already equals the full set.
        if (sourceCoversAllDictionaries) {
            return RecursiveLookupQueryResult(
                term = exactQuery.ifBlank { sourceComputed?.query ?: term },
                hits = sourceHits
            )
        }
    }
    val computed = computeTapLookupResultsWithWinningCandidate(
        context = context,
        dictionaries = dictionaries,
        query = term,
        profile = if (isEntryLinkTap) DictionaryQueryProfile.FULL else DictionaryQueryProfile.FAST
    )
    val finalHits = if (isEntryTap) {
        filterExactOrKeep(
            computed?.hits.orEmpty(),
            strictExact = isEntryLinkTap
        )
    } else {
        computed?.hits.orEmpty()
    }
    return RecursiveLookupQueryResult(
        term = exactQuery.ifBlank { computed?.query ?: term },
        hits = finalHits
    )
}

private fun entryExactPrefixBoost(entry: DictionaryEntry, query: String): Int {
    val q = query.trim()
    if (q.isBlank()) return 0
    val term = entry.term.trim()
    val reading = entry.reading?.trim().orEmpty()
    return when {
        term.equals(q, ignoreCase = true) -> 4
        reading.equals(q, ignoreCase = true) -> 3
        term.startsWith(q, ignoreCase = true) -> 2
        reading.startsWith(q, ignoreCase = true) -> 1
        else -> 0
    }
}

internal fun rebuildDefinitionRectsByMatchedLengthCore(
    rects: List<Rect>,
    charRects: List<Rect>,
    nodeText: String,
    startOffset: Int,
    matchedLength: Int
): List<Rect> {
    val exactRects = rebuildRectsFromCharacterRectsShared(charRects, matchedLength)
    if (exactRects.isNotEmpty()) return exactRects
    if (rects.isEmpty()) return emptyList()
    val safeNodeTextLength = nodeText.length.takeIf { it > 0 } ?: return rects
    val safeStartOffset = startOffset.coerceIn(0, safeNodeTextLength - 1)
    val remainingLength = (safeNodeTextLength - safeStartOffset).coerceAtLeast(1)
    val safeMatchedLength = matchedLength.coerceAtLeast(1).coerceAtMost(remainingLength)
    val safeBaseLength = selectLookupScanText(nodeText, safeStartOffset)?.text?.length?.coerceAtLeast(safeMatchedLength)
        ?: remainingLength
    if (safeMatchedLength >= safeBaseLength) return rects
    val totalWidth = rects.sumOf { (it.right - it.left).coerceAtLeast(0f).toDouble() }.toFloat()
    if (totalWidth <= 0f) return rects
    var remainingWidth = totalWidth * (safeMatchedLength.toFloat() / safeBaseLength.toFloat())
    val result = mutableListOf<Rect>()
    for (rect in rects) {
        if (remainingWidth <= 0f) break
        val rectWidth = (rect.right - rect.left).coerceAtLeast(0f)
        if (rectWidth <= 0f) continue
        if (remainingWidth >= rectWidth) {
            result += rect
            remainingWidth -= rectWidth
        } else {
            result += Rect(
                left = rect.left,
                top = rect.top,
                right = rect.left + remainingWidth,
                bottom = rect.bottom
            )
            remainingWidth = 0f
        }
    }
    return result.ifEmpty { rects.take(1) }
}

internal fun rebuildDefinitionAnchorRectsByMatchedLengthCore(
    charRects: List<Rect>,
    fallbackRect: Rect?,
    matchedLength: Int
): List<Rect> {
    val exactRects = rebuildRectsFromCharacterRectsShared(charRects, matchedLength)
    return when {
        exactRects.isNotEmpty() -> exactRects
        fallbackRect != null -> listOf(fallbackRect)
        else -> emptyList()
    }
}

internal fun DefinitionLookupTapData.resolveScreenAnchorRects(): List<Rect> {
    val fromChars = screenCharRects.filter { !it.isEmpty }
    val fromRect = screenRect?.takeIf { !it.isEmpty }?.let { listOf(it) }.orEmpty()
    val host = hostView ?: return emptyList()
    val location = IntArray(2)
    host.getLocationOnScreen(location)
    val fallbackLocalRects = if (localCharRects.isNotEmpty()) localCharRects else localRects
    val fromLocal = fallbackLocalRects
        .filter { !it.isEmpty }
        .map { rect ->
            Rect(
                left = location[0].toFloat() + rect.left,
                top = location[1].toFloat() + rect.top,
                right = location[0].toFloat() + rect.right,
                bottom = location[1].toFloat() + rect.bottom
            )
        }
    if (fromChars.isNotEmpty()) return fromChars
    if (fromLocal.isNotEmpty()) return fromLocal
    return fromRect
}

internal fun ReaderLookupAnchor?.boundingRectCoreOrNull(): Rect? {
    val rects = this?.rects?.filter { !it.isEmpty } ?: return null
    if (rects.isEmpty()) return null
    var left = rects.first().left
    var top = rects.first().top
    var right = rects.first().right
    var bottom = rects.first().bottom
    rects.drop(1).forEach { rect ->
        left = minOf(left, rect.left)
        top = minOf(top, rect.top)
        right = maxOf(right, rect.right)
        bottom = maxOf(bottom, rect.bottom)
    }
    return Rect(left = left, top = top, right = right, bottom = bottom)
}
