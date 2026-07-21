package moe.tekuza.m9player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingOverlayReaderExitSourceTest {
    @Test
    fun bookReaderShowsOverlayInAppWhenReaderExitSettingIsEnabled() {
        val source = File("src/main/java/moe/tekuza/m9player/BookReaderActivity.kt").readText()
        val onStopBlock = source
            .substringAfter("override fun onStop()")
            .substringBefore("override fun onDestroy()")

        assertTrue(onStopBlock.contains("floatingOverlayShowOnReaderExit || !appForeground"))
        assertTrue(onStopBlock.contains("!readerOrPlayerVisible"))
        assertFalse(onStopBlock.contains("!isAppProcessInForeground(this@BookReaderActivity) &&"))
    }

    @Test
    fun legadoReaderUsesSameReaderExitOverlayRule() {
        val source = File("src/main/java/moe/tekuza/m9player/LegadoReaderActivity.kt").readText()
        val onStopBlock = source
            .substringAfter("override fun onStop()")
            .substringBefore("private fun intentLocalReaderBook()")

        assertTrue(onStopBlock.contains("floatingOverlayShowOnReaderExit || !appForeground"))
        assertTrue(onStopBlock.contains("!readerOrPlayerVisible"))
    }

    @Test
    fun floatingBridgeControlsFallbackToSharedPlaybackSessionAfterReaderExit() {
        val source = File("src/main/java/moe/tekuza/m9player/BookReaderFloatingBridge.kt").readText()

        assertFalse(source.contains("interface Controller"))
        assertFalse(source.contains("fun attach("))
        assertFalse(source.contains("fun detach("))
        assertTrue(source.contains("BookReaderPlaybackSession.togglePlayPause()"))
        assertTrue(source.contains("fun seekAdjacent(context: Context, step: Int)"))
        assertTrue(source.contains("BookReaderPlaybackSession.seekToPosition(cueStartMs)"))
    }
}
