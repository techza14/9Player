package moe.tekuza.m9player

import android.content.Context
import de.manhhao.hoshi.LookupResult
import moe.tekuza.m9player.hoshi.dictionary.LookupEngine
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupItem
import moe.tekuza.m9player.hoshi.features.dictionary.LookupPopupOptions
import moe.tekuza.m9player.hoshi.features.dictionary.createLookupPopupItem
import moe.tekuza.m9player.hoshi.features.dictionary.currentDictionaryStyles
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionData

internal class HoshiLookupSession(
    context: Context,
    private val dictionariesProvider: () -> List<LoadedDictionary>,
    private val versionProvider: () -> Long = { loadDictionaryDataVersion(context) }
) {
    private val appContext = context.applicationContext
    private var preparedVersion: Long = Long.MIN_VALUE
    private var preparedFingerprint: String = ""

    private fun fingerprint(dictionaries: List<LoadedDictionary>): String =
        dictionaries.joinToString(separator = "\n") { dictionary ->
            "${dictionary.cacheKey.trim()}\u0001${dictionary.name.trim()}\u0001${dictionary.dictionaryType.trim()}"
        }

    fun ensurePrepared(): List<LoadedDictionary> {
        val dictionaries = dictionariesProvider()
        val version = versionProvider()
        val nextFingerprint = fingerprint(dictionaries)
        if (preparedVersion != version || preparedFingerprint != nextFingerprint) {
            prepareHoshiLookupForDictionaries(appContext, dictionaries)
            preparedVersion = version
            preparedFingerprint = nextFingerprint
        }
        return dictionaries
    }

    fun lookup(query: String, maxResults: Int, scanLength: Int): List<LookupResult> {
        if (ensurePrepared().isEmpty()) return emptyList()
        return LookupEngine.lookup(query, maxResults, scanLength)
    }

    fun dictionaryStyles(): Map<String, String> {
        if (ensurePrepared().isEmpty()) return emptyMap()
        return currentDictionaryStyles()
    }

    fun createPopup(
        selection: ReaderSelectionData,
        options: LookupPopupOptions
    ): Pair<LookupPopupItem, Int>? {
        if (ensurePrepared().isEmpty()) return null
        return createLookupPopupItem(
            selection = selection,
            options = options,
            dictionaryStyles = currentDictionaryStyles(),
            lookup = ::lookup
        )
    }
}
