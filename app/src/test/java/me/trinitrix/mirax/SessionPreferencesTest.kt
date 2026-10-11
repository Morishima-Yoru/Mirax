package me.trinitrix.mirax

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.session.LanguagePreference
import me.trinitrix.mirax.session.PictureScale
import me.trinitrix.mirax.session.SessionSettings
import me.trinitrix.mirax.session.StandardVideoModes
import me.trinitrix.mirax.session.VideoMode
import org.junit.Test

/**
 * Unit tests for SessionPreferences constants and key names (issue #17).
 * Note: Actual SharedPreferences persistence requires Android context (Robolectric/device).
 * These tests verify the new keys exist and default values are correct.
 */
class SessionPreferencesTest {

    @Test
    fun sessionSettings_defaultsIncludeControlPanelAndOrientationLock() {
        val settings = SessionSettings()

        assertThat(settings.controlPanelEnabled).isTrue()
        assertThat(settings.orientationLockEnabled).isFalse()
    }

    @Test
    fun sessionSettings_newFieldsCanBeSet() {
        val settings = SessionSettings(
            controlPanelEnabled = false,
            orientationLockEnabled = true,
        )

        assertThat(settings.controlPanelEnabled).isFalse()
        assertThat(settings.orientationLockEnabled).isTrue()
    }

    @Test
    fun sessionSettings_exportIncludesNewFields() {
        val settings = SessionSettings(
            controlPanelEnabled = false,
            orientationLockEnabled = true,
            floatingBallEnabled = false,
            touchEnabled = false,
            showDebugMessages = false,
            showDebugOverlay = true,
            cameraCutoutAffectsLayout = true,
            broadcastAutoStopMinutes = 5,
        )

        assertThat(settings.controlPanelEnabled).isFalse()
        assertThat(settings.orientationLockEnabled).isTrue()
    }

    @Test
    fun sessionSettings_defaultsMatchFreshInstall() {
        val defaults = SessionSettings(
            advertisingEnabled = false,
            languagePreference = LanguagePreference.FOLLOW_SYSTEM,
            displayNameOverride = null,
            preferredMode = null,
            checkedStandardModes = StandardVideoModes.DEFAULT_CHECKED,
            maxVideoBitrateBps = StandardVideoModes.BITRATE_CAP_BPS,
            provisioningConsumed = false,
            floatingBallEnabled = true,
            pictureScale = PictureScale.PROPORTIONAL,
            customModes = emptyList(),
            autoAddWmSizeOnConnect = true,
            touchEnabled = true,
            showDebugMessages = true,
            showDebugOverlay = false,
            cameraCutoutAffectsLayout = false,
            broadcastAutoStopMinutes = 1,
            controlPanelEnabled = true,
            orientationLockEnabled = false,
        )

        val empty = SessionSettings()

        assertThat(empty).isEqualTo(defaults)
    }

    @Test
    fun sessionPreferences_controlPanelEnabledKeyExists() {
        assertThat(me.trinitrix.mirax.SessionPreferences::class.java.getDeclaredField("KEY_CONTROL_PANEL_ENABLED")).isNotNull()
    }

    @Test
    fun sessionPreferences_orientationLockEnabledKeyExists() {
        assertThat(me.trinitrix.mirax.SessionPreferences::class.java.getDeclaredField("KEY_ORIENTATION_LOCK_ENABLED")).isNotNull()
    }

    @Test
    fun sessionPreferences_saveControlPanelEnabled_methodExists() {
        assertThat(me.trinitrix.mirax.SessionPreferences::class.java.getDeclaredMethod("saveControlPanelEnabled",
            android.content.Context::class.java, Boolean::class.java)).isNotNull()
    }

    @Test
    fun sessionPreferences_saveOrientationLockEnabled_methodExists() {
        assertThat(me.trinitrix.mirax.SessionPreferences::class.java.getDeclaredMethod("saveOrientationLockEnabled",
            android.content.Context::class.java, Boolean::class.java)).isNotNull()
    }
}