package moe.tekuza.m9player

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.InputStream
import java.net.URLDecoder
import java.util.Locale
import java.util.zip.ZipInputStream

private const val DICT_BUNDLED_RES_SCHEME = "dictres"

internal data class BundledDictionaryResource(
    val mimeType: String,
    val inputStream: InputStream
)

internal fun buildBundledDictionaryResourceUri(cacheKey: String, rawPath: String): String {
    val safeCacheKey = cacheKey.trim().ifBlank { "dict" }
    val normalizedPath = rawPath
        .trim()
        .replace('\\', '/')
        .trimStart('/')
    val encodedPath = normalizedPath
        .split('/')
        .filter { it.isNotBlank() }
        .joinToString("/") { Uri.encode(it) }
    return "$DICT_BUNDLED_RES_SCHEME://$safeCacheKey/$encodedPath"
}

internal fun openBundledDictionaryResource(
    context: Context,
    requestUri: Uri
): BundledDictionaryResource? {
    if (!requestUri.scheme.equals(DICT_BUNDLED_RES_SCHEME, ignoreCase = true)) return null
    val cacheKey = requestUri.host.orEmpty().trim()
    if (cacheKey.isBlank()) return null
    val sourceUriRaw = lookupDictionarySourceUriByCacheKey(context, cacheKey) ?: return null
    val sourceUri = runCatching { Uri.parse(sourceUriRaw) }.getOrNull() ?: return null
    val relativePath = requestUri.encodedPath
        ?.trimStart('/')
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val decodedPath = runCatching { URLDecoder.decode(relativePath, Charsets.UTF_8.name()) }
        .getOrDefault(relativePath)
        .replace('\\', '/')
        .trim('/')
    if (decodedPath.isBlank()) return null

    val variants = linkedSetOf<String>().apply {
        add(decodedPath)
        add(decodedPath.trimStart('/'))
        add(decodedPath.removePrefix("./"))
        add(decodedPath.substringAfterLast('/'))
    }.filter { it.isNotBlank() }

    val stream = context.contentResolver.openInputStream(sourceUri) ?: return null
    val zip = ZipInputStream(stream.buffered())
    var foundEntryName: String? = null
    try {
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val name = entry.name.replace('\\', '/')
            val matched = variants.any { candidate ->
                name.equals(candidate, ignoreCase = true) ||
                    name.endsWith("/$candidate", ignoreCase = true)
            }
            if (matched) {
                foundEntryName = name
                break
            }
        }
        if (foundEntryName == null) {
            zip.close()
            return null
        }
        val mime = guessBundledResourceMime(foundEntryName!!)
        return BundledDictionaryResource(
            mimeType = mime,
            inputStream = zip
        )
    } catch (_: Throwable) {
        runCatching { zip.close() }
        return null
    }
}

private fun guessBundledResourceMime(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (ext.isBlank()) return "application/octet-stream"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: when (ext) {
            "css" -> "text/css"
            "svg" -> "image/svg+xml"
            "js" -> "application/javascript"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }
}

