package moe.tekuza.m9player

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private const val GITHUB_LATEST_RELEASE_URL = "https://api.github.com/repos/techza14/9Player/releases/latest"
private const val UPDATE_APK_CACHE_DIR = "update_apk"

internal data class AppUpdateRelease(
    val tagName: String,
    val displayName: String,
    val pageUrl: String,
    val apkName: String,
    val apkUrl: String
)

internal sealed interface AppUpdateCheckResult {
    data class UpdateAvailable(val release: AppUpdateRelease) : AppUpdateCheckResult
    data class UpToDate(val latestVersion: String) : AppUpdateCheckResult
    data class NoApkAsset(val latestVersion: String, val pageUrl: String) : AppUpdateCheckResult
    data class Failed(val message: String) : AppUpdateCheckResult
}

internal suspend fun checkLatestAppUpdate(context: Context): AppUpdateCheckResult = withContext(Dispatchers.IO) {
    runCatching {
        cleanupInstalledOrOldUpdateApks(context)
        val connection = (URL(GITHUB_LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "9Player/${resolveAppVersionName(context)}")
        }
        connection.inputStream.bufferedReader().use { reader ->
            val json = JSONObject(reader.readText())
            val tagName = json.optString("tag_name").ifBlank { json.optString("name") }
            val displayName = json.optString("name").ifBlank { tagName }
            val pageUrl = json.optString("html_url")
            val assets = json.optJSONArray("assets")
            var apkName = ""
            var apkUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    val url = asset.optString("browser_download_url")
                    if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                        apkName = name
                        apkUrl = url
                        break
                    }
                }
            }
            val currentVersion = resolveAppVersionName(context)
            if (compareAppVersions(tagName, currentVersion) <= 0) {
                AppUpdateCheckResult.UpToDate(tagName.ifBlank { currentVersion })
            } else if (apkUrl.isBlank()) {
                AppUpdateCheckResult.NoApkAsset(tagName, pageUrl)
            } else {
                AppUpdateCheckResult.UpdateAvailable(
                    AppUpdateRelease(
                        tagName = tagName,
                        displayName = displayName,
                        pageUrl = pageUrl,
                        apkName = apkName,
                        apkUrl = apkUrl
                    )
                )
            }
        }
    }.getOrElse { error ->
        AppUpdateCheckResult.Failed(error.message ?: error.javaClass.simpleName)
    }
}

internal suspend fun downloadAppUpdateApk(
    context: Context,
    release: AppUpdateRelease,
    onProgress: (Float?) -> Unit
): Result<File> = withContext(Dispatchers.IO) {
    runCatching {
        val updateDir = updateApkCacheDir(context)
        val outputFile = File(updateDir, updateApkFileName(release))
        cleanupUpdateApksExcept(updateDir, outputFile)
        if (outputFile.exists()) outputFile.delete()
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "9Player/${resolveAppVersionName(context)}")
        }
        val total = connection.contentLengthLong.takeIf { it > 0L }
        connection.inputStream.use { input ->
            outputFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    onProgress(total?.let { (copied.toFloat() / it.toFloat()).coerceIn(0f, 1f) })
                }
            }
        }
        outputFile
    }
}

internal fun launchAppUpdateInstall(context: Context, apkFile: File): Boolean {
    if (!apkFile.isFile) return false
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
    }
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

private fun cleanupInstalledOrOldUpdateApks(context: Context) {
    cleanupInstalledOrOldUpdateApks(
        updateDir = updateApkCacheDir(context),
        currentVersion = resolveAppVersionName(context)
    )
}

internal fun cleanupInstalledOrOldUpdateApks(updateDir: File, currentVersion: String) {
    updateDir.listFiles { file ->
        file.isFile && file.extension.equals("apk", ignoreCase = true)
    }?.forEach { file ->
        val apkVersion = file.name.extractUpdateApkVersion() ?: return@forEach
        if (compareAppVersions(apkVersion, currentVersion) <= 0) {
            file.delete()
        }
    }
}

internal fun cleanupUpdateApksExcept(updateDir: File, keepFile: File) {
    updateDir.mkdirs()
    val keepPath = keepFile.toPath().toAbsolutePath().normalize().toString()
    updateDir.listFiles { file ->
        file.isFile && file.extension.equals("apk", ignoreCase = true)
    }?.forEach { file ->
        if (file.toPath().toAbsolutePath().normalize().toString() != keepPath) {
            file.delete()
        }
    }
}

private fun updateApkCacheDir(context: Context): File {
    return File(context.cacheDir, UPDATE_APK_CACHE_DIR).apply { mkdirs() }
}

private fun String.extractUpdateApkVersion(): String? {
    return Regex("""(?:^|[-_])v?(\d+(?:\.\d+)+)(?:[-_][^.]*)?\.apk$""", RegexOption.IGNORE_CASE)
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
}

private fun updateApkFileName(release: AppUpdateRelease): String {
    val releaseName = release.apkName.sanitizeFileName()
    if (releaseName.extractUpdateApkVersion() != null) return releaseName
    val tagName = release.tagName.sanitizeFileName().ifBlank { "update" }
    return "9player-$tagName.apk"
}

private fun compareAppVersions(leftRaw: String, rightRaw: String): Int {
    val left = leftRaw.toVersionParts()
    val right = rightRaw.toVersionParts()
    val max = maxOf(left.size, right.size)
    for (i in 0 until max) {
        val diff = (left.getOrNull(i) ?: 0) - (right.getOrNull(i) ?: 0)
        if (diff != 0) return diff
    }
    return 0
}

private fun String.toVersionParts(): List<Int> {
    return trim()
        .lowercase(Locale.US)
        .removePrefix("v")
        .split('.', '-', '_')
        .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }
}

private fun String.sanitizeFileName(): String {
    return replace(Regex("""[\\/:*?"<>|]"""), "_")
}
