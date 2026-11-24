package me.trinitrix.mirax.ui.standby.foreground

import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.IBinder
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.ReplacementSpan
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import me.trinitrix.mirax.R
import me.trinitrix.mirax.awaitActivityAction
import me.trinitrix.mirax.session.PrePlayFailureCause
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.standbyprobe.StandbyProbeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Native candidate regressions; parent owns execution and visual acceptance. */
@RunWith(AndroidJUnit4::class)
class StandbyForegroundViewTest {
    /** Checks native roles and callback replacement with local synthetic states only. */
    @Test
    fun menuAndNoticeActionsExposeButtonRoles() {
        val fixture = AttachedNativeRolesFixture()
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        val bodyAssertion = AtomicReference<AssertionError?>(null)
        try {
            // shortcut: ActivityScenario launch/close cannot be interrupted here, require the parent's external 120s watchdog.
            ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
                var primary: Throwable? = null
                try {
                    fixture.awaitWindow(scenario, deadline)
                    awaitActivityAction(scenario, fixture.remaining(deadline)) { activity ->
                        fixture.requireHost(activity)
                        try {
                            menuAndNoticeActionsExposeButtonRoles(fixture)
                        } catch (failure: AssertionError) {
                            // Retain the original assertion even if the bounded wait has already timed out.
                            if (failure !is NativeRolesEntryIncomplete) bodyAssertion.compareAndSet(null, failure)
                            throw failure
                        }
                    }
                    fixture.remaining(deadline)
                } catch (failure: Throwable) {
                    primary = failure
                    throw failure
                } finally {
                    try {
                        val cleanupDeadline = SystemClock.elapsedRealtime() + 5_000L
                        awaitActivityAction(scenario, fixture.remaining(cleanupDeadline)) { fixture.removeOwnedRoots() }
                        fixture.remaining(cleanupDeadline)
                    } catch (failure: Throwable) {
                        Log.e("NATIVE_ROLES_ENTRY", "owned cleanup failed", failure)
                        val previous = primary
                        if (previous == null) throw failure
                        if (previous !== failure) previous.addSuppressed(failure)
                    }
                }
            }
        } catch (failure: Throwable) {
            Log.e("NATIVE_ROLES_ENTRY", "entry failed", failure)
            val assertion = bodyAssertion.get()
            if (assertion != null) {
                if (assertion !== failure) assertion.addSuppressed(failure)
                throw assertion
            }
            val origin = failure.stackTrace.firstOrNull()
            if (failure is AssertionError && origin?.className == "androidx.test.core.app.ActivityScenario" &&
                origin.methodName == "waitForActivityToBecomeAnyOf"
            ) throw NativeRolesEntryIncomplete("ActivityScenario lifecycle wait", failure)
            if (failure !is AssertionError) throw NativeRolesEntryIncomplete("native roles observation", failure)
            throw failure
        }
        bodyAssertion.get()?.let { throw it }
        Log.i("NATIVE_ROLES_ENTRY", "core=complete ownedCleanup=complete normalClose=returned")
    }

    /** Test-only attachment and read-only observation; neither hook may mutate node or view flags. */
    internal interface NativeRolesFixture {
        /** @param root Original rendered view. @param state Unchanged synthetic state, after both renders. */
        fun beforeActions(root: View, state: StandbyForegroundState)

        /**
         * Observes the exact node before its original assertions, without mutating or recycling it.
         * @param root Original rendered view.
         * @param state Unchanged synthetic state.
         * @param tag Original action selector.
         * @param action Actual action view.
         * @param node Actual node owned and recycled by the shared core.
         */
        fun observeActionNode(root: View, state: StandbyForegroundState, tag: String, action: View, node: AccessibilityNodeInfo)
    }

    /** @param fixture Main-thread-only opt-in attachment/observation, or null for the original detached contract. */
    internal fun menuAndNoticeActionsExposeButtonRoles(fixture: NativeRolesFixture?) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val views = listOf(
            LandscapeStandbyForegroundView(context),
            MatterStandbyForegroundView(context),
            HardlineStandbyForegroundView(context),
        )
        for (view in views) {
            val oldCalls = IntArray(5)
            val calls = IntArray(5)
            fun callbacks(counts: IntArray) = StandbyForegroundCallbacks(
                onOpenSettings = { counts[0]++ },
                onDismissFailure = { counts[1]++ },
                onShowFrozenInfo = { counts[2]++ },
                onStopBroadcastWhilePaused = { counts[3]++ },
                onToggleBroadcast = { counts[4]++ },
            )
            val states = listOf(
                StandbyForegroundState(
                    phase = ScreenPhase.READY, advertisingEnabled = false,
                    failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT, failureText = "Synthetic failure",
                ) to StandbyForegroundTags.FAILED_DISMISS,
                StandbyForegroundState(
                    phase = ScreenPhase.FROZEN, advertisingEnabled = false,
                ) to StandbyForegroundTags.FROZEN_INFO,
                StandbyForegroundState(
                    phase = ScreenPhase.WIFI_PAUSED, advertisingEnabled = true,
                ) to StandbyForegroundTags.WIFI_STOP,
            )
            for ((index, pair) in states.withIndex()) {
                val (state, actionTag) = pair
                view.render(state, "Title", "Status", "Detail", callbacks(oldCalls))
                view.render(state, "Title", "Status", "Detail", callbacks(calls))
                fixture?.beforeActions(view, state)
                for (tag in listOf(StandbyForegroundTags.MENU, actionTag)) {
                    val action = requireNotNull(view.findViewWithTag<View>(tag))
                    assertEquals(View.VISIBLE, action.visibility)
                    val node = action.createAccessibilityNodeInfo()
                    try {
                        fixture?.observeActionNode(view, state, tag, action, node)
                        assertEquals(android.widget.Button::class.java.name, node.className.toString())
                        assertTrue(node.isClickable)
                        assertTrue(node.actionList.any {
                            it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                        })
                        assertTrue(!node.contentDescription.isNullOrBlank() || !node.text.isNullOrBlank())
                    } finally {
                        node.recycle()
                    }
                    assertTrue(action.performClick())
                }
                assertEquals(index + 1, calls[0])
                for (callbackIndex in 1..3) assertEquals(if (callbackIndex <= index + 1) 1 else 0, calls[callbackIndex])
                assertEquals(0, calls[4])
                assertTrue(oldCalls.all { it == 0 })
                val dock = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST)).createAccessibilityNodeInfo()
                try {
                    val frozen = state.phase == ScreenPhase.FROZEN
                    assertEquals(
                        if (frozen) android.widget.Button::class.java.name else android.widget.Switch::class.java.name,
                        dock.className.toString(),
                    )
                    assertEquals(!frozen, dock.isCheckable)
                    assertEquals(!frozen && state.advertisingEnabled, dock.isChecked)
                } finally {
                    dock.recycle()
                }
            }
        }
    }

    /** Missing native window evidence must not be mistaken for a completed role contract. */
    private class NativeRolesEntryIncomplete(reason: String, cause: Throwable? = null) :
        AssertionError("NATIVE_ROLES_ENTRY fixture=INCOMPLETE $reason", cause)

    /** Owns only shared-core roots and their original controls in one real probe window. */
    private class AttachedNativeRolesFixture : NativeRolesFixture {
        private var host: StandbyProbeActivity? = null
        private var container: FrameLayout? = null
        private var windowToken: IBinder? = null
        private val roots = mutableListOf<OwnedRoot>()

        /** @property root Original shared-core root. @property controls Its original tagged controls. */
        private data class OwnedRoot(val root: View, val controls: Map<String, View>)

        /** @param scenario Original scenario. @param deadline One monotonic window/core deadline. */
        fun awaitWindow(scenario: ActivityScenario<StandbyProbeActivity>, deadline: Long) {
            while (true) {
                var ready = false
                awaitActivityAction(scenario, remaining(deadline)) { activity ->
                    if (host == null) {
                        requireFixture(activity.contentRoot.childCount == 0, "initial probe content not empty")
                        host = activity
                        container = activity.contentRoot
                    }
                    requireFixture(activity === host && activity.contentRoot === container && !activity.isFinishing && !activity.isDestroyed,
                        "probe host/root identity changed")
                    val root = container ?: throw NativeRolesEntryIncomplete("probe content missing")
                    val token = root.windowToken
                    if (root.isAttachedToWindow && token != null) {
                        requireFixture(root.childCount == 0, "probe content changed before role core")
                        windowToken = token
                        ready = true
                    }
                }
                remaining(deadline)
                if (ready) return
                Thread.yield()
            }
        }

        /** @param activity Observed original host. @return Its unchanged attached content root. */
        fun requireHost(activity: StandbyProbeActivity): FrameLayout {
            val root = container ?: throw NativeRolesEntryIncomplete("probe content missing")
            requireFixture(activity === host && activity.contentRoot === root && !activity.isFinishing && !activity.isDestroyed &&
                root.isAttachedToWindow && windowToken != null && root.windowToken === windowToken, "original probe host/window missing")
            return root
        }

        /** Attaches each rendered root once, preserving its original controls across all subsequent states. */
        override fun beforeActions(root: View, state: StandbyForegroundState) {
            val activity = host ?: throw NativeRolesEntryIncomplete("probe host missing")
            val parent = requireHost(activity)
            var owned = roots.firstOrNull { it.root === root }
            if (owned == null) {
                requireFixture(root.parent == null && !root.isAttachedToWindow && root.windowToken == null, "role root not detached")
                val controls = listOf(
                    StandbyForegroundTags.MENU, StandbyForegroundTags.FAILED_DISMISS,
                    StandbyForegroundTags.FROZEN_INFO, StandbyForegroundTags.WIFI_STOP, StandbyForegroundTags.BROADCAST,
                ).associateWith { tag -> root.findViewWithTag<View>(tag) ?: throw NativeRolesEntryIncomplete("original control missing") }
                val original = OwnedRoot(root, controls)
                roots.add(original)
                owned = original
                parent.addView(root)
            }
            requireFixture(root.parent === parent && root.isAttachedToWindow && root.windowToken === windowToken,
                "original root did not attach to the original window")
            requireFixture(owned.controls.all { (tag, action) ->
                root.findViewWithTag<View>(tag) === action && action.isAttachedToWindow && action.windowToken === windowToken
            }, "original controls/window changed")
        }

        /** The unchanged shared core owns, asserts and recycles the actual node. */
        override fun observeActionNode(root: View, state: StandbyForegroundState, tag: String, action: View, node: AccessibilityNodeInfo) {}

        /** Removes every owned root independently; preserves all cleanup failures without touching foreign views. */
        fun removeOwnedRoots() {
            var failure: Throwable? = null
            fun clean(action: () -> Unit) {
                try {
                    action()
                } catch (error: Throwable) {
                    Log.e("NATIVE_ROLES_ENTRY", "owned root cleanup failed", error)
                    val previous = failure
                    if (previous == null) failure = error else if (previous !== error) previous.addSuppressed(error)
                }
            }
            for (owned in roots) clean {
                if (owned.root.parent === container) container?.removeView(owned.root)
                requireFixture(owned.root.parent == null && !owned.root.isAttachedToWindow && owned.root.windowToken == null,
                    "owned root removal incomplete")
            }
            clean { requireFixture(container == null || container?.childCount == 0, "probe content not empty after owned cleanup") }
            failure?.let { throw it }
        }

        /** @param deadline Existing phase deadline. @return Positive remaining time, never a reset. */
        fun remaining(deadline: Long): Long = (deadline - SystemClock.elapsedRealtime()).also {
            requireFixture(it > 0L, "5s native roles phase deadline")
        }

        /** @param condition Required fixture evidence. @param reason Static diagnostic without user text. */
        private fun requireFixture(condition: Boolean, reason: String) {
            if (!condition) throw NativeRolesEntryIncomplete(reason)
        }
    }

    /** Genuine failures stay in notices/actions; neither render nor failure-only refresh owns center detail. */
    @Test
    fun nonMatterCenterDetail_staysEmptyThroughFailureRenderArrivalAndDismissal() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            for (locale in listOf(Locale.ENGLISH, Locale.TRADITIONAL_CHINESE)) {
                val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
                val failureText = context.getString(R.string.preplay_failed_timeout)
                for (view in listOf(LandscapeStandbyForegroundView(context), HardlineStandbyForegroundView(context))) {
                    val detail = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_DETAIL))
                    fun assertEmptyCenter() {
                        assertEquals(View.GONE, detail.visibility)
                        assertTrue(detail.text.isEmpty())
                    }
                    assertEmptyCenter()
                    for (phase in ScreenPhase.entries) {
                        var dismissCalls = 0
                        var otherActions = 0
                        val callbacks = StandbyForegroundCallbacks(
                            onDismissFailure = { dismissCalls++; view.updateFailure(null, "") },
                            onToggleBroadcast = { otherActions++ }, onOpenSettings = { otherActions++ },
                            onShowFrozenInfo = { otherActions++ }, onOpenShizuku = { otherActions++ },
                            onStopBroadcastWhilePaused = { otherActions++ },
                        )
                        val state = StandbyForegroundState(phase,
                            advertisingEnabled = phase == ScreenPhase.ADVERTISING || phase == ScreenPhase.WIFI_PAUSED,
                            shouldOfferShizuku = phase == ScreenPhase.FROZEN,
                            isProjecting = phase == ScreenPhase.CONNECTED)
                        view.render(state, "Host title", "HOST STATUS", "Arbitrary host detail", callbacks)
                        val hint = view.findViewWithTag<TextView>("foreground_hint")
                        val hintBefore = hint?.text?.toString()
                        val notice = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.FAILED))
                        val dismiss = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.FAILED_DISMISS))
                        assertEmptyCenter()
                        for ((index, renderFailure) in listOf(false, true).withIndex()) {
                            if (renderFailure) {
                                view.render(state.copy(failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT, failureText = failureText),
                                    "Host title", "HOST STATUS", failureText, callbacks)
                            } else {
                                view.updateFailure(PrePlayFailureCause.PRE_PLAY_TIMEOUT, failureText)
                            }
                            assertEmptyCenter()
                            assertEquals(View.VISIBLE, notice.visibility)
                            assertEquals(failureText, notice.text.toString())
                            assertEquals(View.VISIBLE, dismiss.visibility)
                            assertTrue(dismiss.isClickable && dismiss.isFocusable)
                            assertEquals(hintBefore, hint?.text?.toString())
                            assertEquals(0, otherActions)
                            assertTrue(dismiss.performClick())
                            assertEquals(index + 1, dismissCalls)
                            assertEquals(View.GONE, notice.visibility)
                            assertTrue(notice.text.isEmpty())
                            assertEquals(View.GONE, dismiss.visibility)
                            assertEmptyCenter()
                            assertEquals(hintBefore, hint?.text?.toString())
                        }
                        val retainedActions = when (phase) {
                            ScreenPhase.FROZEN -> listOf(StandbyForegroundTags.FROZEN_INFO, "foreground_shizuku")
                            ScreenPhase.WIFI_PAUSED -> listOf(StandbyForegroundTags.WIFI_STOP)
                            else -> listOf(StandbyForegroundTags.MENU)
                        }
                        for (tag in retainedActions) {
                            val action = requireNotNull(view.findViewWithTag<View>(tag))
                            assertEquals(View.VISIBLE, action.visibility)
                            assertTrue(action.performClick())
                        }
                        assertEquals(retainedActions.size, otherActions)
                    }
                }
            }
        }
    }

    @Test
    fun constructorsAndRepeatedRender_preserveControlsAndDoNotInvokeActions() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val views = listOf(
                LandscapeStandbyForegroundView(context),
                MatterStandbyForegroundView(context),
                HardlineStandbyForegroundView(context),
            )
            var actions = 0
            val callbacks = StandbyForegroundCallbacks(
                onToggleBroadcast = { actions++ },
                onDismissFailure = { actions++ },
                onShowFrozenInfo = { actions++ },
                onOpenSettings = { actions++ },
            )
            val state = StandbyForegroundState(
                phase = ScreenPhase.READY,
                advertisingEnabled = false,
                receiverName = "A long receiver name 接收裝置名稱",
                clockText = "12:34",
            )
            for (view in views) {
                view.render(state, "Title", "READY", "Detail", callbacks)
                val broadcast = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                val menu = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.MENU))
                view.render(state, "Title", "READY", "Detail", callbacks)
                view.updateClock("12:35")
                if (view is MatterStandbyForegroundView) {
                    assertEquals(
                        "12:35",
                        requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.CLOCK)).text.toString(),
                    )
                } else {
                    assertNull(view.findViewWithTag<View>(StandbyForegroundTags.CLOCK))
                }
                view.updateFailure(PrePlayFailureCause.PRE_PLAY_TIMEOUT, "Timeout")
                assertEquals(
                    "Timeout",
                    requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.FAILED)).text.toString(),
                )
                assertSame(broadcast, view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                assertSame(menu, view.findViewWithTag<View>(StandbyForegroundTags.MENU))
                assertEquals(0, actions)
                assertEquals(
                    View.VISIBLE,
                    requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.FAILED_DISMISS)).visibility,
                )
                view.updateFailure(null, "")
                assertEquals(
                    View.GONE,
                    requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.FAILED_DISMISS)).visibility,
                )
                view.render(state.copy(phase = ScreenPhase.FROZEN), "Title", "FROZEN", "", callbacks)
                assertEquals(View.VISIBLE, broadcast.visibility)
                assertEquals(
                    View.VISIBLE,
                    requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.FROZEN_INFO)).visibility,
                )
                assertEquals(0, actions)
            }
        }
    }

    @Test
    fun controls_invokeOnlyTheirCurrentHostCallback() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val view = LandscapeStandbyForegroundView(context)
            val state = StandbyForegroundState(ScreenPhase.READY, false)
            var oldActions = 0
            var newActions = 0
            view.render(state, "Title", "READY", "", StandbyForegroundCallbacks(onToggleBroadcast = { oldActions++ }))
            val detail = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.PHASE_DETAIL))
            assertEquals(View.GONE, detail.visibility)
            val broadcast = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
            view.render(state, "Title", "READY", "", StandbyForegroundCallbacks(onToggleBroadcast = { newActions++ }))
            assertTrue(broadcast.performClick())
            assertEquals(0, oldActions)
            assertEquals(1, newActions)
            view.render(state, "Title", "READY", "", StandbyForegroundCallbacks())
            assertEquals(View.GONE, broadcast.visibility)
        }
    }

    @Test
    fun broadcastDock_routesFrozenAndWifiToRealHostCallbacks() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val views = listOf(
                LandscapeStandbyForegroundView(context),
                MatterStandbyForegroundView(context),
                HardlineStandbyForegroundView(context),
            )
            for (view in views) {
                var toggles = 0
                var frozen = 0
                var wifiStops = 0
                val callbacks = StandbyForegroundCallbacks(
                    onToggleBroadcast = { toggles++ },
                    onShowFrozenInfo = { frozen++ },
                    onStopBroadcastWhilePaused = { wifiStops++ },
                )
                val state = StandbyForegroundState(ScreenPhase.FROZEN, false)
                view.render(state, "Title", "FROZEN", "", callbacks)
                val dock = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                assertEquals(View.VISIBLE, dock.visibility)
                val frozenNode = dock.createAccessibilityNodeInfo()
                assertEquals(android.widget.Button::class.java.name, frozenNode.className)
                assertFalse(frozenNode.isCheckable)
                frozenNode.recycle()
                assertTrue(dock.performClick())
                assertEquals(1, frozen)
                assertEquals(0, toggles)

                view.render(state.copy(phase = ScreenPhase.WIFI_PAUSED, advertisingEnabled = true), "Title", "PAUSED", "", callbacks)
                assertSame(dock, view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                val wifiNode = dock.createAccessibilityNodeInfo()
                assertEquals(android.widget.Switch::class.java.name, wifiNode.className)
                assertTrue(wifiNode.isCheckable)
                assertTrue(wifiNode.isChecked)
                wifiNode.recycle()
                assertTrue(dock.performClick())
                assertEquals(1, wifiStops)
                assertEquals(0, toggles)

                view.render(state, "Title", "FROZEN", "", StandbyForegroundCallbacks())
                assertEquals(View.GONE, dock.visibility)
                view.render(state.copy(phase = ScreenPhase.READY, isProjecting = true), "Title", "READY", "", callbacks)
                assertEquals(View.GONE, dock.visibility)
            }
        }
    }

    @Test
    fun safeInsets_recomputeFromBaseWhenInsetsShrink() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val view = MatterStandbyForegroundView(context)
            view.setPadding(3, 4, 5, 6)
            fun apply(left: Int, top: Int, right: Int, bottom: Int) {
                view.applySecurityInsets(Insets.of(left, top, right, bottom))
            }
            apply(30, 40, 50, 60)
            assertEquals(33, view.paddingLeft)
            assertEquals(66, view.paddingBottom)
            ViewCompat.dispatchApplyWindowInsets(
                view,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(1, 2, 3, 4))
                    .build(),
            )
            assertEquals(33, view.paddingLeft)
            assertEquals(66, view.paddingBottom)
            apply(1, 2, 3, 4)
            assertEquals(4, view.paddingLeft)
            assertEquals(6, view.paddingTop)
            assertEquals(8, view.paddingRight)
            assertEquals(10, view.paddingBottom)
            apply(0, 0, 0, 0)
            assertEquals(3, view.paddingLeft)
            assertEquals(4, view.paddingTop)
            assertEquals(5, view.paddingRight)
            assertEquals(6, view.paddingBottom)
        }
    }

    /** Keeps fixed plate codes distinct from localized accessibility meanings and failure notice text. */
    @Test
    fun matterStatus_usesLocalizedPhaseAndFailureWithoutReplacingViews() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            val phases = listOf(
                Triple(ScreenPhase.FROZEN, "UNAVAILABLE", R.string.standby_foreground_phase_frozen_title),
                Triple(ScreenPhase.READY, "DISCONN", R.string.standby_foreground_phase_off_title),
                Triple(ScreenPhase.ARMING, "ARMING", R.string.standby_foreground_phase_arming_title),
                Triple(ScreenPhase.ADVERTISING, "STANDBY", R.string.standby_foreground_phase_waiting_title),
                Triple(ScreenPhase.CONNECTING, "ESTABLISHING", R.string.standby_foreground_phase_connecting_title),
                Triple(ScreenPhase.CONNECTED, "CONNECTED", R.string.standby_foreground_phase_projecting_title),
                Triple(ScreenPhase.WIFI_PAUSED, "WAIT_WIFI", R.string.standby_foreground_phase_wifi_paused_title),
            )
            val failedTitleId = R.string.standby_foreground_phase_failed_title
            for (locale in listOf(Locale.ENGLISH, Locale.TRADITIONAL_CHINESE)) {
                var ownedView: MatterStandbyForegroundView? = null
                var failure: Throwable? = null
                try {
                    awaitActivityAction(scenario) { activity ->
                        val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                            setLocale(locale)
                        })
                        val view = MatterStandbyForegroundView(context)
                        ownedView = view
                        activity.contentRoot.addView(view)
                    }
                    val view = requireNotNull(ownedView)
                    awaitForegroundLayout(scenario, view)
                    awaitActivityAction(scenario) { activity ->
                        val decor = activity.window.decorView
                        assertSame("$locale mounted Matter view", activity.contentRoot, view.parent)
                        val context = view.context
                        val word = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_EYEBROW))
                        val dock = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                        val notice = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.FAILED))
                        val dismiss = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.FAILED_DISMISS))
                        var dismissCalls = 0
                        val callbacks = StandbyForegroundCallbacks(onDismissFailure = {
                            dismissCalls++
                            view.updateFailure(null, "")
                        })
                        fun assertStatus(label: String, code: String, titleId: Int, noticeText: String = "") {
                            val description = context.getString(R.string.standby_foreground_matter_status_desc, context.getString(titleId))
                            assertEquals("$label visible code", code, word.text.toString())
                            assertEquals("$label view description", description, word.contentDescription?.toString())
                            assertTrue("$label word attached and shown in a visible window",
                                word.isAttachedToWindow && word.isShown && word.windowVisibility == View.VISIBLE)
                            assertSame("$label word in probe window", decor, word.rootView)
                            assertEquals("$label node description", description, word.createAccessibilityNodeInfo().contentDescription?.toString())
                            assertEquals(label, 1f, word.alpha, 0f)
                            assertEquals(label, 1f, dock.alpha, 0f)
                            assertSame(label, word, view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_EYEBROW))
                            assertSame(label, dock, view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                            val noticeVisibility = if (noticeText.isEmpty()) View.GONE else View.VISIBLE
                            assertEquals("$label notice visibility", noticeVisibility, notice.visibility)
                            assertEquals("$label notice text", noticeText, notice.text.toString())
                            assertEquals("$label dismiss visibility", noticeVisibility, dismiss.visibility)
                        }
                        for ((phase, code, titleId) in phases) {
                            val label = "${locale.toLanguageTag()}/$phase"
                            val state = StandbyForegroundState(phase, phase == ScreenPhase.ADVERTISING, receiverName = "Test Receiver")
                            val fullRenderDetail = "Failure detail from full render"
                            val previousDismissCalls = dismissCalls
                            view.render(
                                state.copy(failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT, failureText = fullRenderDetail),
                                "Supplied failed title", "Supplied failed eyebrow", "Arbitrary failed detail", callbacks,
                            )
                            assertStatus("$label full render with cause", "ERROR", failedTitleId, fullRenderDetail)
                            assertTrue("$label dismiss click", dismiss.performClick())
                            assertEquals(label, previousDismissCalls + 1, dismissCalls)
                            assertStatus("$label dismissed", code, titleId)

                            view.render(state, "Supplied title", "Supplied eyebrow", "Arbitrary detail", callbacks)
                            assertStatus("$label phase render", code, titleId)
                            view.render(state, "Different title", "Different eyebrow", "Different detail", callbacks)
                            assertStatus("$label repeated render", code, titleId)
                            view.updateFailure(PrePlayFailureCause.PRE_PLAY_TIMEOUT, "Long host failure explanation")
                            assertStatus("$label failure arrival", "ERROR", failedTitleId, "Long host failure explanation")
                            view.updateFailure(PrePlayFailureCause.PRE_PLAY_TIMEOUT, "Changed explanation")
                            assertStatus("$label text refresh", "ERROR", failedTitleId, "Changed explanation")
                            view.updateFailure(PrePlayFailureCause.NEGOTIATION_FAILED, "Negotiation explanation")
                            assertStatus("$label cause refresh", "ERROR", failedTitleId, "Negotiation explanation")
                            view.updateFailure(null, "")
                            assertStatus("$label cleared", code, titleId)
                        }
                    }
                } catch (error: Throwable) {
                    failure = error
                    throw error
                } finally {
                    try {
                        awaitActivityAction(scenario) {
                            ownedView?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
                        }
                    } catch (cleanup: Throwable) {
                        val first = failure ?: throw cleanup
                        if (first !== cleanup) first.addSuppressed(cleanup)
                    }
                }
            }
        }
    }

    @Test
    fun matterStatus_keepsNominalSizeTrackingAndValidColorInConnecting() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val view = MatterStandbyForegroundView(context)
            view.render(
                StandbyForegroundState(ScreenPhase.CONNECTING, false),
                "", "CONNECTING", "", StandbyForegroundCallbacks(),
            )
            val word = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_EYEBROW))
            val time = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.CLOCK))
            val expected = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 20f, context.resources.displayMetrics,
            )
            assertEquals(expected, word.textSize, 0.01f)
            assertEquals(0.04f, word.letterSpacing, 0f)
            assertEquals(0.25f, time.letterSpacing, 0f)
            assertEquals(0xFFEEEEEA.toInt(), word.currentTextColor)
            assertEquals(1, word.maxLines)
        }
    }

    @Test
    fun matterBlink_staleCallbacksCannotRestartAfterProjectionOrAncestorHide() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    val container = android.widget.FrameLayout(activity)
                    val view = MatterStandbyForegroundView(activity)
                    container.addView(view)
                    activity.contentRoot.addView(container)
                    val field = MatterStandbyForegroundView::class.java.getDeclaredField("blinkCallback")
                    field.isAccessible = true
                    val state = StandbyForegroundState(ScreenPhase.CONNECTING, false, isProjecting = false)
                    val callbacks = StandbyForegroundCallbacks()
                    view.render(state, "", "CONNECTING", "", callbacks)
                    val first = field.get(view) as? Runnable
                    assertNotNull(first)
                    val light = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.MATTER_LIGHT))
                    first?.run()
                    assertEquals(0.2f, light.alpha, 0f)
                    view.render(state.copy(isProjecting = true), "", "CONNECTING", "", callbacks)
                    assertNull(field.get(view))
                    first?.run()
                    assertEquals(1f, light.alpha, 0f)
                    assertNull(field.get(view))
                    view.render(state, "", "CONNECTING", "", callbacks)
                    val second = field.get(view) as? Runnable
                    assertNotNull(second)
                    container.visibility = View.GONE
                    assertNull(field.get(view))
                    second?.run()
                    assertNull(field.get(view))
                    assertEquals(1f, light.alpha, 0f)
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    /** Checks literal copy, both source group ranges/flags, and the unchanged shortcut/native receiver widths. */
    private fun assertShortcutCopy(hint: TextView, text: CharSequence, expected: String, density: Float, receiverGroup: String) {
        assertEquals(expected, text.toString())
        val styled = text as Spanned
        val spans = styled.getSpans(0, styled.length, ReplacementSpan::class.java)
        assertEquals(2, spans.size)
        val shortcut = spans.single { it.javaClass.simpleName == "ShortcutSpan" }
        val start = expected.indexOf("Win + K")
        assertTrue(start >= 0)
        assertEquals(start, styled.getSpanStart(shortcut))
        assertEquals(start + 7, styled.getSpanEnd(shortcut))
        assertEquals(Spanned.SPAN_EXCLUSIVE_EXCLUSIVE, styled.getSpanFlags(shortcut))
        val keyPaint = Paint(hint.paint).apply { typeface = Typeface.create(hint.typeface, 600, false) }
        val expectedWidth = kotlin.math.ceil((keyPaint.measureText("Win") + keyPaint.measureText("K") +
            hint.paint.measureText(" + ") + 24f * density).toDouble()).toInt()
        assertEquals(expectedWidth, shortcut.getSize(hint.paint, styled, start, start + 7, null))
        val group = spans.single { it.javaClass.simpleName == "ReceiverGroupSpan" }
        val groupStart = expected.length - receiverGroup.length
        assertEquals(groupStart, styled.getSpanStart(group))
        assertEquals(expected.length, styled.getSpanEnd(group))
        assertEquals(receiverGroup, styled.subSequence(groupStart, styled.length).toString())
        assertEquals(Spanned.SPAN_EXCLUSIVE_EXCLUSIVE, styled.getSpanFlags(group))
        assertEquals(kotlin.math.ceil(hint.paint.measureText(receiverGroup).toDouble()).toInt(),
            group.getSize(hint.paint, styled, groupStart, styled.length, null))
    }

    /** A uses one opacity owner for rich/plain copy, semantic repeats, interruptions, and blank exits. */
    @Test
    fun landscapeHintFade_preservesLocalizedSpansTimingsAndBlankExit() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var views: List<Pair<Locale, LandscapeStandbyForegroundView>>
            val oldReceiver = "<Studio & \"TV\" / 'Room'>"
            val newReceiver = "「<Desk & \"客廳\" / 'TV'>」"
            val state = StandbyForegroundState(ScreenPhase.ADVERTISING, true, receiverName = oldReceiver,
                copyMotionAllowed = true, isProjecting = false)
            var actions = 0
            val callbacks = StandbyForegroundCallbacks(onToggleBroadcast = { actions++ }, onOpenSettings = { actions++ })
            try {
                scenario.onActivity { activity ->
                    views = listOf(Locale.ENGLISH, Locale.TRADITIONAL_CHINESE).map { locale ->
                        val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                            densityDpi = 240
                            fontScale = 1f
                            setLocale(locale)
                        })
                        locale to LandscapeStandbyForegroundView(context).also { view ->
                            view.render(state, "Ready", "BROADCAST ON", "", callbacks)
                            activity.contentRoot.addView(view, ViewGroup.LayoutParams(800, 1280))
                        }
                    }
                }
                awaitForegroundLayout(scenario, *views.map { it.second }.toTypedArray())
                scenario.onActivity {
                    val changesField = StandbyForegroundView::class.java.getDeclaredField("copyChanges").apply { isAccessible = true }
                    val scheduled = StandbyForegroundView::class.java.getDeclaredField("copyFrameScheduled").apply { isAccessible = true }
                    val advance = StandbyForegroundView::class.java.getDeclaredMethod("advanceCopyChanges", java.lang.Long.TYPE)
                        .apply { isAccessible = true }
                    for ((locale, view) in views) {
                        fun receiverGroup(name: String): String = if (locale == Locale.ENGLISH) name else "「$name」"
                        fun invitation(name: String): String = if (locale == Locale.ENGLISH) {
                            "Windows: press Win + K · Choose $name"
                        } else {
                            "Windows 按 Win + K，選擇「$name」"
                        }
                        val hint = requireNotNull(view.findViewWithTag<TextView>("foreground_hint"))
                        val title = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
                        val dock = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                        val dockBounds = Rect(dock.left, dock.top, dock.right, dock.bottom)
                        val changes = requireNotNull(changesField.get(view) as? Map<*, *>)
                        fun startMillis(): Long {
                            val change = requireNotNull(changes[hint])
                            return change.javaClass.getDeclaredField("phaseStartMillis").apply { isAccessible = true }.getLong(change)
                        }
                        fun relayout() {
                            repeat(2) {
                                view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY))
                                view.layout(0, 0, 800, 1280)
                            }
                        }
                        assertShortcutCopy(hint, hint.text, invitation(oldReceiver), 1.5f, receiverGroup(oldReceiver))
                        view.render(state.copy(receiverName = newReceiver), "Ready", "BROADCAST ON", "", callbacks)
                        val first = requireNotNull(changes[hint])
                        val start = startMillis()
                        val payload = first.javaClass.getDeclaredField("text").apply { isAccessible = true }.get(first) as SpannedString
                        assertShortcutCopy(hint, payload, invitation(newReceiver), 1.5f, receiverGroup(newReceiver))
                        assertEquals(0, payload.getSpans(0, payload.length, LineHeightSpan::class.java).size)
                        assertTrue(scheduled.getBoolean(view))
                        repeat(3) {
                            relayout()
                            view.render(state.copy(receiverName = newReceiver), "Ready", "BROADCAST ON", "", callbacks)
                            assertSame(first, changes[hint])
                            assertEquals(start, startMillis())
                        }
                        advance.invoke(view, start + 75L)
                        assertEquals(1f - PathInterpolator(0f, 0f, 0.58f, 1f).getInterpolation(0.5f), hint.alpha, 0.001f)
                        assertShortcutCopy(hint, hint.text, invitation(oldReceiver), 1.5f, receiverGroup(oldReceiver))
                        view.updateClock("12:35")
                        view.updateFailure(PrePlayFailureCause.PRE_PLAY_TIMEOUT, "Timeout")
                        assertSame(first, changes[hint])
                        assertEquals("Timeout", requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.FAILED)).text.toString())
                        view.updateFailure(null, "")
                        advance.invoke(view, start + 149L)
                        assertEquals(View.VISIBLE, hint.visibility)
                        assertShortcutCopy(hint, hint.text, invitation(oldReceiver), 1.5f, receiverGroup(oldReceiver))
                        advance.invoke(view, start + 150L)
                        assertEquals(0f, hint.alpha, 0f)
                        assertShortcutCopy(hint, hint.text, invitation(newReceiver), 1.5f, receiverGroup(newReceiver))
                        relayout()
                        assertEquals(720, hint.width)
                        assertTrue(hint.height > 0 && hint.left >= 0 && hint.right <= view.width && hint.bottom <= view.height)
                        assertEquals(1094, hint.bottom)
                        val styled = hint.text as Spanned
                        assertEquals(1, styled.getSpans(0, styled.length, LineHeightSpan::class.java).size)
                        view.render(state.copy(receiverName = newReceiver), "Ready", "BROADCAST ON", "", callbacks)
                        assertSame(first, changes[hint])
                        assertEquals(start + 150L, startMillis())
                        advance.invoke(view, start + 240L)
                        assertEquals(PathInterpolator(0.42f, 0f, 1f, 1f).getInterpolation(0.5f), hint.alpha, 0.001f)
                        advance.invoke(view, start + 330L)
                        assertEquals(1f, hint.alpha, 0f)
                        assertTrue(changes.isEmpty())
                        assertFalse(scheduled.getBoolean(view))
                        repeat(3) {
                            relayout()
                            view.render(state.copy(receiverName = newReceiver), "Ready", "BROADCAST ON", "", callbacks)
                            assertTrue(changes.isEmpty())
                            assertFalse(scheduled.getBoolean(view))
                        }
                        assertShortcutCopy(hint, hint.text, invitation(newReceiver), 1.5f, receiverGroup(newReceiver))

                        view.render(state.copy(phase = ScreenPhase.WIFI_PAUSED), "Ready", "BROADCAST ON", "Arbitrary detail", callbacks)
                        val wifiStart = startMillis()
                        assertShortcutCopy(hint, hint.text, invitation(newReceiver), 1.5f, receiverGroup(newReceiver))
                        advance.invoke(view, wifiStart + 150L)
                        val wifiCopy = if (locale == Locale.ENGLISH) "Wi-Fi is off · Turn on Wi-Fi to broadcast"
                            else "Wi-Fi 已關閉 · 請開啟 Wi-Fi 以廣播"
                        assertEquals(wifiCopy, hint.text.toString())
                        assertEquals(0, (hint.text as Spanned).getSpans(0, hint.text.length, ReplacementSpan::class.java).size)
                        advance.invoke(view, wifiStart + 330L)
                        view.render(state.copy(phase = ScreenPhase.FROZEN), "Ready", "BROADCAST ON", "", callbacks)
                        val frozenStart = startMillis()
                        advance.invoke(view, frozenStart + 150L)
                        val frozenCopy = if (locale == Locale.ENGLISH) "Receiver unavailable" else "接收不可用"
                        assertEquals(frozenCopy, hint.text.toString())
                        assertEquals(0, (hint.text as Spanned).getSpans(0, hint.text.length, ReplacementSpan::class.java).size)
                        advance.invoke(view, frozenStart + 330L)
                        view.render(state.copy(phase = ScreenPhase.READY, advertisingEnabled = false), "Ready", "BROADCAST ON", "", callbacks)
                        val blankStart = startMillis()
                        advance.invoke(view, blankStart + 149L)
                        assertEquals(frozenCopy, hint.text.toString())
                        assertEquals(View.VISIBLE, hint.visibility)
                        advance.invoke(view, blankStart + 150L)
                        assertTrue(hint.text.isEmpty())
                        assertEquals(View.GONE, hint.visibility)
                        assertEquals(1f, hint.alpha, 0f)
                        assertTrue(changes.isEmpty())
                        assertFalse(scheduled.getBoolean(view))
                        advance.invoke(view, blankStart + 1000L)
                        assertEquals(View.GONE, hint.visibility)

                        view.render(state, "Ready", "BROADCAST ON", "", callbacks)
                        assertShortcutCopy(hint, hint.text, invitation(oldReceiver), 1.5f, receiverGroup(oldReceiver))
                        assertTrue(changes.isEmpty())
                        view.render(state.copy(receiverName = "Abandoned"), "Ready", "BROADCAST ON", "", callbacks)
                        advance.invoke(view, startMillis() + 75L)
                        val interruptedAlpha = hint.alpha
                        view.render(state.copy(receiverName = newReceiver), "Ready", "BROADCAST ON", "", callbacks)
                        val latestStart = startMillis()
                        assertEquals(interruptedAlpha, hint.alpha, 0f)
                        advance.invoke(view, latestStart + 75L)
                        assertEquals(interruptedAlpha * (1f - PathInterpolator(0f, 0f, 0.58f, 1f).getInterpolation(0.5f)), hint.alpha, 0.001f)
                        advance.invoke(view, latestStart + 150L)
                        assertShortcutCopy(hint, hint.text, invitation(newReceiver), 1.5f, receiverGroup(newReceiver))
                        advance.invoke(view, latestStart + 330L)
                        assertTrue(changes.isEmpty())
                        assertEquals(60f, title.textSize, 0.001f)
                        assertEquals(350, title.typeface.weight)
                        assertEquals(72, requireNotNull(title.layout).getLineBottom(0) - title.layout.getLineTop(0))
                        assertEquals(16.5f, hint.textSize, 0.001f)
                        assertEquals(dockBounds, Rect(dock.left, dock.top, dock.right, dock.bottom))
                        assertEquals(0, actions)
                    }
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    /** Formatting-only targets and mutable inputs keep a frozen rich payload; existing gates cancel the same owner. */
    @Test
    fun landscapeHintFade_snapshotsStyleChangesAndFinishesAtVisibilityPolicyGates() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var view: LandscapeStandbyForegroundView
            val receiver = "<Literal & TV>"
            val expected = "Windows: press Win + K · Choose $receiver"
            val state = StandbyForegroundState(ScreenPhase.ADVERTISING, true, receiverName = receiver,
                copyMotionAllowed = true, isProjecting = false)
            var actions = 0
            val callbacks = StandbyForegroundCallbacks(onToggleBroadcast = { actions++ }, onOpenSettings = { actions++ })
            try {
                scenario.onActivity { activity ->
                    val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                        densityDpi = 240
                        fontScale = 1f
                        setLocale(Locale.ENGLISH)
                    })
                    view = LandscapeStandbyForegroundView(context)
                    view.render(state, "Ready", "BROADCAST ON", "", callbacks)
                    activity.contentRoot.addView(view, ViewGroup.LayoutParams(800, 1280))
                }
                awaitForegroundLayout(scenario, view)
                scenario.onActivity { activity ->
                    val hint = requireNotNull(view.findViewWithTag<TextView>("foreground_hint"))
                    val changes = requireNotNull(StandbyForegroundView::class.java.getDeclaredField("copyChanges")
                        .apply { isAccessible = true }.get(view) as? Map<*, *>)
                    val scheduled = StandbyForegroundView::class.java.getDeclaredField("copyFrameScheduled").apply { isAccessible = true }
                    val replace = StandbyForegroundView::class.java.getDeclaredMethod("replaceCopy", TextView::class.java,
                        CharSequence::class.java, java.lang.Boolean.TYPE).apply { isAccessible = true }
                    val advance = StandbyForegroundView::class.java.getDeclaredMethod("advanceCopyChanges", java.lang.Long.TYPE)
                        .apply { isAccessible = true }
                    fun startMillis(): Long {
                        val change = requireNotNull(changes[hint])
                        return change.javaClass.getDeclaredField("phaseStartMillis").apply { isAccessible = true }.getLong(change)
                    }
                    val newDensityContext = view.context.createConfigurationContext(Configuration(view.resources.configuration).apply {
                        densityDpi = 320
                    })
                    val source = LandscapeStandbyForegroundView(newDensityContext)
                    source.render(state, "Ready", "BROADCAST ON", "", callbacks)
                    val sourceHint = requireNotNull(source.findViewWithTag<TextView>("foreground_hint"))
                    val mutable = SpannableStringBuilder(sourceHint.text)
                    replace.invoke(view, hint, mutable, false)
                    val pending = requireNotNull(changes[hint])
                    val payload = pending.javaClass.getDeclaredField("text").apply { isAccessible = true }.get(pending) as CharSequence
                    assertTrue(payload is SpannedString)
                    assertShortcutCopy(hint, hint.text, expected, 1.5f, receiver)
                    assertShortcutCopy(hint, payload, expected, 2f, receiver)
                    val start = startMillis()
                    mutable.getSpans(0, mutable.length, ReplacementSpan::class.java).forEach(mutable::removeSpan)
                    mutable.replace(0, mutable.length, "Caller mutated the target")
                    assertShortcutCopy(hint, payload, expected, 2f, receiver)
                    advance.invoke(view, start + 150L)
                    assertShortcutCopy(hint, hint.text, expected, 2f, receiver)
                    assertEquals(0f, hint.alpha, 0f)
                    source.render(state, "Ready", "BROADCAST ON", "", callbacks)
                    replace.invoke(view, hint, sourceHint.text, false)
                    assertSame(pending, changes[hint])
                    assertEquals(start + 150L, startMillis())
                    advance.invoke(view, start + 330L)
                    assertTrue(changes.isEmpty())
                    mutable.append(" again")
                    assertShortcutCopy(hint, hint.text, expected, 2f, receiver)
                    replace.invoke(view, hint, sourceHint.text, false)
                    assertTrue(changes.isEmpty())

                    for (gate in listOf("hidden", "projecting", "reduced", "detached")) {
                        view.render(state.copy(copyMotionAllowed = false), "Ready", "BROADCAST ON", "", callbacks)
                        val next = state.copy(receiverName = "<Latest & TV>")
                        view.render(next, "Ready", "BROADCAST ON", "", callbacks)
                        val gateStart = startMillis()
                        advance.invoke(view, gateStart + 75L)
                        when (gate) {
                            "hidden" -> view.motionLayer.visibility = View.GONE
                            "projecting" -> view.render(next.copy(phase = ScreenPhase.CONNECTED, isProjecting = true),
                                "Ready", "BROADCAST ON", "", callbacks)
                            "reduced" -> view.render(next.copy(copyMotionAllowed = false), "Ready", "BROADCAST ON", "", callbacks)
                            "detached" -> activity.contentRoot.removeView(view)
                        }
                        assertTrue(gate, changes.isEmpty())
                        assertFalse(gate, scheduled.getBoolean(view))
                        assertEquals(gate, 1f, hint.alpha, 0f)
                        if (gate == "projecting") {
                            assertTrue(hint.text.isEmpty())
                            assertEquals(View.GONE, hint.visibility)
                        } else {
                            assertShortcutCopy(hint, hint.text, "Windows: press Win + K · Choose <Latest & TV>", 1.5f, "<Latest & TV>")
                            assertEquals(View.VISIBLE, hint.visibility)
                        }
                        advance.invoke(view, gateStart + 1000L)
                        assertTrue(gate, changes.isEmpty())
                        assertFalse(gate, scheduled.getBoolean(view))
                        if (gate == "hidden" || gate == "detached") {
                            view.render(state, "Ready", "BROADCAST ON", "", callbacks)
                            assertShortcutCopy(hint, hint.text, expected, 1.5f, receiver)
                            assertTrue(changes.isEmpty())
                        }
                        view.motionLayer.visibility = View.VISIBLE
                    }
                    assertEquals(0, actions)
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    /** Layout-owned indentation/leading never become source copy or restart pending/completed fades. */
    @Test
    fun landscapeTitleIndent_reappliesAfterCopySwapWithoutRestartingFades() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var view: LandscapeStandbyForegroundView
            val state = StandbyForegroundState(ScreenPhase.READY, false, receiverName = "Studio",
                copyMotionAllowed = true, isProjecting = false)
            var actions = 0
            val callbacks = StandbyForegroundCallbacks(onToggleBroadcast = { actions++ })
            val nextCopy = "Waiting for Connection · Ready to receive projection"
            try {
                scenario.onActivity { activity ->
                    val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                        densityDpi = 240
                        fontScale = 1f
                        setLocale(Locale.ENGLISH)
                    })
                    view = LandscapeStandbyForegroundView(context)
                    view.render(state, "Before", "READY", "", callbacks)
                    activity.contentRoot.addView(view, ViewGroup.LayoutParams(800, 1280))
                }
                awaitForegroundLayout(scenario, view)
                scenario.onActivity {
                    val title = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
                    val changes = requireNotNull(StandbyForegroundView::class.java.getDeclaredField("copyChanges")
                        .apply { isAccessible = true }.get(view) as? Map<*, *>)
                    val scheduled = StandbyForegroundView::class.java.getDeclaredField("copyFrameScheduled").apply { isAccessible = true }
                    val advance = StandbyForegroundView::class.java.getDeclaredMethod("advanceCopyChanges", java.lang.Long.TYPE)
                        .apply { isAccessible = true }
                    fun startMillis(): Long {
                        val change = requireNotNull(changes[title])
                        return change.javaClass.getDeclaredField("phaseStartMillis").apply { isAccessible = true }.getLong(change)
                    }
                    fun relayout() {
                        repeat(2) {
                            view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY))
                            view.layout(0, 0, 800, 1280)
                        }
                    }
                    fun assertDecoration() {
                        val styled = title.text as Spanned
                        assertEquals(1, styled.getSpans(0, styled.length, LeadingMarginSpan::class.java).size)
                        assertEquals(1, styled.getSpans(0, styled.length, LineHeightSpan::class.java).size)
                        val textLayout = requireNotNull(title.layout)
                        assertEquals(0, title.paddingLeft)
                        assertEquals(6, textLayout.getParagraphLeft(0))
                        assertEquals(title.width, textLayout.getParagraphRight(0))
                        assertEquals(72, textLayout.getLineBottom(0) - textLayout.getLineTop(0))
                    }
                    assertDecoration()
                    view.render(state, nextCopy, "READY", "", callbacks)
                    val pending = requireNotNull(changes[title])
                    val start = startMillis()
                    val payload = pending.javaClass.getDeclaredField("text").apply { isAccessible = true }.get(pending) as SpannedString
                    assertEquals(nextCopy, payload.toString())
                    assertEquals(0, payload.getSpans(0, payload.length, LeadingMarginSpan::class.java).size)
                    assertEquals(0, payload.getSpans(0, payload.length, LineHeightSpan::class.java).size)
                    repeat(3) {
                        relayout()
                        view.render(state, nextCopy, "READY", "", callbacks)
                        assertSame(pending, changes[title])
                        assertEquals(start, startMillis())
                    }
                    advance.invoke(view, start + 149L)
                    assertEquals("Before", title.text.toString())
                    assertDecoration()
                    advance.invoke(view, start + 150L)
                    assertEquals(nextCopy, title.text.toString())
                    assertEquals(0f, title.alpha, 0f)
                    relayout()
                    assertDecoration()
                    assertTrue(requireNotNull(title.layout).lineCount > 1)
                    view.render(state, nextCopy, "READY", "", callbacks)
                    assertSame(pending, changes[title])
                    assertEquals(start + 150L, startMillis())
                    advance.invoke(view, start + 330L)
                    assertEquals(1f, title.alpha, 0f)
                    repeat(3) {
                        relayout()
                        view.render(state, nextCopy, "READY", "", callbacks)
                        assertTrue(changes.isEmpty())
                        assertFalse(scheduled.getBoolean(view))
                        assertDecoration()
                    }
                    view.render(state, "", "READY", "", callbacks)
                    val clearingStart = startMillis()
                    advance.invoke(view, clearingStart + 149L)
                    assertEquals(nextCopy, title.text.toString())
                    advance.invoke(view, clearingStart + 150L)
                    relayout()
                    assertTrue(title.text.isEmpty())
                    val blank = title.text as Spanned
                    assertEquals(0, blank.getSpans(0, 0, LeadingMarginSpan::class.java).size)
                    assertEquals(0, blank.getSpans(0, 0, LineHeightSpan::class.java).size)
                    assertTrue(changes.isEmpty())
                    assertFalse(scheduled.getBoolean(view))
                    view.render(state.copy(copyMotionAllowed = false), "Immediate", "READY", "", callbacks)
                    relayout()
                    assertEquals("Immediate", title.text.toString())
                    assertDecoration()
                    assertTrue(changes.isEmpty())
                    assertEquals(0, actions)
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun copyFade_usesPrototypeTimingsWithoutPlatformAnimators() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var views: List<StandbyForegroundView>
            val state = StandbyForegroundState(ScreenPhase.READY, false, copyMotionAllowed = true, isProjecting = false)
            val callbacks = StandbyForegroundCallbacks()
            try {
                scenario.onActivity { activity ->
                    views = listOf(LandscapeStandbyForegroundView(activity), HardlineStandbyForegroundView(activity))
                    views.forEach { view ->
                        view.render(state, "Old", "Status", "", callbacks)
                        activity.contentRoot.addView(view, ViewGroup.LayoutParams(-1, -1))
                    }
                }
                awaitForegroundLayout(scenario, *views.toTypedArray())
                scenario.onActivity {
                    val changesField = StandbyForegroundView::class.java.getDeclaredField("copyChanges").apply {
                        isAccessible = true
                    }
                    val scheduledField = StandbyForegroundView::class.java.getDeclaredField("copyFrameScheduled").apply {
                        isAccessible = true
                    }
                    val advance = StandbyForegroundView::class.java.getDeclaredMethod(
                        "advanceCopyChanges", java.lang.Long.TYPE,
                    ).apply { isAccessible = true }
                    for ((view, fadeOut, fadeIn) in listOf(Triple(views[0], 150L, 180L), Triple(views[1], 90L, 90L))) {
                        val title = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
                        view.render(state, "New", "Status", "", callbacks)
                        val changes = requireNotNull(changesField.get(view) as? Map<*, *>)
                        val change = requireNotNull(changes[title])
                        val start = change.javaClass.getDeclaredField("phaseStartMillis").apply {
                            isAccessible = true
                        }.getLong(change)
                        assertTrue(scheduledField.getBoolean(view))
                        assertEquals("Old", title.text.toString())
                        advance.invoke(view, start + fadeOut / 2)
                        assertEquals("Old", title.text.toString())
                        assertTrue(title.alpha in 0.25f..0.4f)
                        advance.invoke(view, start + fadeOut - 1)
                        assertEquals("Old", title.text.toString())
                        advance.invoke(view, start + fadeOut)
                        assertEquals("New", title.text.toString())
                        assertEquals(0f, title.alpha, 0f)
                        advance.invoke(view, start + fadeOut + fadeIn / 2)
                        assertTrue(title.alpha in 0.25f..0.4f)
                        advance.invoke(view, start + fadeOut + fadeIn)
                        assertEquals(1f, title.alpha, 0f)
                        assertTrue(changes.isEmpty())
                        assertFalse(scheduledField.getBoolean(view))

                        view.render(state, "Interrupted", "Status", "", callbacks)
                        val interrupted = requireNotNull(changes[title])
                        val interruptedStart = interrupted.javaClass.getDeclaredField("phaseStartMillis").apply {
                            isAccessible = true
                        }.getLong(interrupted)
                        advance.invoke(view, interruptedStart + fadeOut / 2)
                        val alpha = title.alpha
                        view.render(state, "Latest", "Status", "", callbacks)
                        assertEquals(alpha, title.alpha, 0f)
                        view.motionLayer.visibility = View.GONE
                        assertEquals("Latest", title.text.toString())
                        assertEquals(1f, title.alpha, 0f)
                        assertTrue(changes.isEmpty())
                        assertFalse(scheduledField.getBoolean(view))
                        advance.invoke(view, interruptedStart + fadeOut + fadeIn)
                        assertEquals("Latest", title.text.toString())
                        assertFalse(scheduledField.getBoolean(view))
                        view.motionLayer.visibility = View.VISIBLE
                        view.render(state, "Pending", "Status", "", callbacks)
                        assertTrue(scheduledField.getBoolean(view))
                        view.render(state.copy(copyMotionAllowed = false), "Pending", "Status", "", callbacks)
                        assertEquals("Pending", title.text.toString())
                        assertEquals(1f, title.alpha, 0f)
                        assertTrue(changes.isEmpty())
                        assertFalse(scheduledField.getBoolean(view))
                        view.render(state.copy(copyMotionAllowed = false), "Immediate", "Status", "", callbacks)
                        assertEquals("Immediate", title.text.toString())
                        assertFalse(scheduledField.getBoolean(view))
                        view.render(state, "", "Status", "", callbacks)
                        val clearing = requireNotNull(changes[title])
                        val clearingStart = clearing.javaClass.getDeclaredField("phaseStartMillis").apply {
                            isAccessible = true
                        }.getLong(clearing)
                        advance.invoke(view, clearingStart + fadeOut)
                        assertEquals("", title.text.toString())
                        assertEquals(1f, title.alpha, 0f)
                        assertTrue(changes.isEmpty())
                        assertFalse(scheduledField.getBoolean(view))
                    }
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun projecting_finishesPendingCopyWithoutInvokingActions() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    var actions = 0
                    val callbacks = StandbyForegroundCallbacks(onToggleBroadcast = { actions++ })
                    val state = StandbyForegroundState(
                        ScreenPhase.READY, false, copyMotionAllowed = true, isProjecting = false,
                    )
                    val view = LandscapeStandbyForegroundView(activity)
                    view.render(state, "Before", "READY", "", callbacks)
                    activity.contentRoot.addView(view)
                    view.render(state, "After", "READY", "", callbacks)
                    val changesField = StandbyForegroundView::class.java.getDeclaredField("copyChanges")
                    changesField.isAccessible = true
                    val pending = requireNotNull(changesField.get(view) as? Map<*, *>)
                    assertTrue(pending.isNotEmpty())
                    view.render(state.copy(isProjecting = true), "After", "READY", "", callbacks)
                    val title = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
                    assertEquals("After", title.text.toString())
                    assertEquals(1f, title.alpha, 0f)
                    assertTrue(pending.isEmpty())
                    assertEquals(0, actions)
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun clockAndFailureUpdates_keepPendingCopyAnimationAndControls() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    val view = LandscapeStandbyForegroundView(activity)
                    val state = StandbyForegroundState(
                        ScreenPhase.READY, false, copyMotionAllowed = true, isProjecting = false,
                    )
                    var actions = 0
                    val callbacks = StandbyForegroundCallbacks(onToggleBroadcast = { actions++ })
                    val field = StandbyForegroundView::class.java.getDeclaredField("copyChanges")
                    field.isAccessible = true
                    val matter = MatterStandbyForegroundView(activity)
                    val matterState = state.copy(clockText = "12:34")
                    matter.render(matterState, "Before", "READY", "", callbacks)
                    activity.contentRoot.addView(matter)
                    matter.render(matterState, "After", "READY AGAIN", "", callbacks)
                    assertNull(field.get(matter))
                    val clock = requireNotNull(matter.findViewWithTag<TextView>(StandbyForegroundTags.CLOCK))
                    val phase = requireNotNull(matter.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_EYEBROW))
                    val matterButton = requireNotNull(matter.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                    val phaseBeforeClock = phase.text.toString()
                    assertTrue(phaseBeforeClock.isNotEmpty())
                    assertEquals("12:34", clock.text.toString())
                    view.render(state, "Before", "READY", "", callbacks)
                    activity.contentRoot.addView(view)
                    view.render(state, "After", "READY", "", callbacks)
                    val pending = requireNotNull(field.get(view) as? Map<*, *>).toMap()
                    assertTrue(pending.isNotEmpty())
                    assertTrue(pending.values.any { it != null })
                    val button = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                    val title = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.PHASE_TITLE))
                    view.updateClock("12:35")
                    matter.updateClock("12:35")
                    assertEquals("12:35", clock.text.toString())
                    assertSame(clock, matter.findViewWithTag<TextView>(StandbyForegroundTags.CLOCK))
                    assertSame(phase, matter.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_EYEBROW))
                    assertEquals(phaseBeforeClock, phase.text.toString())
                    val afterClock = requireNotNull(field.get(view) as? Map<*, *>)
                    assertEquals(pending.keys, afterClock.keys)
                    pending.forEach { (key, animation) -> assertSame(animation, afterClock[key]) }
                    assertNull(field.get(matter))
                    view.updateFailure(PrePlayFailureCause.PRE_PLAY_TIMEOUT, "Timeout")
                    matter.updateFailure(PrePlayFailureCause.PRE_PLAY_TIMEOUT, "Timeout")
                    val current = requireNotNull(field.get(view) as? Map<*, *>)
                    assertEquals(pending.keys, current.keys)
                    pending.forEach { (key, animation) -> assertSame(animation, current[key]) }
                    assertNull(field.get(matter))
                    assertSame(button, view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                    assertSame(title, view.findViewWithTag<View>(StandbyForegroundTags.PHASE_TITLE))
                    assertSame(matterButton, matter.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                    assertEquals("Timeout", requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.FAILED)).text.toString())
                    assertEquals("Timeout", requireNotNull(matter.findViewWithTag<TextView>(StandbyForegroundTags.FAILED)).text.toString())
                    assertEquals("12:35", clock.text.toString())
                    assertEquals(0, actions)
                    assertTrue(button.performClick())
                    assertEquals(1, actions)
                    assertTrue(matterButton.performClick())
                    assertEquals(2, actions)

                    matter.render(
                        matterState.copy(phase = ScreenPhase.CONNECTING, isProjecting = false),
                        "After", "CONNECTING", "", callbacks,
                    )
                    assertNull(field.get(matter))
                    val blinkField = MatterStandbyForegroundView::class.java.getDeclaredField("blinkCallback")
                    blinkField.isAccessible = true
                    val light = requireNotNull(matter.findViewWithTag<View>(StandbyForegroundTags.MATTER_LIGHT))
                    val failed = requireNotNull(matter.findViewWithTag<TextView>(StandbyForegroundTags.FAILED))
                    val controls = requireNotNull(matterButton.parent)
                    val motionLayer = matter.motionLayer
                    motionLayer.translationX = 13f
                    motionLayer.translationY = -7f
                    requireNotNull(blinkField.get(matter) as? Runnable).run()
                    assertEquals(0.2f, light.alpha, 0f)
                    val blink = requireNotNull(blinkField.get(matter) as? Runnable)
                    matter.updateClock("12:36")
                    assertEquals("12:36", clock.text.toString())
                    assertSame(clock, matter.findViewWithTag<TextView>(StandbyForegroundTags.CLOCK))
                    assertSame(blink, blinkField.get(matter))
                    assertSame(light, matter.findViewWithTag<View>(StandbyForegroundTags.MATTER_LIGHT))
                    assertEquals(0.2f, light.alpha, 0f)
                    assertSame(matterButton, matter.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                    assertSame(motionLayer, matter.motionLayer)
                    assertEquals(13f, motionLayer.translationX, 0f)
                    assertEquals(-7f, motionLayer.translationY, 0f)
                    matter.updateFailure(null, "")
                    assertSame(blink, blinkField.get(matter))
                    assertEquals(0.2f, light.alpha, 0f)
                    assertSame(failed, matter.findViewWithTag<TextView>(StandbyForegroundTags.FAILED))
                    assertEquals("", failed.text.toString())
                    assertEquals(View.GONE, failed.visibility)
                    assertSame(clock, matter.findViewWithTag<TextView>(StandbyForegroundTags.CLOCK))
                    assertEquals("12:36", clock.text.toString())
                    assertSame(matterButton, matter.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                    assertSame(motionLayer, matter.motionLayer)
                    assertEquals(13f, motionLayer.translationX, 0f)
                    assertEquals(-7f, motionLayer.translationY, 0f)
                    matter.updateFailure(PrePlayFailureCause.PRE_PLAY_TIMEOUT, "Still timed out")
                    assertEquals("Still timed out", failed.text.toString())
                    assertEquals(View.VISIBLE, failed.visibility)
                    assertNull(blinkField.get(matter))
                    assertEquals(1f, light.alpha, 0f)
                    assertSame(failed, matter.findViewWithTag<TextView>(StandbyForegroundTags.FAILED))
                    assertSame(clock, matter.findViewWithTag<TextView>(StandbyForegroundTags.CLOCK))
                    assertEquals("12:36", clock.text.toString())
                    assertSame(phase, matter.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_EYEBROW))
                    assertEquals("ERROR", phase.text.toString())
                    assertSame(light, matter.findViewWithTag<View>(StandbyForegroundTags.MATTER_LIGHT))
                    assertSame(matterButton, matter.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                    assertSame(controls, matterButton.parent)
                    assertSame(motionLayer, matter.motionLayer)
                    assertEquals(13f, motionLayer.translationX, 0f)
                    assertEquals(-7f, motionLayer.translationY, 0f)
                    matter.updateFailure(PrePlayFailureCause.PRE_PLAY_TIMEOUT, "Updated timeout")
                    assertEquals("Updated timeout", failed.text.toString())
                    assertEquals(View.VISIBLE, failed.visibility)
                    assertNull(blinkField.get(matter))
                    assertEquals(1f, light.alpha, 0f)
                    assertSame(failed, matter.findViewWithTag<TextView>(StandbyForegroundTags.FAILED))
                    assertSame(clock, matter.findViewWithTag<TextView>(StandbyForegroundTags.CLOCK))
                    assertSame(phase, matter.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_EYEBROW))
                    assertSame(light, matter.findViewWithTag<View>(StandbyForegroundTags.MATTER_LIGHT))
                    assertSame(matterButton, matter.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                    assertSame(controls, matterButton.parent)
                    assertSame(motionLayer, matter.motionLayer)
                    assertEquals(13f, motionLayer.translationX, 0f)
                    assertEquals(-7f, motionLayer.translationY, 0f)
                    assertEquals("12:36", clock.text.toString())
                    assertEquals("ERROR", phase.text.toString())
                    assertNull(field.get(matter))
                    assertEquals(2, actions)
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun receiverName_keepsHostTextAndEllipsisInBothLocales() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            for (locale in listOf(java.util.Locale.ENGLISH, java.util.Locale.TRADITIONAL_CHINESE)) {
                val config = android.content.res.Configuration(base.resources.configuration)
                config.setLocale(locale)
                val context = base.createConfigurationContext(config)
                val receiver = "A long receiver name 中文接收裝置名稱"
                for (view in listOf(LandscapeStandbyForegroundView(context), HardlineStandbyForegroundView(context))) {
                    view.render(
                        StandbyForegroundState(ScreenPhase.READY, false, receiverName = receiver),
                        "Title", "READY", "Detail", StandbyForegroundCallbacks(),
                    )
                    val name = requireNotNull(view.findViewWithTag<TextView>(StandbyForegroundTags.RECEIVER_NAME))
                    assertEquals(receiver, name.text.toString())
                    assertEquals(1, name.maxLines)
                    assertEquals(android.text.TextUtils.TruncateAt.END, name.ellipsize)
                }
            }
        }
    }

    @Test
    fun measuredGeometry_reportsActualViewPixelsAcrossWidths() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            lateinit var root: FrameLayout
            lateinit var landscape: LandscapeStandbyForegroundView
            lateinit var hardline: HardlineStandbyForegroundView
            lateinit var widths: List<Int>
            var heightPx = 0
            try {
                scenario.onActivity { activity -> root = activity.contentRoot }
                awaitForegroundLayout(scenario, root)
                scenario.onActivity { activity ->
                    val visible = visibleContentBounds(root)
                    val density = activity.resources.displayMetrics.density
                    val wide = minOf(visible.width(), (920f * density).toInt())
                    val narrow = minOf((320f * density).toInt(), wide - 1)
                    assertTrue("Visible content is too narrow for two widths", narrow > 0)
                    widths = listOf(narrow, wide)
                    heightPx = minOf(visible.height(), (800f * density).toInt())
                    landscape = LandscapeStandbyForegroundView(activity)
                    hardline = HardlineStandbyForegroundView(activity)
                    val state = StandbyForegroundState(ScreenPhase.READY, false, receiverName = "接收器 Receiver")
                    landscape.render(state, "Title", "READY", "Detail", StandbyForegroundCallbacks())
                    hardline.render(state, "Title", "READY", "Detail", StandbyForegroundCallbacks())
                    android.util.Log.i("StandbyGeometry", foregroundSnapshot("measuredGeometry_reportsActualViewPixelsAcrossWidths detached fixtureInsets=0", landscape))
                    android.util.Log.i("StandbyGeometry", foregroundSnapshot("measuredGeometry_reportsActualViewPixelsAcrossWidths detached fixtureInsets=0", hardline))
                    assertNull(landscape.foregroundGeometryInViewPixels())
                    assertNull(hardline.foregroundRectsInViewPixels())
                    root.addView(landscape, visibleContentLayoutParams(root, visible, narrow, heightPx))
                    root.addView(hardline, visibleContentLayoutParams(root, visible, narrow, heightPx))
                }
                for (width in widths) {
                    scenario.onActivity {
                        for (view in listOf(landscape, hardline)) {
                            view.layoutParams = view.layoutParams.apply { this.width = width; height = heightPx }
                        }
                    }
                    awaitForegroundLayout(scenario, landscape, hardline)
                    scenario.onActivity {
                        assertEquals(width, landscape.width)
                        assertEquals(width, hardline.width)
                        assertEquals(heightPx, landscape.height)
                        assertEquals(heightPx, hardline.height)
                        val visible = visibleContentBounds(root)
                        assertTrue(visible.contains(landscape.left, landscape.top, landscape.right, landscape.bottom))
                        assertTrue(visible.contains(hardline.left, hardline.top, hardline.right, hardline.bottom))
                        val landscapeEvidence = foregroundSnapshot("measuredGeometry_reportsActualViewPixelsAcrossWidths fixtureInsets=0", landscape)
                        val hardlineEvidence = foregroundSnapshot("measuredGeometry_reportsActualViewPixelsAcrossWidths fixtureInsets=0", hardline)
                        android.util.Log.i("StandbyGeometry", landscapeEvidence)
                        android.util.Log.i("StandbyGeometry", hardlineEvidence)
                        val landscapeGeometry = requireNotNull(landscape.foregroundGeometryInViewPixels()) { landscapeEvidence }
                        val hardlineGeometry = requireNotNull(hardline.foregroundRectsInViewPixels()) { hardlineEvidence }
                        assertTrue(landscapeGeometry.brandBottomPx > 0f)
                        assertTrue(landscapeGeometry.primaryTopPx >= 0f)
                        assertTrue(hardlineGeometry.centerBand.height() > 0)
                        assertTrue(hardlineGeometry.header.width() <= width)
                    }
                }
                scenario.onActivity { activity ->
                    activity.contentRoot.removeView(landscape)
                    activity.contentRoot.removeView(hardline)
                    assertNull(landscape.foregroundGeometryInViewPixels())
                    assertNull(hardline.foregroundRectsInViewPixels())
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }

    @Test
    fun prototypeAnchors_doNotStackAtTheOrigin() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val density = context.resources.displayMetrics.density
            val width = (920f * density).toInt()
            val height = (800f * density).toInt()
            val state = StandbyForegroundState(ScreenPhase.ADVERTISING, true, receiverName = "Receiver")
            val callbacks = StandbyForegroundCallbacks(onToggleBroadcast = {}, onOpenSettings = {})
            val landscape = LandscapeStandbyForegroundView(context)
            val matter = MatterStandbyForegroundView(context)
            val hardline = HardlineStandbyForegroundView(context)
            for (view in listOf(landscape, matter, hardline)) {
                view.render(state, "Title", "READY", "Detail", callbacks)
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, width, height)
            }
            fun bounds(parent: StandbyForegroundView, tag: String): android.graphics.Rect {
                val child = requireNotNull(parent.findViewWithTag<View>(tag))
                return android.graphics.Rect(0, 0, child.width, child.height).also {
                    parent.offsetDescendantRectToMyCoords(child, it)
                }
            }
            for (view in listOf(landscape, hardline)) {
                val title = bounds(view, StandbyForegroundTags.PHASE_TITLE)
                assertTrue("Title should be near the vertical center", title.centerY() > height * 0.30f)
                assertTrue("Title should be near the vertical center", title.centerY() < height * 0.60f)
            }
            val plate = bounds(matter, StandbyForegroundTags.MATTER_PLATE)
            assertTrue("Matter plate should be at the lower right", plate.left > width / 2)
            assertTrue("Matter plate should be at the lower right", plate.top > height * 0.70f)
        }
    }

    @Test
    fun focusedControl_survivesLocalRefreshAndRepeatedRender() {
        ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    val view = LandscapeStandbyForegroundView(activity)
                    val state = StandbyForegroundState(ScreenPhase.READY, false)
                    val callbacks = StandbyForegroundCallbacks(onToggleBroadcast = {})
                    view.render(state, "Title", "READY", "", callbacks)
                    activity.contentRoot.addView(
                        view,
                        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                    )
                    val button = requireNotNull(view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                    button.isFocusableInTouchMode = true
                    assertTrue(button.requestFocus())
                    view.updateFailure(PrePlayFailureCause.PRE_PLAY_TIMEOUT, "Timeout")
                    view.updateClock("12:35")
                    view.render(state, "Title", "READY", "", callbacks)
                    assertSame(button, view.findViewWithTag<View>(StandbyForegroundTags.BROADCAST))
                    assertTrue(button.hasFocus())
                }
            } finally {
                scenario.onActivity { it.contentRoot.removeAllViews() }
            }
        }
    }
}
