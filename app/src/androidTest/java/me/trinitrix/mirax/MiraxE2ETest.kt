package me.trinitrix.mirax

import android.app.ActivityManager
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import me.trinitrix.mirax.broadcast.BroadcastAutoStop
import me.trinitrix.mirax.broadcast.BroadcastAutoStopTimer
import me.trinitrix.mirax.data.standby.StandbyActive
import me.trinitrix.mirax.data.standby.StandbyFraming
import me.trinitrix.mirax.data.standby.StandbyMediaKind
import me.trinitrix.mirax.data.standby.StandbySnapshot
import me.trinitrix.mirax.host.SessionPlatformBinding
import me.trinitrix.mirax.session.AppLanguage
import me.trinitrix.mirax.session.LanguagePreference
import me.trinitrix.mirax.session.RootExitConfirmation
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.StandbyDestination
import me.trinitrix.mirax.session.StandbyNavigationState
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.ui.standby.BigStandbyHost
import me.trinitrix.mirax.ui.standby.StandbyHostLease
import me.trinitrix.mirax.ui.standby.StandbyMediaOwner
import me.trinitrix.mirax.ui.standby.StandbyTrackedDriver
import me.trinitrix.mirax.ui.standby.media.AndroidStandbyMediaDriver
import me.trinitrix.mirax.ui.standby.media.StandbyMediaUnderlayView
import me.trinitrix.mirax.ui.standby.media.StandbyPlaybackState
import me.trinitrix.mirax.ui.standby.runtime.StandbyRuntimeHost
import me.trinitrix.mirax.ui.standby.runtime.StandbyVisibilityState
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundTags
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundView
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
 * - Cold-start standby home and legacy dashboard rendering
 * - Navigation between homes and nested sections with origin return
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
    private lateinit var packageName: String
    private val launchTimeoutMs = 10000L
    private var main03Reason = "not-inspected"
    private var main03Snapshot = "not-inspected"
    private var originalWaitForIdleTimeout: Long? = null

    /**
     * Checks an observation budget; this cannot interrupt an in-flight SDK call.
     * @param startTime Monotonic start time.
     * @param timeout Original budget in milliseconds.
     * @param now Monotonic observation time, explicit for boundary self-checks.
     * @return Whether the observation is strictly before the deadline.
     */
    private fun hasWaitBudget(startTime: Long, timeout: Long, now: Long = SystemClock.elapsedRealtime()): Boolean {
        val elapsed = now - startTime
        return elapsed >= 0L && elapsed < timeout
    }

    /**
     * Records a successful observation only within its original budget.
     * @param label Wait name and captured diagnostic fields.
     * @param startTime Monotonic start time.
     * @param timeout Original budget in milliseconds.
     * @return Whether the caller may return its observed result.
     */
    private fun acceptWaitResult(label: String, startTime: Long, timeout: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!hasWaitBudget(startTime, timeout, now)) return false
        logMain03("wait=$label observedReady=true elapsedMs=${now - startTime} budgetMs=$timeout")
        return hasWaitBudget(startTime, timeout)
    }

    /**
     * Waits for a package-scoped UI object.
     *
     * @param selector object selector.
     * @param timeout maximum wait in milliseconds.
     * @return found UI object.
     */
    private fun waitForObject(selector: BySelector, timeout: Long): UiObject2 {
        val startTime = SystemClock.elapsedRealtime()
        while (hasWaitBudget(startTime, timeout)) {
            val obj = device.findObject(selector)
            if (!hasWaitBudget(startTime, timeout)) break
            if (obj != null && acceptWaitResult("object", startTime, timeout)) return obj
            if (!hasWaitBudget(startTime, timeout)) break
            Thread.sleep(100)
        }
        throw AssertionError("UI object not found within ${timeout}ms")
    }

    /**
     * Accessible controls belonging to the visible standby foreground.
     *
     * @param menuDescription menu accessibility description.
     * @param menuBounds clipped menu bounds.
     * @param broadcastDescription broadcast accessibility description.
     * @param broadcastBounds clipped broadcast bounds.
     * @param broadcastClassName Accessibility class of the tagged native dock.
     */
    private data class StandbyControls(
        val menuDescription: String,
        val menuBounds: Rect,
        val broadcastDescription: String,
        val broadcastBounds: Rect,
        val broadcastClassName: String,
    )

    /**
     * Waits for an enabled, clickable accessibility control with visible bounds.
     *
     * @param selector package-scoped control selector.
     * @param timeout maximum wait in milliseconds.
     * @return visible actionable control.
     */
    private fun waitForControl(selector: BySelector, timeout: Long): UiObject2 {
        val startTime = SystemClock.elapsedRealtime()
        while (hasWaitBudget(startTime, timeout)) {
            val candidates = device.findObjects(selector)
            if (!hasWaitBudget(startTime, timeout)) break
            val control = candidates.firstOrNull {
                hasWaitBudget(startTime, timeout) && it.isClickable && hasWaitBudget(startTime, timeout) &&
                    it.isEnabled && hasWaitBudget(startTime, timeout) && !it.visibleBounds.isEmpty
            }
            if (!hasWaitBudget(startTime, timeout)) break
            if (control != null && acceptWaitResult("control", startTime, timeout)) return control
            if (!hasWaitBudget(startTime, timeout)) break
            Thread.sleep(100)
        }
        throw AssertionError("Clickable UI control not found within ${timeout}ms")
    }

    /**
     * Finds an actionable page control, scrolling existing page containers if needed.
     *
     * @param selector package-scoped control selector.
     * @param timeout maximum wait in milliseconds.
     * @return visible actionable control.
     */
    private fun waitForPageControl(selector: BySelector, timeout: Long = 5000): UiObject2 {
        val startTime = SystemClock.elapsedRealtime()
        var direction = Direction.DOWN
        while (hasWaitBudget(startTime, timeout)) {
            val candidates = device.findObjects(selector)
            if (!hasWaitBudget(startTime, timeout)) break
            val control = candidates.firstOrNull {
                hasWaitBudget(startTime, timeout) && it.isClickable && hasWaitBudget(startTime, timeout) &&
                    it.isEnabled && hasWaitBudget(startTime, timeout) && !it.visibleBounds.isEmpty
            }
            if (!hasWaitBudget(startTime, timeout)) break
            if (control != null && acceptWaitResult("page-control", startTime, timeout)) return control
            if (!hasWaitBudget(startTime, timeout)) break
            var moved = false
            val scrolls = device.findObjects(By.pkg(packageName).clazz(ScrollView::class.java.name))
            if (!hasWaitBudget(startTime, timeout)) break
            scrolls.forEach {
                if (hasWaitBudget(startTime, timeout) && it.scroll(direction, 0.6f)) moved = true
            }
            if (!hasWaitBudget(startTime, timeout)) break
            if (!moved) {
                direction = if (direction == Direction.DOWN) Direction.UP else Direction.DOWN
                Thread.sleep(100)
            }
        }
        throw AssertionError("Clickable page control not found within ${timeout}ms")
    }

    /**
     * Finds a visible drawer card without matching the obscured home.
     *
     * @param label localized card label.
     * @param timeout maximum wait in milliseconds.
     * @return visible actionable card.
     */
    private fun waitForDrawerCard(label: String, timeout: Long): UiObject2 {
        val startTime = SystemClock.elapsedRealtime()
        var direction = Direction.DOWN
        while (hasWaitBudget(startTime, timeout)) {
            try {
                val drawer = device.findObject(By.pkg(packageName).res("$packageName:id/drawerBody"))
                if (!hasWaitBudget(startTime, timeout)) break
                val card = drawer?.findObject(By.descContains(label))
                if (!hasWaitBudget(startTime, timeout)) break
                var cardBounds = "not-observed"
                if (card != null && card.isClickable && hasWaitBudget(startTime, timeout) &&
                    card.isEnabled && hasWaitBudget(startTime, timeout) &&
                    !card.visibleBounds.also { cardBounds = it.toShortString() }.isEmpty &&
                    acceptWaitResult("drawer-card boundsAtPredicate=$cardBounds", startTime, timeout)
                ) return card
                if (!hasWaitBudget(startTime, timeout)) break
                direction = readDrawerScrollDirection(label, startTime, timeout) ?: direction
                if (!hasWaitBudget(startTime, timeout)) break
                // The existing drawer ScrollView directly owns drawerBody, unlike page scrollers.
                val scroll = device.findObject(By.pkg(packageName).clazz(ScrollView::class.java.name)
                    .hasChild(By.res("$packageName:id/drawerBody")))
                if (!hasWaitBudget(startTime, timeout)) break
                val canScrollFurther = scroll?.scroll(direction, 0.6f) == true
                if (!hasWaitBudget(startTime, timeout)) break
                if (scroll == null) {
                    Thread.sleep(100)
                } else if (!canScrollFurther) {
                    direction = if (direction == Direction.DOWN) Direction.UP else Direction.DOWN
                }
            } catch (error: StaleObjectException) {
                Log.w("MiraxMain03", "[DEBUG-main03] Stale drawer nodes; reacquiring within the original budget", error)
                if (!hasWaitBudget(startTime, timeout)) break
                Thread.sleep(100)
            }
        }
        throw AssertionError("Drawer card not found within ${timeout}ms: $label")
    }

    /**
     * Reads a unique native card's position before choosing the next public drawer gesture.
     * @param label Localized primary card label, not a detail/description substring.
     * @param startTime Original drawer-search start.
     * @param timeout Original drawer-search budget.
     * @return Direction toward the card, or null for unknown/already-contained geometry.
     */
    private fun readDrawerScrollDirection(label: String, startTime: Long, timeout: Long): Direction? =
        readNativeBackOnMain(startTime, timeout) {
            val main = resumedActivity() ?: return@readNativeBackOnMain null
            if (!main.hasWindowFocus()) return@readNativeBackOnMain null
            val body = main.findViewById<ViewGroup>(R.id.drawerBody) ?: return@readNativeBackOnMain null
            val scroll = body.parent as? ScrollView ?: return@readNativeBackOnMain null
            if (!isVisible(scroll) || scroll.isLayoutRequested || body.isLayoutRequested) return@readNativeBackOnMain null
            val target = (0 until body.childCount).mapNotNull { body.getChildAt(it) as? ViewGroup }
                .filter { card ->
                    // menuCard builds its primary label first; detail text is not target identity.
                    card.isClickable && (card.getChildAt(0) as? TextView)?.text?.toString() == label
                }.singleOrNull() ?: return@readNativeBackOnMain null
            if (!target.isAttachedToWindow || !target.isShown || target.isLayoutRequested || target.height <= 0) {
                return@readNativeBackOnMain null
            }
            // Direct body/card bounds and the viewport use the same ScrollView content coordinates.
            val viewportTop = scroll.scrollY + scroll.paddingTop
            val viewportBottom = scroll.scrollY + scroll.height - scroll.paddingBottom
            if (viewportBottom <= viewportTop) return@readNativeBackOnMain null
            when {
                body.top + target.top < viewportTop -> Direction.UP
                body.top + target.bottom > viewportBottom -> Direction.DOWN
                else -> null
            }
        }

    /**
     * Finds the resumed target activity on the main thread.
     *
     * @return resumed target activity, or null.
     */
    private fun resumedActivity(): MainActivity? =
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .firstOrNull { it is MainActivity && it.packageName == packageName } as? MainActivity

    /**
     * Resolves text from the currently localized activity rather than the application context.
     *
     * @param resourceId string resource.
     * @return localized activity text.
     */
    private fun currentText(resourceId: Int): String {
        var text: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            text = resumedActivity()?.getString(resourceId)
        }
        return text ?: throw AssertionError("Target activity is not resumed")
    }

    /**
     * Finds the real standby host in the activity view tree.
     *
     * @param view root of the inspected subtree.
     * @return standby host, or null.
     */
    private fun findStandbyHost(view: View): BigStandbyHost? {
        if (view is BigStandbyHost) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val host = findStandbyHost(view.getChildAt(index))
                if (host != null) return host
            }
        }
        return null
    }

    /**
     * Checks attachment and nonempty clipped bounds on the main thread.
     *
     * @param view inspected view.
     * @return whether the view is visibly attached.
     */
    private fun isVisible(view: View): Boolean {
        val bounds = Rect()
        return view.isAttachedToWindow && view.isShown && view.alpha > 0f &&
            view.getGlobalVisibleRect(bounds) && !bounds.isEmpty
    }

    /**
     * Logs diagnostic messages without changing the test outcome.
     * @param message Diagnostic fields, without user text.
     */
    private fun logMain03(message: String) {
        try {
            Log.i("MiraxMain03", "[DEBUG-main03] $message")
        } catch (error: Throwable) {
            runCatching { System.err.println("[DEBUG-main03] log failure=${error.javaClass.simpleName}") }
        }
    }

    /**
     * Records a native failure snapshot on the main thread.
     * @param reason Failed readiness check.
     * @param activity Resumed target, or null.
     * @return Always null, preserving the readiness check.
     */
    private fun standbyUnavailable(reason: String, activity: MainActivity?): StandbyControls? {
        val changed = main03Reason != reason
        main03Reason = reason
        try {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            val stages = Stage.values().filter { stage ->
                monitor.getActivitiesInStage(stage).any { it is MainActivity && it.packageName == packageName }
            }
            val host = activity?.window?.decorView?.let(::findStandbyHost)
            val foregrounds = host?.let { parent ->
                (0 until parent.childCount).map { parent.getChildAt(it) }.filterIsInstance<StandbyForegroundView>()
            }.orEmpty()
            val visible = foregrounds.filter(::isVisible)
            val foreground = visible.singleOrNull()
            fun flags(view: View?): String {
                val bounds = Rect()
                val clipped = view?.getGlobalVisibleRect(bounds) == true
                return "present=${view != null} visible=${view?.let(::isVisible)} " +
                    "clickable=${view?.isClickable} enabled=${view?.isEnabled} " +
                    "descNonBlank=${view?.contentDescription?.isNotBlank() == true} " +
                    "bounds=${if (clipped) bounds.toShortString() else "none"}"
            }
            val menu = foreground?.findViewWithTag<View>(StandbyForegroundTags.MENU)
            val broadcast = foreground?.findViewWithTag<View>(StandbyForegroundTags.BROADCAST)
            main03Snapshot = "observedMs=${SystemClock.elapsedRealtime()} reason=$reason " +
                "stages=$stages focus=${activity?.hasWindowFocus()} " +
                "drawer=${activity?.findViewById<View>(R.id.drawer)?.isShown} " +
                "scrim=${activity?.findViewById<View>(R.id.drawerScrim)?.isShown} " +
                "hostPresent=${host != null} hostVisible=${host?.let(::isVisible)} " +
                "foregroundTotal=${host?.let { foregrounds.size }} foregroundVisible=${host?.let { visible.size }} " +
                "menu={${flags(menu)}} broadcast={${flags(broadcast)}}"
        } catch (error: Throwable) {
            main03Snapshot = "reason=$reason nativeDiagnosticError=${error.javaClass.simpleName}"
            if (!changed) logMain03("native $main03Snapshot")
        }
        if (changed) logMain03("native $main03Snapshot")
        return null
    }

    /**
     * Inspects the active foreground and its native controls on the main thread.
     *
     * @return visible controls, or null while standby is not ready.
     */
    private fun inspectStandby(): StandbyControls? {
        val activity = resumedActivity() ?: return standbyUnavailable("no-resumed-activity", null)
        if (!activity.hasWindowFocus()) return standbyUnavailable("no-window-focus", activity)
        if (activity.findViewById<View>(R.id.drawer).isShown) return standbyUnavailable("drawer-visible", activity)
        if (activity.findViewById<View>(R.id.drawerScrim).isShown) return standbyUnavailable("scrim-visible", activity)
        val host = findStandbyHost(activity.window.decorView) ?: return standbyUnavailable("host-missing", activity)
        if (!isVisible(host)) return standbyUnavailable("host-invisible", activity)
        val foreground = (0 until host.childCount).map { host.getChildAt(it) }
            .filterIsInstance<StandbyForegroundView>().filter(::isVisible).singleOrNull()
            ?: return standbyUnavailable("foreground-count", activity)
        val menu = foreground.findViewWithTag<View>(StandbyForegroundTags.MENU)
            ?: return standbyUnavailable("menu-missing", activity)
        val broadcast = foreground.findViewWithTag<View>(StandbyForegroundTags.BROADCAST)
            ?: return standbyUnavailable("broadcast-missing", activity)
        if (!isVisible(menu) || !menu.isClickable || !menu.isEnabled ||
            !isVisible(broadcast) || !broadcast.isClickable || !broadcast.isEnabled
        ) return standbyUnavailable("control-not-actionable", activity)
        val menuDescription = menu.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: return standbyUnavailable("menu-description-blank", activity)
        val broadcastDescription = broadcast.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            ?: return standbyUnavailable("broadcast-description-blank", activity)
        val broadcastClassName = broadcast.createAccessibilityNodeInfo()?.className?.toString()?.takeIf { it.isNotBlank() }
            ?: return standbyUnavailable("broadcast-class-blank", activity)
        val menuBounds = Rect()
        val broadcastBounds = Rect()
        if (!menu.getGlobalVisibleRect(menuBounds) || !broadcast.getGlobalVisibleRect(broadcastBounds)) {
            return standbyUnavailable("control-bounds-missing", activity)
        }
        main03Snapshot = "observedMs=${SystemClock.elapsedRealtime()} " +
            "reason=ready resumed=true focus=true drawer=false scrim=false " +
            "hostPresent=true hostVisible=true foregroundVisible=1 " +
            "menu={visible=true clickable=true enabled=true descNonBlank=true bounds=${menuBounds.toShortString()}} " +
            "broadcast={visible=true clickable=true enabled=true descNonBlank=true " +
            "expectedDockClass=$broadcastClassName bounds=${broadcastBounds.toShortString()}}"
        if (main03Reason != "ready") logMain03("native $main03Snapshot")
        main03Reason = "ready"
        return StandbyControls(menuDescription, menuBounds, broadcastDescription, broadcastBounds, broadcastClassName)
    }

    /**
     * Waits for the real host and matching accessible foreground controls.
     *
     * @param timeout maximum wait in milliseconds.
     * @return verified foreground controls.
     */
    private fun waitForStandby(timeout: Long = 5000): StandbyControls {
        fun timedStandbyFind(label: String, selector: BySelector, expectedBounds: Rect? = null): UiObject2? {
            val start = SystemClock.elapsedRealtime()
            logMain03("find-$label beginMs=$start")
            var outcome = "threw"
            return try {
                val candidates = device.findObjects(selector)
                val target = if (expectedBounds != null) {
                    candidates.firstOrNull { Rect.intersects(it.visibleBounds, expectedBounds) }
                } else {
                    candidates.firstOrNull()
                }
                target.also {
                    outcome = if (it == null) "not-found" else "found"
                }
            } finally {
                val end = SystemClock.elapsedRealtime()
                logMain03("find-$label endMs=$end elapsedMs=${end - start} outcome=$outcome")
            }
        }
        val startTime = SystemClock.elapsedRealtime()
        var lastCheck = "not-observed"
        var lastLookupMs = 0L
        while (hasWaitBudget(startTime, timeout)) {
            var controls: StandbyControls? = null
            val inspectStart = SystemClock.elapsedRealtime()
            logMain03("inspect beginMs=$inspectStart")
            var inspected = false
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { controls = inspectStandby() }
                inspected = true
            } finally {
                val inspectEnd = SystemClock.elapsedRealtime()
                logMain03("inspect endMs=$inspectEnd elapsedMs=${inspectEnd - inspectStart} " +
                    "completed=$inspected ready=${controls != null}")
            }
            if (!hasWaitBudget(startTime, timeout)) break
            val current = controls
            if (current != null) {
                val menu = timedStandbyFind("menu", By.pkg(packageName).desc(current.menuDescription))
                if (!hasWaitBudget(startTime, timeout)) break
                val broadcast = timedStandbyFind(
                    "broadcast",
                    By.pkg(packageName).desc(current.broadcastDescription).clazz(current.broadcastClassName),
                    current.broadcastBounds,
                )
                lastLookupMs = SystemClock.elapsedRealtime()
                if (!hasWaitBudget(startTime, timeout)) break
                var menuClickable: Boolean? = null
                var menuEnabled: Boolean? = null
                var broadcastClickable: Boolean? = null
                var broadcastEnabled: Boolean? = null
                var menuNodeBounds: Rect? = null
                var broadcastNodeBounds: Rect? = null
                var menuIntersects: Boolean? = null
                var broadcastIntersects: Boolean? = null
                val matches = menu != null && broadcast != null && hasWaitBudget(startTime, timeout) &&
                    menu.isClickable.also { menuClickable = it } && hasWaitBudget(startTime, timeout) &&
                    menu.isEnabled.also { menuEnabled = it } && hasWaitBudget(startTime, timeout) &&
                    broadcast.isClickable.also { broadcastClickable = it } && hasWaitBudget(startTime, timeout) &&
                    broadcast.isEnabled.also { broadcastEnabled = it } && hasWaitBudget(startTime, timeout) &&
                    Rect.intersects(menu.visibleBounds.also { menuNodeBounds = it }, current.menuBounds)
                        .also { menuIntersects = it } && hasWaitBudget(startTime, timeout) &&
                    Rect.intersects(broadcast.visibleBounds.also { broadcastNodeBounds = it }, current.broadcastBounds)
                        .also { broadcastIntersects = it }
                try {
                    lastCheck = "predicateEndMs=${SystemClock.elapsedRealtime()} matched=$matches " +
                        "expectedDockClass=${current.broadcastClassName} " +
                        "actualPackage=not-observed actualDescription=not-observed " +
                        "menu={selectorMatchedAtFind=${if (menu == null) "unknown" else "true"} " +
                        "clickableAtPredicate=${menuClickable?.toString() ?: "not-observed"} " +
                        "enabledAtPredicate=${menuEnabled?.toString() ?: "not-observed"} " +
                        "boundsAtPredicate=${menuNodeBounds?.toShortString() ?: "not-observed"} " +
                        "nativeBoundsAtInspect=${current.menuBounds.toShortString()} " +
                        "intersectsNativeAtPredicate=${menuIntersects?.toString() ?: "not-observed"}} " +
                        "broadcast={selectorMatchedAtFind=${if (broadcast == null) "unknown" else "true"} " +
                        "clickableAtPredicate=${broadcastClickable?.toString() ?: "not-observed"} " +
                        "enabledAtPredicate=${broadcastEnabled?.toString() ?: "not-observed"} " +
                        "boundsAtPredicate=${broadcastNodeBounds?.toShortString() ?: "not-observed"} " +
                        "nativeBoundsAtInspect=${current.broadcastBounds.toShortString()} " +
                        "intersectsNativeAtPredicate=${broadcastIntersects?.toString() ?: "not-observed"}}"
                } catch (error: Throwable) {
                    lastCheck = "diagnosticError=${error.javaClass.simpleName}"
                    logMain03(lastCheck)
                }
                if (matches) {
                    logMain03("matched atMs=${SystemClock.elapsedRealtime()} lastCheck={$lastCheck}")
                    if (acceptWaitResult("standby expectedDockClass=${current.broadcastClassName}", startTime, timeout)) {
                        return current
                    }
                }
            }
            if (!hasWaitBudget(startTime, timeout)) break
            Thread.sleep(100)
        }
        val failure = AssertionError("Visible BigStandbyHost and accessible foreground controls not found within ${timeout}ms")
        try {
            logMain03("final atMs=${SystemClock.elapsedRealtime()} timeoutMs=$timeout " +
                "lastLookupMs=$lastLookupMs lastCheck={$lastCheck} native={$main03Snapshot}")
        } catch (error: Throwable) {
            failure.addSuppressed(error)
        }
        throw failure
    }

    /**
     * Finds visible page text without accepting a matching drawer label.
     *
     * @param view root of the inspected subtree.
     * @param text expected text.
     * @return whether matching page text is visible.
     */
    private fun hasVisibleText(view: View, text: String): Boolean {
        if (view is TextView && view.text.toString() == text && isVisible(view)) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (hasVisibleText(view.getChildAt(index), text)) return true
            }
        }
        return false
    }

    /**
     * Waits for localized text in one of the actual page containers.
     *
     * @param resourceId expected localized text resource.
     * @param timeout maximum wait in milliseconds.
     */
    private fun waitForPageText(resourceId: Int, timeout: Long = 5000) {
        val startTime = SystemClock.elapsedRealtime()
        var lastObservation = "not-observed"
        while (hasWaitBudget(startTime, timeout)) {
            var found = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity = resumedActivity()
                var focus = "not-observed"
                var drawerShown = "not-observed"
                var scrimShown = "not-observed"
                var startTextMatch = "not-observed"
                var endTextMatch = "not-observed"
                if (activity != null && activity.hasWindowFocus().also { focus = it.toString() } &&
                    !activity.findViewById<View>(R.id.drawer).isShown.also { drawerShown = it.toString() } &&
                    !activity.findViewById<View>(R.id.drawerScrim).isShown.also { scrimShown = it.toString() }
                ) {
                    val text = activity.getString(resourceId)
                    found = listOf(R.id.pageStart, R.id.pageEnd).any { containerId ->
                        hasVisibleText(activity.findViewById(containerId), text).also {
                            if (containerId == R.id.pageStart) startTextMatch = it.toString()
                            else endTextMatch = it.toString()
                        }
                    }
                }
                // Additional local metadata, not original short-circuit predicate observations.
                var addedLocalStartRootTag = "not-observed"
                var addedLocalEndRootTag = "not-observed"
                if (activity != null && hasWaitBudget(startTime, timeout)) {
                    addedLocalStartRootTag = (activity.findViewById<ViewGroup>(R.id.pageStart)
                        ?.getChildAt(0)?.tag as? StandbyDestination)?.name ?: "unknown"
                }
                if (activity != null && hasWaitBudget(startTime, timeout)) {
                    addedLocalEndRootTag = (activity.findViewById<ViewGroup>(R.id.pageEnd)
                        ?.getChildAt(0)?.tag as? StandbyDestination)?.name ?: "unknown"
                }
                val sample = SystemClock.elapsedRealtime()
                lastObservation = "sampleMs=$sample elapsedMs=${sample - startTime} " +
                    "withinBudget=${hasWaitBudget(startTime, timeout, sample)} resumedMain=${activity != null} " +
                    "focus=$focus drawerShown=$drawerShown scrimShown=$scrimShown " +
                    "startTextMatch=$startTextMatch endTextMatch=$endTextMatch found=$found " +
                    "addedLocalStartRootTag=$addedLocalStartRootTag addedLocalEndRootTag=$addedLocalEndRootTag"
            }
            if (!hasWaitBudget(startTime, timeout)) break
            if (found &&
                acceptWaitResult("page-text resourceId=$resourceId last={$lastObservation}", startTime, timeout)
            ) return
            if (!hasWaitBudget(startTime, timeout)) break
            Thread.sleep(100)
        }
        logMain03("wait=page-text resourceId=$resourceId outcome=timeout last={$lastObservation}")
        throw AssertionError("Page text $resourceId not found within ${timeout}ms")
    }

    /**
     * Waits for an existing page tag to appear or disappear outside the drawer.
     *
     * @param tag existing page view tag.
     * @param visible expected visibility.
     */
    private fun waitForPageTag(tag: String, visible: Boolean = true) {
        val startTime = SystemClock.elapsedRealtime()
        while (hasWaitBudget(startTime, 5000)) {
            var matched = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity = resumedActivity()
                if (activity != null && activity.hasWindowFocus() &&
                    !activity.findViewById<View>(R.id.drawer).isShown &&
                    !activity.findViewById<View>(R.id.drawerScrim).isShown
                ) {
                    val found = listOf(R.id.pageStart, R.id.pageEnd).any {
                        activity.findViewById<View>(it).findViewWithTag<View>(tag)?.let(::isVisible) == true
                    }
                    matched = found == visible
                }
            }
            if (!hasWaitBudget(startTime, 5000)) break
            if (matched && acceptWaitResult("page-tag", startTime, 5000)) return
            if (!hasWaitBudget(startTime, 5000)) break
            Thread.sleep(100)
        }
        throw AssertionError("Page tag $tag visibility did not become $visible")
    }

    /** Preserves device preparation, then scopes the SDK idle policy to this serial test. */
    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instrumentation)
        context = ApplicationProvider.getApplicationContext<Context>()
        // Dynamic target package supports both original and suffixed (.vanillaprobe) builds.
        packageName = instrumentation.targetContext.packageName

        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.pressHome()

        check(hasWaitBudget(100L, 10L, 109L) && !hasWaitBudget(100L, 10L, 110L) &&
            !hasWaitBudget(100L, 10L, 180L) && !hasWaitBudget(100L, 10L, 99L)
        ) { "Wait budget boundary self-check failed" }
        // Configurator is process-wide; do not run this class alongside parallel tests.
        val configurator = Configurator.getInstance()
        val before = configurator.waitForIdleTimeout
        originalWaitForIdleTimeout = before
        configurator.setWaitForIdleTimeout(0L)
        val applied = configurator.waitForIdleTimeout
        logMain03("idle beforeMs=$before appliedMs=$applied")
        check(applied == 0L) { "UiAutomator idle timeout was not disabled" }
    }

    /** Restores and verifies the saved SDK idle policy even when Home throws. */
    @After
    fun tearDown() {
        try {
            device.pressHome()
        } finally {
            originalWaitForIdleTimeout?.let { original ->
                val configurator = Configurator.getInstance()
                configurator.setWaitForIdleTimeout(original)
                val restored = configurator.waitForIdleTimeout
                logMain03("idle restoredMs=$restored expectedMs=$original")
                check(restored == original) { "UiAutomator idle timeout was not restored" }
            }
        }
    }

    /** Verifies a fresh activity starts at the real Big Standby home. */
    @Test
    fun testAppLaunch_showsDashboard() {
        launchApp()
        waitForStandby()
    }

    /** Opens history through the shared standby sidebar and returns home. */
    @Test
    fun testConnectionHistoryClick() {
        launchApp()
        openDrawer()
        waitForDrawerCard(currentText(R.string.history_title), 5000).click()
        waitForPageText(R.string.history_body)
        device.pressBack()
        waitForStandby()
    }

    /**
     * Test navigation to General settings and return to standby.
     */
    @Test
    fun testNavigation_toGeneral() {
        launchApp()

        openDrawer()

        waitForDrawerCard(currentText(R.string.nav_general), 5000).click()
        waitForPageText(R.string.nav_general)

        // Navigate back to standby
        device.pressBack()

        // Verify back to standby
        waitForStandby()
    }

    /**
     * Cold-start standby is the root; switching homes replaces the root and
     * nested sections return to the origin home.
     */
    @Test
    fun testStandbyColdStartAndOriginReturn() {
        launchApp()

        waitForStandby()
        openDrawer()
        waitForDrawerCard(currentText(R.string.nav_dashboard_legacy), 5000).click()
        waitForPageTag("legacyIdentity")

        openDrawer()
        waitForDrawerCard(currentText(R.string.history_title), 5000).click()
        waitForPageText(R.string.history_body)
        device.pressBack()
        waitForPageTag("legacyIdentity")
    }

    /**
     * Test Start broadcast button.
     */
    @Test
    fun testStartBroadcastButton() {
        launchApp()

        val controls = waitForStandby()
        val onDescription = currentText(R.string.standby_foreground_broadcast_desc_on)
        val offDescription = currentText(R.string.standby_foreground_broadcast_desc_off)
        if (controls.broadcastDescription == onDescription) {
            waitForControl(By.pkg(packageName).desc(onDescription), launchTimeoutMs).click()
            waitForControl(By.pkg(packageName).desc(offDescription), launchTimeoutMs)
        }

        val startButton = waitForControl(By.pkg(packageName).desc(offDescription), launchTimeoutMs)
        assertThat(startButton.isChecked, Matchers.equalTo(false))
        startButton.click()
        val stopButton = waitForControl(By.pkg(packageName).desc(onDescription), launchTimeoutMs)
        assertThat(stopButton.isChecked, Matchers.equalTo(true))
        stopButton.click()
        val stopped = waitForControl(By.pkg(packageName).desc(offDescription), launchTimeoutMs)
        assertThat(stopped.isChecked, Matchers.equalTo(false))
    }

    /**
     * Test navigation to Picture settings.
     */
    @Test
    fun testNavigation_toPicture() {
        launchApp()

        openDrawer()

        waitForDrawerCard(currentText(R.string.nav_picture), 5000).click()
        waitForPageText(R.string.nav_picture)

        // Navigate back
        device.pressBack()

        // Verify back to standby
        waitForStandby()
    }

    /**
     * Test navigation to Advanced settings.
     */
    @Test
    fun testNavigation_toAdvanced() {
        launchApp()

        openDrawer()

        waitForDrawerCard(currentText(R.string.nav_advanced), 5000).click()
        waitForPageText(R.string.nav_advanced)

        // Navigate back
        device.pressBack()

        // Verify back to standby
        waitForStandby()
    }

    /**
     * Test About page navigation and returning to standby root.
     */
    @Test
    fun testNavigation_toAbout() {
        launchApp()

        openDrawer()

        waitForDrawerCard(currentText(R.string.nav_about), 5000).click()
        waitForPageText(R.string.nav_about)

        // Navigate back
        device.pressBack()

        // Verify back to standby
        waitForStandby()
    }

    /**
     * Test dual-home switching and returning to the respective origin home.
     */
    @Test
    fun testDualHome_switchAndOriginReturn() {
        launchApp()

        waitForStandby()
        openDrawer()
        waitForDrawerCard(currentText(R.string.nav_dashboard_legacy), 5000).click()
        waitForPageTag("legacyIdentity")

        // Preserve coverage of the legacy dashboard's own history shortcut.
        waitForPageControl(By.pkg(packageName).descContains(currentText(R.string.history_entry))).click()
        waitForPageText(R.string.history_body)
        device.pressBack()
        waitForPageTag("legacyIdentity")

        waitForPageControl(By.pkg(packageName).text(currentText(R.string.dashboard_open_standby))).click()
        waitForStandby()
    }

    /**
     * Test nested navigation (Language -> General -> Standby).
     */
    @Test
    fun testNestedNavigation_languageToGeneralToStandby() {
        launchApp()

        openDrawer()
        waitForDrawerCard(currentText(R.string.nav_general), 5000).click()
        waitForPageText(R.string.nav_general)
        waitForPageControl(By.pkg(packageName).descContains(currentText(R.string.settings_language_title))).click()
        waitForPageTag("language")
        waitForPageText(R.string.settings_language_title)

        device.pressBack()
        waitForPageTag("language", visible = false)
        waitForPageText(R.string.nav_general)
        device.pressBack()
        waitForStandby()
    }

    /**
     * Test drawer close on Back without navigating away from current page.
     */
    @Test
    fun testBackDrawer_closesWithoutNavigating() {
        launchApp()

        openDrawer()
        device.pressBack()

        // Verify still on Standby
        waitForStandby()
    }

    /**
     * Test root single Back does not leave the app.
     */
    @Test
    fun testRootSingleBack_staysOnRoot() {
        launchApp()

        device.pressBack()

        // Verify still on Standby
        waitForStandby()
    }

    /** Changes language on the same page and restores the original language state. */
    @Test
    fun testLanguageChangesImmediatelyWithoutLeavingPage() {
        val original = captureLanguage()
        try {
            launchApp()
            openDrawer()
            waitForDrawerCard(currentText(R.string.nav_general), 5000).click()
            waitForPageText(R.string.nav_general)
            waitForPageControl(By.pkg(packageName).descContains(currentText(R.string.settings_language_title))).click()
            waitForPageTag("language")

            waitForPageControl(By.pkg(packageName).text(currentText(R.string.language_english))).click()
            waitForLanguageState("Language", "English")
            waitForPageControl(By.pkg(packageName).text("Traditional Chinese")).click()
            waitForLanguageState("語言", "繁體中文")
            waitForPageControl(By.pkg(packageName).text("英文")).click()
            waitForLanguageState("Language", "English")
            waitForPageControl(By.pkg(packageName).text("Follow system")).click()

            val systemChinese = me.trinitrix.mirax.host.HostEnvironment.isSystemTraditionalChinese(context)
            val systemTitle = if (systemChinese) "語言" else "Language"
            val followLabel = if (systemChinese) "跟隨系統" else "Follow system"
            waitForLanguageState(systemTitle, followLabel)
            waitForPageControl(By.pkg(packageName).text(if (systemChinese) "英文" else "English")).click()
            waitForLanguageState("Language", "English")
            waitForPageControl(By.pkg(packageName).text("Follow system")).click()
            waitForLanguageState(systemTitle, followLabel)
        } finally {
            restoreLanguage(original)
        }
    }

    /** Observes native Back across replaced roots and Dashboard-origin parents without editing settings. */
    @Test
    fun testSwitchedRootsAndDashboardParents() {
        launchApp()
        // Launch readiness and the initial readonly observation have separate budgets.
        val initialReadStart = SystemClock.elapsedRealtime()
        val initial = readNativeBackOnMain(initialReadStart, launchTimeoutMs) { nativeBackReading() }
        NativeBackFixture(initial, ::nativeBackReading).observe {
            drawerRoute(StandbyDestination.DASHBOARD, R.string.nav_dashboard_legacy, StandbyDestination.DASHBOARD)
            rootBack()
            drawerRoute(StandbyDestination.ADVANCED, R.string.nav_advanced, StandbyDestination.DASHBOARD)
            backTo(StandbyDestination.DASHBOARD)
            drawerRoute(StandbyDestination.ABOUT, R.string.nav_about, StandbyDestination.DASHBOARD)
            backTo(StandbyDestination.DASHBOARD)
            drawerRoute(StandbyDestination.STANDBY, R.string.nav_standby, StandbyDestination.STANDBY)
            rootBack()
        }
    }

    /** Observes editable settings parents and native IME/focus priority without changing settings. */
    @Test
    fun testEditableSettingsParentsAndBackPriority() {
        launchApp()
        val initialReadStart = SystemClock.elapsedRealtime()
        val initial = readNativeBackOnMain(initialReadStart, launchTimeoutMs) {
            nativeBackReading() to MiraxApp.instance.session.exportSettings()
        }
        val fixture = NativeBackFixture(initial.first) { expectedMain ->
            val value = nativeBackSettingsReading(expectedMain)
            assertThat("CHECK4_BACK persisted settings remain unchanged",
                MiraxApp.instance.session.exportSettings() == initial.second, Matchers.equalTo(true))
            value
        }
        fixture.observe {
            drawerRoute(StandbyDestination.DASHBOARD, R.string.nav_dashboard_legacy, StandbyDestination.DASHBOARD)
            drawerRoute(StandbyDestination.GENERAL, R.string.nav_general, StandbyDestination.DASHBOARD)
            nestedRoute(StandbyDestination.LANGUAGE, R.string.settings_language_title)
            settingsBackTo(StandbyDestination.GENERAL)
            settingsBackTo(StandbyDestination.DASHBOARD)
            drawerRoute(StandbyDestination.PICTURE, R.string.nav_picture, StandbyDestination.DASHBOARD)
            nestedRoute(StandbyDestination.CUSTOM, R.string.custom_resolution)
            val width = focusCustomWidth()
            settingsBackTo(StandbyDestination.PICTURE, width)
            nestedRoute(StandbyDestination.MODES, R.string.standard_modes_label)
            settingsBackTo(StandbyDestination.PICTURE)
            nestedRoute(StandbyDestination.SCALE, R.string.settings_scale_title)
            settingsBackTo(StandbyDestination.PICTURE)
            settingsBackTo(StandbyDestination.DASHBOARD)
        }
    }

    /** Observes own-dialog Back, expired/timely root exit and positive IMAGE retirement without cleanup-driven exit. */
    @Test
    fun testDialogAndRootExitTimingRelease() {
        val app = MiraxApp.instance
        val session = app.session
        val platform = SessionPlatformBinding.platform
        check(platform.javaClass.name == "me.trinitrix.mirax.host.FakeSessionPlatform") {
            "CHECK4_EXIT fixture=INCOMPLETE requires isolated fake platform"
        }
        val forbidden = nativeExitField(platform.javaClass, "forbiddenCalls").get(platform) as AtomicInteger
        check(forbidden.get() == 0) { "CHECK4_EXIT fixture=INCOMPLETE forbidden platform history" }
        val locked = AtomicReference<MainActivity?>(null)
        val startupCreations = AtomicInteger()
        val replacement = AtomicBoolean(false)
        val destroyed = AtomicReference<NativeExitDestroyed?>(null)
        val terminal = AtomicReference(NativeExitTerminal())
        val observerFailure = AtomicReference<Throwable?>(null)
        val callback = ActivityLifecycleCallback { activity, stage ->
            try {
                if (activity.javaClass == MainActivity::class.java) {
                    val expected = locked.get()
                    if (expected == null) {
                        if (stage == Stage.CREATED) startupCreations.incrementAndGet()
                    } else if (activity !== expected && (stage == Stage.CREATED || stage == Stage.RESUMED)) {
                        replacement.set(true)
                    } else if (activity === expected && stage == Stage.DESTROYED) {
                        destroyed.compareAndSet(null, NativeExitDestroyed(
                            SystemClock.elapsedRealtime(), activity.isFinishing, activity.isChangingConfigurations,
                        ))
                    }
                }
            } catch (error: Throwable) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                observerFailure.compareAndSet(null, error)
                logMain03("CHECK4_EXIT lifecycleFailure=${error.javaClass.simpleName}")
            }
        }
        var primaryFailure: Throwable? = null
        try {
            readNativeBackOnMain(SystemClock.elapsedRealtime()) {
                ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
            }
            launchApp()
            val initialStart = SystemClock.elapsedRealtime()
            val ready = readNativeBackOnMain(initialStart, launchTimeoutMs) {
                val value = nativeBackReading()
                check(value.windowFocused && value.homeVisible && value.navigation ==
                    StandbyNavigationState(StandbyDestination.STANDBY, StandbyDestination.STANDBY)
                ) { "CHECK4_EXIT fixture=INCOMPLETE focused cold Standby missing" }
                check(!value.main.isFinishing && !value.main.isDestroyed) { "CHECK4_EXIT fixture=INCOMPLETE Main already leaving" }
                locked.set(value.main)
                Triple(value, session.exportSettings(), value.main.taskId)
            }
            val main = ready.first.main
            val baseline = ready.first.session
            val originalSettings = ready.second
            val oldTaskId = ready.third
            check(oldTaskId >= 0) { "CHECK4_EXIT fixture=INCOMPLETE original task unavailable" }
            val fixture = NativeBackFixture(ready.first, ::nativeBackReading)
            val dashboard = StandbyNavigationState(StandbyDestination.DASHBOARD, StandbyDestination.DASHBOARD)
            val standby = StandbyNavigationState(StandbyDestination.STANDBY, StandbyDestination.STANDBY)
            val timer = readNativeBackOnMain(initialStart, launchTimeoutMs) {
                val delegate = nativeExitField(BroadcastAutoStop::class.java, "timer\$delegate")
                    .get(BroadcastAutoStop) as Lazy<*>
                check(delegate.isInitialized()) { "CHECK4_EXIT fixture=INCOMPLETE idle timer unavailable" }
                delegate.value as BroadcastAutoStopTimer
            }

            /** Checks the same process authorities, never logging their payloads. */
            fun unchanged() {
                observerFailure.get()?.let { throw it }
                check(!replacement.get()) { "CHECK4_EXIT replacement Main after readiness" }
                check(app.session === session && SessionPlatformBinding.platform === platform && forbidden.get() == 0) {
                    "CHECK4_EXIT platform/session identity or forbidden command changed"
                }
                val value = session.snapshot()
                check(value.phase == baseline.phase && value.wfdOwner == baseline.wfdOwner &&
                    value.advertisingEnabled == baseline.advertisingEnabled && value.wfdAdvertise == baseline.wfdAdvertise &&
                    value.showPicture == baseline.showPicture && value.connectionRuns == baseline.connectionRuns &&
                    value.connectionOfferModes == baseline.connectionOfferModes &&
                    value.connectionPreferredMode == baseline.connectionPreferredMode && value.selectedMode == baseline.selectedMode &&
                    session.exportSettings() == originalSettings) { "CHECK4_EXIT session/command/settings changed" }
                check((nativeExitField(BroadcastAutoStopTimer::class.java, "armedMinutes").get(timer) != null) == ready.first.idleArmed &&
                    (nativeExitField(BroadcastAutoStop::class.java, "pendingStop").get(BroadcastAutoStop) != null) == ready.first.idlePending
                ) { "CHECK4_EXIT idle clock state changed" }
            }

            /** Reads the original locked live Main inside the supplied phase. @param start Phase origin. @param timeout Original fence. @return Live root facts. */
            fun live(start: Long, timeout: Long = 5000L): NativeBackReading {
                val value = fixture.reading(start, timeout)
                fixture.verify(value, standby)
                check(value.homeVisible && !value.drawerVisible) { "CHECK4_EXIT fixture=INCOMPLETE Standby root unavailable" }
                readNativeBackOnMain(start, timeout) {
                    unchanged()
                    check(!main.isFinishing && !main.isDestroyed && destroyed.get() == null && main.taskId == oldTaskId) {
                        "CHECK4_EXIT Main/task changed before qualified exit input"
                    }
                }
                return value
            }

            fixture.observe {
                Log.i("MiraxCheck4Back", "CHECK4_EXIT locked main=${System.identityHashCode(main)} task=$oldTaskId startupCreated=${startupCreations.get()}")
                drawerRoute(StandbyDestination.DASHBOARD, R.string.nav_dashboard_legacy, StandbyDestination.DASHBOARD)
                val dialogStart = SystemClock.elapsedRealtime()
                nativeExitClickControl(dialogStart, main, "frozenInfo", R.string.frozen_dialog_title)
                val title = readNativeBackOnMain(dialogStart) { main.getString(R.string.frozen_dialog_title) }
                var ownDialog: Dialog? = null
                while (true) {
                    val titleBounds = readNativeBackOnMain(dialogStart) {
                        unchanged()
                        check(resumedActivity() === main && main.taskId == oldTaskId) { "CHECK4_EXIT dialog replaced Main/task" }
                        val value = nativeBackReading(main)
                        check(value.navigation == dashboard && StandbyDestination.DASHBOARD in value.visiblePages &&
                            !value.rootPending && value.rootSince == null) { "CHECK4_EXIT dialog changed route/root confirmation" }
                        val dialog = nativeExitField(MainActivity::class.java, "frozenDialog").get(main) as Dialog?
                        if (dialog?.isShowing != true || dialog.window?.decorView?.hasWindowFocus() != true) {
                            null
                        } else {
                            val titles = arrayListOf<View>()
                            dialog.window?.decorView?.findViewsWithText(titles, title, View.FIND_VIEWS_WITH_TEXT)
                            val nativeTitle = titles.filterIsInstance<TextView>()
                                .singleOrNull { it.text.toString() == title && isVisible(it) }
                            if (nativeTitle != null) {
                                val location = IntArray(2)
                                nativeTitle.getLocationOnScreen(location)
                                ownDialog = dialog
                                Rect(location[0], location[1], location[0] + nativeTitle.width, location[1] + nativeTitle.height)
                            } else null
                        }
                    }
                    if (titleBounds != null) {
                        val nodes = device.findObjects(By.pkg(packageName).text(title))
                        within(dialogStart)
                        val found = nodes.any { node ->
                            within(dialogStart)
                            val bounds = node.visibleBounds
                            within(dialogStart)
                            Rect.intersects(bounds, titleBounds)
                        }
                        within(dialogStart)
                        if (found) break
                    }
                    within(dialogStart)
                    SystemClock.sleep(25L)
                    within(dialogStart)
                }
                val dismissStart = SystemClock.elapsedRealtime()
                readNativeBackOnMain(dismissStart) {
                    unchanged()
                    check(ownDialog?.isShowing == true &&
                        nativeExitField(MainActivity::class.java, "frozenDialog").get(main) === ownDialog
                    ) { "CHECK4_EXIT fixture=INCOMPLETE own dialog missing before Back" }
                }
                within(dismissStart)
                val dialogInputBefore = SystemClock.elapsedRealtime()
                val dialogAccepted = device.pressKeyCode(KeyEvent.KEYCODE_BACK)
                val dialogInputAfter = SystemClock.elapsedRealtime()
                within(dismissStart)
                assertThat("CHECK4_EXIT dialog Back admission", dialogAccepted, Matchers.equalTo(true))
                while (true) {
                    val gone = readNativeBackOnMain(dismissStart) {
                        unchanged()
                        ownDialog?.isShowing == false && nativeExitField(MainActivity::class.java, "frozenDialog").get(main) == null
                    }
                    if (gone) {
                        val value = reading(dismissStart)
                        verify(value, dashboard)
                        check(!value.rootPending && value.rootSince == null && !value.drawerVisible) {
                            "CHECK4_EXIT dialog Back armed root or opened drawer"
                        }
                        Log.i("MiraxCheck4Back", "CHECK4_EXIT dialogBack main=${System.identityHashCode(main)} " +
                            "inputBeforeMs=$dialogInputBefore inputAfterMs=$dialogInputAfter sampleMs=${value.observedMs} rootPending=false")
                        within(dismissStart)
                        break
                    }
                    within(dismissStart)
                    SystemClock.sleep(25L)
                    within(dismissStart)
                }
                step(StandbyDestination.STANDBY, StandbyDestination.STANDBY) { start ->
                    nativeExitClickControl(start, main, "openStandby", R.string.dashboard_open_standby)
                }
                val mediaStart = SystemClock.elapsedRealtime()
                val available = runBlocking { app.standbyMediaStore.snapshot() } as? StandbySnapshot.Available
                    ?: throw AssertionError("CHECK4_EXIT fixture=INCOMPLETE retained Available missing")
                within(mediaStart)
                val active = available.active
                check(active.generation == 1L && active.kind == StandbyMediaKind.IMAGE &&
                    active.framing == StandbyFraming(0.5f, 0.5f, 1.2f) && active.file?.length() == 799L
                ) { "CHECK4_EXIT fixture=INCOMPLETE retained IMAGE baseline differs" }
                within(mediaStart)
                var image: NativeExitImage? = null
                while (image == null) {
                    live(mediaStart)
                    image = readNativeBackOnMain(mediaStart) { captureNativeExitImage(main, active) }
                    if (image == null) {
                        within(mediaStart)
                        SystemClock.sleep(25L)
                    }
                    within(mediaStart)
                }
                val originalImage = image
                checkNativeExitEvidence()
                val originalTask = readNativeBackOnMain(mediaStart) {
                    unchanged()
                    bindNativeExitTask(mediaStart, oldTaskId, main.componentName, terminal)
                }

                /** Performs one first/rearming Back and observes its own prompt inside the original 2s fence. @param previous Previous real arm, or null initially. @return Renewed real arm. */
                fun confirmRoot(previous: Long?): Long {
                    val start = SystemClock.elapsedRealtime()
                    val before = live(start)
                    val qualified = if (previous == null) before.rootSince == null else
                        before.rootSince == previous && SystemClock.elapsedRealtime() - previous > 2000L
                    check(!before.rootPending && qualified) { "CHECK4_EXIT fixture=INCOMPLETE first/expired root prerequisite" }
                    val prompt = readNativeBackOnMain(start) {
                        unchanged()
                        check(originalImage.live()) { "CHECK4_EXIT positive IMAGE resources disappeared before root input" }
                        main.getString(R.string.press_back_again_to_leave)
                    }
                    within(start)
                    val presentation = RootToastPrompt.observe(device, packageName, prompt, null) { within(start) }
                    val inputBefore = presentation.inputBeforeElapsedMs
                    val inputAfter = presentation.inputAfterElapsedMs
                    within(inputBefore, 2000L)
                    while (true) {
                        val value = live(inputBefore, 2000L)
                        val since = value.rootSince
                        if (value.rootPending && since != null && since in inputBefore..presentation.observedElapsedMs && since != previous) {
                            readNativeBackOnMain(inputBefore, 2000L) {
                                unchanged()
                                check(originalImage.live()) { "CHECK4_EXIT positive IMAGE resources disappeared before confirmation seal" }
                            }
                            val sealed = live(inputBefore, 2000L)
                            check(sealed.rootPending && sealed.rootSince == since) { "CHECK4_EXIT confirmation arm changed before seal" }
                            within(inputBefore, 2000L)
                            Log.i("MiraxCheck4Back", "CHECK4_EXIT confirmation main=${System.identityHashCode(main)} " +
                                "inputBeforeMs=$inputBefore inputAfterMs=$inputAfter armedMs=$since sampleMs=${sealed.observedMs} " +
                                "inputBeforeUptimeMs=${presentation.inputBeforeUptimeMs} inputAfterUptimeMs=${presentation.inputAfterUptimeMs} " +
                                "eventUptimeMs=${presentation.eventUptimeMs} observedUptimeMs=${presentation.observedUptimeMs} " +
                                "eventObservedElapsedMs=${presentation.observedElapsedMs} origin=$packageName class=android.widget.Toast matching=true")
                            within(inputBefore, 2000L)
                            return since
                        }
                        within(inputBefore, 2000L)
                        SystemClock.sleep(25L)
                        within(inputBefore, 2000L)
                    }
                }

                val firstSince = confirmRoot(null)
                val expiredStart = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - firstSince <= 2000L) {
                    live(expiredStart)
                    within(expiredStart)
                    SystemClock.sleep(25L)
                    within(expiredStart)
                }
                live(expiredStart)
                val renewedSince = confirmRoot(firstSince)
                val exitStart = SystemClock.elapsedRealtime()
                terminal.updateAndGet { it.copy(exitStartMs = exitStart, renewedSinceMs = renewedSince) }
                val preExit = live(exitStart)
                check(preExit.rootPending && preExit.rootSince == renewedSince) { "CHECK4_EXIT renewed confirmation missing" }
                readNativeBackOnMain(exitStart) {
                    unchanged()
                    check(originalImage.live()) { "CHECK4_EXIT positive IMAGE resources missing before timely exit" }
                }
                within(renewedSince, 2000L)
                val inputBefore = SystemClock.elapsedRealtime()
                terminal.updateAndGet { it.copy(inputBeforeMs = inputBefore) }
                val exitAccepted = device.pressKeyCode(KeyEvent.KEYCODE_BACK)
                val inputAfter = SystemClock.elapsedRealtime()
                terminal.updateAndGet { it.copy(inputAfterMs = inputAfter, inputAdmitted = exitAccepted) }
                within(renewedSince, 2000L)
                within(exitStart)
                assertThat("CHECK4_EXIT timely Back admission", exitAccepted, Matchers.equalTo(true))
                check(inputBefore >= renewedSince && inputAfter >= inputBefore && inputAfter - renewedSince < 2000L) {
                    "CHECK4_EXIT fixture=INCOMPLETE timely input brackets missed"
                }
                while (true) {
                    terminal.updateAndGet {
                        NativeExitTerminal(exitStartMs = it.exitStartMs, renewedSinceMs = it.renewedSinceMs,
                            inputBeforeMs = it.inputBeforeMs, inputAfterMs = it.inputAfterMs, inputAdmitted = it.inputAdmitted,
                            sampleBeforeMs = SystemClock.elapsedRealtime(), taskPrebound = it.taskPrebound)
                    }
                    val retired = readNativeBackOnMain(exitStart) {
                        unchanged()
                        val event = destroyed.get()
                        terminal.updateAndGet { it.copy(callbackPresent = event != null) }
                        if (event == null) false else {
                            val observedMs = event.observedMs
                            val qualified = observedMs >= inputBefore && event.finishing && !event.changingConfigurations
                            terminal.updateAndGet { it.copy(callbackObservedMs = observedMs, callbackQualified = qualified) }
                            check(qualified) { "CHECK4_EXIT destruction was not the input-owned finish" }
                            val isDestroyed = main.isDestroyed
                            terminal.updateAndGet { it.copy(isDestroyed = isDestroyed) }
                            isDestroyed && originalImage.retired(terminal)
                        }
                    }
                    terminal.updateAndGet { it.copy(sampleAfterMs = SystemClock.elapsedRealtime()) }
                    if (retired && nativeExitTaskDeparted(exitStart, originalTask, terminal)) {
                        terminal.updateAndGet {
                            it.copy(sampleBeforeMs = SystemClock.elapsedRealtime(), sampleAfterMs = null,
                                isDestroyed = null, closeFailureAbsent = null, leaseBinding = null, driverOutput = null,
                                underlayOutput = null, ownerSettled = null, mainStopped = null, runtimeStopped = null, retired = null)
                        }
                        readNativeBackOnMain(exitStart) {
                            unchanged()
                            val isDestroyed = main.isDestroyed
                            terminal.updateAndGet { it.copy(isDestroyed = isDestroyed) }
                            check(isDestroyed && originalImage.retired(terminal)) { "CHECK4_EXIT retirement changed before terminal seal" }
                        }
                        terminal.updateAndGet { it.copy(sampleAfterMs = SystemClock.elapsedRealtime()) }
                        within(exitStart)
                        break
                    }
                    within(exitStart)
                    SystemClock.sleep(25L)
                    within(exitStart)
                }
                val retainedStart = SystemClock.elapsedRealtime()
                val after = runBlocking { app.standbyMediaStore.snapshot() }
                within(retainedStart)
                check(after == available && active.file.length() == 799L) { "CHECK4_EXIT retained Available changed after exit" }
                within(retainedStart)
                Log.i("MiraxCheck4Back", "CHECK4_EXIT terminal main=${System.identityHashCode(main)} task=$oldTaskId " +
                    "inputBeforeMs=$inputBefore inputAfterMs=$inputAfter destroyedMs=${destroyed.get()?.observedMs} resourcesRetired=true")
                within(retainedStart)
            }
        } catch (error: Throwable) {
            primaryFailure = error
            if (error is InterruptedException) Thread.currentThread().interrupt()
            try {
                logNativeExitTerminal(terminal.get())
            } catch (diagnosticFailure: Throwable) {
                val marker = logNativeBackDiagnosticFailure("exitTerminal", diagnosticFailure)
                if (error !== marker && error.suppressed.none { it === marker }) error.addSuppressed(marker)
            }
            throw error
        } finally {
            try {
                readNativeBackOnMain(SystemClock.elapsedRealtime()) {
                    ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
                    observerFailure.get()?.let { throw it }
                    check(!replacement.get()) { "CHECK4_EXIT replacement Main before observer removal" }
                }
            } catch (cleanup: Throwable) {
                if (cleanup is InterruptedException) Thread.currentThread().interrupt()
                val primary = primaryFailure
                if (primary == null) throw cleanup
                if (primary !== cleanup) primary.addSuppressed(cleanup)
            }
        }
    }

    /** Precise callback evidence, not a cleanup/PID surrogate. */
    private data class NativeExitDestroyed(val observedMs: Long, val finishing: Boolean, val changingConfigurations: Boolean)

    /** Retains only evaluated scalar facts; null means UNOBSERVED, including short-circuited groups. */
    private data class NativeExitTerminal(
        val exitStartMs: Long? = null,
        val renewedSinceMs: Long? = null,
        val inputBeforeMs: Long? = null,
        val inputAfterMs: Long? = null,
        val inputAdmitted: Boolean? = null,
        val sampleBeforeMs: Long? = null,
        val sampleAfterMs: Long? = null,
        val callbackPresent: Boolean? = null,
        val callbackObservedMs: Long? = null,
        val callbackQualified: Boolean? = null,
        val isDestroyed: Boolean? = null,
        val closeFailureAbsent: Boolean? = null,
        val leaseBinding: Boolean? = null,
        val driverOutput: Boolean? = null,
        val underlayOutput: Boolean? = null,
        val ownerSettled: Boolean? = null,
        val mainStopped: Boolean? = null,
        val runtimeStopped: Boolean? = null,
        val retired: Boolean? = null,
        val taskPrebound: Boolean? = null,
        val taskReached: Boolean? = null,
        val taskReadValid: Boolean? = null,
        val taskGone: Boolean? = null,
        val taskMatches: Boolean? = null,
        val taskRunning: Boolean? = null,
        val taskActivities: Int? = null,
        val taskVisible: Boolean? = null,
        val taskDeparted: Boolean? = null,
        val rootPresent: Boolean? = null,
        val windowPresent: Boolean? = null,
        val windowActive: Boolean? = null,
        val windowFocused: Boolean? = null,
        val windowApplication: Boolean? = null,
        val windowMatchesRoot: Boolean? = null,
        val packageNonblank: Boolean? = null,
        val packageForeign: Boolean? = null,
        val windowQualified: Boolean? = null,
    )

    /** Positively identified own Main task; never format the handle or component. */
    private class NativeExitTaskBinding(val handle: ActivityManager.AppTask, val taskId: Int, val component: ComponentName)

    /** Private identity closures never expose request/file payloads; retirement annotates the original predicate order. */
    private class NativeExitImage(
        val lease: Long,
        val requestToken: Long,
        val live: () -> Boolean,
        val retired: (AtomicReference<NativeExitTerminal>) -> Boolean,
    )

    /** Reads a fixed private field only. @param type Declaring type. @param name Fixed source field. @return Accessible field metadata. */
    private fun nativeExitField(type: Class<*>, name: String): Field = type.getDeclaredField(name).apply { isAccessible = true }

    /** Clicks the public control only after its SDK bounds match the actual Dashboard tag. @param start Original input phase. @param main Locked Main. @param tag Existing control tag. @param title Existing label resource. */
    private fun nativeExitClickControl(start: Long, main: MainActivity, tag: String, title: Int) {
        val label = readNativeBackOnMain(start) { main.getString(title) }
        val control = waitForPageControl(By.pkg(packageName).text(label))
        check(hasWaitBudget(start, 5000L)) { "CHECK4_EXIT fixture=INCOMPLETE control lookup deadline" }
        val nativeBounds = readNativeBackOnMain(start) {
            check(resumedActivity() === main && main.hasWindowFocus()) { "CHECK4_EXIT fixture=INCOMPLETE control Main changed" }
            val page = listOf(R.id.pageStart, R.id.pageEnd).mapNotNull { id ->
                main.findViewById<ViewGroup>(id)?.getChildAt(0)
            }.singleOrNull { it.tag == StandbyDestination.DASHBOARD && isVisible(it) }
                ?: throw AssertionError("CHECK4_EXIT fixture=INCOMPLETE visible Dashboard missing")
            val view = page.findViewWithTag<View>(tag)
                ?: throw AssertionError("CHECK4_EXIT fixture=INCOMPLETE tagged Dashboard control missing")
            check(view.isClickable && view.isEnabled && isVisible(view)) {
                "CHECK4_EXIT fixture=INCOMPLETE Dashboard control not actionable"
            }
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
        }
        check(hasWaitBudget(start, 5000L)) { "CHECK4_EXIT fixture=INCOMPLETE control bounds deadline" }
        val accessibleBounds = control.visibleBounds
        check(hasWaitBudget(start, 5000L)) { "CHECK4_EXIT fixture=INCOMPLETE control bounds deadline" }
        assertThat("CHECK4_EXIT actual tagged control overlap", Rect.intersects(accessibleBounds, nativeBounds), Matchers.equalTo(true))
        control.click()
        check(hasWaitBudget(start, 5000L)) { "CHECK4_EXIT fixture=INCOMPLETE control input deadline" }
    }

    /** Captures actual nonempty IMAGE ownership before exit. @param main Locked Main. @param active Original Available record. @return Private identity witness, or null while preparation is incomplete. */
    private fun captureNativeExitImage(main: MainActivity, active: StandbyActive): NativeExitImage? {
        val owner = MiraxApp.instance.standbyMediaOwner
        val driver = nativeExitField(StandbyMediaOwner::class.java, "driver").get(owner) as AndroidStandbyMediaDriver
        val leases = nativeExitField(StandbyMediaOwner::class.java, "leases").get(owner) as StandbyHostLease
        val tracked = nativeExitField(StandbyMediaOwner::class.java, "trackedDriver").get(owner) as StandbyTrackedDriver
        val host = nativeExitField(MainActivity::class.java, "standbyHost").get(main) as? BigStandbyHost ?: return null
        val underlay = host.mediaUnderlay
        val leaseField = nativeExitField(MainActivity::class.java, "mediaLease")
        val lease = leaseField.get(main) as? Long ?: return null
        val bindingField = nativeExitField(StandbyMediaOwner::class.java, "binding")
        val binding = bindingField.get(owner) ?: return null
        val bindingLease = nativeExitField(binding.javaClass, "lease")
        val bindingView = nativeExitField(binding.javaClass, "view")
        val requestField = nativeExitField(AndroidStandbyMediaDriver::class.java, "request")
        val request = requestField.get(driver) ?: return null
        val tokenField = nativeExitField(request.javaClass, "token")
        val token = tokenField.getLong(request)
        val fileField = nativeExitField(request.javaClass, "file")
        val file = active.file ?: return null
        val sourceLease = nativeExitField(request.javaClass, "sourceLease")
        val drawableField = nativeExitField(AndroidStandbyMediaDriver::class.java, "drawable")
        val drawable = drawableField.get(driver) as? BitmapDrawable ?: return null
        val runtime = MainActivity::class.java.getDeclaredMethod("getStandbyRuntime").apply { isAccessible = true }
            .invoke(main) as StandbyRuntimeHost

        /** Reads an owner Boolean; never formats private state. @param name Fixed field name. @return Current flag. */
        fun ownerFlag(name: String): Boolean = nativeExitField(StandbyMediaOwner::class.java, name).getBoolean(owner)
        /** Reads an owner nullable field without exposing it. @param name Fixed field name. @return Whether absent. */
        fun ownerNull(name: String): Boolean = nativeExitField(StandbyMediaOwner::class.java, name).get(owner) == null
        /** Reads a driver nullable field without exposing it. @param name Fixed field name. @return Whether absent. */
        fun driverNull(name: String): Boolean = nativeExitField(AndroidStandbyMediaDriver::class.java, name).get(driver) == null
        /** Checks the existing transaction boundary without modifying ownership. @return Whether no transaction/cleanup is pending. */
        fun settled(): Boolean = !ownerFlag("cleanupPending") && !ownerFlag("cleanupInProgress") &&
            !ownerFlag("staging") && !ownerFlag("loadingActive") && !ownerFlag("refreshPending") &&
            ownerNull("cleanupWaiter") && ownerNull("edit")
        /** Checks only observed keep-awake ownership. @return Whether the original window flag is held. */
        fun awake(): Boolean = main.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

        val live = {
            val size = underlay.decodedContentSize()
            val bitmap = drawable.bitmap
            check(driverNull("closeFailure")) { "CHECK4_EXIT driver close failure" }
            leaseField.get(main) == lease && leases.isCurrent(lease) && bindingField.get(owner) === binding &&
                bindingLease.getLong(binding) == lease && bindingView.get(binding) === underlay &&
                requestField.get(driver) === request && tokenField.getLong(request) == token && fileField.get(request) == file &&
                !nativeExitField(request.javaClass, "isVideo").getBoolean(request) &&
                !nativeExitField(request.javaClass, "isAnimated").getBoolean(request) &&
                nativeExitField(request.javaClass, "imagePrepared").getBoolean(request) && sourceLease.get(request) == null &&
                tracked.isPrepared(file) && drawableField.get(driver) === drawable &&
                nativeExitField(StandbyMediaUnderlayView::class.java, "mediaDrawable").get(underlay) === drawable &&
                drawable.callback === underlay && bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0 &&
                !drawable.bounds.isEmpty && size != null && size.width > 0 && size.height > 0 &&
                underlay.isOwnedBy(driver) && driver.isBoundUnderlay(underlay) && isVisible(underlay) &&
                underlay.currentFraming() == active.framing &&
                nativeExitField(StandbyMediaOwner::class.java, "active").get(owner) == active && settled() &&
                runtime.currentVisibility() == StandbyVisibilityState.Visible && runtime.shouldHoldKeepAwake() && awake()
        }
        if (!live()) return null
        val retired = { terminal: AtomicReference<NativeExitTerminal> ->
            val closeFailureAbsent = driverNull("closeFailure")
            terminal.updateAndGet { it.copy(closeFailureAbsent = closeFailureAbsent) }
            check(closeFailureAbsent) { "CHECK4_EXIT driver close failure after exit" }
            // Static IMAGE uses no source pin; null-to-null is not asserted as a pin-release witness.
            val result = (leaseField.get(main) == null && !leases.isCurrent(lease) && bindingField.get(owner) == null)
                .also { value -> terminal.updateAndGet { it.copy(leaseBinding = value) } } &&
                (requestField.get(driver) == null && driverNull("closing") &&
                    !nativeExitField(AndroidStandbyMediaDriver::class.java, "loading").getBoolean(driver) &&
                    driverNull("input") && drawableField.get(driver) == null && sourceLease.get(request) == null)
                    .also { value -> terminal.updateAndGet { it.copy(driverOutput = value) } } &&
                (!tracked.isPrepared(file) && !driver.isBoundUnderlay(underlay) && !underlay.isOwnedBy(driver) &&
                    nativeExitField(StandbyMediaUnderlayView::class.java, "mediaDriver").get(underlay) == null &&
                    nativeExitField(StandbyMediaUnderlayView::class.java, "mediaDrawable").get(underlay) == null &&
                    drawable.callback == null && underlay.decodedContentSize() == null)
                    .also { value -> terminal.updateAndGet { it.copy(underlayOutput = value) } } &&
                (settled() &&
                    nativeExitField(StandbyMediaOwner::class.java, "playbackState").get(owner) == StandbyPlaybackState.PAUSED &&
                    ownerNull("decodedSize") && !ownerFlag("mediaReady") && !ownerFlag("visible") &&
                    nativeExitField(StandbyMediaOwner::class.java, "active").get(owner) == active)
                    .also { value -> terminal.updateAndGet { it.copy(ownerSettled = value) } } &&
                (nativeExitField(MainActivity::class.java, "mediaCleanupCancel").get(main) == null &&
                    !nativeExitField(MainActivity::class.java, "activityStarted").getBoolean(main) &&
                    !nativeExitField(MainActivity::class.java, "standbyMediaReady").getBoolean(main))
                    .also { value -> terminal.updateAndGet { it.copy(mainStopped = value) } } &&
                (runtime.currentVisibility() == StandbyVisibilityState.Background && !runtime.shouldRunAmbient() &&
                    !runtime.isHoldingSensors() && !runtime.shouldHoldKeepAwake() && !awake())
                    .also { value -> terminal.updateAndGet { it.copy(runtimeStopped = value) } }
            terminal.updateAndGet { it.copy(retired = result) }
            result
        }
        return NativeExitImage(lease, token, live, retired)
    }

    /** Fences each SDK read with the unchanged 5s phase. @param start Original phase origin. @param read Readonly SDK call. @return Timely result. */
    private fun <T> readNativeExitWithin(start: Long, read: () -> T): T {
        check(hasWaitBudget(start, 5000L)) { "CHECK4_EXIT fixture=INCOMPLETE task/window read deadline" }
        val result = read()
        check(hasWaitBudget(start, 5000L)) { "CHECK4_EXIT fixture=INCOMPLETE task/window read deadline" }
        return result
    }

    /** Binds a positively visible own Main task before input, within mediaStart. @param start Original preparation phase. @param taskId Locked Main task. @param component Actual Main component. @param terminal Scalar-only trace. @return Unique positive task binding. */
    private fun bindNativeExitTask(
        start: Long,
        taskId: Int,
        component: ComponentName,
        terminal: AtomicReference<NativeExitTerminal>,
    ): NativeExitTaskBinding {
        terminal.updateAndGet { it.copy(taskPrebound = false) }
        check(taskId >= 0 && component.packageName == packageName && component.className == MainActivity::class.java.name) {
            "CHECK4_EXIT fixture=INCOMPLETE original own Main task identity unavailable"
        }
        val manager = readNativeExitWithin(start) { context.getSystemService(ActivityManager::class.java) }
            ?: throw AssertionError("CHECK4_EXIT fixture=INCOMPLETE ActivityManager unavailable")
        val handles = checkNotNull(readNativeExitWithin(start) { manager.appTasks }) {
            "CHECK4_EXIT fixture=INCOMPLETE own task query unavailable"
        }
        var binding: NativeExitTaskBinding? = null
        for (handle in handles) {
            val info = checkNotNull(readNativeExitWithin(start) { handle.taskInfo }) {
                "CHECK4_EXIT fixture=INCOMPLETE own task facts unavailable before input"
            }
            readNativeExitWithin(start) {
                if (info.taskId == taskId) {
                    check(binding == null && info.baseActivity == component && info.topActivity == component &&
                        info.isRunning && info.numActivities > 0 && readNativeExitWithin(start) { info.isVisible }) {
                        "CHECK4_EXIT fixture=INCOMPLETE ambiguous or nonforeground original Main task"
                    }
                    binding = NativeExitTaskBinding(handle, taskId, component)
                }
            }
        }
        val original = checkNotNull(binding) { "CHECK4_EXIT fixture=INCOMPLETE original own task not positively bound" }
        readNativeExitWithin(start) { terminal.updateAndGet { it.copy(taskPrebound = true) } }
        return original
    }

    /** Classifies only successful observations of a positively prebound task. @param value Retained scalar facts. @return Whether that exact task is gone or hidden, not merely historical. */
    private fun qualifiesNativeExitTask(value: NativeExitTerminal): Boolean =
        value.taskPrebound == true && value.taskReadValid == true &&
            (value.taskGone == true || (value.taskGone == false && value.taskMatches == true && value.taskVisible == false))

    /** Requires a fresh active/input-focused application root, never a Toast or last-package cache. @param value Retained scalar facts. @return Whether the observed root belongs to another focused application. */
    private fun qualifiesNativeExitWindow(value: NativeExitTerminal): Boolean =
        value.rootPresent == true && value.windowPresent == true && value.windowActive == true &&
            value.windowFocused == true && value.windowApplication == true && value.windowMatchesRoot == true &&
            value.packageNonblank == true && value.packageForeign == true

    /** Requires fresh own-task departure plus a focused foreign application window under exitStart. @param start Original exit phase. @param original Positive pre-input binding. @param terminal Scalar-only trace. @return Qualified departure. */
    private fun nativeExitTaskDeparted(
        start: Long,
        original: NativeExitTaskBinding,
        terminal: AtomicReference<NativeExitTerminal>,
    ): Boolean {
        terminal.updateAndGet { it.copy(taskReached = true) }
        // Only a successful null is documented absence; IPC/permission/argument failures propagate unchanged.
        val info = readNativeExitWithin(start) { original.handle.taskInfo }
        val facts = readNativeExitWithin(start) {
            if (info == null) {
                terminal.updateAndGet { it.copy(taskReadValid = true, taskGone = true) }
            } else {
                val taskId = info.taskId
                val running = info.isRunning
                val activities = info.numActivities
                val visible = readNativeExitWithin(start) { info.isVisible }
                terminal.updateAndGet {
                    it.copy(taskReadValid = taskId >= 0 && activities >= 0, taskGone = false,
                        taskMatches = taskId == original.taskId, taskRunning = running,
                        taskActivities = activities, taskVisible = visible)
                }
            }
        }
        val departed = qualifiesNativeExitTask(facts)
        terminal.updateAndGet { it.copy(taskDeparted = departed, sampleAfterMs = SystemClock.elapsedRealtime()) }
        return departed && nativeExitFocusedWindow(start, terminal)
    }

    /** Samples the existing connection without changing flags/listeners; all acquired handles are recycled. @param start Original exit phase. @param terminal Scalar-only trace. @return Fresh qualified application-window evidence. */
    @Suppress("DEPRECATION")
    private fun nativeExitFocusedWindow(start: Long, terminal: AtomicReference<NativeExitTerminal>): Boolean {
        val automation = checkNotNull(readNativeExitWithin(start) {
            InstrumentationRegistry.getInstrumentation().getUiAutomation(Configurator.getInstance().uiAutomationFlags)
        }) { "CHECK4_EXIT fixture=INCOMPLETE existing accessibility connection unavailable" }
        var root: AccessibilityNodeInfo? = null
        var window: AccessibilityWindowInfo? = null
        var failure: Throwable? = null
        var qualified = false
        /** Preserves the first original query/cleanup error. @param error Original error, never formatted here. */
        fun record(error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            val primary = failure
            if (primary == null) failure = error else if (primary !== error && primary.suppressed.none { it === error }) {
                primary.addSuppressed(error)
            }
        }
        try {
            readNativeExitWithin(start) { root = automation.rootInActiveWindow }
            val observedRoot = root
            terminal.updateAndGet { it.copy(rootPresent = observedRoot != null) }
            if (observedRoot != null) {
                readNativeExitWithin(start) { window = observedRoot.window }
                val observedWindow = window
                terminal.updateAndGet { it.copy(windowPresent = observedWindow != null) }
                if (observedWindow != null) {
                    val active = readNativeExitWithin(start) { observedWindow.isActive }
                    terminal.updateAndGet { it.copy(windowActive = active) }
                    val focused = readNativeExitWithin(start) { observedWindow.isFocused }
                    terminal.updateAndGet { it.copy(windowFocused = focused) }
                    val application = readNativeExitWithin(start) { observedWindow.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    terminal.updateAndGet { it.copy(windowApplication = application) }
                    val windowId = readNativeExitWithin(start) { observedWindow.id }
                    val matches = windowId >= 0 && windowId == readNativeExitWithin(start) { observedRoot.windowId }
                    terminal.updateAndGet { it.copy(windowMatchesRoot = matches) }
                    if (active && focused && application && matches) {
                        val observedPackage = readNativeExitWithin(start) { observedRoot.packageName?.toString() }
                        terminal.updateAndGet {
                            it.copy(packageNonblank = !observedPackage.isNullOrBlank(),
                                packageForeign = observedPackage?.let { value -> value.isNotBlank() && value != packageName })
                        }
                    }
                }
            }
            qualified = qualifiesNativeExitWindow(terminal.get())
            terminal.updateAndGet { it.copy(windowQualified = qualified, sampleAfterMs = SystemClock.elapsedRealtime()) }
        } catch (error: Throwable) {
            record(error)
        } finally {
            try { window?.recycle() } catch (error: Throwable) { record(error) }
            try { root?.recycle() } catch (error: Throwable) { record(error) }
        }
        failure?.let { throw it }
        return readNativeExitWithin(start) { qualified }
    }

    /** Pure boundary checks, reached only by B2 preparation; these add no test selector or SDK work. */
    private fun checkNativeExitEvidence() {
        val gone = NativeExitTerminal(taskPrebound = true, taskReadValid = true, taskGone = true)
        val hidden = gone.copy(taskGone = false, taskMatches = true, taskRunning = true, taskActivities = 1, taskVisible = false)
        check(qualifiesNativeExitTask(gone) && qualifiesNativeExitTask(hidden))
        check(!qualifiesNativeExitTask(gone.copy(taskPrebound = null)) && !qualifiesNativeExitTask(gone.copy(taskPrebound = false)))
        check(!qualifiesNativeExitTask(hidden.copy(taskMatches = false)) && !qualifiesNativeExitTask(hidden.copy(taskReadValid = null)))
        check(!qualifiesNativeExitTask(hidden.copy(taskVisible = true)) && !qualifiesNativeExitTask(NativeExitTerminal()))
        check(!qualifiesNativeExitTask(gone.copy(taskReadValid = false)) && !qualifiesNativeExitTask(hidden.copy(taskVisible = null)))
        val window = NativeExitTerminal(rootPresent = true, windowPresent = true, windowActive = true,
            windowFocused = true, windowApplication = true, windowMatchesRoot = true, packageNonblank = true, packageForeign = true)
        check(qualifiesNativeExitWindow(window))
        check(!qualifiesNativeExitWindow(window.copy(windowPresent = null)) && !qualifiesNativeExitWindow(window.copy(windowActive = false)))
        check(!qualifiesNativeExitWindow(window.copy(windowFocused = false)) && !qualifiesNativeExitWindow(window.copy(windowApplication = false)))
        check(!qualifiesNativeExitWindow(window.copy(windowMatchesRoot = false)) && !qualifiesNativeExitWindow(window.copy(packageNonblank = false)))
        check(!qualifiesNativeExitWindow(window.copy(packageForeign = false)) && !qualifiesNativeExitWindow(NativeExitTerminal()))
    }

    /** Formats only retained scalars, never performing a postdeadline UI/reflection/task read. @param value Last already-observed facts; null fields mean UNOBSERVED. */
    private fun logNativeExitTerminal(value: NativeExitTerminal) {
        val firstFalseGroup = when {
            value.taskPrebound == false -> "ownTaskPrebind"
            value.inputAdmitted == false -> "inputAdmission"
            value.callbackPresent == false -> "callbackPresence"
            value.callbackQualified == false -> "callbackQualification"
            value.isDestroyed == false -> "isDestroyed"
            value.closeFailureAbsent == false -> "driverClose"
            value.leaseBinding == false -> "leaseBinding"
            value.driverOutput == false -> "driverOutput"
            value.underlayOutput == false -> "underlayOutput"
            value.ownerSettled == false -> "ownerSettled"
            value.mainStopped == false -> "mainStopped"
            value.runtimeStopped == false -> "runtimeStopped"
            value.retired == false -> "retirement"
            value.taskDeparted == false -> "ownTaskDeparture"
            value.windowQualified == false -> "focusedApplicationWindow"
            else -> "UNOBSERVED"
        }
        Log.i("MiraxCheck4Back", "CHECK4_EXIT terminalObserved firstFalseGroup=$firstFalseGroup facts=$value")
    }

    /** Exercises genuine parked-root escape in both directions and native input retained by a real uncommitted child. */
    @Test
    fun testRootParkingDpadAndChildDraftInput() {
        runNativeMainFocus {
            enterCustom()
            val initial = tapWidth()
            check(initial.text.isEmpty() && initial.selectionStart == 0 && initial.selectionEnd == 0) {
                "MAIN_FOCUS fixture=INCOMPLETE fresh Custom draft unavailable"
            }
            parkWidth(initial)
            escapeRoot(View.FOCUS_FORWARD)
            val tapped = tapWidth()
            val draft = hideWidthIme(tapped)
            check(draft.text.isEmpty() && draft.selectionStart == 0 && draft.selectionEnd == 0) {
                "MAIN_FOCUS fixture=INCOMPLETE fresh child draft/caret unavailable"
            }
            val typeStart = SystemClock.elapsedRealtime()
            val beforeType = state(typeStart)
            check(beforeType.draft == draft && beforeType.focus === draft.field && !beforeType.rootParked) {
                "MAIN_FOCUS numeric input requires the real child after parking installation"
            }
            key(typeStart, KeyEvent.KEYCODE_1)
            val typed = awaitState(typeStart) {
                it.focus === draft.field && !it.rootParked && it.draft?.let { field ->
                    field.field === draft.field && field.text == "1" && field.selectionStart == 1 && field.selectionEnd == 1
                } == true
            }
            val leftStart = SystemClock.elapsedRealtime()
            val beforeLeft = state(leftStart)
            check(beforeLeft.focus === draft.field && beforeLeft.draft == typed.draft && !beforeLeft.rootParked) {
                "MAIN_FOCUS child direction prerequisite changed"
            }
            key(leftStart, KeyEvent.KEYCODE_DPAD_LEFT)
            val left = awaitState(leftStart) {
                it.focus === draft.field && !it.rootParked && it.draft?.let { field ->
                    field.field === draft.field && field.text == "1" && field.selectionStart == 0 && field.selectionEnd == 0
                } == true
            }
            parkWidth(checkNotNull(left.draft))
            val backward = escapeRoot(View.FOCUS_BACKWARD)
            check(backward.draft?.field === draft.field && backward.draft.text == "1") {
                "MAIN_FOCUS root traversal changed the uncommitted private draft"
            }
        }
    }

    /** Observes native drawer/dialog focus ownership after the public editable-to-root handoff installed parking. */
    @Test
    fun testParkedRootModalInputIsolation() {
        runNativeMainFocus {
            enterCustom()
            parkWidth(tapWidth())
            drawerCycle()
            routes.drawerRoute(StandbyDestination.DASHBOARD, R.string.nav_dashboard_legacy, StandbyDestination.DASHBOARD)
            route = StandbyNavigationState(StandbyDestination.DASHBOARD, StandbyDestination.DASHBOARD)
            dialogCycle()
        }
    }

    /** Uses native keyboard Menu activation and requires the positively captured eligible Menu to regain focus. */
    @Test
    fun testKeyboardMenuActivationRestoresEligibleFocus() {
        runNativeMainFocus {
            keyboardMenuActivationRestoresEligibleFocus()
        }
    }

    /** Private draft/caret facts; neither this object nor its text is ever formatted. */
    private data class NativeFocusDraft(val field: EditText, val text: String, val selectionStart: Int, val selectionEnd: Int)

    /** Immutable scalar snapshots plus private identity references, never a live off-main property read. */
    private data class NativeFocusState(
        val focus: View?,
        val meaningful: Boolean,
        val rootParked: Boolean,
        val rootFocusable: Boolean,
        val ime: NativeBackImeVisibility,
        val draft: NativeFocusDraft?,
        val drawerOpen: Boolean,
        val drawerGone: Boolean,
        val scrimGone: Boolean,
        val drawerOwnsFocus: Boolean,
        val splitAccessibility: Int,
        val splitDescendants: Int,
        val splitHasFocus: Boolean,
        val splitHasNoFocusables: Boolean,
        val backgroundKeyboardHidden: Boolean,
        val backgroundAccessibilityHidden: Boolean,
        val observedMs: Long,
        val focusInSplit: Boolean,
    )

    /** Existing acknowledged key brackets, not a second input or a new clock origin. */
    private data class NativeFocusKeyInput(val beforeMs: Long, val afterMs: Long)

    /** Only completed-poll scalars survive for escape diagnostics; no View, draft or session payload. */
    private data class NativeFocusEscapeSample(
        val observedMs: Long,
        val rootParked: Boolean,
        val ime: NativeBackImeVisibility,
        val focusPresent: Boolean,
        val meaningful: Boolean,
        val inSplit: Boolean,
        val samePredicted: Boolean,
    )

    /** Actual owned dialog/window identities and private cached Main focus, not a demand that Main's cached focus be null. */
    private data class NativeFocusDialog(
        val dialog: Dialog,
        val decor: View,
        val token: android.os.IBinder,
        val focus: View?,
        val mainCachedFocus: View?,
        val titleBounds: Rect,
        val focusedChild: Boolean,
    )

    /** Launches once and reuses the unchanged route observer, bounded queue and editable reader. @param action One exact native focus protocol. */
    private fun runNativeMainFocus(action: NativeMainFocus.() -> Unit) {
        check(nativeFocusDirection(0, 1) == KeyEvent.KEYCODE_DPAD_DOWN &&
            nativeFocusDirection(0, -1) == KeyEvent.KEYCODE_DPAD_UP &&
            nativeFocusDirection(1, 0) == KeyEvent.KEYCODE_DPAD_RIGHT &&
            nativeFocusDirection(-1, 0) == KeyEvent.KEYCODE_DPAD_LEFT && nativeFocusDirection(0, 0) == null
        ) { "MAIN_FOCUS geometry classifier self-check" }
        val app = MiraxApp.instance
        val session = app.session
        val platform = SessionPlatformBinding.platform
        check(packageName == "me.trinitrix.mirax.vanillaprobe" &&
            platform.javaClass.name == "me.trinitrix.mirax.host.FakeSessionPlatform"
        ) { "MAIN_FOCUS fixture=INCOMPLETE isolated fake binding required" }
        val forbidden = nativeExitField(platform.javaClass, "forbiddenCalls").get(platform) as AtomicInteger
        check(forbidden.get() == 0) { "MAIN_FOCUS forbidden platform history" }
        val launchStart = SystemClock.elapsedRealtime()
        launchApp()
        check(hasWaitBudget(launchStart, launchTimeoutMs)) { "MAIN_FOCUS fixture=INCOMPLETE launch deadline" }
        val readyStart = SystemClock.elapsedRealtime()
        val ready = readNativeBackOnMain(readyStart) {
            val base = nativeBackReading()
            Triple(base, session.exportSettings(), base.main.taskId)
        }
        val main = ready.first.main
        val baseline = ready.first.session
        val invariant: (NativeBackReading) -> Unit = { value ->
            check(resumedActivity() === main && value.main === main && !main.isFinishing && !main.isDestroyed &&
                main.taskId == ready.third && ready.third >= 0 && app.session === session &&
                SessionPlatformBinding.platform === platform && forbidden.get() == 0 && session.exportSettings() == ready.second
            ) { "MAIN_FOCUS original Main/task/settings/platform changed" }
            val current = value.session
            check(current.phase == baseline.phase && current.wfdOwner == baseline.wfdOwner &&
                current.advertisingEnabled == baseline.advertisingEnabled && current.wfdAdvertise == baseline.wfdAdvertise &&
                current.showPicture == baseline.showPicture && current.connectionRuns == baseline.connectionRuns &&
                current.connectionOfferModes == baseline.connectionOfferModes &&
                current.connectionPreferredMode == baseline.connectionPreferredMode && current.selectedMode == baseline.selectedMode &&
                !value.idleArmed && !value.idlePending && !value.rootPending && value.rootSince == null &&
                nativeExitField(MainActivity::class.java, "mediaEditor").get(main) == null &&
                nativeExitField(MainActivity::class.java, "mediaOverlay").get(main) == null &&
                nativeExitField(MainActivity::class.java, "mediaEdit").get(main) == null &&
                !nativeExitField(MainActivity::class.java, "mediaStaging").getBoolean(main)
            ) { "MAIN_FOCUS navigation input changed session/idle/root/media state" }
        }
        val fixture = NativeBackFixture(ready.first) { expectedMain ->
            nativeBackSettingsReading(expectedMain).also(invariant)
        }
        var primary: Throwable? = null
        try {
            fixture.observe { NativeMainFocus(main, this, invariant).action() }
        } catch (error: Throwable) {
            primary = error
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw error
        } finally {
            try {
                readNativeBackOnMain(SystemClock.elapsedRealtime()) { invariant(readNativeBackFacts(main)) }
            } catch (cleanup: Throwable) {
                if (cleanup is InterruptedException) Thread.currentThread().interrupt()
                val original = primary
                if (original == null) throw cleanup
                if (original !== cleanup && original.suppressed.none { it === cleanup }) original.addSuppressed(cleanup)
            }
        }
    }

    /** Coded only for these three non-mutating Main protocols; modal and ordinary readers stay distinct. */
    private inner class NativeMainFocus(
        private val main: MainActivity,
        val routes: NativeBackFixture,
        private val invariant: (NativeBackReading) -> Unit,
    ) {
        var route = StandbyNavigationState(StandbyDestination.STANDBY, StandbyDestination.STANDBY)

        /** Retains the original UI fence. @param start Phase origin. */
        private fun within(start: Long) = routes.within(start)

        /** Reads no synthetic state and preserves the route observer's errors. @param base Actual Main facts, including a modal's unfocused Main. */
        private fun common(base: NativeBackReading) {
            routes.observerFailure.get()?.let { throw it }
            check(!routes.unexpectedRoute.get()) { "MAIN_FOCUS intermediate route changed" }
            invariant(base)
            check(base.navigation == route && route.current in base.visiblePages) { "MAIN_FOCUS route/visible pane changed" }
        }

        /** One public key, never called from a polling predicate. @param start Original phase. @param code Exact protocol key. @return Existing strict-ACK input brackets. */
        fun key(start: Long, code: Int): NativeFocusKeyInput {
            within(start)
            val before = SystemClock.elapsedRealtime()
            val admitted = device.pressKeyCode(code)
            val after = SystemClock.elapsedRealtime()
            within(start)
            assertThat("MAIN_FOCUS native key admission", admitted, Matchers.equalTo(true))
            check(after >= before) { "MAIN_FOCUS non-monotonic key bracket" }
            Log.i("MiraxCheck4Focus", "key=$code inputBeforeMs=$before inputAfterMs=$after")
            within(start)
            return NativeFocusKeyInput(before, after)
        }

        /** Reads ordinary Main focus with the unchanged B1 policy. @param start Phase origin. @return Private immutable state. */
        fun state(start: Long): NativeFocusState = readNativeBackOnMain(start) {
            val base = nativeBackSettingsReading(main)
            common(base)
            routes.verify(base, route)
            val root = main.findViewById<ViewGroup>(R.id.root)
            val split = main.findViewById<ViewGroup>(R.id.split)
            val drawer = main.findViewById<ViewGroup>(R.id.drawer)
            val focus = main.currentFocus
            val keyboard = arrayListOf<View>()
            root.addFocusables(keyboard, View.FOCUS_FORWARD, View.FOCUSABLES_ALL)
            val accessibility = arrayListOf<View>()
            root.addChildrenForAccessibility(accessibility)
            val page = nativeFocusPage(main, StandbyDestination.CUSTOM)
            val width = page?.findViewWithTag<View>("w") as? EditText
            val draft = width?.let { NativeFocusDraft(it, checkNotNull(it.text).toString(), it.selectionStart, it.selectionEnd) }
            val result = NativeFocusState(focus,
                nativeFocusMeaningful(main, focus) && focus != null && focus.isFocused && focus.hasWindowFocus(),
                root.isFocused && focus === root && root.hasWindowFocus(), root.isFocusable,
                checkNotNull(base.settingsFocus).imeVisibility, draft,
                nativeExitField(MainActivity::class.java, "drawerOpen").getBoolean(main),
                drawer.visibility == View.GONE, main.findViewById<View>(R.id.drawerScrim).visibility == View.GONE,
                focus != null && nativeFocusWithin(focus, drawer) && focus.isFocused && focus.hasWindowFocus(),
                split.importantForAccessibility, split.descendantFocusability, split.hasFocus(),
                split.getFocusables(View.FOCUS_FORWARD).isEmpty(),
                keyboard.none { nativeFocusWithin(it, split) }, accessibility.none { nativeFocusWithin(it, split) },
                base.observedMs, nativeFocusWithin(focus, split))
            if (!result.drawerGone || !result.scrimGone) {
                check(!result.rootFocusable && result.splitAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS &&
                    result.splitDescendants == ViewGroup.FOCUS_BLOCK_DESCENDANTS && !result.splitHasFocus &&
                    result.splitHasNoFocusables && result.backgroundKeyboardHidden && result.backgroundAccessibilityHidden
                ) { "MAIN_FOCUS drawer/closing split isolation lost" }
            }
            result
        }

        /** Polls readonly facts only. @param start Original phase. @param complete Required actual state. @return Timely state. */
        fun awaitState(start: Long, complete: (NativeFocusState) -> Boolean): NativeFocusState {
            while (true) {
                val value = state(start)
                if (complete(value)) {
                    within(start)
                    return value
                }
                within(start)
                SystemClock.sleep(25L)
                within(start)
            }
        }

        /** Activates only a natively focused Menu and requires its positive eligible identity to return after Back. */
        fun keyboardMenuActivationRestoresEligibleFocus() {
            val acquisitionStart = SystemClock.elapsedRealtime()
            check(route == StandbyNavigationState(StandbyDestination.STANDBY, StandbyDestination.STANDBY)) {
                "MAIN_FOCUS fixture=INCOMPLETE keyboard Menu requires Standby origin"
            }
            val initial = state(acquisitionStart)
            check(initial.ime == NativeBackImeVisibility.HIDDEN && initial.focus !is EditText &&
                !initial.drawerOpen && initial.drawerGone && initial.scrimGone
            ) { "MAIN_FOCUS fixture=INCOMPLETE keyboard acquisition prerequisite missing" }
            val menu = readNativeBackOnMain(acquisitionStart) {
                check(resumedActivity() === main && main.hasWindowFocus() && main.currentFocus !is EditText &&
                    main.findViewById<View>(R.id.drawer).visibility == View.GONE &&
                    main.findViewById<View>(R.id.drawerScrim).visibility == View.GONE
                ) { "MAIN_FOCUS fixture=INCOMPLETE ordinary Main ownership changed before TAB" }
                val page = checkNotNull(nativeFocusPage(main, StandbyDestination.STANDBY)) {
                    "MAIN_FOCUS fixture=INCOMPLETE actual Standby page unavailable"
                }
                val foreground = nativeFocusDescendants(page).filterIsInstance<StandbyForegroundView>()
                    .singleOrNull { nativeFocusVisible(it) }
                    ?: throw AssertionError("MAIN_FOCUS fixture=INCOMPLETE unique visible foreground unavailable")
                val actual = nativeFocusDescendants(foreground).singleOrNull {
                    it.tag == StandbyForegroundTags.MENU && nativeFocusVisible(it)
                } ?: throw AssertionError("MAIN_FOCUS fixture=INCOMPLETE unique visible foreground Menu unavailable")
                check(actual !is EditText && nativeFocusWithin(actual, main.findViewById<View>(R.id.split)) &&
                    nativeFocusMeaningful(main, actual) && !nativeFocusBounds(actual).isEmpty
                ) { "MAIN_FOCUS fixture=INCOMPLETE foreground Menu is not a safe split control" }
                actual
            }
            key(acquisitionStart, KeyEvent.KEYCODE_TAB)
            val acquired = awaitState(acquisitionStart) {
                check(it.ime == NativeBackImeVisibility.HIDDEN && !it.drawerOpen && it.drawerGone && it.scrimGone) {
                    "MAIN_FOCUS fixture=INCOMPLETE keyboard acquisition lost ordinary Main"
                }
                it.focus != null && it.focus !is EditText && !it.rootParked && it.meaningful && it.focusInSplit
            }
            if (acquired.focus !== menu) {
                val code = readNativeBackOnMain(acquisitionStart) {
                    val focus = checkNotNull(main.currentFocus) { "MAIN_FOCUS fixture=INCOMPLETE native child focus missing" }
                    val split = main.findViewById<View>(R.id.split)
                    check(focus === acquired.focus && focus !is EditText && focus.isFocused && focus.hasWindowFocus() &&
                        nativeFocusMeaningful(main, focus) && nativeFocusWithin(focus, split) &&
                        nativeFocusMeaningful(main, menu) && nativeFocusWithin(menu, split)
                    ) { "MAIN_FOCUS fixture=INCOMPLETE direct Menu edge ownership changed" }
                    val edge = listOf(
                        View.FOCUS_LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
                        View.FOCUS_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
                        View.FOCUS_UP to KeyEvent.KEYCODE_DPAD_UP,
                        View.FOCUS_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
                    ).firstOrNull { (direction, _) -> focus.focusSearch(direction) === menu }
                        ?: throw AssertionError("MAIN_FOCUS fixture=INCOMPLETE no direct native Menu edge")
                    check(main.currentFocus === focus && focus.isFocused && focus.hasWindowFocus()) {
                        "MAIN_FOCUS fixture=INCOMPLETE direct Menu edge changed current focus"
                    }
                    edge.second
                }
                key(acquisitionStart, code)
                awaitState(acquisitionStart) {
                    check(it.ime == NativeBackImeVisibility.HIDDEN && !it.drawerOpen && it.drawerGone && it.scrimGone) {
                        "MAIN_FOCUS fixture=INCOMPLETE Menu traversal lost ordinary Main"
                    }
                    it.focus === menu && !it.rootParked && it.meaningful && it.focusInSplit
                }
            }
            within(acquisitionStart)
            val openStart = SystemClock.elapsedRealtime()
            val baseline = state(openStart)
            check(baseline.focus === menu && !baseline.rootParked && baseline.meaningful && baseline.focusInSplit &&
                baseline.ime == NativeBackImeVisibility.HIDDEN && !baseline.drawerOpen && baseline.drawerGone && baseline.scrimGone
            ) { "MAIN_FOCUS fixture=INCOMPLETE actual focused Menu unavailable before CENTER" }
            val prior = readNativeBackOnMain(openStart) {
                val focused = checkNotNull(main.currentFocus) { "MAIN_FOCUS fixture=INCOMPLETE Menu focus missing before CENTER" }
                val token = checkNotNull(main.window.decorView.windowToken) { "MAIN_FOCUS fixture=INCOMPLETE Main window detached" }
                check(focused === menu && focused !is EditText && focused.isFocused && focused.hasWindowFocus() &&
                    focused.windowToken === token && nativeFocusWithin(focused, main.findViewById<View>(R.id.split)) &&
                    nativeFocusMeaningful(main, focused) && !nativeFocusBounds(focused).isEmpty
                ) { "MAIN_FOCUS fixture=INCOMPLETE Menu prior identity is not positively eligible" }
                focused to token
            }
            key(openStart, KeyEvent.KEYCODE_DPAD_CENTER)
            awaitState(openStart) {
                it.drawerOpen && !it.drawerGone && !it.scrimGone && it.drawerOwnsFocus && it.meaningful &&
                    it.ime == NativeBackImeVisibility.HIDDEN
            }
            readNativeBackOnMain(openStart) {
                val drawer = main.findViewById<View>(R.id.drawer)
                val focus = main.currentFocus
                check(main.window.decorView.windowToken === prior.second && focus != null && focus !== drawer &&
                    nativeFocusWithin(focus, drawer) && nativeFocusMeaningful(main, focus) &&
                    focus.isFocused && focus.hasWindowFocus()
                ) { "MAIN_FOCUS fixture=INCOMPLETE native Menu did not yield an owned meaningful drawer child" }
                check(nativeExitField(MainActivity::class.java, "splitPreviousFocus").get(main) === prior.first) {
                    "MAIN_FOCUS drawer did not save the positively captured eligible Menu identity"
                }
            }
            within(openStart)
            val closeStart = SystemClock.elapsedRealtime()
            val beforeBack = state(closeStart)
            check(beforeBack.drawerOpen && !beforeBack.drawerGone && !beforeBack.scrimGone &&
                beforeBack.drawerOwnsFocus && beforeBack.meaningful && beforeBack.ime == NativeBackImeVisibility.HIDDEN
            ) { "MAIN_FOCUS keyboard Menu Back requires actual drawer focus" }
            key(closeStart, KeyEvent.KEYCODE_BACK)
            val closed = awaitState(closeStart) {
                !it.drawerOpen && it.drawerGone && it.scrimGone && it.focus === prior.first && !it.rootParked &&
                    it.meaningful && it.focusInSplit && it.ime == NativeBackImeVisibility.HIDDEN
            }
            check(closed.splitAccessibility == baseline.splitAccessibility && closed.splitDescendants == baseline.splitDescendants &&
                closed.backgroundKeyboardHidden == baseline.backgroundKeyboardHidden &&
                closed.backgroundAccessibilityHidden == baseline.backgroundAccessibilityHidden
            ) { "MAIN_FOCUS keyboard Menu split boundary was not restored" }
            readNativeBackOnMain(closeStart) {
                val restored = prior.first
                check(main.currentFocus === restored && restored.isFocused && restored.hasWindowFocus() &&
                    main.window.decorView.windowToken === prior.second && restored.windowToken === prior.second &&
                    nativeFocusWithin(restored, main.findViewById<View>(R.id.split)) &&
                    nativeFocusMeaningful(main, restored) && !nativeFocusBounds(restored).isEmpty
                ) { "MAIN_FOCUS positively captured eligible Menu focus was not restored" }
            }
            within(closeStart)
            Log.i("MiraxCheck4Focus", "keyboardMenuCenter=OBSERVED previousEligible=true previousIdentity=RESTORED")
            within(closeStart)
        }

        /** Enters Picture through the real sidebar, then its actual Custom jump. */
        fun enterCustom() {
            routes.drawerRoute(StandbyDestination.PICTURE, R.string.nav_picture, StandbyDestination.STANDBY)
            routes.step(StandbyDestination.CUSTOM, StandbyDestination.STANDBY) { start ->
                val label = readNativeBackOnMain(start) { main.getString(R.string.custom_resolution) }
                val control = waitForPageControl(By.pkg(packageName).descContains(label))
                within(start)
                val bounds = readNativeBackOnMain(start) {
                    val page = checkNotNull(nativeFocusPage(main, StandbyDestination.PICTURE))
                    val jump = nativeFocusDescendants(page).filterIsInstance<ViewGroup>().singleOrNull {
                        it.isClickable && (it.getChildAt(0) as? TextView)?.text?.toString() == label && nativeFocusVisible(it)
                    } ?: throw AssertionError("MAIN_FOCUS fixture=INCOMPLETE actual Custom jump missing")
                    nativeFocusBounds(jump)
                }
                within(start)
                val accessible = control.visibleBounds
                within(start)
                check(Rect.intersects(bounds, accessible)) { "MAIN_FOCUS actual Custom jump overlap missing" }
                control.click()
                within(start)
            }
            route = StandbyNavigationState(StandbyDestination.CUSTOM, StandbyDestination.STANDBY)
        }

        /** Clicks the exact real width and waits for its native IME, never setting value or selection. @return Naturally focused private draft with observed visible IME. */
        fun tapWidth(): NativeFocusDraft {
            val start = SystemClock.elapsedRealtime()
            state(start)
            val label = readNativeBackOnMain(start) { main.getString(R.string.custom_width) }
            val control = waitForPageControl(By.pkg(packageName).desc(label).clazz(EditText::class.java.name))
            within(start)
            val before = readNativeBackOnMain(start) {
                val field = checkNotNull(nativeFocusPage(main, StandbyDestination.CUSTOM)?.findViewWithTag<View>("w") as? EditText)
                NativeBackWidthReading(field, checkNotNull(field.text).toString(), nativeFocusBounds(field), main.currentFocus === field)
            }
            within(start)
            val accessible = control.visibleBounds
            within(start)
            check(Rect.intersects(accessible, before.bounds)) { "MAIN_FOCUS actual width target overlap missing" }
            control.click()
            within(start)
            // A touch-triggered IME show can still be pending when an early inset sample is HIDDEN.
            val after = awaitState(start) {
                it.ime == NativeBackImeVisibility.VISIBLE && it.focus === before.field && it.draft?.let { field ->
                    field.field === before.field && field.text == before.text && field.selectionStart >= 0 &&
                        field.selectionStart == field.selectionEnd && field.selectionEnd <= field.text.length
                } == true
            }
            return checkNotNull(after.draft)
        }

        /** Uses at most one qualified IME Back, requiring the same actual editable child afterwards. @param expected Private width identity/value. @return Actual hidden-IME child/caret facts. */
        fun hideWidthIme(expected: NativeFocusDraft): NativeFocusDraft {
            val start = SystemClock.elapsedRealtime()
            val before = state(start)
            check(before.focus === expected.field && before.draft?.field === expected.field && before.draft.text == expected.text &&
                !before.drawerOpen && before.drawerGone && before.scrimGone
            ) { "MAIN_FOCUS fixture=INCOMPLETE own editable width prerequisite" }
            if (before.ime == NativeBackImeVisibility.VISIBLE) {
                within(start)
                Log.i("MiraxCheck4Focus", "phase=ime ime=${before.ime} widthFocused=${before.focus === expected.field} rootParked=${before.rootParked}")
                key(start, KeyEvent.KEYCODE_BACK)
            }
            val after = awaitState(start) {
                it.ime == NativeBackImeVisibility.HIDDEN && it.focus === expected.field &&
                    it.draft?.field === expected.field && it.draft.text == expected.text
            }
            return checkNotNull(after.draft)
        }

        /** Establishes true root ownership through one separately qualified editable Back. @param expected Private uncommitted draft. */
        fun parkWidth(expected: NativeFocusDraft) {
            val hidden = hideWidthIme(expected)
            val start = SystemClock.elapsedRealtime()
            val before = state(start)
            check(before.ime == NativeBackImeVisibility.HIDDEN && before.focus === hidden.field &&
                before.draft?.field === hidden.field && before.draft.text == hidden.text && !before.rootParked
            ) { "MAIN_FOCUS fixture=INCOMPLETE hidden editable Back prerequisite ime=${before.ime} sameFocus=${before.focus === hidden.field} sameDraftField=${before.draft?.field === hidden.field} rootParked=${before.rootParked}" }
            within(start)
            Log.i("MiraxCheck4Focus", "phase=focus ime=${before.ime} widthFocused=${before.focus === hidden.field} rootParked=${before.rootParked}")
            key(start, KeyEvent.KEYCODE_BACK)
            awaitState(start) {
                it.rootParked && it.ime == NativeBackImeVisibility.HIDDEN && it.draft?.field === hidden.field && it.draft.text == hidden.text
            }
        }

        /** Prediction is a safety prerequisite and diagnostic comparison, not post-key identity. @param direction Required traversal family. @return Actual qualified post-key child state. */
        fun escapeRoot(direction: Int): NativeFocusState {
            val start = SystemClock.elapsedRealtime()
            val phase = when (direction) {
                View.FOCUS_FORWARD -> "forward"
                View.FOCUS_BACKWARD -> "backward"
                else -> "unsupported"
            }
            var input: NativeFocusKeyInput? = null
            var lastCompleted: NativeFocusEscapeSample? = null
            var pollReadAttemptMs: Long? = null
            var pollReadIncomplete = false

            /** Formats retained scalar evidence only, including when the current queued read never returns. @param sealed Whether the actual outcome qualified in time. */
            fun logOutcome(sealed: Boolean) {
                val last = lastCompleted
                val retained = if (last == null) "UNOBSERVED" else
                    "sampleMs=${last.observedMs} rootParked=${last.rootParked} ime=${last.ime} " +
                        "focusPresent=${last.focusPresent} meaningful=${last.meaningful} inSplit=${last.inSplit} samePredicted=${last.samePredicted}"
                Log.i("MiraxCheck4Focus", "escape phase=$phase sealed=$sealed phaseStartMs=$start " +
                    "inputBeforeMs=${input?.beforeMs ?: "UNOBSERVED"} inputAfterMs=${input?.afterMs ?: "UNOBSERVED"} " +
                    "pollReadAttempted=${pollReadAttemptMs != null} pollReadAttemptMs=${pollReadAttemptMs ?: "UNOBSERVED"} " +
                    "pollReadIncomplete=$pollReadIncomplete last={$retained}")
            }

            try {
                val before = state(start)
                check(before.rootParked && before.ime == NativeBackImeVisibility.HIDDEN &&
                    !before.drawerOpen && before.drawerGone && before.scrimGone
                ) { "MAIN_FOCUS fixture=INCOMPLETE genuine root ownership missing" }
                val predicted = readNativeBackOnMain(start) {
                    val root = main.findViewById<ViewGroup>(R.id.root)
                    check(root.isFocused && main.currentFocus === root && root.hasWindowFocus()) { "MAIN_FOCUS root ownership changed before DPAD" }
                    val next = checkNotNull(root.focusSearch(direction)) { "MAIN_FOCUS fixture=INCOMPLETE root has no native child traversal" }
                    check(nativeFocusTarget(main, next) && nativeFocusWithin(next, main.findViewById<View>(R.id.split))) {
                        "MAIN_FOCUS fixture=INCOMPLETE root destination is not a safe meaningful Main child " +
                            "nextClass=${next.javaClass.simpleName} nextId=${next.id} nextTag=${next.tag} " +
                            "touchMode=${root.isInTouchMode} clickable=${next.isClickable} focusable=${next.isFocusable} " +
                            "inTouch=${next.isFocusableInTouchMode} shown=${next.isShown} sameRoot=${next === root} " +
                            "target=${nativeFocusTarget(main, next)} inSplit=${nativeFocusWithin(next, main.findViewById<View>(R.id.split))}"
                    }
                    next
                }
                val code = when (direction) {
                    View.FOCUS_FORWARD -> KeyEvent.KEYCODE_DPAD_DOWN
                    View.FOCUS_BACKWARD -> KeyEvent.KEYCODE_DPAD_UP
                    else -> throw AssertionError("MAIN_FOCUS unsupported root direction")
                }
                input = key(start, code)
                while (true) {
                    pollReadAttemptMs = SystemClock.elapsedRealtime()
                    pollReadIncomplete = true
                    val value = state(start)
                    lastCompleted = NativeFocusEscapeSample(value.observedMs, value.rootParked, value.ime,
                        value.focus != null, value.meaningful, value.focusInSplit, value.focus === predicted)
                    pollReadIncomplete = false
                    if (!value.rootParked && value.focus != null && value.meaningful && value.focusInSplit &&
                        !value.drawerOpen && value.drawerGone && value.scrimGone
                    ) {
                        within(start)
                        logOutcome(true)
                        within(start)
                        return value
                    }
                    within(start)
                    SystemClock.sleep(25L)
                    within(start)
                }
            } catch (error: Throwable) {
                if (error is InterruptedException) Thread.currentThread().interrupt()
                try {
                    logOutcome(false)
                } catch (diagnosticFailure: Throwable) {
                    val marker = logNativeBackDiagnosticFailure("escapeRoot", diagnosticFailure)
                    if (error !== marker && error.suppressed.none { it === marker }) error.addSuppressed(marker)
                }
                throw error
            }
        }

        /** Opens the Standby foreground Menu after two public parent Backs; observes native drawer ownership and natural split restoration. */
        fun drawerCycle() {
            check(route == StandbyNavigationState(StandbyDestination.CUSTOM, StandbyDestination.STANDBY)) {
                "MAIN_FOCUS fixture=INCOMPLETE declared Custom/Standby route missing"
            }
            for (target in listOf(StandbyDestination.PICTURE, StandbyDestination.STANDBY)) {
                routes.step(target, StandbyDestination.STANDBY) { start ->
                    val before = state(start)
                    check(before.rootParked && before.focus !is EditText && before.ime == NativeBackImeVisibility.HIDDEN &&
                        !before.drawerOpen && before.drawerGone && before.scrimGone
                    ) { "MAIN_FOCUS fixture=INCOMPLETE parked noneditable parent Back prerequisite missing" }
                    readNativeBackOnMain(start) {
                        val root = main.findViewById<ViewGroup>(R.id.root)
                        check(resumedActivity() === main && root.isFocused && main.currentFocus === root && root.hasWindowFocus()) {
                            "MAIN_FOCUS fixture=INCOMPLETE parent Back root ownership changed"
                        }
                    }
                    key(start, KeyEvent.KEYCODE_BACK)
                }
                route = StandbyNavigationState(target, StandbyDestination.STANDBY)
            }
            val openStart = SystemClock.elapsedRealtime()
            val baseline = state(openStart)
            check(baseline.rootParked && baseline.ime == NativeBackImeVisibility.HIDDEN &&
                !baseline.drawerOpen && baseline.drawerGone && baseline.scrimGone
            ) { "MAIN_FOCUS fixture=INCOMPLETE genuine parked root missing before Menu click" }
            readNativeBackOnMain(openStart) {
                val root = main.findViewById<ViewGroup>(R.id.root)
                check(resumedActivity() === main && root.isFocused && main.currentFocus === root && root.hasWindowFocus()) {
                    "MAIN_FOCUS fixture=INCOMPLETE parked root unavailable before Standby Menu"
                }
                val standby = checkNotNull(inspectStandby()) { "MAIN_FOCUS fixture=INCOMPLETE owned Standby foreground Menu unavailable" }
                check(!standby.menuBounds.isEmpty) { "MAIN_FOCUS fixture=INCOMPLETE Standby Menu bounds unavailable" }
            }
            // Touch setup measures modal isolation, not keyboard Menu/CENTER reachability.
            within(openStart)
            openDrawer()
            within(openStart)
            awaitState(openStart) { it.drawerOpen && !it.drawerGone && !it.scrimGone && it.drawerOwnsFocus && it.meaningful }
            val previous = readNativeBackOnMain(openStart) {
                val captured = nativeExitField(MainActivity::class.java, "splitPreviousFocus").get(main) as View?
                val eligible = captured != null && captured !== main.findViewById<View>(R.id.root) &&
                    nativeFocusWithin(captured, main.findViewById<View>(R.id.split)) && nativeFocusMeaningful(main, captured)
                captured to eligible
            }
            within(openStart)
            val directionStart = SystemClock.elapsedRealtime()
            val before = state(directionStart)
            check(before.drawerOpen && !before.drawerGone && !before.scrimGone && before.drawerOwnsFocus && before.meaningful) {
                "MAIN_FOCUS drawer key requires actual drawer focus"
            }
            val code = readNativeBackOnMain(directionStart) {
                val drawer = main.findViewById<ViewGroup>(R.id.drawer)
                check(main.currentFocus === before.focus && before.focus !== drawer) { "MAIN_FOCUS drawer focus changed before direction" }
                val targets = arrayListOf<View>()
                drawer.addFocusables(targets, View.FOCUS_FORWARD, View.FOCUSABLES_ALL)
                nativeFocusGeometryKey(drawer, before.focus, targets.filter { nativeFocusMeaningful(main, it) })
            }
            key(directionStart, code)
            val moved = awaitState(directionStart) {
                check(it.drawerOpen && !it.drawerGone && !it.scrimGone && it.drawerOwnsFocus) {
                    "MAIN_FOCUS directional key escaped the owned drawer"
                }
                it.meaningful && it.focus !== before.focus
            }
            val closeStart = SystemClock.elapsedRealtime()
            val beforeBack = state(closeStart)
            check(beforeBack.drawerOpen && !beforeBack.drawerGone && !beforeBack.scrimGone &&
                beforeBack.drawerOwnsFocus && beforeBack.meaningful
            ) { "MAIN_FOCUS drawer Back requires actual drawer focus" }
            key(closeStart, KeyEvent.KEYCODE_BACK)
            val closed = awaitState(closeStart) { !it.drawerOpen && it.drawerGone && it.scrimGone }
            check(closed.splitAccessibility == baseline.splitAccessibility && closed.splitDescendants == baseline.splitDescendants &&
                closed.backgroundKeyboardHidden == baseline.backgroundKeyboardHidden &&
                closed.backgroundAccessibilityHidden == baseline.backgroundAccessibilityHidden
            ) { "MAIN_FOCUS natural split boundary was not restored" }
            val previousIdentity = readNativeBackOnMain(closeStart) {
                val captured = previous.first
                if (previous.second) {
                    check(captured != null && nativeFocusWithin(captured, main.findViewById<View>(R.id.split)) &&
                        nativeFocusMeaningful(main, captured) && main.currentFocus === captured &&
                        captured.isFocused && captured.hasWindowFocus()
                    ) { "MAIN_FOCUS valid captured split focus not naturally restored" }
                    "RESTORED"
                } else if (captured == null) "NOTOBSERVED" else "INAPPLICABLE"
            }
            within(closeStart)
            Log.i("MiraxCheck4Focus", "drawer sealed=true setup=STANDBY_FOREGROUND_MENU_CLICK parentEdges=2 keyboardMenuCenter=NOTOBSERVED channels=CONSERVATION " +
                "keyboardHiddenBaseline=${baseline.backgroundKeyboardHidden} keyboardHiddenRestored=${closed.backgroundKeyboardHidden} " +
                "accessibilityHiddenBaseline=${baseline.backgroundAccessibilityHidden} accessibilityHiddenRestored=${closed.backgroundAccessibilityHidden} " +
                "ownDrawerFocus=${moved.drawerOwnsFocus} previousEligible=${previous.second} previousIdentity=$previousIdentity")
            within(closeStart)
        }

        /** Reads only an actual owned Dialog window; this distinct reader never relaxes the original Main readers. @param start Phase origin. @param expected Held dialog after discovery. @return Qualified native window facts, or null while opening. */
        private fun dialogState(start: Long, expected: Dialog? = null): NativeFocusDialog? = readNativeBackOnMain(start) {
            val base = readNativeBackFacts(main)
            common(base)
            val dialog = nativeExitField(MainActivity::class.java, "frozenDialog").get(main) as Dialog?
            if (expected != null) check(dialog === expected && dialog.isShowing) { "MAIN_FOCUS own Dialog replaced or dismissed" }
            if (dialog?.isShowing != true) return@readNativeBackOnMain null
            val decor = dialog.window?.decorView
            val token = decor?.windowToken
            if (expected != null) check(decor != null && token != null) { "MAIN_FOCUS held Dialog window detached" }
            if (decor == null || token == null) return@readNativeBackOnMain null
            if (!nativeFocusVisible(decor) || !decor.hasWindowFocus() || main.hasWindowFocus()) {
                check(expected == null) { "MAIN_FOCUS held Dialog lost focused-window ownership" }
                return@readNativeBackOnMain null
            }
            check(token !== main.window.decorView.windowToken) { "MAIN_FOCUS Dialog does not own a distinct native window" }
            val title = main.getString(R.string.frozen_dialog_title)
            val matches = arrayListOf<View>()
            decor.findViewsWithText(matches, title, View.FIND_VIEWS_WITH_TEXT)
            val nativeTitle = matches.filterIsInstance<TextView>().singleOrNull { it.text.toString() == title && nativeFocusVisible(it) }
                ?: throw AssertionError("MAIN_FOCUS fixture=INCOMPLETE own localized Dialog title missing")
            val focus = decor.findFocus()
            val focusedChild = focus != null && focus !== decor && nativeFocusWithin(focus, decor) &&
                focus.isFocused && focus.isEnabled && focus.isFocusable && nativeFocusVisible(focus) &&
                focus.windowToken === token && (focus is TextView || focus is ScrollView || focus is androidx.core.widget.NestedScrollView)
            NativeFocusDialog(dialog, decor, token, focus, main.currentFocus, nativeFocusBounds(nativeTitle), focusedChild)
        }

        /** Uses only the real frozenInfo control, one geometric DPAD direction and one native Back. */
        fun dialogCycle() {
            val openStart = SystemClock.elapsedRealtime()
            state(openStart)
            nativeExitClickControl(openStart, main, "frozenInfo", R.string.frozen_dialog_title)
            val title = readNativeBackOnMain(openStart) { main.getString(R.string.frozen_dialog_title) }
            var opened: NativeFocusDialog? = null
            while (opened == null) {
                val value = dialogState(openStart)
                if (value != null) {
                    within(openStart)
                    val nodes = device.findObjects(By.pkg(packageName).text(title))
                    within(openStart)
                    val matched = nodes.any { node ->
                        within(openStart)
                        val bounds = node.visibleBounds
                        within(openStart)
                        Rect.intersects(bounds, value.titleBounds)
                    }
                    within(openStart)
                    if (matched) opened = value
                }
                if (opened == null) {
                    SystemClock.sleep(25L)
                    within(openStart)
                }
            }
            val held = checkNotNull(opened)
            val directionStart = SystemClock.elapsedRealtime()
            val before = checkNotNull(dialogState(directionStart, held.dialog)) { "MAIN_FOCUS fixture=INCOMPLETE focused own Dialog unavailable" }
            val code = readNativeBackOnMain(directionStart) {
                check(before.decor === held.decor && before.token === held.token &&
                    before.decor.hasWindowFocus() && !main.hasWindowFocus() && before.decor.findFocus() === before.focus) {
                    "MAIN_FOCUS Dialog input owner changed"
                }
                val buttons = listOf(android.R.id.button1, android.R.id.button2, android.R.id.button3)
                    .mapNotNull { held.dialog.findViewById<View>(it) }.filter { nativeFocusVisible(it) && it.isEnabled && it.isFocusable }
                nativeFocusGeometryKey(before.decor, before.focus, buttons)
            }
            key(directionStart, code)
            while (true) {
                val after = dialogState(directionStart, held.dialog)
                if (after != null) {
                    check(after.decor === held.decor && after.token === held.token) {
                        "MAIN_FOCUS held Dialog window identity changed"
                    }
                    if (after.focusedChild && after.focus !== before.focus) break
                }
                SystemClock.sleep(25L)
                within(directionStart)
            }
            within(directionStart)
            val closeStart = SystemClock.elapsedRealtime()
            checkNotNull(dialogState(closeStart, held.dialog)) { "MAIN_FOCUS Dialog Back lost its actual window" }
            key(closeStart, KeyEvent.KEYCODE_BACK)
            while (true) {
                val gone = readNativeBackOnMain(closeStart) {
                    common(readNativeBackFacts(main))
                    val current = nativeExitField(MainActivity::class.java, "frozenDialog").get(main) as Dialog?
                    check(current == null || current === held.dialog) { "MAIN_FOCUS another Dialog replaced the dismissed one" }
                    !held.dialog.isShowing && current == null && main.hasWindowFocus()
                }
                if (gone) {
                    state(closeStart)
                    within(closeStart)
                    return
                }
                SystemClock.sleep(25L)
                within(closeStart)
            }
        }
    }

    /** @param main Locked Main. @param destination Exact actual page tag. @return Its one visible native root, if present. */
    private fun nativeFocusPage(main: MainActivity, destination: StandbyDestination): View? =
        listOf(R.id.pageStart, R.id.pageEnd).mapNotNull { main.findViewById<ViewGroup>(it)?.getChildAt(0) }
            .singleOrNull { it.tag == destination && nativeFocusVisible(it) }

    /** @param child Actual view identity. @param ancestor Actual container identity. @return Whether the native parent chain contains the container. */
    private fun nativeFocusWithin(child: View?, ancestor: View): Boolean {
        var cursor = child
        while (cursor != null) {
            if (cursor === ancestor) return true
            cursor = cursor.parent as? View
        }
        return false
    }

    /** @param view Actual native view. @return Attachment, clipping and positive ancestor visibility. */
    private fun nativeFocusVisible(view: View): Boolean {
        if (!isVisible(view) || !view.isLaidOut || view.width <= 0 || view.height <= 0) return false
        var cursor: View? = view
        while (cursor != null) {
            if (cursor.visibility != View.VISIBLE || cursor.alpha <= 0f) return false
            cursor = cursor.parent as? View
        }
        return true
    }

    /** Safety only, not proof of post-key visibility or focus. @param main Locked Main. @param view Predicted or actual child. @return A real semantic Main target (Custom fields or Picture bitrate field, excluding General broadcast name). */
    private fun nativeFocusTarget(main: MainActivity, view: View?): Boolean {
        val root = main.findViewById<View>(R.id.root)
        if (view == null || !view.isAttachedToWindow || !view.isShown || !view.isEnabled || !view.isFocusable ||
            root.windowToken == null || view.windowToken !== root.windowToken || !nativeFocusWithin(view, root) ||
            view === root || view === main.findViewById<View>(R.id.drawerScrim)
        ) return false
        if (view is EditText) return (view.tag in setOf("w", "h", "hz") &&
            nativeFocusPage(main, StandbyDestination.CUSTOM)?.let { nativeFocusWithin(view, it) } == true) ||
            (view.tag == "max-bitrate" &&
                nativeFocusPage(main, StandbyDestination.PICTURE)?.let { nativeFocusWithin(view, it) } == true)
        return view.isClickable && (!view.contentDescription.isNullOrBlank() || (view is TextView && view.text.isNotBlank()))
    }

    /** @param main Locked Main. @param view Actual child. @return A meaningful target with positive current clipped geometry. */
    private fun nativeFocusMeaningful(main: MainActivity, view: View?): Boolean =
        nativeFocusTarget(main, view) && view != null && nativeFocusVisible(view)

    /** @param view Actual view read only on main. @return Positive screen coordinates for native/SDK overlap and geometry. */
    private fun nativeFocusBounds(view: View): Rect {
        check(nativeFocusVisible(view)) { "MAIN_FOCUS fixture=INCOMPLETE native bounds unavailable" }
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    /** @param view Actual native root. @return Its actual child tree, never synthetic controls. */
    private fun nativeFocusDescendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { nativeFocusDescendants(view.getChildAt(it)) } else emptyList()

    /** @param dx Actual horizontal center delta. @param dy Actual vertical center delta. @return One directional key, or null for coincident centers. */
    private fun nativeFocusDirection(dx: Int, dy: Int): Int? = when {
        dx == 0 && dy == 0 -> null
        kotlin.math.abs(dy.toLong()) >= kotlin.math.abs(dx.toLong()) -> if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
        dx > 0 -> KeyEvent.KEYCODE_DPAD_RIGHT
        else -> KeyEvent.KEYCODE_DPAD_LEFT
    }

    /** Geometry chooses one candidate direction, not the success result. @param container Actual active modal. @param focus Actual current focus, if any. @param targets Actual native semantic controls. @return One key with a real geometric destination. */
    private fun nativeFocusGeometryKey(container: View, focus: View?, targets: List<View>): Int {
        val origin = nativeFocusBounds(focus ?: container)
        val originX = origin.centerX()
        val originY = if (focus == null) origin.top else origin.centerY()
        val candidates = targets.filter { it !== focus && it.isEnabled && it.isFocusable && nativeFocusVisible(it) && nativeFocusWithin(it, container) }
            .mapNotNull { target ->
                val bounds = nativeFocusBounds(target)
                val dx = bounds.centerX() - originX
                val dy = bounds.centerY() - originY
                nativeFocusDirection(dx, dy)?.let { key -> key to (kotlin.math.abs(dx.toLong()) + kotlin.math.abs(dy.toLong())) }
            }
        return candidates.minByOrNull { it.second }?.first
            ?: throw AssertionError("MAIN_FOCUS fixture=INCOMPLETE no natural directional focus destination")
    }

    /**
     * Shares route/session witnesses and observer lifetime, not reader policy.
     * @param initial Post-readiness Standby baseline.
     * @param readFacts Exact family reader, invoked only inside the existing queued main task.
     */
    private inner class NativeBackFixture(
        initial: NativeBackReading,
        private val readFacts: (MainActivity) -> NativeBackReading,
    ) {
        private val main = initial.main
        private val baseline = initial.session

        init {
            assertThat("CHECK4_BACK fixture=INCOMPLETE requires disconnected FROZEN",
                baseline.phase == ScreenPhase.FROZEN && baseline.wfdOwner == WfdOwner.NONE &&
                    !baseline.advertisingEnabled && baseline.wfdAdvertise == null && !baseline.showPicture,
                Matchers.equalTo(true))
        }

        private var expected = StandbyNavigationState(StandbyDestination.STANDBY, StandbyDestination.STANDBY)
        var allowed = setOf(expected)
        val unexpectedRoute = AtomicBoolean(false)
        val observerFailure = AtomicReference<Throwable?>(null)
        val navigationField = MainActivity::class.java.getDeclaredField("navState").apply { isAccessible = true }
        val observer = ViewTreeObserver.OnPreDrawListener {
            try {
                val navigation = navigationField.get(main) as StandbyNavigationState
                val visibleHomes = listOf(R.id.pageStart, R.id.pageEnd).mapNotNull { id ->
                    main.findViewById<ViewGroup>(id).getChildAt(0)?.takeIf(::isVisible)
                        ?.tag as? StandbyDestination
                }.filter { it == StandbyDestination.STANDBY || it == StandbyDestination.DASHBOARD }
                if (navigation !in allowed || visibleHomes.any { home -> allowed.none { it.current == home } }) {
                    unexpectedRoute.set(true)
                }
            } catch (error: Throwable) {
                if (observerFailure.compareAndSet(null, error)) {
                    logMain03("CHECK4_BACK routeObservationFailure=${error.javaClass.simpleName}")
                }
            }
            true
        }

        /** Rejects a late observation, never extending the original budget. @param start Phase start. @param timeout Original budget. */
        fun within(start: Long, timeout: Long = 5000L) {
            if (!hasWaitBudget(start, timeout)) {
                throw AssertionError("CHECK4_BACK fixture=INCOMPLETE observation deadline budgetMs=$timeout")
            }
        }

        /** Reads queued facts and preserves any observer failure. @param start Phase start. @param timeout Original budget. @return Current facts. */
        fun reading(start: Long, timeout: Long = 5000L): NativeBackReading {
            val result = readNativeBackOnMain(start, timeout) {
                observerFailure.get()?.let { throw it }
                readFacts(main)
            }
            assertThat("CHECK4_BACK unexpected intermediate route/home", unexpectedRoute.get(), Matchers.equalTo(false))
            return result
        }

        /** Compares only readonly facts, never printing session payloads. @param value Observed facts. @param target Expected route. */
        fun verify(value: NativeBackReading, target: StandbyNavigationState) {
            assertThat("CHECK4_BACK same RESUMED Main with window focus",
                value.main === main && value.windowFocused, Matchers.equalTo(true))
            assertThat("CHECK4_BACK route=${target.current} origin=${target.homeOrigin} " +
                "observedNavigation=${value.navigation} visiblePages=${value.visiblePages}",
                value.navigation == target && target.current in value.visiblePages, Matchers.equalTo(true))
            val session = value.session
            assertThat("CHECK4_BACK session/broadcast/settings invariant",
                session.phase == baseline.phase && session.wfdOwner == baseline.wfdOwner &&
                    session.advertisingEnabled == baseline.advertisingEnabled && session.wfdAdvertise == baseline.wfdAdvertise &&
                    session.showPicture == baseline.showPicture && session.connectionRuns == baseline.connectionRuns &&
                    session.connectionOfferModes == baseline.connectionOfferModes &&
                    session.connectionPreferredMode == baseline.connectionPreferredMode && session.selectedMode == baseline.selectedMode &&
                    session.effectiveBroadcastName == baseline.effectiveBroadcastName && session.displayNameOverride == baseline.displayNameOverride &&
                    session.languagePreference == baseline.languagePreference && session.appLanguage == baseline.appLanguage &&
                    session.customModes == baseline.customModes && session.standardModes == baseline.standardModes &&
                    session.pictureScale == baseline.pictureScale && session.maxVideoBitrateBps == baseline.maxVideoBitrateBps &&
                    session.broadcastAutoStopMinutes == baseline.broadcastAutoStopMinutes,
                Matchers.equalTo(true))
            assertThat("CHECK4_BACK FROZEN idle schedule remains unarmed without pending stop",
                !value.idleArmed && !value.idlePending, Matchers.equalTo(true))
        }

        /** Awaits the actual destination root before either family's strict reader. @param start Original phase start. @param destination Required root tag. */
        private fun waitForDestinationRoot(start: Long, destination: StandbyDestination) {
            while (true) {
                val ready = readNativeBackOnMain(start) {
                    observerFailure.get()?.let { throw it }
                    check(resumedActivity() === main && main.hasWindowFocus()) {
                        "CHECK4_BACK fixture=INCOMPLETE destination root requires same RESUMED focused Main"
                    }
                    listOf(R.id.pageStart, R.id.pageEnd).any { id ->
                        main.findViewById<ViewGroup>(id)?.getChildAt(0)?.let { root ->
                            root.tag == destination && isVisible(root)
                        } == true
                    }
                }
                within(start)
                if (ready) return
                SystemClock.sleep(25L)
                within(start)
            }
        }

        /** Seals public content and route within one UI budget. @param start Phase start. @param target Expected route. */
        fun seal(start: Long, target: StandbyNavigationState) {
            when (target.current) {
                StandbyDestination.STANDBY -> waitForStandby()
                StandbyDestination.DASHBOARD -> waitForPageTag("legacyIdentity")
                StandbyDestination.GENERAL -> waitForPageText(R.string.nav_general)
                StandbyDestination.LANGUAGE -> {
                    waitForPageTag("language")
                    within(start)
                    waitForPageText(R.string.settings_language_title)
                }
                StandbyDestination.PICTURE -> waitForPageText(R.string.nav_picture)
                StandbyDestination.CUSTOM -> waitForPageText(R.string.custom_resolution_body)
                StandbyDestination.MODES -> {
                    waitForPageTag("modes")
                    within(start)
                    waitForPageText(R.string.standard_modes_label)
                }
                StandbyDestination.SCALE -> {
                    waitForPageTag("scale")
                    within(start)
                    waitForPageText(R.string.settings_scale_title)
                }
                StandbyDestination.ADVANCED -> waitForPageText(R.string.nav_advanced)
                StandbyDestination.ABOUT -> waitForPageText(R.string.nav_about)
                else -> throw AssertionError("CHECK4_BACK unexpected test destination")
            }
            within(start)
            waitForDestinationRoot(start, target.current)
            readNativeBackOnMain(start) { allowed = setOf(target) }
            val value = reading(start)
            verify(value, target)
            assertThat("CHECK4_BACK sealed page has no drawer or root confirmation",
                !value.drawerVisible && !value.rootPending && value.rootSince == null, Matchers.equalTo(true))
            expected = target
            Log.i("MiraxCheck4Back", "sealed page=${target.current} origin=${target.homeOrigin} main=${System.identityHashCode(main)} sampleMs=${value.observedMs}")
            within(start)
        }

        /** Brackets a native input and seals its destination. @param target Destination. @param origin Home origin. @param input Budget-aware native input. */
        fun step(target: StandbyDestination, origin: StandbyDestination, input: (Long) -> Unit) {
            val start = SystemClock.elapsedRealtime()
            val next = StandbyNavigationState(target, origin)
            verify(reading(start), expected)
            readNativeBackOnMain(start) { allowed = setOf(expected, next) }
            val beforeInput = SystemClock.elapsedRealtime()
            within(start)
            input(start)
            val afterInput = SystemClock.elapsedRealtime()
            within(start)
            Log.i("MiraxCheck4Back", "input from=${expected.current} to=$target beforeMs=$beforeInput afterMs=$afterInput")
            within(start)
            seal(start, next)
        }

        /** Uses the existing drawer controls and proves opening resets confirmation. @param target Destination. @param title Localized card resource. @param origin Home origin. */
        fun drawerRoute(target: StandbyDestination, title: Int, origin: StandbyDestination) {
            step(target, origin) { start ->
                openDrawer()
                within(start)
                val opened = reading(start)
                verify(opened, expected)
                assertThat("CHECK4_BACK drawer opening resets confirmation",
                    opened.drawerVisible && !opened.rootPending && opened.rootSince == null, Matchers.equalTo(true))
                val label = currentText(title)
                within(start)
                val card = waitForDrawerCard(label, 5000)
                within(start)
                card.click()
                within(start)
            }
        }

        /** Enters a fixed nested route without focusing or changing a setting. @param target Destination. @param title Localized jump resource. */
        fun nestedRoute(target: StandbyDestination, title: Int) {
            var diagnosticFailure: Throwable? = null
            try {
                step(target, StandbyDestination.DASHBOARD) { start ->
                    val label = currentText(title)
                    within(start)
                    val control = waitForPageControl(By.pkg(packageName).descContains(label))
                    within(start)
                    if (target == StandbyDestination.LANGUAGE) {
                        try {
                            val targetPackage = control.applicationPackage
                            within(start)
                            val targetClass = control.className
                            within(start)
                            val targetResource = control.resourceName
                            within(start)
                            val targetBounds = control.visibleBounds
                            within(start)
                            Log.i("MiraxCheck4Back", "CHECK4_FOCUS_DIAG languageTarget package=$targetPackage " +
                                "class=$targetClass resource=$targetResource bounds=${targetBounds.toShortString()} " +
                                "sampleMs=${SystemClock.elapsedRealtime()}")
                        } catch (error: Throwable) {
                            diagnosticFailure = logNativeBackDiagnosticFailure("languageTarget", error)
                        }
                        within(start)
                    }
                    control.click()
                    within(start)
                }
            } catch (error: Throwable) {
                diagnosticFailure?.let { if (it !== error) error.addSuppressed(it) }
                throw error
            }
        }

        /** Uses only a real device Back for a parent transition. @param target Expected parent. */
        fun backTo(target: StandbyDestination) {
            step(target, StandbyDestination.DASHBOARD) { start ->
                val accepted = device.pressKeyCode(KeyEvent.KEYCODE_BACK)
                within(start)
                assertThat("CHECK4_BACK native Back injection", accepted, Matchers.equalTo(true))
            }
        }

        /** Observes first-root confirmation inside the existing 2000ms window. */
        fun rootBack() {
            val start = SystemClock.elapsedRealtime()
            val before = reading(start)
            verify(before, expected)
            assertThat("CHECK4_BACK first root Back precondition",
                !before.drawerVisible && !before.rootPending && before.rootSince == null && before.homeVisible,
                Matchers.equalTo(true))
            val inputBefore = SystemClock.elapsedRealtime()
            within(start)
            val accepted = device.pressKeyCode(KeyEvent.KEYCODE_BACK)
            val inputAfter = SystemClock.elapsedRealtime()
            within(inputBefore, 2000L)
            assertThat("CHECK4_BACK root Back injection", accepted, Matchers.equalTo(true))
            var value = reading(inputBefore, 2000L)
            verify(value, expected)
            while (!value.rootPending) {
                within(inputBefore, 2000L)
                SystemClock.sleep(25L)
                within(inputBefore, 2000L)
                value = reading(inputBefore, 2000L)
                verify(value, expected)
            }
            val since = value.rootSince ?: throw AssertionError("CHECK4_BACK confirmation has no arm time")
            assertThat("CHECK4_BACK native first Back retains visible root and arms confirmation",
                value.homeVisible && !value.drawerVisible && since in inputBefore..value.observedMs,
                Matchers.equalTo(true))
            Log.i("MiraxCheck4Back", "rootBack page=${expected.current} origin=${expected.homeOrigin} main=${System.identityHashCode(main)} inputBeforeMs=$inputBefore inputAfterMs=$inputAfter armedMs=$since sampleMs=${value.observedMs}")
            within(inputBefore, 2000L)
        }

        /** Checks B1's same-route overlay prerequisite. @param value Current facts. @return Independent focus/IME facts. */
        private fun verifySettingsState(value: NativeBackReading): NativeBackFocusDiagnostic {
            verify(value, expected)
            check(!value.drawerVisible && !value.rootPending && value.rootSince == null) {
                "CHECK4_BACK fixture=INCOMPLETE settings overlay/root precondition"
            }
            return value.settingsFocus
                ?: throw AssertionError("CHECK4_BACK fixture=INCOMPLETE missing settings focus facts")
        }

        /** Polls facts only, never inputs or a renewed deadline. @param start Original phase start. @param complete Required postcondition. @return Timely facts. */
        private fun awaitSettings(start: Long, complete: (NativeBackFocusDiagnostic) -> Boolean): NativeBackReading {
            var lastFocus: NativeBackFocusDiagnostic? = null
            try {
                while (true) {
                    val value = reading(start)
                    val focus = verifySettingsState(value)
                    lastFocus = focus
                    if (complete(focus)) {
                        within(start)
                        return value
                    }
                    within(start)
                    SystemClock.sleep(25L)
                    within(start)
                }
            } catch (error: AssertionError) {
                if (error.message in setOf(
                    "CHECK4_BACK fixture=INCOMPLETE observation deadline budgetMs=5000",
                    "CHECK4_BACK fixture=INCOMPLETE main observation deadline",
                    "CHECK4_BACK fixture=INCOMPLETE late main result",
                )) {
                    try {
                        Log.w("MiraxCheck4Back", "CHECK4_FOCUS_DIAG settingsDeadline " +
                            "lastIme=${lastFocus?.imeVisibility} lastEditable=${lastFocus?.focusEditable} " +
                            "lastField=${lastFocus?.focusField} lastPage=${lastFocus?.focusPage} " +
                            "observedMs=${lastFocus?.observedMs}")
                    } catch (diagnosticFailure: Throwable) {
                        error.addSuppressed(logNativeBackDiagnosticFailure("settingsDeadline", diagnosticFailure))
                    }
                }
                throw error
            }
        }

        /** Injects exactly one overlay Back before read-only polling. @param start Original phase start. @param kind Fixed phase label. @param complete Required postcondition. @return Sealed same-route facts. */
        private fun settingsOverlayBack(
            start: Long,
            kind: String,
            complete: (NativeBackFocusDiagnostic) -> Boolean,
        ): NativeBackReading {
            val inputBefore = SystemClock.elapsedRealtime()
            within(start)
            val accepted = device.pressKeyCode(KeyEvent.KEYCODE_BACK)
            val inputAfter = SystemClock.elapsedRealtime()
            within(start)
            assertThat("CHECK4_BACK settings overlay Back injection", accepted, Matchers.equalTo(true))
            val value = awaitSettings(start, complete)
            Log.i("MiraxCheck4Back", "priority=$kind page=${expected.current} origin=${expected.homeOrigin} " +
                "main=${System.identityHashCode(main)} inputBeforeMs=$inputBefore inputAfterMs=$inputAfter sampleMs=${value.observedMs}")
            within(start)
            return value
        }

        /** Reads the actual attached Custom width without logging its value. @param start Original phase start. @return Private width witness. */
        private fun readCustomWidth(start: Long): NativeBackWidthReading = readNativeBackOnMain(start) {
            observerFailure.get()?.let { throw it }
            check(resumedActivity() === main && main.hasWindowFocus()) {
                "CHECK4_BACK fixture=INCOMPLETE Custom width Main changed"
            }
            val page = listOf(R.id.pageStart, R.id.pageEnd).mapNotNull { id ->
                main.findViewById<ViewGroup>(id)?.getChildAt(0)
            }.firstOrNull { it.tag == StandbyDestination.CUSTOM && isVisible(it) }
                ?: throw AssertionError("CHECK4_BACK fixture=INCOMPLETE visible Custom page missing")
            val field = page.findViewWithTag<View>("w") as? EditText
                ?: throw AssertionError("CHECK4_BACK fixture=INCOMPLETE Custom width field missing")
            val bounds = Rect()
            check(isVisible(field) && field.getGlobalVisibleRect(bounds) && !bounds.isEmpty) {
                "CHECK4_BACK fixture=INCOMPLETE Custom width field not visible"
            }
            NativeBackWidthReading(field, field.text?.toString().orEmpty(), bounds, main.currentFocus === field)
        }

        /** Clicks only the real Custom width and requires its own IME. @return Unlogged pre-click value/identity for the first Back comparison. */
        fun focusCustomWidth(): NativeBackWidthReading {
            val start = SystemClock.elapsedRealtime()
            check(expected.current == StandbyDestination.CUSTOM) { "CHECK4_BACK unexpected width route" }
            verifySettingsState(reading(start))
            val label = currentText(R.string.custom_width)
            within(start)
            val control = waitForPageControl(By.pkg(packageName).desc(label).clazz(EditText::class.java.name))
            within(start)
            val width = readCustomWidth(start)
            val accessibleBounds = control.visibleBounds
            within(start)
            assertThat("CHECK4_BACK fixture=INCOMPLETE Custom width target overlap",
                Rect.intersects(accessibleBounds, width.bounds), Matchers.equalTo(true))
            control.click()
            within(start)
            awaitSettings(start) { focus ->
                focus.imeVisibility == NativeBackImeVisibility.VISIBLE && focus.focusEditable &&
                    focus.focusField == NativeBackFocusField.W && focus.focusPage == StandbyDestination.CUSTOM
            }
            val after = readCustomWidth(start)
            assertThat("CHECK4_BACK Custom width focus preserves its private value",
                after.field === width.field && after.text == width.text && after.focused, Matchers.equalTo(true))
            within(start)
            return width
        }

        /** Observes IME, focused-field and parent Back as distinct input phases. @param target Required logical parent. @param width Optional original Custom IME value witness. */
        fun settingsBackTo(target: StandbyDestination, width: NativeBackWidthReading? = null) {
            val imeStart = SystemClock.elapsedRealtime()
            val beforeIme = reading(imeStart)
            val ime = verifySettingsState(beforeIme)
            if (width != null) {
                check(ime.imeVisibility == NativeBackImeVisibility.VISIBLE && ime.focusEditable &&
                    ime.focusField == NativeBackFocusField.W && ime.focusPage == StandbyDestination.CUSTOM) {
                    "CHECK4_BACK fixture=INCOMPLETE own Custom width IME prerequisite"
                }
                val current = readCustomWidth(imeStart)
                assertThat("CHECK4_BACK Custom width first Back prerequisite",
                    current.field === width.field && current.text == width.text && current.focused, Matchers.equalTo(true))
            }
            if (ime.imeVisibility == NativeBackImeVisibility.VISIBLE) {
                settingsOverlayBack(imeStart, "IME") { it.imeVisibility == NativeBackImeVisibility.HIDDEN }
                if (width != null) {
                    val after = readCustomWidth(imeStart)
                    assertThat("CHECK4_BACK IME Back preserves the private Custom width value",
                        after.field === width.field && after.text == width.text, Matchers.equalTo(true))
                    within(imeStart)
                }
            }

            val focusStart = SystemClock.elapsedRealtime()
            val beforeFocus = reading(focusStart)
            val focus = verifySettingsState(beforeFocus)
            check(focus.imeVisibility == NativeBackImeVisibility.HIDDEN) {
                "CHECK4_BACK fixture=INCOMPLETE IME is not hidden before focused-field phase"
            }
            if (focus.focusEditable) {
                settingsOverlayBack(focusStart, "FOCUS") {
                    it.imeVisibility == NativeBackImeVisibility.HIDDEN && !it.focusEditable
                }
            }

            step(target, StandbyDestination.DASHBOARD) { start ->
                val parent = verifySettingsState(reading(start))
                check(parent.imeVisibility == NativeBackImeVisibility.HIDDEN && !parent.focusEditable) {
                    "CHECK4_BACK fixture=INCOMPLETE parent Back requires hidden IME and noneditable focus"
                }
                within(start)
                val accepted = device.pressKeyCode(KeyEvent.KEYCODE_BACK)
                within(start)
                assertThat("CHECK4_BACK settings parent Back injection", accepted, Matchers.equalTo(true))
            }
        }

        /** Preserves the original observer/Throwable lifetime for one family. @param action Fixed public-input route sequence. */
        fun observe(action: NativeBackFixture.() -> Unit) {
            var primaryFailure: Throwable? = null
            try {
                readNativeBackOnMain(SystemClock.elapsedRealtime()) {
                    main.window.decorView.viewTreeObserver.addOnPreDrawListener(observer)
                }
                seal(SystemClock.elapsedRealtime(), expected)
                action()
            } catch (error: Throwable) {
                primaryFailure = error
                throw error
            } finally {
                try {
                    readNativeBackOnMain(SystemClock.elapsedRealtime()) {
                        main.window.decorView.viewTreeObserver.removeOnPreDrawListener(observer)
                    }
                    observerFailure.get()?.let { throw it }
                    assertThat("CHECK4_BACK no intermediate route/home before observer removal",
                        unexpectedRoute.get(), Matchers.equalTo(false))
                } catch (error: Throwable) {
                    val primary = primaryFailure
                    if (primary == null) throw error
                    if (primary !== error) primary.addSuppressed(error)
                }
            }
        }
    }

    /** Private field/value witness; never format or log this payload-bearing object. */
    private data class NativeBackWidthReading(
        val field: EditText,
        val text: String,
        val bounds: Rect,
        val focused: Boolean,
    )

    /**
     * Strongly typed readonly facts; payload-bearing fields are never logged.
     * @property main Actual RESUMED Main identity.
     * @property navigation Current production route and home origin.
     * @property visiblePages Attached visible page-root tags.
     * @property homeVisible Actual foreground controls or Dashboard identity content.
     * @property windowFocused Whether Main owns window focus.
     * @property drawerVisible Whether either native drawer layer is shown.
     * @property rootPending Production confirmation predicate at observation.
     * @property rootSince Original monotonic confirmation arm time.
     * @property session Real application session snapshot, retained only for comparisons.
     * @property idleArmed Whether the existing idle timer is armed.
     * @property idlePending Whether the existing pending stop is present.
     * @property observedMs Monotonic end of the queued observation.
     * @property settingsFocus B1-only focus/IME facts; absent for the unchanged strict A reader.
     */
    private data class NativeBackReading(
        val main: MainActivity,
        val navigation: StandbyNavigationState,
        val visiblePages: Set<StandbyDestination>,
        val homeVisible: Boolean,
        val windowFocused: Boolean,
        val drawerVisible: Boolean,
        val rootPending: Boolean,
        val rootSince: Long?,
        val session: SessionSnapshot,
        val idleArmed: Boolean,
        val idlePending: Boolean,
        val observedMs: Long,
        val settingsFocus: NativeBackFocusDiagnostic? = null,
    )

    /** Whitelisted field identities; arbitrary view tags never enter diagnostic output. */
    private enum class NativeBackFocusField { NONE, NAME, MAX_BITRATE, W, H, HZ, OTHER }

    /** Missing window insets are unknown, never evidence of a hidden IME. */
    private enum class NativeBackImeVisibility { UNKNOWN, HIDDEN, VISIBLE }

    /** Page-root facts, including absent roots as null attachment/visibility. */
    private data class NativeBackDiagnosticPage(
        val pane: String,
        val destination: StandbyDestination?,
        val attached: Boolean?,
        val visible: Boolean?,
    )

    /** Sequential readonly scalars from one main task, without View/Main/session payloads. */
    private data class NativeBackFocusDiagnostic(
        val mainIdentity: Int,
        val sameMain: Boolean?,
        val windowFocused: Boolean,
        val navigation: StandbyNavigationState,
        val pages: List<NativeBackDiagnosticPage>,
        val focusClass: String?,
        val focusField: NativeBackFocusField,
        val focusEditable: Boolean,
        val focusAttached: Boolean?,
        val focusShown: Boolean?,
        val focusPage: StandbyDestination?,
        val mediaEditorPresent: Boolean,
        val imeVisibility: NativeBackImeVisibility,
        val observedMs: Long,
    )

    /** Classifies only fixed tags. @param tag Untrusted view tag, never logged. @return Whitelisted field kind. */
    private fun classifyNativeBackFocusField(tag: String?): NativeBackFocusField = when (tag) {
        "name" -> NativeBackFocusField.NAME
        "max-bitrate" -> NativeBackFocusField.MAX_BITRATE
        "w" -> NativeBackFocusField.W
        "h" -> NativeBackFocusField.H
        "hz" -> NativeBackFocusField.HZ
        else -> NativeBackFocusField.OTHER
    }

    /** Logs a failure-only snapshot on the caller's main task. @param main Observed Main. @param expectedMain Locked Main, if available. */
    private fun logNativeBackFocusDiagnostic(main: MainActivity, expectedMain: MainActivity?) {
        val diagnostic = inspectNativeBackFocus(main, expectedMain)
        Log.i("MiraxCheck4Back", "CHECK4_FOCUS_DIAG snapshot=$diagnostic")
    }

    /** Reads sequential focus facts without logging. @param main Observed Main. @param expectedMain Locked Main, if available. @return Payload-free facts. */
    private fun inspectNativeBackFocus(main: MainActivity, expectedMain: MainActivity?): NativeBackFocusDiagnostic {
        check(listOf("name", "max-bitrate", "w", "h", "hz", null, "unlisted").map(::classifyNativeBackFocusField) ==
            listOf(NativeBackFocusField.NAME, NativeBackFocusField.MAX_BITRATE, NativeBackFocusField.W,
                NativeBackFocusField.H, NativeBackFocusField.HZ, NativeBackFocusField.OTHER, NativeBackFocusField.OTHER))
        val focus = main.currentFocus
        val roots = listOf("pageStart" to R.id.pageStart, "pageEnd" to R.id.pageEnd).map { (pane, id) ->
            pane to main.findViewById<ViewGroup>(id)?.getChildAt(0)
        }
        val insets = ViewCompat.getRootWindowInsets(main.window.decorView)
        return NativeBackFocusDiagnostic(
            mainIdentity = System.identityHashCode(main),
            sameMain = expectedMain?.let { main === it },
            windowFocused = main.hasWindowFocus(),
            navigation = MainActivity::class.java.getDeclaredField("navState").apply { isAccessible = true }
                .get(main) as StandbyNavigationState,
            pages = roots.map { (pane, root) ->
                NativeBackDiagnosticPage(pane, root?.tag as? StandbyDestination, root?.isAttachedToWindow, root?.let(::isVisible))
            },
            focusClass = focus?.javaClass?.name,
            focusField = if (focus == null) NativeBackFocusField.NONE else classifyNativeBackFocusField(focus.tag as? String),
            focusEditable = focus is EditText,
            focusAttached = focus?.isAttachedToWindow,
            focusShown = focus?.isShown,
            focusPage = roots.firstOrNull { (_, root) -> focus != null && root?.findFocus() === focus }
                ?.second?.tag as? StandbyDestination,
            mediaEditorPresent = MainActivity::class.java.getDeclaredField("mediaEditor").apply { isAccessible = true }.get(main) != null,
            imeVisibility = when (insets?.isVisible(WindowInsetsCompat.Type.ime())) {
                true -> NativeBackImeVisibility.VISIBLE
                false -> NativeBackImeVisibility.HIDDEN
                null -> NativeBackImeVisibility.UNKNOWN
            },
            observedMs = SystemClock.elapsedRealtime(),
        )
    }

    /**
     * Logs only phase/class and returns payload-free secondary evidence.
     * @param phase Fixed diagnostic label.
     * @param failure Optional failure inspected only for its class and direct interruption.
     * @return Fresh marker without the original cause or suppressed tree.
     */
    private fun logNativeBackDiagnosticFailure(phase: String, failure: Throwable): IllegalStateException {
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        val label = "CHECK4_FOCUS_DIAG $phase failureClass=${failure.javaClass.name}"
        val marker = IllegalStateException(label)
        try {
            Log.w("MiraxCheck4Back", label)
        } catch (loggingFailure: Throwable) {
            if (loggingFailure is InterruptedException) Thread.currentThread().interrupt()
            marker.addSuppressed(IllegalStateException(
                "CHECK4_FOCUS_DIAG $phase loggingFailureClass=${loggingFailure.javaClass.name}",
            ))
        }
        return marker
    }

    /** Reads production facts on main, refusing editors or editable focus. @param expectedMain Locked Main for failure diagnostics only. @return Current typed facts. */
    private fun nativeBackReading(expectedMain: MainActivity? = null): NativeBackReading {
        val main = resumedActivity() ?: throw AssertionError("CHECK4_BACK fixture=INCOMPLETE no RESUMED Main")
        try {
            check(main.currentFocus !is EditText &&
                MainActivity::class.java.getDeclaredField("mediaEditor").apply { isAccessible = true }.get(main) == null
            ) { "CHECK4_BACK fixture=INCOMPLETE unexpected editable focus/editor" }
        } catch (error: IllegalStateException) {
            try {
                logNativeBackFocusDiagnostic(main, expectedMain)
            } catch (diagnosticFailure: Throwable) {
                error.addSuppressed(logNativeBackDiagnosticFailure("snapshot", diagnosticFailure))
            }
            throw error
        }
        return readNativeBackFacts(main)
    }

    /** Reads B1's explicit settings context, never relaxing A's guard. @param expectedMain Post-readiness Main. @return Base and independent focus/IME facts. */
    private fun nativeBackSettingsReading(expectedMain: MainActivity): NativeBackReading {
        val main = resumedActivity() ?: throw AssertionError("CHECK4_BACK fixture=INCOMPLETE no RESUMED Main")
        val base = readNativeBackFacts(main)
        val focus = inspectNativeBackFocus(main, expectedMain)
        check(focus.sameMain == true && focus.windowFocused && focus.navigation == base.navigation) {
            "CHECK4_BACK fixture=INCOMPLETE settings Main/route changed during observation"
        }
        check(!focus.mediaEditorPresent) { "CHECK4_BACK fixture=INCOMPLETE settings media editor present" }
        val dialog = MainActivity::class.java.getDeclaredField("frozenDialog").apply { isAccessible = true }
            .get(main) as android.app.Dialog?
        check(dialog?.isShowing != true) { "CHECK4_BACK fixture=INCOMPLETE settings dialog present" }
        check(focus.imeVisibility != NativeBackImeVisibility.UNKNOWN) {
            "CHECK4_BACK fixture=INCOMPLETE settings IME visibility unavailable"
        }
        check(!focus.focusEditable || (focus.focusAttached == true && focus.focusShown == true &&
            focus.focusPage in base.visiblePages && focus.focusField != NativeBackFocusField.OTHER &&
            focus.focusField != NativeBackFocusField.NONE)) {
            "CHECK4_BACK fixture=INCOMPLETE unknown or detached editable settings focus"
        }
        return base.copy(settingsFocus = focus, observedMs = focus.observedMs)
    }

    /** Reads the original base facts after the family's own prerequisites. @param main Observed Main. @return Original navigation/session facts. */
    private fun readNativeBackFacts(main: MainActivity): NativeBackReading {
        val navigation = MainActivity::class.java.getDeclaredField("navState").apply { isAccessible = true }
            .get(main) as StandbyNavigationState
        val root = MainActivity::class.java.getDeclaredField("rootExit").apply { isAccessible = true }
            .get(main) as RootExitConfirmation
        val pages = listOf(R.id.pageStart, R.id.pageEnd).mapNotNull { id ->
            main.findViewById<ViewGroup>(id).getChildAt(0)?.takeIf(::isVisible)
        }
        val homeVisible = when (navigation.current) {
            StandbyDestination.STANDBY -> inspectStandby() != null
            StandbyDestination.DASHBOARD -> pages.any { it.findViewWithTag<View>("legacyIdentity")?.let(::isVisible) == true }
            else -> false
        }
        val timerDelegate = BroadcastAutoStop::class.java.getDeclaredField("timer\$delegate")
            .apply { isAccessible = true }.get(BroadcastAutoStop) as Lazy<*>
        check(timerDelegate.isInitialized()) { "CHECK4_BACK fixture=INCOMPLETE idle timer was not initialized by host" }
        val timer = timerDelegate.value as BroadcastAutoStopTimer
        return NativeBackReading(
            main, navigation, pages.mapNotNull { it.tag as? StandbyDestination }.toSet(), homeVisible,
            main.hasWindowFocus(), main.findViewById<View>(R.id.drawer).isShown || main.findViewById<View>(R.id.drawerScrim).isShown,
            root.isPending, RootExitConfirmation::class.java.getDeclaredField("pendingSince").apply { isAccessible = true }.get(root) as Long?,
            MiraxApp.instance.session.snapshot(),
            BroadcastAutoStopTimer::class.java.getDeclaredField("armedMinutes").apply { isAccessible = true }.get(timer) != null,
            BroadcastAutoStop::class.java.getDeclaredField("pendingStop").apply { isAccessible = true }.get(BroadcastAutoStop) != null,
            SystemClock.elapsedRealtime(),
        )
    }

    /**
     * Awaits queued readonly observations without global idle or wrapping the original Throwable.
     * @param start Original phase start.
     * @param timeout Original launch/UI budget, narrowed to 2000ms for root confirmation.
     * @param action Read or test-observer registration/removal on main.
     * @return Captured result, or the original failure rethrown on the test thread.
     */
    private fun <T> readNativeBackOnMain(start: Long, timeout: Long = 5000L, action: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "CHECK4_BACK must await main off the main thread" }
        if (!hasWaitBudget(start, timeout)) throw AssertionError("CHECK4_BACK fixture=INCOMPLETE main observation deadline")
        val completed = CountDownLatch(1)
        val pending = AtomicBoolean(true)
        val result = AtomicReference<Result<T>?>(null)
        val handler = Handler(Looper.getMainLooper())
        val task = Runnable {
            if (pending.compareAndSet(true, false)) {
                try {
                    result.set(Result.success(action()))
                } catch (error: Throwable) {
                    result.set(Result.failure(error))
                    logMain03("CHECK4_BACK queuedObservationFailure=${error.javaClass.simpleName}")
                } finally {
                    completed.countDown()
                }
            }
        }
        check(handler.post(task)) { "CHECK4_BACK fixture=INCOMPLETE main post rejected" }
        try {
            val remaining = timeout - (SystemClock.elapsedRealtime() - start)
            if (remaining <= 0L || !completed.await(remaining, TimeUnit.MILLISECONDS)) {
                throw AssertionError("CHECK4_BACK fixture=INCOMPLETE main observation deadline")
            }
        } catch (error: Throwable) {
            if (pending.compareAndSet(true, false)) handler.removeCallbacks(task)
            if (error is InterruptedException) Thread.currentThread().interrupt()
            logMain03("CHECK4_BACK boundedObservationFailure=${error.javaClass.simpleName}")
            throw error
        }
        val captured = result.get() ?: throw AssertionError("CHECK4_BACK fixture=INCOMPLETE missing main result")
        val value = captured.getOrThrow()
        if (!hasWaitBudget(start, timeout)) throw AssertionError("CHECK4_BACK fixture=INCOMPLETE late main result")
        return value
    }

    /** Starts a fresh target activity without clearing its process or stored data. */
    internal fun launchApp() {
        val intent = Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
        )
        InstrumentationRegistry.getInstrumentation().startActivitySync(intent)
        waitForStandby(launchTimeoutMs)
    }

    /** Opens the native standby handle or the existing inner-page menu. */
    internal fun openDrawer() {
        var standby: StandbyControls? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { standby = inspectStandby() }
        val selector = standby?.let { By.pkg(packageName).desc(it.menuDescription) }
            ?: By.pkg(packageName).res("$packageName:id/menuButton")
        waitForControl(selector, 5000).click()
        waitForObject(By.pkg(packageName).res("$packageName:id/drawerBody"), 5000)
        device.waitForIdle(500)
    }

    /**
     * Verifies the localized title and selected option remain on the language page.
     *
     * @param title expected localized page title.
     * @param selectedLabel expected localized selected option.
     */
    private fun waitForLanguageState(title: String, selectedLabel: String) {
        val startTime = SystemClock.elapsedRealtime()
        waitForPageTag("language")
        if (hasWaitBudget(startTime, 5000)) waitForObject(By.pkg(packageName).text(title), 5000)
        val selector = By.pkg(packageName).text(selectedLabel)
        if (hasWaitBudget(startTime, 5000)) waitForPageControl(selector)
        while (hasWaitBudget(startTime, 5000)) {
            val candidates = device.findObjects(selector)
            if (!hasWaitBudget(startTime, 5000)) break
            val selected = candidates.firstOrNull {
                hasWaitBudget(startTime, 5000) && it.isSelected && hasWaitBudget(startTime, 5000) &&
                    it.isEnabled && hasWaitBudget(startTime, 5000) &&
                    it.isClickable && hasWaitBudget(startTime, 5000) && !it.visibleBounds.isEmpty
            }
            if (!hasWaitBudget(startTime, 5000)) break
            if (selected != null) {
                waitForPageTag("language")
                if (acceptWaitResult("language-state", startTime, 5000)) return
                break
            }
            Thread.sleep(100)
        }
        throw AssertionError("Selected language option not found: $selectedLabel")
    }

    /**
     * Language state captured before the test changes it.
     *
     * @param present whether the stored key existed.
     * @param raw original stored value, including unknown strings.
     * @param preference original session preference.
     * @param resolved original session-resolved language.
     * @param locales original application locale list.
     */
    private data class LanguageBaseline(
        val present: Boolean,
        val raw: String?,
        val preference: LanguagePreference,
        val resolved: AppLanguage,
        val locales: LocaleListCompat,
    )

    /**
     * Captures only the existing language key and its runtime state.
     *
     * @return exact persisted and runtime language state.
     */
    private fun captureLanguage(): LanguageBaseline {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        waitForTargetStopped()
        runBlocking { MiraxApp.instance.standbyLanguageGateway.currentChoice() }
        lateinit var baseline: LanguageBaseline
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val preferences = context.getSharedPreferences("mirax_session", Context.MODE_PRIVATE)
            val present = preferences.contains("language_preference")
            val raw = preferences.getString("language_preference", null)
            check(!present || raw != null) { "Cannot preserve a null language preference" }
            val snapshot = MiraxApp.instance.session.snapshot()
            baseline = LanguageBaseline(
                present,
                raw,
                snapshot.languagePreference,
                snapshot.appLanguage,
                AppCompatDelegate.getApplicationLocales(),
            )
        }
        return baseline
    }

    /** Waits until the target activity has unbound its pending language submission. */
    private fun waitForTargetStopped() {
        val startTime = SystemClock.elapsedRealtime()
        while (hasWaitBudget(startTime, launchTimeoutMs)) {
            var active = true
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val monitor = ActivityLifecycleMonitorRegistry.getInstance()
                active = listOf(Stage.STARTED, Stage.RESUMED, Stage.PAUSED).any { stage ->
                    monitor.getActivitiesInStage(stage).any { it is MainActivity && it.packageName == packageName }
                }
            }
            if (!hasWaitBudget(startTime, launchTimeoutMs)) break
            if (!active && acceptWaitResult("target-stopped", startTime, launchTimeoutMs)) return
            if (!hasWaitBudget(startTime, launchTimeoutMs)) break
            Thread.sleep(100)
        }
        throw AssertionError("Target activity did not stop before language restoration")
    }

    /**
     * Restores the exact raw key, gateway state, session preference, and app locales.
     *
     * @param baseline language state captured before the test.
     */
    private fun restoreLanguage(baseline: LanguageBaseline) {
        val preferences = context.getSharedPreferences("mirax_session", Context.MODE_PRIVATE)
        val gateway = MiraxApp.instance.standbyLanguageGateway
        try {
            device.pressHome()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            waitForTargetStopped()
            runBlocking { gateway.currentChoice() }
        } finally {
            try {
                val editor = preferences.edit()
                if (baseline.present) {
                    editor.putString("language_preference", baseline.raw)
                } else {
                    editor.remove("language_preference")
                }
                check(editor.commit()) { "Could not restore the original language preference" }
                runBlocking { gateway.currentChoice() }
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    MiraxApp.instance.session.handle(SessionAction.SetLanguagePreference(baseline.preference))
                    AppCompatDelegate.setApplicationLocales(baseline.locales)
                }
            }
        }
        assertThat(preferences.contains("language_preference"), Matchers.equalTo(baseline.present))
        assertThat(preferences.getString("language_preference", null), Matchers.equalTo(baseline.raw))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val snapshot = MiraxApp.instance.session.snapshot()
            assertThat(snapshot.languagePreference, Matchers.equalTo(baseline.preference))
            assertThat(snapshot.appLanguage, Matchers.equalTo(baseline.resolved))
            assertThat(
                AppCompatDelegate.getApplicationLocales().toLanguageTags(),
                Matchers.equalTo(baseline.locales.toLanguageTags()),
            )
        }
    }
}
