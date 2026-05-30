package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun detectHoshiDictionaryTypeRejectsUnsafeZipEntryPath() {
        val zip = zipBytes(
            "../term_bank_1.json" to """[["日本","にほん","tag","","Japan",1,[]]]"""
        )

        assertIllegalArgument {
            detectHoshiDictionaryType(ByteArrayInputStream(zip))
        }
    }

    @Test
    fun glossaryHtmlSanitizerRemovesExecutableHtml() {
        val html = glossaryRawToDefinitionHtmlSql(
            """<div onclick="mineEntry()">safe<script>bad()</script><a href="javascript:bad()">x</a><iframe srcdoc="<p>x</p>"></iframe></div>"""
        )

        assertTrue(html.contains("safe"))
        assertFalse(html.contains("onclick", ignoreCase = true))
        assertFalse(html.contains("<script", ignoreCase = true))
        assertFalse(html.contains("javascript:", ignoreCase = true))
        assertFalse(html.contains("<iframe", ignoreCase = true))
        assertFalse(html.contains("srcdoc", ignoreCase = true))
    }

    @Test
    fun structuredGlossaryRejectsDangerousTagsAndUrls() {
        val script = glossaryRawToDefinitionHtmlSql("""{"tag":"script","content":"alert(1)"}""")
        val image = glossaryRawToDefinitionHtmlSql("""{"type":"image","path":"javascript:alert(1)","content":"x"}""")

        assertTrue(script.contains("alert(1)"))
        assertFalse(script.contains("<script", ignoreCase = true))
        assertFalse(image, image.contains("<img", ignoreCase = true))
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

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            org.junit.Assert.fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
