package moe.tekuza.m9player

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryUiStoreTest {
    @Test
    fun buildCombinedDictionaryItemsKeepsImportedOrder() {
        val ordered = buildCombinedDictionaryItems(
            context = ContextWrapper(null),
            dictionaryRefs = listOf(importedRef("b"), importedRef("a"))
        )

        assertEquals(listOf("imp:b", "imp:a"), ordered.map { it.id })
    }

    @Test
    fun moveImportedDictionaryRefsMovesByDictionaryId() {
        val refs = listOf(importedRef("a"), importedRef("b"), importedRef("c"))
        val moved = moveImportedDictionaryRefs(
            dictionaryRefs = refs,
            dictionaryId = importedDictionaryId(refs[2]),
            toIndex = 0
        )

        assertEquals(listOf("c", "a", "b"), moved.map { it.name })
    }

    @Test
    fun moveImportedDictionaryRefsIgnoresMissingDictionaryId() {
        val refs = listOf(importedRef("a"), importedRef("b"))
        val moved = moveImportedDictionaryRefs(
            dictionaryRefs = refs,
            dictionaryId = "imp:missing",
            toIndex = 0
        )

        assertSame(refs, moved)
    }

    @Test
    fun setImportedDictionaryEnabledUpdatesTargetOnly() {
        val a = importedRef("a", enabled = true)
        val b = importedRef("b", enabled = true)
        val updated = setImportedDictionaryEnabled(
            dictionaryRefs = listOf(a, b),
            dictionaryId = importedDictionaryId(b),
            enabled = false
        )

        assertTrue(updated[0].enabled)
        assertFalse(updated[1].enabled)
    }

    @Test
    fun setImportedDictionaryEnabledReturnsSameListWhenNothingChanges() {
        val refs = listOf(importedRef("a", enabled = false))
        val updated = setImportedDictionaryEnabled(
            dictionaryRefs = refs,
            dictionaryId = importedDictionaryId(refs.first()),
            enabled = false
        )

        assertSame(refs, updated)
    }

    private fun importedRef(cacheKey: String, enabled: Boolean = true) = PersistedDictionaryRef(
        uri = "content://dictionary/$cacheKey",
        name = cacheKey,
        cacheKey = cacheKey,
        enabled = enabled
    )
}
