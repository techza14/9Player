package moe.tekuza.m9player.hoshi.features.dictionary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LookupPopupHtmlShimTest {
    @Test
    fun lookupPopupHtmlExposesDictionaryCollapseAndCustomCssSettings() {
        val htmlSource = File("src/main/java/moe/tekuza/m9player/hoshi/features/dictionary/LookupPopupHtml.kt").readText()
        val bridgeSource = File("src/main/java/moe/tekuza/m9player/hoshi/features/dictionary/PopupWebViewMessages.kt").readText()
        val popupViewSource = File("src/main/java/moe/tekuza/m9player/hoshi/features/dictionary/LookupPopupView.kt").readText()
        val popupScript = File("src/main/assets/hoshi-popup/popup.js").readText()

        assertTrue(htmlSource.contains("""window.collapsedDictionaries = ${'$'}collapsedDictionaries;"""))
        assertTrue(htmlSource.contains("""window.customCSS = ${'$'}{JSONObject.quote(normalizedSettings.customCSS)};"""))
        assertTrue(htmlSource.contains("""window.dictionaryMediaRequestEndpoint = "https://hoshi.local/image";"""))
        assertTrue(bridgeSource.contains("""uri.scheme == "https"""))
        assertFalse(bridgeSource.contains("""uri.scheme == "image"""))
        assertFalse(popupViewSource.contains("""uri.scheme == "image"""))
        assertFalse(popupScript.contains("image://"))
        assertFalse(popupScript.contains("showImagePreview"))
    }

    @Test
    fun androidWebKitShimExposesHandlersPopupJsCallsDuringEntryRender() {
        val htmlSource = File("src/main/java/moe/tekuza/m9player/hoshi/features/dictionary/LookupPopupHtml.kt").readText()

        assertTrue(htmlSource.contains("duplicateCheck: { postMessage: async function(expression)"))
        assertTrue(htmlSource.contains("viewDuplicate: { postMessage: function(noteIds)"))
        assertTrue(htmlSource.contains("mineEntry: { postMessage: async function(content)"))
        assertTrue(htmlSource.contains("getEntry: { postMessage: async function(index)"))
        assertTrue(htmlSource.contains("playWordAudio: { postMessage: function(content)"))
        assertTrue(htmlSource.contains("window.HoshiAndroidPopupBridge"))
        assertTrue(htmlSource.contains("makeRequestId(name)"))
        assertTrue(htmlSource.contains("window.HoshiPopup.mineEntryAsync(requestId, body)"))
        assertTrue(htmlSource.contains("window.HoshiPopup.duplicateCheckAsync(requestId, body)"))
    }

    @Test
    fun popupDuplicateStateCanOpenExistingAnkiNotes() {
        val popupJsSource = File("src/main/assets/hoshi-popup/popup.js").readText()
        val popupCssSource = File("src/main/assets/hoshi-popup/popup.css").readText()
        val bridgeSource = File("src/main/java/moe/tekuza/m9player/hoshi/features/dictionary/PopupWebViewMessages.kt").readText()

        assertTrue(popupJsSource.contains("createDuplicateSearchButton("))
        assertTrue(popupJsSource.contains("className: 'mine-button-stack'"))
        assertTrue(popupJsSource.contains("mineButtonStack.appendChild(mineButton)"))
        assertTrue(popupJsSource.contains("mineButtonStack.appendChild(duplicateSearchButton)"))
        assertTrue(popupJsSource.contains("function stopDuplicateSearchPointerEvent(event)"))
        assertTrue(popupJsSource.contains("onpointerdown: stopDuplicateSearchPointerEvent"))
        assertTrue(popupJsSource.contains("event.stopPropagation();"))
        assertTrue(popupCssSource.contains(".mine-button-stack {") && popupCssSource.contains("position: relative;"))
        assertTrue(popupCssSource.contains(".duplicate-search-button {") && popupCssSource.contains("position: absolute;"))
        assertTrue(popupCssSource.contains("width: 42px;"))
        assertTrue(popupCssSource.contains("height: 42px;"))
        assertTrue(popupCssSource.contains(".duplicate-search-button {") && popupCssSource.contains("background-color: transparent;"))
        assertTrue(popupCssSource.contains(".duplicate-search-button:active"))
        assertTrue(popupCssSource.contains(".duplicate-search-button svg {") && popupCssSource.contains("width: 18px;"))
        assertTrue(popupCssSource.contains(".duplicate-search-button svg {") && popupCssSource.contains("height: 18px;"))
        assertTrue(!popupJsSource.contains("buttonsContainer.appendChild(duplicateSearchButton)"))
        assertTrue(!popupJsSource.contains("entryDiv.appendChild(header.duplicateSearchButton)"))
        assertTrue(!popupJsSource.contains("View duplicate / 查看重复</span>"))
        assertTrue(popupJsSource.contains("webkit.messageHandlers.viewDuplicate.postMessage(noteIds)"))
        assertTrue(popupJsSource.contains("normalizeDuplicateCheckResult("))
        assertTrue(bridgeSource.contains("fun duplicateCheck(expression: String): String"))
        assertTrue(bridgeSource.contains("fun duplicateCheckAsync(requestId: String, expression: String)"))
        assertTrue(bridgeSource.contains("fun mineEntryAsync(requestId: String, content: String)"))
        assertTrue(bridgeSource.contains("postAsyncBridgeResult(requestId"))
        assertTrue(bridgeSource.contains("fun viewDuplicate(noteIdsJson: String): Boolean"))
    }

    @Test
    fun popupScopesDictionaryCssBeforeInjectingIt() {
        val popupJsSource = File("src/main/assets/hoshi-popup/popup.js").readText()

        assertTrue(popupJsSource.contains("function scopeCssRules(css, scopeSelector)"))
        assertTrue(popupJsSource.contains("const scopedDictStyle = constructDictCss(dictStyle, dictName);"))
        assertTrue(popupJsSource.contains("textContent: [scopedDictStyle, defaultDictStyle].filter(Boolean).join('\\n')"))
        assertTrue(!popupJsSource.contains("""${'$'}{dictStyle}
                color: var(--text-color)"""))
    }

    @Test
    fun mainFirstLayerLookupUsesPopupAudioSvgAsset() {
        val popupCssSource = File("src/main/assets/hoshi-popup/popup.css").readText()
        val audioSvgSource = File("src/main/assets/hoshi-popup/audio.svg").readText()
        val mainActivitySource = File("src/main/java/moe/tekuza/m9player/MainActivity.kt").readText()

        assertTrue(mainActivitySource.contains("val mainHoshiLookupAssets = remember(context) { LookupPopupAssets.load(context) }"))
        assertTrue(mainActivitySource.contains("assets = mainHoshiLookupAssets"))
        assertTrue(!mainActivitySource.contains("assets = null,"))
        assertTrue(audioSvgSource.contains("M12 3v10.55"))
        assertTrue(popupCssSource.contains(".audio-button svg {") && popupCssSource.contains("width: 18px;"))
        assertTrue(popupCssSource.contains(".audio-button svg {") && popupCssSource.contains("height: 18px;"))
        assertTrue(popupCssSource.contains(".range-button svg {") && popupCssSource.contains("width: 18px;"))
        assertTrue(popupCssSource.contains(".range-button svg {") && popupCssSource.contains("height: 18px;"))
        assertTrue(popupCssSource.contains(".duplicate-search-button svg {") && popupCssSource.contains("width: 18px;"))
        assertTrue(popupCssSource.contains(".duplicate-search-button svg {") && popupCssSource.contains("height: 18px;"))
        assertTrue(!popupCssSource.contains("color: #32679A;"))
        assertTrue(!popupCssSource.contains("width: 20px;"))
        assertTrue(!popupCssSource.contains("height: 20px;"))
        assertTrue(!popupCssSource.contains("width: 16px;"))
        assertTrue(!popupCssSource.contains("height: 16px;"))
    }

    @Test
    fun mainLookupPreviewUsesHighlightedSentence() {
        val mainActivitySource = File("src/main/java/moe/tekuza/m9player/MainActivity.kt").readText()
        val collectionsPopupSource = File("src/main/java/moe/tekuza/m9player/MainCollectionsHoshiPopup.kt").readText()

        assertTrue(mainActivitySource.contains("""previewSentence = collectionLookupPreviewSentence"""))
        assertTrue(mainActivitySource.contains("""selectedRange = collectionLookupPreviewSelectedRange"""))
        assertTrue(collectionsPopupSource.contains("""text = buildMainHighlightedText(previewSentence, selectedRange)"""))
    }

    @Test
    fun mainLookupPreviewRendersEmbeddedFirstLayer() {
        val mainActivitySource = File("src/main/java/moe/tekuza/m9player/MainActivity.kt").readText()

        assertTrue(mainActivitySource.contains("""showCollectionFirstLayerLookup("""))
        assertTrue(mainActivitySource.contains("""sentence = collectionLookupPreviewSentence"""))
        assertTrue(mainActivitySource.contains("""sentenceOffset = selection.range.first"""))
        assertTrue(mainActivitySource.contains("""collectionFirstLayerHtml"""))
    }

    @Test
    fun hoshiPopupUsesWhiteCardColorAndLightSentenceHighlight() {
        val colorSource = File("src/main/java/moe/tekuza/m9player/HoshiUiColors.kt").readText()
        val popupViewSource = File("src/main/java/moe/tekuza/m9player/hoshi/features/dictionary/LookupPopupView.kt").readText()
        val htmlSource = File("src/main/java/moe/tekuza/m9player/hoshi/features/dictionary/LookupPopupHtml.kt").readText()
        val mainActivitySource = File("src/main/java/moe/tekuza/m9player/MainActivity.kt").readText()

        assertTrue(colorSource.contains("""internal val HoshiPanelBackground = Color(0xFFEDF3FA)"""))
        assertTrue(colorSource.contains("""internal val HoshiCardBackground = Color(0xFFFFFFFF)"""))
        assertTrue(popupViewSource.contains("""val popupBackground = if (state.darkMode) HoshiDarkCardBackground else HoshiCardBackground"""))
        assertTrue(htmlSource.contains("""--background-color: #ffffff;"""))
        assertTrue(htmlSource.contains("""background-color: #ffffff !important;"""))
        assertTrue(mainActivitySource.contains("""SpanStyle(background = Color(0x1FA0A0A0))"""))
    }

    @Test
    fun mainLookupPreviewSentenceIsNoLongerCappedToFixedHeight() {
        val mainActivitySource = File("src/main/java/moe/tekuza/m9player/MainActivity.kt").readText()
        val collectionsPopupSource = File("src/main/java/moe/tekuza/m9player/MainCollectionsHoshiPopup.kt").readText()

        assertTrue(!mainActivitySource.contains("""heightIn(min = 96.dp, max = 180.dp)"""))
        assertTrue(!collectionsPopupSource.contains("""heightIn(min = 96.dp, max = 180.dp)"""))
        assertTrue(collectionsPopupSource.contains("""wrapContentHeight()"""))
    }

}
