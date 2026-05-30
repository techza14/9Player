package moe.tekuza.m9player

import android.content.Context
import moe.tekuza.m9player.legado.reader.M9LayoutMode
import moe.tekuza.m9player.legado.reader.M9PageAnim
import moe.tekuza.m9player.legado.reader.M9TextWeight
import moe.tekuza.m9player.legado.reader.page.ReadView
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

private const val LEGADO_READER_SETTINGS_PREFS = "legado_reader_settings"
private const val LEGADO_READER_SETTINGS_KEY = "legado_reader_settings_json"
private const val LEGADO_READER_BOOK_ANCHORS_KEY = "legado_reader_book_anchors_json"
private const val LEGADO_READER_SIMULATED_READING_KEY = "legado_reader_simulated_reading_json"
internal const val DEFAULT_LEGADO_READER_STYLE_INDEX = 1

internal data class LegadoReaderStyleConfig(
    val name: String = "",
    val bgColor: Int = 0xFFF8F1E3.toInt(),
    val textColor: Int = 0xFF2C241B.toInt(),
    val tipColor: Int = 0xFF8F8373.toInt(),
    val bgAlpha: Int = 100,
    val darkStatusIcon: Boolean = true,
    val underline: Boolean = false,
    val bgAssetName: String? = null,
    val bgImageUri: String? = null,
    val textSizeSp: Int = 20,
    val lineSpacingDp: Int = 8,
    val paragraphSpacingDp: Int = 14,
    val letterSpacingDp: Int = 0,
    val textWeight: M9TextWeight = M9TextWeight.NORMAL,
    val typefaceIndex: Int = 0,
    val paragraphIndentCount: Int = 0,
    val paddingDp: Int = 22
)

internal fun defaultLegadoReaderStyleConfigs(): List<LegadoReaderStyleConfig> = listOf(
    LegadoReaderStyleConfig(
        "微信读书",
        0xFFC0EDC6.toInt(),
        0xFF0B0B0B.toInt(),
        0xFF606060.toInt(),
        darkStatusIcon = true,
        textSizeSp = 24,
        lineSpacingDp = 10,
        paragraphSpacingDp = 6,
        paragraphIndentCount = 2,
        paddingDp = 22
    ),
    LegadoReaderStyleConfig("预设1", 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF777777.toInt(), darkStatusIcon = true),
    LegadoReaderStyleConfig("预设2", 0xFFDDC090.toInt(), 0xFF3E3422.toInt(), 0xFF7B6543.toInt(), darkStatusIcon = true),
    LegadoReaderStyleConfig("预设3", 0xFFC2D8AA.toInt(), 0xFF596C44.toInt(), 0xFF758A60.toInt(), darkStatusIcon = false),
    LegadoReaderStyleConfig("预设4", 0xFFDBB8E2.toInt(), 0xFF68516C.toInt(), 0xFF87678C.toInt(), darkStatusIcon = false),
    LegadoReaderStyleConfig("预设5", 0xFFABCEE0.toInt(), 0xFF3D4C54.toInt(), 0xFF637985.toInt(), darkStatusIcon = false)
)

internal enum class ReaderChapterSourceMode {
    BOOK,
    M4B
}

internal enum class ReaderBodyTitleMode {
    LEFT,
    CENTER,
    HIDE
}

internal enum class ReaderHeaderMode {
    HIDE_WHEN_STATUS_BAR_SHOW,
    SHOW,
    HIDE
}

internal enum class ReaderFooterMode {
    SHOW,
    HIDE
}

internal enum class ReaderTipContent {
    NONE,
    BOOK_NAME,
    CHAPTER_TITLE,
    TIME,
    BATTERY_PERCENTAGE,
    PAGE,
    TOTAL_PROGRESS,
    CHAPTER_PROGRESS,
    PAGE_AND_TOTAL,
    TIME_BATTERY_PERCENTAGE
}

internal enum class ReaderTipColorMode {
    FOLLOW_CONTENT,
    CUSTOM
}

internal enum class ReaderTipDividerColorMode {
    DEFAULT,
    FOLLOW_CONTENT,
    CUSTOM
}

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
    val readerStyleSelect: Int = DEFAULT_LEGADO_READER_STYLE_INDEX,
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
    val bodyTitleMode: ReaderBodyTitleMode = ReaderBodyTitleMode.LEFT,
    val bodyTitleSizeAddSp: Int = 0,
    val bodyTitleTopSpacingDp: Int = 0,
    val bodyTitleBottomSpacingDp: Int = 0,
    val headerMode: ReaderHeaderMode = ReaderHeaderMode.HIDE_WHEN_STATUS_BAR_SHOW,
    val footerMode: ReaderFooterMode = ReaderFooterMode.SHOW,
    val tipHeaderLeft: ReaderTipContent = ReaderTipContent.CHAPTER_TITLE,
    val tipHeaderMiddle: ReaderTipContent = ReaderTipContent.NONE,
    val tipHeaderRight: ReaderTipContent = ReaderTipContent.TIME,
    val tipFooterLeft: ReaderTipContent = ReaderTipContent.BOOK_NAME,
    val tipFooterMiddle: ReaderTipContent = ReaderTipContent.NONE,
    val tipFooterRight: ReaderTipContent = ReaderTipContent.PAGE_AND_TOTAL,
    val tipColorMode: ReaderTipColorMode = ReaderTipColorMode.FOLLOW_CONTENT,
    val tipDividerColorMode: ReaderTipDividerColorMode = ReaderTipDividerColorMode.DEFAULT,
    val tipDividerColor: Int = 0x1F000000,
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
    val crossPageCueWindowEnabled: Boolean = true,
    val stopPlaybackOnImage: Boolean = false,
    val imagePauseSeconds: Int = 0,
    val verticalControlDirectionReversed: Boolean = false,
    val verticalProgressDirectionReversed: Boolean = false,
    val selectionPrimaryActionKey: String = "default",
    val chapterSourceMode: ReaderChapterSourceMode = ReaderChapterSourceMode.BOOK,
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

internal data class SimulatedReadingConfig(
    val enabled: Boolean = false,
    val startEpochDay: Long = currentSimulatedReadingEpochDay(),
    val startChapter: Int = 1,
    val dailyChapters: Int = 1
)

internal fun currentSimulatedReadingEpochDay(): Long = LocalDate.now().toEpochDay()

internal fun simulatedReadingDateLabel(epochDay: Long): String {
    return runCatching { LocalDate.ofEpochDay(epochDay).toString() }
        .getOrDefault(LocalDate.now().toString())
}

internal fun simulatedReadingUnlockedChapterCount(
    config: SimulatedReadingConfig,
    realChapterCount: Int,
    todayEpochDay: Long = currentSimulatedReadingEpochDay()
): Int {
    if (realChapterCount <= 0) return 0
    if (!config.enabled) return realChapterCount
    val daysPassed = (todayEpochDay - config.startEpochDay).coerceAtLeast(0L)
    val unlocked = config.startChapter.coerceAtLeast(1).toLong() +
        daysPassed * config.dailyChapters.coerceAtLeast(1).toLong()
    return unlocked.coerceIn(1L, realChapterCount.toLong()).toInt()
}

internal fun loadLegadoReaderPersistedState(context: Context): LegadoReaderPersistedState {
    val raw = context.getSharedPreferences(LEGADO_READER_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .getString(LEGADO_READER_SETTINGS_KEY, null)
        ?: return LegadoReaderPersistedState()
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return LegadoReaderPersistedState()
    val selectedStyleIndex = json.optInt("readerStyleSelect", DEFAULT_LEGADO_READER_STYLE_INDEX)
    val textSizeSp = json.optInt("textSizeSp", 20)
    val lineSpacingDp = json.optInt("lineSpacingDp", 8)
    val paragraphSpacingDp = json.optInt("paragraphSpacingDp", 14)
    val letterSpacingDp = json.optInt("letterSpacingDp", 0)
    val textWeight = json.optString("textWeight")
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { M9TextWeight.valueOf(it) }.getOrNull() }
        ?: M9TextWeight.NORMAL
    val typefaceIndex = json.optInt("typefaceIndex", 0)
    val paragraphIndentCount = json.optInt("paragraphIndentCount", 0)
    val paddingDp = json.optInt("paddingDp", 22)
    val selectedStyleLayoutFallback = LegadoReaderStyleConfig(
        textSizeSp = textSizeSp,
        lineSpacingDp = lineSpacingDp,
        paragraphSpacingDp = paragraphSpacingDp,
        letterSpacingDp = letterSpacingDp,
        textWeight = textWeight,
        typefaceIndex = typefaceIndex,
        paragraphIndentCount = paragraphIndentCount,
        paddingDp = paddingDp
    )
    val styleConfigs = readStyleConfigs(
        json.optJSONArray("readerStyleConfigs"),
        selectedStyleIndex,
        selectedStyleLayoutFallback
    )
        ?: defaultLegadoReaderStyleConfigs()
    return LegadoReaderPersistedState(
        textSizeSp = textSizeSp,
        lineSpacingDp = lineSpacingDp,
        paragraphSpacingDp = paragraphSpacingDp,
        letterSpacingDp = letterSpacingDp,
        textWeight = textWeight,
        typefaceIndex = typefaceIndex,
        paragraphIndentCount = paragraphIndentCount,
        paddingDp = paddingDp,
        layoutMode = json.optString("layoutMode")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { M9LayoutMode.valueOf(it) }.getOrNull() }
            ?: M9LayoutMode.HORIZONTAL,
        pageAnim = json.optString("pageAnim")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { M9PageAnim.valueOf(it) }.getOrNull() }
            ?: M9PageAnim.NONE,
        readerStyleSelect = selectedStyleIndex.coerceIn(0, styleConfigs.lastIndex),
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
        bodyTitleMode = json.optEnum("bodyTitleMode", ReaderBodyTitleMode.LEFT),
        bodyTitleSizeAddSp = json.optInt("bodyTitleSizeAddSp", 0).coerceIn(0, 10),
        bodyTitleTopSpacingDp = json.optInt("bodyTitleTopSpacingDp", 0).coerceIn(0, 100),
        bodyTitleBottomSpacingDp = json.optInt("bodyTitleBottomSpacingDp", 0).coerceIn(0, 100),
        headerMode = json.optEnum(
            "headerMode",
            if (json.optBoolean("showReadTitleAddition", true)) {
                ReaderHeaderMode.HIDE_WHEN_STATUS_BAR_SHOW
            } else {
                ReaderHeaderMode.HIDE
            }
        ),
        footerMode = json.optEnum(
            "footerMode",
            if (json.optBoolean("showReadTitleAddition", true)) ReaderFooterMode.SHOW else ReaderFooterMode.HIDE
        ),
        tipHeaderLeft = json.optTipContent(
            "tipHeaderLeft",
            if (json.optBoolean("showHeaderTitle", true)) ReaderTipContent.CHAPTER_TITLE else ReaderTipContent.NONE
        ),
        tipHeaderMiddle = json.optTipContent("tipHeaderMiddle", ReaderTipContent.NONE),
        tipHeaderRight = json.optTipContent(
            "tipHeaderRight",
            if (json.optBoolean("showHeaderClock", true)) ReaderTipContent.TIME else ReaderTipContent.NONE
        ),
        tipFooterLeft = json.optTipContent(
            "tipFooterLeft",
            ReaderTipContent.BOOK_NAME
        ),
        tipFooterMiddle = json.optTipContent("tipFooterMiddle", ReaderTipContent.NONE),
        tipFooterRight = json.optTipContent(
            "tipFooterRight",
            when {
                json.has("tipFooterRight") -> ReaderTipContent.PAGE_AND_TOTAL
                json.optBoolean("showFooterPageNumber", true) && json.optBoolean("showFooterProgress", true) ->
                    ReaderTipContent.PAGE_AND_TOTAL
                json.optBoolean("showFooterPageNumber", true) -> ReaderTipContent.PAGE
                json.optBoolean("showFooterProgress", true) -> ReaderTipContent.TOTAL_PROGRESS
                else -> ReaderTipContent.NONE
            }
        ),
        tipColorMode = json.optEnum("tipColorMode", ReaderTipColorMode.FOLLOW_CONTENT),
        tipDividerColorMode = json.optEnum("tipDividerColorMode", ReaderTipDividerColorMode.DEFAULT),
        tipDividerColor = json.optInt("tipDividerColor", 0x1F000000),
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
        crossPageCueWindowEnabled = json.optBoolean("crossPageCueWindowEnabled", true),
        stopPlaybackOnImage = json.optBoolean("stopPlaybackOnImage", false),
        imagePauseSeconds = json.optInt("imagePauseSeconds", 0).coerceIn(0, 300),
        verticalControlDirectionReversed = json.optBoolean("verticalControlDirectionReversed", false),
        verticalProgressDirectionReversed = json.optBoolean("verticalProgressDirectionReversed", false),
        selectionPrimaryActionKey = json.optString("selectionPrimaryActionKey")
            .takeIf { it.isNotBlank() }
            ?: "default",
        chapterSourceMode = json.optString("chapterSourceMode")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { ReaderChapterSourceMode.valueOf(it) }.getOrNull() }
            ?: ReaderChapterSourceMode.BOOK,
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

private inline fun <reified T : Enum<T>> JSONObject.optEnum(name: String, fallback: T): T {
    return optString(name)
        .takeIf { it.isNotBlank() }
        ?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
        ?: fallback
}

private fun JSONObject.optTipContent(name: String, fallback: ReaderTipContent): ReaderTipContent {
    return when (val raw = optString(name).takeIf { it.isNotBlank() }) {
        "BATTERY" -> ReaderTipContent.BATTERY_PERCENTAGE
        "TIME_BATTERY" -> ReaderTipContent.TIME_BATTERY_PERCENTAGE
        null -> fallback
        else -> runCatching { ReaderTipContent.valueOf(raw) }.getOrNull() ?: fallback
    }
}

private fun readStyleConfigs(
    array: JSONArray?,
    selectedStyleIndex: Int,
    selectedStyleLayoutFallback: LegadoReaderStyleConfig
): List<LegadoReaderStyleConfig>? {
    if (array == null || array.length() == 0) return null
    val defaults = defaultLegadoReaderStyleConfigs()
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val fallback = defaults.getOrNull(index) ?: LegadoReaderStyleConfig()
            val layoutFallback = if (index == selectedStyleIndex && !item.has("textSizeSp")) {
                selectedStyleLayoutFallback
            } else {
                fallback
            }
            add(
                LegadoReaderStyleConfig(
                    name = item.optString("name", fallback.name),
                    bgColor = item.optInt("bgColor", fallback.bgColor),
                    textColor = item.optInt("textColor", fallback.textColor),
                    tipColor = item.optInt("tipColor", fallback.tipColor),
                    bgAlpha = item.optInt("bgAlpha", fallback.bgAlpha).coerceIn(0, 100),
                    darkStatusIcon = item.optBoolean("darkStatusIcon", fallback.darkStatusIcon),
                    underline = item.optBoolean("underline", fallback.underline),
                    bgAssetName = item.optString("bgAssetName").takeIf { it.isNotBlank() },
                    bgImageUri = item.optString("bgImageUri").takeIf { it.isNotBlank() },
                    textSizeSp = item.optInt("textSizeSp", layoutFallback.textSizeSp),
                    lineSpacingDp = item.optInt("lineSpacingDp", layoutFallback.lineSpacingDp),
                    paragraphSpacingDp = item.optInt("paragraphSpacingDp", layoutFallback.paragraphSpacingDp),
                    letterSpacingDp = item.optInt("letterSpacingDp", layoutFallback.letterSpacingDp),
                    textWeight = item.optString("textWeight")
                        .takeIf { it.isNotBlank() }
                        ?.let { runCatching { M9TextWeight.valueOf(it) }.getOrNull() }
                        ?: layoutFallback.textWeight,
                    typefaceIndex = item.optInt("typefaceIndex", layoutFallback.typefaceIndex),
                    paragraphIndentCount = item.optInt(
                        "paragraphIndentCount",
                        layoutFallback.paragraphIndentCount
                    ),
                    paddingDp = item.optInt("paddingDp", layoutFallback.paddingDp)
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
                    put("textSizeSp", style.textSizeSp)
                    put("lineSpacingDp", style.lineSpacingDp)
                    put("paragraphSpacingDp", style.paragraphSpacingDp)
                    put("letterSpacingDp", style.letterSpacingDp)
                    put("textWeight", style.textWeight.name)
                    put("typefaceIndex", style.typefaceIndex)
                    put("paragraphIndentCount", style.paragraphIndentCount)
                    put("paddingDp", style.paddingDp)
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
        put("bodyTitleMode", state.bodyTitleMode.name)
        put("bodyTitleSizeAddSp", state.bodyTitleSizeAddSp)
        put("bodyTitleTopSpacingDp", state.bodyTitleTopSpacingDp)
        put("bodyTitleBottomSpacingDp", state.bodyTitleBottomSpacingDp)
        put("headerMode", state.headerMode.name)
        put("footerMode", state.footerMode.name)
        put("tipHeaderLeft", state.tipHeaderLeft.name)
        put("tipHeaderMiddle", state.tipHeaderMiddle.name)
        put("tipHeaderRight", state.tipHeaderRight.name)
        put("tipFooterLeft", state.tipFooterLeft.name)
        put("tipFooterMiddle", state.tipFooterMiddle.name)
        put("tipFooterRight", state.tipFooterRight.name)
        put("tipColorMode", state.tipColorMode.name)
        put("tipDividerColorMode", state.tipDividerColorMode.name)
        put("tipDividerColor", state.tipDividerColor)
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
        put("crossPageCueWindowEnabled", state.crossPageCueWindowEnabled)
        put("stopPlaybackOnImage", state.stopPlaybackOnImage)
        put("imagePauseSeconds", state.imagePauseSeconds)
        put("verticalControlDirectionReversed", state.verticalControlDirectionReversed)
        put("verticalProgressDirectionReversed", state.verticalProgressDirectionReversed)
        put("selectionPrimaryActionKey", state.selectionPrimaryActionKey)
        put("chapterSourceMode", state.chapterSourceMode.name)
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

internal fun loadSimulatedReadingConfig(context: Context, bookUri: String?): SimulatedReadingConfig {
    val key = bookUri?.trim()?.takeIf { it.isNotBlank() } ?: return SimulatedReadingConfig()
    val raw = context.getSharedPreferences(LEGADO_READER_SETTINGS_PREFS, Context.MODE_PRIVATE)
        .getString(LEGADO_READER_SIMULATED_READING_KEY, null)
        ?: return SimulatedReadingConfig()
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return SimulatedReadingConfig()
    val item = root.optJSONObject(key) ?: return SimulatedReadingConfig()
    return SimulatedReadingConfig(
        enabled = item.optBoolean("enabled", false),
        startEpochDay = item.optLong("startEpochDay", currentSimulatedReadingEpochDay()),
        startChapter = item.optInt("startChapter", 1).coerceAtLeast(1),
        dailyChapters = item.optInt("dailyChapters", 1).coerceAtLeast(1)
    )
}

internal fun saveSimulatedReadingConfig(
    context: Context,
    bookUri: String?,
    config: SimulatedReadingConfig
) {
    val key = bookUri?.trim()?.takeIf { it.isNotBlank() } ?: return
    val prefs = context.getSharedPreferences(LEGADO_READER_SETTINGS_PREFS, Context.MODE_PRIVATE)
    val root = prefs.getString(LEGADO_READER_SIMULATED_READING_KEY, null)
        ?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?: JSONObject()
    root.put(
        key,
        JSONObject().apply {
            put("enabled", config.enabled)
            put("startEpochDay", config.startEpochDay)
            put("startChapter", config.startChapter.coerceAtLeast(1))
            put("dailyChapters", config.dailyChapters.coerceAtLeast(1))
        }
    )
    prefs.edit()
        .putString(LEGADO_READER_SIMULATED_READING_KEY, root.toString())
        .apply()
}
