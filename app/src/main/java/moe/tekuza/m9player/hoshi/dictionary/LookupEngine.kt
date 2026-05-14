package moe.tekuza.m9player.hoshi.dictionary

import de.manhhao.hoshi.HoshiDicts
import de.manhhao.hoshi.DictionaryStyle
import de.manhhao.hoshi.LookupResult

object LookupEngine {
    fun lookup(text: String, maxResults: Int = 16, scanLength: Int = 16): List<LookupResult> =
        HoshiDicts.lookup(HoshiDicts.lookupObject, text, maxResults, scanLength).toList()

    fun getStyles(): List<DictionaryStyle> =
        HoshiDicts.getStyles(HoshiDicts.lookupObject).toList()
}
