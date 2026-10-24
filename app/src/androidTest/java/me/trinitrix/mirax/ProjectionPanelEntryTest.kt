package me.trinitrix.mirax

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import me.trinitrix.mirax.session.ControlPanelEntry
import me.trinitrix.mirax.session.PictureScale
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.wfd.DebugOverlayView
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises projection entry points through the actual Android view hierarchy. */
@RunWith(AndroidJUnit4::class)
class ProjectionPanelEntryTest {
    /** An edge contact may report its last displacement only in UP, without a final MOVE. */
    @Test
    fun edgeReleaseWithoutMove_isDrag_notPanelTap() = withHiddenPanel { scenario ->
        scenario.onActivity { activity ->
            val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
            val button = controlButton(stage)
            val x = stage.width - 1f
            val y = button.y + button.height / 2
            val distance = ViewConfiguration.get(activity).scaledTouchSlop + 2f
            assertThat(distance).isLessThan(button.height / 2f)
            sendMotion(stage, MotionEvent.ACTION_DOWN, x, y)
            sendMotion(stage, MotionEvent.ACTION_UP, x, y + distance)
            assertThat(MiraxApp.instance.session.snapshot().controlPanelExpanded).isFalse()
            assertThat(button.y).isWithin(1f).of(y - button.height / 2 + distance)
        }
    }

    /** Moving away and back in one batched edge event must never restore tap eligibility. */
    @Test
    fun batchedEdgeMove_remembersDrag_evenWhenLatestPointReturnsToStart() = withHiddenPanel { scenario ->
        scenario.onActivity { activity ->
            val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
            val button = controlButton(stage)
            val x = stage.width - 1f
            val y = button.y + button.height / 2
            val distance = ViewConfiguration.get(activity).scaledTouchSlop + 2f
            sendMotion(stage, MotionEvent.ACTION_DOWN, x, y)
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, x, y + distance, 0)
            try {
                event.addBatch(now + 1, x, y + 1f, 1f, 1f, 0)
                stage.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
            sendMotion(stage, MotionEvent.ACTION_UP, x, y + 1f)
            assertThat(MiraxApp.instance.session.snapshot().controlPanelExpanded).isFalse()
        }
    }

    /** Android palm/edge cancellation can arrive as a canceled UP rather than ACTION_CANCEL. */
    @Test
    fun edgeCanceledUp_neverOpensPanel_andNextRealTapStillWorks() = withHiddenPanel { scenario ->
        scenario.onActivity { activity ->
            val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
            val button = controlButton(stage)
            val x = stage.width - 1f
            val y = button.y + button.height / 2
            sendMotion(stage, MotionEvent.ACTION_DOWN, x, y)
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 1,
                arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }),
                arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0f; size = 1f }),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.FLAG_CANCELED)
            try {
                stage.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
            assertThat(MiraxApp.instance.session.snapshot().controlPanelExpanded).isFalse()
            sendMotion(stage, MotionEvent.ACTION_DOWN, x, y)
            sendMotion(stage, MotionEvent.ACTION_UP, x, y)
            assertThat(MiraxApp.instance.session.snapshot().controlPanelExpanded).isTrue()
        }
    }

    /** Reserve the whole movement plane before dragging, not a moving compositor crop. */
    @Test
    fun dragging_reservesStableViewport_andRendersBeforePointerUp() = withHiddenPanel { scenario ->
        val app = ApplicationProvider.getApplicationContext<MiraxApp>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val original = app.session.snapshot()
        val artifacts = File(app.cacheDir, "handle-continuous-drag").apply { mkdirs() }
        val viewport = Rect()
        val handle = Rect()
        val downTime = SystemClock.uptimeMillis()
        fun inject(action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            try {
                check(instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        try {
            scenario.onActivity { activity ->
                SessionHost.setPictureScale(activity, PictureScale.MATCH_EDGES)
                app.session.handle(SessionAction.SetShowDebugOverlay(false))
                PictureActivity.refreshIfShowing()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
                stage.getGlobalVisibleRect(viewport)
                controlButton(stage).getGlobalVisibleRect(handle)
                val holder = stage.children.filterIsInstance<SurfaceView>().single().holder
                val canvas = checkNotNull(holder.lockCanvas())
                try {
                    canvas.drawColor(Color.WHITE)
                } finally {
                    holder.unlockCanvasAndPost(canvas)
                }
            }
            val inset = MiraxUi.dp(app, 4)
            val startX = (handle.right - inset).toFloat()
            val startY = handle.centerY().toFloat()
            inject(MotionEvent.ACTION_DOWN, startX, startY)
            val points = listOf(0.8f to 0.25f, 0.6f to 0.45f, 0.4f to 0.65f, 0.2f to 0.45f,
                0.005f to 0.45f)
            for ((index, point) in points.withIndex()) {
                inject(MotionEvent.ACTION_MOVE, viewport.width() * point.first, viewport.height() * point.second)
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
                    controlButton(stage).getGlobalVisibleRect(handle)
                }
                val dump = device.executeShellCommand("dumpsys SurfaceFlinger")
                File(artifacts, "layers-$index.txt").writeText(dump)
                val windowLayer = checkNotNull(Regex(
                    "(?s)- Output Layer[^\\n]*\\(me\\.trinitrix\\.mirax/me\\.trinitrix\\.mirax\\.PictureActivity#\\d+\\)(.*?)(?=\\n\\s*- Output Layer|\\z)",
                ).find(dump)) { "Projection window output layer missing from SurfaceFlinger" }.value
                // displayFrame uses physical panel coordinates; this region follows the logical display rotation.
                val region = checkNotNull(Regex(
                    "Region visibleNonTransparentRegion[^\\n]*\\n\\s*\\[\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+)\\]",
                ).find(windowLayer))
                val crop = Rect(region.groupValues[1].toInt(), region.groupValues[2].toInt(),
                    region.groupValues[3].toInt(), region.groupValues[4].toInt())
                device.takeScreenshot(File(artifacts, "held-$index.png"))
                assertWithMessage("Compositor region $crop must contain logical viewport $viewport")
                    .that(crop.contains(viewport)).isTrue()
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    val x = handle.right - inset
                    for (y in listOf(handle.top + inset, handle.centerY(), handle.bottom - inset)) {
                        assertThat(Color.red(screenshot.getPixel(x, y))).isLessThan(200)
                    }
                } finally {
                    screenshot.recycle()
                }
                assertThat(app.session.snapshot().controlPanelExpanded).isFalse()
                if (index == points.lastIndex) {
                    assertThat(handle.right).isAtMost(MiraxUi.dp(app, 10))
                }
            }
        } finally {
            inject(MotionEvent.ACTION_CANCEL, viewport.centerX().toFloat(), viewport.centerY().toFloat())
            scenario.onActivity { activity ->
                SessionHost.setPictureScale(activity, original.pictureScale)
                app.session.handle(SessionAction.SetShowDebugOverlay(original.showDebugOverlay))
                PictureActivity.refreshIfShowing()
            }
        }
    }

    /** A short same-edge drag must update SurfaceFlinger's crop, not only the View's bounds. */
    @Test
    fun shortSameEdgeDrag_compositedHandleRemainsFullyVisible() = withHiddenPanel { scenario ->
        val app = ApplicationProvider.getApplicationContext<MiraxApp>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val originalScale = app.session.snapshot().pictureScale
        val originalDebug = app.session.snapshot().showDebugOverlay
        val artifacts = File(app.cacheDir, "handle-compositor-regression").apply { mkdirs() }
        var bounds = Rect()
        try {
            scenario.onActivity { activity ->
                SessionHost.setPictureScale(activity, PictureScale.MATCH_EDGES)
                app.session.handle(SessionAction.SetShowDebugOverlay(false))
                PictureActivity.refreshIfShowing()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
                val surface = stage.children.filterIsInstance<SurfaceView>().single()
                val canvas = checkNotNull(surface.holder.lockCanvas())
                try {
                    canvas.drawColor(Color.WHITE)
                } finally {
                    surface.holder.unlockCanvasAndPost(canvas)
                }
                controlButton(stage).getGlobalVisibleRect(bounds)
            }
            SystemClock.sleep(200)
            device.takeScreenshot(File(artifacts, "before.png"))
            val shift = MiraxUi.dp(app, 20)
            val inset = MiraxUi.dp(app, 4)
            for ((index, delta) in listOf(shift, -shift, shift).withIndex()) {
                val oldBounds = Rect(bounds)
                val x = bounds.right - inset
                device.swipe(x, bounds.centerY(), x, bounds.centerY() + delta, 12)
                scenario.onActivity { activity ->
                    val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
                    controlButton(stage).getGlobalVisibleRect(bounds)
                }
                if (delta > 0) {
                    assertThat(bounds.bottom).isGreaterThan(oldBounds.bottom + inset)
                } else {
                    assertThat(bounds.top).isLessThan(oldBounds.top - inset)
                }
                val sampleY = if (delta > 0) bounds.bottom - inset else bounds.top + inset
                var visibleRed = 255
                val deadline = SystemClock.uptimeMillis() + 2000
                do {
                    val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                    try {
                        visibleRed = Color.red(screenshot.getPixel(x, sampleY))
                        if (visibleRed < 200) break
                    } finally {
                        screenshot.recycle()
                    }
                    SystemClock.sleep(100)
                } while (SystemClock.uptimeMillis() < deadline)
                device.takeScreenshot(File(artifacts, "after-$index.png"))
                File(artifacts, "bounds-$index.txt").writeText(
                    "view=$bounds old=$oldBounds sampleY=$sampleY red=$visibleRed",
                )
                assertThat(visibleRed).isLessThan(200)
            }
        } finally {
            scenario.onActivity { activity ->
                SessionHost.setPictureScale(activity, originalScale)
                app.session.handle(SessionAction.SetShowDebugOverlay(originalDebug))
                PictureActivity.refreshIfShowing()
            }
        }
    }

    /** The real rendered handle stays translucent and has no bright line on the attached side. */
    @Test
    fun handleRender_hasNoDockedBorder_andRemainsTranslucentWhenPressed() = withHiddenPanel { scenario ->
        scenario.onActivity { activity ->
            val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
            val button = controlButton(stage)
            assertHandlePixels(button, dockedRight = true)
            sendMotion(stage, MotionEvent.ACTION_DOWN, button.x + button.width / 2, button.y + button.height / 2)
            assertHandlePixels(button, dockedRight = true)
            sendMotion(stage, MotionEvent.ACTION_MOVE, -stage.width.toFloat(), stage.height / 2f)
            sendMotion(stage, MotionEvent.ACTION_UP, -stage.width.toFloat(), stage.height / 2f)
        }
        awaitDock(scenario, right = false)
        scenario.onActivity { activity ->
            val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
            assertHandlePixels(controlButton(stage), dockedRight = false)
        }
    }

    /** A shown handle must remain visibly and touchably above a debug overlay covering its location. */
    @Test
    fun movedHandle_aboveDebugOverlay_stillReceivesTap() = withHiddenPanel { scenario ->
        val app = ApplicationProvider.getApplicationContext<MiraxApp>()
        val originalDebug = app.session.snapshot().showDebugOverlay
        try {
            scenario.onActivity {
                app.session.handle(SessionAction.SetShowDebugOverlay(true))
                PictureActivity.refreshIfShowing()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
                val button = controlButton(stage)
                val debug = stage.children.filterIsInstance<ProjectionControlsLayer>().single()
                    .children.filterIsInstance<DebugOverlayView>().single()
                debug.x = button.x
                debug.y = button.y
                assertThat(button.isShown).isTrue()
                assertThat(debug.isShown).isTrue()
                val x = button.x + button.width - MiraxUi.dp(activity, 2)
                val y = button.y + button.height / 2
                sendMotion(stage, MotionEvent.ACTION_DOWN, x, y)
                sendMotion(stage, MotionEvent.ACTION_UP, x, y)
                assertThat(app.session.snapshot().controlPanelExpanded).isTrue()
            }
        } finally {
            scenario.onActivity {
                app.session.handle(SessionAction.SetShowDebugOverlay(originalDebug))
                PictureActivity.refreshIfShowing()
            }
        }
    }

    /** Docking keeps the current strip position until animation, and a new press takes over in place. */
    @Test
    fun dockingAnimation_canBeRegrabbed_andCancelNeverClicks() = withHiddenPanel { scenario ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var releasedX = 0f
        var stageWidth = 0
        scenario.onActivity { activity ->
            val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
            val button = controlButton(stage)
            stageWidth = stage.width
            sendMotion(stage, MotionEvent.ACTION_DOWN, button.x + button.width - 6, button.y + button.height / 2)
            sendMotion(stage, MotionEvent.ACTION_MOVE, stage.width * 0.35f, stage.height / 3f)
            releasedX = button.x
            fun stripLeft(): Int {
                val image = Bitmap.createBitmap(button.width, button.height, Bitmap.Config.ARGB_8888)
                try {
                    button.draw(Canvas(image))
                    return (0 until button.width).first { Color.alpha(image.getPixel(it, button.height / 2)) > 0 }
                } finally {
                    image.recycle()
                }
            }
            val releasedStrip = stripLeft()
            sendMotion(stage, MotionEvent.ACTION_UP, stage.width * 0.35f, stage.height / 3f)
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                assertThat(button.x).isWithin(1f).of(releasedX)
                assertThat(stripLeft()).isEqualTo(releasedStrip)
            }
        }
        SystemClock.sleep(50)
        scenario.onActivity { activity ->
            val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
            val button = controlButton(stage)
            val caughtX = button.x
            val x = button.x + button.width / 2
            val y = button.y + button.height / 2
            sendMotion(stage, MotionEvent.ACTION_DOWN, x, y)
            assertThat(button.x).isWithin(1f).of(caughtX)
            sendMotion(stage, MotionEvent.ACTION_MOVE, x + MiraxUi.dp(activity, 20), y)
            assertThat(button.x).isWithin(2f).of(caughtX + MiraxUi.dp(activity, 20))
            sendMotion(stage, MotionEvent.ACTION_CANCEL, x, y)
            assertThat(MiraxApp.instance.session.snapshot().controlPanelExpanded).isFalse()
        }
        SystemClock.sleep(250)
        instrumentation.waitForIdleSync()
        scenario.onActivity { activity ->
            val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
            val button = controlButton(stage)
            assertThat(button.x).isWithin(1f).of(0f)
            assertThat(button.x + button.width).isLessThan(stageWidth.toFloat())
        }
    }

    /** The empty part of the fixed control layer must not intercept a picture contact. */
    @Test
    fun controlPlane_outsideHandle_stillRoutesTouchToSurface() = withHiddenPanel { scenario ->
        var receivedDown = false
        var receivedUp = false
        scenario.onActivity { activity ->
            val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
            val surface = stage.children.filterIsInstance<SurfaceView>().single()
            surface.setOnTouchListener { _, event ->
                receivedDown = receivedDown || event.actionMasked == MotionEvent.ACTION_DOWN
                receivedUp = receivedUp || event.actionMasked == MotionEvent.ACTION_UP
                true
            }
            sendMotion(stage, MotionEvent.ACTION_DOWN, stage.width / 2f, stage.height / 2f)
            sendMotion(stage, MotionEvent.ACTION_UP, stage.width / 2f, stage.height / 2f)
            assertThat(receivedDown).isTrue()
            assertThat(receivedUp).isTrue()
            assertThat(MiraxApp.instance.session.snapshot().controlPanelExpanded).isFalse()
        }
    }

    /** A second contact cannot turn a canceled drag into a panel click. */
    @Test
    fun secondPointer_cancelsContact_withoutClickingOrHandingOff() = withHiddenPanel { scenario ->
        scenario.onActivity { activity ->
            val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
            val button = controlButton(stage)
            val x = button.x + button.width / 2
            val y = button.y + button.height / 2
            sendMotion(stage, MotionEvent.ACTION_DOWN, x, y)
            val properties = Array(2) { id -> MotionEvent.PointerProperties().apply {
                this.id = id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            } }
            val coordinates = Array(2) { id -> MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y + id * 2
                pressure = 1f
                size = 1f
            } }
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP)) {
                val event = MotionEvent.obtain(now, now, action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try {
                    stage.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
            sendMotion(stage, MotionEvent.ACTION_UP, x, y)
            assertThat(MiraxApp.instance.session.snapshot().controlPanelExpanded).isFalse()
            sendMotion(stage, MotionEvent.ACTION_DOWN, x, y)
            sendMotion(stage, MotionEvent.ACTION_UP, x, y)
            assertThat(MiraxApp.instance.session.snapshot().controlPanelExpanded).isTrue()
        }
    }

    /** A viewport resize cancels the old docking target instead of animating outside new bounds. */
    @Test
    fun resizeDuringDocking_staysWithinNewViewport() = withHiddenPanel { scenario ->
        scenario.onActivity { activity ->
            val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
            val button = controlButton(stage)
            sendMotion(stage, MotionEvent.ACTION_DOWN, button.x + button.width - 6, button.y + button.height / 2)
            sendMotion(stage, MotionEvent.ACTION_MOVE, stage.width * 0.7f, stage.height / 3f)
            sendMotion(stage, MotionEvent.ACTION_UP, stage.width * 0.7f, stage.height / 3f)
            val layer = button.parent as FrameLayout
            layer.layoutParams = layer.layoutParams.apply { width = stage.width - MiraxUi.dp(activity, 120) }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(250)
        scenario.onActivity { activity ->
            val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
            val button = controlButton(stage)
            val layer = button.parent as FrameLayout
            assertThat(button.x + button.width).isWithin(1f).of(layer.width.toFloat())
            assertThat(MiraxApp.instance.session.snapshot().controlPanelExpanded).isFalse()
        }
    }

    /** The translucent edge handle docks on either side without losing its touch target. */
    @Test
    fun controlButton_dragClamps_thenTapOpensPanel() = withHiddenPanel { scenario ->
        scenario.onActivity { activity ->
            val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
            val button = controlButton(stage)
            val session = MiraxApp.instance.session
            assertThat(button.contentDescription).isEqualTo(activity.getString(R.string.control_panel_open))
            assertThat(button.width).isAtLeast(MiraxUi.dp(activity, 48))
            assertThat(button.height).isAtLeast(MiraxUi.dp(activity, 48))
            assertThat(button.x + button.width).isEqualTo((stage.width - stage.paddingRight).toFloat())
            assertThat(button.background.alpha).isLessThan(255)
            sendMotion(stage, MotionEvent.ACTION_DOWN, button.x + button.width / 2, button.y + button.height / 2)
            sendMotion(stage, MotionEvent.ACTION_MOVE, -stage.width.toFloat(), stage.height * 2f)
            sendMotion(stage, MotionEvent.ACTION_UP, -stage.width.toFloat(), stage.height * 2f)
            assertThat(session.snapshot().controlPanelExpanded).isFalse()
        }
        awaitDock(scenario, right = false)
        scenario.onActivity { activity ->
            val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
            val button = controlButton(stage)
            assertThat(button.x).isEqualTo(stage.paddingLeft.toFloat())
            assertThat(button.y + button.height).isAtMost((stage.height - stage.paddingBottom).toFloat())

            sendMotion(stage, MotionEvent.ACTION_DOWN, button.x + button.width / 2, button.y + button.height / 2)
            sendMotion(stage, MotionEvent.ACTION_MOVE, stage.width * 2f, stage.height * 2f)
            sendMotion(stage, MotionEvent.ACTION_UP, stage.width * 2f, stage.height * 2f)
        }
        awaitDock(scenario, right = true)
        scenario.onActivity { activity ->
            val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
            val button = controlButton(stage)
            val session = MiraxApp.instance.session
            assertThat(button.x + button.width).isEqualTo((stage.width - stage.paddingRight).toFloat())

            val x = button.x + button.width / 2
            val y = button.y + button.height / 2
            sendMotion(stage, MotionEvent.ACTION_DOWN, x, y)
            sendMotion(stage, MotionEvent.ACTION_CANCEL, x, y)
            assertThat(session.snapshot().controlPanelExpanded).isFalse()
            sendMotion(stage, MotionEvent.ACTION_DOWN, x, y)
            sendMotion(stage, MotionEvent.ACTION_UP, x, y)
            assertThat(session.snapshot().controlPanelExpanded).isTrue()
            assertThat(button.isShown).isFalse()
        }
    }

    /** Either long press opens once, while short and canceled presses keep their own meaning. */
    @Test
    fun volumeKeys_longPressOpens_shortPressAdjusts_canceledPressDoesNothing() = withHiddenPanel { scenario ->
        scenario.onActivity { SessionHost.setControlPanelEntry(it, ControlPanelEntry.VOLUME_KEYS) }
        val audio = ApplicationProvider.getApplicationContext<MiraxApp>().getSystemService(AudioManager::class.java)
        var originalStream = AudioManager.USE_DEFAULT_STREAM_TYPE
        val originalVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val raise = originalVolume < audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        try {
            scenario.onActivity { activity ->
                originalStream = activity.volumeControlStream
                activity.volumeControlStream = AudioManager.STREAM_MUSIC
                for (key in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) {
                    sendKey(activity, KeyEvent.ACTION_DOWN, key)
                    assertThat(MiraxApp.instance.session.snapshot().controlPanelExpanded).isFalse()
                    sendKey(activity, KeyEvent.ACTION_DOWN, key, repeat = 1, flags = KeyEvent.FLAG_LONG_PRESS)
                    assertThat(MiraxApp.instance.session.snapshot().controlPanelExpanded).isTrue()
                    sendKey(activity, KeyEvent.ACTION_DOWN, key, repeat = 2, flags = KeyEvent.FLAG_LONG_PRESS)
                    sendKey(activity, KeyEvent.ACTION_UP, key)
                    assertThat(MiraxApp.instance.session.snapshot().controlPanelExpanded).isTrue()
                    assertThat(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).isEqualTo(originalVolume)
                    SessionHost.collapseControlPanel(activity)
                    PictureActivity.refreshIfShowing()
                }

                sendKey(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
                sendKey(activity, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP, flags = KeyEvent.FLAG_CANCELED)
                assertThat(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).isEqualTo(originalVolume)
                val key = if (raise) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
                sendKey(activity, KeyEvent.ACTION_DOWN, key)
                assertThat(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).isEqualTo(originalVolume)
                sendKey(activity, KeyEvent.ACTION_UP, key)
                assertThat(MiraxApp.instance.session.snapshot().controlPanelExpanded).isFalse()
            }
            // The audio service may apply a volume request after the key callback returns.
            val expectedVolume = originalVolume + if (raise) 1 else -1
            val deadline = SystemClock.uptimeMillis() + 2000
            while (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != expectedVolume &&
                SystemClock.uptimeMillis() < deadline
            ) {
                SystemClock.sleep(20)
            }
            assertThat(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).isEqualTo(expectedVolume)
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
            scenario.onActivity { it.volumeControlStream = originalStream }
        }
    }

    /** The Advanced preference selects a single entry while leaving the home overlay setting alone. */
    @Test
    fun entryPreference_switchesVisibleButtonAndPersistsWithoutChangingHomeBall() = withHiddenPanel { scenario ->
        val app = ApplicationProvider.getApplicationContext<MiraxApp>()
        val homeBallEnabled = app.session.snapshot().floatingBallEnabled
        scenario.onActivity { activity ->
            val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
            val button = controlButton(stage)
            assertThat(button.isShown).isTrue()
            SessionHost.setControlPanelEntry(activity, ControlPanelEntry.VOLUME_KEYS)
            assertThat(button.isShown).isFalse()
            button.performClick()
            assertThat(app.session.snapshot().controlPanelExpanded).isFalse()
            assertThat(SessionPreferences.load(activity).controlPanelEntry).isEqualTo(ControlPanelEntry.VOLUME_KEYS)
            assertThat(app.session.snapshot().floatingBallEnabled).isEqualTo(homeBallEnabled)

            sendKey(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)
            sendKey(activity, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, repeat = 1, flags = KeyEvent.FLAG_LONG_PRESS)
            assertThat(app.session.snapshot().controlPanelExpanded).isTrue()
            sendKey(activity, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
            SessionHost.collapseControlPanel(activity)
            PictureActivity.refreshIfShowing()
            SessionHost.setControlPanelEntry(activity, ControlPanelEntry.FLOATING_BUTTON)
            assertThat(button.isShown).isTrue()
            assertThat(SessionPreferences.load(activity).controlPanelEntry).isEqualTo(ControlPanelEntry.FLOATING_BUTTON)
            assertThat(activity.onKeyLongPress(KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN))).isFalse()
            assertThat(app.session.snapshot().controlPanelExpanded).isFalse()
        }
    }

    /** Selecting the control ball leaves physical volume-key events to Android. */
    @Test
    fun controlBallMode_volumeKeyIsHandledByAndroid_notPanelShortcut() = withHiddenPanel { scenario ->
        scenario.onActivity { activity ->
            assertThat(MiraxApp.instance.session.snapshot().controlPanelEntry).isEqualTo(ControlPanelEntry.FLOATING_BUTTON)
            val first = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
            val held = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
            assertThat(activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, first)).isFalse()
            assertThat(activity.onKeyLongPress(KeyEvent.KEYCODE_VOLUME_UP, held)).isFalse()
            assertThat(MiraxApp.instance.session.snapshot().controlPanelExpanded).isFalse()
        }
    }

    /** This checks the exclusion request, not an OEM's delivery of physical edge gestures. */
    @Test
    fun backExclusion_isRequestedOnlyWithPanelHidden() = withHiddenPanel { scenario ->
        scenario.onActivity { activity ->
            val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
            assertThat(stage.systemGestureExclusionRects).containsExactly(Rect(0, 0, stage.width, stage.height))
            controlButton(stage).performClick()
            assertThat(stage.systemGestureExclusionRects).isEmpty()
        }
    }

    /** @return the panel entry in its fixed compositor plane */
    private fun controlButton(stage: FrameLayout): ProjectionControlButton =
        stage.children.filterIsInstance<ProjectionControlsLayer>().single()
            .children.filterIsInstance<ProjectionControlButton>().single()

    /** Wait off the UI thread for the real docking animation, never force its final position. */
    private fun awaitDock(scenario: ActivityScenario<PictureActivity>, right: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 2000
        var docked: Boolean
        do {
            docked = false
            scenario.onActivity { activity ->
                val stage = activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
                val button = controlButton(stage)
                val parent = button.parent as FrameLayout
                val target = if (right) (parent.width - parent.paddingRight - button.width).toFloat()
                    else parent.paddingLeft.toFloat()
                docked = kotlin.math.abs(button.x - target) < 0.1f
            }
            if (!docked) SystemClock.sleep(20)
        } while (!docked && SystemClock.uptimeMillis() < deadline)
        assertThat(docked).isTrue()
    }

    /** Assert the drawn strip, including antialiased corner pixels, at a settled edge. */
    private fun assertHandlePixels(button: ProjectionControlButton, dockedRight: Boolean) {
        val bitmap = Bitmap.createBitmap(button.width, button.height, Bitmap.Config.ARGB_8888)
        try {
            button.draw(Canvas(bitmap))
            val edgeX = if (dockedRight) button.width - 1 else 0
            val pixel = bitmap.getPixel(edgeX, button.height / 2)
            assertThat(Color.alpha(pixel)).isIn(1..254)
            assertThat(Color.red(pixel)).isLessThan(100)
            val inset = MiraxUi.dp(button.context, 4)
            val insideX = if (dockedRight) button.width - inset else inset
            assertThat(Color.alpha(bitmap.getPixel(insideX, button.height / 2))).isIn(1..254)
            val stripWidth = MiraxUi.dp(button.context, 8)
            val start = if (dockedRight) button.width - stripWidth else 0
            val cornerAlphas = (0 until inset).flatMap { y ->
                (start until start + stripWidth).map { x -> Color.alpha(bitmap.getPixel(x, y)) }
            }
            assertThat(cornerAlphas.any { it in 1 until Color.alpha(pixel) }).isTrue()
        } finally {
            bitmap.recycle()
        }
    }

    /** @param block assertions to run after the mock panel is hidden and laid out */
    private fun withHiddenPanel(block: (ActivityScenario<PictureActivity>) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<MiraxApp>()
        val originalEntry = app.session.snapshot().controlPanelEntry
        app.session.handle(SessionAction.SetControlPanelEntry(ControlPanelEntry.FLOATING_BUTTON))
        SessionPreferences.saveControlPanelEntry(app, ControlPanelEntry.FLOATING_BUTTON)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        try {
            val scenario = ActivityScenario.launch<PictureActivity>(
                Intent(app, PictureActivity::class.java).putExtra("mirax_mock_session", true),
            )
            try {
                scenario.onActivity {
                    SessionHost.collapseControlPanel(it)
                    PictureActivity.refreshIfShowing()
                }
                instrumentation.waitForIdleSync()
                block(scenario)
            } finally {
                scenario.close()
            }
        } finally {
            app.session.handle(SessionAction.SetControlPanelEntry(originalEntry))
            SessionPreferences.saveControlPanelEntry(app, originalEntry)
        }
    }

    /** Dispatch a pointer sample through the same hierarchy used by physical touch. */
    private fun sendMotion(stage: FrameLayout, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        try {
            stage.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    /** Dispatch Android key tracking flags without relying on physical key repeat timing. */
    private fun sendKey(activity: PictureActivity, action: Int, code: Int, repeat: Int = 0, flags: Int = 0) {
        val now = SystemClock.uptimeMillis()
        activity.dispatchKeyEvent(KeyEvent(now, now, action, code, repeat, 0, -1, 0, flags))
    }

    /** Letterbox taps must stay phone-only without opening the control panel. */
    @Test
    fun blackMarginTap_doesNotOpenPanel() {
        val app = ApplicationProvider.getApplicationContext<MiraxApp>()
        val originalScale = app.session.snapshot().pictureScale
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        val scenario = ActivityScenario.launch<PictureActivity>(
            Intent(app, PictureActivity::class.java).putExtra("mirax_mock_session", true),
        )
        try {
            scenario.onActivity { activity ->
                SessionHost.setPictureScale(activity, PictureScale.PROPORTIONAL)
                SessionHost.collapseControlPanel(activity)
                PictureActivity.refreshIfShowing()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val content = activity.findViewById<FrameLayout>(android.R.id.content)
                val stage = content.getChildAt(0) as FrameLayout
                assertThat(stage.children.filter { it.javaClass == FrameLayout::class.java }.single().isShown).isFalse()
                val surface = stage.children.filterIsInstance<SurfaceView>().single()
                val x: Float
                val y: Float
                if (surface.top > stage.paddingTop) {
                    x = stage.width / 2f
                    y = (stage.paddingTop + surface.top) / 2f
                } else {
                    assertThat(surface.left).isGreaterThan(stage.paddingLeft)
                    x = (stage.paddingLeft + surface.left) / 2f
                    y = stage.height / 2f
                }
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, now, action, x, y, 0)
                    try {
                        stage.dispatchTouchEvent(event)
                    } finally {
                        event.recycle()
                    }
                }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertThat(app.session.snapshot().controlPanelExpanded).isFalse()
            }
        } finally {
            scenario.onActivity { SessionHost.setPictureScale(it, originalScale) }
            scenario.close()
        }
    }
}
