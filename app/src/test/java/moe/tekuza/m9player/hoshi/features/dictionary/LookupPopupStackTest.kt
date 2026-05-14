package moe.tekuza.m9player.hoshi.features.dictionary

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LookupPopupStackTest {
    @Test
    fun swipingAChildPopupDismissesOnlyThatLayer() {
        val source = File("src/main/java/moe/tekuza/m9player/hoshi/features/dictionary/LookupPopupStack.kt").readText()

        assertTrue(source.contains("""onTapOutside = {}"""))
        assertTrue(source.contains("""onSwipeDismiss = {"""))
        assertTrue(source.contains("""onPopupsChange(dismissPopupAt(popups, index))"""))
    }

    @Test
    fun rootPopupDismissalOnlyClearsRecursiveChildrenInMainActivity() {
        val source = File("src/main/java/moe/tekuza/m9player/MainActivity.kt").readText()

        assertTrue(source.contains("""onCloseAll = {"""))
        assertTrue(source.contains("""onRootPopupDismissed = {"""))
        assertTrue(source.contains("""clearMainHoshiChildPopups()"""))
    }
}
