package me.trinitrix.mirax

import android.content.Context
import me.trinitrix.mirax.session.SessionSettings

/**
 * Persists session settings across process death. Defaults match a fresh install.
 */
object SessionPreferences {
    private const val PREFS = "mirax_session"
    private const val KEY_ADVERTISING = "advertising_enabled"

    fun load(context: Context): SessionSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SessionSettings(
            advertisingEnabled = prefs.getBoolean(KEY_ADVERTISING, false),
        )
    }

    fun saveAdvertising(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ADVERTISING, enabled)
            .apply()
    }
}
