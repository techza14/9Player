package com.tekuza.p9player

import android.os.Bundle
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

    fun updateStep(seconds: Int) {
        val millis = seconds.coerceIn(1, 300) * 1000L
        saveAudiobookSeekStepMillis(context, millis)
        config = loadAudiobookSettingsConfig(context)
        inputSeconds = (config.seekStepMillis / 1000L).toString()
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
                            statusText = "请输入 1 到 300 之间的秒数。"
                        } else {
                            updateStep(seconds)
                        }
                    }
                ) {
                    Text("保存")
                }

                Text("阅读器里切到“按时长”模式后，会按这里的秒数前进或后退。")
                if (statusText != null) {
                    Text(statusText!!)
                }
            }
        }
    }
}
