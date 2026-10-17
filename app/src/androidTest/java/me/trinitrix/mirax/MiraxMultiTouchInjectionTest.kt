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
import me.trinitrix.mirax.session.ScreenPhase
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
            repeat(MOVE_STEPS) {
                first.x += bounds.width() * 0.1f / MOVE_STEPS
                second.x -= bounds.width() * 0.1f / MOVE_STEPS
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

    private fun surfaceViewOf(view: View?): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            surfaceViewOf(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun inject(instrumentation: Instrumentation, downTime: Long, action: Int, changedId: Int, pointers: List<Pointer>) {
        val properties = pointers.map { pointer ->
            PointerProperties().apply { id = pointer.id; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }.toTypedArray()
        val coords = pointers.map { pointer ->
            PointerCoords().apply { x = pointer.x; y = pointer.y; pressure = 1f; size = 1f }
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

    private data class Pointer(val id: Int, var x: Float, var y: Float)

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
