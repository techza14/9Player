package moe.tekuza.m9player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

@Composable
internal fun ControllerBluetoothSection() {
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
            status = context.getString(R.string.controller_bluetooth_status_shizuku_missing)
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        status = context.getString(R.string.controller_bluetooth_status_shizuku_opened)
    }

    fun openBluetoothSettings() {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        status = context.getString(R.string.controller_bluetooth_status_bluetooth_opened)
    }

    val btPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            btPermissionGranted = granted || hasBluetoothConnectPermissionForUi(context)
            if (btPermissionGranted) {
                target = detectConnectedControllerInfo(context) ?: loadTargetControllerInfo(context)
                status = if (target != null) {
                    context.getString(R.string.controller_bluetooth_status_controller_found)
                } else {
                    context.getString(R.string.controller_bluetooth_status_controller_not_found)
                }
            } else {
                status = context.getString(R.string.controller_bluetooth_status_permission_denied)
            }
        }

    fun refreshTargetController() {
        val detectedWithoutPermission = detectConnectedControllerInfo(context)
        if (detectedWithoutPermission != null) {
            target = detectedWithoutPermission
            status = context.getString(R.string.controller_bluetooth_status_target_refreshed)
            return
        }
        if (!hasBluetoothConnectPermissionForUi(context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                status = context.getString(R.string.controller_bluetooth_status_permission_unsupported)
            }
            return
        }
        btPermissionGranted = true
        val detected = detectConnectedControllerInfo(context) ?: loadTargetControllerInfo(context)
        target = detected
        status = if (detected != null) {
            context.getString(R.string.controller_bluetooth_status_target_refreshed)
        } else {
            context.getString(R.string.controller_bluetooth_status_none_found)
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
                status = context.getString(R.string.controller_bluetooth_status_shizuku_authorized)
                return@launch
            }
            val result = runCatching { Shizuku.requestPermission(1001) }
            if (result.isFailure) {
                status = context.getString(
                    R.string.controller_bluetooth_status_shizuku_request_failed,
                    result.exceptionOrNull()?.message ?: context.getString(R.string.common_unknown)
                )
                return@launch
            }
            status = context.getString(R.string.controller_bluetooth_status_shizuku_requested)
            repeat(12) {
                delay(500L)
                requestShizukuBinder(context)
                if (hasShizukuPermission(context)) {
                    shizukuPermissionGranted = true
                    shizukuRunning = true
                    status = context.getString(R.string.controller_bluetooth_status_shizuku_granted)
                    return@launch
                }
            }
            shizukuRunning = Shizuku.pingBinder()
            shizukuPermissionGranted = hasShizukuPermission(context)
            if (!shizukuPermissionGranted) {
                status = context.getString(R.string.controller_bluetooth_status_shizuku_still_denied)
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
            status = context.getString(R.string.controller_bluetooth_status_running_action)
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
                    context.getString(
                        R.string.controller_bluetooth_status_disconnect_done,
                        targetAddress ?: context.getString(R.string.common_unknown)
                    )
                }
                SleepBluetoothOutcome.BLUETOOTH_DISABLED -> {
                    context.getString(R.string.controller_bluetooth_status_disable_done)
                }
                SleepBluetoothOutcome.FAILED -> {
                    context.getString(R.string.controller_bluetooth_status_failed, result.detail)
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
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.controller_bluetooth_target_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.controller_bluetooth_address, target?.address ?: stringResource(R.string.common_not_selected)))
                Text(stringResource(R.string.controller_bluetooth_name, target?.name ?: stringResource(R.string.common_unknown)))
                Text(stringResource(if (btPermissionGranted) R.string.controller_bluetooth_permission_granted else R.string.controller_bluetooth_permission_denied))
                Text(stringResource(if (shizukuRunning) R.string.controller_bluetooth_shizuku_running else R.string.controller_bluetooth_shizuku_not_running))
                Text(stringResource(if (shizukuPermissionGranted) R.string.controller_bluetooth_shizuku_permission_granted else R.string.controller_bluetooth_shizuku_permission_denied))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.controller_bluetooth_disable_fallback),
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )
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
                Text(stringResource(R.string.controller_bluetooth_disable_fallback_help))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = { refreshTargetController() }) {
                        Text(stringResource(R.string.common_refresh))
                    }
                    OutlinedButton(onClick = { requestShizukuPermission() }) {
                        Text(stringResource(R.string.controller_bluetooth_request_permission))
                    }
                    OutlinedButton(onClick = { openShizukuApp() }) {
                        Text(stringResource(R.string.controller_bluetooth_open_shizuku))
                    }
                    OutlinedButton(onClick = { openBluetoothSettings() }) {
                        Text(stringResource(R.string.controller_bluetooth_open_bluetooth))
                    }
                    Button(
                        onClick = { disconnectControllerBluetooth() },
                        enabled = !actionRunning
                    ) {
                        Text(stringResource(R.string.controller_bluetooth_disconnect))
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
    return context.getString(
        R.string.controller_bluetooth_status_diagnostic,
        providerRegistered.toString(),
        managerInstalled.toString(),
        directCallError
    )
}



