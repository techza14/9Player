package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Test

class LookupAnkiSupportTest {

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
