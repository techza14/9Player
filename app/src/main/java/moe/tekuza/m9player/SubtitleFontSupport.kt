package moe.tekuza.m9player

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap

private const val SUBTITLE_FONT_DIR = "fonts"
private const val SUBTITLE_CUSTOM_FONT_FILE = "subtitle-custom-font.ttf"

private object SubtitleFontCache {
    private val cache = ConcurrentHashMap<String, Typeface?>()

    fun getOrLoad(context: Context, uri: Uri?): Typeface? {
        val key = uri?.toString()?.trim().orEmpty()
        if (key.isBlank()) return null
        return cache.getOrPut(key) { loadTypefaceInternal(context, uri) }
    }

    fun clear(uri: Uri? = null) {
        val key = uri?.toString()?.trim().orEmpty()
        if (key.isBlank()) {
            cache.clear()
        } else {
            cache.remove(key)
        }
    }

    private fun loadTypefaceInternal(context: Context, uri: Uri?): Typeface? {
        val safeUri = uri ?: return null
        return runCatching {
            openSubtitleFontInputStream(context, safeUri)?.use { input ->
                val tempFile = File.createTempFile("subtitle-font-", ".tmp", context.cacheDir)
                tempFile.outputStream().use { output -> input.copyTo(output) }
                try {
                    Typeface.createFromFile(tempFile)
                } finally {
                    runCatching { tempFile.delete() }
                }
            }
        }.getOrNull()
    }

}

internal fun resolveSubtitleTypeface(context: Context, uri: Uri?): Typeface? {
    return SubtitleFontCache.getOrLoad(context, uri)
}

internal fun importSubtitleCustomFontToPrivateStorage(context: Context, sourceUri: Uri): Uri? {
    return runCatching {
        val fontDir = File(context.filesDir, SUBTITLE_FONT_DIR).apply { mkdirs() }
        val targetFile = File(fontDir, SUBTITLE_CUSTOM_FONT_FILE)
        val tempFile = File.createTempFile("subtitle-custom-font-", ".tmp", fontDir)
        try {
            openSubtitleFontInputStream(context, sourceUri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null
            Typeface.createFromFile(tempFile)
            tempFile.copyTo(targetFile, overwrite = true)
            Uri.fromFile(targetFile).also { SubtitleFontCache.clear(it) }
        } finally {
            runCatching { tempFile.delete() }
        }
    }.getOrNull()
}

internal fun deleteImportedSubtitleCustomFont(context: Context) {
    runCatching {
        File(File(context.filesDir, SUBTITLE_FONT_DIR), SUBTITLE_CUSTOM_FONT_FILE).delete()
    }
    SubtitleFontCache.clear()
}

private fun openSubtitleFontInputStream(context: Context, uri: Uri) =
    if (uri.scheme.equals("file", ignoreCase = true)) {
        uri.path?.let { FileInputStream(File(it)) }
    } else {
        context.contentResolver.openInputStream(uri)
    }

internal object SubtitleFontUiRefreshTicker {
    var version: Int by mutableIntStateOf(0)
        private set

    fun bump() {
        version += 1
    }
}
