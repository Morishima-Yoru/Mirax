package me.trinitrix.mirax.ui.standby.settings

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.data.standby.theme.StandbyLanguage
import me.trinitrix.mirax.data.standby.theme.StandbyThemeId
import org.junit.Test

/**
 * JVM tests for [StandbySettingsPanelModel].
 */
class StandbySettingsPanelModelTest {

    @Test
    fun defaultIsMatterAndFollowSystem() {
        val model = StandbySettingsPanelModel()
        assertThat(model.theme).isEqualTo(StandbyThemeId.MATTER)
        assertThat(model.language).isNull()
        assertThat(model.isThemeUnknown).isFalse()
        assertThat(model.isLanguageUnknown).isFalse()
    }

    @Test
    fun unsetStorageShowsMatterAndFollowSystem() {
        val model = StandbySettingsPanelModel.fromStorage(null, null)
        assertThat(model.theme).isEqualTo(StandbyThemeId.MATTER)
        assertThat(model.language).isNull()
        assertThat(model.isThemeSelected(StandbyThemeId.MATTER)).isTrue()
        assertThat(model.isLanguageSelected(null)).isTrue()
    }

    @Test
    fun storedValuesRoundTrip() {
        val model = StandbySettingsPanelModel.fromStorage("hardline", "ENGLISH")
        assertThat(model.theme).isEqualTo(StandbyThemeId.HARDLINE)
        assertThat(model.language).isEqualTo(StandbyLanguage.ENGLISH)
        assertThat(model.isThemeUnknown).isFalse()
        assertThat(model.isLanguageUnknown).isFalse()
    }

    @Test
    fun unknownThemeFallsBackWithoutOverwriting() {
        val model = StandbySettingsPanelModel.fromStorage("future-theme", null)
        assertThat(model.theme).isEqualTo(StandbyThemeId.MATTER)
        assertThat(model.themeRaw).isEqualTo("future-theme")
        assertThat(model.isThemeUnknown).isTrue()
        // Display keeps Matter selected even though the raw is preserved.
        assertThat(model.isThemeSelected(StandbyThemeId.MATTER)).isTrue()
        assertThat(model.isThemeSelected(StandbyThemeId.HARDLINE)).isFalse()
    }

    @Test
    fun unknownLanguageFallsBackWithoutOverwriting() {
        val model = StandbySettingsPanelModel.fromStorage(null, "FUTURE_LANG")
        assertThat(model.language).isNull()
        assertThat(model.languageRaw).isEqualTo("FUTURE_LANG")
        assertThat(model.isLanguageUnknown).isTrue()
        assertThat(model.isLanguageSelected(null)).isTrue()
    }

    @Test
    fun withThemeUpdatesIdentityAndRaw() {
        val model = StandbySettingsPanelModel().withTheme(StandbyThemeId.LANDSCAPE)
        assertThat(model.theme).isEqualTo(StandbyThemeId.LANDSCAPE)
        assertThat(model.themeRaw).isEqualTo(StandbyThemeId.LANDSCAPE.storageValue)
        assertThat(model.isThemeUnknown).isFalse()
        assertThat(model.isThemeSelected(StandbyThemeId.LANDSCAPE)).isTrue()
        assertThat(model.isThemeSelected(StandbyThemeId.MATTER)).isFalse()
    }

    @Test
    fun withLanguageNullMeansFollowSystem() {
        val model = StandbySettingsPanelModel()
            .withLanguage(StandbyLanguage.ENGLISH)
            .withLanguage(null)
        assertThat(model.language).isNull()
        assertThat(model.languageRaw).isEqualTo(StandbySettingsPanelModel.FOLLOW_SYSTEM_RAW)
        assertThat(model.isLanguageSelected(null)).isTrue()
        assertThat(model.isLanguageSelected(StandbyLanguage.ENGLISH)).isFalse()
    }

    @Test
    fun languageOptionsCoverAllThreeChoices() {
        val model = StandbySettingsPanelModel.fromStorage(null, StandbyLanguage.TRADITIONAL_CHINESE.name)
        assertThat(model.isLanguageSelected(StandbyLanguage.TRADITIONAL_CHINESE)).isTrue()
        assertThat(model.isLanguageSelected(StandbyLanguage.ENGLISH)).isFalse()
        assertThat(model.isLanguageSelected(null)).isFalse()
    }

    @Test
    fun traversalOrderIsStable() {
        assertThat(StandbySettingsPanelModel.THEME_ORDER).containsExactly(
            StandbyThemeId.LANDSCAPE,
            StandbyThemeId.MATTER,
            StandbyThemeId.HARDLINE,
            StandbyThemeId.LEGACY,
        ).inOrder()
        assertThat(StandbySettingsPanelModel.LANGUAGE_ORDER).containsExactly(
            null,
            StandbyLanguage.TRADITIONAL_CHINESE,
            StandbyLanguage.ENGLISH,
        ).inOrder()
    }

    @Test
    fun identifiersAreStorageValuesNeverDisplayStrings() {
        assertThat(StandbyThemeId.LANDSCAPE.storageValue).isEqualTo("landscape")
        assertThat(StandbyThemeId.MATTER.storageValue).isEqualTo("matter")
        assertThat(StandbyThemeId.HARDLINE.storageValue).isEqualTo("hardline")
    }
}
