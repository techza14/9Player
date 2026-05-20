package moe.tekuza.m9player

import android.content.Context
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.legado.reader.M9PageAnim
import moe.tekuza.m9player.legado.reader.M9TextWeight
import moe.tekuza.m9player.legado.reader.page.ReadView
import org.json.JSONArray
import org.json.JSONObject

private const val LEGADO_READER_SETTINGS_PREFS = "legado_reader_settings"
private const val LEGADO_READER_SETTINGS_KEY = "legado_reader_settings_json"
private const val LEGADO_READER_BOOK_ANCHORS_KEY = "legado_reader_book_anchors_json"

internal data class LegadoReaderStyleConfig(
    val name: String = "",
    val bgColor: Int = 0xFFF8F1E3.toInt(),
    val textColor: Int = 0xFF2C241B.toInt(),
    val tipColor: Int = 0xFF8F8373.toInt(),
    val bgAlpha: Int = 100,
    val darkStatusIcon: Boolean = true,
    val underline: Boolean = false,
    val bgAssetName: String? = null,
    val bgImageUri: String? = null
)

internal fun defaultLegadoReaderStyleConfigs(): List<LegadoReaderStyleConfig> = listOf(
    LegadoReaderStyleConfig("微信读书", 0xFFC0EDC6.toInt(), 0xFF0B0B0B.toInt(), 0xFF606060.toInt(), darkStatusIcon = true),
    LegadoReaderStyleConfig("预设1", 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF777777.toInt(), darkStatusIcon = true),
    LegadoReaderStyleConfig("预设2", 0xFFDDC090.toInt(), 0xFF3E3422.toInt(), 0xFF7B6543.toInt(), darkStatusIcon = true),
    LegadoReaderStyleConfig("预设3", 0xFFC2D8AA.toInt(), 0xFF596C44.toInt(), 0xFF758A60.toInt(), darkStatusIcon = false),
    LegadoReaderStyleConfig("预设4", 0xFFDBB8E2.toInt(), 0xFF68516C.toInt(), 0xFF87678C.toInt(), darkStatusIcon = false),
    LegadoReaderStyleConfig("预设5", 0xFFABCEE0.toInt(), 0xFF3D4C54.toInt(), 0xFF637985.toInt(), darkStatusIcon = false)
)

internal data class LegadoReaderPersistedState(
    val textSizeSp: Int = 20,
    val lineSpacingDp: Int = 8,
    val paragraphSpacingDp: Int = 14,
    val letterSpacingDp: Int = 0,
    val textWeight: M9TextWeight = M9TextWeight.NORMAL,
    val typefaceIndex: Int = 0,
    val paragraphIndentCount: Int = 0,
    val paddingDp: Int = 22,
    val layoutMode: M9LayoutMode = M9LayoutMode.HORIZONTAL,
    val pageAnim: M9PageAnim = M9PageAnim.NONE,
    val readerStyleSelect: Int = 0,
    val readerNightMode: Boolean = false,
    val readerStyleConfigs: List<LegadoReaderStyleConfig> = defaultLegadoReaderStyleConfigs(),
    val cueHighlightColor: Int = 0xFFFFEFF6.toInt(),
    val hideStatusBar: Boolean = false,
    val readBodyToLh: Boolean = true,
    val hideNavigationBar: Boolean = false,
    val showBrightnessView: Boolean = true,
    val brightnessAuto: Boolean = true,
    val brightnessValue: Int = 160,
    val brightnessPanelOnRight: Boolean = false,
    val showReadTitleAddition: Boolean = true,
    val useZhLayout: Boolean = true,
    val textFullJustify: Boolean = true,
    val textBottomJustify: Boolean = true,
    val clickRegionActions: List<ReadView.TapAction> = ReadView.defaultClickRegionActions(),
    val progressByChapter: Boolean = true,
    val keepScreenOn: Boolean = false,
    val noAnimScrollPage: Boolean = false,
    val previewImageByClick: Boolean = false,
    val disableReturnKey: Boolean = false,
    val readBarStyleFollowPage: Boolean = false,
    val playbackBarPinnedVisible: Boolean = false,
    val showRubyText: Boolean = true,
    val preferredCharsetName: String? = null,
    val currentBookUri: String? = null,
    val currentChapterIndex: Int = 0,
    val currentCharPosition: Int = 0
)

internal data class LegadoReaderBookAnchor(
    val chapterIndex: Int,
    val charPosition: Int
)

internal fun loadLegadoReaderPersistedState(context: Context): LegadoReaderPersistedState {
    val raw = context.getSharedPreferences(LEGADO_READER_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .getString(LEGADO_READER_SETTINGS_KEY, null)
        ?: return LegadoReaderPersistedState()
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return LegadoReaderPersistedState()
    val styleConfigs = readStyleConfigs(json.optJSONArray("readerStyleConfigs"))
        ?: defaultLegadoReaderStyleConfigs()
    return LegadoReaderPersistedState(
        textSizeSp = json.optInt("textSizeSp", 20),
        lineSpacingDp = json.optInt("lineSpacingDp", 8),
        paragraphSpacingDp = json.optInt("paragraphSpacingDp", 14),
        letterSpacingDp = json.optInt("letterSpacingDp", 0),
        textWeight = json.optString("textWeight")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { M9TextWeight.valueOf(it) }.getOrNull() }
            ?: M9TextWeight.NORMAL,
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
        readerStyleSelect = json.optInt("readerStyleSelect", 0).coerceIn(0, styleConfigs.lastIndex),
        readerNightMode = json.optBoolean("readerNightMode", false),
        readerStyleConfigs = styleConfigs,
        cueHighlightColor = json.optInt("cueHighlightColor", 0xFFFFEFF6.toInt()),
        hideStatusBar = json.optBoolean("hideStatusBar", false),
        readBodyToLh = json.optBoolean("readBodyToLh", true),
        hideNavigationBar = json.optBoolean("hideNavigationBar", false),
        showBrightnessView = json.optBoolean("showBrightnessView", true),
        brightnessAuto = json.optBoolean("brightnessAuto", true),
        brightnessValue = json.optInt("brightnessValue", 160),
        brightnessPanelOnRight = json.optBoolean("brightnessPanelOnRight", false),
        showReadTitleAddition = json.optBoolean("showReadTitleAddition", true),
        useZhLayout = json.optBoolean("useZhLayout", true),
        textFullJustify = json.optBoolean("textFullJustify", true),
        textBottomJustify = json.optBoolean("textBottomJustify", true),
        clickRegionActions = readClickRegionActions(json.optJSONArray("clickRegionActions"))
            ?: ReadView.defaultClickRegionActions(),
        progressByChapter = json.optBoolean("progressByChapter", true),
        keepScreenOn = json.optBoolean("keepScreenOn", false),
        noAnimScrollPage = json.optBoolean("noAnimScrollPage", false),
        previewImageByClick = json.optBoolean("previewImageByClick", false),
        disableReturnKey = json.optBoolean("disableReturnKey", false),
        readBarStyleFollowPage = json.optBoolean("readBarStyleFollowPage", false),
        playbackBarPinnedVisible = json.optBoolean("playbackBarPinnedVisible", false),
        showRubyText = json.optBoolean("showRubyText", true),
        preferredCharsetName = json.optString("preferredCharsetName").takeIf { it.isNotBlank() },
        currentBookUri = json.optString("currentBookUri").takeIf { it.isNotBlank() },
        currentChapterIndex = json.optInt("currentChapterIndex", 0),
        currentCharPosition = json.optInt("currentCharPosition", 0)
    )
}

private fun readClickRegionActions(array: JSONArray?): List<ReadView.TapAction>? {
    if (array == null || array.length() == 0) return null
    val defaults = ReadView.defaultClickRegionActions()
    val actions = buildList {
        for (index in 0 until array.length()) {
            val value = array.optString(index).takeIf { it.isNotBlank() } ?: continue
            runCatching { ReadView.TapAction.valueOf(value) }.getOrNull()?.let(::add)
        }
    }
    return actions.takeIf { it.size == defaults.size }
}

private fun readStyleConfigs(array: JSONArray?): List<LegadoReaderStyleConfig>? {
    if (array == null || array.length() == 0) return null
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            add(
                LegadoReaderStyleConfig(
                    name = item.optString("name"),
                    bgColor = item.optInt("bgColor", 0xFFF8F1E3.toInt()),
                    textColor = item.optInt("textColor", 0xFF2C241B.toInt()),
                    tipColor = item.optInt("tipColor", 0xFF8F8373.toInt()),
                    bgAlpha = item.optInt("bgAlpha", 100).coerceIn(0, 100),
                    darkStatusIcon = item.optBoolean("darkStatusIcon", true),
                    underline = item.optBoolean("underline", false),
                    bgAssetName = item.optString("bgAssetName").takeIf { it.isNotBlank() },
                    bgImageUri = item.optString("bgImageUri").takeIf { it.isNotBlank() }
                )
            )
        }
    }.takeIf { it.isNotEmpty() }
}

internal fun saveLegadoReaderPersistedState(context: Context, state: LegadoReaderPersistedState) {
    val json = JSONObject().apply {
        put("textSizeSp", state.textSizeSp)
        put("lineSpacingDp", state.lineSpacingDp)
        put("paragraphSpacingDp", state.paragraphSpacingDp)
        put("letterSpacingDp", state.letterSpacingDp)
        put("textWeight", state.textWeight.name)
        put("typefaceIndex", state.typefaceIndex)
        put("paragraphIndentCount", state.paragraphIndentCount)
        put("paddingDp", state.paddingDp)
        put("layoutMode", state.layoutMode.name)
        put("pageAnim", state.pageAnim.name)
        put("readerStyleSelect", state.readerStyleSelect)
        put("readerNightMode", state.readerNightMode)
        put("readerStyleConfigs", JSONArray().apply {
            state.readerStyleConfigs.forEach { style ->
                put(JSONObject().apply {
                    put("name", style.name)
                    put("bgColor", style.bgColor)
                    put("textColor", style.textColor)
                    put("tipColor", style.tipColor)
                    put("bgAlpha", style.bgAlpha)
                    put("darkStatusIcon", style.darkStatusIcon)
                    put("underline", style.underline)
                    put("bgAssetName", style.bgAssetName)
                    put("bgImageUri", style.bgImageUri)
                })
            }
        })
        put("cueHighlightColor", state.cueHighlightColor)
        put("hideStatusBar", state.hideStatusBar)
        put("readBodyToLh", state.readBodyToLh)
        put("hideNavigationBar", state.hideNavigationBar)
        put("showBrightnessView", state.showBrightnessView)
        put("brightnessAuto", state.brightnessAuto)
        put("brightnessValue", state.brightnessValue)
        put("brightnessPanelOnRight", state.brightnessPanelOnRight)
        put("showReadTitleAddition", state.showReadTitleAddition)
        put("useZhLayout", state.useZhLayout)
        put("textFullJustify", state.textFullJustify)
        put("textBottomJustify", state.textBottomJustify)
        put("clickRegionActions", JSONArray().apply {
            state.clickRegionActions.forEach { put(it.name) }
        })
        put("progressByChapter", state.progressByChapter)
        put("keepScreenOn", state.keepScreenOn)
        put("noAnimScrollPage", state.noAnimScrollPage)
        put("previewImageByClick", state.previewImageByClick)
        put("disableReturnKey", state.disableReturnKey)
        put("readBarStyleFollowPage", state.readBarStyleFollowPage)
        put("playbackBarPinnedVisible", state.playbackBarPinnedVisible)
        put("showRubyText", state.showRubyText)
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

internal fun loadLegadoReaderBookAnchor(context: Context, bookUri: String?): LegadoReaderBookAnchor? {
    val key = bookUri?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val raw = context.getSharedPreferences(LEGADO_READER_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .getString(LEGADO_READER_BOOK_ANCHORS_KEY, null)
        ?: return null
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    val item = root.optJSONObject(key) ?: return null
    return LegadoReaderBookAnchor(
        chapterIndex = item.optInt("chapterIndex", 0),
        charPosition = item.optInt("charPosition", 0)
    )
}

internal fun saveLegadoReaderBookAnchor(
    context: Context,
    bookUri: String?,
    anchor: LegadoReaderBookAnchor?
) {
    val key = bookUri?.trim()?.takeIf { it.isNotBlank() } ?: return
    val safeAnchor = anchor ?: return
    val prefs = context.getSharedPreferences(LEGADO_READER_SETTINGS_PREFS, Context.MODE_PRIVATE)
    val root = prefs.getString(LEGADO_READER_BOOK_ANCHORS_KEY, null)
        ?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?: JSONObject()
    root.put(
        key,
        JSONObject().apply {
            put("chapterIndex", safeAnchor.chapterIndex.coerceAtLeast(0))
            put("charPosition", safeAnchor.charPosition.coerceAtLeast(0))
        }
    )
    prefs.edit()
        .putString(LEGADO_READER_BOOK_ANCHORS_KEY, root.toString())
        .apply()
}
