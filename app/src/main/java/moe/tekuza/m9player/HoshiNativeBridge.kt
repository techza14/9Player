package moe.tekuza.m9player

import org.json.JSONArray
import org.json.JSONObject

internal data class HoshiImportResult(
    val success: Boolean,
    val title: String,
    val termCount: Long,
    val dictPath: String,
    val errors: List<String>
)

internal data class HoshiLookupHit(
    val term: String,
    val reading: String?,
    val dictionary: String,
    val glossaryRaw: String,
    val frequency: String?,
    val pitch: String?,
    val score: Int,
    val matchedLength: Int
)

internal object HoshiNativeBridge {
    private const val NATIVE_LIBRARY_NAME = "tset_native"
    private const val LOOKUP_CACHE_MAX = 256

    private data class LookupCacheKey(
        val dictionaryKey: String,
        val query: String,
        val maxResults: Int,
        val scanLength: Int
    )

    private val lookupCache = object : LinkedHashMap<LookupCacheKey, List<HoshiLookupHit>>(LOOKUP_CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LookupCacheKey, List<HoshiLookupHit>>): Boolean {
            return size > LOOKUP_CACHE_MAX
        }
    }

    private val loaded: Boolean = runCatching {
        System.loadLibrary(NATIVE_LIBRARY_NAME)
        true
    }.getOrElse { false }

    internal val isAvailable: Boolean
        get() = loaded

    internal fun importZip(zipPath: String, outputDir: String, lowRam: Boolean = false): HoshiImportResult {
        if (!loaded) {
            return HoshiImportResult(
                success = false,
                title = "",
                termCount = 0L,
                dictPath = "",
                errors = listOf("Native hoshidicts library not loaded")
            )
        }
        val raw = runCatching {
            nativeImportZip(zipPath, outputDir, lowRam)
        }.getOrElse { throwable ->
            return HoshiImportResult(
                success = false,
                title = "",
                termCount = 0L,
                dictPath = "",
                errors = listOf(throwable.message ?: "native import failed")
            )
        }
        return parseImportResult(raw)
    }

    internal fun lookup(
        dictionaryPaths: List<String>,
        query: String,
        maxResults: Int,
        scanLength: Int
    ): List<HoshiLookupHit> {
        if (!loaded || dictionaryPaths.isEmpty() || query.isBlank()) return emptyList()
        val normalizedQuery = query.trim()
        val normalizedMaxResults = maxResults.coerceAtLeast(1)
        val normalizedScanLength = scanLength.coerceAtLeast(1)
        val dictionaryKey = dictionaryPaths.joinToString(separator = "\u0001") { it.trim() }
        val cacheKey = LookupCacheKey(
            dictionaryKey = dictionaryKey,
            query = normalizedQuery,
            maxResults = normalizedMaxResults,
            scanLength = normalizedScanLength
        )
        synchronized(lookupCache) {
            lookupCache[cacheKey]?.let { return it }
        }
        val raw = runCatching {
            nativeLookup(
                dictionaryPaths.toTypedArray(),
                normalizedQuery,
                normalizedMaxResults,
                normalizedScanLength
            )
        }.getOrElse { return emptyList() }
        val parsed = parseLookupResult(raw)
        synchronized(lookupCache) {
            lookupCache[cacheKey] = parsed
        }
        return parsed
    }

    internal fun clearLookupCache() {
        if (!loaded) return
        synchronized(lookupCache) {
            lookupCache.clear()
        }
        runCatching { nativeClearLookupCache() }
    }

    private fun parseImportResult(raw: String): HoshiImportResult {
        return runCatching {
            val json = JSONObject(raw)
            val errors = mutableListOf<String>()
            val errorsArray = json.optJSONArray("errors")
            if (errorsArray != null) {
                for (i in 0 until errorsArray.length()) {
                    val value = errorsArray.optString(i).trim()
                    if (value.isNotBlank()) errors += value
                }
            }
            val topError = json.optString("error").trim()
            if (topError.isNotBlank()) errors += topError
            HoshiImportResult(
                success = json.optBoolean("success", false),
                title = json.optString("title").trim(),
                termCount = json.optLong("termCount", 0L).coerceAtLeast(0L),
                dictPath = json.optString("dictPath").trim(),
                errors = errors
            )
        }.getOrElse {
            HoshiImportResult(
                success = false,
                title = "",
                termCount = 0L,
                dictPath = "",
                errors = listOf("Invalid native import result")
            )
        }
    }

    private fun parseLookupResult(raw: String): List<HoshiLookupHit> {
        return runCatching {
            val json = JSONObject(raw)
            val array = json.optJSONArray("results") ?: JSONArray()
            val out = ArrayList<HoshiLookupHit>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val term = obj.optString("term").trim()
                val dictionary = obj.optString("dictionary").trim()
                val glossary = obj.optString("glossary").trim()
                if (term.isBlank() || dictionary.isBlank() || glossary.isBlank()) continue
                val reading = obj.optString("reading").trim().ifBlank { null }
                val frequency = obj.optString("frequency").trim().ifBlank { null }
                val pitch = obj.optString("pitch").trim().ifBlank { null }
                val score = obj.optInt("score", 0)
                val matchedLength = obj.optInt("matchedLength", term.length).coerceAtLeast(0)
                out += HoshiLookupHit(
                    term = term,
                    reading = reading,
                    dictionary = dictionary,
                    glossaryRaw = glossary,
                    frequency = frequency,
                    pitch = pitch,
                    score = score,
                    matchedLength = matchedLength
                )
            }
            out
        }.getOrElse { emptyList() }
    }

    @JvmStatic
    private external fun nativeImportZip(zipPath: String, outputDir: String, lowRam: Boolean): String

    @JvmStatic
    private external fun nativeLookup(
        dictionaryPaths: Array<String>,
        query: String,
        maxResults: Int,
        scanLength: Int
    ): String

    @JvmStatic
    private external fun nativeClearLookupCache()

}
