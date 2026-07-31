package moe.tekuza.m9player.legado.reader.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderCharCountTest {

    @Test
    fun countsOnlyReaderCharacters() {
        assertEquals(3, "一、二。A!".readerCharCount())
        assertEquals(6, "abc 123".readerCharCount())
        assertEquals(14, "吾輩は猫である。名前はまだ無い。".readerCharCount())
        assertEquals(0, "、。！？…\n\t ".readerCharCount())
    }

    @Test
    fun countsUnicodeCodePointsNotUtf16Units() {
        assertEquals(1, "𠮟".readerCharCount())
        assertEquals(2, "𠮟る".readerCharCount())
    }

    @Test
    fun countsSubstringRanges() {
        val text = "吾輩は猫である。名前はまだ無い。"
        assertEquals(9, text.readerCharCount(0, 10))
        assertEquals(7, text.readerCharCount(8, 15))
    }
}
