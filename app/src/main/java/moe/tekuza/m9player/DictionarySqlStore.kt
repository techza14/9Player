package moe.tekuza.m9player

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import android.util.Log
import android.util.JsonReader
import android.util.JsonToken
import moe.tekuza.m9player.hoshi.dictionary.HoshiDictionaryQuerySession
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.StringReader
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

private const val DICTIONARY_ENTRY_STORE_DIR = "dictionary_entry_store"
private const val DICTIONARY_HOSHI_ROOT_DIR = "hoshidicts"
private const val DICTIONARY_HOSHI_INFO_FILE = "info.json"
private const val DICTIONARY_HOSHI_INDEX_FILE = "index.json"
private const val DICTIONARY_HOSHI_BLOBS_FILE = "blobs.bin"
private const val DICTIONARY_HOSHI_OFFSETS_FILE = "offsets.bin"
private const val DICTIONARY_HOSHI_HASH_FILE = "hash.mph"
private const val DICTIONARY_HOSHI_HASH_TABLE_FILE = "hash.table"
private const val DICTIONARY_HOSHI_STYLES_FILE = "styles.css"
private const val HOSHI_LOOKUP_PERF_LOG_TAG = "HoshiLookupPerf"
private const val HOSHI_META_TYPE_SCAN_LIMIT_ROWS = 2048
private const val HOSHI_META_TYPE_SCAN_MAX_BYTES = 8L * 1024L * 1024L
private const val HOSHI_TYPE_SCAN_MAX_ENTRIES = 20_000
private const val HOSHI_TYPE_SCAN_MAX_ENTRY_BYTES = 64L * 1024L * 1024L
private const val HOSHI_TYPE_SCAN_MAX_PATH_CHARS = 512
private const val HOSHI_IMPORT_ARCHIVE_MAX_BYTES = 2L * 1024L * 1024L * 1024L
private const val HOSHI_IMPORT_COPY_BUFFER_BYTES = 256 * 1024

private val NORMALIZE_WHITESPACE_REGEX = Regex("\\s+")
private val STRIP_HTML_TAGS_REGEX = Regex("<[^>]+>")
private val LOOKS_LIKE_HTML_REGEX = Regex("<\\s*/?\\s*[a-zA-Z][^>]*>")
private val CAMEL_CASE_BOUNDARY_REGEX = Regex("([a-z])([A-Z])")
private val MARKDOWN_IMAGE_REGEX = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)")
private val MARKDOWN_LINK_REGEX = Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)")
private val PLAIN_URL_REGEX = Regex("https?://[^\\s<]+")
private val DICTIONARY_STORAGE_SAFE_KEY_REGEX = Regex("[^A-Za-z0-9._-]")
private val HOSHI_TERM_BANK_FILE_REGEX = Regex("term_bank_\\d+\\.json")
private val HOSHI_TERM_META_BANK_FILE_REGEX = Regex("term_meta_bank_\\d+\\.json")
private val HTML_TAG_NAME_SANITIZE_REGEX = Regex("[^a-z0-9-]")
private val CSS_SIZE_UNIT_REGEX = Regex("^[a-z%]+$")
private val CSS_NUMBER_REGEX = Regex("^[+-]?(?:\\d+\\.?\\d*|\\.\\d+)$")
private val HOSHI_WINDOWS_ABSOLUTE_PATH_REGEX = Regex("""^[A-Za-z]:.*""")
private val DANGEROUS_HTML_BLOCK_REGEX =
    Regex("(?is)<\\s*(script|style|iframe|object|embed|form|textarea|select|button|svg|math)\\b[^>]*>.*?<\\s*/\\s*\\1\\s*>")
private val DANGEROUS_HTML_TAG_REGEX =
    Regex("(?is)<\\s*/?\\s*(script|style|link|meta|iframe|object|embed|form|input|button|textarea|select|option|base|svg|math)\\b[^>]*>")
private val HTML_EVENT_ATTRIBUTE_REGEX =
    Regex("(?is)\\s+on[a-z0-9_-]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)")
private val HTML_SRCDOC_ATTRIBUTE_REGEX =
    Regex("(?is)\\s+srcdoc\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)")
private val HTML_DANGEROUS_URL_ATTRIBUTE_REGEX =
    Regex("(?is)\\s+(href|src|xlink:href)\\s*=\\s*(\"\\s*(?:javascript:|vbscript:|data:text/html)[^\"]*\"|'\\s*(?:javascript:|vbscript:|data:text/html)[^']*'|(?:javascript:|vbscript:|data:text/html)[^\\s>]*)")
private val STRUCTURED_ALLOWED_HTML_TAGS = setOf(
    "a", "b", "blockquote", "br", "code", "dd", "del", "details", "dfn", "div", "dl", "dt",
    "em", "i", "img", "ins", "kbd", "li", "mark", "ol", "p", "pre", "rp", "rt", "ruby",
    "s", "samp", "small", "span", "strong", "sub", "summary", "sup", "table", "tbody",
    "td", "tfoot", "th", "thead", "tr", "u", "ul", "var"
)
private val DANGEROUS_INLINE_STYLE_TOKENS = listOf(
    "javascript:",
    "vbscript:",
    "data:text/html",
    "expression(",
    "-moz-binding"
)
private var hoshiLookupPreparedKey: String? = null
private val hoshiLookupPreparedLock = Any()


private fun dictionaryStorageRootDir(context: Context): File {
    val dir = File(context.filesDir, DICTIONARY_ENTRY_STORE_DIR)
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun dictionaryStorageSafeKey(cacheKey: String): String {
    return cacheKey.trim().ifBlank { "unknown" }.replace(DICTIONARY_STORAGE_SAFE_KEY_REGEX, "_")
}

private fun dictionaryStorageDir(context: Context, cacheKey: String): File {
    val dir = File(dictionaryStorageRootDir(context), dictionaryStorageSafeKey(cacheKey))
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun dictionaryHoshiRootDir(context: Context, cacheKey: String): File {
    val dir = File(dictionaryStorageDir(context, cacheKey), DICTIONARY_HOSHI_ROOT_DIR)
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun dictionaryHoshiTypeRootDir(
    context: Context,
    cacheKey: String,
    type: HoshiDictionaryType
): File {
    val dir = File(dictionaryHoshiRootDir(context, cacheKey), type.directoryName)
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun isValidHoshiDictionaryDir(dir: File): Boolean {
    if (!dir.isDirectory) return false
    if (!File(dir, DICTIONARY_HOSHI_BLOBS_FILE).isFile) return false
    val legacy = File(dir, DICTIONARY_HOSHI_INFO_FILE).isFile &&
        File(dir, DICTIONARY_HOSHI_OFFSETS_FILE).isFile &&
        File(dir, DICTIONARY_HOSHI_HASH_FILE).isFile
    val current = File(dir, DICTIONARY_HOSHI_INDEX_FILE).isFile &&
        File(dir, DICTIONARY_HOSHI_HASH_TABLE_FILE).isFile
    return legacy || current
}

private fun normalizeHoshiZipPath(path: String): String {
    return path.replace('\\', '/').trimStart('/').removePrefix("./")
}

private fun isSafeHoshiZipPath(path: String): Boolean {
    val normalized = path.trim().replace('\\', '/')
    if (
        normalized.isBlank() ||
        normalized.length > HOSHI_TYPE_SCAN_MAX_PATH_CHARS ||
        normalized.startsWith("/") ||
        HOSHI_WINDOWS_ABSOLUTE_PATH_REGEX.matches(normalized)
    ) {
        return false
    }
    return normalized.split('/').none { it == ".." }
}

private fun isHoshiTermBankFile(path: String): Boolean {
    val fileName = normalizeHoshiZipPath(path).substringAfterLast('/').lowercase(Locale.US)
    return HOSHI_TERM_BANK_FILE_REGEX.matches(fileName)
}

private fun isHoshiTermMetaBankFile(path: String): Boolean {
    val fileName = normalizeHoshiZipPath(path).substringAfterLast('/').lowercase(Locale.US)
    return HOSHI_TERM_META_BANK_FILE_REGEX.matches(fileName)
}

private fun inferHoshiDictionaryTypeFromPath(dir: File): HoshiDictionaryType? {
    var current: File? = dir
    while (current != null) {
        when (current.name) {
            HoshiDictionaryType.Term.directoryName -> return HoshiDictionaryType.Term
            HoshiDictionaryType.Frequency.directoryName -> return HoshiDictionaryType.Frequency
            HoshiDictionaryType.Pitch.directoryName -> return HoshiDictionaryType.Pitch
        }
        current = current.parentFile
    }
    return null
}

private fun detectHoshiDictionaryType(
    contentResolver: ContentResolver,
    uri: Uri
): HoshiDictionaryType {
    return contentResolver.openInputStream(uri)?.use { stream ->
        detectHoshiDictionaryType(stream)
    } ?: HoshiDictionaryType.Term
}

private fun detectHoshiDictionaryType(zipFile: File): HoshiDictionaryType {
    var hasTermBank = false
    var hasFrequencyMeta = false
    var hasPitchMeta = false

    ZipFile(zipFile).use { zip ->
        val entries = zip.entries()
        var entryCount = 0
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            entryCount += 1
            require(entryCount <= HOSHI_TYPE_SCAN_MAX_ENTRIES) {
                "Dictionary ZIP has too many entries: $entryCount"
            }
            require(isSafeHoshiZipPath(entry.name)) {
                "Unsafe dictionary ZIP entry: ${entry.name}"
            }
            if (entry.size >= 0L) {
                require(entry.size <= HOSHI_TYPE_SCAN_MAX_ENTRY_BYTES) {
                    "Dictionary ZIP entry too large: ${entry.size} bytes"
                }
            }
            if (!entry.isDirectory) {
                val path = normalizeHoshiZipPath(entry.name)
                when {
                    isHoshiTermBankFile(path) -> {
                        hasTermBank = true
                    }

                    isHoshiTermMetaBankFile(path) -> {
                        val modeFlags = zip.getInputStream(entry).use(::readHoshiMetaModeFlags)
                        if (modeFlags.hasFrequency) hasFrequencyMeta = true
                        if (modeFlags.hasPitch) hasPitchMeta = true
                    }
                }
            }
            if (hasFrequencyMeta) {
                break
            }
        }
    }

    return classifyHoshiDictionaryType(hasTermBank, hasFrequencyMeta, hasPitchMeta)
}

internal fun detectHoshiDictionaryType(
    stream: java.io.InputStream
): HoshiDictionaryType {
    var hasTermBank = false
    var hasFrequencyMeta = false
    var hasPitchMeta = false

    ZipInputStream(BufferedInputStream(stream)).use { zip ->
        var entryCount = 0
        var entry = zip.nextEntry
        while (entry != null) {
            entryCount += 1
            require(entryCount <= HOSHI_TYPE_SCAN_MAX_ENTRIES) {
                "Dictionary ZIP has too many entries: $entryCount"
            }
            require(isSafeHoshiZipPath(entry.name)) {
                "Unsafe dictionary ZIP entry: ${entry.name}"
            }
            if (entry.size >= 0L) {
                require(entry.size <= HOSHI_TYPE_SCAN_MAX_ENTRY_BYTES) {
                    "Dictionary ZIP entry too large: ${entry.size} bytes"
                }
            }
            if (!entry.isDirectory) {
                val path = normalizeHoshiZipPath(entry.name)
                when {
                    isHoshiTermBankFile(path) -> {
                        hasTermBank = true
                    }

                    isHoshiTermMetaBankFile(path) -> {
                        val modeFlags = readHoshiMetaModeFlags(zip)
                        if (modeFlags.hasFrequency) hasFrequencyMeta = true
                        if (modeFlags.hasPitch) hasPitchMeta = true
                    }
                }
            }
            if (hasFrequencyMeta) {
                break
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }

    return classifyHoshiDictionaryType(hasTermBank, hasFrequencyMeta, hasPitchMeta)
}

private fun classifyHoshiDictionaryType(
    hasTermBank: Boolean,
    hasFrequencyMeta: Boolean,
    hasPitchMeta: Boolean
): HoshiDictionaryType {
    return when {
        hasTermBank -> HoshiDictionaryType.Term
        hasFrequencyMeta && !hasPitchMeta -> HoshiDictionaryType.Frequency
        hasPitchMeta && !hasFrequencyMeta -> HoshiDictionaryType.Pitch
        hasFrequencyMeta -> HoshiDictionaryType.Frequency
        hasPitchMeta -> HoshiDictionaryType.Pitch
        else -> HoshiDictionaryType.Term
    }
}

private data class HoshiMetaModeFlags(
    val hasFrequency: Boolean = false,
    val hasPitch: Boolean = false
)

private fun readHoshiMetaModeFlags(stream: java.io.InputStream): HoshiMetaModeFlags {
    return try {
        val reader = InputStreamReader(stream, Charsets.UTF_8)
        var flags = HoshiMetaModeFlags()
        var arrayDepth = 0
        var rowFieldIndex = -1
        var rowCount = 0
        var scannedBytes = 0L
        var inString = false
        var escaped = false
        var capturingMode = false
        val modeBuilder = StringBuilder()

        while (true) {
            val read = reader.read()
            if (read < 0) break
            scannedBytes += 1
            require(scannedBytes <= HOSHI_META_TYPE_SCAN_MAX_BYTES) {
                "Dictionary meta scan exceeded byte limit"
            }
            val char = read.toChar()

            if (inString) {
                when {
                    escaped -> {
                        if (capturingMode) modeBuilder.append(char)
                        escaped = false
                    }

                    char == '\\' -> {
                        escaped = true
                    }

                    char == '"' -> {
                        inString = false
                        if (capturingMode) {
                            flags = flags.withMode(modeBuilder.toString())
                            capturingMode = false
                            modeBuilder.clear()
                            if (flags.hasFrequency && flags.hasPitch) break
                        }
                    }

                    capturingMode -> modeBuilder.append(char)
                }
                continue
            }

            when (char) {
                '"' -> {
                    inString = true
                    if (arrayDepth == 2 && rowFieldIndex == 1) {
                        capturingMode = true
                        modeBuilder.clear()
                    }
                }

                '[' -> {
                    arrayDepth += 1
                    if (arrayDepth == 2) {
                        rowFieldIndex = 0
                        rowCount += 1
                        if (rowCount > HOSHI_META_TYPE_SCAN_LIMIT_ROWS) break
                    }
                }

                ']' -> {
                    if (arrayDepth == 2) rowFieldIndex = -1
                    if (arrayDepth > 0) arrayDepth -= 1
                }

                ',' -> {
                    if (arrayDepth == 2 && rowFieldIndex >= 0) {
                        rowFieldIndex += 1
                    }
                }
            }
        }
        flags
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (_: Throwable) {
        HoshiMetaModeFlags()
    }
}

private fun HoshiMetaModeFlags.with(other: HoshiMetaModeFlags): HoshiMetaModeFlags {
    return HoshiMetaModeFlags(
        hasFrequency = hasFrequency || other.hasFrequency,
        hasPitch = hasPitch || other.hasPitch
    )
}

private fun HoshiMetaModeFlags.withMode(mode: String): HoshiMetaModeFlags {
    val normalizedMode = mode.lowercase(Locale.US)
    return with(
        HoshiMetaModeFlags(
            hasFrequency = normalizedMode == "freq",
            hasPitch = normalizedMode == "pitch" || normalizedMode.contains("accent")
        )
    )
}

private fun locateHoshiDictionaryDir(
    context: Context,
    cacheKey: String,
    type: HoshiDictionaryType? = null
): File? {
    val root = File(dictionaryStorageDir(context, cacheKey), DICTIONARY_HOSHI_ROOT_DIR)
    if (!root.isDirectory) return null
    val searchRoots = if (type != null) {
        listOf(File(root, type.directoryName))
    } else {
        HoshiDictionaryType.entries.map { File(root, it.directoryName) }
    }
    return searchRoots.asSequence()
        .filter { it.isDirectory }
        .flatMap { directory ->
            directory.listFiles()?.asSequence() ?: emptySequence()
        }
        .filter { isValidHoshiDictionaryDir(it) }
        .sortedByDescending { it.lastModified() }
        .firstOrNull()
        ?: root.listFiles()
            ?.filter { isValidHoshiDictionaryDir(it) }
            ?.sortedByDescending { it.lastModified() }
            ?.firstOrNull()
}

private fun deleteDictionaryStorageDir(context: Context, cacheKey: String): Boolean {
    val dir = File(dictionaryStorageRootDir(context), dictionaryStorageSafeKey(cacheKey))
    if (!dir.exists()) return false
    return runCatching { dir.deleteRecursively() }.getOrElse { false }
}

private fun clearHoshiLookupPreparation() {
    synchronized(hoshiLookupPreparedLock) {
        hoshiLookupPreparedKey = null
    }
}

internal fun invalidateDictionaryLookupCaches() {
    clearHoshiLookupPreparation()
    HoshiNativeBridge.clearLookupCache()
}

private data class HoshiDictionaryBinding(
    val dictionary: LoadedDictionary,
    val dictionaryDir: File,
    val dictionaryType: HoshiDictionaryType
)

private fun collectHoshiDictionaryBindings(
    context: Context,
    dictionaries: List<LoadedDictionary>
): List<HoshiDictionaryBinding> {
    if (!HoshiNativeBridge.isAvailable) return emptyList()
    return dictionaries.mapNotNull { dictionary ->
        val cacheKey = dictionary.cacheKey.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val type = runCatching { HoshiDictionaryType.valueOf(dictionary.dictionaryType) }
            .getOrDefault(HoshiDictionaryType.Term)
        val requestedDir = locateHoshiDictionaryDir(context, cacheKey, type)
        val dir = requestedDir
            ?: locateHoshiDictionaryDir(context, cacheKey, null)
            ?: return@mapNotNull null
        val resolvedType = when {
            requestedDir != null -> type
            type != HoshiDictionaryType.Term -> type
            else -> inferHoshiDictionaryTypeFromPath(dir) ?: type
        }
        HoshiDictionaryBinding(dictionary = dictionary, dictionaryDir = dir, dictionaryType = resolvedType)
    }
}

private fun prepareHoshiLookupIfNeeded(bindings: List<HoshiDictionaryBinding>) {
    if (bindings.isEmpty()) return
    val signature = bindings.joinToString(separator = "\n") { binding ->
        "${binding.dictionary.cacheKey.trim()}\u0001${binding.dictionaryType.name}\u0001${binding.dictionaryDir.absolutePath}"
    }
    synchronized(hoshiLookupPreparedLock) {
        if (hoshiLookupPreparedKey == signature) return
        val prepareStartNs = SystemClock.elapsedRealtimeNanos()
        val termBindings = ArrayList<HoshiDictionaryBinding>()
        val freqBindings = ArrayList<HoshiDictionaryBinding>()
        val pitchBindings = ArrayList<HoshiDictionaryBinding>()
        bindings.forEach { binding ->
            when (binding.dictionaryType) {
                HoshiDictionaryType.Term -> termBindings += binding
                HoshiDictionaryType.Frequency -> freqBindings += binding
                HoshiDictionaryType.Pitch -> pitchBindings += binding
            }
        }
        val termPaths = termBindings.map { it.dictionaryDir.absolutePath }.toTypedArray()
        val freqPaths = freqBindings.map { it.dictionaryDir.absolutePath }.toTypedArray()
        val pitchPaths = pitchBindings.map { it.dictionaryDir.absolutePath }.toTypedArray()
        logDebug(HOSHI_LOOKUP_PERF_LOG_TAG) {
            "rebuildQuery start dictCount=${bindings.size} signatureHash=${signature.hashCode()}"
        }
        logDebug("HoshiLookupPopup") {
            "prepareHoshiLookup dictCount=${bindings.size} termPaths=${termPaths.size} freqPaths=${freqPaths.size} pitchPaths=${pitchPaths.size} " +
                "terms=${termBindings.joinToString { it.dictionary.name }} " +
                "freqs=${freqBindings.joinToString { it.dictionary.name }} " +
                "pitches=${pitchBindings.joinToString { it.dictionary.name }}"
        }
        HoshiDictionaryQuerySession.rebuild(
            termPaths,
            freqPaths,
            pitchPaths
        )
        hoshiLookupPreparedKey = signature
        logDebug(HOSHI_LOOKUP_PERF_LOG_TAG) {
            "rebuildQuery done elapsedMs=${(SystemClock.elapsedRealtimeNanos() - prepareStartNs) / 1_000_000L}"
        }
    }
}

internal fun prepareHoshiLookupForDictionaries(
    context: Context,
    dictionaries: List<LoadedDictionary>
): Int {
    val bindings = collectHoshiDictionaryBindings(context, dictionaries)
    prepareHoshiLookupIfNeeded(bindings)
    return bindings.size
}

internal fun loadDictionaryFromStorage(
    context: Context,
    cacheKey: String,
    dictionaryType: String = HoshiDictionaryType.Term.name,
    fallbackDisplayName: String = "Dictionary"
): LoadedDictionary? {
    if (cacheKey.isBlank()) return null
    return runCatching {
        val resolvedType = runCatching { HoshiDictionaryType.valueOf(dictionaryType) }
            .getOrDefault(HoshiDictionaryType.Term)
        val requestedDir = locateHoshiDictionaryDir(context, cacheKey, resolvedType)
        val dictionaryDir = requestedDir
            ?: locateHoshiDictionaryDir(context, cacheKey, null)
            ?: return null
        val infoFile = listOf(
            File(dictionaryDir, DICTIONARY_HOSHI_INDEX_FILE),
            File(dictionaryDir, DICTIONARY_HOSHI_INFO_FILE),
        ).firstOrNull(File::isFile)
        val infoJson = runCatching {
            infoFile?.let { JSONObject(it.readText(Charsets.UTF_8)) }
        }.getOrNull()
        val resolvedName = infoJson?.optString("title")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackDisplayName.substringBeforeLast('.').trim().ifBlank { "Dictionary" }
        val resolvedDictionaryType = when {
            requestedDir != null -> resolvedType
            resolvedType != HoshiDictionaryType.Term -> resolvedType
            else -> inferHoshiDictionaryTypeFromPath(dictionaryDir) ?: resolvedType
        }
        val resolvedCount = infoJson?.optInt("termCount", -1)
            ?.takeIf { it >= 0 }
            ?: 0
        val stylesCss = runCatching {
            val stylesFile = File(dictionaryDir, DICTIONARY_HOSHI_STYLES_FILE)
            if (stylesFile.isFile) stylesFile.readText(Charsets.UTF_8).trim().ifBlank { null } else null
        }.getOrNull()
        LoadedDictionary(
            cacheKey = cacheKey,
            name = resolvedName,
            format = "Yomichan/Migaku ZIP (hoshidicts)",
            dictionaryType = resolvedDictionaryType.name,
            entries = emptyList(),
            stylesCss = stylesCss,
            entryCount = resolvedCount
        )
    }.getOrNull()
}

internal fun loadPersistedDictionaryFromStorage(
    context: Context,
    ref: PersistedDictionaryRef,
    fallbackDisplayName: String = "Dictionary"
): Pair<PersistedDictionaryRef, LoadedDictionary>? {
    val displayName = ref.name.ifBlank { fallbackDisplayName }
    val cacheKey = ref.cacheKey ?: buildDictionaryCacheKey(ref.uri, displayName)
    val loaded = loadDictionaryFromStorage(
        context = context,
        cacheKey = cacheKey,
        dictionaryType = ref.dictionaryType,
        fallbackDisplayName = displayName
    ) ?: return null
    return ref.copy(
        name = loaded.name.ifBlank { displayName },
        cacheKey = cacheKey,
        dictionaryType = loaded.dictionaryType
    ) to loaded
}

internal fun deleteDictionaryStorage(context: Context, cacheKey: String): Boolean {
    if (cacheKey.isBlank()) return true
    val storageDeleted = deleteDictionaryStorageDir(context, cacheKey)
    if (storageDeleted) {
        clearHoshiLookupPreparation()
        HoshiNativeBridge.clearLookupCache()
    }
    return storageDeleted
}

internal fun importDictionaryFromZip(
    context: Context,
    contentResolver: ContentResolver,
    uri: Uri,
    displayName: String,
    cacheKey: String,
    onProgress: ((DictionaryImportProgress) -> Unit)? = null
): LoadedDictionary {
    require(displayName.trim().lowercase(Locale.US).endsWith(".zip")) {
        "Only ZIP dictionaries are supported"
    }
    val imported = importDictionaryZipWithHoshi(
        context = context,
        contentResolver = contentResolver,
        uri = uri,
        displayName = displayName,
        cacheKey = cacheKey,
        onProgress = onProgress
    )
    clearHoshiLookupPreparation()
    HoshiNativeBridge.clearLookupCache()
    return imported
}

private fun importDictionaryZipWithHoshi(
    context: Context,
    contentResolver: ContentResolver,
    uri: Uri,
    displayName: String,
    cacheKey: String,
    onProgress: ((DictionaryImportProgress) -> Unit)?
): LoadedDictionary {
    if (!HoshiNativeBridge.isAvailable) {
        error("hoshidicts native bridge unavailable")
    }

    onProgress?.invoke(DictionaryImportProgress(stage = "准备导入", current = 0, total = 100))
    val tempZip = File.createTempFile("dict_import_", ".zip", context.cacheDir)
    try {
        val archiveSize = queryDictionaryImportSize(contentResolver, uri).takeIf { it > 0L }
        archiveSize?.let { size ->
            require(size <= HOSHI_IMPORT_ARCHIVE_MAX_BYTES) {
                "Dictionary archive too large: $size bytes"
            }
        }
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempZip).use { output ->
                val buffer = ByteArray(HOSHI_IMPORT_COPY_BUFFER_BYTES)
                var copied = 0L
                var lastProgress = -1
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    require(copied <= HOSHI_IMPORT_ARCHIVE_MAX_BYTES) {
                        "Dictionary archive too large: $copied bytes"
                    }
                    archiveSize?.let { totalBytes ->
                        val progress = ((copied.toDouble() / totalBytes.toDouble()) * 30.0)
                            .toInt()
                            .coerceIn(0, 30)
                        if (progress != lastProgress) {
                            lastProgress = progress
                            onProgress?.invoke(DictionaryImportProgress(stage = "读取辞典文件", current = progress, total = 100))
                        }
                    }
                }
            }
        } ?: error("Unable to read dictionary archive")
        onProgress?.invoke(DictionaryImportProgress(stage = "分析辞典", current = 35, total = 100))

        val dictionaryType = detectHoshiDictionaryType(tempZip)
        logDebug(HOSHI_LOOKUP_PERF_LOG_TAG) {
            "auto classify import uri=${uri} type=${dictionaryType.name}"
        }
        val hoshiTypeRoot = dictionaryHoshiTypeRootDir(context, cacheKey, dictionaryType)
        if (hoshiTypeRoot.exists()) {
            hoshiTypeRoot.deleteRecursively()
        }
        hoshiTypeRoot.mkdirs()

        onProgress?.invoke(DictionaryImportProgress(stage = "导入辞典，可能需要几分钟", current = 0, total = 0))
        onProgress?.invoke(DictionaryImportProgress(stage = "整理辞典", current = 95, total = 100))
        HoshiNativeBridge.clearLookupCache()
        clearHoshiLookupPreparation()
        val nativeResult = HoshiNativeBridge.importZip(
            zipPath = tempZip.absolutePath,
            outputDir = hoshiTypeRoot.absolutePath,
            lowRam = true
        )
        if (!nativeResult.success) {
            val errorDetail = nativeResult.errors.firstOrNull().orEmpty()
            val message = if (errorDetail.isBlank()) "hoshidicts import failed" else "hoshidicts import failed: $errorDetail"
            error(message)
        }

        val importedDir = nativeResult.dictPath
            .takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf(::isValidHoshiDictionaryDir)
            ?: locateHoshiDictionaryDir(context, cacheKey, dictionaryType)
            ?: error("hoshidicts output not found")

        val dictionaryName = nativeResult.title.ifBlank {
            importedDir.name.ifBlank {
                displayName.substringBeforeLast('.').ifBlank { "Dictionary" }
            }
        }
        val stylesCss = runCatching {
            val stylesFile = File(importedDir, DICTIONARY_HOSHI_STYLES_FILE)
            if (stylesFile.isFile) {
                stylesFile.readText(Charsets.UTF_8).trim().ifBlank { null }
            } else {
                null
            }
        }.getOrNull()
        val entryCount = (nativeResult.termCount.takeIf { it > 0L } ?: nativeResult.metaCount)
            .coerceAtLeast(0L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        HoshiNativeBridge.clearLookupCache()
        onProgress?.invoke(DictionaryImportProgress(stage = "完成", current = 100, total = 100))
        return LoadedDictionary(
            cacheKey = cacheKey,
            name = dictionaryName,
            format = "Yomichan/Migaku ZIP (hoshidicts)",
            dictionaryType = dictionaryType.name,
            entries = emptyList(),
            stylesCss = stylesCss,
            entryCount = entryCount
        )
    } finally {
        runCatching { tempZip.delete() }
    }
}

private fun queryDictionaryImportSize(contentResolver: ContentResolver, uri: Uri): Long {
    return runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use -1L
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index < 0 || cursor.isNull(index)) -1L else cursor.getLong(index)
        } ?: -1L
    }.getOrDefault(-1L)
}

internal fun glossaryRawToDefinitionHtmlSql(glossaryRaw: String): String {
    val trimmed = glossaryRaw.trim()
    if (trimmed.isBlank()) return ""
    val parsedDefinition = runCatching {
        val reader = JsonReader(StringReader(trimmed))
        reader.isLenient = true
        val value = readJsonValueSql(reader)
        extractGlossaryFromRawValueSql(value).firstOrNull().orEmpty()
    }.getOrNull()

    return parsedDefinition ?: normalizeDefinitionForDisplaySql(trimmed)
}

private fun readJsonValueSql(reader: JsonReader): Any? {
    return when (reader.peek()) {
        JsonToken.BEGIN_ARRAY -> {
            val list = mutableListOf<Any?>()
            reader.beginArray()
            while (reader.hasNext()) {
                list += readJsonValueSql(reader)
            }
            reader.endArray()
            list
        }

        JsonToken.BEGIN_OBJECT -> {
            val map = linkedMapOf<String, Any?>()
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                map[key] = readJsonValueSql(reader)
            }
            reader.endObject()
            map
        }

        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> {
            reader.nextNull()
            null
        }

        else -> {
            reader.skipValue()
            null
        }
    }
}

private fun extractGlossaryFromRawValueSql(value: Any?): List<String> {
    val definitions = mutableListOf<String>()
    fun collect(raw: Any?) {
        if (definitions.size >= 2) return
        val text = extractTextSnippetSql(raw)
        if (!text.isNullOrBlank()) definitions += text
    }

    when (value) {
        is List<*> -> value.forEach(::collect)
        else -> collect(value)
    }
    return compactDefinitionsSql(definitions)
}

private fun extractTextSnippetSql(value: Any?): String? {
    if (value == null) return null
    val raw = when (value) {
        is String -> value
        is Number, is Boolean -> value.toString()
        is List<*> -> buildString {
            value.forEach { child ->
                val text = extractTextSnippetSql(child) ?: return@forEach
                append(text)
            }
        }

        is Map<*, *> -> structuredMapToHtmlSql(value)
        else -> value.toString()
    }.trim()
    if (raw.isBlank()) return null
    return clampDefinitionLengthForStorageSql(normalizeDefinitionForDisplaySql(raw))
}

private fun structuredMapToHtmlSql(value: Map<*, *>): String {
    fun mapString(key: String): String = value[key]?.toString().orEmpty()

    val type = mapString("type").trim().lowercase(Locale.ROOT)
    if (type == "image") {
        val path = listOf("path", "src", "url")
            .map { mapString(it).trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        if (path.isBlank() || !isSafeDictionaryHtmlUrlSql(path)) return ""
        val dataAttributes = extractStructuredDataAttributesSql(value["data"]).toMutableMap()
        val explicitClass = mapString("class").trim()
        if (dataAttributes["class"].isNullOrBlank() && explicitClass.isNotBlank()) {
            dataAttributes["class"] = explicitClass
        }
        logDebug("HoshiLookupPopup") {
            "structured image(sql) dict=${mapString("dictionary").takeIf { it.isNotBlank() } ?: mapString("dict")} " +
                "path=${path.take(64)} class=${explicitClass.ifBlank { dataAttributes["class"].orEmpty() }} " +
                "dataKeys=${dataAttributes.keys.joinToString(",")} styleLen=${styleValueToCssSql(value["style"]).length} " +
                "lang=${mapString("lang").takeIf { it.isNotBlank() } ?: ""}"
        }
        val dataScAttrs = buildStructuredDataScAttributesSql(dataAttributes)
        val styleAttr = mergeInlineStyleSql(
            styleValueToCssSql(value["style"]),
            supplementalInlineStyleSql(value, "img")
        ).takeIf { it.isNotBlank() }?.let(::sanitizeInlineStyleSql)?.let {
            " style=\"${escapeHtmlAttributeSql(it)}\""
        } ?: ""
        val langAttr = mapString("lang").trim().takeIf { it.isNotBlank() }?.let {
            " lang=\"${escapeHtmlAttributeSql(it)}\""
        } ?: ""
        val inlineAttrs = buildInlineHtmlAttributesSql(value)
        return "<img$dataScAttrs$langAttr$styleAttr$inlineAttrs>"
    }

    val tagRaw = mapString("tag").trim().lowercase(Locale.ROOT)
    val content = extractTextSnippetSql(value["content"]).orEmpty()
    if (tagRaw.isNotBlank()) {
        val tag = tagRaw.replace(HTML_TAG_NAME_SANITIZE_REGEX, "")
        if (tag.isBlank() || tag !in STRUCTURED_ALLOWED_HTML_TAGS) return content

        val dataAttributes = extractStructuredDataAttributesSql(value["data"]).toMutableMap()
        val explicitClass = mapString("class").trim()
        if (dataAttributes["class"].isNullOrBlank() && explicitClass.isNotBlank()) {
            dataAttributes["class"] = explicitClass
        }
        val dataScAttrs = buildStructuredDataScAttributesSql(dataAttributes)
        val langAttr = mapString("lang").trim().takeIf { it.isNotBlank() }?.let {
            " lang=\"${escapeHtmlAttributeSql(it)}\""
        } ?: ""
        val styleAttr = mergeInlineStyleSql(
            styleValueToCssSql(value["style"]),
            supplementalInlineStyleSql(value, tag)
        ).takeIf { it.isNotBlank() }?.let(::sanitizeInlineStyleSql)?.let {
            " style=\"${escapeHtmlAttributeSql(it)}\""
        } ?: ""
        val inlineAttrs = buildInlineHtmlAttributesSql(value)
        return if (isVoidHtmlTagSql(tag)) {
            "<$tag$dataScAttrs$langAttr$styleAttr$inlineAttrs>"
        } else {
            "<$tag$dataScAttrs$langAttr$styleAttr$inlineAttrs>$content</$tag>"
        }
    }

    if (content.isNotBlank()) return content

    val textValue = listOf("text", "value")
        .map { mapString(it) }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
    if (textValue.isNotBlank()) return textValue

    val fallback = value.values
        .mapNotNull { extractTextSnippetSql(it) }
        .filter { it.isNotBlank() }
    return fallback.joinToString("")
}

private fun extractStructuredDataAttributesSql(rawData: Any?): Map<String, String> {
    val attributes = linkedMapOf<String, String>()

    fun putAttribute(rawKey: String?, rawValue: Any?) {
        val key = normalizeStructuredDataKeySql(rawKey)
        if (key.isBlank()) return
        val value = when (rawValue) {
            null -> ""
            is String -> rawValue.trim()
            is Number, is Boolean -> rawValue.toString()
            else -> rawValue.toString().trim()
        }
        attributes[key] = value
    }

    when (rawData) {
        is Map<*, *> -> rawData.forEach { (key, value) ->
            putAttribute(key?.toString(), value)
        }

        is String -> {
            val trimmed = rawData.trim()
            if (trimmed.startsWith("@{") && trimmed.endsWith("}")) {
                val body = trimmed.substring(2, trimmed.length - 1)
                body.split(';').forEach { token ->
                    val part = token.trim()
                    if (part.isBlank()) return@forEach
                    val separator = part.indexOf('=')
                    if (separator < 0) {
                        putAttribute(part, "")
                    } else {
                        putAttribute(part.substring(0, separator), part.substring(separator + 1))
                    }
                }
            }
        }
    }

    return attributes
}

private fun normalizeStructuredDataKeySql(rawKey: String?): String {
    val base = rawKey
        ?.trim()
        .orEmpty()
    if (base.isBlank()) return ""

    return when (base.lowercase(Locale.ROOT)) {
        "sc-class", "scclass", "class" -> "class"
        // Yomitan dictionary packs commonly use dic-item while CSS targets dic_item.
        "dic-item" -> "dic_item"
        else -> base
    }
}

private fun buildStructuredDataScAttributesSql(data: Map<String, String>): String {
    if (data.isEmpty()) return ""
    val classAttr = data["class"]?.trim().takeIf { !it.isNullOrBlank() }
    val dataAttrs = data.entries.joinToString(separator = "") { (key, value) ->
        val escapedValue = escapeHtmlAttributeSql(value)
        buildString {
            append(" data-sc-$key=\"$escapedValue\"")
            append(" data-sc$key=\"$escapedValue\"")
        }
    }
    return buildString {
        if (!classAttr.isNullOrBlank()) {
            append(" class=\"${escapeHtmlAttributeSql(classAttr)}\"")
        }
        append(dataAttrs)
    }
}

private fun isVoidHtmlTagSql(tag: String): Boolean {
    return tag in setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")
}

private fun buildInlineHtmlAttributesSql(value: Map<*, *>): String {
    val attrs = linkedMapOf<String, String>()
    val tag = value["tag"]?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
    val suppressWidthHeightAttr = tag == "img" && !value["sizeUnits"]?.toString().isNullOrBlank()
    val src = listOf("src", "path", "url")
        .asSequence()
        .map { key -> value[key]?.toString()?.trim().orEmpty() }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
    if (src.isNotBlank() && isSafeDictionaryHtmlUrlSql(src)) attrs["src"] = src
    val allowed = if (suppressWidthHeightAttr) {
        listOf("href", "alt", "title", "target", "rel", "colspan", "rowspan")
    } else {
        listOf("href", "alt", "title", "target", "rel", "width", "height", "colspan", "rowspan")
    }
    allowed.forEach { key ->
        val raw = value[key]?.toString()?.trim().orEmpty()
        if ((key == "href" || key == "src") && !isSafeDictionaryHtmlUrlSql(raw)) return@forEach
        if (raw.isNotBlank()) attrs[key] = raw
    }
    return attrs.entries.joinToString(separator = "") { (key, raw) ->
        " $key=\"${escapeHtmlAttributeSql(raw)}\""
    }
}

private fun supplementalInlineStyleSql(value: Map<*, *>, tag: String): String {
    if (tag != "img") return ""
    val unit = normalizeCssUnitSql(value["sizeUnits"]?.toString().orEmpty()) ?: return ""
    val width = toCssLengthSql(value["width"]?.toString().orEmpty(), unit)
    val height = toCssLengthSql(value["height"]?.toString().orEmpty(), unit)
    val verticalAlign = value["verticalAlign"]?.toString()?.trim().orEmpty()

    val styles = mutableListOf<String>()
    if (width.isNotBlank()) styles += "width: $width"
    if (height.isNotBlank()) styles += "height: $height"
    if (verticalAlign.isNotBlank()) styles += "vertical-align: $verticalAlign"
    return styles.joinToString("; ")
}

private fun normalizeCssUnitSql(rawUnit: String): String? {
    val unit = rawUnit.trim().lowercase(Locale.ROOT)
    if (unit.isBlank()) return null
    return if (unit.matches(CSS_SIZE_UNIT_REGEX)) unit else null
}

private fun toCssLengthSql(raw: String, unit: String): String {
    val text = raw.trim()
    if (text.isBlank()) return ""
    return if (text.matches(CSS_NUMBER_REGEX)) "$text$unit" else text
}

private fun mergeInlineStyleSql(base: String, extra: String): String {
    val parts = listOf(base.trim().trimEnd(';'), extra.trim().trimEnd(';'))
        .filter { it.isNotBlank() }
    return parts.joinToString("; ")
}

private fun styleValueToCssSql(value: Any?): String {
    return when (value) {
        null -> ""
        is String -> value.trim()
        is Map<*, *> -> {
            val parts = mutableListOf<String>()
            value.forEach { (key, raw) ->
                val k = key?.toString()?.trim().orEmpty()
                val v = raw?.toString()?.trim().orEmpty()
                if (k.isBlank() || v.isBlank()) return@forEach
                parts += "${camelToKebabSql(k)}: $v"
            }
            parts.joinToString("; ")
        }

        else -> value.toString().trim()
    }
}

private fun camelToKebabSql(value: String): String {
    return value
        .replace(CAMEL_CASE_BOUNDARY_REGEX, "$1-$2")
        .lowercase(Locale.ROOT)
}

private fun compactDefinitionsSql(rawDefinitions: List<String>): List<String> {
    return rawDefinitions
        .map(::normalizeDefinitionForDisplaySql)
        .filter { it.isNotBlank() && isLikelyDefinitionSql(it) }
        .map(::clampDefinitionLengthForStorageSql)
        .distinct()
        .take(2)
}

private fun normalizeDefinitionForDisplaySql(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""
    return if (looksLikeHtmlSql(trimmed)) sanitizeDictionaryDefinitionHtmlSql(trimmed) else plainDefinitionToHtmlSql(trimmed)
}

private fun clampDefinitionLengthForStorageSql(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return ""
    return if (looksLikeHtmlSql(trimmed)) sanitizeDictionaryDefinitionHtmlSql(trimmed) else trimmed.take(3200)
}

internal fun lookupDictionarySourceUriByCacheKey(context: Context, cacheKey: String): String? {
    if (cacheKey.isBlank()) return null
    return loadPersistedImports(context)
        .dictionaries
        .firstOrNull { it.cacheKey == cacheKey }
        ?.uri
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

private fun looksLikeHtmlSql(text: String): Boolean {
    return LOOKS_LIKE_HTML_REGEX.containsMatchIn(text)
}

private fun sanitizeDictionaryDefinitionHtmlSql(raw: String): String {
    return raw
        .replace(DANGEROUS_HTML_BLOCK_REGEX, "")
        .replace(DANGEROUS_HTML_TAG_REGEX, "")
        .replace(HTML_EVENT_ATTRIBUTE_REGEX, "")
        .replace(HTML_SRCDOC_ATTRIBUTE_REGEX, "")
        .replace(HTML_DANGEROUS_URL_ATTRIBUTE_REGEX, "")
        .trim()
}

private fun sanitizeInlineStyleSql(raw: String): String? {
    val style = raw.trim()
    if (style.isBlank()) return null
    val lower = style.lowercase(Locale.ROOT)
    if (DANGEROUS_INLINE_STYLE_TOKENS.any { lower.contains(it) }) return null
    return style.take(2000)
}

private fun isSafeDictionaryHtmlUrlSql(raw: String): Boolean {
    val value = raw.trim()
    if (value.isBlank()) return false
    val lower = value.lowercase(Locale.ROOT)
    if (
        lower.startsWith("javascript:") ||
        lower.startsWith("vbscript:") ||
        lower.startsWith("data:text/html")
    ) {
        return false
    }
    if (lower.startsWith("data:")) return lower.startsWith("data:image/")
    val scheme = value.substringBefore(':', missingDelimiterValue = "")
    if (scheme == value) return true
    return scheme.lowercase(Locale.ROOT) in setOf("http", "https", "dictres", "image", "mailto", "tel")
}

private fun isLikelyDefinitionSql(text: String): Boolean {
    val plain = stripHtmlTagsSql(text)
    if (plain.length < 2) return false
    if (plain.all { it.isDigit() }) return false
    return true
}

private fun plainDefinitionToHtmlSql(raw: String): String {
    val normalized = raw
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
    if (normalized.isBlank()) return ""
    val linked = linkifyPlainTextWithMarkdownSql(normalized)
    return linked.replace("\n", "<br/>")
}

private fun linkifyPlainTextWithMarkdownSql(text: String): String {
    val out = StringBuilder()
    var cursor = 0

    data class Token(val start: Int, val end: Int, val html: String)

    fun sanitizeUrlOrNull(raw: String): String? {
        val candidate = raw.trim().trim('"', '\'')
        if (candidate.isBlank()) return null
        val lower = candidate.lowercase(Locale.ROOT)
        if (lower.startsWith("javascript:")) return null
        return candidate
    }

    val tokens = mutableListOf<Token>()

    MARKDOWN_IMAGE_REGEX.findAll(text).forEach { match ->
        val alt = match.groupValues[1]
        val src = sanitizeUrlOrNull(match.groupValues[2]) ?: return@forEach
        tokens += Token(
            start = match.range.first,
            end = match.range.last + 1,
            html = "<img src=\"${escapeHtmlAttributeSql(src)}\" alt=\"${escapeHtmlAttributeSql(alt)}\" />"
        )
    }
    MARKDOWN_LINK_REGEX.findAll(text).forEach { match ->
        val label = match.groupValues[1]
        val href = sanitizeUrlOrNull(match.groupValues[2]) ?: return@forEach
        tokens += Token(
            start = match.range.first,
            end = match.range.last + 1,
            html = "<a href=\"${escapeHtmlAttributeSql(href)}\">${escapeHtmlTextSql(label)}</a>"
        )
    }

    val occupied = tokens.sortedBy { it.start }
    PLAIN_URL_REGEX.findAll(text).forEach { match ->
        val start = match.range.first
        val end = match.range.last + 1
        if (occupied.any { start < it.end && end > it.start }) return@forEach
        val href = sanitizeUrlOrNull(match.value) ?: return@forEach
        val safeHref = escapeHtmlAttributeSql(href)
        val safeLabel = escapeHtmlTextSql(href)
        tokens += Token(start = start, end = end, html = "<a href=\"$safeHref\">$safeLabel</a>")
    }

    tokens
        .sortedBy { it.start }
        .forEach { token ->
            if (token.start < cursor) return@forEach
            if (token.start > cursor) {
                out.append(escapeHtmlTextSql(text.substring(cursor, token.start)))
            }
            out.append(token.html)
            cursor = token.end
        }

    if (cursor < text.length) {
        out.append(escapeHtmlTextSql(text.substring(cursor)))
    }
    return out.toString()
}

private fun stripHtmlTagsSql(value: String): String {
    return value
        .replace(STRIP_HTML_TAGS_REGEX, " ")
        .replace(NORMALIZE_WHITESPACE_REGEX, " ")
        .trim()
}

private fun escapeHtmlAttributeSql(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private fun escapeHtmlTextSql(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
