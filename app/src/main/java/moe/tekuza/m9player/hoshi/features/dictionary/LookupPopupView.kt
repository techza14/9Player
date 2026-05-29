package moe.tekuza.m9player.hoshi.features.dictionary

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.manhhao.hoshi.HoshiDicts
import de.manhhao.hoshi.LookupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.tekuza.m9player.hoshi.webview.applyHoshiWebViewSecurityDefaults
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionData
import moe.tekuza.m9player.AnkiDuplicateCheckResult
import moe.tekuza.m9player.HoshiCardBackground
import moe.tekuza.m9player.HoshiDarkCardBackground
import moe.tekuza.m9player.HoshiDarkPopupBorder
import moe.tekuza.m9player.R
import moe.tekuza.m9player.decodeSampledBitmap
import moe.tekuza.m9player.destroyWebViewSafely
import moe.tekuza.m9player.logDebug

private val LookupPopupActionBarHeight = 44.dp
private const val HOSHI_LOOKUP_POPUP_LOG_TAG = "HoshiLookupPopup"
private const val HOSHI_PREVIEW_MAX_SIDE_PX = 2048

@Composable
internal fun LookupPopupView(
    state: LookupPopupState,
    onSwipeDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    clearSelectionSignal: Int = 0,
    onTapOutside: () -> Unit = onSwipeDismiss,
    onTextSelected: (ReaderSelectionData) -> Int? = { null },
    onRangeSelection: (() -> Unit)? = null,
    onMineEntry: ((String) -> Boolean)? = null,
    onMineEntryAsync: ((String, (Boolean) -> Unit) -> Unit)? = null,
    onDuplicateCheck: ((String) -> AnkiDuplicateCheckResult)? = null,
    onDuplicateCheckAsync: ((String, (AnkiDuplicateCheckResult) -> Unit) -> Unit)? = null,
    onViewDuplicate: ((List<Long>) -> Boolean)? = null,
    onPlayWordAudio: ((String, String?, String?) -> Unit)? = null,
    onImageTap: ((String) -> Unit)? = null,
    onCloseAll: (() -> Unit)? = null,
    showActionBar: Boolean = false,
    showCloseAll: Boolean = false,
    warmShell: Boolean = false,
    contentResetKey: Any? = null,
    isPopupActive: Boolean = true,
    isContentVisible: Boolean = true,
    onLookupRedirect: (String) -> List<LookupResult> = { query ->
        moe.tekuza.m9player.hoshi.dictionary.LookupEngine.lookup(
            query,
            state.dictionarySettings.maxResults,
            state.dictionarySettings.scanLength,
        )
    },
    onLookupRedirected: (ReaderSelectionData) -> Unit = {},
) {
    if (state.results.isEmpty() && !warmShell) {
        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
            "view skipped reason=empty_results selection='${state.selection.text.take(32)}' rect=${state.selection.rect.x},${state.selection.rect.y} ${state.selection.rect.width}x${state.selection.rect.height}"
        }
        return
    }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val assets = remember(context) { LookupPopupAssets.load(context) }
    val htmlResults = if (warmShell) emptyList() else state.results
    val html = remember(
        htmlResults,
        state.dictionaryStyles,
        state.dictionarySettings,
        state.swipeToDismiss,
        state.swipeThreshold,
        state.darkMode,
        state.eInkMode,
        state.audioSettings,
        state.showPlayAudio,
        state.showRangeSelection,
    ) {
        LookupPopupHtml.render(
            results = htmlResults,
            assets = assets,
            dictionaryStyles = state.dictionaryStyles,
            settings = state.dictionarySettings,
            audioSettings = state.audioSettings,
            showPlayAudio = state.showPlayAudio,
            swipeToDismiss = state.swipeToDismiss,
            swipeThreshold = state.swipeThreshold,
            darkMode = state.darkMode,
            eInkMode = state.eInkMode,
            showRangeSelection = state.showRangeSelection,
        )
    }
    var contentReady by remember(html, contentResetKey) { mutableStateOf(false) }
    var previewImageUrl by remember { mutableStateOf<String?>(null) }
    val frame = remember(
        state.selection.rect,
        state.avoidRects,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        state.width,
        state.height,
        state.isVertical,
        state.isFullWidth,
        state.topInset,
        state.bottomInset,
    ) {
        LookupPopupLayout(
            selectionRect = state.selection.rect,
            avoidRects = state.avoidRects,
            screenWidth = configuration.screenWidthDp.toDouble(),
            screenHeight = configuration.screenHeightDp.toDouble(),
            maxWidth = state.width.toDouble(),
            maxHeight = state.height.toDouble() + if (showActionBar) LookupPopupActionBarHeight.value.toDouble() else 0.0,
            isVertical = state.isVertical,
            isFullWidth = state.isFullWidth,
            topInset = state.topInset,
            bottomInset = state.bottomInset,
        ).calculate()
    }
    val frameX = frame.centerX - frame.width / 2
    val frameY = frame.centerY - frame.height / 2
    val effectiveFrameX = if (isPopupActive) frameX else -10000.0
    val effectiveFrameY = if (isPopupActive) frameY else -10000.0
    val popupShape = if (state.eInkMode) RectangleShape else RoundedCornerShape(18.dp)
    val popupBackground = if (state.darkMode) HoshiDarkCardBackground else HoshiCardBackground
    val contentBackground = if (state.darkMode) HoshiDarkCardBackground else HoshiCardBackground
    val popupBorder = when {
        state.eInkMode && state.darkMode -> Color.White
        state.eInkMode -> Color.Black
        state.darkMode -> HoshiDarkPopupBorder
        else -> Color(0x477A7F87)
    }
    logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
        "view active=$isPopupActive visible=$isContentVisible warmShell=$warmShell showActionBar=$showActionBar popupActionBar=${state.popupActionBar} hasCloseAll=${onCloseAll != null} results=${state.results.size} " +
            "selectionRect(screenDp)=${state.selection.rect.x},${state.selection.rect.y} ${state.selection.rect.width}x${state.selection.rect.height} " +
            "avoidRects=${state.avoidRects.size} " +
            "popupFrame(screenDp)=${frameX},${frameY} ${frame.width}x${frame.height} " +
            "popupTopGapDp=${frameY - (state.selection.rect.y + state.selection.rect.height)}"
    }
    val positionProvider = remember(effectiveFrameX, effectiveFrameY, density.density) {
        val densityScale = density.density.coerceAtLeast(0.1f)
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                return IntOffset(
                    x = (effectiveFrameX * densityScale).toInt(),
                    y = (effectiveFrameY * densityScale).toInt()
                )
            }
        }
    }

    Popup(
        popupPositionProvider = positionProvider,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = false
        )
    ) {
        Surface(
            modifier = Modifier
                .then(if (isPopupActive) Modifier.width(frame.width.dp).height(frame.height.dp) else Modifier.size(1.dp))
                .alpha(if (contentReady && isPopupActive && isContentVisible) 1f else 0f)
                .clip(popupShape)
                .background(popupBackground),
            shape = popupShape,
            color = popupBackground,
            border = BorderStroke(1.dp, popupBorder),
            tonalElevation = 0.dp,
            shadowElevation = if (state.eInkMode) 0.dp else 10.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(10.dp),
                    shape = popupShape,
                    color = contentBackground,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    LookupPopupWebView(
                        html = html,
                        results = state.results,
                        assets = assets,
                        darkMode = state.darkMode,
                        backgroundColor = contentBackground,
                        selectionOffsetX = frameX + 10.0,
                        selectionOffsetY = frameY + 10.0,
                        clearSelectionSignal = clearSelectionSignal,
                        warmShell = warmShell,
                        callbacks = PopupWebViewCallbacks(
                            onTapOutside = onTapOutside,
                            onSwipeDismiss = onSwipeDismiss,
                            onRangeSelection = onRangeSelection ?: {},
                            onPlayWordAudio = onPlayWordAudio ?: { _, _, _ -> },
                            onImageTap = { src ->
                                onImageTap?.invoke(src)
                                if (isHoshiPreviewBitmapCandidate(src)) {
                                    previewImageUrl = src
                                }
                            },
                            onMineEntry = onMineEntry ?: { false },
                            onMineEntryAsync = onMineEntryAsync,
                            onDuplicateCheck = onDuplicateCheck ?: { AnkiDuplicateCheckResult() },
                            onDuplicateCheckAsync = onDuplicateCheckAsync,
                            onViewDuplicate = onViewDuplicate ?: { false },
                            onOpenLink = { rawUrl ->
                                val uri = runCatching { Uri.parse(rawUrl.trim()) }.getOrNull() ?: return@PopupWebViewCallbacks
                                val scheme = uri.scheme?.lowercase().orEmpty()
                                if (scheme !in setOf("http", "https", "mailto", "tel")) return@PopupWebViewCallbacks
                                val popupContext = context.applicationContext
                                val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { popupContext.startActivity(intent) }
                            },
                            onTextSelected = onTextSelected,
                            onLookupRedirect = onLookupRedirect,
                            onLookupRedirected = { selection, results ->
                                logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
                                    "redirected query='${selection.text.take(32)}' resultCount=${results.size} freqCount=${results.firstOrNull()?.term?.frequencies?.size ?: 0} pitchCount=${results.firstOrNull()?.term?.pitches?.size ?: 0} rect=${selection.rect.x},${selection.rect.y} ${selection.rect.width}x${selection.rect.height}"
                                }
                                onLookupRedirected(selection)
                            },
                            onContentReady = { contentReady = true },
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (showActionBar) {
                    LookupPopupActionBar(
                        onClose = onSwipeDismiss,
                        showCloseAll = showCloseAll,
                        onCloseAll = onCloseAll,
                    )
                }
            }
        }
    }
    previewImageUrl?.takeIf { it.isNotBlank() }?.let { imageUrl ->
        Dialog(
            onDismissRequest = { previewImageUrl = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { previewImageUrl = null },
                contentAlignment = Alignment.Center,
            ) {
                HoshiImagePreviewWebView(
                    imageUrl = imageUrl,
                    onTap = { previewImageUrl = null },
                    modifier = Modifier
                        .fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun LookupPopupActionBar(
    onClose: () -> Unit,
    showCloseAll: Boolean,
    onCloseAll: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LookupPopupActionBarHeight)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onClose,
            colors = ButtonDefaults.textButtonColors(
                contentColor = Color(0xFF32679A)
            ),
            contentPadding = ButtonDefaults.ContentPadding
        ) {
            Text(text = stringResource(R.string.common_close))
        }
        if (showCloseAll && onCloseAll != null) {
            TextButton(
                onClick = onCloseAll,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color(0xFF32679A)
                ),
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                Text(text = stringResource(R.string.common_close_all))
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LookupPopupWebView(
    html: String,
    results: List<LookupResult>,
    assets: LookupPopupAssets,
    darkMode: Boolean,
    backgroundColor: Color,
    selectionOffsetX: Double,
    selectionOffsetY: Double,
    clearSelectionSignal: Int,
    backSignal: Int = 0,
    forwardSignal: Int = 0,
    warmShell: Boolean = false,
    callbacks: PopupWebViewCallbacks,
    modifier: Modifier = Modifier,
) {
    val callbackHolder = remember { PopupWebViewCallbackHolder(callbacks) }
    callbackHolder.callbacks = callbacks
    val lookupResultsHolder = remember { PopupLookupResultsHolder(results) }
    val contentReadyGate = remember { PopupContentReadyGate() }
    val webViewHolder = remember { mutableStateOf<WebView?>(null) }
    val offsetState = remember {
        PopupWebViewOffsetState(
            selectionOffsetX = selectionOffsetX,
            selectionOffsetY = selectionOffsetY,
        )
    }
    var loadedHtml by remember { mutableStateOf<String?>(null) }
    var appliedClearSelectionSignal by remember { mutableStateOf(clearSelectionSignal) }
    var appliedBackSignal by remember { mutableStateOf(backSignal) }
    var appliedForwardSignal by remember { mutableStateOf(forwardSignal) }
    var shellReady by remember { mutableStateOf(false) }
    var appliedWarmResults by remember { mutableStateOf<List<LookupResult>?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            webViewHolder.value?.let(::destroyWebViewSafely)
            webViewHolder.value = null
        }
    }
    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
        factory = { context ->
            WebView(context).apply {
                webViewHolder.value = this
                applyHoshiWebViewSecurityDefaults()
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(backgroundColor.toArgb())
                addJavascriptInterface(
                    PopupWebViewBridge(
                        webView = this,
                        callbackHolder = callbackHolder,
                        lookupResultsHolder = lookupResultsHolder,
                        offsetState = offsetState,
                        contentReadyGate = contentReadyGate,
                        onShellReady = { shellReady = true },
                    ),
                    "HoshiPopup",
                )
                webViewClient = PopupMessageWebViewClient(callbackHolder, assets)
            }
        },
        update = { webView ->
            callbackHolder.callbacks = callbacks
            offsetState.selectionOffsetX = selectionOffsetX
            offsetState.selectionOffsetY = selectionOffsetY
            webView.setBackgroundColor(backgroundColor.toArgb())
            if (loadedHtml != html) {
                loadedHtml = html
                shellReady = false
                appliedWarmResults = null
                contentReadyGate.reset()
                if (!warmShell) {
                    lookupResultsHolder.results = results
                }
                webView.loadDataWithBaseURL(
                    "https://hoshi.local/popup/",
                    html,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            if (warmShell && shellReady && appliedWarmResults !== results) {
                appliedWarmResults = results
                lookupResultsHolder.results = results
                contentReadyGate.reset()
                webView.evaluateJavascript("window.replacePopupResults && window.replacePopupResults(${results.size})", null)
            } else if (!warmShell && shellReady) {
                lookupResultsHolder.results = results
            }
            if (appliedClearSelectionSignal != clearSelectionSignal) {
                appliedClearSelectionSignal = clearSelectionSignal
                webView.evaluateJavascript("window.hoshiSelection.clearSelection()", null)
            }
            if (appliedBackSignal != backSignal) {
                appliedBackSignal = backSignal
                webView.evaluateJavascript("window.navigateBack()", null)
            }
            if (appliedForwardSignal != forwardSignal) {
                appliedForwardSignal = forwardSignal
                webView.evaluateJavascript("window.navigateForward()", null)
            }
        },
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun HoshiImagePreviewWebView(
    imageUrl: String,
    onTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val previewState = produceState<Pair<Boolean, ImageBitmap?>>(initialValue = false to null, key1 = imageUrl) {
        value = true to withContext(Dispatchers.IO) {
            decodeHoshiPreviewImageBitmap(imageUrl)
        }
    }.value
    val previewBitmap = previewState.second
    if (previewState.first && previewBitmap == null) {
        LaunchedEffect(imageUrl) { onTap() }
    }
    if (previewBitmap != null) {
        Image(
            bitmap = previewBitmap,
            contentDescription = null,
            modifier = modifier
                .background(Color.Black)
                .padding(12.dp)
                .clickable { onTap() },
            contentScale = ContentScale.Fit,
        )
    } else {
        Box(modifier = modifier.clickable { onTap() })
    }
}

private fun decodeHoshiPreviewImageBitmap(rawUrl: String): ImageBitmap? {
    val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return null
    val dictionary: String
    val path: String
    when {
        uri.scheme == "image" -> {
            dictionary = uri.getQueryParameter("dictionary").orEmpty()
            path = uri.getQueryParameter("path").orEmpty()
        }
        uri.scheme == "https" && uri.host == "hoshi.local" && uri.path == "/image" -> {
            dictionary = uri.getQueryParameter("dictionary").orEmpty()
            path = uri.getQueryParameter("path").orEmpty()
        }
        else -> return null
    }
    if (dictionary.isBlank() || path.isBlank()) return null
    return runCatching {
        val data = HoshiDicts.getMediaFile(HoshiDicts.lookupObject, dictionary, path)
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        logDebug(HOSHI_LOOKUP_POPUP_LOG_TAG) {
            "imagePreview bitmap hit dictionary=$dictionary path=$path bytes=${data.size}"
        }
        decodeSampledBitmap(
            bytes = data,
            targetWidthPx = HOSHI_PREVIEW_MAX_SIDE_PX,
            targetHeightPx = HOSHI_PREVIEW_MAX_SIDE_PX,
        )
            ?.asImageBitmap()
    }.getOrNull()
}

internal fun isHoshiPreviewBitmapCandidate(rawUrl: String): Boolean {
    val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
    val path = when {
        uri.scheme == "image" -> uri.getQueryParameter("path").orEmpty()
        uri.scheme == "https" && uri.host == "hoshi.local" && uri.path == "/image" -> {
            uri.getQueryParameter("path").orEmpty()
        }
        else -> return false
    }
    return when (path.substringAfterLast('.', missingDelimiterValue = "").lowercase()) {
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> true
        else -> false
    }
}
