package moe.tekuza.m9player

import android.webkit.WebView

internal fun destroyWebViewSafely(webView: WebView) {
    runCatching { webView.stopLoading() }
    runCatching { webView.loadUrl("about:blank") }
    runCatching { webView.clearHistory() }
    runCatching { webView.removeAllViews() }
    runCatching { webView.destroy() }
}
