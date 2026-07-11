package moe.tekuza.m9player.hoshi.dictionary

import android.os.SystemClock
import de.manhhao.hoshi.HoshiDicts
import de.manhhao.hoshi.DictionaryStyle
import de.manhhao.hoshi.LookupResult
import moe.tekuza.m9player.logDebug

object LookupEngine {
    fun lookup(text: String, maxResults: Int = 16, scanLength: Int = 16): List<LookupResult> {
        val startedAt = SystemClock.elapsedRealtimeNanos()
        return HoshiDicts.lookup(HoshiDicts.lookupObject, text, maxResults, scanLength).toList().also { results ->
            logDebug("HoshiLookupPerf") {
                "nativeLookup elapsedMs=${(SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000.0} " +
                    "query='${text.take(32)}' results=${results.size} maxResults=$maxResults scanLength=$scanLength"
            }
        }
    }

    fun getStyles(): List<DictionaryStyle> =
        HoshiDicts.getStyles(HoshiDicts.lookupObject).toList()
}
