package me.trinitrix.mirax.data.standby.theme

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

/**
 * JVM tests for [StandbyLocaleResolver].
 */
class StandbyLocaleResolverTest {

    private fun hant(): Locale {
        return Locale.Builder().setLanguage("zh").setScript("Hant").build()
    }

    private fun hans(): Locale {
        return Locale.Builder().setLanguage("zh").setScript("Hans").build()
    }

    private fun scripted(script: String, region: String): Locale {
        return Locale.Builder().setLanguage("zh").setScript(script).setRegion(region).build()
    }

    @Test
    fun explicitChoiceAlwaysWins() {
        assertThat(
            StandbyLocaleResolver.resolve(StandbyLanguage.ENGLISH, Locale.TRADITIONAL_CHINESE),
        ).isEqualTo(StandbyLanguage.ENGLISH)
        assertThat(
            StandbyLocaleResolver.resolve(StandbyLanguage.TRADITIONAL_CHINESE, Locale.ENGLISH),
        ).isEqualTo(StandbyLanguage.TRADITIONAL_CHINESE)
    }

    @Test
    fun followSystemZhTwIsTraditional() {
        assertThat(
            StandbyLocaleResolver.resolve(null, Locale.TRADITIONAL_CHINESE),
        ).isEqualTo(StandbyLanguage.TRADITIONAL_CHINESE)
    }

    @Test
    fun followSystemHantScriptIsTraditional() {
        assertThat(StandbyLocaleResolver.resolve(null, hant()))
            .isEqualTo(StandbyLanguage.TRADITIONAL_CHINESE)
    }

    @Test
    fun followSystemHansIsEnglish() {
        assertThat(StandbyLocaleResolver.resolve(null, hans()))
            .isEqualTo(StandbyLanguage.ENGLISH)
        assertThat(StandbyLocaleResolver.resolve(null, Locale.SIMPLIFIED_CHINESE))
            .isEqualTo(StandbyLanguage.ENGLISH)
    }

    @Test
    fun followSystemEnUsIsEnglish() {
        assertThat(StandbyLocaleResolver.resolve(null, Locale.US))
            .isEqualTo(StandbyLanguage.ENGLISH)
    }

    @Test
    fun followSystemAbsentIsEnglish() {
        assertThat(StandbyLocaleResolver.resolve(null, null as Locale?))
            .isEqualTo(StandbyLanguage.ENGLISH)
        assertThat(StandbyLocaleResolver.resolve(null, emptyList()))
            .isEqualTo(StandbyLanguage.ENGLISH)
    }

    @Test
    fun onlyPrimaryLocaleCounts() {
        assertThat(
            StandbyLocaleResolver.resolve(null, listOf(Locale.US, Locale.TRADITIONAL_CHINESE)),
        ).isEqualTo(StandbyLanguage.ENGLISH)
        assertThat(
            StandbyLocaleResolver.resolve(
                null,
                listOf(Locale.TRADITIONAL_CHINESE, Locale.US),
            ),
        ).isEqualTo(StandbyLanguage.TRADITIONAL_CHINESE)
    }

    @Test
    fun explicitHansTwIsEnglish() {
        assertThat(StandbyLocaleResolver.resolve(null, scripted("Hans", "TW")))
            .isEqualTo(StandbyLanguage.ENGLISH)
        assertThat(StandbyLocaleResolver.resolve(null, scripted("Hans", "HK")))
            .isEqualTo(StandbyLanguage.ENGLISH)
    }

    @Test
    fun explicitHantCnIsTraditional() {
        assertThat(StandbyLocaleResolver.resolve(null, scripted("Hant", "CN")))
            .isEqualTo(StandbyLanguage.TRADITIONAL_CHINESE)
    }

    @Test
    fun regionWithoutScriptTwHkMoIsTraditional() {
        assertThat(
            StandbyLocaleResolver.resolve(null, Locale.Builder().setLanguage("zh").setRegion("HK").build()),
        ).isEqualTo(StandbyLanguage.TRADITIONAL_CHINESE)
        assertThat(
            StandbyLocaleResolver.resolve(null, Locale.Builder().setLanguage("zh").setRegion("MO").build()),
        ).isEqualTo(StandbyLanguage.TRADITIONAL_CHINESE)
        assertThat(
            StandbyLocaleResolver.resolve(null, Locale.Builder().setLanguage("zh").setRegion("TW").build()),
        ).isEqualTo(StandbyLanguage.TRADITIONAL_CHINESE)
    }

    @Test
    fun regionWithoutScriptCnIsEnglish() {
        assertThat(
            StandbyLocaleResolver.resolve(null, Locale.Builder().setLanguage("zh").setRegion("CN").build()),
        ).isEqualTo(StandbyLanguage.ENGLISH)
    }
}
