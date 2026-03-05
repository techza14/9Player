package com.example.tset

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class PersistedDictionaryRef(
    val uri: String,
    val name: String,
    val cacheKey: String? = null
)

internal data class PersistedMecabDictionaryRef(
    val uri: String,
    val name: String,
    val cacheKey: String
)

internal data class PersistedImports(
    val audioUri: String?,
    val audioName: String?,
    val srtUri: String?,
    val srtName: String?,
    val dictionaries: List<PersistedDictionaryRef>,
    val mecabDictionary: PersistedMecabDictionaryRef? = null
)

private const val PREFS_NAME = "reader_sync_imports"
private const val KEY_STATE_JSON = "state_json"

internal fun loadPersistedImports(context: Context): PersistedImports {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_STATE_JSON, null) ?: return PersistedImports(
        audioUri = null,
        audioName = null,
        srtUri = null,
        srtName = null,
        dictionaries = emptyList(),
        mecabDictionary = null
    )

    val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return PersistedImports(
        audioUri = null,
        audioName = null,
        srtUri = null,
        srtName = null,
        dictionaries = emptyList(),
        mecabDictionary = null
    )

    val dictionaries = mutableListOf<PersistedDictionaryRef>()
    val array = obj.optJSONArray("dictionaries") ?: JSONArray()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val uri = item.optString("uri").trim()
        if (uri.isBlank()) continue
        val name = item.optString("name").trim()
        val cacheKey = item.optString("cacheKey").trim().ifBlank { null }
        dictionaries += PersistedDictionaryRef(uri = uri, name = name, cacheKey = cacheKey)
    }

    val mecabDictionary = obj.optJSONObject("mecabDictionary")?.let { item ->
        val uri = item.optString("uri").trim()
        val name = item.optString("name").trim()
        val cacheKey = item.optString("cacheKey").trim()
        if (uri.isBlank() || cacheKey.isBlank()) {
            null
        } else {
            PersistedMecabDictionaryRef(
                uri = uri,
                name = name.ifBlank { "MeCab Dictionary" },
                cacheKey = cacheKey
            )
        }
    }

    return PersistedImports(
        audioUri = obj.optString("audioUri").trim().ifBlank { null },
        audioName = obj.optString("audioName").trim().ifBlank { null },
        srtUri = obj.optString("srtUri").trim().ifBlank { null },
        srtName = obj.optString("srtName").trim().ifBlank { null },
        dictionaries = dictionaries,
        mecabDictionary = mecabDictionary
    )
}

internal fun savePersistedImports(context: Context, state: PersistedImports) {
    val obj = JSONObject().apply {
        put("audioUri", state.audioUri ?: "")
        put("audioName", state.audioName ?: "")
        put("srtUri", state.srtUri ?: "")
        put("srtName", state.srtName ?: "")
        put(
            "dictionaries",
            JSONArray().apply {
                state.dictionaries.forEach { ref ->
                    put(JSONObject().apply {
                        put("uri", ref.uri)
                        put("name", ref.name)
                        put("cacheKey", ref.cacheKey ?: "")
                    })
                }
            }
        )
        put(
            "mecabDictionary",
            state.mecabDictionary?.let { ref ->
                JSONObject().apply {
                    put("uri", ref.uri)
                    put("name", ref.name)
                    put("cacheKey", ref.cacheKey)
                }
            } ?: JSONObject.NULL
        )
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_STATE_JSON, obj.toString())
        .apply()
}
