package me.trinitrix.mirax

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import java.io.File
import me.trinitrix.mirax.data.standby.StagedCandidate
import me.trinitrix.mirax.data.standby.StandbyFraming
import me.trinitrix.mirax.data.standby.StandbyMediaKind
import me.trinitrix.mirax.session.AppLanguage
import me.trinitrix.mirax.ui.standby.StandbyMediaEdit
import me.trinitrix.mirax.ui.standby.StandbyMediaEditStatus
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundTags
import me.trinitrix.mirax.ui.standby.framing.StandbyFramingEditorView
import me.trinitrix.mirax.ui.standby.media.StandbyFramingControlsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the editor's focus boundary without publishing a media candidate. */
@RunWith(AndroidJUnit4::class)
class MainActivityMediaEditorTest {
    @Test
    fun editorBlocksBackgroundFocusAndRestoresItsPreviousSettings() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = requireNotNull(activity.findViewById<FrameLayout>(R.id.root))
                val split = requireNotNull(activity.findViewById<ViewGroup>(R.id.split))
                val drawer = requireNotNull(activity.findViewById<ViewGroup>(R.id.drawer))
                val scrim = requireNotNull(activity.findViewById<View>(R.id.drawerScrim))
                split.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                drawer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                scrim.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                split.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                drawer.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
                scrim.isFocusable = true

                val candidate = StagedCandidate(
                    1L,
                    File(activity.cacheDir, "modal-only-unused"),
                    StandbyMediaKind.IMAGE,
                    StandbyFraming.CENTERED,
                )
                val edit = StandbyMediaEdit(candidate, StandbyFramingControlsModel(), StandbyMediaEditStatus.EDITING)
                val show = MainActivity::class.java.getDeclaredMethod("showMediaEditor", StandbyMediaEdit::class.java)
                show.isAccessible = true
                show.invoke(activity, edit)
                val overlay = root.getChildAt(root.childCount - 1)
                assertTrue(overlay.isClickable)
                assertTrue(overlay.hasFocus())
                assertTrue(overlay.elevation > drawer.elevation)
                assertEquals(
                    activity.getString(R.string.standby_framing_title),
                    ViewCompat.getAccessibilityPaneTitle(overlay).toString(),
                )
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, split.importantForAccessibility)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, drawer.importantForAccessibility)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, scrim.importantForAccessibility)
                assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, split.descendantFocusability)
                assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, drawer.descendantFocusability)
                assertFalse(scrim.isFocusable)

                val remove = MainActivity::class.java.getDeclaredMethod(
                    "removeMediaEditor",
                    requireNotNull(Boolean::class.javaPrimitiveType),
                )
                remove.isAccessible = true
                remove.invoke(activity, false)
                assertEquals(-1, root.indexOfChild(overlay))
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, split.importantForAccessibility)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, drawer.importantForAccessibility)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, scrim.importantForAccessibility)
                assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, split.descendantFocusability)
                assertEquals(ViewGroup.FOCUS_BEFORE_DESCENDANTS, drawer.descendantFocusability)
                assertTrue(scrim.isFocusable)
            }
        }
    }

    @Test
    fun playbackReadinessAndDecodedSizeGateTheCurrentEditorWithoutResolvingPendingCommit() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val render = MainActivity::class.java.getDeclaredMethod(
                    "renderMediaPresentation",
                    requireNotNull(Boolean::class.javaPrimitiveType),
                    Size::class.java,
                    StandbyMediaEdit::class.java,
                    requireNotNull(Boolean::class.javaPrimitiveType),
                ).apply { isAccessible = true }
                val editorField = MainActivity::class.java.getDeclaredField("mediaEditor").apply { isAccessible = true }
                val remove = MainActivity::class.java.getDeclaredMethod(
                    "removeMediaEditor", requireNotNull(Boolean::class.javaPrimitiveType),
                ).apply { isAccessible = true }
                val candidate = StagedCandidate(
                    10L, File(activity.cacheDir, "readiness-unused"), StandbyMediaKind.IMAGE, StandbyFraming.CENTERED,
                )
                val edit = StandbyMediaEdit(candidate, StandbyFramingControlsModel(), StandbyMediaEditStatus.EDITING)
                try {
                    render.invoke(activity, false, null, edit, false)
                    val editor = editorField.get(activity) as StandbyFramingEditorView
                    layoutEditor(editor)
                    assertFalse(confirmButton(editor).isEnabled)
                    render.invoke(activity, false, Size(1920, 1080), edit, false)
                    assertFalse(confirmButton(editor).isEnabled)
                    render.invoke(activity, true, Size(1920, 1080), edit, false)
                    assertTrue(confirmButton(editor).isEnabled)
                    render.invoke(activity, false, Size(1920, 1080), edit, false)
                    assertFalse(confirmButton(editor).isEnabled)
                    render.invoke(activity, true, Size(1920, 1080), edit, false)
                    assertTrue(confirmButton(editor).isEnabled)

                    val replacement = edit.copy(candidate = candidate.copy(generation = 11L))
                    render.invoke(activity, true, null, replacement, false)
                    val next = editorField.get(activity) as StandbyFramingEditorView
                    layoutEditor(next)
                    assertFalse(confirmButton(next).isEnabled)
                    render.invoke(activity, false, Size(1280, 720), replacement, false)
                    assertFalse(confirmButton(next).isEnabled)

                    val committing = replacement.copy(
                        candidate = candidate.copy(generation = 12L), status = StandbyMediaEditStatus.COMMITTING,
                    )
                    render.invoke(activity, false, Size(1280, 720), committing, false)
                    val pending = editorField.get(activity) as StandbyFramingEditorView
                    layoutEditor(pending)
                    assertTrue(pending.isCommitPending)
                    assertFalse(pending.isConfirmed)
                    render.invoke(activity, false, null, committing.copy(status = StandbyMediaEditStatus.UNKNOWN), false)
                    assertTrue(pending.isCommitPending)
                    assertFalse(pending.isConfirmed)
                    assertFalse(confirmButton(pending).isEnabled)
                } finally {
                    remove.invoke(activity, false)
                }
            }
        }
    }

    /** Arranges root ownership locally; observes product drawer focus without native input or post-open repair. */
    @Test
    fun drawerTakesFocusFromParkedRootWithoutRepair() {
        val sdkIdleBefore = Configurator.getInstance().waitForIdleTimeout
        var primary: Throwable? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val expectedMain = awaitDrawerReady(scenario)
                var restoreBaseline: (() -> Unit)? = null
                var opened = false
                try {
                    awaitDrawerPhase(scenario, expectedMain) { currentMain ->
                        val root = requireNotNull(currentMain.findViewById<ViewGroup>(R.id.root))
                        val menu = drawerMenu(currentMain)
                        val bounds = Rect()
                        currentMain.hasWindowFocus() && drawerGone(currentMain) && root.isLaidOut &&
                            root.width > 0 && root.height > 0 && root.windowToken != null && menu != null &&
                            menu.isAttachedToWindow && menu.isShown && menu.isEnabled && menu.isClickable &&
                            menu.getGlobalVisibleRect(bounds) && !bounds.isEmpty
                    }
                    awaitDrawerAction(scenario, expectedMain) { currentMain ->
                        assertTrue("DRAWER_ACCESSIBILITY fixture=INCOMPLETE root setup ownership changed",
                            currentMain.hasWindowFocus() && drawerGone(currentMain) && currentMain.currentFocus !is EditText)
                        val root = requireNotNull(currentMain.findViewById<ViewGroup>(R.id.root))
                        val split = requireNotNull(currentMain.findViewById<ViewGroup>(R.id.split))
                        val originalFocusable = root.focusable
                        val originalTouchFocusable = root.isFocusableInTouchMode
                        val originalAccessibility = root.importantForAccessibility
                        val originalDescendants = root.descendantFocusability
                        val originalSplitAccessibility = split.importantForAccessibility
                        val originalSplitDescendants = split.descendantFocusability
                        restoreBaseline = {
                            split.importantForAccessibility = originalSplitAccessibility
                            split.descendantFocusability = originalSplitDescendants
                            root.isFocusableInTouchMode = originalTouchFocusable
                            root.setFocusable(originalFocusable)
                            root.importantForAccessibility = originalAccessibility
                            root.descendantFocusability = originalDescendants
                            assertEquals(originalSplitAccessibility, split.importantForAccessibility)
                            assertEquals(originalSplitDescendants, split.descendantFocusability)
                            assertEquals(originalFocusable, root.focusable)
                            assertEquals(originalTouchFocusable, root.isFocusableInTouchMode)
                            assertEquals(originalAccessibility, root.importantForAccessibility)
                            assertEquals(originalDescendants, root.descendantFocusability)
                        }
                        // Match the existing close helper's local baseline; cleanup restores the natural flags.
                        split.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        split.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                        root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        root.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
                        root.isFocusableInTouchMode = true
                        root.isFocusable = true
                        assertTrue("DRAWER_ACCESSIBILITY fixture=INCOMPLETE root request rejected", root.requestFocus())
                        assertTrue("DRAWER_ACCESSIBILITY fixture=INCOMPLETE parked root ownership missing",
                            root.isFocused && currentMain.currentFocus === root && root.hasWindowFocus() && !split.hasFocus())
                        val menu = requireNotNull(drawerMenu(currentMain))
                        assertTrue(menu.isShown && menu.isEnabled && menu.isClickable)
                        opened = true
                        assertTrue("Public Menu must handle its opening click", menu.performClick())
                    }
                    // Geometry readiness is setup, not the focus regression's RED assertion.
                    awaitDrawerPhase(scenario, expectedMain) { currentMain ->
                        assertDrawerIsolation(currentMain)
                        val drawer = requireNotNull(currentMain.findViewById<View>(R.id.drawer))
                        val scrim = requireNotNull(currentMain.findViewById<View>(R.id.drawerScrim))
                        val close = requireNotNull(currentMain.findViewById<View>(R.id.drawerClose))
                        val bounds = Rect()
                        drawer.visibility == View.VISIBLE && scrim.visibility == View.VISIBLE &&
                            drawer.isLaidOut && drawer.translationX == 0f && scrim.alpha == 1f &&
                            drawer.hasWindowFocus() && close.isShown && close.getGlobalVisibleRect(bounds) && !bounds.isEmpty
                    }
                    awaitDrawerAction(scenario, expectedMain) { currentMain ->
                        assertDrawerIsolation(currentMain)
                        val root = requireNotNull(currentMain.findViewById<View>(R.id.root))
                        val drawer = requireNotNull(currentMain.findViewById<ViewGroup>(R.id.drawer))
                        val scrim = requireNotNull(currentMain.findViewById<View>(R.id.drawerScrim))
                        val focus = currentMain.currentFocus
                        val bounds = Rect()
                        assertFalse(root.isFocusable)
                        assertTrue("Opening from a parked root naturally focuses a meaningful drawer child",
                            focus != null && focus !== root && focus !== drawer && focus !== scrim &&
                                drawerDescendants(drawer).any { it === focus } && focus.isFocused && focus.hasWindowFocus() &&
                                focus.isAttachedToWindow && focus.isShown && focus.isEnabled && focus.isFocusable &&
                                focus.isLaidOut && focus.width > 0 && focus.height > 0 && focus.alpha > 0f &&
                                root.windowToken != null && focus.windowToken === root.windowToken &&
                                focus.getGlobalVisibleRect(bounds) && !bounds.isEmpty && focus.isClickable &&
                                (!focus.contentDescription.isNullOrBlank() || (focus is TextView && focus.text.isNotBlank())))
                    }
                } catch (failure: Throwable) {
                    primary = failure
                    if (failure is InterruptedException) Thread.currentThread().interrupt()
                    throw failure
                } finally {
                    try {
                        if (opened) {
                            awaitDrawerAction(scenario, expectedMain) { currentMain ->
                                if (!drawerGone(currentMain)) {
                                    assertTrue("Own drawer Close must handle cleanup",
                                        requireNotNull(currentMain.findViewById<View>(R.id.drawerClose)).performClick())
                                }
                            }
                            awaitDrawerGone(scenario, expectedMain)
                        }
                        awaitDrawerAction(scenario, expectedMain) { currentMain ->
                            assertTrue("Arrangement flags restore only after both drawer surfaces are gone", drawerGone(currentMain))
                            restoreBaseline?.invoke()
                        }
                    } catch (cleanup: Throwable) {
                        if (cleanup is InterruptedException) Thread.currentThread().interrupt()
                        val original = primary
                        if (original == null) throw cleanup
                        if (original !== cleanup && original.suppressed.none { it === cleanup }) original.addSuppressed(cleanup)
                    }
                }
            }
        } catch (failure: Throwable) {
            primary = failure
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            throw failure
        } finally {
            try {
                val sdkIdleAfter = Configurator.getInstance().waitForIdleTimeout
                Log.i("MiraxRootDrawer", "sdkIdleBeforeMs=$sdkIdleBefore sdkIdleAfterMs=$sdkIdleAfter unchanged=${sdkIdleBefore == sdkIdleAfter}")
                assertEquals("Root drawer regression must not change the SDK idle timeout", sdkIdleBefore, sdkIdleAfter)
            } catch (cleanup: Throwable) {
                if (cleanup is InterruptedException) Thread.currentThread().interrupt()
                val original = primary
                if (original == null) throw cleanup
                if (original !== cleanup && original.suppressed.none { it === cleanup }) original.addSuppressed(cleanup)
            }
        }
    }

    /** Exercises native menus and a synthetic drag-helper boundary without publishing media. */
    @Test
    fun drawerBlocksBackgroundFocusThroughCloseDragAndEditor() {
        drawerBlocksBackgroundFocusThroughCloseDragAndEditor(null)
    }

    /** Supplies only the editor transaction; the native sidebar, drag and assertions remain shared. */
    internal fun interface DrawerMediaFixture {
        /** @param scenario Original scenario. @param expectedMain Main locked by the shared prefix. */
        fun exercise(scenario: ActivityScenario<MainActivity>, expectedMain: MainActivity)
    }

    /** @param fixture Real public-owner transaction, or null for both original synthetic close orders. */
    internal fun drawerBlocksBackgroundFocusThroughCloseDragAndEditor(fixture: DrawerMediaFixture?) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val remove = MainActivity::class.java.getDeclaredMethod(
            "removeMediaEditor", requireNotNull(Boolean::class.javaPrimitiveType),
        ).apply { isAccessible = true }
        val show = MainActivity::class.java.getDeclaredMethod(
            "showMediaEditor", StandbyMediaEdit::class.java,
        ).apply { isAccessible = true }
        val begin = MainActivity::class.java.getDeclaredMethod("beginDrawerDrag").apply { isAccessible = true }
        val finish = MainActivity::class.java.getDeclaredMethod(
            "finishDrawerDrag", requireNotNull(Float::class.javaPrimitiveType), requireNotNull(Float::class.javaPrimitiveType),
        ).apply { isAccessible = true }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val baselines = mutableListOf<Pair<ViewGroup, Pair<Int, Int>>>()
            fun prepareBaseline(currentMain: MainActivity) {
                val split = requireNotNull(currentMain.findViewById<ViewGroup>(R.id.split))
                if (baselines.none { it.first === split }) {
                    assertTrue(drawerGone(currentMain))
                    baselines.add(split to (split.importantForAccessibility to split.descendantFocusability))
                    split.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    split.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                }
            }
            var primary: Throwable? = null
            var dragStarted = false
            try {
                val expectedMain = awaitDrawerReady(scenario)
                awaitDrawerAction(scenario, expectedMain) { currentMain -> prepareBaseline(currentMain) }
                // A directional key exits touch mode without invoking a background action.
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                // Navigate only through real sidebar cards, including a return to Standby.
                for (destination in listOf(R.string.nav_standby, R.string.nav_dashboard_legacy, R.string.nav_standby)) {
                    awaitDrawerAction(scenario, expectedMain) { currentMain ->
                        prepareBaseline(currentMain)
                        val menu = requireNotNull(drawerMenu(currentMain))
                        assertTrue(menu.requestFocus())
                        assertTrue(menu.hasFocus())
                        assertTrue(menu.performClick())
                        assertDrawerIsolation(currentMain)
                        assertFalse(menu.hasFocus())
                        val close = requireNotNull(currentMain.findViewById<View>(R.id.drawerClose))
                        assertTrue(close.requestFocus())
                        assertTrue(close.hasFocus())
                        val drawer = requireNotNull(currentMain.findViewById<ViewGroup>(R.id.drawer))
                        val label = drawerDescendants(drawer).filterIsInstance<TextView>().first {
                            it.text.toString() == currentMain.getString(destination)
                        }
                        var card: View = label
                        while (!card.isClickable) card = card.parent as View
                        assertTrue(card.performClick())
                        assertDrawerClosingOrRestored(currentMain)
                    }
                    awaitDrawerGone(scenario, expectedMain)
                    var closeOrigin: View? = null
                    awaitDrawerAction(scenario, expectedMain) { currentMain ->
                        prepareBaseline(currentMain)
                        val menu = requireNotNull(drawerMenu(currentMain))
                        assertTrue(menu.requestFocus())
                        closeOrigin = menu
                        assertTrue(menu.performClick())
                        assertDrawerIsolation(currentMain)
                        assertTrue(requireNotNull(currentMain.findViewById<View>(R.id.drawerClose)).performClick())
                        assertDrawerClosingOrRestored(currentMain)
                    }
                    awaitDrawerGone(scenario, expectedMain)
                    awaitDrawerAction(scenario, expectedMain) {
                        assertDrawerFocusRestored(closeOrigin)
                    }
                }
                awaitDrawerAction(scenario, expectedMain) { currentMain ->
                    prepareBaseline(currentMain)
                    dragStarted = true
                    begin.invoke(currentMain)
                    assertDrawerIsolation(currentMain)
                    // Synthetic helper boundary, not a physical swipe.
                    finish.invoke(currentMain, 0f, 0f)
                    dragStarted = false
                    assertDrawerClosingOrRestored(currentMain)
                }
                awaitDrawerGone(scenario, expectedMain)
                if (fixture != null) fixture.exercise(scenario, expectedMain)
                else for (removeEditorFirst in listOf(true, false)) {
                    var editorFixture: DrawerEditorFixture? = null
                    awaitDrawerAction(scenario, expectedMain) { currentMain ->
                        prepareBaseline(currentMain)
                        val owner = MiraxApp.instance.standbyMediaOwner
                        val ownerEdit = try {
                            owner.javaClass.getDeclaredField("edit").apply { isAccessible = true }.get(owner) as StandbyMediaEdit?
                        } catch (failure: Exception) {
                            drawerFixtureIncomplete("stage=editor reason=owner_edit_read", failure)
                        }
                        assertTrue(ownerEdit == null)
                        assertTrue(requireNotNull(drawerMenu(currentMain)).performClick())
                        assertDrawerIsolation(currentMain)
                        val candidate = StagedCandidate(
                            20L, File(currentMain.cacheDir, "drawer-modal-unused"),
                            StandbyMediaKind.IMAGE, StandbyFraming.CENTERED,
                        )
                        try {
                            show.invoke(currentMain, StandbyMediaEdit(
                                candidate, StandbyFramingControlsModel(), StandbyMediaEditStatus.EDITING,
                            ))
                        } catch (failure: Throwable) {
                            drawerFixtureIncomplete("stage=editor reason=show_failed", failure)
                        }
                        val expectedEditor = readDrawerEditor(currentMain)
                        requireDrawerEditor(currentMain, expectedEditor)
                        editorFixture = expectedEditor
                        assertDrawerIsolation(currentMain)
                        if (removeEditorFirst) {
                            requireDrawerEditor(currentMain, expectedEditor)
                            remove.invoke(currentMain, false)
                            assertDrawerClosingOrRestored(currentMain)
                        }
                    }
                    if (removeEditorFirst) {
                        awaitDrawerGone(scenario, expectedMain)
                    } else {
                        val expectedEditor = editorFixture
                            ?: drawerFixtureIncomplete("stage=editor reason=fixture_missing")
                        awaitDrawerPhase(scenario, expectedMain) { currentMain ->
                            requireDrawerEditor(currentMain, expectedEditor)
                            assertDrawerIsolation(currentMain)
                            drawerGone(currentMain)
                        }
                        awaitDrawerAction(scenario, expectedMain) { currentMain ->
                            requireDrawerEditor(currentMain, expectedEditor)
                            assertDrawerIsolation(currentMain)
                            remove.invoke(currentMain, false)
                            assertDrawerRestored(currentMain)
                        }
                    }
                }
            } catch (failure: Throwable) {
                primary = failure
                throw failure
            } finally {
                var cleanupFailure: Throwable? = null
                fun clean(action: () -> Unit) {
                    try {
                        action()
                    } catch (failure: Throwable) {
                        val previous = primary ?: cleanupFailure
                        if (previous == null) cleanupFailure = failure else previous.addSuppressed(failure)
                    }
                }
                if (fixture == null) clean { awaitActivityAction(scenario) { currentMain -> remove.invoke(currentMain, false) } }
                clean {
                    awaitActivityAction(scenario) { currentMain ->
                        if (dragStarted) finish.invoke(currentMain, 0f, 0f)
                        if (!drawerGone(currentMain)) {
                            requireNotNull(currentMain.findViewById<View>(R.id.drawerClose)).performClick()
                        }
                    }
                    awaitDrawerPhase(scenario, null) { drawerGone(it) }
                }
                clean {
                    awaitActivityAction(scenario) {
                        baselines.forEach { (split, flags) ->
                            split.importantForAccessibility = flags.first
                            split.descendantFocusability = flags.second
                        }
                    }
                }
                if (primary == null) cleanupFailure?.let { throw it }
            }
        }
    }

    /**
     * Locks Main only after its restored language and visible native menu agree.
     * @param scenario Real Main scenario, including natural startup recreation.
     * @return The exact ready Main for the complete drawer transaction.
     */
    private fun awaitDrawerReady(scenario: ActivityScenario<MainActivity>): MainActivity {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) drawerFixtureIncomplete("stage=startup reason=readiness_deadline")
            var readyMain: MainActivity? = null
            try {
                awaitActivityAction(scenario, remaining) { currentMain ->
                    if (drawerLanguageReady(currentMain)) {
                        val menu = drawerMenu(currentMain)
                        if (menu != null && menu.isShown && menu.isEnabled && menu.isClickable) readyMain = currentMain
                    }
                }
            } catch (failure: Exception) {
                drawerFixtureIncomplete("stage=startup reason=readiness_observation", failure)
            }
            if (SystemClock.elapsedRealtime() > deadline) {
                drawerFixtureIncomplete("stage=startup reason=readiness_deadline")
            }
            readyMain?.let { return it }
            Thread.yield()
        }
    }

    /**
     * Reads the actual language fields without applying locales or recreating Main.
     * @param activity Current startup Main.
     * @return Whether host language restoration matches the primary resource locale.
     */
    private fun drawerLanguageReady(activity: MainActivity): Boolean {
        val languageReady: Boolean
        val appliedLanguage: AppLanguage?
        try {
            languageReady = MainActivity::class.java.getDeclaredField("languageReady")
                .apply { isAccessible = true }.getBoolean(activity)
            appliedLanguage = MainActivity::class.java.getDeclaredField("appliedAppLanguage")
                .apply { isAccessible = true }.get(activity) as AppLanguage?
        } catch (failure: Exception) {
            drawerFixtureIncomplete("stage=startup reason=language_read", failure)
        }
        val language = MiraxApp.instance.session.snapshot().appLanguage
        val locale = activity.resources.configuration.locales[0]
        val localeReady = when (language) {
            AppLanguage.ENGLISH -> locale.language == "en"
            AppLanguage.TRADITIONAL_CHINESE -> locale.language == "zh" && locale.country == "TW"
        }
        return languageReady && appliedLanguage == language && localeReady
    }

    /**
     * Guards identity before every transaction action, never during necessary cleanup.
     * @param scenario Real Main scenario.
     * @param expectedMain Locked ready Main.
     * @param action Native operation or observation after the identity guard.
     */
    private fun awaitDrawerAction(
        scenario: ActivityScenario<MainActivity>,
        expectedMain: MainActivity,
        action: (MainActivity) -> Unit,
    ) {
        awaitActivityAction(scenario) { currentMain ->
            requireDrawerMain(currentMain, expectedMain)
            action(currentMain)
        }
    }

    /**
     * Rejects recreation during a transaction instead of silently reacquiring Main.
     * @param currentMain Current scenario Main.
     * @param expectedMain Main locked after language readiness.
     */
    private fun requireDrawerMain(currentMain: MainActivity, expectedMain: MainActivity) {
        if (currentMain !== expectedMain) drawerFixtureIncomplete("stage=transaction reason=main_replaced")
    }

    /**
     * Holds actual native editor identities, not the synthetic edit model.
     * @property editor Actual Main editor.
     * @property overlay Actual Main overlay.
     */
    internal data class DrawerEditorFixture(val editor: StandbyFramingEditorView, val overlay: FrameLayout)

    /**
     * Reads typed actual modal fields; absent or unreadable fixtures cannot pass.
     * @param activity Locked transaction Main.
     * @return Actual editor and overlay identities.
     */
    internal fun readDrawerEditor(activity: MainActivity): DrawerEditorFixture {
        val editor: StandbyFramingEditorView?
        val overlay: FrameLayout?
        try {
            editor = MainActivity::class.java.getDeclaredField("mediaEditor")
                .apply { isAccessible = true }.get(activity) as StandbyFramingEditorView?
            overlay = MainActivity::class.java.getDeclaredField("mediaOverlay")
                .apply { isAccessible = true }.get(activity) as FrameLayout?
        } catch (failure: Exception) {
            drawerFixtureIncomplete("stage=editor reason=modal_read", failure)
        }
        if (editor == null || overlay == null) drawerFixtureIncomplete("stage=editor reason=modal_absent")
        return DrawerEditorFixture(editor, overlay)
    }

    /**
     * Checks the modal lifetime before any isolation assertion across main-loop boundaries.
     * @param activity Locked Main, checked by the enclosing action or phase guard.
     * @param expected Modal identities captured at editor attachment.
     */
    internal fun requireDrawerEditor(activity: MainActivity, expected: DrawerEditorFixture) {
        val actual = readDrawerEditor(activity)
        if (actual.editor !== expected.editor || actual.overlay !== expected.overlay) {
            drawerFixtureIncomplete("stage=editor reason=modal_replaced")
        }
        if (!expected.editor.isAttachedToWindow || !expected.overlay.isAttachedToWindow ||
            expected.overlay.parent !== activity.findViewById<FrameLayout>(R.id.root)
        ) drawerFixtureIncomplete("stage=editor reason=modal_detached")
    }

    /**
     * Reports only static fixture preconditions; native product assertions remain ordinary failures.
     * @param reason Static stage and reason, never user content.
     * @param cause Original observation or show failure when one exists.
     */
    private fun drawerFixtureIncomplete(reason: String, cause: Throwable? = null): Nothing {
        if (cause == null) throw AssertionError("DRAWER_ACCESSIBILITY fixture=INCOMPLETE $reason")
        throw AssertionError("DRAWER_ACCESSIBILITY fixture=INCOMPLETE $reason", cause)
    }

    /**
     * Bounds a drawer phase without changing its ordinary product assertions.
     * @param scenario Real Main scenario.
     * @param expectedMain Locked Main, or null only for necessary cleanup.
     * @param condition Native observation after identity validation.
     */
    internal fun awaitDrawerPhase(
        scenario: ActivityScenario<MainActivity>,
        expectedMain: MainActivity?,
        condition: (MainActivity) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            assertTrue("Drawer phase exceeded 5s", remaining > 0L)
            var done = false
            awaitActivityAction(scenario, remaining) { currentMain ->
                if (expectedMain != null) requireDrawerMain(currentMain, expectedMain)
                done = condition(currentMain)
            }
            if (done) {
                assertTrue("Drawer phase exceeded 5s", SystemClock.elapsedRealtime() <= deadline)
                return
            }
            Thread.yield()
        }
    }

    /** Finds only the visible home menu, never the broadcast action. */
    internal fun drawerMenu(activity: MainActivity): View? {
        val root = requireNotNull(activity.findViewById<ViewGroup>(R.id.root))
        return drawerDescendants(root).firstOrNull { it.tag == StandbyForegroundTags.MENU && it.isShown }
            ?: activity.findViewById<View>(R.id.menuButton)?.takeIf { it.isShown }
    }

    /** Enumerates real native children. */
    internal fun drawerDescendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { drawerDescendants(view.getChildAt(it)) } else emptyList()

    /** Checks flags plus the native accessibility and keyboard trees. */
    internal fun assertDrawerIsolation(activity: MainActivity) {
        val root = requireNotNull(activity.findViewById<ViewGroup>(R.id.root))
        val split = requireNotNull(activity.findViewById<ViewGroup>(R.id.split))
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, split.importantForAccessibility)
        assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, split.descendantFocusability)
        assertFalse(split.hasFocus())
        val children = arrayListOf<View>()
        root.addChildrenForAccessibility(children)
        val background = drawerDescendants(split)
        assertTrue(children.none { it in background })
        val focusables = arrayListOf<View>()
        root.addFocusables(focusables, View.FOCUS_FORWARD, View.FOCUSABLES_ALL)
        assertTrue(focusables.none { it in background })
    }

    /** @param activity Locked Main. @param expected Actual native modal identities. */
    internal fun assertDrawerEditorIsolation(activity: MainActivity, expected: DrawerEditorFixture) {
        requireDrawerEditor(activity, expected)
        assertDrawerIsolation(activity)
        val drawer = requireNotNull(activity.findViewById<ViewGroup>(R.id.drawer))
        val scrim = requireNotNull(activity.findViewById<View>(R.id.drawerScrim))
        assertTrue(expected.overlay.isClickable)
        assertTrue(expected.overlay.hasFocus())
        assertTrue(expected.overlay.elevation > drawer.elevation)
        assertEquals(activity.getString(R.string.standby_framing_title), ViewCompat.getAccessibilityPaneTitle(expected.overlay).toString())
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, drawer.importantForAccessibility)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, scrim.importantForAccessibility)
        assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, drawer.descendantFocusability)
        assertFalse(scrim.isFocusable)
    }

    /** Accepts zero-animation closure while checking every still-present boundary. */
    private fun assertDrawerClosingOrRestored(activity: MainActivity) {
        if (drawerGone(activity)) assertDrawerRestored(activity) else assertDrawerIsolation(activity)
    }

    /** Requires both native drawer surfaces to be gone. */
    internal fun drawerGone(activity: MainActivity): Boolean =
        requireNotNull(activity.findViewById<View>(R.id.drawer)).visibility == View.GONE &&
            requireNotNull(activity.findViewById<View>(R.id.drawerScrim)).visibility == View.GONE

    /**
     * Waits for both animations without changing system animation settings.
     * @param scenario Real Main scenario.
     * @param expectedMain Main locked for the transaction.
     */
    internal fun awaitDrawerGone(scenario: ActivityScenario<MainActivity>, expectedMain: MainActivity) {
        awaitDrawerPhase(scenario, expectedMain) { currentMain ->
            assertDrawerClosingOrRestored(currentMain)
            drawerGone(currentMain)
        }
    }

    /** @param closeOrigin Original native menu, never a replacement or detached focus target. */
    internal fun assertDrawerFocusRestored(closeOrigin: View?) {
        val origin = closeOrigin ?: drawerFixtureIncomplete("stage=close reason=origin_missing")
        if (!origin.isAttachedToWindow || !origin.isShown || !origin.isFocusable || !origin.isEnabled) {
            drawerFixtureIncomplete("stage=close reason=origin_ineligible")
        }
        assertTrue("Closing the drawer restores its attached background focus", origin.hasFocus())
    }

    /** Requires distinguishable original values, not framework defaults. */
    internal fun assertDrawerRestored(activity: MainActivity) {
        val split = requireNotNull(activity.findViewById<ViewGroup>(R.id.split))
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, split.importantForAccessibility)
        assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, split.descendantFocusability)
    }

    private fun layoutEditor(view: View) {
        val width = (480 * view.resources.displayMetrics.density + 0.5f).toInt()
        val height = (760 * view.resources.displayMetrics.density + 0.5f).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun confirmButton(view: View): Button = requireNotNull(findConfirm(view))

    private fun findConfirm(view: View): Button? {
        if (view is Button && view.text == view.context.getString(R.string.standby_media_confirm)) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findConfirm(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
