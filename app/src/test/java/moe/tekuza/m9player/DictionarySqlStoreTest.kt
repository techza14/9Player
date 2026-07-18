package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DictionarySqlStoreTest {
    @Test
    fun nativeImportCountsClassifyDictionaryType() {
        fun result(term: Long = 0, frequency: Long = 0, pitch: Long = 0) = HoshiImportResult(
            success = true,
            title = "test",
            termCount = term,
            metaCount = frequency + pitch,
            frequencyCount = frequency,
            pitchCount = pitch,
            mediaCount = 0,
            dictPath = "",
            errors = emptyList()
        )

        assertEquals(HoshiDictionaryType.Term, classifyHoshiDictionaryType(result(term = 1, frequency = 1)))
        assertEquals(HoshiDictionaryType.Frequency, classifyHoshiDictionaryType(result(frequency = 1, pitch = 1)))
        assertEquals(HoshiDictionaryType.Pitch, classifyHoshiDictionaryType(result(pitch = 1)))
    }

    @Test
    fun dictionaryDirectoryReplacementPublishesStagedOutput() {
        val root = Files.createTempDirectory("dictionary-replace-test").toFile()
        try {
            val staged = root.resolve("staged").also(File::mkdirs)
            val target = root.resolve("target").also(File::mkdirs)
            staged.resolve("new.txt").writeText("new")
            target.resolve("old.txt").writeText("old")

            replaceDictionaryDirectory(staged, target)

            assertTrue(target.resolve("new.txt").isFile)
            assertFalse(target.resolve("old.txt").exists())
            assertFalse(staged.exists())
        } finally {
            root.deleteRecursively()
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

}
