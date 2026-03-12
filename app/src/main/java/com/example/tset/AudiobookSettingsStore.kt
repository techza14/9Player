package com.tekuza.p9player

import android.content.Context

private const val AUDIOBOOK_SETTINGS_PREFS = "audiobook_settings_prefs"
private const val AUDIOBOOK_SKIP_MILLIS_KEY = "audiobook_skip_millis"
private const val AUDIOBOOK_FLOATING_OVERLAY_ENABLED_KEY = "audiobook_floating_overlay_enabled"
private const val AUDIOBOOK_FLOATING_OVERLAY_X_KEY = "audiobook_floating_overlay_x"
private const val AUDIOBOOK_FLOATING_OVERLAY_Y_KEY = "audiobook_floating_overlay_y"
private const val AUDIOBOOK_PAUSE_ON_LOOKUP_KEY = "audiobook_pause_on_lookup"
private const val AUDIOBOOK_ACTIVE_CUE_AT_TOP_KEY = "audiobook_active_cue_at_top"
private const val DEFAULT_AUDIOBOOK_SKIP_MILLIS = 10_000L

internal data class AudiobookSettingsConfig(
    val seekStepMillis: Long = DEFAULT_AUDIOBOOK_SKIP_MILLIS,
    val floatingOverlayEnabled: Boolean = false,
    val pausePlaybackOnLookup: Boolean = false,
    val activeCueDisplayAtTop: Boolean = false,
    val floatingOverlayX: Int = 24,
    val floatingOverlayY: Int = 0
)

internal fun loadAudiobookSettingsConfig(context: Context): AudiobookSettingsConfig {
    val prefs = context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
    return AudiobookSettingsConfig(
        seekStepMillis = prefs.getLong(AUDIOBOOK_SKIP_MILLIS_KEY, DEFAULT_AUDIOBOOK_SKIP_MILLIS)
            .coerceIn(1_000L, 300_000L),
        floatingOverlayEnabled = prefs.getBoolean(AUDIOBOOK_FLOATING_OVERLAY_ENABLED_KEY, false),
        pausePlaybackOnLookup = prefs.getBoolean(AUDIOBOOK_PAUSE_ON_LOOKUP_KEY, false),
        activeCueDisplayAtTop = prefs.getBoolean(AUDIOBOOK_ACTIVE_CUE_AT_TOP_KEY, false),
        floatingOverlayX = prefs.getInt(AUDIOBOOK_FLOATING_OVERLAY_X_KEY, 24),
        floatingOverlayY = prefs.getInt(AUDIOBOOK_FLOATING_OVERLAY_Y_KEY, 0)
    )
}

internal fun saveAudiobookSeekStepMillis(context: Context, millis: Long) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putLong(AUDIOBOOK_SKIP_MILLIS_KEY, millis.coerceIn(1_000L, 300_000L))
        .apply()
}

internal fun saveAudiobookFloatingOverlayEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(AUDIOBOOK_FLOATING_OVERLAY_ENABLED_KEY, enabled)
        .apply()
}

internal fun saveAudiobookPausePlaybackOnLookup(context: Context, enabled: Boolean) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(AUDIOBOOK_PAUSE_ON_LOOKUP_KEY, enabled)
        .apply()
}

internal fun saveAudiobookActiveCueDisplayAtTop(context: Context, enabled: Boolean) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(AUDIOBOOK_ACTIVE_CUE_AT_TOP_KEY, enabled)
        .apply()
}

internal fun saveAudiobookFloatingOverlayPosition(context: Context, x: Int, y: Int) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putInt(AUDIOBOOK_FLOATING_OVERLAY_X_KEY, x)
        .putInt(AUDIOBOOK_FLOATING_OVERLAY_Y_KEY, y)
        .apply()
}
