package com.tekuza.p9player

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.TransformationResult
import androidx.media3.transformer.Transformer
import com.ichi2.anki.api.AddContentApi
import java.io.File
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

internal fun loadAnkiCatalog(context: Context): AnkiCatalog {
    if (!isAnkiInstalled(context)) error("AnkiDroid is not installed")
    if (!hasAnkiReadWritePermission(context)) {
        error("Anki access permission is required")
    }

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
        lowered.contains("audio") || lowered.contains("sound") -> "{cut-audio}"
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

internal fun exportToAnkiDroidApi(
    context: Context,
    card: MinedCard,
    config: AnkiExportConfig
) {
    if (!isAnkiInstalled(context)) error("AnkiDroid is not installed")
    if (!hasAnkiReadWritePermission(context)) error("Anki access permission is required")

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

    val variables = runCatching {
        buildAnkiVariables(
            context = context,
            api = api,
            card = card,
            includeCutAudio = requiresCutAudio
        )
    }.getOrElse { throwable ->
        error("Anki variable build failed. ${throwableDetail(throwable)}")
    }

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
    includeCutAudio: Boolean
): Map<String, String> {
    val dictionaryName = card.dictionaryName.orEmpty()
    val glossaryHtml = buildStyledGlossary(
        definitions = card.definitions,
        dictionaryName = dictionaryName,
        dictionaryCss = card.dictionaryCss
    )
    val glossaryFirst = card.definitions.firstOrNull().orEmpty()
    val glossaryNoDictionary = card.definitions.joinToString("<br>")
    val glossaryPlain = card.definitions.joinToString("\n")
    val styledGlossaryFirst = if (glossaryFirst.isBlank()) {
        ""
    } else {
        buildStyledGlossary(
            definitions = listOf(glossaryFirst),
            dictionaryName = dictionaryName,
            dictionaryCss = card.dictionaryCss
        )
    }
    val cutAudio = if (includeCutAudio) {
        attachAudio(api, context, card).orEmpty()
    } else {
        ""
    }
    val popupSelectionText = card.popupSelectionText?.trim().orEmpty()
    val clozeTarget = popupSelectionText.ifBlank { card.word }
    val (clozePrefix, clozeBody, clozeSuffix) = splitCloze(card.sentence, clozeTarget)
    val frequencyNumber = extractFirstNumber(card.frequency)
    val expressionFurigana = buildExpressionFurigana(card.word, card.reading)
    val sentenceFurigana = ""
    val singleFrequency = card.frequency.orEmpty()
    val resolvedBookTitle = resolveBookTitle(context, card)

    return mapOf(
        "word" to card.word,
        "expression" to card.word,
        "dictionary-name" to dictionaryName,
        "dictionary" to dictionaryName,
        "dictionary-alias" to dictionaryName,
        "popup-selection-text" to popupSelectionText.ifBlank { card.word },
        "search-query" to card.word,
        "sentence" to card.sentence,
        "sentence-furigana" to sentenceFurigana,
        "sentence-furigana-plain" to sentenceFurigana,
        "sentencefurigana" to sentenceFurigana,
        "sentencefuriganaplain" to sentenceFurigana,
        "cloze-prefix" to clozePrefix,
        "cloze-body" to clozeBody,
        "cloze-body-kana" to (card.reading ?: clozeBody),
        "cloze-suffix" to clozeSuffix,
        "reading" to card.reading.orEmpty(),
        "furigana" to expressionFurigana,
        "furigana-plain" to expressionFurigana,
        "expression-furigana" to expressionFurigana,
        "conjugation" to "",
        "part-of-speech" to "",
        "phonetic-transcriptions" to "",
        "definitions" to glossaryHtml,
        "definition" to glossaryHtml,
        "glossary" to glossaryHtml,
        "glossary-no-dictionary" to glossaryNoDictionary,
        "glossary-first" to styledGlossaryFirst,
        "glossary-first-brief" to glossaryFirst,
        "glossary-first-no-dictionary" to glossaryFirst,
        "single-glossary" to glossaryHtml,
        "single-glossary-brief" to glossaryFirst,
        "single-glossary-no-dictionary" to glossaryNoDictionary,
        "glossary-brief" to glossaryFirst,
        "glossary-plain" to glossaryPlain,
        "glossary-plain-no-dictionary" to glossaryPlain,
        "dictionary-css" to card.dictionaryCss.orEmpty(),
        "pitch" to card.pitch.orEmpty(),
        "pitch-accents" to card.pitch.orEmpty(),
        "pitch-accent-positions" to card.pitch.orEmpty(),
        "pitch-accent-categories" to card.pitch.orEmpty(),
        "pitch-accent-graphs" to "",
        "pitch-accent-graphs-jj" to "",
        "frequency" to card.frequency.orEmpty(),
        "frequencies" to card.frequency.orEmpty(),
        "single-frequency" to singleFrequency,
        "single-frequency-number" to frequencyNumber,
        "frequency-harmonic-rank" to frequencyNumber,
        "frequency-harmonic-occurrence" to frequencyNumber,
        "frequency-average-rank" to frequencyNumber,
        "frequency-average-occurrence" to frequencyNumber,
        "audio" to "",
        "audioTag" to "",
        "cut-audio" to cutAudio,
        "screenshot" to "",
        "tags" to "",
        "url" to "",
        "document-title" to resolvedBookTitle,
        "book-title" to resolvedBookTitle,
        "book-cover" to ""
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
    if (definitions.isEmpty()) return ""
    val normalizedDefinitions = definitions
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (normalizedDefinitions.isEmpty()) return ""
    val dictName = dictionaryName?.trim().orEmpty()
    if (dictName.isBlank()) {
        val rawCss = dictionaryCss?.trim().orEmpty()
        val styleBlock = if (rawCss.isBlank()) {
            ""
        } else {
            "<style>$rawCss</style>"
        }
        val body = if (normalizedDefinitions.all { it.startsWith("<li") }) {
            "<ol>${normalizedDefinitions.joinToString("")}</ol>"
        } else {
            normalizedDefinitions.joinToString("<br>")
        }
        return """
            <div style="text-align: left;" class="yomitan-glossary">
                $body
                $styleBlock
            </div>
        """.trimIndent()
    }

    val body = normalizedDefinitions.joinToString("<br>")
    val safeName = escapeHtmlText(dictName)
    val safeAttr = escapeHtmlAttribute(dictName)
    val scopedCss = buildScopedDictionaryCss(dictionaryCss.orEmpty(), dictName)
    return """
        <div style="text-align: left;" class="yomitan-glossary">
            <ol>
                <li data-dictionary="$safeAttr">
                    <i>($safeName)</i> <span>$body</span>
                </li>
                <style>$scopedCss</style>
            </ol>
        </div>
    """.trimIndent()
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
        if (grantReadPermission && uri.scheme.equals("content", ignoreCase = true)) {
            runCatching {
                context.grantUriPermission(
                    ANKI_PACKAGE_NAME,
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

private fun addMediaAsAudioTag(
    api: AddContentApi,
    uri: Uri,
    preferredName: String,
    onAttemptFailure: (String) -> Unit = {}
): String? {
    val mediaTag = runCatching {
        // Per Anki API contract, mediaType must be "audio" or "image".
        api.addMediaFromUri(uri, preferredName, "audio")
    }.onFailure {
        onAttemptFailure("exception=${it.message ?: it.javaClass.simpleName}")
    }.getOrNull()
    if (!mediaTag.isNullOrBlank()) return mediaTag
    onAttemptFailure("returned-null")
    return null
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/tset")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
    }

    val stagedUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    return try {
        openInputStreamForUri(context, sourceUri)?.use { input ->
            resolver.openOutputStream(stagedUri, "w")?.use { output ->
                input.copyTo(output)
            } ?: error("Cannot open MediaStore output stream.")
        } ?: error("Cannot open audio input stream.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(stagedUri, publishValues, null, null)
        }

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

    onFailure("all-clip-methods-failed")
    return null
}

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
            bufferInfo.flags = extractor.sampleFlags
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
    val selectedDictionaryName = normalizeDictionaryToken(
        variables["dictionary"].orEmpty().ifBlank { variables["dictionary-name"].orEmpty() }
    )
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
        if (requestedDictionaryName.isBlank() || selectedDictionaryName.isBlank()) {
            ""
        } else if (requestedDictionaryName == selectedDictionaryName) {
            variables[markerKey].orEmpty()
        } else {
            ""
        }
    }
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

private fun escapeHtmlText(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private fun escapeHtmlAttribute(value: String): String {
    return escapeHtmlText(value).replace("\"", "&quot;")
}

private fun escapeCssString(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}

private fun buildScopedDictionaryCss(rawCss: String, dictionaryName: String): String {
    val trimmed = rawCss.trim()
    if (trimmed.isBlank()) return ""
    if (dictionaryName.isBlank()) return trimmed

    val dictionaryAttr = escapeCssString(dictionaryName)
    val prefix = ".yomitan-glossary [data-dictionary=\"$dictionaryAttr\"]"
    val ruleRegex = Regex("([^{}]+)\\{([^}]*)\\}")
    val scoped = ruleRegex.replace(trimmed) { match ->
        val selectors = match.groupValues[1]
        val body = match.groupValues[2]
        if (selectors.trim().startsWith("@")) return@replace match.value
        val prefixed = selectors
            .split(',')
            .map { selector ->
                val s = selector.trim()
                if (s.isBlank()) s else "$prefix $s"
            }
            .joinToString(", ")
        "$prefixed {$body}"
    }

    return scoped.trim()
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



