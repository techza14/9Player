package moe.tekuza.m9player

internal fun ReaderLookupSession.closeLayerOrClear(layerIndex: Int): CloseLookupAction {
    return if (layerIndex <= 0) {
        CloseLookupAction.ClearAll
    } else {
        CloseLookupAction.ShowLayer(layerIndex)
    }
}

internal fun ReaderLookupSession.afterAddToAnki(layerIndex: Int): CloseLookupAction {
    return if (layerIndex <= 0) {
        CloseLookupAction.ClearAll
    } else {
        CloseLookupAction.ShowLayer(layerIndex - 1)
    }
}

internal fun ReaderLookupSession.toggleCollapsedSection(layerIndex: Int, sectionKey: String, currentlyExpanded: Boolean) {
    replaceAt(layerIndex) { current ->
        current.copy(
            collapsedSections = current.collapsedSections.toMutableMap().apply {
                put(sectionKey, currentlyExpanded)
            }
        )
    }
}

internal sealed interface CloseLookupAction {
    data object ClearAll : CloseLookupAction
    data class ShowLayer(val index: Int) : CloseLookupAction
}
