package com.tekuza.p9player

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

internal enum class SleepBluetoothOutcome {
    TARGET_DISCONNECTED,
    BLUETOOTH_DISABLED,
    FAILED
}

internal data class SleepBluetoothActionResult(
    val outcome: SleepBluetoothOutcome,
    val detail: String
)

internal fun tryDisconnectTargetControllerThenDisableBluetooth(
    context: Context,
    targetAddress: String?,
    allowDisableBluetoothFallback: Boolean
): SleepBluetoothActionResult {
    val address = targetAddress
        ?.trim()
        ?.uppercase()
        ?.takeIf { isBluetoothAddress(it) }

    if (!Shizuku.pingBinder()) {
        runCatching { ShizukuProvider.requestBinderForNonProviderProcess(context) }
        val start = System.currentTimeMillis()
        while (!Shizuku.pingBinder() && System.currentTimeMillis() - start < 2_000L) {
            try {
                Thread.sleep(120L)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    if (!Shizuku.pingBinder()) {
        return SleepBluetoothActionResult(
            outcome = SleepBluetoothOutcome.FAILED,
            detail = "Shizuku not running"
        )
    }
    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
        return SleepBluetoothActionResult(
            outcome = SleepBluetoothOutcome.FAILED,
            detail = "Shizuku permission denied"
        )
    }

    var disconnectSucceeded = false
    if (!address.isNullOrBlank()) {
        val disconnectCommands = listOf(
            "cmd bluetooth_manager disconnect $address",
            "cmd bluetooth_manager disconnect --address $address",
            "cmd bluetooth_manager disconnect-device $address"
        )
        for (command in disconnectCommands) {
            val result = runShizukuShell(command)
            if (result.exitCode == 0) {
                disconnectSucceeded = true
                break
            }
        }
    }

    if (disconnectSucceeded) {
        return SleepBluetoothActionResult(
            outcome = SleepBluetoothOutcome.TARGET_DISCONNECTED,
            detail = "Disconnected controller $address"
        )
    }

    if (!allowDisableBluetoothFallback) {
        val reason = if (address.isNullOrBlank()) {
            "未找到手柄"
        } else {
            "断开手柄失败：$address"
        }
        return SleepBluetoothActionResult(
            outcome = SleepBluetoothOutcome.FAILED,
            detail = "$reason；已按设置跳过关闭蓝牙"
        )
    }

    val disableCommands = listOf(
        "cmd bluetooth_manager disable",
        "svc bluetooth disable"
    )
    disableCommands.forEach { command ->
        val result = runShizukuShell(command)
        if (result.exitCode == 0) {
            return SleepBluetoothActionResult(
                outcome = SleepBluetoothOutcome.BLUETOOTH_DISABLED,
                detail = "Bluetooth disabled"
            )
        }
    }

    return SleepBluetoothActionResult(
        outcome = SleepBluetoothOutcome.FAILED,
        detail = "断开手柄并关闭蓝牙失败"
    )
}

private data class ShizukuShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

private fun runShizukuShell(command: String): ShizukuShellResult {
    return runCatching {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(
            null,
            arrayOf("sh", "-c", command),
            null,
            null
        ) as Process
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        ShizukuShellResult(exitCode = exit, stdout = stdout, stderr = stderr)
    }.getOrElse { error ->
        ShizukuShellResult(
            exitCode = -1,
            stdout = "",
            stderr = error.message.orEmpty()
        )
    }
}

private fun isBluetoothAddress(value: String): Boolean {
    return Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(value)
}

