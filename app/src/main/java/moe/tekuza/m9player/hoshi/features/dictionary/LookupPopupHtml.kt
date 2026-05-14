package moe.tekuza.m9player.hoshi.features.dictionary

import android.content.Context
import android.util.Log
import de.manhhao.hoshi.LookupResult
import moe.tekuza.m9player.AudiobookSettingsConfig
import org.json.JSONArray
import org.json.JSONObject

internal data class LookupPopupAssets(
    val popupJs: String,
    val popupCss: String,
    val selectionJs: String = "",
    val rangeSelectionSvg: String = "",
    val audioSvg: String = "",
) {
    companion object {
        fun load(context: Context): LookupPopupAssets = LookupPopupAssets(
            popupJs = context.assets.open("hoshi-popup/popup.js").bufferedReader().use { it.readText() },
            popupCss = context.assets.open("hoshi-popup/popup.css").bufferedReader().use { it.readText() },
            selectionJs = context.assets.open("hoshi-popup/selection.js").bufferedReader().use { it.readText() },
            rangeSelectionSvg = context.assets.open("hoshi-popup/view_object_track.svg").bufferedReader().use { it.readText() },
            audioSvg = context.assets.open("hoshi-popup/audio.svg").bufferedReader().use { it.readText() },
        )
    }
}

internal object LookupPopupHtml {
    fun render(
        results: List<LookupResult>,
        assets: LookupPopupAssets? = null,
        dictionaryStyles: Map<String, String> = emptyMap(),
        topSpacerPx: Int = 0,
        settings: DictionarySettings = DictionarySettings(),
        audioSettings: AudiobookSettingsConfig = AudiobookSettingsConfig(),
        showPlayAudio: Boolean = false,
        showRangeSelection: Boolean = false,
        showCloseAllButton: Boolean = false,
        swipeToDismiss: Boolean = false,
        swipeThreshold: Int = 40,
        backgroundColorCss: String? = null,
        darkMode: Boolean = false,
        eInkMode: Boolean = false,
        hideUntilContentReady: Boolean = false,
    ): String {
        val entryCount = results.size
        if (results.isNotEmpty()) {
            val first = results.first()
            Log.d(
                "HoshiLookupPopup",
                "render entryCount=$entryCount firstTerm='${first.term.expression}' freqCount=${first.term.frequencies.size} pitchCount=${first.term.pitches.size} glossCount=${first.term.glossaries.size}"
            )
        } else {
            Log.d("HoshiLookupPopup", "render entryCount=0")
        }
        val entries = if (assets == null) {
            "[]"
        } else {
            JSONArray().apply {
                results.forEach { put(it.toEntryJson()) }
            }.toString()
        }
        val styles = JSONObject().apply {
            dictionaryStyles.forEach { (dictionary, css) -> put(dictionary, css) }
        }.toString()
        val normalizedSettings = settings.normalized()
        val effectiveSwipeThreshold = if (swipeToDismiss) swipeThreshold.coerceAtLeast(0) else 0
        val colorScheme = if (darkMode) "dark" else "light"
        val popupCss = assets?.let { """<style>${it.popupCss}</style>""" }
            ?: """<link rel="stylesheet" href="$PopupAssetBaseUrl/popup.css">"""
        val eInkCss = if (eInkMode) """<style>$eInkPopupCss</style>""" else ""
        val backgroundOverrideCss = backgroundColorCss
            ?.takeIf { it.isNotBlank() }
            ?.let { cssColor ->
                """
                <style>
                    html[data-hoshi-color-scheme="light"],
                    html[data-hoshi-color-scheme="light"] body,
                    html[data-hoshi-color-scheme="dark"],
                    html[data-hoshi-color-scheme="dark"] body {
                        --background-color: $cssColor;
                        --background-color-light: $cssColor;
                        background-color: $cssColor !important;
                    }
                    html[data-hoshi-color-scheme="dark"] .overlay {
                        background: $cssColor;
                    }
                </style>
                """.trimIndent()
            }
            .orEmpty()
        val contentReadyCss = if (hideUntilContentReady) {
            """
            <style>
                #entries-container { visibility: hidden; }
                html[data-hoshi-content-ready="true"] #entries-container { visibility: visible; }
            </style>
            """.trimIndent()
        } else {
            ""
        }
        val selectionJs = assets?.let { """<script>${it.selectionJs}</script>""" }
            ?: """<script src="$PopupAssetBaseUrl/selection.js"></script>"""
        val popupJs = assets?.let { """<script>${it.popupJs}</script>""" }
            ?: """<script src="$PopupAssetBaseUrl/popup.js"></script>"""
        val topSpacer = if (topSpacerPx > 0) """<div style="height: ${topSpacerPx}px;"></div>""" else ""
        val rangeSelectionSvg = JSONObject.quote(assets?.rangeSelectionSvg.orEmpty())
        val audioSvg = JSONObject.quote(assets?.audioSvg.orEmpty())
        val audioSources = if (showPlayAudio) {
            JSONArray().apply {
                put("https://hoshi-reader.manhhaoo-do.workers.dev/?term={term}&reading={reading}")
            }.toString()
        } else {
            "[]"
        }
        val entriesContainer = if (topSpacerPx > 0) {
            """<div id="entries-container" style="min-height: 100vh;"></div>"""
        } else {
            """<div id="entries-container"></div>"""
        }
        return """
            <!DOCTYPE html>
            <html data-hoshi-color-scheme="$colorScheme" data-hoshi-eink-mode="$eInkMode">
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                $popupCss
                <style>$androidColorSchemeCss</style>
                $eInkCss
                $backgroundOverrideCss
                $contentReadyCss
                $selectionJs
                $popupJs
            </head>
            <body>
                <script>
                    window.HoshiAndroidPopup = window.HoshiAndroidPopup || {
                        postMessage: function(name, body) {
                            try {
                                if (window.HoshiPopup && window.HoshiPopup.postMessage) {
                                    window.HoshiPopup.postMessage(JSON.stringify({ name: name, body: body || null }));
                                }
                            } catch (e) {
                                console.warn('HoshiPopup bridge failed', e);
                            }
                            if (name === 'tapOutside' || name === 'swipeDismiss') {
                                window.location.href = 'hoshi-popup://' + name;
                            }
                        }
                    };
                    window.webkit = {
                        messageHandlers: {
                            openLink: { postMessage: function(url) { window.HoshiAndroidPopup.postMessage('openLink', url); } },
                            textSelected: { postMessage: function(selection) { window.HoshiAndroidPopup.postMessage('textSelected', selection); } },
                            tapOutside: { postMessage: function() { window.HoshiAndroidPopup.postMessage('tapOutside'); } },
                            swipeDismiss: { postMessage: function() { window.HoshiAndroidPopup.postMessage('swipeDismiss'); } },
                            rangeSelection: { postMessage: function() { window.HoshiAndroidPopup.postMessage('rangeSelection'); } },
                            playWordAudio: { postMessage: function(content) { window.HoshiAndroidPopup.postMessage('playWordAudio', content); } },
                            contentReady: { postMessage: function() { window.HoshiAndroidPopup.postMessage('contentReady'); } },
                            mineEntry: { postMessage: async function(content) { return window.HoshiPopup.mineEntry(JSON.stringify(content)); } },
                            duplicateCheck: { postMessage: async function(expression) { return window.HoshiPopup.duplicateCheck(expression); } },
                            viewDuplicate: { postMessage: function(noteIds) { return window.HoshiPopup.viewDuplicate(JSON.stringify(noteIds || [])); } },
                            getEntry: { postMessage: async function(index) {
                                if (window.HoshiPopup && window.HoshiPopup.getEntry) {
                                    var entryJson = window.HoshiPopup.getEntry(index);
                                    return entryJson ? JSON.parse(entryJson) : null;
                                }
                                return window.lookupEntries[index];
                            } },
                            lookupRedirect: { postMessage: async function(query) {
                                if (window.HoshiPopup && window.HoshiPopup.lookupRedirect) {
                                    return window.HoshiPopup.lookupRedirect(query);
                                }
                                return 0;
                            } },
                            lookupRedirectAt: { postMessage: async function(payload) {
                                if (window.HoshiPopup && window.HoshiPopup.lookupRedirectAt) {
                                    return window.HoshiPopup.lookupRedirectAt(JSON.stringify(payload));
                                }
                                return 0;
                            } }
                        }
                    };
                    window.collapseDictionaries = ${normalizedSettings.collapseDictionaries};
                    window.compactGlossaries = ${normalizedSettings.compactGlossaries};
                    window.showExpressionTags = ${normalizedSettings.showExpressionTags};
                    window.harmonicFrequency = false;
                    window.deduplicatePitchAccents = ${normalizedSettings.deduplicatePitchAccents};
                    window.compactPitchAccents = false;
                    window.showPlayAudio = ${showPlayAudio};
                    window.showRangeSelection = ${showRangeSelection};
                    window.rangeSelectionIconSvg = $rangeSelectionSvg;
                    window.audioIconSvg = $audioSvg;
                    window.audioSources = $audioSources;
                    window.audioPlaybackMode = ${JSONObject.quote(audioSettings.lookupAudioMode.storageValue)};
                    window.disablePopupImageViewportMaxHeight = true;
                    window.audioEnableAutoplay = ${audioSettings.lookupPlaybackAudioAutoPlay};
                    window.needsAudio = ${showPlayAudio};
                    window.allowDupes = true;
                    window.useAnkiConnect = false;
                    window.embedMedia = false;
                    window.compactGlossariesAnki = false;
                    window.customCSS = ${JSONObject.quote(normalizedSettings.customCSS)};
                    window.swipeThreshold = $effectiveSwipeThreshold;
                    window.dictionaryStyles = $styles;
                    window.lookupEntries = $entries;
                    window.entryCount = $entryCount;
                </script>
                <script>
                    (function() {
                        if (!window.swipeThreshold) {
                            return;
                        }
                        var startX, startY, startTarget;
                        function isHorizontalScrollableTarget(target) {
                            if (!target || !target.closest) {
                                return false;
                            }
                            var scrollable = target.closest('.gloss-sc-table-container, .expression-scroll');
                            return !!scrollable && scrollable.scrollWidth > scrollable.clientWidth + 1;
                        }
                        document.addEventListener('touchstart', function(e) {
                            startX = e.touches[0].clientX;
                            startY = e.touches[0].clientY;
                            startTarget = e.target;
                        });
                        document.addEventListener('touchend', function(e) {
                            var dx = e.changedTouches[0].clientX - startX;
                            var dy = e.changedTouches[0].clientY - startY;
                            var absDx = Math.abs(dx);
                            var absDy = Math.abs(dy);
                            var isHorizontalDismiss = absDx > window.swipeThreshold && absDx > absDy * 1.75;
                            var hasSelection = window.getSelection().toString();
                            if (isHorizontalDismiss && !hasSelection && !isHorizontalScrollableTarget(startTarget)) {
                                webkit.messageHandlers.swipeDismiss.postMessage(null);
                            }
                            startTarget = null;
                        });
                    })();
                </script>
                $topSpacer
                $entriesContainer
                <div class="overlay">
                    <div class="overlay-close" onclick="closeOverlay()">x</div>
                    <div class="overlay-content"></div>
                </div>
                <script>
                    (function() {
                        var container = document.getElementById('entries-container');
                        var posted = false;
                        function postReady() {
                        if (posted) return;
                        posted = true;
                        requestAnimationFrame(function() {
                            requestAnimationFrame(function() {
                                document.documentElement.setAttribute('data-hoshi-content-ready', 'true');
                                webkit.messageHandlers.contentReady.postMessage(null);
                            });
                        });
                    }
                        window.addEventListener('hoshiPopupRendered', postReady, { once: true });
                        window.renderPopup();
                    })();
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    internal fun entryJsonString(result: LookupResult): String = result.toEntryJson().toString()

    private fun LookupResult.toEntryJson(): JSONObject = JSONObject().apply {
        Log.d(
            "HoshiLookupPopup",
            "entryJson term='${term.expression}' freqCount=${term.frequencies.size} pitchCount=${term.pitches.size} glossCount=${term.glossaries.size}"
        )
        put("expression", term.expression)
        put("reading", term.reading)
        put("matched", matched)
        put("deinflectionTrace", JSONArray().apply {
            process.reversedArray().forEach { name ->
                put(JSONObject().apply {
                    put("name", name)
                    put("description", "")
                })
            }
        })
        put("glossaries", JSONArray().apply {
            term.glossaries.forEach { glossary ->
                put(JSONObject().apply {
                    put("dictionary", glossary.dictName)
                    put("content", glossary.glossary)
                    put("definitionTags", glossary.definitionTags)
                    put("termTags", glossary.termTags)
                })
            }
        })
        put("frequencies", JSONArray().apply {
            term.frequencies.forEach { frequency ->
                put(JSONObject().apply {
                    put("dictionary", frequency.dictName)
                    put("frequencies", JSONArray().apply {
                        frequency.frequencies.forEach { tag ->
                            put(JSONObject().apply {
                                put("value", tag.value)
                                put("displayValue", tag.displayValue)
                            })
                        }
                    })
                })
            }
        })
        put("pitches", JSONArray().apply {
            term.pitches.forEach { pitch ->
                put(JSONObject().apply {
                    put("dictionary", pitch.dictName)
                    put("pitchPositions", JSONArray().apply {
                        pitch.pitchPositions.distinct().forEach { put(it) }
                    })
                })
            }
        })
        put("rules", JSONArray().apply {
            term.rules.splitToSequence(' ')
                .filter { it.isNotBlank() }
                .forEach { put(it) }
        })
    }

    private const val androidColorSchemeCss = """
        html[data-hoshi-color-scheme="light"],
        html[data-hoshi-color-scheme="light"] body {
            --background-color: #ffffff;
            --background-color-light: #ffffff;
            --text-color: #000;
            color-scheme: light;
            background-color: #ffffff !important;
        }

        html[data-hoshi-color-scheme="dark"],
        html[data-hoshi-color-scheme="dark"] body {
            --background-color: #202C3A;
            --background-color-light: #202C3A;
            --text-color: #fff;
            --text-color-light1: #D6DEE8;
            --text-color-light2: #B7C3D1;
            --text-color-light3: #9BA8B8;
            --text-color-light4: #8391A3;
            --background-color-dark1: #17202B;
            color-scheme: dark;
            background-color: #202C3A !important;
        }

        html[data-hoshi-color-scheme="dark"] .overlay {
            background: #202C3A;
        }

        html[data-hoshi-color-scheme="dark"] .glossary-group > div[data-dictionary] {
            color: var(--text-color) !important;
        }
    """

    private const val eInkPopupCss = """
        html[data-hoshi-eink-mode="true"],
        html[data-hoshi-eink-mode="true"] body {
            --background-color: #fff;
            --background-color-light: #fff;
            --text-color: #000;
            --text-color-light1: #000;
            --text-color-light2: #000;
            --text-color-light3: #000;
            --text-color-light4: #000;
            --background-color-dark1: #fff;
            color-scheme: light;
        }
        html[data-hoshi-eink-mode="true"] *,
        html[data-hoshi-eink-mode="true"] *::before,
        html[data-hoshi-eink-mode="true"] *::after {
            transition: none !important;
            animation-duration: 0s !important;
            box-shadow: none !important;
            text-shadow: none !important;
        }
    """

    private const val PopupAssetBaseUrl = "https://hoshi.local/popup"
}
