package moe.tekuza.m9player

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.manhhao.hoshi.LookupResult
import moe.tekuza.m9player.hoshi.features.dictionary.HoshiImagePreviewWebView
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupAssets
import moe.tekuza.m9player.hoshi.features.dictionary.PopupLookupResultsHolder
import moe.tekuza.m9player.hoshi.features.dictionary.PopupMessageWebViewClient
import moe.tekuza.m9player.hoshi.features.dictionary.PopupWebViewBridge
import moe.tekuza.m9player.hoshi.features.dictionary.PopupWebViewCallbackHolder
import moe.tekuza.m9player.hoshi.features.dictionary.PopupWebViewCallbacks
import moe.tekuza.m9player.hoshi.features.dictionary.PopupWebViewOffsetState
import moe.tekuza.m9player.hoshi.features.dictionary.withAdditionalImageTap
import moe.tekuza.m9player.hoshi.webview.applyHoshiWebViewSecurityDefaults

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun MainHoshiResultWebView(
    html: String,
    results: List<LookupResult>,
    clearSelectionSignal: Int,
    callbacks: PopupWebViewCallbacks,
    selectionOffsetX: Double = 0.0,
    selectionOffsetY: Double = 0.0,
    windowOffsetAdjustmentX: Double = 0.0,
    windowOffsetAdjustmentY: Double = 0.0,
    modifier: Modifier = Modifier,
) {
    val currentCallbacks by rememberUpdatedState(callbacks)
    val context = LocalContext.current
    var previewImageUrl by remember { mutableStateOf<String?>(null) }
    val effectiveCallbacks = currentCallbacks.withAdditionalImageTap { src -> previewImageUrl = src }
    val assets = remember(context) { LookupPopupAssets.load(context) }
    val callbackHolder = remember { PopupWebViewCallbackHolder(effectiveCallbacks) }
    val lookupResultsHolder = remember { PopupLookupResultsHolder(results) }
    val offsetState = remember {
        PopupWebViewOffsetState(
            selectionOffsetX = selectionOffsetX,
            selectionOffsetY = selectionOffsetY,
            windowOffsetAdjustmentX = windowOffsetAdjustmentX,
            windowOffsetAdjustmentY = windowOffsetAdjustmentY,
        )
    }
    var loadedHtml by remember { mutableStateOf<String?>(null) }
    var appliedClearSelectionSignal by remember { mutableStateOf(clearSelectionSignal) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                applyHoshiWebViewSecurityDefaults()
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(
                        PopupWebViewBridge(
                            webView = this,
                            callbackHolder = callbackHolder,
                            lookupResultsHolder = lookupResultsHolder,
                            offsetState = offsetState,
                        ),
                        "HoshiPopup",
                    )
                webViewClient = PopupMessageWebViewClient(callbackHolder, assets)
            }
        },
        update = { webView ->
            callbackHolder.callbacks = effectiveCallbacks
            offsetState.selectionOffsetX = selectionOffsetX
            offsetState.selectionOffsetY = selectionOffsetY
            offsetState.windowOffsetAdjustmentX = windowOffsetAdjustmentX
            offsetState.windowOffsetAdjustmentY = windowOffsetAdjustmentY
            webView.webViewClient = PopupMessageWebViewClient(callbackHolder, assets)
            if (loadedHtml != html) {
                lookupResultsHolder.results = results
                loadedHtml = html
                webView.loadDataWithBaseURL(
                    "https://hoshi.local/dictionary/",
                    html,
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            if (appliedClearSelectionSignal != clearSelectionSignal) {
                appliedClearSelectionSignal = clearSelectionSignal
                webView.evaluateJavascript("window.hoshiSelection.clearSelection()", null)
            }
        },
    )
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
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
