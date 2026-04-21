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
            .map(::normalizeStructuredContentLikeHoshi)
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
        val definitions = item.definitions
        if (definitions.size <= 1) {
            val leadingLabel = if (includeDictionaryLabel && dictionaryLabel.isNotBlank()) {
                "<i>($safeLabel)</i> "
            } else {
                ""
            }
            val def = definitions.firstOrNull().orEmpty()
            buildGlossaryListItem(
                dictionaryAttr = safeAttr,
                leadingLabel = leadingLabel,
                definition = def
            )
        } else {
            definitions.mapIndexed { index, def ->
                val leadingLabel = if (includeDictionaryLabel) {
                    if (dictionaryLabel.isNotBlank()) {
                        if (index == 0) "<i>(${index + 1}, $safeLabel)</i> " else "<i>(${index + 1})</i> "
                    } else {
                        "<i>(${index + 1})</i> "
                    }
                } else {
                    ""
                }
                buildGlossaryListItem(
                    dictionaryAttr = safeAttr,
                    leadingLabel = leadingLabel,
                    definition = def
                )
            }.joinToString(separator = "")
        }
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

internal fun normalizeStructuredContentLikeHoshi(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""
    if (!trimmed.contains("data-sc", ignoreCase = true)) return trimmed

    var out = trimmed
    out = normalizeDataScAttributeNames(out)
    out = stripNonDashedDataScAttrs(out)
    out = addGlossClass(out, "span", "gloss-sc-span")
    out = addGlossClass(out, "div", "gloss-sc-div")
    out = addGlossClass(out, "table", "gloss-sc-table")
    out = addGlossClass(out, "tr", "gloss-sc-tr")
    out = addGlossClass(out, "td", "gloss-sc-td")
    out = addGlossClass(out, "th", "gloss-sc-th")
    out = addGlossClass(out, "thead", "gloss-sc-thead")
    out = addGlossClass(out, "tbody", "gloss-sc-tbody")
    out = addGlossClass(out, "tfoot", "gloss-sc-tfoot")

    // Hoshi-like table styling and wrapping.
    out = applyHoshiTableInlineStyles(out)
    out = out.replace(
        Regex("<table\\b([^>]*)>", RegexOption.IGNORE_CASE),
        "<div class=\"gloss-sc-table-container\"><table$1>"
    ).replace(
        Regex("</table>", RegexOption.IGNORE_CASE),
        "</table></div>"
    )

    return if (out.contains("class=\"structured-content\"")) out else "<span class=\"structured-content\">$out</span>"
}

private fun normalizeDataScAttributeNames(html: String): String {
    var out = html
    // 1) Normalize ascii data-sc keys: data-sc-dic_item -> data-sc-dic-item
    out = out.replace(
        Regex("""(\sdata-sc-)([A-Za-z0-9_:-]+)(\s*=\s*"[^"]*")""")
    ) { m ->
        val prefix = m.groupValues[1]
        val key = m.groupValues[2].replace('_', '-')
        val suffix = m.groupValues[3]
        "$prefix$key$suffix"
    }
    // 2) Drop dashed CJK variants (e.g. data-sc-標準), keep canonical data-sc標準.
    out = out.replace(
        Regex("""\sdata-sc-[^\x00-\x7F][^\s=]*\s*=\s*"[^"]*""""),
        ""
    )
    return out
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
        .yomitan-glossary {
            overflow-x: hidden;
        }
        .yomitan-glossary .structured-content,
        .yomitan-glossary [data-sc-body] {
            overflow-x: hidden;
            max-width: 100%;
        }
        .yomitan-glossary .gloss-sc-table-container {
            display: block;
            overflow-x: auto;
            overflow-y: hidden;
            max-width: 100%;
            -webkit-overflow-scrolling: touch;
        }
        .yomitan-glossary .gloss-sc-table {
            width: max-content;
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
    val out = StringBuilder()
    var i = 0
    while (i < css.length) {
        while (i < css.length && css[i].isWhitespace()) {
            out.append(css[i])
            i += 1
        }
        if (i >= css.length) break

        if (i + 1 < css.length && css[i] == '/' && css[i + 1] == '*') {
            val end = css.indexOf("*/", i + 2)
            if (end == -1) break
            out.append(css.substring(i, end + 2))
            i = end + 2
            continue
        }

        val bracePos = css.indexOf('{', i)
        if (bracePos == -1) {
            out.append(css.substring(i))
            break
        }

        val header = css.substring(i, bracePos).trim()
        i = bracePos + 1
        var depth = 1
        val bodyStart = i
        while (i < css.length && depth > 0) {
            when (css[i]) {
                '{' -> depth += 1
                '}' -> depth -= 1
            }
            i += 1
        }
        val body = if (depth == 0) css.substring(bodyStart, i - 1) else css.substring(bodyStart)

        if (header.isBlank()) continue

        if (header.startsWith("@")) {
            val shouldRecurse = header.startsWith("@media") ||
                header.startsWith("@supports") ||
                header.startsWith("@document") ||
                header.startsWith("@layer")
            val inner = if (shouldRecurse) scopeCssRecursive(body, prefix) else body
            out.append(header).append(" {").append(inner).append("}")
            continue
        }

        val scopedSelector = header
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(", ") { s ->
                if (s.startsWith("&")) s else "$prefix $s"
            }
        if (scopedSelector.isBlank()) continue
        val (properties, nestedRules) = splitPropertiesAndNestedRules(body)
        out.append(scopedSelector).append(" {").append(properties).append("}")
        if (nestedRules.isNotBlank()) {
            out.append(scopeNestedRulesWithinSelector(nestedRules, scopedSelector))
        }
    }
    return out.toString()
}

private fun splitPropertiesAndNestedRules(blockContent: String): Pair<String, String> {
    val properties = StringBuilder()
    val nested = StringBuilder()
    var pos = 0
    while (pos < blockContent.length) {
        while (pos < blockContent.length && blockContent[pos].isWhitespace()) {
            properties.append(blockContent[pos])
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

private fun scopeNestedRulesWithinSelector(nestedRules: String, parentSelector: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < nestedRules.length) {
        while (i < nestedRules.length && nestedRules[i].isWhitespace()) {
            out.append(nestedRules[i])
            i += 1
        }
        if (i >= nestedRules.length) break

        if (i + 1 < nestedRules.length && nestedRules[i] == '/' && nestedRules[i + 1] == '*') {
            val end = nestedRules.indexOf("*/", i + 2)
            if (end == -1) break
            out.append(nestedRules.substring(i, end + 2))
            i = end + 2
            continue
        }

        val bracePos = nestedRules.indexOf('{', i)
        if (bracePos == -1) {
            out.append(nestedRules.substring(i))
            break
        }

        val header = nestedRules.substring(i, bracePos).trim()
        i = bracePos + 1
        var depth = 1
        val bodyStart = i
        while (i < nestedRules.length && depth > 0) {
            when (nestedRules[i]) {
                '{' -> depth += 1
                '}' -> depth -= 1
            }
            i += 1
        }
        val body = if (depth == 0) nestedRules.substring(bodyStart, i - 1) else nestedRules.substring(bodyStart)
        if (header.isBlank()) continue

        if (header.startsWith("@")) {
            val shouldRecurse = header.startsWith("@media") ||
                header.startsWith("@supports") ||
                header.startsWith("@document") ||
                header.startsWith("@layer")
            val inner = if (shouldRecurse) scopeCssRecursive(body, parentSelector) else body
            out.append(header).append(" {").append(inner).append("}")
            continue
        }

        val scopedNestedSelector = header
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(", ") { s ->
                if (s.startsWith("&")) {
                    s.replaceFirst("&", parentSelector)
                } else {
                    "$parentSelector $s"
                }
            }
        out.append(scopedNestedSelector).append(" {").append(body).append("}")
    }
    return out.toString()
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

private fun addGlossClass(html: String, tag: String, token: String): String {
    val openTagRegex = Regex("<$tag\\b([^>]*)>", RegexOption.IGNORE_CASE)
    return html.replace(openTagRegex) { match ->
        val attrs = match.groupValues[1]
        // Match only a real `class="..."` attribute, never `data-sc-class="..."`.
        val classRegex = Regex("(^|\\s)class\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        if (classRegex.containsMatchIn(attrs)) {
            val updatedAttrs = classRegex.replace(attrs) { classMatch ->
                val leadingSpace = classMatch.groupValues[1]
                val existing = classMatch.groupValues[2]
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .toMutableList()
                if (!existing.contains(token)) {
                    existing.add(token)
                }
                "${leadingSpace}class=\"${existing.joinToString(" ")}\""
            }
            "<$tag$updatedAttrs>"
        } else {
            "<$tag class=\"$token\"$attrs>"
        }
    }
}

private fun stripNonDashedDataScAttrs(html: String): String {
    // Remove attributes like data-scbody/data-scclass/data-schtml, keep canonical dashed form.
    val nonDashed = Regex("\\sdata-sc(?!-)[A-Za-z0-9_:.]+\\s*=\\s*\"[^\"]*\"", RegexOption.IGNORE_CASE)
    return html.replace(nonDashed, "")
}

private fun applyHoshiTableInlineStyles(html: String): String {
    val tableStyle = "table-layout:auto;border-collapse:collapse;"
    val cellStyle = "border-style:solid;padding:0.25em;vertical-align:top;border-width:1px;border-color:currentColor;"
    val thStyle = "font-weight:bold;$cellStyle"
    return html
        .replace(Regex("<table(?=[>\\s])", RegexOption.IGNORE_CASE), "<table style=\"$tableStyle\"")
        .replace(Regex("<th(?=[>\\s])", RegexOption.IGNORE_CASE), "<th style=\"$thStyle\"")
        .replace(Regex("<td(?=[>\\s])", RegexOption.IGNORE_CASE), "<td style=\"$cellStyle\"")
}

private fun buildGlossaryListItem(
    dictionaryAttr: String,
    leadingLabel: String,
    definition: String
): String {
    val wrappedContent = if (definition.trimStart().startsWith("<li", ignoreCase = true)) {
        "<ol>$definition</ol>"
    } else {
        "<span>$definition</span>"
    }
    return """
        <li data-dictionary="$dictionaryAttr">
            $leadingLabel$wrappedContent
        </li>
    """.trimIndent()
}
