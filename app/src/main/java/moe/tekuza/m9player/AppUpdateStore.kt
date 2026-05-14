package moe.tekuza.m9player

import android.content.Context

private const val APP_UPDATE_PREFS = "app_update_prefs"
private const val KEY_AUTO_UPDATE_ENABLED = "auto_update_enabled"
private const val KEY_FIRST_PROMPT_SHOWN = "first_prompt_shown"
private const val KEY_LAST_CHECKED_AT = "last_checked_at"

internal data class AppUpdateConfig(
    val autoUpdateEnabled: Boolean = false,
    val firstPromptShown: Boolean = false,
    val lastCheckedAtMs: Long = 0L
)

internal fun loadAppUpdateConfig(context: Context): AppUpdateConfig {
    val prefs = context.getSharedPreferences(APP_UPDATE_PREFS, Context.MODE_PRIVATE)
    return AppUpdateConfig(
        autoUpdateEnabled = prefs.getBoolean(KEY_AUTO_UPDATE_ENABLED, false),
        firstPromptShown = prefs.getBoolean(KEY_FIRST_PROMPT_SHOWN, false),
        lastCheckedAtMs = prefs.getLong(KEY_LAST_CHECKED_AT, 0L)
    )
}

internal fun saveAutoUpdateEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(APP_UPDATE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_AUTO_UPDATE_ENABLED, enabled)
        .apply()
}

internal fun markAutoUpdateFirstPromptShown(context: Context) {
    context.getSharedPreferences(APP_UPDATE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_FIRST_PROMPT_SHOWN, true)
        .apply()
}

internal fun saveAppUpdateCheckedAt(context: Context, checkedAtMs: Long) {
    context.getSharedPreferences(APP_UPDATE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putLong(KEY_LAST_CHECKED_AT, checkedAtMs)
        .apply()
}
