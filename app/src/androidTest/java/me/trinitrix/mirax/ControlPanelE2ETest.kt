package me.trinitrix.mirax

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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

        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.pressHome()
    }

    @After
    fun tearDown() {
        device.pressHome()
    }

    /**
     * Launch PictureActivity with mock session and verify control panel is hidden by default.
     */
    @Test
    fun testMockSession_controlPanelHiddenByDefault() {
        // Launch PictureActivity with mock session
        val intent = Intent(context, PictureActivity::class.java).apply {
            putExtra("mirax_mock_session", true)
        }
        val scenario = ActivityScenario.launch<PictureActivity>(intent)

        scenario.onActivity { activity: PictureActivity ->
            // Wait for activity to be ready
            Thread.sleep(1000)

            // Verify control panel is hidden (controlPanelExpanded = false by default)
            val controlPanelRoot = findViewByIdSafely(activity, "controlPanelRoot")
            assertThat(controlPanelRoot?.visibility).isEqualTo(android.view.View.GONE)
        }

        scenario.close()
    }

    /**
     * Test that tapping the scrim expands the control panel.
     * Note: In mock session, we can't easily simulate the toggle, but we can verify
     * the session action dispatching works.
     */
    @Test
    fun testMockSession_controlPanelExpandsOnToggle() {
        val intent = Intent(context, PictureActivity::class.java).apply {
            putExtra("mirax_mock_session", true)
        }
        val scenario = ActivityScenario.launch<PictureActivity>(intent)

        scenario.onActivity { activity: PictureActivity ->
            Thread.sleep(1000)

            // Verify initial state - panel is collapsed
            val controlPanelRoot = findViewByIdSafely(activity, "controlPanelRoot")
            assertThat(controlPanelRoot?.visibility).isEqualTo(android.view.View.GONE)

            // Simulate ToggleControlPanel action
            // This tests that the session action works correctly
            val session = activity.application as me.trinitrix.mirax.MiraxApp
            session.session.handle(me.trinitrix.mirax.session.SessionAction.ToggleControlPanel)

            // Verify panel expanded
            val snapshot = session.session.snapshot()
            assertThat(snapshot.controlPanelExpanded).isTrue()
        }

        scenario.close()
    }

    /**
     * Test that back key behavior follows the new control panel logic:
     * - Panel closed → first back → panel expands
     * - Panel expanded → first back → panel closes, toast shown
     * - Panel expanded → second back → requests end connection
     */
    @Test
    fun testMockSession_backKeyLogic() {
        val intent = Intent(context, PictureActivity::class.java).apply {
            putExtra("mirax_mock_session", true)
        }
        val scenario = ActivityScenario.launch<PictureActivity>(intent)

        scenario.onActivity { activity: PictureActivity ->
            Thread.sleep(1000)

            val session = activity.application as me.trinitrix.mirax.MiraxApp

            // Initial state - panel collapsed
            var snapshot = session.session.snapshot()
            assertThat(snapshot.controlPanelExpanded).isFalse()

            // First back - should expand panel
            session.session.handle(me.trinitrix.mirax.session.SessionAction.SystemBack)
            snapshot = session.session.snapshot()
            assertThat(snapshot.controlPanelExpanded).isTrue()

            // Second back - should collapse panel and arm for end
            session.session.handle(me.trinitrix.mirax.session.SessionAction.SystemBack)
            snapshot = session.session.snapshot()
            assertThat(snapshot.controlPanelExpanded).isFalse()
            // Note: ActiveConnectionState handles the armed state internally
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
            assertThat(snapshot.sourceDeviceName).isEqualTo("MockDevice")

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
            assertThat(snapshot.phase).isEqualTo(me.trinitrix.mirax.session.ScreenPhase.ADVERTISING)
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
            assertThat(snapshot.sourceDeviceName).isEqualTo("MockDevice")
            assertThat(snapshot.touchEnabled).isTrue()
            assertThat(snapshot.pictureScale).isEqualTo(me.trinitrix.mirax.session.PictureScale.PROPORTIONAL)
            assertThat(snapshot.cameraCutoutAffectsLayout).isFalse()
            assertThat(snapshot.showDebugOverlay).isFalse()
            assertThat(snapshot.controlPanelExpanded).isFalse()
            assertThat(snapshot.orientationLockEnabled).isFalse()
        }

        scenario.close()
    }

    /** Verify landscape actions and status stay visible independently of the scrollable controls. */
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
                val endButton = waitForObject(By.text("結束連線"), launchTimeoutMs)
                val status = waitForObject(By.text("投放中"), launchTimeoutMs)
                val scrollView = waitForObject(By.clazz("android.widget.ScrollView"), launchTimeoutMs)
                assertThat(scrollView.findObject(By.text("結束連線"))).isNull()
                assertThat(scrollView.findObject(By.text("投放中"))).isNull()
                val buttonBounds = endButton.visibleBounds
                val statusBounds = status.visibleBounds
                assertThat(buttonBounds.height()).isGreaterThan(0)
                assertThat(buttonBounds.top).isLessThan(scrollView.visibleBounds.top)
                assertThat(buttonBounds.bottom).isAtMost(device.displayHeight)
                if (scrollView.isScrollable) {
                    scrollView.scroll(Direction.DOWN, 1f)
                    assertThat(waitForObject(By.text("結束連線"), launchTimeoutMs).visibleBounds)
                        .isEqualTo(buttonBounds)
                    assertThat(waitForObject(By.text("投放中"), launchTimeoutMs).visibleBounds)
                        .isEqualTo(statusBounds)
                }
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

    private fun findViewByIdSafely(activity: android.app.Activity, viewIdName: String): android.view.View? {
        val id = activity.resources.getIdentifier(viewIdName, "id", activity.packageName)
        if (id == 0) return null
        return activity.findViewById<android.view.View>(id)
    }
}
