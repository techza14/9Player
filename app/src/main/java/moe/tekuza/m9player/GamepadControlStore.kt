package moe.tekuza.m9player

import android.content.Context

internal enum class GamepadControlScheme(val value: String) {
    SCHEME_1("scheme_1"),
    SCHEME_2("scheme_2"),
    CUSTOM("custom")
}

private const val GAMEPAD_PREFS = "gamepad_control_prefs"
private const val GAMEPAD_SCHEME_KEY = "gamepad_scheme"
private const val GAMEPAD_CUSTOM_PREV_KEY = "custom_prev_key"
private const val GAMEPAD_CUSTOM_NEXT_KEY = "custom_next_key"
private const val GAMEPAD_CUSTOM_COLLECT_KEY = "custom_collect_key"
private const val GAMEPAD_CUSTOM_DOUBLE_PREVIOUS_KEY = "custom_double_previous"
private const val GAMEPAD_DIM_SCREEN_IN_CONTROL_MODE_KEY = "dim_screen_in_control_mode"
private const val GAMEPAD_SINGLE_TAP_COLLECT_ONLY_IN_CONTROL_MODE_KEY = "single_tap_collect_only_in_control_mode"

internal data class GamepadControlConfig(
    val scheme: GamepadControlScheme,
    val previousKeyCode: Int,
    val nextKeyCode: Int,
    val collectKeyCode: Int,
    val doubleTapCollectPrevious: Boolean,
    val dimScreenInControlMode: Boolean,
    val singleTapCollectOnlyInControlMode: Boolean
)

internal fun loadGamepadControlScheme(context: Context): GamepadControlScheme {
    val prefs = context.getSharedPreferences(GAMEPAD_PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString(GAMEPAD_SCHEME_KEY, GamepadControlScheme.SCHEME_1.value).orEmpty()
    return GamepadControlScheme.entries.firstOrNull { it.value == raw } ?: GamepadControlScheme.SCHEME_1
}

internal fun saveGamepadControlScheme(context: Context, scheme: GamepadControlScheme) {
    context.getSharedPreferences(GAMEPAD_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(GAMEPAD_SCHEME_KEY, scheme.value)
        .apply()
}

internal fun loadGamepadControlConfig(context: Context): GamepadControlConfig {
    val prefs = context.getSharedPreferences(GAMEPAD_PREFS, Context.MODE_PRIVATE)
    val scheme = loadGamepadControlScheme(context)
    val dimScreenInControlMode = prefs.getBoolean(GAMEPAD_DIM_SCREEN_IN_CONTROL_MODE_KEY, false)
    val singleTapCollectOnlyInControlMode = prefs.getBoolean(
        GAMEPAD_SINGLE_TAP_COLLECT_ONLY_IN_CONTROL_MODE_KEY,
        true
    )
    return when (scheme) {
        GamepadControlScheme.SCHEME_1 -> GamepadControlConfig(
            scheme = scheme,
            previousKeyCode = android.view.KeyEvent.KEYCODE_DPAD_LEFT,
            nextKeyCode = android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            collectKeyCode = android.view.KeyEvent.KEYCODE_BUTTON_X,
            doubleTapCollectPrevious = true,
            dimScreenInControlMode = dimScreenInControlMode,
            singleTapCollectOnlyInControlMode = singleTapCollectOnlyInControlMode
        )

        GamepadControlScheme.SCHEME_2 -> GamepadControlConfig(
            scheme = scheme,
            previousKeyCode = android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            nextKeyCode = android.view.KeyEvent.KEYCODE_DPAD_UP,
            collectKeyCode = android.view.KeyEvent.KEYCODE_BUTTON_L1,
            doubleTapCollectPrevious = true,
            dimScreenInControlMode = dimScreenInControlMode,
            singleTapCollectOnlyInControlMode = singleTapCollectOnlyInControlMode
        )

        GamepadControlScheme.CUSTOM -> GamepadControlConfig(
            scheme = scheme,
            previousKeyCode = prefs.getInt(GAMEPAD_CUSTOM_PREV_KEY, android.view.KeyEvent.KEYCODE_DPAD_LEFT),
            nextKeyCode = prefs.getInt(GAMEPAD_CUSTOM_NEXT_KEY, android.view.KeyEvent.KEYCODE_DPAD_RIGHT),
            collectKeyCode = prefs.getInt(GAMEPAD_CUSTOM_COLLECT_KEY, android.view.KeyEvent.KEYCODE_BUTTON_X),
            doubleTapCollectPrevious = prefs.getBoolean(GAMEPAD_CUSTOM_DOUBLE_PREVIOUS_KEY, true),
            dimScreenInControlMode = dimScreenInControlMode,
            singleTapCollectOnlyInControlMode = singleTapCollectOnlyInControlMode
        )
    }
}

internal fun saveDimScreenInControlMode(context: Context, enabled: Boolean) {
    context.getSharedPreferences(GAMEPAD_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(GAMEPAD_DIM_SCREEN_IN_CONTROL_MODE_KEY, enabled)
        .apply()
}

internal fun saveSingleTapCollectOnlyInControlMode(context: Context, enabled: Boolean) {
    context.getSharedPreferences(GAMEPAD_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(GAMEPAD_SINGLE_TAP_COLLECT_ONLY_IN_CONTROL_MODE_KEY, enabled)
        .apply()
}

internal fun saveCustomGamepadControlConfig(
    context: Context,
    previousKeyCode: Int,
    nextKeyCode: Int,
    collectKeyCode: Int,
    doubleTapCollectPrevious: Boolean
) {
    context.getSharedPreferences(GAMEPAD_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putInt(GAMEPAD_CUSTOM_PREV_KEY, previousKeyCode)
        .putInt(GAMEPAD_CUSTOM_NEXT_KEY, nextKeyCode)
        .putInt(GAMEPAD_CUSTOM_COLLECT_KEY, collectKeyCode)
        .putBoolean(GAMEPAD_CUSTOM_DOUBLE_PREVIOUS_KEY, doubleTapCollectPrevious)
        .apply()
}

