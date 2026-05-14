package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainHoshiLookupContentTest {
    @Test
    fun findsFirstCandidateWithLookupHit() {
        val hit = findFirstLookupScanHit("「ねえ待って！」") { query ->
            if (query == "待って") listOf(query) else emptyList()
        }

        assertEquals("待って", hit?.first?.text)
        assertEquals(listOf("待って"), hit?.second)
    }

    @Test
    fun returnsNullWhenNoCandidateHits() {
        val hit = findFirstLookupScanHit("？！") { query ->
            listOf(query)
        }

        assertNull(hit)
    }

    @Test
    fun buildsMatchedRangeFromSentenceOffset() {
        val range = matchedRangeFromSentenceOffset(
            sentence = "短い人生でしたが",
            sentenceOffset = 0,
            matchedText = "短い"
        )

        assertEquals(0 until 2, range)
    }

    @Test
    fun returnsNullForInvalidMatchedRangeInputs() {
        assertNull(
            matchedRangeFromSentenceOffset(
                sentence = "短い人生でしたが",
                sentenceOffset = null,
                matchedText = "短い"
            )
        )
        assertNull(
            matchedRangeFromSentenceOffset(
                sentence = "短い人生でしたが",
                sentenceOffset = 99,
                matchedText = "短い"
            )
        )
    }
}
