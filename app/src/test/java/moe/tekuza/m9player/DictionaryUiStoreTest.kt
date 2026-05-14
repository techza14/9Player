package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryUiStoreTest {
    @Test
    fun normalizeDictionaryOrderIdsRemovesMissingIdsAndAppendsNewIds() {
        val normalized = normalizeDictionaryOrderIds(
            orderIds = listOf("mnt:missing", "imp:a"),
            currentIds = listOf("imp:a", "imp:b")
        )

        assertEquals(listOf("imp:a", "imp:b"), normalized)
    }

    @Test
    fun moveDictionaryOrderMovesItemWithinNormalizedOrder() {
        val moved = moveDictionaryOrder(
            orderIds = listOf("imp:a", "imp:b", "imp:c"),
            currentIds = listOf("imp:a", "imp:b", "imp:c"),
            fromIndex = 2,
            toIndex = 0
        )

        assertEquals(listOf("imp:c", "imp:a", "imp:b"), moved)
    }

    @Test
    fun moveDictionaryOrderIgnoresInvalidIndexes() {
        val order = listOf("imp:a", "imp:b")
        val moved = moveDictionaryOrder(
            orderIds = order,
            currentIds = order,
            fromIndex = 5,
            toIndex = 0
        )

        assertEquals(order, moved)
    }

    @Test
    fun orderedCombinedDictionaryItemsMixesImportedAndMountedBySavedOrder() {
        val imported = listOf(
            item("imp:a", CombinedDictionaryType.IMPORTED),
            item("imp:b", CombinedDictionaryType.IMPORTED)
        )
        val mounted = listOf(
            item("mnt:m", CombinedDictionaryType.MOUNTED)
        )

        val ordered = orderedCombinedDictionaryItems(
            importedItems = imported,
            mountedItems = mounted,
            dictionaryOrderIds = listOf("mnt:m", "imp:b", "imp:a")
        )

        assertEquals(listOf("mnt:m", "imp:b", "imp:a"), ordered.map { it.id })
    }

    @Test
    fun orderedCombinedDictionaryItemsAppendsDictionariesMissingFromSavedOrder() {
        val ordered = orderedCombinedDictionaryItems(
            importedItems = listOf(item("imp:a"), item("imp:b")),
            mountedItems = listOf(item("mnt:m", CombinedDictionaryType.MOUNTED)),
            dictionaryOrderIds = listOf("imp:b")
        )

        assertEquals(listOf("imp:b", "imp:a", "mnt:m"), ordered.map { it.id })
    }

    @Test
    fun removeDictionaryOrderIdCleansDeletedDictionaryFromOrder() {
        val cleaned = removeDictionaryOrderId(
            orderIds = listOf("imp:a", "imp:b", "mnt:m"),
            removedId = "imp:b"
        )

        assertEquals(listOf("imp:a", "mnt:m"), cleaned)
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

    @Test
    fun setMountedDictionaryEnabledUpdatesTargetOnly() {
        val state = MdxMountState(
            enabled = true,
            entries = listOf(
                mountedEntry("a", enabled = true),
                mountedEntry("b", enabled = true)
            )
        )
        val updated = setMountedDictionaryEnabled(state, cacheKey = "b", enabled = false)

        assertTrue(updated.entries[0].enabled)
        assertFalse(updated.entries[1].enabled)
    }

    private fun item(
        id: String,
        type: CombinedDictionaryType = CombinedDictionaryType.IMPORTED,
        enabled: Boolean = true
    ) = CombinedDictionaryItem(
        id = id,
        type = type,
        title = id,
        countText = "",
        enabled = enabled
    )

    private fun importedRef(cacheKey: String, enabled: Boolean = true) = PersistedDictionaryRef(
        uri = "content://dictionary/$cacheKey",
        name = cacheKey,
        cacheKey = cacheKey,
        enabled = enabled
    )

    private fun mountedEntry(cacheKey: String, enabled: Boolean = true) = MdxMountedEntry(
        treeUri = "content://tree/$cacheKey",
        mdxUri = "content://mdx/$cacheKey",
        displayName = "$cacheKey.mdx",
        cacheKey = cacheKey,
        enabled = enabled
    )
}
