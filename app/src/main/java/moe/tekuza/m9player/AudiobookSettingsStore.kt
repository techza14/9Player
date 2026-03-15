package moe.tekuza.m9player

import android.content.Context
import android.net.Uri

private const val AUDIOBOOK_SETTINGS_PREFS = "audiobook_settings_prefs"
private const val AUDIOBOOK_SKIP_MILLIS_KEY = "audiobook_skip_millis"
private const val AUDIOBOOK_FLOATING_OVERLAY_ENABLED_KEY = "audiobook_floating_overlay_enabled"
private const val AUDIOBOOK_FLOATING_OVERLAY_X_KEY = "audiobook_floating_overlay_x"
private const val AUDIOBOOK_FLOATING_OVERLAY_Y_KEY = "audiobook_floating_overlay_y"
private const val AUDIOBOOK_PAUSE_ON_LOOKUP_KEY = "audiobook_pause_on_lookup"
private const val AUDIOBOOK_ACTIVE_CUE_AT_TOP_KEY = "audiobook_active_cue_at_top"
private const val AUDIOBOOK_LOOKUP_AUDIO_ENABLED_KEY = "audiobook_lookup_audio_enabled"
private const val AUDIOBOOK_LOOKUP_AUDIO_AUTO_PLAY_KEY = "audiobook_lookup_audio_auto_play"
private const val AUDIOBOOK_LOOKUP_AUDIO_MODE_KEY = "audiobook_lookup_audio_mode"
private const val AUDIOBOOK_LOOKUP_LOCAL_AUDIO_URI_KEY = "audiobook_lookup_local_audio_uri"
private const val DEFAULT_AUDIOBOOK_SKIP_MILLIS = 10_000L

internal enum class LookupAudioMode(val storageValue: String) {
    LOCAL_TTS("local_tts"),
    LOCAL_AUDIO("local_audio"); // Uses android.db local-audio database.

    companion object {
        fun fromStorage(value: String?): LookupAudioMode {
            return entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: LOCAL_TTS
        }
    }
}

internal data class AudiobookSettingsConfig(
    val seekStepMillis: Long = DEFAULT_AUDIOBOOK_SKIP_MILLIS,
    val floatingOverlayEnabled: Boolean = false,
    val pausePlaybackOnLookup: Boolean = false,
    val activeCueDisplayAtTop: Boolean = false,
    val lookupPlaybackAudioEnabled: Boolean = false,
    val lookupPlaybackAudioAutoPlay: Boolean = false,
    val lookupAudioMode: LookupAudioMode = LookupAudioMode.LOCAL_TTS,
    val lookupLocalAudioUri: Uri? = null,
    val floatingOverlayX: Int = 24,
    val floatingOverlayY: Int = 0
)

internal fun loadAudiobookSettingsConfig(context: Context): AudiobookSettingsConfig {
    val prefs = context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
    val lookupAudioUriRaw = prefs.getString(AUDIOBOOK_LOOKUP_LOCAL_AUDIO_URI_KEY, null)
    val lookupAudioUri = lookupAudioUriRaw
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
    return AudiobookSettingsConfig(
        seekStepMillis = prefs.getLong(AUDIOBOOK_SKIP_MILLIS_KEY, DEFAULT_AUDIOBOOK_SKIP_MILLIS)
            .coerceIn(1_000L, 300_000L),
        floatingOverlayEnabled = prefs.getBoolean(AUDIOBOOK_FLOATING_OVERLAY_ENABLED_KEY, false),
        pausePlaybackOnLookup = prefs.getBoolean(AUDIOBOOK_PAUSE_ON_LOOKUP_KEY, false),
        activeCueDisplayAtTop = prefs.getBoolean(AUDIOBOOK_ACTIVE_CUE_AT_TOP_KEY, false),
        lookupPlaybackAudioEnabled = prefs.getBoolean(AUDIOBOOK_LOOKUP_AUDIO_ENABLED_KEY, false),
        lookupPlaybackAudioAutoPlay = prefs.getBoolean(AUDIOBOOK_LOOKUP_AUDIO_AUTO_PLAY_KEY, false),
        lookupAudioMode = LookupAudioMode.fromStorage(prefs.getString(AUDIOBOOK_LOOKUP_AUDIO_MODE_KEY, null)),
        lookupLocalAudioUri = lookupAudioUri,
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

internal fun saveLookupPlaybackAudioEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(AUDIOBOOK_LOOKUP_AUDIO_ENABLED_KEY, enabled)
        .apply()
}

internal fun saveLookupPlaybackAudioAutoPlay(context: Context, enabled: Boolean) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(AUDIOBOOK_LOOKUP_AUDIO_AUTO_PLAY_KEY, enabled)
        .apply()
}

internal fun saveLookupAudioMode(context: Context, mode: LookupAudioMode) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(AUDIOBOOK_LOOKUP_AUDIO_MODE_KEY, mode.storageValue)
        .apply()
}

internal fun saveLookupLocalAudioUri(context: Context, uri: Uri?) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(AUDIOBOOK_LOOKUP_LOCAL_AUDIO_URI_KEY, uri?.toString())
        .apply()
}
