package moe.tekuza.m9player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File

internal fun buildReaderSourceStamp(context: Context, uri: Uri): String {
    val scheme = uri.scheme?.lowercase().orEmpty()
    val fileStamp = if (scheme == "file") {
        uri.path
            ?.let(::File)
            ?.takeIf { it.exists() }
            ?.let { file -> "file:${file.length()}:${file.lastModified()}" }
    } else {
        null
    }
    if (fileStamp != null) return "${uri}|$fileStamp"

    val documentStamp = runCatching {
        DocumentFile.fromSingleUri(context, uri)?.let { doc ->
            "doc:${doc.length()}:${doc.lastModified()}"
        }
    }.getOrNull()
    if (!documentStamp.isNullOrBlank()) return "${uri}|$documentStamp"

    val queryStamp = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L
            val name = if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
            "query:$size:$name"
        }
    }.getOrNull()
    return "${uri}|${queryStamp ?: "unknown"}"
}
