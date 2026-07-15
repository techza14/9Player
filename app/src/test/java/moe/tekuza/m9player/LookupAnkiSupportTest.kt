package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LookupAnkiSupportTest {

    @Test
    fun exportDebugLogsDoNotContainUserLookupValues() {
        val source = File("src/main/java/moe/tekuza/m9player/LookupAnkiSupport.kt").readText()

        assertFalse(source.contains("term=${'$'}{entry.term}"))
        assertFalse(source.contains("word=${'$'}{card.word}"))
        assertTrue(source.contains("termLength=${'$'}{entry.term.length}"))
        assertTrue(source.contains("wordLength=${'$'}{card.word.length}"))
    }

    @Test
    fun resolveLookupExportWord_prefersCurrentLookupTerm() {
        assertEquals(
            "あら",
            resolveLookupExportWord(
                popupSelectionText = "登録",
                lookupTermOverride = "あら",
                entryTerm = "登録"
            )
        )
    }

    @Test
    fun resolveLookupExportWord_fallsBackToPopupSelection() {
        assertEquals(
            "登録",
            resolveLookupExportWord(
                popupSelectionText = "登録",
                lookupTermOverride = "",
                entryTerm = "動"
            )
        )
    }
}
