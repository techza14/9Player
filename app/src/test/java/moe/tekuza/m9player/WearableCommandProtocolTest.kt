package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearableCommandProtocolTest {
    @Test
    fun acceptsOnlyKnownJsonCommands() {
        assertEquals(
            WearableCommand("COLLECT_CURRENT", "7"),
            WearableCommandProtocol.validate("collect_current", "7")
        )
        assertNull(WearableCommandProtocol.validate("DELETE_ALL"))
        assertNull(WearableCommandProtocol.validate(""))
    }

    @Test
    fun forwardsCollectToTheReaderControlCallback() {
        var calls = 0
        BookReaderFloatingBridge.setControlCollectListener(object : BookReaderFloatingBridge.ControlCollectListener {
            override fun onControlCollectRequested(): Boolean {
                calls += 1
                return true
            }
        })
        try {
            assertTrue(BookReaderFloatingBridge.requestControlCollect())
            assertEquals(1, calls)
        } finally {
            BookReaderFloatingBridge.setControlCollectListener(null)
        }
    }
}
