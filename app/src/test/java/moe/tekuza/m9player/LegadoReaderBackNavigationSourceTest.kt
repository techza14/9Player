package moe.tekuza.m9player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegadoReaderBackNavigationSourceTest {
    @Test
    fun defaultReaderLaunchDoesNotOptIntoReturningToPlayerOnSystemBack() {
        val mainSource = File("src/main/java/moe/tekuza/m9player/MainActivity.kt").readText()
        val defaultReaderIntentBlock = mainSource
            .substringAfter("fun createLegadoReaderIntent")
            .substringBefore("fun openReaderBook")

        assertTrue(defaultReaderIntentBlock.contains("LegadoReaderActivity::class.java"))
        assertFalse(defaultReaderIntentBlock.contains("EXTRA_RETURN_TO_PLAYER_ON_BACK"))
    }

    @Test
    fun playerOpenedReaderMayReturnToPlayerOnSystemBack() {
        val playerSource = File("src/main/java/moe/tekuza/m9player/BookReaderActivity.kt").readText()
        val openEbookReaderBlock = playerSource
            .substringAfter("R.string.bookreader_open_ebook_reader")
            .substringBefore("if (!uiLayoutEditMode)")

        assertTrue(openEbookReaderBlock.contains("LegadoReaderActivity::class.java"))
        assertTrue(openEbookReaderBlock.contains("EXTRA_RETURN_TO_PLAYER_ON_BACK"))
    }

    @Test
    fun legadoSystemBackRequiresExplicitReturnToPlayerOptIn() {
        val legadoSource = File("src/main/java/moe/tekuza/m9player/LegadoReaderActivity.kt").readText()
        val returnToPlayerBlock = legadoSource
            .substringAfter("private fun returnToPlayerIfShared()")
            .substringBefore("private fun updateDisplayedBookTitle")

        assertTrue(returnToPlayerBlock.contains("returnToPlayerOnBack"))
        assertTrue(returnToPlayerBlock.contains("bridgeCanReturnToPlayer()"))
    }
}
