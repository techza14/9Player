package moe.tekuza.m9player

import android.content.Context
import android.util.Log
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class RecursiveLookupApplyData(
    val sourceLayerIndex: Int,
    val definitionKey: String,
    val term: String,
    val sourceCue: ReaderSubtitleCue?,
    val sourceCueIndex: Int?,
    val hits: List<DictionarySearchResult>,
    val adjustedHighlightRects: List<Rect>,
    val adjustedAnchor: ReaderLookupAnchor?,
    val adjustedAnchorBounds: Rect?,
    val shouldPlaceBelow: Boolean,
    val popupSentence: String
)

private fun applyRecursiveLookupToSession(
    session: ReaderLookupSession,
    applied: RecursiveLookupApplyData,
    buildLayer: (RecursiveLookupApplyData) -> ReaderLookupLayer
) {
    session.truncateTo(applied.sourceLayerIndex)
    if (session.getOrNull(applied.sourceLayerIndex) != null) {
        session.replaceAt(applied.sourceLayerIndex) { layer ->
            layer.copy(
                highlightedDefinitionKey = applied.definitionKey,
                highlightedDefinitionRects = applied.adjustedHighlightRects
            )
        }
    }
    session.push(buildLayer(applied))
}

internal fun launchRecursiveLookupIntoSession(
    context: Context,
    scope: CoroutineScope,
    session: ReaderLookupSession,
    sourceLayerIndex: Int,
    definitionKey: String,
    tapData: DefinitionLookupTapData,
    explicitAnchor: ReaderLookupAnchor?,
    requireSourceCue: Boolean = false,
    viewportHeight: Int,
    dictionaries: List<LoadedDictionary>,
    nextRequestNonce: () -> Long,
    isRequestNonceCurrent: (Long) -> Boolean,
    logAnchorTag: String,
    logPosTag: String,
    buildPopupSentence: (String, DefinitionLookupTapData) -> String,
    buildLayer: (RecursiveLookupApplyData) -> ReaderLookupLayer,
    onNoSourceLayer: (Int) -> Unit = {},
    onNoCue: (Int) -> Unit = {},
    onNoSelection: () -> Unit = {},
    onNoDictionary: () -> Unit = {},
    onBeforeApply: () -> Unit = {},
    onAfterApply: (RecursiveLookupApplyData) -> Unit = {},
    onFailure: (Throwable) -> Unit = {}
) {
    launchRecursiveLookup(
        context = context,
        scope = scope,
        sourceLayerIndex = sourceLayerIndex,
        definitionKey = definitionKey,
        tapData = tapData,
        explicitAnchor = explicitAnchor,
        resolveSourceLayer = { index -> session.getOrNull(index) },
        requireSourceCue = requireSourceCue,
        viewportHeight = viewportHeight,
        dictionaries = dictionaries,
        nextRequestNonce = nextRequestNonce,
        isRequestNonceCurrent = isRequestNonceCurrent,
        logAnchorTag = logAnchorTag,
        logPosTag = logPosTag,
        buildPopupSentence = buildPopupSentence,
        callbacks = RecursiveLookupCallbacks(
            onNoSourceLayer = onNoSourceLayer,
            onNoCue = onNoCue,
            onNoSelection = onNoSelection,
            onNoDictionary = onNoDictionary,
            onBeforeApply = onBeforeApply,
            onApply = { applied ->
                applyRecursiveLookupToSession(
                    session = session,
                    applied = applied,
                    buildLayer = buildLayer
                )
                onAfterApply(applied)
            },
            onFailure = onFailure
        )
    )
}

internal data class RecursiveLookupCallbacks(
    val onNoSourceLayer: (Int) -> Unit = {},
    val onNoCue: (Int) -> Unit = {},
    val onNoSelection: () -> Unit = {},
    val onNoDictionary: () -> Unit = {},
    val onBeforeApply: () -> Unit = {},
    val onApply: (RecursiveLookupApplyData) -> Unit,
    val onFailure: (Throwable) -> Unit = {}
)

private fun launchRecursiveLookup(
    context: Context,
    scope: CoroutineScope,
    sourceLayerIndex: Int,
    definitionKey: String,
    tapData: DefinitionLookupTapData,
    explicitAnchor: ReaderLookupAnchor?,
    resolveSourceLayer: (Int) -> ReaderLookupLayer?,
    requireSourceCue: Boolean = false,
    viewportHeight: Int,
    dictionaries: List<LoadedDictionary>,
    nextRequestNonce: () -> Long,
    isRequestNonceCurrent: (Long) -> Boolean,
    logAnchorTag: String,
    logPosTag: String,
    buildPopupSentence: (String, DefinitionLookupTapData) -> String,
    callbacks: RecursiveLookupCallbacks
) {
    val sourceLayer = resolveSourceLayer(sourceLayerIndex) ?: run {
        Log.d(logAnchorTag, "recursiveAbort reason=no_source_layer index=$sourceLayerIndex")
        callbacks.onNoSourceLayer(sourceLayerIndex)
        return
    }
    val sourceCue = sourceLayer.cue
    if (requireSourceCue && sourceCue == null) {
        Log.d(logAnchorTag, "recursiveAbort reason=no_cue index=$sourceLayerIndex")
        callbacks.onNoCue(sourceLayerIndex)
        return
    }
    val start = prepareRecursiveLookupStart(
        sourceLayer = sourceLayer,
        tapData = tapData,
        explicitAnchor = explicitAnchor,
        viewportHeight = viewportHeight
    ) ?: run {
        Log.d(
            logAnchorTag,
            "recursiveAbort reason=no_selection scan='${tapData.scanText.take(24)}' text='${tapData.text.take(24)}'"
        )
        callbacks.onNoSelection()
        return
    }
    val term = start.term
    Log.d(
        logAnchorTag,
        "recursiveStart sourceLayer=$sourceLayerIndex term=$term tapAnchor=${formatRectForLogLocal(start.tapAnchorBounds)} currentAnchor=${formatRectForLogLocal(start.currentAnchorBounds)} placeBelow=${start.shouldPlaceBelow}"
    )

    if (dictionaries.isEmpty()) {
        Log.d(logAnchorTag, "recursiveAbort reason=no_dictionary")
        callbacks.onNoDictionary()
        return
    }

    val requestNonce = nextRequestNonce()
    scope.launch {
        val result = withContext(Dispatchers.Default) {
            runCatching {
                executeRecursiveLookupQuery(
                    context = context,
                    dictionaries = dictionaries,
                    term = term
                )
            }
        }
        result.onSuccess { queryResult ->
            if (!isRequestNonceCurrent(requestNonce)) return@onSuccess
            val hits = queryResult.hits
            if (hits.isEmpty()) {
                Log.d(logAnchorTag, "recursiveAbort reason=no_hits term=$term")
                return@onSuccess
            }
            val matchedLength = hits.first().matchedLength.coerceAtLeast(1)
            val resolvedRects = resolveDefinitionMatchedRects(tapData, matchedLength)
            val adjustedHighlightRects = if (resolvedRects != null) {
                resolvedRects.localCharRects
                    .let { rebuildRectsFromCharacterRectsShared(it, matchedLength) }
                    .let { sanitizeResolvedHighlightRectsShared(it, tapData.localRects) }
            } else {
                rebuildDefinitionRectsByMatchedLengthCore(
                    rects = tapData.localRects,
                    charRects = tapData.localCharRects,
                    nodeText = tapData.nodeText,
                    startOffset = tapData.offset,
                    matchedLength = matchedLength
                )
            }
            val tapScreenAnchorRects = tapData.resolveScreenAnchorRects()
            val adjustedAnchorRects = resolvedRects?.screenCharRects
                ?.let { rebuildRectsFromCharacterRectsShared(it, matchedLength) }
                ?.takeIf { it.isNotEmpty() }
                ?: rebuildDefinitionAnchorRectsByMatchedLengthCore(
                    charRects = tapScreenAnchorRects,
                    fallbackRect = explicitAnchor.boundingRectCoreOrNull() ?: tapScreenAnchorRects.firstOrNull(),
                    matchedLength = matchedLength
                )
            val adjustedAnchor = if (adjustedAnchorRects.isNotEmpty()) {
                ReaderLookupAnchor(rects = adjustedAnchorRects)
            } else {
                explicitAnchor ?: sourceLayer.anchor
            }
            val adjustedAnchorBounds = adjustedAnchor.boundingRectCoreOrNull()
            val popupSentence = buildPopupSentence(term, tapData)
            callbacks.onBeforeApply()
            callbacks.onApply(
                RecursiveLookupApplyData(
                    sourceLayerIndex = sourceLayerIndex,
                    definitionKey = definitionKey,
                    term = term,
                    sourceCue = sourceCue,
                    sourceCueIndex = sourceLayer.cueIndex,
                    hits = hits,
                    adjustedHighlightRects = adjustedHighlightRects,
                    adjustedAnchor = adjustedAnchor,
                    adjustedAnchorBounds = adjustedAnchorBounds,
                    shouldPlaceBelow = start.shouldPlaceBelow,
                    popupSentence = popupSentence
                )
            )
            Log.d(
                logPosTag,
                "push layer=? source=recursive term=$term anchor=${formatRectForLogLocal(adjustedAnchorBounds)} placeBelow=${start.shouldPlaceBelow} fromLayer=$sourceLayerIndex"
            )
        }.onFailure { error ->
            if (!isRequestNonceCurrent(requestNonce)) return@onFailure
            callbacks.onFailure(error)
            Log.d(logPosTag, "recursiveFail sourceLayer=$sourceLayerIndex term=$term")
        }
    }
}

private fun formatRectForLogLocal(rect: Rect?): String {
    return if (rect == null) {
        "null"
    } else {
        "${rect.left.toInt()},${rect.top.toInt()},${rect.right.toInt()},${rect.bottom.toInt()}"
    }
}
