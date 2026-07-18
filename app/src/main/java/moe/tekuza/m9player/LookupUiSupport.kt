package moe.tekuza.m9player

import androidx.compose.ui.geometry.Rect

internal fun mergeRectsByLineShared(rects: List<Rect>): List<Rect> {
    if (rects.isEmpty()) return emptyList()
    val sorted = rects.sortedWith(compareBy<Rect> { it.top }.thenBy { it.left })
    val result = mutableListOf<Rect>()
    val verticalTolerance = 2f
    var current = sorted.first()
    for (index in 1 until sorted.size) {
        val rect = sorted[index]
        if (kotlin.math.abs(rect.top - current.top) <= verticalTolerance &&
            kotlin.math.abs(rect.bottom - current.bottom) <= verticalTolerance
        ) {
            current = Rect(
                left = minOf(current.left, rect.left),
                top = minOf(current.top, rect.top),
                right = maxOf(current.right, rect.right),
                bottom = maxOf(current.bottom, rect.bottom)
            )
        } else {
            result += current
            current = rect
        }
    }
    result += current
    return result
}
