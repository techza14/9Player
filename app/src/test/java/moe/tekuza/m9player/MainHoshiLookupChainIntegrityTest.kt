package moe.tekuza.m9player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainHoshiLookupChainIntegrityTest {
    private val mainActivitySource: String by lazy {
        File("src/main/java/moe/tekuza/m9player/MainActivity.kt").readText()
    }
    private val collectionsPopupSource: String by lazy {
        File("src/main/java/moe/tekuza/m9player/MainCollectionsHoshiPopup.kt").readText()
    }

    @Test
    fun mainScreenKeepsNewHoshiLookupStack() {
        listOf(
            "LookupPopupStackView(",
            "mainHoshiLookupSession.createPopup(",
            "mainHoshiLookupPopups",
        ).forEach { requiredSymbol ->
            assertTrue("New Hoshi lookup stack must stay wired: $requiredSymbol", mainActivitySource.contains(requiredSymbol))
        }
    }

    @Test
    fun dictionaryPageDoesNotKeepLegacyLookupResultChain() {
        listOf(
            "var lookupResults",
            "var selectedEntryKey",
            "dictionaryLookupAutoPlayedKey",
            "dictionaryLookupCollapsedSections",
            "dictionaryPageHighlightedDefinitionKey",
            "dictionaryPageHighlightedRects",
            "mainHoshiResultHtml",
            "mainHoshiResultResults",
            "mainHoshiResultQuery",
            "mainHoshiResultInPopup",
            "mainHoshiPreviewSentence",
            "triggerLookupCandidates",
            "showMainHoshiFirstLayerResult",
            "runMainHoshiFirstLayerLookup",
            "groupedLookupResults",
        ).forEach { legacySymbol ->
            assertFalse("Legacy lookup chain should be removed: $legacySymbol", mainActivitySource.contains(legacySymbol))
        }
    }

    @Test
    fun dictionaryQueryUsesNewHoshiPopupStack() {
        val dictionaryBlock = mainActivitySource
            .substringAfter("if (activeSection == MiningSection.DICTIONARY)")
            .substringBefore("if (activeSection == MiningSection.COLLECTIONS)")

        assertTrue(
            "Dictionary query should dispatch through the new Hoshi stack path",
            dictionaryBlock.contains("triggerMainHoshiQueryLookup(")
        )
        assertFalse(
            "Dictionary query must not dispatch through the legacy lookup result state",
            dictionaryBlock.contains("triggerLookupCandidates(")
        )
        assertTrue(
            "Dictionary query should render an embedded first lookup layer",
            dictionaryBlock.contains("dictionaryFirstLayerHtml")
        )
    }

    @Test
    fun dictionaryQueryPageUsesKeyboardSearchAndFullWidthResults() {
        val dictionaryBlock = mainActivitySource
            .substringAfter("if (activeSection == MiningSection.DICTIONARY)")
            .substringBefore("if (activeSection == MiningSection.COLLECTIONS)")
        val inputPanelBlock = dictionaryBlock
            .substringAfter("OutlinedTextField(")
            .substringBefore("if (lookupLoading)")
        val resultBlock = dictionaryBlock
            .substringAfter("if (dictionaryFirstLayerHtml.isNotBlank())")
            .substringBefore("if (showDictionaryManager)")

        assertTrue(
            "Dictionary search input should use the keyboard search action",
            inputPanelBlock.contains("keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)")
        )
        assertTrue(
            "Dictionary search input should submit lookup from keyboard Search",
            inputPanelBlock.contains("keyboardActions = KeyboardActions(") &&
                inputPanelBlock.contains("onSearch = {")
        )
        assertFalse(
            "Dictionary query page should not keep an explicit query button",
            inputPanelBlock.contains("dictionary_query_button")
        )
        assertFalse(
            "Dictionary query page should not keep an explicit clear button",
            inputPanelBlock.contains("common_clear")
        )
        assertTrue(
            "Dictionary result webview should stretch as the page body",
            resultBlock.contains(".weight(1f)") && resultBlock.contains(".fillMaxWidth()")
        )
        assertFalse(
            "Dictionary result webview should not be wrapped in a small result card",
            resultBlock.contains("Card(")
        )
    }

    @Test
    fun dictionaryInlineResultUsesPageBackgroundInsteadOfWhiteCardBackground() {
        val dictionaryFirstLayerFunction = mainActivitySource
            .substringAfter("fun showDictionaryFirstLayerLookup(rawQuery: String)")
            .substringBefore("fun showCollectionFirstLayerLookup(")
        val dictionaryBlock = mainActivitySource
            .substringAfter("if (activeSection == MiningSection.DICTIONARY)")
            .substringBefore("if (activeSection == MiningSection.COLLECTIONS)")
        val resultBlock = dictionaryBlock
            .substringAfter("if (dictionaryFirstLayerHtml.isNotBlank())")
            .substringBefore("if (showDictionaryManager)")

        assertTrue(
            "Dictionary inline HTML should override the Hoshi white popup background",
            dictionaryFirstLayerFunction.contains("backgroundColorCss = dictionaryPageBackgroundCss")
        )
        assertTrue(
            "Dictionary inline result surface should blend into the page background",
            resultBlock.contains("color = dictionaryPageBackground")
        )
        assertFalse(
            "Dictionary inline result should not keep the white card background",
            resultBlock.contains("color = HoshiCardBackground")
        )
    }

    @Test
    fun collectionsPreviewEmbedsFirstLayerWithoutLegacyResultState() {
        assertTrue(
            "Collections lookup preview should still show the tapped sentence",
            collectionsPopupSource.contains("MainLookupClickableSentence(")
        )
        assertTrue(
            "Collections preview should render the first lookup layer inside the preview popup",
            collectionsPopupSource.contains("MainHoshiResultWebView(")
        )
        assertTrue(
            "Collections first layer must be driven by renamed local state",
            mainActivitySource.contains("collectionFirstLayerHtml")
        )
    }

    @Test
    fun mainLookupOverlaysCloseWhenSwitchingSections() {
        val activeSectionEffect = mainActivitySource
            .substringAfter("LaunchedEffect(activeSection) {")
            .substringBefore("LaunchedEffect(Unit)")

        assertTrue(
            "Switching bottom-nav sections should dismiss recursive and collection lookup overlays",
            activeSectionEffect.contains("closeMainLookupPopup()")
        )
    }

    @Test
    fun collectionsFirstLayerCanGrowToBottomOfWindow() {
        assertTrue(
            "Collections first-layer popup should derive its max height from the window bottom",
            collectionsPopupSource.contains("maxPopupHeight")
        )
        assertTrue(
            "Collections first-layer popup should cap at available window height",
            collectionsPopupSource.contains("windowSize.height") &&
                collectionsPopupSource.contains("bottomMargin")
        )
        assertFalse(
            "Collections first-layer popup should not stay fixed at the old 520dp height",
            collectionsPopupSource.contains("val popupHeight = 520.dp + popupFooterHeight")
        )
        assertFalse(
            "Collections first-layer result should not force a fixed popup height",
            collectionsPopupSource.contains(".height(popupHeight)")
        )
    }

    @Test
    fun duplicateSkippedDoesNotShowToastButCanOpenDuplicateSearch() {
        val exportFunction = mainActivitySource
            .substringAfter("fun exportMainHoshiLookupEntryToAnkiAsync(content: String, onComplete: (Boolean) -> Unit)")
            .substringBefore("fun mainHoshiFallbackSelection(")

        assertFalse(
            "Duplicate skipped should be quiet instead of showing the old toast",
            exportFunction.contains("if (message.isNotBlank())")
        )
        assertTrue(
            "Duplicate skipped should explicitly bypass the export toast",
            exportFunction.contains("if (message.isNotBlank() && exportResult !is AnkiExportResult.DuplicateSkipped)")
        )
        assertTrue(
            "Main lookup callbacks should expose duplicate search opening",
            mainActivitySource.contains("onViewDuplicate = { noteIds ->")
        )
    }

    @Test
    fun ankiBridgeCallbacksDoNotRunBlockingOnJsBridgeThread() {
        val blockingSource = listOf(
            "src/main/java/moe/tekuza/m9player/MainActivity.kt",
            "src/main/java/moe/tekuza/m9player/BookReaderActivity.kt",
            "src/main/java/moe/tekuza/m9player/AudiobookFloatingOverlayService.kt",
            "src/main/java/moe/tekuza/m9player/hoshi/features/dictionary/PopupWebViewMessages.kt",
        ).joinToString("\n") { File(it).readText() }

        assertFalse(
            "Anki WebView bridge paths should dispatch to coroutine callbacks instead of blocking bridge/UI threads",
            blockingSource.contains("runBlocking")
        )
        assertTrue(mainActivitySource.contains("onMineEntryAsync = { content, onComplete ->"))
        assertTrue(mainActivitySource.contains("onDuplicateCheckAsync = { expression, onComplete ->"))
    }
}
