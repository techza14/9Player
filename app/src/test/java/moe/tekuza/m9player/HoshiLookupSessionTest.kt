package moe.tekuza.m9player

import de.manhhao.hoshi.Frequency
import de.manhhao.hoshi.FrequencyEntry
import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.PitchEntry
import de.manhhao.hoshi.TermResult
import org.junit.Assert.assertEquals
import org.junit.Test

class HoshiLookupSessionTest {
    @Test
    fun orderLookupResultsByDictionaryPrioritySortsEmbeddedDictionarySections() {
        val result = lookupResult(
            glossaries = arrayOf(glossary("B"), glossary("A")),
            frequencies = arrayOf(frequency("B"), frequency("A")),
            pitches = arrayOf(pitch("B"), pitch("A")),
        )

        val ordered = orderLookupResultsByDictionaryPriority(
            results = listOf(result),
            dictionaryPriorityByName = mapOf("A" to 0, "B" to 1)
        )

        assertEquals(listOf("A", "B"), ordered.single().term.glossaries.map { it.dictName })
        assertEquals(listOf("A", "B"), ordered.single().term.frequencies.map { it.dictName })
        assertEquals(listOf("A", "B"), ordered.single().term.pitches.map { it.dictName })
    }

    @Test
    fun orderLookupResultsByDictionaryPrioritySortsContiguousSameTermResults() {
        val lowPriority = lookupResult(glossaries = arrayOf(glossary("B")))
        val highPriority = lookupResult(glossaries = arrayOf(glossary("A")))

        val ordered = orderLookupResultsByDictionaryPriority(
            results = listOf(lowPriority, highPriority),
            dictionaryPriorityByName = mapOf("A" to 0, "B" to 1)
        )

        assertEquals(listOf("A", "B"), ordered.map { it.term.glossaries.first().dictName })
    }

    private fun lookupResult(
        glossaries: Array<GlossaryEntry>,
        frequencies: Array<FrequencyEntry> = emptyArray(),
        pitches: Array<PitchEntry> = emptyArray(),
    ) = LookupResult(
        matched = "word",
        deinflected = "word",
        process = emptyArray(),
        term = TermResult(
            "word",
            "reading",
            "",
            glossaries,
            frequencies,
            pitches,
        ),
        preprocessorSteps = 0,
    )

    private fun glossary(dictionary: String) = GlossaryEntry(
        dictName = dictionary,
        glossary = "$dictionary glossary",
        definitionTags = "",
        termTags = "",
    )

    private fun frequency(dictionary: String) = FrequencyEntry(
        dictName = dictionary,
        frequencies = arrayOf(Frequency(value = 1, displayValue = "1")),
    )

    private fun pitch(dictionary: String) = PitchEntry(
        dictName = dictionary,
        pitchPositions = intArrayOf(1),
    )
}
