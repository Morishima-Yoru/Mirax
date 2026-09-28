package me.trinitrix.mirax

import android.content.Context
import me.trinitrix.mirax.session.LanguagePreference
import me.trinitrix.mirax.session.SessionSettings

/**
 * Persists session settings across process death. Defaults match a fresh install.
 */
object SessionPreferences {
    private const val PREFS = "mirax_session"
    private const val KEY_ADVERTISING = "advertising_enabled"
    private const val KEY_LANGUAGE = "language_preference"
    private const val KEY_DISPLAY_NAME_OVERRIDE = "display_name_override"
    private const val KEY_HAS_DISPLAY_NAME_OVERRIDE = "has_display_name_override"

    fun load(context: Context): SessionSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val language = when (prefs.getString(KEY_LANGUAGE, LanguagePreference.FOLLOW_SYSTEM.name)) {
            LanguagePreference.TRADITIONAL_CHINESE.name -> LanguagePreference.TRADITIONAL_CHINESE
            LanguagePreference.ENGLISH.name -> LanguagePreference.ENGLISH
            else -> LanguagePreference.FOLLOW_SYSTEM
        }
        val override = if (prefs.getBoolean(KEY_HAS_DISPLAY_NAME_OVERRIDE, false)) {
            prefs.getString(KEY_DISPLAY_NAME_OVERRIDE, null)
        } else {
            null
        }
        return SessionSettings(
            advertisingEnabled = prefs.getBoolean(KEY_ADVERTISING, false),
            languagePreference = language,
            displayNameOverride = override,
        )
    }

    fun saveAdvertising(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ADVERTISING, enabled)
            .apply()
    }

    fun saveLanguagePreference(context: Context, preference: LanguagePreference) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, preference.name)
            .apply()
    }

    fun saveDisplayNameOverride(context: Context, override: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (override == null) {
                    putBoolean(KEY_HAS_DISPLAY_NAME_OVERRIDE, false)
                    remove(KEY_DISPLAY_NAME_OVERRIDE)
                } else {
                    putBoolean(KEY_HAS_DISPLAY_NAME_OVERRIDE, true)
                    putString(KEY_DISPLAY_NAME_OVERRIDE, override)
                }
            }
            .apply()
    }
}
