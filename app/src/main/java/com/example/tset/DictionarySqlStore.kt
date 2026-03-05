package com.example.tset

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
import java.io.InputStreamReader
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.max

private const val DB_NAME = "dictionary_store.db"
private const val DB_VERSION = 1

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
private const val COL_TERM_FIRST = "term_first"
private const val COL_READING_FIRST = "reading_first"
private const val COL_ALIAS_FIRST = "alias_first"

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
        $COL_ALIAS_NORM,
        $COL_TERM_FIRST,
        $COL_READING_FIRST,
        $COL_ALIAS_FIRST
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
"""

private data class SqlZipScanResult(
    val dictionaryName: String,
    val termBankPaths: List<String>,
    val stylesCssPath: String?,
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
                $COL_ALIAS_NORM TEXT NOT NULL,
                $COL_TERM_FIRST TEXT,
                $COL_READING_FIRST TEXT,
                $COL_ALIAS_FIRST TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_cache ON $TABLE_ENTRIES($COL_CACHE_KEY)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_cache_term_norm ON $TABLE_ENTRIES($COL_CACHE_KEY, $COL_TERM_NORM)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_cache_reading_norm ON $TABLE_ENTRIES($COL_CACHE_KEY, $COL_READING_NORM)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_cache_alias_norm ON $TABLE_ENTRIES($COL_CACHE_KEY, $COL_ALIAS_NORM)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_cache_term_first ON $TABLE_ENTRIES($COL_CACHE_KEY, $COL_TERM_FIRST)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_cache_reading_first ON $TABLE_ENTRIES($COL_CACHE_KEY, $COL_READING_FIRST)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_entries_cache_alias_first ON $TABLE_ENTRIES($COL_CACHE_KEY, $COL_ALIAS_FIRST)")
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
    return runCatching {
        val db = writableDb(context)
        db.beginTransaction()
        try {
            db.delete(TABLE_ENTRIES, "$COL_CACHE_KEY = ?", arrayOf(cacheKey))
            db.delete(TABLE_DICTIONARIES, "$COL_CACHE_KEY = ?", arrayOf(cacheKey))
            db.setTransactionSuccessful()
            true
        } finally {
            db.endTransaction()
        }
    }.getOrElse { false }
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
    return if (lower.endsWith(".zip")) {
        importDictionaryZipToSqlite(context, contentResolver, uri, displayName, cacheKey, onProgress)
    } else {
        val parsed = parseDictionaryFile(contentResolver, uri, displayName, onProgress)
        saveParsedDictionaryToSqlite(context, cacheKey, uri.toString(), displayName, parsed)
    }
}

internal fun searchDictionarySql(
    context: Context,
    dictionaries: List<LoadedDictionary>,
    query: String,
    maxResults: Int = MAX_LOOKUP_RESULTS
): List<DictionarySearchResult> {
    val normalizedQuery = normalizeLookupSql(query)
    if (normalizedQuery.isBlank() || dictionaries.isEmpty()) return emptyList()

    val db = readableDb(context)
    val merged = linkedMapOf<String, Pair<DictionaryEntry, Int>>()

    dictionaries.forEach { dictionary ->
        val cacheKey = dictionary.cacheKey.takeIf { it.isNotBlank() } ?: return@forEach
        val localBest = HashMap<String, Pair<DictionaryEntry, Int>>()

        fun collect(selection: String, args: Array<String>, limit: Int) {
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
                selection,
                args,
                null,
                null,
                null,
                limit.toString()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val term = cursor.getString(0).orEmpty()
                    val reading = cursor.getString(1)?.takeIf { it.isNotBlank() }
                    val definition = cursor.getString(2).orEmpty()
                    val pitch = cursor.getString(3)?.takeIf { it.isNotBlank() }
                    val frequency = cursor.getString(4)?.takeIf { it.isNotBlank() }
                    val dictionaryName = cursor.getString(5).orEmpty().ifBlank { dictionary.name }
                    val score = scoreEntryByNormalizedSql(
                        term = cursor.getString(6).orEmpty(),
                        reading = cursor.getString(7).orEmpty(),
                        alias = cursor.getString(8).orEmpty(),
                        normalizedQuery = normalizedQuery
                    )
                    if (score <= 0) continue

                    val entry = DictionaryEntry(
                        term = term,
                        reading = reading,
                        definitions = definition.takeIf { it.isNotBlank() }?.let(::listOf) ?: emptyList(),
                        pitch = pitch,
                        frequency = frequency,
                        dictionary = dictionaryName
                    )
                    val key = entryStableKey(entry)
                    val existing = localBest[key]
                    if (existing == null || score > existing.second) {
                        localBest[key] = entry to score
                    }
                }
            }
        }

        val prefix = "$normalizedQuery%"
        collect("$COL_CACHE_KEY = ? AND $COL_TERM_NORM = ?", arrayOf(cacheKey, normalizedQuery), 80)
        collect("$COL_CACHE_KEY = ? AND $COL_READING_NORM = ?", arrayOf(cacheKey, normalizedQuery), 80)
        collect("$COL_CACHE_KEY = ? AND $COL_ALIAS_NORM = ?", arrayOf(cacheKey, normalizedQuery), 80)
        collect("$COL_CACHE_KEY = ? AND $COL_TERM_NORM LIKE ?", arrayOf(cacheKey, prefix), 320)
        collect("$COL_CACHE_KEY = ? AND $COL_READING_NORM LIKE ?", arrayOf(cacheKey, prefix), 280)
        collect("$COL_CACHE_KEY = ? AND $COL_ALIAS_NORM LIKE ?", arrayOf(cacheKey, prefix), 280)

        if (localBest.size < 12) {
            val contains = "%$normalizedQuery%"
            collect(
                "$COL_CACHE_KEY = ? AND ($COL_TERM_NORM LIKE ? OR $COL_READING_NORM LIKE ? OR $COL_ALIAS_NORM LIKE ?)",
                arrayOf(cacheKey, contains, contains, contains),
                380
            )
        }

        localBest.forEach { (key, value) ->
            val existing = merged[key]
            if (existing == null || value.second > existing.second) {
                merged[key] = value
            }
        }
    }

    return merged.values
        .map { (entry, score) -> DictionarySearchResult(entry = entry, score = score) }
        .sortedWith(
            compareByDescending<DictionarySearchResult> { it.score }
                .thenBy { it.entry.term.length }
                .thenBy { it.entry.term }
        )
        .take(maxResults)
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

    val stylesCss = scan.stylesCssPath
        ?.let { path -> readZipEntryTextSql(contentResolver, uri, path) }
        ?.trim()
        ?.ifBlank { null }

    val db = writableDb(context)
    var entryCount = 0

    db.beginTransaction()
    try {
        clearDictionaryRows(db, cacheKey)

        val statement = db.compileStatement(SQL_INSERT_ENTRY)
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
                            onProgress?.invoke(
                                DictionaryImportProgress(
                                    stage = "Parsing term banks",
                                    current = parsedCount,
                                    total = total
                                )
                            )
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: error("Unable to read dictionary archive")

        upsertDictionaryMeta(
            db = db,
            cacheKey = cacheKey,
            sourceUri = uri.toString(),
            displayName = displayName,
            name = scan.dictionaryName,
            format = "Yomichan/Migaku ZIP",
            stylesCss = stylesCss,
            entryCount = entryCount
        )

        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
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
    val db = writableDb(context)
    db.beginTransaction()
    try {
        clearDictionaryRows(db, cacheKey)
        val statement = db.compileStatement(SQL_INSERT_ENTRY)
        var count = 0
        dictionary.entries.forEach { entry ->
            insertEntrySql(statement, cacheKey, dictionary.name, entry)
            count += 1
        }

        upsertDictionaryMeta(
            db = db,
            cacheKey = cacheKey,
            sourceUri = sourceUri,
            displayName = displayName,
            name = dictionary.name,
            format = dictionary.format,
            stylesCss = dictionary.stylesCss,
            entryCount = count
        )

        db.setTransactionSuccessful()
        return LoadedDictionary(
            cacheKey = cacheKey,
            name = dictionary.name,
            format = dictionary.format,
            entries = emptyList(),
            stylesCss = dictionary.stylesCss,
            entryCount = count
        )
    } finally {
        db.endTransaction()
    }
}

private fun clearDictionaryRows(db: SQLiteDatabase, cacheKey: String) {
    db.delete(TABLE_ENTRIES, "$COL_CACHE_KEY = ?", arrayOf(cacheKey))
    db.delete(TABLE_DICTIONARIES, "$COL_CACHE_KEY = ?", arrayOf(cacheKey))
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
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            continue
        }

        reader.beginArray()
        var term = ""
        var reading: String? = null
        var glossaryValue: Any? = null
        val fallbackValues = mutableListOf<Any?>()
        var column = 0

        while (reader.hasNext()) {
            when (column) {
                0 -> term = readJsonScalarAsTextSql(reader).trim()
                1 -> reading = readJsonScalarAsTextSql(reader).trim().ifBlank { null }
                5 -> glossaryValue = readJsonValueSql(reader)
                else -> fallbackValues += readJsonValueSql(reader)
            }
            column += 1
        }
        reader.endArray()

        if (term.isBlank()) continue
        val definitions = extractGlossaryFromRawValueSql(glossaryValue).ifEmpty {
            extractGlossaryFromFallbackSql(fallbackValues)
        }
        if (definitions.isEmpty()) continue

        val entry = DictionaryEntry(
            term = term,
            reading = reading,
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

private fun insertEntrySql(
    statement: SQLiteStatement,
    cacheKey: String,
    dictionaryName: String,
    entry: DictionaryEntry
) {
    val definition = entry.definitions.firstOrNull().orEmpty()
    val termNorm = normalizeLookupSql(entry.term)
    val readingNorm = normalizeLookupSql(entry.reading.orEmpty())
    val aliasNorm = extractAliasForLookupSql(entry.term, definition)

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
    bindFirstChar(statement, 11, termNorm)
    bindFirstChar(statement, 12, readingNorm)
    bindFirstChar(statement, 13, aliasNorm)
    statement.executeInsert()
}

private fun bindFirstChar(statement: SQLiteStatement, index: Int, value: String) {
    val first = value.firstOrNull()?.toString().orEmpty()
    if (first.isBlank()) statement.bindNull(index) else statement.bindString(index, first)
}

private fun extractGlossaryFromFallbackSql(values: List<Any?>): List<String> {
    val definitions = mutableListOf<String>()
    values.forEach { value ->
        if (definitions.size >= 2) return@forEach
        val text = extractTextSnippetSql(value)
        if (!text.isNullOrBlank()) definitions += text
    }
    return compactDefinitionsSql(definitions)
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
        is String -> value.trim()
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
    fun mapString(key: String): String = value[key]?.toString()?.trim().orEmpty()

    val type = mapString("type").lowercase(Locale.ROOT)
    if (type == "image") {
        val path = listOf("path", "src", "url")
            .map { mapString(it) }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return if (path.isBlank()) "" else "<img src=\"${escapeHtmlAttributeSql(path)}\" />"
    }

    val tagRaw = mapString("tag").lowercase(Locale.ROOT)
    val content = extractTextSnippetSql(value["content"]).orEmpty()
    if (tagRaw.isNotBlank()) {
        val tag = tagRaw.replace(Regex("[^a-z0-9-]"), "")
        if (tag.isBlank()) return content

        val dataMap = value["data"] as? Map<*, *>
        val dataClass = dataMap?.get("class")?.toString()?.trim().orEmpty()
        val scClass = listOf("sc-class", "scClass", "class")
            .mapNotNull { key -> dataMap?.get(key)?.toString()?.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        val explicitClass = mapString("class")
        val mergedClass = when {
            explicitClass.isNotBlank() && dataClass.isNotBlank() -> "$explicitClass $dataClass"
            explicitClass.isNotBlank() -> explicitClass
            dataClass.isNotBlank() -> dataClass
            else -> ""
        }
        val classAttr = mergedClass.takeIf { it.isNotBlank() }?.let {
            " class=\"${escapeHtmlAttributeSql(it)}\""
        } ?: ""
        val scClassAttr = scClass.takeIf { it.isNotBlank() }?.let {
            " data-sc-class=\"${escapeHtmlAttributeSql(it)}\""
        } ?: ""
        val langAttr = mapString("lang").takeIf { it.isNotBlank() }?.let {
            " lang=\"${escapeHtmlAttributeSql(it)}\""
        } ?: ""
        val styleAttr = styleValueToCssSql(value["style"]).takeIf { it.isNotBlank() }?.let {
            " style=\"${escapeHtmlAttributeSql(it)}\""
        } ?: ""
        return "<$tag$classAttr$scClassAttr$langAttr$styleAttr>$content</$tag>"
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
    return fallback.joinToString("<br>")
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
        .replace(Regex("([a-z])([A-Z])"), "$1-$2")
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
    return Regex("<\\s*/?\\s*[a-zA-Z][^>]*>").containsMatchIn(text)
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
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun stripHtmlTagsSql(value: String): String {
    return value
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun extractAliasForLookupSql(term: String, definitionHtml: String): String {
    val plain = stripHtmlTagsSql(definitionHtml).take(180)
    val termTokens = Regex("[\\u4E00-\\u9FFF\\u3400-\\u4DBF\\uF900-\\uFAFF々〆ヶ]{1,12}")
        .findAll(term)
        .map { it.value }
        .toList()
    val definitionTokens = Regex("[\\u4E00-\\u9FFF\\u3400-\\u4DBF\\uF900-\\uFAFF々〆ヶ]{1,12}")
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
        .replace(Regex("[\\p{Punct}\\p{S}]"), " ")
        .replace(Regex("\\s+"), "")
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
    var stylesCssPath: String? = null
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
                    when {
                        path.substringAfterLast('/').equals("index.json", true) -> {
                            val raw = zip.readBytes().decodeToString()
                            val title = runCatching {
                                val obj = JSONObject(raw)
                                obj.optString("title").ifBlank { obj.optString("name") }
                            }.getOrNull()
                            if (!title.isNullOrBlank()) dictionaryName = title
                        }

                        stylesCssPath == null && path.substringAfterLast('/').equals("styles.css", true) -> {
                            stylesCssPath = path
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
        stylesCssPath = stylesCssPath,
        firstDslPath = firstDslPath,
        firstJsonPath = firstJsonPath
    )
}

private fun readZipEntryTextSql(contentResolver: ContentResolver, uri: Uri, targetPath: String): String? {
    val normalizedTarget = normalizeZipPathSql(targetPath)
    return contentResolver.openInputStream(uri)?.use { stream ->
        ZipInputStream(BufferedInputStream(stream)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && normalizeZipPathSql(entry.name) == normalizedTarget) {
                    return@use zip.readBytes().decodeToString()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            null
        }
    }
}

private fun normalizeZipPathSql(path: String): String {
    return path.replace('\\', '/').trimStart('/')
}

private fun isTermBankFileSql(path: String): Boolean {
    val name = path.substringAfterLast('/').lowercase(Locale.US)
    return Regex("term_bank_\\d+\\.json").matches(name)
}
