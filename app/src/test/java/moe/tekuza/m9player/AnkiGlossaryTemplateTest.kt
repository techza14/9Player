package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiGlossaryTemplateTest {
    @Test
    fun glossaryFirstUsesTheFirstGlossaryItemHtml() {
        assertEquals(
            "eat",
            buildGlossaryFirstItemHtml(listOf("eat", "drink"))
        )
    }

    @Test
    fun glossaryFirstPreservesExistingListItemMarkup() {
        assertEquals(
            "eat",
            buildGlossaryFirstItemHtml(listOf("<li>eat</li>", "<li>drink</li>"))
        )
    }

    @Test
    fun glossaryFirstExtractsFirstItemFromWrappedGlossaryMarkup() {
        assertEquals(
            "alpha",
            buildGlossaryFirstItemHtml(
                listOf(
                    """<li><div class="yomitan-glossary"><ol><li data-dictionary="大辞林　第四版">alpha</li><li data-dictionary="大辞林　第四版">beta</li></ol></div></li>"""
                )
            )
        )
    }

    @Test
    fun glossaryFirstReturnsEmptyForBlankDefinitions() {
        assertEquals("", buildGlossaryFirstItemHtml(listOf("   ")))
    }

    @Test
    fun glossaryFirstRemovesLeadingIndexButKeepsDictionaryName() {
        assertEquals(
            """<i>(大辞林　第四版)</i> alpha""",
            buildGlossaryFirstItemHtml(
                listOf("""<li data-dictionary="大辞林　第四版"><i>(1, 大辞林　第四版)</i> alpha</li>""")
            )
        )
    }

    @Test
    fun glossaryBriefUsesTheFirstGlossaryItemHtml() {
        assertEquals(
            """<i>(大辞林　第四版)</i> alpha""",
            buildGlossaryBriefHtml(
                listOf(
                    """<li data-dictionary="大辞林　第四版"><i>(1, 大辞林　第四版)</i> alpha</li>""",
                    """<li data-dictionary="大辞林　第四版"><i>(2, 大辞林　第四版)</i> beta</li>"""
                )
            )
        )
    }

    @Test
    fun glossaryNoDictionaryRemovesDictionaryLabelFromEachItem() {
        assertEquals(
            "alpha<br>beta",
            buildGlossaryNoDictionaryHtml(
                listOf(
                    """<li data-dictionary="大辞林　第四版"><i>(1, 大辞林　第四版)</i> alpha</li>""",
                    """<li data-dictionary="大辞林　第四版"><i>(2, 大辞林　第四版)</i> beta</li>"""
                )
            )
        )
    }

    @Test
    fun glossaryHtmlOmitsNumericDictionaryLabels() {
        val html = renderYomitanGlossaryHtml(
            items = listOf(
                GlossaryHtmlItem(
                    dictionaryName = "大辞林　第四版",
                    definitions = listOf("alpha", "beta"),
                    dictionaryCss = null
                ),
                GlossaryHtmlItem(
                    dictionaryName = "大辞泉 第二版",
                    definitions = listOf("gamma"),
                    dictionaryCss = null
                )
            )
        )
        assertFalse(html.contains("(1,"))
        assertFalse(html.contains("<li"))
    }

    @Test
    fun glossaryHtmlWithoutListItemsKeepsDictionaryScopedCss() {
        val html = renderYomitanGlossaryHtml(
            items = listOf(
                GlossaryHtmlItem(
                    dictionaryName = "大辞林　第四版",
                    definitions = listOf("""<span class="term">alpha</span>"""),
                    dictionaryCss = """.term { color: red; }"""
                )
            )
        )

        assertFalse(html.contains("<li"))
        assertTrue(html.contains("""<div data-dictionary="大辞林　第四版""""))
        assertTrue(html.contains(""".yomitan-glossary [data-dictionary="大辞林　第四版"] .term"""))
        assertTrue(html.contains("color: red"))
    }

    @Test
    fun glossaryHtmlPreservesStructuredContentAttributeNames() {
        val html = renderYomitanGlossaryHtml(
            items = listOf(
                GlossaryHtmlItem(
                    dictionaryName = "小学館3日中",
                    definitions = listOf(
                        """<div data-sc-previous_h3="" lang="ja">読める<span data-sc-class_pinyin_h="" lang="ja">よめる</span></div>"""
                    ),
                    dictionaryCss = null
                )
            )
        )

        assertTrue(html.contains("""data-sc-previous_h3="""))
        assertTrue(html.contains("""data-sc-class_pinyin_h="""))
        assertFalse(html.contains("""data-sc-previous-h3"""))
        assertFalse(html.contains("""data-sc-class-pinyin-h"""))
    }

    @Test
    fun glossaryFirstKeepsDictionaryScopedCssWhileUsingOnlyTheFirstItem() {
        val firstItem = buildGlossaryFirstItemHtml(
            listOf(
                """<li data-dictionary="小学館3日中"><i>(小学館3日中)</i> <span><div data-sc-previous_h3="" lang="ja">読める<span data-sc-class_pinyin_h="" lang="ja">よめる</span></div></span></li>"""
            )
        )

        val html = renderYomitanGlossaryHtml(
            items = listOf(
                GlossaryHtmlItem(
                    dictionaryName = "小学館3日中",
                    definitions = listOf(firstItem),
                    dictionaryCss = """.term { color: red; }"""
                )
            )
        )

        assertTrue(html.contains("""<div data-dictionary="小学館3日中""""))
        assertTrue(html.contains("""data-sc-previous_h3="""))
        assertTrue(html.contains(""".yomitan-glossary [data-dictionary="小学館3日中"] .term"""))
    }
}
