package io.github.mhammaddawudi.freeport.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Applies the language chosen in Settings, rather than the phone's.
 *
 * The app ships in two languages and the choice belongs to the user: someone
 * on an English phone may well want the Kurdish interface, and the reverse.
 * Every entry point — the activity, the application, the VPN service — wraps
 * its base context through here so notifications read the same as the screen.
 */
object Locales {

    fun wrap(context: Context): Context = wrap(context, SettingsRepository.savedLanguage(context))

    fun wrap(context: Context, language: AppLanguage): Context {
        val locale = Locale.forLanguageTag(language.tag)
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }
}
