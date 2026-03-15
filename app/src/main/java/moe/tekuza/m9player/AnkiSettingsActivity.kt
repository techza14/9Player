package moe.tekuza.m9player

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import moe.tekuza.m9player.ui.theme.TsetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AnkiSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TsetTheme {
                AnkiSettingsScreen(onBack = { finish() })
            }
        }
    }
}

private val ANKI_FIELD_VARIABLE_CHOICES = listOf(
    "",
    "{audio}",
    "{cut-audio}",
    "{cloze-body}",
    "{cloze-body-kana}",
    "{cloze-prefix}",
    "{cloze-suffix}",
    "{conjugation}",
    "{dictionary}",
    "{dictionary-alias}",
    "{document-title}",
    "{book-title}",
    "{expression}",
    "{frequencies}",
    "{frequency-harmonic-occurrence}",
    "{frequency-harmonic-rank}",
    "{frequency-average-occurrence}",
    "{frequency-average-rank}",
    "{part-of-speech}",
    "{phonetic-transcriptions}",
    "{pitch-accents}",
    "{pitch-accent-graphs}",
    "{pitch-accent-graphs-jj}",
    "{pitch-accent-positions}",
    "{pitch-accent-categories}",
    "{reading}",
    "{furigana}",
    "{furigana-plain}",
    "{glossary}",
    "{glossary-brief}",
    "{glossary-no-dictionary}",
    "{glossary-plain}",
    "{glossary-plain-no-dictionary}",
    "{glossary-first}",
    "{glossary-first-brief}",
    "{glossary-first-no-dictionary}",
    "{single-frequency-DICT-NAME}",
    "{single-frequency-number-DICT-NAME}",
    "{single-glossary-DICT-NAME}",
    "{single-glossary-DICT-NAME-brief}",
    "{single-glossary-DICT-NAME-no-dictionary}",
    "{popup-selection-text}",
    "{screenshot}",
    "{search-query}",
    "{sentence}",
    "{sentence-furigana}",
    "{sentence-furigana-plain}",
    "{tags}",
    "{url}"
)

private const val ANKI_MODEL_TEMPLATE_SNAPSHOTS_KEY = "anki_model_template_snapshots_json"
private const val ANKI_EXPORT_PREFS_NAME = "anki_export_config"

@Composable
private fun AnkiSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var ankiPermissionGranted by remember { mutableStateOf(hasAnkiReadWritePermission(context)) }
    var ankiDeckName by remember { mutableStateOf("Default") }
    var ankiModelName by remember { mutableStateOf("") }
    var ankiTagsInput by remember { mutableStateOf("") }
    var ankiDecks by remember { mutableStateOf<List<String>>(emptyList()) }
    var ankiModels by remember { mutableStateOf<List<AnkiModelTemplate>>(emptyList()) }
    var ankiModelFields by remember { mutableStateOf<List<String>>(emptyList()) }
    val ankiFieldTemplates = remember { mutableStateMapOf<String, String>() }
    val ankiModelTemplateSnapshots = remember { mutableStateMapOf<String, Map<String, String>>() }
    var ankiLoading by remember { mutableStateOf(false) }
    var ankiError by remember { mutableStateOf<String?>(null) }

    val requestAnkiPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            ankiPermissionGranted = granted || hasAnkiReadWritePermission(context)
        }

    fun persistAnkiConfig() {
        val currentModelName = ankiModelName.trim()
        if (currentModelName.isNotBlank() && ankiModelFields.isNotEmpty()) {
            ankiModelTemplateSnapshots[currentModelName] = ankiFieldTemplates.toMap()
        }
        savePersistedAnkiConfig(
            context = context,
            config = PersistedAnkiConfig(
                deckName = ankiDeckName,
                modelName = ankiModelName,
                tags = ankiTagsInput,
                fieldTemplates = ankiFieldTemplates.toMap()
            )
        )
        saveAnkiModelTemplateSnapshots(context, ankiModelTemplateSnapshots.toMap())
    }

    fun syncTemplatesWithModelFields(
        fields: List<String>,
        modelTemplates: Map<String, String> = emptyMap()
    ) {
        ankiFieldTemplates.clear()
        ankiModelFields = fields
        fields.forEach { field ->
            ankiFieldTemplates[field] = modelTemplates[field].orEmpty()
        }
    }

    fun selectAnkiModel(modelName: String) {
        val nextModelName = modelName.trim()
        val previousModelName = ankiModelName.trim()
        if (previousModelName.isNotBlank()) {
            ankiModelTemplateSnapshots[previousModelName] = ankiFieldTemplates.toMap()
        }
        ankiModelName = nextModelName
        val model = ankiModels.firstOrNull { it.name == nextModelName }
        val restoredTemplates = ankiModelTemplateSnapshots[nextModelName].orEmpty()
        syncTemplatesWithModelFields(
            fields = model?.fields ?: emptyList(),
            modelTemplates = restoredTemplates
        )
        persistAnkiConfig()
    }

    fun refreshAnkiCatalog() {
        if (!isAnkiInstalled(context)) {
            ankiError = "未安装 AnkiDroid。"
            ankiDecks = emptyList()
            ankiModels = emptyList()
            ankiModelFields = emptyList()
            return
        }

        if (!ankiPermissionGranted) {
            ankiError = "请先授权 Anki 访问。"
            return
        }

        scope.launch {
            ankiLoading = true
            ankiError = null
            val result = withContext(Dispatchers.IO) {
                runCatching { loadAnkiCatalog(context) }
            }
            ankiLoading = false

            result.onSuccess { catalog ->
                ankiDecks = catalog.decks
                ankiModels = catalog.models
                if (ankiDeckName.isBlank()) {
                    ankiDeckName = catalog.decks.firstOrNull() ?: "默认"
                }
                val resolvedModelName = when {
                    ankiModelName.isNotBlank() && catalog.models.any { it.name == ankiModelName } -> ankiModelName
                    catalog.models.isNotEmpty() -> catalog.models.first().name
                    else -> ""
                }
                selectAnkiModel(resolvedModelName)
            }.onFailure { error ->
                ankiError = error.message ?: "加载 Anki 牌组/模板失败"
            }
        }
    }

    fun clearCurrentFieldTemplates() {
        ankiModelFields.forEach { field ->
            ankiFieldTemplates[field] = ""
        }
        persistAnkiConfig()
    }

    LaunchedEffect(Unit) {
        val persistedAnki = loadPersistedAnkiConfig(context)
        ankiDeckName = persistedAnki.deckName
        ankiModelName = persistedAnki.modelName
        ankiTagsInput = persistedAnki.tags
        ankiModelTemplateSnapshots.clear()
        loadAnkiModelTemplateSnapshots(context).forEach { (modelName, templates) ->
            ankiModelTemplateSnapshots[modelName] = templates
        }
        if (persistedAnki.modelName.isNotBlank()) {
            ankiModelTemplateSnapshots[persistedAnki.modelName] = persistedAnki.fieldTemplates
        }
        ankiFieldTemplates.clear()
        persistedAnki.fieldTemplates.forEach { (field, template) ->
            ankiFieldTemplates[field] = template
        }
        if (ankiPermissionGranted) {
            refreshAnkiCatalog()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("< 返回")
            }
            Text("Anki", style = MaterialTheme.typography.titleLarge)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    if (ankiPermissionGranted) {
                        "Anki 访问：已授权"
                    } else {
                        "Anki 访问：未授权"
                    }
                )
                OutlinedButton(
                    onClick = {
                        if (!isAnkiInstalled(context)) {
                            ankiError = "未安装 AnkiDroid。"
                        } else if (!ankiPermissionGranted) {
                            requestAnkiPermissionLauncher.launch(ANKI_READ_WRITE_PERMISSION)
                        }
                    }
                ) {
                    Text(if (ankiPermissionGranted) "Anki 已授权" else "授权 Anki 访问")
                }

                OutlinedButton(
                    onClick = { refreshAnkiCatalog() },
                    enabled = ankiPermissionGranted && !ankiLoading
                ) {
                    Text(if (ankiLoading) "加载中..." else "刷新牌组/模板")
                }
                if (ankiError != null) {
                    Text("Anki 错误：$ankiError", color = MaterialTheme.colorScheme.error)
                }

                AnkiListSelector(
                    label = "牌组",
                    value = ankiDeckName,
                    options = ankiDecks,
                    placeholder = "请选择牌组",
                    onValueChange = { selectedDeck ->
                        ankiDeckName = selectedDeck
                        persistAnkiConfig()
                    }
                )

                AnkiListSelector(
                    label = "笔记类型/模板",
                    value = ankiModelName,
                    options = ankiModels.map { it.name },
                    placeholder = "请选择模板",
                    onValueChange = { selectedModel ->
                        selectAnkiModel(selectedModel)
                    }
                )

                OutlinedTextField(
                    value = ankiTagsInput,
                    onValueChange = {
                        ankiTagsInput = it
                        persistAnkiConfig()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("标签（空格/逗号分隔）") },
                    singleLine = true
                )

                if (ankiModelFields.isNotEmpty()) {
                    Text("字段变量", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(
                        onClick = { clearCurrentFieldTemplates() }
                    ) {
                        Text("清空字段变量")
                    }
                    Text(
                        "{audio} 用于导出查词发音，{cut-audio} 用于导出剪切音频。"
                    )
                    ankiModelFields.forEach { field ->
                        val selectedValue = ankiFieldTemplates[field].orEmpty()
                        val options = ANKI_FIELD_VARIABLE_CHOICES.distinct()
                        AnkiFieldVariableInput(
                            fieldName = field,
                            value = selectedValue,
                            options = options,
                            onValueChange = { value ->
                                ankiFieldTemplates[field] = value
                                val currentModelName = ankiModelName.trim()
                                if (currentModelName.isNotBlank()) {
                                    ankiModelTemplateSnapshots[currentModelName] = ankiFieldTemplates.toMap()
                                }
                                persistAnkiConfig()
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun loadAnkiModelTemplateSnapshots(context: Context): Map<String, Map<String, String>> {
    val raw = context.getSharedPreferences(ANKI_EXPORT_PREFS_NAME, Context.MODE_PRIVATE)
        .getString(ANKI_MODEL_TEMPLATE_SNAPSHOTS_KEY, null)
        ?: return emptyMap()
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
    val snapshots = linkedMapOf<String, Map<String, String>>()
    val modelKeys = root.keys()
    while (modelKeys.hasNext()) {
        val modelName = modelKeys.next().trim()
        if (modelName.isBlank()) continue
        val fieldsObj = root.optJSONObject(modelName) ?: continue
        val fields = linkedMapOf<String, String>()
        val fieldKeys = fieldsObj.keys()
        while (fieldKeys.hasNext()) {
            val fieldName = fieldKeys.next().trim()
            if (fieldName.isBlank()) continue
            fields[fieldName] = fieldsObj.optString(fieldName).trim()
        }
        snapshots[modelName] = fields
    }
    return snapshots
}

private fun saveAnkiModelTemplateSnapshots(
    context: Context,
    snapshots: Map<String, Map<String, String>>
) {
    val root = JSONObject()
    snapshots.forEach { (modelName, fields) ->
        if (modelName.isBlank()) return@forEach
        val fieldsObj = JSONObject()
        fields.forEach { (fieldName, template) ->
            if (fieldName.isBlank()) return@forEach
            fieldsObj.put(fieldName, template)
        }
        root.put(modelName, fieldsObj)
    }
    context.getSharedPreferences(ANKI_EXPORT_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(ANKI_MODEL_TEMPLATE_SNAPSHOTS_KEY, root.toString())
        .apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnkiListSelector(
    label: String,
    value: String,
    options: List<String>,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    val distinctOptions = remember(options) {
        options
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }
    var expanded by remember(label) { mutableStateOf(false) }

    if (distinctOptions.isEmpty()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(label) },
            singleLine = true
        )
        return
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(type = MenuAnchorType.PrimaryNotEditable),
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            distinctOptions.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice) },
                    onClick = {
                        expanded = false
                        onValueChange(choice)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnkiFieldVariableInput(
    fieldName: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember(fieldName) { mutableStateOf(false) }
    val filteredOptions = remember(options, value) {
        val distinctOptions = options.distinct()
        val query = value.trim()
        if (query.isBlank()) {
            distinctOptions
        } else {
            val startsWithMatches = distinctOptions.filter { it.startsWith(query, ignoreCase = true) }
            val containsMatches = distinctOptions.filter {
                !it.startsWith(query, ignoreCase = true) && it.contains(query, ignoreCase = true)
            }
            startsWithMatches + containsMatches
        }
    }

    ExposedDropdownMenuBox(
        expanded = expanded && filteredOptions.isNotEmpty(),
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { next ->
                onValueChange(next)
                expanded = true
            },
            readOnly = false,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(type = MenuAnchorType.PrimaryEditable),
            label = { Text(fieldName) },
            placeholder = { Text("可输入占位符或从列表选择") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded && filteredOptions.isNotEmpty(),
            onDismissRequest = { expanded = false }
        ) {
            filteredOptions.forEach { choice ->
                val text = choice.ifBlank { "(empty)" }
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onValueChange(choice)
                    }
                )
            }
        }
    }
}



