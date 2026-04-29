package moe.tekuza.m9player

import android.content.Context

internal data class AnkiDuplicateConfig(
    val enabled: Boolean = true,
    val scope: String = "deck",
    val action: String = "prevent"
)

private const val ANKI_DUP_PREFS = "anki_duplicate_config"
private const val KEY_ENABLED = "enabled"
private const val KEY_SCOPE = "scope"
private const val KEY_ACTION = "action"

internal fun loadAnkiDuplicateConfig(context: Context): AnkiDuplicateConfig {
    val prefs = context.getSharedPreferences(ANKI_DUP_PREFS, Context.MODE_PRIVATE)
    return AnkiDuplicateConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        scope = prefs.getString(KEY_SCOPE, "deck").orEmpty().ifBlank { "deck" },
        action = prefs.getString(KEY_ACTION, "prevent").orEmpty().ifBlank { "prevent" }
    )
}

internal fun saveAnkiDuplicateConfig(context: Context, config: AnkiDuplicateConfig) {
    context.getSharedPreferences(ANKI_DUP_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_ENABLED, config.enabled)
        .putString(KEY_SCOPE, config.scope)
        .putString(KEY_ACTION, config.action)
        .apply()
}
