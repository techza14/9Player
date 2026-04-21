package moe.tekuza.m9player

import android.content.Context
import android.net.Uri

private const val AUDIOBOOK_SETTINGS_PREFS = "audiobook_settings_prefs"
private const val AUDIOBOOK_SKIP_MILLIS_KEY = "audiobook_skip_millis"
private const val AUDIOBOOK_FLOATING_OVERLAY_ENABLED_KEY = "audiobook_floating_overlay_enabled"
private const val AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_ENABLED_KEY = "audiobook_floating_overlay_subtitle_enabled"
private const val AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_Y_KEY = "audiobook_floating_overlay_subtitle_y"
private const val AUDIOBOOK_FLOATING_OVERLAY_BUBBLE_X_KEY = "audiobook_floating_overlay_bubble_x"
private const val AUDIOBOOK_FLOATING_OVERLAY_BUBBLE_Y_KEY = "audiobook_floating_overlay_bubble_y"
private const val AUDIOBOOK_FLOATING_OVERLAY_SIZE_DP_KEY = "audiobook_floating_overlay_size_dp"
private const val AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_SIZE_SP_KEY = "audiobook_floating_overlay_subtitle_size_sp"
private const val AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_COLOR_KEY = "audiobook_floating_overlay_subtitle_color"
private const val AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_CUSTOM_COLOR_KEY = "audiobook_floating_overlay_subtitle_custom_color"
private const val AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_SCROLL_ENABLED_KEY = "audiobook_floating_overlay_subtitle_scroll_enabled"
private const val AUDIOBOOK_PAUSE_ON_LOOKUP_KEY = "audiobook_pause_on_lookup"
private const val AUDIOBOOK_ACTIVE_CUE_AT_TOP_KEY = "audiobook_active_cue_at_top"
private const val AUDIOBOOK_LOOKUP_AUDIO_ENABLED_KEY = "audiobook_lookup_audio_enabled"
private const val AUDIOBOOK_LOOKUP_AUDIO_AUTO_PLAY_KEY = "audiobook_lookup_audio_auto_play"
private const val AUDIOBOOK_LOOKUP_AUDIO_MODE_KEY = "audiobook_lookup_audio_mode"
private const val AUDIOBOOK_LOOKUP_LOCAL_AUDIO_URI_KEY = "audiobook_lookup_local_audio_uri"
private const val AUDIOBOOK_LOOKUP_FULL_SENTENCE_KEY = "audiobook_lookup_full_sentence"
private const val AUDIOBOOK_LOOKUP_RANGE_SELECTION_ENABLED_KEY = "audiobook_lookup_range_selection_enabled"
private const val AUDIOBOOK_LOOKUP_ROOT_FULL_WIDTH_ENABLED_KEY = "audiobook_lookup_root_full_width_enabled"
private const val AUDIOBOOK_SUBTITLE_GLOBAL_FONT_ENABLED_KEY = "audiobook_subtitle_global_font_enabled"
private const val AUDIOBOOK_SUBTITLE_CUSTOM_FONT_URI_KEY = "audiobook_subtitle_custom_font_uri"
private const val DEFAULT_AUDIOBOOK_SKIP_MILLIS = 10_000L
internal const val DEFAULT_FLOATING_OVERLAY_SIZE_DP = 58
internal const val MIN_FLOATING_OVERLAY_SIZE_DP = 36
internal const val MAX_FLOATING_OVERLAY_SIZE_DP = 72
internal const val DEFAULT_FLOATING_OVERLAY_SUBTITLE_SIZE_SP = 26
internal const val MIN_FLOATING_OVERLAY_SUBTITLE_SIZE_SP = 12
internal const val MAX_FLOATING_OVERLAY_SUBTITLE_SIZE_SP = 40
internal const val FLOATING_OVERLAY_SUBTITLE_COLOR_WHITE = 0xFFFFFFFF.toInt()
internal const val FLOATING_OVERLAY_SUBTITLE_COLOR_YELLOW = 0xFFFFF59D.toInt()
internal const val FLOATING_OVERLAY_SUBTITLE_COLOR_GREEN = 0xFFA5D6A7.toInt()
internal const val FLOATING_OVERLAY_SUBTITLE_COLOR_CYAN = 0xFF80DEEA.toInt()
internal const val FLOATING_OVERLAY_SUBTITLE_COLOR_PINK = 0xFFF48FB1.toInt()

internal enum class LookupAudioMode(val storageValue: String) {
    LOCAL_TTS("local_tts"),
    LOCAL_AUDIO("local_audio"); // Uses android.db local-audio database.

    companion object {
        fun fromStorage(value: String?): LookupAudioMode {
            return entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: LOCAL_TTS
        }
    }
}

internal enum class FloatingOverlayMode {
    OFF,
    SUBTITLE,
    BUBBLE,
    BOTH;

    val showsSubtitle: Boolean
        get() = this == SUBTITLE || this == BOTH

    val showsBubble: Boolean
        get() = this == BUBBLE || this == BOTH
}

internal data class AudiobookSettingsConfig(
    val seekStepMillis: Long = DEFAULT_AUDIOBOOK_SKIP_MILLIS,
    val floatingOverlayEnabled: Boolean = false,
    val floatingOverlaySubtitleEnabled: Boolean = false,
    val pausePlaybackOnLookup: Boolean = true,
    val activeCueDisplayAtTop: Boolean = false,
    val lookupPlaybackAudioEnabled: Boolean = false,
    val lookupPlaybackAudioAutoPlay: Boolean = false,
    val lookupExportFullSentence: Boolean = false,
    val lookupRangeSelectionEnabled: Boolean = false,
    val lookupRootFullWidthEnabled: Boolean = false,
    val subtitleGlobalFontEnabled: Boolean = false,
    val subtitleCustomFontUri: Uri? = null,
    val lookupAudioMode: LookupAudioMode = LookupAudioMode.LOCAL_TTS,
    val lookupLocalAudioUri: Uri? = null,
    val floatingOverlaySizeDp: Int = DEFAULT_FLOATING_OVERLAY_SIZE_DP,
    val floatingOverlaySubtitleSizeSp: Int = DEFAULT_FLOATING_OVERLAY_SUBTITLE_SIZE_SP,
    val floatingOverlaySubtitleColor: Int = FLOATING_OVERLAY_SUBTITLE_COLOR_WHITE,
    val floatingOverlaySubtitleCustomColor: Int = FLOATING_OVERLAY_SUBTITLE_COLOR_WHITE,
    val floatingOverlaySubtitleScrollEnabled: Boolean = true,
    val floatingOverlaySubtitleY: Int = 0,
    val floatingOverlayBubbleX: Int = 24,
    val floatingOverlayBubbleY: Int = 0
) {
    val floatingOverlayMode: FloatingOverlayMode
        get() = when {
            floatingOverlayEnabled && floatingOverlaySubtitleEnabled -> FloatingOverlayMode.BOTH
            floatingOverlaySubtitleEnabled -> FloatingOverlayMode.SUBTITLE
            floatingOverlayEnabled -> FloatingOverlayMode.BUBBLE
            else -> FloatingOverlayMode.OFF
        }
}

internal fun loadAudiobookSettingsConfig(context: Context): AudiobookSettingsConfig {
    val prefs = context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
    val lookupAudioUriRaw = prefs.getString(AUDIOBOOK_LOOKUP_LOCAL_AUDIO_URI_KEY, null)
    val lookupAudioUri = lookupAudioUriRaw
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
    val subtitleFontUriRaw = prefs.getString(AUDIOBOOK_SUBTITLE_CUSTOM_FONT_URI_KEY, null)
    val subtitleFontUri = subtitleFontUriRaw
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
    return AudiobookSettingsConfig(
        seekStepMillis = prefs.getLong(AUDIOBOOK_SKIP_MILLIS_KEY, DEFAULT_AUDIOBOOK_SKIP_MILLIS)
            .coerceIn(1_000L, 300_000L),
        floatingOverlayEnabled = prefs.getBoolean(AUDIOBOOK_FLOATING_OVERLAY_ENABLED_KEY, false),
        floatingOverlaySubtitleEnabled = prefs.getBoolean(
            AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_ENABLED_KEY,
            false
        ),
        pausePlaybackOnLookup = prefs.getBoolean(AUDIOBOOK_PAUSE_ON_LOOKUP_KEY, true),
        activeCueDisplayAtTop = prefs.getBoolean(AUDIOBOOK_ACTIVE_CUE_AT_TOP_KEY, false),
        lookupPlaybackAudioEnabled = prefs.getBoolean(AUDIOBOOK_LOOKUP_AUDIO_ENABLED_KEY, false),
        lookupPlaybackAudioAutoPlay = prefs.getBoolean(AUDIOBOOK_LOOKUP_AUDIO_AUTO_PLAY_KEY, false),
        lookupExportFullSentence = prefs.getBoolean(AUDIOBOOK_LOOKUP_FULL_SENTENCE_KEY, false),
        lookupRangeSelectionEnabled = prefs.getBoolean(AUDIOBOOK_LOOKUP_RANGE_SELECTION_ENABLED_KEY, false),
        lookupRootFullWidthEnabled = prefs.getBoolean(AUDIOBOOK_LOOKUP_ROOT_FULL_WIDTH_ENABLED_KEY, false),
        subtitleGlobalFontEnabled = prefs.getBoolean(AUDIOBOOK_SUBTITLE_GLOBAL_FONT_ENABLED_KEY, false),
        subtitleCustomFontUri = subtitleFontUri,
        lookupAudioMode = LookupAudioMode.fromStorage(prefs.getString(AUDIOBOOK_LOOKUP_AUDIO_MODE_KEY, null)),
        lookupLocalAudioUri = lookupAudioUri,
        floatingOverlaySizeDp = prefs.getInt(
            AUDIOBOOK_FLOATING_OVERLAY_SIZE_DP_KEY,
            DEFAULT_FLOATING_OVERLAY_SIZE_DP
        ).coerceIn(MIN_FLOATING_OVERLAY_SIZE_DP, MAX_FLOATING_OVERLAY_SIZE_DP),
        floatingOverlaySubtitleSizeSp = prefs.getInt(
            AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_SIZE_SP_KEY,
            DEFAULT_FLOATING_OVERLAY_SUBTITLE_SIZE_SP
        ).coerceIn(
            MIN_FLOATING_OVERLAY_SUBTITLE_SIZE_SP,
            MAX_FLOATING_OVERLAY_SUBTITLE_SIZE_SP
        ),
        floatingOverlaySubtitleColor = prefs.getInt(
            AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_COLOR_KEY,
            FLOATING_OVERLAY_SUBTITLE_COLOR_WHITE
        ),
        floatingOverlaySubtitleCustomColor = prefs.getInt(
            AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_CUSTOM_COLOR_KEY,
            FLOATING_OVERLAY_SUBTITLE_COLOR_WHITE
        ),
        floatingOverlaySubtitleScrollEnabled = prefs.getBoolean(
            AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_SCROLL_ENABLED_KEY,
            true
        ),
        floatingOverlaySubtitleY = prefs.getInt(AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_Y_KEY, 0),
        floatingOverlayBubbleX = prefs.getInt(AUDIOBOOK_FLOATING_OVERLAY_BUBBLE_X_KEY, 24),
        floatingOverlayBubbleY = prefs.getInt(AUDIOBOOK_FLOATING_OVERLAY_BUBBLE_Y_KEY, 0)
    )
}

internal fun saveSubtitleGlobalFontEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(AUDIOBOOK_SUBTITLE_GLOBAL_FONT_ENABLED_KEY, enabled)
        .apply()
}

internal fun saveSubtitleCustomFontUri(context: Context, uri: Uri?) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(AUDIOBOOK_SUBTITLE_CUSTOM_FONT_URI_KEY, uri?.toString())
        .apply()
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

internal fun saveAudiobookFloatingOverlaySubtitleEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_ENABLED_KEY, enabled)
        .apply()
}

internal fun saveAudiobookFloatingOverlayMode(context: Context, mode: FloatingOverlayMode) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(AUDIOBOOK_FLOATING_OVERLAY_ENABLED_KEY, mode.showsBubble)
        .putBoolean(AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_ENABLED_KEY, mode.showsSubtitle)
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

internal fun saveAudiobookFloatingOverlaySubtitlePosition(context: Context, y: Int) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putInt(AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_Y_KEY, y)
        .apply()
}

internal fun saveAudiobookFloatingOverlayBubblePosition(context: Context, x: Int, y: Int) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putInt(AUDIOBOOK_FLOATING_OVERLAY_BUBBLE_X_KEY, x)
        .putInt(AUDIOBOOK_FLOATING_OVERLAY_BUBBLE_Y_KEY, y)
        .apply()
}

internal fun saveAudiobookFloatingOverlaySizeDp(context: Context, sizeDp: Int) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putInt(
            AUDIOBOOK_FLOATING_OVERLAY_SIZE_DP_KEY,
            sizeDp.coerceIn(MIN_FLOATING_OVERLAY_SIZE_DP, MAX_FLOATING_OVERLAY_SIZE_DP)
        )
        .apply()
}

internal fun saveAudiobookFloatingOverlaySubtitleSizeSp(context: Context, sizeSp: Int) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putInt(
            AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_SIZE_SP_KEY,
            sizeSp.coerceIn(
                MIN_FLOATING_OVERLAY_SUBTITLE_SIZE_SP,
                MAX_FLOATING_OVERLAY_SUBTITLE_SIZE_SP
            )
        )
        .apply()
}

internal fun saveAudiobookFloatingOverlaySubtitleColor(context: Context, color: Int) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putInt(AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_COLOR_KEY, color)
        .apply()
}

internal fun saveAudiobookFloatingOverlaySubtitleCustomColor(context: Context, color: Int) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putInt(AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_CUSTOM_COLOR_KEY, color)
        .apply()
}

internal fun saveAudiobookFloatingOverlaySubtitleScrollEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_SCROLL_ENABLED_KEY, enabled)
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

internal fun saveLookupExportFullSentence(context: Context, enabled: Boolean) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(AUDIOBOOK_LOOKUP_FULL_SENTENCE_KEY, enabled)
        .apply()
}

internal fun saveLookupRangeSelectionEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(AUDIOBOOK_LOOKUP_RANGE_SELECTION_ENABLED_KEY, enabled)
        .apply()
}

internal fun saveLookupRootFullWidthEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(AUDIOBOOK_LOOKUP_ROOT_FULL_WIDTH_ENABLED_KEY, enabled)
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
