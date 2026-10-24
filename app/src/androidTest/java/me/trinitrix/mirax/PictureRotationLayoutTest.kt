package me.trinitrix.mirax

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertThat
import me.trinitrix.mirax.session.ControlPanelEntry
import me.trinitrix.mirax.session.PictureRotationReport
import me.trinitrix.mirax.session.PictureScale
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.VideoMode
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.Test
import org.junit.runner.RunWith

/** Observes a static mock picture through real Activity rotation and display composition. */
@RunWith(AndroidJUnit4::class)
class PictureRotationLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<MiraxApp>()
    private val device = UiDevice.getInstance(instrumentation)

    /** A round trip must converge without changing scale or submitting replacement picture frames. */
    @Test
    fun proportional_rotationRoundTrip_keepsStaticPictureCentered() {
        val session = app.session
        val original = session.snapshot()
        val originalSettings = session.exportSettings()
        check(!original.advertisingEnabled && !original.showPicture && original.phase != ScreenPhase.CONNECTING) {
            "Rotation tracer requires an idle receiver, not an active or advertised connection"
        }
        check(PictureActivity.current() == null) { "Another projection Activity is already present" }
        check(original.effects.isEmpty()) { "Rotation tracer requires no pending session effects" }
        val preferences = app.getSharedPreferences("mirax_session", Context.MODE_PRIVATE)
        val persisted = preferences.all.toMap()
        val originalRotation = device.executeShellCommand("cmd window user-rotation").trim()
        val originalAngle = device.executeShellCommand("settings get system user_rotation").trim()
        check(originalRotation == "free" || originalRotation.matches(Regex("lock [0-3]"))) {
            "Unexpected rotation mode: $originalRotation"
        }
        check(originalAngle in listOf("0", "1", "2", "3")) { "Unexpected rotation angle: $originalAngle" }
        val originalDisplayRotation = device.displayRotation
        val artifacts = File(app.cacheDir, "picture-rotation-${System.currentTimeMillis()}")
        val painter = StaticPicture()
        var scenario: ActivityScenario<PictureActivity>? = null
        var holder: SurfaceHolder? = null
        try {
            instrumentation.runOnMainSync {
                session.handle(SessionAction.SetPictureScale(PictureScale.PROPORTIONAL))
                session.handle(SessionAction.SetOrientationLock(false))
                session.handle(SessionAction.SetCameraCutoutAffectsLayout(false))
                session.handle(SessionAction.SetShowDebugOverlay(false))
                session.handle(SessionAction.SetControlPanelEntry(ControlPanelEntry.VOLUME_KEYS))
            }
            device.executeShellCommand("cmd window user-rotation lock 0")
            val active = ActivityScenario.launch<PictureActivity>(
                Intent(app, PictureActivity::class.java).putExtra("mirax_mock_session", true),
            )
            scenario = active
            var initialActivity: PictureActivity? = null
            active.onActivity { activity ->
                initialActivity = activity
                session.handle(SessionAction.CollapseControlPanel)
                PictureActivity.refreshIfShowing()
                holder = pictureSurface(activity).holder.also { it.addCallback(painter) }
            }
            awaitScene(active, 0)
            painter.draw(checkNotNull(holder))
            for ((label, rotation) in listOf("before" to 0, "rotated" to 1, "returned" to 0)) {
                if (label != "before") device.executeShellCommand("cmd window user-rotation lock $rotation")
                awaitScene(active, rotation)
                assertComposition(active, rotation, artifacts, label)
                active.onActivity { activity ->
                    assertThat(activity).isSameInstanceAs(initialActivity)
                    val snapshot = session.snapshot()
                    assertThat(snapshot.selectedMode).isEqualTo(VideoMode(1920, 1080, 60))
                    assertThat(snapshot.pictureScale).isEqualTo(PictureScale.PROPORTIONAL)
                    assertThat(session.exportSettings().preferredMode).isEqualTo(originalSettings.preferredMode)
                    assertThat(snapshot.controlPanelExpanded).isFalse()
                }
            }
        } finally {
            try {
                instrumentation.runOnMainSync { holder?.removeCallback(painter) }
                scenario?.close()
            } finally {
                try {
                    instrumentation.runOnMainSync {
                        session.handle(SessionAction.ConnectionEnded)
                        session.handle(SessionAction.SetPictureScale(original.pictureScale))
                        session.handle(SessionAction.SetOrientationLock(original.orientationLockEnabled))
                        session.handle(SessionAction.SetCameraCutoutAffectsLayout(original.cameraCutoutAffectsLayout))
                        session.handle(SessionAction.SetShowDebugOverlay(original.showDebugOverlay))
                        session.handle(SessionAction.SetControlPanelEntry(original.controlPanelEntry))
                        session.report(PictureRotationReport(originalDisplayRotation * 90))
                    }
                    restorePreferences(preferences, persisted)
                } finally {
                    if (originalRotation == "free") {
                        device.executeShellCommand("cmd window user-rotation lock $originalAngle")
                    }
                    device.executeShellCommand("cmd window user-rotation $originalRotation")
                }
            }
        }
    }

    /** @return the existing picture surface, without adding a production test accessor */
    private fun pictureSurface(activity: PictureActivity): SurfaceView =
        (activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout)
            .children.filterIsInstance<SurfaceView>().single()

    /** A read-only snapshot separates requested geometry, actual View bounds, and buffer bounds. */
    private data class Scene(
        val rotation: Int,
        val window: Rect,
        val panel: Rect,
        val picture: Rect,
        val buffer: Rect,
        val requestedWidth: Int,
        val requestedHeight: Int,
        val ready: Boolean,
    ) {
        /** @return the independently calculated centered 16:9 rectangle in screen coordinates */
        fun expectedPicture(): Rect {
            val factor = min(panel.width() / 1920.0, panel.height() / 1080.0)
            val width = (1920 * factor).roundToInt()
            val height = (1080 * factor).roundToInt()
            val left = panel.left + (panel.width() - width) / 2
            val top = panel.top + (panel.height() - height) / 2
            return Rect(left, top, left + width, top + height)
        }
    }

    /** @return current geometry; reading it never asks the app to lay out or refresh */
    private fun scene(scenario: ActivityScenario<PictureActivity>): Scene {
        var result: Scene? = null
        scenario.onActivity { activity ->
            val surface = pictureSurface(activity)
            val stage = surface.parent as FrameLayout
            fun bounds(view: android.view.View): Rect {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
            }
            val panel = bounds(stage).apply {
                left += stage.paddingLeft
                top += stage.paddingTop
                right -= stage.paddingRight
                bottom -= stage.paddingBottom
            }
            result = Scene(
                rotation = checkNotNull(activity.display).rotation,
                window = Rect(activity.windowManager.currentWindowMetrics.bounds),
                panel = panel,
                picture = bounds(surface),
                buffer = Rect(surface.holder.surfaceFrame),
                requestedWidth = surface.layoutParams.width,
                requestedHeight = surface.layoutParams.height,
                ready = surface.holder.surface.isValid && !stage.isLayoutRequested && !stage.isInLayout,
            )
        }
        return checkNotNull(result)
    }

    /** Wait for the display transition separately from the bounded compositor-convergence check. */
    private fun awaitScene(scenario: ActivityScenario<PictureActivity>, rotation: Int) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var last: Scene
        do {
            last = scene(scenario)
            if (last.rotation == rotation && last.ready && !last.panel.isEmpty && last.picture.width() > 0) return
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("Display transition did not settle: target=$rotation scene=$last")
    }

    /** Check independent geometry and whole-display pixels without refreshing the static source. */
    private fun assertComposition(
        scenario: ActivityScenario<PictureActivity>,
        rotation: Int,
        artifacts: File,
        label: String,
    ) {
        val deadline = SystemClock.uptimeMillis() + 2_000
        var failure = "No display capture"
        do {
            val before = scene(scenario)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            if (bitmap != null) {
                try {
                    val after = scene(scenario)
                    failure = when {
                        before != after || after.rotation != rotation || !after.ready -> "Geometry changed during capture"
                        else -> compositionProblem(after, bitmap)
                    }.orEmpty()
                    if (failure.isEmpty() || SystemClock.uptimeMillis() >= deadline) {
                        check(artifacts.isDirectory || artifacts.mkdirs()) { "Cannot create $artifacts" }
                        File(artifacts, "$label.png").outputStream().use { output ->
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Cannot save display capture" }
                        }
                        Log.i(TAG, "$label: $after capture=${bitmap.width}x${bitmap.height} file=$artifacts/$label.png $failure")
                    }
                    if (failure.isEmpty()) return
                } finally {
                    bitmap.recycle()
                }
            }
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("$label: $failure; artifacts=$artifacts")
    }

    /** @return a user-visible geometry or compositor mismatch, or null when both agree */
    private fun compositionProblem(scene: Scene, bitmap: Bitmap): String? {
        val expected = scene.expectedPicture()
        if (abs(scene.picture.left - expected.left) > 2 || abs(scene.picture.top - expected.top) > 2 ||
            abs(scene.picture.right - expected.right) > 2 || abs(scene.picture.bottom - expected.bottom) > 2
        ) return "Picture bounds differ: expected=$expected scene=$scene"
        if (scene.requestedWidth != expected.width() || scene.requestedHeight != expected.height()) {
            return "Requested picture size differs: expected=$expected scene=$scene"
        }
        if (scene.buffer.width() != 1920 || scene.buffer.height() != 1080) return "Source buffer changed: $scene"
        if (scene.window.left != 0 || scene.window.top != 0 || !scene.window.contains(scene.panel)) {
            return "Tracer requires a full-display window: $scene"
        }
        val scaleX = bitmap.width.toDouble() / scene.window.width()
        val scaleY = bitmap.height.toDouble() / scene.window.height()
        if (abs(scaleX - scaleY) > 0.01) return "Capture orientation differs from logical window: $scene"
        val samples = listOf(
            Triple(0.1, 0.1, Color.RED), Triple(0.9, 0.1, Color.GREEN),
            Triple(0.1, 0.9, Color.BLUE), Triple(0.9, 0.9, Color.YELLOW),
            Triple(0.5, 0.5, Color.WHITE),
        )
        for ((x, y, color) in samples) {
            val px = ((expected.left + expected.width() * x) * scaleX).roundToInt()
            val py = ((expected.top + expected.height() * y) * scaleY).roundToInt()
            if (px !in 0 until bitmap.width || py !in 0 until bitmap.height) return "Sample outside display: ($px,$py)"
            val actual = bitmap.getPixel(px, py)
            if (!sameColor(actual, color)) return "Wrong pixel at ($px,$py): actual=$actual expected=$color scene=$scene"
        }
        val margins = listOf(
            Rect(scene.panel.left, scene.panel.top, expected.left, scene.panel.bottom),
            Rect(expected.right, scene.panel.top, scene.panel.right, scene.panel.bottom),
            Rect(scene.panel.left, scene.panel.top, scene.panel.right, expected.top),
            Rect(scene.panel.left, expected.bottom, scene.panel.right, scene.panel.bottom),
        )
        for (margin in margins.filter { it.width() > 8 && it.height() > 8 }) {
            val x = (margin.exactCenterX() * scaleX).roundToInt().coerceIn(0, bitmap.width - 1)
            val y = (margin.exactCenterY() * scaleY).roundToInt().coerceIn(0, bitmap.height - 1)
            if (!sameColor(bitmap.getPixel(x, y), Color.BLACK)) return "Picture escaped into a margin: $scene"
        }
        return null
    }

    /** @return whether composition preserved the source marker within a small channel tolerance */
    private fun sameColor(actual: Int, expected: Int): Boolean =
        abs(Color.red(actual) - Color.red(expected)) <= 24 &&
            abs(Color.green(actual) - Color.green(expected)) <= 24 &&
            abs(Color.blue(actual) - Color.blue(expected)) <= 24

    /** Redraws only when a genuinely new Surface needs its initial static source image. */
    private class StaticPicture : SurfaceHolder.Callback {
        private var newlyCreated = false

        /** Mark a real lifecycle transition; ordinary view size changes do not redraw the source. */
        override fun surfaceCreated(holder: SurfaceHolder) {
            newlyCreated = true
            Log.i(TAG, "Static source surface created")
        }

        /** Supply a frame only after creation, not for every size or transform callback. */
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.i(TAG, "Static source surface changed: ${width}x$height new=$newlyCreated")
            if (newlyCreated) {
                draw(holder)
                newlyCreated = false
            }
        }

        /** Stop referring to the destroyed surface until the next creation callback. */
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            newlyCreated = false
            Log.i(TAG, "Static source surface destroyed")
        }

        /** @param holder valid source buffer to fill completely, independent of its display bounds */
        fun draw(holder: SurfaceHolder) {
            val canvas = checkNotNull(holder.lockCanvas()) { "Static source surface is unavailable" }
            try {
                val paint = android.graphics.Paint()
                val halfWidth = canvas.width / 2f
                val halfHeight = canvas.height / 2f
                canvas.drawColor(Color.RED)
                paint.color = Color.GREEN
                canvas.drawRect(halfWidth, 0f, canvas.width.toFloat(), halfHeight, paint)
                paint.color = Color.BLUE
                canvas.drawRect(0f, halfHeight, halfWidth, canvas.height.toFloat(), paint)
                paint.color = Color.YELLOW
                canvas.drawRect(halfWidth, halfHeight, canvas.width.toFloat(), canvas.height.toFloat(), paint)
                paint.color = Color.WHITE
                canvas.drawRect(halfWidth * 0.9f, halfHeight * 0.9f, halfWidth * 1.1f, halfHeight * 1.1f, paint)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    /** Restore exact persisted values and key presence without clearing unrelated preference files. */
    private fun restorePreferences(preferences: SharedPreferences, original: Map<String, *>) {
        val editor = preferences.edit()
        for (key in preferences.all.keys - original.keys) editor.remove(key)
        for ((key, value) in original) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                null -> editor.remove(key)
                else -> error("Unsupported preference type for $key")
            }
        }
        check(editor.commit()) { "Could not restore session preferences" }
    }

    private companion object {
        const val TAG = "PictureRotationTest"
    }
}
