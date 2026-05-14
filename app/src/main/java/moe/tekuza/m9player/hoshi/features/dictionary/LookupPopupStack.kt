package moe.tekuza.m9player.hoshi.features.dictionary

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import android.util.Log
import de.manhhao.hoshi.LookupResult
import moe.tekuza.m9player.hoshi.dictionary.LookupEngine
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionData
import moe.tekuza.m9player.AnkiDuplicateCheckResult
import java.util.UUID

internal data class LookupPopupOptions(
    val isVertical: Boolean,
    val isFullWidth: Boolean = false,
    val width: Int = 320,
    val height: Int = 250,
    val swipeToDismiss: Boolean = false,
    val swipeThreshold: Int = 40,
    val topInset: Double = 0.0,
    val bottomInset: Double = 0.0,
    val dictionarySettings: DictionarySettings = DictionarySettings(),
    val darkMode: Boolean = false,
    val eInkMode: Boolean = false,
    val audioSettings: moe.tekuza.m9player.AudiobookSettingsConfig = moe.tekuza.m9player.AudiobookSettingsConfig(),
    val showRangeSelection: Boolean = false,
    val showPlayAudio: Boolean = false,
    val popupActionBar: Boolean = false,
)

internal data class LookupPopupItem(
    val id: String = UUID.randomUUID().toString(),
    val state: LookupPopupState,
    val clearSelectionSignal: Int = 0,
)

internal fun createLookupPopupItem(
    selection: ReaderSelectionData,
    options: LookupPopupOptions,
    dictionaryStyles: Map<String, String>? = null,
    lookup: (String, Int, Int) -> List<de.manhhao.hoshi.LookupResult> = LookupEngine::lookup,
): Pair<LookupPopupItem, Int>? {
    val settings = options.dictionarySettings.normalized()
    val styles = dictionaryStyles ?: currentDictionaryStyles()
    Log.d(
        "HoshiLookupPopup",
        "createLookupPopupItem selection='${selection.text.take(32)}' selectionLen=${selection.text.length} " +
            "rect=${selection.rect.x},${selection.rect.y} ${selection.rect.width}x${selection.rect.height} " +
            "showRange=${options.showRangeSelection} showAudio=${options.showPlayAudio} popupActionBar=${options.popupActionBar}"
    )
    val results = runCatching {
        lookup(selection.text, settings.maxResults, settings.scanLength)
    }.getOrDefault(emptyList())
    val first = results.firstOrNull()
    if (first == null) {
        Log.d(
            "HoshiLookupPopup",
            "createLookupPopupItem empty selection='${selection.text.take(32)}' scanLength=${settings.scanLength} maxResults=${settings.maxResults}"
        )
        return null
    }
    Log.d(
        "HoshiLookupPopup",
        "createLookupPopupItem result selection='${selection.text.take(32)}' firstTerm='${first.matched.take(32)}' results=${results.size} matchedLength=${first.matched.codePointCount(0, first.matched.length)}"
    )
    return LookupPopupItem(
        state = LookupPopupState(
            selection = selection,
            results = results,
            dictionaryStyles = styles,
            dictionarySettings = settings,
            isVertical = options.isVertical,
            isFullWidth = options.isFullWidth,
            width = options.width,
            height = options.height,
            swipeToDismiss = options.swipeToDismiss,
            swipeThreshold = options.swipeThreshold,
            topInset = options.topInset,
            bottomInset = options.bottomInset,
            darkMode = options.darkMode,
            eInkMode = options.eInkMode,
            audioSettings = options.audioSettings,
            showRangeSelection = options.showRangeSelection,
            showPlayAudio = options.showPlayAudio,
            popupActionBar = options.popupActionBar,
        ),
    ) to first.matched.codePointCount(0, first.matched.length)
}

internal fun currentDictionaryStyles(): Map<String, String> =
    runCatching {
        LookupEngine.getStyles().associate { it.dictName to it.styles }
    }.getOrDefault(emptyMap())

internal fun closeChildPopups(
    popups: List<LookupPopupItem>,
    parentIndex: Int,
): List<LookupPopupItem> = popups.take(parentIndex + 1)

internal fun dismissPopupAt(
    popups: List<LookupPopupItem>,
    index: Int,
): List<LookupPopupItem> =
    if (index == 0) {
        emptyList()
    } else {
        closeChildPopups(popups, index - 1).mapIndexed { popupIndex, popup ->
            if (popupIndex == index - 1) {
                popup.copy(clearSelectionSignal = popup.clearSelectionSignal + 1)
            } else {
                popup
            }
        }
    }

@Composable
internal fun LookupPopupStackView(
    popups: List<LookupPopupItem>,
    onPopupsChange: (List<LookupPopupItem>) -> Unit,
    lookupChildPopup: (ReaderSelectionData) -> Pair<LookupPopupItem, Int>?,
    onRangeSelection: (() -> Unit)? = null,
    onPlayWordAudio: ((String, String?, String?) -> Unit)? = null,
    onMineEntry: ((String) -> Boolean)? = null,
    onDuplicateCheck: ((String) -> AnkiDuplicateCheckResult)? = null,
    onViewDuplicate: ((List<Long>) -> Boolean)? = null,
    onCloseAll: (() -> Unit)? = null,
    onLookupRedirect: ((String) -> List<LookupResult>)? = null,
    modifier: Modifier = Modifier,
    onRootPopupDismissed: () -> Unit = {},
    ) {
    popups.forEachIndexed { index, popup ->
        key(popup.id) {
            Log.d(
                "HoshiLookupPopup",
                "stack view index=$index size=${popups.size} showActionBar=${popup.state.popupActionBar} showCloseAll=${onCloseAll != null && index == popups.lastIndex && popups.size > 1} popupActionBar=${popup.state.popupActionBar}"
            )
            Log.d(
                "AnkiExportDebug",
                "hoshiStack callbacks index=$index size=${popups.size} hasMineEntry=${onMineEntry != null} hasDuplicateCheck=${onDuplicateCheck != null}"
            )
            LookupPopupView(
                state = popup.state,
                clearSelectionSignal = popup.clearSelectionSignal,
                onTapOutside = {},
                onSwipeDismiss = {
                    if (index == 0) onRootPopupDismissed()
                    onPopupsChange(dismissPopupAt(popups, index))
                },
                onTextSelected = { selection ->
                    val nextPopups = closeChildPopups(popups, index)
                    lookupChildPopup(selection)?.let { (childPopup, highlightCount) ->
                        onPopupsChange(nextPopups + childPopup)
                        highlightCount
                    }
                },
                onRangeSelection = onRangeSelection,
                onPlayWordAudio = onPlayWordAudio,
                onMineEntry = onMineEntry,
                onDuplicateCheck = onDuplicateCheck,
                onViewDuplicate = onViewDuplicate,
                onCloseAll = onCloseAll,
                showActionBar = popup.state.popupActionBar,
                showCloseAll = onCloseAll != null && index == popups.lastIndex && popups.size > 1,
                onLookupRedirect = onLookupRedirect ?: { query ->
                    LookupEngine.lookup(
                        query,
                        popup.state.dictionarySettings.maxResults,
                        popup.state.dictionarySettings.scanLength,
                    )
                },
                onLookupRedirected = { redirectSelection ->
                    val nextPopups = closeChildPopups(popups, index)
                    lookupChildPopup(redirectSelection)?.let { (childPopup, _) ->
                        Log.d(
                            "HoshiLookupPopup",
                            "stack push redirect parent=$index nextSize=${nextPopups.size + 1} query='${redirectSelection.text.take(32)}' rect=${redirectSelection.rect.x},${redirectSelection.rect.y} ${redirectSelection.rect.width}x${redirectSelection.rect.height}"
                        )
                        onPopupsChange(nextPopups + childPopup)
                    }
                },
                modifier = modifier.fillMaxSize(),
            )
        }
    }
}
