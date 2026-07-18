package moe.tekuza.m9player

internal fun ReaderLookupSession.closeLayerOrClear(layerIndex: Int): CloseLookupAction {
    return if (layerIndex <= 0) {
        CloseLookupAction.ClearAll
    } else {
        CloseLookupAction.ShowLayer(layerIndex - 1)
    }
}

internal sealed interface CloseLookupAction {
    data object ClearAll : CloseLookupAction
    data class ShowLayer(val index: Int) : CloseLookupAction
}
