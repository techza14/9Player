package moe.tekuza.m9player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun LookupPopupCardContent(
    groupedResults: List<GroupedLookupResult>,
    loading: Boolean,
    error: String?,
    highlightedDefinitionKey: String?,
    highlightedDefinitionRects: List<Rect>,
    collapsedSections: Map<String, Boolean>,
    actionState: LookupCardActionState,
    contentMaxHeightDp: Int,
    onToggleSection: ((String, Boolean) -> Unit)?,
    onDefinitionLookup: ((String, DefinitionLookupTapData) -> Unit)?,
    onRangeSelection: (() -> Unit)?,
    onPlayAudio: ((GroupedLookupResult) -> Unit)?,
    onAddToAnki: ((GroupedLookupResult) -> Unit)?,
    onCloseAll: (() -> Unit)?,
    onClose: (() -> Unit)?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val duplicateConfig = remember { loadAnkiDuplicateConfig(context) }
    val allowAddWhenDuplicate = duplicateConfig.action.equals("add", ignoreCase = true)
    val ankiDuplicateByKey = remember { mutableStateMapOf<String, Boolean>() }
    val ankiCheckingByKey = remember { mutableStateMapOf<String, Boolean>() }
    val presentation = remember(groupedResults, highlightedDefinitionKey, highlightedDefinitionRects, collapsedSections) {
        buildLookupPresentation(
            ReaderLookupLayer(
                loading = loading,
                error = error,
                groupedResults = groupedResults,
                sourceTerm = null,
                cue = null,
                cueIndex = null,
                anchorOffset = null,
                anchor = null,
                placeBelow = true,
                preferSidePlacement = false,
                selectedRange = null,
                selectionText = null,
                popupSentence = null,
                highlightedDefinitionKey = highlightedDefinitionKey,
                highlightedDefinitionRects = highlightedDefinitionRects,
                highlightedDefinitionNodePathJson = null,
                highlightedDefinitionOffset = null,
                highlightedDefinitionLength = null,
                collapsedSections = collapsedSections,
                autoPlayNonce = 0L,
                autoPlayedKey = null
            )
        )
    }
    val renderPlanKey = remember(presentation) {
        buildString {
            presentation.forEachIndexed { groupIndex, grouped ->
                append(groupIndex)
                append(':')
                append(grouped.groupedResult.term)
                append('|')
                grouped.dictionaries.forEachIndexed { dictIndex, dictionary ->
                    append(dictIndex)
                    append('=')
                    append(dictionary.sectionKey)
                    append('#')
                    append(dictionary.definitions.size)
                    append(';')
                }
                append('\n')
            }
        }
    }
    var renderPhase by remember(renderPlanKey) { mutableIntStateOf(0) }
    LaunchedEffect(renderPlanKey) {
        renderPhase = 0
        delay(80)
        renderPhase = 1
        delay(100)
        renderPhase = 2
    }
    val firstExpandedSectionKey = remember(presentation) {
        presentation
            .asSequence()
            .flatMap { grouped -> grouped.dictionaries.asSequence() }
            .firstOrNull { it.expanded && it.definitions.isNotEmpty() }
            ?.sectionKey
    }

    Column(
        modifier = Modifier.padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!actionState.sourceTerm.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.floating_lookup_from, actionState.sourceTerm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = contentMaxHeightDp.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
            presentation.forEach { groupedPresentation ->
                val groupedResult = groupedPresentation.groupedResult
                val duplicateCheckText = groupedResult.term
                val ankiKey = remember(groupedResult.term, groupedResult.reading) {
                    "${groupedResult.term}|${groupedResult.reading.orEmpty()}"
                }
                val hasDuplicateState = ankiDuplicateByKey.containsKey(ankiKey)
                val duplicateInAnki = ankiDuplicateByKey[ankiKey] == true
                val checkingDuplicate = (ankiCheckingByKey[ankiKey] == true) || !hasDuplicateState
                LaunchedEffect(ankiKey, actionState.showAddToAnki) {
                    if (!actionState.showAddToAnki) return@LaunchedEffect
                    ankiCheckingByKey[ankiKey] = true
                    val duplicated = hasAnkiDuplicateByFirstFieldAsync(
                        context,
                        duplicateCheckText
                    )
                    ankiDuplicateByKey[ankiKey] = duplicated
                    ankiCheckingByKey[ankiKey] = false
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LookupHeadwordWithReading(
                                term = groupedResult.term,
                                reading = groupedResult.reading,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (actionState.showRangeSelection && onRangeSelection != null) {
                                    OutlinedButton(onClick = onRangeSelection) {
                                        Text("▦")
                                    }
                                }
                                if (actionState.showPlayAudio && onPlayAudio != null) {
                                    OutlinedButton(onClick = { onPlayAudio(groupedResult) }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Audiotrack,
                                            contentDescription = stringResource(R.string.common_audio),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                if (actionState.showAddToAnki && onAddToAnki != null) {
                                    OutlinedButton(
                                        onClick = {
                                            onAddToAnki(groupedResult)
                                            scope.launch {
                                                ankiCheckingByKey[ankiKey] = true
                                                delay(450)
                                                val duplicated = withContext(Dispatchers.IO) {
                                                    hasAnkiDuplicateByFirstFieldAsync(
                                                        context,
                                                        duplicateCheckText
                                                    )
                                                }
                                                ankiDuplicateByKey[ankiKey] = duplicated
                                                ankiCheckingByKey[ankiKey] = false
                                            }
                                        },
                                        enabled = (!duplicateInAnki || allowAddWhenDuplicate) && !checkingDuplicate
                                    ) {
                                        Text(
                                            when {
                                                checkingDuplicate -> "…"
                                                duplicateInAnki -> "-"
                                                else -> "+"
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        val frequencyLabel = stringResource(R.string.bookreader_meta_frequency)
                        val pitchLabel = stringResource(R.string.bookreader_meta_pitch)
                        val topFrequencyBadges = groupedResult.dictionaries
                            .asSequence()
                            .map { dictionaryGroup ->
                                parseMetaBadges(dictionaryGroup.frequency, frequencyLabel)
                            }
                            .firstOrNull { it.isNotEmpty() }
                            .orEmpty()
                        if (topFrequencyBadges.isNotEmpty()) {
                            MetaBadgeRow(
                                badges = topFrequencyBadges,
                                labelColor = Color(0xFFDDF0DD),
                                labelTextColor = Color(0xFF305E33)
                            )
                        }
                        val topPitchBadges = groupedResult.dictionaries
                            .asSequence()
                            .map { dictionaryGroup ->
                                parsePitchBadgeGroups(
                                    raw = dictionaryGroup.pitch,
                                    reading = groupedResult.reading,
                                    defaultLabel = pitchLabel
                                )
                            }
                            .firstOrNull { it.isNotEmpty() }
                            .orEmpty()
                        if (topPitchBadges.isNotEmpty()) {
                            topPitchBadges.forEach { group ->
                                PitchBadgeRow(
                                    group = group,
                                    labelColor = Color(0xFFE7DDF8),
                                    labelTextColor = Color(0xFF4E3A74)
                                )
                            }
                        }

                        groupedPresentation.dictionaries.forEach { dictionaryPresentation ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    DictionaryEntryHeader(
                                        dictionaryName = dictionaryPresentation.dictionaryName,
                                        expanded = dictionaryPresentation.expanded,
                                        onToggleExpanded = {
                                            onToggleSection?.invoke(
                                                dictionaryPresentation.sectionKey,
                                                dictionaryPresentation.expanded
                                            )
                                        }
                                    )
                                    if (dictionaryPresentation.expanded) {
                                        val visibleDefinitions = when (renderPhase) {
                                            0 -> {
                                                if (dictionaryPresentation.sectionKey == firstExpandedSectionKey) {
                                                    dictionaryPresentation.definitions.take(1)
                                                } else {
                                                    emptyList()
                                                }
                                            }
                                            1 -> dictionaryPresentation.definitions.take(1)
                                            else -> dictionaryPresentation.definitions
                                        }
                                        if (visibleDefinitions.isNotEmpty()) {
                                            val mergedHtml = remember(visibleDefinitions) {
                                                buildMergedDefinitionHtml(visibleDefinitions)
                                            }
                                            val mergedCss = visibleDefinitions.firstNotNullOfOrNull { it.dictionaryCss }
                                            val mergedHighlightedRects = visibleDefinitions
                                                .firstOrNull { it.highlightedRects.isNotEmpty() }
                                                ?.highlightedRects
                                                .orEmpty()
                                            val definitionKeyForLookup = visibleDefinitions.first().definitionKey

                                            Card(modifier = Modifier.fillMaxWidth()) {
                                                Column(
                                                    modifier = Modifier.padding(8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    RichDefinitionView(
                                                        definition = mergedHtml,
                                                        indexLabel = "",
                                                        dictionaryName = null,
                                                        dictionaryCss = mergedCss,
                                                        highlightedRects = mergedHighlightedRects,
                                                        onLookupTap = if (onDefinitionLookup != null) {
                                                            { tapData ->
                                                                onDefinitionLookup(definitionKeyForLookup, tapData)
                                                            }
                                                        } else null
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (onClose != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.common_close))
                }
                if (actionState.canCloseAll && onCloseAll != null) {
                    TextButton(onClick = onCloseAll) {
                        Text(stringResource(R.string.common_close_all))
                    }
                }
            }
        }
    }
}

private fun buildMergedDefinitionHtml(
    definitions: List<LookupDefinitionPresentation>
): String {
    if (definitions.isEmpty()) return ""
    return buildString {
        definitions.forEachIndexed { index, definition ->
            if (index > 0) {
                append("<hr/>")
            }
            append("<section data-definition-key=\"")
            append(definition.definitionKey)
            append("\">")
            append(definition.definitionHtml)
            append("</section>")
        }
    }
}
