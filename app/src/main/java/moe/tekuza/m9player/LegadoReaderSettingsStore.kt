package moe.tekuza.m9player

import android.content.Context
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.legado.reader.M9PageAnim
import moe.tekuza.m9player.legado.reader.page.ReadView
import org.json.JSONObject

private const val LEGADO_READER_SETTINGS_PREFS = "legado_reader_settings"
private const val LEGADO_READER_SETTINGS_KEY = "legado_reader_settings_json"

internal data class LegadoReaderPersistedState(
    val textSizeSp: Int = 20,
    val lineSpacingDp: Int = 8,
    val paragraphSpacingDp: Int = 14,
    val letterSpacingDp: Int = 0,
    val textBold: Boolean = false,
    val typefaceIndex: Int = 0,
    val paragraphIndentCount: Int = 0,
    val paddingDp: Int = 22,
    val layoutMode: M9LayoutMode = M9LayoutMode.HORIZONTAL,
    val pageAnim: M9PageAnim = M9PageAnim.NONE,
    val bgColor: Int = 0xFFF8F1E3.toInt(),
    val textColor: Int = 0xFF2C241B.toInt(),
    val tipColor: Int = 0xFF8F8373.toInt(),
    val hideStatusBar: Boolean = false,
    val readBodyToLh: Boolean = true,
    val hideNavigationBar: Boolean = false,
    val showBrightnessView: Boolean = true,
    val volumeKeyPage: Boolean = true,
    val showReadTitleAddition: Boolean = true,
    val useZhLayout: Boolean = true,
    val textFullJustify: Boolean = true,
    val textBottomJustify: Boolean = true,
    val clickMode: ReadView.ClickMode = ReadView.ClickMode.LEFT_CENTER_RIGHT,
    val progressByChapter: Boolean = true,
    val keepScreenOn: Boolean = false,
    val mouseWheelPage: Boolean = true,
    val volumeKeyPageOnPlay: Boolean = false,
    val keyPageOnLongPress: Boolean = false,
    val noAnimScrollPage: Boolean = false,
    val previewImageByClick: Boolean = false,
    val optimizeRender: Boolean = false,
    val disableReturnKey: Boolean = false,
    val readBarStyleFollowPage: Boolean = false,
    val playbackBarPinnedVisible: Boolean = false,
    val preferredCharsetName: String? = null,
    val currentBookUri: String? = null,
    val currentChapterIndex: Int = 0,
    val currentCharPosition: Int = 0
)

internal fun loadLegadoReaderPersistedState(context: Context): LegadoReaderPersistedState {
    val raw = context.getSharedPreferences(LEGADO_READER_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .getString(LEGADO_READER_SETTINGS_KEY, null)
        ?: return LegadoReaderPersistedState()
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return LegadoReaderPersistedState()
    return LegadoReaderPersistedState(
        textSizeSp = json.optInt("textSizeSp", 20),
        lineSpacingDp = json.optInt("lineSpacingDp", 8),
        paragraphSpacingDp = json.optInt("paragraphSpacingDp", 14),
        letterSpacingDp = json.optInt("letterSpacingDp", 0),
        textBold = json.optBoolean("textBold", false),
        typefaceIndex = json.optInt("typefaceIndex", 0),
        paragraphIndentCount = json.optInt("paragraphIndentCount", 0),
        paddingDp = json.optInt("paddingDp", 22),
        layoutMode = json.optString("layoutMode")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { M9LayoutMode.valueOf(it) }.getOrNull() }
            ?: M9LayoutMode.HORIZONTAL,
        pageAnim = json.optString("pageAnim")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { M9PageAnim.valueOf(it) }.getOrNull() }
            ?: M9PageAnim.NONE,
        bgColor = json.optInt("bgColor", 0xFFF8F1E3.toInt()),
        textColor = json.optInt("textColor", 0xFF2C241B.toInt()),
        tipColor = json.optInt("tipColor", 0xFF8F8373.toInt()),
        hideStatusBar = json.optBoolean("hideStatusBar", false),
        readBodyToLh = json.optBoolean("readBodyToLh", true),
        hideNavigationBar = json.optBoolean("hideNavigationBar", false),
        showBrightnessView = json.optBoolean("showBrightnessView", true),
        volumeKeyPage = json.optBoolean("volumeKeyPage", true),
        showReadTitleAddition = json.optBoolean("showReadTitleAddition", true),
        useZhLayout = json.optBoolean("useZhLayout", true),
        textFullJustify = json.optBoolean("textFullJustify", true),
        textBottomJustify = json.optBoolean("textBottomJustify", true),
        clickMode = json.optString("clickMode")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { ReadView.ClickMode.valueOf(it) }.getOrNull() }
            ?: ReadView.ClickMode.LEFT_CENTER_RIGHT,
        progressByChapter = json.optBoolean("progressByChapter", true),
        keepScreenOn = json.optBoolean("keepScreenOn", false),
        mouseWheelPage = json.optBoolean("mouseWheelPage", true),
        volumeKeyPageOnPlay = json.optBoolean("volumeKeyPageOnPlay", false),
        keyPageOnLongPress = json.optBoolean("keyPageOnLongPress", false),
        noAnimScrollPage = json.optBoolean("noAnimScrollPage", false),
        previewImageByClick = json.optBoolean("previewImageByClick", false),
        optimizeRender = json.optBoolean("optimizeRender", false),
        disableReturnKey = json.optBoolean("disableReturnKey", false),
        readBarStyleFollowPage = json.optBoolean("readBarStyleFollowPage", false),
        playbackBarPinnedVisible = json.optBoolean("playbackBarPinnedVisible", false),
        preferredCharsetName = json.optString("preferredCharsetName").takeIf { it.isNotBlank() },
        currentBookUri = json.optString("currentBookUri").takeIf { it.isNotBlank() },
        currentChapterIndex = json.optInt("currentChapterIndex", 0),
        currentCharPosition = json.optInt("currentCharPosition", 0)
    )
}

internal fun saveLegadoReaderPersistedState(context: Context, state: LegadoReaderPersistedState) {
    val json = JSONObject().apply {
        put("textSizeSp", state.textSizeSp)
        put("lineSpacingDp", state.lineSpacingDp)
        put("paragraphSpacingDp", state.paragraphSpacingDp)
        put("letterSpacingDp", state.letterSpacingDp)
        put("textBold", state.textBold)
        put("typefaceIndex", state.typefaceIndex)
        put("paragraphIndentCount", state.paragraphIndentCount)
        put("paddingDp", state.paddingDp)
        put("layoutMode", state.layoutMode.name)
        put("pageAnim", state.pageAnim.name)
        put("bgColor", state.bgColor)
        put("textColor", state.textColor)
        put("tipColor", state.tipColor)
        put("hideStatusBar", state.hideStatusBar)
        put("readBodyToLh", state.readBodyToLh)
        put("hideNavigationBar", state.hideNavigationBar)
        put("showBrightnessView", state.showBrightnessView)
        put("volumeKeyPage", state.volumeKeyPage)
        put("showReadTitleAddition", state.showReadTitleAddition)
        put("useZhLayout", state.useZhLayout)
        put("textFullJustify", state.textFullJustify)
        put("textBottomJustify", state.textBottomJustify)
        put("clickMode", state.clickMode.name)
        put("progressByChapter", state.progressByChapter)
        put("keepScreenOn", state.keepScreenOn)
        put("mouseWheelPage", state.mouseWheelPage)
        put("volumeKeyPageOnPlay", state.volumeKeyPageOnPlay)
        put("keyPageOnLongPress", state.keyPageOnLongPress)
        put("noAnimScrollPage", state.noAnimScrollPage)
        put("previewImageByClick", state.previewImageByClick)
        put("optimizeRender", state.optimizeRender)
        put("disableReturnKey", state.disableReturnKey)
        put("readBarStyleFollowPage", state.readBarStyleFollowPage)
        put("playbackBarPinnedVisible", state.playbackBarPinnedVisible)
        put("preferredCharsetName", state.preferredCharsetName)
        put("currentBookUri", state.currentBookUri)
        put("currentChapterIndex", state.currentChapterIndex)
        put("currentCharPosition", state.currentCharPosition)
    }
    context.getSharedPreferences(LEGADO_READER_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(LEGADO_READER_SETTINGS_KEY, json.toString())
        .apply()
}
