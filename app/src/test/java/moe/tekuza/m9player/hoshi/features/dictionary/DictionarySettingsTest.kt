package moe.tekuza.m9player.hoshi.features.dictionary

import org.junit.Assert.assertEquals
import org.junit.Test

class DictionarySettingsTest {
    @Test
    fun defaultSettingsHaveNoCollapsedDictionaries() {
        val settings = DictionarySettings()

        assertEquals(emptySet<String>(), settings.collapsedDictionaries)
    }

    @Test
    fun cleanupCollapsedDictionariesRemovesDeletedDictionaryNames() {
        val cleaned = cleanupCollapsedDictionaries(
            collapsedDictionaries = setOf("JMdict", "Deleted", " "),
            availableDictionaryNames = listOf("JMdict", "Kanjidic")
        )

        assertEquals(setOf("JMdict"), cleaned)
    }
}
