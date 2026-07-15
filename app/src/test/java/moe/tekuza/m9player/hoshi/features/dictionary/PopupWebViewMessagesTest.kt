package moe.tekuza.m9player.hoshi.features.dictionary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupWebViewMessagesTest {
    @Test
    fun aClosedOrSupersededLookupCannotCommit() {
        val holder = PopupWebViewCallbackHolder(
            PopupWebViewCallbacks(isLookupPopupActive = { true }),
        )

        val first = holder.beginLookup()
        val second = holder.beginLookup()

        assertFalse(holder.isLookupActive(first))
        assertTrue(holder.isLookupActive(second))

        holder.close()
        assertFalse(holder.isLookupActive(second))
    }
}
