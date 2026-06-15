package moe.tekuza.m9player

import android.content.Context

private const val EBOOK_FEATURE_PREFS = "ebook_feature_prefs"
private const val KEY_EBOOK_ENABLED = "ebook_enabled"
private const val KEY_EBOOK_DEFAULT_TO_READER = "ebook_default_to_reader"
private const val KEY_EBOOK_ONLY_IMPORT_ENABLED = "ebook_only_import_enabled"
private const val KEY_EBOOK_IMAGE_SPOILER_ENABLED = "ebook_image_spoiler_enabled"

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

internal fun loadEbookDefaultToReader(context: Context): Boolean {
    return context
        .getSharedPreferences(EBOOK_FEATURE_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_EBOOK_DEFAULT_TO_READER, false)
}

internal fun saveEbookDefaultToReader(context: Context, enabled: Boolean) {
    context
        .getSharedPreferences(EBOOK_FEATURE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_EBOOK_DEFAULT_TO_READER, enabled)
        .apply()
}

internal fun loadEbookOnlyImportEnabled(context: Context): Boolean {
    return context
        .getSharedPreferences(EBOOK_FEATURE_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_EBOOK_ONLY_IMPORT_ENABLED, false)
}

internal fun saveEbookOnlyImportEnabled(context: Context, enabled: Boolean) {
    context
        .getSharedPreferences(EBOOK_FEATURE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_EBOOK_ONLY_IMPORT_ENABLED, enabled)
        .apply()
}

internal fun loadEbookImageSpoilerEnabled(context: Context): Boolean {
    return context
        .getSharedPreferences(EBOOK_FEATURE_PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_EBOOK_IMAGE_SPOILER_ENABLED, false)
}

internal fun saveEbookImageSpoilerEnabled(context: Context, enabled: Boolean) {
    context
        .getSharedPreferences(EBOOK_FEATURE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_EBOOK_IMAGE_SPOILER_ENABLED, enabled)
        .apply()
}
