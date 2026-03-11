package com.tekuza.p9player

import android.content.Context

private const val AUDIOBOOK_SETTINGS_PREFS = "audiobook_settings_prefs"
private const val AUDIOBOOK_SKIP_MILLIS_KEY = "audiobook_skip_millis"
private const val DEFAULT_AUDIOBOOK_SKIP_MILLIS = 10_000L

internal data class AudiobookSettingsConfig(
    val seekStepMillis: Long = DEFAULT_AUDIOBOOK_SKIP_MILLIS
)

internal fun loadAudiobookSettingsConfig(context: Context): AudiobookSettingsConfig {
    val prefs = context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
    return AudiobookSettingsConfig(
        seekStepMillis = prefs.getLong(AUDIOBOOK_SKIP_MILLIS_KEY, DEFAULT_AUDIOBOOK_SKIP_MILLIS)
            .coerceIn(1_000L, 300_000L)
    )
}

internal fun saveAudiobookSeekStepMillis(context: Context, millis: Long) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putLong(AUDIOBOOK_SKIP_MILLIS_KEY, millis.coerceIn(1_000L, 300_000L))
        .apply()
}
