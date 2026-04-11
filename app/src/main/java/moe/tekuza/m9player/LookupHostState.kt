package moe.tekuza.m9player

internal data class LookupCardActionState(
    val sourceTerm: String?,
    val canGoBack: Boolean,
    val canCloseAll: Boolean,
    val showRangeSelection: Boolean,
    val showPlayAudio: Boolean,
    val showAddToAnki: Boolean
)

internal fun buildLookupCardActionState(
    sourceTerm: String?,
    layerIndex: Int,
    sessionSize: Int,
    showRangeSelection: Boolean,
    showPlayAudio: Boolean,
    showAddToAnki: Boolean
): LookupCardActionState {
    return LookupCardActionState(
        sourceTerm = sourceTerm?.takeIf { it.isNotBlank() },
        canGoBack = layerIndex > 0,
        canCloseAll = sessionSize > 1,
        showRangeSelection = showRangeSelection,
        showPlayAudio = showPlayAudio,
        showAddToAnki = showAddToAnki
    )
}
