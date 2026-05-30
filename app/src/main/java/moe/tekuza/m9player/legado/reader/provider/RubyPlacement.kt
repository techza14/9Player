package moe.tekuza.m9player.legado.reader.provider

import moe.tekuza.m9player.EbookRubySegment
import moe.tekuza.m9player.EbookRubySpan

internal data class RubyPlacement(
    val span: EbookRubySpan,
    val segment: EbookRubySegment?
) {
    val text: String
        get() = segment?.text ?: span.text
    val absoluteStart: Int
        get() = span.start + (segment?.baseStart ?: 0)
    val absoluteEnd: Int
        get() = span.start + (segment?.baseEnd ?: (span.end - span.start))
}

internal fun buildRubyPlacements(spans: List<EbookRubySpan>): Map<Int, RubyPlacement> {
    val placements = linkedMapOf<Int, RubyPlacement>()
    spans.forEach { span ->
        if (span.segments.isEmpty()) {
            placements[span.start] = RubyPlacement(span = span, segment = null)
        } else {
            span.segments.forEach { segment ->
                placements[span.start + segment.baseStart] = RubyPlacement(span = span, segment = segment)
            }
        }
    }
    return placements
}
