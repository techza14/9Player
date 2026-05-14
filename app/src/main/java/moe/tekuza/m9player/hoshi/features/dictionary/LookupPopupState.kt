package moe.tekuza.m9player.hoshi.features.dictionary

import de.manhhao.hoshi.LookupResult
import moe.tekuza.m9player.AudiobookSettingsConfig
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionData
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionRect

internal data class LookupPopupState(
    val selection: ReaderSelectionData,
    val results: List<LookupResult>,
    val avoidRects: List<ReaderSelectionRect> = emptyList(),
    val dictionaryStyles: Map<String, String> = emptyMap(),
    val dictionarySettings: DictionarySettings = DictionarySettings(),
    val isVertical: Boolean = true,
    val isFullWidth: Boolean = false,
    val width: Int = 320,
    val height: Int = 250,
    val swipeToDismiss: Boolean = false,
    val swipeThreshold: Int = 40,
    val topInset: Double = 0.0,
    val bottomInset: Double = 0.0,
    val darkMode: Boolean = false,
    val eInkMode: Boolean = false,
    val audioSettings: AudiobookSettingsConfig = AudiobookSettingsConfig(),
    val showRangeSelection: Boolean = false,
    val showPlayAudio: Boolean = false,
    val popupActionBar: Boolean = false,
)
