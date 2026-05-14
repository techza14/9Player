package moe.tekuza.m9player

import androidx.compose.ui.geometry.Rect
import moe.tekuza.m9player.hoshi.features.reader.ReaderSelectionRect
import org.junit.Assert.assertEquals
import org.junit.Test

class BookReaderHoshiSelectionTest {
    @Test
    fun createHoshiReaderSelectionFromCueTapBuildsHoshiSelectionPayload() {
        val selection = createHoshiReaderSelectionFromCueTap(
            cueText = "運が悪いとパーになるって言ったか",
            cueIndex = 0,
            cues = listOf(
                ReaderSubtitleCue(0L, 1000L, "運が悪いとパーになるって言ったか")
            ),
            offset = 0,
            anchorRect = Rect(left = 105f, top = 907f, right = 194f, bottom = 1027f)
        )

        assertEquals("運が悪いとパーになるって言ったか", selection.text)
        assertEquals("運が悪いとパーになるって言ったか", selection.sentence)
        assertEquals(0, selection.normalizedOffset)
        assertEquals(0, selection.sentenceOffset)
        assertEquals(ReaderSelectionRect(105.0, 907.0, 89.0, 120.0), selection.rect)
    }

    @Test
    fun createHoshiReaderSelectionFromCueTapExpandsBackwardWhenTapIsInsidePhrase() {
        val selection = createHoshiReaderSelectionFromCueTap(
            cueText = "こっちを見てくれた",
            cueIndex = 0,
            cues = listOf(
                ReaderSubtitleCue(0L, 1000L, "こっちを見てくれた")
            ),
            offset = 9,
            anchorRect = Rect(left = 10f, top = 20f, right = 30f, bottom = 40f)
        )

        assertEquals("た", selection.text)
        assertEquals(0, selection.normalizedOffset)
        assertEquals(8, selection.sentenceOffset)
    }
}
