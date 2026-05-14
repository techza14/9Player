package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DictionarySqlStoreTest {
    @Test
    fun detectHoshiDictionaryTypeRecognizesFrequencyTermMetaBank() {
        val zip = zipBytes(
            "term_meta_bank_1.json" to """[["日本","freq",123]]"""
        )

        assertEquals(HoshiDictionaryType.Frequency, detectHoshiDictionaryType(ByteArrayInputStream(zip)))
    }

    @Test
    fun detectHoshiDictionaryTypeRecognizesPitchTermMetaBank() {
        val zip = zipBytes(
            "term_meta_bank_1.json" to """[["日本","pitch",{"reading":"にほん"}]]"""
        )

        assertEquals(HoshiDictionaryType.Pitch, detectHoshiDictionaryType(ByteArrayInputStream(zip)))
    }

    @Test
    fun detectHoshiDictionaryTypePrefersFrequencyWhenMetaBankContainsPitchThenFrequency() {
        val zip = zipBytes(
            "term_meta_bank_1.json" to """
                [
                  ["日本","pitch",{"reading":"にほん"}],
                  ["日本","freq",123]
                ]
            """.trimIndent()
        )

        assertEquals(HoshiDictionaryType.Frequency, detectHoshiDictionaryType(ByteArrayInputStream(zip)))
    }

    @Test
    fun detectHoshiDictionaryTypeKeepsTermBankAsTermDictionary() {
        val zip = zipBytes(
            "term_bank_1.json" to """[["日本","にほん","tag","","Japan",1,[]]]"""
        )

        assertEquals(HoshiDictionaryType.Term, detectHoshiDictionaryType(ByteArrayInputStream(zip)))
    }

    private fun zipBytes(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
