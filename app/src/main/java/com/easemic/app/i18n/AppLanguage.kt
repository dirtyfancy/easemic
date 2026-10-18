package com.easemic.app.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.easemic.app.ui.PreferenceMap
import java.util.Locale

/**
 * #E124: Supported display languages matching Waifu Deck (`expo-app`):
 * English (`en`), Simplified Chinese (`zh-Hans`), Traditional Chinese (`zh-Hant`),
 * and Japanese (`ja`).
 */
enum class AppLanguage(val tag: String, val nativeName: String) {
    EN("en", "English"),
    ZH_HANS("zh-Hans", "简体中文"),
    ZH_HANT("zh-Hant", "繁體中文"),
    JA("ja", "日本語"),
    ;

    companion object {
        fun fromTagOrNull(tag: String?): AppLanguage? =
            entries.firstOrNull { it.tag.equals(tag?.trim(), ignoreCase = true) }
    }
}

/**
 * #E124: User language preference (`system` or an explicit [AppLanguage]).
 * Defaults to [ENGLISH] so existing JVM and instrumentation tests see
 * deterministic English copy unless switched, while offering `Follow system`,
 * `English`, `简体中文`, `繁體中文`, and `日本語` in Settings.
 */
enum class LanguagePreference(val storageValue: String) {
    SYSTEM("system"),
    ENGLISH("en"),
    SIMPLIFIED_CHINESE("zh-Hans"),
    TRADITIONAL_CHINESE("zh-Hant"),
    JAPANESE("ja"),
    ;

    companion object {
        fun fromStorage(raw: String?): LanguagePreference =
            entries.firstOrNull { it.storageValue.equals(raw?.trim(), ignoreCase = true) }
                ?: SYSTEM
    }
}

object AppLocaleResolver {
    private val TRADITIONAL_CHINESE_REGIONS = setOf("HK", "MO", "TW")

    fun languageForLocale(
        languageCode: String?,
        scriptCode: String? = null,
        regionCode: String? = null,
    ): AppLanguage {
        val lang = languageCode?.trim()?.lowercase(Locale.US).orEmpty()
        if (lang == "ja") return AppLanguage.JA
        if (lang != "zh") return AppLanguage.EN
        val script = scriptCode?.trim().orEmpty()
        if (script.equals("Hant", ignoreCase = true)) return AppLanguage.ZH_HANT
        if (script.equals("Hans", ignoreCase = true)) return AppLanguage.ZH_HANS
        val region = regionCode?.trim()?.uppercase(Locale.US).orEmpty()
        return if (region in TRADITIONAL_CHINESE_REGIONS) {
            AppLanguage.ZH_HANT
        } else {
            AppLanguage.ZH_HANS
        }
    }

    fun languageForJavaLocale(locale: Locale): AppLanguage =
        languageForLocale(
            languageCode = locale.language,
            scriptCode = locale.script,
            regionCode = locale.country,
        )

    fun resolveLanguage(
        preference: LanguagePreference,
        systemLocales: List<Locale> = listOf(Locale.getDefault()),
    ): AppLanguage = when (preference) {
        LanguagePreference.SYSTEM -> systemLocales.firstOrNull()?.let(::languageForJavaLocale) ?: AppLanguage.EN
        LanguagePreference.ENGLISH -> AppLanguage.EN
        LanguagePreference.SIMPLIFIED_CHINESE -> AppLanguage.ZH_HANS
        LanguagePreference.TRADITIONAL_CHINESE -> AppLanguage.ZH_HANT
        LanguagePreference.JAPANESE -> AppLanguage.JA
    }
}

class AppLanguageStore(private val prefs: PreferenceMap) {
    fun readPreference(): LanguagePreference {
        val raw = prefs.getString(KEY_LANGUAGE_PREF, null)
        return if (raw == null) {
            LanguagePreference.SYSTEM
        } else {
            LanguagePreference.fromStorage(raw)
        }
    }

    fun hasExplicitPreference(): Boolean = prefs.getString(KEY_LANGUAGE_PREF, null) != null

    fun writePreference(preference: LanguagePreference) {
        prefs.putString(KEY_LANGUAGE_PREF, preference.storageValue)
    }

    companion object {
        const val KEY_LANGUAGE_PREF = "app_language_preference_v1"
    }
}

/**
 * Process-wide reactive holder for the active [AppLanguage].
 * Defaults to [AppLanguage.EN] so existing unit and Compose tests that do not
 * mount the language store remain 100% deterministic in English, while
 * `PrivateComicLibraryApp` syncs the user's chosen or system language on launch
 * and whenever the user changes it in Settings.
 */
object AppI18n {
    var currentLanguage: AppLanguage by mutableStateOf(AppLanguage.EN)
    var currentPreference: LanguagePreference by mutableStateOf(LanguagePreference.SYSTEM)

    fun applyPreference(
        preference: LanguagePreference,
        systemLocales: List<Locale> = listOf(Locale.getDefault()),
    ): AppLanguage {
        currentPreference = preference
        val resolved = AppLocaleResolver.resolveLanguage(preference, systemLocales)
        currentLanguage = resolved
        return resolved
    }

    /** Applies the stored choice, or follows the system when the user never chose one (a fresh install). */
    fun applyStoredPreference(
        store: AppLanguageStore,
        systemLocales: List<Locale> = listOf(Locale.getDefault()),
    ): LanguagePreference {
        val preference = store.readPreference()
        applyPreference(preference, systemLocales)
        return preference
    }

    fun resetToEnglishForTests() {
        currentPreference = LanguagePreference.ENGLISH
        currentLanguage = AppLanguage.EN
    }
}

val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.EN }

/**
 * Translates an English UI string into [language] when a translation entry exists,
 * or returns [en] unchanged for English or untranslated strings.
 */
fun tr(
    en: String,
    zhHans: String,
    zhHant: String,
    ja: String,
    language: AppLanguage = AppI18n.currentLanguage,
): String = when (language) {
    AppLanguage.EN -> en
    AppLanguage.ZH_HANS -> zhHans
    AppLanguage.ZH_HANT -> zhHant
    AppLanguage.JA -> ja
}

@Composable
@ReadOnlyComposable
fun trCompose(
    en: String,
    zhHans: String,
    zhHant: String,
    ja: String,
): String = tr(
    en = en,
    zhHans = zhHans,
    zhHant = zhHant,
    ja = ja,
    language = AppI18n.currentLanguage,
)
