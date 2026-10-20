package me.trinitrix.mirax

import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_POINTER_UP
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.trinitrix.mirax.session.PictureScale
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.wfd.P2pNetworkBinder
import me.trinitrix.mirax.wfd.UibcContact
import me.trinitrix.mirax.wfd.UibcPackets
import me.trinitrix.mirax.wfd.UibcPenContact
import me.trinitrix.mirax.wfd.UibcTouchChannel
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.FutureTask

/** Injects Android gestures without launching the app; Windows delivery requires a separate probe. */
@RunWith(AndroidJUnit4::class)
class MiraxMultiTouchInjectionTest {
    @Test
    fun injectTwoFingers_throughPictureActivitySurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = awaitConnectedActivity()
        Log.i(TAG, "READY: PictureActivity is foreground and CONNECTED")
        SystemClock.sleep(injectionDelayMs())

        val downTime = SystemClock.uptimeMillis()
        val active = ArrayList<Int>(2)
        val first = Pointer(0, 0f, 0f)
        val second = Pointer(1, 0f, 0f)
        var completed = false
        try {
            val bounds = onMain {
                assertTrue("Projection lost focus before injection", focusedVisibleConnectedActivity() === activity)
                surfaceBounds(activity)
            }
            first.x = bounds.exactCenterX()
            first.y = bounds.exactCenterY()
            second.x = bounds.exactCenterX() + bounds.width() / 3f
            second.y = bounds.exactCenterY() + bounds.height() / 3f

            inject(instrumentation, downTime, ACTION_DOWN, first.id, listOf(first))
            active.add(first.id)
            SystemClock.sleep(STEP_MS)
            inject(instrumentation, downTime, ACTION_POINTER_DOWN, second.id, listOf(first, second))
            active.add(second.id)
            SystemClock.sleep(STEP_MS)
            val holdSteps = 160
            repeat(holdSteps) {
                first.x += bounds.width() * 0.1f / holdSteps
                second.x -= bounds.width() * 0.1f / holdSteps
                inject(instrumentation, downTime, ACTION_MOVE, second.id, listOf(first, second))
                SystemClock.sleep(STEP_MS)
            }
            inject(instrumentation, downTime, ACTION_POINTER_UP, first.id, listOf(first, second))
            active.remove(first.id)
            SystemClock.sleep(STEP_MS)
            repeat(SECOND_MOVE_STEPS) {
                second.x += bounds.width() * 0.05f / SECOND_MOVE_STEPS
                inject(instrumentation, downTime, ACTION_MOVE, second.id, listOf(second))
                SystemClock.sleep(STEP_MS)
            }
            inject(instrumentation, downTime, ACTION_UP, second.id, listOf(second))
            active.remove(second.id)
            completed = true
            Log.i(TAG, "injected two-finger stream, completed")
        } finally {
            if (!completed && active.isNotEmpty()) {
                try {
                    val left = listOf(first, second).filter { it.id in active }
                    inject(instrumentation, downTime, ACTION_CANCEL, left.first().id, left)
                } catch (error: Throwable) {
                    Log.e(TAG, "cancellation failed", error)
                }
            }
        }
    }

    @Test
    fun injectStylus_throughPictureActivitySurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = awaitConnectedActivity()
        Log.i(TAG, "READY: stylus injection into CONNECTED PictureActivity")
        SystemClock.sleep(injectionDelayMs())
        val bounds = onMain {
            assertTrue("Projection lost focus before injection", focusedVisibleConnectedActivity() === activity)
            surfaceBounds(activity)
        }
        val pen = Pointer(
            0, bounds.left + bounds.width() * 0.95f, bounds.top + bounds.height() * 0.75f,
            MotionEvent.TOOL_TYPE_STYLUS, 0.6f,
        )
        val downTime = SystemClock.uptimeMillis()
        var down = false
        try {
            inject(instrumentation, downTime, ACTION_DOWN, pen.id, listOf(pen))
            down = true
            repeat(MOVE_STEPS) {
                pen.x += bounds.width() * 0.02f / MOVE_STEPS
                inject(instrumentation, downTime, ACTION_MOVE, pen.id, listOf(pen))
                SystemClock.sleep(STEP_MS)
            }
            pen.pressure = 0f
            inject(instrumentation, downTime, ACTION_UP, pen.id, listOf(pen))
            down = false
            Log.i(TAG, "injected stylus stream, completed")
        } finally {
            if (down) {
                try {
                    inject(instrumentation, downTime, ACTION_CANCEL, pen.id, listOf(pen))
                } catch (error: Throwable) {
                    Log.e(TAG, "stylus cancellation failed", error)
                }
            }
        }
    }

    @Test
    fun injectFingerAndStylus_throughPictureActivitySurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = awaitConnectedActivity()
        Log.i(TAG, "READY: finger and stylus injection into CONNECTED PictureActivity")
        SystemClock.sleep(injectionDelayMs())
        val previousScale = MiraxApp.instance.session.snapshot().pictureScale
        try {
            onMain {
                logSurfaceGeometry(activity, "before-fit")
                SessionHost.setPictureScale(activity, PictureScale.PROPORTIONAL)
            }
            instrumentation.waitForIdleSync()
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (!onMain { isWholeSurfaceVisible(activity) }) {
                assertTrue("Proportional surface did not become fully visible", SystemClock.uptimeMillis() < deadline)
                SystemClock.sleep(STEP_MS)
            }
            onMain { logSurfaceGeometry(activity, "after-fit") }
            injectSeparatedFingerAndStylus(instrumentation, activity)
        } finally {
            onMain { SessionHost.setPictureScale(activity, previousScale) }
            Log.i(TAG, "SURFACE_SCALE_RESTORED $previousScale")
        }
    }

    /**
     * Injects far-separated UI contacts only after the full picture surface is visible.
     *
     * @param instrumentation Android input injection owner
     * @param activity connected projection window
     */
    private fun injectSeparatedFingerAndStylus(instrumentation: Instrumentation, activity: PictureActivity) {
        val bounds = onMain {
            assertTrue("Projection lost focus before injection", focusedVisibleConnectedActivity() === activity)
            surfaceBounds(activity)
        }
        val finger = Pointer(0, bounds.left + bounds.width() * 0.05f, bounds.top + bounds.height() * 0.35f)
        val pen = Pointer(
            1, bounds.left + bounds.width() * 0.95f,
            bounds.top + bounds.height() * 0.65f,
            MotionEvent.TOOL_TYPE_STYLUS, 0.6f,
        )
        Log.i(TAG, "UI_COEX_COORDINATES finger=(${finger.x},${finger.y}) pen=(${pen.x},${pen.y})")
        val downTime = SystemClock.uptimeMillis()
        val active = ArrayList<Int>(2)
        try {
            inject(instrumentation, downTime, ACTION_DOWN, finger.id, listOf(finger))
            active.add(finger.id)
            SystemClock.sleep(STEP_MS)
            inject(instrumentation, downTime, ACTION_POINTER_DOWN, pen.id, listOf(finger, pen))
            active.add(pen.id)
            SystemClock.sleep(STEP_MS)
            val holdSteps = 160
            repeat(holdSteps) {
                finger.x += bounds.width() * 0.01f / holdSteps
                pen.x -= bounds.width() * 0.01f / holdSteps
                inject(instrumentation, downTime, ACTION_MOVE, pen.id, listOf(finger, pen))
                SystemClock.sleep(STEP_MS)
            }
            inject(instrumentation, downTime, ACTION_POINTER_UP, finger.id, listOf(finger, pen))
            active.remove(finger.id)
            SystemClock.sleep(STEP_MS)
            pen.pressure = 0f
            inject(instrumentation, downTime, ACTION_UP, pen.id, listOf(pen))
            active.remove(pen.id)
            Log.i(TAG, "injected finger and stylus stream, completed")
        } finally {
            if (active.isNotEmpty()) {
                try {
                    val left = listOf(finger, pen).filter { it.id in active }
                    inject(instrumentation, downTime, ACTION_CANCEL, left.first().id, left)
                } catch (error: Throwable) {
                    Log.e(TAG, "finger and stylus cancellation failed", error)
                }
            }
        }
    }

    /** Exercises every allocated finger slot through the existing socket, without Android viewport cropping. */
    @Test
    fun probeAllFingerSlotsOnExistingChannel() {
        awaitConnectedActivity()
        Log.i(TAG, "READY: all finger slots on existing channel")
        SystemClock.sleep(injectionDelayMs())
        val mode = MiraxApp.instance.session.snapshot().selectedMode
            ?: throw AssertionError("Projection ended before the all-slot probe")
        assertTrue("Projection must remain focused and connected", onMain { focusedVisibleConnectedActivity() != null })
        val contacts = List(UibcPackets.MAX_CONTACTS) { slot ->
            UibcContact(slot, mode.width * (slot + 1) / (UibcPackets.MAX_CONTACTS + 1), mode.height / 2, tip = true)
        }
        assertTrue("Every finger slot must have a distinct ID", contacts.map { it.id }.toSet().size == contacts.size)
        try {
            Log.i(TAG, "ALL_SLOTS_BEGIN count=${contacts.size} contacts=$contacts")
            repeat(160) { step ->
                UibcTouchChannel.submit(contacts.map { it.copy(y = it.y + step % 2) })
                SystemClock.sleep(STEP_MS)
            }
        } finally {
            UibcTouchChannel.submit(emptyList())
            Log.i(TAG, "ALL_SLOTS_END")
        }
    }

    /**
     * Isolates host palm rejection from the cropped Android surface coordinates.
     *
     * Uses the existing socket; only the external Windows probe establishes delivery.
     */
    @Test
    fun probeSeparatedFingerAndStylusOnExistingChannel() {
        awaitConnectedActivity()
        Log.i(TAG, "READY: direct separated finger and pen controls on existing channel")
        SystemClock.sleep(injectionDelayMs())
        val mode = MiraxApp.instance.session.snapshot().selectedMode
            ?: throw AssertionError("Projection ended before the direct coexistence probe")
        assertTrue("Projection must remain focused and connected", onMain { focusedVisibleConnectedActivity() != null })
        val finger = UibcContact(0, mode.width / 20, mode.height * 35 / 100, tip = true)
        val pen = UibcPenContact(
            mode.width * 19 / 20, mode.height * 65 / 100,
            tip = true, inRange = true, pressure = UibcPackets.MAX_PEN_PRESSURE / 2,
        )
        val penUp = pen.copy(tip = false, inRange = false, pressure = 0)
        Log.i(TAG, "DIRECT_COORDINATES mode=${mode.width}x${mode.height} finger=$finger pen=$pen")
        try {
            UibcTouchChannel.submit(emptyList())
            UibcTouchChannel.submitPen(penUp)
            Log.i(TAG, "DIRECT_PHASE touch-control")
            repeat(20) {
                UibcTouchChannel.submit(listOf(finger))
                SystemClock.sleep(STEP_MS)
            }
            UibcTouchChannel.submit(emptyList())
            SystemClock.sleep(1_000)
            Log.i(TAG, "DIRECT_PHASE pen-control")
            repeat(20) {
                UibcTouchChannel.submitPen(pen)
                SystemClock.sleep(STEP_MS)
            }
            UibcTouchChannel.submitPen(penUp)
            SystemClock.sleep(1_000)
            for (penFirst in listOf(false, true)) {
                Log.i(TAG, "DIRECT_PHASE coexistence penFirst=$penFirst")
                if (penFirst) UibcTouchChannel.submitPen(pen) else UibcTouchChannel.submit(listOf(finger))
                SystemClock.sleep(200)
                repeat(160) { step ->
                    val movingFinger = listOf(finger.copy(x = finger.x + step % 2))
                    val movingPen = pen.copy(x = pen.x - step % 2)
                    if (penFirst) {
                        UibcTouchChannel.submit(movingFinger)
                        UibcTouchChannel.submitPen(movingPen)
                    } else {
                        UibcTouchChannel.submitPen(movingPen)
                        UibcTouchChannel.submit(movingFinger)
                    }
                    SystemClock.sleep(STEP_MS)
                }
                UibcTouchChannel.submit(emptyList())
                UibcTouchChannel.submitPen(penUp)
                SystemClock.sleep(1_000)
            }
            Log.i(TAG, "DIRECT_PHASE completed")
        } finally {
            UibcTouchChannel.submit(emptyList())
            UibcTouchChannel.submitPen(penUp)
        }
    }

    /**
     * Probes whether a second type-2 UIBC connection yields an independent Windows pointer.
     *
     * This is an isolated diagnostic, not a production multi-touch route. A Windows
     * pointer probe must be armed before the second contact is submitted.
     */
    @Test
    fun probeSecondSingleTouchSocket() {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("uibcHost")
        assumeTrue("Pass uibcHost to run the dual-socket diagnostic", !host.isNullOrBlank())
        val port = args.getString("uibcPort")?.toIntOrNull() ?: 50000
        require(port in 1..65535)
        awaitConnectedActivity()
        Log.i(TAG, "READY: second type-2 socket probe on connected projection")
        SystemClock.sleep(injectionDelayMs())
        val mode = MiraxApp.instance.session.snapshot().selectedMode
            ?: throw AssertionError("Projection ended before the second socket probe")
        val first = UibcContact(0, mode.width / 3, mode.height / 2, tip = true)
        val second = UibcContact(0, mode.width * 2 / 3, mode.height / 2, tip = true)
        val type = UibcPackets.HID_SINGLE_TOUCH
        Socket().use { socket ->
            assertTrue(
                "Second socket must bind to P2P",
                P2pNetworkBinder.bind(InstrumentationRegistry.getInstrumentation().targetContext, socket),
            )
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), 3_000)
            Log.i(TAG, "SECOND_SOCKET_CONNECTED localPort=${socket.localPort}")
            val output = socket.getOutputStream()
            output.write(UibcPackets.hidcPacket(
                type, UibcPackets.USAGE_REPORT_DESCRIPTOR,
                UibcPackets.touchDescriptor(mode.width, mode.height, 1),
            ))
            output.write(UibcPackets.hidcPacket(
                type, UibcPackets.USAGE_INPUT_REPORT,
                UibcPackets.touchReport(emptyList(), 1),
            ))
            output.flush()
            Log.i(TAG, "SECOND_DESCRIPTOR_SENT 135B")
            SystemClock.sleep(1_500L)
            var secondDown = false
            try {
                UibcTouchChannel.submit(listOf(first))
                SystemClock.sleep(400L)
                output.write(UibcPackets.hidcPacket(
                    type, UibcPackets.USAGE_INPUT_REPORT,
                    UibcPackets.touchReport(listOf(second), 1),
                ))
                output.flush()
                secondDown = true
                Log.i(TAG, "TWO_SOCKETS_DOWN hold=4000ms")
                SystemClock.sleep(4_000L)
            } finally {
                UibcTouchChannel.liftAll()
                if (secondDown) {
                    try {
                        output.write(UibcPackets.hidcPacket(
                            type, UibcPackets.USAGE_INPUT_REPORT,
                            UibcPackets.touchReport(listOf(second.copy(tip = false)), 1),
                        ))
                        output.flush()
                    } catch (error: Exception) {
                        Log.w(TAG, "Second socket release failed", error)
                    }
                }
                Log.i(TAG, "TWO_SOCKETS_RELEASED")
            }
        }
    }

    private fun awaitConnectedActivity(): PictureActivity {
        val deadline = SystemClock.uptimeMillis() + CONNECT_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val ready = onMain { focusedVisibleConnectedActivity() }
            if (ready != null) return ready
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError(
            "No focused, visible PictureActivity within ${CONNECT_TIMEOUT_MS}ms. " +
                "Start the Miracast connection and keep the projection in the foreground."
        )
    }

    private fun focusedVisibleConnectedActivity(): PictureActivity? {
        val activity = PictureActivity.current() ?: return null
        val decor = activity.window?.decorView
        if (activity.isFinishing || !activity.hasWindowFocus() || decor == null || !decor.isShown) return null
        if (surfaceBounds(activity).isEmpty) return null
        val snapshot = MiraxApp.instance.session.snapshot()
        return if (snapshot.phase == ScreenPhase.CONNECTED && snapshot.showPicture && snapshot.touchEnabled && snapshot.selectedMode != null) activity else null
    }

    private fun surfaceBounds(activity: PictureActivity): Rect {
        val bounds = Rect()
        surfaceViewOf(activity.window?.decorView)
            ?.takeIf { it.width > 0 && it.height > 0 }
            ?.getGlobalVisibleRect(bounds)
        return bounds
    }

    /**
     * Checks clipping before fractions of visible bounds are used as full-picture fractions.
     *
     * @param activity connected projection window
     * @return whether the complete surface is visible
     */
    private fun isWholeSurfaceVisible(activity: PictureActivity): Boolean {
        val surface = surfaceViewOf(activity.window?.decorView) ?: return false
        val origin = IntArray(2)
        surface.getLocationOnScreen(origin)
        val bounds = surfaceBounds(activity)
        return surface.width > 0 && surface.height > 0 &&
            bounds == Rect(origin[0], origin[1], origin[0] + surface.width, origin[1] + surface.height)
    }

    /**
     * Records full and visible geometry without assuming that the surface is uncropped.
     *
     * @param activity connected projection window
     * @param phase label for correlation with forwarded input reports
     */
    private fun logSurfaceGeometry(activity: PictureActivity, phase: String) {
        val surface = surfaceViewOf(activity.window?.decorView)
            ?: throw AssertionError("Projection surface missing during geometry capture")
        val origin = IntArray(2)
        surface.getLocationOnScreen(origin)
        val snapshot = MiraxApp.instance.session.snapshot()
        val mode = snapshot.selectedMode ?: throw AssertionError("Projection mode missing during geometry capture")
        Log.i(
            TAG,
            "SURFACE_GEOMETRY phase=$phase size=${surface.width}x${surface.height} " +
                "origin=(${origin[0]},${origin[1]}) visible=${surfaceBounds(activity)} " +
                "picture=${mode.width}x${mode.height} scale=${snapshot.pictureScale} matrix=${surface.matrix}",
        )
    }

    private fun surfaceViewOf(view: View?): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            surfaceViewOf(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun inject(instrumentation: Instrumentation, downTime: Long, action: Int, changedId: Int, pointers: List<Pointer>) {
        val properties = pointers.map { pointer ->
            PointerProperties().apply { id = pointer.id; toolType = pointer.toolType }
        }.toTypedArray()
        val coords = pointers.map { pointer ->
            PointerCoords().apply { x = pointer.x; y = pointer.y; pressure = pointer.pressure; size = 1f }
        }.toTypedArray()
        val actionIndex = if (action == ACTION_POINTER_DOWN || action == ACTION_POINTER_UP) {
            pointers.indexOfFirst { it.id == changedId }.also { require(it >= 0) }
        } else {
            0
        }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(),
            action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            pointers.size, properties, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            assertTrue(
                "injectInputEvent rejected action=${event.actionMasked} pointers=${event.pointerCount}",
                instrumentation.uiAutomation.injectInputEvent(event, true),
            )
        } finally {
            event.recycle()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask<T> { block() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task)
        return task.get()
    }

    private fun injectionDelayMs(): Long =
        InstrumentationRegistry.getArguments().getString(ARG_INJECTION_DELAY_MS)?.toLongOrNull()
            ?.takeIf { it >= 0L } ?: DEFAULT_INJECTION_DELAY_MS

    private data class Pointer(
        val id: Int,
        var x: Float,
        var y: Float,
        val toolType: Int = MotionEvent.TOOL_TYPE_FINGER,
        var pressure: Float = 1f,
    )

    private companion object {
        const val TAG = "MiraxMultiTouch"
        const val CONNECT_TIMEOUT_MS = 180_000L
        const val POLL_MS = 500L
        const val STEP_MS = 40L
        const val MOVE_STEPS = 25
        const val SECOND_MOVE_STEPS = 5
        const val DEFAULT_INJECTION_DELAY_MS = 15_000L
        const val ARG_INJECTION_DELAY_MS = "injectionDelayMs"
    }
}
