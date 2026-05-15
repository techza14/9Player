package moe.tekuza.m9player

import android.content.Context

private const val EBOOK_FEATURE_PREFS = "ebook_feature_prefs"
private const val KEY_EBOOK_ENABLED = "ebook_enabled"

internal fun loadEbookFeatureEnabled(context: Context): Boolean {
    return context
        .getSharedPreferences(EBOOK_FEATURE_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_EBOOK_ENABLED, false)
}

internal fun saveEbookFeatureEnabled(context: Context, enabled: Boolean) {
    context
        .getSharedPreferences(EBOOK_FEATURE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_EBOOK_ENABLED, enabled)
        .apply()
}
