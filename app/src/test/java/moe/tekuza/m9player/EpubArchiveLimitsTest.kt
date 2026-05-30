package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class EpubArchiveLimitsTest {
    @Test
    fun safeEpubPathRejectsTraversalAndAbsoluteNames() {
        assertEquals("OPS/chapter.xhtml", normalizeSafeEpubArchivePath("""OPS\chapter.xhtml"""))
        assertNull(normalizeSafeEpubArchivePath("../evil.xhtml"))
        assertNull(normalizeSafeEpubArchivePath("OPS/../../evil.xhtml"))
        assertNull(normalizeSafeEpubArchivePath("/absolute.xhtml"))
        assertNull(normalizeSafeEpubArchivePath("C:/absolute.xhtml"))
    }

    @Test
    fun limitedReadRejectsSingleEntryOverflow() {
        assertIllegalArgument {
            ByteArrayInputStream(ByteArray(9)).readBytesLimited(
                maxEntryBytes = 8,
                remainingTotalBytes = 128
            )
        }
    }

    @Test
    fun limitedCopyRejectsTotalBudgetOverflow() {
        assertIllegalArgument {
            ByteArrayInputStream(ByteArray(9)).copyToLimited(
                output = ByteArrayOutputStream(),
                maxEntryBytes = 128,
                remainingTotalBytes = 8
            )
        }
    }

    @Test
    fun fallbackMemoryBudgetIsStricterThanArchiveBudget() {
        assertIllegalArgument {
            requireEpubReaderMemoryEntryBudget(EPUB_READER_MEMORY_MAX_ENTRIES + 1)
        }
        assertIllegalArgument {
            requireKnownEpubEntrySize(
                size = EPUB_READER_MEMORY_MAX_ENTRY_BYTES + 1,
                maxEntryBytes = EPUB_READER_MEMORY_MAX_ENTRY_BYTES
            )
        }
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}
