package moe.tekuza.m9player

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import android.util.Log
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.StringReader
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.max

private const val DB_NAME = "dictionary_store.db"
private const val DB_VERSION = 2
private const val DICTIONARY_ENTRY_STORE_DIR = "dictionary_entry_store"
private const val DICTIONARY_ENTRY_DB_FILE = "entries.db"
private const val DICTIONARY_TERM_INDEX_FILE = "term_norm.index"
private const val DICTIONARY_HOSHI_ROOT_DIR = "hoshidicts"
private const val DICTIONARY_HOSHI_INFO_FILE = "info.json"
private const val DICTIONARY_HOSHI_BLOBS_FILE = "blobs.bin"
private const val DICTIONARY_HOSHI_OFFSETS_FILE = "offsets.bin"
private const val DICTIONARY_HOSHI_HASH_FILE = "hash.mph"
private const val DICTIONARY_HOSHI_STYLES_FILE = "styles.css"
private const val DICTIONARY_TERM_INDEX_MAGIC = 0x54494458 // "TIDX"
private const val DICTIONARY_TERM_INDEX_VERSION = 1
private const val DICTIONARY_TERM_INDEX_CACHE_LIMIT = 48
private const val DICTIONARY_MEMORY_INDEX_CACHE_LIMIT = 12
private const val LOOKUP_QUERY_CACHE_LIMIT = 180
private const val LOOKUP_QUERY_CACHE_SCHEMA_VERSION = 3
private const val LOOKUP_REWRITE_LIMIT = 40
private const val FAST_FIRST_SCREEN_RESULTS = 10
private const val FAST_MOUNTED_EARLY_STOP_MS = 1200L
private const val FAST_IMPORT_ALIAS_FROM_DEFINITION = false
private const val IMPORT_PROGRESS_STEP = 3
private const val DICTIONARY_LOOKUP_TAG = "DictionaryLookup"
private const val MDICT_MEDIA_LOG_TAG = "MdictMedia"

internal enum class DictionaryQueryProfile {
    FAST,
    FULL
}

private val SAFE_LOCKING_MODE_REGEX = Regex("^[A-Za-z_]+$")
private val NORMALIZE_PUNCT_OR_SYMBOL_REGEX = Regex("[\\p{Punct}\\p{S}]")
private val NORMALIZE_WHITESPACE_REGEX = Regex("\\s+")
private val STRIP_HTML_TAGS_REGEX = Regex("<[^>]+>")
private val LOOKS_LIKE_HTML_REGEX = Regex("<\\s*/?\\s*[a-zA-Z][^>]*>")
private val CAMEL_CASE_BOUNDARY_REGEX = Regex("([a-z])([A-Z])")
private val STRUCTURED_DATA_KEY_SANITIZE_REGEX = Regex("[^a-z0-9_-]")
private val MARKDOWN_IMAGE_REGEX = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)")
private val MARKDOWN_LINK_REGEX = Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)")
private val PLAIN_URL_REGEX = Regex("https?://[^\\s<]+")
private val KANJI_TOKEN_REGEX = Regex("[\\u4E00-\\u9FFF\\u3400-\\u4DBF\\uF900-\\uFAFF\\u3005\\u3006\\u30F6]{1,12}")
private val HTML_IMG_SRC_QUOTED_REGEX =
    Regex("(?i)<img\\b([^>]*?)\\bsrc\\s*=\\s*(['\"])(.*?)\\2([^>]*)>")
private val HTML_IMG_SRC_UNQUOTED_REGEX =
    Regex("(?i)<img\\b([^>]*?)\\bsrc\\s*=\\s*([^\\s>]+)([^>]*)>")
private val HTML_TAG_REGEX = Regex("(?is)<[^>]+>")
private val HTML_ATTR_QUOTED_REGEX = Regex("(?i)\\b(src|href)\\s*=\\s*(['\"])(.*?)\\2")
private val HTML_ATTR_UNQUOTED_REGEX = Regex("(?i)\\b(src|href)\\s*=\\s*([^\\s\"'>]+)")
private val URI_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

private const val TABLE_DICTIONARIES = "dictionaries"
private const val TABLE_ENTRIES = "entries"

private const val COL_CACHE_KEY = "cache_key"
private const val COL_NAME = "name"
private const val COL_FORMAT = "format"
private const val COL_STYLES_CSS = "styles_css"
private const val COL_ENTRY_COUNT = "entry_count"
private const val COL_URI = "source_uri"
private const val COL_DISPLAY_NAME = "display_name"
private const val COL_UPDATED_AT = "updated_at"

private const val COL_TERM = "term"
private const val COL_READING = "reading"
private const val COL_DEFINITION = "definition"
private const val COL_PITCH = "pitch"
private const val COL_FREQUENCY = "frequency"
private const val COL_DICTIONARY_NAME = "dictionary_name"
private const val COL_TERM_NORM = "term_norm"
private const val COL_READING_NORM = "reading_norm"
private const val COL_ALIAS_NORM = "alias_norm"

private const val SQL_INSERT_ENTRY = """
    INSERT INTO $TABLE_ENTRIES (
        $COL_CACHE_KEY,
        $COL_TERM,
        $COL_READING,
        $COL_DEFINITION,
        $COL_PITCH,
        $COL_FREQUENCY,
        $COL_DICTIONARY_NAME,
        $COL_TERM_NORM,
        $COL_READING_NORM,
        $COL_ALIAS_NORM
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
"""

private data class DictionaryLookupQueryCacheKey(
    val dictionariesKey: String,
    val normalizedQuery: String,
    val maxResults: Int,
    val profile: DictionaryQueryProfile
)

private data class DictionaryTermIndexCacheEntry(
    val fileLastModified: Long,
    val terms: List<String>
)

private data class MemoryDictionaryEntry(
    val term: String,
    val reading: String?,
    val definition: String,
    val pitch: String?,
    val frequency: String?,
    val dictionaryName: String,
    val termNorm: String,
    val readingNorm: String,
    val aliasNorm: String
)

private data class DictionaryMemoryIndex(
    val cacheKey: String,
    val dbLastModified: Long,
    val entries: List<MemoryDictionaryEntry>,
    val exactNormMap: Map<String, IntArray>,
    val sortedTerms: List<String>
)

private val dictionaryLookupQueryCache =
    object : LinkedHashMap<DictionaryLookupQueryCacheKey, List<DictionarySearchResult>>(LOOKUP_QUERY_CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<DictionaryLookupQueryCacheKey, List<DictionarySearchResult>>?
        ): Boolean {
            return size > LOOKUP_QUERY_CACHE_LIMIT
        }
    }

private val dictionaryTermIndexCache =
    object : LinkedHashMap<String, DictionaryTermIndexCacheEntry>(DICTIONARY_TERM_INDEX_CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, DictionaryTermIndexCacheEntry>?
        ): Boolean {
            return size > DICTIONARY_TERM_INDEX_CACHE_LIMIT
        }
    }

private val dictionaryMemoryIndexCache =
    object : LinkedHashMap<String, DictionaryMemoryIndex>(DICTIONARY_MEMORY_INDEX_CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, DictionaryMemoryIndex>?
        ): Boolean {
            return size > DICTIONARY_MEMORY_INDEX_CACHE_LIMIT
        }
    }

private var mountedMdxPrewarmStateKey: String? = null
private val mountedMdxPrewarmLock = Any()


private class DictionaryDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DICTIONARIES (
                $COL_CACHE_KEY TEXT PRIMARY KEY,
                $COL_NAME TEXT NOT NULL,
                $COL_FORMAT TEXT NOT NULL,
                $COL_STYLES_CSS TEXT,
                $COL_ENTRY_COUNT INTEGER NOT NULL DEFAULT 0,
                $COL_URI TEXT,
                $COL_DISPLAY_NAME TEXT,
                $COL_UPDATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ENTRIES (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CACHE_KEY TEXT NOT NULL,
                $COL_TERM TEXT NOT NULL,
                $COL_READING TEXT,
                $COL_DEFINITION TEXT,
                $COL_PITCH TEXT,
                $COL_FREQUENCY TEXT,
                $COL_DICTIONARY_NAME TEXT NOT NULL,
                $COL_TERM_NORM TEXT NOT NULL,
                $COL_READING_NORM TEXT NOT NULL,
                $COL_ALIAS_NORM TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_cache ON $TABLE_ENTRIES($COL_CACHE_KEY)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_cache_term_norm ON $TABLE_ENTRIES($COL_CACHE_KEY, $COL_TERM_NORM)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_cache_reading_norm ON $TABLE_ENTRIES($COL_CACHE_KEY, $COL_READING_NORM)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_cache_alias_norm ON $TABLE_ENTRIES($COL_CACHE_KEY, $COL_ALIAS_NORM)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ENTRIES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_DICTIONARIES")
        onCreate(db)
    }
}

private fun readableDb(context: Context): SQLiteDatabase {
    return DictionaryDbHelper(context).readableDatabase
}

private fun writableDb(context: Context): SQLiteDatabase {
    return DictionaryDbHelper(context).writableDatabase
}

private fun dictionaryStorageRootDir(context: Context): File {
    val dir = File(context.filesDir, DICTIONARY_ENTRY_STORE_DIR)
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun dictionaryStorageSafeKey(cacheKey: String): String {
    return cacheKey.trim().ifBlank { "unknown" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

private fun dictionaryStorageDir(context: Context, cacheKey: String): File {
    val dir = File(dictionaryStorageRootDir(context), dictionaryStorageSafeKey(cacheKey))
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun dictionaryEntryDbFile(context: Context, cacheKey: String): File {
    return File(dictionaryStorageDir(context, cacheKey), DICTIONARY_ENTRY_DB_FILE)
}

private fun dictionaryTermIndexFile(context: Context, cacheKey: String): File {
    return File(dictionaryStorageDir(context, cacheKey), DICTIONARY_TERM_INDEX_FILE)
}

private fun dictionaryHoshiRootDir(context: Context, cacheKey: String): File {
    val dir = File(dictionaryStorageDir(context, cacheKey), DICTIONARY_HOSHI_ROOT_DIR)
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun isValidHoshiDictionaryDir(dir: File): Boolean {
    if (!dir.isDirectory) return false
    return File(dir, DICTIONARY_HOSHI_INFO_FILE).isFile &&
        File(dir, DICTIONARY_HOSHI_BLOBS_FILE).isFile &&
        File(dir, DICTIONARY_HOSHI_OFFSETS_FILE).isFile &&
        File(dir, DICTIONARY_HOSHI_HASH_FILE).isFile
}

private fun locateHoshiDictionaryDir(context: Context, cacheKey: String): File? {
    val root = File(dictionaryStorageDir(context, cacheKey), DICTIONARY_HOSHI_ROOT_DIR)
    if (!root.isDirectory) return null
    return root.listFiles()
        ?.filter { isValidHoshiDictionaryDir(it) }
        ?.sortedByDescending { it.lastModified() }
        ?.firstOrNull()
}

private fun ensureDictionaryEntriesSchema(db: SQLiteDatabase) {
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS $TABLE_ENTRIES (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            $COL_CACHE_KEY TEXT NOT NULL,
            $COL_TERM TEXT NOT NULL,
            $COL_READING TEXT,
            $COL_DEFINITION TEXT,
            $COL_PITCH TEXT,
            $COL_FREQUENCY TEXT,
            $COL_DICTIONARY_NAME TEXT NOT NULL,
            $COL_TERM_NORM TEXT NOT NULL,
            $COL_READING_NORM TEXT NOT NULL,
            $COL_ALIAS_NORM TEXT NOT NULL
        )
        """.trimIndent()
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_cache ON $TABLE_ENTRIES($COL_CACHE_KEY)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_cache_term_norm ON $TABLE_ENTRIES($COL_CACHE_KEY, $COL_TERM_NORM)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_cache_reading_norm ON $TABLE_ENTRIES($COL_CACHE_KEY, $COL_READING_NORM)")
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_cache_alias_norm ON $TABLE_ENTRIES($COL_CACHE_KEY, $COL_ALIAS_NORM)")
}

private fun openDictionaryEntriesDbForWrite(context: Context, cacheKey: String): SQLiteDatabase {
    val dbFile = dictionaryEntryDbFile(context, cacheKey)
    val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
    ensureDictionaryEntriesSchema(db)
    return db
}

private fun openDictionaryEntriesDbForRead(context: Context, cacheKey: String): SQLiteDatabase? {
    val dbFile = dictionaryEntryDbFile(context, cacheKey)
    if (!dbFile.isFile) return null
    return runCatching {
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }.getOrNull()
}

private fun clearDictionaryTermIndexCache(cacheKey: String? = null) {
    synchronized(dictionaryTermIndexCache) {
        if (cacheKey == null) {
            dictionaryTermIndexCache.clear()
        } else {
            dictionaryTermIndexCache.remove(cacheKey)
        }
    }
}

private fun writeDictionaryTermBinaryIndex(
    context: Context,
    cacheKey: String,
    entriesDb: SQLiteDatabase
) {
    val terms = mutableListOf<String>()
    entriesDb.query(
        TABLE_ENTRIES,
        arrayOf(COL_TERM_NORM),
        "$COL_CACHE_KEY = ?",
        arrayOf(cacheKey),
        null,
        null,
        "$COL_TERM_NORM ASC"
    ).use { cursor ->
        var lastTerm = ""
        while (cursor.moveToNext()) {
            val term = cursor.getString(0).orEmpty()
            if (term.isBlank() || term == lastTerm) continue
            lastTerm = term
            terms += term
        }
    }

    val indexFile = dictionaryTermIndexFile(context, cacheKey)
    val tempFile = File(indexFile.parentFile, "${indexFile.name}.tmp")
    DataOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { out ->
        out.writeInt(DICTIONARY_TERM_INDEX_MAGIC)
        out.writeInt(DICTIONARY_TERM_INDEX_VERSION)
        out.writeInt(terms.size)
        terms.forEach { term ->
            val bytes = term.toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
        }
    }
    if (indexFile.exists()) indexFile.delete()
    if (!tempFile.renameTo(indexFile)) {
        tempFile.copyTo(indexFile, overwrite = true)
        tempFile.delete()
    }
    clearDictionaryTermIndexCache(cacheKey)
}

private fun readDictionaryTermBinaryIndex(context: Context, cacheKey: String): List<String>? {
    val indexFile = dictionaryTermIndexFile(context, cacheKey)
    if (!indexFile.isFile) return null
    val fileLastModified = indexFile.lastModified()
    synchronized(dictionaryTermIndexCache) {
        val cached = dictionaryTermIndexCache[cacheKey]
        if (cached != null && cached.fileLastModified == fileLastModified) {
            return cached.terms
        }
    }

    val terms = runCatching {
        DataInputStream(FileInputStream(indexFile).buffered()).use { input ->
            val magic = input.readInt()
            if (magic != DICTIONARY_TERM_INDEX_MAGIC) return null
            val version = input.readInt()
            if (version != DICTIONARY_TERM_INDEX_VERSION) return null
            val count = input.readInt()
            if (count < 0 || count > 5_000_000) return null

            val list = ArrayList<String>(count.coerceAtMost(200_000))
            repeat(count) {
                val size = input.readInt()
                if (size < 0 || size > 8192) return null
                val bytes = ByteArray(size)
                input.readFully(bytes)
                list += bytes.toString(Charsets.UTF_8)
            }
            list
        }
    }.getOrNull() ?: return null

    synchronized(dictionaryTermIndexCache) {
        dictionaryTermIndexCache[cacheKey] = DictionaryTermIndexCacheEntry(
            fileLastModified = fileLastModified,
            terms = terms
        )
    }
    return terms
}

private fun lowerBoundTermIndex(terms: List<String>, target: String): Int {
    var low = 0
    var high = terms.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (terms[mid] < target) low = mid + 1 else high = mid
    }
    return low
}

private fun collectPrefixTermsFromBinaryIndex(
    sortedTerms: List<String>,
    normalizedPrefix: String,
    limit: Int
): List<String> {
    if (normalizedPrefix.isBlank() || limit <= 0 || sortedTerms.isEmpty()) return emptyList()
    val start = lowerBoundTermIndex(sortedTerms, normalizedPrefix)
    if (start >= sortedTerms.size) return emptyList()
    val out = mutableListOf<String>()
    var index = start
    while (index < sortedTerms.size && out.size < limit) {
        val term = sortedTerms[index]
        if (!term.startsWith(normalizedPrefix)) break
        out += term
        index += 1
    }
    return out
}

private fun deleteDictionaryStorageDir(context: Context, cacheKey: String): Boolean {
    val dir = File(dictionaryStorageRootDir(context), dictionaryStorageSafeKey(cacheKey))
    if (!dir.exists()) return false
    return runCatching { dir.deleteRecursively() }.getOrElse { false }
}

private data class FastImportPragmaSnapshot(
    val synchronous: Int?,
    val tempStore: Int?,
    val cacheSize: Int?,
    val lockingMode: String?
)

private fun readPragmaInt(db: SQLiteDatabase, name: String): Int? {
    return runCatching {
        db.rawQuery("PRAGMA $name", null).use { cursor ->
            if (!cursor.moveToFirst()) return null
            cursor.getInt(0)
        }
    }.getOrNull()
}

private fun readPragmaString(db: SQLiteDatabase, name: String): String? {
    return runCatching {
        db.rawQuery("PRAGMA $name", null).use { cursor ->
            if (!cursor.moveToFirst()) return null
            cursor.getString(0)?.trim()?.ifBlank { null }
        }
    }.getOrNull()
}

private inline fun <T> withFastImportPragmas(db: SQLiteDatabase, block: () -> T): T {
    val snapshot = FastImportPragmaSnapshot(
        synchronous = readPragmaInt(db, "synchronous"),
        tempStore = readPragmaInt(db, "temp_store"),
        cacheSize = readPragmaInt(db, "cache_size"),
        lockingMode = readPragmaString(db, "locking_mode")
    )
    runCatching { db.execSQL("PRAGMA synchronous=OFF") }
    runCatching { db.execSQL("PRAGMA temp_store=MEMORY") }
    runCatching { db.execSQL("PRAGMA cache_size=-65536") }
    runCatching { db.execSQL("PRAGMA locking_mode=EXCLUSIVE") }

    try {
        return block()
    } finally {
        snapshot.lockingMode
            ?.takeIf { SAFE_LOCKING_MODE_REGEX.matches(it) }
            ?.let { previousMode ->
                runCatching { db.execSQL("PRAGMA locking_mode=$previousMode") }
            }
        snapshot.synchronous?.let { runCatching { db.execSQL("PRAGMA synchronous=$it") } }
        snapshot.tempStore?.let { runCatching { db.execSQL("PRAGMA temp_store=$it") } }
        snapshot.cacheSize?.let { runCatching { db.execSQL("PRAGMA cache_size=$it") } }
    }
}

private fun clearDictionaryLookupQueryCache() {
    synchronized(dictionaryLookupQueryCache) {
        dictionaryLookupQueryCache.clear()
    }
}

private fun clearDictionaryMemoryIndexCache(cacheKey: String? = null) {
    synchronized(dictionaryMemoryIndexCache) {
        if (cacheKey == null) {
            dictionaryMemoryIndexCache.clear()
        } else {
            dictionaryMemoryIndexCache.remove(cacheKey)
        }
    }
}

internal fun invalidateDictionaryLookupCaches() {
    clearDictionaryLookupQueryCache()
    clearDictionaryMemoryIndexCache()
    clearMountedMdxRuntimeCaches()
    HoshiNativeBridge.clearLookupCache()
    MdictNativeBridge.clearLookupCache()
}

internal fun prewarmDictionaryMemoryIndexes(
    context: Context,
    dictionaries: List<LoadedDictionary>
) {
    if (dictionaries.isEmpty()) return
    val appContext = context.applicationContext
    val targets = dictionaries
        .asSequence()
        .filterNot { it.format.contains("MDX", ignoreCase = true) }
        .mapNotNull { it.cacheKey.takeIf { key -> key.isNotBlank() } }
        .distinct()
        .toList()
    if (targets.isEmpty()) return
    val started = System.currentTimeMillis()
    var warmed = 0
    targets.forEach { cacheKey ->
        if (loadDictionaryMemoryIndex(appContext, cacheKey) != null) {
            warmed += 1
        }
    }
    Log.d(
        DICTIONARY_LOOKUP_TAG,
        "memory prewarm done total=${targets.size} warmed=$warmed elapsedMs=${(System.currentTimeMillis() - started).coerceAtLeast(0L)}"
    )
}

private fun loadDictionaryLookupQueryCache(
    key: DictionaryLookupQueryCacheKey
): List<DictionarySearchResult>? {
    val cached = synchronized(dictionaryLookupQueryCache) {
        dictionaryLookupQueryCache[key]
    }
    Log.d(
        DICTIONARY_LOOKUP_TAG,
        "resultCache ${if (cached != null) "HIT" else "MISS"} query=${key.normalizedQuery} dictKey=${key.dictionariesKey.take(48)} profile=${key.profile}"
    )
    return cached
}

private fun saveDictionaryLookupQueryCache(
    key: DictionaryLookupQueryCacheKey,
    results: List<DictionarySearchResult>
) {
    synchronized(dictionaryLookupQueryCache) {
        dictionaryLookupQueryCache[key] = results
    }
}

private fun buildLookupCacheDictionariesKey(dictionaries: List<LoadedDictionary>): String {
    val base = dictionaries
        .mapNotNull { dictionary ->
            val cacheKey = dictionary.cacheKey.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            "$cacheKey:${dictionary.entryCount}"
        }
        .joinToString("|")
    if (base.isBlank()) return ""
    return "$base|lookupSchema=$LOOKUP_QUERY_CACHE_SCHEMA_VERSION"
}

private data class DictionarySqlLookupConfig(
    val exactLimit: Int,
    val prefixLimit: Int,
    val containsLimit: Int,
    val containsTriggerSize: Int
)

private fun dictionarySqlLookupConfig(
    profile: DictionaryQueryProfile,
    maxResults: Int
): DictionarySqlLookupConfig {
    val safeMax = maxResults.coerceAtLeast(1)
    return when (profile) {
        DictionaryQueryProfile.FAST -> DictionarySqlLookupConfig(
            exactLimit = safeMax.coerceIn(16, 60),
            prefixLimit = (safeMax * 3).coerceIn(48, 180),
            containsLimit = 0,
            containsTriggerSize = 0
        )

        DictionaryQueryProfile.FULL -> DictionarySqlLookupConfig(
            exactLimit = (safeMax * 2).coerceIn(24, 96),
            prefixLimit = (safeMax * 4).coerceIn(80, 260),
            containsLimit = (safeMax * 6).coerceIn(120, 380),
            containsTriggerSize = (safeMax / 3).coerceAtLeast(10)
        )
    }
}

private data class HoshiDictionaryBinding(
    val dictionary: LoadedDictionary,
    val dictionaryDir: File
)

private fun collectHoshiDictionaryBindings(
    context: Context,
    dictionaries: List<LoadedDictionary>
): List<HoshiDictionaryBinding> {
    if (!HoshiNativeBridge.isAvailable) return emptyList()
    return dictionaries.mapNotNull { dictionary ->
        val cacheKey = dictionary.cacheKey.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val dir = locateHoshiDictionaryDir(context, cacheKey) ?: return@mapNotNull null
        HoshiDictionaryBinding(dictionary = dictionary, dictionaryDir = dir)
    }
}

private fun searchDictionaryWithHoshi(
    context: Context,
    dictionaries: List<LoadedDictionary>,
    query: String,
    maxResults: Int,
    profile: DictionaryQueryProfile
): List<DictionarySearchResult> {
    if (query.isBlank() || dictionaries.isEmpty() || !HoshiNativeBridge.isAvailable) return emptyList()
    val bindings = collectHoshiDictionaryBindings(context, dictionaries)
    if (bindings.isEmpty()) return emptyList()

    val dictionaryOrder = bindings
        .mapIndexed { index, binding -> binding.dictionary.name to index }
        .toMap()
    val dictionaryCacheKeyByName = bindings
        .asSequence()
        .mapNotNull { binding ->
            val name = binding.dictionary.name.trim()
            val cacheKey = binding.dictionary.cacheKey.trim()
            if (name.isBlank() || cacheKey.isBlank()) null else name.lowercase(Locale.ROOT) to cacheKey
        }
        .toMap()
    val lookupLimit = when (profile) {
        DictionaryQueryProfile.FAST -> maxResults.coerceIn(8, 16)
        DictionaryQueryProfile.FULL -> (maxResults * 2).coerceIn(24, 140)
    }
    val scanLength = when (profile) {
        DictionaryQueryProfile.FAST -> minOf(query.length.coerceAtLeast(1), 4)
        DictionaryQueryProfile.FULL -> 16
    }
    val hits = HoshiNativeBridge.lookup(
        dictionaryPaths = bindings.map { it.dictionaryDir.absolutePath },
        query = query,
        maxResults = lookupLimit,
        scanLength = scanLength
    )
    if (hits.isEmpty()) return emptyList()

    val merged = linkedMapOf<String, DictionarySearchResult>()
    hits.forEachIndexed { index, hit ->
        val definition = glossaryRawToDefinitionHtmlSql(hit.glossaryRaw)
        val normalizedPitch = hit.pitch?.ifBlank { null }
        val normalizedFrequency = hit.frequency?.ifBlank { null }
        if (definition.isBlank() && normalizedPitch == null && normalizedFrequency == null) {
            return@forEachIndexed
        }
        val dictionaryName = hit.dictionary.ifBlank { dictionaries.firstOrNull()?.name.orEmpty() }
        val entry = DictionaryEntry(
            term = hit.term,
            reading = hit.reading?.ifBlank { null },
            definitions = definition.takeIf { it.isNotBlank() }?.let(::listOf) ?: emptyList(),
            pitch = normalizedPitch,
            frequency = normalizedFrequency,
            dictionary = dictionaryName
        )
        val order = dictionaryOrder[dictionaryName] ?: dictionaryOrder.size
        val sourceCacheKey = dictionaryCacheKeyByName[dictionaryName.trim().lowercase(Locale.ROOT)]
            ?: bindings.singleOrNull()?.dictionary?.cacheKey?.trim()?.ifBlank { null }
        val score = hit.score + (dictionaryOrder.size - order) * 2 - (index / 8)
        val key = entryStableKey(entry)
        val next = DictionarySearchResult(
            entry = entry,
            score = score,
            matchedLength = hit.matchedLength,
            sourceCacheKey = sourceCacheKey.orEmpty()
        )
        val existing = merged[key]
        if (existing == null || next.score > existing.score) {
            merged[key] = next
        }
    }

    return merged.values
        .sortedWith(
            compareByDescending<DictionarySearchResult> { it.score }
                .thenBy { it.entry.term.length }
                .thenBy { it.entry.term }
        )
        .take(maxResults)
}

internal fun loadDictionaryFromSqlite(context: Context, cacheKey: String): LoadedDictionary? {
    if (cacheKey.isBlank()) return null
    return runCatching {
        readableDb(context).query(
            TABLE_DICTIONARIES,
            arrayOf(COL_NAME, COL_FORMAT, COL_STYLES_CSS, COL_ENTRY_COUNT),
            "$COL_CACHE_KEY = ?",
            arrayOf(cacheKey),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            LoadedDictionary(
                cacheKey = cacheKey,
                name = cursor.getString(0).orEmpty(),
                format = cursor.getString(1).orEmpty(),
                entries = emptyList(),
                stylesCss = cursor.getString(2)?.takeIf { it.isNotBlank() },
                entryCount = cursor.getInt(3).coerceAtLeast(0)
            )
        }
    }.getOrNull()
}

internal fun deleteDictionaryFromSqlite(context: Context, cacheKey: String): Boolean {
    if (cacheKey.isBlank()) return true
    val metaDeleted = runCatching {
        val db = writableDb(context)
        db.beginTransaction()
        try {
            // Legacy cleanup for entries that were written before per-dictionary entry DB.
            db.delete(TABLE_ENTRIES, "$COL_CACHE_KEY = ?", arrayOf(cacheKey))
            db.delete(TABLE_DICTIONARIES, "$COL_CACHE_KEY = ?", arrayOf(cacheKey))
            db.setTransactionSuccessful()
            true
        } finally {
            db.endTransaction()
        }
    }.getOrElse { false }
    val storageDeleted = deleteDictionaryStorageDir(context, cacheKey)
    if (metaDeleted || storageDeleted) {
        clearDictionaryLookupQueryCache()
        clearDictionaryTermIndexCache(cacheKey)
        HoshiNativeBridge.clearLookupCache()
        MdictNativeBridge.clearLookupCache()
    }
    return metaDeleted || storageDeleted
}

internal fun importDictionaryToSqlite(
    context: Context,
    contentResolver: ContentResolver,
    uri: Uri,
    displayName: String,
    cacheKey: String,
    onProgress: ((DictionaryImportProgress) -> Unit)? = null
): LoadedDictionary {
    require(displayName.trim().lowercase(Locale.US).endsWith(".zip")) {
        "仅支持 ZIP 辞典"
    }
    val imported = importDictionaryZipWithHoshi(
        context = context,
        contentResolver = contentResolver,
        uri = uri,
        displayName = displayName,
        cacheKey = cacheKey,
        onProgress = onProgress
    )
    clearDictionaryLookupQueryCache()
    HoshiNativeBridge.clearLookupCache()
    MdictNativeBridge.clearLookupCache()
    return imported
}

internal fun searchDictionarySql(
    context: Context,
    dictionaries: List<LoadedDictionary>,
    query: String,
    maxResults: Int = MAX_LOOKUP_RESULTS,
    profile: DictionaryQueryProfile = DictionaryQueryProfile.FAST
): List<DictionarySearchResult> {
    val effectiveDictionaries = includeMountedMdxDictionary(context, dictionaries)
    val normalizedQuery = normalizeLookupSql(query)
    if (normalizedQuery.isBlank() || effectiveDictionaries.isEmpty()) return emptyList()
    Log.d(
        DICTIONARY_LOOKUP_TAG,
        "search start query=$query normalized=$normalizedQuery dicts=${effectiveDictionaries.size} profile=$profile"
    )
    val dictionariesKey = buildLookupCacheDictionariesKey(effectiveDictionaries)
    if (dictionariesKey.isBlank()) return emptyList()
    val lookupCacheKey = DictionaryLookupQueryCacheKey(
        dictionariesKey = dictionariesKey,
        normalizedQuery = normalizedQuery,
        maxResults = maxResults,
        profile = profile
    )
    val cached = loadDictionaryLookupQueryCache(lookupCacheKey)
    if (cached != null) {
        Log.d(DICTIONARY_LOOKUP_TAG, "search cacheHit count=${cached.size} query=$normalizedQuery")
        return cached
    }

    val hoshiResults = searchDictionaryWithHoshi(
        context = context,
        dictionaries = effectiveDictionaries,
        query = query,
        maxResults = maxResults,
        profile = profile
    )
    val mdictNativeResults = searchDictionaryWithMdictNative(
        context = context,
        dictionaries = effectiveDictionaries,
        query = query,
        maxResults = maxResults,
        profile = profile
    )
    val mdxOnly = effectiveDictionaries.isNotEmpty() &&
        effectiveDictionaries.all { it.format.contains("MDX", ignoreCase = true) }
    val sqliteResults = if (mdxOnly && mdictNativeResults.isNotEmpty()) {
        emptyList()
    } else {
        searchDictionaryWithSqlite(
            context = context,
            dictionaries = effectiveDictionaries,
            query = query,
            maxResults = maxResults,
            profile = profile
        )
    }
    val merged = linkedMapOf<String, DictionarySearchResult>()
    (hoshiResults + mdictNativeResults + sqliteResults).forEach { result ->
        val key = entryStableKey(result.entry)
        val existing = merged[key]
        if (existing == null || result.score > existing.score) {
            merged[key] = result
        }
    }
    val topResults = merged.values
        .sortedWith(
            compareByDescending<DictionarySearchResult> { it.score }
                .thenBy { it.entry.term.length }
                .thenBy { it.entry.term }
        )
        .take(maxResults)
    val results = finalizeLookupResultsForDisplay(
        context = context,
        dictionaries = effectiveDictionaries,
        query = normalizedQuery,
        results = topResults,
        rewriteLimit = if (profile == DictionaryQueryProfile.FAST) 0 else LOOKUP_REWRITE_LIMIT
    )
    Log.d(
        DICTIONARY_LOOKUP_TAG,
        "search done hoshi=${hoshiResults.size} mdictNative=${mdictNativeResults.size} sqlite=${sqliteResults.size} merged=${results.size} query=$normalizedQuery"
    )
    if (results.isNotEmpty()) {
        saveDictionaryLookupQueryCache(lookupCacheKey, results)
    }
    return results
}

private data class LookupRewriteContext(
    val mediaDir: File?,
    val fallbackUriBuilder: ((String) -> String)?
)

private fun buildLookupRewriteContextMap(
    context: Context,
    dictionaries: List<LoadedDictionary>
): Map<String, LookupRewriteContext> {
    if (dictionaries.isEmpty()) return emptyMap()
    val mountedState = loadMdxMountState(context)
    val mountedByCacheKey = if (mountedState.enabled) {
        mountedState.entries
            .asSequence()
            .filter { it.enabled && it.cacheKey.isNotBlank() && it.mdxUri.isNotBlank() }
            .associateBy { it.cacheKey }
    } else {
        emptyMap()
    }
    return dictionaries
        .asSequence()
        .filter { it.cacheKey.isNotBlank() && it.format.contains("MDX", ignoreCase = true) }
        .associate { dictionary ->
            val cacheKey = dictionary.cacheKey
            val mountedEntry = mountedByCacheKey[cacheKey]
            val rewriteContext = if (mountedEntry != null) {
                LookupRewriteContext(
                    mediaDir = null,
                    fallbackUriBuilder = { rawSrc -> buildMountedMdictResourceUri(cacheKey, rawSrc) }
                )
            } else {
                LookupRewriteContext(
                    mediaDir = File(dictionaryStorageDir(context, cacheKey), "mdictnative/media"),
                    fallbackUriBuilder = null
                )
            }
            cacheKey to rewriteContext
        }
}

private fun finalizeLookupResultsForDisplay(
    context: Context,
    dictionaries: List<LoadedDictionary>,
    query: String,
    results: List<DictionarySearchResult>,
    rewriteLimit: Int
): List<DictionarySearchResult> {
    if (results.isEmpty()) return emptyList()
    val limit = rewriteLimit.coerceAtLeast(1)
    val rewriteContextByCacheKey = buildLookupRewriteContextMap(context, dictionaries)
    return results.mapIndexed { index, result ->
        val cacheKey = result.sourceCacheKey.takeIf { it.isNotBlank() }
        val rewriteContext = cacheKey?.let(rewriteContextByCacheKey::get)
        val rewrittenDefinitions = result.entry.definitions
            .map { definition ->
                val mdxRewritten = if (index < limit && rewriteContext != null) {
                    rewriteMdictImageSrcToFileUri(
                        definition = definition,
                        mediaDir = rewriteContext.mediaDir,
                        fallbackUriBuilder = rewriteContext.fallbackUriBuilder,
                        logContext = "lookup cacheKey=${cacheKey ?: "unknown"} query=$query term=${result.entry.term}"
                    )
                } else {
                    definition
                }
                normalizeDefinitionForDisplaySql(
                    cacheKey?.let { rewriteBundledDictionaryResourceSrcForDisplay(mdxRewritten, it) }
                        ?: mdxRewritten
                )
            }
        result.copy(entry = result.entry.copy(definitions = rewrittenDefinitions))
    }
}

internal fun includeMountedMdxDictionary(
    context: Context,
    dictionaries: List<LoadedDictionary>
): List<LoadedDictionary> {
    val mounted = mountedMdxDictionariesFromState(context)
    if (mounted.isEmpty()) return dictionaries
    return (dictionaries + mounted).distinctBy { dictionary ->
        dictionary.cacheKey.trim().ifBlank { dictionary.name.trim().lowercase(Locale.ROOT) }
    }
}

private fun mountedMdxDictionariesFromState(context: Context): List<LoadedDictionary> {
    val mountedState = loadMdxMountState(context)
    if (!mountedState.enabled) return emptyList()
    return mountedState.entries
        .asSequence()
        .filter { it.enabled && it.cacheKey.isNotBlank() && it.mdxUri.isNotBlank() }
        .map { entry ->
            val displayName = entry.displayName.ifBlank { "MDX" }
            LoadedDictionary(
                cacheKey = entry.cacheKey,
                name = displayName.substringBeforeLast('.').ifBlank { displayName },
                format = "MDX (mounted)",
                entries = emptyList(),
                stylesCss = null,
                entryCount = 0
            )
        }
        .toList()
}

private fun searchDictionaryWithMdictNative(
    context: Context,
    dictionaries: List<LoadedDictionary>,
    query: String,
    maxResults: Int,
    profile: DictionaryQueryProfile
): List<DictionarySearchResult> {
    if (!MdictNativeBridge.isAvailable) return emptyList()
    val trimmedQuery = query.trim()
    if (trimmedQuery.isBlank()) return emptyList()

    // MDX mounted path is fixed to the fast profile.
    val scanLength = 8
    val lookupLimit = maxResults.coerceIn(8, 48)

    val merged = linkedMapOf<String, DictionarySearchResult>()
    data class NativeLookupJob(
        val order: Int,
        val dictionary: LoadedDictionary,
        val cacheKey: String,
        val mountedEntry: MdxMountedEntry?
    )
    val mountedState = loadMdxMountState(context)
    val mountedByCacheKey = if (mountedState.enabled) {
        mountedState.entries
            .asSequence()
            .filter { it.enabled && it.cacheKey.isNotBlank() && it.mdxUri.isNotBlank() }
            .associateBy { it.cacheKey }
    } else {
        emptyMap()
    }
    val jobs = dictionaries.mapIndexedNotNull { order, dictionary ->
        val isMdx = dictionary.format.contains("MDX", ignoreCase = true)
        if (!isMdx) return@mapIndexedNotNull null
        val cacheKey = dictionary.cacheKey.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
        NativeLookupJob(
            order = order,
            dictionary = dictionary,
            cacheKey = cacheKey,
            mountedEntry = mountedByCacheKey[cacheKey]
        )
    }

    val mountedHitsByCacheKey = hashMapOf<String, List<MdictNativeLookupHit>>()
    val mountedJobs = jobs.filter { it.mountedEntry != null }
    if (mountedJobs.isNotEmpty()) {
        val startedAt = System.currentTimeMillis()
        val threadCount = mountedJobs.size.coerceIn(1, 3)
        val executor = Executors.newFixedThreadPool(threadCount)
        val completion = java.util.concurrent.ExecutorCompletionService<Pair<NativeLookupJob, List<MdictNativeLookupHit>>>(executor)
        val futures = mutableListOf<java.util.concurrent.Future<Pair<NativeLookupJob, List<MdictNativeLookupHit>>>>()
        var earlyStop = false
        var hitsEstimate = 0
        var doneCount = 0
        try {
            mountedJobs.forEach { job ->
                futures += completion.submit(
                    Callable {
                        val entry = job.mountedEntry ?: return@Callable job to emptyList()
                        val hits = lookupMountedMdictNative(
                            context = context,
                            mdxUri = entry.mdxUri,
                            cacheKey = job.cacheKey,
                            query = trimmedQuery,
                            maxResults = lookupLimit,
                            scanLength = scanLength
                        )
                        if (hits.isEmpty()) {
                            Log.d(
                                MDICT_MEDIA_LOG_TAG,
                                "mounted lookup empty cacheKey=${job.cacheKey} query=$trimmedQuery mdxUri=${entry.mdxUri}"
                            )
                        }
                        job to hits
                    }
                )
            }
            repeat(mountedJobs.size) {
                val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
                val doneFuture = if (profile == DictionaryQueryProfile.FAST) {
                    completion.poll(250L, java.util.concurrent.TimeUnit.MILLISECONDS)
                } else {
                    completion.take()
                } ?: run {
                    if (profile == DictionaryQueryProfile.FAST && elapsed >= FAST_MOUNTED_EARLY_STOP_MS) {
                        earlyStop = true
                    }
                    return@repeat
                }
                val (doneJob, hits) = runCatching { doneFuture.get() }.getOrDefault(
                    NativeLookupJob(-1, mountedJobs.first().dictionary, "", null) to emptyList()
                )
                if (doneJob.cacheKey.isNotBlank()) {
                    mountedHitsByCacheKey[doneJob.cacheKey] = hits
                    doneCount += 1
                    hitsEstimate += hits.size
                }
                if (
                    profile == DictionaryQueryProfile.FAST &&
                    (hitsEstimate >= FAST_FIRST_SCREEN_RESULTS ||
                        (System.currentTimeMillis() - startedAt) >= FAST_MOUNTED_EARLY_STOP_MS)
                ) {
                    earlyStop = true
                    return@repeat
                }
            }
            if (earlyStop) {
                futures.forEach { future -> runCatching { future.cancel(true) } }
                Log.d(
                    MDICT_MEDIA_LOG_TAG,
                    "mounted lookup early-stop done=$doneCount/${mountedJobs.size} " +
                        "hits=$hitsEstimate elapsedMs=${(System.currentTimeMillis() - startedAt).coerceAtLeast(0L)} query=$trimmedQuery"
                )
            }
        } finally {
            executor.shutdown()
        }
        val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        Log.d(
            MDICT_MEDIA_LOG_TAG,
            "mounted lookup batch done dicts=${mountedJobs.size} elapsedMs=$elapsed query=$trimmedQuery"
        )
    }

    jobs.forEach { job ->
        val dictionary = job.dictionary
        val order = job.order
        val cacheKey = job.cacheKey
        val mounted = job.mountedEntry != null
        val mediaDir = if (mounted) null else File(dictionaryStorageDir(context, cacheKey), "mdictnative/media")
        val nativeHits = if (mounted) {
            mountedHitsByCacheKey[cacheKey].orEmpty()
        } else {
            val entriesFile = File(dictionaryStorageDir(context, cacheKey), "mdictnative/entries.ndjson")
            if (!entriesFile.isFile) return@forEach
            logMdictIdxbinState(
                context = "lookup-imported",
                cacheKey = cacheKey,
                entriesPath = entriesFile.absolutePath
            )
            if (mediaDir?.isDirectory == false) {
                Log.d(
                    MDICT_MEDIA_LOG_TAG,
                    "lookup mediaDirMissing cacheKey=$cacheKey dict=${dictionary.name} mediaDir=${mediaDir.absolutePath}"
                )
            }
            MdictNativeBridge.lookup(
                entriesPath = entriesFile.absolutePath,
                query = trimmedQuery,
                maxResults = lookupLimit,
                scanLength = scanLength
            )
        }
        nativeHits.forEachIndexed { rank, hit ->
            val definition = normalizeDefinitionForDisplaySql(
                rewriteMdictEntryLinkForDisplay(hit.definition)
            )
            if (definition.isBlank()) return@forEachIndexed
            val score = (hit.score - order - rank).coerceAtLeast(1)
            val result = DictionarySearchResult(
                entry = DictionaryEntry(
                    term = hit.term,
                    reading = hit.reading,
                    definitions = listOf(definition),
                    pitch = null,
                    frequency = null,
                    dictionary = dictionary.name
                ),
                score = score,
                matchedLength = hit.matchedLength,
                sourceCacheKey = cacheKey
            )
            val key = entryStableKey(result.entry)
            val existing = merged[key]
            if (existing == null || result.score > existing.score) {
                merged[key] = result
            }
        }
    }

    return merged.values
        .sortedWith(
            compareByDescending<DictionarySearchResult> { it.score }
                .thenBy { it.entry.term.length }
                .thenBy { it.entry.term }
        )
        .take(maxResults.coerceAtLeast(1))
}

private fun lookupMountedMdictNative(
    context: Context,
    mdxUri: String,
    cacheKey: String,
    query: String,
    maxResults: Int,
    scanLength: Int
): List<MdictNativeLookupHit> {
    val uri = runCatching { Uri.parse(mdxUri) }.getOrNull() ?: return emptyList()
    val fallbackFile = materializeMountedMdxTempFile(context, uri, cacheKey) ?: return emptyList()
    Log.d(MDICT_MEDIA_LOG_TAG, "mounted lookup stable file=${fallbackFile.absolutePath}")
    val mdxIdx = File("${fallbackFile.absolutePath}.idxbin")
    Log.d(
        MDICT_MEDIA_LOG_TAG,
        "mdx idxbin state cacheKey=$cacheKey mdx=${fallbackFile.absolutePath} " +
            "idx=${mdxIdx.absolutePath} idxExists=${mdxIdx.isFile} idxSize=${mdxIdx.length()}"
    )
    return MdictNativeBridge.lookupMdx(
        mdxPath = fallbackFile.absolutePath,
        cacheKey = cacheKey,
        query = query,
        maxResults = maxResults,
        scanLength = scanLength
    )
}

private fun logMdictIdxbinState(
    context: String,
    cacheKey: String,
    entriesPath: String
) {
    val entriesFile = File(entriesPath)
    val idxFile = File("$entriesPath.idxbin")
    Log.d(
        MDICT_MEDIA_LOG_TAG,
        "idxbin state ctx=$context cacheKey=$cacheKey entries=${entriesFile.absolutePath} " +
            "entriesExists=${entriesFile.isFile} entriesSize=${entriesFile.length()} " +
            "idx=${idxFile.absolutePath} idxExists=${idxFile.isFile} idxSize=${idxFile.length()}"
    )
}

private fun materializeMountedMdxTempFile(context: Context, uri: Uri, cacheKey: String): File? {
    val dir = File(dictionaryStorageDir(context, cacheKey), "mdictnative_mounted").apply { mkdirs() }
    val out = File(dir, "mounted.mdx")
    if (out.isFile && out.length() > 0L) {
        return out
    }
    val tmp = File(dir, "mounted.tmp")
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { output -> input.copyTo(output) }
        } ?: return null
        if (out.exists()) runCatching { out.delete() }
        if (!tmp.renameTo(out)) {
            runCatching { tmp.delete() }
            return null
        }
        out
    }.getOrNull()
}

internal fun prebuildMountedMdxIndexesAsync(
    context: Context,
    state: MdxMountState = loadMdxMountState(context)
) {
    if (!state.enabled) return
    val targets = state.entries
        .filter { it.enabled && it.cacheKey.isNotBlank() && it.mdxUri.isNotBlank() }
    if (targets.isEmpty()) return
    val stateKey = buildString {
        append(state.enabled)
        targets.forEach { entry ->
            append('|')
            append(entry.cacheKey)
            append('@')
            append(entry.mdxUri)
            append('#')
            append(entry.enabled)
        }
    }
    synchronized(mountedMdxPrewarmLock) {
        if (mountedMdxPrewarmStateKey == stateKey) return
        mountedMdxPrewarmStateKey = stateKey
    }

    Thread {
        val startedAt = System.currentTimeMillis()
        var warmedCount = 0
        targets.forEach { entry ->
            runCatching {
                val uri = Uri.parse(entry.mdxUri)
                materializeMountedMdxTempFile(
                    context = context.applicationContext,
                    uri = uri,
                    cacheKey = entry.cacheKey
                )?.let { file ->
                    // Warm native in-memory index to avoid first-query stall after app restart.
                    MdictNativeBridge.lookupMdx(
                        mdxPath = file.absolutePath,
                        cacheKey = entry.cacheKey,
                        query = "あ",
                        maxResults = 1,
                        scanLength = 1
                    )
                    warmedCount += 1
                }
            }
        }
        val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        Log.d(
            MDICT_MEDIA_LOG_TAG,
            "mounted prewarm done total=${targets.size} warmed=$warmedCount elapsedMs=$elapsed"
        )
    }.apply {
        name = "mdx-prewarm"
        isDaemon = true
        start()
    }
}

private fun clearMountedMdxRuntimeCaches() {
    synchronized(mountedMdxPrewarmLock) {
        mountedMdxPrewarmStateKey = null
    }
}

private fun searchDictionaryWithSqlite(
    context: Context,
    dictionaries: List<LoadedDictionary>,
    query: String,
    maxResults: Int,
    profile: DictionaryQueryProfile
): List<DictionarySearchResult> {
    val normalizedQuery = normalizeLookupSql(query)
    if (normalizedQuery.isBlank() || dictionaries.isEmpty()) return emptyList()
    val config = dictionarySqlLookupConfig(profile, maxResults)
    val merged = linkedMapOf<String, DictionarySearchResult>()

    dictionaries.forEachIndexed { order, dictionary ->
        val cacheKey = dictionary.cacheKey.takeIf { it.isNotBlank() } ?: return@forEachIndexed
        val mdxLikeDictionary = dictionary.format.contains("MDX", ignoreCase = true)
        if (mdxLikeDictionary) return@forEachIndexed
        val local = searchDictionaryWithMemoryIndex(
            context = context,
            cacheKey = cacheKey,
            normalizedQuery = normalizedQuery,
            rawQuery = query.trim(),
            config = config,
            dictionaryOrder = order,
            maxResults = maxResults
        )
        local.forEach { result ->
            val key = entryStableKey(result.entry)
            val existing = merged[key]
            if (existing == null || result.score > existing.score) {
                merged[key] = result
            }
        }
    }

    return merged.values
        .sortedWith(
            compareByDescending<DictionarySearchResult> { it.score }
                .thenBy { it.entry.term.length }
                .thenBy { it.entry.term }
        )
        .take(maxResults.coerceAtLeast(1) * 2)
}

private fun searchDictionaryWithMemoryIndex(
    context: Context,
    cacheKey: String,
    normalizedQuery: String,
    rawQuery: String,
    config: DictionarySqlLookupConfig,
    dictionaryOrder: Int,
    maxResults: Int
): List<DictionarySearchResult> {
    val startedAt = System.currentTimeMillis()
    val index = loadDictionaryMemoryIndex(context, cacheKey) ?: return emptyList()
    val out = mutableListOf<DictionarySearchResult>()
    val candidateTerms = linkedSetOf<String>()
    candidateTerms += normalizedQuery
    if (index.sortedTerms.isNotEmpty()) {
        candidateTerms += collectPrefixTermsFromBinaryIndex(
            sortedTerms = index.sortedTerms,
            normalizedPrefix = normalizedQuery,
            limit = config.prefixLimit
        )
    }

    val localLimit = (config.exactLimit + config.prefixLimit).coerceAtLeast(24)
    val seenByEntry = HashSet<Int>()
    candidateTerms.forEach { norm ->
        val ids = index.exactNormMap[norm] ?: return@forEach
        for (entryId in ids) {
            if (!seenByEntry.add(entryId)) continue
            val entry = index.entries.getOrNull(entryId) ?: continue
            addMemoryEntryResult(
                entry = entry,
                normalizedQuery = normalizedQuery,
                dictionaryOrder = dictionaryOrder,
                out = out
            )
            if (out.size >= localLimit) break
        }
    }

    if (out.size < config.containsTriggerSize && config.containsLimit > 0) {
        val containsLimit = config.containsLimit
        for (entry in index.entries) {
            if (!(entry.termNorm.contains(normalizedQuery) ||
                    entry.readingNorm.contains(normalizedQuery) ||
                    entry.aliasNorm.contains(normalizedQuery))
            ) continue
            addMemoryEntryResult(
                entry = entry,
                normalizedQuery = normalizedQuery,
                dictionaryOrder = dictionaryOrder,
                out = out
            )
            if (out.size >= containsLimit) break
        }
    }

    if (out.isEmpty() && rawQuery.isNotBlank()) {
        val rawLimit = (maxResults * 8).coerceIn(32, 240)
        for (entry in index.entries) {
            if (!(entry.term.contains(rawQuery) || (entry.reading?.contains(rawQuery) == true))) continue
            addMemoryEntryResult(
                entry = entry,
                normalizedQuery = normalizedQuery,
                dictionaryOrder = dictionaryOrder,
                out = out
            )
            if (out.size >= rawLimit) break
        }
    }
    Log.d(
        DICTIONARY_LOOKUP_TAG,
        "memorySearch cacheKey=$cacheKey query=$normalizedQuery out=${out.size} elapsedMs=${(System.currentTimeMillis() - startedAt).coerceAtLeast(0L)}"
    )
    return out
}

private fun loadDictionaryMemoryIndex(
    context: Context,
    cacheKey: String
): DictionaryMemoryIndex? {
    val startedAt = System.currentTimeMillis()
    val dbFile = dictionaryEntryDbFile(context, cacheKey)
    val dbLastModified = if (dbFile.isFile) dbFile.lastModified() else 0L
    synchronized(dictionaryMemoryIndexCache) {
        val cached = dictionaryMemoryIndexCache[cacheKey]
        if (cached != null && cached.dbLastModified == dbLastModified) {
            Log.d(
                DICTIONARY_LOOKUP_TAG,
                "memoryIndex HIT cacheKey=$cacheKey entries=${cached.entries.size} elapsedMs=${(System.currentTimeMillis() - startedAt).coerceAtLeast(0L)}"
            )
            return cached
        }
    }

    val db = openDictionaryEntriesDbForRead(context, cacheKey) ?: return null
    try {
        val entries = ArrayList<MemoryDictionaryEntry>(2048)
        val byNorm = HashMap<String, MutableList<Int>>(8192)
        db.query(
            TABLE_ENTRIES,
            arrayOf(
                COL_TERM,
                COL_READING,
                COL_DEFINITION,
                COL_PITCH,
                COL_FREQUENCY,
                COL_DICTIONARY_NAME,
                COL_TERM_NORM,
                COL_READING_NORM,
                COL_ALIAS_NORM
            ),
            "$COL_CACHE_KEY = ?",
            arrayOf(cacheKey),
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val term = cursor.getString(0).orEmpty()
                if (term.isBlank()) continue
                val memoryEntry = MemoryDictionaryEntry(
                    term = term,
                    reading = cursor.getString(1)?.trim()?.ifBlank { null },
                    definition = normalizeDefinitionForDisplaySql(cursor.getString(2).orEmpty()),
                    pitch = cursor.getString(3)?.trim()?.ifBlank { null },
                    frequency = cursor.getString(4)?.trim()?.ifBlank { null },
                    dictionaryName = cursor.getString(5).orEmpty(),
                    termNorm = cursor.getString(6).orEmpty(),
                    readingNorm = cursor.getString(7).orEmpty(),
                    aliasNorm = cursor.getString(8).orEmpty()
                )
                val entryId = entries.size
                entries += memoryEntry
                sequenceOf(memoryEntry.termNorm, memoryEntry.readingNorm, memoryEntry.aliasNorm)
                    .filter { it.isNotBlank() }
                    .forEach { norm ->
                        byNorm.getOrPut(norm) { ArrayList(2) }.add(entryId)
                    }
            }
        }
        val exactNormMap = byNorm.mapValues { (_, ids) -> ids.toIntArray() }
        val sortedTerms = readDictionaryTermBinaryIndex(context, cacheKey).orEmpty()
        val built = DictionaryMemoryIndex(
            cacheKey = cacheKey,
            dbLastModified = dbLastModified,
            entries = entries,
            exactNormMap = exactNormMap,
            sortedTerms = sortedTerms
        )
        synchronized(dictionaryMemoryIndexCache) {
            dictionaryMemoryIndexCache[cacheKey] = built
        }
        Log.d(
            DICTIONARY_LOOKUP_TAG,
            "memoryIndex BUILD cacheKey=$cacheKey entries=${entries.size} terms=${sortedTerms.size} elapsedMs=${(System.currentTimeMillis() - startedAt).coerceAtLeast(0L)}"
        )
        return built
    } finally {
        runCatching { db.close() }
    }
}

private fun addMemoryEntryResult(
    entry: MemoryDictionaryEntry,
    normalizedQuery: String,
    dictionaryOrder: Int,
    out: MutableList<DictionarySearchResult>
) {
    val score = scoreEntryByNormalizedSql(
        term = entry.termNorm,
        reading = entry.readingNorm,
        alias = entry.aliasNorm,
        normalizedQuery = normalizedQuery
    ).let { base ->
        if (base > 0) base else 42
    } - dictionaryOrder
    if (score <= 0) return
    out += DictionarySearchResult(
        entry = DictionaryEntry(
            term = entry.term,
            reading = entry.reading,
            definitions = listOf(entry.definition),
            pitch = entry.pitch,
            frequency = entry.frequency,
            dictionary = entry.dictionaryName
        ),
        score = score,
        sourceCacheKey = ""
    )
}

private fun parseMdxLinkTarget(raw: String): String? {
    val trimmed = raw.trimStart()
    if (!trimmed.startsWith("@@@LINK=")) return null
    val target = trimmed
        .removePrefix("@@@LINK=")
        .replace("\u0000", "")
        .lineSequence()
        .firstOrNull()
        .orEmpty()
        .trim()
    return target.ifBlank { null }
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

    onProgress?.invoke(DictionaryImportProgress(stage = "Preparing native import", current = 0, total = 0))
    val tempZip = File.createTempFile("dict_import_", ".zip", context.cacheDir)
    try {
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempZip).use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to read dictionary archive")

        val hoshiRoot = dictionaryHoshiRootDir(context, cacheKey)
        if (hoshiRoot.exists()) {
            hoshiRoot.deleteRecursively()
        }
        hoshiRoot.mkdirs()

        onProgress?.invoke(DictionaryImportProgress(stage = "Importing", current = 0, total = 0))
        val nativeResult = HoshiNativeBridge.importZip(
            zipPath = tempZip.absolutePath,
            outputDir = hoshiRoot.absolutePath,
            lowRam = false
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
            ?: locateHoshiDictionaryDir(context, cacheKey)
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
        val entryCount = nativeResult.termCount
            .coerceAtLeast(0L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        val entriesDb = openDictionaryEntriesDbForWrite(context, cacheKey)
        try {
            clearDictionaryEntryRows(entriesDb, cacheKey)
        } finally {
            runCatching { entriesDb.close() }
        }
        runCatching { dictionaryTermIndexFile(context, cacheKey).delete() }
        clearDictionaryTermIndexCache(cacheKey)

        val metaDb = writableDb(context)
        metaDb.beginTransaction()
        try {
            upsertDictionaryMeta(
                db = metaDb,
                cacheKey = cacheKey,
                sourceUri = uri.toString(),
                displayName = displayName,
                name = dictionaryName,
                format = "Yomichan/Migaku ZIP (hoshidicts)",
                stylesCss = stylesCss,
                entryCount = entryCount
            )
            metaDb.setTransactionSuccessful()
        } finally {
            metaDb.endTransaction()
        }

        HoshiNativeBridge.clearLookupCache()
        onProgress?.invoke(DictionaryImportProgress(stage = "Done", current = 1, total = 1))
        return LoadedDictionary(
            cacheKey = cacheKey,
            name = dictionaryName,
            format = "Yomichan/Migaku ZIP (hoshidicts)",
            entries = emptyList(),
            stylesCss = stylesCss,
            entryCount = entryCount
        )
    } finally {
        runCatching { tempZip.delete() }
    }
}

private fun saveParsedDictionaryToSqlite(
    context: Context,
    cacheKey: String,
    sourceUri: String,
    displayName: String,
    dictionary: LoadedDictionary
): LoadedDictionary {
    val entriesDb = openDictionaryEntriesDbForWrite(context, cacheKey)
    val count = try {
        withFastImportPragmas(entriesDb) {
            entriesDb.beginTransaction()
            try {
                clearDictionaryEntryRows(entriesDb, cacheKey)
                val statement = entriesDb.compileStatement(SQL_INSERT_ENTRY)
                var inserted = 0
                dictionary.entries.forEach { entry ->
                    insertEntrySql(statement, cacheKey, dictionary.name, entry)
                    inserted += 1
                }
                entriesDb.setTransactionSuccessful()
                inserted
            } finally {
                entriesDb.endTransaction()
            }
        }
    } finally {
        runCatching { writeDictionaryTermBinaryIndex(context, cacheKey, entriesDb) }
        runCatching { entriesDb.close() }
    }

    runCatching {
        writableDb(context).delete(TABLE_ENTRIES, "$COL_CACHE_KEY = ?", arrayOf(cacheKey))
    }

    val metaDb = writableDb(context)
    metaDb.beginTransaction()
    try {
        upsertDictionaryMeta(
            db = metaDb,
            cacheKey = cacheKey,
            sourceUri = sourceUri,
            displayName = displayName,
            name = dictionary.name,
            format = dictionary.format,
            stylesCss = dictionary.stylesCss,
            entryCount = count
        )
        metaDb.setTransactionSuccessful()
    } finally {
        metaDb.endTransaction()
    }

    return LoadedDictionary(
        cacheKey = cacheKey,
        name = dictionary.name,
        format = dictionary.format,
        entries = emptyList(),
        stylesCss = dictionary.stylesCss,
        entryCount = count
    )
}

private fun clearDictionaryEntryRows(db: SQLiteDatabase, cacheKey: String) {
    db.delete(TABLE_ENTRIES, "$COL_CACHE_KEY = ?", arrayOf(cacheKey))
}

private fun upsertDictionaryMeta(
    db: SQLiteDatabase,
    cacheKey: String,
    sourceUri: String,
    displayName: String,
    name: String,
    format: String,
    stylesCss: String?,
    entryCount: Int
) {
    val values = ContentValues().apply {
        put(COL_CACHE_KEY, cacheKey)
        put(COL_NAME, name)
        put(COL_FORMAT, format)
        put(COL_STYLES_CSS, stylesCss)
        put(COL_ENTRY_COUNT, entryCount)
        put(COL_URI, sourceUri)
        put(COL_DISPLAY_NAME, displayName)
        put(COL_UPDATED_AT, System.currentTimeMillis())
    }
    db.insertWithOnConflict(TABLE_DICTIONARIES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
}

internal fun glossaryRawToDefinitionHtmlSql(glossaryRaw: String): String {
    val trimmed = glossaryRaw.trim()
    if (trimmed.isBlank()) return ""
    val parsedDefinition = runCatching {
        val reader = JsonReader(StringReader(trimmed))
        reader.isLenient = true
        val value = readJsonValueSql(reader)
        extractGlossaryFromRawValueSql(value).firstOrNull().orEmpty()
    }.getOrNull().orEmpty()

    return parsedDefinition.ifBlank {
        normalizeDefinitionForDisplaySql(trimmed)
    }
}

private fun insertEntrySql(
    statement: SQLiteStatement,
    cacheKey: String,
    dictionaryName: String,
    entry: DictionaryEntry
) {
    val definition = entry.definitions.firstOrNull().orEmpty()
    val termNorm = normalizeLookupSql(entry.term)
    val readingNorm = normalizeLookupSql(entry.reading.orEmpty())
    val aliasNorm = if (FAST_IMPORT_ALIAS_FROM_DEFINITION) {
        extractAliasForLookupSql(entry.term, definition)
    } else {
        termNorm
    }

    statement.clearBindings()
    statement.bindString(1, cacheKey)
    statement.bindString(2, entry.term)
    if (entry.reading.isNullOrBlank()) statement.bindNull(3) else statement.bindString(3, entry.reading)
    if (definition.isBlank()) statement.bindNull(4) else statement.bindString(4, definition)
    if (entry.pitch.isNullOrBlank()) statement.bindNull(5) else statement.bindString(5, entry.pitch)
    if (entry.frequency.isNullOrBlank()) statement.bindNull(6) else statement.bindString(6, entry.frequency)
    statement.bindString(7, dictionaryName)
    statement.bindString(8, termNorm)
    statement.bindString(9, readingNorm)
    statement.bindString(10, aliasNorm)
    statement.executeInsert()
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
        return if (path.isBlank()) "" else "<img src=\"${escapeHtmlAttributeSql(path)}\" />"
    }

    val tagRaw = mapString("tag").trim().lowercase(Locale.ROOT)
    val content = extractTextSnippetSql(value["content"]).orEmpty()
    if (tagRaw.isNotBlank()) {
        val tag = tagRaw.replace(Regex("[^a-z0-9-]"), "")
        if (tag.isBlank()) return content

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
        ).takeIf { it.isNotBlank() }?.let {
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
    if (src.isNotBlank()) attrs["src"] = src
    val allowed = if (suppressWidthHeightAttr) {
        listOf("href", "alt", "title", "target", "rel", "colspan", "rowspan")
    } else {
        listOf("href", "alt", "title", "target", "rel", "width", "height", "colspan", "rowspan")
    }
    allowed.forEach { key ->
        val raw = value[key]?.toString()?.trim().orEmpty()
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
    return if (unit.matches(Regex("^[a-z%]+$"))) unit else null
}

private fun toCssLengthSql(raw: String, unit: String): String {
    val text = raw.trim()
    if (text.isBlank()) return ""
    return if (text.matches(Regex("^[+-]?(?:\\d+\\.?\\d*|\\.\\d+)$"))) "$text$unit" else text
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
    return if (looksLikeHtmlSql(trimmed)) trimmed else plainDefinitionToHtmlSql(trimmed)
}

private fun clampDefinitionLengthForStorageSql(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return ""
    return if (looksLikeHtmlSql(trimmed)) trimmed else trimmed.take(3200)
}

internal fun lookupDictionarySourceUriByCacheKey(context: Context, cacheKey: String): String? {
    if (cacheKey.isBlank()) return null
    return runCatching {
        readableDb(context).query(
            TABLE_DICTIONARIES,
            arrayOf(COL_URI),
            "$COL_CACHE_KEY = ?",
            arrayOf(cacheKey),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            cursor.getString(0)?.trim()?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}

private fun looksLikeHtmlSql(text: String): Boolean {
    return LOOKS_LIKE_HTML_REGEX.containsMatchIn(text)
}

private fun isLikelyDefinitionSql(text: String): Boolean {
    val plain = stripHtmlTagsSql(text)
    if (plain.length < 2) return false
    if (plain.all { it.isDigit() }) return false
    return true
}

private fun normalizeTextLineSql(raw: String): String {
    return raw
        .replace('\u0000', ' ')
        .replace(NORMALIZE_WHITESPACE_REGEX, " ")
        .trim()
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

private fun extractAliasForLookupSql(term: String, definitionHtml: String): String {
    val plain = stripHtmlTagsSql(definitionHtml).take(180)
    val termTokens = KANJI_TOKEN_REGEX
        .findAll(term)
        .map { it.value }
        .toList()
    val definitionTokens = KANJI_TOKEN_REGEX
        .findAll(plain)
        .map { it.value }
        .filter { it.length in 1..12 }
        .distinct()
        .take(8)
        .toList()
    return normalizeLookupSql((termTokens + definitionTokens).joinToString(""))
}

private fun scoreEntryByNormalizedSql(
    term: String,
    reading: String,
    alias: String,
    normalizedQuery: String
): Int {
    var score = 0
    when {
        term == normalizedQuery -> score = max(score, 120)
        reading.isNotBlank() && reading == normalizedQuery -> score = max(score, 115)
        alias.isNotBlank() && alias == normalizedQuery -> score = max(score, 112)
    }
    if (term.startsWith(normalizedQuery)) score = max(score, 96)
    if (reading.isNotBlank() && reading.startsWith(normalizedQuery)) score = max(score, 90)
    if (alias.isNotBlank() && alias.startsWith(normalizedQuery)) score = max(score, 89)
    if (term.contains(normalizedQuery)) score = max(score, 82)
    if (reading.isNotBlank() && reading.contains(normalizedQuery)) score = max(score, 78)
    if (alias.isNotBlank() && alias.contains(normalizedQuery)) score = max(score, 76)

    val distancePenalty = (term.length - normalizedQuery.length).coerceAtLeast(0).coerceAtMost(24)
    return (score - distancePenalty).coerceAtLeast(0)
}

private fun normalizeLookupSql(value: String): String {
    return value
        .lowercase(Locale.ROOT)
        .replace(NORMALIZE_PUNCT_OR_SYMBOL_REGEX, " ")
        .replace(NORMALIZE_WHITESPACE_REGEX, "")
}

private fun rewriteMdictEntryLinkForDisplay(definition: String): String {
    val target = parseMdxLinkTarget(definition) ?: return definition
    val encodedTarget = Uri.encode(target)
    val label = target
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    return "<a href=\"entry://$encodedTarget\">$label</a>"
}

private fun escapeHtmlAttributeSql(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private fun rewriteBundledDictionaryResourceSrcForDisplay(
    definition: String,
    cacheKey: String
): String {
    if (definition.isBlank()) return definition
    if (!definition.contains("href=", ignoreCase = true) &&
        !definition.contains("src=", ignoreCase = true)
    ) return definition

    fun resolveSrc(rawSrc: String): String {
        val src = rawSrc.trim().trim('"', '\'')
        if (src.isBlank()) return rawSrc
        if (src.startsWith("//")) return rawSrc
        if (src.startsWith("#")) return rawSrc
        if (src.startsWith("data:", ignoreCase = true)) return rawSrc
        if (URI_SCHEME_REGEX.containsMatchIn(src)) return rawSrc
        val normalized = src
            .replace('\\', '/')
            .trimStart('/')
            .removePrefix("./")
            .trim()
        if (normalized.isBlank()) return rawSrc
        return buildBundledDictionaryResourceUri(cacheKey, normalized)
    }

    return HTML_TAG_REGEX.replace(definition) { tagMatch ->
        var tag = tagMatch.value
        tag = HTML_ATTR_QUOTED_REGEX.replace(tag) { match ->
            val attr = match.groupValues[1]
            val quote = match.groupValues[2]
            val value = match.groupValues[3]
            "$attr=$quote${escapeHtmlAttributeSql(resolveSrc(value))}$quote"
        }
        tag = HTML_ATTR_UNQUOTED_REGEX.replace(tag) { match ->
            val attr = match.groupValues[1]
            val value = match.groupValues[2]
            "$attr=\"${escapeHtmlAttributeSql(resolveSrc(value))}\""
        }
        tag
    }
}

private fun escapeHtmlTextSql(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private fun rewriteMdictImageSrcToFileUri(
    definition: String,
    mediaDir: File?,
    fallbackUriBuilder: ((String) -> String)? = null,
    logContext: String = ""
): String {
    if (definition.isBlank()) return definition
    if (!definition.contains("<img", ignoreCase = true) &&
        !definition.contains("href=", ignoreCase = true) &&
        !definition.contains("src=", ignoreCase = true)
    ) return definition
    if (mediaDir?.isDirectory != true && fallbackUriBuilder == null) return definition

    var imageCount = 0
    var resolvedCount = 0
    val unresolved = mutableListOf<String>()

    fun resolveSrc(rawSrc: String): String {
        imageCount += 1
        val src = rawSrc.trim().trim('"', '\'')
        if (src.isBlank()) return rawSrc
        if (src.startsWith("//")) return rawSrc
        if (src.startsWith("#")) return rawSrc
        if (src.startsWith("data:", ignoreCase = true)) return rawSrc
        if (URI_SCHEME_REGEX.containsMatchIn(src)) return rawSrc

        val normalized = src
            .replace('\\', '/')
            .trimStart('/')
            .removePrefix("./")
            .trim()
        if (normalized.isBlank()) return rawSrc

        val decoded = runCatching {
            URLDecoder.decode(normalized, Charsets.UTF_8.name())
        }.getOrNull()
        val candidates = linkedSetOf(normalized)
        if (!decoded.isNullOrBlank()) candidates += decoded

        if (mediaDir?.isDirectory == true) {
            val resolved = candidates.firstNotNullOfOrNull { candidate ->
                val file = File(mediaDir, candidate)
                if (file.isFile) file else null
            }
            if (resolved != null) {
                resolvedCount += 1
                return resolved.toURI().toString()
            }
        }
        val fallback = fallbackUriBuilder?.invoke(normalized)
        if (!fallback.isNullOrBlank()) {
            resolvedCount += 1
            return fallback
        }
        if (unresolved.size < 3) unresolved += src
        return rawSrc
    }

    var out = HTML_TAG_REGEX.replace(definition) { tagMatch ->
        var tag = tagMatch.value
        tag = HTML_ATTR_QUOTED_REGEX.replace(tag) { match ->
            val attr = match.groupValues[1]
            val quote = match.groupValues[2]
            val value = match.groupValues[3]
            "$attr=$quote${escapeHtmlAttributeSql(resolveSrc(value))}$quote"
        }
        tag = HTML_ATTR_UNQUOTED_REGEX.replace(tag) { match ->
            val attr = match.groupValues[1]
            val value = match.groupValues[2]
            "$attr=\"${escapeHtmlAttributeSql(resolveSrc(value))}\""
        }
        tag
    }
    if (imageCount > 0) {
        Log.d(
            MDICT_MEDIA_LOG_TAG,
            "rewrite imgs total=$imageCount resolved=$resolvedCount unresolved=${(imageCount - resolvedCount).coerceAtLeast(0)} ctx=$logContext sampleMiss=${unresolved.joinToString("|")}"
        )
    }
    return out
}


