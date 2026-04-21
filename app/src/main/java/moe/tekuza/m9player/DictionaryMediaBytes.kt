package moe.tekuza.m9player

import android.content.Context
import android.net.Uri
import android.util.LruCache
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale

internal data class DictionaryMediaPayload(
    val mimeType: String,
    val bytes: ByteArray
)

private object DictionaryMediaByteCache {
    // Keep a bounded in-memory media cache for fast popup image first paint.
    // 24 MiB is enough for most dictionary image bursts without excessive memory risk.
    private const val MAX_CACHE_BYTES = 24 * 1024 * 1024
    private const val CACHE_KEY_UNKNOWN = "application/octet-stream"

    private val cache = object : LruCache<String, DictionaryMediaPayload>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: DictionaryMediaPayload): Int {
            return value.bytes.size.coerceAtLeast(1)
        }
    }

    @Synchronized
    fun getOrLoad(key: String, loader: () -> DictionaryMediaPayload?): DictionaryMediaPayload? {
        cache.get(key)?.let { return it }
        val loaded = loader() ?: return null
        if (loaded.bytes.isNotEmpty()) {
            cache.put(key, loaded)
        }
        return loaded
    }

    @Synchronized
    fun clear() {
        cache.evictAll()
    }

    fun normalizeMime(mimeType: String?): String {
        return mimeType
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: CACHE_KEY_UNKNOWN
    }
}

internal fun loadDictionaryMediaPayload(
    context: Context,
    requestUri: Uri
): DictionaryMediaPayload? {
    val scheme = requestUri.scheme?.lowercase(Locale.ROOT).orEmpty()
    if (scheme != "dictres" && scheme != "mdictres") return null
    val key = requestUri.toString()
    return DictionaryMediaByteCache.getOrLoad(key) {
        val bundled = openBundledDictionaryResource(context, requestUri)
        if (bundled != null) {
            bundled.inputStream.use { input ->
                val bytes = input.readBytes()
                return@getOrLoad DictionaryMediaPayload(
                    mimeType = DictionaryMediaByteCache.normalizeMime(bundled.mimeType),
                    bytes = bytes
                )
            }
        }
        val mounted = openMountedMdictResource(context, requestUri) ?: return@getOrLoad null
        mounted.inputStream.use { input ->
            val bytes = input.readBytes()
            DictionaryMediaPayload(
                mimeType = DictionaryMediaByteCache.normalizeMime(mounted.mimeType),
                bytes = bytes
            )
        }
    }
}

internal fun clearDictionaryMediaPayloadCache() {
    DictionaryMediaByteCache.clear()
}

internal fun buildDictionaryWebResourceResponse(
    payload: DictionaryMediaPayload
): WebResourceResponse {
    val encoding = if (payload.mimeType.startsWith("text/", ignoreCase = true)) "utf-8" else null
    val headers = mutableMapOf(
        "Cache-Control" to "public, max-age=31536000, immutable"
    )
    return WebResourceResponse(
        payload.mimeType,
        encoding,
        200,
        "OK",
        headers,
        ByteArrayInputStream(payload.bytes)
    )
}
