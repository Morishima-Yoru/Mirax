package me.trinitrix.mirax

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import me.trinitrix.mirax.session.AppLanguage
import java.util.Locale

/**
 * Host-side adapters that turn Android environment values into session reports.
 * The session never reads these APIs; the activity feeds typed reports instead.
 */
object HostEnvironment {
    /**
     * Whether the primary system locale is Traditional Chinese
     * (not the app-overridden configuration).
     */
    fun isSystemTraditionalChinese(): Boolean {
        val locales = Resources.getSystem().configuration.locales
        if (locales.isEmpty) {
            return false
        }
        return isTraditionalChinese(locales[0])
    }

    /**
     * Apply the session-resolved language. Follow-system is already resolved before
     * calling this, so Simplified Chinese systems receive English.
     */
    fun applyAppLanguage(language: AppLanguage) {
        val tag = when (language) {
            AppLanguage.TRADITIONAL_CHINESE -> "zh-TW"
            AppLanguage.ENGLISH -> "en"
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    /**
     * Read the phone's current device name. Does not write Settings.
     */
    fun readDeviceName(context: Context): String {
        val fromSettings = Settings.Global.getString(
            context.contentResolver,
            Settings.Global.DEVICE_NAME,
        )
        if (!fromSettings.isNullOrBlank()) {
            return fromSettings
        }
        return Build.MODEL
    }

    private fun isTraditionalChinese(locale: Locale): Boolean {
        if (!locale.language.equals("zh", ignoreCase = true)) {
            return false
        }
        val script = locale.script
        if (script.equals("Hant", ignoreCase = true)) {
            return true
        }
        if (script.equals("Hans", ignoreCase = true)) {
            return false
        }
        return when (locale.country.uppercase(Locale.ROOT)) {
            "TW", "HK", "MO" -> true
            else -> false
        }
    }
}
