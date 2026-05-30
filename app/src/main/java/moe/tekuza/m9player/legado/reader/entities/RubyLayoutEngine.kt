package moe.tekuza.m9player.legado.reader.entities

import android.graphics.Paint
import moe.tekuza.m9player.EbookRubyKind
import kotlin.math.max

internal data class RubyGlyphBox(
    val text: String,
    val start: Float,
    val end: Float
)

internal object RubyLayoutEngine {
    const val TEXT_RATIO: Float = 0.5f
    const val RESERVE_RATIO: Float = 0.62f
    const val VERTICAL_UNIT_RATIO: Float = 0.88f
    const val MIN_UNIT_RATIO: Float = 0.62f
    const val EDGE_OVERHANG_EM: Float = 0.55f
    const val SEGMENT_OVERHANG_EM: Float = 0.18f
    const val MIN_SCALE: Float = 0.78f
    const val GAP_EM: Float = 0.08f
    const val DISTRIBUTE_THRESHOLD: Float = 0.82f
    private const val GROUP_VERTICAL_OVERHANG_EM = 1.0f

    fun rubyTextSize(baseTextSize: Float): Float {
        return (baseTextSize * TEXT_RATIO).coerceAtLeast(8f)
    }

    fun codePointStrings(text: String): List<String> {
        return text.codePoints()
            .toArray()
            .map { String(Character.toChars(it)) }
    }

    fun fitHorizontalRubyText(
        paint: Paint,
        annotation: String,
        baseWidth: Float,
        beforeOverhang: Float,
        afterOverhang: Float,
        originalSize: Float
    ) {
        val allowedWidth = (baseWidth + beforeOverhang + afterOverhang).coerceAtLeast(baseWidth)
        val measured = paint.measureText(annotation).coerceAtLeast(1f)
        if (measured <= allowedWidth) return
        paint.textSize = (paint.textSize * allowedWidth / measured)
            .coerceAtLeast(originalSize * TEXT_RATIO * MIN_SCALE)
    }

    fun shouldDistributeHorizontal(annotation: String, baseWidth: Float, paint: Paint): Boolean {
        val count = annotation.codePointCount(0, annotation.length)
        if (count <= 1) return false
        return paint.measureText(annotation) < baseWidth * DISTRIBUTE_THRESHOLD
    }

    fun verticalGlyphBoxes(
        annotation: String,
        baseColumns: List<TextColumn>,
        top: Float,
        bottom: Float,
        rubySize: Float,
        beforeOverhang: Float,
        afterOverhang: Float,
        rubyKind: EbookRubyKind,
        segmented: Boolean
    ): List<RubyGlyphBox> {
        val rubyChars = codePointStrings(annotation)
        if (rubyChars.isEmpty()) return emptyList()
        if (shouldAttachPerBase(rubyChars, baseColumns, rubyKind, segmented)) {
            val unitHeight = rubySize * VERTICAL_UNIT_RATIO
            return rubyChars.mapIndexed { index, rubyChar ->
                val base = baseColumns[index]
                val center = (base.start + base.end) * 0.5f
                RubyGlyphBox(rubyChar, center - unitHeight * 0.5f, center + unitHeight * 0.5f)
            }
        }

        val baseHeight = (bottom - top).coerceAtLeast(1f)
        val groupOverhang = if (rubyKind == EbookRubyKind.GROUP || (rubyKind == EbookRubyKind.JUKUGO && !segmented)) {
            rubySize * GROUP_VERTICAL_OVERHANG_EM
        } else {
            0f
        }
        val allowedBefore = max(beforeOverhang, groupOverhang)
        val allowedAfter = max(afterOverhang, groupOverhang)
        val naturalUnitHeight = rubySize * VERTICAL_UNIT_RATIO
        val fillsBaseSpan = usesBaseSpanDistribution(rubyKind, segmented)
        val preferredUnitHeight = if (fillsBaseSpan) {
            baseHeight / rubyChars.size
        } else {
            naturalUnitHeight
        }
        val naturalHeight = preferredUnitHeight * rubyChars.size
        val allowedHeight = (baseHeight + allowedBefore + allowedAfter).coerceAtLeast(baseHeight)
        val unitHeight = if (naturalHeight > allowedHeight) {
            (allowedHeight / rubyChars.size).coerceAtLeast(rubySize * MIN_UNIT_RATIO)
        } else {
            preferredUnitHeight
        }
        val totalHeight = unitHeight * rubyChars.size
        val minY = top - if (fillsBaseSpan) 0f else allowedBefore
        val maxY = bottom + if (fillsBaseSpan) 0f else allowedAfter - totalHeight
        val centeredY = (top + bottom) * 0.5f - totalHeight * 0.5f
        var y = if (maxY >= minY) centeredY.coerceIn(minY, maxY) else centeredY
        return rubyChars.map { rubyChar ->
            RubyGlyphBox(rubyChar, y, y + unitHeight).also {
                y += unitHeight
            }
        }
    }

    private fun usesBaseSpanDistribution(rubyKind: EbookRubyKind, segmented: Boolean): Boolean {
        return !segmented && (rubyKind == EbookRubyKind.GROUP || rubyKind == EbookRubyKind.JUKUGO)
    }

    private fun shouldAttachPerBase(
        rubyChars: List<String>,
        baseColumns: List<TextColumn>,
        rubyKind: EbookRubyKind,
        segmented: Boolean
    ): Boolean {
        if (rubyChars.size <= 1 || rubyChars.size != baseColumns.size) return false
        if (baseColumns.any { it.charData.codePointCount(0, it.charData.length) != 1 }) return false
        return segmented || rubyKind == EbookRubyKind.MONO || rubyKind == EbookRubyKind.JUKUGO
    }
}
