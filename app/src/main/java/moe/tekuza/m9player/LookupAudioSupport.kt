package moe.tekuza.m9player

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal data class PreparedLookupAudio(
    val uri: Uri,
    val cleanup: () -> Unit = {}
)

private data class AndroidDbEntryRef(
    val source: String?,
    val file: String,
    val display: String?,
    val reading: String?
)

private data class AndroidDbAudioBlob(
    val bytes: ByteArray,
    val extension: String
)

private val lookupAudioMainHandler = Handler(Looper.getMainLooper())
private val lookupAudioLock = Any()
private var activeLookupPlayer: MediaPlayer? = null
private var activeLookupPlayerCleanup: (() -> Unit)? = null
private var activeLookupTts: TextToSpeech? = null

private val lookupAudioDbCacheLock = Any()
private var cachedLookupAudioDbUri: String? = null
private var cachedLookupAudioDbMeta: String? = null
private var cachedLookupAudioDbFile: File? = null

internal fun playLookupAudioForTerm(
    context: Context,
    term: String,
    reading: String? = null,
    settings: AudiobookSettingsConfig,
    onError: (String) -> Unit = {}
): Boolean {
    if (!settings.lookupPlaybackAudioEnabled) {
        postLookupAudioError(onError, "请先在设置 > 有声书开启查词播放音频。")
        return false
    }
    val normalizedTerm = term.trim()
    if (normalizedTerm.isBlank()) {
        postLookupAudioError(onError, "当前词条为空，无法播放。")
        return false
    }

    return when (settings.lookupAudioMode) {
        LookupAudioMode.LOCAL_TTS -> playLookupAudioWithTts(
            context = context,
            term = normalizedTerm,
            onError = onError
        )

        LookupAudioMode.LOCAL_AUDIO -> {
            val dbUri = settings.lookupLocalAudioUri
            if (dbUri == null) {
                postLookupAudioError(onError, "未导入 android.db。")
                false
            } else {
                val prepared = runCatching {
                    resolveLookupAudioFromAndroidDb(
                        context = context,
                        dbUri = dbUri,
                        term = normalizedTerm,
                        reading = reading
                    )
                }.getOrNull()
                if (prepared == null) {
                    postLookupAudioError(onError, "android.db 中未找到该词音频。")
                    false
                } else {
                    playLookupAudioFromUri(
                        context = context,
                        uri = prepared.uri,
                        onError = onError,
                        cleanup = prepared.cleanup
                    )
                }
            }
        }
    }
}

internal fun prepareLookupAudioForAnkiExport(
    context: Context,
    term: String,
    reading: String? = null,
    settings: AudiobookSettingsConfig
): PreparedLookupAudio? {
    if (!settings.lookupPlaybackAudioEnabled) return null
    val normalizedTerm = term.trim()
    if (normalizedTerm.isBlank()) return null

    return when (settings.lookupAudioMode) {
        LookupAudioMode.LOCAL_TTS -> {
            val synthesized = synthesizeLookupAudioToTempFile(context, normalizedTerm) ?: return null
            PreparedLookupAudio(
                uri = Uri.fromFile(synthesized),
                cleanup = { runCatching { synthesized.delete() } }
            )
        }

        LookupAudioMode.LOCAL_AUDIO -> {
            val dbUri = settings.lookupLocalAudioUri ?: return null
            resolveLookupAudioFromAndroidDb(
                context = context,
                dbUri = dbUri,
                term = normalizedTerm,
                reading = reading
            )
        }
    }
}

private fun playLookupAudioFromUri(
    context: Context,
    uri: Uri,
    onError: (String) -> Unit,
    cleanup: () -> Unit = {}
): Boolean {
    val player = MediaPlayer()
    synchronized(lookupAudioLock) {
        stopLookupAudioPlaybackLocked()
        activeLookupPlayer = player
        activeLookupPlayerCleanup = cleanup
    }

    return runCatching {
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        player.setDataSource(context.applicationContext, uri)
        player.setOnPreparedListener { prepared ->
            runCatching { prepared.start() }
        }
        player.setOnCompletionListener { completed ->
            val completionCleanup = synchronized(lookupAudioLock) {
                if (activeLookupPlayer === completed) {
                    activeLookupPlayer = null
                    activeLookupPlayerCleanup.also {
                        activeLookupPlayerCleanup = null
                    }
                } else {
                    null
                }
            }
            runCatching { completed.release() }
            runCatching { completionCleanup?.invoke() }
        }
        player.setOnErrorListener { failed, _, _ ->
            val errorCleanup = synchronized(lookupAudioLock) {
                if (activeLookupPlayer === failed) {
                    activeLookupPlayer = null
                    activeLookupPlayerCleanup.also {
                        activeLookupPlayerCleanup = null
                    }
                } else {
                    null
                }
            }
            runCatching { failed.release() }
            runCatching { errorCleanup?.invoke() }
            postLookupAudioError(onError, "查词音频播放失败。")
            true
        }
        player.prepareAsync()
    }.onFailure {
        val startupCleanup = synchronized(lookupAudioLock) {
            if (activeLookupPlayer === player) {
                activeLookupPlayer = null
                activeLookupPlayerCleanup.also {
                    activeLookupPlayerCleanup = null
                }
            } else {
                null
            }
        }
        runCatching { player.release() }
        runCatching { startupCleanup?.invoke() }
        postLookupAudioError(onError, "无法打开查词音频。")
    }.isSuccess
}

private fun playLookupAudioWithTts(
    context: Context,
    term: String,
    onError: (String) -> Unit
): Boolean {
    var ttsRef: TextToSpeech? = null
    val utteranceId = "lookup-play-${System.currentTimeMillis()}"

    val tts = TextToSpeech(context.applicationContext) { initStatus ->
        val current = ttsRef ?: return@TextToSpeech
        if (initStatus != TextToSpeech.SUCCESS) {
            clearLookupTts(current)
            postLookupAudioError(onError, "本地 TTS 初始化失败。")
            return@TextToSpeech
        }

        val languageResult = current.setLanguage(Locale.JAPANESE)
        if (
            languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            current.setLanguage(Locale.getDefault())
        }
        current.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    clearLookupTts(current)
                }

                override fun onError(utteranceId: String?) {
                    clearLookupTts(current)
                    postLookupAudioError(onError, "本地 TTS 播放失败。")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    clearLookupTts(current)
                    postLookupAudioError(onError, "本地 TTS 播放失败。")
                }
            }
        )

        val speakResult = current.speak(term, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (speakResult == TextToSpeech.ERROR) {
            clearLookupTts(current)
            postLookupAudioError(onError, "本地 TTS 播放失败。")
        }
    }
    ttsRef = tts
    synchronized(lookupAudioLock) {
        stopLookupAudioPlaybackLocked()
        activeLookupTts = tts
    }
    return true
}

private fun resolveLookupAudioFromAndroidDb(
    context: Context,
    dbUri: Uri,
    term: String,
    reading: String?
): PreparedLookupAudio? {
    val dbFile = resolveAndroidDbFile(context, dbUri) ?: return null
    val audioBlob = queryAudioBlobFromAndroidDb(
        dbFile = dbFile,
        term = term,
        reading = reading
    ) ?: return null
    val tempAudio = writeLookupAudioBlobToTempFile(
        context = context,
        bytes = audioBlob.bytes,
        extension = audioBlob.extension
    ) ?: return null
    return PreparedLookupAudio(
        uri = Uri.fromFile(tempAudio),
        cleanup = { runCatching { tempAudio.delete() } }
    )
}

private fun resolveAndroidDbFile(context: Context, sourceUri: Uri): File? {
    val scheme = sourceUri.scheme?.lowercase(Locale.ROOT).orEmpty()
    if (scheme == "file") {
        val path = sourceUri.path.orEmpty().trim()
        if (path.isBlank()) return null
        val direct = File(path)
        if (!direct.exists() || !direct.isFile || direct.length() <= 0L) return null
        return direct
    }

    val uriString = sourceUri.toString()
    val meta = querySourceUriMeta(context, sourceUri)
    synchronized(lookupAudioDbCacheLock) {
        val cached = cachedLookupAudioDbFile
        if (
            cachedLookupAudioDbUri == uriString &&
            cachedLookupAudioDbMeta == meta &&
            cached != null &&
            cached.exists() &&
            cached.length() > 0L
        ) {
            return cached
        }
    }

    val cacheDir = File(context.cacheDir, "lookup_audio_db")
    if (!cacheDir.exists()) {
        cacheDir.mkdirs()
    }
    val fileName = "android-${uriString.hashCode().toUInt().toString(16)}.db"
    val target = File(cacheDir, fileName)
    val copied = runCatching {
        openLookupInputStream(context, sourceUri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return null
        if (target.length() <= 0L) {
            runCatching { target.delete() }
            return null
        }
        true
    }.getOrDefault(false)
    if (!copied) return null

    synchronized(lookupAudioDbCacheLock) {
        cachedLookupAudioDbUri = uriString
        cachedLookupAudioDbMeta = meta
        cachedLookupAudioDbFile = target
    }
    return target
}

private fun queryAudioBlobFromAndroidDb(
    dbFile: File,
    term: String,
    reading: String?
): AndroidDbAudioBlob? {
    val normalizedTerm = term.trim()
    if (normalizedTerm.isBlank()) return null
    val normalizedReading = reading?.trim()?.takeIf { it.isNotBlank() }

    val database = runCatching {
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }.getOrNull() ?: return null

    database.use { db ->
        if (!hasAndroidDbAudioTables(db)) return null
        val rawCandidates = linkedSetOf<AndroidDbEntryRef>()
        collectAndroidDbEntryRefs(
            db = db,
            sql = "SELECT source, file, display, reading FROM entries WHERE expression = ? LIMIT 128",
            args = arrayOf(normalizedTerm),
            target = rawCandidates
        )

        val candidates = rawCandidates
            .sortedWith(
                compareBy<AndroidDbEntryRef> {
                    scoreAndroidDbEntryRef(
                        term = normalizedTerm,
                        reading = normalizedReading,
                        entry = it
                    )
                }.thenBy { it.display.orEmpty().length }
            )

        candidates.forEach { ref ->
            val blob = queryAndroidDbBlob(db, ref.source, ref.file)
            if (blob != null && blob.isNotEmpty()) {
                val extension = resolveAudioExtensionFromEntryFileName(ref.file, blob)
                return AndroidDbAudioBlob(
                    bytes = blob,
                    extension = extension
                )
            }
        }
    }
    return null
}

private fun scoreAndroidDbEntryRef(
    term: String,
    reading: String?,
    entry: AndroidDbEntryRef
): Int {
    val normalizedDisplay = entry.display?.trim()?.takeIf { it.isNotBlank() }
    val normalizedReading = entry.reading?.trim()?.takeIf { it.isNotBlank() }
    val readingMatched = !reading.isNullOrBlank() && reading == normalizedReading
    val readingBlank = normalizedReading.isNullOrBlank()
    val displayMatched = normalizedDisplay == term
    val displayBlank = normalizedDisplay.isNullOrBlank()
    val displayPrefixOnly = !displayBlank && !displayMatched && normalizedDisplay!!.startsWith(term)

    return when {
        displayMatched && readingMatched -> 0
        displayMatched && (readingBlank || reading.isNullOrBlank()) -> 1
        displayMatched -> 2
        displayBlank && readingMatched -> 3
        displayBlank && (readingBlank || reading.isNullOrBlank()) -> 4
        displayBlank -> 5
        readingMatched -> 6
        displayPrefixOnly -> 7
        else -> 8
    }
}

private fun hasAndroidDbAudioTables(db: SQLiteDatabase): Boolean {
    val tables = mutableSetOf<String>()
    runCatching {
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('entries', 'android')",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.trim()?.lowercase(Locale.ROOT)?.let { tables += it }
            }
        }
    }
    return tables.contains("entries") && tables.contains("android")
}

private fun collectAndroidDbEntryRefs(
    db: SQLiteDatabase,
    sql: String,
    args: Array<String>,
    target: LinkedHashSet<AndroidDbEntryRef>
) {
    runCatching {
        db.rawQuery(sql, args).use { cursor ->
            val sourceIndex = cursor.getColumnIndex("source")
            val fileIndex = cursor.getColumnIndex("file")
            val displayIndex = cursor.getColumnIndex("display")
            val readingIndex = cursor.getColumnIndex("reading")
            if (fileIndex < 0) return
            while (cursor.moveToNext()) {
                val file = cursor.getString(fileIndex)?.trim().orEmpty()
                if (file.isBlank()) continue
                val source = if (sourceIndex >= 0) {
                    cursor.getString(sourceIndex)?.trim()?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
                val display = if (displayIndex >= 0) {
                    cursor.getString(displayIndex)?.trim()?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
                val reading = if (readingIndex >= 0) {
                    cursor.getString(readingIndex)?.trim()?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
                target += AndroidDbEntryRef(
                    source = source,
                    file = file,
                    display = display,
                    reading = reading
                )
            }
        }
    }
}

private fun queryAndroidDbBlob(
    db: SQLiteDatabase,
    source: String?,
    file: String
): ByteArray? {
    if (!source.isNullOrBlank()) {
        val bySource = runCatching {
            db.rawQuery(
                "SELECT data FROM android WHERE file = ? AND source = ? LIMIT 1",
                arrayOf(file, source)
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getBlob(0)
            }
        }.getOrNull()
        if (bySource != null && bySource.isNotEmpty()) return bySource
    }

    return runCatching {
        db.rawQuery(
            "SELECT data FROM android WHERE file = ? LIMIT 1",
            arrayOf(file)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursor.getBlob(0)
        }
    }.getOrNull()
}

private fun resolveAudioExtensionFromEntryFileName(fileName: String, data: ByteArray): String {
    val ext = fileName
        .substringAfterLast('.', "")
        .trim()
        .lowercase(Locale.ROOT)
        .takeIf { it.isNotBlank() }
    if (!ext.isNullOrBlank()) return ext
    if (data.size >= 3 && data[0] == 'I'.code.toByte() && data[1] == 'D'.code.toByte() && data[2] == '3'.code.toByte()) {
        return "mp3"
    }
    if (data.size >= 12) {
        if (
            data[0] == 'R'.code.toByte() &&
            data[1] == 'I'.code.toByte() &&
            data[2] == 'F'.code.toByte() &&
            data[3] == 'F'.code.toByte() &&
            data[8] == 'W'.code.toByte() &&
            data[9] == 'A'.code.toByte() &&
            data[10] == 'V'.code.toByte() &&
            data[11] == 'E'.code.toByte()
        ) {
            return "wav"
        }
    }
    if (data.size >= 4) {
        if (
            data[0] == 'O'.code.toByte() &&
            data[1] == 'g'.code.toByte() &&
            data[2] == 'g'.code.toByte() &&
            data[3] == 'S'.code.toByte()
        ) {
            return "ogg"
        }
        if (
            data[0] == 'f'.code.toByte() &&
            data[1] == 'L'.code.toByte() &&
            data[2] == 'a'.code.toByte() &&
            data[3] == 'C'.code.toByte()
        ) {
            return "flac"
        }
    }
    if (data.size >= 8) {
        if (
            data[4] == 'f'.code.toByte() &&
            data[5] == 't'.code.toByte() &&
            data[6] == 'y'.code.toByte() &&
            data[7] == 'p'.code.toByte()
        ) {
            return "m4a"
        }
    }
    return "mp3"
}

private fun writeLookupAudioBlobToTempFile(
    context: Context,
    bytes: ByteArray,
    extension: String
): File? {
    if (bytes.isEmpty()) return null
    val safeExtension = extension.trim().trimStart('.').ifBlank { "mp3" }
    val dir = File(context.cacheDir, "lookup_audio")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    val output = File(dir, "lookup-${System.currentTimeMillis()}.$safeExtension")
    return runCatching {
        output.outputStream().use { stream ->
            stream.write(bytes)
            stream.flush()
        }
        output
    }.getOrElse {
        runCatching { output.delete() }
        null
    }
}

private fun querySourceUriMeta(context: Context, sourceUri: Uri): String {
    if (sourceUri.scheme.equals("file", ignoreCase = true)) {
        val file = sourceUri.path?.let { File(it) }
        val size = file?.takeIf { it.exists() }?.length() ?: -1L
        val modified = file?.takeIf { it.exists() }?.lastModified() ?: -1L
        return "$size:$modified"
    }

    var size = -1L
    var modified = -1L
    runCatching {
        context.contentResolver.query(
            sourceUri,
            arrayOf(OpenableColumns.SIZE, "last_modified"),
            null,
            null,
            null
        )?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val modifiedIndex = cursor.getColumnIndex("last_modified")
            if (cursor.moveToFirst()) {
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
                if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                    modified = cursor.getLong(modifiedIndex)
                }
            }
        }
    }
    return "$size:$modified"
}

private fun openLookupInputStream(context: Context, sourceUri: Uri): java.io.InputStream? {
    return when (sourceUri.scheme?.lowercase(Locale.ROOT)) {
        "file" -> runCatching {
            val path = sourceUri.path ?: return@runCatching null
            File(path).inputStream()
        }.getOrNull()

        else -> {
            val direct = runCatching {
                context.contentResolver.openInputStream(sourceUri)
            }.getOrNull()
            if (direct != null) return direct
            val pfd = runCatching {
                context.contentResolver.openFileDescriptor(sourceUri, "r")
            }.getOrNull() ?: return null
            ParcelFileDescriptor.AutoCloseInputStream(pfd)
        }
    }
}

private fun synthesizeLookupAudioToTempFile(
    context: Context,
    term: String
): File? {
    val outputFile = createLookupAudioTempFile(context)
    val utteranceId = "lookup-export-${System.currentTimeMillis()}"
    val doneLatch = CountDownLatch(1)
    var started = false
    var success = false
    var ttsRef: TextToSpeech? = null

    val tts = TextToSpeech(context.applicationContext) { initStatus ->
        val current = ttsRef ?: return@TextToSpeech
        if (initStatus != TextToSpeech.SUCCESS) {
            doneLatch.countDown()
            return@TextToSpeech
        }

        val languageResult = current.setLanguage(Locale.JAPANESE)
        if (
            languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            current.setLanguage(Locale.getDefault())
        }
        current.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    success = true
                    doneLatch.countDown()
                }

                override fun onError(utteranceId: String?) {
                    doneLatch.countDown()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    doneLatch.countDown()
                }
            }
        )

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        val result = current.synthesizeToFile(term, params, outputFile, utteranceId)
        started = result != TextToSpeech.ERROR
        if (!started) {
            doneLatch.countDown()
        }
    }
    ttsRef = tts

    val completed = runCatching {
        doneLatch.await(20, TimeUnit.SECONDS)
    }.getOrDefault(false)
    runCatching {
        ttsRef?.stop()
        ttsRef?.shutdown()
    }

    if (!completed || !started || !success || outputFile.length() <= 0L) {
        runCatching { outputFile.delete() }
        return null
    }
    return outputFile
}

private fun stopLookupAudioPlaybackLocked() {
    val cleanup = activeLookupPlayerCleanup
    activeLookupPlayerCleanup = null

    activeLookupPlayer?.let { player ->
        runCatching {
            player.stop()
        }
        runCatching { player.release() }
    }
    activeLookupPlayer = null
    runCatching { cleanup?.invoke() }

    activeLookupTts?.let { tts ->
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }
    activeLookupTts = null
}

private fun clearLookupTts(target: TextToSpeech) {
    synchronized(lookupAudioLock) {
        if (activeLookupTts === target) {
            activeLookupTts = null
        }
    }
    runCatching { target.stop() }
    runCatching { target.shutdown() }
}

private fun postLookupAudioError(onError: (String) -> Unit, message: String) {
    lookupAudioMainHandler.post { onError(message) }
}

private fun createLookupAudioTempFile(context: Context): File {
    val dir = File(context.cacheDir, "lookup_audio")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return File(dir, "lookup-${System.currentTimeMillis()}.wav")
}
