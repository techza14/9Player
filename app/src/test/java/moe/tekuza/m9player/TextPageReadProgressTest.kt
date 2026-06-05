package moe.tekuza.m9player

import moe.tekuza.m9player.legado.reader.entities.TextPage
import org.junit.Assert.assertEquals
import org.junit.Test

class TextPageReadProgressTest {
    @Test
    fun readProgressUsesWholeDocumentCharacterPositionWhenAvailable() {
        val page = TextPage(
            globalIndex = 438,
            totalPages = 439,
            documentCharEnd = 100,
            documentCharCount = 1000
        )

        assertEquals("10.0%", page.readProgress)
    }

    @Test
    fun readProgressFallsBackToLoadedPageNumbers() {
        val page = TextPage(globalIndex = 0, totalPages = 439)

        assertEquals("0.2%", page.readProgress)
    }
}
