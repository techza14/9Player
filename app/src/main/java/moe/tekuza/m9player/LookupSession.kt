package moe.tekuza.m9player

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

internal class ReaderLookupSession {
    private val _layers = mutableStateListOf<ReaderLookupLayer>()
    val layers: SnapshotStateList<ReaderLookupLayer> get() = _layers

    val activeLayer: ReaderLookupLayer?
        get() = _layers.lastOrNull()

    val lastIndex: Int
        get() = _layers.lastIndex

    val size: Int
        get() = _layers.size

    fun clear() {
        _layers.clear()
    }

    fun push(layer: ReaderLookupLayer) {
        _layers += layer
    }

    fun pop(): ReaderLookupLayer? = _layers.removeLastOrNull()

    fun replaceTop(transform: (ReaderLookupLayer) -> ReaderLookupLayer) {
        val index = _layers.lastIndex
        if (index < 0) return
        _layers[index] = transform(_layers[index])
    }

    fun replaceAt(index: Int, transform: (ReaderLookupLayer) -> ReaderLookupLayer) {
        if (index !in _layers.indices) return
        _layers[index] = transform(_layers[index])
    }

    fun truncateTo(index: Int) {
        if (index !in _layers.indices) return
        while (_layers.size > index + 1) {
            _layers.removeLastOrNull()
        }
    }

    fun getOrNull(index: Int): ReaderLookupLayer? = _layers.getOrNull(index)
}
