package moe.tekuza.m9player

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

private const val DICT_CACHE_VERSION = 8
private const val CACHE_DIR_NAME = "dict_cache"
private const val CACHE_FILE_SUFFIX = ".bin.gz"

internal fun buildDictionaryCacheKey(uri: String, displayName: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$uri|$displayName".toByteArray(Charsets.UTF_8))
    return digest.take(16).joinToString("") { b -> "%02x".format(Locale.US, b) }
}

internal fun saveDictionaryCache(
    context: Context,
    cacheKey: String,
    dictionary: LoadedDictionary
): Boolean {
    return runCatching {
        val outFile = dictionaryCacheFile(context, cacheKey)
        outFile.parentFile?.mkdirs()

        DataOutputStream(
            BufferedOutputStream(
                GZIPOutputStream(outFile.outputStream())
            )
        ).use { out ->
            out.writeInt(DICT_CACHE_VERSION)
            out.writeUTF(dictionary.name)
            out.writeUTF(dictionary.format)
            out.writeBoolean(!dictionary.stylesCss.isNullOrBlank())
            if (!dictionary.stylesCss.isNullOrBlank()) out.writeUTF(dictionary.stylesCss)
            out.writeInt(dictionary.entries.size)

            dictionary.entries.forEach { entry ->
                out.writeUTF(entry.term)

                out.writeBoolean(entry.reading != null)
                if (entry.reading != null) out.writeUTF(entry.reading)

                out.writeInt(entry.definitions.size)
                entry.definitions.forEach(out::writeUTF)

                out.writeBoolean(entry.pitch != null)
                if (entry.pitch != null) out.writeUTF(entry.pitch)

                out.writeBoolean(entry.frequency != null)
                if (entry.frequency != null) out.writeUTF(entry.frequency)

                out.writeUTF(entry.dictionary)
            }
            out.flush()
        }
    }.isSuccess
}

internal fun loadDictionaryCache(context: Context, cacheKey: String): LoadedDictionary? {
    val inFile = dictionaryCacheFile(context, cacheKey)
    if (!inFile.exists() || inFile.length() <= 0L) return null

    return runCatching {
        DataInputStream(
            BufferedInputStream(
                GZIPInputStream(inFile.inputStream())
            )
        ).use { input ->
            val version = input.readInt()
            if (version != DICT_CACHE_VERSION) return null

            val name = input.readUTF()
            val format = input.readUTF()
            val stylesCss = if (input.readBoolean()) input.readUTF() else null
            val count = input.readInt().coerceAtLeast(0)

            val entries = ArrayList<DictionaryEntry>(count)
            repeat(count) {
                val term = input.readUTF()

                val reading = if (input.readBoolean()) input.readUTF() else null

                val defCount = input.readInt().coerceAtLeast(0)
                val definitions = ArrayList<String>(defCount)
                repeat(defCount) { definitions += input.readUTF() }

                val pitch = if (input.readBoolean()) input.readUTF() else null
                val frequency = if (input.readBoolean()) input.readUTF() else null
                val dictionaryName = input.readUTF()

                entries += DictionaryEntry(
                    term = term,
                    reading = reading,
                    definitions = definitions,
                    pitch = pitch,
                    frequency = frequency,
                    dictionary = dictionaryName
                )
            }

            LoadedDictionary(
                name = name,
                format = format,
                entries = entries,
                stylesCss = stylesCss
            )
        }
    }.getOrNull()
}

internal fun deleteDictionaryCache(context: Context, cacheKey: String): Boolean {
    val file = dictionaryCacheFile(context, cacheKey)
    return !file.exists() || file.delete()
}

private fun dictionaryCacheFile(context: Context, cacheKey: String): File {
    return File(File(context.filesDir, CACHE_DIR_NAME), "$cacheKey$CACHE_FILE_SUFFIX")
}

