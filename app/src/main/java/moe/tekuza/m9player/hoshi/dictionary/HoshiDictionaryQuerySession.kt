package moe.tekuza.m9player.hoshi.dictionary

import de.manhhao.hoshi.HoshiDicts
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

internal object HoshiDictionaryQuerySession {
    private val rebuildLock = Any()
    private val queryLock = ReentrantReadWriteLock()
    private var currentSession: Long? = HoshiDicts.lookupObject

    fun rebuild(
        termPaths: Array<String>,
        frequencyPaths: Array<String>,
        pitchPaths: Array<String>,
    ) {
        synchronized(rebuildLock) {
            val nextSession = HoshiDicts.createLookupObject()
            var committed = false
            try {
                HoshiDicts.rebuildQuery(nextSession, termPaths, frequencyPaths, pitchPaths)
                val previousSession = queryLock.write {
                    val previous = currentSession
                    currentSession = nextSession
                    committed = true
                    previous
                }
                previousSession?.let(HoshiDicts::destroyLookupObject)
            } finally {
                if (!committed) HoshiDicts.destroyLookupObject(nextSession)
            }
        }
    }

    fun lookup(text: String, maxResults: Int, scanLength: Int) = queryLock.read {
        currentSession?.let { HoshiDicts.lookup(it, text, maxResults, scanLength).toList() }.orEmpty()
    }

    fun getStyles() = queryLock.read {
        currentSession?.let { HoshiDicts.getStyles(it).toList() }.orEmpty()
    }

    fun getMediaFile(dictionary: String, path: String): ByteArray? = queryLock.read {
        currentSession?.let { HoshiDicts.getMediaFile(it, dictionary, path) }
    }
}
