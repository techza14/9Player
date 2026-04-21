package moe.tekuza.m9player

internal data class GlossaryHtmlItem(
    val dictionaryName: String,
    val definitions: List<String>,
    val dictionaryCss: String?
)

internal fun renderYomitanGlossaryHtml(
    items: List<GlossaryHtmlItem>,
    includeDictionaryLabel: Boolean = true,
    includeParityCss: Boolean = true
): String {
    val normalizedItems = items.mapNotNull { item ->
        val defs = item.definitions
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (defs.isEmpty()) {
            null
        } else {
            item.copy(
                dictionaryName = item.dictionaryName.trim(),
                definitions = defs
            )
        }
    }
    if (normalizedItems.isEmpty()) return ""

    val cssChunks = normalizedItems
        .mapNotNull { item ->
            val dictionaryAttr = resolveDictionaryAttr(item.dictionaryName)
            scopeDictionaryCssLikeHoshi(item.dictionaryCss.orEmpty(), dictionaryAttr)
                .trim()
                .ifBlank { null }
        }
        .toMutableList()
    if (includeParityCss) {
        cssChunks += glossaryDisplayParityCss()
    }
    val styleBlock = cssChunks
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString(separator = "\n")
        .let { css -> if (css.isBlank()) "" else "<style>$css</style>" }

    val itemsHtml = normalizedItems.joinToString(separator = "") { item ->
        val dictionaryLabel = item.dictionaryName
        val dictionaryAttr = resolveDictionaryAttr(dictionaryLabel)
        val safeAttr = escapeHtmlAttributeShared(dictionaryAttr)
        val safeLabel = escapeHtmlTextShared(dictionaryLabel)
        val leadingLabel = if (includeDictionaryLabel && dictionaryLabel.isNotBlank()) {
            "<i>($safeLabel)</i> "
        } else {
            ""
        }
        val allLi = item.definitions.all { it.trimStart().startsWith("<li", ignoreCase = true) }
        val content = if (allLi) {
            "<ol>${item.definitions.joinToString(separator = "")}</ol>"
        } else {
            item.definitions.joinToString(separator = "<br>")
        }
        val wrappedContent = if (allLi) content else "<span>$content</span>"
        """
            <li data-dictionary="$safeAttr">
                $leadingLabel$wrappedContent
            </li>
        """.trimIndent()
    }

    return """
        <div style="text-align: left;" class="yomitan-glossary">
            <ol>
                $itemsHtml
            </ol>
            $styleBlock
        </div>
    """.trimIndent()
}

internal fun scopeDictionaryCssLikeHoshi(rawCss: String, dictionaryName: String): String {
    val trimmed = rawCss.trim()
    if (trimmed.isBlank()) return ""
    val prefix = if (dictionaryName.isBlank()) {
        ".yomitan-glossary"
    } else {
        val dictionaryAttr = escapeCssStringShared(dictionaryName)
        ".yomitan-glossary [data-dictionary=\"$dictionaryAttr\"]"
    }
    return scopeCssRecursive(trimmed, prefix).trim()
}

internal fun glossaryDisplayParityCss(): String {
    return """
        .yomitan-glossary [data-sc-div][data-sc字義],
        .yomitan-glossary [data-sc-div][data-sc-字義] {
            font-size: 14px !important;
            line-height: 1.4;
        }
        [data-sc筆順], [data-sc-筆順] {
            display: block;
            position: static;
            overflow: visible;
            line-height: normal;
        }
        [data-sc筆順] > [data-sc-title2],
        [data-sc-筆順] > [data-sc-title2],
        [data-sc筆順] > [data-sc-タイトル2],
        [data-sc-筆順] > [data-sc-タイトル2] {
            display: block;
            position: static;
            margin: 0 0 0.35em 0;
            line-height: 1.2;
        }
        [data-sc筆順] > .gloss-sc-table-container,
        [data-sc-筆順] > .gloss-sc-table-container,
        [data-sc筆順] > .nine-brushorder-scroll,
        [data-sc-筆順] > .nine-brushorder-scroll {
            display: block;
            position: static;
            margin-top: 0.35em;
        }
        [data-sc筆順] table, [data-sc-筆順] table {
            border-collapse: collapse;
            border-spacing: 0;
            margin: 0;
            border-top: 0.5px solid #444 !important;
            border-left: 0.5px solid #444 !important;
        }
        [data-sc筆順] td, [data-sc-筆順] td {
            border: 0.5px solid #444 !important;
            padding: 0.25em;
        }
    """.trimIndent()
}

private fun resolveDictionaryAttr(dictionaryName: String): String {
    return dictionaryName.ifBlank { "__default__" }
}

private fun scopeCssRecursive(css: String, prefix: String): String {
    val parts = StringBuilder()
    var i = 0
    while (i < css.length) {
        while (i < css.length && css[i].isWhitespace()) {
            parts.append(css[i])
            i += 1
        }
        if (i >= css.length) break

        if (i + 1 < css.length && css[i] == '/' && css[i + 1] == '*') {
            val end = css.indexOf("*/", i + 2)
            if (end == -1) break
            parts.append(css.substring(i, end + 2))
            i = end + 2
            continue
        }

        val bracePos = css.indexOf('{', i)
        if (bracePos == -1) {
            parts.append(css.substring(i))
            break
        }

        val selectorPart = css.substring(i, bracePos)
        val selectorTrimmed = selectorPart.trim()
        val isAtRule = selectorTrimmed.startsWith("@")

        val scopedSelector = if (isAtRule) {
            selectorPart
        } else {
            selectorPart.split(",").joinToString(", ") { raw ->
                val s = raw.trim()
                when {
                    s.isBlank() -> ""
                    s.startsWith("&") -> s
                    else -> "$prefix $s"
                }
            }
        }

        parts.append(scopedSelector).append(" {")

        i = bracePos + 1
        var depth = 1
        val blockStart = i
        while (i < css.length && depth > 0) {
            when (css[i]) {
                '{' -> depth += 1
                '}' -> depth -= 1
            }
            i += 1
        }
        val blockContent = if (depth == 0) {
            css.substring(blockStart, i - 1)
        } else {
            css.substring(blockStart)
        }

        if (isAtRule) {
            val shouldScopeNested = selectorTrimmed.startsWith("@media") ||
                selectorTrimmed.startsWith("@supports") ||
                selectorTrimmed.startsWith("@document") ||
                selectorTrimmed.startsWith("@layer")
            parts.append(
                if (shouldScopeNested) scopeCssRecursive(blockContent, prefix) else blockContent
            )
        } else if (blockContent.contains('{')) {
            val parsed = splitPropertiesAndNestedRules(blockContent)
            parts.append(parsed.first)
            if (parsed.second.isNotBlank()) {
                parts.append(scopeCssRecursive(parsed.second, prefix))
            }
        } else {
            parts.append(blockContent)
        }

        parts.append("}")
    }

    return parts.toString()
}

private fun splitPropertiesAndNestedRules(blockContent: String): Pair<String, String> {
    val properties = StringBuilder()
    val nested = StringBuilder()
    var pos = 0
    while (pos < blockContent.length) {
        while (pos < blockContent.length && blockContent[pos].isWhitespace()) {
            pos += 1
        }
        if (pos >= blockContent.length) break

        val nextSemi = blockContent.indexOf(';', pos)
        val nextBrace = blockContent.indexOf('{', pos)
        if (nextBrace != -1 && (nextSemi == -1 || nextBrace < nextSemi)) {
            var nestedDepth = 1
            var nestedEnd = nextBrace + 1
            while (nestedEnd < blockContent.length && nestedDepth > 0) {
                when (blockContent[nestedEnd]) {
                    '{' -> nestedDepth += 1
                    '}' -> nestedDepth -= 1
                }
                nestedEnd += 1
            }
            nested.append(blockContent.substring(pos, nestedEnd))
            pos = nestedEnd
        } else if (nextSemi != -1) {
            properties.append(blockContent.substring(pos, nextSemi + 1))
            pos = nextSemi + 1
        } else {
            properties.append(blockContent.substring(pos))
            break
        }
    }
    return properties.toString() to nested.toString()
}

private fun escapeHtmlTextShared(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

private fun escapeHtmlAttributeShared(value: String): String {
    return escapeHtmlTextShared(value).replace("\"", "&quot;")
}

private fun escapeCssStringShared(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
}
