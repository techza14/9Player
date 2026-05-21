package moe.tekuza.m9player

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.tekuza.m9player.hoshi.features.dictionary.DictionarySettings
import moe.tekuza.m9player.hoshi.features.dictionary.cleanupCollapsedDictionaries
import moe.tekuza.m9player.hoshi.features.dictionary.loadDictionarySettings
import moe.tekuza.m9player.hoshi.features.dictionary.saveDictionarySettings
import moe.tekuza.m9player.ui.theme.TsetTheme

class DictionarySettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TsetTheme {
                DictionarySettingsScreen(onBack = { finish() })
            }
        }
    }
}

private enum class DictionarySettingsDestination {
    Home,
    CustomCollapsedDictionaries,
    CustomCss,
}

@Composable
private fun DictionarySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dictionaryController = rememberDictionaryManagementController(
        context = context,
        contentResolver = context.contentResolver,
        scope = scope
    )

    val dictionaryRefs = dictionaryController.dictionaryRefs
    val dictionaryLoading = dictionaryController.dictionaryLoading
    val dictionaryProgressText = dictionaryController.dictionaryProgressText
    val dictionaryProgressValue = dictionaryController.dictionaryProgressValue
    val dictionaryError = dictionaryController.dictionaryError
    val dictionaryOrderIds = dictionaryController.dictionaryOrderIds
    val mdxMountState = dictionaryController.mdxMountState

    var showDictionaryManager by remember { mutableStateOf(true) }
    var showDictionaryDeleteActions by remember { mutableStateOf(false) }
    var uiConfig by remember { mutableStateOf(loadDictionaryUiConfig(context)) }
    var dictionarySettings by remember { mutableStateOf(loadDictionarySettings(context)) }
    var destination by remember { mutableStateOf(DictionarySettingsDestination.Home) }

    fun persistDictionarySettings(next: DictionarySettings) {
        val normalized = next.normalized()
        dictionarySettings = normalized
        saveDictionarySettings(context, normalized)
    }

    fun updateDictionarySettings(transform: (DictionarySettings) -> DictionarySettings) {
        persistDictionarySettings(transform(dictionarySettings))
    }

    fun persistDictionaryRefs(refs: List<PersistedDictionaryRef>) {
        val persisted = loadPersistedImports(context)
        savePersistedImports(context, persisted.copy(dictionaries = refs))
    }

    fun refreshLookupData() {
        invalidateDictionaryLookupCaches()
        bumpDictionaryDataVersion(context)
    }

    val pickDictionaryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        dictionaryController.importDictionaries(
            uris = uris,
            onPersistDictionaryRefs = ::persistDictionaryRefs,
            onLookupDataChanged = ::refreshLookupData
        )
    }

    LaunchedEffect(Unit) {
        uiConfig = loadDictionaryUiConfig(context)
        dictionarySettings = loadDictionarySettings(context)
        dictionaryController.reloadExternalState()
        dictionaryController.setPersistedDictionaryRefs(loadPersistedImports(context).dictionaries)
    }

    val termDictionaryNames = remember(dictionaryRefs, mdxMountState) {
        termDictionaryNames(
            dictionaryRefs = dictionaryRefs,
            mdxMountState = mdxMountState
        )
    }
    val visibleCollapsedDictionaries = remember(dictionarySettings.collapsedDictionaries, termDictionaryNames) {
        cleanupCollapsedDictionaries(
            collapsedDictionaries = dictionarySettings.collapsedDictionaries,
            availableDictionaryNames = termDictionaryNames
        )
    }

    LaunchedEffect(termDictionaryNames, dictionarySettings.collapsedDictionaries) {
        if (termDictionaryNames.isNotEmpty() && visibleCollapsedDictionaries != dictionarySettings.collapsedDictionaries) {
            persistDictionarySettings(dictionarySettings.copy(collapsedDictionaries = visibleCollapsedDictionaries))
        }
    }

    when (destination) {
        DictionarySettingsDestination.CustomCollapsedDictionaries -> {
            CustomCollapsedDictionariesScreen(
                dictionaryNames = termDictionaryNames,
                collapsedDictionaries = visibleCollapsedDictionaries,
                onExpandAll = {
                    updateDictionarySettings { settings ->
                        settings.copy(collapsedDictionaries = emptySet())
                    }
                },
                onCollapseAll = {
                    updateDictionarySettings { settings ->
                        settings.copy(collapsedDictionaries = termDictionaryNames.toSet())
                    }
                },
                onToggleDictionary = { name ->
                    updateDictionarySettings { settings ->
                        val collapsed = cleanupCollapsedDictionaries(
                            collapsedDictionaries = settings.collapsedDictionaries,
                            availableDictionaryNames = termDictionaryNames
                        )
                        settings.copy(
                            collapsedDictionaries = if (name in collapsed) {
                                collapsed - name
                            } else {
                                collapsed + name
                            }
                        )
                    }
                },
                onBack = { destination = DictionarySettingsDestination.Home }
            )
            return
        }
        DictionarySettingsDestination.CustomCss -> {
            DictionaryCustomCssScreen(
                dictionaryNames = termDictionaryNames,
                settings = dictionarySettings,
                onSettingsChange = ::updateDictionarySettings,
                onBack = { destination = DictionarySettingsDestination.Home }
            )
            return
        }
        DictionarySettingsDestination.Home -> Unit
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_dictionary_title),
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = hoshiCardBackgroundColor())
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.dictionary_home_rich_mode_label),
                        )
                        Switch(
                            checked = uiConfig.showRichHomeDictionary,
                            onCheckedChange = { checked ->
                                uiConfig = uiConfig.copy(showRichHomeDictionary = checked)
                                saveDictionaryUiConfig(context, uiConfig)
                            }
                        )
                    }
                }
            }

            DictionaryDisplaySettingsCard(
                collapsedDictionaryCount = visibleCollapsedDictionaries.size,
                onConfigureCollapsedDictionaries = {
                    destination = DictionarySettingsDestination.CustomCollapsedDictionaries
                },
                onOpenCustomCss = {
                    destination = DictionarySettingsDestination.CustomCss
                }
            )

            DictionaryManagementCard(
                context = context,
                dictionaryCount = dictionaryRefs.size + mdxMountState.entries.count { it.enabled },
                showHeader = false,
                containerColor = hoshiPanelBackgroundColor(),
                itemContainerColor = hoshiCardBackgroundColor(),
                dictionaryLoading = dictionaryLoading,
                dictionaryProgressText = dictionaryProgressText,
                dictionaryProgressValue = dictionaryProgressValue,
                dictionaryError = dictionaryError,
                showDictionaryManager = showDictionaryManager,
                showDictionaryDeleteActions = showDictionaryDeleteActions,
                dictionaryRefs = dictionaryRefs,
                dictionaryOrderIds = dictionaryOrderIds,
                mdxMountState = mdxMountState,
                onImportClick = { pickDictionaryLauncher.launch(arrayOf("application/zip", "*/*")) },
                onShowDictionaryManagerToggle = { showDictionaryManager = !showDictionaryManager },
                onShowDictionaryDeleteActionsToggle = { showDictionaryDeleteActions = !showDictionaryDeleteActions },
                onOpenMdxClick = {
                    context.startActivity(Intent(context, MdxMountSettingsActivity::class.java))
                },
                onMoveCombinedDictionary = { fromIndex, toIndex ->
                    dictionaryController.moveCombinedDictionary(fromIndex, toIndex, ::refreshLookupData)
                },
                onRemoveImportedDictionary = { index ->
                    dictionaryController.removeImportedDictionary(index, ::persistDictionaryRefs, ::refreshLookupData)
                },
                onRemoveMountedDictionary = { cacheKey ->
                    dictionaryController.removeMountedDictionary(cacheKey, ::refreshLookupData)
                },
                onSetImportedDictionaryEnabled = { dictionaryId, enabled ->
                    dictionaryController.setImportedDictionaryEnabled(
                        dictionaryId = dictionaryId,
                        enabled = enabled,
                        onPersistDictionaryRefs = ::persistDictionaryRefs,
                        onLookupDataChanged = ::refreshLookupData
                    )
                },
                onSetMountedDictionaryEnabled = { cacheKey, enabled ->
                    dictionaryController.setMountedDictionaryEnabled(cacheKey, enabled, ::refreshLookupData)
                }
            )
        }
    }
}

@Composable
private fun DictionaryDisplaySettingsCard(
    collapsedDictionaryCount: Int,
    onConfigureCollapsedDictionaries: () -> Unit,
    onOpenCustomCss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = hoshiCardBackgroundColor())
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.dictionary_display_settings),
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedButton(
                onClick = onConfigureCollapsedDictionaries,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(
                        R.string.dictionary_collapse_custom_configure,
                        collapsedDictionaryCount
                    )
                )
            }
            OutlinedButton(
                onClick = onOpenCustomCss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.dictionary_custom_css))
            }
        }
    }
}

@Composable
private fun CustomCollapsedDictionariesScreen(
    dictionaryNames: List<String>,
    collapsedDictionaries: Set<String>,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    onToggleDictionary: (String) -> Unit,
    onBack: () -> Unit,
) {
    SettingsScaffold(
        title = stringResource(R.string.dictionary_collapse_custom_title),
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onExpandAll,
                    modifier = Modifier.weight(1f),
                    enabled = collapsedDictionaries.isNotEmpty()
                ) {
                    Text(stringResource(R.string.dictionary_collapse_expand_all))
                }
                OutlinedButton(
                    onClick = onCollapseAll,
                    modifier = Modifier.weight(1f),
                    enabled = dictionaryNames.isNotEmpty() && collapsedDictionaries.size != dictionaryNames.size
                ) {
                    Text(stringResource(R.string.dictionary_collapse_collapse_all))
                }
            }
            if (dictionaryNames.isEmpty()) {
                Text(
                    text = stringResource(R.string.dictionary_no_term_dictionaries),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                dictionaryNames.forEach { name ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleDictionary(name) },
                        colors = CardDefaults.cardColors(containerColor = hoshiCardBackgroundColor())
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = name,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (name in collapsedDictionaries) {
                                    stringResource(R.string.dictionary_collapsed)
                                } else {
                                    stringResource(R.string.dictionary_expanded)
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DictionaryCustomCssScreen(
    dictionaryNames: List<String>,
    settings: DictionarySettings,
    onSettingsChange: ((DictionarySettings) -> DictionarySettings) -> Unit,
    onBack: () -> Unit,
) {
    var selectorMenuExpanded by remember { mutableStateOf(false) }
    var cssFieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = settings.customCSS,
                selection = TextRange(settings.customCSS.length)
            )
        )
    }

    LaunchedEffect(settings.customCSS) {
        if (settings.customCSS != cssFieldValue.text) {
            cssFieldValue = TextFieldValue(
                text = settings.customCSS,
                selection = TextRange(settings.customCSS.length)
            )
        }
    }

    fun applyCssValue(value: TextFieldValue) {
        cssFieldValue = value
        onSettingsChange { it.copy(customCSS = value.text) }
    }

    SettingsScaffold(
        title = stringResource(R.string.dictionary_custom_css),
        onBack = onBack
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    OutlinedButton(
                        onClick = { selectorMenuExpanded = true },
                        enabled = dictionaryNames.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.dictionary_custom_css_selector))
                    }
                    DropdownMenu(
                        expanded = selectorMenuExpanded,
                        onDismissRequest = { selectorMenuExpanded = false }
                    ) {
                        dictionaryNames.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    selectorMenuExpanded = false
                                    applyCssValue(
                                        insertCustomCssText(
                                            cssFieldValue,
                                            dictionarySelectorCssSnippet(name)
                                        )
                                    )
                                }
                            )
                        }
                    }
                }
                TextButton(
                    onClick = {
                        applyCssValue(TextFieldValue(""))
                    }
                ) {
                    Text(stringResource(R.string.dictionary_custom_css_reset))
                }
            }
            OutlinedTextField(
                value = cssFieldValue,
                onValueChange = ::applyCssValue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp),
                label = { Text(stringResource(R.string.dictionary_custom_css_editor_label)) },
                minLines = 12
            )
        }
    }
}

internal fun insertCustomCssText(
    value: TextFieldValue,
    insertedText: String,
): TextFieldValue {
    val start = value.selection.min.coerceIn(0, value.text.length)
    val end = value.selection.max.coerceIn(start, value.text.length)
    val nextText = buildString {
        append(value.text.substring(0, start))
        append(insertedText)
        append(value.text.substring(end))
    }
    return TextFieldValue(nextText, selection = TextRange(start + insertedText.length))
}

internal fun dictionarySelectorCssSnippet(dictionaryTitle: String): String =
    "[data-dictionary=\"${dictionaryTitle.cssDoubleQuotedContent()}\"] {\n    \n}\n"

private fun String.cssDoubleQuotedContent(): String =
    buildString(length) {
        this@cssDoubleQuotedContent.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\a ")
                '\r' -> Unit
                else -> append(ch)
            }
        }
    }

private fun termDictionaryNames(
    dictionaryRefs: List<PersistedDictionaryRef>,
    mdxMountState: MdxMountState,
): List<String> {
    val imported = dictionaryRefs
        .asSequence()
        .filter { it.dictionaryType.equals("Term", ignoreCase = true) }
        .map { it.name.trim() }
        .filter { it.isNotBlank() }
    val mounted = if (mdxMountState.enabled) {
        mdxMountState.entries
            .asSequence()
            .filter { it.enabled }
            .map { entry ->
                val displayName = entry.displayName.ifBlank { "MDX" }
                displayName.substringBeforeLast('.').ifBlank { displayName }.trim()
            }
            .filter { it.isNotBlank() }
    } else {
        emptySequence()
    }
    return (imported + mounted).distinct().toList()
}
