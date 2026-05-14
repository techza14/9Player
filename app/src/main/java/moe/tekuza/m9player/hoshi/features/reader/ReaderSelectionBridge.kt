package moe.tekuza.m9player.hoshi.features.reader

import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

private const val READER_SELECTION_BRIDGE_LOG_TAG = "HoshiSelectionBridge"

internal class ReaderSelectionBridge(
    private val webView: WebView,
    private val onTextSelected: (ReaderSelectionData) -> Int?,
) {
    @JavascriptInterface
    fun postMessage(message: String) {
        val postStartNs = SystemClock.elapsedRealtimeNanos()
        Log.d(READER_SELECTION_BRIDGE_LOG_TAG, "postMessage received jsonLen=${message.length}")
        val data = ReaderSelectionBridgePayload.fromJson(message) ?: return
        webView.post {
            Log.d(
                READER_SELECTION_BRIDGE_LOG_TAG,
                "postMessage dispatch textLen=${data.text.length} rect=${data.rect.x.toInt()},${data.rect.y.toInt()} ${data.rect.width.toInt()}x${data.rect.height.toInt()}"
            )
            val highlightCount = onTextSelected(data) ?: return@post
            Log.d(
                READER_SELECTION_BRIDGE_LOG_TAG,
                "postMessage callback highlightCount=$highlightCount elapsedMs=${(SystemClock.elapsedRealtimeNanos() - postStartNs) / 1_000_000L}"
            )
            webView.evaluateJavascript(ReaderSelectionCommand.HighlightSelection(highlightCount).source, null)
        }
    }
}

internal object ReaderSelectionBridgePayload {
    fun fromJson(message: String): ReaderSelectionData? {
        val payload = runCatching { JSONObject(message) }.getOrNull() ?: return null
        val rect = payload.optJSONObject("rect") ?: return null
        return ReaderSelectionData(
            text = payload.optString("text"),
            sentence = payload.optString("sentence"),
            rect = ReaderSelectionRect(
                x = rect.optDouble("x"),
                y = rect.optDouble("y"),
                width = rect.optDouble("width"),
                height = rect.optDouble("height"),
            ),
            normalizedOffset = payload.opt("normalizedOffset")?.let { if (it == JSONObject.NULL) null else (it as? Number)?.toInt() },
            sentenceOffset = payload.opt("sentenceOffset")?.let { if (it == JSONObject.NULL) null else (it as? Number)?.toInt() },
            textRects = payload.optJSONArray("textRects").toTextRects(),
        )
    }

    private fun JSONArray?.toTextRects(): List<ReaderSelectionTextRect> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index) ?: continue
                val rect = item.optJSONObject("rect") ?: continue
                add(
                    ReaderSelectionTextRect(
                        startOffset = item.optInt("startOffset", 0),
                        endOffset = item.optInt("endOffset", 0),
                        rect = ReaderSelectionRect(
                            x = rect.optDouble("x"),
                            y = rect.optDouble("y"),
                            width = rect.optDouble("width"),
                            height = rect.optDouble("height"),
                        )
                    )
                )
            }
        }
    }
}
