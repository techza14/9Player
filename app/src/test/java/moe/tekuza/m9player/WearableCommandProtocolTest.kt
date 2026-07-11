package moe.tekuza.m9player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
