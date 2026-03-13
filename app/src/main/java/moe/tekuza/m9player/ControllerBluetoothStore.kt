package moe.tekuza.m9player

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.InputDevice
import androidx.core.content.ContextCompat
import java.util.Locale

internal data class TargetControllerInfo(
    val address: String,
    val name: String?
)

internal data class ControllerBluetoothBehaviorConfig(
    val disableBluetoothIfControllerMissing: Boolean
)

private const val TARGET_CONTROLLER_PREFS = "target_controller_prefs"
private const val TARGET_CONTROLLER_ADDRESS_KEY = "target_address"
private const val TARGET_CONTROLLER_NAME_KEY = "target_name"
private const val TARGET_CONTROLLER_DISABLE_BT_FALLBACK_KEY = "disable_bt_fallback"

internal fun loadTargetControllerInfo(context: Context): TargetControllerInfo? {
    val prefs = context.getSharedPreferences(TARGET_CONTROLLER_PREFS, Context.MODE_PRIVATE)
    val address = prefs.getString(TARGET_CONTROLLER_ADDRESS_KEY, null)
        ?.trim()
        ?.uppercase(Locale.US)
        ?.takeIf { isBluetoothAddress(it) }
        ?: return null
    val name = prefs.getString(TARGET_CONTROLLER_NAME_KEY, null)?.trim()?.takeIf { it.isNotBlank() }
    return TargetControllerInfo(address = address, name = name)
}

internal fun saveTargetControllerInfo(context: Context, info: TargetControllerInfo) {
    val normalized = info.address.trim().uppercase(Locale.US)
    if (!isBluetoothAddress(normalized)) return
    context.getSharedPreferences(TARGET_CONTROLLER_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(TARGET_CONTROLLER_ADDRESS_KEY, normalized)
        .putString(TARGET_CONTROLLER_NAME_KEY, info.name?.trim().orEmpty())
        .apply()
}

internal fun loadControllerBluetoothBehaviorConfig(context: Context): ControllerBluetoothBehaviorConfig {
    val prefs = context.getSharedPreferences(TARGET_CONTROLLER_PREFS, Context.MODE_PRIVATE)
    return ControllerBluetoothBehaviorConfig(
        disableBluetoothIfControllerMissing = prefs.getBoolean(TARGET_CONTROLLER_DISABLE_BT_FALLBACK_KEY, false)
    )
}

internal fun saveControllerBluetoothBehaviorConfig(
    context: Context,
    disableBluetoothIfControllerMissing: Boolean
) {
    context.getSharedPreferences(TARGET_CONTROLLER_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(TARGET_CONTROLLER_DISABLE_BT_FALLBACK_KEY, disableBluetoothIfControllerMissing)
        .apply()
}

internal fun detectConnectedControllerInfo(context: Context): TargetControllerInfo? {
    val fromInput = detectControllerInfoFromInputDevices()
    if (fromInput != null) {
        saveTargetControllerInfo(context, fromInput)
        return fromInput
    }

    val bluetoothManager = context.getSystemService(BluetoothManager::class.java) ?: return null
    if (!hasBluetoothConnectPermission(context)) return null

    val profileCandidates = listOf(
        4, // HID_HOST (system API constant)
        BluetoothProfile.A2DP,
        BluetoothProfile.HEADSET,
        BluetoothProfile.GATT,
        BluetoothProfile.GATT_SERVER
    ).distinct()

    val allDevices = linkedMapOf<String, BluetoothDevice>()
    profileCandidates.forEach { profile ->
        val devices = runCatching { bluetoothManager.getConnectedDevices(profile) }.getOrNull().orEmpty()
        devices.forEach { device ->
            val address = device.address?.trim()?.uppercase(Locale.US) ?: return@forEach
            if (!isBluetoothAddress(address)) return@forEach
            allDevices[address] = device
        }
    }
    if (allDevices.isEmpty()) return null

    val best = allDevices.values
        .map { device ->
            val score = scoreLikelyController(device)
            score to device
        }
        .sortedByDescending { it.first }
        .firstOrNull()
        ?.second
        ?: return null

    val address = best.address?.trim()?.uppercase(Locale.US) ?: return null
    if (!isBluetoothAddress(address)) return null
    val info = TargetControllerInfo(
        address = address,
        name = best.name?.trim()?.takeIf { it.isNotBlank() }
    )
    saveTargetControllerInfo(context, info)
    return info
}

private fun detectControllerInfoFromInputDevices(): TargetControllerInfo? {
    val candidates = mutableListOf<InputDevice>()
    InputDevice.getDeviceIds().forEach { id ->
        val device = InputDevice.getDevice(id) ?: return@forEach
        if (scoreLikelyControllerInputDevice(device) > 0) {
            candidates += device
        }
    }
    if (candidates.isEmpty()) return null

    val best = candidates.maxByOrNull(::scoreLikelyControllerInputDevice) ?: return null
    val address = readInputDeviceBluetoothAddress(best) ?: return null
    return TargetControllerInfo(
        address = address,
        name = best.name?.trim()?.takeIf { it.isNotBlank() }
    )
}

private fun scoreLikelyControllerInputDevice(device: InputDevice): Int {
    var score = 0
    val sources = device.sources
    if ((sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) score += 12
    if ((sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) score += 10
    if ((sources and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD) score += 6

    val name = device.name?.lowercase(Locale.US).orEmpty()
    if (name.contains("controller") || name.contains("gamepad") || name.contains("xbox") || name.contains("dual")) {
        score += 12
    }
    return score
}

private fun readInputDeviceBluetoothAddress(device: InputDevice): String? {
    val address = runCatching {
        val method = InputDevice::class.java.methods
            .firstOrNull { it.name == "getBluetoothAddress" && it.parameterCount == 0 }
        method?.invoke(device) as? String
    }.getOrNull()
        ?.trim()
        ?.uppercase(Locale.US)
        ?.takeIf { it != "00:00:00:00:00:00" && isBluetoothAddress(it) }
    return address
}

private fun scoreLikelyController(device: BluetoothDevice): Int {
    var score = 0
    val name = device.name?.lowercase(Locale.US).orEmpty()
    if (name.contains("controller") || name.contains("gamepad") || name.contains("xbox") || name.contains("dual")) {
        score += 12
    }
    val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
    if (major == BluetoothClass.Device.Major.PERIPHERAL) {
        score += 8
    }
    return score
}

private fun hasBluetoothConnectPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

private fun isBluetoothAddress(value: String): Boolean {
    return Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$").matches(value)
}

