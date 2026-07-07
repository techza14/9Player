package moe.tekuza.m9player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeImportAndSleepTimerSourceTest {
    @Test
    fun addBookDialogDoesNotOfferDeletingSourceFiles() {
        val source = File("src/main/java/moe/tekuza/m9player/MainDialogs.kt").readText()
        val addBookDialogBlock = source
            .substringAfter("internal fun AddBookDialog(")
            .substringBefore("dismissButton =")

        assertFalse(addBookDialogBlock.contains("delete_books_delete_source_files"))
        assertFalse(addBookDialogBlock.contains("onDeleteSourceFilesWhenAutoMoveChange"))
    }

    @Test
    fun homeImportDeletesSourceFilesWhenAutoMoving() {
        val source = File("src/main/java/moe/tekuza/m9player/MainActivity.kt").readText()
        val confirmBlock = source
            .substringAfter("fun confirmAddBookFromDialog()")
            .substringBefore("importResult.onSuccess")

        assertTrue(confirmBlock.contains("val deleteSourceFilesForAutoMove = shouldAutoMove"))
        assertTrue(confirmBlock.contains("deleteSourceFiles = deleteSourceFilesForAutoMove"))
    }
}
