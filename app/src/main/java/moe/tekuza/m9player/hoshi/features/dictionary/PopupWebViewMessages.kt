package moe.tekuza.m9player.hoshi.features.dictionary

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import de.manhhao.hoshi.HoshiDicts
import de.manhhao.hoshi.LookupResult
import moe.tekuza.m9player.hoshi.dictionary.LookupEngine
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionData
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionRect
import moe.tekuza.m9player.AnkiDuplicateCheckResult
import moe.tekuza.m9player.logDebug
import org.json.JSONArray
import org.json.JSONObject

private const val HOSHI_LOOKUP_POPUP_LOG_TAG = "HoshiLookupPopup"

internal class PopupWebViewCallbacks(
    val onTapOutside: () -> Unit = {},
    val onSwipeDismiss: () -> Unit = {},
    val onOpenLink: (String) -> Unit = {},
    val onImageTap: (String) -> Unit = {},
    val onMineEntry: (String) -> Boolean = { false },
    val onDuplicateCheck: (String) -> AnkiDuplicateCheckResult = { AnkiDuplicateCheckResult() },
    val onViewDuplicate: (List<Long>) -> Boolean = { false },
    val onRangeSelection: () -> Unit = {},
    val onPlayWordAudio: (String, String?, String?) -> Unit = { _, _, _ -> },
    val onCloseAll: () -> Unit = {},
    val onTextSelected: (ReaderSelectionData) -> Int? = { null },
    val onLookupRedirect: (String) -> List<LookupResult> = { query -> LookupEngine.lookup(query) },
    val onLookupRedirected: (ReaderSelectionData, List<LookupResult>) -> Unit = { _, _ -> },
    val onContentReady: () -> Unit = {},
)

internal fun PopupWebViewCallbacks.withAdditionalImageTap(
    handler: (String) -> Unit,
): PopupWebViewCallbacks = PopupWebViewCallbacks(
    onTapOutside = onTapOutside,
    onSwipeDismiss = onSwipeDismiss,
    onOpenLink = onOpenLink,
    onImageTap = { src ->
        onImageTap(src)
        if (isHoshiPreviewBitmapCandidate(src)) {
            handler(src)
        }
    },
    onMineEntry = onMineEntry,
    onDuplicateCheck = onDuplicateCheck,
    onViewDuplicate = onViewDuplicate,
    onRangeSelection = onRangeSelection,
    onPlayWordAudio = onPlayWordAudio,
    onCloseAll = onCloseAll,
    onTextSelected = onTextSelected,
    onLookupRedirect = onLookupRedirect,
    onLookupRedirected = onLookupRedirected,
    onContentReady = onContentReady,
)

internal class PopupWebViewCallbackHolder(
    var callbacks: PopupWebViewCallbacks,
)

internal class PopupLookupResultsHolder(
    var results: List<LookupResult>,
)

internal data class PopupWebViewOffsetState(
    var selectionOffsetX: Double = 0.0,
    var selectionOffsetY: Double = 0.0,
    var windowOffsetAdjustmentX: Double = 0.0,
    var windowOffsetAdjustmentY: Double = 0.0,
)

internal class PopupContentReadyGate {
    private var generation = 0L
    private var requestId = 0L

    fun reset() {
        generation += 1
        requestId += 1
    }

    fun awaitReadyToDraw(webView: WebView, onReady: () -> Unit) {
        val currentGeneration = generation
        val currentRequestId = requestId + 1
        requestId = currentRequestId
        webView.postVisualStateCallback(
            currentRequestId,
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (generation != currentGeneration || this@PopupContentReadyGate.requestId != currentRequestId) {
                        return
                    }
                    onReady()
                }
            },
        )
    }
}

internal class PopupMessageWebViewClient(
    private val callbackHolder: PopupWebViewCallbackHolder,
    private val assets: LookupPopupAssets? = null,
    private val imageRequestHandler: DictionaryImageRequestHandler = DictionaryImageRequestHandler(),
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        handlePopupUrl(request.url)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        handlePopupUrl(Uri.parse(url))

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        handleAssetRequest(request.url)
            ?: imageRequestHandler.handleImageRequest(request.url)

    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
        Uri.parse(url).let { uri ->
            handleAssetRequest(uri)
                ?: imageRequestHandler.handleImageRequest(uri)
        }

    private fun handleAssetRequest(uri: Uri): WebResourceResponse? {
        val assets = assets ?: return null
        if (uri.host != "hoshi.local" || !uri.path.orEmpty().startsWith("/popup/")) return null
        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) { "asset request uri=$uri" }
        val content = when (uri.lastPathSegment) {
            "popup.css" -> assets.popupCss
            "selection.js" -> assets.selectionJs
            "popup.js" -> assets.popupJs
            else -> return null
        }
        val mimeType = when (uri.lastPathSegment) {
            "popup.css" -> "text/css"
            else -> "application/javascript"
        }
        return WebResourceResponse(
            mimeType,
            "UTF-8",
            ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun handlePopupUrl(uri: Uri): Boolean {
        if (uri.scheme != "hoshi-popup") return false
        when (uri.host) {
            "tapOutside" -> callbackHolder.callbacks.onTapOutside()
            "swipeDismiss" -> callbackHolder.callbacks.onSwipeDismiss()
        }
        return true
    }
}

internal class DictionaryImageRequestHandler(
    private val loadMedia: (dictionary: String, path: String) -> ByteArray? = { dictionary, path ->
        HoshiDicts.getMediaFile(HoshiDicts.lookupObject, dictionary, path)
    },
) {
    fun handleImageRequest(uri: Uri): WebResourceResponse? {
        val isIosImageScheme = uri.scheme == "image"
        val isAndroidImageEndpoint = uri.scheme == "https" &&
            uri.host == "hoshi.local" &&
            uri.path == "/image"
        if (!isIosImageScheme && !isAndroidImageEndpoint) return null
        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) { "image request uri=$uri" }
        val dictionary = uri.getQueryParameter("dictionary").orEmpty()
        val mediaPath = uri.getQueryParameter("path").orEmpty()
        if (dictionary.isBlank() || mediaPath.isBlank()) return null
        val data = loadMedia(dictionary, mediaPath)?.takeIf { it.isNotEmpty() }
        if (data == null) {
            logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) { "image miss dictionary=$dictionary path=$mediaPath" }
            return null
        }
        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
            "image hit dictionary=$dictionary path=$mediaPath bytes=${data.size} mime=${dictionaryImageMimeType(mediaPath)}"
        }

        return WebResourceResponse(
            dictionaryImageMimeType(mediaPath),
            null,
            ByteArrayInputStream(data),
        ).apply {
            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*")
        }
    }
}

internal fun dictionaryImageMimeType(path: String): String =
    when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "heic" -> "image/heic"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

internal class PopupWebViewBridge(
    private val webView: WebView,
    private val callbackHolder: PopupWebViewCallbackHolder,
    private val lookupResultsHolder: PopupLookupResultsHolder = PopupLookupResultsHolder(emptyList()),
    private val offsetState: PopupWebViewOffsetState = PopupWebViewOffsetState(),
    private val contentReadyGate: PopupContentReadyGate? = null,
    private val onShellReady: () -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class PopupSelectionOffset(
        val x: Double,
        val y: Double,
    )

    private fun currentSelectionOffset(): PopupSelectionOffset {
        val selectionOffsetX = offsetState.selectionOffsetX
        val selectionOffsetY = offsetState.selectionOffsetY
        val windowOffsetAdjustmentX = offsetState.windowOffsetAdjustmentX
        val windowOffsetAdjustmentY = offsetState.windowOffsetAdjustmentY
        if (
            selectionOffsetX != 0.0 ||
            selectionOffsetY != 0.0 ||
            windowOffsetAdjustmentX != 0.0 ||
            windowOffsetAdjustmentY != 0.0
        ) {
            return PopupSelectionOffset(
                x = selectionOffsetX + windowOffsetAdjustmentX,
                y = selectionOffsetY + windowOffsetAdjustmentY,
            )
        }
        val location = IntArray(2)
        return runCatching {
            webView.getLocationOnScreen(location)
            val density = webView.resources.displayMetrics.density.toDouble().coerceAtLeast(0.1)
            PopupSelectionOffset(
                x = location[0] / density,
                y = location[1] / density,
            )
        }.getOrElse {
            PopupSelectionOffset(
                x = selectionOffsetX,
                y = selectionOffsetY,
            )
        }
    }

    @JavascriptInterface
    fun getEntry(index: Int): String? =
        lookupResultsHolder.results.getOrNull(index)?.let { LookupPopupHtml.entryJsonString(it) }

    @JavascriptInterface
    fun shellReady() {
        mainHandler.post(onShellReady)
    }

    @JavascriptInterface
    fun lookupRedirect(query: String): Int {
        val results = callbackHolder.callbacks.onLookupRedirect(query)
        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
            "lookupRedirect query='${query.take(32)}' resultCount=${results.size}"
        }
        if (results.isNotEmpty()) {
            val offset = currentSelectionOffset()
            val selection = ReaderSelectionData(
                text = query,
                sentence = query,
                rect = ReaderSelectionRect(offset.x, offset.y, 1.0, 1.0),
                normalizedOffset = 0,
                sentenceOffset = 0,
            )
            mainHandler.post { callbackHolder.callbacks.onLookupRedirected(selection, results) }
        }
        return 0
    }

    @JavascriptInterface
    fun lookupRedirectAt(message: String): Int {
        val payload = runCatching { JSONObject(message) }.getOrNull() ?: return 0
        val query = payload.optString("query").takeIf { it.isNotBlank() } ?: return 0
        val offset = currentSelectionOffset()
        val selection = payload.toSelectionData(offset.x, offset.y)?.copy(
            text = query,
            sentence = payload.optString("sentence").ifBlank { query },
            normalizedOffset = 0,
            sentenceOffset = 0,
        ) ?: return 0
        val results = callbackHolder.callbacks.onLookupRedirect(query)
        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
            "lookupRedirectAt query='${query.take(32)}' resultCount=${results.size} rect=${selection.rect.x},${selection.rect.y} ${selection.rect.width}x${selection.rect.height}"
        }
        if (results.isNotEmpty()) {
            mainHandler.post { callbackHolder.callbacks.onLookupRedirected(selection, results) }
        }
        return 0
    }

    @JavascriptInterface
    fun postMessage(message: String) {
        val payload = runCatching { JSONObject(message) }.getOrNull() ?: return
        val callbacks = callbackHolder.callbacks
        when (payload.optString("name")) {
            "openLink" -> payload.optString("body").takeIf { it.isNotBlank() }?.let(callbacks.onOpenLink)
            "debug" -> payload.optJSONObject("body")?.let { body ->
                logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
                    "popupDebug name=${body.optString("name").takeIf { it.isNotBlank() } ?: "unknown"} body=$body"
                }
            }
            "imageTap" -> payload.optJSONObject("body")?.optString("src")?.takeIf { it.isNotBlank() }?.let { src ->
                logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) { "imageTap src=$src" }
                callbacks.onImageTap(src)
            }
            "rangeSelection" -> mainHandler.post(callbacks.onRangeSelection)
            "playWordAudio" -> payload.optJSONObject("body")?.let { body ->
                val term = body.optString("term").takeIf { it.isNotBlank() } ?: return@let
                val reading = body.optString("reading").takeIf { it.isNotBlank() }
                val url = body.optString("url").takeIf { it.isNotBlank() } ?: ""
                mainHandler.post { callbacks.onPlayWordAudio(url, term, reading) }
            }
            "tapOutside" -> mainHandler.post {
                callbacks.onTapOutside()
                webView.evaluateJavascript("window.hoshiSelection.clearSelection()", null)
            }
            "swipeDismiss" -> mainHandler.post(callbacks.onSwipeDismiss)
            "contentReady", "contentReadyToDraw" -> mainHandler.post {
                val gate = contentReadyGate
                if (gate != null) {
                    gate.awaitReadyToDraw(webView, callbacks.onContentReady)
                } else {
                    callbacks.onContentReady()
                }
            }
            "textSelected" -> payload.optJSONObject("body")?.let { body ->
                val offset = currentSelectionOffset()
                body.toSelectionData(offset.x, offset.y)?.let { selection ->
                    val rect = body.optJSONObject("rect")
                    val rawX = rect?.optDouble("x")
                    val rawY = rect?.optDouble("y")
                    val rawWidth = rect?.optDouble("width")
                    val rawHeight = rect?.optDouble("height")
                    logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
                        "textSelected text='${selection.text.take(32)}' " +
                            "webViewOffset(windowDp)=${offset.x},${offset.y} " +
                            "selectedRect(screenDp)=${selection.rect.x},${selection.rect.y} ${selection.rect.width}x${selection.rect.height} " +
                            "selectedRect(rawDp)=${rawX},${rawY} ${rawWidth}x${rawHeight} " +
                            "raw=${rect?.toString()}"
                    }
                    mainHandler.post {
                        val highlightCount = callbacks.onTextSelected(selection) ?: return@post
                        webView.evaluateJavascript("window.hoshiSelection.highlightSelection($highlightCount)", null)
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun mineEntry(content: String): Boolean {
        val callbacks = callbackHolder.callbacks
        return runCatching {
            logDebug("AnkiExportDebug") {
                "bridge mineEntry dispatch contentSize=${content.length}"
            }
            val accepted = callbacks.onMineEntry(content)
            logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
                "mineEntry accepted=$accepted contentSize=${content.length}"
            }
            accepted
        }.getOrElse {
            Log.w("HoshiLookupPopup", "mineEntry failed", it)
            false
        }
    }

    @JavascriptInterface
    fun duplicateCheck(expression: String): String {
        val callbacks = callbackHolder.callbacks
        return runCatching {
            val result = callbacks.onDuplicateCheck(expression)
            logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
                "duplicateCheck expression='${expression.take(32)}' duplicated=${result.duplicate} noteIds=${result.noteIds.size} allowAdd=${result.allowAdd}"
            }
            JSONObject()
                .put("duplicate", result.duplicate)
                .put("allowAdd", result.allowAdd)
                .put("preventAdd", result.preventAdd)
                .put("noteIds", JSONArray().apply { result.noteIds.forEach { put(it) } })
                .toString()
        }.getOrElse {
            Log.w("HoshiLookupPopup", "duplicateCheck failed", it)
            """{"duplicate":false,"noteIds":[]}"""
        }
    }

    @JavascriptInterface
    fun viewDuplicate(noteIdsJson: String): Boolean {
        val noteIds = runCatching {
            val array = JSONArray(noteIdsJson)
            List(array.length()) { index -> array.optLong(index, 0L) }
                .filter { it > 0L }
                .distinct()
        }.getOrDefault(emptyList())
        if (noteIds.isEmpty()) return false
        return runCatching {
            callbackHolder.callbacks.onViewDuplicate(noteIds)
        }.getOrElse {
            Log.w("HoshiLookupPopup", "viewDuplicate failed", it)
            false
        }
    }
}

private fun JSONObject.toSelectionData(
    offsetX: Double,
    offsetY: Double,
): ReaderSelectionData? {
    val rect = optJSONObject("rect") ?: return null
    return ReaderSelectionData(
        text = optString("text"),
        sentence = optString("sentence"),
        rect = ReaderSelectionRect(
            x = offsetX + rect.optDouble("x"),
            y = offsetY + rect.optDouble("y"),
            width = rect.optDouble("width"),
            height = rect.optDouble("height"),
        ),
        normalizedOffset = opt("normalizedOffset")?.let { if (it == JSONObject.NULL) null else (it as? Number)?.toInt() },
        sentenceOffset = opt("sentenceOffset")?.let { if (it == JSONObject.NULL) null else (it as? Number)?.toInt() },
    )
}
