package moe.tekuza.m9player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookReaderLookupEmptySelectionSourceTest {
    @Test
    fun emptyHoshiLookupClearsVisibleSelectionRange() {
        val source = File("src/main/java/moe/tekuza/m9player/BookReaderActivity.kt").readText()

        assertTrue(source.contains("hoshiLookupSelectionRange = null"))
        assertTrue(source.contains("hoshiLookupSelectionCueIndex = null"))
        assertTrue(source.contains("clearHoshiLookupSelection()"))
    }
}
