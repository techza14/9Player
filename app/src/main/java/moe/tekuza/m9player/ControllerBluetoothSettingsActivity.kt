package moe.tekuza.m9player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import moe.tekuza.m9player.ui.theme.TsetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class ControllerBluetoothSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TsetTheme {
                ControllerBluetoothSettingsScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun ControllerBluetoothSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var target by remember { mutableStateOf(loadTargetControllerInfo(context)) }
    var status by remember { mutableStateOf<String?>(null) }
    var actionRunning by remember { mutableStateOf(false) }
    var btPermissionGranted by remember { mutableStateOf(hasBluetoothConnectPermissionForUi(context)) }
    var shizukuRunning by remember { mutableStateOf(false) }
    var shizukuPermissionGranted by remember { mutableStateOf(false) }
    var disableBluetoothFallback by remember {
        mutableStateOf(
            loadControllerBluetoothBehaviorConfig(context).disableBluetoothIfControllerMissing
        )
    }

    fun openShizukuApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_MANAGER_PACKAGE)
        if (intent == null) {
            status = "未安装 Shizuku。"
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        status = "已打开 Shizuku。"
    }

    fun openBluetoothSettings() {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        status = "已打开系统蓝牙界面。"
    }

    val btPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            btPermissionGranted = granted || hasBluetoothConnectPermissionForUi(context)
            if (btPermissionGranted) {
                target = detectConnectedControllerInfo(context) ?: loadTargetControllerInfo(context)
                status = if (target != null) {
                    "已检测到手柄。"
                } else {
                    "权限已授予，但未找到已连接手柄。"
                }
            } else {
                status = "蓝牙权限被拒绝。"
            }
        }

    fun refreshTargetController() {
        val detectedWithoutPermission = detectConnectedControllerInfo(context)
        if (detectedWithoutPermission != null) {
            target = detectedWithoutPermission
            status = "目标手柄已刷新。"
            return
        }
        if (!hasBluetoothConnectPermissionForUi(context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                status = "当前系统不支持蓝牙权限请求。"
            }
            return
        }
        btPermissionGranted = true
        val detected = detectConnectedControllerInfo(context) ?: loadTargetControllerInfo(context)
        target = detected
        status = if (detected != null) {
            "目标手柄已刷新。"
        } else {
            "未找到已连接手柄。"
        }
    }

    fun requestShizukuPermission() {
        scope.launch {
            shizukuRunning = waitForShizukuBinder(context, timeoutMs = 1_200L)
            if (!shizukuRunning) {
                status = buildShizukuNotRunningMessage(context)
                shizukuPermissionGranted = false
                return@launch
            }
            if (hasShizukuPermission(context)) {
                shizukuPermissionGranted = true
                status = "Shizuku 已授权。"
                return@launch
            }
            val result = runCatching { Shizuku.requestPermission(1001) }
            if (result.isFailure) {
                status = "请求 Shizuku 权限失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                return@launch
            }
            status = "已请求 Shizuku 权限，请在弹窗中允许。"
            repeat(12) {
                delay(500L)
                requestShizukuBinder(context)
                if (hasShizukuPermission(context)) {
                    shizukuPermissionGranted = true
                    shizukuRunning = true
                    status = "Shizuku 权限已授予。"
                    return@launch
                }
            }
            shizukuRunning = Shizuku.pingBinder()
            shizukuPermissionGranted = hasShizukuPermission(context)
            if (!shizukuPermissionGranted) {
                status = "Shizuku 权限仍未授予。"
            }
        }
    }

    fun disconnectControllerBluetooth() {
        if (actionRunning) return
        scope.launch {
            shizukuRunning = waitForShizukuBinder(context, timeoutMs = 1_200L)
            if (!shizukuRunning) {
                status = buildShizukuNotRunningMessage(context)
                shizukuPermissionGranted = false
                return@launch
            }
            if (!hasShizukuPermission(context)) {
                requestShizukuPermission()
                return@launch
            }
            shizukuPermissionGranted = true
            actionRunning = true
            status = "正在执行蓝牙操作..."
            val targetAddress = target?.address ?: loadTargetControllerInfo(context)?.address
            val result = withContext(Dispatchers.IO) {
                tryDisconnectTargetControllerThenDisableBluetooth(
                    context = context,
                    targetAddress = targetAddress,
                    allowDisableBluetoothFallback = disableBluetoothFallback
                )
            }
            status = when (result.outcome) {
                SleepBluetoothOutcome.TARGET_DISCONNECTED -> {
                    "完成：目标手柄已断开（${targetAddress ?: "未知地址"}）。"
                }
                SleepBluetoothOutcome.BLUETOOTH_DISABLED -> {
                    "完成：已执行兜底关闭蓝牙。"
                }
                SleepBluetoothOutcome.FAILED -> {
                    "失败：${result.detail}"
                }
            }
            actionRunning = false
        }
    }

    LaunchedEffect(Unit) {
        shizukuRunning = waitForShizukuBinder(context, timeoutMs = 1_200L)
        shizukuPermissionGranted = hasShizukuPermission(context)
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
            TextButton(onClick = onBack) {
                Text("< 返回")
            }
            Text("手柄蓝牙", style = MaterialTheme.typography.titleLarge)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("目标手柄", style = MaterialTheme.typography.titleMedium)
                Text("地址：${target?.address ?: "无"}")
                Text("名称：${target?.name ?: "未知"}")
                Text("蓝牙权限：${if (btPermissionGranted) "已授权" else "未授权"}")
                Text("Shizuku：${if (shizukuRunning) "运行中" else "未运行"}")
                Text("Shizuku 权限：${if (shizukuPermissionGranted) "已授权" else "未授权"}")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("找不到手柄时关闭蓝牙")
                    Switch(
                        checked = disableBluetoothFallback,
                        onCheckedChange = { checked ->
                            disableBluetoothFallback = checked
                            saveControllerBluetoothBehaviorConfig(
                                context = context,
                                disableBluetoothIfControllerMissing = checked
                            )
                        }
                    )
                }
                Text("若开启此设置断开手柄，蓝牙时若无法识别手柄，则直接关闭蓝牙。")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { refreshTargetController() }) {
                        Text("刷新")
                    }
                    OutlinedButton(onClick = { requestShizukuPermission() }) {
                        Text("请求 Shizuku 权限")
                    }
                    OutlinedButton(onClick = { openShizukuApp() }) {
                        Text("打开 Shizuku")
                    }
                    OutlinedButton(onClick = { openBluetoothSettings() }) {
                        Text("打开蓝牙")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { disconnectControllerBluetooth() },
                        enabled = !actionRunning
                    ) {
                        Text("断开手柄蓝牙")
                    }
                }
                if (status != null) {
                    Text(status!!)
                }
            }
        }
    }
}

private fun hasBluetoothConnectPermissionForUi(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

private fun hasShizukuPermission(context: android.content.Context): Boolean {
    requestShizukuBinder(context)
    return Shizuku.pingBinder() &&
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
}

private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.privileged.api"

private fun buildShizukuNotRunningMessage(context: android.content.Context): String {
    val pm = context.packageManager
    val authority = "${context.packageName}.shizuku"
    val providerRegistered = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.resolveContentProvider(authority, PackageManager.ComponentInfoFlags.of(0L)) != null
    } else {
        @Suppress("DEPRECATION")
        pm.resolveContentProvider(authority, 0) != null
    }
    val managerInstalled = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(SHIZUKU_MANAGER_PACKAGE, PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(SHIZUKU_MANAGER_PACKAGE, 0)
        }
    }.isSuccess
    val directCallError = runCatching {
        context.contentResolver.call(
            Uri.parse("content://$authority"),
            "getBinder",
            null,
            Bundle()
        )
        null
    }.exceptionOrNull()?.let { "${it::class.java.simpleName}: ${it.message ?: "未知"}" } ?: "无"
    return "Shizuku 未运行。诊断(provider=$providerRegistered manager=$managerInstalled call=$directCallError)"
}


