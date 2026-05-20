package moe.tekuza.m9player

import android.content.Context
import android.net.Uri

private const val AUDIOBOOK_SETTINGS_PREFS = "audiobook_settings_prefs"
private const val AUDIOBOOK_SKIP_MILLIS_KEY = "audiobook_skip_millis"
private const val AUDIOBOOK_FLOATING_OVERLAY_ENABLED_KEY = "audiobook_floating_overlay_enabled"
private const val AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_ENABLED_KEY = "audiobook_floating_overlay_subtitle_enabled"
private const val AUDIOBOOK_FLOATING_OVERLAY_SHOW_ON_READER_EXIT_KEY = "audiobook_floating_overlay_show_on_reader_exit"
private const val AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_X_KEY = "audiobook_floating_overlay_subtitle_x"
private const val AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_Y_KEY = "audiobook_floating_overlay_subtitle_y"
private const val AUDIOBOOK_FLOATING_OVERLAY_BUBBLE_X_KEY = "audiobook_floating_overlay_bubble_x"
private const val AUDIOBOOK_FLOATING_OVERLAY_BUBBLE_Y_KEY = "audiobook_floating_overlay_bubble_y"
private const val AUDIOBOOK_FLOATING_OVERLAY_SIZE_DP_KEY = "audiobook_floating_overlay_size_dp"
private const val AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_SIZE_SP_KEY = "audiobook_floating_overlay_subtitle_size_sp"
private const val AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_COLOR_KEY = "audiobook_floating_overlay_subtitle_color"
private const val AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_CUSTOM_COLOR_KEY = "audiobook_floating_overlay_subtitle_custom_color"
private const val AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_SCROLL_ENABLED_KEY = "audiobook_floating_overlay_subtitle_scroll_enabled"
private const val AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_WRITING_MODE_KEY = "audiobook_floating_overlay_subtitle_writing_mode"
private const val AUDIOBOOK_BOOK_SUBTITLE_WRITING_MODE_KEY = "audiobook_book_subtitle_writing_mode"
private const val AUDIOBOOK_READER_PLAYBACK_MODE_KEY = "audiobook_reader_playback_mode"
private const val AUDIOBOOK_BOOK_SUBTITLE_ACTIVE_SIZE_SP_KEY = "audiobook_book_subtitle_active_size_sp"
private const val AUDIOBOOK_BOOK_SUBTITLE_INACTIVE_SIZE_SP_KEY = "audiobook_book_subtitle_inactive_size_sp"
private const val AUDIOBOOK_BOOK_SUBTITLE_VERTICAL_ACTIVE_SIZE_SP_KEY = "audiobook_book_subtitle_vertical_active_size_sp"
private const val AUDIOBOOK_BOOK_SUBTITLE_VERTICAL_INACTIVE_SIZE_SP_KEY = "audiobook_book_subtitle_vertical_inactive_size_sp"
private const val AUDIOBOOK_BOOK_SUBTITLE_HORIZONTAL_LINE_HEIGHT_SP_KEY = "audiobook_book_subtitle_horizontal_line_height_sp"
private const val AUDIOBOOK_BOOK_SUBTITLE_VERTICAL_COLUMN_SPACING_PERCENT_KEY = "audiobook_book_subtitle_vertical_column_spacing_percent"
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
private const val AUDIOBOOK_SUBTITLE_CUSTOM_FONT_NAME_KEY = "audiobook_subtitle_custom_font_name"
@Volatile
private var cachedAudiobookSettingsConfig: AudiobookSettingsConfig? = null
private const val DEFAULT_AUDIOBOOK_SKIP_MILLIS = 10_000L
internal const val DEFAULT_FLOATING_OVERLAY_SIZE_DP = 58
internal const val MIN_FLOATING_OVERLAY_SIZE_DP = 36
internal const val MAX_FLOATING_OVERLAY_SIZE_DP = 72
internal const val DEFAULT_FLOATING_OVERLAY_SUBTITLE_SIZE_SP = 26
internal const val MIN_FLOATING_OVERLAY_SUBTITLE_SIZE_SP = 12
internal const val MAX_FLOATING_OVERLAY_SUBTITLE_SIZE_SP = 40
internal const val DEFAULT_BOOK_SUBTITLE_ACTIVE_SIZE_SP = 34
internal const val MIN_BOOK_SUBTITLE_ACTIVE_SIZE_SP = 22
internal const val MAX_BOOK_SUBTITLE_ACTIVE_SIZE_SP = 56
internal const val DEFAULT_BOOK_SUBTITLE_INACTIVE_SIZE_SP = 22
internal const val MIN_BOOK_SUBTITLE_INACTIVE_SIZE_SP = 14
internal const val MAX_BOOK_SUBTITLE_INACTIVE_SIZE_SP = 40
internal const val DEFAULT_BOOK_SUBTITLE_HORIZONTAL_LINE_HEIGHT_SP = 42
internal const val MIN_BOOK_SUBTITLE_HORIZONTAL_LINE_HEIGHT_SP = 24
internal const val MAX_BOOK_SUBTITLE_HORIZONTAL_LINE_HEIGHT_SP = 72
internal const val DEFAULT_BOOK_SUBTITLE_VERTICAL_COLUMN_SPACING_PERCENT = 100
internal const val MIN_BOOK_SUBTITLE_VERTICAL_COLUMN_SPACING_PERCENT = 80
internal const val MAX_BOOK_SUBTITLE_VERTICAL_COLUMN_SPACING_PERCENT = 150
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

internal enum class FloatingSubtitleWritingMode(val storageValue: String) {
    HORIZONTAL("horizontal"),
    VERTICAL_RTL("vertical_rtl");

    companion object {
        fun fromStorage(value: String?): FloatingSubtitleWritingMode {
            return entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: HORIZONTAL
        }
    }
}

internal enum class ReaderPlaybackMode(val storageValue: String) {
    NORMAL("normal"),
    CONDENSED("condensed");

    companion object {
        fun fromStorage(value: String?): ReaderPlaybackMode {
            return entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: NORMAL
        }
    }
}

internal data class AudiobookSettingsConfig(
    val seekStepMillis: Long = DEFAULT_AUDIOBOOK_SKIP_MILLIS,
    val floatingOverlayEnabled: Boolean = false,
    val floatingOverlaySubtitleEnabled: Boolean = false,
    val floatingOverlayShowOnReaderExit: Boolean = false,
    val pausePlaybackOnLookup: Boolean = true,
    val activeCueDisplayAtTop: Boolean = false,
    val lookupPlaybackAudioEnabled: Boolean = false,
    val lookupPlaybackAudioAutoPlay: Boolean = false,
    val lookupExportFullSentence: Boolean = false,
    val lookupRangeSelectionEnabled: Boolean = false,
    val lookupRootFullWidthEnabled: Boolean = false,
    val subtitleGlobalFontEnabled: Boolean = false,
    val subtitleCustomFontUri: Uri? = null,
    val subtitleCustomFontName: String? = null,
    val lookupAudioMode: LookupAudioMode = LookupAudioMode.LOCAL_TTS,
    val lookupLocalAudioUri: Uri? = null,
    val floatingOverlaySizeDp: Int = DEFAULT_FLOATING_OVERLAY_SIZE_DP,
    val floatingOverlaySubtitleSizeSp: Int = DEFAULT_FLOATING_OVERLAY_SUBTITLE_SIZE_SP,
    val floatingOverlaySubtitleColor: Int = FLOATING_OVERLAY_SUBTITLE_COLOR_WHITE,
    val floatingOverlaySubtitleCustomColor: Int = FLOATING_OVERLAY_SUBTITLE_COLOR_WHITE,
    val floatingOverlaySubtitleScrollEnabled: Boolean = true,
    val floatingOverlaySubtitleWritingMode: FloatingSubtitleWritingMode = FloatingSubtitleWritingMode.HORIZONTAL,
    val bookSubtitleWritingMode: FloatingSubtitleWritingMode = FloatingSubtitleWritingMode.HORIZONTAL,
    val bookSubtitleActiveSizeSp: Int = DEFAULT_BOOK_SUBTITLE_ACTIVE_SIZE_SP,
    val bookSubtitleInactiveSizeSp: Int = DEFAULT_BOOK_SUBTITLE_INACTIVE_SIZE_SP,
    val bookSubtitleVerticalActiveSizeSp: Int = DEFAULT_BOOK_SUBTITLE_ACTIVE_SIZE_SP,
    val bookSubtitleVerticalInactiveSizeSp: Int = DEFAULT_BOOK_SUBTITLE_INACTIVE_SIZE_SP,
    val bookSubtitleHorizontalLineHeightSp: Int = DEFAULT_BOOK_SUBTITLE_HORIZONTAL_LINE_HEIGHT_SP,
    val bookSubtitleVerticalColumnSpacingPercent: Int = DEFAULT_BOOK_SUBTITLE_VERTICAL_COLUMN_SPACING_PERCENT,
    val readerPlaybackMode: ReaderPlaybackMode = ReaderPlaybackMode.NORMAL,
    val floatingOverlaySubtitleX: Int = 0,
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

private fun audiobookSettingsPrefs(context: Context) =
    context.getSharedPreferences(AUDIOBOOK_SETTINGS_PREFS, Context.MODE_PRIVATE)

private fun audiobookSettingsEditor(context: Context): android.content.SharedPreferences.Editor {
    cachedAudiobookSettingsConfig = null
    return audiobookSettingsPrefs(context).edit()
}

internal fun loadAudiobookSettingsConfig(context: Context): AudiobookSettingsConfig {
    cachedAudiobookSettingsConfig?.let { return it }
    val prefs = audiobookSettingsPrefs(context)
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
        floatingOverlayShowOnReaderExit = prefs.getBoolean(
            AUDIOBOOK_FLOATING_OVERLAY_SHOW_ON_READER_EXIT_KEY,
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
        subtitleCustomFontName = prefs.getString(AUDIOBOOK_SUBTITLE_CUSTOM_FONT_NAME_KEY, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() },
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
        floatingOverlaySubtitleWritingMode = FloatingSubtitleWritingMode.fromStorage(
            prefs.getString(AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_WRITING_MODE_KEY, null)
        ),
        bookSubtitleWritingMode = FloatingSubtitleWritingMode.fromStorage(
            prefs.getString(AUDIOBOOK_BOOK_SUBTITLE_WRITING_MODE_KEY, null)
        ),
        bookSubtitleActiveSizeSp = prefs.getInt(
            AUDIOBOOK_BOOK_SUBTITLE_ACTIVE_SIZE_SP_KEY,
            DEFAULT_BOOK_SUBTITLE_ACTIVE_SIZE_SP
        ).coerceIn(MIN_BOOK_SUBTITLE_ACTIVE_SIZE_SP, MAX_BOOK_SUBTITLE_ACTIVE_SIZE_SP),
        bookSubtitleInactiveSizeSp = prefs.getInt(
            AUDIOBOOK_BOOK_SUBTITLE_INACTIVE_SIZE_SP_KEY,
            DEFAULT_BOOK_SUBTITLE_INACTIVE_SIZE_SP
        ).coerceIn(MIN_BOOK_SUBTITLE_INACTIVE_SIZE_SP, MAX_BOOK_SUBTITLE_INACTIVE_SIZE_SP),
        bookSubtitleVerticalActiveSizeSp = prefs.getInt(
            AUDIOBOOK_BOOK_SUBTITLE_VERTICAL_ACTIVE_SIZE_SP_KEY,
            DEFAULT_BOOK_SUBTITLE_ACTIVE_SIZE_SP
        ).coerceIn(MIN_BOOK_SUBTITLE_ACTIVE_SIZE_SP, MAX_BOOK_SUBTITLE_ACTIVE_SIZE_SP),
        bookSubtitleVerticalInactiveSizeSp = prefs.getInt(
            AUDIOBOOK_BOOK_SUBTITLE_VERTICAL_INACTIVE_SIZE_SP_KEY,
            DEFAULT_BOOK_SUBTITLE_INACTIVE_SIZE_SP
        ).coerceIn(MIN_BOOK_SUBTITLE_INACTIVE_SIZE_SP, MAX_BOOK_SUBTITLE_INACTIVE_SIZE_SP),
        bookSubtitleHorizontalLineHeightSp = prefs.getInt(
            AUDIOBOOK_BOOK_SUBTITLE_HORIZONTAL_LINE_HEIGHT_SP_KEY,
            DEFAULT_BOOK_SUBTITLE_HORIZONTAL_LINE_HEIGHT_SP
        ).coerceIn(MIN_BOOK_SUBTITLE_HORIZONTAL_LINE_HEIGHT_SP, MAX_BOOK_SUBTITLE_HORIZONTAL_LINE_HEIGHT_SP),
        bookSubtitleVerticalColumnSpacingPercent = prefs.getInt(
            AUDIOBOOK_BOOK_SUBTITLE_VERTICAL_COLUMN_SPACING_PERCENT_KEY,
            DEFAULT_BOOK_SUBTITLE_VERTICAL_COLUMN_SPACING_PERCENT
        ).coerceIn(
            MIN_BOOK_SUBTITLE_VERTICAL_COLUMN_SPACING_PERCENT,
            MAX_BOOK_SUBTITLE_VERTICAL_COLUMN_SPACING_PERCENT
        ),
        readerPlaybackMode = ReaderPlaybackMode.fromStorage(
            prefs.getString(AUDIOBOOK_READER_PLAYBACK_MODE_KEY, null)
        ),
        floatingOverlaySubtitleX = prefs.getInt(AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_X_KEY, 0),
        floatingOverlaySubtitleY = prefs.getInt(AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_Y_KEY, 0),
        floatingOverlayBubbleX = prefs.getInt(AUDIOBOOK_FLOATING_OVERLAY_BUBBLE_X_KEY, 24),
        floatingOverlayBubbleY = prefs.getInt(AUDIOBOOK_FLOATING_OVERLAY_BUBBLE_Y_KEY, 0)
    ).also { cachedAudiobookSettingsConfig = it }
}

internal fun saveSubtitleGlobalFontEnabled(context: Context, enabled: Boolean) {
    audiobookSettingsEditor(context)
        .putBoolean(AUDIOBOOK_SUBTITLE_GLOBAL_FONT_ENABLED_KEY, enabled)
        .apply()
}

internal fun saveSubtitleCustomFontUri(context: Context, uri: Uri?) {
    audiobookSettingsEditor(context)
        .putString(AUDIOBOOK_SUBTITLE_CUSTOM_FONT_URI_KEY, uri?.toString())
        .remove(AUDIOBOOK_SUBTITLE_CUSTOM_FONT_NAME_KEY)
        .apply()
}

internal fun saveSubtitleCustomFont(context: Context, uri: Uri?, displayName: String?) {
    audiobookSettingsEditor(context)
        .putString(AUDIOBOOK_SUBTITLE_CUSTOM_FONT_URI_KEY, uri?.toString())
        .putString(AUDIOBOOK_SUBTITLE_CUSTOM_FONT_NAME_KEY, displayName?.trim()?.takeIf { it.isNotBlank() })
        .apply()
}

internal fun saveAudiobookSeekStepMillis(context: Context, millis: Long) {
    audiobookSettingsEditor(context)
        .putLong(AUDIOBOOK_SKIP_MILLIS_KEY, millis.coerceIn(1_000L, 300_000L))
        .apply()
}

internal fun saveAudiobookFloatingOverlayShowOnReaderExit(context: Context, enabled: Boolean) {
    audiobookSettingsEditor(context)
        .putBoolean(AUDIOBOOK_FLOATING_OVERLAY_SHOW_ON_READER_EXIT_KEY, enabled)
        .apply()
}

internal fun saveAudiobookFloatingOverlayMode(context: Context, mode: FloatingOverlayMode) {
    audiobookSettingsEditor(context)
        .putBoolean(AUDIOBOOK_FLOATING_OVERLAY_ENABLED_KEY, mode.showsBubble)
        .putBoolean(AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_ENABLED_KEY, mode.showsSubtitle)
        .apply()
}

internal fun saveAudiobookPausePlaybackOnLookup(context: Context, enabled: Boolean) {
    audiobookSettingsEditor(context)
        .putBoolean(AUDIOBOOK_PAUSE_ON_LOOKUP_KEY, enabled)
        .apply()
}

internal fun saveAudiobookActiveCueDisplayAtTop(context: Context, enabled: Boolean) {
    audiobookSettingsEditor(context)
        .putBoolean(AUDIOBOOK_ACTIVE_CUE_AT_TOP_KEY, enabled)
        .apply()
}

internal fun saveAudiobookFloatingOverlaySubtitlePosition(context: Context, y: Int) {
    audiobookSettingsEditor(context)
        .putInt(AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_Y_KEY, y)
        .apply()
}

internal fun saveAudiobookFloatingOverlaySubtitlePosition(context: Context, x: Int, y: Int) {
    audiobookSettingsEditor(context)
        .putInt(AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_X_KEY, x)
        .putInt(AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_Y_KEY, y)
        .apply()
}

internal fun saveAudiobookFloatingOverlayBubblePosition(context: Context, x: Int, y: Int) {
    audiobookSettingsEditor(context)
        .putInt(AUDIOBOOK_FLOATING_OVERLAY_BUBBLE_X_KEY, x)
        .putInt(AUDIOBOOK_FLOATING_OVERLAY_BUBBLE_Y_KEY, y)
        .apply()
}

internal fun saveAudiobookFloatingOverlaySizeDp(context: Context, sizeDp: Int) {
    audiobookSettingsEditor(context)
        .putInt(
            AUDIOBOOK_FLOATING_OVERLAY_SIZE_DP_KEY,
            sizeDp.coerceIn(MIN_FLOATING_OVERLAY_SIZE_DP, MAX_FLOATING_OVERLAY_SIZE_DP)
        )
        .apply()
}

internal fun saveAudiobookFloatingOverlaySubtitleSizeSp(context: Context, sizeSp: Int) {
    audiobookSettingsEditor(context)
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
    audiobookSettingsEditor(context)
        .putInt(AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_COLOR_KEY, color)
        .apply()
}

internal fun saveAudiobookFloatingOverlaySubtitleCustomColor(context: Context, color: Int) {
    audiobookSettingsEditor(context)
        .putInt(AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_CUSTOM_COLOR_KEY, color)
        .apply()
}

internal fun saveAudiobookFloatingOverlaySubtitleScrollEnabled(context: Context, enabled: Boolean) {
    audiobookSettingsEditor(context)
        .putBoolean(AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_SCROLL_ENABLED_KEY, enabled)
        .apply()
}

internal fun saveAudiobookFloatingOverlaySubtitleWritingMode(
    context: Context,
    mode: FloatingSubtitleWritingMode
) {
    audiobookSettingsEditor(context)
        .putString(AUDIOBOOK_FLOATING_OVERLAY_SUBTITLE_WRITING_MODE_KEY, mode.storageValue)
        .apply()
}

internal fun saveAudiobookBookSubtitleWritingMode(
    context: Context,
    mode: FloatingSubtitleWritingMode
) {
    audiobookSettingsEditor(context)
        .putString(AUDIOBOOK_BOOK_SUBTITLE_WRITING_MODE_KEY, mode.storageValue)
        .apply()
}

internal fun saveAudiobookBookSubtitleActiveSizeSp(context: Context, sizeSp: Int) {
    audiobookSettingsEditor(context)
        .putInt(
            AUDIOBOOK_BOOK_SUBTITLE_ACTIVE_SIZE_SP_KEY,
            sizeSp.coerceIn(MIN_BOOK_SUBTITLE_ACTIVE_SIZE_SP, MAX_BOOK_SUBTITLE_ACTIVE_SIZE_SP)
        )
        .apply()
}

internal fun saveAudiobookBookSubtitleInactiveSizeSp(context: Context, sizeSp: Int) {
    audiobookSettingsEditor(context)
        .putInt(
            AUDIOBOOK_BOOK_SUBTITLE_INACTIVE_SIZE_SP_KEY,
            sizeSp.coerceIn(MIN_BOOK_SUBTITLE_INACTIVE_SIZE_SP, MAX_BOOK_SUBTITLE_INACTIVE_SIZE_SP)
        )
        .apply()
}

internal fun saveAudiobookBookSubtitleVerticalActiveSizeSp(context: Context, sizeSp: Int) {
    audiobookSettingsEditor(context)
        .putInt(
            AUDIOBOOK_BOOK_SUBTITLE_VERTICAL_ACTIVE_SIZE_SP_KEY,
            sizeSp.coerceIn(MIN_BOOK_SUBTITLE_ACTIVE_SIZE_SP, MAX_BOOK_SUBTITLE_ACTIVE_SIZE_SP)
        )
        .apply()
}

internal fun saveAudiobookBookSubtitleVerticalInactiveSizeSp(context: Context, sizeSp: Int) {
    audiobookSettingsEditor(context)
        .putInt(
            AUDIOBOOK_BOOK_SUBTITLE_VERTICAL_INACTIVE_SIZE_SP_KEY,
            sizeSp.coerceIn(MIN_BOOK_SUBTITLE_INACTIVE_SIZE_SP, MAX_BOOK_SUBTITLE_INACTIVE_SIZE_SP)
        )
        .apply()
}

internal fun saveAudiobookBookSubtitleHorizontalLineHeightSp(context: Context, lineHeightSp: Int) {
    audiobookSettingsEditor(context)
        .putInt(
            AUDIOBOOK_BOOK_SUBTITLE_HORIZONTAL_LINE_HEIGHT_SP_KEY,
            lineHeightSp.coerceIn(
                MIN_BOOK_SUBTITLE_HORIZONTAL_LINE_HEIGHT_SP,
                MAX_BOOK_SUBTITLE_HORIZONTAL_LINE_HEIGHT_SP
            )
        )
        .apply()
}

internal fun saveAudiobookBookSubtitleVerticalColumnSpacingPercent(context: Context, percent: Int) {
    audiobookSettingsEditor(context)
        .putInt(
            AUDIOBOOK_BOOK_SUBTITLE_VERTICAL_COLUMN_SPACING_PERCENT_KEY,
            percent.coerceIn(
                MIN_BOOK_SUBTITLE_VERTICAL_COLUMN_SPACING_PERCENT,
                MAX_BOOK_SUBTITLE_VERTICAL_COLUMN_SPACING_PERCENT
            )
        )
        .apply()
}

internal fun resetAudiobookBookSubtitleTypography(context: Context) {
    audiobookSettingsEditor(context)
        .remove(AUDIOBOOK_BOOK_SUBTITLE_ACTIVE_SIZE_SP_KEY)
        .remove(AUDIOBOOK_BOOK_SUBTITLE_INACTIVE_SIZE_SP_KEY)
        .remove(AUDIOBOOK_BOOK_SUBTITLE_VERTICAL_ACTIVE_SIZE_SP_KEY)
        .remove(AUDIOBOOK_BOOK_SUBTITLE_VERTICAL_INACTIVE_SIZE_SP_KEY)
        .remove(AUDIOBOOK_BOOK_SUBTITLE_HORIZONTAL_LINE_HEIGHT_SP_KEY)
        .remove(AUDIOBOOK_BOOK_SUBTITLE_VERTICAL_COLUMN_SPACING_PERCENT_KEY)
        .apply()
}

internal fun resetAudiobookBookSubtitleHorizontalTypography(context: Context) {
    audiobookSettingsEditor(context)
        .remove(AUDIOBOOK_BOOK_SUBTITLE_ACTIVE_SIZE_SP_KEY)
        .remove(AUDIOBOOK_BOOK_SUBTITLE_INACTIVE_SIZE_SP_KEY)
        .remove(AUDIOBOOK_BOOK_SUBTITLE_HORIZONTAL_LINE_HEIGHT_SP_KEY)
        .apply()
}

internal fun resetAudiobookBookSubtitleVerticalTypography(context: Context) {
    audiobookSettingsEditor(context)
        .remove(AUDIOBOOK_BOOK_SUBTITLE_VERTICAL_ACTIVE_SIZE_SP_KEY)
        .remove(AUDIOBOOK_BOOK_SUBTITLE_VERTICAL_INACTIVE_SIZE_SP_KEY)
        .remove(AUDIOBOOK_BOOK_SUBTITLE_VERTICAL_COLUMN_SPACING_PERCENT_KEY)
        .apply()
}

internal fun saveAudiobookReaderPlaybackMode(
    context: Context,
    mode: ReaderPlaybackMode
) {
    audiobookSettingsEditor(context)
        .putString(AUDIOBOOK_READER_PLAYBACK_MODE_KEY, mode.storageValue)
        .apply()
}

internal fun saveLookupPlaybackAudioEnabled(context: Context, enabled: Boolean) {
    audiobookSettingsEditor(context)
        .putBoolean(AUDIOBOOK_LOOKUP_AUDIO_ENABLED_KEY, enabled)
        .apply()
}

internal fun saveLookupPlaybackAudioAutoPlay(context: Context, enabled: Boolean) {
    audiobookSettingsEditor(context)
        .putBoolean(AUDIOBOOK_LOOKUP_AUDIO_AUTO_PLAY_KEY, enabled)
        .apply()
}

internal fun saveLookupExportFullSentence(context: Context, enabled: Boolean) {
    audiobookSettingsEditor(context)
        .putBoolean(AUDIOBOOK_LOOKUP_FULL_SENTENCE_KEY, enabled)
        .apply()
}

internal fun saveLookupRangeSelectionEnabled(context: Context, enabled: Boolean) {
    audiobookSettingsEditor(context)
        .putBoolean(AUDIOBOOK_LOOKUP_RANGE_SELECTION_ENABLED_KEY, enabled)
        .apply()
}

internal fun saveLookupRootFullWidthEnabled(context: Context, enabled: Boolean) {
    audiobookSettingsEditor(context)
        .putBoolean(AUDIOBOOK_LOOKUP_ROOT_FULL_WIDTH_ENABLED_KEY, enabled)
        .apply()
}

internal fun saveLookupAudioMode(context: Context, mode: LookupAudioMode) {
    audiobookSettingsEditor(context)
        .putString(AUDIOBOOK_LOOKUP_AUDIO_MODE_KEY, mode.storageValue)
        .apply()
}

internal fun saveLookupLocalAudioUri(context: Context, uri: Uri?) {
    audiobookSettingsEditor(context)
        .putString(AUDIOBOOK_LOOKUP_LOCAL_AUDIO_URI_KEY, uri?.toString())
        .apply()
}
