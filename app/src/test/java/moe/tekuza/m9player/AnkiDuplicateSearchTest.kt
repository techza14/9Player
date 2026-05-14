package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiDuplicateSearchTest {
    @Test
    fun duplicateNoteIdsBecomeAnkiBrowserSearchQuery() {
        assertEquals(
            "nid:123 or nid:456 or nid:789",
            buildAnkiDuplicateNoteSearchQuery(listOf(123L, 456L, 456L, -1L, 0L, 789L))
        )
    }
}
