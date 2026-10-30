package app.halo.launcher.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import app.halo.launcher.R
import java.util.Locale

/**
 * Halo's interface language: "system", "en" or "de".
 *
 * Android 13+: per-app language via [LocaleManager]. The system persists it and delivers it as a
 * configuration change; MainActivity recreates itself when the locale changes, so every screen
 * (and everything remembered in composition) picks up the new language at once.
 * Android 9–12: the choice is kept in SharedPreferences "halo_lang", applied in
 * [wrap] (MainActivity.attachBaseContext) and the activity is recreated.
 *
 * Code that runs outside an activity (ViewModel, assistant, text-to-speech, weather) uses
 * [localized] / [string] so it speaks the selected language on every Android version, and
 * [effective] / [locale] to know which language the interface is actually shown in.
 */
object Lang {
    private const val PREFS = "halo_lang"
    private const val KEY = "code"
    val CODES = listOf("system", "en", "de")

    fun set(activity: Activity, code: String) {
        val c = code.takeIf { it in CODES } ?: "system"
        cache = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val lm = activity.getSystemService(LocaleManager::class.java) ?: return
            // "system" clears the per-app override: Halo follows the TV's language again.
            val wanted = if (c == "system") LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(c)
            if (lm.applicationLocales != wanted) lm.applicationLocales = wanted
        } else {
            val prefs = activity.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if ((prefs.getString(KEY, "system") ?: "system") == c) return
            prefs.edit().putString(KEY, c).apply()
            if (c == "system") Locale.setDefault(systemLocale())
            activity.recreate()
        }
    }

    /** The language chosen for Halo ("system" when none was chosen). */
    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val l = runCatching { context.getSystemService(LocaleManager::class.java)?.applicationLocales }.getOrNull()
            if (l == null || l.isEmpty) "system" else l[0].language.takeIf { it in CODES } ?: "system"
        } else {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "system") ?: "system"
        }

    /** For attachBaseContext: applies the stored language on Android 9–12; a no-op on 13+. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val code = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "system") ?: "system"
        if (code == "system" || code !in CODES) return base
        val locale = Locale.forLanguageTag(code)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    @Volatile private var cache: Triple<Context, String, Context>? = null

    /**
     * [context] with Halo's language applied (for non-UI code that holds the application context,
     * which isn't wrapped on Android 9–12). Returns [context] itself when it already matches.
     */
    fun localized(context: Context): Context {
        val code = current(context)
        if (code == "system") return context
        val want = Locale.forLanguageTag(code)
        if (context.resources.configuration.locales.get(0)?.language == want.language) return context
        cache?.let { (base, c, ctx) -> if (base === context && c == code) return ctx }
        val config = Configuration(context.resources.configuration)
        config.setLocale(want)
        config.setLocales(LocaleList(want))
        return context.createConfigurationContext(config).also { cache = Triple(context, code, it) }
    }

    /** A string resource in Halo's language. */
    fun string(context: Context, @StringRes id: Int, vararg args: Any): String =
        localized(context).resources.getString(id, *args)

    /**
     * The language the interface is really shown in: "de" or "en". For "system" this is what
     * Android resolved from the TV's language list (German if it prefers German, else English).
     */
    fun effective(context: Context): String =
        runCatching { localized(context).resources.getString(R.string.lang_code) }.getOrNull()
            ?.takeIf { it == "de" || it == "en" } ?: "en"

    fun isGerman(context: Context): Boolean = effective(context) == "de"

    /** Locale for dates, times and number formats: the configured one when it speaks the UI language. */
    fun locale(context: Context): Locale =
        localeFor(effective(context), localized(context).resources.configuration.locales.get(0))

    /** [configured] when it speaks [language] (keeps de-AT, en-GB …), else the plain language. */
    fun localeFor(language: String, configured: Locale?): Locale =
        if (configured != null && configured.language == language) configured
        else if (language == "de") Locale.GERMANY else Locale.US

    /** BCP-47 tag for speech (Gemini TTS, speech recognition): "de-DE" or "en-US". */
    fun speechTag(context: Context): String = if (isGerman(context)) "de-DE" else "en-US"

    private fun systemLocale(): Locale =
        android.content.res.Resources.getSystem().configuration.locales.get(0) ?: Locale.getDefault()
}
