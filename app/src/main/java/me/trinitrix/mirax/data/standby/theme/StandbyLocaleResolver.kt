package me.trinitrix.mirax.data.standby.theme

import android.util.Log
import java.util.Locale

/**
 * Resolved standby UI language.
 */
enum class StandbyLanguage {
    TRADITIONAL_CHINESE,
    ENGLISH,
}

/**
 * Pure resolver for the standby UI language.
 *
 * An explicit stored choice always wins. Follow-system (explicit `null`)
 * checks only the primary system locale: an explicit `Hant` script yields
 * [StandbyLanguage.TRADITIONAL_CHINESE], an explicit `Hans` script yields
 * [StandbyLanguage.ENGLISH], and a locale without a script mirrors
 * `HostEnvironment` by mapping `TW`, `HK`, and `MO` regions to
 * [StandbyLanguage.TRADITIONAL_CHINESE]. Every other locale, including
 * `zh-Hans-TW` and an absent system locale, yields
 * [StandbyLanguage.ENGLISH].
 */
object StandbyLocaleResolver {
    private const val TAG = "StandbyLocale"
    /**
     * Resolves the UI language from an explicit choice and the primary system locale.
     *
     * @param explicit stored language choice, or `null` to follow the system.
     * @param systemPrimary primary system locale, or `null` when unknown.
     * @return explicit choice when present, else the follow-system mapping.
     */
    fun resolve(explicit: StandbyLanguage?, systemPrimary: Locale?): StandbyLanguage {
        if (explicit != null) {
            return explicit
        }
        if (systemPrimary == null) {
            return StandbyLanguage.ENGLISH
        }
        if (!systemPrimary.language.equals("zh", ignoreCase = true)) {
            return StandbyLanguage.ENGLISH
        }
        val script = try {
            systemPrimary.script
        } catch (e: RuntimeException) {
            Log.w(TAG, "Standby locale script unreadable; falling back to country")
            ""
        }
        if (script.equals("Hant", ignoreCase = true)) {
            return StandbyLanguage.TRADITIONAL_CHINESE
        }
        if (script.equals("Hans", ignoreCase = true)) {
            return StandbyLanguage.ENGLISH
        }
        // No explicit script: mirror HostEnvironment for TW/HK/MO.
        return when (systemPrimary.country.uppercase(Locale.ROOT)) {
            "TW", "HK", "MO" -> StandbyLanguage.TRADITIONAL_CHINESE
            else -> StandbyLanguage.ENGLISH
        }
    }

    /**
     * Resolves using an ordered system locale list; only the primary (first) entry counts.
     *
     * @param explicit stored language choice, or `null` to follow the system.
     * @param systemLocales ordered system locales, primary first.
     * @return same result as [resolve] with the list head.
     */
    fun resolve(explicit: StandbyLanguage?, systemLocales: List<Locale>): StandbyLanguage {
        return resolve(explicit, systemLocales.firstOrNull())
    }
}
