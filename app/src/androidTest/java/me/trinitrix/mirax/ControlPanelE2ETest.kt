package me.trinitrix.mirax

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.session.PictureScale
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * End-to-end integration tests for the control panel (issue #22).
 *
 * These tests use the mock session feature (intent extra mirax_mock_session=true)
 * to launch PictureActivity without a real Miracast connection.
 *
 * Run with: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ControlPanelE2ETest {

    private lateinit var device: UiDevice
    private lateinit var context: Context
    private val packageName = "me.trinitrix.mirax"
    private val launchTimeoutMs = 10000L

    /**
     * Helper to get a non-null UiObject2 by polling device.findObject() until found or timeout.
     * Throws AssertionError if not found within timeout.
     */
    private fun waitForObject(selector: BySelector, timeout: Long): UiObject2 {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeout) {
            val obj = device.findObject(selector)
            if (obj != null) return obj
            Thread.sleep(100)
        }
        throw AssertionError("UI object not found within ${timeout}ms")
    }

    /**
     * Helper to get a non-null UiObject2 from device.findObject() using BySelector.
     * Throws AssertionError if not found.
     */
    private fun findObject(selector: BySelector): UiObject2 {
        return device.findObject(selector) ?: throw AssertionError("UI object not found")
    }

    @Before
    fun setUp() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instrumentation)
        context = ApplicationProvider.getApplicationContext<Context>()
        val session = (context as MiraxApp).session
        if (session.snapshot().controlPanelExpanded) {
            session.handle(me.trinitrix.mirax.session.SessionAction.ToggleControlPanel)
        }

        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.pressHome()
    }

    @After
    fun tearDown() {
        device.pressHome()
    }

    /** Verify the debug mock opens the control panel on launch. */
    @Test
    fun testMockSession_controlPanelVisibleByDefault() {
        val intent = Intent(context, PictureActivity::class.java).apply {
            putExtra("mirax_mock_session", true)
        }
        val scenario = ActivityScenario.launch<PictureActivity>(intent)

        lateinit var closeLabel: String
        var minTouchSize = 0
        scenario.onActivity { activity ->
            assertThat((activity.application as MiraxApp).session.snapshot().controlPanelExpanded).isTrue()
            closeLabel = activity.getString(R.string.control_panel_close)
            minTouchSize = (48 * activity.resources.displayMetrics.density).toInt()
        }
        waitForObject(By.text("Mock Device"), launchTimeoutMs)
        val closeButton = waitForObject(By.desc(closeLabel), launchTimeoutMs)
        assertThat(closeButton.visibleBounds.width()).isAtLeast(minTouchSize)
        assertThat(closeButton.visibleBounds.height()).isAtLeast(minTouchSize)

        scenario.close()
    }

    /** Verify portrait settings replace the body without hiding status or disconnect. */
    @Test
    fun testMockSession_portraitSettingsPageKeepsActionsVisible() {
        val originalRotation = device.executeShellCommand("cmd window user-rotation").trim()
        val originalAngle = device.executeShellCommand("settings get system user_rotation").trim()
        try {
            device.executeShellCommand("cmd window user-rotation lock 0")
            val intent = Intent(context, PictureActivity::class.java).apply {
                putExtra("mirax_mock_session", true)
            }
            val scenario = ActivityScenario.launch<PictureActivity>(intent)
            try {
                lateinit var settingsLabel: String
                lateinit var sourceLabel: String
                lateinit var scaleLabel: String
                lateinit var debugLabel: String
                lateinit var endLabel: String
                lateinit var statusLabel: String
                lateinit var backLabel: String
                scenario.onActivity { activity ->
                    settingsLabel = activity.getString(R.string.control_panel_other_settings)
                    sourceLabel = activity.getString(R.string.control_panel_source_device)
                    scaleLabel = activity.getString(R.string.control_panel_scale_title)
                    debugLabel = activity.getString(R.string.control_panel_debug_overlay)
                    endLabel = activity.getString(R.string.end_connection_confirm_action)
                    statusLabel = activity.getString(R.string.control_panel_casting_status)
                    backLabel = activity.getString(R.string.nav_back)
                }
                val mainPanelBounds = waitForObject(By.clazz(ScrollView::class.java), launchTimeoutMs).visibleBounds
                waitForObject(By.text(settingsLabel), launchTimeoutMs).click()
                waitForObject(By.text(debugLabel), launchTimeoutMs)
                assertThat(waitForObject(By.clazz(ScrollView::class.java), launchTimeoutMs).visibleBounds)
                    .isEqualTo(mainPanelBounds)
                assertThat(device.findObject(By.text(sourceLabel))).isNull()
                assertThat(device.findObject(By.text(scaleLabel))).isNull()
                waitForObject(By.text(statusLabel), launchTimeoutMs)
                waitForObject(By.text(endLabel), launchTimeoutMs)
                device.pressBack()
                waitForObject(By.text(sourceLabel), launchTimeoutMs)
                waitForObject(By.text(scaleLabel), launchTimeoutMs)
                waitForObject(By.text(settingsLabel), launchTimeoutMs).click()
                waitForObject(By.desc(backLabel), launchTimeoutMs).click()
                waitForObject(By.text(sourceLabel), launchTimeoutMs)
                waitForObject(By.text(scaleLabel), launchTimeoutMs)
                assertThat(waitForObject(By.clazz(ScrollView::class.java), launchTimeoutMs).visibleBounds)
                    .isEqualTo(mainPanelBounds)
            } finally {
                scenario.close()
            }
        } finally {
            if (originalRotation == "free" && originalAngle in listOf("0", "1", "2", "3")) {
                device.executeShellCommand("cmd window user-rotation lock $originalAngle")
            }
            device.executeShellCommand("cmd window user-rotation $originalRotation")
        }
    }

    /** Verify the panel toggle closes and reopens the debug mock session. */
    @Test
    fun testMockSession_controlPanelExpandsOnToggle() {
        val intent = Intent(context, PictureActivity::class.java).apply {
            putExtra("mirax_mock_session", true)
        }
        val scenario = ActivityScenario.launch<PictureActivity>(intent)

        scenario.onActivity { activity ->
            val session = (activity.application as MiraxApp).session
            assertThat(session.snapshot().controlPanelExpanded).isTrue()

            session.handle(me.trinitrix.mirax.session.SessionAction.ToggleControlPanel)
            assertThat(session.snapshot().controlPanelExpanded).isFalse()

            session.handle(me.trinitrix.mirax.session.SessionAction.ToggleControlPanel)
            assertThat(session.snapshot().controlPanelExpanded).isTrue()
        }

        scenario.close()
    }

    /** Verify tapping a scale button persists the selected picture scale. */
    @Test
    fun testMockSession_scaleClickPersistsSelection() {
        val originalScale = SessionPreferences.load(context).pictureScale
        val targetScale = if (originalScale == PictureScale.CENTER_CROP) PictureScale.MATCH_EDGES else PictureScale.CENTER_CROP
        val intent = Intent(context, PictureActivity::class.java).apply {
            putExtra("mirax_mock_session", true)
        }
        val scenario = ActivityScenario.launch<PictureActivity>(intent)
        try {
            lateinit var scaleLabel: String
            scenario.onActivity { activity ->
                assertThat((activity.application as MiraxApp).session.snapshot().controlPanelExpanded).isTrue()
                scaleLabel = activity.getString(
                    if (targetScale == PictureScale.CENTER_CROP) R.string.control_panel_scale_crop
                    else R.string.control_panel_scale_stretch,
                )
            }
            onView(withText(scaleLabel)).perform(scrollTo(), click())
            assertThat(SessionPreferences.load(context).pictureScale).isEqualTo(targetScale)
        } finally {
            scenario.onActivity { activity -> SessionHost.setPictureScale(activity, originalScale) }
            scenario.close()
        }
    }

    /** Verify Back collapses the expanded panel, then requests end confirmation. */
    @Test
    fun testMockSession_backKeyLogic() {
        val intent = Intent(context, PictureActivity::class.java).apply {
            putExtra("mirax_mock_session", true)
        }
        val scenario = ActivityScenario.launch<PictureActivity>(intent)

        scenario.onActivity { activity: PictureActivity ->
            Thread.sleep(1000)

            val session = activity.application as me.trinitrix.mirax.MiraxApp

            var snapshot = session.session.snapshot()
            assertThat(snapshot.controlPanelExpanded).isTrue()

            session.session.handle(me.trinitrix.mirax.session.SessionAction.SystemBack)
            snapshot = session.session.snapshot()
            assertThat(snapshot.controlPanelExpanded).isFalse()
            assertThat(snapshot.effects).contains(me.trinitrix.mirax.session.SessionEffect.ShowPressBackAgainToEndToast)

            session.session.handle(me.trinitrix.mirax.session.SessionAction.SystemBack)
            snapshot = session.session.snapshot()
            assertThat(snapshot.effects).contains(me.trinitrix.mirax.session.SessionEffect.ConfirmEndConnection)
            assertThat(snapshot.phase).isEqualTo(me.trinitrix.mirax.session.ScreenPhase.CONNECTED)
            session.session.handle(me.trinitrix.mirax.session.SessionAction.AcknowledgeEffects)
        }

        scenario.close()
    }

    /**
     * Test that orientation lock switch toggles correctly in mock session.
     */
    @Test
    fun testMockSession_orientationLockToggle() {
        val intent = Intent(context, PictureActivity::class.java).apply {
            putExtra("mirax_mock_session", true)
        }
        val scenario = ActivityScenario.launch<PictureActivity>(intent)

        scenario.onActivity { activity: PictureActivity ->
            Thread.sleep(1000)

            val session = activity.application as me.trinitrix.mirax.MiraxApp

            // Initial state - orientation lock off
            var snapshot = session.session.snapshot()
            assertThat(snapshot.orientationLockEnabled).isFalse()

            // Toggle orientation lock on
            session.session.handle(me.trinitrix.mirax.session.SessionAction.SetOrientationLock(true))
            snapshot = session.session.snapshot()
            assertThat(snapshot.orientationLockEnabled).isTrue()

            // Toggle orientation lock off
            session.session.handle(me.trinitrix.mirax.session.SessionAction.SetOrientationLock(false))
            snapshot = session.session.snapshot()
            assertThat(snapshot.orientationLockEnabled).isFalse()
        }

        scenario.close()
    }

    /**
     * Test that source device name is displayed correctly in mock session.
     */
    @Test
    fun testMockSession_sourceDeviceName() {
        val intent = Intent(context, PictureActivity::class.java).apply {
            putExtra("mirax_mock_session", true)
        }
        val scenario = ActivityScenario.launch<PictureActivity>(intent)

        scenario.onActivity { activity: PictureActivity ->
            Thread.sleep(1000)

            val session = activity.application as me.trinitrix.mirax.MiraxApp

            // Initial state - mock device name
            var snapshot = session.session.snapshot()
            assertThat(snapshot.sourceDeviceName).isEqualTo("Mock Device")

            // Update source device name
            session.session.handle(me.trinitrix.mirax.session.SessionAction.SetSourceDeviceName("TestSource"))
            snapshot = session.session.snapshot()
            assertThat(snapshot.sourceDeviceName).isEqualTo("TestSource")

            // Clear source device name
            session.session.handle(me.trinitrix.mirax.session.SessionAction.SetSourceDeviceName(null))
            snapshot = session.session.snapshot()
            assertThat(snapshot.sourceDeviceName).isNull()
        }

        scenario.close()
    }

    /**
     * Test that end connection button shows confirm dialog.
     */
    @Test
    fun testMockSession_endConnectionShowsConfirm() {
        val intent = Intent(context, PictureActivity::class.java).apply {
            putExtra("mirax_mock_session", true)
        }
        val scenario = ActivityScenario.launch<PictureActivity>(intent)

        scenario.onActivity { activity: PictureActivity ->
            Thread.sleep(1000)

            val session = activity.application as me.trinitrix.mirax.MiraxApp

            // Request end connection
            session.session.handle(me.trinitrix.mirax.session.SessionAction.RequestEndConnection)
            var snapshot = session.session.snapshot()

            // Should show confirm dialog
            assertThat(snapshot.effects).contains(me.trinitrix.mirax.session.SessionEffect.ConfirmEndConnection)

            // Acknowledge and end
            session.session.handle(me.trinitrix.mirax.session.SessionAction.AcknowledgeEffects)
            session.session.handle(me.trinitrix.mirax.session.SessionAction.EndConnection)

            snapshot = session.session.snapshot()
            assertThat(snapshot.phase).isNotEqualTo(me.trinitrix.mirax.session.ScreenPhase.CONNECTED)
        }

        scenario.close()
    }

    /**
     * Test that the mock session provides correct initial values.
     */
    @Test
    fun testMockSession_initialValues() {
        val intent = Intent(context, PictureActivity::class.java).apply {
            putExtra("mirax_mock_session", true)
        }
        val scenario = ActivityScenario.launch<PictureActivity>(intent)

        scenario.onActivity { activity: PictureActivity ->
            Thread.sleep(1000)

            val session = activity.application as me.trinitrix.mirax.MiraxApp
            val snapshot = session.session.snapshot()

            assertThat(snapshot.phase).isEqualTo(me.trinitrix.mirax.session.ScreenPhase.CONNECTED)
            assertThat(snapshot.handleResolutionText).isEqualTo("1920×1080")
            assertThat(snapshot.handleRefreshRateHz).isEqualTo(60)
            assertThat(snapshot.sourceDeviceName).isEqualTo("Mock Device")
            assertThat(snapshot.touchEnabled).isTrue()
            assertThat(snapshot.pictureScale).isEqualTo(me.trinitrix.mirax.session.PictureScale.PROPORTIONAL)
            assertThat(snapshot.cameraCutoutAffectsLayout).isFalse()
            assertThat(snapshot.showDebugOverlay).isFalse()
            assertThat(snapshot.controlPanelExpanded).isTrue()
            assertThat(snapshot.orientationLockEnabled).isFalse()
        }

        scenario.close()
    }

    /** Verify a saved lock applies immediately, even after Back hides the panel. */
    @Test
    fun testMockSession_orientationLockAppliesWithPanelHidden() {
        val session = (context as MiraxApp).session
        val originalLock = session.snapshot().orientationLockEnabled
        session.handle(me.trinitrix.mirax.session.SessionAction.SetOrientationLock(true))
        val intent = Intent(context, PictureActivity::class.java).apply {
            putExtra("mirax_mock_session", true)
        }
        val scenario = ActivityScenario.launch<PictureActivity>(intent)
        try {
            var expectedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            scenario.onActivity { activity ->
                expectedOrientation = if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
                assertThat(activity.requestedOrientation).isEqualTo(expectedOrientation)
            }
            device.pressBack()
            scenario.onActivity { activity ->
                assertThat(session.snapshot().controlPanelExpanded).isFalse()
                assertThat(activity.requestedOrientation).isEqualTo(expectedOrientation)
            }
        } finally {
            scenario.close()
            SessionHost.toggleOrientationLock(context, originalLock)
        }
    }

    /** A display-only picture tap must not become a phone control-panel action. */
    @Test
    fun testMockSession_displayOnlyTouchDoesNotOpenPanel() {
        val session = (context as MiraxApp).session
        val originalTouchEnabled = session.snapshot().touchEnabled
        val intent = Intent(context, PictureActivity::class.java).apply {
            putExtra("mirax_mock_session", true)
        }
        val scenario = ActivityScenario.launch<PictureActivity>(intent)
        try {
            scenario.onActivity { activity ->
                session.handle(me.trinitrix.mirax.session.SessionAction.SetTouchEnabled(false))
                SessionHost.commit(activity)
            }
            device.pressBack()
            scenario.onActivity { assertThat(session.snapshot().controlPanelExpanded).isFalse() }
            device.click(device.displayWidth / 2, device.displayHeight / 2)
            scenario.onActivity { assertThat(session.snapshot().controlPanelExpanded).isFalse() }
        } finally {
            scenario.onActivity { activity ->
                session.handle(me.trinitrix.mirax.session.SessionAction.SetTouchEnabled(originalTouchEnabled))
                SessionHost.commit(activity)
            }
            scenario.close()
        }
    }

    /** Verify landscape settings replace the right pane while the source and actions stay visible. */
    @Test
    fun testMockSession_landscapeEndActionStaysVisible() {
        val originalRotation = device.executeShellCommand("cmd window user-rotation").trim()
        val originalAngle = device.executeShellCommand("settings get system user_rotation").trim()
        try {
            device.executeShellCommand("cmd window user-rotation lock 1")
            val intent = Intent(context, PictureActivity::class.java).apply {
                putExtra("mirax_mock_session", true)
            }
            val scenario = ActivityScenario.launch<PictureActivity>(intent)
            try {
                device.waitForIdle()
                lateinit var endLabel: String
                lateinit var statusLabel: String
                lateinit var sourceLabel: String
                lateinit var scaleLabel: String
                lateinit var settingsLabel: String
                lateinit var debugLabel: String
                lateinit var touchLabel: String
                lateinit var cutoutLabel: String
                scenario.onActivity { activity ->
                    endLabel = activity.getString(R.string.end_connection_confirm_action)
                    statusLabel = activity.getString(R.string.control_panel_casting_status)
                    sourceLabel = activity.getString(R.string.control_panel_source_device)
                    scaleLabel = activity.getString(R.string.control_panel_scale_title)
                    settingsLabel = activity.getString(R.string.control_panel_other_settings)
                    debugLabel = activity.getString(R.string.control_panel_debug_overlay)
                    touchLabel = activity.getString(R.string.control_panel_touch_input)
                    cutoutLabel = activity.getString(R.string.control_panel_avoid_cutout)
                }
                val endButton = waitForObject(By.text(endLabel), launchTimeoutMs)
                val status = waitForObject(By.text(statusLabel), launchTimeoutMs)
                waitForObject(By.text(sourceLabel), launchTimeoutMs)
                waitForObject(By.text(scaleLabel), launchTimeoutMs)
                val scrollView = waitForObject(By.clazz("android.widget.ScrollView"), launchTimeoutMs)
                assertThat(scrollView.findObject(By.text(endLabel))).isNull()
                assertThat(scrollView.findObject(By.text(statusLabel))).isNull()
                val panelBounds = scrollView.parent.visibleBounds
                assertThat(panelBounds.width()).isAtMost(device.displayWidth * 4 / 5)
                assertThat(panelBounds.height()).isAtMost(device.displayHeight * 4 / 5)
                val buttonBounds = endButton.visibleBounds
                val statusBounds = status.visibleBounds
                assertThat(buttonBounds.height()).isGreaterThan(0)
                assertThat(buttonBounds.top).isLessThan(scrollView.visibleBounds.top)
                assertThat(buttonBounds.bottom).isAtMost(device.displayHeight)
                if (scrollView.isScrollable) {
                    scrollView.scroll(Direction.DOWN, 1f)
                    assertThat(waitForObject(By.text(endLabel), launchTimeoutMs).visibleBounds)
                        .isEqualTo(buttonBounds)
                    assertThat(waitForObject(By.text(statusLabel), launchTimeoutMs).visibleBounds)
                        .isEqualTo(statusBounds)
                }
                waitForObject(By.text(settingsLabel), launchTimeoutMs).click()
                waitForObject(By.text(debugLabel), launchTimeoutMs)
                waitForObject(By.text(sourceLabel), launchTimeoutMs)
                assertThat(device.findObject(By.text(scaleLabel))).isNull()
                assertThat(waitForObject(By.text(endLabel), launchTimeoutMs).visibleBounds).isEqualTo(buttonBounds)
                assertThat(scrollView.parent.visibleBounds).isEqualTo(panelBounds)
                if (scrollView.isScrollable) {
                    scrollView.scroll(Direction.DOWN, 1f)
                }
                waitForObject(By.text(touchLabel), launchTimeoutMs)
                waitForObject(By.text(cutoutLabel), launchTimeoutMs)
                assertThat(waitForObject(By.text(endLabel), launchTimeoutMs).visibleBounds).isEqualTo(buttonBounds)
                device.pressBack()
                waitForObject(By.text(scaleLabel), launchTimeoutMs)
                waitForObject(By.text(sourceLabel), launchTimeoutMs)
                assertThat(scrollView.parent.visibleBounds).isEqualTo(panelBounds)
            } finally {
                scenario.close()
            }
        } finally {
            if (originalRotation == "free" && originalAngle in listOf("0", "1", "2", "3")) {
                device.executeShellCommand("cmd window user-rotation lock $originalAngle")
            }
            device.executeShellCommand("cmd window user-rotation $originalRotation")
        }
    }

    /** Verify the panel resources are available in both supported app languages. */
    @Test
    fun testControlPanel_stringsInBothLanguages() {
        val english = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) },
        )
        val chinese = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.TAIWAN) },
        )
        assertThat(english.getString(R.string.control_panel_casting_status)).isEqualTo("Projection active")
        assertThat(chinese.getString(R.string.control_panel_casting_status)).isEqualTo("投放中")
        assertThat(english.getString(R.string.control_panel_scale_fit)).isEqualTo("Fit")
        assertThat(chinese.getString(R.string.control_panel_scale_fit)).isEqualTo("等比")
        assertThat(english.getString(R.string.control_panel_other_settings)).isEqualTo("Other settings")
        assertThat(chinese.getString(R.string.control_panel_other_settings)).isEqualTo("其他設定")
        assertThat(english.getString(R.string.control_panel_close)).isEqualTo("Close control panel")
        assertThat(chinese.getString(R.string.control_panel_close)).isEqualTo("關閉投放控制面板")
        assertThat(english.getString(R.string.control_panel_rotate)).isEqualTo("Switch orientation")
        assertThat(chinese.getString(R.string.control_panel_rotate)).isEqualTo("切換螢幕方向")
        assertThat(english.getString(R.string.end_connection_confirm_action)).isEqualTo("End connection")
        assertThat(chinese.getString(R.string.end_connection_confirm_action)).isEqualTo("結束連線")
    }
}
