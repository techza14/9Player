package com.tekuza.p9player

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tekuza.p9player.ui.theme.TsetTheme

private enum class CaptureAction {
    PREVIOUS,
    NEXT,
    COLLECT
}

class ControllerSettingsActivity : ComponentActivity() {
    private var captureKeyHandler: ((KeyEvent) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TsetTheme {
                ControllerSettingsScreen(
                    registerCaptureKeyHandler = { handler -> captureKeyHandler = handler },
                    onBack = { finish() }
                )
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (captureKeyHandler?.invoke(event) == true) return true
        return super.dispatchKeyEvent(event)
    }
}

@Composable
private fun ControllerSettingsScreen(
    registerCaptureKeyHandler: (((KeyEvent) -> Boolean)?) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(loadGamepadControlConfig(context)) }
    var captureAction by remember { mutableStateOf<CaptureAction?>(null) }
    var capturedKeyCode by remember { mutableStateOf<Int?>(null) }

    fun reloadConfig() {
        config = loadGamepadControlConfig(context)
    }

    fun selectScheme(next: GamepadControlScheme) {
        saveGamepadControlScheme(context, next)
        reloadConfig()
    }

    fun updateCustom(
        previousKeyCode: Int = config.previousKeyCode,
        nextKeyCode: Int = config.nextKeyCode,
        collectKeyCode: Int = config.collectKeyCode,
        doubleTapCollectPrevious: Boolean = config.doubleTapCollectPrevious
    ) {
        saveCustomGamepadControlConfig(
            context = context,
            previousKeyCode = previousKeyCode,
            nextKeyCode = nextKeyCode,
            collectKeyCode = collectKeyCode,
            doubleTapCollectPrevious = doubleTapCollectPrevious
        )
        reloadConfig()
    }

    fun openCaptureDialog(action: CaptureAction) {
        captureAction = action
        capturedKeyCode = null
    }

    fun closeCaptureDialog() {
        captureAction = null
        capturedKeyCode = null
    }

    fun confirmCapturedKey() {
        val action = captureAction ?: return
        val keyCode = capturedKeyCode ?: return
        when (action) {
            CaptureAction.PREVIOUS -> updateCustom(previousKeyCode = keyCode)
            CaptureAction.NEXT -> updateCustom(nextKeyCode = keyCode)
            CaptureAction.COLLECT -> updateCustom(collectKeyCode = keyCode)
        }
        closeCaptureDialog()
    }

    DisposableEffect(captureAction) {
        if (captureAction == null) {
            registerCaptureKeyHandler(null)
            onDispose { registerCaptureKeyHandler(null) }
        } else {
            registerCaptureKeyHandler { event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@registerCaptureKeyHandler true
                if (event.repeatCount > 0) return@registerCaptureKeyHandler true
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    closeCaptureDialog()
                    return@registerCaptureKeyHandler true
                }
                capturedKeyCode = event.keyCode
                true
            }
            onDispose { registerCaptureKeyHandler(null) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                Text("手柄", style = MaterialTheme.typography.titleLarge)
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectScheme(GamepadControlScheme.SCHEME_1) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("方案 1", style = MaterialTheme.typography.titleMedium)
                        Text("左键=上一句，右键=下一句")
                        Text("X=收藏当前句，双击 X=收藏上一句")
                    }
                    RadioButton(
                        selected = config.scheme == GamepadControlScheme.SCHEME_1,
                        onClick = { selectScheme(GamepadControlScheme.SCHEME_1) }
                    )
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectScheme(GamepadControlScheme.SCHEME_2) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("方案 2", style = MaterialTheme.typography.titleMedium)
                        Text("下键=上一句，上键=下一句")
                        Text("LB=收藏当前句，双击 LB=收藏上一句")
                    }
                    RadioButton(
                        selected = config.scheme == GamepadControlScheme.SCHEME_2,
                        onClick = { selectScheme(GamepadControlScheme.SCHEME_2) }
                    )
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectScheme(GamepadControlScheme.CUSTOM) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("自定义", style = MaterialTheme.typography.titleMedium)
                        Text("自定义按键绑定")
                    }
                    RadioButton(
                        selected = config.scheme == GamepadControlScheme.CUSTOM,
                        onClick = { selectScheme(GamepadControlScheme.CUSTOM) }
                    )
                }
            }

            if (config.scheme == GamepadControlScheme.CUSTOM) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("自定义绑定", style = MaterialTheme.typography.titleMedium)
                        Text("点击绑定后，按下手柄按键，再确认。")

                        SettingRow(
                            label = "上一句",
                            keyLabel = formatGamepadKeyLabel(config.previousKeyCode),
                            onChange = { openCaptureDialog(CaptureAction.PREVIOUS) }
                        )
                        SettingRow(
                            label = "下一句",
                            keyLabel = formatGamepadKeyLabel(config.nextKeyCode),
                            onChange = { openCaptureDialog(CaptureAction.NEXT) }
                        )
                        SettingRow(
                            label = "收藏",
                            keyLabel = formatGamepadKeyLabel(config.collectKeyCode),
                            onChange = { openCaptureDialog(CaptureAction.COLLECT) }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("收藏键双击 = 上一句")
                            Switch(
                                checked = config.doubleTapCollectPrevious,
                                onCheckedChange = { checked -> updateCustom(doubleTapCollectPrevious = checked) }
                            )
                        }
                    }
                }
            }
        }

        val action = captureAction
        if (action != null) {
            val title = when (action) {
                CaptureAction.PREVIOUS -> "绑定按键：上一句"
                CaptureAction.NEXT -> "绑定按键：下一句"
                CaptureAction.COLLECT -> "绑定按键：收藏"
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text("现在按下一个手柄按键")
                        Text("已捕获：${capturedKeyCode?.let(::formatGamepadKeyLabel) ?: "（等待中...）"}")

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { closeCaptureDialog() }) { Text("取消") }
                            TextButton(
                                onClick = { confirmCapturedKey() },
                                enabled = capturedKeyCode != null
                            ) { Text("确认") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    keyLabel: String,
    onChange: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("$label: $keyLabel")
        OutlinedButton(onClick = onChange) { Text("绑定按键") }
    }
}

private fun formatGamepadKeyLabel(keyCode: Int): String {
    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
        KeyEvent.KEYCODE_DPAD_UP -> "Up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
        KeyEvent.KEYCODE_BUTTON_X -> "X"
        KeyEvent.KEYCODE_BUTTON_L1 -> "LB"
        else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").ifBlank { keyCode.toString() }
    }
}

