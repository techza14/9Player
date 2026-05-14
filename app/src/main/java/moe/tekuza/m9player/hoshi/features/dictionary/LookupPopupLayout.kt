package moe.tekuza.m9player.hoshi.features.dictionary

import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionRect

data class LookupPopupFrame(
    val width: Double,
    val height: Double,
    val centerX: Double,
    val centerY: Double,
)

data class LookupPopupLayout(
    val selectionRect: ReaderSelectionRect,
    val avoidRects: List<ReaderSelectionRect> = emptyList(),
    val screenWidth: Double,
    val screenHeight: Double,
    val maxWidth: Double,
    val maxHeight: Double,
    val isVertical: Boolean,
    val isFullWidth: Boolean = false,
    val topInset: Double = 0.0,
    val bottomInset: Double = 0.0,
) {
    fun calculate(): LookupPopupFrame {
        val width = width()
        val height = height()
        val positioned = positionedFrame(width, height)
        return LookupPopupFrame(
            width = width,
            height = height,
            centerX = positioned.first,
            centerY = positioned.second,
        )
    }

    private fun width(): Double {
        if (isFullWidth) return screenWidth - screenBorderPadding * 2
        if (isVertical) return minOf(maxOf(spaceLeft(), spaceRight()) - screenBorderPadding, maxWidth)
        return minOf(screenWidth - screenBorderPadding * 2, maxWidth)
    }

    private fun height(): Double {
        if (isVertical || isFullWidth) return maxHeight
        return minOf(maxOf(spaceAbove(), spaceBelow()) - screenBorderPadding, maxHeight)
    }

    private fun centerX(width: Double): Double {
        if (isFullWidth) return width / 2 + screenBorderPadding
        if (isVertical) {
            val raw = if (showOnRight()) {
                selectionRect.x + selectionRect.width + popupPadding + width / 2
            } else {
                selectionRect.x - popupPadding - width / 2
            }
            return raw.coerceIn(width / 2, screenWidth - width / 2)
        }
        val raw = selectionRect.x + width / 2
        return raw.coerceIn(width / 2 + screenBorderPadding, screenWidth - width / 2 - screenBorderPadding)
    }

    private fun centerY(height: Double): Double {
        if (isFullWidth) return screenHeight - height / 2 - screenBorderPadding
        if (isVertical) {
            val raw = selectionRect.y + height / 2
            return raw.coerceIn(
                height / 2 + screenBorderPadding + topInset,
                screenHeight - bottomInset - height / 2 - screenBorderPadding,
            )
        }
        val raw = if (showBelow(height)) {
            selectionRect.y + selectionRect.height + popupPadding + height / 2
        } else {
            selectionRect.y - popupPadding - height / 2
        }
        return raw.coerceIn(
            height / 2 + topInset + screenBorderPadding,
            screenHeight - bottomInset - height / 2 - screenBorderPadding,
        )
    }

    private fun spaceLeft(): Double = selectionRect.x - popupPadding
    private fun spaceRight(): Double = screenWidth - selectionRect.x - selectionRect.width - popupPadding
    private fun spaceAbove(): Double = selectionRect.y - topInset - popupPadding
    private fun spaceBelow(): Double = screenHeight - bottomInset - selectionRect.y - selectionRect.height - popupPadding
    private fun showOnRight(): Boolean = spaceRight() >= spaceLeft()
    private fun showBelow(height: Double): Boolean = spaceBelow() >= height

    private fun positionedFrame(width: Double, height: Double): Pair<Double, Double> {
        val defaultCenterX = centerX(width)
        val defaultCenterY = centerY(height)
        val avoid = avoidRects.filter { it.width > 0.0 && it.height > 0.0 }
        if (avoid.isEmpty()) return defaultCenterX to defaultCenterY

        val minX = width / 2 + screenBorderPadding
        val maxX = screenWidth - width / 2 - screenBorderPadding
        val minY = height / 2 + topInset + screenBorderPadding
        val maxY = screenHeight - bottomInset - height / 2 - screenBorderPadding
        if (minX > maxX || minY > maxY) return defaultCenterX to defaultCenterY

        val anchorCenterX = selectionRect.x + selectionRect.width / 2
        val belowY = selectionRect.y + selectionRect.height + popupPadding + height / 2
        val aboveY = selectionRect.y - popupPadding - height / 2
        val sideRightX = selectionRect.x + selectionRect.width + popupPadding + width / 2
        val sideLeftX = selectionRect.x - popupPadding - width / 2
        val centerYOnAnchor = selectionRect.y + selectionRect.height / 2

        val candidates = buildList {
            if (!isVertical) {
                val yOrder = if (showBelow(height)) listOf(belowY, aboveY) else listOf(aboveY, belowY)
                yOrder.forEach { y ->
                    val clampedY = y.coerceIn(minY, maxY)
                    add(anchorCenterX.coerceIn(minX, maxX) to clampedY)
                    nonOverlappingXAtY(
                        y = clampedY - height / 2,
                        preferredCenterX = anchorCenterX.coerceIn(minX, maxX),
                        width = width,
                        height = height,
                        minCenterX = minX,
                        maxCenterX = maxX,
                        avoid = avoid
                    )?.let { add(it to clampedY) }
                }
            }
            val sideXOrder = if (showOnRight()) listOf(sideRightX, sideLeftX) else listOf(sideLeftX, sideRightX)
            sideXOrder.forEach { x ->
                val clampedX = x.coerceIn(minX, maxX)
                val clampedY = centerYOnAnchor.coerceIn(minY, maxY)
                add(clampedX to clampedY)
                nonOverlappingYAtX(
                    x = clampedX - width / 2,
                    preferredCenterY = clampedY,
                    width = width,
                    height = height,
                    minCenterY = minY,
                    maxCenterY = maxY,
                    avoid = avoid
                )?.let { add(clampedX to it) }
            }
            add(defaultCenterX to defaultCenterY)
        }

        return candidates.minWithOrNull(
            compareBy<Pair<Double, Double>>(
                { overlapArea(it.first, it.second, width, height, avoid) },
                { distanceSquared(it.first, it.second, defaultCenterX, defaultCenterY) }
            )
        ) ?: (defaultCenterX to defaultCenterY)
    }

    private fun nonOverlappingXAtY(
        y: Double,
        preferredCenterX: Double,
        width: Double,
        height: Double,
        minCenterX: Double,
        maxCenterX: Double,
        avoid: List<ReaderSelectionRect>
    ): Double? {
        val top = y
        val bottom = y + height
        val blockers = avoid.filter { bottom > it.y && top < it.y + it.height }
        if (blockers.isEmpty()) return preferredCenterX
        val candidates = buildList {
            add(preferredCenterX)
            blockers.forEach { rect ->
                add(rect.x - popupPadding - width / 2)
                add(rect.x + rect.width + popupPadding + width / 2)
            }
        }.map { it.coerceIn(minCenterX, maxCenterX) }
        return candidates
            .filter { overlapArea(it, y + height / 2, width, height, avoid) <= 0.0 }
            .minByOrNull { kotlin.math.abs(it - preferredCenterX) }
    }

    private fun nonOverlappingYAtX(
        x: Double,
        preferredCenterY: Double,
        width: Double,
        height: Double,
        minCenterY: Double,
        maxCenterY: Double,
        avoid: List<ReaderSelectionRect>
    ): Double? {
        val left = x
        val right = x + width
        val blockers = avoid.filter { right > it.x && left < it.x + it.width }
        if (blockers.isEmpty()) return preferredCenterY
        val candidates = buildList {
            add(preferredCenterY)
            blockers.forEach { rect ->
                add(rect.y - popupPadding - height / 2)
                add(rect.y + rect.height + popupPadding + height / 2)
            }
        }.map { it.coerceIn(minCenterY, maxCenterY) }
        return candidates
            .filter { overlapArea(x + width / 2, it, width, height, avoid) <= 0.0 }
            .minByOrNull { kotlin.math.abs(it - preferredCenterY) }
    }

    private fun overlapArea(
        centerX: Double,
        centerY: Double,
        width: Double,
        height: Double,
        avoid: List<ReaderSelectionRect>
    ): Double {
        val left = centerX - width / 2
        val top = centerY - height / 2
        val right = centerX + width / 2
        val bottom = centerY + height / 2
        return avoid.sumOf { rect ->
            val overlapWidth = (minOf(right, rect.x + rect.width) - maxOf(left, rect.x)).coerceAtLeast(0.0)
            val overlapHeight = (minOf(bottom, rect.y + rect.height) - maxOf(top, rect.y)).coerceAtLeast(0.0)
            overlapWidth * overlapHeight
        }
    }

    private fun distanceSquared(x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy
    }

    private companion object {
        const val popupPadding = 4.0
        const val screenBorderPadding = 6.0
    }
}
