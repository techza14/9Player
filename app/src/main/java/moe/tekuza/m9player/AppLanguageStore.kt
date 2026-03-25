package moe.tekuza.m9player

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

private const val APP_LANGUAGE_PREFS = "app_language_prefs"
private const val KEY_APP_LANGUAGE = "app_language"

internal enum class AppLanguageOption(
    val value: String,
    val fallbackLabel: String,
    val labelResId: Int? = null
) {
    SYSTEM("", "跟随系统", R.string.settings_language_follow_system),
    ENGLISH("en", "English"),
    SIMPLIFIED_CHINESE("zh-CN", "简体中文"),
    TRADITIONAL_CHINESE("zh-TW", "繁體中文"),
    JAPANESE("ja", "日本語");

    companion object {
        fun fromValue(value: String?): AppLanguageOption {
            return entries.firstOrNull { it.value == value } ?: SYSTEM
        }
    }

    fun displayLabel(context: Context): String {
        return labelResId?.let(context::getString) ?: fallbackLabel
    }
}

internal fun loadAppLanguageOption(context: Context): AppLanguageOption {
    val prefs = context.getSharedPreferences(APP_LANGUAGE_PREFS, Context.MODE_PRIVATE)
    return AppLanguageOption.fromValue(prefs.getString(KEY_APP_LANGUAGE, AppLanguageOption.SYSTEM.value))
}

internal fun saveAppLanguageOption(context: Context, option: AppLanguageOption) {
    context.getSharedPreferences(APP_LANGUAGE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_APP_LANGUAGE, option.value)
        .apply()
}

internal fun applyAppLanguage(option: AppLanguageOption) {
    AppCompatDelegate.setApplicationLocales(
        if (option == AppLanguageOption.SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(option.value)
        }
    )
}

internal fun applySavedAppLanguage(context: Context) {
    applyAppLanguage(loadAppLanguageOption(context))
}
