package moe.tekuza.m9player

import android.util.Log
import org.json.JSONObject

internal data class MdictNativeImportResult(
    val success: Boolean,
    val title: String,
    val termCount: Long,
    val mediaCount: Long,
    val entriesFile: String,
    val errors: List<String>
)

internal data class MdictNativeExtractMddResult(
    val success: Boolean,
    val mediaCount: Long,
    val errors: List<String>
)

internal data class MdictNativeLookupHit(
    val term: String,
    val reading: String?,
    val definition: String,
    val matchedLength: Int,
    val score: Int
)

internal object MdictNativeBridge {
    private const val NATIVE_LIBRARY_NAME = "tset_native"
    private const val LOG_TAG = "MdictNative"

    private val loaded: Boolean = runCatching {
        System.loadLibrary(NATIVE_LIBRARY_NAME)
        true
    }.getOrElse { false }

    internal val isAvailable: Boolean
        get() = loaded

    internal fun lookup(
        entriesPath: String,
        query: String,
        maxResults: Int = 16,
        scanLength: Int = 16
    ): List<MdictNativeLookupHit> {
        if (!loaded) return emptyList()
        val safeEntriesPath = entriesPath.trim()
        val safeQuery = query.trim()
        if (safeEntriesPath.isBlank() || safeQuery.isBlank()) return emptyList()
        val raw = runCatching {
            nativeLookup(
                safeEntriesPath,
                safeQuery,
                maxResults.coerceIn(1, 128),
                scanLength.coerceAtLeast(1)
            )
        }.getOrElse { return emptyList() }
        return parseLookupResult(raw)
    }

    internal fun lookupMdx(
        mdxPath: String,
        cacheKey: String,
        query: String,
        maxResults: Int = 16,
        scanLength: Int = 16
    ): List<MdictNativeLookupHit> {
        if (!loaded) return emptyList()
        val safeMdxPath = mdxPath.trim()
        val safeCacheKey = cacheKey.trim()
        val safeQuery = query.trim()
        if (safeMdxPath.isBlank() || safeQuery.isBlank()) return emptyList()
        val raw = runCatching {
            nativeLookupMdx(
                safeMdxPath,
                safeCacheKey,
                safeQuery,
                maxResults.coerceIn(1, 128),
                scanLength.coerceAtLeast(1)
            )
        }.getOrElse { return emptyList() }
        return parseLookupResult(raw)
    }

    internal fun clearLookupCache() {
        if (!loaded) return
        runCatching { nativeClearLookupCache() }
    }

    internal fun importMdx(mdxPath: String, outputDir: String): MdictNativeImportResult {
        if (!loaded) {
            return MdictNativeImportResult(
                success = false,
                title = "",
                termCount = 0L,
                mediaCount = 0L,
                entriesFile = "",
                errors = listOf("Native mdict library not loaded")
            )
        }
        val raw = runCatching { nativeImportMdx(mdxPath, outputDir) }
            .getOrElse { throwable ->
                return MdictNativeImportResult(
                    success = false,
                    title = "",
                    termCount = 0L,
                    mediaCount = 0L,
                    entriesFile = "",
                    errors = listOf(throwable.message ?: "native mdict import failed")
                )
            }
        return parseImportResult(raw)
    }

    private fun parseImportResult(raw: String): MdictNativeImportResult {
        return runCatching {
            val json = JSONObject(raw)
            val errors = mutableListOf<String>()
            val topError = json.optString("error").trim()
            if (topError.isNotBlank()) errors += topError
            val errorsArray = json.optJSONArray("errors")
            if (errorsArray != null) {
                for (i in 0 until errorsArray.length()) {
                    val value = errorsArray.optString(i).trim()
                    if (value.isNotBlank()) errors += value
                }
            }
            MdictNativeImportResult(
                success = json.optBoolean("success", false),
                title = json.optString("title").trim(),
                termCount = json.optLong("termCount", 0L).coerceAtLeast(0L),
                mediaCount = json.optLong("mediaCount", 0L).coerceAtLeast(0L),
                entriesFile = json.optString("entriesFile").trim(),
                errors = errors
            )
        }.getOrElse {
            MdictNativeImportResult(
                success = false,
                title = "",
                termCount = 0L,
                mediaCount = 0L,
                entriesFile = "",
                errors = listOf("Invalid native mdict import result")
            )
        }
    }

    private fun parseLookupResult(raw: String): List<MdictNativeLookupHit> {
        return runCatching {
            val root = JSONObject(raw)
            val rootError = root.optString("error").trim()
            val arr = root.optJSONArray("results") ?: return emptyList()
            val parsed = buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val term = item.optString("term").trim()
                    if (term.isBlank()) continue
                    val definition = item.optString("definition").trim()
                    add(
                        MdictNativeLookupHit(
                            term = term,
                            reading = item.optString("reading").trim().ifBlank { null },
                            definition = definition,
                            matchedLength = item.optInt("matchedLength", 0).coerceAtLeast(0),
                            score = item.optInt("score", 0)
                        )
                    )
                }
            }
            if (parsed.isEmpty() && rootError.isNotBlank()) {
                Log.d(LOG_TAG, "lookup empty with error=$rootError")
            }
            parsed
        }.getOrDefault(emptyList())
    }

    internal fun extractMdd(mddPath: String, outputDir: String): MdictNativeExtractMddResult {
        if (!loaded) {
            return MdictNativeExtractMddResult(
                success = false,
                mediaCount = 0L,
                errors = listOf("Native mdict library not loaded")
            )
        }
        val raw = runCatching { nativeExtractMdd(mddPath, outputDir) }
            .getOrElse { throwable ->
                return MdictNativeExtractMddResult(
                    success = false,
                    mediaCount = 0L,
                    errors = listOf(throwable.message ?: "native mdict extract mdd failed")
                )
            }
        return runCatching {
            val json = JSONObject(raw)
            val errors = mutableListOf<String>()
            val topError = json.optString("error").trim()
            if (topError.isNotBlank()) errors += topError
            val errorsArray = json.optJSONArray("errors")
            if (errorsArray != null) {
                for (i in 0 until errorsArray.length()) {
                    val value = errorsArray.optString(i).trim()
                    if (value.isNotBlank()) errors += value
                }
            }
            MdictNativeExtractMddResult(
                success = json.optBoolean("success", false),
                mediaCount = json.optLong("mediaCount", 0L).coerceAtLeast(0L),
                errors = errors
            )
        }.getOrElse {
            MdictNativeExtractMddResult(
                success = false,
                mediaCount = 0L,
                errors = listOf("Invalid native mdict extract mdd result")
            )
        }
    }

    @JvmStatic
    private external fun nativeImportMdx(mdxPath: String, outputDir: String): String

    @JvmStatic
    private external fun nativeExtractMdd(mddPath: String, outputDir: String): String

    @JvmStatic
    private external fun nativeLookup(entriesPath: String, query: String, maxResults: Int, scanLength: Int): String

    @JvmStatic
    private external fun nativeLookupMdx(
        mdxPath: String,
        cacheKey: String,
        query: String,
        maxResults: Int,
        scanLength: Int
    ): String

    @JvmStatic
    private external fun nativeClearLookupCache()
}
