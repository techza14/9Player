package moe.tekuza.m9player

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

internal data class LookupComputationResult(
    val query: String,
    val hits: List<DictionarySearchResult>
)

internal fun buildLookupCandidates(rawCandidates: List<String>): List<String> {
    return rawCandidates
        .asSequence()
        .flatMap { raw ->
            val trimmed = raw.trim()
            if (trimmed.isBlank()) {
                emptySequence()
            } else {
                val orderedTokens = tokenizeLookupTerms(trimmed)
                val extracted = extractLookupCandidates(trimmed)
                (orderedTokens + extracted + listOf(trimmed)).asSequence()
            }
        }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .take(24)
        .toList()
}

internal fun loadAvailableDictionaries(
    context: Context,
    contentResolver: ContentResolver = context.contentResolver
): List<LoadedDictionary> {
    val persisted = loadPersistedImports(context)
    val refs = persisted.dictionaries.distinctBy { it.uri }
    return refs.mapNotNull { ref ->
        val restoredUri = runCatching { Uri.parse(ref.uri) }.getOrNull()
        val displayName = ref.name.ifBlank {
            restoredUri?.let { queryLookupSourceDisplayName(contentResolver, it) }.orEmpty()
        }
        val cacheKey = ref.cacheKey ?: buildDictionaryCacheKey(ref.uri, displayName)
        loadDictionaryFromSqlite(context, cacheKey)
    }
}

private fun queryLookupSourceDisplayName(contentResolver: ContentResolver, uri: Uri): String {
    return runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else {
                    null
                }
            }
    }.getOrNull()?.trim().orEmpty()
}

internal fun computeLookupResults(
    context: Context,
    dictionaries: List<LoadedDictionary>,
    candidates: List<String>
): List<DictionarySearchResult> {
    return computeLookupResultsWithWinningCandidate(
        context = context,
        dictionaries = dictionaries,
        candidates = candidates
    )?.hits.orEmpty()
}

internal fun computeTapLookupResultsWithWinningCandidate(
    context: Context,
    dictionaries: List<LoadedDictionary>,
    query: String,
    profile: DictionaryQueryProfile = DictionaryQueryProfile.FULL
): LookupComputationResult? {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isBlank()) return null
    return computeLookupResultsWithWinningCandidate(
        context = context,
        dictionaries = dictionaries,
        candidates = listOf(normalizedQuery),
        profile = profile,
        expandCandidates = false
    )
}

internal fun computeLookupResultsWithWinningCandidate(
    context: Context,
    dictionaries: List<LoadedDictionary>,
    candidates: List<String>,
    profile: DictionaryQueryProfile = DictionaryQueryProfile.FULL,
    expandCandidates: Boolean = true
): LookupComputationResult? {
    if (dictionaries.isEmpty() || candidates.isEmpty()) return null
    val normalizedCandidates = if (expandCandidates) {
        buildLookupCandidates(candidates)
    } else {
        candidates
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(24)
            .toList()
    }
    if (normalizedCandidates.isEmpty()) return null

    val expectedDictionaryNames = dictionaries
        .map { it.name.trim() }
        .filter { it.isNotBlank() }
        .toSet()

    var primaryQuery: String? = null
    val mergedByKey = linkedMapOf<String, DictionarySearchResult>()
    val coveredDictionaryNames = linkedSetOf<String>()

    for (query in normalizedCandidates) {
        val hits = searchDictionarySql(
            context = context,
            dictionaries = dictionaries,
            query = query,
            maxResults = MAX_LOOKUP_RESULTS,
            profile = profile
        )
        if (hits.isEmpty()) continue

        if (primaryQuery == null) {
            primaryQuery = query
        }

        hits.forEach { hit ->
            val dictionaryName = hit.entry.dictionary.trim()
            if (dictionaryName.isNotBlank()) {
                coveredDictionaryNames += dictionaryName
            }
            val key = entryStableKey(hit.entry)
            val existing = mergedByKey[key]
            if (existing == null || hit.score > existing.score) {
                mergedByKey[key] = hit
            }
        }

        val allCovered = expectedDictionaryNames.isNotEmpty() &&
            coveredDictionaryNames.containsAll(expectedDictionaryNames)
        if (allCovered || mergedByKey.size >= MAX_LOOKUP_RESULTS * 2) {
            break
        }
    }

    val finalQuery = primaryQuery ?: return null
    val fallbackMatchedLength = finalQuery.length.coerceAtLeast(1)
    val sortedHits = mergedByKey.values
        .sortedWith(
            compareByDescending<DictionarySearchResult> { it.score }
                .thenBy { it.entry.term.length }
                .thenBy { it.entry.term }
        )
        .map { hit ->
            if (hit.matchedLength > 0) hit else hit.copy(matchedLength = fallbackMatchedLength)
        }

    val finalHits = buildList {
        if (MAX_LOOKUP_RESULTS <= 0) return@buildList

        val pickedKeys = linkedSetOf<String>()

        // Keep one top hit per dictionary first so mixed imports (e.g. JMDICT + MDICT)
        // can both appear instead of being crowded out by a single dictionary.
        for (hit in sortedHits) {
            if (size >= MAX_LOOKUP_RESULTS) break
            val dictionaryName = hit.entry.dictionary.trim().lowercase()
            if (dictionaryName.isBlank()) continue
            if (pickedKeys.add(dictionaryName)) {
                add(hit)
            }
        }

        // Fill remaining slots by global score ordering.
        for (hit in sortedHits) {
            if (size >= MAX_LOOKUP_RESULTS) break
            if (contains(hit)) continue
            add(hit)
        }
    }

    if (finalHits.isEmpty()) return null
    return LookupComputationResult(
        query = finalQuery,
        hits = finalHits
    )
}
