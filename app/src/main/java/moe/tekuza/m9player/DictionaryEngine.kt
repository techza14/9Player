
package moe.tekuza.m9player

import android.content.ContentResolver
import android.net.Uri
import android.text.Html
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.math.max

internal const val MAX_LOOKUP_RESULTS = 40

private const val MAX_DEFS_PER_ENTRY = 1
private const val MAX_DEF_LENGTH = 1200
private const val MAX_ENTRIES_PER_DICTIONARY = 90_000

private val STRUCTURED_DATA_KEY_SANITIZE_REGEX = Regex("[^a-z0-9_-]")
private val MARKDOWN_IMAGE_REGEX = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)")
private val MARKDOWN_LINK_REGEX = Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)")
private val PLAIN_URL_REGEX = Regex("https?://[^\\s<]+")

private data class TermMeta(
    val pitches: MutableSet<String> = linkedSetOf(),
    val frequencies: MutableSet<String> = linkedSetOf()
)

private data class ZipScanResult(
    val dictionaryName: String,
    val termBankPaths: List<String>,
    val termMeta: Map<String, TermMeta>,
    val stylesCssPath: String?,
    val firstDslPath: String?,
    val firstJsonPath: String?
)

internal data class DictionaryImportProgress(
    val stage: String,
    val current: Int,
    val total: Int
)

private data class DictionarySearchCache(
    val entries: List<DictionaryEntry>,
    val normalizedTerms: Array<String>,
    val normalizedReadings: Array<String>,
    val normalizedAliases: Array<String>,
    val prefix1Index: Map<Char, IntArray>
)

private enum class LookupTokenType {
    KANJI,
    HIRAGANA,
    KATAKANA,
    LATIN_DIGIT
}

private data class LookupTokenSpan(
    val token: String,
    val start: Int,
    val endExclusive: Int,
    val type: LookupTokenType
)

private val searchCache = HashMap<String, DictionarySearchCache>()

internal fun parseDictionaryFile(
    contentResolver: ContentResolver,
    uri: Uri,
    displayName: String,
    onProgress: ((DictionaryImportProgress) -> Unit)? = null
): LoadedDictionary {
    val lowerName = displayName.lowercase(Locale.US)
    val dictionary = when {
        lowerName.endsWith(".zip") -> parseDictionaryZip(contentResolver, uri, displayName, onProgress)
        lowerName.endsWith(".dsl") -> parseDslDictionary(readTextFromUri(contentResolver, uri), displayName)
        lowerName.endsWith(".json") -> parseJsonDictionary(readTextFromUri(contentResolver, uri), displayName)
        else -> {
            runCatching { parseDictionaryZip(contentResolver, uri, displayName, onProgress) }
                .recoverCatching { parseJsonDictionary(readTextFromUri(contentResolver, uri), displayName) }
                .getOrElse { parseDslDictionary(readTextFromUri(contentResolver, uri), displayName) }
        }
    }
    onProgress?.invoke(DictionaryImportProgress(stage = "Preparing dictionary", current = 0, total = 0))
    onProgress?.invoke(DictionaryImportProgress(stage = "Done", current = 1, total = 1))
    return dictionary
}

internal fun prepareDictionarySearchCache(dictionary: LoadedDictionary) {
    cacheForDictionary(dictionary)
}

internal fun searchDictionary(
    dictionaries: List<LoadedDictionary>,
    query: String,
    maxResults: Int = MAX_LOOKUP_RESULTS
): List<DictionarySearchResult> {
    val normalizedQuery = normalizeLookup(query)
    if (normalizedQuery.isBlank()) return emptyList()

    val hits = mutableListOf<DictionarySearchResult>()
    dictionaries.forEach { dictionary ->
        val cache = cacheForDictionary(dictionary)
        val localBest = HashMap<Int, Int>()
        val candidateSet = linkedSetOf<Int>()
        val firstChar = normalizedQuery.firstOrNull()
        if (firstChar != null) {
            cache.prefix1Index[firstChar]?.forEach(candidateSet::add)
        }
        if (candidateSet.isEmpty()) return@forEach

        fun considerEntry(index: Int) {
            val score = scoreEntryByNormalized(
                term = cache.normalizedTerms[index],
                reading = cache.normalizedReadings[index],
                alias = cache.normalizedAliases[index],
                normalizedQuery = normalizedQuery
            )
            if (score > 0) {
                localBest[index] = max(localBest[index] ?: 0, score)
            }
        }

        candidateSet.forEach(::considerEntry)

        localBest.forEach { (index, score) ->
            hits += DictionarySearchResult(entry = cache.entries[index], score = score)
        }
    }

    return hits
        .sortedWith(
            compareByDescending<DictionarySearchResult> { it.score }
                .thenBy { it.entry.term.length }
                .thenBy { it.entry.term }
        )
        .distinctBy { entryStableKey(it.entry) }
        .take(maxResults)
}

private fun cacheForDictionary(dictionary: LoadedDictionary): DictionarySearchCache {
    val cacheKey = dictionarySearchCacheKey(dictionary)
    synchronized(searchCache) {
        val existing = searchCache[cacheKey]
        if (existing != null) return existing

        val cache = buildDictionarySearchCache(dictionary)
        searchCache[cacheKey] = cache
        return cache
    }
}

private fun buildDictionarySearchCache(dictionary: LoadedDictionary): DictionarySearchCache {
    val entries = dictionary.entries
    val normalizedTerms = Array(entries.size) { "" }
    val normalizedReadings = Array(entries.size) { "" }
    val normalizedAliases = Array(entries.size) { "" }

    val prefix1Index = HashMap<Char, MutableList<Int>>()

    entries.forEachIndexed { index, entry ->
        val term = normalizeLookup(entry.term)
        val reading = normalizeLookup(entry.reading.orEmpty())
        val alias = extractAliasForLookup(entry)
        normalizedTerms[index] = term
        normalizedReadings[index] = reading
        normalizedAliases[index] = alias

        if (term.isNotBlank()) {
            prefix1Index.getOrPut(term.first()) { mutableListOf() }.add(index)
        }

        if (reading.isNotBlank()) {
            prefix1Index.getOrPut(reading.first()) { mutableListOf() }.add(index)
        }
        if (alias.isNotBlank()) {
            prefix1Index.getOrPut(alias.first()) { mutableListOf() }.add(index)
        }
    }

    return DictionarySearchCache(
        entries = entries,
        normalizedTerms = normalizedTerms,
        normalizedReadings = normalizedReadings,
        normalizedAliases = normalizedAliases,
        prefix1Index = prefix1Index.mapValues { it.value.toIntArray() }
    )
}

private fun stripHtmlTags(value: String): String {
    return value
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun dictionarySearchCacheKey(dictionary: LoadedDictionary): String {
    return "${dictionary.name}|${dictionary.format}|${dictionary.entries.size}"
}

private fun scoreEntryByNormalized(
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

private fun extractAliasForLookup(entry: DictionaryEntry): String {
    val definitionPlain = stripHtmlTags(entry.definitions.firstOrNull().orEmpty()).take(140)
    val tokens = Regex("[\\u4E00-\\u9FFF\\u3400-\\u4DBF\\uF900-\\uFAFF銆呫€嗐兌]+")
        .findAll(definitionPlain)
        .map { it.value.trim() }
        .filter { it.length in 1..12 }
        .distinct()
        .take(6)
        .toList()
    if (tokens.isEmpty()) return ""
    return normalizeLookup(tokens.joinToString(""))
}

internal fun entryStableKey(entry: DictionaryEntry): String {
    return buildString {
        append(entry.dictionary)
        append('|')
        append(entry.term)
        append('|')
        append(entry.reading.orEmpty())
        append('|')
        append(entry.definitions.joinToString("||"))
    }
}

internal fun extractLookupToken(text: String): String {
    return extractLookupCandidates(text).firstOrNull().orEmpty()
}

internal fun extractLookupCandidates(text: String): List<String> {
    val spans = buildLookupTokenSpans(text)
    if (spans.isEmpty()) {
        val fallback = text.trim()
        return if (fallback.isBlank()) emptyList() else listOf(fallback)
    }

    return spans
        .map { it.token }
        .distinct()
        .sortedWith(
            compareByDescending<String> { lookupTokenPriority(it) }
                .thenByDescending { it.length }
                .thenBy { it }
        )
}

internal fun tokenizeLookupTerms(text: String): List<String> {
    return buildLookupTokenSpans(text).map { it.token }.distinct()
}

private fun readTextFromUri(contentResolver: ContentResolver, uri: Uri): String {
    return contentResolver.openInputStream(uri)?.use { input ->
        input.bufferedReader(Charsets.UTF_8).readText()
    } ?: error("Unable to read dictionary file")
}

private fun parseDictionaryZip(
    contentResolver: ContentResolver,
    uri: Uri,
    fallbackName: String,
    onProgress: ((DictionaryImportProgress) -> Unit)?
): LoadedDictionary {
    onProgress?.invoke(DictionaryImportProgress(stage = "Scanning archive", current = 0, total = 0))
    val scan = scanDictionaryZip(contentResolver, uri, fallbackName, onProgress)

    if (scan.termBankPaths.isNotEmpty()) {
        val entries = parseTermBanksFromZip(
            contentResolver = contentResolver,
            uri = uri,
            dictionaryName = scan.dictionaryName,
            termMeta = scan.termMeta,
            targetTermBankPaths = scan.termBankPaths,
            onProgress = onProgress
        )
        if (entries.isEmpty()) error("No valid terms found in Yomichan dictionary")
        val stylesCss = scan.stylesCssPath?.let { path ->
            readZipEntryText(contentResolver, uri, path)
        }?.trim()?.ifBlank { null }
        return LoadedDictionary(
            name = scan.dictionaryName,
            format = "Yomichan/Migaku ZIP",
            entries = entries,
            stylesCss = stylesCss
        )
    }

    scan.firstDslPath?.let { path ->
        val text = readZipEntryText(contentResolver, uri, path) ?: error("Failed to read DSL in ZIP")
        return parseDslDictionary(text, path.substringAfterLast('/'))
    }

    scan.firstJsonPath?.let { path ->
        val text = readZipEntryText(contentResolver, uri, path) ?: error("Failed to read JSON in ZIP")
        return parseJsonDictionary(text, path.substringAfterLast('/'))
    }

    error("Unsupported dictionary archive format")
}

private fun scanDictionaryZip(
    contentResolver: ContentResolver,
    uri: Uri,
    fallbackName: String,
    onProgress: ((DictionaryImportProgress) -> Unit)?
): ZipScanResult {
    var dictionaryName = fallbackName.substringBeforeLast('.').ifBlank { "Dictionary" }
    val termBankPaths = mutableListOf<String>()
    var firstDslPath: String? = null
    var firstJsonPath: String? = null
    var stylesCssPath: String? = null
    var stylesCssPriority = Int.MIN_VALUE
    val termMeta = mutableMapOf<String, TermMeta>()
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
                    val path = normalizeZipPath(entry.name)
                    val fileName = path.substringAfterLast('/').lowercase(Locale.US)
                    when {
                        fileName == "index.json" -> {
                            val name = extractTitleFromIndexJson(zip.readBytes().decodeToString())
                            if (!name.isNullOrBlank()) dictionaryName = name
                        }

                        isTermMetaBankFile(path) -> Unit

                        isTermBankFile(path) -> {
                            termBankPaths += path
                        }

                        fileName.endsWith(".css") -> {
                            val priority = when (fileName) {
                                "styles.css" -> 3
                                "style.css" -> 2
                                else -> 1
                            }
                            if (priority >= stylesCssPriority) {
                                stylesCssPath = path
                                stylesCssPriority = priority
                            }
                        }

                        firstDslPath == null && path.lowercase(Locale.US).endsWith(".dsl") -> {
                            firstDslPath = path
                        }

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

    return ZipScanResult(
        dictionaryName = dictionaryName,
        termBankPaths = termBankPaths.sorted(),
        termMeta = termMeta,
        stylesCssPath = stylesCssPath,
        firstDslPath = firstDslPath,
        firstJsonPath = firstJsonPath
    )
}

private fun parseTermBanksFromZip(
    contentResolver: ContentResolver,
    uri: Uri,
    dictionaryName: String,
    termMeta: Map<String, TermMeta>,
    targetTermBankPaths: List<String>,
    onProgress: ((DictionaryImportProgress) -> Unit)?
): List<DictionaryEntry> {
    val entries = mutableListOf<DictionaryEntry>()
    val remaining = targetTermBankPaths.toHashSet()
    val total = remaining.size
    var parsedCount = 0
    var stopParsing = false

    contentResolver.openInputStream(uri)?.use { stream ->
        ZipInputStream(BufferedInputStream(stream)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && !stopParsing) {
                if (!entry.isDirectory) {
                    val path = normalizeZipPath(entry.name)
                    if (path in remaining) {
                        parseTermBankStream(zip, dictionaryName, termMeta, entries)
                        remaining.remove(path)
                        parsedCount += 1
                        onProgress?.invoke(
                            DictionaryImportProgress(
                                stage = "Parsing term banks",
                                current = parsedCount,
                                total = total
                            )
                        )
                        if (entries.size >= MAX_ENTRIES_PER_DICTIONARY) {
                            stopParsing = true
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    } ?: error("Unable to read dictionary archive")

    return entries
}

private fun readZipEntryText(contentResolver: ContentResolver, uri: Uri, targetPath: String): String? {
    val normalizedTarget = normalizeZipPath(targetPath)
    return contentResolver.openInputStream(uri)?.use { stream ->
        ZipInputStream(BufferedInputStream(stream)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && normalizeZipPath(entry.name) == normalizedTarget) {
                    return@use zip.readBytes().decodeToString()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            null
        }
    }
}

private fun extractTitleFromIndexJson(jsonText: String): String? {
    val obj = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
    return firstNonBlank(obj.optString("title"), obj.optString("name"))
}

private fun parseTermMetaBank(text: String, termMeta: MutableMap<String, TermMeta>) {
    val array = runCatching { JSONArray(text) }.getOrNull() ?: return
    for (i in 0 until array.length()) {
        val row = array.optJSONArray(i) ?: continue
        if (row.length() < 3) continue

        val term = row.optString(0).trim()
        if (term.isBlank()) continue

        val mode = row.optString(1).lowercase(Locale.US)
        val data = row.opt(2)
        val reading = row.optString(3).trim().ifBlank { extractReadingFromMetaData(data) }
        val key = metaKey(term, reading)

        val meta = termMeta.getOrPut(key) { TermMeta() }
        when {
            mode.contains("freq") -> extractMetaStrings(data).forEach(meta.frequencies::add)
            mode.contains("pitch") || mode.contains("accent") -> extractMetaStrings(data).forEach(meta.pitches::add)
        }
    }
}

private fun parseTermBankStream(
    zip: ZipInputStream,
    dictionaryName: String,
    termMeta: Map<String, TermMeta>,
    output: MutableList<DictionaryEntry>
) {
    val reader = JsonReader(InputStreamReader(zip, Charsets.UTF_8))
    reader.isLenient = true
    if (reader.peek() != JsonToken.BEGIN_ARRAY) {
        reader.skipValue()
        return
    }

    reader.beginArray()
    while (reader.hasNext() && output.size < MAX_ENTRIES_PER_DICTIONARY) {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            continue
        }

        reader.beginArray()
        var term = ""
        var reading: String? = null
        var column = 0
        var glossaryValue: Any? = null

        while (reader.hasNext()) {
            when (column) {
                0 -> term = readJsonScalarAsText(reader).trim()
                1 -> reading = readJsonScalarAsText(reader).trim().ifBlank { null }
                5 -> glossaryValue = readJsonValue(reader)
                else -> reader.skipValue()
            }
            column += 1
        }
        reader.endArray()

        if (term.isBlank()) continue
        val definitions = extractGlossaryFromRawValue(glossaryValue)
        if (definitions.isEmpty()) continue

        val keyWithReading = metaKey(term, reading)
        val keyWithoutReading = metaKey(term, null)
        val meta = termMeta[keyWithReading] ?: termMeta[keyWithoutReading]

        output += DictionaryEntry(
            term = term,
            reading = reading,
            definitions = definitions,
            pitch = meta?.pitches?.takeIf { it.isNotEmpty() }?.joinToString(" / "),
            frequency = meta?.frequencies?.takeIf { it.isNotEmpty() }?.joinToString(" / "),
            dictionary = dictionaryName
        )
    }
    while (reader.hasNext()) {
        reader.skipValue()
    }
    reader.endArray()
}

private fun readJsonScalarAsText(reader: JsonReader): String {
    return when (reader.peek()) {
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        JsonToken.NULL -> {
            reader.nextNull()
            ""
        }

        else -> {
            val value = readJsonValue(reader)
            value?.toString().orEmpty()
        }
    }
}

private fun readJsonValue(reader: JsonReader): Any? {
    return when (reader.peek()) {
        JsonToken.BEGIN_ARRAY -> {
            val list = mutableListOf<Any?>()
            reader.beginArray()
            while (reader.hasNext()) {
                list += readJsonValue(reader)
            }
            reader.endArray()
            list
        }

        JsonToken.BEGIN_OBJECT -> {
            val map = linkedMapOf<String, Any?>()
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                map[key] = readJsonValue(reader)
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

private fun extractGlossaryFromRawValue(value: Any?): List<String> {
    val definitions = mutableListOf<String>()
    fun collect(raw: Any?) {
        if (definitions.size >= MAX_DEFS_PER_ENTRY) return
        val text = extractTextSnippet(raw)
        if (!text.isNullOrBlank()) definitions += text
    }

    when (value) {
        is List<*> -> {
            value.forEach { item ->
                collect(item)
                if (definitions.size >= MAX_DEFS_PER_ENTRY) return@forEach
            }
        }

        else -> collect(value)
    }

    return compactDefinitions(definitions)
}

private fun parseJsonDictionary(jsonText: String, displayName: String): LoadedDictionary {
    val fallbackName = displayName.substringBeforeLast('.').ifBlank { "Dictionary" }
    val trimmed = jsonText.trim()
    if (trimmed.isBlank()) error("Dictionary JSON is empty")

    if (trimmed.startsWith("[")) {
        val root = JSONArray(trimmed)
        val entries = parseEntriesFromJsonArray(root, fallbackName)
        if (entries.isEmpty()) error("No dictionary entries in JSON")
        return LoadedDictionary(name = fallbackName, format = "Migaku/JSON", entries = entries)
    }

    if (trimmed.startsWith("{")) {
        val obj = JSONObject(trimmed)
        val dictionaryName = firstNonBlank(
            obj.optString("title"),
            obj.optString("name"),
            fallbackName
        ) ?: fallbackName

        val array = when {
            obj.opt("entries") is JSONArray -> obj.getJSONArray("entries")
            obj.opt("terms") is JSONArray -> obj.getJSONArray("terms")
            obj.opt("items") is JSONArray -> obj.getJSONArray("items")
            else -> null
        } ?: error("Unsupported dictionary JSON object")

        val entries = parseEntriesFromJsonArray(array, dictionaryName)
        if (entries.isEmpty()) error("No dictionary entries in JSON object")
        return LoadedDictionary(name = dictionaryName, format = "Migaku/JSON", entries = entries)
    }

    error("Unsupported dictionary JSON")
}

private fun parseEntriesFromJsonArray(array: JSONArray, dictionaryName: String): List<DictionaryEntry> {
    val entries = mutableListOf<DictionaryEntry>()
    for (i in 0 until array.length()) {
        if (entries.size >= MAX_ENTRIES_PER_DICTIONARY) break
        when (val node = array.opt(i)) {
            is JSONArray -> {
                val term = node.optString(0).trim()
                if (term.isBlank()) continue
                val reading = node.optString(1).trim().ifBlank { null }
                val definitions = compactDefinitions(extractGlossaryFromTermRow(node))
                if (definitions.isEmpty()) continue
                entries += DictionaryEntry(
                    term = term,
                    reading = reading,
                    definitions = definitions,
                    pitch = null,
                    frequency = null,
                    dictionary = dictionaryName
                )
            }

            is JSONObject -> {
                parseJsonObjectEntry(node, dictionaryName)?.let {
                    if (entries.size < MAX_ENTRIES_PER_DICTIONARY) entries += it
                }
            }
        }
    }
    return entries
}

private fun parseJsonObjectEntry(obj: JSONObject, dictionaryName: String): DictionaryEntry? {
    val term = firstNonBlank(
        obj.optString("term"),
        obj.optString("word"),
        obj.optString("expression"),
        obj.optString("headword")
    )?.trim() ?: return null
    if (term.isBlank()) return null

    val reading = firstNonBlank(
        obj.optString("reading"),
        obj.optString("kana"),
        obj.optString("pronunciation")
    )?.trim()?.ifBlank { null }

    val definitions = mutableListOf<String>()
    listOf("definitions", "definition", "glossary", "meanings", "translation", "translations").forEach { key ->
        definitions += jsonValueToStrings(obj.opt(key))
    }
    val cleanDefinitions = compactDefinitions(definitions)
    if (cleanDefinitions.isEmpty()) return null

    val pitch = firstNonBlank(
        jsonValueToFlatText(obj.opt("pitch")),
        jsonValueToFlatText(obj.opt("pitchAccent")),
        jsonValueToFlatText(obj.opt("accent"))
    )
    val frequency = firstNonBlank(
        jsonValueToFlatText(obj.opt("frequency")),
        jsonValueToFlatText(obj.opt("freq"))
    )

    return DictionaryEntry(
        term = term,
        reading = reading,
        definitions = cleanDefinitions,
        pitch = pitch,
        frequency = frequency,
        dictionary = dictionaryName
    )
}

private fun parseDslDictionary(dslText: String, displayName: String): LoadedDictionary {
    var dictionaryName = displayName.substringBeforeLast('.').ifBlank { "DSL Dictionary" }
    val entries = mutableListOf<DictionaryEntry>()

    var currentTerms = mutableListOf<String>()
    var currentDefinitions = mutableListOf<String>()

    fun flushEntry() {
        if (currentTerms.isEmpty() || currentDefinitions.isEmpty()) {
            currentTerms.clear()
            currentDefinitions.clear()
            return
        }

        val definitions = compactDefinitions(currentDefinitions.map(::stripDslMarkup))
        if (definitions.isEmpty()) {
            currentTerms.clear()
            currentDefinitions.clear()
            return
        }

        currentTerms
            .map(::stripDslMarkup)
            .map(::normalizeTextLine)
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { term ->
                if (entries.size >= MAX_ENTRIES_PER_DICTIONARY) return@forEach
                entries += DictionaryEntry(
                    term = term,
                    reading = null,
                    definitions = definitions,
                    pitch = null,
                    frequency = null,
                    dictionary = dictionaryName
                )
            }

        currentTerms.clear()
        currentDefinitions.clear()
    }

    dslText
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .forEach { line ->
            when {
                line.startsWith("#NAME", ignoreCase = true) -> {
                    val name = line.substringAfter("#NAME", "").trim().trim('"')
                    if (name.isNotBlank()) dictionaryName = name
                }

                line.startsWith("#") -> Unit
                line.isBlank() -> flushEntry()
                line.first().isWhitespace() -> {
                    if (currentTerms.isNotEmpty()) currentDefinitions += line.trim()
                }

                else -> {
                    flushEntry()
                    currentTerms = splitDslHeadwords(line).toMutableList()
                }
            }
        }
    flushEntry()

    if (entries.isEmpty()) error("No dictionary entries found in DSL")
    return LoadedDictionary(
        name = dictionaryName,
        format = "DSL",
        entries = entries
    )
}

private fun extractGlossaryFromTermRow(row: JSONArray): List<String> {
    val definitions = mutableListOf<String>()

    fun collect(value: Any?) {
        if (definitions.size >= MAX_DEFS_PER_ENTRY) return
        val text = extractTextSnippet(value)
        if (!text.isNullOrBlank()) definitions += text
    }

    val preferred = row.opt(5)
    when (preferred) {
        is JSONArray -> {
            for (i in 0 until preferred.length()) {
                collect(preferred.opt(i))
                if (definitions.size >= MAX_DEFS_PER_ENTRY) break
            }
        }

        else -> collect(preferred)
    }

    if (definitions.isEmpty()) {
        for (index in 2 until row.length()) {
            collect(row.opt(index))
            if (definitions.size >= MAX_DEFS_PER_ENTRY) break
        }
    }

    return compactDefinitions(definitions)
}

private fun extractTextSnippet(value: Any?): String? {
    if (value == null || value == JSONObject.NULL) return null
    val raw = when (value) {
        is String -> value
        is Number, is Boolean -> value.toString()
        is List<*> -> {
            buildString {
                value.forEach { item ->
                    val child = extractTextSnippet(item) ?: return@forEach
                    append(child)
                }
            }
        }

        is Map<*, *> -> structuredJsonToHtml(JSONObject(value))
        is JSONArray -> {
            buildString {
                for (i in 0 until value.length()) {
                    val child = extractTextSnippet(value.opt(i)) ?: continue
                    append(child)
                }
            }
        }

        is JSONObject -> structuredJsonToHtml(value)
        else -> value.toString()
    }.trim()

    if (raw.isBlank()) return null
    return clampDefinitionLengthForStorage(normalizeDefinitionForDisplay(raw))
}

private fun appendFlattenedText(value: Any?, builder: StringBuilder, maxLen: Int) {
    if (value == null || value == JSONObject.NULL || builder.length >= maxLen) return
    when (value) {
        is String -> appendToken(builder, value, maxLen)
        is Number, is Boolean -> appendToken(builder, value.toString(), maxLen)
        is JSONArray -> {
            for (i in 0 until value.length()) {
                appendFlattenedText(value.opt(i), builder, maxLen)
                if (builder.length >= maxLen) break
            }
        }

        is JSONObject -> {
            val preferredKeys = listOf("content", "text", "definition", "meaning", "value")
            var consumedPreferred = false
            preferredKeys.forEach { key ->
                if (value.has(key)) {
                    consumedPreferred = true
                    appendFlattenedText(value.opt(key), builder, maxLen)
                }
            }
            if (!consumedPreferred) {
                val iterator = value.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    appendFlattenedText(value.opt(key), builder, maxLen)
                    if (builder.length >= maxLen) break
                }
            }
        }
    }
}

private fun appendToken(builder: StringBuilder, token: String, maxLen: Int) {
    val cleaned = normalizeTextLine(token)
    if (cleaned.isBlank()) return
    if (builder.isNotEmpty()) builder.append(' ')
    builder.append(cleaned)
    if (builder.length > maxLen) {
        builder.setLength(maxLen)
    }
}

private fun compactDefinitions(rawDefinitions: List<String>): List<String> {
    return rawDefinitions
        .map(::normalizeDefinitionForDisplay)
        .filter { it.isNotBlank() && isLikelyDefinition(it) }
        .map(::clampDefinitionLengthForStorage)
        .distinct()
        .take(MAX_DEFS_PER_ENTRY)
}

private fun structuredJsonToHtml(obj: JSONObject): String {
    val type = obj.optString("type").trim().lowercase(Locale.ROOT)
    if (type == "image") {
        val path = firstNonBlank(
            obj.optString("path"),
            obj.optString("src"),
            obj.optString("url")
        ) ?: ""
        return if (path.isBlank()) "" else "<img src=\"${escapeHtmlAttribute(path)}\" />"
    }

    val tagRaw = obj.optString("tag").trim().lowercase(Locale.ROOT)
    val content = if (obj.has("content")) {
        extractTextSnippet(obj.opt("content")).orEmpty()
    } else {
        ""
    }

    if (tagRaw.isNotBlank()) {
        val tag = tagRaw.replace(Regex("[^a-z0-9-]"), "")
        if (tag.isBlank()) return content

        val dataAttributes = extractStructuredDataAttributes(obj.opt("data")).toMutableMap()
        val styleValue = styleValueToCss(obj.opt("style"))
        val styleAttr = styleValue.takeIf { it.isNotBlank() }?.let {
            " style=\"${escapeHtmlAttribute(it)}\""
        } ?: ""
        val langAttr = obj.optString("lang").trim().takeIf { it.isNotBlank() }?.let {
            " lang=\"${escapeHtmlAttribute(it)}\""
        } ?: ""
        val explicitClass = obj.optString("class").trim()
        if (dataAttributes["class"].isNullOrBlank() && explicitClass.isNotBlank()) {
            dataAttributes["class"] = explicitClass
        }
        val dataScAttrs = buildStructuredDataScAttributes(dataAttributes)
        val inlineAttrs = buildInlineHtmlAttributes(obj)
        return if (isVoidHtmlTag(tag)) {
            "<$tag$dataScAttrs$langAttr$styleAttr$inlineAttrs>"
        } else {
            "<$tag$dataScAttrs$langAttr$styleAttr$inlineAttrs>$content</$tag>"
        }
    }

    if (content.isNotBlank()) return content

    val textValue = firstNonBlank(
        obj.optString("text"),
        obj.optString("value")
    )
    if (!textValue.isNullOrBlank()) return textValue

    val fallbackParts = mutableListOf<String>()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val part = extractTextSnippet(obj.opt(key)).orEmpty()
        if (part.isNotBlank()) fallbackParts += part
    }
    return fallbackParts.joinToString("")
}

private fun extractStructuredDataAttributes(rawData: Any?): Map<String, String> {
    val attributes = linkedMapOf<String, String>()

    fun putAttribute(rawKey: String?, rawValue: Any?) {
        val key = normalizeStructuredDataKey(rawKey)
        if (key.isBlank()) return
        val value = when (rawValue) {
            null, JSONObject.NULL -> ""
            is String -> rawValue.trim()
            is Number, is Boolean -> rawValue.toString()
            else -> rawValue.toString().trim()
        }
        attributes[key] = value
    }

    when (rawData) {
        null, JSONObject.NULL -> Unit
        is JSONObject -> {
            val keys = rawData.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                putAttribute(key, rawData.opt(key))
            }
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

private fun normalizeStructuredDataKey(rawKey: String?): String {
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

private fun buildStructuredDataScAttributes(data: Map<String, String>): String {
    if (data.isEmpty()) return ""
    val classAttr = data["class"]?.trim().takeIf { !it.isNullOrBlank() }
    val dataAttrs = data.entries.joinToString(separator = "") { (key, value) ->
        val escapedValue = escapeHtmlAttribute(value)
        buildString {
            append(" data-sc-$key=\"$escapedValue\"")
            append(" data-sc$key=\"$escapedValue\"")
        }
    }
    return buildString {
        if (!classAttr.isNullOrBlank()) {
            append(" class=\"${escapeHtmlAttribute(classAttr)}\"")
        }
        append(dataAttrs)
    }
}

private fun isVoidHtmlTag(tag: String): Boolean {
    return tag in setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr")
}

private fun buildInlineHtmlAttributes(obj: JSONObject): String {
    val attrs = linkedMapOf<String, String>()
    val src = firstNonBlank(
        obj.optString("src"),
        obj.optString("path"),
        obj.optString("url")
    )?.trim().orEmpty()
    if (src.isNotBlank()) attrs["src"] = src
    val allowed = listOf("href", "alt", "title", "target", "rel", "width", "height", "colspan", "rowspan")
    allowed.forEach { key ->
        val value = obj.optString(key).trim()
        if (value.isNotBlank()) attrs[key] = value
    }
    return attrs.entries.joinToString(separator = "") { (key, value) ->
        " $key=\"${escapeHtmlAttribute(value)}\""
    }
}

private fun styleValueToCss(value: Any?): String {
    return when (value) {
        null, JSONObject.NULL -> ""
        is String -> value.trim()
        is JSONObject -> {
            val parts = mutableListOf<String>()
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val raw = value.opt(key)
                val text = when (raw) {
                    null, JSONObject.NULL -> ""
                    is String -> raw.trim()
                    is Number, is Boolean -> raw.toString()
                    else -> raw.toString().trim()
                }
                if (text.isBlank()) continue
                parts += "${camelToKebab(key)}: $text"
            }
            parts.joinToString("; ")
        }

        else -> value.toString().trim()
    }
}

private fun camelToKebab(value: String): String {
    return value
        .replace(Regex("([a-z])([A-Z])"), "$1-$2")
        .lowercase(Locale.ROOT)
}

private fun normalizeDefinitionForDisplay(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""
    return if (looksLikeHtml(trimmed)) trimmed else plainDefinitionToHtml(trimmed).take(MAX_DEF_LENGTH)
}

private fun clampDefinitionLengthForStorage(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) return ""
    return if (looksLikeHtml(trimmed)) trimmed else trimmed.take(MAX_DEF_LENGTH)
}

private fun looksLikeHtml(text: String): Boolean {
    return Regex("<\\s*/?\\s*[a-zA-Z][^>]*>").containsMatchIn(text)
}

private fun plainDefinitionToHtml(raw: String): String {
    val normalized = raw
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
    if (normalized.isBlank()) return ""
    val linked = linkifyPlainTextWithMarkdown(normalized)
    return linked.replace("\n", "<br/>")
}

private fun linkifyPlainTextWithMarkdown(text: String): String {
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
            html = "<img src=\"${escapeHtmlAttribute(src)}\" alt=\"${escapeHtmlAttribute(alt)}\" />"
        )
    }
    MARKDOWN_LINK_REGEX.findAll(text).forEach { match ->
        val label = match.groupValues[1]
        val href = sanitizeUrlOrNull(match.groupValues[2]) ?: return@forEach
        tokens += Token(
            start = match.range.first,
            end = match.range.last + 1,
            html = "<a href=\"${escapeHtmlAttribute(href)}\">${escapeHtmlTextEngine(label)}</a>"
        )
    }

    val occupied = tokens.sortedBy { it.start }
    PLAIN_URL_REGEX.findAll(text).forEach { match ->
        val start = match.range.first
        val end = match.range.last + 1
        if (occupied.any { start < it.end && end > it.start }) return@forEach
        val href = sanitizeUrlOrNull(match.value) ?: return@forEach
        val safeHref = escapeHtmlAttribute(href)
        val safeLabel = escapeHtmlTextEngine(href)
        tokens += Token(start = start, end = end, html = "<a href=\"$safeHref\">$safeLabel</a>")
    }

    tokens
        .sortedBy { it.start }
        .forEach { token ->
            if (token.start < cursor) return@forEach
            if (token.start > cursor) {
                out.append(escapeHtmlTextEngine(text.substring(cursor, token.start)))
            }
            out.append(token.html)
            cursor = token.end
        }

    if (cursor < text.length) {
        out.append(escapeHtmlTextEngine(text.substring(cursor)))
    }
    return out.toString()
}

private fun escapeHtmlAttribute(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private fun escapeHtmlTextEngine(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private fun normalizeLookup(value: String): String {
    return value
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\p{Punct}\\p{S}]"), " ")
        .replace(Regex("\\s+"), "")
}

private fun buildLookupTokenSpans(text: String): List<LookupTokenSpan> {
    if (text.isBlank()) return emptyList()

    val spans = mutableListOf<LookupTokenSpan>()
    var index = 0

    while (index < text.length) {
        val firstChar = text[index]
        val type = classifyLookupChar(firstChar)
        if (type == null) {
            index += 1
            continue
        }

        var end = index + 1
        while (end < text.length) {
            val nextType = classifyLookupChar(text[end]) ?: break
            if (nextType != type) break
            end += 1
        }

        val token = text.substring(index, end).trim()
        if (token.isNotBlank()) {
            spans += LookupTokenSpan(
                token = token,
                start = index,
                endExclusive = end,
                type = type
            )
        }
        index = end
    }

    return spans
}

private fun classifyLookupChar(ch: Char): LookupTokenType? {
    if (ch.isWhitespace()) return null
    if (!ch.isLetterOrDigit() && ch != '々' && ch != '〆' && ch != 'ヶ' && ch != 'ー') return null

    return when {
        ch in '\u4E00'..'\u9FFF' ||
            ch in '\u3400'..'\u4DBF' ||
            ch in '\uF900'..'\uFAFF' ||
            ch == '々' ||
            ch == '〆' ||
            ch == 'ヶ' -> LookupTokenType.KANJI

        ch in '\u3040'..'\u309F' -> LookupTokenType.HIRAGANA

        ch in '\u30A0'..'\u30FF' ||
            ch in '\u31F0'..'\u31FF' ||
            ch in '\uFF66'..'\uFF9F' ||
            ch == 'ー' -> LookupTokenType.KATAKANA

        else -> LookupTokenType.LATIN_DIGIT
    }
}

private fun lookupTokenPriority(token: String): Int {
    var hasKanji = false
    var hasKana = false
    var hasLatin = false

    token.forEach { ch ->
        when (classifyLookupChar(ch)) {
            LookupTokenType.KANJI -> hasKanji = true
            LookupTokenType.HIRAGANA,
            LookupTokenType.KATAKANA -> hasKana = true

            LookupTokenType.LATIN_DIGIT -> hasLatin = true
            null -> Unit
        }
    }

    return when {
        hasKanji && hasKana -> 5
        hasKanji -> 4
        hasKana -> 3
        hasLatin -> 2
        else -> 1
    }
}

private fun normalizeZipPath(path: String): String {
    return path.replace('\\', '/').trimStart('/')
}

private fun isTermBankFile(path: String): Boolean {
    val name = path.substringAfterLast('/').lowercase(Locale.US)
    return Regex("term_bank_\\d+\\.json").matches(name)
}

private fun isTermMetaBankFile(path: String): Boolean {
    val name = path.substringAfterLast('/').lowercase(Locale.US)
    return Regex("term_meta_bank_\\d+\\.json").matches(name)
}

private fun extractReadingFromMetaData(data: Any?): String? {
    val obj = data as? JSONObject ?: return null
    return firstNonBlank(
        obj.optString("reading"),
        obj.optString("kana"),
        obj.optString("pronunciation")
    )?.trim()?.ifBlank { null }
}

private fun extractMetaStrings(data: Any?): List<String> {
    if (data == null || data == JSONObject.NULL) return emptyList()
    return when (data) {
        is JSONArray -> buildList {
            for (i in 0 until data.length()) addAll(extractMetaStrings(data.opt(i)))
        }

        is JSONObject -> {
            val prioritized = mutableListOf<String>()
            listOf("value", "displayValue", "frequency", "freq", "pitch", "accent", "position").forEach { key ->
                prioritized += jsonValueToFlatText(data.opt(key))
            }
            val cleaned = prioritized.map(::normalizeTextLine).filter { it.isNotBlank() }
            if (cleaned.isNotEmpty()) cleaned.distinct() else {
                buildList {
                    val iterator = data.keys()
                    while (iterator.hasNext()) {
                        val key = iterator.next()
                        val value = normalizeTextLine(jsonValueToFlatText(data.opt(key)))
                        if (value.isNotBlank()) add("$key:$value")
                    }
                }
            }
        }

        else -> listOf(normalizeTextLine(data.toString())).filter { it.isNotBlank() }
    }
}

private fun metaKey(term: String, reading: String?): String {
    return "${term.trim()}|${reading?.trim().orEmpty()}"
}

private fun jsonValueToStrings(value: Any?): List<String> {
    if (value == null || value == JSONObject.NULL) return emptyList()
    return when (value) {
        is JSONArray -> buildList {
            for (i in 0 until value.length()) addAll(jsonValueToStrings(value.opt(i)))
        }

        is JSONObject -> {
            val preferred = listOf("text", "content", "definition", "meaning", "value")
                .mapNotNull { key -> value.opt(key) }
                .flatMap(::jsonValueToStrings)
            if (preferred.isNotEmpty()) preferred else listOf(normalizeTextLine(value.toString()))
        }

        else -> listOf(normalizeTextLine(value.toString()))
    }
}

private fun jsonValueToFlatText(value: Any?): String {
    return jsonValueToStrings(value).joinToString(" / ").trim()
}

private fun splitDslHeadwords(line: String): List<String> {
    val cleaned = line.substringBefore('\t').trim()
    if (cleaned.isBlank()) return emptyList()
    return cleaned
        .split('|', ';', ',')
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

private fun stripDslMarkup(text: String): String {
    val noDslTags = text.replace(Regex("\\[[^\\]]*]"), " ")
    return Html.fromHtml(noDslTags, Html.FROM_HTML_MODE_LEGACY).toString()
}

private fun normalizeTextLine(raw: String): String {
    return raw
        .replace('\u0000', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun isLikelyDefinition(text: String): Boolean {
    val plain = text
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (plain.length < 2) return false
    if (plain.all { it.isDigit() }) return false
    return true
}

private fun firstNonBlank(vararg values: String?): String? {
    return values.firstOrNull { !it.isNullOrBlank() }?.trim()
}

