package moe.tekuza.m9player

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryCustomCssEditorTest {
    @Test
    fun dictionarySelectorSnippetEscapesCssDoubleQuotedContent() {
        val snippet = dictionarySelectorCssSnippet("JM\"dict\\Line\nBreak\rIgnored")

        assertEquals(
            "[data-dictionary=\"JM\\\"dict\\\\Line\\a BreakIgnored\"] {\n    \n}\n",
            snippet
        )
    }

    @Test
    fun insertCustomCssTextReplacesSelectedRangeAndMovesCursorAfterInsert() {
        val value = TextFieldValue(
            text = "abc def",
            selection = TextRange(4, 7)
        )

        val inserted = insertCustomCssText(value, "xyz")

        assertEquals("abc xyz", inserted.text)
        assertEquals(TextRange(7), inserted.selection)
    }
}
