package moe.tekuza.m9player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class MdxMountedEntry(
    val treeUri: String,
    val mdxUri: String,
    val displayName: String,
    val cacheKey: String,
    val relativeDir: String = "",
    val enabled: Boolean = true
)

internal data class MdxMountState(
    val enabled: Boolean = false,
    val entries: List<MdxMountedEntry> = emptyList()
)

private const val MDX_MOUNT_PREFS = "mdx_mount_prefs"
private const val KEY_ENABLED = "enabled"
private const val KEY_ENTRIES_JSON = "entries_json"

internal fun loadMdxMountState(context: Context): MdxMountState {
    val prefs = context.getSharedPreferences(MDX_MOUNT_PREFS, Context.MODE_PRIVATE)
    val enabled = prefs.getBoolean(KEY_ENABLED, false)
    val fromJson = parseEntriesJson(prefs.getString(KEY_ENTRIES_JSON, null))
    return MdxMountState(enabled = enabled, entries = dedupeMountedEntries(fromJson))
}

internal fun saveMdxMountState(context: Context, state: MdxMountState) {
    val normalized = state.copy(entries = dedupeMountedEntries(state.entries))
    val json = JSONArray().apply {
        normalized.entries.forEach { entry ->
            put(
                JSONObject().apply {
                    put("tree_uri", entry.treeUri)
                    put("mdx_uri", entry.mdxUri)
                    put("display_name", entry.displayName)
                    put("cache_key", entry.cacheKey)
                    put("relative_dir", entry.relativeDir)
                    put("enabled", entry.enabled)
                }
            )
        }
    }

    context.getSharedPreferences(MDX_MOUNT_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_ENABLED, normalized.enabled)
        .putString(KEY_ENTRIES_JSON, json.toString())
        .apply()
    invalidateDictionaryLookupCaches()
    prebuildMountedMdxIndexesAsync(context.applicationContext, normalized)
}

private fun parseEntriesJson(raw: String?): List<MdxMountedEntry> {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(text)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val treeUri = item.optString("tree_uri").trim()
                val mdxUri = item.optString("mdx_uri").trim()
                val displayName = item.optString("display_name").trim()
                val cacheKey = item.optString("cache_key").trim()
                val relativeDir = item.optString("relative_dir").trim().trim('/')
                if (treeUri.isBlank() || mdxUri.isBlank() || cacheKey.isBlank()) continue
                add(
                    MdxMountedEntry(
                        treeUri = treeUri,
                        mdxUri = mdxUri,
                        displayName = displayName.ifBlank { "mounted.mdx" },
                        cacheKey = cacheKey,
                        relativeDir = relativeDir,
                        enabled = item.optBoolean("enabled", true)
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

private fun dedupeMountedEntries(entries: List<MdxMountedEntry>): List<MdxMountedEntry> {
    val map = LinkedHashMap<String, MdxMountedEntry>()
    entries.forEach { entry ->
        val cacheKey = entry.cacheKey.trim()
        val treeUri = entry.treeUri.trim()
        val mdxUri = entry.mdxUri.trim()
        if (cacheKey.isBlank() || treeUri.isBlank() || mdxUri.isBlank()) return@forEach
        map[cacheKey] = entry.copy(
            cacheKey = cacheKey,
            treeUri = treeUri,
            mdxUri = mdxUri,
            displayName = entry.displayName.trim().ifBlank { "mounted.mdx" },
            relativeDir = entry.relativeDir.trim().trim('/')
        )
    }
    return map.values.toList()
}
