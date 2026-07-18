package moe.tekuza.m9player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.ScrollState
import kotlinx.coroutines.delay
import kotlin.math.abs

private fun dictionarySlotTop(
    items: List<CombinedDictionaryItem>,
    index: Int,
    baseTop: Float,
    heights: Map<String, Float>,
    spacing: Float,
): Float {
    var top = baseTop
    items.take(index.coerceAtLeast(0)).forEach { item ->
        top += (heights[item.id] ?: 0f) + spacing
    }
    return top
}

private fun dictionarySlotTarget(
    items: List<CombinedDictionaryItem>,
    draggedCenterY: Float,
    baseTop: Float,
    heights: Map<String, Float>,
    spacing: Float,
): Int {
    if (items.isEmpty()) return -1
    return items.indices.minByOrNull { index ->
        val center = dictionarySlotTop(items, index, baseTop, heights, spacing) +
            (heights[items[index].id] ?: 0f) / 2f
        abs(center - draggedCenterY)
    } ?: -1
}

@Composable
internal fun DictionaryManagementCard(
    context: android.content.Context = LocalContext.current,
    dictionaryScrollState: ScrollState? = null,
    dictionaryCount: Int,
    showHeader: Boolean = true,
    containerColor: Color = HoshiPanelBackground,
    itemContainerColor: Color = HoshiSoftCardBackground,
    dictionaryLoading: Boolean,
    dictionaryProgressText: String?,
    dictionaryProgressValue: Float?,
    dictionaryError: String?,
    showDictionaryManager: Boolean,
    showDictionaryDeleteActions: Boolean,
    dictionaryRefs: List<PersistedDictionaryRef>,
    onImportClick: () -> Unit,
    onShowDictionaryManagerToggle: () -> Unit,
    onShowDictionaryDeleteActionsToggle: () -> Unit,
    onMoveImportedDictionary: (dictionaryId: String, toIndex: Int) -> Unit,
    onRemoveImportedDictionary: (index: Int) -> Unit,
    onSetImportedDictionaryEnabled: (dictionaryId: String, enabled: Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showHeader) {
                Text(stringResource(R.string.dictionary_title))
                Text(stringResource(R.string.dictionary_summary, dictionaryCount))
            }

            if (dictionaryLoading) {
                val progressPercent = dictionaryProgressValue?.let { (it.coerceIn(0f, 1f) * 100).toInt() }
                Text(
                    if (progressPercent != null) {
                        "${dictionaryProgressText ?: stringResource(R.string.dictionary_importing)} · $progressPercent%"
                    } else {
                        dictionaryProgressText ?: stringResource(R.string.dictionary_importing)
                    }
                )
                if (dictionaryProgressValue != null) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { dictionaryProgressValue.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (dictionaryError != null) {
                Text(stringResource(R.string.dictionary_error, dictionaryError), color = androidx.compose.material3.MaterialTheme.colorScheme.error)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onImportClick,
                    enabled = !dictionaryLoading
                ) {
                    Text(stringResource(R.string.dictionary_import))
                }
                OutlinedButton(onClick = onShowDictionaryManagerToggle) {
                    Text(
                        if (showDictionaryManager) {
                            stringResource(R.string.dictionary_hide_list)
                        } else {
                            stringResource(R.string.dictionary_show_list)
                        }
                    )
                }
                OutlinedButton(onClick = onShowDictionaryDeleteActionsToggle) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.common_delete),
                        tint = if (showDictionaryDeleteActions) androidx.compose.material3.MaterialTheme.colorScheme.error else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (showDictionaryManager) {
                val combinedItems = remember(context, dictionaryRefs, dictionaryLoading) {
                    buildCombinedDictionaryItems(
                        context = context,
                        dictionaryRefs = dictionaryRefs
                    )
                }
                var draggedDictionaryId by remember { mutableStateOf<String?>(null) }
                var dragStartIndex by remember { mutableIntStateOf(-1) }
                var dragTargetIndex by remember { mutableIntStateOf(-1) }
                var dragOffsetY by remember { mutableFloatStateOf(0f) }
                var dragPointerY by remember { mutableFloatStateOf(0f) }
                var dragInitialTop by remember { mutableFloatStateOf(0f) }
                var dragHeights by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
                var listRootBounds by remember { mutableStateOf<Rect?>(null) }
                val itemRootBounds = remember { mutableMapOf<String, Rect>() }
                val itemLocalBounds = remember { mutableMapOf<String, Rect>() }
                val density = LocalDensity.current
                val itemSpacing = with(density) { 8.dp.toPx() }

                fun resetDrag() {
                    draggedDictionaryId = null
                    dragStartIndex = -1
                    dragTargetIndex = -1
                    dragOffsetY = 0f
                    dragPointerY = 0f
                    dragInitialTop = 0f
                    dragHeights = emptyMap()
                }

                fun updateDrag(deltaY: Float) {
                    val id = draggedDictionaryId ?: return
                    val currentIndex = dragStartIndex
                    val height = dragHeights[id] ?: return
                    if (currentIndex !in combinedItems.indices) return
                    dragOffsetY += deltaY
                    val draggedCenterY = dragInitialTop + dragOffsetY + height / 2f
                    val targetIndex = dictionarySlotTarget(
                        items = combinedItems,
                        draggedCenterY = draggedCenterY,
                        baseTop = itemLocalBounds[combinedItems.first().id]?.top ?: 0f,
                        heights = dragHeights,
                        spacing = itemSpacing,
                    )
                    if (targetIndex != dragTargetIndex) {
                        dragTargetIndex = targetIndex
                    }
                }

                val onDragStart = rememberUpdatedState<(String, Offset) -> Unit> { id, position ->
                    if (!dictionaryLoading) {
                        val index = combinedItems.indexOfFirst { it.id == id }
                        val bounds = itemRootBounds[id]
                        val localBounds = combinedItems.mapNotNull { item ->
                            itemLocalBounds[item.id]?.let { item.id to it }
                        }.toMap()
                        if (index >= 0 && bounds != null && localBounds.size == combinedItems.size) {
                            val heights = localBounds.mapValues { (_, rect) -> rect.height }
                            draggedDictionaryId = id
                            dragStartIndex = index
                            dragTargetIndex = index
                            dragOffsetY = 0f
                            dragPointerY = bounds.top + position.y
                            dragInitialTop = localBounds[id]?.top ?: 0f
                            dragHeights = heights
                        }
                    }
                }
                val onDragDelta = rememberUpdatedState<(String, Float) -> Unit> { id, deltaY ->
                    if (draggedDictionaryId == id) {
                        dragPointerY += deltaY
                        updateDrag(deltaY)
                    }
                }
                val onDragEnd = rememberUpdatedState<(String) -> Unit> { id ->
                    if (draggedDictionaryId == id) {
                        val initialIndex = dragStartIndex
                        val finalIndex = dragTargetIndex
                        resetDrag()
                        if (initialIndex >= 0 && finalIndex >= 0 && finalIndex != initialIndex) {
                            onMoveImportedDictionary(id, finalIndex)
                        }
                    }
                }
                val onDragCancel = rememberUpdatedState<(String) -> Unit> { id ->
                    if (draggedDictionaryId == id) resetDrag()
                }

                LaunchedEffect(draggedDictionaryId, dictionaryScrollState) {
                    val scrollState = dictionaryScrollState ?: return@LaunchedEffect
                    val edgeThreshold = with(density) { 72.dp.toPx() }
                    val maxDelta = with(density) { 8.dp.toPx() }
                    while (draggedDictionaryId != null) {
                        val pointerY = dragPointerY
                        val viewport = listRootBounds
                        val viewportStart = viewport?.top ?: 0f
                        val viewportEnd = viewport?.bottom ?: 0f
                        val firstItemTop = combinedItems.firstOrNull()
                            ?.let { itemRootBounds[it.id]?.top }
                        val delta = when {
                            pointerY <= viewportStart + edgeThreshold &&
                                firstItemTop != null &&
                                firstItemTop < viewportStart &&
                                scrollState.value > 0 -> {
                                val requested = maxDelta * (
                                    (viewportStart + edgeThreshold - pointerY) / edgeThreshold
                                ).coerceIn(0f, 1f)
                                -requested.coerceAtMost(viewportStart - firstItemTop)
                            }
                            viewportEnd > viewportStart &&
                                pointerY >= viewportEnd - edgeThreshold &&
                                scrollState.value < scrollState.maxValue -> {
                                maxDelta * (
                                    (pointerY - (viewportEnd - edgeThreshold)) / edgeThreshold
                                ).coerceIn(0f, 1f)
                            }
                            else -> 0f
                        }.coerceIn(-maxDelta, maxDelta)
                        if (delta != 0f) {
                            val consumed = scrollState.scrollBy(delta)
                            if (consumed != 0f) {
                                updateDrag(consumed)
                            }
                        }
                        delay(16)
                    }
                }

                if (combinedItems.isEmpty()) {
                    Text(stringResource(R.string.dictionary_empty))
                } else {
                    Column(
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            listRootBounds = coordinates.boundsInRoot()
                        },
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        combinedItems.forEachIndexed { importedIndex, item ->
                        key(item.id) {
                            val isDragging = draggedDictionaryId == item.id
                            val displacement = when {
                                dragStartIndex < dragTargetIndex && importedIndex in (dragStartIndex + 1)..dragTargetIndex -> {
                                    -((dragHeights[draggedDictionaryId] ?: 0f) + itemSpacing)
                                }
                                dragTargetIndex < dragStartIndex && importedIndex in dragTargetIndex until dragStartIndex -> {
                                    (dragHeights[draggedDictionaryId] ?: 0f) + itemSpacing
                                }
                                else -> 0f
                            }
                            val animatedDisplacement by animateFloatAsState(
                                targetValue = displacement,
                                animationSpec = spring(),
                                label = "dictionary-reorder-${item.id}",
                            )
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onGloballyPositioned { coordinates ->
                                        val rootPosition = coordinates.positionInRoot()
                                        itemRootBounds[item.id] = Rect(
                                            left = rootPosition.x,
                                            top = rootPosition.y,
                                            right = rootPosition.x + coordinates.size.width,
                                            bottom = rootPosition.y + coordinates.size.height,
                                        )
                                        val position = coordinates.positionInParent()
                                        itemLocalBounds[item.id] = Rect(
                                            left = position.x,
                                            top = position.y,
                                            right = position.x + coordinates.size.width,
                                            bottom = position.y + coordinates.size.height,
                                        )
                                    }
                                    .zIndex(if (isDragging) 1f else 0f)
                                    .graphicsLayer {
                                        translationY = if (isDragging) dragOffsetY else animatedDisplacement
                                        shadowElevation = if (isDragging) 12.dp.toPx() else 0f
                                    }
                                    .pointerInput(item.id, dictionaryLoading) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = { position -> onDragStart.value(item.id, position) },
                                            onDrag = { change, dragAmount ->
                                                onDragDelta.value(item.id, dragAmount.y)
                                                change.consume()
                                            },
                                            onDragEnd = { onDragEnd.value(item.id) },
                                            onDragCancel = { onDragCancel.value(item.id) },
                                        )
                                    },
                                colors = CardDefaults.cardColors(containerColor = itemContainerColor)
                            ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = item.title,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (item.usesLegacyMediaFormat) {
                                            Text(
                                                text = stringResource(R.string.dictionary_legacy_media_format_hint),
                                                color = androidx.compose.material3.MaterialTheme.colorScheme.tertiary,
                                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.width(72.dp),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Switch(
                                            checked = item.enabled,
                                            onCheckedChange = { checked -> onSetImportedDictionaryEnabled(item.id, checked) },
                                            enabled = !dictionaryLoading
                                        )
                                    }
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = { onMoveImportedDictionary(item.id, importedIndex - 1) },
                                        enabled = !dictionaryLoading && importedIndex > 0
                                    ) {
                                        Text("↑")
                                    }
                                    OutlinedButton(
                                        onClick = { onMoveImportedDictionary(item.id, importedIndex + 1) },
                                        enabled = !dictionaryLoading &&
                                            importedIndex >= 0 &&
                                            importedIndex < combinedItems.lastIndex
                                    ) {
                                        Text("↓")
                                    }
                                    if (showDictionaryDeleteActions) {
                                        OutlinedButton(
                                            onClick = {
                                                val targetIndex = dictionaryRefs.indexOfFirst { importedDictionaryId(it) == item.id }
                                                if (targetIndex >= 0) {
                                                    onRemoveImportedDictionary(targetIndex)
                                                }
                                            },
                                            enabled = !dictionaryLoading,
                                            contentPadding = ButtonDefaults.ContentPadding
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.Delete,
                                                contentDescription = stringResource(R.string.common_delete),
                                                tint = androidx.compose.material3.MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}
}
