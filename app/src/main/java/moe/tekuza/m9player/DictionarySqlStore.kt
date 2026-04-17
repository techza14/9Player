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
private const val LOOKUP_QUERY_CACHE_SCHEMA_VERSION = 3
private const val FAST_IMPORT_ALIAS_FROM_DEFINITION = false
private const val IMPORT_PROGRESS_STEP = 3
private const val DICTIONARY_LOOKUP_TAG = "DictionaryLookup"
private const val MDICT_MEDIA_LOG_TAG = "MdictMedia"

internal enum class DictionaryQueryProfile {
    FAST,
    FULL
}

private enum class DictionaryImportType {
    ZIP,
    MDX
}

private val SAFE_LOCKING_MODE_REGEX = Regex("^[A-Za-z_]+$")
private val NORMALIZE_PUNCT_OR_SYMBOL_REGEX = Regex("[\\p{Punct}\\p{S}]")
private val NORMALIZE_WHITESPACE_REGEX = Regex("\\s+")
private val STRIP_HTML_TAGS_REGEX = Regex("<[^>]+>")
private val LOOKS_LIKE_HTML_REGEX = Regex("<\\s*/?\\s*[a-zA-Z][^>]*>")
private val CAMEL_CASE_BOUNDARY_REGEX = Regex("([a-z])([A-Z])")
private val STRUCTURED_DATA_KEY_SANITIZE_REGEX = Regex("[^a-z0-9_-]")
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

internal fun invalidateDictionaryLookupCaches() {
    clearDictionaryLookupQueryCache()
    HoshiNativeBridge.clearLookupCache()
    MdictNativeBridge.clearLookupCache()
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
    companionDocuments: Map<String, Uri> = emptyMap(),
    onProgress: ((DictionaryImportProgress) -> Unit)? = null
): LoadedDictionary {
    val imported = when (detectDictionaryImportType(displayName)) {
        DictionaryImportType.ZIP -> importDictionaryZipWithHoshi(
            context = context,
            contentResolver = contentResolver,
            uri = uri,
            displayName = displayName,
            cacheKey = cacheKey,
            onProgress = onProgress
        )

        DictionaryImportType.MDX -> importDictionaryMdxWithNative(
            context = context,
            contentResolver = contentResolver,
            uri = uri,
            displayName = displayName,
            cacheKey = cacheKey,
            companionDocuments = companionDocuments,
            onProgress = onProgress
        )
    }
    clearDictionaryLookupQueryCache()
    HoshiNativeBridge.clearLookupCache()
    MdictNativeBridge.clearLookupCache()
    return imported
}

private fun detectDictionaryImportType(displayName: String): DictionaryImportType {
    val lower = displayName.trim().lowercase(Locale.US)
    return when {
        lower.endsWith(".zip") -> DictionaryImportType.ZIP
        lower.endsWith(".mdx") -> DictionaryImportType.MDX
        else -> error("仅支持 ZIP 或 MDX 辞典")
    }
}

internal fun searchDictionarySql(
    context: Context,
    dictionaries: List<LoadedDictionary>,
    query: String,
    maxResults: Int = MAX_LOOKUP_RESULTS,
    profile: DictionaryQueryProfile = DictionaryQueryProfile.FULL
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
    val hasMountedMdx = effectiveDictionaries.any { it.format.contains("mounted", ignoreCase = true) }
    val lookupCacheKey = DictionaryLookupQueryCacheKey(
        dictionariesKey = dictionariesKey,
        normalizedQuery = normalizedQuery,
        maxResults = maxResults,
        profile = profile
    )
    val cached = if (hasMountedMdx) null else loadDictionaryLookupQueryCache(lookupCacheKey)
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
    val results = merged.values
        .sortedWith(
            compareByDescending<DictionarySearchResult> { it.score }
                .thenBy { it.entry.term.length }
                .thenBy { it.entry.term }
        )
        .take(maxResults)
    Log.d(
        DICTIONARY_LOOKUP_TAG,
        "search done hoshi=${hoshiResults.size} mdictNative=${mdictNativeResults.size} sqlite=${sqliteResults.size} merged=${results.size} query=$normalizedQuery"
    )
    if (!hasMountedMdx || results.isNotEmpty()) {
        saveDictionaryLookupQueryCache(lookupCacheKey, results)
    }
    return results
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

    val scanLength = when (profile) {
        DictionaryQueryProfile.FAST -> 8
        DictionaryQueryProfile.FULL -> 16
    }
    val lookupLimit = when (profile) {
        DictionaryQueryProfile.FAST -> maxResults.coerceIn(8, 48)
        DictionaryQueryProfile.FULL -> (maxResults * 2).coerceIn(16, 96)
    }

    val merged = linkedMapOf<String, DictionarySearchResult>()
    val mountedState = loadMdxMountState(context)
    val mountedByCacheKey = if (mountedState.enabled) {
        mountedState.entries
            .asSequence()
            .filter { it.enabled && it.cacheKey.isNotBlank() && it.mdxUri.isNotBlank() }
            .associateBy { it.cacheKey }
    } else {
        emptyMap()
    }
    dictionaries.forEachIndexed { order, dictionary ->
        val isMdx = dictionary.format.contains("MDX", ignoreCase = true)
        if (!isMdx) return@forEachIndexed
        val cacheKey = dictionary.cacheKey.takeIf { it.isNotBlank() } ?: return@forEachIndexed
        val mountedEntry = mountedByCacheKey[cacheKey]
        val mounted = mountedEntry != null
        val mediaDir = if (mounted) {
            null
        } else {
            File(dictionaryStorageDir(context, cacheKey), "mdictnative/media")
        }
        val nativeHits = if (mounted) {
            val hits = lookupMountedMdictNative(
                context = context,
                mdxUri = mountedEntry!!.mdxUri,
                cacheKey = cacheKey,
                query = trimmedQuery,
                maxResults = lookupLimit,
                scanLength = scanLength
            )
            if (hits.isEmpty()) {
                Log.d(
                    MDICT_MEDIA_LOG_TAG,
                    "mounted lookup empty cacheKey=$cacheKey query=$trimmedQuery mdxUri=${mountedEntry.mdxUri}"
                )
            }
            hits
        } else {
            val entriesFile = File(dictionaryStorageDir(context, cacheKey), "mdictnative/entries.ndjson")
            if (!entriesFile.isFile) return@forEachIndexed
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
                rewriteMdictImageSrcToFileUri(
                    definition = rewriteMdictEntryLinkForDisplay(hit.definition),
                    mediaDir = mediaDir,
                    fallbackUriBuilder = if (mounted) {
                        { rawSrc -> buildMountedMdictResourceUri(cacheKey, rawSrc) }
                    } else {
                        null
                    },
                    logContext = "lookup cacheKey=$cacheKey query=$trimmedQuery term=${hit.term}"
                )
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
                matchedLength = hit.matchedLength
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
    val pfd = runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
    if (pfd != null) {
        pfd.use {
            val fdPath = "/proc/self/fd/${it.fd}"
            Log.d(MDICT_MEDIA_LOG_TAG, "mounted lookup start cacheKey=$cacheKey query=$query fd=${it.fd}")
            val hits = MdictNativeBridge.lookupMdx(
                mdxPath = fdPath,
                cacheKey = cacheKey,
                query = query,
                maxResults = maxResults,
                scanLength = scanLength
            )
            if (hits.isNotEmpty()) return hits
            Log.d(MDICT_MEDIA_LOG_TAG, "mounted lookup fd path returned empty, fallback to temp file")
        }
    } else {
        Log.d(MDICT_MEDIA_LOG_TAG, "mounted lookup openFileDescriptor failed, fallback to temp file")
    }
    val fallbackFile = materializeMountedMdxTempFile(context, uri, cacheKey) ?: return emptyList()
    Log.d(MDICT_MEDIA_LOG_TAG, "mounted lookup fallback file=${fallbackFile.absolutePath}")
    return MdictNativeBridge.lookupMdx(
        mdxPath = fallbackFile.absolutePath,
        cacheKey = cacheKey,
        query = query,
        maxResults = maxResults,
        scanLength = scanLength
    )
}

private fun materializeMountedMdxTempFile(context: Context, uri: Uri, cacheKey: String): File? {
    val dir = File(context.cacheDir, "mdx_mount_runtime").apply { mkdirs() }
    val safeName = cacheKey.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "mounted" }
    val out = File(dir, "$safeName.mdx")
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        } ?: return null
        out
    }.getOrNull()
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
        val db = openDictionaryEntriesDbForRead(context, cacheKey) ?: return@forEachIndexed
        try {
            val candidateTerms = linkedSetOf<String>()
            candidateTerms += normalizedQuery
            val indexTerms = readDictionaryTermBinaryIndex(context, cacheKey)
            if (indexTerms != null) {
                candidateTerms += collectPrefixTermsFromBinaryIndex(
                    sortedTerms = indexTerms,
                    normalizedPrefix = normalizedQuery,
                    limit = config.prefixLimit
                )
            }

            val localLimit = (config.exactLimit + config.prefixLimit).coerceAtLeast(24)
            val local = mutableListOf<DictionarySearchResult>()
            candidateTerms.forEach { norm ->
                queryEntriesByExactNorm(
                    db = db,
                    cacheKey = cacheKey,
                    normalized = norm,
                    limit = localLimit,
                    dictionaryOrder = order,
                    out = local
                )
            }
            if (local.size < config.containsTriggerSize && config.containsLimit > 0) {
                queryEntriesByContainsNorm(
                    db = db,
                    cacheKey = cacheKey,
                    normalized = normalizedQuery,
                    limit = config.containsLimit,
                    dictionaryOrder = order,
                    out = local
                )
            }
            if (local.isEmpty()) {
                queryEntriesByRawContains(
                    db = db,
                    cacheKey = cacheKey,
                    rawQuery = query.trim(),
                    limit = (maxResults * 8).coerceIn(32, 240),
                    dictionaryOrder = order,
                    out = local
                )
            }
            local.forEach { result ->
                val key = entryStableKey(result.entry)
                val existing = merged[key]
                if (existing == null || result.score > existing.score) {
                    merged[key] = result
                }
            }
        } finally {
            runCatching { db.close() }
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

private fun queryEntriesByExactNorm(
    db: SQLiteDatabase,
    cacheKey: String,
    normalized: String,
    limit: Int,
    dictionaryOrder: Int,
    out: MutableList<DictionarySearchResult>
) {
    if (normalized.isBlank() || limit <= 0) return
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
        "$COL_CACHE_KEY = ? AND ($COL_TERM_NORM = ? OR $COL_READING_NORM = ? OR $COL_ALIAS_NORM = ?)",
        arrayOf(cacheKey, normalized, normalized, normalized),
        null,
        null,
        null,
        limit.toString()
    ).use { cursor ->
        while (cursor.moveToNext()) {
            addCursorEntryResult(db, cacheKey, cursor, normalized, dictionaryOrder, out)
        }
    }
}

private fun queryEntriesByContainsNorm(
    db: SQLiteDatabase,
    cacheKey: String,
    normalized: String,
    limit: Int,
    dictionaryOrder: Int,
    out: MutableList<DictionarySearchResult>
) {
    if (normalized.isBlank() || limit <= 0) return
    val likeArg = "%$normalized%"
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
        "$COL_CACHE_KEY = ? AND ($COL_TERM_NORM LIKE ? OR $COL_READING_NORM LIKE ? OR $COL_ALIAS_NORM LIKE ?)",
        arrayOf(cacheKey, likeArg, likeArg, likeArg),
        null,
        null,
        null,
        limit.toString()
    ).use { cursor ->
        while (cursor.moveToNext()) {
            addCursorEntryResult(db, cacheKey, cursor, normalized, dictionaryOrder, out)
        }
    }
}

private fun queryEntriesByRawContains(
    db: SQLiteDatabase,
    cacheKey: String,
    rawQuery: String,
    limit: Int,
    dictionaryOrder: Int,
    out: MutableList<DictionarySearchResult>
) {
    if (rawQuery.isBlank() || limit <= 0) return
    val likeArg = "%$rawQuery%"
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
        "$COL_CACHE_KEY = ? AND ($COL_TERM LIKE ? OR $COL_READING LIKE ?)",
        arrayOf(cacheKey, likeArg, likeArg),
        null,
        null,
        null,
        limit.toString()
    ).use { cursor ->
        while (cursor.moveToNext()) {
            addCursorEntryResult(db, cacheKey, cursor, normalizeLookupSql(rawQuery), dictionaryOrder, out)
        }
    }
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

private fun resolveLinkedDefinitionFromSqlite(
    db: SQLiteDatabase,
    cacheKey: String,
    initialDefinition: String,
    maxDepth: Int = 8
): String {
    var current = initialDefinition
    val visited = HashSet<String>()
    var depth = 0
    while (depth < maxDepth) {
        val target = parseMdxLinkTarget(current) ?: break
        if (!visited.add(target)) break
        val targetNorm = normalizeLookupSql(target)
        var nextDefinition: String? = null
        db.query(
            TABLE_ENTRIES,
            arrayOf(COL_DEFINITION),
            "$COL_CACHE_KEY = ? AND ($COL_TERM = ? OR $COL_TERM_NORM = ?)",
            arrayOf(cacheKey, target, targetNorm),
            null,
            null,
            null,
            "1"
        ).use { c ->
            if (c.moveToFirst()) {
                nextDefinition = c.getString(0).orEmpty()
            }
        }
        val next = nextDefinition?.trim().orEmpty()
        if (next.isBlank()) break
        current = next
        depth += 1
    }
    return current
}

private fun addCursorEntryResult(
    db: SQLiteDatabase,
    cacheKey: String,
    cursor: android.database.Cursor,
    normalizedQuery: String,
    dictionaryOrder: Int,
    out: MutableList<DictionarySearchResult>
) {
    val term = cursor.getString(0).orEmpty()
    if (term.isBlank()) return
    val reading = cursor.getString(1)?.trim()?.ifBlank { null }
    val definitionRaw = cursor.getString(2).orEmpty()
    val definition = resolveLinkedDefinitionFromSqlite(
        db = db,
        cacheKey = cacheKey,
        initialDefinition = definitionRaw
    )
    val pitch = cursor.getString(3)?.trim()?.ifBlank { null }
    val frequency = cursor.getString(4)?.trim()?.ifBlank { null }
    val dictionaryName = cursor.getString(5).orEmpty()
    val termNorm = cursor.getString(6).orEmpty()
    val readingNorm = cursor.getString(7).orEmpty()
    val aliasNorm = cursor.getString(8).orEmpty()

    val score = scoreEntryByNormalizedSql(
        term = termNorm,
        reading = readingNorm,
        alias = aliasNorm,
        normalizedQuery = normalizedQuery
    ).let { base ->
        if (base > 0) base else 42
    } - dictionaryOrder
    if (score <= 0) return

    out += DictionarySearchResult(
        entry = DictionaryEntry(
            term = term,
            reading = reading,
            definitions = listOf(definition),
            pitch = pitch,
            frequency = frequency,
            dictionary = dictionaryName
        ),
        score = score
    )
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

private fun importDictionaryMdxWithNative(
    context: Context,
    contentResolver: ContentResolver,
    uri: Uri,
    displayName: String,
    cacheKey: String,
    companionDocuments: Map<String, Uri>,
    onProgress: ((DictionaryImportProgress) -> Unit)?
): LoadedDictionary {
    if (!MdictNativeBridge.isAvailable) {
        error("mdict native bridge unavailable")
    }

    onProgress?.invoke(DictionaryImportProgress(stage = "Preparing MDX import", current = 0, total = 0))
    val tempImportDir = File.createTempFile("mdict_import_", "", context.cacheDir).apply {
        delete()
        mkdirs()
    }
    val baseName = displayName.substringBeforeLast('.').ifBlank { "dictionary" }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('_')
        .ifBlank { "dictionary" }
    val tempMdx = File(tempImportDir, "$baseName.mdx")
    val tempMdd = File(tempImportDir, "$baseName.mdd")
    try {
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempMdx).use { output ->
                input.copyTo(output)
            }
        } ?: error("Unable to read dictionary file")
        val copiedMdd = tryCopySiblingMddForImport(
            context = context,
            contentResolver = contentResolver,
            mdxUri = uri,
            displayName = displayName,
            companionDocuments = companionDocuments,
            targetMdd = tempMdd
        )
        Log.d(
            MDICT_MEDIA_LOG_TAG,
            "import prep mdx=${tempMdx.absolutePath} mddCopied=$copiedMdd mddPath=${tempMdd.absolutePath}"
        )

        val mdxOutputDir = File(dictionaryStorageDir(context, cacheKey), "mdictnative")
        if (mdxOutputDir.exists()) mdxOutputDir.deleteRecursively()
        mdxOutputDir.mkdirs()

        onProgress?.invoke(DictionaryImportProgress(stage = "Importing MDX", current = 0, total = 0))
        val result = MdictNativeBridge.importMdx(
            mdxPath = tempMdx.absolutePath,
            outputDir = mdxOutputDir.absolutePath
        )
        if (!result.success) {
            val detail = result.errors.firstOrNull().orEmpty()
            error(if (detail.isBlank()) "mdict native import failed" else "mdict native import failed: $detail")
        }
        val entriesFile = result.entriesFile
            .takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isFile }
            ?: error("mdict native import output missing entries file")
        val mediaDir = File(mdxOutputDir, "media")
        mediaDir.mkdirs()
        val copiedCompanion = copySiblingCompanionResourcesForMdict(
            context = context,
            contentResolver = contentResolver,
            mdxUri = uri,
            companionDocuments = companionDocuments,
            targetMediaDir = mediaDir
        )
        val mediaFiles = if (mediaDir.isDirectory) {
            runCatching { mediaDir.walkTopDown().count { it.isFile } }.getOrDefault(0)
        } else {
            0
        }
        Log.d(
            MDICT_MEDIA_LOG_TAG,
            "import done cacheKey=$cacheKey title=${result.title} terms=${result.termCount} mediaNative=${result.mediaCount} mediaCopied=$copiedCompanion mediaFiles=$mediaFiles mediaDir=${mediaDir.absolutePath}"
        )

        val dictionaryName = result.title.ifBlank {
            displayName.substringBeforeLast('.').ifBlank { "MDict" }
        }
        val totalEstimate = result.termCount
            .coerceAtLeast(0L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        onProgress?.invoke(DictionaryImportProgress(stage = "Saving MDX entries", current = 0, total = totalEstimate))
        val entriesDb = openDictionaryEntriesDbForWrite(context, cacheKey)
        val insertedCount = try {
            withFastImportPragmas(entriesDb) {
                entriesDb.beginTransaction()
                try {
                    clearDictionaryEntryRows(entriesDb, cacheKey)
                    val statement = entriesDb.compileStatement(SQL_INSERT_ENTRY)
                    var inserted = 0
                    entriesFile.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            val raw = line.trim()
                            if (raw.isBlank()) return@forEach
                            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
                            val term = json.optString("term").trim()
                            if (term.isBlank()) return@forEach
                            val reading = json.optString("reading").trim().ifBlank { null }
                            val definition = normalizeDefinitionForDisplaySql(
                                rewriteMdictImageSrcToFileUri(
                                    definition = json.optString("definition").trim(),
                                    mediaDir = mediaDir,
                                    logContext = "import cacheKey=$cacheKey term=$term"
                                )
                            )
                            val entry = DictionaryEntry(
                                term = term,
                                reading = reading,
                                definitions = listOf(definition),
                                pitch = null,
                                frequency = null,
                                dictionary = dictionaryName
                            )
                            insertEntrySql(statement, cacheKey, dictionaryName, entry)
                            inserted += 1
                            if (inserted % 500 == 0) {
                                onProgress?.invoke(
                                    DictionaryImportProgress(
                                        stage = "Saving MDX entries",
                                        current = inserted,
                                        total = totalEstimate
                                    )
                                )
                            }
                        }
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
        if (insertedCount <= 0) {
            val detail = result.errors.firstOrNull().orEmpty()
            error(if (detail.isBlank()) "mdict native import produced no entries" else "mdict native import produced no entries: $detail")
        }

        val metaDb = writableDb(context)
        metaDb.beginTransaction()
        try {
            upsertDictionaryMeta(
                db = metaDb,
                cacheKey = cacheKey,
                sourceUri = uri.toString(),
                displayName = displayName,
                name = dictionaryName,
                format = "MDX (native)",
                stylesCss = null,
                entryCount = insertedCount
            )
            metaDb.setTransactionSuccessful()
        } finally {
            metaDb.endTransaction()
        }

        onProgress?.invoke(
            DictionaryImportProgress(
                stage = "Done",
                current = insertedCount,
                total = totalEstimate.coerceAtLeast(insertedCount)
            )
        )
        return LoadedDictionary(
            cacheKey = cacheKey,
            name = dictionaryName,
            format = "MDX (native)",
            entries = emptyList(),
            stylesCss = null,
            entryCount = insertedCount
        )
    } finally {
        runCatching { tempImportDir.deleteRecursively() }
    }
}

private fun tryCopySiblingMddForImport(
    context: Context,
    contentResolver: ContentResolver,
    mdxUri: Uri,
    displayName: String,
    companionDocuments: Map<String, Uri>,
    targetMdd: File
): Boolean {
    val expectedMddName = displayName.substringBeforeLast('.') + ".mdd"
    val mddFromSelection = companionDocuments.entries.firstOrNull {
        it.key.equals(expectedMddName, ignoreCase = true)
    }?.value
    if (mddFromSelection != null) {
        runCatching {
            contentResolver.openInputStream(mddFromSelection)?.use { input ->
                FileOutputStream(targetMdd).use { output -> input.copyTo(output) }
            } ?: error("open selected mdd failed")
        }.onSuccess {
            Log.d(MDICT_MEDIA_LOG_TAG, "import sibling mdd copied from selected docs: $expectedMddName")
            return true
        }
    }

    if (mdxUri.scheme.equals("file", ignoreCase = true)) {
        val path = mdxUri.path.orEmpty()
        if (path.isNotBlank()) {
            val file = File(path).let { source ->
                File(source.parentFile ?: return false, expectedMddName)
            }
            if (file.isFile) {
                runCatching {
                    FileInputStream(file).use { input ->
                        FileOutputStream(targetMdd).use { output -> input.copyTo(output) }
                    }
                }.onSuccess {
                    Log.d(MDICT_MEDIA_LOG_TAG, "import sibling mdd copied from file uri: ${file.absolutePath}")
                    return true
                }
            }
        }
    }

    val doc = DocumentFile.fromSingleUri(context, mdxUri)
    val parent = doc?.parentFile
    if (parent != null && parent.canRead()) {
        val sibling = parent.findFile(expectedMddName)
        if (sibling != null && sibling.isFile) {
            contentResolver.openInputStream(sibling.uri)?.use { input ->
                FileOutputStream(targetMdd).use { output -> input.copyTo(output) }
            } ?: return false
            Log.d(MDICT_MEDIA_LOG_TAG, "import sibling mdd copied from document tree: $expectedMddName")
            return true
        }
    }

    runCatching {
        val docId = DocumentsContract.getDocumentId(mdxUri)
        val expectedId = docId.substringBeforeLast(':', docId) + ":" + expectedMddName
        val siblingUri = DocumentsContract.buildDocumentUriUsingTree(mdxUri, expectedId)
        contentResolver.openInputStream(siblingUri)?.use { input ->
            FileOutputStream(targetMdd).use { output -> input.copyTo(output) }
            Log.d(MDICT_MEDIA_LOG_TAG, "import sibling mdd copied via DocumentsContract id=$expectedId")
            return true
        }
    }

    Log.d(MDICT_MEDIA_LOG_TAG, "import sibling mdd not found, expected=$expectedMddName uri=$mdxUri")
    return false
}

private fun copySiblingCompanionResourcesForMdict(
    context: Context,
    contentResolver: ContentResolver,
    mdxUri: Uri,
    companionDocuments: Map<String, Uri>,
    targetMediaDir: File
): Int {
    if (!targetMediaDir.exists()) targetMediaDir.mkdirs()
    val allowedExtensions = setOf("css", "ddb", "svg", "png", "jpg", "jpeg", "gif", "webp", "woff", "woff2")
    var copied = 0

    if (companionDocuments.isNotEmpty()) {
        companionDocuments.forEach { (name, docUri) ->
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext !in allowedExtensions) return@forEach
            val target = File(targetMediaDir, name)
            runCatching {
                contentResolver.openInputStream(docUri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } ?: error("open selected companion failed")
            }.onSuccess { copied += 1 }
        }
        if (copied > 0) {
            Log.d(MDICT_MEDIA_LOG_TAG, "copied companion resources from selected docs count=$copied")
            return copied
        }
    }

    if (mdxUri.scheme.equals("file", ignoreCase = true)) {
        val path = mdxUri.path.orEmpty()
        val parent = File(path).parentFile
        if (parent != null && parent.isDirectory) {
            parent.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val ext = file.extension.lowercase(Locale.ROOT)
                if (ext !in allowedExtensions) return@forEach
                val target = File(targetMediaDir, file.name)
                runCatching { file.copyTo(target, overwrite = true) }
                    .onSuccess { copied += 1 }
            }
            if (copied > 0) {
                Log.d(MDICT_MEDIA_LOG_TAG, "copied companion resources from file dir count=$copied")
                return copied
            }
        }
    }

    val doc = DocumentFile.fromSingleUri(context, mdxUri)
    val parent = doc?.parentFile
    if (parent != null && parent.canRead()) {
        parent.listFiles().forEach { child ->
            if (!child.isFile) return@forEach
            val name = child.name.orEmpty()
            val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext !in allowedExtensions) return@forEach
            val target = File(targetMediaDir, name)
            runCatching {
                contentResolver.openInputStream(child.uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }.onSuccess { copied += 1 }
        }
    }

    Log.d(MDICT_MEDIA_LOG_TAG, "copied companion resources count=$copied")
    return copied
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


