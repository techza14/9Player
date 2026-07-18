package moe.tekuza.m9player.hoshi.features.dictionary

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupExternalLinkOpenerTest {
    @Test
    fun acceptsBrowserAndPlatformLinksButRejectsUnsafeSchemes() {
        assertEquals("https://example.com/path", externalBrowserUrl(" https://example.com/path "))
        assertEquals("mailto:test@example.com", externalBrowserUrl("mailto:test@example.com"))
        assertEquals("tel:+123", externalBrowserUrl("tel:+123"))
        assertNull(externalBrowserUrl("javascript:alert(1)"))
        assertNull(externalBrowserUrl("intent://example.com"))
        assertNull(externalBrowserUrl("https:///missing-host"))
    }

    @Test
    fun everyLookupHostUsesTheSharedExternalLinkOpener() {
        listOf(
            "src/main/java/moe/tekuza/m9player/MainActivity.kt",
            "src/main/java/moe/tekuza/m9player/MainCollectionsHoshiPopup.kt",
            "src/main/java/moe/tekuza/m9player/AudiobookFloatingOverlayService.kt",
            "src/main/java/moe/tekuza/m9player/hoshi/features/dictionary/LookupPopupView.kt",
        ).forEach { path ->
            assertTrue("Missing popup external-link callback in $path", File(path).readText().contains("onOpenLink ="))
        }
    }
}
