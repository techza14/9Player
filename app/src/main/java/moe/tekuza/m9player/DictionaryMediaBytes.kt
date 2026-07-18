package moe.tekuza.m9player

import android.content.Context
import android.net.Uri
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

internal const val DICTIONARY_MEDIA_RESPONSE_MAX_BYTES = 8L * 1024L * 1024L

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
                val bytes = input.readDictionaryMediaBytesLimited(bundled.sizeBytes)
                    ?: return@getOrLoad null
                logDebug("BookLookupTap") {
                    "media load bundled hit uri=$mappedUri mime=${bundled.mimeType} bytes=${bytes.size}"
                }
                return@getOrLoad DictionaryMediaPayload(
                    mimeType = DictionaryMediaByteCache.normalizeMime(bundled.mimeType),
                    bytes = bytes
                )
            }
        }
        null
    }
}

private fun InputStream.readDictionaryMediaBytesLimited(knownSize: Long): ByteArray? {
    if (knownSize > DICTIONARY_MEDIA_RESPONSE_MAX_BYTES) return null
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read.toLong()
        if (total > DICTIONARY_MEDIA_RESPONSE_MAX_BYTES) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun mapDictionaryMediaRequestUri(requestUri: Uri): Uri? {
    val scheme = requestUri.scheme?.lowercase(Locale.ROOT).orEmpty()
    if (scheme == "dictres") return requestUri
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
