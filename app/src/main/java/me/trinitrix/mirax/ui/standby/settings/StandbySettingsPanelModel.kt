package me.trinitrix.mirax.ui.standby.settings

import me.trinitrix.mirax.data.standby.theme.StandbyLanguage
import me.trinitrix.mirax.data.standby.theme.StandbyThemeId

/**
 * Pure selection state for the standby settings panel.
 *
 * Unset input resolves to [StandbyThemeId.MATTER] and follow-system (`null`
 * language). Unknown raw values resolve the same way but keep the original
 * raw string, so the panel never writes anything back unprompted. Selection
 * is exposed as booleans so the view can express it as text, never by color
 * alone.
 *
 * @param theme resolved theme choice for display.
 * @param themeRaw original stored theme value, or `null` when never set.
 * @param language resolved language choice, or `null` to follow the system.
 * @param languageRaw original stored language value, or `null` when never set.
 */
data class StandbySettingsPanelModel(
    val theme: StandbyThemeId = StandbyThemeId.MATTER,
    val themeRaw: String? = null,
    val language: StandbyLanguage? = null,
    val languageRaw: String? = null,
) {
    /** True when a stored theme value names no known identity. */
    val isThemeUnknown: Boolean
        get() = themeRaw != null && StandbyThemeId.fromStorage(themeRaw) == null

    /** True when a stored language value names no known choice. */
    val isLanguageUnknown: Boolean
        get() = languageRaw != null && !isKnownLanguageRaw(languageRaw)

    /**
     * Whether [id] is the current theme choice.
     *
     * @param id theme identity to check.
     * @return true when [id] matches the current theme.
     */
    fun isThemeSelected(id: StandbyThemeId): Boolean = id == theme

    /**
     * Whether [language] is the current language choice.
     *
     * @param language choice to check, or `null` for follow-system.
     * @return true when [language] matches the current choice.
     */
    fun isLanguageSelected(language: StandbyLanguage?): Boolean = language == this.language

    /**
     * Returns a copy with [id] selected; the raw storage value follows the identity.
     *
     * @param id theme identity to select.
     */
    fun withTheme(id: StandbyThemeId): StandbySettingsPanelModel =
        copy(theme = id, themeRaw = id.storageValue)

    /**
     * Returns a copy with [language] selected; `null` persists as follow-system.
     *
     * @param language choice to select, or `null` to follow the system.
     */
    fun withLanguage(language: StandbyLanguage?): StandbySettingsPanelModel =
        copy(language = language, languageRaw = language?.name ?: FOLLOW_SYSTEM_RAW)

    companion object {
        /** Stable follow-system marker; the only language raw that means `null`. */
        const val FOLLOW_SYSTEM_RAW = "FOLLOW_SYSTEM"

        /** Stable traversal order for theme options. */
        val THEME_ORDER: List<StandbyThemeId> =
            listOf(StandbyThemeId.LANDSCAPE, StandbyThemeId.MATTER, StandbyThemeId.HARDLINE)

        /** Stable traversal order for language options; `null` is follow-system. */
        val LANGUAGE_ORDER: List<StandbyLanguage?> =
            listOf(null, StandbyLanguage.TRADITIONAL_CHINESE, StandbyLanguage.ENGLISH)

        /**
         * Builds display state from stored raw values without writing anything back.
         *
         * @param themeRaw stored theme identity, or `null` when never set.
         * @param languageRaw stored language name, or `null` to follow the system.
         * @return display state with unknown values fallen back but raws preserved.
         */
        fun fromStorage(themeRaw: String?, languageRaw: String?): StandbySettingsPanelModel {
            return StandbySettingsPanelModel(
                theme = StandbyThemeId.resolveOrDefault(themeRaw),
                themeRaw = themeRaw,
                language = parseLanguage(languageRaw),
                languageRaw = languageRaw,
            )
        }

        /**
         * Parses a stored language name; absent, follow-system, and unknown
         * values all yield `null` (follow-system display).
         *
         * @param raw stored language name, or `null` when never set.
         * @return matching choice, or `null` for follow-system display.
         */
        fun parseLanguage(raw: String?): StandbyLanguage? {
            return StandbyLanguage.entries.firstOrNull { it.name == raw }
        }

        private fun isKnownLanguageRaw(raw: String): Boolean {
            return raw == FOLLOW_SYSTEM_RAW || StandbyLanguage.entries.any { it.name == raw }
        }
    }
}
