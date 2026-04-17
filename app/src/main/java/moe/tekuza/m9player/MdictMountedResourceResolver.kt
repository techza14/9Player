package moe.tekuza.m9player

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.util.Locale

private const val MDICT_MOUNTED_SCHEME = "mdictres"

internal fun buildMountedMdictResourceUri(cacheKey: String, rawPath: String): String {
    val safeCacheKey = cacheKey.trim().ifBlank { "mounted" }
    val normalizedPath = rawPath
        .trim()
        .replace('\\', '/')
        .trimStart('/')
    val encodedPath = normalizedPath
        .split('/')
        .filter { it.isNotBlank() }
        .joinToString("/") { Uri.encode(it) }
    return "$MDICT_MOUNTED_SCHEME://$safeCacheKey/$encodedPath"
}

internal data class MountedMdictResource(
    val mimeType: String,
    val inputStream: InputStream
)

internal fun openMountedMdictResource(
    context: Context,
    requestUri: Uri
): MountedMdictResource? {
    if (!requestUri.scheme.equals(MDICT_MOUNTED_SCHEME, ignoreCase = true)) return null
    val cacheKey = requestUri.host.orEmpty().trim()
    if (cacheKey.isBlank()) return null

    val mountState = loadMdxMountState(context)
    if (!mountState.enabled) return null
    val mountEntry = mountState.entries.firstOrNull {
        it.enabled && it.cacheKey.equals(cacheKey, ignoreCase = false)
    } ?: return null
    val treeUri = mountEntry.treeUri.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
    val treeRoot = DocumentFile.fromTreeUri(context, treeUri) ?: return null

    val relativePath = requestUri.encodedPath
        ?.trimStart('/')
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val decodedPath = runCatching { URLDecoder.decode(relativePath, Charsets.UTF_8.name()) }
        .getOrDefault(relativePath)
        .replace('\\', '/')
        .trim('/')
    if (decodedPath.isBlank()) return null
    val relativeDir = mountEntry.relativeDir.trim('/').takeIf { it.isNotBlank() }
    val scopedPath = relativeDir?.let { "$it/$decodedPath" }
    val targetFile = findTreeFileByRelativePath(treeRoot, scopedPath ?: decodedPath)
        ?: if (scopedPath != null) findTreeFileByRelativePath(treeRoot, decodedPath) else null
    val stream = if (targetFile?.isFile == true) {
        context.contentResolver.openInputStream(targetFile.uri)
    } else null
    val resolvedStream = stream ?: run {
        ensureMountedMdictMediaExtracted(context, mountEntry)
        val fallback = findMountedMediaFile(
            mountedMdictMediaDir(context, cacheKey),
            scopedPath ?: decodedPath
        )
        if (!fallback.isFile) return null
        FileInputStream(fallback)
    }
    val mime = guessMimeTypeFromName(targetFile?.name.orEmpty().ifBlank { decodedPath.substringAfterLast('/') })
    return MountedMdictResource(mimeType = mime, inputStream = resolvedStream)
}

internal fun mountedMdictMediaDir(context: Context, cacheKey: String): File {
    val safe = cacheKey.trim().ifBlank { "mounted" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val dir = File(context.cacheDir, "mdx_mount_media/$safe")
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun ensureMountedMdictMediaExtracted(context: Context, mount: MdxMountedEntry) {
    val cacheKey = mount.cacheKey.ifBlank { return }
    val treeUri = mount.treeUri.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return
    val displayName = mount.displayName.ifBlank { return }
    val mediaDir = mountedMdictMediaDir(context, cacheKey)
    val readyFile = File(mediaDir, ".mdd_extracted")
    if (readyFile.isFile) return

    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return
    val expectedMdd = displayName.substringBeforeLast('.') + ".mdd"
    val preferredMddPath = mount.relativeDir.trim('/').takeIf { it.isNotBlank() }?.let { "$it/$expectedMdd" }
    val mddDoc = when {
        preferredMddPath != null -> findTreeFileByRelativePath(root, preferredMddPath)
            ?: findTreeFileByRelativePath(root, expectedMdd)
        else -> findTreeFileByRelativePath(root, expectedMdd)
    } ?: return
    val tempMdd = File.createTempFile("mounted_mdd_lazy_", ".mdd", context.cacheDir)
    try {
        context.contentResolver.openInputStream(mddDoc.uri)?.use { input ->
            FileOutputStream(tempMdd).use { output -> input.copyTo(output) }
        } ?: return
        val result = MdictNativeBridge.extractMdd(tempMdd.absolutePath, mediaDir.absolutePath)
        if (result.success && result.mediaCount > 0) {
            runCatching { readyFile.writeText(result.mediaCount.toString()) }
        }
    } finally {
        runCatching { tempMdd.delete() }
    }
}

private fun findTreeFileByRelativePath(root: DocumentFile, relativePath: String): DocumentFile? {
    val candidates = buildPathVariants(relativePath)
    candidates.forEach { candidate ->
        val parts = candidate.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return@forEach
        var current: DocumentFile? = root
        for (part in parts) {
            val next = current?.findFile(part)
            if (next != null) {
                current = next
                continue
            }
            current = current
                ?.listFiles()
                ?.firstOrNull { it.name.equals(part, ignoreCase = true) }
                ?: run {
                    current = null
                    break
                }
        }
        if (current?.isFile == true) return current
    }
    return null
}

private fun findMountedMediaFile(root: File, relativePath: String): File {
    val variants = buildPathVariants(relativePath)
    variants.forEach { variant ->
        val direct = File(root, variant)
        if (direct.isFile) return direct
        val parent = direct.parentFile
        if (parent?.isDirectory == true) {
            val byCaseInsensitive = parent.listFiles()?.firstOrNull {
                it.name.equals(direct.name, ignoreCase = true)
            }
            if (byCaseInsensitive?.isFile == true) return byCaseInsensitive
        }
    }
    val baseName = relativePath.substringAfterLast('/').substringAfterLast('\\')
    if (baseName.isNotBlank() && root.isDirectory) {
        root.walkTopDown().forEach { file ->
            if (file.isFile && file.name.equals(baseName, ignoreCase = true)) return file
        }
    }
    return File(root, relativePath)
}

private fun buildPathVariants(raw: String): List<String> {
    val decoded = runCatching { URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrDefault(raw)
    val normalized = decoded.replace('\\', '/').trim()
    val trimmed = normalized.trimStart('/')
    val baseName = trimmed.substringAfterLast('/')
    return linkedSetOf(
        normalized,
        trimmed,
        "/$trimmed",
        baseName
    ).filter { it.isNotBlank() }.toList()
}

private fun guessMimeTypeFromName(name: String): String {
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
