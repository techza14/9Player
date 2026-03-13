package moe.tekuza.m9player

import android.content.Context
import org.json.JSONObject

internal data class PersistedAnkiConfig(
    val deckName: String,
    val modelName: String,
    val tags: String,
    val fieldTemplates: Map<String, String>
)

private const val ANKI_PREFS_NAME = "anki_export_config"
private const val ANKI_KEY_STATE = "anki_state_json"

internal fun loadPersistedAnkiConfig(context: Context): PersistedAnkiConfig {
    val prefs = context.getSharedPreferences(ANKI_PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(ANKI_KEY_STATE, null) ?: return PersistedAnkiConfig(
        deckName = "Default",
        modelName = "",
        tags = "",
        fieldTemplates = emptyMap()
    )

    val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return PersistedAnkiConfig(
        deckName = "Default",
        modelName = "",
        tags = "",
        fieldTemplates = emptyMap()
    )

    val fieldsObj = obj.optJSONObject("fieldTemplates") ?: JSONObject()
    val templates = mutableMapOf<String, String>()
    val keys = fieldsObj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = fieldsObj.optString(key).trim()
        if (key.isNotBlank()) templates[key] = value
    }

    return PersistedAnkiConfig(
        deckName = obj.optString("deckName").trim().ifBlank { "Default" },
        modelName = obj.optString("modelName").trim(),
        tags = obj.optString("tags").trim(),
        fieldTemplates = templates
    )
}

internal fun savePersistedAnkiConfig(context: Context, config: PersistedAnkiConfig) {
    val obj = JSONObject().apply {
        put("deckName", config.deckName)
        put("modelName", config.modelName)
        put("tags", config.tags)
        put(
            "fieldTemplates",
            JSONObject().apply {
                config.fieldTemplates.forEach { (field, template) ->
                    put(field, template)
                }
            }
        )
    }
    context.getSharedPreferences(ANKI_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(ANKI_KEY_STATE, obj.toString())
        .apply()
}

