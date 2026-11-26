package me.trinitrix.mirax.ui.standby.framing

import android.content.Context
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import me.trinitrix.mirax.R
import me.trinitrix.mirax.data.standby.StandbyFraming
import me.trinitrix.mirax.standbyprobe.StandbyProbeActivity
import me.trinitrix.mirax.ui.standby.foreground.awaitForegroundLayout
import me.trinitrix.mirax.ui.standby.media.StandbyFramingControlsModel
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android view input and transaction behavior, without a media decoder. */
@RunWith(AndroidJUnit4::class)
class StandbyFramingEditorViewTest {
    @Test
    fun missingOrInvalidContentSizeDoesNotEnableEdits() = onMain {
        val context = themedContext()
        val model = StandbyFramingControlsModel()
        val previews = mutableListOf<StandbyFraming>()
        val commits = mutableListOf<StandbyFraming>()
        val editor = StandbyFramingEditorView(
            context, View(context), model, { previews.add(it) }, { commits.add(it) }, {},
        )
        layout(editor, 480, 760)
        editor.setPlaybackReady(true)
        editor.setContentSize(0f, 900f)
        editor.setContentSize(Float.NaN, 900f)
        editor.setContentSize(1600f, Float.POSITIVE_INFINITY)
        assertThat(button(editor, R.string.standby_framing_zoom_in).isEnabled).isFalse()
        assertThat(button(editor, R.string.standby_media_confirm).isEnabled).isFalse()
        button(editor, R.string.standby_framing_zoom_in).performClick()
        button(editor, R.string.standby_media_confirm).performClick()
        assertThat(model.isPreviewing).isFalse()
        assertThat(previews).isEmpty()
        assertThat(commits).isEmpty()
    }

    @Test
    fun playbackReadinessGatesConfirmationWithoutResolvingPendingCommit() = onMain {
        val context = themedContext()
        var requests = 0
        val editor = StandbyFramingEditorView(context, View(context), StandbyFramingControlsModel(), {}, { requests++ }, {})
        layout(editor, 480, 760)
        editor.setContentSize(1600f, 900f)
        layout(editor, 480, 760)
        val confirm = button(editor, R.string.standby_media_confirm)

        assertThat(confirm.isEnabled).isFalse()
        confirm.performClick()
        assertThat(requests).isEqualTo(0)
        editor.setPlaybackReady(true)
        assertThat(confirm.isEnabled).isTrue()
        editor.setPlaybackReady(false)
        assertThat(confirm.isEnabled).isFalse()
        confirm.performClick()
        assertThat(requests).isEqualTo(0)

        editor.setPlaybackReady(true)
        confirm.performClick()
        assertThat(requests).isEqualTo(1)
        assertThat(editor.isCommitPending).isTrue()
        editor.setPlaybackReady(false)
        assertThat(editor.isCommitPending).isTrue()
        assertThat(confirm.isEnabled).isFalse()
        editor.setPlaybackReady(true)
        assertThat(editor.isCommitPending).isTrue()
        assertThat(confirm.isEnabled).isFalse()
        editor.setPlaybackReady(false)
        assertThat(editor.failCommit("Save failed")).isTrue()
        assertThat(confirm.isEnabled).isFalse()
        editor.setPlaybackReady(true)
        assertThat(confirm.isEnabled).isTrue()
    }

    @Test
    fun touchPinchAndPointerSwitchDoNotJump() = onMain {
        val context = themedContext()
        val model = StandbyFramingControlsModel(StandbyFraming(0.5f, 0.5f, 2f))
        val editor = StandbyFramingEditorView(context, View(context), model, {}, {}, {})
        layout(editor, 480, 760)
        editor.setContentSize(1600f, 900f)
        editor.setPlaybackReady(true)
        layout(editor, 480, 760)
        val preview = interaction(editor)
        val width = preview.width.toFloat()
        val y = preview.height / 2f
        val down = SystemClock.uptimeMillis()

        touch(preview, down, down, MotionEvent.ACTION_DOWN, point(0, width * 0.35f, y))
        touch(preview, down, down + 10, MotionEvent.ACTION_POINTER_DOWN or
            (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            point(0, width * 0.35f, y), point(1, width * 0.65f, y))
        assertThat(model.visible().centerX).isEqualTo(0.5f)
        touch(preview, down, down + 20, MotionEvent.ACTION_MOVE,
            point(0, width * 0.25f, y), point(1, width * 0.75f, y))
        touch(preview, down, down + 30, MotionEvent.ACTION_MOVE,
            point(0, width * 0.2f, y), point(1, width * 0.8f, y))
        val pinched = model.visible()
        assertThat(pinched.zoom).isGreaterThan(2f)
        assertThat(pinched.centerX).isEqualTo(0.5f)
        touch(preview, down, down + 40, MotionEvent.ACTION_POINTER_UP,
            point(0, width * 0.2f, y), point(1, width * 0.8f, y))
        touch(preview, down, down + 50, MotionEvent.ACTION_MOVE, point(1, width * 0.8f, y))
        assertThat(model.visible()).isEqualTo(pinched)
        touch(preview, down, down + 60, MotionEvent.ACTION_MOVE, point(1, width * 0.84f, y))
        assertThat(model.visible().centerX).isLessThan(pinched.centerX)
        touch(preview, down, down + 70, MotionEvent.ACTION_UP, point(1, width * 0.84f, y))
    }

    @Test
    fun keyboardAccessibilityAndRealButtonsAreAvailable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val model = StandbyFramingControlsModel(StandbyFraming(0.5f, 0.5f, 2f))
        val windowFocused = CountDownLatch(1)
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) windowFocused.countDown()
        }
        var observer: ViewTreeObserver? = null
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var context: Context
            lateinit var editor: StandbyFramingEditorView
            lateinit var preview: View
            try {
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    observer = decor.viewTreeObserver
                    observer?.addOnWindowFocusChangeListener(focusListener)
                    if (decor.hasWindowFocus()) windowFocused.countDown()
                    context = ContextThemeWrapper(activity, R.style.Theme_Mirax)
                    editor = StandbyFramingEditorView(context, View(context), model, {}, {}, {})
                    activity.contentRoot.addView(
                        editor,
                        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                    )
                }
                assertThat(windowFocused.await(5, TimeUnit.SECONDS)).isTrue()
                awaitForegroundLayout(scenario, editor)
                scenario.onActivity {
                    editor.setContentSize(1600f, 900f)
                    editor.setPlaybackReady(true)
                }
                awaitForegroundLayout(scenario, editor)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertThat(activity.window.decorView.hasWindowFocus()).isTrue()
                    preview = interaction(editor)
                    assertThat(preview.isFocusable).isTrue()
                    assertThat(preview.isInTouchMode).isFalse()
                    assertThat(preview.contentDescription.toString())
                        .isEqualTo(context.getString(R.string.standby_framing_preview_description))
                    assertThat(preview.requestFocus()).isTrue()
                    assertThat(preview.isFocused).isTrue()
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    val afterKey = model.visible()
                    assertThat(afterKey.centerX).isLessThan(0.5f)

                    val action = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                    val info = preview.createAccessibilityNodeInfo()
                    assertThat(info.actionList.any {
                        it.id == action && it.label == context.getString(R.string.standby_framing_move_left)
                    }).isTrue()
                    assertThat(preview.performAccessibilityAction(action, null)).isTrue()
                    assertThat(model.visible().centerX).isGreaterThan(afterKey.centerX)

                    for (id in listOf(
                        R.string.standby_framing_zoom_in,
                        R.string.standby_framing_zoom_out,
                        R.string.standby_media_confirm,
                        R.string.standby_media_cancel,
                    )) {
                        val button = button(editor, id)
                        assertThat(button.width).isAtLeast(dp(context, 48))
                        assertThat(button.height).isAtLeast(dp(context, 48))
                        assertThat(button.isFocusable).isTrue()
                    }
                    val beforeZoom = model.visible().zoom
                    button(editor, R.string.standby_framing_zoom_in).performClick()
                    assertThat(model.visible().zoom).isGreaterThan(beforeZoom)
                    button(editor, R.string.standby_framing_zoom_out).performClick()
                    assertThat(model.visible().zoom).isWithin(0.00001f).of(beforeZoom)
                    assertThat(preview.requestFocus()).isTrue()
                    assertThat(preview.isFocused).isTrue()
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                instrumentation.waitForIdleSync()
                scenario.onActivity {
                    assertThat(button(editor, R.string.standby_framing_zoom_in).isFocused).isTrue()
                }
            } finally {
                scenario.onActivity { activity ->
                    observer?.takeIf { it.isAlive }?.removeOnWindowFocusChangeListener(focusListener)
                    activity.contentRoot.removeAllViews()
                }
            }
        }
    }

    @Test
    fun resizeAndFailedCommitKeepDraftAndBlockDuplicateActions() = onMain {
        val context = themedContext()
        val baseline = StandbyFraming(0.5f, 0.5f, 2f)
        val model = StandbyFramingControlsModel(baseline)
        val previews = mutableListOf<StandbyFraming>()
        val commits = mutableListOf<StandbyFraming>()
        var canceled = 0
        val media = View(context)
        val editor = StandbyFramingEditorView(
            context, media, model, { previews.add(it) }, { commits.add(it) }, { canceled++ },
        )
        layout(editor, 480, 760)
        editor.setContentSize(1600f, 900f)
        editor.setPlaybackReady(true)
        layout(editor, 480, 760)
        assertThat(media.parent).isNotNull()
        val countBeforeZoom = previews.size
        button(editor, R.string.standby_framing_zoom_in).performClick()
        val draft = model.visible()
        assertThat(previews.size).isEqualTo(countBeforeZoom + 1)
        assertThat(previews.last()).isEqualTo(draft)
        button(editor, R.string.standby_media_confirm).performClick()
        button(editor, R.string.standby_media_confirm).performClick()
        button(editor, R.string.standby_media_cancel).performClick()
        assertThat(commits).containsExactly(draft)
        assertThat(canceled).isEqualTo(0)
        assertThat(editor.isCommitPending).isTrue()
        assertThat(find(editor) {
            it is TextView && it.text == context.getString(R.string.standby_framing_saving) &&
                it.visibility == View.VISIBLE
        }).isNotNull()
        assertThat(button(editor, R.string.standby_media_confirm).isEnabled).isFalse()
        assertThat(button(editor, R.string.standby_media_cancel).isEnabled).isFalse()
        assertThat(model.committed).isEqualTo(baseline)
        assertThat(editor.failCommit("Save failed")).isTrue()
        assertThat(find(editor) {
            it is TextView && it.text == "Save failed" && it.visibility == View.VISIBLE
        }).isNotNull()
        assertThat(model.visible()).isEqualTo(draft)

        val beforeInvalidSize = previews.size
        editor.setContentSize(Float.NaN, 900f)
        editor.setContentSize(0f, 900f)
        editor.setContentSize(Float.POSITIVE_INFINITY, 900f)
        editor.setContentSize(1600f, Float.NEGATIVE_INFINITY)
        assertThat(previews.size).isEqualTo(beforeInvalidSize)
        assertThat(model.visible()).isEqualTo(draft)
        val beforeResize = previews.size
        layout(editor, 760, 480)
        assertThat(model.visible()).isEqualTo(draft)
        assertThat(previews.size).isGreaterThan(beforeResize)
        button(editor, R.string.standby_media_confirm).performClick()
        assertThat(commits).containsExactly(draft, draft)
        assertThat(model.committed).isEqualTo(baseline)
        assertThat(editor.completeCommit()).isTrue()
        assertThat(editor.isConfirmed).isTrue()
        assertThat(model.committed).isEqualTo(draft)
        assertThat(editor.completeCommit()).isFalse()
    }

    @Test
    fun resizeDuringTouchRequiresFreshDownWithoutMovingDraft() = onMain {
        val context = themedContext()
        val model = StandbyFramingControlsModel(StandbyFraming(0.5f, 0.5f, 2f))
        val editor = StandbyFramingEditorView(context, View(context), model, {}, {}, {})
        layout(editor, 480, 760)
        editor.setContentSize(1600f, 900f)
        editor.setPlaybackReady(true)
        layout(editor, 480, 760)
        val preview = interaction(editor)
        val down = SystemClock.uptimeMillis()
        touch(preview, down, down, MotionEvent.ACTION_DOWN, point(0, 100f, 100f))
        val beforeResize = model.visible()
        layout(editor, 760, 480)
        touch(preview, down, down + 10, MotionEvent.ACTION_MOVE, point(0, 300f, 100f))
        assertThat(model.visible()).isEqualTo(beforeResize)
        touch(preview, down, down + 20, MotionEvent.ACTION_UP, point(0, 300f, 100f))
        touch(preview, down + 30, down + 30, MotionEvent.ACTION_DOWN, point(0, 100f, 100f))
        touch(preview, down + 30, down + 40, MotionEvent.ACTION_MOVE, point(0, 140f, 100f))
        assertThat(model.visible().centerX).isLessThan(beforeResize.centerX)
        touch(preview, down + 30, down + 50, MotionEvent.ACTION_UP, point(0, 140f, 100f))
    }

    @Test
    fun recreatedPendingEditorReusesPreviewAndRetainedDraft() = onMain {
        val context = themedContext()
        val baseline = StandbyFraming(0.5f, 0.5f, 2f)
        val model = StandbyFramingControlsModel(baseline)
        val media = View(context)
        var requests = 0
        val original = StandbyFramingEditorView(context, media, model, {}, { requests++ }, {})
        layout(original, 480, 760)
        original.setContentSize(1600f, 900f)
        original.setPlaybackReady(true)
        layout(original, 480, 760)
        button(original, R.string.standby_framing_zoom_in).performClick()
        val draft = model.visible()
        button(original, R.string.standby_media_confirm).performClick()
        assertThat(requests).isEqualTo(1)
        val parent = media.parent as ViewGroup
        parent.removeView(media)
        val recreated = StandbyFramingEditorView(context, media, model, {}, { requests++ }, {}, true)
        layout(recreated, 760, 480)
        recreated.setContentSize(1600f, 900f)
        recreated.setPlaybackReady(true)
        layout(recreated, 760, 480)
        assertThat(recreated.isCommitPending).isTrue()
        assertThat(recreated.visibleFraming).isEqualTo(draft)
        button(recreated, R.string.standby_media_cancel).performClick()
        button(recreated, R.string.standby_media_confirm).performClick()
        assertThat(requests).isEqualTo(1)
        assertThat(model.committed).isEqualTo(baseline)
        assertThat(recreated.completeCommit()).isTrue()
        assertThat(model.committed).isEqualTo(draft)
    }

    @Test
    fun cancelRestoresBaselineBeforeNotifyingHostOnce() = onMain {
        val context = themedContext()
        val baseline = StandbyFraming(0.4f, 0.6f, 2f)
        val model = StandbyFramingControlsModel(baseline)
        val previews = mutableListOf<StandbyFraming>()
        var canceled = 0
        val editor = StandbyFramingEditorView(context, View(context), model,
            { previews.add(it) }, {}, {
                assertThat(previews.last()).isEqualTo(baseline)
                canceled++
            })
        layout(editor, 480, 760)
        editor.setContentSize(1600f, 900f)
        editor.setPlaybackReady(true)
        layout(editor, 480, 760)
        button(editor, R.string.standby_framing_zoom_in).performClick()
        assertThat(model.visible()).isNotEqualTo(baseline)
        button(editor, R.string.standby_media_cancel).performClick()
        button(editor, R.string.standby_media_cancel).performClick()
        assertThat(canceled).isEqualTo(1)
        assertThat(model.visible()).isEqualTo(baseline)
        assertThat(model.committed).isEqualTo(baseline)
    }

    private fun themedContext(): Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Mirax,
    )

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }
    }

    private fun dp(context: Context, value: Int): Int =
        (context.resources.displayMetrics.density * value + 0.5f).toInt()

    private fun layout(view: View, widthDp: Int, heightDp: Int) {
        val width = dp(view.context, widthDp)
        val height = dp(view.context, heightDp)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun interaction(editor: View): View = requireNotNull(find(editor) {
        it.contentDescription == it.context.getString(R.string.standby_framing_preview_description)
    })

    private fun button(editor: View, label: Int): Button = requireNotNull(find(editor) {
        it is Button && it.text == it.context.getString(label)
    } as? Button)

    private fun find(view: View, predicate: (View) -> Boolean): View? {
        if (predicate(view)) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val found = find(view.getChildAt(index), predicate)
                if (found != null) return found
            }
        }
        return null
    }

    private data class Point(val id: Int, val x: Float, val y: Float)

    private fun point(id: Int, x: Float, y: Float): Point = Point(id, x, y)

    private fun touch(view: View, down: Long, time: Long, action: Int, vararg points: Point) {
        val properties = points.map { point ->
            MotionEvent.PointerProperties().apply {
                id = point.id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }.toTypedArray()
        val coords = points.map { point ->
            MotionEvent.PointerCoords().apply {
                x = point.x
                y = point.y
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()
        val event = MotionEvent.obtain(
            down, time, action, points.size, properties, coords, 0, 0,
            1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            assertThat(view.dispatchTouchEvent(event)).isTrue()
        } finally {
            event.recycle()
        }
    }
}
