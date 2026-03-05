package com.example.tset

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.worksap.nlp.sudachi.Config
import com.worksap.nlp.sudachi.Dictionary
import com.worksap.nlp.sudachi.DictionaryFactory
import com.worksap.nlp.sudachi.Morpheme
import com.worksap.nlp.sudachi.Tokenizer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream

private const val MECAB_CACHE_DIR_NAME = "mecab_dict_cache"
private const val MECAB_UNKNOWN_BASE_COST = 9_000
private const val COPY_BUFFER_SIZE = 64 * 1024

private val LOOKUP_TOKEN_REGEX =
    Regex("[\\p{L}\\p{N}\\u3400-\\u9FFF\\u3040-\\u30FF\\u3005\\u3006\\u30F6\\u30FC]")

private val SUDACHI_DIC_NAME_REGEX = Regex("system_.*\\.dic", RegexOption.IGNORE_CASE)

internal data class MecabInstallResult(
    val name: String,
    val cacheKey: String,
    val tokenizer: MecabTokenizer
)

internal data class MecabTokenSpan(
    val surface: String,
    val startChar: Int,
    val endChar: Int,
    val feature: String,
    val lemma: String?
)

internal class MecabTokenizer private constructor(
    val dictionaryName: String,
    private val dictionaryPath: String,
    private val dictionary: Dictionary,
    private val tokenizer: Tokenizer,
    private val splitMode: Tokenizer.SplitMode
) {
    private val lock = Any()

    internal fun tokenize(text: String): List<MecabTokenSpan> {
        if (text.isBlank()) return emptyList()

        val morphemes = synchronized(lock) {
            runCatching { tokenizer.tokenize(splitMode, text) }.getOrElse {
                return fallbackSpans(text)
            }
        }

        val spans = morphemes
            .map { morpheme -> morphemeToSpan(morpheme) }
            .filter { it.surface.isNotBlank() && it.endChar > it.startChar }

        return if (spans.isNotEmpty()) spans else fallbackSpans(text)
    }

    internal fun close() {
        runCatching { dictionary.close() }
    }

    private fun morphemeToSpan(morpheme: Morpheme): MecabTokenSpan {
        val surface = morpheme.surface().orEmpty()
        val start = morpheme.begin().coerceAtLeast(0)
        val end = morpheme.end().coerceAtLeast(start)
        val pos = runCatching { morpheme.partOfSpeech().joinToString(",") }.getOrDefault("")
        val reading = runCatching { morpheme.readingForm() }.getOrNull().orEmpty()
        val dictionaryForm = runCatching { morpheme.dictionaryForm() }.getOrNull().orEmpty()
        val normalizedForm = runCatching { morpheme.normalizedForm() }.getOrNull().orEmpty()
        val lemma = pickLemma(
            surface = surface,
            dictionaryForm = dictionaryForm,
            normalizedForm = normalizedForm
        )
        val feature = buildString {
            append(pos)
            if (reading.isNotBlank()) append("|$reading")
            if (dictionaryForm.isNotBlank()) append("|$dictionaryForm")
            if (normalizedForm.isNotBlank()) append("|$normalizedForm")
        }
        return MecabTokenSpan(
            surface = surface,
            startChar = start,
            endChar = end,
            feature = feature,
            lemma = lemma
        )
    }

    private fun pickLemma(surface: String, dictionaryForm: String, normalizedForm: String): String? {
        val candidates = listOf(normalizedForm, dictionaryForm)
            .map { it.trim() }
            .filter { isLemmaCandidate(it, surface) }
        return candidates.firstOrNull()
    }

    private fun fallbackSpans(text: String): List<MecabTokenSpan> {
        val tokens = tokenizeLookupTerms(text)
        if (tokens.isEmpty()) {
            return listOf(
                MecabTokenSpan(
                    surface = text,
                    startChar = 0,
                    endChar = text.length,
                    feature = "",
                    lemma = null
                )
            )
        }

        val spans = mutableListOf<MecabTokenSpan>()
        var from = 0
        tokens.forEach { token ->
            val start = text.indexOf(token, from).takeIf { it >= 0 } ?: text.indexOf(token).takeIf { it >= 0 } ?: return@forEach
            val end = (start + token.length).coerceAtMost(text.length)
            spans += MecabTokenSpan(
                surface = text.substring(start, end),
                startChar = start,
                endChar = end,
                feature = "",
                lemma = null
            )
            from = end
        }
        return spans
    }

    companion object {
        internal fun fromDictionaryFile(
            dictionaryFile: File,
            dictionaryName: String,
            splitMode: Tokenizer.SplitMode = Tokenizer.SplitMode.C
        ): MecabTokenizer {
            check(dictionaryFile.isFile) { "Sudachi dictionary not found: ${dictionaryFile.absolutePath}" }
            val defaults = Config.defaultConfig()
            val config = Config.empty()
                .systemDictionary(dictionaryFile.toPath())
                .withFallback(defaults)
            val dictionary = DictionaryFactory().create(config)
            val tokenizer = dictionary.create()
            return MecabTokenizer(
                dictionaryName = dictionaryName,
                dictionaryPath = dictionaryFile.absolutePath,
                dictionary = dictionary,
                tokenizer = tokenizer,
                splitMode = splitMode
            )
        }
    }
}

internal fun isMecabDictionaryArchive(contentResolver: ContentResolver, uri: Uri): Boolean {
    return runCatching {
        val directName = queryUriTailName(uri)
        if (directName.endsWith(".dic", ignoreCase = true) && directName.startsWith("system_", ignoreCase = true)) {
            return@runCatching true
        }

        contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(BufferedInputStream(stream)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val fileName = normalizeZipEntryName(entry.name).substringAfterLast('/')
                        if (SUDACHI_DIC_NAME_REGEX.matches(fileName)) {
                            return@runCatching true
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        false
    }.getOrDefault(false)
}

internal fun installMecabDictionaryZip(
    context: Context,
    contentResolver: ContentResolver,
    uri: Uri,
    displayName: String,
    cacheKey: String,
    onProgress: ((DictionaryImportProgress) -> Unit)? = null
): MecabInstallResult {
    val dictionaryName = displayName.substringBeforeLast('.').ifBlank { "Sudachi Dictionary" }
    onProgress?.invoke(DictionaryImportProgress(stage = "Extracting Sudachi dictionary", current = 0, total = 0))

    val cacheRoot = File(context.filesDir, MECAB_CACHE_DIR_NAME).apply { mkdirs() }
    val tempDir = File(cacheRoot, "$cacheKey.tmp").apply {
        if (exists()) deleteRecursively()
        mkdirs()
    }
    val targetDir = File(cacheRoot, cacheKey)

    val selectedName = extractSudachiDictionaryFile(contentResolver, uri, tempDir)
    val extractedDic = File(tempDir, selectedName)
    check(extractedDic.isFile) { "No valid Sudachi system dictionary (.dic) found" }

    onProgress?.invoke(DictionaryImportProgress(stage = "Loading Sudachi dictionary", current = 0, total = 0))
    runCatching { MecabTokenizer.fromDictionaryFile(extractedDic, dictionaryName) }
        .onFailure { cause ->
            tempDir.deleteRecursively()
            error(
                "Invalid Sudachi dictionary file: $selectedName. " +
                    "Please import Sudachi 'system_*.dic' (e.g. system_core.dic)." +
                    " (${cause.message ?: "unknown error"})"
            )
        }
        .onSuccess { it.close() }

    if (targetDir.exists()) {
        targetDir.deleteRecursively()
    }
    if (!tempDir.renameTo(targetDir)) {
        tempDir.copyRecursively(targetDir, overwrite = true)
        tempDir.deleteRecursively()
    }

    val tokenizer = MecabTokenizer.fromDictionaryFile(File(targetDir, selectedName), dictionaryName)
    onProgress?.invoke(DictionaryImportProgress(stage = "Done", current = 1, total = 1))
    return MecabInstallResult(
        name = dictionaryName,
        cacheKey = cacheKey,
        tokenizer = tokenizer
    )
}

internal fun loadInstalledMecabTokenizer(
    context: Context,
    dictionaryName: String,
    cacheKey: String
): MecabTokenizer? {
    if (cacheKey.isBlank()) return null
    val directory = File(File(context.filesDir, MECAB_CACHE_DIR_NAME), cacheKey)
    if (!directory.isDirectory) return null
    val dicFile = directory
        .listFiles()
        ?.firstOrNull { it.isFile && SUDACHI_DIC_NAME_REGEX.matches(it.name) }
        ?: return null
    return runCatching {
        MecabTokenizer.fromDictionaryFile(
            dictionaryFile = dicFile,
            dictionaryName = dictionaryName
        )
    }.getOrNull()
}

internal fun deleteInstalledMecabTokenizer(context: Context, cacheKey: String): Boolean {
    if (cacheKey.isBlank()) return true
    val directory = File(File(context.filesDir, MECAB_CACHE_DIR_NAME), cacheKey)
    return !directory.exists() || directory.deleteRecursively()
}

internal fun extractLookupCandidatesWithMecab(
    text: String,
    tokenizer: MecabTokenizer?
): List<String> {
    val sudachi = tokenizer ?: return extractLookupCandidates(text)
    val spans = sudachi.tokenize(text)
    if (spans.isEmpty()) return extractLookupCandidates(text)

    val candidates = linkedSetOf<String>()
    spans.forEach { span ->
        addLookupCandidate(candidates, span.surface)
        addLookupCandidate(candidates, span.lemma.orEmpty())
    }

    if (candidates.isEmpty()) return extractLookupCandidates(text)
    return sortLookupCandidates(candidates.toList())
}

internal fun extractLookupCandidatesAtWithMecab(
    text: String,
    charOffset: Int,
    tokenizer: MecabTokenizer?
): List<String> {
    val sudachi = tokenizer ?: return extractLookupCandidatesAt(text, charOffset)
    val spans = sudachi.tokenize(text)
        .filter { isLookupCandidate(it.surface) }
    if (spans.isEmpty()) return extractLookupCandidatesAt(text, charOffset)

    val maxIndex = (text.length - 1).coerceAtLeast(0)
    val offset = charOffset.coerceIn(0, maxIndex)

    var centerIndex = spans.indexOfFirst { offset in it.startChar until it.endChar }
    if (centerIndex < 0) {
        centerIndex = spans.indices.minByOrNull { index ->
            val span = spans[index]
            when {
                offset < span.startChar -> span.startChar - offset
                offset >= span.endChar -> offset - span.endChar + 1
                else -> 0
            }
        } ?: return extractLookupCandidatesAt(text, charOffset)
    }

    val center = spans[centerIndex]
    val prev = spans.getOrNull(centerIndex - 1)
    val next = spans.getOrNull(centerIndex + 1)
    val candidates = linkedSetOf<String>()

    addLookupCandidate(candidates, center.surface)
    addLookupCandidate(candidates, center.lemma.orEmpty())

    if (prev != null) addLookupCandidate(candidates, prev.surface + center.surface)
    if (next != null) addLookupCandidate(candidates, center.surface + next.surface)
    if (prev != null && next != null) addLookupCandidate(candidates, prev.surface + center.surface + next.surface)

    extractLookupCandidates(center.surface).forEach { addLookupCandidate(candidates, it) }

    if (candidates.isEmpty()) return extractLookupCandidatesAt(text, charOffset)
    return sortLookupCandidates(candidates.toList())
}

internal fun tokenizeLookupTermsWithMecab(
    text: String,
    tokenizer: MecabTokenizer?
): List<String> {
    val sudachi = tokenizer ?: return tokenizeLookupTerms(text)
    val terms = sudachi.tokenize(text)
        .mapNotNull { sanitizeLookupCandidate(it.surface) }
        .distinct()
    return if (terms.isNotEmpty()) terms else tokenizeLookupTerms(text)
}

private fun extractSudachiDictionaryFile(
    contentResolver: ContentResolver,
    uri: Uri,
    tempDir: File
): String {
    val directName = queryUriTailName(uri)
    if (directName.endsWith(".dic", ignoreCase = true)) {
        val normalizedName = sanitizeDicName(directName)
        val output = File(tempDir, normalizedName)
        contentResolver.openInputStream(uri)?.use { input ->
            BufferedOutputStream(output.outputStream()).use { out ->
                input.copyTo(out, COPY_BUFFER_SIZE)
            }
        } ?: error("Unable to read Sudachi dictionary file")
        return normalizedName
    }

    val candidates = mutableListOf<String>()
    val extractedNames = mutableSetOf<String>()
    contentResolver.openInputStream(uri)?.use { stream ->
        ZipInputStream(BufferedInputStream(stream)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val fileName = normalizeZipEntryName(entry.name).substringAfterLast('/')
                    if (fileName.endsWith(".dic", ignoreCase = true)) {
                        val normalizedName = sanitizeDicName(fileName)
                        val output = File(tempDir, normalizedName)
                        BufferedOutputStream(output.outputStream()).use { out ->
                            zip.copyTo(out, COPY_BUFFER_SIZE)
                        }
                        if (normalizedName !in extractedNames) {
                            extractedNames += normalizedName
                            candidates += normalizedName
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    } ?: error("Unable to read Sudachi dictionary archive")

    val selected = candidates.firstOrNull { SUDACHI_DIC_NAME_REGEX.matches(it) }
        ?: candidates.firstOrNull()
        ?: error("No .dic file found in archive")
    return selected
}

private fun sanitizeDicName(fileName: String): String {
    val clean = fileName.substringAfterLast('/').trim()
    val lower = clean.lowercase(Locale.US)
    if (lower.endsWith(".dic")) return clean
    return "$clean.dic"
}

private fun queryUriTailName(uri: Uri): String {
    return uri.lastPathSegment.orEmpty().substringAfterLast('/').substringAfterLast('\\').ifBlank { "dictionary.dic" }
}

private fun normalizeZipEntryName(name: String): String {
    return name.replace('\\', '/').trimStart('/')
}

private fun isLemmaCandidate(value: String, surface: String): Boolean {
    if (value.isBlank() || value == "*") return false
    if (!isLookupCandidate(value)) return false
    return value != surface || value.length > 1
}

private fun addLookupCandidate(candidates: MutableSet<String>, raw: String) {
    val normalized = sanitizeLookupCandidate(raw) ?: return
    candidates += normalized
}

private fun sanitizeLookupCandidate(raw: String): String? {
    val cleaned = raw.trim()
    if (cleaned.isBlank()) return null
    if (!isLookupCandidate(cleaned)) return null
    return cleaned
}

private fun isLookupCandidate(value: String): Boolean {
    if (value.isBlank()) return false
    return LOOKUP_TOKEN_REGEX.containsMatchIn(value)
}

private fun sortLookupCandidates(candidates: List<String>): List<String> {
    return candidates
        .distinct()
        .sortedWith(
            compareByDescending<String> { lookupPriority(it) }
                .thenByDescending { it.length }
                .thenBy { it }
        )
}

private fun lookupPriority(token: String): Int {
    var hasKanji = false
    var hasKana = false
    var hasLatinDigit = false

    token.forEach { ch ->
        when {
            ch in '\u4E00'..'\u9FFF' ||
                ch in '\u3400'..'\u4DBF' ||
                ch in '\uF900'..'\uFAFF' ||
                ch == '\u3005' ||
                ch == '\u3006' ||
                ch == '\u30F6' -> hasKanji = true

            ch in '\u3040'..'\u309F' ||
                ch in '\u30A0'..'\u30FF' ||
                ch in '\u31F0'..'\u31FF' ||
                ch in '\uFF66'..'\uFF9F' ||
                ch == '\u30FC' -> hasKana = true

            ch.isLetterOrDigit() -> hasLatinDigit = true
        }
    }

    return when {
        hasKanji && hasKana -> 5
        hasKanji -> 4
        hasKana -> 3
        hasLatinDigit -> 2
        else -> 1
    }
}
