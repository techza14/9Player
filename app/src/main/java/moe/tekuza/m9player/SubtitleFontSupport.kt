package moe.tekuza.m9player

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private object SubtitleFontCache {
    private val cache = ConcurrentHashMap<String, Typeface?>()

    fun getOrLoad(context: Context, uri: Uri?): Typeface? {
        val key = uri?.toString()?.trim().orEmpty()
        if (key.isBlank()) return null
        return cache.getOrPut(key) { loadTypefaceInternal(context, uri) }
    }

    private fun loadTypefaceInternal(context: Context, uri: Uri?): Typeface? {
        val safeUri = uri ?: return null
        return runCatching {
            context.contentResolver.openInputStream(safeUri)?.use { input ->
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

internal object SubtitleFontUiRefreshTicker {
    var version: Int by mutableIntStateOf(0)
        private set

    fun bump() {
        version += 1
    }
}
