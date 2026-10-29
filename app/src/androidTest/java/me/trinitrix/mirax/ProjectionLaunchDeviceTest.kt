package me.trinitrix.mirax

import android.content.Intent
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.trinitrix.mirax.host.OverlayPermission
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SessionEffect
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.wfd.WfdOwnerBridge
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Verifies unseen-picture recovery without a PC stream on an explicitly selected privileged device. */
@RunWith(AndroidJUnit4::class)
class ProjectionLaunchDeviceTest {
    /** A reused launcher activity must recover a connection whose initial launch was consumed. */
    @Test(timeout = 30_000)
    fun launcherNewIntent_recoversUnseenPicture(): Unit = verifyLaunch(background = false)

    /** The real owner must foreground Mirax without an overlay grant or a visible app window. */
    @Test(timeout = 30_000)
    fun backgroundOwnerLaunch_withoutOverlay_opensPictureSurface(): Unit = verifyLaunch(background = true)

    /**
     * Exercise the real host and lifecycle, substituting only the connected RTSP state.
     *
     * @param background whether to request the picture from Home through the privileged owner
     */
    private fun verifyLaunch(background: Boolean): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("projectionLaunchTestMode") == "privileged")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val session = MiraxApp.instance.session
        assumeTrue(!session.snapshot().showPicture)
        if (background) assumeTrue(!OverlayPermission.canDrawOverlays(context))
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        val launch = Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
        )
        val originalHelperExchange = WfdOwnerBridge.helperExchange
        val originalShizukuLauncher = WfdOwnerBridge.shizukuProjectionLauncher
        val ownerLaunches = AtomicInteger(0)
        ActivityScenario.launch<MainActivity>(launch).use { scenario ->
            try {
                scenario.onActivity {
                    assertThat(session.snapshot().wfdOwner).isNotEqualTo(WfdOwner.NONE)
                }
                if (background) {
                    WfdOwnerBridge.helperExchange = { command, readAll ->
                        if (command == "SHOW_PROJECTION") ownerLaunches.incrementAndGet()
                        originalHelperExchange(command, readAll)
                    }
                    WfdOwnerBridge.shizukuProjectionLauncher = {
                        ownerLaunches.incrementAndGet()
                        originalShizukuLauncher()
                    }
                    withTimeout(10_000) {
                        while (WfdOwnerBridge.wmSize(null).isNullOrBlank()) delay(50)
                    }
                    device.pressHome()
                    instrumentation.waitForIdleSync()
                    withTimeout(5_000) {
                        var dashboardStopped = false
                        while (!dashboardStopped) {
                            instrumentation.runOnMainSync { dashboardStopped = !MainActivity.isShowing() }
                            if (!dashboardStopped) delay(50)
                        }
                    }
                    assertThat(device.currentPackageName).isNotEqualTo(context.packageName)
                }
                instrumentation.runOnMainSync {
                    session.enterMockConnected()
                    session.handle(SessionAction.AcknowledgeEffects)
                    if (background) {
                        session.handle(SessionAction.OpenedMiraxDashboard)
                        SessionHost.commit(context)
                    } else {
                        context.startActivity(launch)
                    }
                }
                withTimeout(10_000) {
                    var pictureReady = false
                    while (!pictureReady) {
                        instrumentation.runOnMainSync {
                            val picture = PictureActivity.current()
                            val stage = picture?.findViewById<FrameLayout>(android.R.id.content)?.getChildAt(0) as? FrameLayout
                            val surface = stage?.children?.filterIsInstance<SurfaceView>()?.firstOrNull()
                            pictureReady = picture?.hasWindowFocus() == true && surface?.holder?.surface?.isValid == true
                        }
                        if (!pictureReady) delay(50)
                    }
                }
                if (background) assertThat(ownerLaunches.get()).isEqualTo(1)
                instrumentation.runOnMainSync {
                    session.handle(SessionAction.AcknowledgeEffects)
                    session.handle(SessionAction.OpenedMiraxDashboard)
                    assertThat(session.snapshot().effects).doesNotContain(SessionEffect.BringProjectionToFront)
                    assertThat(session.snapshot().showPicture).isTrue()
                }
            } finally {
                instrumentation.runOnMainSync {
                    session.handle(SessionAction.ConnectionEnded)
                    PictureActivity.finishIfShowing()
                    SessionHost.commit(context)
                }
                WfdOwnerBridge.helperExchange = originalHelperExchange
                WfdOwnerBridge.shizukuProjectionLauncher = originalShizukuLauncher
            }
        }
    }
}
