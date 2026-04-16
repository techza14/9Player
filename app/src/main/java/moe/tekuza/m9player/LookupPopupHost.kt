package moe.tekuza.m9player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

@Composable
internal fun LookupPopupHost(
    visible: Boolean,
    session: ReaderLookupSession,
    logTag: String,
    temporarilyHidden: Boolean = false,
    resolveAnchor: (index: Int, layer: ReaderLookupLayer) -> ReaderLookupAnchor? = { _, layer -> layer.anchor },
    onDismissTopLayer: () -> Unit,
    onTruncateToLayer: ((Int) -> Unit)? = null,
    buildActionState: (index: Int, layer: ReaderLookupLayer, isTop: Boolean, isPrevious: Boolean) -> LookupCardActionState,
    contentMaxHeightReserveDp: (index: Int, layer: ReaderLookupLayer, isTop: Boolean, isPrevious: Boolean) -> Int = { _, _, _, _ -> 0 },
    beforeCardContent: @Composable (index: Int, layer: ReaderLookupLayer, isTop: Boolean, isPrevious: Boolean) -> Unit = { _, _, _, _ -> },
    onToggleSection: ((Int, String, Boolean) -> Unit)? = null,
    onDefinitionLookup: ((Int, String, DefinitionLookupTapData) -> Unit)? = null,
    onRangeSelection: ((Int) -> Unit)? = null,
    onPlayAudio: ((Int, GroupedLookupResult) -> Unit)? = null,
    onAddToAnki: ((Int, GroupedLookupResult) -> Unit)? = null,
    onCloseAll: (() -> Unit)? = null
) {
    if (!visible) return
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val gapPx = with(density) { 14.dp.roundToPx() }
    val screenPaddingPx = with(density) { 12.dp.roundToPx() }

    session.layers.forEachIndexed { index, layer ->
        val isTopLayer = index == session.lastIndex
        val isPreviousLayer = index == session.lastIndex - 1
        val effectiveAnchor = resolveAnchor(index, layer)
        val useTopCenterHost = effectiveAnchor == null
        val popupSizeSpec = remember(
            configuration.screenWidthDp,
            configuration.screenHeightDp,
            effectiveAnchor,
            layer.placeBelow,
            layer.preferSidePlacement,
            density.density
        ) {
            computeSharedLookupPopupSizeSpec(
                screenWidthDp = configuration.screenWidthDp,
                screenHeightDp = configuration.screenHeightDp,
                anchor = effectiveAnchor,
                placeBelow = layer.placeBelow,
                preferSidePlacement = layer.preferSidePlacement,
                density = density.density
            )
        }
        val positionProvider = remember(
            effectiveAnchor,
            layer.placeBelow,
            layer.preferSidePlacement,
            popupSizeSpec.preferredDirection,
            gapPx,
            screenPaddingPx
        ) {
            SharedLookupPopupPositionProvider(
                anchor = effectiveAnchor,
                placeBelow = layer.placeBelow,
                preferSidePlacement = layer.preferSidePlacement,
                preferredDirection = popupSizeSpec.preferredDirection,
                gapPx = gapPx,
                screenPaddingPx = screenPaddingPx,
                logTag = logTag
            )
        }
        val baseContentMaxHeightDp = if (useTopCenterHost) {
            if (layer.groupedResults.isEmpty()) {
                (configuration.screenHeightDp * 0.26f).toInt().coerceIn(150, 220)
            } else {
                (configuration.screenHeightDp - 260).coerceIn(240, 560)
            }
        } else {
            popupSizeSpec.contentMaxHeightDp
        }
        val reserveDp = contentMaxHeightReserveDp(index, layer, isTopLayer, isPreviousLayer).coerceAtLeast(0)
        val effectiveContentMaxHeightDp = (baseContentMaxHeightDp - reserveDp).coerceAtLeast(140)
        val popupContent: @Composable () -> Unit = {
            if (temporarilyHidden) {
                Box(
                    modifier = Modifier
                        .size(1.dp)
                        .alpha(0f)
                )
            } else {
                Surface(
                    modifier = Modifier
                        .then(
                            if (useTopCenterHost) {
                                Modifier
                                    .fillMaxWidth(0.96f)
                                    .padding(top = 72.dp)
                            } else {
                                Modifier
                                    .width(popupSizeSpec.widthDp.dp)
                                    .padding(horizontal = 6.dp, vertical = 10.dp)
                            }
                        )
                        .then(
                            if (!isTopLayer && !isPreviousLayer && onTruncateToLayer != null) {
                                Modifier.clickable { onTruncateToLayer(index) }
                            } else {
                                Modifier
                            }
                        ),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = if (isTopLayer) 8.dp else 6.dp,
                    shadowElevation = if (isTopLayer) 10.dp else 6.dp,
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
                    )
                ) {
                    Column(
                        modifier = Modifier,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        beforeCardContent(index, layer, isTopLayer, isPreviousLayer)
                        LookupPopupCardContent(
                            groupedResults = layer.groupedResults,
                            loading = layer.loading,
                            error = layer.error,
                            highlightedDefinitionKey = layer.highlightedDefinitionKey,
                            highlightedDefinitionRects = layer.highlightedDefinitionRects,
                            collapsedSections = layer.collapsedSections,
                            actionState = buildActionState(index, layer, isTopLayer, isPreviousLayer),
                            contentMaxHeightDp = effectiveContentMaxHeightDp,
                            onToggleSection = onToggleSection?.let { callback ->
                                { key, expanded -> callback(index, key, expanded) }
                            },
                            onDefinitionLookup = onDefinitionLookup?.let { callback ->
                                { definitionKey, tapData -> callback(index, definitionKey, tapData) }
                            },
                            onRangeSelection = onRangeSelection?.let { callback ->
                                { callback(index) }
                            },
                            onPlayAudio = onPlayAudio?.let { callback ->
                                { groupedResult -> callback(index, groupedResult) }
                            },
                            onAddToAnki = onAddToAnki?.let { callback ->
                                { groupedResult -> callback(index, groupedResult) }
                            },
                            onCloseAll = if (isTopLayer) onCloseAll else null,
                            onClose = {
                                if (isTopLayer) {
                                    onDismissTopLayer()
                                } else {
                                    onTruncateToLayer?.invoke(index)
                                }
                            }
                        )
                    }
                }
            }
        }

        if (useTopCenterHost) {
            Popup(
                alignment = Alignment.TopCenter,
                onDismissRequest = {
                    if (isTopLayer) onDismissTopLayer()
                },
                properties = PopupProperties(
                    focusable = isTopLayer && !temporarilyHidden && session.size == 1,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = isTopLayer && session.size == 1,
                    clippingEnabled = false
                )
            ) {
                popupContent()
            }
        } else {
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = {
                    if (isTopLayer) onDismissTopLayer()
                },
                properties = PopupProperties(
                    focusable = isTopLayer && !temporarilyHidden && session.size == 1,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = isTopLayer && session.size == 1,
                    clippingEnabled = false
                )
            ) {
                popupContent()
            }
        }
    }
}
