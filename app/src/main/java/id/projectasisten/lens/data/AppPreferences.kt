package id.projectasisten.lens.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

data class LanguageOption(
    val code: String,
    val displayName: String
)

class AppPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isShortcutEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHORTCUT_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SHORTCUT_ENABLED, value).apply()

    var defaultTargetLanguage: String
        get() = prefs.getString(KEY_DEFAULT_TARGET_LANG, DEFAULT_LANGUAGE_CODE) ?: DEFAULT_LANGUAGE_CODE
        set(value) = prefs.edit().putString(KEY_DEFAULT_TARGET_LANG, value).apply()

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) {
            prefs.edit().putInt(KEY_THEME_MODE, value).apply()
            AppCompatDelegate.setDefaultNightMode(value)
        }

    fun applySavedThemeMode() {
        AppCompatDelegate.setDefaultNightMode(themeMode)
    }

    companion object {
        private const val PREFS_NAME = "project_lens_prefs"
        private const val KEY_SHORTCUT_ENABLED = "shortcut_enabled"
        private const val KEY_DEFAULT_TARGET_LANG = "default_target_lang"
        private const val KEY_THEME_MODE = "theme_mode"

        const val DEFAULT_LANGUAGE_CODE = "id"

        val SUPPORTED_TARGET_LANGUAGES: List<LanguageOption> = listOf(
            LanguageOption("id", "Bahasa Indonesia"),
            LanguageOption("en", "English (Inggris)"),
            LanguageOption("ja", "日本語 (Jepang)"),
            LanguageOption("ko", "한국어 (Korea)"),
            LanguageOption("zh", "中文 (Mandarin)"),
            LanguageOption("ar", "العربية (Arab)"),
            LanguageOption("de", "Deutsch (Jerman)"),
            LanguageOption("es", "Español (Spanyol)"),
            LanguageOption("fr", "Français (Prancis)")
        )

        fun getDisplayNameForCode(code: String): String {
            return SUPPORTED_TARGET_LANGUAGES.firstOrNull { it.code == code }?.displayName
                ?: code.uppercase()
        }
    }
}
