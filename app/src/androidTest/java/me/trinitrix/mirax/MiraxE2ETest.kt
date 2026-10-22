package me.trinitrix.mirax

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiSelector
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end integration tests for Mirax using UiAutomator.
 *
 * These tests verify:
 * - App launch and dashboard rendering
 * - Navigation between pages (Dashboard, Settings, General, Picture, Advanced)
 * - Start broadcast button behavior
 * - Connection history interaction
 * - Broadcast state transitions
 *
 * Run with: ./gradlew connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class MiraxE2ETest {

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
     * Wait for either localized label instead of assuming an unfinished UI update chose the other language.
     * @param first first localized selector
     * @param second second localized selector
     * @param timeout maximum wait in milliseconds
     * @return the visible UI object
     */
    private fun waitForEither(first: BySelector, second: BySelector, timeout: Long): UiObject2 {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeout) {
            device.findObject(first)?.let { return it }
            device.findObject(second)?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("Neither UI object found within ${timeout}ms")
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
     * Launch Mirax and verify the dashboard is visible.
     */
    @Test
    fun testAppLaunch_showsDashboard() {
        // Launch the app
        launchApp()

        // Verify key UI elements are present (launchApp waits for Start broadcast button)
        findObject(By.pkg(packageName).text(startBroadcastLabel()))
        findObject(By.pkg(packageName).descContains(historyLabel()))
    }

    /**
     * Test connection history click interaction in main pane.
     */
    @Test
    fun testConnectionHistoryClick() {
        launchApp()

        // Click on connection history area in main view (not drawer)
        val historyArea = waitForObject(
            By.pkg(packageName).descContains(historyLabel()),
            5000
        )
        historyArea.click()
        device.waitForIdle(2000)

        // Navigate back
        device.pressBack()

        // Verify back to dashboard
        val dashboardTitle = waitForObject(
            By.pkg(packageName).text("Mirax"),
            5000
        )
    }

    /**
     * Test navigation to General settings.
     */
    @Test
    fun testNavigation_toGeneral() {
        launchApp()

        openDrawer()

        val generalItem = waitForObject(
            By.pkg(packageName).desc("General, TrebleDroid vanilla"),
            5000
        )
        generalItem.click()

        // Verify General page - look for General settings
        val generalTitle = waitForObject(
            By.pkg(packageName).text("General"),
            5000
        )

        // Navigate back to dashboard
        device.pressBack()

        // Verify back to dashboard
        val dashboardTitle = waitForObject(
            By.pkg(packageName).text("Mirax"),
            5000
        )
    }

    /**
     * Test Start broadcast button.
     */
    @Test
    fun testStartBroadcastButton() {
        launchApp()

        val startButton = waitForObject(
            By.pkg(packageName).text(startBroadcastLabel()),
            launchTimeoutMs
        )

        assertThat(startButton.isClickable(), Matchers.equalTo(true))
        startButton.click()
        val stopButton = device.findObject(UiSelector().packageName(packageName).text(stopBroadcastLabel()))
        assertThat(stopButton.waitForExists(launchTimeoutMs), Matchers.equalTo(true))
        stopButton.click()
        waitForObject(By.pkg(packageName).text(startBroadcastLabel()), launchTimeoutMs)
    }

    /**
     * Test navigation to Picture settings.
     */
    @Test
    fun testNavigation_toPicture() {
        launchApp()

        openDrawer()

        val pictureItem = waitForObject(
            By.pkg(packageName).descContains("Picture"),
            5000
        )
        pictureItem.click()

        // Verify Picture page
        val pictureTitle = waitForObject(
            By.pkg(packageName).text("Picture"),
            5000
        )

        // Navigate back
        device.pressBack()

        // Verify back to dashboard
        val dashboardTitle = waitForObject(
            By.pkg(packageName).text("Mirax"),
            5000
        )
    }

    /**
     * Test navigation to Advanced settings.
     */
    @Test
    fun testNavigation_toAdvanced() {
        launchApp()

        openDrawer()

        // Navigate to Advanced
        val advancedItem = waitForObject(
            By.pkg(packageName).text("Advanced"),
            5000
        )
        advancedItem.click()

        // Verify Advanced page
        val advancedTitle = waitForObject(
            By.pkg(packageName).text("Advanced"),
            5000
        )

        // Navigate back
        device.pressBack()

        // Verify back to dashboard
        val dashboardTitle = waitForObject(
            By.pkg(packageName).text("Mirax"),
            5000
        )
    }

    /**
     * Test Settings navigation and basic interaction.
     */
    @Test
    fun testSettingsNavigation() {
        launchApp()

        openDrawer()

        val settingsItem = waitForObject(
            By.pkg(packageName).text("Settings"),
            5000
        )
        settingsItem.click()

        // Verify Settings page or drawer content
        val settingsTitle = waitForObject(
            By.pkg(packageName).text("Settings"),
            5000
        )

        // Navigate back
        device.pressBack()

        // Verify back to dashboard
        val dashboardTitle = waitForObject(
            By.pkg(packageName).text("Mirax"),
            5000
        )
    }

    @Test
    fun testLanguageChangesImmediatelyWithoutLeavingPage() {
        device.executeShellCommand("am start -n $packageName/.MainActivity")
        waitForObject(By.res("$packageName:id/menuButton"), 5000).click()
        val general = waitForEither(
            By.pkg(packageName).text("General"), By.pkg(packageName).text("一般"), 5000
        )
        general.click()
        val language = waitForEither(
            By.pkg(packageName).descContains("Language"), By.pkg(packageName).descContains("語言"), 5000
        )
        language.click()

        val english = waitForEither(
            By.pkg(packageName).text("English"), By.pkg(packageName).text("英文"), 5000
        )
        english.click()
        waitForObject(By.pkg(packageName).text("Language"), 5000)
        waitForObject(By.pkg(packageName).text("Traditional Chinese"), 5000).click()
        waitForObject(By.pkg(packageName).text("語言"), 5000)
        waitForObject(By.pkg(packageName).text("英文"), 5000).click()
        waitForObject(By.pkg(packageName).text("Language"), 5000)
        waitForObject(By.pkg(packageName).text("Follow system"), 5000).click()
        val systemChinese = me.trinitrix.mirax.host.HostEnvironment.isSystemTraditionalChinese(context)
        val systemTitle = if (systemChinese) "語言" else "Language"
        waitForObject(By.pkg(packageName).text(systemTitle), 5000)
        val followLabel = if (systemChinese) "跟隨系統" else "Follow system"
        assertThat(waitForObject(By.pkg(packageName).text(followLabel), 5000).isSelected, Matchers.`is`(true))
        val englishLabel = if (systemChinese) "英文" else "English"
        waitForObject(By.pkg(packageName).text(englishLabel), 5000).click()
        assertThat(waitForObject(By.pkg(packageName).text(englishLabel), 5000).isSelected, Matchers.`is`(true))
        waitForObject(By.pkg(packageName).text("Follow system"), 5000).click()
        assertThat(waitForObject(By.pkg(packageName).text("Follow system"), 5000).isSelected, Matchers.`is`(true))
    }

    private fun launchApp(): UiObject2 {
        device.executeShellCommand("am start -n $packageName/.MainActivity")
        waitForObject(By.res("$packageName:id/menuButton"), launchTimeoutMs)
        return waitForObject(By.pkg(packageName).text(startBroadcastLabel()), launchTimeoutMs)
    }

    private fun openDrawer() {
        val menuButton = waitForObject(
            By.res("me.trinitrix.mirax:id/menuButton"),
            5000
        )
        menuButton.click()
        // Wait for drawer to fully open - wait for a drawer item
        waitForObject(By.text("Settings"), 5000)
        device.waitForIdle(500)
    }

    private fun startBroadcastLabel(): String = context.getString(R.string.start_broadcast)

    private fun stopBroadcastLabel(): String = context.getString(R.string.stop_broadcast)

    private fun historyLabel(): String = context.getString(R.string.history_entry)

}
