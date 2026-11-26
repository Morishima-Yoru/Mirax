package me.trinitrix.mirax.ui.standby

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.Choreographer
import android.view.MotionEvent
import androidx.lifecycle.Lifecycle
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import me.trinitrix.mirax.ui.standby.runtime.StandbyOledDriftMath
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.trinitrix.mirax.awaitActivityAction
import me.trinitrix.mirax.data.standby.AndroidStandbyMediaValidator
import me.trinitrix.mirax.data.standby.StandbyFraming
import me.trinitrix.mirax.data.standby.StandbyMediaStore
import me.trinitrix.mirax.data.standby.theme.StandbyThemeId
import me.trinitrix.mirax.session.PrePlayFailureCause
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.standbyprobe.StandbyProbeActivity
import me.trinitrix.mirax.ui.standby.foreground.HardlineStandbyForegroundView
import me.trinitrix.mirax.ui.standby.foreground.LandscapeStandbyForegroundView
import me.trinitrix.mirax.ui.standby.foreground.MatterStandbyForegroundView
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundCallbacks
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundState
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundTags
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundView
import me.trinitrix.mirax.ui.standby.foreground.awaitForegroundLayout
import me.trinitrix.mirax.ui.standby.hardline.HardlineSceneView
import me.trinitrix.mirax.ui.standby.hardline.RogHardlineScene
import me.trinitrix.mirax.ui.standby.landscape.LandscapeSceneView
import me.trinitrix.mirax.ui.standby.matter.MatterParticleView
import me.trinitrix.mirax.ui.standby.media.AndroidStandbyMediaDriver
import me.trinitrix.mirax.ui.standby.media.StandbyMediaPlaybackController
import me.trinitrix.mirax.ui.standby.media.StandbyMediaUnderlayView
import me.trinitrix.mirax.ui.standby.media.StandbyPlaybackState
import me.trinitrix.mirax.ui.standby.media.StandbyVideoOutput
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Direct host checks; execution and visual acceptance belong to the parent. */
@RunWith(AndroidJUnit4::class)
class BigStandbyHostTest {
    /** Uses fixed monotonic inputs; real vsyncs only deliver callbacks, never determine expected phase. */
    @Test
    fun legacyAmbient_retainsSharedPhaseAcrossThemesMediaAndRuntime() {
        val clock = AtomicLong(1000L)
        val reads = AtomicInteger()
        val menuClicks = AtomicInteger()
        val state = StandbyForegroundState(ScreenPhase.READY, false, receiverName = "Studio", isProjecting = false)
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var host: BigStandbyHost
            var owned: BigStandbyHost? = null
            try {
                awaitActivityAction(scenario) { activity ->
                    host = BigStandbyHost(activity, StandbyForegroundCallbacks(
                        onToggleBroadcast = {}, onOpenSettings = { menuClicks.incrementAndGet() },
                    ))
                    owned = host
                    host.clockUptimeMillis = { reads.incrementAndGet(); clock.get() }
                    host.render(StandbyThemeId.HARDLINE, state, "Ready", "BROADCAST OFF", "")
                    val hardline = (0 until host.childCount).map(host::getChildAt).filterIsInstance<HardlineStandbyForegroundView>().single()
                    assertEquals(1.2f * host.resources.displayMetrics.density, hardline.motionLayer.translationX, 0.0001f)
                    assertEquals(2.4f * host.resources.displayMetrics.density, hardline.motionLayer.translationY, 0.0001f)
                    assertEquals(0, reads.get())
                    host.applySafeInsets(Insets.of(20, 30, 40, 50))
                    host.setRuntimeVisible(true)
                    activity.contentRoot.addView(host, FrameLayout.LayoutParams(-1, -1))
                }
                awaitForegroundLayout(scenario, host)
                awaitLegacyVsyncs(2)
                awaitActivityAction(scenario) { assertLegacyPosition(host, 0.0, true) }
                clock.addAndGet(10_000L)
                awaitLegacyVsyncs(2)
                awaitActivityAction(scenario) {
                    assertLegacyPosition(host, 0.08, true)
                    val foreground = (0 until host.childCount).map(host::getChildAt).filterIsInstance<HardlineStandbyForegroundView>().single()
                    val menu = requireNotNull(foreground.findViewWithTag<View>(StandbyForegroundTags.MENU))
                    val controls = menu.parent as View
                    assertTrue(menu.requestFocusFromTouch())
                    val focus = Rect()
                    menu.getFocusedRect(focus)
                    assertEquals(Rect(0, 0, menu.width, menu.height), focus)
                    assertTrue(menu.width >= ceil(48.0 * host.resources.displayMetrics.density).toInt())
                    // This point is above the old 50% target, so parent dispatch must follow the moved real target.
                    val x = controls.left + menu.x + menu.width / 2f
                    val y = controls.top + menu.y + 1f
                    assertTrue(y < (foreground.height - menu.height) / 2f)
                    val downTime = android.os.SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(downTime, downTime, action, x, y, 0)
                        try { assertTrue(foreground.dispatchTouchEvent(event)) } finally { event.recycle() }
                    }
                }
                awaitActivityAction(scenario) { }
                assertEquals(1, menuClicks.get())
                clock.addAndGet(-80L)
                awaitLegacyVsyncs(2)
                awaitActivityAction(scenario) { assertLegacyPosition(host, 0.08, true) }
                var heldReads = 0
                awaitActivityAction(scenario) { host.setRuntimeVisible(false); heldReads = reads.get() }
                clock.addAndGet(100_000L)
                awaitLegacyVsyncs(3)
                awaitActivityAction(scenario) {
                    assertEquals(heldReads, reads.get())
                    assertLegacyPosition(host, 0.08, true)
                    host.setRuntimeVisible(true)
                }
                awaitLegacyVsyncs(2)
                awaitActivityAction(scenario) { assertLegacyPosition(host, 0.08, true) }
                clock.addAndGet(80L)
                awaitLegacyVsyncs(2)
                awaitActivityAction(scenario) {
                    assertLegacyPosition(host, 0.16, true)
                    host.updateMatterMotion(11f, -7f, 13f, -9f)
                    host.render(StandbyThemeId.MATTER, state, "Ready", "BROADCAST OFF", "")
                }
                clock.addAndGet(80L)
                awaitLegacyVsyncs(2)
                awaitActivityAction(scenario) {
                    assertLegacyPosition(host, 0.24)
                    val children = (0 until host.childCount).map(host::getChildAt)
                    val matter = children.filterIsInstance<MatterStandbyForegroundView>().single()
                    val particles = children.filterIsInstance<MatterParticleView>().single()
                    assertEquals(13f, matter.motionLayer.translationX, 0f)
                    assertEquals(-9f, matter.motionLayer.translationY, 0f)
                    assertEquals(11f, particles.particleLayerOffsetXPx, 0f)
                    assertEquals(-7f, particles.particleLayerOffsetYPx, 0f)
                    host.render(StandbyThemeId.LANDSCAPE, state, "Ready", "BROADCAST OFF", "")
                }
                clock.addAndGet(80L)
                awaitLegacyVsyncs(2)
                awaitActivityAction(scenario) {
                    assertLegacyPosition(host, 0.32)
                    host.render(StandbyThemeId.HARDLINE, state, "Ready", "BROADCAST OFF", "")
                }
                awaitLegacyVsyncs(2)
                awaitActivityAction(scenario) {
                    assertLegacyPosition(host, 0.32, true)
                    host.setMediaVisible(true)
                }
                clock.addAndGet(80L)
                awaitLegacyVsyncs(2)
                awaitActivityAction(scenario) {
                    assertLegacyPosition(host, 0.40, false)
                    assertEquals(View.GONE, (0 until host.childCount).map(host::getChildAt).filterIsInstance<HardlineSceneView>().single().visibility)
                    host.setMediaVisible(false)
                    host.render(StandbyThemeId.HARDLINE, state.copy(phase = ScreenPhase.FROZEN), "Unavailable", "FROZEN", "")
                }
                clock.addAndGet(80L)
                awaitLegacyVsyncs(2)
                awaitActivityAction(scenario) {
                    assertLegacyPosition(host, 0.48, true)
                    host.render(StandbyThemeId.HARDLINE, state.copy(phase = ScreenPhase.CONNECTED, isProjecting = true), "Connected", "", "")
                    host.setRuntimeVisible(false)
                }
                awaitLegacyVsyncs(2)
                awaitActivityAction(scenario) { assertLegacyPosition(host, 0.48, false) }
            } finally {
                awaitActivityAction(scenario) { activity -> owned?.let { it.setRuntimeVisible(false); activity.contentRoot.removeView(it) } }
            }
        }
    }

    /** Hidden, zero-size and retired hosts never consume hidden time or revive an old callback. */
    @Test
    fun legacyAmbient_visibilityWindowDetachAndRetiredCallbacksDoNotCatchUp() {
        val clock = AtomicLong(1000L)
        val reads = AtomicInteger()
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var host: BigStandbyHost
            lateinit var parent: FrameLayout
            var owned: FrameLayout? = null
            var ownedHost: BigStandbyHost? = null
            var retiredCallback: Choreographer.FrameCallback? = null
            try {
                scenario.onActivity { activity ->
                    parent = FrameLayout(activity)
                    owned = parent
                    host = BigStandbyHost(activity, StandbyForegroundCallbacks(onToggleBroadcast = {}, onOpenSettings = {}))
                    ownedHost = host
                    host.clockUptimeMillis = { reads.incrementAndGet(); clock.get() }
                    host.render(StandbyThemeId.HARDLINE,
                        StandbyForegroundState(ScreenPhase.READY, false, isProjecting = false), "Ready", "OFF", "")
                    host.setRuntimeVisible(true)
                    parent.addView(host, FrameLayout.LayoutParams(-1, -1))
                    activity.contentRoot.addView(parent, FrameLayout.LayoutParams(-1, -1))
                }
                awaitForegroundLayout(scenario, host)
                awaitLegacyVsyncs(2)
                clock.addAndGet(80L)
                awaitLegacyVsyncs(2)
                var heldReads = 0
                scenario.onActivity {
                    assertLegacyPosition(host, 0.08, true)
                    val retired = requireNotNull(legacyCallback(host))
                    retiredCallback = retired
                    parent.visibility = View.GONE
                    assertNull(legacyCallback(host))
                    heldReads = reads.get()
                    retired.doFrame(0L)
                    assertEquals(heldReads, reads.get())
                    assertNull(legacyCallback(host))
                }
                clock.addAndGet(100_000L)
                awaitLegacyVsyncs(3)
                scenario.onActivity {
                    assertEquals(heldReads, reads.get())
                    assertLegacyPosition(host, 0.08, true)
                    parent.visibility = View.VISIBLE
                }
                awaitLegacyVsyncs(2)
                scenario.onActivity {
                    assertLegacyPosition(host, 0.08, true)
                    val current = requireNotNull(legacyCallback(host))
                    val activeReads = reads.get()
                    requireNotNull(retiredCallback).doFrame(0L)
                    assertSame(current, legacyCallback(host))
                    assertEquals(activeReads, reads.get())
                    host.visibility = View.INVISIBLE
                    heldReads = reads.get()
                }
                clock.addAndGet(100_000L)
                awaitLegacyVsyncs(2)
                scenario.onActivity {
                    assertEquals(heldReads, reads.get())
                    host.visibility = View.VISIBLE
                }
                awaitLegacyVsyncs(2)
                scenario.onActivity {
                    assertLegacyPosition(host, 0.08, true)
                    parent.removeView(host)
                    heldReads = reads.get()
                    assertNull(legacyCallback(host))
                    host.setRuntimeVisible(true)
                }
                clock.addAndGet(100_000L)
                awaitLegacyVsyncs(2)
                scenario.onActivity {
                    assertEquals(heldReads, reads.get())
                    parent.addView(host, FrameLayout.LayoutParams(-1, -1))
                }
                awaitLegacyVsyncs(3)
                scenario.onActivity {
                    assertLegacyPosition(host, 0.08, true)
                    host.layoutParams = FrameLayout.LayoutParams(0, 0)
                }
                awaitLegacyVsyncs(3)
                scenario.onActivity {
                    assertEquals(0, host.width)
                    assertEquals(0, host.height)
                    assertNull(legacyCallback(host))
                    heldReads = reads.get()
                }
                clock.addAndGet(100_000L)
                awaitLegacyVsyncs(2)
                scenario.onActivity {
                    assertEquals(heldReads, reads.get())
                    host.layoutParams = FrameLayout.LayoutParams(-1, -1)
                }
                awaitLegacyVsyncs(3)
                scenario.onActivity { assertLegacyPosition(host, 0.08, true) }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.onActivity {
                    assertTrue(host.windowVisibility != View.VISIBLE)
                    assertNull(legacyCallback(host))
                    heldReads = reads.get()
                    host.setRuntimeVisible(false)
                }
                clock.addAndGet(100_000L)
                awaitLegacyVsyncs(2)
                scenario.onActivity { assertEquals(heldReads, reads.get()) }
                scenario.moveToState(Lifecycle.State.RESUMED)
                awaitLegacyVsyncs(2)
                scenario.onActivity {
                    assertEquals(heldReads, reads.get())
                    assertLegacyPosition(host, 0.08, true)
                    host.setRuntimeVisible(true)
                }
                awaitLegacyVsyncs(2)
                scenario.onActivity { assertLegacyPosition(host, 0.08, true) }
                clock.addAndGet(80L)
                awaitLegacyVsyncs(2)
                scenario.onActivity { assertLegacyPosition(host, 0.16, true) }
            } finally {
                scenario.onActivity { activity ->
                    ownedHost?.setRuntimeVisible(false)
                    owned?.let { activity.contentRoot.removeView(it) }
                }
            }
        }
    }

    /** Unknown initial state stays unknown; failure-only changes use the same atomic cached presentation path. */
    @Test
    fun hardlineStatus_forwardsAtomicFailureOnlyUpdatesWithoutInventingInitialPhase() {
        val clock = AtomicLong(1000L)
        val reads = AtomicInteger()
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var host: BigStandbyHost
            lateinit var scene: HardlineSceneView
            lateinit var model: RogHardlineScene
            var ownedHost: BigStandbyHost? = null
            try {
                scenario.onActivity { activity ->
                    host = BigStandbyHost(activity, StandbyForegroundCallbacks())
                    ownedHost = host
                    host.clockUptimeMillis = clock::get
                    scene = (0 until host.childCount).map(host::getChildAt).filterIsInstance<HardlineSceneView>().single()
                    scene.clockUptimeMillis = { reads.incrementAndGet(); clock.get() }
                    model = hardlineStatusModel(scene)
                    host.setRuntimeVisible(true)
                    host.setMediaVisible(true)
                    assertNull(model.statusText)
                    assertNull(RogHardlineScene::class.java.getDeclaredField("statusTrack").apply { isAccessible = true }.get(model))
                    host.setMediaVisible(false)
                    activity.contentRoot.addView(host, FrameLayout.LayoutParams(-1, -1))
                }
                awaitForegroundLayout(scenario, host)
                val name = "  <Host & \"TV\" / 客廳>  "
                scenario.onActivity {
                    val before = reads.get()
                    host.render(StandbyThemeId.HARDLINE, StandbyForegroundState(ScreenPhase.ADVERTISING, false,
                        receiverName = name, copyMotionAllowed = false, isProjecting = false), "Ready", "OFF", "")
                    assertEquals(before + 1, reads.get())
                    assertEquals("Windows: press Win + K · Choose $name", model.statusText)
                    assertEquals(1.0, hardlineStatusValue(model, "WAITING", "alpha"), 0.0)
                }
                awaitForegroundLayout(scenario, host, scene)
                scenario.onActivity {
                    clock.set(1060L)
                    val before = reads.get()
                    host.updateFailure(PrePlayFailureCause.PRE_PLAY_TIMEOUT, "Timeout")
                    assertEquals(before + 1, reads.get())
                    assertTrue(model.statusAlert)
                    assertEquals("Connection failed · Press Win + K to retry", model.statusText)
                    assertEquals(0.06, hardlineStatusValue(model, "WAITING", "elapsedSeconds"), 1e-9)
                    val cached = BigStandbyHost::class.java.getDeclaredField("presentationState").apply { isAccessible = true }
                        .get(host) as StandbyForegroundState
                    assertEquals(PrePlayFailureCause.PRE_PLAY_TIMEOUT, cached.failureCause)
                    assertEquals("Timeout", cached.failureText)
                    clock.set(1100L)
                    host.setMediaVisible(true)
                    assertEquals(View.GONE, scene.visibility)
                    assertEquals(RogHardlineScene.StatusMode.MEDIA_PAUSED, hardlineStatusMode(model))
                    assertEquals(0.04, hardlineStatusValue(model, "FAILURE", "elapsedSeconds"), 1e-9)
                    clock.set(1140L)
                    host.updateFailure(null, "")
                    assertEquals("Windows: press Win + K · Choose $name", model.statusText)
                    assertFalse(model.statusAlert)
                    clock.set(1540L)
                    host.setMediaVisible(false)
                    assertEquals(1.0, hardlineStatusValue(model, "WAITING", "alpha"), 0.0)
                    assertEquals(0.06, hardlineStatusValue(model, "WAITING", "elapsedSeconds"), 1e-9)
                    assertEquals(0.04, hardlineStatusValue(model, "FAILURE", "elapsedSeconds"), 1e-9)
                    val refreshed = BigStandbyHost::class.java.getDeclaredField("presentationState").apply { isAccessible = true }
                        .get(host) as StandbyForegroundState
                    assertNull(refreshed.failureCause)
                    assertEquals("", refreshed.failureText)
                }
            } finally {
                scenario.onActivity { activity -> ownedHost?.let { it.setRuntimeVisible(false); activity.contentRoot.removeView(it) } }
            }
        }
    }

    /** Removing D resets only status; direct stop, media, ancestor and actual Host projection remain separate facts. */
    @Test
    fun hardlineStatus_themeResetRetainsSimulationAndOverlappingHostGates() {
        val clock = AtomicLong(1000L)
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var host: BigStandbyHost
            lateinit var parent: FrameLayout
            lateinit var scene: HardlineSceneView
            lateinit var model: RogHardlineScene
            var ownedHost: BigStandbyHost? = null
            var ownedParent: FrameLayout? = null
            val ready = StandbyForegroundState(ScreenPhase.READY, false, receiverName = "Studio", isProjecting = false)
            try {
                scenario.onActivity { activity ->
                    parent = FrameLayout(activity)
                    ownedParent = parent
                    host = BigStandbyHost(activity, StandbyForegroundCallbacks())
                    ownedHost = host
                    host.clockUptimeMillis = clock::get
                    scene = (0 until host.childCount).map(host::getChildAt).filterIsInstance<HardlineSceneView>().single()
                    scene.clockUptimeMillis = clock::get
                    model = hardlineStatusModel(scene)
                    host.render(StandbyThemeId.HARDLINE, ready, "Ready", "OFF", "")
                    host.setRuntimeVisible(true)
                    parent.addView(host, FrameLayout.LayoutParams(-1, -1))
                    activity.contentRoot.addView(parent, FrameLayout.LayoutParams(-1, -1))
                }
                awaitForegroundLayout(scenario, host, scene)
                scenario.onActivity {
                    fun deliver(time: Long) {
                        clock.set(time)
                        val callback = HardlineSceneView::class.java.getDeclaredField("frameCallback").apply { isAccessible = true }
                            .get(scene) as Choreographer.FrameCallback
                        Choreographer.getInstance().removeFrameCallback(callback)
                        callback.doFrame(0L)
                        val legacy = requireNotNull(legacyCallback(host))
                        Choreographer.getInstance().removeFrameCallback(legacy)
                        legacy.doFrame(0L)
                    }
                    deliver(1000L)
                    deliver(1040L)
                    val physics = scene.animationElapsedSeconds
                    val traces = model.streams.map { it.copy() }
                    val legacyField = BigStandbyHost::class.java.getDeclaredField("legacyElapsedSeconds").apply { isAccessible = true }
                    assertEquals(0.04, legacyField.getDouble(host), 1e-9)
                    val children = (0 until host.childCount).map(host::getChildAt)
                    clock.set(1060L)
                    host.render(StandbyThemeId.LANDSCAPE, ready, "Ready", "OFF", "")
                    assertEquals(RogHardlineScene.StatusMode.REMOVED, hardlineStatusMode(model))
                    for (track in listOf("READY", "ARMING", "WIFI_PAUSED", "FROZEN", "WAITING", "CONNECTING", "FAILURE", "PROJECTING")) {
                        assertEquals(0.0, hardlineStatusValue(model, track, "elapsedSeconds"), 0.0)
                    }
                    clock.set(5060L)
                    host.render(StandbyThemeId.HARDLINE, ready.copy(phase = ScreenPhase.FROZEN), "Unavailable", "FROZEN", "")
                    assertSame(model, hardlineStatusModel(scene))
                    assertEquals(1.0, hardlineStatusValue(model, "FROZEN", "alpha"), 0.0)
                    assertEquals(0.0, hardlineStatusValue(model, "FROZEN", "elapsedSeconds"), 0.0)
                    assertEquals(physics, scene.animationElapsedSeconds, 0.0)
                    assertEquals(traces, model.streams)
                    assertEquals(0.04, legacyField.getDouble(host), 1e-9)
                    children.forEachIndexed { index, child -> assertSame(child, host.getChildAt(index)) }
                    clock.set(5160L)
                    host.setRuntimeVisible(false)
                    host.setMediaVisible(true)
                    scene.setProjecting(true)
                    host.setRuntimeVisible(true)
                    scene.setProjecting(false)
                    assertEquals(RogHardlineScene.StatusMode.MEDIA_PAUSED, hardlineStatusMode(model))
                    assertFalse(scene.animationFrameScheduled)
                    parent.visibility = View.GONE
                    host.setMediaVisible(false)
                    assertEquals(RogHardlineScene.StatusMode.STOPPED, hardlineStatusMode(model))
                    assertFalse(scene.animationFrameScheduled)
                    clock.set(6000L)
                    parent.visibility = View.VISIBLE
                    assertEquals(0.1, hardlineStatusValue(model, "FROZEN", "elapsedSeconds"), 1e-9)
                    host.render(StandbyThemeId.HARDLINE, ready.copy(phase = ScreenPhase.CONNECTED, isProjecting = null), "Connected", "", "")
                    assertNull(model.statusText)
                    assertEquals(RogHardlineScene.StatusMode.STOPPED, hardlineStatusMode(model))
                    assertTrue("Actual projection stops status, not the old non-status Host scheduling policy", scene.animationFrameScheduled)
                    scene.setProjecting(true)
                    assertFalse(scene.animationFrameScheduled)
                    scene.setProjecting(false)
                    assertEquals(RogHardlineScene.StatusMode.STOPPED, hardlineStatusMode(model))
                    assertTrue(scene.animationFrameScheduled)
                }
            } finally {
                scenario.onActivity { activity ->
                    ownedHost?.setRuntimeVisible(false)
                    ownedParent?.let { activity.contentRoot.removeView(it) }
                }
            }
        }
    }

    private fun hardlineStatusModel(view: HardlineSceneView): RogHardlineScene =
        HardlineSceneView::class.java.getDeclaredField("rogScene").apply { isAccessible = true }.get(view) as RogHardlineScene

    private fun hardlineStatusMode(model: RogHardlineScene): RogHardlineScene.StatusMode =
        RogHardlineScene::class.java.getDeclaredField("statusMode").apply { isAccessible = true }.get(model) as RogHardlineScene.StatusMode

    /** Adapter evidence only; independent Canvas alpha/geometry assertions live in HardlineSceneViewTest. */
    private fun hardlineStatusValue(model: RogHardlineScene, track: String, property: String): Double {
        val layers = RogHardlineScene::class.java.getDeclaredField("statusLayers").apply { isAccessible = true }.get(model) as Array<*>
        val layer = requireNotNull(layers.single {
            val value = requireNotNull(it)
            (value.javaClass.getDeclaredField("track").apply { isAccessible = true }.get(value) as Enum<*>).name == track
        })
        return layer.javaClass.getDeclaredField(property).apply { isAccessible = true }.getDouble(layer)
    }

    /** Checks wiring against already-tested burn math, not another ambient implementation. */
    private fun assertLegacyPosition(host: BigStandbyHost, seconds: Double, handleTracking: Boolean? = null) {
        val burn = StandbyOledDriftMath.burnOffsets(seconds)
        fun fixed(value: Double): Double = BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toDouble()
        val density = host.resources.displayMetrics.density.toDouble()
        val children = (0 until host.childCount).map(host::getChildAt)
        val hardline = children.filterIsInstance<HardlineStandbyForegroundView>().single()
        for (foreground in listOf(children.filterIsInstance<LandscapeStandbyForegroundView>().single(), hardline)) {
            assertEquals((fixed(burn.x) * density).toFloat(), foreground.motionLayer.translationX, 0.0001f)
            assertEquals((fixed(burn.y) * density).toFloat(), foreground.motionLayer.translationY, 0.0001f)
        }
        val scene = children.filterIsInstance<HardlineSceneView>().single()
        for ((name, expected) in listOf("legacySvgX" to fixed(burn.x * 1.4), "legacySvgY" to fixed(burn.y * 1.4))) {
            val field = HardlineSceneView::class.java.getDeclaredField(name).apply { isAccessible = true }
            assertEquals(expected, field.getDouble(scene), 0.000001)
        }
        if (handleTracking != null) {
            val menu = requireNotNull(hardline.findViewWithTag<View>(StandbyForegroundTags.MENU))
            val controls = menu.parent as View
            val location = IntArray(2)
            host.getLocationInWindow(location)
            val expected = if (handleTracking) {
                fixed(location[1] / density + host.height / density * 0.47 + burn.y) * density - location[1]
            } else {
                (host.height - menu.height) / 2 + menu.height / 2.0
            }
            assertEquals(expected.toFloat(), controls.top + menu.y + menu.height / 2f, 0.001f)
        }
    }

    /** Reads the existing private callback identity without adding a production diagnostic API. */
    private fun legacyCallback(host: BigStandbyHost): Choreographer.FrameCallback? =
        BigStandbyHost::class.java.getDeclaredField("legacyFrameCallback").let {
            it.isAccessible = true
            it.get(host) as? Choreographer.FrameCallback
        }

    /** Waits for delivery at fixed clock inputs, without wall sleeps or guessed frame durations. */
    private fun awaitLegacyVsyncs(count: Int) {
        repeat(count) {
            val delivered = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                Choreographer.getInstance().postFrameCallback { delivered.countDown() }
            }
            assertTrue("No native vsync callback arrived", delivered.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun hardlineHeaderUsesSliceGeometryAndNonScalingCssStroke() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertNull(hardlineHeaderLowerPx(0, 1000))
            assertNull(hardlineHeaderLowerPx(1600, 0))
            assertEquals(69f, requireNotNull(hardlineHeaderLowerPx(1600, 1000)), 0.001f)
            assertEquals(66.28f, requireNotNull(hardlineHeaderLowerPx(540, 960)), 0.001f)
            assertEquals(-31f, requireNotNull(hardlineHeaderLowerPx(1600, 800)), 0.001f)
            assertEquals(88.54f, requireNotNull(hardlineHeaderLowerPx(800, 1280, 1.5f)), 0.001f)
            val small = (requireNotNull(hardlineHeaderLowerPx(640, 360)) - 1f) / 360f
            val large = (requireNotNull(hardlineHeaderLowerPx(3840, 2160)) - 1f) / 2160f
            assertEquals(small, large, 0.0001f)
        }
    }

    @Test
    fun safeInsetsReplaceRatherThanAccumulateAcrossForegrounds() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val host = BigStandbyHost(context, StandbyForegroundCallbacks())
            val foregrounds = (0 until host.childCount).map(host::getChildAt).filterIsInstance<StandbyForegroundView>()
            assertEquals(3, foregrounds.size)
            val bases = foregrounds.map { listOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
            host.applySafeInsets(Insets.of(11, 17, 3, 5))
            host.applySafeInsets(Insets.of(4, 6, 8, 10))
            foregrounds.forEachIndexed { index, view ->
                assertEquals(bases[index][0] + 4, view.paddingLeft)
                assertEquals(bases[index][1] + 6, view.paddingTop)
                assertEquals(bases[index][2] + 8, view.paddingRight)
                assertEquals(bases[index][3] + 10, view.paddingBottom)
            }
        }
    }

    @Test
    fun attachedHostFillsTheMeasuredViewportAndRetainsChildren() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var host: BigStandbyHost
            lateinit var originalUnderlay: View
            scenario.onActivity { activity ->
                host = BigStandbyHost(activity, StandbyForegroundCallbacks())
                originalUnderlay = host.mediaUnderlay
                activity.contentRoot.addView(
                    host,
                    FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
                )
            }
            me.trinitrix.mirax.ui.standby.foreground.awaitForegroundLayout(scenario, host)
            scenario.onActivity { activity ->
                assertTrue(host.width > 0 && host.height > 0)
                assertEquals(activity.contentRoot.width, host.width)
                assertEquals(activity.contentRoot.height, host.height)
                activity.contentRoot.removeView(host)
                activity.contentRoot.addView(host)
                assertSame(originalUnderlay, host.mediaUnderlay)
                assertSame(host, originalUnderlay.parent)
            }
        }
    }

    @Test
    fun themeAndMediaChangesRetainScenesAndForegrounds() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val host = BigStandbyHost(context, StandbyForegroundCallbacks())
            val original = (0 until host.childCount).map(host::getChildAt)
            val scenes = listOf(
                original.filterIsInstance<LandscapeSceneView>().single(),
                original.filterIsInstance<MatterParticleView>().single(),
                original.filterIsInstance<HardlineSceneView>().single(),
            )
            val foregrounds = listOf(
                original.filterIsInstance<LandscapeStandbyForegroundView>().single(),
                original.filterIsInstance<MatterStandbyForegroundView>().single(),
                original.filterIsInstance<HardlineStandbyForegroundView>().single(),
            )
            val state = StandbyForegroundState(ScreenPhase.READY, advertisingEnabled = false)
            val themes = listOf(StandbyThemeId.LANDSCAPE, StandbyThemeId.MATTER, StandbyThemeId.HARDLINE)
            host.setRuntimeVisible(true)
            themes.forEachIndexed { selected, theme ->
                host.render(theme, state, "Ready", "Standby", "")
                scenes.forEachIndexed { index, view ->
                    assertEquals(if (index == selected) View.VISIBLE else View.GONE, view.visibility)
                }
                foregrounds.forEachIndexed { index, view ->
                    assertEquals(if (index == selected) View.VISIBLE else View.GONE, view.visibility)
                }
                host.setMediaVisible(true)
                scenes.forEach { assertEquals(View.GONE, it.visibility) }
                assertEquals(View.VISIBLE, foregrounds[selected].visibility)
                host.setMediaVisible(false)
                assertEquals(View.VISIBLE, scenes[selected].visibility)
            }
            assertEquals(original.size, host.childCount)
            original.forEachIndexed { index, view -> assertSame(view, host.getChildAt(index)) }
            host.setRuntimeVisible(false)
        }
    }

    @Test
    fun missingThemeKeepsNavigationAvailableWithoutSelectingAnUnconfirmedTheme() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            var menuClicks = 0
            val host = BigStandbyHost(context, StandbyForegroundCallbacks(onOpenSettings = { menuClicks++ }))
            val fallback = requireNotNull(host.findViewWithTag<View>("standby-theme-unavailable-menu"))
            host.applySafeInsets(Insets.of(11, 17, 3, 5))
            val params = fallback.layoutParams as FrameLayout.LayoutParams
            assertEquals(11, params.leftMargin)
            assertEquals(17, params.topMargin)
            assertEquals(View.VISIBLE, fallback.visibility)
            assertTrue(fallback.performClick())
            assertEquals(1, menuClicks)
            host.render(StandbyThemeId.MATTER, StandbyForegroundState(ScreenPhase.READY, false), "", "", "")
            assertEquals(View.GONE, fallback.visibility)
        }
    }

    @Test
    fun clockFailureAndPreviewUpdatesKeepTheSameViews() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val host = BigStandbyHost(context, StandbyForegroundCallbacks(onDismissFailure = {}))
            host.render(
                StandbyThemeId.MATTER,
                StandbyForegroundState(ScreenPhase.READY, false, clockText = "12:34"),
                "Ready", "Standby", "",
            )
            val matter = (0 until host.childCount).map(host::getChildAt)
                .filterIsInstance<MatterStandbyForegroundView>().single()
            val clock = requireNotNull(matter.findViewWithTag<TextView>(StandbyForegroundTags.CLOCK))
            val failure = requireNotNull(matter.findViewWithTag<TextView>(StandbyForegroundTags.FAILED))
            host.updateClock("12:35")
            host.updateFailure(PrePlayFailureCause.PRE_PLAY_TIMEOUT, "Timeout")
            assertEquals("12:35", clock.text.toString())
            assertEquals("Timeout", failure.text.toString())
            assertSame(clock, matter.findViewWithTag<TextView>(StandbyForegroundTags.CLOCK))
            assertSame(failure, matter.findViewWithTag<TextView>(StandbyForegroundTags.FAILED))

            val preview = host.takeMediaPreview()
            val editorViewport = FrameLayout(context)
            editorViewport.addView(preview)
            host.restoreMediaPreview()
            assertSame(host.mediaUnderlay, preview)
            assertSame(host, preview.parent)
            assertSame(preview, host.getChildAt(0))
        }
    }

    /** Characterizes owned-window inputs and full Host metadata without visual acceptance gates. */
    @Test
    fun surfaceVideo_characterizesSpatialFramingAndHostReadability() {
        check(Looper.myLooper() != Looper.getMainLooper())
        checkSpatialCleanupSelfTest()
        val cleanup = SpatialFixtureCleanup()
        val observer = SpatialStateObserver()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var evidenceDir: File? = null
        var storageDir: File? = null
        var metadataJob: Job? = null
        var scenario: ActivityScenario<StandbyProbeActivity>? = null
        var hostView: BigStandbyHost? = null
        var underlayView: StandbyMediaUnderlayView? = null
        var previewContainer: FrameLayout? = null
        var driver: AndroidStandbyMediaDriver? = null
        var controller: StandbyMediaPlaybackController? = null
        var scenarioLaunchStarted = false
        var nativeConstructionStarted = false
        var bodyComplete = false
        val controlCalls = LinkedBlockingQueue<String>()
        try {
            val external = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)) {
                "External files storage is required for persistent window evidence"
            }
            val evidence = Files.createTempDirectory(external.toPath(), "standby-spatial-evidence-").toFile()
            evidenceDir = evidence
            Log.i(LOG_TAG, "CHARACTERIZATION_NOT_VISUAL_ACCEPTANCE evidenceDir=${evidence.absolutePath}")
            val storage = Files.createTempDirectory(
                instrumentation.targetContext.cacheDir.toPath(), "standby-driver-spatial-",
            ).toFile()
            storageDir = storage
            Log.i(LOG_TAG, "Owned spatial storageDir=${storage.absolutePath}")
            File(evidence, "fixture.json").writeText(JSONObject()
                .put("classification", "CHARACTERIZATION_NOT_VISUAL_ACCEPTANCE")
                .put("evidence_directory", evidence.absolutePath)
                .put("owned_storage_directory", storage.absolutePath).toString(2))
            val fixtureFile = File(storage, "fixture.mp4")
            instrumentation.context.assets.open("standby-spatial-320x240.mp4").use { input ->
                FileOutputStream(fixtureFile).use { output -> input.copyTo(output) }
            }
            val bytes = fixtureFile.readBytes()
            check(bytes.size == 2654) { "Unexpected fixture length: ${bytes.size}" }
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(sha == SPATIAL_ASSET_SHA256) { "Unexpected fixture SHA-256: $sha" }
            val job = SupervisorJob()
            metadataJob = job
            val preferences = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) {
                File(storage, "metadata.preferences_pb")
            }
            val store = StandbyMediaStore(preferences, AndroidStandbyMediaValidator(), storage)
            scenarioLaunchStarted = true
            val currentScenario = ActivityScenario.launch(StandbyProbeActivity::class.java)
            scenario = currentScenario
            var activityRef: StandbyProbeActivity? = null
            awaitActivityAction(currentScenario) { activity ->
                activityRef = activity
                // Keep original controls present; callbacks record fixture-local requests only.
                val host = BigStandbyHost(activity, StandbyForegroundCallbacks(
                    onToggleBroadcast = { controlCalls.offer("toggle_broadcast") },
                    onOpenSettings = { controlCalls.offer("open_settings") },
                ))
                hostView = host
                underlayView = host.mediaUnderlay
                activity.contentRoot.addView(host, FrameLayout.LayoutParams(-1, -1))
                nativeConstructionStarted = true
                val platform = AndroidStandbyMediaDriver(activity, storage, store)
                driver = platform
                val playback = StandbyMediaPlaybackController(
                    platform,
                    onError = { _, _, cause -> observer.onError(cause) },
                    onStateChanged = observer::onStateChanged,
                )
                controller = playback
                platform.bindUnderlay(host.mediaUnderlay)
                playback.attach(fixtureFile, isVideo = true, isAnimated = false)
            }
            val activity = checkNotNull(activityRef)
            val host = checkNotNull(hostView)
            val underlay = checkNotNull(underlayView)
            val platform = checkNotNull(driver)
            val playback = checkNotNull(controller)
            observer.awaitState(StandbyPlaybackState.PLAYING)
            val container = onUi {
                val owned = FrameLayout(activity)
                previewContainer = owned
                host.takeMediaPreview()
                host.visibility = View.GONE
                activity.contentRoot.addView(owned, FrameLayout.LayoutParams(-1, -1))
                owned
            }
            for (vector in spatialVectors) {
                observer.throwIfFailed()
                onUi {
                    val params = FrameLayout.LayoutParams(vector.vpW, vector.vpH, Gravity.TOP or Gravity.LEFT)
                    if (underlay.parent == null) container.addView(underlay, params) else underlay.layoutParams = params
                    underlay.setFraming(vector.framing)
                }
                val witness = awaitWindowSettledAndWitness(activity, underlay, platform, playback, observer, cleanup)
                check(witness.viewportBounds.width == vector.vpW && witness.viewportBounds.height == vector.vpH)
                val png = File(evidence, "spatial-vector-${vector.id}.png")
                val sidecar = JSONObject()
                    .put("classification", "CHARACTERIZATION_NOT_VISUAL_ACCEPTANCE")
                    .put("full_window_png", png.name)
                    .put("EXPECTED", vector.expectedJson())
                    .put("OBSERVED", witness.json())
                    .put("capture_integrity", "pending")
                    .put("not_measured", UNMEASURED)
                val json = File(evidence, "spatial-vector-${vector.id}.json")
                json.writeText(sidecar.toString(2))
                val bitmap = captureWindowBitmap(witness.window, cleanup)
                try {
                    savePng(bitmap, png)
                    onUi { verifyVideoWindowUnchanged(activity, underlay, platform, playback, witness, observer) }
                    check(bitmap.width == witness.windowBounds.width && bitmap.height == witness.windowBounds.height)
                    val samples = JSONArray()
                    for (core in spatialCores) {
                        val destination = vector.projectCore(core.bounds) ?: continue
                        val absolute = destination.offset(witness.viewportBounds.left, witness.viewportBounds.top)
                        absolute.requireInside(witness.viewportBounds)
                        samples.put(JSONObject().put("core", core.name)
                            .put("destination_bounds_in_window", absolute.json())
                            .put("OBSERVED_RGB", sampleRegion(bitmap, absolute).json()))
                    }
                    observer.throwIfFailed()
                    sidecar.put("capture_integrity", "owned_window_unchanged")
                        .put("OBSERVED_samples", samples)
                    json.writeText(sidecar.toString(2))
                } catch (error: Throwable) {
                    cleanup.record(error)
                    throw error
                } finally {
                    cleanup.attempt { bitmap.recycle() }
                }
            }
            val hostFraming = StandbyFraming.CENTERED
            onUi {
                container.removeView(underlay)
                activity.contentRoot.removeView(container)
                host.restoreMediaPreview()
                underlay.setFraming(hostFraming)
                host.visibility = View.VISIBLE
                host.setMediaVisible(true)
            }
            for (theme in listOf(StandbyThemeId.LANDSCAPE, StandbyThemeId.MATTER, StandbyThemeId.HARDLINE)) {
                observer.throwIfFailed()
                onUi {
                    host.render(theme, StandbyForegroundState(
                        ScreenPhase.READY, advertisingEnabled = false, clockText = "12:34", receiverName = "Mirax Host",
                    ), "Ready", "Standby", "Connected")
                }
                val witness = awaitWindowSettledAndWitness(activity, underlay, platform, playback, observer, cleanup)
                val metadata = onUi {
                    verifyVideoWindowUnchanged(activity, underlay, platform, playback, witness, observer)
                    snapshotTheme(host, theme)
                }
                val png = File(evidence, "host-composition-${theme.name.lowercase()}.png")
                val sidecar = JSONObject().put("classification", "CHARACTERIZATION_NOT_VISUAL_ACCEPTANCE")
                    .put("theme", theme.name).put("full_window_png", png.name)
                    .put("EXPECTED_requested_framing", framingJson(hostFraming))
                    .put("OBSERVED", witness.json()).put("OBSERVED_host", metadata.json())
                    .put("control_callbacks_observed", JSONArray(controlCalls.toList()))
                    .put("capture_integrity", "pending").put("not_measured", UNMEASURED)
                val json = File(evidence, "host-composition-${theme.name.lowercase()}.json")
                json.writeText(sidecar.toString(2))
                val bitmap = captureWindowBitmap(witness.window, cleanup)
                try {
                    savePng(bitmap, png)
                    onUi {
                        verifyVideoWindowUnchanged(activity, underlay, platform, playback, witness, observer)
                        check(snapshotTheme(host, theme) == metadata) { "Foreground metadata changed during capture" }
                    }
                    check(bitmap.width == witness.windowBounds.width && bitmap.height == witness.windowBounds.height)
                    observer.throwIfFailed()
                    sidecar.put("capture_integrity", "owned_window_and_host_unchanged")
                    json.writeText(sidecar.toString(2))
                } catch (error: Throwable) {
                    cleanup.record(error)
                    throw error
                } finally {
                    cleanup.attempt { bitmap.recycle() }
                }
            }
            observer.throwIfFailed()
            bodyComplete = true
        } catch (error: Throwable) {
            cleanup.record(error)
        } finally {
            try {
                cleanup.attempt { observer.transferTo(cleanup) }
                val released = cleanup.attempt {
                    val state = onUi {
                        val current = controller
                        if (current != null) {
                            current.release()
                            current.state
                        } else {
                            val platform = driver
                            check(platform != null || !nativeConstructionStarted) { "Native construction has no handle" }
                            if (platform != null) check(platform.release()) { "Partial driver release remains pending" }
                            null
                        }
                    }
                    if (state != null && state != StandbyPlaybackState.RELEASED) {
                        observer.awaitState(StandbyPlaybackState.RELEASED, cleanup = true)
                    }
                    onUi { controller?.let { check(it.state == StandbyPlaybackState.RELEASED) } }
                }
                val nativeClosed = cleanup.attempt {
                    check(onUi {
                        val platform = driver
                        cleanup.nativeClosureKnown(nativeConstructionStarted, platform != null, platform?.isVideoNativeClosed == true)
                    }) { "Native video closure is unproven" }
                }
                val unbound = cleanup.attempt { onUi { driver?.bindUnderlay(null) } }
                val underlayRemoved = cleanup.attempt { onUi { retireView(underlayView) } }
                val containerRemoved = cleanup.attempt { onUi { retireView(previewContainer) } }
                val hostRemoved = cleanup.attempt { onUi { retireView(hostView) } }
                val scenarioClosed = cleanup.attempt {
                    val current = scenario
                    if (current != null) current.close() else check(!scenarioLaunchStarted) { "Activity launch has no closable handle" }
                }
                val metadataCancelled = cleanup.attempt { metadataJob?.cancel() }
                val metadataJoined = cleanup.attempt {
                    metadataJob?.let { job -> runBlocking { withTimeout(5_000L) { job.join() } } }
                }
                cleanup.attempt { onUi { observer.transferTo(cleanup) } }
                val closuresClean = released && nativeClosed && unbound && underlayRemoved && containerRemoved &&
                    hostRemoved && scenarioClosed && metadataCancelled && metadataJoined
                cleanup.attempt {
                    evidenceDir?.let { directory ->
                        File(directory, "fixture-result.json").writeText(JSONObject()
                            .put("body_completed", bodyComplete).put("closures_clean", closuresClean)
                            .put("owned_storage_directory", storageDir?.absolutePath ?: JSONObject.NULL)
                            .put("evidence_directory", directory.absolutePath)
                            .put("failure", cleanup.failure?.stackTraceToString() ?: JSONObject.NULL)
                            .put("storage_deletion_eligible", cleanup.permitsDeletion(bodyComplete, closuresClean)).toString(2))
                    }
                }
                cleanup.attempt {
                    if (cleanup.permitsDeletion(bodyComplete, closuresClean)) {
                        storageDir?.let { check(it.deleteRecursively()) { "Failed to delete owned fixture storage" } }
                    }
                }
                if (cleanup.failure != null || !bodyComplete || !closuresClean) {
                    Log.e(LOG_TAG, "Retaining owned storageDir=${storageDir?.absolutePath}; evidenceDir=${evidenceDir?.absolutePath}", cleanup.failure)
                }
            } finally {
                cleanup.restoreInterrupt()
            }
        }
        cleanup.throwIfFailed()
    }

    /** Checks the actual cleanup, transfer and pure sample predicates before native allocation. */
    private fun checkSpatialCleanupSelfTest() {
        val clean = SpatialFixtureCleanup()
        check(clean.permitsDeletion(true, true))
        check(!clean.permitsDeletion(false, true) && !clean.permitsDeletion(true, false))
        check(clean.nativeClosureKnown(false, false, false))
        check(!clean.nativeClosureKnown(true, false, true))
        check(!clean.nativeClosureKnown(true, true, false))
        check(clean.nativeClosureKnown(true, true, true))
        val first = AssertionError("original fixture failure")
        val second = IllegalStateException("cleanup failure")
        val third = IllegalArgumentException("observer failure")
        val fourth = IllegalStateException("later observer failure")
        val failed = SpatialFixtureCleanup()
        failed.record(first)
        failed.record(second)
        failed.record(first)
        failed.record(second)
        val observer = SpatialStateObserver()
        observer.throwIfFailed()
        observer.onError(third)
        observer.onError(fourth)
        observer.onError(third)
        check(runCatching { observer.throwIfFailed() }.exceptionOrNull() === third)
        observer.transferTo(failed)
        observer.transferTo(failed)
        check(failed.failure === first && first.suppressed.toList() == listOf(second, third, fourth))
        check(runCatching { failed.throwIfFailed() }.exceptionOrNull() === first)
        check(!failed.permitsDeletion(true, true))
        val box = PixelBounds(0, 0, 20, 20)
        PixelBounds(1, 1, 2, 2).requireInside(box)
        check(runCatching { PixelBounds(2, 1, 1, 2).requireInside(box) }.isFailure)
        check(runCatching { PixelBounds(-1, 1, 2, 2).requireInside(box) }.isFailure)
        check(spatialVectors[0].projectCore(spatialCores[0].bounds) == PixelBounds(33, 43, 47, 57))
        check(spatialVectors[0].projectCore(spatialCores[1].bounds) == null)
        check(spatialVectors[1].projectCore(spatialCores[1].bounds) == PixelBounds(133, 13, 147, 27))
        check(spatialVectors[2].projectCore(spatialCores[1].bounds) == PixelBounds(10, 17, 30, 36))
    }

    /** Detaches only a recorded test-owned view; never releases a borrowed Surface. */
    private fun retireView(view: View?) {
        view?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            check(it.parent == null) { "Owned view retirement incomplete" }
        }
    }

    /** Finds the current production SurfaceView without retaining its borrowed native Surface. */
    private fun findSurfaceView(root: View): SurfaceView? {
        if (root is SurfaceView) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findSurfaceView(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    /** Copies window coordinates on UI; no mutable Android Rect crosses the thread boundary. */
    private fun boundsInWindow(view: View): PixelBounds {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return PixelBounds(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    /** Takes one typed UI snapshot, including ancestor visibility, transforms and actual clipping. */
    private fun snapshotNode(view: View): NodeSnapshot {
        var cumulativeAlpha = view.alpha
        val parentVisibilities = mutableListOf<Int>()
        var parent: ViewParent? = view.parent
        while (parent is View) {
            cumulativeAlpha *= parent.alpha
            parentVisibilities.add(parent.visibility)
            parent = parent.parent
        }
        val transforms = listOf(view.scaleX, view.scaleY, view.translationX, view.translationY, view.translationZ,
            view.pivotX, view.pivotY, view.rotation, view.rotationX, view.rotationY, view.elevation)
        check(transforms.all { it.isFinite() } && cumulativeAlpha.isFinite() && view.alpha.isFinite())
        val group = view as? ViewGroup
        return NodeSnapshot(
            identity = System.identityHashCode(view).toString(16),
            className = view.javaClass.name,
            parentIdentity = System.identityHashCode(view.parent).toString(16),
            bounds = boundsInWindow(view),
            layoutBounds = PixelBounds(view.left, view.top, view.right, view.bottom),
            clip = view.clipBounds?.let { PixelBounds(it.left, it.top, it.right, it.bottom) },
            clipChildren = group?.clipChildren,
            clipToPadding = group?.clipToPadding,
            alpha = view.alpha,
            cumulativeAlpha = cumulativeAlpha,
            visibility = view.visibility,
            windowVisibility = view.windowVisibility,
            shown = view.isShown,
            attached = view.isAttachedToWindow,
            hardwareAccelerated = view.isHardwareAccelerated,
            parentVisibilities = parentVisibilities.toList(),
            transforms = transforms,
            padding = listOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom),
            children = if (group == null) emptyList() else (0 until group.childCount)
                .map { System.identityHashCode(group.getChildAt(it)).toString(16) },
            backgroundColor = (view.background as? ColorDrawable)?.color,
        )
    }

    /** Requires an actual completed visible layout, ignoring only GONE retained subtrees. */
    private fun isLayoutSettled(view: View): Boolean {
        if (view.visibility == View.GONE) return true
        if (!view.isLaidOut || view.isLayoutRequested || view.isInLayout) return false
        return view !is ViewGroup || (0 until view.childCount).all { isLayoutSettled(view.getChildAt(it)) }
    }

    /** Wait eligibility uses the current rendering contract, not an old queued PLAYING event. */
    private fun isCaptureReady(
        activity: StandbyProbeActivity,
        underlay: StandbyMediaUnderlayView,
        driver: AndroidStandbyMediaDriver,
        controller: StandbyMediaPlaybackController,
        observer: SpatialStateObserver,
    ): Boolean {
        observer.throwIfFailed()
        check(!activity.isDestroyed && !activity.isFinishing &&
            ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) == Stage.RESUMED)
        val output = underlay.currentVideoOutput ?: return false
        val child = findSurfaceView(underlay) ?: return false
        // Alpha is exposed only for the current output after the driver's rendering-start contract.
        return controller.state == StandbyPlaybackState.PLAYING && !driver.isVideoNativeClosed &&
            driver.isCurrentUnderlayBinding(underlay, driver.underlayBindingGeneration) &&
            underlay.ownsVideoOutput(output) && underlay.isVideoOutputGeometryUsable(output) &&
            underlay.isShown && child.isShown && child.alpha == 1f && isLayoutSettled(activity.contentRoot)
    }

    /** Captures actual identities, binding, clip and finite geometry entirely on UI. */
    private fun witnessVideoWindow(
        activity: StandbyProbeActivity,
        underlay: StandbyMediaUnderlayView,
        driver: AndroidStandbyMediaDriver,
        controller: StandbyMediaPlaybackController,
        observer: SpatialStateObserver,
    ): VideoWindowWitness {
        check(isCaptureReady(activity, underlay, driver, controller, observer)) { "Current video output is not capture-ready" }
        val window = activity.window
        val decor = window.decorView
        val token = checkNotNull(decor.windowToken)
        val root = activity.contentRoot
        val parent = checkNotNull(underlay.parent as? ViewGroup)
        val child = checkNotNull(findSurfaceView(underlay))
        val output = checkNotNull(underlay.currentVideoOutput)
        check(underlay.childCount == 1 && underlay.getChildAt(0) === child)
        for (view in listOf(decor, root, parent, underlay, child)) {
            check(view.isAttachedToWindow && view.isShown && view.windowToken === token && view.rootView === decor)
        }
        check(child.isHardwareAccelerated && child.scaleX > 0f && child.scaleY > 0f)
        check(underlay.clipChildren && underlay.clipToPadding &&
            underlay.clipBounds == Rect(0, 0, underlay.width, underlay.height))
        val windowBounds = boundsInWindow(decor)
        check(windowBounds.left == 0 && windowBounds.top == 0)
        val rootBounds = boundsInWindow(root)
        val viewport = boundsInWindow(underlay)
        rootBounds.requireInside(windowBounds)
        viewport.requireInside(rootBounds)
        val size = checkNotNull(underlay.decodedContentSize())
        return VideoWindowWitness(
            window, decor, token, root, parent, child, output, driver.underlayBindingGeneration,
            System.identityHashCode(driver).toString(16), System.identityHashCode(controller).toString(16),
            windowBounds, rootBounds, viewport,
            listOf(decor, root, parent, underlay, child).map(::snapshotNode),
            underlay.currentFraming(), controller.state, size.width, size.height,
        )
    }

    /** Rechecks the same live output/window/binding and all snapshotted geometry after capture. */
    private fun verifyVideoWindowUnchanged(
        activity: StandbyProbeActivity,
        underlay: StandbyMediaUnderlayView,
        driver: AndroidStandbyMediaDriver,
        controller: StandbyMediaPlaybackController,
        witness: VideoWindowWitness,
        observer: SpatialStateObserver,
    ) {
        val current = witnessVideoWindow(activity, underlay, driver, controller, observer)
        check(current.window === witness.window && current.decor === witness.decor && current.token === witness.token &&
            current.root === witness.root && current.parent === witness.parent && current.child === witness.child &&
            current.output === witness.output && current == witness) { "Owned window/output/binding/geometry changed during capture" }
    }

    /** Observes two matching ready preDraws within a deadline; removes its listener on every exit. */
    private fun awaitWindowSettledAndWitness(
        activity: StandbyProbeActivity,
        underlay: StandbyMediaUnderlayView,
        driver: AndroidStandbyMediaDriver,
        controller: StandbyMediaPlaybackController,
        observer: SpatialStateObserver,
        cleanup: SpatialFixtureCleanup,
    ): VideoWindowWitness {
        val completed = CountDownLatch(1)
        val result = AtomicReference<Result<VideoWindowWitness>?>(null)
        var previous: VideoWindowWitness? = null
        var tree: ViewTreeObserver? = null
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            /** Transfers callback failure unchanged instead of throwing into Android traversal. */
            override fun onPreDraw(): Boolean {
                if (result.get() != null) return true
                try {
                    if (isCaptureReady(activity, underlay, driver, controller, observer)) {
                        val current = witnessVideoWindow(activity, underlay, driver, controller, observer)
                        if (current == previous) result.set(Result.success(current))
                        previous = current
                    } else {
                        previous = null
                    }
                } catch (error: Throwable) {
                    result.set(Result.failure(error))
                }
                if (result.get() != null) completed.countDown() else activity.window.decorView.postInvalidateOnAnimation()
                return true
            }
        }
        try {
            onUi {
                val decor = activity.window.decorView
                val current = decor.viewTreeObserver
                tree = current
                current.addOnPreDrawListener(listener)
                decor.postInvalidateOnAnimation()
            }
            check(completed.await(10, TimeUnit.SECONDS)) { "Timed out awaiting current PLAYING output and settled preDraws" }
            val witness = checkNotNull(result.get()).getOrThrow()
            onUi { verifyVideoWindowUnchanged(activity, underlay, driver, controller, witness, observer) }
            return witness
        } catch (error: Throwable) {
            cleanup.record(error)
            throw error
        } finally {
            cleanup.attempt { onUi { tree?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener) } }
        }
    }

    /** Collects actual visible text/controls only inside the single selected shown foreground. */
    private fun snapshotTheme(host: BigStandbyHost, theme: StandbyThemeId): ThemeUiSnapshot {
        check(Looper.myLooper() == Looper.getMainLooper())
        val foreground = (0 until host.childCount).map(host::getChildAt)
            .filterIsInstance<StandbyForegroundView>().single { it.isShown }
        check(when (theme) {
            StandbyThemeId.LANDSCAPE -> foreground is LandscapeStandbyForegroundView
            StandbyThemeId.MATTER -> foreground is MatterStandbyForegroundView
            StandbyThemeId.HARDLINE -> foreground is HardlineStandbyForegroundView
            // Legacy shows the dashboard column instead of a theme foreground.
            StandbyThemeId.LEGACY -> error("LEGACY theme has no standby foreground view")
        })
        check(host.getChildAt(0) === host.mediaUnderlay)
        check(host.width == (host.parent as View).width && host.height == (host.parent as View).height)
        val metrics = host.resources.displayMetrics
        val views = mutableListOf<ForegroundViewSnapshot>()
        /** Traverses the selected visible subtree without replacing or resizing product controls. */
        fun collect(view: View) {
            if (!view.isShown) return
            if (view is TextView || view.tag is String || view.isClickable || view.isFocusable) {
                val textView = view as? TextView
                views.add(ForegroundViewSnapshot(
                    snapshotNode(view), view.tag as? String, view.contentDescription?.toString(),
                    view.isClickable, view.isFocusable, view.isEnabled, view.importantForAccessibility,
                    view.minimumWidth, view.minimumHeight, textView?.text?.toString(), textView?.textSize,
                    textView?.textSize?.div(metrics.scaledDensity), textView?.currentTextColor,
                ))
            }
            if (view is ViewGroup) (0 until view.childCount).forEach { collect(view.getChildAt(it)) }
        }
        collect(foreground)
        val scrim = host.getChildAt(1)
        return ThemeUiSnapshot(snapshotNode(host), snapshotNode(foreground),
            (0 until host.childCount).map { snapshotNode(host.getChildAt(it)) },
            host.indexOfChild(scrim), snapshotNode(scrim), views.toList(), metrics.density, metrics.scaledDensity)
    }

    /** Transfers the original UI Throwable through Result, never a racy view-property read. */
    private fun <T> onUi(action: () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(runCatching(action)) }
        return checkNotNull(result.get()).getOrThrow()
    }

    /** Captures only the witnessed owned window off UI; always retires a hardware original. */
    private fun captureWindowBitmap(window: Window, cleanup: SpatialFixtureCleanup): Bitmap {
        check(Looper.myLooper() != Looper.getMainLooper())
        val raw = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(window)) {
            "UiAutomation.takeScreenshot(ownedWindow) returned null"
        }
        if (raw.config != Bitmap.Config.HARDWARE) return raw
        try {
            return checkNotNull(raw.copy(Bitmap.Config.ARGB_8888, false)) { "HARDWARE bitmap software copy failed" }
        } catch (error: Throwable) {
            cleanup.record(error)
            throw error
        } finally {
            cleanup.attempt { raw.recycle() }
        }
    }

    /** Leaves partial outputs intact; a false compression return never becomes capture success. */
    private fun savePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG compression failed: ${file.absolutePath}" }
            output.flush()
        }
        check(file.isFile && file.length() > 0L) { "PNG output missing/empty: ${file.absolutePath}" }
    }

    /** Samples every pixel in the exact validated rectangle, without clamping or color gates. */
    private fun sampleRegion(bitmap: Bitmap, bounds: PixelBounds): SampledStats {
        bounds.requireInside(PixelBounds(0, 0, bitmap.width, bitmap.height))
        var count = 0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var minR = 255
        var minG = 255
        var minB = 255
        var maxR = 0
        var maxG = 0
        var maxB = 0
        for (y in bounds.top until bounds.bottom) {
            for (x in bounds.left until bounds.right) {
                val pixel = bitmap.getPixel(x, y)
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)
                sumR += red
                sumG += green
                sumB += blue
                minR = minOf(minR, red)
                minG = minOf(minG, green)
                minB = minOf(minB, blue)
                maxR = maxOf(maxR, red)
                maxG = maxOf(maxG, green)
                maxB = maxOf(maxB, blue)
                count++
            }
        }
        check(count > 0)
        return SampledStats(count, sumR / count, sumG / count, sumB / count, minR, maxR, minG, maxG, minB, maxB)
    }

    /** Encodes the requested or observed framing without using production crop geometry as an oracle. */
    private fun framingJson(framing: StandbyFraming): JSONObject = JSONObject()
        .put("center_x", framing.centerX.toDouble()).put("center_y", framing.centerY.toDouble())
        .put("zoom", framing.zoom.toDouble())

    /** Preserves the first error, distinct suppressed errors and interruption across independent teardown. */
    private class SpatialFixtureCleanup {
        var failure: Throwable? = null
            private set
        private var interrupted = false

        /** Records the original instance, deduplicating only identical Throwable objects. */
        fun record(error: Throwable) {
            if (error is InterruptedException) interrupted = true
            val first = failure
            if (first == null) failure = error
            else if (first !== error && first.suppressed.none { it === error }) first.addSuppressed(error)
        }

        /** Attempts one action even when another cleanup step failed or interruption was observed. */
        fun attempt(action: () -> Unit): Boolean {
            if (Thread.interrupted()) {
                interrupted = true
                record(InterruptedException("Spatial fixture cleanup interrupted"))
            }
            return try {
                action()
                true
            } catch (error: Throwable) {
                record(error)
                false
            }
        }

        /** A started construction without a handle is unknown, never positively closed. */
        fun nativeClosureKnown(started: Boolean, hasHandle: Boolean, closed: Boolean): Boolean =
            if (hasHandle) closed else !started

        /** Deletion needs successful body completion and every closure, with no recorded failure. */
        fun permitsDeletion(bodyComplete: Boolean, closuresClean: Boolean): Boolean =
            failure == null && bodyComplete && closuresClean

        /** Restores interruption only after all cleanup attempts. */
        fun restoreInterrupt() {
            if (interrupted) Thread.currentThread().interrupt()
        }

        /** Rethrows the first original error with its complete suppressed history. */
        fun throwIfFailed() {
            failure?.let { throw it }
        }
    }

    /** Keeps original asynchronous errors independently of the playback state queue. */
    private class SpatialStateObserver {
        private val states = LinkedBlockingQueue<StandbyPlaybackState>()
        private val failures = mutableListOf<Throwable>()

        /** Enqueues actual controller transitions. */
        fun onStateChanged(state: StandbyPlaybackState) {
            states.offer(state)
        }

        /** Retains every distinct original error; no fallback or blanket error clearing. */
        @Synchronized
        fun onError(cause: Throwable) {
            if (failures.none { it === cause }) failures.add(cause)
            states.offer(StandbyPlaybackState.ERROR)
        }

        /** Rethrows the first original, preserving later causes as suppressed errors. */
        @Synchronized
        fun throwIfFailed() {
            val first = failures.firstOrNull() ?: return
            failures.drop(1).forEach { error ->
                if (first !== error && first.suppressed.none { it === error }) first.addSuppressed(error)
            }
            throw first
        }

        /** Transfers all originals both before and after cleanup without clearing the observer. */
        @Synchronized
        fun transferTo(cleanup: SpatialFixtureCleanup) {
            failures.forEach(cleanup::record)
        }

        /** Bounds state waits; cleanup ignores queued ERROR only after independent error transfer. */
        fun awaitState(expected: StandbyPlaybackState, cleanup: Boolean = false) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                if (!cleanup) throwIfFailed()
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "Timed out awaiting $expected" }
                val state = states.poll(remaining, TimeUnit.NANOSECONDS)
                    ?: throw AssertionError("Timed out awaiting $expected")
                if (!cleanup) throwIfFailed()
                if (state == expected) return
            }
        }
    }

    /** Immutable integer pixel bounds in an explicitly named coordinate space. */
    private data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top

        /** Tests complete containment, including validity of both rectangles. */
        fun contains(other: PixelBounds): Boolean = width > 0 && height > 0 && other.width > 0 && other.height > 0 &&
            other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

        /** Rejects out-of-bounds, inverted and empty rectangles instead of repairing them. */
        fun requireInside(outer: PixelBounds) {
            check(outer.contains(this)) { "Invalid/out-of-bounds rectangle: $this within $outer" }
        }

        /** Translates an expected viewport sample into window coordinates. */
        fun offset(x: Int, y: Int): PixelBounds = PixelBounds(left + x, top + y, right + x, bottom + y)

        /** Encodes exact integer boundaries. */
        fun json(): JSONObject = JSONObject().put("left", left).put("top", top).put("right", right).put("bottom", bottom)
    }

    /** Immutable UI-property values; handles are kept separately for identity-only checks on UI. */
    private data class NodeSnapshot(
        val identity: String,
        val className: String,
        val parentIdentity: String,
        val bounds: PixelBounds,
        val layoutBounds: PixelBounds,
        val clip: PixelBounds?,
        val clipChildren: Boolean?,
        val clipToPadding: Boolean?,
        val alpha: Float,
        val cumulativeAlpha: Float,
        val visibility: Int,
        val windowVisibility: Int,
        val shown: Boolean,
        val attached: Boolean,
        val hardwareAccelerated: Boolean,
        val parentVisibilities: List<Int>,
        val transforms: List<Float>,
        val padding: List<Int>,
        val children: List<String>,
        val backgroundColor: Int?,
    ) {
        /** Encodes observed UI values, never inferred visual acceptance. */
        fun json(): JSONObject = JSONObject().put("identity", identity).put("class", className)
            .put("parent_identity", parentIdentity).put("bounds_in_window", bounds.json())
            .put("layout_bounds_in_parent", layoutBounds.json()).put("clip_bounds_local", clip?.json() ?: JSONObject.NULL)
            .put("clip_children", clipChildren ?: JSONObject.NULL).put("clip_to_padding", clipToPadding ?: JSONObject.NULL)
            .put("alpha", alpha.toDouble()).put("cumulative_alpha", cumulativeAlpha.toDouble())
            .put("visibility", visibility).put("window_visibility", windowVisibility).put("is_shown", shown)
            .put("attached", attached).put("hardware_accelerated", hardwareAccelerated)
            .put("ancestor_visibility", JSONArray(parentVisibilities)).put("padding_ltrb", JSONArray(padding))
            .put("transform_fields", "scaleX,scaleY,translationX,translationY,translationZ,pivotX,pivotY,rotation,rotationX,rotationY,elevation")
            .put("transform_values", JSONArray(transforms.map { it.toDouble() })).put("children", JSONArray(children))
            .put("background_argb", backgroundColor ?: JSONObject.NULL)
    }

    /** Owned identities plus immutable actual geometry; native output is borrowed, never released here. */
    private data class VideoWindowWitness(
        val window: Window,
        val decor: View,
        val token: IBinder,
        val root: FrameLayout,
        val parent: ViewGroup,
        val child: SurfaceView,
        val output: StandbyVideoOutput,
        val binding: Long,
        val driverIdentity: String,
        val controllerIdentity: String,
        val windowBounds: PixelBounds,
        val rootBounds: PixelBounds,
        val viewportBounds: PixelBounds,
        val nodes: List<NodeSnapshot>,
        val framing: StandbyFraming,
        val controllerState: StandbyPlaybackState,
        val decodedWidth: Int,
        val decodedHeight: Int,
    ) {
        /** Encodes identities only; all mutable property values came from the UI snapshot. */
        fun json(): JSONObject = JSONObject().put("window_identity", System.identityHashCode(window).toString(16))
            .put("window_token_identity", System.identityHashCode(token).toString(16))
            .put("output_identity", System.identityHashCode(output).toString(16)).put("binding_generation", binding)
            .put("driver_identity", driverIdentity).put("controller_identity", controllerIdentity)
            .put("window_bounds", windowBounds.json()).put("root_bounds", rootBounds.json())
            .put("viewport_bounds", viewportBounds.json()).put("nodes_decor_root_parent_viewport_surface", JSONArray(nodes.map { it.json() }))
            .put("controller_state", controllerState.name).put("current_owned_output_geometry_usable", true)
            .put("readiness", "Current PLAYING, owned output, usable geometry, current rendering alpha and matching preDraws")
            .put("decoded_width", decodedWidth).put("decoded_height", decodedHeight)
            .put("framing", JSONObject().put("center_x", framing.centerX.toDouble())
                .put("center_y", framing.centerY.toDouble()).put("zoom", framing.zoom.toDouble()))
    }

    /** Actual selected-theme text/control values from one UI-thread traversal. */
    private data class ForegroundViewSnapshot(
        val node: NodeSnapshot,
        val tag: String?,
        val description: String?,
        val clickable: Boolean,
        val focusable: Boolean,
        val enabled: Boolean,
        val importantForAccessibility: Int,
        val minimumWidth: Int,
        val minimumHeight: Int,
        val text: String?,
        val textSizePx: Float?,
        val textSizeSpFromScaledDensity: Float?,
        val textColor: Int?,
    ) {
        /** Encodes raw text and accessibility strings through standard JSON escaping. */
        fun json(): JSONObject = JSONObject().put("node", node.json()).put("tag", tag ?: JSONObject.NULL)
            .put("content_description", description ?: JSONObject.NULL).put("clickable", clickable).put("focusable", focusable)
            .put("enabled", enabled).put("important_for_accessibility", importantForAccessibility)
            .put("minimum_width_px", minimumWidth).put("minimum_height_px", minimumHeight)
            .put("text", text ?: JSONObject.NULL).put("text_size_px", textSizePx?.toDouble() ?: JSONObject.NULL)
            .put("text_size_sp_from_scaled_density", textSizeSpFromScaledDensity?.toDouble() ?: JSONObject.NULL)
            .put("text_argb", textColor ?: JSONObject.NULL)
    }

    /** Complete measured Host, selected foreground, product sibling scrim and visible child metadata. */
    private data class ThemeUiSnapshot(
        val host: NodeSnapshot,
        val foreground: NodeSnapshot,
        val siblings: List<NodeSnapshot>,
        val scrimOrder: Int,
        val scrim: NodeSnapshot,
        val views: List<ForegroundViewSnapshot>,
        val density: Float,
        val scaledDensity: Float,
    ) {
        /** Records scrim attenuation, glyph coverage, AA and touch-target acceptance as unmeasured. */
        fun json(): JSONObject = JSONObject().put("full_host", host.json()).put("selected_shown_foreground", foreground.json())
            .put("host_sibling_order", JSONArray(siblings.map { it.json() })).put("scrim_sibling_index", scrimOrder)
            .put("scrim", scrim.json()).put("visible_views", JSONArray(views.map { it.json() }))
            .put("density", density.toDouble()).put("scaled_density", scaledDensity.toDouble())
            .put("sp_conversion", "Observed px / scaledDensity; nonlinear-font setter SP is not inferred")
            .put("attenuation_assessment", "not_measured").put("glyph_z_assessment", "not_measured")
            .put("antialiasing_assessment", "not_measured").put("touch_target_acceptance", "not_measured")
    }

    /** Channel-complete raw statistics over an exact nonempty integer rectangle. */
    private data class SampledStats(
        val count: Int,
        val meanR: Double,
        val meanG: Double,
        val meanB: Double,
        val minR: Int,
        val maxR: Int,
        val minG: Int,
        val maxG: Int,
        val minB: Int,
        val maxB: Int,
    ) {
        /** Encodes all channel means and extrema without classification thresholds. */
        fun json(): JSONObject = JSONObject().put("count", count)
            .put("mean_r", meanR).put("mean_g", meanG).put("mean_b", meanB)
            .put("min_r", minR).put("max_r", maxR).put("min_g", minG).put("max_g", maxG)
            .put("min_b", minB).put("max_b", maxB)
    }

    /** Frozen eroded source core copied from spatial-oracle.json. */
    private data class SourceCore(val name: String, val bounds: PixelBounds)

    /** Fixed independent source-window vector; never invokes production crop math. */
    private data class VectorDef(
        val id: Int,
        val vpW: Int,
        val vpH: Int,
        val framing: StandbyFraming,
        val sourceWindow: PixelBounds,
        val scale: Double,
    ) {
        /** Projects only fully visible frozen cores and rounds inward; excluded cores are not sampled. */
        fun projectCore(core: PixelBounds): PixelBounds? {
            if (!sourceWindow.contains(core)) return null
            val destination = PixelBounds(
                ceil((core.left - sourceWindow.left) * scale).toInt(),
                ceil((core.top - sourceWindow.top) * scale).toInt(),
                floor((core.right - sourceWindow.left) * scale).toInt(),
                floor((core.bottom - sourceWindow.top) * scale).toInt(),
            )
            destination.requireInside(PixelBounds(0, 0, vpW, vpH))
            return destination
        }

        /** Uniform expected source/core/scale data, separate from observed window and RGB telemetry. */
        fun expectedJson(): JSONObject = JSONObject().put("vector_id", id).put("source_asset_sha256", SPATIAL_ASSET_SHA256)
            .put("source_width", 320).put("source_height", 240).put("viewport_width", vpW).put("viewport_height", vpH)
            .put("requested_framing", JSONObject().put("center_x", framing.centerX.toDouble())
                .put("center_y", framing.centerY.toDouble()).put("zoom", framing.zoom.toDouble()))
            .put("source_window", sourceWindow.json()).put("scale", scale)
            .put("source_cores", JSONArray(spatialCores.map { core ->
                val destination = projectCore(core.bounds)
                JSONObject().put("core", core.name).put("source_bounds", core.bounds.json())
                    .put("expected_sample_bounds_in_viewport", destination?.json() ?: JSONObject.NULL)
                    .put("sampling", if (destination == null) "outside_expected_window_not_sampled" else "projected_core_telemetry")
            }))
    }

    private companion object {
        private const val LOG_TAG = "BigStandbyHostTest"
        private const val SPATIAL_ASSET_SHA256 = "7ace5449e0f6f5e3a9e8f8f0a855a9bfacbced7cd12563e9b5bf9096f3778768"
        private const val UNMEASURED = "Color/topology acceptance, actual centroids/distances/clip, glyph Z/coverage, AA/contrast, attenuation, 48dp acceptance, physical compositor timing"
        private val spatialCores = listOf(
            SourceCore("amber", PixelBounds(52, 108, 76, 132)),
            SourceCore("red", PixelBounds(266, 10, 278, 22)),
            SourceCore("green", PixelBounds(298, 10, 310, 22)),
            SourceCore("blue", PixelBounds(266, 50, 278, 62)),
        )
        private val spatialVectors = listOf(
            VectorDef(1, 200, 100, StandbyFraming(0.5f, 0.5f, 1f), PixelBounds(0, 40, 320, 200), 0.625),
            VectorDef(2, 200, 100, StandbyFraming(0.95f, 0.05f, 2f), PixelBounds(160, 0, 320, 80), 1.25),
            VectorDef(3, 100, 200, StandbyFraming(0.95f, 0.05f, 2f), PixelBounds(260, 0, 320, 120), 5.0 / 3.0),
        )
    }
}
