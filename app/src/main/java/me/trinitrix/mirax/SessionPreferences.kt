package me.trinitrix.mirax

import android.content.Context
import me.trinitrix.mirax.session.LanguagePreference
import me.trinitrix.mirax.session.SessionSettings
import me.trinitrix.mirax.session.StandardVideoModes
import me.trinitrix.mirax.session.VideoMode

/**
 * Persists session settings across process death. Defaults match a fresh install.
 */
object SessionPreferences {
    private const val PREFS = "mirax_session"
    private const val KEY_ADVERTISING = "advertising_enabled"
    private const val KEY_LANGUAGE = "language_preference"
    private const val KEY_DISPLAY_NAME_OVERRIDE = "display_name_override"
    private const val KEY_HAS_DISPLAY_NAME_OVERRIDE = "has_display_name_override"
    private const val KEY_PREFERRED_MODE = "preferred_mode"
    private const val KEY_CHECKED_STANDARD_MODES = "checked_standard_modes"
    private const val KEY_PROVISIONING_CONSUMED = "provisioning_consumed"
    private const val KEY_MAX_VIDEO_BITRATE_BPS = "max_video_bitrate_bps"
    private const val KEY_BOTTOM_HANDLE_ENABLED = "bottom_handle_enabled"

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
        val preferred = prefs.getString(KEY_PREFERRED_MODE, null)?.let { decodeMode(it) }
        val checked = prefs.getStringSet(KEY_CHECKED_STANDARD_MODES, null)
            ?.mapNotNull { decodeMode(it) }
            ?.toSet()
            ?: StandardVideoModes.DEFAULT_CHECKED
        return SessionSettings(
            advertisingEnabled = prefs.getBoolean(KEY_ADVERTISING, false),
            languagePreference = language,
            displayNameOverride = override,
            preferredMode = preferred,
            checkedStandardModes = checked,
            maxVideoBitrateBps = prefs.getLong(
                KEY_MAX_VIDEO_BITRATE_BPS,
                StandardVideoModes.BITRATE_CAP_BPS,
            ),
            provisioningConsumed = prefs.getBoolean(KEY_PROVISIONING_CONSUMED, preferred != null),
            bottomHandleEnabled = prefs.getBoolean(KEY_BOTTOM_HANDLE_ENABLED, true),
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

    /**
     * Persist preferred mode, checklist, bitrate cap, provisioning flag, and
     * bottom-handle setting.
     */
    fun saveResolutionSettings(context: Context, settings: SessionSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (settings.preferredMode == null) {
                    remove(KEY_PREFERRED_MODE)
                } else {
                    putString(KEY_PREFERRED_MODE, encodeMode(settings.preferredMode))
                }
                putStringSet(
                    KEY_CHECKED_STANDARD_MODES,
                    settings.checkedStandardModes.map { encodeMode(it) }.toSet(),
                )
                putBoolean(KEY_PROVISIONING_CONSUMED, settings.provisioningConsumed)
                putLong(KEY_MAX_VIDEO_BITRATE_BPS, settings.maxVideoBitrateBps)
                putBoolean(KEY_BOTTOM_HANDLE_ENABLED, settings.bottomHandleEnabled)
            }
            .apply()
    }

    fun saveBottomHandleEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BOTTOM_HANDLE_ENABLED, enabled)
            .apply()
    }

    private fun encodeMode(mode: VideoMode): String =
        "${mode.width}x${mode.height}@${mode.refreshHz}"

    private fun decodeMode(raw: String): VideoMode? {
        val match = Regex("""^(\d+)x(\d+)@(\d+)$""").matchEntire(raw) ?: return null
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull() ?: return null
        val refresh = match.groupValues[3].toIntOrNull() ?: return null
        if (width <= 0 || height <= 0 || refresh <= 0) {
            return null
        }
        return VideoMode(width, height, refresh)
    }
}
