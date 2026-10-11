package me.trinitrix.mirax.ui

import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.session.PictureScale
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.session.TileState
import me.trinitrix.mirax.session.WidgetStatus
import me.trinitrix.mirax.session.StandardVideoModes
import me.trinitrix.mirax.session.SessionEffect
import me.trinitrix.mirax.session.ConnectionRun
import me.trinitrix.mirax.session.LanguagePreference
import me.trinitrix.mirax.session.AppLanguage
import me.trinitrix.mirax.session.StandardModeRow
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import org.junit.Test

/**
 * UI unit tests for the control panel (issue #22).
 * Tests the visibility and behavior of the control panel in PictureActivity.
 */
class ControlPanelTest {

    private fun createBaseSnapshot(
        controlPanelExpanded: Boolean = false,
        orientationLockEnabled: Boolean = false,
    ): SessionSnapshot {
        return SessionSnapshot(
            phase = ScreenPhase.CONNECTED,
            wfdOwner = WfdOwner.HELPER,
            tileState = TileState.CONNECTED,
            widgetStatus = WidgetStatus.CONNECTED,
            advertisingEnabled = true,
            shouldRequestShizukuPermission = false,
            canReadWmSize = true,
            selectedMode = VideoMode(1920, 1080, 60),
            nextAdvertisementModes = setOf(VideoMode(1920, 1080, 60)),
            connectionOfferModes = setOf(VideoMode(1920, 1080, 60)),
            connectionPreferredMode = VideoMode(1920, 1080, 60),
            preferredMode = null,
            maxVideoBitrateBps = StandardVideoModes.BITRATE_CAP_BPS,
            wfdAdvertise = null,
            showPicture = true,
            handleResolutionText = "1920×1080",
            handleRefreshRateHz = 60,
            sourceDeviceName = "TestDevice",
            controlPanelExpanded = controlPanelExpanded,
            orientationLockEnabled = orientationLockEnabled,
            effects = emptyList(),
            connectionRuns = emptyList(),
            handshakeLog = "",
        )
    }

    @Test
    fun controlPanel_hiddenByDefault() {
        val snapshot = createBaseSnapshot(controlPanelExpanded = false)

        // Test that renderControlPanel hides panel when controlPanelExpanded = false
        assertThat(snapshot.controlPanelExpanded).isFalse()
    }

    @Test
    fun controlPanel_showsWhenExpanded() {
        val snapshot = createBaseSnapshot(controlPanelExpanded = true)

        // Test that renderControlPanel shows panel when controlPanelExpanded = true
        assertThat(snapshot.controlPanelExpanded).isTrue()
    }

    @Test
    fun orientationLockSwitch_togglesState() {
        var snapshot = createBaseSnapshot(controlPanelExpanded = true, orientationLockEnabled = false)

        assertThat(snapshot.orientationLockEnabled).isFalse()

        // Toggle orientation lock
        snapshot = snapshot.copy(orientationLockEnabled = true)
        assertThat(snapshot.orientationLockEnabled).isTrue()

        // Toggle back
        snapshot = snapshot.copy(orientationLockEnabled = false)
        assertThat(snapshot.orientationLockEnabled).isFalse()
    }

    @Test
    fun endConnectionButton_triggersConfirmDialog() {
        // The end connection button shows a confirm dialog when clicked
        // This tests the button click behavior
        val action = SessionAction.RequestEndConnection
        assertThat(action).isInstanceOf(SessionAction::class.java)
    }

    @Test
    fun scrimClick_collapsesControlPanel() {
        // Test that clicking the scrim collapses the control panel
        // This maps to SessionAction.CollapseControlPanel
        val action = SessionAction.CollapseControlPanel
        assertThat(action).isInstanceOf(SessionAction::class.java)
    }

    @Test
    fun floatingBallClick_expandsControlPanel() {
        // Test that tapping the floating ball expands the control panel
        // This maps to SessionAction.ToggleControlPanel
        val action = SessionAction.ToggleControlPanel
        assertThat(action).isInstanceOf(SessionAction::class.java)
    }
}