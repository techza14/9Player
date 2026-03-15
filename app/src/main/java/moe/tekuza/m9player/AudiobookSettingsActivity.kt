package moe.tekuza.m9player

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.tekuza.m9player.ui.theme.TsetTheme

class AudiobookSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TsetTheme {
                AudiobookSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun AudiobookSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(loadAudiobookSettingsConfig(context)) }
    var importState by remember { mutableStateOf(loadPersistedImports(context)) }
    var inputSeconds by remember { mutableStateOf((config.seekStepMillis / 1000L).toString()) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var importGuideVisible by remember { mutableStateOf(!importState.importOnboardingCompleted) }
    var lookupAudioImporting by remember { mutableStateOf(false) }
    var lookupAudioImportStage by remember { mutableStateOf("准备中...") }
    var lookupAudioImportCopiedBytes by remember { mutableStateOf(0L) }
    var lookupAudioImportTotalBytes by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val overlayGranted = remember(config.floatingOverlayEnabled, statusText) {
        canDrawOverlaysCompat(context)
    }

    val refreshConfig = {
        config = loadAudiobookSettingsConfig(context)
        importState = loadPersistedImports(context)
        inputSeconds = (config.seekStepMillis / 1000L).toString()
    }
    val pickLookupAudioLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) {
                statusText = "未选择文件。"
                Toast.makeText(context, "未选择文件", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            persistLookupAudioReadPermission(context, uri)
            statusText = "正在导入 android.db..."
            Toast.makeText(context, "正在导入 android.db...", Toast.LENGTH_SHORT).show()
            scope.launch {
                lookupAudioImporting = true
                lookupAudioImportStage = "准备导入..."
                lookupAudioImportCopiedBytes = 0L
                lookupAudioImportTotalBytes = null
                try {
                    val imported = withContext(Dispatchers.IO) {
                        importLookupAudioDatabase(
                            context = context,
                            sourceUri = uri,
                            onStageChanged = { stage ->
                                scope.launch(Dispatchers.Main) {
                                    lookupAudioImportStage = stage
                                }
                            },
                            onCopyProgress = { copiedBytes, totalBytes ->
                                scope.launch(Dispatchers.Main) {
                                    lookupAudioImportCopiedBytes = copiedBytes
                                    lookupAudioImportTotalBytes = totalBytes
                                }
                            }
                        )
                    }
                    if (imported != null) {
                        saveLookupLocalAudioUri(context, imported)
                        refreshConfig()
                        val selectedName = queryLookupAudioDisplayName(context, imported)
                        statusText = "已导入 android.db：$selectedName"
                        Toast.makeText(context, "已导入 android.db", Toast.LENGTH_SHORT).show()
                    } else {
                        statusText = "导入失败：请导入有效的 android.db（含 entries/android 表）"
                        Toast.makeText(
                            context,
                            "导入失败：请确认文件是有效 android.db",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } finally {
                    lookupAudioImporting = false
                }
            }
        }

    fun updateStep(seconds: Int) {
        val millis = seconds.coerceIn(1, 300) * 1000L
        saveAudiobookSeekStepMillis(context, millis)
        refreshConfig()
        statusText = "已保存：${config.seekStepMillis / 1000L} 秒"
    }
    fun updateAutoMove(enabled: Boolean) {
        savePersistedImports(
            context,
            importState.copy(
                autoMoveToAudiobookFolder = enabled,
                importOnboardingCompleted = true
            )
        )
        refreshConfig()
        statusText = if (enabled) {
            "已设置：自动移动到有声书文件夹"
        } else {
            "已设置：保留原文件位置"
        }
    }

    if (importGuideVisible) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("导入方式") },
            text = {
                Text("首次使用：是否自动将书籍移动到有声书文件夹？之后可在此页随时修改。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        updateAutoMove(true)
                        importGuideVisible = false
                    }
                ) {
                    Text("自动移动")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        updateAutoMove(false)
                        importGuideVisible = false
                    }
                ) {
                    Text("不自动移动")
                }
            }
        )
    }

    if (lookupAudioImporting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("导入 android.db") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(lookupAudioImportStage)
                    val totalBytes = lookupAudioImportTotalBytes
                    if (totalBytes != null && totalBytes > 0L) {
                        val progress = (lookupAudioImportCopiedBytes.toFloat() / totalBytes.toFloat())
                            .coerceIn(0f, 1f)
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("${formatBytes(lookupAudioImportCopiedBytes)} / ${formatBytes(totalBytes)}")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(formatBytes(lookupAudioImportCopiedBytes))
                    }
                }
            },
            confirmButton = {}
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("< 返回") }
            Text("有声书", style = MaterialTheme.typography.titleLarge)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("前进后退时长", style = MaterialTheme.typography.titleMedium)
                Text("当前：${config.seekStepMillis / 1000L} 秒")

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30).forEach { seconds ->
                        OutlinedButton(onClick = { updateStep(seconds) }) {
                            Text("${seconds}s")
                        }
                    }
                }

                OutlinedTextField(
                    value = inputSeconds,
                    onValueChange = { value ->
                        inputSeconds = value.filter { it.isDigit() }.take(3)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("自定义秒数") },
                    singleLine = true
                )

                Button(
                    onClick = {
                        val seconds = inputSeconds.toIntOrNull()
                        if (seconds == null || seconds <= 0) {
                            statusText = "请输入 1 到 300 的秒数。"
                        } else {
                            updateStep(seconds)
                        }
                    }
                ) {
                    Text("保存")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("导入模式", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (importState.autoMoveToAudiobookFolder) {
                        "当前：自动移动到有声书文件夹"
                    } else {
                        "当前：保留原文件位置"
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { updateAutoMove(true) }) {
                        Text("自动移动")
                    }
                    OutlinedButton(onClick = { updateAutoMove(false) }) {
                        Text("不自动移动")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("查词", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("点击查词时停止播放")
                    Switch(
                        checked = config.pausePlaybackOnLookup,
                        onCheckedChange = { checked ->
                            saveAudiobookPausePlaybackOnLookup(context, checked)
                            refreshConfig()
                            statusText = if (checked) {
                                "已开启：点击查词时停止播放。"
                            } else {
                                "已关闭：点击查词时停止播放。"
                            }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("播放音频")
                    Switch(
                        checked = config.lookupPlaybackAudioEnabled,
                        onCheckedChange = { checked ->
                            saveLookupPlaybackAudioEnabled(context, checked)
                            refreshConfig()
                            statusText = if (checked) {
                                "已开启：查词时可播放音频。"
                            } else {
                                "已关闭：查词时播放音频。"
                            }
                        }
                    )
                }
                if (config.lookupPlaybackAudioEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("查词结果自动播放")
                        Switch(
                            checked = config.lookupPlaybackAudioAutoPlay,
                            onCheckedChange = { checked ->
                                saveLookupPlaybackAudioAutoPlay(context, checked)
                                refreshConfig()
                                statusText = if (checked) {
                                    "已开启：查词结果自动播放。"
                                } else {
                                    "已关闭：查词结果自动播放。"
                                }
                            }
                        )
                    }
                    Text("查词音频来源")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                saveLookupAudioMode(context, LookupAudioMode.LOCAL_TTS)
                                refreshConfig()
                                statusText = "已切换：本地 TTS。"
                            }
                        ) {
                            Text("本地 TTS")
                        }
                        OutlinedButton(
                            onClick = {
                                saveLookupAudioMode(context, LookupAudioMode.LOCAL_AUDIO)
                                refreshConfig()
                                statusText = "已切换：本地音频。"
                            }
                        ) {
                            Text("本地音频")
                        }
                    }
                    Text(
                    if (config.lookupAudioMode == LookupAudioMode.LOCAL_TTS) {
                        "当前：本地 TTS"
                    } else {
                        "当前：android.db"
                    }
                )
                if (config.lookupAudioMode == LookupAudioMode.LOCAL_AUDIO) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                pickLookupAudioLauncher.launch("*/*")
                            },
                            enabled = !lookupAudioImporting
                        ) {
                            Text("导入 android.db")
                        }
                            OutlinedButton(
                                onClick = {
                                    deleteImportedLookupAudioDatabaseIfAny(context, config.lookupLocalAudioUri)
                                    saveLookupLocalAudioUri(context, null)
                                    refreshConfig()
                                    statusText = "已清除 android.db。"
                            },
                                enabled = !lookupAudioImporting
                        ) {
                            Text("清除")
                        }
                    }
                        val selectedLookupAudioName = config.lookupLocalAudioUri?.let { uri ->
                            queryLookupAudioDisplayName(context, uri)
                        }
                        Text(
                            selectedLookupAudioName?.let { "当前数据库：$it" } ?: "当前数据库：未选择"
                        )
                    }
                }
                Text("正在播放的词显示位置")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            saveAudiobookActiveCueDisplayAtTop(context, false)
                            refreshConfig()
                            statusText = "已设置：正在播放的词显示在中间。"
                        }
                    ) {
                        Text("中间")
                    }
                    OutlinedButton(
                        onClick = {
                            saveAudiobookActiveCueDisplayAtTop(context, true)
                            refreshConfig()
                            statusText = "已设置：正在播放的词显示在上方。"
                        }
                    ) {
                        Text("上方")
                    }
                }
                Text(
                    if (config.activeCueDisplayAtTop) {
                        "当前：上方"
                    } else {
                        "当前：中间"
                    }
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("悬浮窗", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("退出 App 后显示播放悬浮球")
                    Switch(
                        checked = config.floatingOverlayEnabled,
                        onCheckedChange = { checked ->
                            saveAudiobookFloatingOverlayEnabled(context, checked)
                            refreshConfig()
                            statusText = if (checked) {
                                "已开启悬浮窗功能。"
                            } else {
                                "已关闭悬浮窗功能。"
                            }
                        }
                    )
                }
                if (config.floatingOverlayEnabled) {
                    Text(
                        if (overlayGranted) {
                            "悬浮窗权限：已授予"
                        } else {
                            "悬浮窗权限：未授予"
                        }
                    )
                    if (!overlayGranted) {
                        Button(
                            onClick = {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                        ) {
                            Text("授予悬浮窗权限")
                        }
                    }
                    Text("悬浮球：单击播放/暂停，双击展开前进/后退/收藏。")
                }
            }
        }

        statusText?.let {
            Text(it)
        }
    }
}

private fun canDrawOverlaysCompat(context: android.content.Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
}

private fun persistLookupAudioReadPermission(context: android.content.Context, uri: Uri) {
    val resolver = context.contentResolver
    val readWriteFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    try {
        resolver.takePersistableUriPermission(uri, readWriteFlags)
        return
    } catch (_: SecurityException) {
        // Ignore and fall back to read-only permission.
    }
    try {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: SecurityException) {
        // Some providers do not support persistable permission.
    }
}

private fun queryLookupAudioDisplayName(context: android.content.Context, uri: Uri): String {
    if (uri.scheme.equals("file", ignoreCase = true)) {
        val name = uri.path.orEmpty().substringAfterLast('/')
        if (name.isNotBlank()) return name
    }
    val fromQuery = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)
            } else {
                null
            }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
    return fromQuery ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "未知文件" }
}

private fun importLookupAudioDatabase(
    context: android.content.Context,
    sourceUri: Uri,
    onStageChanged: ((String) -> Unit)? = null,
    onCopyProgress: ((copiedBytes: Long, totalBytes: Long?) -> Unit)? = null
): Uri? {
    val dir = File(context.filesDir, "lookup_audio_db")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    val target = File(dir, "android.db")
    val temp = File(dir, "android.db.tmp")
    val totalBytes = queryLookupAudioSourceSize(context, sourceUri)

    val copied = runCatching {
        onStageChanged?.invoke("复制文件...")
        var copiedBytes = 0L
        var lastProgressEmitAt = 0L
        onCopyProgress?.invoke(0L, totalBytes)
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copiedBytes += read
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgressEmitAt >= 120L) {
                        onCopyProgress?.invoke(copiedBytes, totalBytes)
                        lastProgressEmitAt = now
                    }
                }
                output.flush()
            }
        } ?: return null
        onCopyProgress?.invoke(copiedBytes, totalBytes)
        if (temp.length() <= 0L) {
            runCatching { temp.delete() }
            return null
        }
        true
    }.getOrDefault(false)
    if (!copied) return null

    onStageChanged?.invoke("校验数据库...")
    val valid = runCatching {
        SQLiteDatabase.openDatabase(temp.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            var hasEntries = false
            var hasAndroid = false
            db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('entries','android')",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    when (cursor.getString(0)?.trim()?.lowercase()) {
                        "entries" -> hasEntries = true
                        "android" -> hasAndroid = true
                    }
                }
            }
            hasEntries && hasAndroid
        }
    }.getOrDefault(false)

    if (!valid) {
        runCatching { temp.delete() }
        return null
    }

    onStageChanged?.invoke("写入目标文件...")
    runCatching { if (target.exists()) target.delete() }
    val moved = runCatching { temp.renameTo(target) }.getOrDefault(false)
    if (!moved) {
        val copiedFallback = runCatching {
            temp.inputStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        }.getOrDefault(false)
        runCatching { temp.delete() }
        if (!copiedFallback) {
            runCatching { target.delete() }
            return null
        }
    }
    onStageChanged?.invoke("导入完成")
    return Uri.fromFile(target)
}

private fun queryLookupAudioSourceSize(context: android.content.Context, uri: Uri): Long? {
    if (uri.scheme.equals("file", ignoreCase = true)) {
        val path = uri.path.orEmpty()
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.exists()) return null
        return file.length().takeIf { it > 0L }
    }
    val result = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                cursor.getLong(index)
            } else {
                null
            }
        }
    }.getOrNull()
    return result?.takeIf { it > 0L }
}

private fun formatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        safe >= gb -> String.format("%.2f GB", safe / gb)
        safe >= mb -> String.format("%.2f MB", safe / mb)
        safe >= kb -> String.format("%.2f KB", safe / kb)
        else -> "$safe B"
    }
}

private fun deleteImportedLookupAudioDatabaseIfAny(context: android.content.Context, uri: Uri?) {
    val target = uri ?: return
    if (!target.scheme.equals("file", ignoreCase = true)) return
    val path = target.path ?: return
    val importedDir = File(context.filesDir, "lookup_audio_db").absolutePath
    if (!path.startsWith(importedDir, ignoreCase = true)) return
    runCatching { File(path).delete() }
}
