package moe.tekuza.m9player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BookReaderSleepTimerControlModeSourceTest {
    @Test
    fun sleepTimerExitsControlModeBeforeBluetoothDisconnect() {
        val source = File("src/main/java/moe/tekuza/m9player/BookReaderActivity.kt").readText()
        val timerBlock = source
            .substringAfter("if (remainingMs <= 0L) {")
            .substringBefore("if (statusParts.isEmpty()) {")

        assertTrue(timerBlock.contains("controlModeEnabled = false"))
        assertTrue(timerBlock.contains("view.keepScreenOn = false"))
        assertTrue(timerBlock.contains("tryDisconnectTargetControllerThenDisableBluetooth("))
        assertTrue(
            timerBlock.indexOf("controlModeEnabled = false") <
                timerBlock.indexOf("tryDisconnectTargetControllerThenDisableBluetooth(")
        )
    }
}
