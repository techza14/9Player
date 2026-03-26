package moe.tekuza.m9player

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import android.net.Uri
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
import java.io.InputStreamReader
import java.io.StringReader
import java.util.Locale
import java.util.zip.ZipInputStream
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
private const val LOOKUP_QUERY_CACHE_LIMIT = 180
private const val FAST_IMPORT_ALIAS_FROM_DEFINITION = false
private const val IMPORT_PROGRESS_STEP = 3

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
private val KANJI_TOKEN_REGEX = Regex("[\\u4E00-\\u9FFF\\u3400-\\u4DBF\\uF900-\\uFAFF\\u3005\\u3006\\u30F6]{1,12}")
private val TERM_BANK_FILE_REGEX = Regex("term_bank_\\d+\\.json")

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

private data class SqlZipScanResult(
    val dictionaryName: String,
    val termBankPaths: List<String>,
    val stylesCss: String?,
    val firstDslPath: String?,
    val firstJsonPath: String?
)

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

private fun loadDictionaryLookupQueryCache(
    key: DictionaryLookupQueryCacheKey
): List<DictionarySearchResult>? {
    return synchronized(dictionaryLookupQueryCache) {
        dictionaryLookupQueryCache[key]
    }
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
    return dictionaries
        .mapNotNull { dictionary ->
            val cacheKey = dictionary.cacheKey.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            "$cacheKey:${dictionary.entryCount}"
        }
        .joinToString("|")
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
    val lookupLimit = when (profile) {
        DictionaryQueryProfile.FAST -> maxResults.coerceIn(16, 64)
        DictionaryQueryProfile.FULL -> (maxResults * 2).coerceIn(24, 140)
    }
    val scanLength = when (profile) {
        DictionaryQueryProfile.FAST -> 8
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
        val definition = glossaryRawToDefinitionHtmlSql(hit.glossaryRaw).ifBlank { return@forEachIndexed }
        val dictionaryName = hit.dictionary.ifBlank { dictionaries.firstOrNull()?.name.orEmpty() }
        val entry = DictionaryEntry(
            term = hit.term,
            reading = hit.reading?.ifBlank { null },
            definitions = listOf(definition),
            pitch = hit.pitch?.ifBlank { null },
            frequency = hit.frequency?.ifBlank { null },
            dictionary = dictionaryName
        )
        val order = dictionaryOrder[dictionaryName] ?: dictionaryOrder.size
        val score = hit.score + (dictionaryOrder.size - order) * 2 - (index / 8)
        val key = entryStableKey(entry)
        val next = DictionarySearchResult(
            entry = entry,
            score = score,
            matchedLength = hit.matchedLength
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
    val lower = displayName.lowercase(Locale.US)
    check(lower.endsWith(".zip")) { "仅支持 ZIP 辞典（hoshidicts）" }
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
    return imported
}

internal fun searchDictionarySql(
    context: Context,
    dictionaries: List<LoadedDictionary>,
    query: String,
    maxResults: Int = MAX_LOOKUP_RESULTS,
    profile: DictionaryQueryProfile = DictionaryQueryProfile.FULL
): List<DictionarySearchResult> {
    val normalizedQuery = normalizeLookupSql(query)
    if (normalizedQuery.isBlank() || dictionaries.isEmpty()) return emptyList()
    val dictionariesKey = buildLookupCacheDictionariesKey(dictionaries)
    if (dictionariesKey.isBlank()) return emptyList()
    val lookupCacheKey = DictionaryLookupQueryCacheKey(
        dictionariesKey = dictionariesKey,
        normalizedQuery = normalizedQuery,
        maxResults = maxResults,
        profile = profile
    )
    val cached = loadDictionaryLookupQueryCache(lookupCacheKey)
    if (cached != null) {
        return cached
    }

    val results = searchDictionaryWithHoshi(
        context = context,
        dictionaries = dictionaries,
        query = query,
        maxResults = maxResults,
        profile = profile
    )
    saveDictionaryLookupQueryCache(lookupCacheKey, results)
    return results
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

private fun importDictionaryZipToSqlite(
    context: Context,
    contentResolver: ContentResolver,
    uri: Uri,
    displayName: String,
    cacheKey: String,
    onProgress: ((DictionaryImportProgress) -> Unit)?
): LoadedDictionary {
    onProgress?.invoke(DictionaryImportProgress(stage = "Scanning archive", current = 0, total = 0))
    val scan = scanDictionaryZipSql(contentResolver, uri, displayName, onProgress)

    if (scan.termBankPaths.isEmpty()) {
        val fallback = parseDictionaryFile(contentResolver, uri, displayName, onProgress)
        return saveParsedDictionaryToSqlite(
            context = context,
            cacheKey = cacheKey,
            sourceUri = uri.toString(),
            displayName = displayName,
            dictionary = fallback
        )
    }

    val stylesCss = scan.stylesCss
    var entryCount = 0
    val entriesDb = openDictionaryEntriesDbForWrite(context, cacheKey)
    try {
        withFastImportPragmas(entriesDb) {
            entriesDb.beginTransaction()
            try {
                clearDictionaryEntryRows(entriesDb, cacheKey)

                val statement = entriesDb.compileStatement(SQL_INSERT_ENTRY)
                val remaining = scan.termBankPaths.toHashSet()
                val total = remaining.size
                var parsedCount = 0

                contentResolver.openInputStream(uri)?.use { stream ->
                    ZipInputStream(BufferedInputStream(stream)).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                val path = normalizeZipPathSql(entry.name)
                                if (path in remaining) {
                                    entryCount += parseTermBankIntoSql(zip, scan.dictionaryName, cacheKey, statement)
                                    remaining.remove(path)
                                    parsedCount += 1
                                    if (parsedCount == 1 || parsedCount % IMPORT_PROGRESS_STEP == 0 || parsedCount == total) {
                                        onProgress?.invoke(
                                            DictionaryImportProgress(
                                                stage = "Parsing term banks",
                                                current = parsedCount,
                                                total = total
                                            )
                                        )
                                    }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                } ?: error("Unable to read dictionary archive")

                entriesDb.setTransactionSuccessful()
            } finally {
                entriesDb.endTransaction()
            }
        }

        onProgress?.invoke(DictionaryImportProgress(stage = "Building binary index", current = 0, total = 0))
        writeDictionaryTermBinaryIndex(context, cacheKey, entriesDb)
    } finally {
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
            sourceUri = uri.toString(),
            displayName = displayName,
            name = scan.dictionaryName,
            format = "Yomichan/Migaku ZIP",
            stylesCss = stylesCss,
            entryCount = entryCount
        )
        metaDb.setTransactionSuccessful()
    } finally {
        metaDb.endTransaction()
    }

    onProgress?.invoke(DictionaryImportProgress(stage = "Done", current = 1, total = 1))
    return LoadedDictionary(
        cacheKey = cacheKey,
        name = scan.dictionaryName,
        format = "Yomichan/Migaku ZIP",
        entries = emptyList(),
        stylesCss = stylesCss,
        entryCount = entryCount
    )
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

private fun parseTermBankIntoSql(
    zip: ZipInputStream,
    dictionaryName: String,
    cacheKey: String,
    statement: SQLiteStatement
): Int {
    val reader = JsonReader(InputStreamReader(zip, Charsets.UTF_8))
    reader.isLenient = true
    if (reader.peek() != JsonToken.BEGIN_ARRAY) {
        reader.skipValue()
        return 0
    }

    var count = 0
    reader.beginArray()
    while (reader.hasNext()) {
        val parsed = when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> parseTermBankArrayRowSql(reader)
            JsonToken.BEGIN_OBJECT -> parseTermBankObjectRowSql(reader)
            else -> {
                reader.skipValue()
                null
            }
        } ?: continue

        val definitions = extractGlossaryFromRawValueSql(parsed.glossaryValue)
        if (definitions.isEmpty()) continue

        val entry = DictionaryEntry(
            term = parsed.term,
            reading = parsed.reading,
            definitions = definitions,
            pitch = null,
            frequency = null,
            dictionary = dictionaryName
        )
        insertEntrySql(statement, cacheKey, dictionaryName, entry)
        count += 1
    }
    reader.endArray()
    return count
}

private data class ParsedTermBankRowSql(
    val term: String,
    val reading: String?,
    val glossaryValue: Any?
)

private fun parseTermBankArrayRowSql(reader: JsonReader): ParsedTermBankRowSql? {
    reader.beginArray()
    var term = ""
    var reading: String? = null
    var glossaryValue: Any? = null
    var fallbackGlossaryValue: Any? = null
    var column = 0

    while (reader.hasNext()) {
        when (column) {
            0 -> term = readJsonScalarAsTextSql(reader).trim()
            1 -> reading = readJsonScalarAsTextSql(reader).trim().ifBlank { null }
            5 -> glossaryValue = readJsonValueSql(reader)
            else -> {
                if (glossaryValue == null && fallbackGlossaryValue == null && column in 2..10) {
                    val candidate = readJsonValueSql(reader)
                    if (looksLikeGlossaryCandidateSql(candidate)) {
                        fallbackGlossaryValue = candidate
                    }
                } else {
                    reader.skipValue()
                }
            }
        }
        column += 1
    }
    reader.endArray()

    if (term.isBlank()) return null
    return ParsedTermBankRowSql(
        term = term,
        reading = reading,
        glossaryValue = glossaryValue ?: fallbackGlossaryValue
    )
}

private fun parseTermBankObjectRowSql(reader: JsonReader): ParsedTermBankRowSql? {
    reader.beginObject()
    var term = ""
    var reading: String? = null
    var glossaryValue: Any? = null
    var fallbackGlossaryValue: Any? = null

    while (reader.hasNext()) {
        val key = reader.nextName().trim().lowercase(Locale.ROOT)
        when (key) {
            "term", "expression", "word" -> {
                term = readJsonScalarAsTextSql(reader).trim()
            }

            "reading", "kana", "pronunciation" -> {
                reading = readJsonScalarAsTextSql(reader).trim().ifBlank { null }
            }

            "glossary", "definition", "definitions", "meanings", "senses" -> {
                glossaryValue = readJsonValueSql(reader)
            }

            else -> {
                if (glossaryValue == null && fallbackGlossaryValue == null) {
                    val candidate = readJsonValueSql(reader)
                    if (
                        key.contains("glossary") ||
                        key.contains("definition") ||
                        key.contains("meaning")
                    ) {
                        if (looksLikeGlossaryCandidateSql(candidate)) {
                            fallbackGlossaryValue = candidate
                        }
                    }
                } else {
                    reader.skipValue()
                }
            }
        }
    }
    reader.endObject()

    if (term.isBlank()) return null
    return ParsedTermBankRowSql(
        term = term,
        reading = reading,
        glossaryValue = glossaryValue ?: fallbackGlossaryValue
    )
}

private fun looksLikeGlossaryCandidateSql(value: Any?): Boolean {
    return when (value) {
        null -> false
        is String -> value.trim().length >= 2
        is List<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> false
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

private fun readJsonScalarAsTextSql(reader: JsonReader): String {
    return when (reader.peek()) {
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        JsonToken.NULL -> {
            reader.nextNull()
            ""
        }

        else -> {
            val value = readJsonValueSql(reader)
            value?.toString().orEmpty()
        }
    }
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
    return normalizeDefinitionForDisplaySql(raw).take(3200)
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
        val styleAttr = styleValueToCssSql(value["style"]).takeIf { it.isNotBlank() }?.let {
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

    val canonical = when (base.lowercase(Locale.ROOT)) {
        "sc-class", "scclass", "class" -> "class"
        else -> base
            .replace(CAMEL_CASE_BOUNDARY_REGEX, "$1_$2")
            .lowercase(Locale.ROOT)
    }
    return canonical
        .replace(STRUCTURED_DATA_KEY_SANITIZE_REGEX, "")
        .trim('_', '-')
}

private fun buildStructuredDataScAttributesSql(data: Map<String, String>): String {
    if (data.isEmpty()) return ""
    return data.entries.joinToString(separator = "") { (key, value) ->
        val attrName = "data-sc-$key"
        " $attrName=\"${escapeHtmlAttributeSql(value)}\""
    }
}

private fun isVoidHtmlTagSql(tag: String): Boolean {
    return tag in setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")
}

private fun buildInlineHtmlAttributesSql(value: Map<*, *>): String {
    val allowed = listOf("src", "href", "alt", "title", "target", "rel", "width", "height", "colspan", "rowspan")
    return allowed.joinToString(separator = "") { key ->
        val raw = value[key]?.toString()?.trim().orEmpty()
        if (raw.isBlank()) "" else " $key=\"${escapeHtmlAttributeSql(raw)}\""
    }
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
        .map { it.take(3200) }
        .distinct()
        .take(2)
}

private fun normalizeDefinitionForDisplaySql(raw: String): String {
    val trimmed = raw.trim()
    return if (looksLikeHtmlSql(trimmed)) trimmed else normalizeTextLineSql(trimmed)
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

private fun escapeHtmlAttributeSql(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private fun scanDictionaryZipSql(
    contentResolver: ContentResolver,
    uri: Uri,
    fallbackName: String,
    onProgress: ((DictionaryImportProgress) -> Unit)?
): SqlZipScanResult {
    var dictionaryName = fallbackName.substringBeforeLast('.').ifBlank { "Dictionary" }
    val termBankPaths = mutableListOf<String>()
    var stylesCss: String? = null
    var stylesCssPriority = Int.MIN_VALUE
    var firstDslPath: String? = null
    var firstJsonPath: String? = null
    var scannedEntries = 0

    contentResolver.openInputStream(uri)?.use { stream ->
        ZipInputStream(BufferedInputStream(stream)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                scannedEntries += 1
                if (scannedEntries == 1 || scannedEntries % 25 == 0) {
                    onProgress?.invoke(
                        DictionaryImportProgress(
                            stage = "Scanning archive",
                            current = scannedEntries,
                            total = 0
                        )
                    )
                }
                if (!entry.isDirectory) {
                    val path = normalizeZipPathSql(entry.name)
                    val fileName = path.substringAfterLast('/').lowercase(Locale.US)
                    when {
                        fileName == "index.json" -> {
                            val raw = zip.readBytes().decodeToString()
                            val title = runCatching {
                                val obj = JSONObject(raw)
                                obj.optString("title").ifBlank { obj.optString("name") }
                            }.getOrNull()
                            if (!title.isNullOrBlank()) dictionaryName = title
                        }

                        fileName.endsWith(".css") -> {
                            val css = runCatching { zip.readBytes().decodeToString() }.getOrNull()
                            if (!css.isNullOrBlank()) {
                                val priority = when (fileName) {
                                    "styles.css" -> 3
                                    "style.css" -> 2
                                    else -> 1
                                }
                                if (priority >= stylesCssPriority) {
                                    stylesCss = css
                                    stylesCssPriority = priority
                                }
                            }
                        }

                        isTermBankFileSql(path) -> termBankPaths += path
                        firstDslPath == null && path.lowercase(Locale.US).endsWith(".dsl") -> firstDslPath = path

                        firstJsonPath == null &&
                            path.lowercase(Locale.US).endsWith(".json") &&
                            !path.substringAfterLast('/').equals("index.json", true) -> {
                            firstJsonPath = path
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    } ?: error("Unable to read dictionary archive")

    return SqlZipScanResult(
        dictionaryName = dictionaryName,
        termBankPaths = termBankPaths.sorted(),
        stylesCss = stylesCss,
        firstDslPath = firstDslPath,
        firstJsonPath = firstJsonPath
    )
}

private fun normalizeZipPathSql(path: String): String {
    return path.replace('\\', '/').trimStart('/')
}

private fun isTermBankFileSql(path: String): Boolean {
    val name = path.substringAfterLast('/').lowercase(Locale.US)
    return TERM_BANK_FILE_REGEX.matches(name)
}



