package moe.tekuza.m9player

import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.PopupPositionProvider

internal data class SharedLookupPopupSizeSpec(
    val widthDp: Int,
    val contentMaxHeightDp: Int,
    val preferredDirection: SharedLookupPopupDirection
)

internal enum class SharedLookupPopupDirection {
    BELOW,
    ABOVE,
    RIGHT,
    LEFT
}

internal fun computeSharedLookupPopupSizeSpec(
    screenWidthDp: Int,
    screenHeightDp: Int,
    anchor: ReaderLookupAnchor?,
    placeBelow: Boolean,
    preferSidePlacement: Boolean,
    density: Float
): SharedLookupPopupSizeSpec {
    val safeDensity = density.takeIf { it > 0f } ?: 1f
    val screenWidth = screenWidthDp.toFloat().coerceAtLeast(1f)
    val screenHeight = screenHeightDp.toFloat().coerceAtLeast(1f)
    val anchorBounds = anchor.primaryRectCoreOrNull() ?: anchor.boundingRectCoreOrNull()
    val anchorLeftDp = ((anchorBounds?.left ?: screenWidth * safeDensity * 0.4f) / safeDensity).coerceIn(0f, screenWidth)
    val anchorRightDp = ((anchorBounds?.right ?: screenWidth * safeDensity * 0.6f) / safeDensity).coerceIn(0f, screenWidth)
    val anchorTopDp = ((anchorBounds?.top ?: screenHeight * safeDensity * 0.46f) / safeDensity).coerceIn(0f, screenHeight)
    val anchorBottomDp = ((anchorBounds?.bottom ?: screenHeight * safeDensity * 0.56f) / safeDensity).coerceIn(0f, screenHeight)
    val screenPaddingDp = 12f
    val guardDp = 24f
    val preferredMinWidthDp = 220f
    val maxWidthDp = 320f
    val preferredMinContentHeightDp = 96f
    val maxContentHeightDp = 260f
    val chromeReserveDp = 112f

    data class DirectionCap(val direction: SharedLookupPopupDirection, val widthCap: Float, val contentHeightCap: Float)

    val fullWidthCap = (screenWidth - screenPaddingDp * 2f).coerceAtLeast(0f)
    val fullHeightCap = (screenHeight - screenPaddingDp * 2f - chromeReserveDp).coerceAtLeast(0f)
    val belowCap = DirectionCap(
        direction = SharedLookupPopupDirection.BELOW,
        widthCap = fullWidthCap,
        contentHeightCap = (screenHeight - anchorBottomDp - guardDp - screenPaddingDp - chromeReserveDp).coerceAtLeast(0f)
    )
    val aboveCap = DirectionCap(
        direction = SharedLookupPopupDirection.ABOVE,
        widthCap = fullWidthCap,
        contentHeightCap = (anchorTopDp - guardDp - screenPaddingDp - chromeReserveDp).coerceAtLeast(0f)
    )
    val rightCap = DirectionCap(
        direction = SharedLookupPopupDirection.RIGHT,
        widthCap = (screenWidth - anchorRightDp - guardDp - screenPaddingDp).coerceAtLeast(0f),
        contentHeightCap = fullHeightCap
    )
    val leftCap = DirectionCap(
        direction = SharedLookupPopupDirection.LEFT,
        widthCap = (anchorLeftDp - guardDp - screenPaddingDp).coerceAtLeast(0f),
        contentHeightCap = fullHeightCap
    )

    val verticalCaps = if (placeBelow) listOf(belowCap, aboveCap) else listOf(aboveCap, belowCap)
    val sideCaps = if (preferSidePlacement) listOf(rightCap, leftCap) else listOf(leftCap, rightCap)

    val bestCap =
        verticalCaps.firstOrNull { it.widthCap >= preferredMinWidthDp && it.contentHeightCap >= preferredMinContentHeightDp }
            ?: sideCaps.firstOrNull { it.widthCap >= preferredMinWidthDp && it.contentHeightCap >= preferredMinContentHeightDp }
            ?: (verticalCaps + sideCaps).maxByOrNull { it.widthCap * it.contentHeightCap }

    val width = (bestCap?.widthCap ?: maxWidthDp)
        .coerceIn(1f, maxWidthDp)
        .toInt()
    val contentMaxHeight = (bestCap?.contentHeightCap ?: maxContentHeightDp)
        .coerceIn(1f, maxContentHeightDp)
        .toInt()

    return SharedLookupPopupSizeSpec(
        widthDp = width,
        contentMaxHeightDp = contentMaxHeight,
        preferredDirection = bestCap?.direction ?: if (placeBelow) SharedLookupPopupDirection.BELOW else SharedLookupPopupDirection.ABOVE
    )
}

internal class SharedLookupPopupPositionProvider(
    private val anchor: ReaderLookupAnchor?,
    private val placeBelow: Boolean,
    private val preferSidePlacement: Boolean,
    private val preferredDirection: SharedLookupPopupDirection,
    private val gapPx: Int,
    private val screenPaddingPx: Int,
    private val logTag: String
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val effectiveGapPx = gapPx.coerceIn(8, 20)
        val sourceRects = anchor?.rects
            ?.filter { !it.isEmpty }
            ?.map {
                IntRect(
                    left = it.left.toInt(),
                    top = it.top.toInt(),
                    right = it.right.toInt(),
                    bottom = it.bottom.toInt()
                )
            }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(
                IntRect(
                    left = (windowSize.width * 0.4f).toInt(),
                    top = (windowSize.height * 0.46f).toInt(),
                    right = (windowSize.width * 0.6f).toInt(),
                    bottom = (windowSize.height * 0.56f).toInt()
                )
            )
        // If caller passes preferred rect first, respect it for wrapped vertical selections.
        val primaryRect = sourceRects.first()
        val sourceBoundsRect = IntRect(
            left = sourceRects.minOf { it.left },
            top = sourceRects.minOf { it.top },
            right = sourceRects.maxOf { it.right },
            bottom = sourceRects.maxOf { it.bottom }
        )
        // Avoid over-blocking vertical wrapped selections: keep per-rect blocks.
        val blockedRects = sourceRects
        // For wrapped selections, place popup next to the dominant rect.
        val placementAnchorRect = if (sourceRects.size > 1) primaryRect else sourceBoundsRect
        val maxX = (windowSize.width - popupContentSize.width - screenPaddingPx).coerceAtLeast(screenPaddingPx)
        val maxY = (windowSize.height - popupContentSize.height - screenPaddingPx).coerceAtLeast(screenPaddingPx)
        Log.d(
            logTag,
            "calc sourceRects=${formatIntRectsForLogShared(sourceRects)} blockedRects=${formatIntRectsForLogShared(blockedRects)} primary=${formatIntRectForLogShared(primaryRect)} popup=${popupContentSize.width}x${popupContentSize.height} placeBelow=$placeBelow side=$preferSidePlacement preferred=$preferredDirection"
        )

        fun fitsScreen(candidate: IntOffset): Boolean {
            return candidate.x in screenPaddingPx..maxX && candidate.y in screenPaddingPx..maxY
        }

        fun clampToScreen(candidate: IntOffset): IntOffset {
            return IntOffset(
                x = candidate.x.coerceIn(screenPaddingPx, maxX),
                y = candidate.y.coerceIn(screenPaddingPx, maxY)
            )
        }

        fun isNonOverlapping(candidate: IntOffset): Boolean {
            val candidateRect = popupRectShared(candidate, popupContentSize)
            return blockedRects.none { rectsOverlapShared(it, candidateRect) }
        }

        fun directionPriority(direction: SharedLookupPopupDirection): Int {
            val baseOrder = if (preferSidePlacement) {
                if (placeBelow) {
                    listOf(SharedLookupPopupDirection.RIGHT, SharedLookupPopupDirection.LEFT, SharedLookupPopupDirection.BELOW, SharedLookupPopupDirection.ABOVE)
                } else {
                    listOf(SharedLookupPopupDirection.RIGHT, SharedLookupPopupDirection.LEFT, SharedLookupPopupDirection.ABOVE, SharedLookupPopupDirection.BELOW)
                }
            } else {
                if (placeBelow) {
                    listOf(SharedLookupPopupDirection.BELOW, SharedLookupPopupDirection.ABOVE, SharedLookupPopupDirection.RIGHT, SharedLookupPopupDirection.LEFT)
                } else {
                    listOf(SharedLookupPopupDirection.ABOVE, SharedLookupPopupDirection.BELOW, SharedLookupPopupDirection.RIGHT, SharedLookupPopupDirection.LEFT)
                }
            }
            val order = listOf(preferredDirection) + baseOrder.filter { it != preferredDirection }
            return order.indexOf(direction).takeIf { it >= 0 } ?: Int.MAX_VALUE
        }

        fun buildAdjacentCandidates(): List<Pair<SharedLookupPopupDirection, IntOffset>> {
            val forbidden = placementAnchorRect
            val popupW = popupContentSize.width
            val popupH = popupContentSize.height
            val forbiddenW = (forbidden.right - forbidden.left).coerceAtLeast(1)
            val forbiddenH = (forbidden.bottom - forbidden.top).coerceAtLeast(1)
            val xVariants = listOf(
                forbidden.left,
                forbidden.right - popupW,
                forbidden.left + ((forbiddenW - popupW) / 2)
            )
            val yVariants = listOf(
                forbidden.top,
                forbidden.bottom - popupH,
                forbidden.top + ((forbiddenH - popupH) / 2)
            )
            val belowY = forbidden.bottom + effectiveGapPx
            val aboveY = forbidden.top - popupH - effectiveGapPx
            val rightX = forbidden.right + effectiveGapPx
            val leftX = forbidden.left - popupW - effectiveGapPx
            return buildList {
                xVariants.forEach { x ->
                    add(SharedLookupPopupDirection.BELOW to IntOffset(x, belowY))
                    add(SharedLookupPopupDirection.ABOVE to IntOffset(x, aboveY))
                }
                yVariants.forEach { y ->
                    add(SharedLookupPopupDirection.RIGHT to IntOffset(rightX, y))
                    add(SharedLookupPopupDirection.LEFT to IntOffset(leftX, y))
                }
            }
        }

        fun returnWithLog(reason: String, candidate: IntOffset): IntOffset {
            val popup = popupRectShared(candidate, popupContentSize)
            val sourceOverlap = sourceRects.sumOf { overlapAreaShared(it, popup) }
            val blockedOverlap = blockedRects.sumOf { overlapAreaShared(it, popup) }
            Log.d(
                logTag,
                "show reason=$reason pos=${candidate.x},${candidate.y} sourceOverlap=$sourceOverlap blockedOverlap=$blockedOverlap sourceBounds=${formatIntRectForLogShared(sourceBoundsRect)} popupRect=${formatIntRectForLogShared(popup)}"
            )
            return candidate
        }

        val adjacent = buildAdjacentCandidates()
        val bestAdjacent = adjacent
            .minWithOrNull(
                compareBy<Pair<SharedLookupPopupDirection, IntOffset>> { (direction, _) ->
                    directionPriority(direction)
                }.thenBy { (_, candidate) ->
                    if (fitsScreen(candidate) && isNonOverlapping(candidate)) 0 else 1
                }.thenBy { (_, candidate) ->
                    rectDistanceShared(placementAnchorRect, popupRectShared(candidate, popupContentSize))
                }
            )
        if (bestAdjacent != null) {
            val candidate = bestAdjacent.second
            if (fitsScreen(candidate) && isNonOverlapping(candidate)) {
                return returnWithLog("adjacent_fit", candidate)
            }
        }

        val clampedAdjacent = adjacent
            .map { (direction, candidate) -> direction to clampToScreen(candidate) }
            .distinctBy { (_, candidate) -> candidate }
            .filter { (_, candidate) -> fitsScreen(candidate) && isNonOverlapping(candidate) }
            .minWithOrNull(
                compareBy<Pair<SharedLookupPopupDirection, IntOffset>> { (direction, _) ->
                    directionPriority(direction)
                }.thenBy { (_, candidate) ->
                    rectDistanceShared(placementAnchorRect, popupRectShared(candidate, popupContentSize))
                }
            )?.second
        if (clampedAdjacent != null) return returnWithLog("adjacent_clamped_fit", clampedAdjacent)

        val nearestNonOverlap = run {
            val step = maxOf(32, popupContentSize.height / 12)
            val left = screenPaddingPx
            val right = maxX
            val top = screenPaddingPx
            val bottom = maxY
            var best: IntOffset? = null
            var bestDistance = Int.MAX_VALUE
            var y = top
            while (y <= bottom) {
                var x = left
                while (x <= right) {
                    val candidate = IntOffset(x, y)
                    if (fitsScreen(candidate) && isNonOverlapping(candidate)) {
                        val distance = rectDistanceShared(placementAnchorRect, popupRectShared(candidate, popupContentSize))
                        if (distance < bestDistance) {
                            best = candidate
                            bestDistance = distance
                        }
                    }
                    x += step
                }
                y += step
            }
            best
        }
        if (nearestNonOverlap != null) return returnWithLog("nearest_non_overlap_fit", nearestNonOverlap)

        val rawCandidates = adjacent.map { it.second }
        val clampedCandidates = rawCandidates.map(::clampToScreen).distinct()
        val fitCount = clampedCandidates.count { fitsScreen(it) }
        val nonOverlapCount = clampedCandidates.count { isNonOverlapping(it) }
        val fitAndNonOverlapCount = clampedCandidates.count { fitsScreen(it) && isNonOverlapping(it) }
        Log.d(
            logTag,
            "reject reason=no_adjacent_candidate raw=${rawCandidates.size} clamped=${clampedCandidates.size} fit=$fitCount nonOverlap=$nonOverlapCount fitAndNonOverlap=$fitAndNonOverlapCount sourceBounds=${formatIntRectForLogShared(sourceBoundsRect)}"
        )
        return IntOffset(
            x = windowSize.width + screenPaddingPx,
            y = windowSize.height + screenPaddingPx
        )
    }
}

private fun ReaderLookupAnchor?.primaryRectCoreOrNull(): Rect? {
    val rects = this?.rects?.filter { !it.isEmpty } ?: return null
    return rects.maxWithOrNull(
        compareBy<Rect> {
            val w = it.width.coerceAtLeast(0f)
            val h = it.height.coerceAtLeast(0f)
            w * h
        }.thenBy { it.height.coerceAtLeast(0f) }
            .thenBy { it.right }
    )
}

private fun popupRectShared(position: IntOffset, popupContentSize: IntSize): IntRect {
    return IntRect(
        left = position.x,
        top = position.y,
        right = position.x + popupContentSize.width,
        bottom = position.y + popupContentSize.height
    )
}

private fun rectsOverlapShared(a: IntRect, b: IntRect): Boolean {
    return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
}

private fun overlapAreaShared(a: IntRect, b: IntRect): Int {
    val left = maxOf(a.left, b.left)
    val top = maxOf(a.top, b.top)
    val right = minOf(a.right, b.right)
    val bottom = minOf(a.bottom, b.bottom)
    if (right <= left || bottom <= top) return 0
    return (right - left) * (bottom - top)
}

private fun rectDistanceShared(a: IntRect, b: IntRect): Int {
    val dx = when {
        b.left >= a.right -> b.left - a.right
        a.left >= b.right -> a.left - b.right
        else -> 0
    }
    val dy = when {
        b.top >= a.bottom -> b.top - a.bottom
        a.top >= b.bottom -> a.top - b.bottom
        else -> 0
    }
    return dx + dy
}

private fun formatIntRectForLogShared(rect: IntRect): String {
    return "${rect.left},${rect.top},${rect.right},${rect.bottom}"
}

private fun formatIntRectsForLogShared(rects: List<IntRect>): String {
    if (rects.isEmpty()) return "[]"
    return rects.joinToString(prefix = "[", postfix = "]") { formatIntRectForLogShared(it) }
}
