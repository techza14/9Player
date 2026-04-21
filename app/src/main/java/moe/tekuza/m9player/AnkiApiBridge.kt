package moe.tekuza.m9player

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.util.Log
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.TransformationResult
import androidx.media3.transformer.Transformer
import com.ichi2.anki.api.AddContentApi
import com.zuidsoft.audioconverter.ConvertionCode
import com.zuidsoft.audioconverter.WavToM4AConverter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class AnkiModelTemplate(
    val id: Long,
    val name: String,
    val fields: List<String>
)

internal data class AnkiCatalog(
    val decks: List<String>,
    val models: List<AnkiModelTemplate>
)

internal data class AnkiExportConfig(
    val deckName: String,
    val modelName: String,
    val fieldTemplates: Map<String, String>,
    val tags: Set<String>
)

internal data class PreparedAnkiExport(
    val config: AnkiExportConfig,
    val requiresLookupAudio: Boolean
)

internal sealed interface AnkiExportResult {
    data object Added : AnkiExportResult
    data class NotAvailable(
        val state: AnkiAvailabilityState,
        val message: String
    ) : AnkiExportResult
    data class InvalidConfig(
        val message: String
    ) : AnkiExportResult
    data class Failed(
        val message: String,
        val cause: Throwable? = null
    ) : AnkiExportResult
}

private data class StagedAnkiAudio(
    val uri: Uri,
    val extension: String,
    val cleanup: () -> Unit
)

private val TEMPLATE_VARIABLE_REGEX = Regex("\\{([^{}]+)\\}")
private val SINGLE_GLOSSARY_DICT_MARKER_REGEX = Regex("\\{single-glossary-([^{}]+)\\}", RegexOption.IGNORE_CASE)
private val SINGLE_FREQUENCY_NUMBER_DICT_MARKER_REGEX =
    Regex("\\{single-frequency-number-([^{}]+)\\}", RegexOption.IGNORE_CASE)
private val SINGLE_FREQUENCY_DICT_MARKER_REGEX = Regex("\\{single-frequency-([^{}]+)\\}", RegexOption.IGNORE_CASE)
private val NON_ALNUM_TEMPLATE_KEY_REGEX = Regex("[^a-z0-9]")
private val DICTIONARY_TOKEN_STRIP_REGEX = Regex("[\\s\\p{Punct}\\p{S}]")
private val ANKI_LINK_TAG_REGEX = Regex("(?is)<link\\b[^>]*>")
private val ANKI_IMG_TAG_REGEX = Regex("(?is)<img\\b[^>]*>")
private val ANKI_STYLE_TAG_REGEX = Regex("(?is)<style\\b[^>]*>(.*?)</style>")
private val ANKI_ATTR_QUOTED_REGEX = Regex("(?i)\\b%s\\s*=\\s*(['\"])(.*?)\\1")
private val ANKI_ATTR_UNQUOTED_REGEX = Regex("(?i)\\b%s\\s*=\\s*([^\\s\"'<>`]+)")
private val ANKI_URI_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
private val ANKI_IMG_SRC_IN_TAG_REGEX = Regex("(?is)\\bsrc\\s*=\\s*(['\"])(.*?)\\1")
private const val ANKI_AUDIO_LOG_TAG = "AnkiAudio"
private const val ANKI_EXPORT_DEBUG_TAG = "AnkiExportDebug"

internal fun loadAnkiCatalog(context: Context): AnkiCatalog {
    ankiAvailabilityErrorMessage(context, requirePermission = true)?.let(::error)

    val api = AddContentApi(context)
    val deckNames = (api.getDeckList() ?: emptyMap())
        .values
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .sortedBy { it.lowercase(Locale.ROOT) }

    val modelMap = api.getModelList(1) ?: emptyMap()
    val models = modelMap.entries.mapNotNull { (id, nameRaw) ->
        val name = nameRaw.trim()
        if (name.isBlank()) return@mapNotNull null
        val fields = (api.getFieldList(id) ?: emptyArray())
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (fields.isEmpty()) return@mapNotNull null

        AnkiModelTemplate(
            id = id,
            name = name,
            fields = fields
        )
    }.sortedBy { it.name.lowercase(Locale.ROOT) }

    return AnkiCatalog(
        decks = deckNames,
        models = models
    )
}

internal fun defaultFieldTemplate(fieldName: String): String {
    val lowered = fieldName.lowercase(Locale.ROOT)
    return when {
        lowered.contains("sentence") && (lowered.contains("furigana") || lowered.contains("kana")) -> "{sentence-furigana}"
        lowered.contains("sentence") -> "{sentence}"
        lowered.contains("book") || lowered.contains("title") -> "{book-title}"
        lowered.contains("word") || lowered.contains("term") || lowered.contains("expression") -> "{expression}"
        lowered.contains("reading") || lowered.contains("kana") || lowered.contains("furigana") -> "{reading}"
        lowered.contains("meaning") || lowered.contains("definition") || lowered.contains("gloss") -> "{glossary}"
        lowered.contains("pitch") -> "{pitch-accent-positions}"
        lowered.contains("freq") || lowered.contains("frequency") -> "{frequencies}"
        lowered.contains("audio") || lowered.contains("sound") -> {
            if (lowered.contains("cut") || lowered.contains("clip")) "{cut-audio}" else "{audio}"
        }
        else -> ""
    }
}

internal fun defaultTemplatesForFields(fields: List<String>): Map<String, String> {
    return fields.associateWith(::defaultFieldTemplate)
}

internal fun hasAnyAnkiFieldTemplate(templates: Map<String, String>): Boolean {
    return templates.values.any { it.trim().isNotBlank() }
}

internal fun parseAnkiTags(raw: String): Set<String> {
    return raw
        .split(Regex("[,\\s]+"))
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toSet()
}

internal fun prepareAnkiExport(
    context: Context,
    persistedConfig: PersistedAnkiConfig,
    audioUri: Uri?,
    lookupAudioUri: Uri?
): PreparedAnkiExport {
    ankiAvailabilityErrorMessage(context, requirePermission = true)?.let(::error)
    if (persistedConfig.modelName.isBlank()) error(context.getString(R.string.error_anki_model_missing))

    val catalog = loadAnkiCatalog(context)
    val model = catalog.models.firstOrNull { it.name == persistedConfig.modelName }
        ?: error(context.getString(R.string.error_anki_model_not_found, persistedConfig.modelName))

    val templates = model.fields.associateWith { field ->
        persistedConfig.fieldTemplates[field].orEmpty()
    }
    if (!hasAnyAnkiFieldTemplate(templates)) {
        error(context.getString(R.string.error_anki_fields_empty))
    }
    val requiresLookupAudio = templates.values.any {
        templateUsesVariable(it, "audio")
    }

    return PreparedAnkiExport(
        config = AnkiExportConfig(
            deckName = persistedConfig.deckName.ifBlank { "Default" },
            modelName = model.name,
            fieldTemplates = templates,
            tags = parseAnkiTags(persistedConfig.tags)
        ),
        requiresLookupAudio = requiresLookupAudio
    )
}

internal fun exportToAnkiDroidApi(
    context: Context,
    card: MinedCard,
    config: AnkiExportConfig
) {
    Log.d(
        ANKI_EXPORT_DEBUG_TAG,
        "export start word=${card.word} primaryDict=${card.dictionaryName.orEmpty()} glossaryByDictCount=${card.glossaryByDictionary.size} model=${config.modelName} deck=${config.deckName}"
    )
    ankiAvailabilityErrorMessage(context, requirePermission = true)?.let(::error)

    val api = runCatching { AddContentApi(context) }.getOrElse { throwable ->
        error("Anki API init failed. ${throwableDetail(throwable)}")
    }
    val deckId = runCatching { findOrCreateDeckId(api, config.deckName) }.getOrElse { throwable ->
        error("Anki deck resolve failed for '${config.deckName}'. ${throwableDetail(throwable)}")
    }
    val model = runCatching { findModel(api, config.modelName) }.getOrElse { throwable ->
        error("Anki model resolve failed for '${config.modelName}'. ${throwableDetail(throwable)}")
    }
        ?: error("Anki model not found: ${config.modelName}")

    val templatesByField = model.fields.associateWith { fieldName ->
        config.fieldTemplates[fieldName].orEmpty().trim()
    }
    if (templatesByField.values.none { it.isNotBlank() }) {
        error("All field variables are empty. Configure at least one marker in Settings > Anki.")
    }
    val requiresCutAudio = templatesByField.values.any { templateUsesVariable(it, "cut-audio") }
    val requiresLookupAudio = templatesByField.values.any {
        templateUsesVariable(it, "audio")
    }

    val variables = runCatching {
        buildAnkiVariables(
            context = context,
            api = api,
            card = card,
            includeCutAudio = requiresCutAudio,
            includeLookupAudio = requiresLookupAudio
        )
    }.getOrElse { throwable ->
        error("Anki variable build failed. ${throwableDetail(throwable)}")
    }
    Log.d(
        ANKI_EXPORT_DEBUG_TAG,
        "export variables dict=${variables["dictionary"].orEmpty()} glossaryLen=${variables["glossary"]?.length ?: 0} singleGlossaryLen=${variables["single-glossary"]?.length ?: 0}"
    )

    val fieldValues = runCatching {
        model.fields.map { fieldName ->
            val template = templatesByField[fieldName].orEmpty()
            val value = resolveTemplate(template, variables).trim()
            value
        }.toTypedArray()
    }.getOrElse { throwable ->
        error("Anki field rendering failed for model '${model.name}'. ${throwableDetail(throwable)}")
    }
    if (fieldValues.all { it.isBlank() }) {
        error("All rendered field values are empty. Check your field variables in Settings > Anki.")
    }
    Log.d(
        ANKI_EXPORT_DEBUG_TAG,
        "export rendered fields=${model.fields.zip(fieldValues.asList()).joinToString(separator = "|") { (name, value) -> "$name:${value.length}" }}"
    )

    val tags = config.tags
    val noteId = runCatching {
        api.addNote(model.id, deckId, fieldValues, tags)
    }.getOrElse { throwable ->
        error(
            "Anki addNote failed. model=${model.name}, deck=${config.deckName}, " +
                "fields=${model.fields.size}, tags=${tags.joinToString(",")}. ${throwableDetail(throwable)}"
        )
    }
    if (noteId == null || noteId <= 0L) {
        val emptyFields = model.fields
            .zip(fieldValues.asList())
            .filter { (_, value) -> value.isBlank() }
            .map { (name, _) -> name }
        val detail = if (emptyFields.isEmpty()) {
            ""
        } else {
            " Empty fields: ${emptyFields.joinToString(", ")}."
        }
        error("AnkiDroid rejected the note. Check model fields and templates.$detail")
    }
}

internal fun exportToAnkiDroidApiResult(
    context: Context,
    card: MinedCard,
    config: AnkiExportConfig
): AnkiExportResult {
    return try {
        exportToAnkiDroidApi(context, card, config)
        AnkiExportResult.Added
    } catch (error: Throwable) {
        classifyAnkiExportFailure(context, error)
    }
}

internal fun prepareAnkiExportResult(
    context: Context,
    persistedConfig: PersistedAnkiConfig,
    audioUri: Uri?,
    lookupAudioUri: Uri?
): Result<PreparedAnkiExport> {
    return runCatching {
        prepareAnkiExport(
            context = context,
            persistedConfig = persistedConfig,
            audioUri = audioUri,
            lookupAudioUri = lookupAudioUri
        )
    }
}

private fun findOrCreateDeckId(api: AddContentApi, deckNameRaw: String): Long {
    val deckName = deckNameRaw.trim().ifBlank { "Default" }
    val deckList = runCatching { api.getDeckList() }.getOrElse { throwable ->
        error("Anki getDeckList failed. ${throwableDetail(throwable)}")
    }
    val existingDeckId = deckList
        ?.entries
        ?.firstOrNull { it.value.equals(deckName, ignoreCase = true) }
        ?.key
    if (existingDeckId != null) return existingDeckId

    return runCatching { api.addNewDeck(deckName) }.getOrElse { throwable ->
        error("Anki addNewDeck failed for '$deckName'. ${throwableDetail(throwable)}")
    }
}

private fun findModel(api: AddContentApi, modelName: String): AnkiModelTemplate? {
    val normalizedName = modelName.trim()
    if (normalizedName.isBlank()) return null

    val modelList = runCatching { api.getModelList(1) }.getOrElse { throwable ->
        error("Anki getModelList failed. ${throwableDetail(throwable)}")
    }
    val entry = modelList
        ?.entries
        ?.firstOrNull { it.value.equals(normalizedName, ignoreCase = true) }
        ?: return null

    val fields = (runCatching { api.getFieldList(entry.key) }.getOrElse { throwable ->
        error("Anki getFieldList failed for modelId=${entry.key}. ${throwableDetail(throwable)}")
    } ?: emptyArray())
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (fields.isEmpty()) return null

    return AnkiModelTemplate(
        id = entry.key,
        name = entry.value,
        fields = fields
    )
}

private fun throwableDetail(throwable: Throwable): String {
    val message = throwable.message?.trim().orEmpty()
    val frame = throwable.stackTrace.firstOrNull()?.let { top ->
        " @${top.className}.${top.methodName}(${top.fileName ?: "Unknown"}:${top.lineNumber})"
    }.orEmpty()
    return if (message.isBlank()) {
        "${throwable.javaClass.simpleName}$frame"
    } else {
        "${throwable.javaClass.simpleName}: $message$frame"
    }
}

private fun buildAnkiVariables(
    context: Context,
    api: AddContentApi,
    card: MinedCard,
    includeCutAudio: Boolean,
    includeLookupAudio: Boolean
): Map<String, String> {
    val glossarySources = buildMinedCardGlossarySources(card)
    Log.d(
        ANKI_EXPORT_DEBUG_TAG,
        "variables sources count=${glossarySources.size} names=${glossarySources.joinToString(separator = "|") { "${it.dictionaryName}:${it.definitions.size}" }}"
    )
    val primaryGlossarySource = selectPrimaryGlossarySource(card, glossarySources)
    val dictionaryName = primaryGlossarySource?.dictionaryName.orEmpty()
    val glossaryHtml = buildStyledGlossaryFromSources(glossarySources)
    val allDefinitions = glossarySources.flatMap { it.definitions }
    val glossaryFirst = allDefinitions.firstOrNull().orEmpty()
    val glossaryNoDictionary = allDefinitions.joinToString("<br>")
    val glossaryPlain = allDefinitions.joinToString("\n")
    val singleGlossaryHtml = primaryGlossarySource?.let { source ->
        buildStyledGlossary(
            definitions = source.definitions,
            dictionaryName = source.dictionaryName,
            dictionaryCss = source.dictionaryCss
        )
    }.orEmpty()
    val singleGlossaryFirst = primaryGlossarySource?.definitions?.firstOrNull().orEmpty()
    val singleGlossaryNoDictionary = primaryGlossarySource?.definitions?.joinToString("<br>").orEmpty()
    // Hoshi parity: glossary-first is the first dictionary's full glossary HTML.
    val styledGlossaryFirst = singleGlossaryHtml
    val cutAudio = if (includeCutAudio) {
        attachAudio(api, context, card).orEmpty()
    } else {
        ""
    }
    val lookupAudio = if (includeLookupAudio) {
        attachLookupAudio(api, context, card.lookupAudioUri).orEmpty()
    } else {
        ""
    }
    val popupSelectionText = card.popupSelectionText?.trim().orEmpty()
    val clozeTarget = popupSelectionText.ifBlank { card.word }
    val (clozePrefix, clozeBody, clozeSuffix) = splitCloze(card.sentence, clozeTarget)
    val frequencyNumber = extractFirstNumber(card.frequency)
    val expressionFurigana = buildExpressionFurigana(card.word, card.reading)
    val singleFrequency = card.frequency.orEmpty()
    val resolvedBookTitle = resolveBookTitle(context, card)

    val variables = mutableMapOf(
        "expression" to card.word,
        "dictionary-name" to dictionaryName,
        "dictionary" to dictionaryName,
        "dictionary-alias" to dictionaryName,
        "popup-selection-text" to popupSelectionText.ifBlank { card.word },
        "search-query" to card.word,
        "sentence" to card.sentence,
        "cloze-prefix" to clozePrefix,
        "cloze-body" to clozeBody,
        "cloze-body-kana" to (card.reading ?: clozeBody),
        "cloze-suffix" to clozeSuffix,
        "reading" to card.reading.orEmpty(),
        "furigana" to expressionFurigana,
        "furigana-plain" to expressionFurigana,
        "expression-furigana" to expressionFurigana,
        "definitions" to glossaryHtml,
        "definition" to glossaryHtml,
        "glossary" to glossaryHtml,
        "glossary-no-dictionary" to glossaryNoDictionary,
        "glossary-first" to styledGlossaryFirst,
        "glossary-first-brief" to glossaryFirst,
        "glossary-first-no-dictionary" to singleGlossaryNoDictionary,
        "single-glossary" to singleGlossaryHtml,
        "single-glossary-brief" to singleGlossaryFirst,
        "single-glossary-no-dictionary" to singleGlossaryNoDictionary,
        "glossary-brief" to glossaryFirst,
        "glossary-plain" to glossaryPlain,
        "glossary-plain-no-dictionary" to glossaryPlain,
        "dictionary-css" to card.dictionaryCss.orEmpty(),
        "pitch" to card.pitch.orEmpty(),
        "pitch-accents" to card.pitch.orEmpty(),
        "pitch-accent-positions" to card.pitch.orEmpty(),
        "pitch-accent-categories" to card.pitch.orEmpty(),
        "frequency" to card.frequency.orEmpty(),
        "frequencies" to card.frequency.orEmpty(),
        "single-frequency" to singleFrequency,
        "single-frequency-number" to frequencyNumber,
        "frequency-harmonic-rank" to frequencyNumber,
        "frequency-harmonic-occurrence" to frequencyNumber,
        "frequency-average-rank" to frequencyNumber,
        "frequency-average-occurrence" to frequencyNumber,
        "audio" to lookupAudio,
        "cut-audio" to cutAudio,
        "book-title" to resolvedBookTitle
    )
    glossarySources.forEach { source ->
        val normalizedName = normalizeDictionaryToken(source.dictionaryName)
        if (normalizedName.isBlank()) return@forEach
        variables[templateSingleGlossaryKey("single-glossary", normalizedName)] = buildStyledGlossary(
            definitions = source.definitions,
            dictionaryName = source.dictionaryName,
            dictionaryCss = source.dictionaryCss
        )
        variables[templateSingleGlossaryKey("single-glossary-brief", normalizedName)] =
            source.definitions.firstOrNull().orEmpty()
        variables[templateSingleGlossaryKey("single-glossary-no-dictionary", normalizedName)] =
            source.definitions.joinToString("<br>")
    }
    val mediaSrcCache = mutableMapOf<String, String>()
    variables.replaceAll { key, value ->
        rewriteHtmlForAnkiExport(
            context = context,
            api = api,
            html = value,
            sourceLabel = key,
            mediaSrcCache = mediaSrcCache
        )
    }
    Log.d(
        ANKI_EXPORT_DEBUG_TAG,
        "variables done primary=${primaryGlossarySource?.dictionaryName.orEmpty()} dynamicSingleKeys=${variables.keys.count { it.startsWith("__single-glossary::") }}"
    )
    return variables
}

private fun rewriteHtmlForAnkiExport(
    context: Context,
    api: AddContentApi,
    html: String,
    sourceLabel: String,
    mediaSrcCache: MutableMap<String, String>
): String {
    if (html.isBlank()) return html
    if (!html.contains("<img", ignoreCase = true) && !html.contains("<link", ignoreCase = true)) {
        return html
    }

    val cssChunks = mutableListOf<String>()
    val cssSet = linkedSetOf<String>()
    var output = ANKI_STYLE_TAG_REGEX.replace(html) { match ->
        val css = match.groupValues.getOrNull(1).orEmpty().trim()
        if (css.isNotBlank() && cssSet.add(css)) {
            cssChunks += css
        }
        ""
    }
    output = ANKI_LINK_TAG_REGEX.replace(output) { match ->
        val tag = match.value
        val rel = findHtmlAttributeValue(tag, "rel")?.lowercase(Locale.ROOT).orEmpty()
        if (!rel.contains("stylesheet")) return@replace tag
        val hrefRaw = findHtmlAttributeValue(tag, "href").orEmpty()
        val hrefUri = resolveAnkiHtmlResourceUri(hrefRaw)
        if (hrefUri == null) return@replace tag
        val cssText = runCatching {
            openInputStreamForUri(context, hrefUri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull()
        if (cssText.isNullOrBlank()) return@replace tag
        val normalizedCss = cssText.trim()
        if (normalizedCss.isNotBlank() && cssSet.add(normalizedCss)) {
            cssChunks += normalizedCss
        }
        ""
    }
    if (cssChunks.isNotEmpty()) {
        output = "<style>${cssChunks.joinToString("\n")}</style>$output"
    }

    var imageIndex = 0
    output = ANKI_IMG_TAG_REGEX.replace(output) { match ->
        var tag = match.value
        val quotedSrcRegex = Regex(ANKI_ATTR_QUOTED_REGEX.pattern.format("src"), setOf(RegexOption.IGNORE_CASE))
        val unquotedSrcRegex = Regex(ANKI_ATTR_UNQUOTED_REGEX.pattern.format("src"), setOf(RegexOption.IGNORE_CASE))
        var replaced = false
        tag = quotedSrcRegex.replace(tag) { attrMatch ->
            val quote = attrMatch.groupValues[1]
            val rawSrc = attrMatch.groupValues[2]
            val rewritten = rewriteAnkiImageSrc(
                context = context,
                api = api,
                rawSrc = rawSrc,
                sourceLabel = sourceLabel,
                imageIndex = imageIndex,
                mediaSrcCache = mediaSrcCache
            )
            replaced = true
            imageIndex += 1
            "src=$quote${escapeHtmlAttributeAnki(rewritten)}$quote"
        }
        if (!replaced) {
            tag = unquotedSrcRegex.replace(tag) { attrMatch ->
                val rawSrc = attrMatch.groupValues[1]
                val rewritten = rewriteAnkiImageSrc(
                    context = context,
                    api = api,
                    rawSrc = rawSrc,
                    sourceLabel = sourceLabel,
                    imageIndex = imageIndex,
                    mediaSrcCache = mediaSrcCache
                )
                imageIndex += 1
                "src=\"${escapeHtmlAttributeAnki(rewritten)}\""
            }
        }
        tag
    }
    return output
}

private fun rewriteAnkiImageSrc(
    context: Context,
    api: AddContentApi,
    rawSrc: String,
    sourceLabel: String,
    imageIndex: Int,
    mediaSrcCache: MutableMap<String, String>
): String {
    val src = rawSrc.trim().trim('"', '\'')
    if (src.isBlank()) return rawSrc
    if (src.startsWith("#")) return rawSrc
    if (src.startsWith("//")) return rawSrc
    if (src.startsWith("data:", ignoreCase = true)) return rawSrc
    if (src.startsWith("http://", ignoreCase = true) || src.startsWith("https://", ignoreCase = true)) return rawSrc

    mediaSrcCache[src]?.let { return it }

    val uri = resolveAnkiHtmlResourceUri(src) ?: return rawSrc
    val preferredName = buildPreferredImageMediaName(context, uri, sourceLabel, imageIndex)
    val resolvedSrc = addMediaAsImageSrc(
        api = api,
        context = context,
        sourceUri = uri,
        preferredName = preferredName
    ) ?: rawSrc
    mediaSrcCache[src] = resolvedSrc
    return resolvedSrc
}

private fun addMediaAsImageSrc(
    api: AddContentApi,
    context: Context,
    sourceUri: Uri,
    preferredName: String
): String? {
    val extension = preferredName.substringAfterLast('.', "png")
    val temp = createAnkiMediaTempFile(context, prefix = "anki-img", extension = extension)
    return try {
        openInputStreamForUri(context, sourceUri)?.use { input ->
            temp.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null
        if (temp.length() <= 0L) return null

        fun callAddMedia(uri: Uri, grantPermission: Boolean): String? {
            if (grantPermission && uri.scheme.equals("content", ignoreCase = true)) {
                runCatching {
                    context.grantUriPermission(
                        requireAnkiPackageName(context),
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            return try {
                val mediaTag = runCatching {
                    api.addMediaFromUri(uri, preferredName, "image")
                }.getOrNull().orEmpty()
                parseImageSrcFromAnkiTag(mediaTag).ifBlank { preferredName }
            } finally {
                if (grantPermission && uri.scheme.equals("content", ignoreCase = true)) {
                    runCatching { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
            }
        }

        val providerUri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", temp)
        }.getOrNull()
        val fromProvider = providerUri?.let { callAddMedia(it, grantPermission = true) }
        if (!fromProvider.isNullOrBlank()) return fromProvider

        val fromFile = callAddMedia(Uri.fromFile(temp), grantPermission = false)
        if (!fromFile.isNullOrBlank()) return fromFile
        null
    } catch (_: Exception) {
        null
    } finally {
        runCatching { temp.delete() }
    }
}

private fun parseImageSrcFromAnkiTag(tag: String): String {
    if (tag.isBlank()) return ""
    val matched = ANKI_IMG_SRC_IN_TAG_REGEX.find(tag)?.groupValues?.getOrNull(2).orEmpty().trim()
    return if (matched.isNotBlank()) matched else ""
}

private fun buildPreferredImageMediaName(
    context: Context,
    uri: Uri,
    sourceLabel: String,
    imageIndex: Int
): String {
    val ext = resolveImageExtension(context, uri, fallback = "png")
    val safeLabel = sourceLabel
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "glossary" }
    return "mdict-$safeLabel-${System.currentTimeMillis()}-$imageIndex.$ext"
}

private fun resolveImageExtension(
    context: Context,
    uri: Uri,
    fallback: String
): String {
    val fromPath = uri.lastPathSegment
        ?.substringAfterLast('.', "")
        ?.trim()
        ?.trimStart('.')
        ?.lowercase(Locale.ROOT)
        .orEmpty()
    if (fromPath.isNotBlank()) return fromPath

    val fromMime = runCatching { context.contentResolver.getType(uri) }
        .getOrNull()
        ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        ?.trim()
        ?.lowercase(Locale.ROOT)
        .orEmpty()
    if (fromMime.isNotBlank()) return fromMime
    return fallback.lowercase(Locale.ROOT)
}

private fun resolveAnkiHtmlResourceUri(raw: String): Uri? {
    val src = raw.trim().trim('"', '\'')
    if (src.isBlank()) return null
    if (src.startsWith("#")) return null
    if (src.startsWith("//")) return null
    if (src.startsWith("data:", ignoreCase = true)) return null
    if (src.startsWith("http://", ignoreCase = true) || src.startsWith("https://", ignoreCase = true)) return null

    return if (ANKI_URI_SCHEME_REGEX.containsMatchIn(src)) {
        runCatching { Uri.parse(src) }.getOrNull()
    } else {
        runCatching {
            val asFile = File(src)
            if (asFile.isAbsolute) Uri.fromFile(asFile) else null
        }.getOrNull()
    }
}

private fun findHtmlAttributeValue(tag: String, attribute: String): String? {
    val quotedRegex = Regex(ANKI_ATTR_QUOTED_REGEX.pattern.format(attribute), setOf(RegexOption.IGNORE_CASE))
    quotedRegex.find(tag)?.let { return it.groupValues.getOrNull(2) }
    val unquotedRegex = Regex(ANKI_ATTR_UNQUOTED_REGEX.pattern.format(attribute), setOf(RegexOption.IGNORE_CASE))
    unquotedRegex.find(tag)?.let { return it.groupValues.getOrNull(1) }
    return null
}

private fun escapeHtmlAttributeAnki(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private data class MinedCardGlossarySource(
    val dictionaryName: String,
    val definitions: List<String>,
    val dictionaryCss: String?
)

private fun buildMinedCardGlossarySources(card: MinedCard): List<MinedCardGlossarySource> {
    val mapped = card.glossaryByDictionary
        .mapNotNull { dictionaryGlossary ->
            val dictionaryName = dictionaryGlossary.dictionaryName.trim()
            val definitions = dictionaryGlossary.definitions
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (dictionaryName.isBlank() || definitions.isEmpty()) {
                null
            } else {
                MinedCardGlossarySource(
                    dictionaryName = dictionaryName,
                    definitions = definitions,
                    dictionaryCss = dictionaryGlossary.dictionaryCss
                )
            }
        }
    if (mapped.isNotEmpty()) {
        Log.d(
            ANKI_EXPORT_DEBUG_TAG,
            "sources using glossaryByDictionary count=${mapped.size} names=${mapped.joinToString("|") { it.dictionaryName }}"
        )
        return mapped
    }
    val fallbackDefinitions = card.definitions
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (fallbackDefinitions.isEmpty()) return emptyList()
    val fallback = listOf(
        MinedCardGlossarySource(
            dictionaryName = card.dictionaryName.orEmpty().ifBlank { "Dictionary" },
            definitions = fallbackDefinitions,
            dictionaryCss = card.dictionaryCss
        )
    )
    Log.d(
        ANKI_EXPORT_DEBUG_TAG,
        "sources fallback dict=${fallback.first().dictionaryName} defs=${fallbackDefinitions.size}"
    )
    return fallback
}

private fun selectPrimaryGlossarySource(
    card: MinedCard,
    sources: List<MinedCardGlossarySource>
): MinedCardGlossarySource? {
    if (sources.isEmpty()) return null
    val preferred = normalizeDictionaryToken(card.dictionaryName.orEmpty())
    if (preferred.isBlank()) return sources.firstOrNull()
    return sources.firstOrNull { normalizeDictionaryToken(it.dictionaryName) == preferred }
        ?: sources.firstOrNull()
}

private fun buildStyledGlossaryFromSources(sources: List<MinedCardGlossarySource>): String {
    if (sources.isEmpty()) return ""
    return renderYomitanGlossaryHtml(
        items = sources.map { source ->
            GlossaryHtmlItem(
                dictionaryName = source.dictionaryName,
                definitions = source.definitions.map(::sanitizeAnkiDefinitionHtml),
                dictionaryCss = source.dictionaryCss
            )
        },
        includeDictionaryLabel = true,
        includeParityCss = true
    )
}

private fun resolveBookTitle(context: Context, card: MinedCard): String {
    val audioName = resolveAudioDisplayName(context, card.audioUri)
    val preferred = audioName?.trim().orEmpty().ifBlank { card.bookTitle.orEmpty().trim() }
    if (preferred.isBlank()) return ""
    return preferred.substringBeforeLast('.', preferred)
}

private fun resolveAudioDisplayName(context: Context, uri: Uri?): String? {
    val target = uri ?: return null
    if (target.scheme.equals("file", ignoreCase = true)) {
        return File(target.path.orEmpty()).name.takeIf { it.isNotBlank() }
    }
    val fromQuery = runCatching {
        context.contentResolver.query(
            target,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)
            } else {
                null
            }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
    return fromQuery ?: target.lastPathSegment?.substringAfterLast('/')
}

private fun buildStyledGlossary(
    definitions: List<String>,
    dictionaryName: String?,
    dictionaryCss: String?
): String {
    return renderYomitanGlossaryHtml(
        items = listOf(
            GlossaryHtmlItem(
                dictionaryName = dictionaryName.orEmpty(),
                definitions = definitions.map(::sanitizeAnkiDefinitionHtml),
                dictionaryCss = dictionaryCss
            )
        ),
        includeDictionaryLabel = true,
        includeParityCss = true
    )
}

private fun sanitizeAnkiDefinitionHtml(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""
    return trimmed
}

internal fun classifyAnkiExportFailure(
    context: Context,
    error: Throwable
): AnkiExportResult {
    val message = error.message?.trim().orEmpty()
    return when {
        message == context.getString(R.string.error_anki_not_installed) ||
            message.contains("AnkiDroid is not installed", ignoreCase = true) -> {
            AnkiExportResult.NotAvailable(AnkiAvailabilityState.NOT_INSTALLED, context.getString(R.string.error_anki_not_installed))
        }
        message == context.getString(R.string.error_anki_api_unavailable) ||
            message.contains("API is unavailable", ignoreCase = true) -> {
            AnkiExportResult.NotAvailable(AnkiAvailabilityState.API_UNAVAILABLE, context.getString(R.string.error_anki_api_unavailable))
        }
        message == context.getString(R.string.error_anki_permission_required) ||
            (message.contains("permission", ignoreCase = true) && message.contains("Anki", ignoreCase = true)) -> {
            AnkiExportResult.NotAvailable(AnkiAvailabilityState.PERMISSION_MISSING, context.getString(R.string.error_anki_permission_required))
        }
        message == context.getString(R.string.error_anki_model_missing) ||
            message == context.getString(R.string.error_anki_fields_empty) ||
            message == context.getString(R.string.error_anki_lookup_audio_missing) ||
            message == context.getString(R.string.error_anki_cut_audio_missing) ||
            message.startsWith("No Anki model configured", ignoreCase = true) ||
            message.startsWith("Configured model not found", ignoreCase = true) ||
            message.startsWith("All field variables are empty", ignoreCase = true) ||
            message.startsWith("All rendered field values are empty", ignoreCase = true) -> {
            AnkiExportResult.InvalidConfig(if (message.isBlank()) formatAnkiExportThrowable(error) else message)
        }
        else -> AnkiExportResult.Failed(formatAnkiExportThrowable(error), error)
    }
}

internal fun ankiExportResultMessage(
    context: Context,
    result: AnkiExportResult
): String {
    return when (result) {
        AnkiExportResult.Added -> context.getString(R.string.anki_toast_added)
        is AnkiExportResult.NotAvailable -> result.message
        is AnkiExportResult.InvalidConfig -> result.message
        is AnkiExportResult.Failed -> result.message
    }
}

private fun formatAnkiExportThrowable(error: Throwable): String {
    val message = error.message?.trim().orEmpty()
    return if (message.isBlank()) {
        error.javaClass.simpleName
    } else {
        message
    }
}

private fun attachAudio(
    api: AddContentApi,
    context: Context,
    card: MinedCard
): String? {
    val sourceUri = card.audioUri ?: return null
    val sourceExtension = resolveAudioExtension(context, sourceUri, fallback = "m4a")
    val preferredName = "tset-${System.currentTimeMillis()}"
    val failures = mutableListOf<String>()
    failures += "source-scheme=${sourceUri.scheme.orEmpty()}"

    fun attemptWithUri(
        label: String,
        uri: Uri,
        grantReadPermission: Boolean
    ): String? {
        Log.d(
            ANKI_AUDIO_LOG_TAG,
            "audio-attempt label=$label uri=$uri scheme=${uri.scheme.orEmpty()} last=${uri.lastPathSegment.orEmpty()} grant=$grantReadPermission"
        )
        if (grantReadPermission && uri.scheme.equals("content", ignoreCase = true)) {
            runCatching {
                context.grantUriPermission(
                    requireAnkiPackageName(context),
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.onFailure {
                failures += "$label grant-failed(${uri.scheme.orEmpty()}): ${it.message ?: it.javaClass.simpleName}"
            }
        }
        return try {
            addMediaAsAudioTag(
                api = api,
                uri = uri,
                preferredName = preferredName
            ) { reason ->
                failures += "$label $reason"
            }
        } finally {
            if (grantReadPermission && uri.scheme.equals("content", ignoreCase = true)) {
                runCatching {
                    context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
    }

    val clipFile = createCueAudioClip(
        context = context,
        sourceUri = sourceUri,
        cueStartMs = card.cueStartMs,
        cueEndMs = card.cueEndMs
    ) { reason ->
        failures += "clip $reason"
    }
    try {
        if (clipFile != null) {
            val clipFileUri = Uri.fromFile(clipFile)
            val providerUri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", clipFile)
            }.onFailure {
                failures += "clip fileprovider-uri-failed: ${it.message ?: it.javaClass.simpleName}"
            }.getOrNull()

            if (providerUri != null) {
                attemptWithUri(
                    label = "clip-fileprovider",
                    uri = providerUri,
                    grantReadPermission = true
                )?.let { return it }
            }

            attemptWithUri(
                label = "clip-file",
                uri = clipFileUri,
                grantReadPermission = false
            )?.let { return it }

            val stagedClip = stageAudioInMediaStore(
                context = context,
                sourceUri = clipFileUri,
                extension = clipFile.extension.ifBlank { "m4a" }
            )
            if (stagedClip != null) {
                try {
                    attemptWithUri(
                        label = "clip-mediastore",
                        uri = stagedClip.uri,
                        grantReadPermission = true
                    )?.let { return it }
                } finally {
                    runCatching { stagedClip.cleanup() }
                }
            } else {
                failures += "clip-mediastore stage-failed"
            }
        } else {
            failures += "clip-not-created"
        }

        if (card.requireCueAudioClip) {
            val detail = failures.distinct().joinToString(" | ").take(900)
            error("Failed to attach cue audio clip to Anki media. $detail")
        }

        attemptWithUri(
            label = "source-direct",
            uri = sourceUri,
            grantReadPermission = true
        )?.let { return it }

        val copiedSource = copyUriToTempAudioFile(
            context = context,
            sourceUri = sourceUri,
            extension = sourceExtension,
            prefix = "source"
        )
        if (copiedSource != null) {
            try {
                val copiedProviderUri = runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copiedSource)
                }.onFailure {
                    failures += "source fileprovider-uri-failed: ${it.message ?: it.javaClass.simpleName}"
                }.getOrNull()
                if (copiedProviderUri != null) {
                    attemptWithUri(
                        label = "source-fileprovider",
                        uri = copiedProviderUri,
                        grantReadPermission = true
                    )?.let { return it }
                }
                attemptWithUri(
                    label = "source-file",
                    uri = Uri.fromFile(copiedSource),
                    grantReadPermission = false
                )?.let { return it }
            } finally {
                runCatching { copiedSource.delete() }
            }
        } else {
            failures += "source-copy-failed"
        }

        val stagedSource = stageAudioInMediaStore(
            context = context,
            sourceUri = sourceUri,
            extension = sourceExtension
        )
        if (stagedSource != null) {
            try {
                attemptWithUri(
                    label = "source-mediastore",
                    uri = stagedSource.uri,
                    grantReadPermission = true
                )?.let { return it }
            } finally {
                runCatching { stagedSource.cleanup() }
            }
        } else {
            failures += "source-mediastore stage-failed"
        }

        val detail = failures.distinct().joinToString(" | ").take(900)
        error("Failed to attach subtitle audio clip to Anki media. $detail")
    } finally {
        clipFile?.delete()
    }
}

private fun attachLookupAudio(
    api: AddContentApi,
    context: Context,
    lookupAudioUri: Uri?
): String? {
    val sourceUri = lookupAudioUri ?: return null
    val sourceExtension = resolveAudioExtension(context, sourceUri, fallback = "wav")
    val preferredName = "lookup-${System.currentTimeMillis()}"
    val failures = mutableListOf<String>()
    failures += "source-scheme=${sourceUri.scheme.orEmpty()}"

    fun attemptWithUri(
        label: String,
        uri: Uri,
        grantReadPermission: Boolean
    ): String? {
        Log.d(
            ANKI_AUDIO_LOG_TAG,
            "lookup-attempt label=$label uri=$uri scheme=${uri.scheme.orEmpty()} last=${uri.lastPathSegment.orEmpty()} grant=$grantReadPermission"
        )
        if (grantReadPermission && uri.scheme.equals("content", ignoreCase = true)) {
            runCatching {
                context.grantUriPermission(
                    requireAnkiPackageName(context),
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }.onFailure {
                failures += "$label grant-failed(${uri.scheme.orEmpty()}): ${it.message ?: it.javaClass.simpleName}"
            }
        }
        return try {
            addMediaAsAudioTag(
                api = api,
                uri = uri,
                preferredName = preferredName
            ) { reason ->
                failures += "$label $reason"
            }
        } finally {
            if (grantReadPermission && uri.scheme.equals("content", ignoreCase = true)) {
                runCatching {
                    context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
    }

    val transcodedLookup = transcodeAudioToM4a(
        context = context,
        sourceUri = sourceUri,
        prefix = "lookup-tx"
    ) { reason ->
        failures += "lookup-transcode $reason"
    }
    if (transcodedLookup != null) {
        try {
            val transcodedProviderUri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", transcodedLookup)
            }.onFailure {
                failures += "lookup transcoded fileprovider-uri-failed: ${it.message ?: it.javaClass.simpleName}"
            }.getOrNull()
            if (transcodedProviderUri != null) {
                attemptWithUri(
                    label = "lookup-transcoded-fileprovider",
                    uri = transcodedProviderUri,
                    grantReadPermission = true
                )?.let { return it }
            }
            attemptWithUri(
                label = "lookup-transcoded-file",
                uri = Uri.fromFile(transcodedLookup),
                grantReadPermission = false
            )?.let { return it }
        } finally {
            runCatching { transcodedLookup.delete() }
        }
    }

    attemptWithUri(
        label = "lookup-direct",
        uri = sourceUri,
        grantReadPermission = true
    )?.let { return it }

    val copiedSource = copyUriToTempAudioFile(
        context = context,
        sourceUri = sourceUri,
        extension = sourceExtension,
        prefix = "lookup"
    )
    if (copiedSource != null) {
        try {
            val copiedProviderUri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copiedSource)
            }.onFailure {
                failures += "lookup fileprovider-uri-failed: ${it.message ?: it.javaClass.simpleName}"
            }.getOrNull()
            if (copiedProviderUri != null) {
                attemptWithUri(
                    label = "lookup-fileprovider",
                    uri = copiedProviderUri,
                    grantReadPermission = true
                )?.let { return it }
            }
            attemptWithUri(
                label = "lookup-file",
                uri = Uri.fromFile(copiedSource),
                grantReadPermission = false
            )?.let { return it }
        } finally {
            runCatching { copiedSource.delete() }
        }
    } else {
        failures += "lookup-copy-failed"
    }

    val stagedSource = stageAudioInMediaStore(
        context = context,
        sourceUri = sourceUri,
        extension = sourceExtension
    )
    if (stagedSource != null) {
        try {
            attemptWithUri(
                label = "lookup-mediastore",
                uri = stagedSource.uri,
                grantReadPermission = true
            )?.let { return it }
        } finally {
            runCatching { stagedSource.cleanup() }
        }
    } else {
        failures += "lookup-mediastore stage-failed"
    }

    val detail = failures.distinct().joinToString(" | ").take(900)
    error("Failed to attach lookup audio to Anki media. $detail")
}

private fun addMediaAsAudioTag(
    api: AddContentApi,
    uri: Uri,
    preferredName: String,
    onAttemptFailure: (String) -> Unit = {}
): String? {
    val resolvedName = buildPreferredAudioMediaName(preferredName, uri)
    Log.d(
        ANKI_AUDIO_LOG_TAG,
        "anki-addmedia-attempt uri=$uri scheme=${uri.scheme.orEmpty()} last=${uri.lastPathSegment.orEmpty()} name=$resolvedName"
    )
    val mediaTag = runCatching {
        // Per Anki API contract, mediaType must be "audio" or "image".
        api.addMediaFromUri(uri, resolvedName, "audio")
    }.onFailure {
        onAttemptFailure("exception=${it.message ?: it.javaClass.simpleName}")
    }.getOrNull()
    if (!mediaTag.isNullOrBlank()) {
        Log.d(
            ANKI_AUDIO_LOG_TAG,
            "anki-addmedia-success name=$resolvedName tag=$mediaTag"
        )
        return mediaTag
    }
    Log.d(
        ANKI_AUDIO_LOG_TAG,
        "anki-addmedia-null name=$resolvedName uri=$uri"
    )
    onAttemptFailure("returned-null")
    return null
}

private fun buildPreferredAudioMediaName(preferredName: String, uri: Uri): String {
    val currentExt = preferredName.substringAfterLast('.', "")
    if (currentExt.isNotBlank()) return preferredName
    val uriExt = uri.lastPathSegment
        ?.substringAfterLast('.', "")
        ?.trim()
        ?.trimStart('.')
        ?.lowercase(Locale.ROOT)
        .orEmpty()
    return if (uriExt.isNotBlank()) "$preferredName.$uriExt" else preferredName
}

private fun stageAudioInMediaStore(
    context: Context,
    sourceUri: Uri,
    extension: String
): StagedAnkiAudio? {
    val resolver = context.contentResolver
    val safeExt = extension.trim().trimStart('.').ifBlank { "m4a" }.lowercase(Locale.ROOT)
    val mimeType = mimeTypeForAudioExtension(safeExt)
    val fileName = "tset-${System.currentTimeMillis()}.$safeExt"
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/tset")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    val stagedUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    return try {
        openInputStreamForUri(context, sourceUri)?.use { input ->
            resolver.openOutputStream(stagedUri, "w")?.use { output ->
                input.copyTo(output)
            } ?: error("Cannot open MediaStore output stream.")
        } ?: error("Cannot open audio input stream.")

        val publishValues = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        resolver.update(stagedUri, publishValues, null, null)

        StagedAnkiAudio(
            uri = stagedUri,
            extension = safeExt,
            cleanup = { resolver.delete(stagedUri, null, null) }
        )
    } catch (_: Exception) {
        resolver.delete(stagedUri, null, null)
        null
    }
}

private fun openInputStreamForUri(context: Context, uri: Uri): InputStream? {
    return when (uri.scheme?.lowercase(Locale.ROOT)) {
        "mdictres" -> runCatching {
            openMountedMdictResource(context, uri)?.inputStream
        }.getOrNull()

        "file" -> runCatching {
            val path = uri.path ?: return@runCatching null
            File(path).inputStream()
        }.getOrNull()

        else -> {
            val direct = runCatching {
                context.contentResolver.openInputStream(uri)
            }.getOrNull()
            if (direct != null) return direct

            val pfd = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")
            }.getOrNull() ?: return null
            ParcelFileDescriptor.AutoCloseInputStream(pfd)
        }
    }
}

private fun copyUriToTempAudioFile(
    context: Context,
    sourceUri: Uri,
    extension: String,
    prefix: String
): File? {
    val outFile = createAnkiMediaTempFile(context, prefix = prefix, extension = extension)
    return try {
        openInputStreamForUri(context, sourceUri)?.use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null
        if (outFile.length() <= 0L) {
            outFile.delete()
            null
        } else {
            outFile
        }
    } catch (_: Exception) {
        outFile.delete()
        null
    }
}

private fun mimeTypeForAudioExtension(extension: String): String {
    val fromMap = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(extension.lowercase(Locale.ROOT))
    if (!fromMap.isNullOrBlank()) return fromMap
    return when (extension.lowercase(Locale.ROOT)) {
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"
        "flac" -> "audio/flac"
        else -> "audio/*"
    }
}

private fun resolveAudioExtension(
    context: Context,
    uri: Uri,
    fallback: String
): String {
    fun fromFileName(name: String?): String? {
        val ext = name
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.trim()
            ?.trimStart('.')
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return ext.takeIf { it.isNotBlank() }
    }

    val fromPath = fromFileName(uri.lastPathSegment)
    if (!fromPath.isNullOrBlank()) return fromPath

    val fromDisplayName = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0) null else fromFileName(cursor.getString(index))
        }
    }.getOrNull()
    if (!fromDisplayName.isNullOrBlank()) return fromDisplayName

    val mime = runCatching { context.contentResolver.getType(uri).orEmpty() }.getOrDefault("")
    val fromMime = MimeTypeMap.getSingleton()
        .getExtensionFromMimeType(mime)
        ?.trim()
        ?.trimStart('.')
        ?.lowercase(Locale.ROOT)
    if (!fromMime.isNullOrBlank()) return fromMime

    return fallback
}

private fun createCueAudioClip(
    context: Context,
    sourceUri: Uri,
    cueStartMs: Long,
    cueEndMs: Long,
    onFailure: (String) -> Unit = {}
): File? {
    if (cueEndMs <= cueStartMs) return null
    val clipByRemux = createCueAudioClipByRemux(
        context = context,
        sourceUri = sourceUri,
        cueStartMs = cueStartMs,
        cueEndMs = cueEndMs,
        onFailure = onFailure
    )
    if (clipByRemux != null) return clipByRemux

    val clipByTransformer = createCueAudioClipByTransformer(
        context = context,
        sourceUri = sourceUri,
        cueStartMs = cueStartMs,
        cueEndMs = cueEndMs,
        onFailure = onFailure
    )
    if (clipByTransformer != null) return clipByTransformer

    val clipByExoDecode = createCueAudioClipByExoDecodeToWav(
        context = context,
        sourceUri = sourceUri,
        cueStartMs = cueStartMs,
        cueEndMs = cueEndMs,
        onFailure = onFailure
    )
    if (clipByExoDecode != null) return clipByExoDecode

    onFailure("all-clip-methods-failed")
    return null
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun createCueAudioClipByExoDecodeToWav(
    context: Context,
    sourceUri: Uri,
    cueStartMs: Long,
    cueEndMs: Long,
    onFailure: (String) -> Unit
): File? {
    if (cueEndMs <= cueStartMs) return null

    val clipDurationMs = cueEndMs - cueStartMs
    if (clipDurationMs < 50L) {
        onFailure("exo-window-too-short")
        return null
    }

    val outputDir = File(context.cacheDir, "anki_media")
    if (!outputDir.exists()) {
        outputDir.mkdirs()
    }
    val outputPrefixName = "tset-cue-exo-${System.currentTimeMillis()}"
    val outputPrefixPath = File(outputDir, outputPrefixName).absolutePath

    outputDir.listFiles()
        ?.filter { it.name.startsWith(outputPrefixName) }
        ?.forEach { runCatching { it.delete() } }

    val teeAudioProcessor = TeeAudioProcessor(
        TeeAudioProcessor.WavFileAudioBufferSink(outputPrefixPath)
    )
    val renderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean
        ) = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(teeAudioProcessor))
            .build()
    }

    val playerThread = HandlerThread("anki-cue-exo")
    playerThread.start()
    val playerHandler = Handler(playerThread.looper)
    val playbackLatch = CountDownLatch(1)
    val initLatch = CountDownLatch(1)
    val releaseLatch = CountDownLatch(1)
    val completed = AtomicBoolean(false)
    val released = AtomicBoolean(false)
    var failureDetail: String? = null
    var player: ExoPlayer? = null

    playerHandler.post {
        try {
            player = ExoPlayer.Builder(context, renderersFactory)
                .setLooper(playerThread.looper)
                .build()
                .apply {
                    volume = 0f
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) {
                                completed.set(true)
                                playbackLatch.countDown()
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            failureDetail = "${error.errorCodeName}:${error.message.orEmpty()}"
                            playbackLatch.countDown()
                        }
                    })
                    setMediaItem(
                        MediaItem.Builder()
                            .setUri(sourceUri)
                            .setClipStartPositionMs(cueStartMs.coerceAtLeast(0L))
                            .setClipEndPositionMs(cueEndMs.coerceAtLeast(cueStartMs + 1L))
                            .build()
                    )
                    prepare()
                    play()
                }
        } catch (error: Throwable) {
            failureDetail = "exo-init-failed=${error.javaClass.simpleName}:${error.message.orEmpty()}"
            playbackLatch.countDown()
        } finally {
            initLatch.countDown()
        }
    }

    val initialized = runCatching { initLatch.await(10, TimeUnit.SECONDS) }.getOrDefault(false)
    if (!initialized) {
        onFailure("exo-init-timeout")
        playerHandler.post {
            if (released.compareAndSet(false, true)) {
                runCatching { player?.release() }
                playerThread.quitSafely()
                releaseLatch.countDown()
            }
        }
        runCatching { releaseLatch.await(5, TimeUnit.SECONDS) }
        outputDir.listFiles()?.filter { it.name.startsWith(outputPrefixName) }?.forEach { it.delete() }
        return null
    }

    val timeoutMs = (clipDurationMs.coerceAtLeast(3_000L) + 15_000L).coerceAtMost(120_000L)
    val finished = runCatching { playbackLatch.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    if (!finished) {
        onFailure("exo-timeout")
    } else if (!completed.get()) {
        onFailure(failureDetail ?: "exo-playback-failed")
    }

    playerHandler.post {
        if (released.compareAndSet(false, true)) {
            runCatching { player?.release() }
            playerThread.quitSafely()
            releaseLatch.countDown()
        }
    }
    runCatching { releaseLatch.await(10, TimeUnit.SECONDS) }

    if (!finished || !completed.get()) {
        outputDir.listFiles()?.filter { it.name.startsWith(outputPrefixName) }?.forEach { it.delete() }
        return null
    }

    val wavFile = outputDir.listFiles()
        ?.filter { it.name.startsWith(outputPrefixName) && it.extension.equals("wav", ignoreCase = true) }
        ?.maxByOrNull { it.length() }

    if (wavFile == null || !wavFile.exists() || wavFile.length() <= 44L) {
        outputDir.listFiles()?.filter { it.name.startsWith(outputPrefixName) }?.forEach { it.delete() }
        onFailure("exo-no-wav-output")
        return null
    }

    val transcoded = transcodeAudioToM4a(
        context = context,
        sourceUri = Uri.fromFile(wavFile),
        prefix = "cue-exo-tx"
    ) { reason ->
        onFailure("exo-wav-transcode-$reason")
    }
    if (transcoded != null) {
        runCatching { wavFile.delete() }
        return transcoded
    }

    return wavFile
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun createCueAudioClipByRemux(
    context: Context,
    sourceUri: Uri,
    cueStartMs: Long,
    cueEndMs: Long,
    onFailure: (String) -> Unit
): File? {
    val startUs = cueStartMs.coerceAtLeast(0L) * 1000L
    val endUs = cueEndMs.coerceAtLeast(cueStartMs + 1L) * 1000L
    if (endUs - startUs < 50_000L) {
        onFailure("window-too-short")
        return null
    }

    val outputFile = createAnkiMediaTempFile(context, prefix = "cue", extension = "m4a")
    val extractor = MediaExtractor()
    var muxer: MediaMuxer? = null
    return try {
        val contextDataSourceSet = runCatching {
            extractor.setDataSource(context, sourceUri, null)
        }.onFailure {
            onFailure("datasource-context-failed=${it.javaClass.simpleName}")
        }.isSuccess
        if (!contextDataSourceSet) {
            val path = sourceUri.path
            if (path.isNullOrBlank()) {
                onFailure("datasource-file-path-missing")
                outputFile.delete()
                return null
            }
            val pathDataSourceSet = runCatching {
                extractor.setDataSource(path)
            }.onFailure {
                onFailure("datasource-path-failed=${it.javaClass.simpleName}")
            }.isSuccess
            if (!pathDataSourceSet) {
                outputFile.delete()
                return null
            }
        }

        val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            mime.startsWith("audio/")
        } ?: run {
            onFailure("no-audio-track")
            outputFile.delete()
            return null
        }

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val trackMime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        val maxInputSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(64 * 1024)
        } else {
            256 * 1024
        }

        if (trackMime.equals("audio/mpeg", ignoreCase = true)) {
            outputFile.delete()
            val mp3ClipFile = createAnkiMediaTempFile(context, prefix = "cue", extension = "mp3")
            return createCueMp3ClipBySampleCopy(
                extractor = extractor,
                outputFile = mp3ClipFile,
                maxInputSize = maxInputSize,
                startUs = startUs,
                endUs = endUs,
                onFailure = onFailure
            )
        }

        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outputTrack = runCatching { muxer.addTrack(format) }.onFailure {
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            onFailure("add-track-failed mime=$mime error=${it.javaClass.simpleName}")
        }.getOrNull() ?: run {
            runCatching { muxer.release() }
            outputFile.delete()
            return null
        }
        muxer.start()

        val buffer = ByteBuffer.allocateDirect(maxInputSize)
        val bufferInfo = MediaCodec.BufferInfo()
        var wroteAnySample = false

        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        while (true) {
            bufferInfo.offset = 0
            bufferInfo.size = extractor.readSampleData(buffer, 0)
            if (bufferInfo.size <= 0) break

            val sampleTimeUs = extractor.sampleTime
            if (sampleTimeUs < 0L) break
            if (sampleTimeUs < startUs) {
                if (!extractor.advance()) break
                continue
            }
            if (sampleTimeUs > endUs) break

            bufferInfo.presentationTimeUs = sampleTimeUs - startUs
            val sampleFlags = extractor.sampleFlags
            var codecFlags = 0
            if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
            }
            if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
            }
            bufferInfo.flags = codecFlags
            muxer.writeSampleData(outputTrack, buffer, bufferInfo)
            wroteAnySample = true

            if (!extractor.advance()) break
        }

        if (!wroteAnySample) {
            onFailure("no-sample-in-window")
            runCatching { muxer.stop() }
            outputFile.delete()
            return null
        }

        muxer.stop()
        if (outputFile.length() <= 0L) {
            outputFile.delete()
            null
        } else {
            outputFile
        }
    } catch (e: Exception) {
        onFailure("remux-exception=${e.javaClass.simpleName}")
        outputFile.delete()
        null
    } finally {
        runCatching { muxer?.release() }
        runCatching { extractor.release() }
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun transcodeAudioToM4a(
    context: Context,
    sourceUri: Uri,
    prefix: String,
    onFailure: (String) -> Unit
): File? {
    val sourceExtension = resolveAudioExtension(context, sourceUri, fallback = "").lowercase(Locale.ROOT)
    if (sourceExtension == "wav") {
        transcodeWavToM4aWithAudioConverter(
            context = context,
            sourceUri = sourceUri,
            prefix = prefix,
            onFailure = onFailure
        )?.let { return it }
    }

    return try {
        val outputFile = createAnkiMediaTempFile(context, prefix = prefix, extension = "m4a")
        val done = AtomicBoolean(false)
        val success = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        var errorDetail: String? = null

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                if (done.compareAndSet(false, true)) {
                    success.set(true)
                    latch.countDown()
                }
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                if (done.compareAndSet(false, true)) {
                    errorDetail = "${exportException.errorCodeName}:${exportException.message.orEmpty()}"
                    latch.countDown()
                }
            }

            override fun onTransformationCompleted(
                mediaItem: MediaItem,
                transformationResult: TransformationResult
            ) {
                if (done.compareAndSet(false, true)) {
                    success.set(true)
                    latch.countDown()
                }
            }

            override fun onTransformationError(mediaItem: MediaItem, exception: Exception) {
                if (done.compareAndSet(false, true)) {
                    errorDetail = "${exception.javaClass.simpleName}:${exception.message.orEmpty()}"
                    latch.countDown()
                }
            }
        }

        val mediaItem = MediaItem.fromUri(sourceUri)
        val editedItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            .build()
        val transformer = Transformer.Builder(context)
            .setAudioMimeType("audio/mp4a-latm")
            .setListener(listener)
            .build()

        val started = runCatching {
            transformer.start(editedItem, outputFile.absolutePath)
        }.onFailure {
            onFailure("start-failed=${it.javaClass.simpleName}")
        }.isSuccess
        if (!started) {
            outputFile.delete()
            return null
        }

        val finished = runCatching {
            latch.await(120, TimeUnit.SECONDS)
        }.getOrElse {
            onFailure("await-failed=${it.javaClass.simpleName}")
            false
        }
        if (!finished) {
            transformer.cancel()
            onFailure("timeout")
            outputFile.delete()
            return null
        }
        if (!success.get()) {
            onFailure("error=${errorDetail.orEmpty()}")
            outputFile.delete()
            return null
        }
        if (outputFile.length() <= 0L) {
            onFailure("empty-output")
            outputFile.delete()
            return null
        }
        outputFile
    } catch (e: Exception) {
        onFailure("exception=${e.javaClass.simpleName}")
        null
    }
}

private data class WavHeaderInfo(
    val sampleRate: Int,
    val channelCount: Int
)

private fun transcodeWavToM4aWithAudioConverter(
    context: Context,
    sourceUri: Uri,
    prefix: String,
    onFailure: (String) -> Unit
): File? {
    var tempWavFile: File? = null
    val wavFile = try {
        if (sourceUri.scheme.equals("file", ignoreCase = true)) {
            val path = sourceUri.path
            if (path.isNullOrBlank()) {
                onFailure("audioconverter-file-path-missing")
                return null
            }
            File(path)
        } else {
            copyUriToTempAudioFile(
                context = context,
                sourceUri = sourceUri,
                extension = "wav",
                prefix = "${prefix}-src"
            )?.also { tempWavFile = it }
        }
    } catch (e: Exception) {
        onFailure("audioconverter-source-exception=${e.javaClass.simpleName}")
        null
    } ?: run {
        onFailure("audioconverter-source-unavailable")
        return null
    }

    try {
        val wavInfo = parseSimpleWavHeader(wavFile)
        if (wavInfo == null) {
            onFailure("audioconverter-invalid-wav-header")
            return null
        }
        Log.d(
            ANKI_AUDIO_LOG_TAG,
            "wav-to-m4a-start source=$sourceUri file=${wavFile.absolutePath} sampleRate=${wavInfo.sampleRate} channels=${wavInfo.channelCount}"
        )

        val outputFile = createAnkiMediaTempFile(context, prefix = prefix, extension = "m4a")
        val bitRate = recommendedM4aBitrate(
            sampleRate = wavInfo.sampleRate,
            channelCount = wavInfo.channelCount
        )
        Log.d(
            ANKI_AUDIO_LOG_TAG,
            "wav-to-m4a-config output=${outputFile.absolutePath} bitRate=$bitRate"
        )
        val result = runCatching {
            WavToM4AConverter(
                wavInfo.sampleRate,
                wavInfo.channelCount,
                bitRate
            ).convert(wavFile, outputFile)
        }.onFailure {
            Log.e(
                ANKI_AUDIO_LOG_TAG,
                "wav-to-m4a-exception source=$sourceUri file=${wavFile.absolutePath}",
                it
            )
            onFailure("audioconverter-exception=${it.javaClass.simpleName}")
        }.getOrNull() ?: run {
            outputFile.delete()
            return null
        }

        if (result.convertCode != ConvertionCode.SUCCESS) {
            Log.e(
                ANKI_AUDIO_LOG_TAG,
                "wav-to-m4a-failed code=${result.convertCode} message=${result.errorMessage.orEmpty()}"
            )
            onFailure("audioconverter-failed=${result.errorMessage.orEmpty()}")
            outputFile.delete()
            return null
        }
        if (!outputFile.exists() || outputFile.length() <= 0L) {
            Log.e(
                ANKI_AUDIO_LOG_TAG,
                "wav-to-m4a-empty-output output=${outputFile.absolutePath}"
            )
            onFailure("audioconverter-empty-output")
            outputFile.delete()
            return null
        }
        Log.d(
            ANKI_AUDIO_LOG_TAG,
            "wav-to-m4a-success output=${outputFile.absolutePath} size=${outputFile.length()}"
        )
        return outputFile
    } finally {
        runCatching { tempWavFile?.delete() }
    }
}

private fun parseSimpleWavHeader(file: File): WavHeaderInfo? {
    return runCatching<WavHeaderInfo?> {
        FileInputStream(file).use { input: FileInputStream ->
            val header = ByteArray(44)
            if (input.read(header) < header.size) return@use null
            val riff = String(header, 0, 4, Charsets.US_ASCII)
            val wave = String(header, 8, 4, Charsets.US_ASCII)
            if (riff != "RIFF" || wave != "WAVE") return@use null

            val channelCount = littleEndianUnsignedShort(header, 22)
            val sampleRate = littleEndianInt(header, 24)
            if (channelCount <= 0 || sampleRate <= 0) return@use null

            WavHeaderInfo(
                sampleRate = sampleRate,
                channelCount = channelCount
            )
        }
    }.getOrNull()
}

private fun littleEndianUnsignedShort(buffer: ByteArray, offset: Int): Int {
    return (buffer[offset].toInt() and 0xFF) or
        ((buffer[offset + 1].toInt() and 0xFF) shl 8)
}

private fun littleEndianInt(buffer: ByteArray, offset: Int): Int {
    return (buffer[offset].toInt() and 0xFF) or
        ((buffer[offset + 1].toInt() and 0xFF) shl 8) or
        ((buffer[offset + 2].toInt() and 0xFF) shl 16) or
        ((buffer[offset + 3].toInt() and 0xFF) shl 24)
}

private fun recommendedM4aBitrate(sampleRate: Int, channelCount: Int): Int {
    val normalizedChannels = channelCount.coerceIn(1, 2)
    return when {
        sampleRate >= 48_000 -> 128_000 * normalizedChannels
        sampleRate >= 44_100 -> 96_000 * normalizedChannels
        sampleRate >= 24_000 -> 64_000 * normalizedChannels
        else -> 48_000 * normalizedChannels
    }
}

private fun createCueMp3ClipBySampleCopy(
    extractor: MediaExtractor,
    outputFile: File,
    maxInputSize: Int,
    startUs: Long,
    endUs: Long,
    onFailure: (String) -> Unit
): File? {
    return try {
        val buffer = ByteBuffer.allocateDirect(maxInputSize)
        var wroteAnySample = false
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        FileOutputStream(outputFile).use { out ->
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size <= 0) break

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0L) break
                if (sampleTimeUs < startUs) {
                    if (!extractor.advance()) break
                    continue
                }
                if (sampleTimeUs > endUs) break

                val bytes = ByteArray(size)
                buffer.position(0)
                buffer.get(bytes, 0, size)
                out.write(bytes)
                wroteAnySample = true

                if (!extractor.advance()) break
            }
        }
        if (!wroteAnySample || outputFile.length() <= 0L) {
            onFailure("mp3-copy-empty-output")
            outputFile.delete()
            null
        } else {
            outputFile
        }
    } catch (e: Exception) {
        onFailure("mp3-copy-exception=${e.javaClass.simpleName}")
        outputFile.delete()
        null
    }
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun createCueAudioClipByTransformer(
    context: Context,
    sourceUri: Uri,
    cueStartMs: Long,
    cueEndMs: Long,
    onFailure: (String) -> Unit
): File? {
    return try {
        val outputFile = createAnkiMediaTempFile(context, prefix = "cue-tx", extension = "m4a")
        val done = AtomicBoolean(false)
        val success = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        var errorDetail: String? = null

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                if (done.compareAndSet(false, true)) {
                    success.set(true)
                    latch.countDown()
                }
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                if (done.compareAndSet(false, true)) {
                    errorDetail = "${exportException.errorCodeName}:${exportException.message.orEmpty()}"
                    latch.countDown()
                }
            }

            override fun onTransformationCompleted(
                mediaItem: MediaItem,
                transformationResult: TransformationResult
            ) {
                if (done.compareAndSet(false, true)) {
                    success.set(true)
                    latch.countDown()
                }
            }

            override fun onTransformationError(mediaItem: MediaItem, exception: Exception) {
                if (done.compareAndSet(false, true)) {
                    errorDetail = "${exception.javaClass.simpleName}:${exception.message.orEmpty()}"
                    latch.countDown()
                }
            }
        }

        val mediaItem = MediaItem.Builder()
            .setUri(sourceUri)
            .setClipStartPositionMs(cueStartMs.coerceAtLeast(0L))
            .setClipEndPositionMs(cueEndMs.coerceAtLeast(cueStartMs + 1L))
            .build()
        val editedItem = EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            .build()
        val transformer = Transformer.Builder(context)
            .setAudioMimeType("audio/mp4a-latm")
            .setListener(listener)
            .build()

        val started = runCatching {
            transformer.start(editedItem, outputFile.absolutePath)
        }.onFailure {
            onFailure("transformer-start-failed=${it.javaClass.simpleName}")
        }.isSuccess
        if (!started) {
            outputFile.delete()
            return null
        }

        val finished = runCatching {
            latch.await(90, TimeUnit.SECONDS)
        }.getOrElse {
            onFailure("transformer-await-failed=${it.javaClass.simpleName}")
            false
        }
        if (!finished) {
            transformer.cancel()
            onFailure("transformer-timeout")
            outputFile.delete()
            return null
        }
        if (!success.get()) {
            onFailure("transformer-error=${errorDetail.orEmpty()}")
            outputFile.delete()
            return null
        }
        if (outputFile.length() <= 0L) {
            onFailure("transformer-empty-output")
            outputFile.delete()
            return null
        }
        outputFile
    } catch (e: Exception) {
        onFailure("transformer-exception=${e.javaClass.simpleName}")
        null
    }
}

private fun createAnkiMediaTempFile(
    context: Context,
    prefix: String,
    extension: String
): File {
    val dir = File(context.cacheDir, "anki_media")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    val safeExt = extension.trim().trimStart('.').ifBlank { "m4a" }
    return File(dir, "tset-$prefix-${System.currentTimeMillis()}.$safeExt")
}

private fun resolveTemplate(template: String, variables: Map<String, String>): String {
    var output = template
    output = output.replace(SINGLE_GLOSSARY_DICT_MARKER_REGEX) { match ->
        val rawMarker = match.groupValues.getOrNull(1).orEmpty().trim()
        val (requestedNameRaw, markerKey) = when {
            rawMarker.endsWith("-brief", ignoreCase = true) -> {
                rawMarker.removeSuffix("-brief").trimEnd() to "single-glossary-brief"
            }

            rawMarker.endsWith("-no-dictionary", ignoreCase = true) -> {
                rawMarker.removeSuffix("-no-dictionary").trimEnd() to "single-glossary-no-dictionary"
            }

            else -> rawMarker to "single-glossary"
        }
        val requestedDictionaryName = normalizeDictionaryToken(requestedNameRaw)
        if (requestedDictionaryName.isBlank()) return@replace ""
        variables[templateSingleGlossaryKey(markerKey, requestedDictionaryName)].orEmpty()
    }
    val selectedDictionaryName = normalizeDictionaryToken(
        variables["dictionary"].orEmpty().ifBlank { variables["dictionary-name"].orEmpty() }
    )
    output = output.replace(SINGLE_FREQUENCY_NUMBER_DICT_MARKER_REGEX) { match ->
        val requestedDictionaryName = normalizeDictionaryToken(match.groupValues.getOrNull(1).orEmpty())
        if (requestedDictionaryName.isBlank() || selectedDictionaryName.isBlank()) {
            ""
        } else if (requestedDictionaryName == selectedDictionaryName) {
            variables["single-frequency-number"].orEmpty()
        } else {
            ""
        }
    }
    output = output.replace(SINGLE_FREQUENCY_DICT_MARKER_REGEX) { match ->
        val requestedDictionaryName = normalizeDictionaryToken(match.groupValues.getOrNull(1).orEmpty())
        if (requestedDictionaryName.isBlank() || selectedDictionaryName.isBlank()) {
            ""
        } else if (requestedDictionaryName == selectedDictionaryName) {
            variables["single-frequency"].orEmpty()
        } else {
            ""
        }
    }
    val normalizedVariables = HashMap<String, String>(variables.size * 2)
    variables.forEach { (key, value) ->
        normalizedVariables.putIfAbsent(key, value)
        normalizedVariables.putIfAbsent(canonicalizeTemplateKey(key), value)
    }
    return output.replace(TEMPLATE_VARIABLE_REGEX) { match ->
        val key = match.groupValues.getOrNull(1).orEmpty()
        normalizedVariables[key]
            ?: normalizedVariables[canonicalizeTemplateKey(key)]
            ?: ""
    }
}

private fun templateSingleGlossaryKey(markerKey: String, normalizedDictionaryName: String): String {
    return "__${markerKey}::$normalizedDictionaryName"
}

private fun templateUsesVariable(template: String, variableName: String): Boolean {
    if (template.isBlank()) return false
    val target = canonicalizeTemplateKey(variableName)
    if (target.isBlank()) return false
    return TEMPLATE_VARIABLE_REGEX
        .findAll(template)
        .any { match ->
            val marker = match.groupValues.getOrNull(1).orEmpty()
            canonicalizeTemplateKey(marker) == target
        }
}

private fun canonicalizeTemplateKey(key: String): String {
    return key
        .trim()
        .lowercase(Locale.ROOT)
        .replace(NON_ALNUM_TEMPLATE_KEY_REGEX, "")
}

private fun normalizeDictionaryToken(value: String): String {
    return value
        .trim()
        .lowercase(Locale.ROOT)
        .replace(DICTIONARY_TOKEN_STRIP_REGEX, "")
        .replace("\u8bcd\u5178", "\u8f9e\u5178")
        .replace("\u93e1", "\u955c")
}

private fun splitCloze(sentence: String, word: String): Triple<String, String, String> {
    if (sentence.isBlank() || word.isBlank()) return Triple(sentence, "", "")
    val index = sentence.indexOf(word)
    if (index < 0) return Triple(sentence, "", "")
    val prefix = sentence.substring(0, index)
    val body = sentence.substring(index, index + word.length)
    val suffix = sentence.substring(index + word.length)
    return Triple(prefix, body, suffix)
}

private fun extractFirstNumber(text: String?): String {
    if (text.isNullOrBlank()) return ""
    return Regex("\\d+(?:\\.\\d+)?").find(text)?.value.orEmpty()
}

private fun buildExpressionFurigana(expression: String, reading: String?): String {
    val exp = expression.trim()
    val rd = reading?.trim().orEmpty()
    if (exp.isBlank()) return ""
    if (rd.isBlank()) return exp
    return "$exp[$rd]"
}



