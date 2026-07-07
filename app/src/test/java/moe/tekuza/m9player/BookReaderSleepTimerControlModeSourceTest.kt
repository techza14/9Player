package moe.tekuza.m9player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BookReaderSleepTimerControlModeSourceTest {
    @Test
    fun sleepTimerLogsControlModeExitState() {
        val source = File("src/main/java/moe/tekuza/m9player/BookReaderActivity.kt").readText()
        val timerBlock = source
            .substringAfter("if (remainingMs <= 0L) {")
            .substringBefore("if (statusParts.isEmpty()) {")

        assertTrue(timerBlock.contains("BOOK_READER_SLEEP_LOG_TAG"))
        assertTrue(timerBlock.contains("controlModeBefore=\$controlModeEnabled"))
        assertTrue(timerBlock.contains("controlModeEnabled = false"))
        assertTrue(timerBlock.contains("controlModeAfter=\$controlModeEnabled"))
    }
}
