package moe.tekuza.m9player

import org.json.JSONObject

internal data class MdictNativeImportResult(
    val success: Boolean,
    val title: String,
    val termCount: Long,
    val entriesFile: String,
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
                scanLength.coerceIn(1, 64)
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
                entriesFile = json.optString("entriesFile").trim(),
                errors = errors
            )
        }.getOrElse {
            MdictNativeImportResult(
                success = false,
                title = "",
                termCount = 0L,
                entriesFile = "",
                errors = listOf("Invalid native mdict import result")
            )
        }
    }

    private fun parseLookupResult(raw: String): List<MdictNativeLookupHit> {
        return runCatching {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("results") ?: return emptyList()
            buildList {
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
        }.getOrDefault(emptyList())
    }

    @JvmStatic
    private external fun nativeImportMdx(mdxPath: String, outputDir: String): String

    @JvmStatic
    private external fun nativeLookup(entriesPath: String, query: String, maxResults: Int, scanLength: Int): String

    @JvmStatic
    private external fun nativeClearLookupCache()
}
