package moe.tekuza.m9player

internal fun overlayModeLabel(context: android.content.Context, mode: FloatingOverlayMode): String {
    return when (mode) {
        FloatingOverlayMode.OFF -> context.getString(R.string.audiobook_overlay_disabled)
        FloatingOverlayMode.SUBTITLE -> context.getString(R.string.audiobook_overlay_mode_subtitle)
        FloatingOverlayMode.BUBBLE -> context.getString(R.string.audiobook_overlay_mode_bubble)
        FloatingOverlayMode.BOTH -> context.getString(R.string.audiobook_overlay_mode_both)
    }
}
