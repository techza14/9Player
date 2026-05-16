package moe.tekuza.m9player

import android.content.Context
import android.net.Uri
import android.util.LruCache
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

internal data class DictionaryMediaPayload(
    val mimeType: String,
    val bytes: ByteArray
)

private object DictionaryMediaByteCache {
    // Keep a bounded in-memory media cache for fast popup image first paint.
    // Keep this conservative; the app can run with a 256 MiB heap.
    private const val MAX_CACHE_BYTES = 8 * 1024 * 1024
    private const val MAX_CACHEABLE_PAYLOAD_BYTES = 2 * 1024 * 1024
    private const val CACHE_KEY_UNKNOWN = "application/octet-stream"

    private data class InFlightLoad(
        val latch: CountDownLatch = CountDownLatch(1),
        @Volatile var result: DictionaryMediaPayload? = null
    )

    private val lock = Any()
    private val clearExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "DictionaryMediaCacheClear").apply { isDaemon = true }
    }
    private val inFlightLoads = HashMap<String, InFlightLoad>()

    private var cache = createCache()

    private fun createCache() = object : LruCache<String, DictionaryMediaPayload>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: DictionaryMediaPayload): Int {
            return value.bytes.size.coerceAtLeast(1)
        }
    }

    fun getOrLoad(key: String, loader: () -> DictionaryMediaPayload?): DictionaryMediaPayload? {
        var inFlight: InFlightLoad? = null
        var isLoaderOwner = false
        synchronized(lock) {
            cache.get(key)?.let { return it }
            inFlight = inFlightLoads[key]
            if (inFlight == null) {
                inFlight = InFlightLoad()
                inFlightLoads[key] = inFlight!!
                isLoaderOwner = true
            }
        }

        if (isLoaderOwner) {
            val loaded = runCatching { loader() }.getOrNull()
            synchronized(lock) {
                if (
                    loaded != null &&
                    loaded.bytes.isNotEmpty() &&
                    loaded.bytes.size <= MAX_CACHEABLE_PAYLOAD_BYTES
                ) {
                    cache.put(key, loaded)
                }
                inFlight!!.result = loaded
                inFlightLoads.remove(key)
                inFlight!!.latch.countDown()
            }
            return loaded
        }

        inFlight!!.latch.await()
        synchronized(lock) {
            cache.get(key)?.let { return it }
        }
        return inFlight!!.result
    }

    fun clearAsync() {
        clearExecutor.execute {
            val oldCache: LruCache<String, DictionaryMediaPayload> = synchronized(lock) {
                val previous = cache
                cache = createCache()
                previous
            }
            oldCache.evictAll()
        }
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
    val mappedUri = mapDictionaryMediaRequestUri(requestUri) ?: return null
    val key = mappedUri.toString()
    logDebug("BookLookupTap") { "media load request uri=$requestUri mapped=$mappedUri key=$key" }
    return DictionaryMediaByteCache.getOrLoad(key) {
        val bundled = openBundledDictionaryResource(context, mappedUri)
        if (bundled != null) {
            bundled.inputStream.use { input ->
                val bytes = input.readBytes()
                logDebug("BookLookupTap") {
                    "media load bundled hit uri=$mappedUri mime=${bundled.mimeType} bytes=${bytes.size}"
                }
                return@getOrLoad DictionaryMediaPayload(
                    mimeType = DictionaryMediaByteCache.normalizeMime(bundled.mimeType),
                    bytes = bytes
                )
            }
        }
        val mounted = openMountedMdictResource(context, mappedUri) ?: return@getOrLoad null
        mounted.inputStream.use { input ->
            val bytes = input.readBytes()
            logDebug("BookLookupTap") {
                "media load mounted hit uri=$mappedUri mime=${mounted.mimeType} bytes=${bytes.size}"
            }
            DictionaryMediaPayload(
                mimeType = DictionaryMediaByteCache.normalizeMime(mounted.mimeType),
                bytes = bytes
            )
        }
    }
}

private fun mapDictionaryMediaRequestUri(requestUri: Uri): Uri? {
    val scheme = requestUri.scheme?.lowercase(Locale.ROOT).orEmpty()
    if (scheme == "dictres" || scheme == "mdictres") return requestUri
    if (scheme != "https" && scheme != "http") return null
    if (requestUri.host != "hoshi.local") return null
    if (requestUri.path != "/image") return null

    val dictionary = requestUri.getQueryParameter("dictionary").orEmpty().trim()
    val mediaPath = requestUri.getQueryParameter("path").orEmpty().trim()
    if (dictionary.isBlank() || mediaPath.isBlank()) return null
    return Uri.parse("dictres://$dictionary/${Uri.encode(mediaPath)}")
}

internal fun clearDictionaryMediaPayloadCache() {
    DictionaryMediaByteCache.clearAsync()
}

internal fun buildDictionaryWebResourceResponse(
    payload: DictionaryMediaPayload
): WebResourceResponse {
    logDebug("BookLookupTap") { "media response mime=${payload.mimeType} bytes=${payload.bytes.size}" }
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
