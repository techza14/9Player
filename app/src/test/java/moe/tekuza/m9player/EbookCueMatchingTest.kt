package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EbookCueMatchingTest {
    @Test
    fun matchEbookCuesData_skipsShortStageCue() {
        val document = EbookDocument(
            title = "book",
            format = "TXT",
            chapters = listOf(
                EbookChapter(
                    title = "chapter",
                    text = "これは本文です。次に本当に読む長い文章があります。"
                )
            )
        )
        val cues = listOf(
            EbookSrtCue(startMs = 0L, endMs = 1000L, text = "＊あ"),
            EbookSrtCue(startMs = 1000L, endMs = 2000L, text = "本当に読む長い文章")
        )

        val data = matchEbookCuesData(document, cues, searchWindow = 200)

        assertEquals(1, data.matches.size)
        assertEquals(1, data.matches.single().cueIndex)
        assertEquals(1, data.unmatched)
        assertTrue(shouldSkipEbookCueForMatching(cues.first()))
    }
}
