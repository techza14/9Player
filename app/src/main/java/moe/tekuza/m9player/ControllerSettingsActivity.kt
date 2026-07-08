package moe.tekuza.m9player

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import moe.tekuza.m9player.ui.theme.TsetTheme

private enum class CaptureAction {
    PREVIOUS,
    NEXT,
    COLLECT
}

private data class GamepadHint(
    val keyCode: Int,
    val token: Long = System.currentTimeMillis()
)

class ControllerSettingsActivity : AppCompatActivity() {
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
    var gamepadHint by remember { mutableStateOf<GamepadHint?>(null) }

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
        registerCaptureKeyHandler { event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@registerCaptureKeyHandler captureAction != null
            if (event.repeatCount > 0) return@registerCaptureKeyHandler captureAction != null
            if (captureAction != null) {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    closeCaptureDialog()
                    return@registerCaptureKeyHandler true
                }
                capturedKeyCode = event.keyCode
                return@registerCaptureKeyHandler true
            }
            if (isPreviewableGamepadKey(event.keyCode)) {
                gamepadHint = GamepadHint(event.keyCode)
                return@registerCaptureKeyHandler true
            }
            false
        }
        onDispose { registerCaptureKeyHandler(null) }
    }

    LaunchedEffect(gamepadHint?.token) {
        if (gamepadHint == null) return@LaunchedEffect
        delay(1000)
        gamepadHint = null
    }

    SettingsScaffold(
        title = stringResource(R.string.controller_title),
        onBack = onBack
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectScheme(GamepadControlScheme.SCHEME_1) },
                colors = CardDefaults.cardColors(containerColor = hoshiCardBackgroundColor())
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.controller_scheme_1_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.controller_scheme_1_desc_1))
                        Text(stringResource(R.string.controller_scheme_1_desc_2))
                        Text(stringResource(R.string.controller_scheme_1_desc_3))
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
                    .clickable { selectScheme(GamepadControlScheme.SCHEME_2) },
                colors = CardDefaults.cardColors(containerColor = hoshiCardBackgroundColor())
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.controller_scheme_2_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.controller_scheme_2_desc_1))
                        Text(stringResource(R.string.controller_scheme_2_desc_2))
                        Text(stringResource(R.string.controller_scheme_2_desc_3))
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
                    .clickable { selectScheme(GamepadControlScheme.CUSTOM) },
                colors = CardDefaults.cardColors(containerColor = hoshiCardBackgroundColor())
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.controller_custom_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.controller_custom_desc))
                    }
                    RadioButton(
                        selected = config.scheme == GamepadControlScheme.CUSTOM,
                        onClick = { selectScheme(GamepadControlScheme.CUSTOM) }
                    )
                }
            }

            if (config.scheme == GamepadControlScheme.CUSTOM) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = hoshiCardBackgroundColor())
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(stringResource(R.string.controller_custom_mapping_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.controller_custom_mapping_help))

                        SettingRow(
                            label = stringResource(R.string.controller_previous),
                            keyLabel = formatGamepadKeyLabel(context, config.previousKeyCode),
                            onChange = { openCaptureDialog(CaptureAction.PREVIOUS) }
                        )
                        SettingRow(
                            label = stringResource(R.string.controller_next),
                            keyLabel = formatGamepadKeyLabel(context, config.nextKeyCode),
                            onChange = { openCaptureDialog(CaptureAction.NEXT) }
                        )
                        SettingRow(
                            label = stringResource(R.string.controller_collect),
                            keyLabel = formatGamepadKeyLabel(context, config.collectKeyCode),
                            onChange = { openCaptureDialog(CaptureAction.COLLECT) }
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.controller_double_tap_collect_previous),
                                modifier = Modifier.weight(1f).padding(end = 12.dp)
                            )
                            Switch(
                                checked = config.doubleTapCollectPrevious,
                                onCheckedChange = { checked -> updateCustom(doubleTapCollectPrevious = checked) }
                            )
                        }
                    }
                }
            }

                    ControllerBluetoothSection()
        }

        gamepadHint?.let { hint ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(stringResource(R.string.controller_preview_key_name_value, formatGamepadKeyLabel(context, hint.keyCode)))
                    Text(stringResource(R.string.controller_preview_current_mapping_value, describeGamepadMapping(context, config, hint.keyCode)))
                    Text(stringResource(R.string.controller_preview_special_behavior_value, describeGamepadSpecialBehavior(context, config, hint.keyCode)))
                }
            }
        }

        val action = captureAction
        if (action != null) {
            val title = when (action) {
                CaptureAction.PREVIOUS -> context.getString(R.string.controller_capture_previous)
                CaptureAction.NEXT -> context.getString(R.string.controller_capture_next)
                CaptureAction.COLLECT -> context.getString(R.string.controller_capture_collect)
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
                        Text(stringResource(R.string.controller_capture_help))
                        Text(
                            stringResource(
                                R.string.controller_capture_result,
                                capturedKeyCode?.let { formatGamepadKeyLabel(context, it) }
                                    ?: stringResource(R.string.controller_capture_waiting)
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { closeCaptureDialog() }) { Text(stringResource(R.string.common_cancel)) }
                            TextButton(
                                onClick = { confirmCapturedKey() },
                                enabled = capturedKeyCode != null
                            ) { Text(stringResource(R.string.common_save)) }
                        }
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
        Text(
            text = "$label: $keyLabel",
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        OutlinedButton(onClick = onChange) { Text(stringResource(R.string.common_change)) }
    }
}

private fun formatGamepadKeyLabel(context: android.content.Context, keyCode: Int): String {
    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> context.getString(R.string.controller_key_left)
        KeyEvent.KEYCODE_DPAD_RIGHT -> context.getString(R.string.controller_key_right)
        KeyEvent.KEYCODE_DPAD_UP -> context.getString(R.string.controller_key_up)
        KeyEvent.KEYCODE_DPAD_DOWN -> context.getString(R.string.controller_key_down)
        KeyEvent.KEYCODE_BUTTON_A -> "A"
        KeyEvent.KEYCODE_BUTTON_B -> "B"
        KeyEvent.KEYCODE_BUTTON_X -> "X"
        KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
        KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
        KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
        KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "SELECT"
        KeyEvent.KEYCODE_BUTTON_START -> "START"
        KeyEvent.KEYCODE_BUTTON_MODE -> "MODE"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
        KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
        else -> {
            val raw = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
            raw
                .removePrefix("BUTTON_")
                .replace('_', ' ')
                .ifBlank { keyCode.toString() }
        }
    }
}

private fun describeGamepadMapping(
    context: android.content.Context,
    config: GamepadControlConfig,
    keyCode: Int
): String {
    val labels = buildList {
        if (config.previousKeyCode == keyCode) add(context.getString(R.string.controller_previous))
        if (config.nextKeyCode == keyCode) add(context.getString(R.string.controller_next))
        if (config.collectKeyCode == keyCode) add(context.getString(R.string.controller_collect))
    }
    return labels.joinToString(" / ").ifBlank {
        context.getString(R.string.controller_preview_unassigned)
    }
}

private fun describeGamepadSpecialBehavior(
    context: android.content.Context,
    config: GamepadControlConfig,
    keyCode: Int
): String {
    return if (config.collectKeyCode == keyCode && config.doubleTapCollectPrevious) {
        context.getString(R.string.controller_preview_special_double_tap_previous)
    } else {
        context.getString(R.string.controller_preview_none)
    }
}

private fun isPreviewableGamepadKey(keyCode: Int): Boolean {
    return when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_L2,
        KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_MODE,
        KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_BUTTON_THUMBR -> true
        else -> false
    }
}

