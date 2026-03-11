package com.tekuza.p9player

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tekuza.p9player.ui.theme.TsetTheme

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
    var inputSeconds by remember { mutableStateOf((config.seekStepMillis / 1000L).toString()) }
    var statusText by remember { mutableStateOf<String?>(null) }
    val overlayGranted = remember(config.floatingOverlayEnabled, statusText) {
        canDrawOverlaysCompat(context)
    }

    fun refreshConfig() {
        config = loadAudiobookSettingsConfig(context)
        inputSeconds = (config.seekStepMillis / 1000L).toString()
    }

    fun updateStep(seconds: Int) {
        val millis = seconds.coerceIn(1, 300) * 1000L
        saveAudiobookSeekStepMillis(context, millis)
        refreshConfig()
        statusText = "已保存：${config.seekStepMillis / 1000L} 秒"
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
                            statusText = if (checked) "已开启悬浮窗功能。" else "已关闭悬浮窗功能。"
                        }
                    )
                }
                if (config.floatingOverlayEnabled) {
                    Text(
                        if (overlayGranted) {
                            "悬浮窗权限：已授权"
                        } else {
                            "悬浮窗权限：未授权"
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

        if (statusText != null) {
            Text(statusText!!)
        }
    }
}

private fun canDrawOverlaysCompat(context: android.content.Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
}

