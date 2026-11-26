package me.trinitrix.mirax

import android.app.Dialog
import android.app.LocaleManager
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.trinitrix.mirax.broadcast.BroadcastAutoStop
import me.trinitrix.mirax.broadcast.BroadcastAutoStopTimer
import me.trinitrix.mirax.data.standby.StagedCandidate
import me.trinitrix.mirax.data.standby.StandbyActive
import me.trinitrix.mirax.data.standby.StandbyFraming
import me.trinitrix.mirax.data.standby.StandbyMediaKind
import me.trinitrix.mirax.data.standby.StandbyMediaStore
import me.trinitrix.mirax.data.standby.StandbySnapshot
import me.trinitrix.mirax.data.standby.theme.StandbyLanguage
import me.trinitrix.mirax.data.standby.theme.StandbyThemeId
import me.trinitrix.mirax.host.HostEnvironment
import me.trinitrix.mirax.host.SessionPlatformBinding
import me.trinitrix.mirax.session.LanguagePreference
import me.trinitrix.mirax.session.PrePlayAttemptId
import me.trinitrix.mirax.session.PrePlayFailureCause
import me.trinitrix.mirax.session.PrePlayFailureEpisodeOwner
import me.trinitrix.mirax.session.RootExitConfirmation
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.SinkAttemptSource
import me.trinitrix.mirax.session.StandbyDestination
import me.trinitrix.mirax.session.StandbyNavigationState
import me.trinitrix.mirax.standbyprobe.media.StandbyMediaProbeFixtures
import me.trinitrix.mirax.ui.standby.BigStandbyHost
import me.trinitrix.mirax.ui.standby.StandbyHostLease
import me.trinitrix.mirax.ui.standby.StandbyMediaEdit
import me.trinitrix.mirax.ui.standby.StandbyMediaEditStatus
import me.trinitrix.mirax.ui.standby.StandbyMediaOwner
import me.trinitrix.mirax.ui.standby.foreground.LandscapeStandbyForegroundView
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundCallbacks
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundState
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundTags
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundView
import me.trinitrix.mirax.ui.standby.framing.StandbyFramingEditorView
import me.trinitrix.mirax.ui.standby.hardline.HardlineSceneView
import me.trinitrix.mirax.ui.standby.landscape.LandscapeSceneView
import me.trinitrix.mirax.ui.standby.matter.MatterParticleView
import me.trinitrix.mirax.ui.standby.media.AndroidStandbyMediaDriver
import me.trinitrix.mirax.ui.standby.media.StandbyFramingControlsModel
import me.trinitrix.mirax.ui.standby.runtime.AndroidStandbyMotion
import me.trinitrix.mirax.ui.standby.runtime.StandbyOledDriftSource
import me.trinitrix.mirax.ui.standby.runtime.StandbyRuntimeHost
import me.trinitrix.mirax.ui.standby.settings.StandbyLanguageGateway
import me.trinitrix.mirax.wfd.rtsp.RtspFailure
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the Activity's real standby gates with deterministic failure time. */
@RunWith(AndroidJUnit4::class)
class MainActivityStandbyIntegrationTest {
    @Test
    fun failureOnlyRefresh_restoresResolvedPrimaryCopyAndSceneState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "me.trinitrix.mirax.vanillaprobe" &&
            instrumentation.context.packageName == "me.trinitrix.mirax.vanillaprobe.test"
        ) { "FAILURE_REFRESH requires the exact isolated main-probe package pair" }
        val app = instrumentation.targetContext.applicationContext
        check(app.javaClass == MiraxApp::class.java && app === MiraxApp.instance) {
            "FAILURE_REFRESH requires the real MiraxApp application"
        }
        try {
            app.classLoader.loadClass("me.trinitrix.mirax.host.AndroidSessionPlatform")
            throw AssertionError("FAILURE_REFRESH refuses a compiled real AndroidSessionPlatform")
        } catch (expected: ClassNotFoundException) {
            Log.i("FAKESEAM", "FAILURE_REFRESH prelaunch AndroidSessionPlatform absent")
        }
        check(SessionPlatformBinding.platform.javaClass.name == "me.trinitrix.mirax.host.FakeSessionPlatform") {
            "FAILURE_REFRESH requires the runtime FakeSessionPlatform binding"
        }
        withEnglishFailureRefreshFixture(app as MiraxApp) { scenario ->
            awaitActivityAction(scenario) { activity ->
                check(activity.javaClass == MainActivity::class.java && !activity.isFinishing && !activity.isDestroyed &&
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().singleOrNull() === activity
                ) { "FAILURE_REFRESH requires this scenario's real RESUMED Main" }
                val snapshot = MiraxApp.instance.session.snapshot()
                assertEquals(LanguagePreference.ENGLISH, snapshot.languagePreference)
                assertEquals(Locale.ENGLISH.language, activity.resources.configuration.locales[0]?.language)
                assertEquals("BROADCAST OFF", activity.getString(R.string.standby_foreground_phase_off_eyebrow))
                Log.i("FAKESEAM", "FAILURE_REFRESH launched with English session and resources")
                assumeTrue(snapshot.phase != ScreenPhase.CONNECTED)
                val hostField = activityField("standbyHost")
                val themeField = activityField("standbyTheme")
                val motionField = activityField("standbyCopyMotionAllowed")
                val originalHost = hostField.get(activity)
                val originalTheme = themeField.get(activity)
                val originalMotion = motionField.get(activity)
                val ownerField = PrePlayFailureEpisodeOwner::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
                val attemptField = SessionHost::class.java.getDeclaredField("lastPrePlayAttempt").apply { isAccessible = true }
                val originalOwner = ownerField.get(null)
                val originalAttempt = attemptField.get(null)
                val owner = PrePlayFailureEpisodeOwner(nowMs = { 1_000L }, scheduler = { _, _ -> true },
                    canceller = {}, isOwnerThread = { true })
                var attemptSequence = 913L
                val source = SinkAttemptSource(913, 914L)
                var callbacks = 0
                // An unmounted host exercises the real binding without copy-fade timing or hardware output.
                val host = BigStandbyHost(activity, StandbyForegroundCallbacks(onToggleBroadcast = { callbacks++ }))
                val foregrounds = (0 until host.childCount).map(host::getChildAt).filterIsInstance<StandbyForegroundView>()
                val primary = foregrounds.filterNot { it is me.trinitrix.mirax.ui.standby.foreground.MatterStandbyForegroundView }
                val hint = requireNotNull(foregrounds.filterIsInstance<LandscapeStandbyForegroundView>().single()
                    .findViewWithTag<TextView>("foreground_hint"))
                val titles = primary.map { requireNotNull(it.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE)) }
                val eyebrows = primary.map { requireNotNull(it.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_EYEBROW)) }
                val notices = primary.map { requireNotNull(it.findViewWithTag<TextView>(StandbyForegroundTags.FAILED)) }
                val scene = (0 until host.childCount).map(host::getChildAt).filterIsInstance<HardlineSceneView>().single()
                val rog = HardlineSceneView::class.java.getDeclaredField("rogScene").apply { isAccessible = true }
                    .get(scene) as me.trinitrix.mirax.ui.standby.hardline.RogHardlineScene
                val landscape = (0 until host.childCount).map(host::getChildAt).filterIsInstance<LandscapeSceneView>().single()
                val stateField = StandbyForegroundView::class.java.getDeclaredField("foregroundState").apply { isAccessible = true }
                val refresh = activityMethod("refreshPrePlayFailureViews", SessionSnapshot::class.java)

                /**
                 * Checks that failure presentation overlays the same session facts.
                 * @param input Snapshot supplied to the real Main binding.
                 * @param cause Expected shown cause at this checkpoint.
                 */
                fun assertForwardedState(input: SessionSnapshot, cause: PrePlayFailureCause?) {
                    foregrounds.forEach {
                        val state = stateField.get(it) as StandbyForegroundState
                        assertEquals(input.phase, state.phase)
                        assertEquals(input.advertisingEnabled, state.advertisingEnabled)
                        assertEquals(input.effectiveBroadcastName, state.receiverName)
                        assertEquals(cause, state.failureCause)
                    }
                }

                try {
                    hostField.set(activity, host)
                    ownerField.set(null, owner)
                    owner.onForegroundVisibilityChanged(true)
                    for (theme in listOf(StandbyThemeId.LANDSCAPE, StandbyThemeId.HARDLINE)) {
                        themeField.set(activity, theme)
                        for (phase in listOf(ScreenPhase.READY, ScreenPhase.ADVERTISING, ScreenPhase.CONNECTING)) {
                            val connecting = phase == ScreenPhase.CONNECTING
                            val advertising = phase != ScreenPhase.READY
                            val input = snapshot.copy(
                                phase = phase, advertisingEnabled = advertising,
                                effectiveBroadcastName = if (connecting) "Studio" else snapshot.effectiveBroadcastName,
                            )
                            val (titleId, expectedEyebrow) = when (phase) {
                                ScreenPhase.READY -> R.string.standby_foreground_phase_off_title to "BROADCAST OFF"
                                ScreenPhase.ADVERTISING -> R.string.standby_foreground_phase_waiting_title to "BROADCAST ON"
                                ScreenPhase.CONNECTING -> R.string.standby_foreground_phase_connecting_title to "CONNECTING"
                                else -> error("Unexpected failure refresh fixture phase: $phase")
                            }
                            val expectedTitle = activity.getString(titleId)
                            owner.onDismissed()
                            refresh.invoke(activity, input)
                            val originalSceneText = rog.statusText
                            titles.forEach { assertEquals(expectedTitle, it.text.toString()) }
                            eyebrows.forEach { assertEquals(expectedEyebrow, it.text.toString()) }
                            notices.forEach { assertEquals(View.GONE, it.visibility) }
                            assertForwardedState(input, null)
                            if (connecting) assertEquals("", hint.text.toString())
                            val attempt = PrePlayAttemptId(++attemptSequence)
                            attemptField.set(null, attempt)
                            assertTrue(owner.onPrePlayAttemptStarted(attempt, source))
                            owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
                            refresh.invoke(activity, input)
                            titles.forEach { assertEquals(activity.getString(R.string.standby_foreground_phase_failed_title), it.text.toString()) }
                            eyebrows.forEach { assertEquals("CONNECTION FAILED", it.text.toString()) }
                            assertTrue(rog.statusAlert)
                            assertEquals("Connection failed · Press Win + K to retry", rog.statusText)
                            assertEquals(if (advertising) 1.0 else 0.0, landscape.blendTarget, 0.0)
                            assertForwardedState(input, PrePlayFailureCause.PRE_PLAY_TIMEOUT)
                            notices.forEach {
                                assertEquals(View.VISIBLE, it.visibility)
                                assertEquals(activity.getString(R.string.preplay_failed_timeout), it.text.toString())
                            }
                            if (connecting) {
                                assertEquals(activity.getString(R.string.standby_foreground_connection_hint_format, input.effectiveBroadcastName),
                                    hint.text.toString())
                            }
                            owner.onDismissed()
                            refresh.invoke(activity, input)
                            titles.forEach { assertEquals(expectedTitle, it.text.toString()) }
                            eyebrows.forEach { assertEquals(expectedEyebrow, it.text.toString()) }
                            assertForwardedState(input, null)
                            if (connecting) assertEquals("", hint.text.toString())
                            assertFalse(rog.statusAlert)
                            assertEquals(originalSceneText, rog.statusText)
                            primary.forEach {
                                assertEquals(View.GONE, requireNotNull(it.findViewWithTag<View>(StandbyForegroundTags.PHASE_DETAIL)).visibility)
                                assertEquals(View.GONE, requireNotNull(it.findViewWithTag<View>(StandbyForegroundTags.FAILED)).visibility)
                            }
                            assertEquals(0, callbacks)
                        }
                    }
                } finally {
                    hostField.set(activity, originalHost)
                    themeField.set(activity, originalTheme)
                    motionField.set(activity, originalMotion)
                    ownerField.set(null, originalOwner)
                    attemptField.set(null, originalAttempt)
                }
            }
        }
    }

    /**
     * Pins only the guarded Main probe while the failure-refresh assertions require English.
     * @param app Owned probe application verified by the caller.
     * @param test Assertions to run against the launched Main.
     */
    private fun withEnglishFailureRefreshFixture(app: MiraxApp, test: (ActivityScenario<MainActivity>) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun <T> onMain(action: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(action) }
            return requireNotNull(result).getOrThrow()
        }

        val localeManager = requireNotNull(app.getSystemService(LocaleManager::class.java))
        val gatewayField = MiraxApp::class.java.getDeclaredField("standbyLanguageGateway").apply { isAccessible = true }
        val (previousGateway, previousPreference, previousLocales) = onMain {
            Triple(app.standbyLanguageGateway, app.session.snapshot().languagePreference, localeManager.applicationLocales)
        }
        val preferences = app.getSharedPreferences("mirax_session", Context.MODE_PRIVATE)
        val previousStoredChoice = preferences.all["language_preference"]
        val englishLocales = LocaleList(Locale.ENGLISH)
        Closeable {
            onMain {
                app.session.handle(SessionAction.SetLanguagePreference(previousPreference))
                try {
                    localeManager.applicationLocales = previousLocales
                } finally {
                    gatewayField.set(app, previousGateway)
                }
                check(app.standbyLanguageGateway === previousGateway &&
                    app.session.snapshot().languagePreference == previousPreference &&
                    localeManager.applicationLocales == previousLocales &&
                    preferences.all["language_preference"] == previousStoredChoice
                ) { "FAILURE_REFRESH did not restore the language baseline" }
                Log.i("FAKESEAM", "FAILURE_REFRESH restored session language and package locales; stored choice unchanged")
            }
        }.use {
            onMain {
                // Main reloads the choice on start; keep that read in memory to avoid reverting the fixture or writing preferences.
                gatewayField.set(app, object : StandbyLanguageGateway {
                    override val choiceFlow = flowOf(StandbyLanguage.ENGLISH)
                    override suspend fun currentChoice() = StandbyLanguage.ENGLISH
                    override suspend fun selectLanguage(language: StandbyLanguage?) {
                        error("FAILURE_REFRESH must not persist a language choice")
                    }
                })
                app.session.handle(SessionAction.SetLanguagePreference(LanguagePreference.ENGLISH))
                HostEnvironment.applyAppLanguage(app.session.snapshot().appLanguage)
                // AppCompat may not have an activity delegate yet; use its package-scoped backend directly if needed.
                if (localeManager.applicationLocales != englishLocales) {
                    localeManager.applicationLocales = englishLocales
                }
                check(localeManager.applicationLocales == englishLocales) { "FAILURE_REFRESH could not pin package locales" }
            }
            ActivityScenario.launch(MainActivity::class.java).use(test)
        }
    }

    @Test
    fun drawerOpenDragAndCloseKeepFailureTimeRunningWhileEditorPausesIt() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val snapshot = MiraxApp.instance.session.snapshot()
                assumeTrue(snapshot.phase != ScreenPhase.CONNECTED)
                val host = activityField("standbyHost").get(activity) as BigStandbyHost
                val ownerField = PrePlayFailureEpisodeOwner::class.java.getDeclaredField("INSTANCE")
                    .apply { isAccessible = true }
                val latestField = SessionHost::class.java.getDeclaredField("lastPrePlayAttempt")
                    .apply { isAccessible = true }
                val previousOwner = ownerField.get(null) as PrePlayFailureEpisodeOwner?
                val previousAttempt = latestField.get(null) as PrePlayAttemptId?
                var now = 1_000L
                val owner = PrePlayFailureEpisodeOwner(
                    nowMs = { now },
                    scheduler = { _, _ -> true },
                    canceller = {},
                    isOwnerThread = { true },
                )
                val sync = activityMethod("syncStandbyForeground", SessionSnapshot::class.java)
                val cause = activityMethod("prePlayFailureCause", SessionSnapshot::class.java)
                val ready = snapshot.copy(phase = ScreenPhase.READY)
                val removeEditor = activityMethod("removeMediaEditor", requireNotNull(Boolean::class.javaPrimitiveType))
                ownerField.set(null, owner)
                try {
                    sync.invoke(activity, snapshot)
                    val source = SinkAttemptSource(72, 73L)
                    SessionHost.onSinkAttemptStarted(source)
                    owner.onPrePlayTerminal(RtspFailure.Timeout("M2", 2), source)
                    assertEquals(7_500L, requireNotNull(owner.snapshot()).remainingMs)
                    assertEquals(PrePlayFailureCause.PRE_PLAY_TIMEOUT, cause.invoke(activity, ready))

                    now += 1_000L
                    activityMethod("openDrawer").invoke(activity)
                    assertEquals(6_500L, requireNotNull(owner.snapshot()).remainingMs)
                    assertEquals(PrePlayFailureCause.PRE_PLAY_TIMEOUT, cause.invoke(activity, ready))
                    assertSame(host, activityField("standbyHost").get(activity))
                    now += 1_000L
                    activityMethod("closeDrawer").invoke(activity)
                    assertEquals(5_500L, requireNotNull(owner.snapshot()).remainingMs)

                    now += 1_000L
                    val root = activity.findViewById<View>(R.id.root)
                    assertTrue(root.width > 0 && root.height > 0)
                    val location = IntArray(2)
                    root.getLocationOnScreen(location)
                    val x = location[0] + root.width / 2f
                    val y = location[1] + root.height / 2f
                    val distance = ViewConfiguration.get(activity).scaledTouchSlop * 4f + 1f
                    val dx = if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) -distance else distance
                    val eventTime = SystemClock.uptimeMillis()
                    val swipe = activityMethod("onDrawerSwipe", MotionEvent::class.java)
                    val down = MotionEvent.obtain(eventTime, eventTime, MotionEvent.ACTION_DOWN, x, y, 0)
                    val move = MotionEvent.obtain(eventTime, eventTime + 16, MotionEvent.ACTION_MOVE, x + dx, y, 0)
                    val cancel = MotionEvent.obtain(eventTime, eventTime + 32, MotionEvent.ACTION_CANCEL, x + dx, y, 0)
                    try {
                        swipe.invoke(activity, down)
                        assertEquals(true, swipe.invoke(activity, move))
                        assertEquals(4_500L, requireNotNull(owner.snapshot()).remainingMs)
                        assertEquals(PrePlayFailureCause.PRE_PLAY_TIMEOUT, cause.invoke(activity, ready))
                        now += 1_000L
                        assertEquals(true, swipe.invoke(activity, cancel))
                    } finally {
                        down.recycle()
                        move.recycle()
                        cancel.recycle()
                    }
                    assertEquals(3_500L, requireNotNull(owner.snapshot()).remainingMs)
                    assertSame(host, activityField("standbyHost").get(activity))

                    now += 500L
                    val candidate = StagedCandidate(
                        1L, File(activity.cacheDir, "failure-gate-unused"),
                        StandbyMediaKind.IMAGE, StandbyFraming.CENTERED,
                    )
                    val edit = StandbyMediaEdit(candidate, StandbyFramingControlsModel(), StandbyMediaEditStatus.EDITING)
                    activityMethod("showMediaEditor", StandbyMediaEdit::class.java).invoke(activity, edit)
                    assertNull(cause.invoke(activity, ready))
                    assertEquals(3_000L, requireNotNull(owner.snapshot()).remainingMs)
                    now += 10_000L
                    assertEquals(3_000L, requireNotNull(owner.snapshot()).remainingMs)
                    removeEditor.invoke(activity, true)
                    assertEquals(PrePlayFailureCause.PRE_PLAY_TIMEOUT, cause.invoke(activity, ready))
                    now += 2_999L
                    assertEquals(1L, requireNotNull(owner.snapshot()).remainingMs)
                    now += 1L
                    sync.invoke(activity, snapshot)
                    assertNull(owner.snapshot())
                } finally {
                    try {
                        removeEditor.invoke(activity, false)
                    } finally {
                        ownerField.set(null, previousOwner)
                        latestField.set(null, previousAttempt)
                        sync.invoke(activity, snapshot)
                    }
                }
            }
        }
    }

    /**
     * Exercises standby sensor holding and keep-awake policies across themes, using an existing Available
     * image file with validation through public import or a fresh test-owned temporary source to drive a real
     * public import and cancellation transaction.
     */
    @Test
    fun attachedMatterAloneHoldsSensorsWhileOtherThemesKeepAwakeAndAnimate() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Test must run off the main thread" }
        checkErrorChainingSelfTest()
        checkSnapshotBaselineSelfTest()

        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MiraxApp
        val owner = app.standbyMediaOwner
        val selected = activityField("standbyTheme")
        val readiness = activityField("standbyMediaReady")
        val update = activityMethod("updateStandby", View::class.java, SessionSnapshot::class.java)
        val sync = activityMethod("syncStandbyForeground", SessionSnapshot::class.java)
        var interrupted = false
        var primaryError: Throwable? = null

        /** Records and logs a failure while clearing interruption for subsequent cleanup waits. */
        fun recordError(error: Throwable) {
            val pendingInterrupt = Thread.interrupted()
            interrupted = interrupted || error is InterruptedException || pendingInterrupt
            Log.e("MainStandbyIntegration", "Standby integration error observed", error)
            primaryError = recordThrowable(primaryError, error)
        }

        /** Attempts one cleanup independently, retaining its failure for the final rethrow. */
        fun attemptCleanup(action: () -> Unit) {
            if (Thread.interrupted()) interrupted = true
            try {
                action()
            } catch (error: Throwable) {
                recordError(error)
            }
        }

        var scenarioLaunchStarted = false
        var scenario: ActivityScenario<MainActivity>? = null
        var hostRef: BigStandbyHost? = null
        var baselineCaptured = false
        var originalTheme: StandbyThemeId? = null
        var originalReady = false
        var originalMediaVisible = false
        var currentLease: Long? = null
        var candidateFile: File? = null
        // Read and change transaction ownership only in serialized main-thread actions.
        var importTriggered = false
        var initialSnapshot: StandbySnapshot? = null
        var ownedSourceFile: File? = null
        var sourceSafeToDelete = true

        /** Runs an action within its phase budget on the original Host's activity. */
        fun onHost(start: Long, action: (MainActivity) -> Unit) {
            awaitActivityAction(checkNotNull(scenario), remainingBudget(start)) { activity ->
                assertSame(checkNotNull(hostRef), activityField("standbyHost").get(activity))
                action(activity)
            }
            remainingBudget(start)
        }

        /** Observes the real edit on main and retains its candidate before any cancellation. */
        fun observeEdit(): StandbyMediaEdit? = ownerEdit(owner).also { edit ->
            if (edit != null) candidateFile = edit.candidate.file
        }

        try {
            val snapshot = runBlocking {
                withTimeout(5_000L) {
                    app.standbyMediaStore.snapshot()
                }
            }
            initialSnapshot = snapshot
            val sourceFile = when (snapshot) {
                is StandbySnapshot.Available -> {
                    val activeImage = snapshot.active
                    check(activeImage.kind == StandbyMediaKind.IMAGE) {
                        "Test requires active media kind IMAGE, got: ${activeImage.kind}"
                    }
                    val activeFile = checkNotNull(activeImage.file) { "Active image file must not be null" }
                    check(activeFile.isFile && activeFile.length() > 0L) {
                        "Active image file missing or empty: ${activeFile.absolutePath}"
                    }
                    activeFile
                }
                StandbySnapshot.NeverSet -> {
                    // Snapshot metadata only; importer performs actual decode and format validation on stage.
                    val tempSource = File.createTempFile("standby-source-", ".png", app.cacheDir)
                    ownedSourceFile = tempSource
                    tempSource.writeBytes(Base64.decode(StandbyMediaProbeFixtures.SMALL_PNG_BASE64, Base64.DEFAULT))
                    check(tempSource.isFile && tempSource.length() > 0L) {
                        "Failed to create owned temporary source file: ${tempSource.absolutePath}"
                    }
                    tempSource
                }
                else -> error("Unsupported initial standby snapshot state for sensor policy test: $snapshot")
            }

            scenarioLaunchStarted = true
            val currentScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = currentScenario

            // Phase 1: Initial UI inspection and baseline setup
            val phase1Start = SystemClock.elapsedRealtime()
            awaitActivityAction(currentScenario, remainingBudget(phase1Start)) { activity ->
                val snapshot = MiraxApp.instance.session.snapshot()
                assertTrue("Sensor policy test requires a non-connected session", snapshot.phase != ScreenPhase.CONNECTED)
                val host = activityField("standbyHost").get(activity) as BigStandbyHost
                hostRef = host
                assertTrue(host.isAttachedToWindow)
                val runtime = activityMethod("getStandbyRuntime").invoke(activity) as StandbyRuntimeHost
                originalTheme = selected.get(activity) as StandbyThemeId?
                originalReady = readiness.getBoolean(activity)
                val mediaVisibleField = BigStandbyHost::class.java.getDeclaredField("mediaVisible")
                    .apply { isAccessible = true }
                originalMediaVisible = mediaVisibleField.getBoolean(host)
                baselineCaptured = true

                currentLease = activityField("mediaLease").get(activity) as? Long
                checkNotNull(currentLease) { "mediaLease must not be null after MainActivity launch" }

                fun select(theme: StandbyThemeId) {
                    selected.set(activity, theme)
                    update.invoke(activity, host, snapshot)
                    sync.invoke(activity, snapshot)
                }

                // Initial false-readiness seam
                readiness.setBoolean(activity, false)
                host.setMediaVisible(false)
                for ((theme, field) in listOf(
                    StandbyThemeId.LANDSCAPE to "landscapeScene",
                    StandbyThemeId.HARDLINE to "hardlineScene",
                )) {
                    select(theme)
                    assertFalse(runtime.isHoldingSensors())
                    assertTrue(runtime.shouldHoldKeepAwake())
                    assertTrue(runtime.shouldRunAmbient())
                    assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
                    val scene = BigStandbyHost::class.java.getDeclaredField(field).apply { isAccessible = true }
                        .get(host) as View
                    val projecting = scene.javaClass.getDeclaredField("projecting").apply { isAccessible = true }
                    assertFalse(projecting.getBoolean(scene))
                    assertTrue(foregroundState(host, theme).copyMotionAllowed)
                }
            }

            remainingBudget(phase1Start)

            // Phase 2: Public import must publish the actual attached editor.
            val phase2Start = SystemClock.elapsedRealtime()
            val lease = checkNotNull(currentLease)
            val host = checkNotNull(hostRef)
            sourceSafeToDelete = false
            onHost(phase2Start) { activity ->
                check(activityField("mediaLease").get(activity) == lease) { "Main media lease changed before import" }
                check(ownerEdit(owner) == null && !ownerStaging(owner)) { "Import requires an idle media owner" }
                importTriggered = true
                owner.importDocument(lease, Uri.fromFile(sourceFile))
            }
            awaitPhase(phase2Start, "published editor attached") {
                var ready = false
                onHost(phase2Start) { activity ->
                    val edit = observeEdit()
                    val mainEdit = activityField("mediaEdit").get(activity) as StandbyMediaEdit?
                    val mainEditor = activityField("mediaEditor").get(activity) as View?
                    ready = !ownerStaging(owner) && !activityField("mediaStaging").getBoolean(activity) &&
                        edit?.status == StandbyMediaEditStatus.EDITING && mainEdit === edit &&
                        mainEditor != null && mainEditor.isAttachedToWindow
                    if (ready) {
                        // Model software policy seam: isolate editor gating from real preview readiness.
                        val currentSnapshot = MiraxApp.instance.session.snapshot()
                        selected.set(activity, StandbyThemeId.HARDLINE)
                        readiness.setBoolean(activity, false)
                        host.setMediaVisible(false)
                        update.invoke(activity, host, currentSnapshot)
                        sync.invoke(activity, currentSnapshot)
                        assertEquals(StandbyThemeId.HARDLINE, selected.get(activity))
                        assertFalse(readiness.getBoolean(activity))
                        assertSame(edit, ownerEdit(owner))
                        assertSame(edit, activityField("mediaEdit").get(activity))
                        assertSame(mainEditor, activityField("mediaEditor").get(activity))
                        assertTrue(checkNotNull(mainEditor).isAttachedToWindow)
                        assertFalse(foregroundState(host, StandbyThemeId.HARDLINE).copyMotionAllowed)
                    }
                }
                ready
            }
            val candidate = checkNotNull(candidateFile) { "No candidate observed during import" }
            check(candidate.canonicalPath != sourceFile.canonicalPath) {
                "Candidate file must be distinct from original source file"
            }

            // Phase 3: Public cancel must retire the candidate without changing the original baseline.
            val phase3Start = SystemClock.elapsedRealtime()
            onHost(phase3Start) { activity ->
                check(activityField("mediaLease").get(activity) == lease) { "Main media lease changed before cancel" }
                check(observeEdit()?.status == StandbyMediaEditStatus.EDITING) { "Expected editable candidate before cancel" }
                owner.cancel(lease)
            }
            awaitPhase(phase3Start, "editor removed and candidate discarded") {
                var retired = false
                onHost(phase3Start) { activity ->
                    val mainEdit = activityField("mediaEdit").get(activity) as StandbyMediaEdit?
                    val mainEditor = activityField("mediaEditor").get(activity) as View?
                    retired = observeEdit() == null && mainEdit == null && !ownerStaging(owner) &&
                        !activityField("mediaStaging").getBoolean(activity) &&
                        (mainEditor == null || !mainEditor.isAttachedToWindow)
                }
                retired && !candidate.exists()
            }
            sourceSafeToDelete = true

            val postCancelSnapshot = runBlocking {
                withTimeout(5_000L) {
                    app.standbyMediaStore.snapshot()
                }
            }
            assertEquals(initialSnapshot, postCancelSnapshot)

            // Phase 4: Reapply software policy seam and verify post-editor motion and sensor behaviors
            val phase4Start = SystemClock.elapsedRealtime()
            onHost(phase4Start) { act ->
                val snapshot = MiraxApp.instance.session.snapshot()
                val runtime = activityMethod("getStandbyRuntime").invoke(act) as StandbyRuntimeHost
                fun select(theme: StandbyThemeId) {
                    selected.set(act, theme)
                    update.invoke(act, host, snapshot)
                    sync.invoke(act, snapshot)
                }

                // Same model software policy inputs as with the real editor; not native playback resumption.
                selected.set(act, StandbyThemeId.HARDLINE)
                readiness.setBoolean(act, false)
                host.setMediaVisible(false)
                update.invoke(act, host, snapshot)
                sync.invoke(act, snapshot)
                assertEquals(StandbyThemeId.HARDLINE, selected.get(act))
                assertFalse(readiness.getBoolean(act))
                assertTrue(foregroundState(host, StandbyThemeId.HARDLINE).copyMotionAllowed)

                // Matter holds sensors while copyMotionAllowed is false
                select(StandbyThemeId.MATTER)
                assertTrue(runtime.isHoldingSensors())
                assertFalse(foregroundState(host, StandbyThemeId.MATTER).copyMotionAllowed)

                // Custom media visible: Matter still holds sensors
                host.setMediaVisible(true)
                assertTrue(runtime.isHoldingSensors())

                // Detached host releases sensors; keep awake remains true
                val parent = host.parent as ViewGroup
                val index = parent.indexOfChild(host)
                val params = host.layoutParams
                var detachFailure: Throwable? = null
                try {
                    parent.removeView(host)
                    assertFalse(runtime.isHoldingSensors())
                    assertTrue(runtime.shouldHoldKeepAwake())
                } catch (error: Throwable) {
                    detachFailure = error
                } finally {
                    try {
                        if (host.parent == null) parent.addView(host, index, params)
                        assertSame(parent, host.parent)
                    } catch (restoreFailure: Throwable) {
                        detachFailure = recordThrowable(detachFailure, restoreFailure)
                    }
                }
                detachFailure?.let { throw it }
                assertTrue(runtime.isHoldingSensors())

                // Switch to Landscape: sensors released, keep awake remains true
                select(StandbyThemeId.LANDSCAPE)
                assertFalse(runtime.isHoldingSensors())
                assertTrue(runtime.shouldHoldKeepAwake())
            }
        } catch (error: Throwable) {
            recordError(error)
        } finally {
            try {
                val cleanupScenario = scenario
                val lease = currentLease
                if (cleanupScenario != null && lease != null) {
                    // Queue every independent phase after any running import; inspect ownership only on main.
                    attemptCleanup {
                        val start = SystemClock.elapsedRealtime()
                        awaitPhase(start, "cleanup staging completion") {
                            var settled = false
                            awaitActivityAction(cleanupScenario, remainingBudget(start)) { activity ->
                                settled = if (importTriggered) {
                                    observeEdit()
                                    !ownerStaging(owner) && !activityField("mediaStaging").getBoolean(activity)
                                } else {
                                    true
                                }
                            }
                            settled
                        }
                    }
                    attemptCleanup {
                        val start = SystemClock.elapsedRealtime()
                        awaitActivityAction(cleanupScenario, remainingBudget(start)) { activity ->
                            if (importTriggered) {
                                val edit = observeEdit()
                                if (edit?.status == StandbyMediaEditStatus.EDITING) {
                                    check(activityField("mediaLease").get(activity) == lease) { "Main media lease changed during cleanup" }
                                    owner.cancel(lease)
                                }
                            }
                        }
                        remainingBudget(start)
                    }
                    attemptCleanup {
                        val start = SystemClock.elapsedRealtime()
                        awaitPhase(start, "cleanup editor retirement and candidate deletion") {
                            var retired = false
                            var inspectFiles = false
                            awaitActivityAction(cleanupScenario, remainingBudget(start)) { activity ->
                                if (importTriggered) {
                                    inspectFiles = true
                                    val edit = observeEdit()
                                    val mainEdit = activityField("mediaEdit").get(activity) as StandbyMediaEdit?
                                    val mainEditor = activityField("mediaEditor").get(activity) as View?
                                    retired = edit == null && mainEdit == null && !ownerStaging(owner) &&
                                        !activityField("mediaStaging").getBoolean(activity) &&
                                        (mainEditor == null || !mainEditor.isAttachedToWindow)
                                }
                            }
                            if (!inspectFiles) {
                                true
                            } else if (!retired) {
                                false
                            } else {
                                val candidate = candidateFile
                                if (candidate != null) {
                                    !candidate.exists()
                                } else {
                                    val active = (initialSnapshot as? StandbySnapshot.Available)?.active?.file
                                    if (active != null) {
                                        val directory = checkNotNull(active.parentFile)
                                        val files = checkNotNull(directory.listFiles()) { "Cannot inspect media directory: $directory" }
                                        files.singleOrNull()?.canonicalFile == active.canonicalFile && active.isFile
                                    } else {
                                        val storeDir = File(app.filesDir, "standby_media")
                                        check(!java.nio.file.Files.isSymbolicLink(storeDir.toPath())) {
                                            "Store media directory must not be a symlink: ${storeDir.path}"
                                        }
                                        if (!storeDir.exists()) {
                                            true
                                        } else {
                                            check(storeDir.isDirectory) {
                                                "Store media directory is not a directory: ${storeDir.path}"
                                            }
                                            checkNotNull(storeDir.listFiles()) {
                                                "Cannot inspect store media directory: ${storeDir.path}"
                                            }.isEmpty()
                                        }
                                    }
                                }
                            }
                        }
                        sourceSafeToDelete = true
                    }
                }
                attemptCleanup {
                    if (baselineCaptured) {
                        val start = SystemClock.elapsedRealtime()
                        onHost(start) { activity ->
                            val host = checkNotNull(hostRef)
                            val snapshot = MiraxApp.instance.session.snapshot()
                            readiness.setBoolean(activity, originalReady)
                            host.setMediaVisible(originalMediaVisible)
                            selected.set(activity, originalTheme)
                            update.invoke(activity, host, snapshot)
                            sync.invoke(activity, snapshot)
                        }
                    }
                }
                attemptCleanup {
                    if (cleanupScenario != null) {
                        cleanupScenario.close()
                    } else if (scenarioLaunchStarted) {
                        error("ActivityScenario launch returned no handle; closure is incomplete")
                    }
                }
                attemptCleanup {
                    val source = ownedSourceFile
                    if (source != null) {
                        check(sourceSafeToDelete) {
                            "Owned source file retained due to unsettled transaction: ${source.absolutePath}"
                        }
                        check(source.name.startsWith("standby-source-") && source.name.endsWith(".png")) {
                            "Owned source file outside owned namespace: ${source.name}"
                        }
                        check(source.canonicalFile.parentFile == app.cacheDir.canonicalFile) {
                            "Owned source file outside cache directory: ${source.canonicalPath}"
                        }
                        check(!java.nio.file.Files.isSymbolicLink(source.toPath())) {
                            "Owned source file must not be a symlink: ${source.canonicalPath}"
                        }
                        val cand = candidateFile
                        check(cand == null || source.canonicalPath != cand.canonicalPath) {
                            "Owned source file matches candidate alias: ${source.canonicalPath}"
                        }
                        val active = (initialSnapshot as? StandbySnapshot.Available)?.active?.file
                        check(active == null || source.canonicalPath != active.canonicalPath) {
                            "Owned source file matches active alias: ${source.canonicalPath}"
                        }
                        if (source.exists()) {
                            check(source.isFile && source.delete()) { "Failed to delete owned temporary source file" }
                        }
                    }
                }
            } finally {
                if (Thread.interrupted()) interrupted = true
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        primaryError?.let { throw it }
    }

    @Test
    fun mediaReadinessImmediatelyFinishesHardlineCopyFadeOnly() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val snapshot = MiraxApp.instance.session.snapshot()
                assumeTrue(snapshot.phase != ScreenPhase.CONNECTED)
                assumeTrue(activityField("mediaEdit").get(activity) == null)
                val host = activityField("standbyHost").get(activity) as BigStandbyHost
                val originalPreview = host.mediaUnderlay
                val selected = activityField("standbyTheme")
                val originalTheme = selected.get(activity) as StandbyThemeId?
                val originalReady = activityField("standbyMediaReady").getBoolean(activity)
                val update = activityMethod("updateStandby", View::class.java, SessionSnapshot::class.java)
                val render = activityMethod(
                    "renderMediaPresentation", java.lang.Boolean.TYPE, Size::class.java,
                    StandbyMediaEdit::class.java, java.lang.Boolean.TYPE,
                )
                val scheduled = StandbyForegroundView::class.java.getDeclaredField("copyFrameScheduled")
                    .apply { isAccessible = true }
                val changes = StandbyForegroundView::class.java.getDeclaredField("copyChanges")
                    .apply { isAccessible = true }
                val foreground = BigStandbyHost::class.java.getDeclaredField("hardlineForeground")
                    .apply { isAccessible = true }.get(host) as StandbyForegroundView
                val title = requireNotNull(foreground.findViewWithTag<TextView>(StandbyForegroundTags.PHASE_TITLE))
                val callbacks = StandbyForegroundCallbacks()
                var failure: Throwable? = null
                var interrupted = false
                try {
                    render.invoke(activity, false, null, null, false)
                    selected.set(activity, StandbyThemeId.HARDLINE)
                    update.invoke(activity, host, snapshot)
                    val state = foregroundState(host, StandbyThemeId.HARDLINE)
                    assertTrue(state.copyMotionAllowed)
                    foreground.render(state.copy(copyMotionAllowed = false), "Initial copy", "Status", "", callbacks)
                    foreground.render(state, "Initial copy", "Status", "", callbacks)
                    foreground.render(state, "Pending copy", "Status", "", callbacks)
                    assertTrue(scheduled.getBoolean(foreground))
                    assertEquals("Initial copy", title.text.toString())

                    render.invoke(activity, true, null, null, false)
                    assertFalse(foregroundState(host, StandbyThemeId.HARDLINE).copyMotionAllowed)
                    assertFalse(scheduled.getBoolean(foreground))
                    assertTrue((changes.get(foreground) as Map<*, *>).isEmpty())
                    assertEquals(1f, title.alpha, 0f)
                    assertSame(host, activityField("standbyHost").get(activity))
                    assertSame(originalPreview, host.mediaUnderlay)

                    render.invoke(activity, false, null, null, false)
                    val resumed = foregroundState(host, StandbyThemeId.HARDLINE)
                    assertTrue(resumed.copyMotionAllowed)
                    foreground.render(resumed, "Another copy", "Status", "", callbacks)
                    assertTrue(scheduled.getBoolean(foreground))

                    selected.set(activity, StandbyThemeId.LANDSCAPE)
                    update.invoke(activity, host, snapshot)
                    render.invoke(activity, true, null, null, false)
                    assertTrue(foregroundState(host, StandbyThemeId.LANDSCAPE).copyMotionAllowed)
                    render.invoke(activity, false, null, null, false)
                    assertTrue(foregroundState(host, StandbyThemeId.LANDSCAPE).copyMotionAllowed)
                    selected.set(activity, StandbyThemeId.MATTER)
                    update.invoke(activity, host, snapshot)
                    assertFalse(foregroundState(host, StandbyThemeId.MATTER).copyMotionAllowed)
                    render.invoke(activity, true, null, null, false)
                    assertFalse(foregroundState(host, StandbyThemeId.MATTER).copyMotionAllowed)
                } catch (error: Throwable) {
                    Log.e("MainStandbyIntegration", "Hardline copy-fade body threw", error)
                    if (error is InterruptedException) interrupted = true
                    failure = error
                } finally {
                    try {
                        selected.set(activity, originalTheme)
                    } catch (stepError: Throwable) {
                        Log.e("MainStandbyIntegration", "Restore standbyTheme threw", stepError)
                        if (stepError is InterruptedException) interrupted = true
                        failure = recordThrowable(failure, stepError)
                    }
                    try {
                        render.invoke(activity, originalReady, null, null, false)
                    } catch (stepError: Throwable) {
                        Log.e("MainStandbyIntegration", "Restore media presentation threw", stepError)
                        if (stepError is InterruptedException) interrupted = true
                        failure = recordThrowable(failure, stepError)
                    }
                    try {
                        update.invoke(activity, host, snapshot)
                    } catch (stepError: Throwable) {
                        Log.e("MainStandbyIntegration", "Restore updateStandby threw", stepError)
                        if (stepError is InterruptedException) interrupted = true
                        failure = recordThrowable(failure, stepError)
                    }
                    if (interrupted) Thread.currentThread().interrupt()
                }
                failure?.let { throw it }
            }
        }
    }

    /** Observes native editor Back retiring only the public import's candidate before a separate root confirmation. */
    @Test
    fun nativeEditorBackRetiresAvailableCandidate() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "CHECK4_EDITOR must run off main" }
        checkErrorChainingSelfTest()
        checkNativeEditorImportPermission()
        checkNativeEditorPendingRecoverySelfTest()
        check(!nativeEditorDistinct("original", "original", false))
        check(!nativeEditorDistinct("original", "candidate", true))
        check(nativeEditorDistinct("original", "candidate", false))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MiraxApp
        check(app.packageName == "me.trinitrix.mirax.vanillaprobe" &&
            SessionPlatformBinding.platform.javaClass.name == "me.trinitrix.mirax.host.FakeSessionPlatform"
        ) { "CHECK4_EDITOR fixture=INCOMPLETE isolated retained fixture required" }
        val owner = app.standbyMediaOwner
        val device = UiDevice.getInstance(instrumentation)
        val setupStart = SystemClock.elapsedRealtime()
        val configurator = Configurator.getInstance()
        val savedSdkIdle = configurator.waitForIdleTimeout
        val lockedHost = AtomicReference<NativeEditorBackHost?>(null)
        val firstEdit = AtomicReference<StandbyMediaEdit?>(null)
        val editorWitness = AtomicReference<NativeEditorBackCandidate?>(null)
        val candidateFacts = AtomicReference<NativeEditorFileFacts?>(null)
        val importOperation = AtomicReference<NativeEditorImportOperation?>(null)
        val mainChanged = AtomicBoolean(false)
        val callback = ActivityLifecycleCallback { activity, stage ->
            val original = lockedHost.get()?.main
            if (original != null && activity is MainActivity &&
                ((activity !== original && (stage == Stage.CREATED || stage == Stage.RESUMED)) ||
                    (activity === original && stage in setOf(Stage.PAUSED, Stage.STOPPED, Stage.DESTROYED)))
            ) mainChanged.set(true)
        }
        var scenario: ActivityScenario<MainActivity>? = null
        var launchStarted = false
        var originalSnapshot: StandbySnapshot.Available? = null
        var protectedFiles: List<NativeEditorProtectedFile> = emptyList()
        var primaryError: Throwable? = null
        var interrupted = false

        /** Keeps original error identity while allowing independent bounded cleanup waits. @param error Body or cleanup failure. */
        fun recordError(error: Throwable) {
            val pendingInterrupt = Thread.interrupted()
            interrupted = interrupted || error is InterruptedException || pendingInterrupt
            Log.e("MainStandbyIntegration", "CHECK4_EDITOR failure=${error.javaClass.simpleName}")
            primaryError = recordThrowable(primaryError, error)
        }

        /** Retains cleanup failure without replacing a body failure. @param action One cleanup action. */
        fun attemptCleanup(action: () -> Unit) {
            if (Thread.interrupted()) interrupted = true
            try {
                action()
            } catch (error: Throwable) {
                recordError(error)
            }
        }

        /** Uses the existing queued action and the original phase clock. @param start Phase origin. @param budgetMs Phase budget in milliseconds. @param action Serialized action on the locked Main. */
        fun onHost(start: Long, budgetMs: Long = 5_000L, action: (MainActivity) -> Unit) {
            awaitActivityAction(checkNotNull(scenario), remainingBudget(start, budgetMs)) { activity ->
                remainingBudget(start, budgetMs)
                check(!mainChanged.get()) { "CHECK4_EDITOR locked Main left or was replaced" }
                checkNotNull(lockedHost.get()).verify(activity)
                action(activity)
                remainingBudget(start, budgetMs)
            }
            remainingBudget(start, budgetMs)
        }

        /** Verifies the complete Available record and original bytes/metadata without printing them. @param start Original phase clock. @param budgetMs Phase budget in milliseconds. */
        fun conserved(start: Long, budgetMs: Long = 5_000L) {
            remainingBudget(start, budgetMs)
            val snapshot = runBlocking { withTimeout(remainingBudget(start, budgetMs)) { app.standbyMediaStore.snapshot() } }
            remainingBudget(start, budgetMs)
            check(snapshot == checkNotNull(originalSnapshot)) { "CHECK4_EDITOR original Available changed" }
            protectedFiles.forEach { original ->
                remainingBudget(start, budgetMs)
                val observed = nativeEditorReadProtected(original.file)
                remainingBudget(start, budgetMs)
                check(observed.facts == original.facts && observed.bytes.contentEquals(original.bytes)) {
                    "CHECK4_EDITOR original file/preferences bytes or metadata changed"
                }
            }
            remainingBudget(start, budgetMs)
        }

        /** Checks the pre-bound directory/provenance after staging/discard has actually settled. @param operation Original import. @param start Original phase origin. @param budgetMs Phase budget in milliseconds. @return Whether no new store entry remains. */
        fun operationFilesSettled(operation: NativeEditorImportOperation, start: Long, budgetMs: Long = 5_000L): Boolean {
            remainingBudget(start, budgetMs)
            val created = nativeEditorCreatedNames(operation.directory)
            val entries = nativeEditorDirectoryNames(operation.directory)
            remainingBudget(start, budgetMs)
            return created == operation.permission.createdBefore && entries == operation.entriesBefore
        }

        /** Adopts only this pre-bound operation's real publication, including after a body timeout. @param activity Original Main. @param operation Original import authority. @return Its exact first edit, or null before/after publication. */
        fun observeImportEdit(activity: MainActivity, operation: NativeEditorImportOperation): StandbyMediaEdit? {
            check(operation.owner === owner && operation.host === lockedHost.get()) { "CHECK4_EDITOR import authority changed" }
            operation.host.verify(activity)
            val edit = ownerEdit(owner) ?: return null
            nativeEditorAssertPublication(operation, edit)
            firstEdit.compareAndSet(null, edit)
            check(firstEdit.get() === edit) { "CHECK4_EDITOR first operation candidate replaced" }
            return edit
        }

        /** Awaits normal asynchronous retirement; never mutates a transaction. @param start Original phase origin. @param requireUnarmed Whether this is the tested editor-Back phase. @param budgetMs Phase budget in milliseconds. */
        fun awaitRetired(start: Long, requireUnarmed: Boolean, budgetMs: Long = 5_000L) {
            val edit = checkNotNull(firstEdit.get()) { "CHECK4_EDITOR fixture=INCOMPLETE candidate never locked" }
            val operation = checkNotNull(importOperation.get())
            awaitPhase(start, "native editor/candidate retirement", budgetMs) {
                var retired = false
                onHost(start, budgetMs) { activity ->
                    val current = ownerEdit(owner)
                    check(current == null || current === edit) { "CHECK4_EDITOR different transaction during retirement" }
                    if (requireUnarmed) nativeEditorAssertUnarmed(activity)
                    retired = nativeEditorImportQuiescent(activity, operation, edit, editorWitness.get())
                }
                if (!retired) false else {
                    remainingBudget(start, budgetMs)
                    val absent = Files.notExists(edit.candidate.file.toPath(), LinkOption.NOFOLLOW_LINKS)
                    remainingBudget(start, budgetMs)
                    absent && operationFilesSettled(operation, start, budgetMs)
                }
            }
            conserved(start, budgetMs)
            onHost(start, budgetMs) { activity ->
                check(nativeEditorImportQuiescent(activity, operation, edit, editorWitness.get())) {
                    "CHECK4_EDITOR retirement changed before seal"
                }
                if (requireUnarmed) nativeEditorAssertUnarmed(activity)
            }
        }

        try {
            remainingBudget(setupStart)
            // Configurator is process-wide; this selector must run serially with exact restoration.
            configurator.setWaitForIdleTimeout(0L)
            remainingBudget(setupStart)
            check(configurator.waitForIdleTimeout == 0L) { "CHECK4_EDITOR SDK idle policy not applied" }
            remainingBudget(setupStart)
            val available = runBlocking { withTimeout(remainingBudget(setupStart)) { app.standbyMediaStore.snapshot() } }
                as? StandbySnapshot.Available ?: error("CHECK4_EDITOR fixture=INCOMPLETE Available required")
            remainingBudget(setupStart)
            originalSnapshot = available
            val active = available.active
            check(active.kind == StandbyMediaKind.IMAGE && active.generation == 1L &&
                active.framing == StandbyFraming(0.5f, 0.5f, 1.2f)
            ) { "CHECK4_EDITOR fixture=INCOMPLETE retained IMAGE tuple differs" }
            val originalFile = checkNotNull(active.file) { "CHECK4_EDITOR fixture=INCOMPLETE original file absent" }
            val directory = File(app.filesDir, "standby_media")
            check(!Files.isSymbolicLink(directory.toPath()) && originalFile.canonicalFile.parentFile == directory.canonicalFile) {
                "CHECK4_EDITOR fixture=INCOMPLETE original outside store"
            }
            protectedFiles = listOf(
                originalFile,
                File(app.filesDir, "datastore/standby_preferences.preferences_pb"),
                File(app.applicationInfo.dataDir, "shared_prefs/mirax_session.xml"),
            ).map { file ->
                remainingBudget(setupStart)
                nativeEditorReadProtected(file).also { remainingBudget(setupStart) }
            }
            check(protectedFiles.first().facts.size == 799L) { "CHECK4_EDITOR fixture=INCOMPLETE retained IMAGE size differs" }
            launchStarted = true
            val launchStart = SystemClock.elapsedRealtime()
            val currentScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = currentScenario
            val launchElapsed = SystemClock.elapsedRealtime() - launchStart
            check(launchElapsed in 0L until 10_000L) { "CHECK4_EDITOR fixture=INCOMPLETE launch deadline expired" }
            val readyStart = SystemClock.elapsedRealtime()
            awaitPhase(readyStart, "post-ready retained Standby") {
                awaitActivityAction(currentScenario, remainingBudget(readyStart)) { activity ->
                    remainingBudget(readyStart)
                    if (lockedHost.get() == null) {
                        val host = nativeEditorCaptureHost(activity, owner, active)
                        if (host != null) {
                            lockedHost.set(host)
                            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(callback)
                        }
                    }
                    remainingBudget(readyStart)
                }
                lockedHost.get() != null
            }
            conserved(readyStart)
            val importStart = SystemClock.elapsedRealtime()
            val importDirectory = directory.canonicalFile
            val entriesBefore = nativeEditorDirectoryNames(importDirectory)
            remainingBudget(importStart)
            val preparedImport = AtomicReference<NativeEditorImportOperation?>(null)
            onHost(importStart) { activity ->
                nativeEditorAssertUnarmed(activity)
                check(nativeEditorRetired(activity, owner, null)) { "CHECK4_EDITOR import requires settled idle owner/Main" }
                preparedImport.set(nativeEditorCaptureImport(checkNotNull(lockedHost.get()), owner, active,
                    importDirectory, entriesBefore))
            }
            val operation = checkNotNull(preparedImport.get())
            // Publish recovery authority before queueing the mutating action, not after sampling its started flag.
            importOperation.set(operation)
            onHost(importStart) { activity ->
                check(operation.permission.begin()) { "CHECK4_EDITOR import permission revoked before effect" }
                try {
                    operation.host.verify(activity)
                    nativeEditorAssertUnarmed(activity)
                    check(nativeEditorRetired(activity, owner, null) &&
                        nativeEditorRevision(owner) == operation.permission.revisionBefore &&
                        nativeEditorWatermark(importDirectory) == operation.watermarkBefore &&
                        nativeEditorCreatedNames(importDirectory) == operation.permission.createdBefore
                    ) { "CHECK4_EDITOR pre-bound import facts changed before effect" }
                    operation.permission.markInvoked()
                    owner.importDocument(operation.host.lease, Uri.fromFile(originalFile))
                    check(nativeEditorRevision(owner) == operation.permission.revisionBefore + 1L) {
                        "CHECK4_EDITOR public import admission unproven"
                    }
                } finally {
                    operation.permission.finish()
                }
            }
            awaitPhase(importStart, "same real EDITING transaction and native editor") {
                var ready = false
                onHost(importStart) { activity ->
                    nativeEditorAssertUnarmed(activity)
                    val edit = observeImportEdit(activity, operation)
                    if (edit != null) {
                        check(firstEdit.get() === edit && edit.status == StandbyMediaEditStatus.EDITING) {
                            "CHECK4_EDITOR first transaction changed or is not editable"
                        }
                        val mainEdit = activityField("mediaEdit").get(activity) as StandbyMediaEdit?
                        check(mainEdit == null || mainEdit === edit) { "CHECK4_EDITOR Main owns a different transaction" }
                        val editor = activityField("mediaEditor").get(activity) as StandbyFramingEditorView?
                        val overlay = activityField("mediaOverlay").get(activity) as View?
                        ready = mainEdit === edit && activityField("mediaEditorEdit").get(activity) === edit &&
                            !ownerStaging(owner) && !activityField("mediaStaging").getBoolean(activity) &&
                            editor != null && overlay != null && nativeEditorShown(editor) && nativeEditorShown(overlay) &&
                            overlay.parent === activity.findViewById<View>(R.id.root) && overlay.hasFocus() &&
                            editor.windowToken === activity.window.decorView.windowToken && !editor.isCommitPending
                        if (ready) {
                            val witness = NativeEditorBackCandidate(edit, checkNotNull(editor), checkNotNull(overlay))
                            editorWitness.compareAndSet(null, witness)
                            val first = checkNotNull(editorWitness.get())
                            check(first.edit === edit && first.editor === editor && first.overlay === overlay) {
                                "CHECK4_EDITOR native editor identity changed"
                            }
                        }
                    }
                }
                ready
            }
            val edit = checkNotNull(firstEdit.get())
            candidateFacts.set(nativeEditorValidateCandidate(protectedFiles.first(), edit, directory, importStart))
            remainingBudget(importStart)
            conserved(importStart)
            val backStart = SystemClock.elapsedRealtime()
            val beforeBack = nativeEditorValidateCandidate(protectedFiles.first(), edit, directory, backStart)
            check(beforeBack == candidateFacts.get()) { "CHECK4_EDITOR candidate changed before Back" }
            onHost(backStart) { activity ->
                nativeEditorAssertUnarmed(activity)
                val witness = checkNotNull(editorWitness.get())
                check(ownerEdit(owner) === edit && edit.status == StandbyMediaEditStatus.EDITING &&
                    activityField("mediaEdit").get(activity) === edit && activityField("mediaEditorEdit").get(activity) === edit &&
                    activityField("mediaEditor").get(activity) === witness.editor &&
                    activityField("mediaOverlay").get(activity) === witness.overlay &&
                    nativeEditorShown(witness.editor) && nativeEditorShown(witness.overlay) && witness.overlay.hasFocus() &&
                    !ownerStaging(owner) && !activityField("mediaStaging").getBoolean(activity)
                ) { "CHECK4_EDITOR fixture=INCOMPLETE exact editor Back prerequisites lost" }
            }
            remainingBudget(backStart)
            val editorInputBefore = SystemClock.elapsedRealtime()
            val editorAccepted = device.pressKeyCode(KeyEvent.KEYCODE_BACK)
            val editorInputAfter = SystemClock.elapsedRealtime()
            remainingBudget(backStart)
            check(editorAccepted) { "CHECK4_EDITOR native editor Back admission failed" }
            check(editorInputAfter >= editorInputBefore) { "CHECK4_EDITOR non-monotonic editor input bracket" }
            awaitRetired(backStart, true)
            Log.i("MainStandbyIntegration", "CHECK4_EDITOR nativeRetirement inputBeforeMs=$editorInputBefore " +
                "inputAfterMs=$editorInputAfter candidateAbsent=true originalConserved=true rootArmed=false")
            remainingBudget(backStart)

            val rootStart = SystemClock.elapsedRealtime()
            var prompt: String? = null
            awaitActivityAction(currentScenario, nativeEditorRootBudget(rootStart)) { activity ->
                nativeEditorRootBudget(rootStart)
                check(!mainChanged.get()) { "CHECK4_EDITOR Main changed before root Back" }
                checkNotNull(lockedHost.get()).verify(activity)
                check(nativeEditorRetired(activity, owner, editorWitness.get())) { "CHECK4_EDITOR root prerequisite not retired" }
                nativeEditorAssertUnarmed(activity)
                prompt = activity.getString(R.string.press_back_again_to_leave)
                nativeEditorRootBudget(rootStart)
            }
            nativeEditorRootBudget(rootStart)
            val presentation = RootToastPrompt.observe(device, app.packageName, checkNotNull(prompt), rootStart) {
                nativeEditorRootBudget(rootStart)
            }
            val rootInputBefore = presentation.inputBeforeElapsedMs
            val rootInputAfter = presentation.inputAfterElapsedMs
            nativeEditorRootBudget(rootStart)
            while (true) {
                var since: Long? = null
                awaitActivityAction(currentScenario, nativeEditorRootBudget(rootStart)) { activity ->
                    nativeEditorRootBudget(rootStart)
                    check(!mainChanged.get()) { "CHECK4_EDITOR Main changed after root Back" }
                    checkNotNull(lockedHost.get()).verify(activity)
                    check(nativeEditorRetired(activity, owner, editorWitness.get())) { "CHECK4_EDITOR editor returned after root Back" }
                    val root = activityField("rootExit").get(activity) as RootExitConfirmation
                    if (root.isPending) since = nativeEditorField(RootExitConfirmation::class.java, "pendingSince").get(root) as Long?
                    nativeEditorRootBudget(rootStart)
                }
                nativeEditorRootBudget(rootStart)
                val armedSince = since
                if (armedSince != null) {
                    check(armedSince in rootInputBefore..rootInputAfter && armedSince <= presentation.observedElapsedMs) {
                        "CHECK4_EDITOR root arm outside qualified input/presentation bracket"
                    }
                    awaitActivityAction(currentScenario, nativeEditorRootBudget(rootStart)) { activity ->
                        nativeEditorRootBudget(rootStart)
                        check(!mainChanged.get()) { "CHECK4_EDITOR Main changed before root seal" }
                        checkNotNull(lockedHost.get()).verify(activity)
                        val root = activityField("rootExit").get(activity) as RootExitConfirmation
                        check(root.isPending &&
                            nativeEditorField(RootExitConfirmation::class.java, "pendingSince").get(root) == armedSince &&
                            nativeEditorRetired(activity, owner, editorWitness.get())
                        ) { "CHECK4_EDITOR root/retirement witness changed before seal" }
                        nativeEditorRootBudget(rootStart)
                    }
                    nativeEditorRootBudget(rootStart)
                    Log.i("MainStandbyIntegration", "CHECK4_EDITOR rootConfirmation inputBeforeMs=$rootInputBefore " +
                        "inputAfterMs=$rootInputAfter armedMs=$armedSince inputBeforeUptimeMs=${presentation.inputBeforeUptimeMs} " +
                        "inputAfterUptimeMs=${presentation.inputAfterUptimeMs} eventUptimeMs=${presentation.eventUptimeMs} " +
                        "observedUptimeMs=${presentation.observedUptimeMs} eventObservedElapsedMs=${presentation.observedElapsedMs} " +
                        "origin=${app.packageName} class=android.widget.Toast matching=true sameMain=true")
                    nativeEditorRootBudget(rootStart)
                    break
                }
                Thread.sleep(minOf(25L, nativeEditorRootBudget(rootStart)))
            }
        } catch (error: Throwable) {
            primaryError = error
            importOperation.get()?.permission?.revoke()
            recordError(error)
        } finally {
            val bodyFailed = primaryError != null
            val operation = importOperation.get()
            operation?.permission?.revoke()
            var releaseAllowed = operation == null
            try {
                if (operation != null) {
                    attemptCleanup {
                        val start = SystemClock.elapsedRealtime()
                        awaitPhase(start, "bound import action and staging completion") {
                            var settled = false
                            onHost(start) { activity ->
                                operation.host.verify(activity)
                                settled = operation.permission.isSettled && !ownerStaging(owner) &&
                                    !activityField("mediaStaging").getBoolean(activity)
                            }
                            settled
                        }
                        var cancelEdit: StandbyMediaEdit? = null
                        onHost(start) { activity ->
                            if (ownerEdit(owner) != null) {
                                check(bodyFailed) { "CHECK4_EDITOR transaction returned after successful body" }
                                val edit = checkNotNull(observeImportEdit(activity, operation))
                                check(activityField("mediaEdit").get(activity) === edit) {
                                    "CHECK4_EDITOR fixture=INCOMPLETE operation/Main edit mismatch; no cancel"
                                }
                                val editor = activityField("mediaEditor").get(activity) as StandbyFramingEditorView?
                                val overlay = activityField("mediaOverlay").get(activity) as View?
                                if (editor != null && overlay != null) {
                                    check(activityField("mediaEditorEdit").get(activity) === edit) {
                                        "CHECK4_EDITOR fixture=INCOMPLETE operation/editor mismatch; no cancel"
                                    }
                                    editorWitness.compareAndSet(null, NativeEditorBackCandidate(edit, editor, overlay))
                                    val first = checkNotNull(editorWitness.get())
                                    check(first.edit === edit && first.editor === editor && first.overlay === overlay) {
                                        "CHECK4_EDITOR fixture=INCOMPLETE original editor replaced; no cancel"
                                    }
                                }
                                cancelEdit = edit
                            }
                        }
                        val edit = cancelEdit
                        if (edit != null) {
                            val facts = nativeEditorValidateCandidate(protectedFiles.first(), edit, operation.directory, start)
                            val previous = candidateFacts.get()
                            check(previous == null || previous == facts) { "CHECK4_EDITOR fixture=INCOMPLETE candidate replaced; no cancel" }
                            conserved(start)
                            attemptCleanup {
                                onHost(start) { activity ->
                                    check(firstEdit.get() === edit && ownerEdit(owner) === edit &&
                                        activityField("mediaEdit").get(activity) === edit && !ownerStaging(owner)
                                    ) { "CHECK4_EDITOR fixture=INCOMPLETE cancel ownership changed" }
                                    nativeEditorAssertPublication(operation, edit)
                                    check(operation.permission.claimCancel(nativeEditorRevision(owner), edit.candidate.generation,
                                        nativeEditorCreatedNames(operation.directory), edit.candidate.file.name)) {
                                        "CHECK4_EDITOR fixture=INCOMPLETE operation cancel not authorized"
                                    }
                                    owner.cancel(operation.host.lease)
                                }
                            }
                        }
                        if (firstEdit.get() != null) {
                            awaitRetired(start, false)
                        } else {
                            check(bodyFailed) { "CHECK4_EDITOR successful body has no candidate witness" }
                            awaitPhase(start, "bound import proven no-candidate quiescence") {
                                var settled = false
                                onHost(start) { activity ->
                                    settled = nativeEditorImportQuiescent(activity, operation, null, null)
                                }
                                settled && operationFilesSettled(operation, start)
                            }
                            conserved(start)
                            onHost(start) { activity ->
                                check(nativeEditorImportQuiescent(activity, operation, null, null)) {
                                    "CHECK4_EDITOR no-candidate quiescence changed before seal"
                                }
                            }
                        }
                        releaseAllowed = true
                    }
                }
                if (operation != null && !releaseAllowed) {
                    attemptCleanup {
                        val recoveryRan = AtomicBoolean(false)
                        val recovery: () -> Unit = {
                            if (recoveryRan.compareAndSet(false, true)) {
                                val wasInterrupted = Thread.interrupted()
                                var recoveryInterrupted = false
                                var recoveryFailure: Throwable? = null
                                var recoveryProven = false

                                fun recordRecoveryError(error: Throwable) {
                                    val pendingInterrupt = Thread.interrupted()
                                    recoveryInterrupted = recoveryInterrupted || error is InterruptedException || pendingInterrupt
                                    Log.e("MainStandbyIntegration", "CHECK4_EDITOR recoveryFailure=${error.javaClass.simpleName}")
                                    recoveryFailure = recordThrowable(recoveryFailure, error)
                                }

                                try {
                                    operation.permission.revoke()
                                    check(!mainChanged.get()) { "CHECK4_EDITOR locked Main left or was replaced before recovery" }
                                    val handoffStart = SystemClock.elapsedRealtime()
                                    remainingRecoveryBudget(handoffStart)
                                    awaitRecoveryPhase(handoffStart, "retained Main/lease bound import staging completion") {
                                        check(!mainChanged.get()) { "CHECK4_EDITOR locked Main left or was replaced during recovery" }
                                        remainingRecoveryBudget(handoffStart)
                                        var settled = false
                                        onHost(handoffStart, NATIVE_EDITOR_RECOVERY_BUDGET_MS) { activity ->
                                            operation.host.verify(activity)
                                            settled = operation.permission.isSettled && !ownerStaging(owner) &&
                                                !activityField("mediaStaging").getBoolean(activity)
                                        }
                                        settled
                                    }
                                    var cancelEdit: StandbyMediaEdit? = null
                                    onHost(handoffStart, NATIVE_EDITOR_RECOVERY_BUDGET_MS) { activity ->
                                        if (ownerEdit(owner) != null) {
                                            check(bodyFailed) { "CHECK4_EDITOR transaction returned after successful body" }
                                            val edit = checkNotNull(observeImportEdit(activity, operation))
                                            check(activityField("mediaEdit").get(activity) === edit) {
                                                "CHECK4_EDITOR fixture=INCOMPLETE operation/Main edit mismatch; no cancel"
                                            }
                                            val editor = activityField("mediaEditor").get(activity) as StandbyFramingEditorView?
                                            val overlay = activityField("mediaOverlay").get(activity) as View?
                                            if (editor != null && overlay != null) {
                                                check(activityField("mediaEditorEdit").get(activity) === edit) {
                                                    "CHECK4_EDITOR fixture=INCOMPLETE operation/editor mismatch; no cancel"
                                                }
                                                editorWitness.compareAndSet(null, NativeEditorBackCandidate(edit, editor, overlay))
                                                val first = checkNotNull(editorWitness.get())
                                                check(first.edit === edit && first.editor === editor && first.overlay === overlay) {
                                                    "CHECK4_EDITOR fixture=INCOMPLETE original editor replaced; no cancel"
                                                }
                                            }
                                            cancelEdit = edit
                                        }
                                    }
                                    val edit = cancelEdit
                                    if (edit != null) {
                                        val facts = nativeEditorValidateCandidate(
                                            protectedFiles.first(), edit, operation.directory,
                                            handoffStart, NATIVE_EDITOR_RECOVERY_BUDGET_MS,
                                        )
                                        val previous = candidateFacts.get()
                                        check(previous == null || previous == facts) {
                                            "CHECK4_EDITOR fixture=INCOMPLETE candidate replaced; no cancel"
                                        }
                                        conserved(handoffStart, NATIVE_EDITOR_RECOVERY_BUDGET_MS)
                                        onHost(handoffStart, NATIVE_EDITOR_RECOVERY_BUDGET_MS) { activity ->
                                            check(firstEdit.get() === edit && ownerEdit(owner) === edit &&
                                                activityField("mediaEdit").get(activity) === edit && !ownerStaging(owner)
                                            ) { "CHECK4_EDITOR fixture=INCOMPLETE cancel ownership changed" }
                                            nativeEditorAssertPublication(operation, edit)
                                            check(operation.permission.claimCancel(nativeEditorRevision(owner), edit.candidate.generation,
                                                nativeEditorCreatedNames(operation.directory), edit.candidate.file.name)) {
                                                "CHECK4_EDITOR fixture=INCOMPLETE operation cancel not authorized"
                                            }
                                            owner.cancel(operation.host.lease)
                                        }
                                    }
                                    if (firstEdit.get() != null) {
                                        awaitRetired(handoffStart, false, NATIVE_EDITOR_RECOVERY_BUDGET_MS)
                                    } else {
                                        check(bodyFailed) { "CHECK4_EDITOR successful body has no candidate witness" }
                                        awaitRecoveryPhase(handoffStart, "retained Main/lease no-candidate quiescence") {
                                            check(!mainChanged.get()) { "CHECK4_EDITOR locked Main left or was replaced during recovery" }
                                            remainingRecoveryBudget(handoffStart)
                                            var settled = false
                                            onHost(handoffStart, NATIVE_EDITOR_RECOVERY_BUDGET_MS) { activity ->
                                                settled = nativeEditorImportQuiescent(activity, operation, null, null)
                                            }
                                            settled && operationFilesSettled(operation, handoffStart, NATIVE_EDITOR_RECOVERY_BUDGET_MS)
                                        }
                                        conserved(handoffStart, NATIVE_EDITOR_RECOVERY_BUDGET_MS)
                                        onHost(handoffStart, NATIVE_EDITOR_RECOVERY_BUDGET_MS) { activity ->
                                            check(nativeEditorImportQuiescent(activity, operation, null, null)) {
                                                "CHECK4_EDITOR no-candidate quiescence changed before seal"
                                            }
                                        }
                                    }
                                    recoveryProven = true
                                } catch (error: Throwable) {
                                    recordRecoveryError(error)
                                } finally {
                                    try {
                                        try {
                                            nativeEditorRemoveObserver(callback)
                                            check(!mainChanged.get()) {
                                                "CHECK4_EDITOR locked Main left or was replaced before recovery close"
                                            }
                                        } catch (error: Throwable) {
                                            recordRecoveryError(error)
                                        }
                                        try {
                                            if (recoveryProven) {
                                                val current = scenario
                                                if (current != null) current.close() else check(!launchStarted) {
                                                    "CHECK4_EDITOR fixture=INCOMPLETE launch returned no scenario handle"
                                                }
                                            }
                                        } catch (error: Throwable) {
                                            recordRecoveryError(error)
                                        }
                                        try {
                                            val idleStart = SystemClock.elapsedRealtime()
                                            configurator.setWaitForIdleTimeout(savedSdkIdle)
                                            remainingBudget(idleStart)
                                            check(configurator.waitForIdleTimeout == savedSdkIdle) {
                                                "CHECK4_EDITOR saved SDK idle policy not restored"
                                            }
                                            remainingBudget(idleStart)
                                        } catch (error: Throwable) {
                                            recordRecoveryError(error)
                                        }
                                    } finally {
                                        if (wasInterrupted || recoveryInterrupted) Thread.currentThread().interrupt()
                                    }
                                }
                                recoveryFailure?.let { throw it }
                            }
                        }
                        if (!pendingNativeEditorRecovery.compareAndSet(null, recovery)) {
                            try {
                                nativeEditorRemoveObserver(callback)
                            } finally {
                                configurator.setWaitForIdleTimeout(savedSdkIdle)
                            }
                            error("CHECK4_EDITOR pending native editor recovery already registered")
                        }
                        error("CHECK4_EDITOR containment INCOMPLETE; handoff registered for retained Main/lease recovery")
                    }
                } else {
                    attemptCleanup {
                        // Queue even after a registration timeout: an already-begun action cannot be cancelled.
                        nativeEditorRemoveObserver(callback)
                        check(!mainChanged.get()) { "CHECK4_EDITOR locked Main left or was replaced before cleanup" }
                    }
                    attemptCleanup {
                        check(releaseAllowed) {
                            "CHECK4_EDITOR containment INCOMPLETE; retain original Main/lease for scoped operation recovery; no close/stop substitute"
                        }
                        val current = scenario
                        if (current != null) current.close() else check(!launchStarted) {
                            "CHECK4_EDITOR fixture=INCOMPLETE launch returned no scenario handle"
                        }
                    }
                    attemptCleanup {
                        val start = SystemClock.elapsedRealtime()
                        configurator.setWaitForIdleTimeout(savedSdkIdle)
                        remainingBudget(start)
                        check(configurator.waitForIdleTimeout == savedSdkIdle) { "CHECK4_EDITOR saved SDK idle policy not restored" }
                        remainingBudget(start)
                    }
                }
            } finally {
                if (Thread.interrupted()) interrupted = true
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        primaryError?.let { throw it }
    }

    /** Arranges retired delivery after restart, then observes three real frame batches for each owner. */
    @Test
    fun retiredFrameCallbacksCannotAffectRestartedRuns() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "MOTION_F1 must run off main" }
        checkErrorChainingSelfTest()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MiraxApp
        check(app.packageName == "me.trinitrix.mirax.vanillaprobe" &&
            SessionPlatformBinding.platform.javaClass.name == "me.trinitrix.mirax.host.FakeSessionPlatform"
        ) { "MOTION_F1 fixture=INCOMPLETE isolated regular Main required" }
        val owner = app.standbyMediaOwner
        val setupStart = SystemClock.elapsedRealtime()
        val configurator = Configurator.getInstance()
        val savedSdkIdle = configurator.waitForIdleTimeout
        val hardlineEntries = RetiredFrameEntries()
        val landscapeEntries = RetiredFrameEntries()
        val matterEntries = RetiredFrameEntries()
        val motionEntries = RetiredFrameEntries()
        var scenario: ActivityScenario<MainActivity>? = null
        var launchStarted = false
        var lockedHost: NativeEditorBackHost? = null
        var content: ViewGroup? = null
        var container: FrameLayout? = null
        var hardline: HardlineSceneView? = null
        var landscape: LandscapeSceneView? = null
        var matter: MatterParticleView? = null
        var motion: AndroidStandbyMotion? = null
        var drift: StandbyOledDriftSource? = null
        var primaryError: Throwable? = null
        var interrupted = false

        /** Retains the original failure and interruption while allowing independent cleanup. @param error Observed failure. */
        fun recordError(error: Throwable) {
            val pendingInterrupt = Thread.interrupted()
            interrupted = interrupted || error is InterruptedException || pendingInterrupt
            Log.e("MainStandbyIntegration", "MOTION_F1 failure", error)
            primaryError = recordThrowable(primaryError, error)
        }

        /** Attempts one cleanup without hiding an earlier failure. @param action Independent cleanup. */
        fun attemptCleanup(action: () -> Unit) {
            if (Thread.interrupted()) interrupted = true
            try {
                action()
            } catch (error: Throwable) {
                recordError(error)
            }
        }

        /** Checks the unchanged regular fixture on main. @param activity Original Main. */
        fun verify(activity: MainActivity) {
            checkNotNull(lockedHost).verify(activity)
            check(app.session.snapshot().phase == ScreenPhase.FROZEN && nativeEditorRetired(activity, owner, null)) {
                "MOTION_F1 fixture=INCOMPLETE unarmed FROZEN retained Main required"
            }
            nativeEditorAssertUnarmed(activity)
        }

        /** Uses the caller's unchanged phase origin around the queued action. @param start Original phase origin. @param action Main-thread action. */
        fun onHost(start: Long, action: (MainActivity) -> Unit) {
            remainingBudget(start)
            awaitActivityAction(checkNotNull(scenario), remainingBudget(start)) { activity ->
                remainingBudget(start)
                verify(activity)
                action(activity)
                remainingBudget(start)
            }
            remainingBudget(start)
        }

        try {
            remainingBudget(setupStart)
            configurator.setWaitForIdleTimeout(0L)
            remainingBudget(setupStart)
            check(configurator.waitForIdleTimeout == 0L) { "MOTION_F1 SDK idle policy not applied" }
            Log.i("MainStandbyIntegration", "MOTION_F1 SDK saved=$savedSdkIdle temporary=0")
            val available = runBlocking { withTimeout(remainingBudget(setupStart)) { app.standbyMediaStore.snapshot() } }
                as? StandbySnapshot.Available ?: error("MOTION_F1 fixture=INCOMPLETE retained Available required")
            remainingBudget(setupStart)
            launchStarted = true
            val launchStart = SystemClock.elapsedRealtime()
            val currentScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = currentScenario
            check(SystemClock.elapsedRealtime() - launchStart in 0L until 10_000L) { "MOTION_F1 launch deadline expired" }
            val readyStart = SystemClock.elapsedRealtime()
            awaitPhase(readyStart, "MOTION_F1 retained Main readiness") {
                awaitActivityAction(currentScenario, remainingBudget(readyStart)) { activity ->
                    remainingBudget(readyStart)
                    if (lockedHost == null) lockedHost = nativeEditorCaptureHost(activity, owner, available.active)
                    if (lockedHost != null) verify(activity)
                    remainingBudget(readyStart)
                }
                lockedHost != null
            }
            val rootStart = SystemClock.elapsedRealtime()
            awaitActivityAction(currentScenario, nativeEditorRootBudget(rootStart)) { activity ->
                nativeEditorRootBudget(rootStart)
                verify(activity)
                nativeEditorRootBudget(rootStart)
            }
            nativeEditorRootBudget(rootStart)
            val containerStart = SystemClock.elapsedRealtime()
            onHost(containerStart) { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                check(nativeEditorShown(root)) { "MOTION_F1 fixture=INCOMPLETE content root unavailable" }
                content = root
                val owned = FrameLayout(activity).apply {
                    isClickable = false
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
                container = owned
                root.addView(owned, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }

            val hardlineStart = SystemClock.elapsedRealtime()
            onHost(hardlineStart) { activity ->
                val scene = HardlineSceneView(activity)
                hardline = scene
                scene.setProjecting(true)
                scene.clockUptimeMillis = { retiredFrameEntry(hardlineEntries); SystemClock.uptimeMillis() }
                checkNotNull(container).addView(scene, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            var settledBounds: Rect? = null
            awaitPhase(hardlineStart, "MOTION_F1 Hardline settled geometry") {
                var settled = false
                onHost(hardlineStart) {
                    val scene = checkNotNull(hardline)
                    if (nativeEditorShown(scene) && !scene.isLayoutRequested && scene.drawEvidence.observed) {
                        val bounds = retiredFrameBounds(scene)
                        settled = bounds == settledBounds
                        settledBounds = bounds
                    } else settledBounds = null
                }
                settled
            }
            onHost(hardlineStart) { activity ->
                val scene = checkNotNull(hardline)
                scene.setProjecting(false)
                val old = retiredSceneState(scene, hardlineEntries).callback
                scene.setProjecting(true)
                queueRetiredFrameObserver(hardlineEntries)
                scene.setProjecting(false)
                val restarted = retiredSceneState(scene, hardlineEntries)
                check(restarted.callback !== old && restarted.scheduled && !restarted.projecting) { "MOTION_F1 Hardline run not replaced" }
                old.doFrame(System.nanoTime())
                assertEquals(restarted, retiredSceneState(scene, hardlineEntries))
                scene.setProjecting(false)
                scene.javaClass.getDeclaredMethod("scheduleFrameIfNeeded").apply { isAccessible = true }.invoke(scene)
                assertEquals(restarted, retiredSceneState(scene, hardlineEntries))
                hardlineEntries.start = hardlineStart
                hardlineEntries.readback = {
                    verify(activity)
                    retiredSceneContinued(restarted, retiredSceneState(scene, hardlineEntries))
                }
                hardlineEntries.observing = true
            }
            awaitRetiredFrames(hardlineEntries, hardlineStart)
            onHost(hardlineStart) {
                checkNotNull(hardline).setProjecting(true)
                retiredFrameRemove(hardline, container)
            }

            val landscapeStart = SystemClock.elapsedRealtime()
            onHost(landscapeStart) { activity ->
                val scene = LandscapeSceneView(activity)
                landscape = scene
                scene.setProjecting(true)
                scene.clockUptimeMillis = { retiredFrameEntry(landscapeEntries); SystemClock.uptimeMillis() }
                checkNotNull(container).addView(scene, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            settledBounds = null
            awaitPhase(landscapeStart, "MOTION_F1 Landscape settled geometry") {
                var settled = false
                onHost(landscapeStart) {
                    val scene = checkNotNull(landscape)
                    if (nativeEditorShown(scene) && !scene.isLayoutRequested && scene.drawEvidence.observed) {
                        val bounds = retiredFrameBounds(scene)
                        settled = bounds == settledBounds
                        settledBounds = bounds
                    } else settledBounds = null
                }
                settled
            }
            onHost(landscapeStart) { activity ->
                val scene = checkNotNull(landscape)
                scene.setProjecting(false)
                val old = retiredSceneState(scene, landscapeEntries).callback
                scene.setProjecting(true)
                queueRetiredFrameObserver(landscapeEntries)
                scene.setProjecting(false)
                val restarted = retiredSceneState(scene, landscapeEntries)
                check(restarted.callback !== old && restarted.scheduled && !restarted.projecting) { "MOTION_F1 Landscape run not replaced" }
                old.doFrame(System.nanoTime())
                assertEquals(restarted, retiredSceneState(scene, landscapeEntries))
                scene.setProjecting(false)
                scene.javaClass.getDeclaredMethod("scheduleFrameIfNeeded").apply { isAccessible = true }.invoke(scene)
                assertEquals(restarted, retiredSceneState(scene, landscapeEntries))
                landscapeEntries.start = landscapeStart
                landscapeEntries.readback = {
                    verify(activity)
                    retiredSceneContinued(restarted, retiredSceneState(scene, landscapeEntries))
                }
                landscapeEntries.observing = true
            }
            awaitRetiredFrames(landscapeEntries, landscapeStart)
            onHost(landscapeStart) {
                checkNotNull(landscape).setProjecting(true)
                retiredFrameRemove(landscape, container)
            }

            val matterStart = SystemClock.elapsedRealtime()
            onHost(matterStart) { activity ->
                val scene = MatterParticleView(activity)
                matter = scene
                scene.setProjecting(true)
                scene.clockUptimeMillis = { retiredFrameEntry(matterEntries); SystemClock.uptimeMillis() }
                checkNotNull(container).addView(scene, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            settledBounds = null
            awaitPhase(matterStart, "MOTION_F1 Matter settled geometry") {
                var settled = false
                onHost(matterStart) {
                    val scene = checkNotNull(matter)
                    if (nativeEditorShown(scene) && !scene.isLayoutRequested && scene.drawEvidence.observed) {
                        val bounds = retiredFrameBounds(scene)
                        settled = bounds == settledBounds
                        settledBounds = bounds
                    } else settledBounds = null
                }
                settled
            }
            onHost(matterStart) { activity ->
                val scene = checkNotNull(matter)
                scene.setProjecting(false)
                val old = retiredSceneState(scene, matterEntries).callback
                scene.setProjecting(true)
                queueRetiredFrameObserver(matterEntries)
                scene.setProjecting(false)
                val restarted = retiredSceneState(scene, matterEntries)
                check(restarted.callback !== old && restarted.scheduled && !restarted.projecting) { "MOTION_F1 Matter run not replaced" }
                old.doFrame(System.nanoTime())
                assertEquals(restarted, retiredSceneState(scene, matterEntries))
                scene.setProjecting(false)
                scene.javaClass.getDeclaredMethod("scheduleFrameIfNeeded").apply { isAccessible = true }.invoke(scene)
                assertEquals(restarted, retiredSceneState(scene, matterEntries))
                matterEntries.start = matterStart
                matterEntries.readback = {
                    verify(activity)
                    retiredSceneContinued(restarted, retiredSceneState(scene, matterEntries))
                }
                matterEntries.observing = true
            }
            awaitRetiredFrames(matterEntries, matterStart)
            onHost(matterStart) {
                checkNotNull(matter).setProjecting(true)
                retiredFrameRemove(matter, container)
            }

            val motionStart = SystemClock.elapsedRealtime()
            onHost(motionStart) { activity ->
                val temporary = AndroidStandbyMotion(activity, host = { retiredFrameEntry(motionEntries); null }, displayDegrees = { 0 })
                motion = temporary
                val source = nativeEditorField(AndroidStandbyMotion::class.java, "drift").get(temporary) as StandbyOledDriftSource
                drift = source
                source.start()
                val old = retiredMotionState(temporary, motionEntries).callback
                source.stop()
                queueRetiredFrameObserver(motionEntries)
                source.start()
                val restarted = retiredMotionState(temporary, motionEntries)
                check(restarted.callback !== old && restarted.running && restarted.previousFrameNanos == 0L) { "MOTION_F1 drift run not replaced" }
                old.doFrame(System.nanoTime())
                assertEquals(restarted, retiredMotionState(temporary, motionEntries))
                source.start()
                assertEquals(restarted, retiredMotionState(temporary, motionEntries))
                check(!temporary.runtime.isHoldingSensors()) { "MOTION_F1 temporary motion acquired sensors" }
                motionEntries.start = motionStart
                motionEntries.readback = {
                    verify(activity)
                    val continued = retiredMotionState(temporary, motionEntries)
                    assertSame(restarted.callback, continued.callback)
                    check(continued.running && continued.previousFrameNanos > restarted.previousFrameNanos &&
                        continued.reads == restarted.reads + 3L && !temporary.runtime.isHoldingSensors()
                    ) { "MOTION_F1 drift continuation/state changed" }
                }
                motionEntries.observing = true
            }
            awaitRetiredFrames(motionEntries, motionStart)
            onHost(motionStart) {
                checkNotNull(drift).stop()
                val temporary = checkNotNull(motion)
                check(nativeEditorField(AndroidStandbyMotion::class.java, "frameCallback").get(temporary) == null &&
                    !nativeEditorField(AndroidStandbyMotion::class.java, "framesRunning").getBoolean(temporary) &&
                    nativeEditorField(AndroidStandbyMotion::class.java, "previousFrameNanos").getLong(temporary) == 0L
                ) { "MOTION_F1 drift did not retire" }
            }
            val finalStart = SystemClock.elapsedRealtime()
            val after = runBlocking { withTimeout(remainingBudget(finalStart)) { app.standbyMediaStore.snapshot() } }
            remainingBudget(finalStart)
            assertEquals(available, after)
            onHost(finalStart) { Log.i("MainStandbyIntegration", "MOTION_F1 arrangedOwners=4 nativeBucketsEach=3 entriesEach=1") }
        } catch (error: Throwable) {
            recordError(error)
        } finally {
            try {
                attemptCleanup { retiredFrameCleanup { cleanupRetiredFrameObserver(hardlineEntries) } }
                attemptCleanup { retiredFrameCleanup { cleanupRetiredFrameObserver(landscapeEntries) } }
                attemptCleanup { retiredFrameCleanup { cleanupRetiredFrameObserver(matterEntries) } }
                attemptCleanup { retiredFrameCleanup { cleanupRetiredFrameObserver(motionEntries) } }
                attemptCleanup { retiredFrameCleanup { drift?.stop() } }
                attemptCleanup { retiredFrameCleanup { hardline?.setProjecting(true) } }
                attemptCleanup { retiredFrameCleanup { retiredFrameRemove(hardline, container) } }
                attemptCleanup { retiredFrameCleanup { landscape?.setProjecting(true) } }
                attemptCleanup { retiredFrameCleanup { retiredFrameRemove(landscape, container) } }
                attemptCleanup { retiredFrameCleanup { matter?.setProjecting(true) } }
                attemptCleanup { retiredFrameCleanup { retiredFrameRemove(matter, container) } }
                attemptCleanup {
                    retiredFrameCleanup {
                        container?.let { owned ->
                            check(owned.parent == null || owned.parent === content) { "MOTION_F1 container ownership changed" }
                            (owned.parent as? ViewGroup)?.removeView(owned)
                            check(owned.parent == null && !owned.isAttachedToWindow && owned.childCount == 0) { "MOTION_F1 container cleanup incomplete" }
                        }
                    }
                }
                attemptCleanup {
                    val start = SystemClock.elapsedRealtime()
                    val current = scenario
                    if (current != null) {
                        current.close()
                        remainingBudget(start)
                        check(current.state == androidx.lifecycle.Lifecycle.State.DESTROYED) { "MOTION_F1 Main unclosed" }
                        remainingBudget(start)
                    } else check(!launchStarted) { "MOTION_F1 fixture=INCOMPLETE launch returned no scenario" }
                }
                attemptCleanup {
                    val start = SystemClock.elapsedRealtime()
                    configurator.setWaitForIdleTimeout(savedSdkIdle)
                    remainingBudget(start)
                    check(configurator.waitForIdleTimeout == savedSdkIdle) { "MOTION_F1 saved SDK idle policy not restored" }
                    remainingBudget(start)
                    Log.i("MainStandbyIntegration", "MOTION_F1 SDK restored=$savedSdkIdle")
                }
            } finally {
                if (Thread.interrupted()) interrupted = true
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
        primaryError?.let { throw it }
    }

    /** Main-owned hook counts; only the latch/failure are consumed off main after completion. */
    private class RetiredFrameEntries {
        var reads = 0L
        var observing = false
        var observer: Choreographer.FrameCallback? = null
        var currentFrameTimeNanos: Long? = null
        var start = 0L
        val buckets = linkedMapOf<Long, Int>()
        var readback: (() -> Unit)? = null
        var failure: Throwable? = null
        val completed = CountDownLatch(1)
    }

    /** Immutable scene facts captured on main before and after arranged retired delivery. */
    private data class RetiredSceneState(
        val callback: Choreographer.FrameCallback,
        val scheduled: Boolean,
        val projecting: Boolean,
        val elapsed: Double,
        val lastUptimeMillis: Long?,
        val reads: Long,
        val hardlineCount: Long?,
        val width: Int,
        val height: Int,
        val bounds: Rect,
    )

    /** Immutable temporary drift facts; its host supplier always returns null. */
    private data class RetiredMotionState(
        val callback: Choreographer.FrameCallback,
        val running: Boolean,
        val previousFrameNanos: Long,
        val reads: Long,
    )

    /** Queues the public prefix before the replacement owner is posted. @param entries Test-local owner counter, still disabled. */
    private fun queueRetiredFrameObserver(entries: RetiredFrameEntries) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "MOTION_F1 observer setup off main" }
        check(entries.observer == null && !entries.observing && entries.currentFrameTimeNanos == null &&
            entries.buckets.isEmpty() && entries.failure == null
        ) { "MOTION_F1 observer already owned or used" }
        val observer = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (entries.observer !== this || !entries.observing || entries.failure != null) return
                try {
                    remainingBudget(entries.start)
                    val previous = entries.currentFrameTimeNanos
                    check(frameTimeNanos > 0L && (previous == null || frameTimeNanos > previous)) {
                        "MOTION_F1 non-positive/non-monotonic native prefix timestamp"
                    }
                    if (previous != null) {
                        check(entries.buckets[previous] == 1) { "MOTION_F1 preceding native bucket incomplete" }
                    }
                    check(entries.buckets.size < 3) { "MOTION_F1 fourth native prefix before readback" }
                    entries.buckets[frameTimeNanos] = 0
                    entries.currentFrameTimeNanos = frameTimeNanos
                    if (entries.observer === this && entries.observing) {
                        Choreographer.getInstance().postFrameCallback(this)
                    }
                } catch (error: Throwable) {
                    failRetiredFrameObservation(entries, error)
                }
            }
        }
        entries.observer = observer
        try {
            Choreographer.getInstance().postFrameCallback(observer)
        } catch (error: Throwable) {
            failRetiredFrameObservation(entries, error)
            throw checkNotNull(entries.failure)
        }
    }

    /** Invalidates before removal so extracted old observer deliveries cannot repost. @param entries Owned observation. */
    private fun retireRetiredFrameObserver(entries: RetiredFrameEntries) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "MOTION_F1 observer retirement off main" }
        val observer = entries.observer
        entries.observer = null
        entries.observing = false
        try {
            if (observer != null) Choreographer.getInstance().removeFrameCallback(observer)
        } finally {
            entries.currentFrameTimeNanos = null
        }
    }

    /** Preserves native failures and independently retires/signals even if removal fails. @param entries Failed observation. @param error Original failure. */
    private fun failRetiredFrameObservation(entries: RetiredFrameEntries, error: Throwable) {
        Log.e("MainStandbyIntegration", "MOTION_F1 native frame observation failed", error)
        entries.failure = recordThrowable(entries.failure, error)
        try {
            retireRetiredFrameObserver(entries)
        } catch (cleanupError: Throwable) {
            Log.e("MainStandbyIntegration", "MOTION_F1 observer removal failed", cleanupError)
            entries.failure = recordThrowable(entries.failure, cleanupError)
        } finally {
            entries.readback = null
            entries.completed.countDown()
        }
    }

    /** Signals cancellation independently of observer-removal success. @param entries This case's observation. */
    private fun cleanupRetiredFrameObserver(entries: RetiredFrameEntries) {
        try {
            retireRetiredFrameObserver(entries)
        } finally {
            entries.readback = null
            entries.completed.countDown()
        }
    }

    /** Accepts one owner hook in an existing public-prefix bucket. @param entries Test-local owner counter. */
    private fun retiredFrameEntry(entries: RetiredFrameEntries) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "MOTION_F1 frame hook off main" }
        entries.reads++
        if (!entries.observing) return
        try {
            remainingBudget(entries.start)
            check(entries.failure == null) { "MOTION_F1 failed observation received owner hook" }
            val observer = checkNotNull(entries.observer) { "MOTION_F1 owner hook has no prefix observer" }
            val frameTime = checkNotNull(entries.currentFrameTimeNanos) { "MOTION_F1 owner hook preceded native prefix" }
            check(frameTime > 0L && entries.buckets[frameTime] == 0) { "MOTION_F1 duplicate/missing current native bucket" }
            entries.buckets[frameTime] = 1
            if (entries.buckets.size == 3) {
                check(Handler(Looper.getMainLooper()).post {
                    if (entries.observer !== observer || !entries.observing || entries.failure != null) return@post
                    try {
                        remainingBudget(entries.start)
                        val frozen = entries.buckets.toMap()
                        retireRetiredFrameObserver(entries)
                        check(frozen.size == 3 && frozen.values.all { it == 1 } &&
                            frozen.keys.zipWithNext().all { (before, after) -> after > before }
                        ) { "MOTION_F1 duplicate/non-successive observed frame entries" }
                        checkNotNull(entries.readback) { "MOTION_F1 frame readback missing" }.invoke()
                        remainingBudget(entries.start)
                    } catch (error: Throwable) {
                        failRetiredFrameObservation(entries, error)
                    } finally {
                        entries.readback = null
                        entries.completed.countDown()
                    }
                }) { "MOTION_F1 main rejected drained readback" }
            }
        } catch (error: Throwable) {
            failRetiredFrameObservation(entries, error)
        }
    }

    /** Waits off main using the original owner phase; no current callback is manually invoked. @param entries Owner's counter. @param start Original phase origin. */
    private fun awaitRetiredFrames(entries: RetiredFrameEntries, start: Long) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "MOTION_F1 frame wait on main" }
        check(entries.completed.await(remainingBudget(start), TimeUnit.MILLISECONDS)) { "MOTION_F1 native continuation timed out" }
        entries.failure?.let { throw it }
        remainingBudget(start)
    }

    /** Reads positively clipped attached bounds without changing geometry. @param view Owned scene. @return Current visible bounds. */
    private fun retiredFrameBounds(view: View): Rect {
        check(nativeEditorShown(view) && !view.isLayoutRequested) { "MOTION_F1 fixture=INCOMPLETE scene geometry unavailable" }
        return Rect().also { check(view.getGlobalVisibleRect(it) && !it.isEmpty) { "MOTION_F1 scene clipping unavailable" } }
    }

    /** Captures only the three named scene owners' unchanged private fields. @param view Owned scene. @param entries Its clock counter. @return Current live-run facts. */
    private fun retiredSceneState(view: View, entries: RetiredFrameEntries): RetiredSceneState {
        check(Looper.myLooper() == Looper.getMainLooper()) { "MOTION_F1 scene read off main" }
        check(view is HardlineSceneView || view is LandscapeSceneView || view is MatterParticleView) { "MOTION_F1 unexpected scene" }
        val type = view.javaClass
        val elapsed = if (view is MatterParticleView) "activeElapsedMillis" else "activeElapsedSeconds"
        return RetiredSceneState(
            checkNotNull(nativeEditorField(type, "frameCallback").get(view) as? Choreographer.FrameCallback) { "MOTION_F1 live scene callback absent" },
            nativeEditorField(type, "frameScheduled").getBoolean(view),
            nativeEditorField(type, "projecting").getBoolean(view),
            nativeEditorField(type, elapsed).getDouble(view),
            nativeEditorField(type, "lastUptimeMillis").get(view) as Long?,
            entries.reads,
            if (view is HardlineSceneView) nativeEditorField(type, "animationCallbackCount").getLong(view) else null,
            view.width, view.height, retiredFrameBounds(view),
        )
    }

    /** Checks continuation without treating a pending Boolean alone as queue-count proof. @param before Restarted run. @param after Drained third native batch. */
    private fun retiredSceneContinued(before: RetiredSceneState, after: RetiredSceneState) {
        assertSame(before.callback, after.callback)
        check(after.scheduled && !after.projecting && after.elapsed > before.elapsed &&
            checkNotNull(after.lastUptimeMillis) > (before.lastUptimeMillis ?: 0L) && after.reads == before.reads + 3L &&
            after.width == before.width && after.height == before.height && after.bounds == before.bounds &&
            (before.hardlineCount == null || after.hardlineCount == before.hardlineCount + 3L)
        ) { "MOTION_F1 scene continuation/state changed" }
    }

    /** Reads the temporary Motion owner only, never Main's sensor runtime. @param motion Temporary owner. @param entries Its null-host counter. @return Current live drift facts. */
    private fun retiredMotionState(motion: AndroidStandbyMotion, entries: RetiredFrameEntries): RetiredMotionState {
        check(Looper.myLooper() == Looper.getMainLooper()) { "MOTION_F1 drift read off main" }
        val type = AndroidStandbyMotion::class.java
        return RetiredMotionState(
            checkNotNull(nativeEditorField(type, "frameCallback").get(motion) as? Choreographer.FrameCallback) { "MOTION_F1 live drift callback absent" },
            nativeEditorField(type, "framesRunning").getBoolean(motion),
            nativeEditorField(type, "previousFrameNanos").getLong(motion),
            entries.reads,
        )
    }

    /** Removes only this case's scene and verifies callback retirement. @param scene Owned scene, if constructed. @param container Owned parent. */
    private fun retiredFrameRemove(scene: View?, container: FrameLayout?) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "MOTION_F1 scene removal off main" }
        if (scene == null) return
        check(scene.parent == null || scene.parent === container) { "MOTION_F1 scene ownership changed" }
        container?.removeView(scene)
        check(scene.parent == null && !scene.isAttachedToWindow &&
            nativeEditorField(scene.javaClass, "frameCallback").get(scene) == null &&
            !nativeEditorField(scene.javaClass, "frameScheduled").getBoolean(scene)
        ) { "MOTION_F1 scene cleanup incomplete" }
    }

    /** Queues cleanup even after scenario death without cancelling running work. @param start Original cleanup phase origin. @param action Owned-object cleanup on main. */
    private fun retiredFrameCleanup(start: Long = SystemClock.elapsedRealtime(), action: () -> Unit) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "MOTION_F1 cleanup wait on main" }
        val pending = AtomicBoolean(true)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val handler = Handler(Looper.getMainLooper())
        val task = Runnable {
            if (pending.compareAndSet(true, false)) {
                try {
                    remainingBudget(start)
                    action()
                    remainingBudget(start)
                } catch (error: Throwable) {
                    Log.e("MainStandbyIntegration", "MOTION_F1 cleanup failed", error)
                    failure.set(error)
                } finally {
                    completed.countDown()
                }
            }
        }
        check(handler.post(task)) { "MOTION_F1 main rejected cleanup" }
        try {
            check(completed.await(remainingBudget(start), TimeUnit.MILLISECONDS)) { "MOTION_F1 cleanup timed out" }
        } catch (error: Throwable) {
            if (pending.compareAndSet(true, false)) handler.removeCallbacks(task)
            throw error
        }
        failure.get()?.let { throw it }
        remainingBudget(start)
    }

    /** C-only queued-effect permission; revocation never declares a running import cancelled. */
    private enum class NativeEditorImportStage { PENDING, REVOKED, RUNNING, COMPLETE }

    /** Pre-bound revision/provenance authority for one import and at most one public failure cancel. */
    private class NativeEditorImportPermission(
        val revisionBefore: Long,
        val generation: Long,
        val createdBefore: Set<String>,
    ) {
        private val stage = AtomicReference(NativeEditorImportStage.PENDING)
        private val invoked = AtomicBoolean(false)
        private val cancelPending = AtomicBoolean(true)

        /** Whether no queued mutating action can still begin or remain inside its effect. */
        val isSettled: Boolean
            get() = stage.get().let { it == NativeEditorImportStage.REVOKED || it == NativeEditorImportStage.COMPLETE }

        /** Whether the single public import may have produced asynchronous work. */
        val wasInvoked: Boolean get() = invoked.get()

        /** Revokes only the queued-not-started effect. */
        fun revoke() {
            stage.compareAndSet(NativeEditorImportStage.PENDING, NativeEditorImportStage.REVOKED)
        }

        /** @return Whether this caller claimed the one mutating effect. */
        fun begin(): Boolean = stage.compareAndSet(NativeEditorImportStage.PENDING, NativeEditorImportStage.RUNNING)

        /** Records possible asynchronous work before calling the public API. */
        fun markInvoked() {
            check(stage.get() == NativeEditorImportStage.RUNNING && invoked.compareAndSet(false, true)) {
                "CHECK4_EDITOR duplicate/unowned import effect"
            }
        }

        /** Seals the synchronous action even when its public call throws. */
        fun finish() {
            check(stage.compareAndSet(NativeEditorImportStage.RUNNING, NativeEditorImportStage.COMPLETE)) {
                "CHECK4_EDITOR import effect completion unproven"
            }
        }

        /** @param revision Actual owner revision. @param candidateGeneration Actual stage generation. @param created Actual created basenames. @param name Actual candidate basename. @return Whether this is the sole publication attributable to this import. */
        fun owns(revision: Long, candidateGeneration: Long, created: Set<String>, name: String): Boolean =
            stage.get() == NativeEditorImportStage.COMPLETE && invoked.get() && revision == revisionBefore + 1L &&
                candidateGeneration == generation && name !in createdBefore && created == createdBefore + name

        /** @param revision Actual owner revision. @param candidateGeneration Actual stage generation. @param created Actual created basenames. @param name Actual candidate basename. @return Whether this exact operation may receive its one public failure cancel. */
        fun claimCancel(revision: Long, candidateGeneration: Long, created: Set<String>, name: String): Boolean =
            owns(revision, candidateGeneration, created, name) && cancelPending.compareAndSet(true, false)
    }

    /** Immutable original authorities and pre-effect store facts; cleanup waiting never creates this record. */
    private class NativeEditorImportOperation(
        val host: NativeEditorBackHost,
        val owner: StandbyMediaOwner,
        val active: StandbyActive,
        val directory: File,
        val watermarkBefore: Long?,
        val entriesBefore: Set<String>,
        val permission: NativeEditorImportPermission,
    )

    /** Reads only the main-owned revision. @param owner Original media owner. @return Actual production revision. */
    private fun nativeEditorRevision(owner: StandbyMediaOwner): Long {
        check(Looper.myLooper() == Looper.getMainLooper()) { "CHECK4_EDITOR revision requires main" }
        return nativeEditorField(StandbyMediaOwner::class.java, "readRevision").getLong(owner)
    }

    /** Reads the actual process store watermark without allocating a generation. @param directory Canonical store directory. @return Existing watermark, if allocated. */
    private fun nativeEditorWatermark(directory: File): Long? {
        val values = nativeEditorField(StandbyMediaStore::class.java, "dirWatermarks").get(null) as Map<*, *>
        val value = values[directory.path]
        check(value == null || value is Long) { "CHECK4_EDITOR store watermark type unknown" }
        return value
    }

    /** Copies actual store creation provenance without calling its mutating get-or-put helper. @param directory Canonical store directory. @return Private immutable basenames. */
    private fun nativeEditorCreatedNames(directory: File): Set<String> {
        val values = nativeEditorField(StandbyMediaStore::class.java, "dirCreated").get(null) as Map<*, *>
        val value = values[directory.path] ?: return emptySet()
        check(value is Set<*>) { "CHECK4_EDITOR store provenance type unknown" }
        return value.map { it as? String ?: error("CHECK4_EDITOR store provenance entry type unknown") }.toSet()
    }

    /** Reads entries only, never removes an orphan or treats exists=false as absence. @param directory Canonical store directory. @return Exact current private basenames. */
    private fun nativeEditorDirectoryNames(directory: File): Set<String> {
        check(!Files.isSymbolicLink(directory.toPath())) { "CHECK4_EDITOR store directory became a symlink" }
        return Files.newDirectoryStream(directory.toPath()).use { entries -> entries.map { it.fileName.toString() }.toSet() }
    }

    /** Binds recovery authority before queueing import, using the existing allocation/revision contracts. @param host Original host/lease/binding authority. @param owner Original owner. @param active Protected Available media. @param directory Canonical store directory. @param entriesBefore Pre-effect directory observation. @return One immutable operation record. */
    private fun nativeEditorCaptureImport(
        host: NativeEditorBackHost,
        owner: StandbyMediaOwner,
        active: StandbyActive,
        directory: File,
        entriesBefore: Set<String>,
    ): NativeEditorImportOperation {
        host.verify(host.main)
        check(ownerEdit(owner) == null && nativeEditorOwnerSettled(owner) &&
            nativeEditorField(StandbyMediaOwner::class.java, "activeLoaded").getBoolean(owner) &&
            nativeEditorField(StandbyMediaOwner::class.java, "active").get(owner) == active
        ) { "CHECK4_EDITOR import requires loaded idle original owner" }
        check(entriesBefore == setOf(checkNotNull(active.file).name)) { "CHECK4_EDITOR import directory is not the protected baseline" }
        val created = nativeEditorCreatedNames(directory)
        check(created.isEmpty()) { "CHECK4_EDITOR pre-existing staged ownership prevents import" }
        val revision = nativeEditorRevision(owner)
        val watermark = nativeEditorWatermark(directory)
        val base = maxOf(active.generation, watermark ?: 0L)
        check(revision in 0L until Long.MAX_VALUE - 2L && base in 0L until Long.MAX_VALUE - 1L) {
            "CHECK4_EDITOR import revision/generation cannot advance safely"
        }
        return NativeEditorImportOperation(host, owner, active, directory, watermark, entriesBefore,
            NativeEditorImportPermission(revision, base + 1L, created))
    }

    /** Rejects arbitrary later edits, including an otherwise valid candidate from another operation. @param operation Pre-effect authority. @param edit Actual owner edit. */
    private fun nativeEditorAssertPublication(operation: NativeEditorImportOperation, edit: StandbyMediaEdit) {
        operation.host.verify(operation.host.main)
        check(ownerEdit(operation.owner) === edit && edit.status == StandbyMediaEditStatus.EDITING &&
            edit.candidate.kind == StandbyMediaKind.IMAGE && edit.candidate.framing == operation.active.framing &&
            nativeEditorWatermark(operation.directory) == operation.permission.generation &&
            operation.permission.owns(nativeEditorRevision(operation.owner), edit.candidate.generation,
                nativeEditorCreatedNames(operation.directory), edit.candidate.file.name)
        ) { "CHECK4_EDITOR fixture=INCOMPLETE publication is not the pre-bound import's first editable candidate" }
    }

    /** Requires effect completion plus staging/discard/refresh retirement before lease release. @param activity Original Main. @param operation Pre-bound import. @param edit Its locked first edit, or null for proven no-candidate recovery. @param witness Its original native views, if published. @return Whether all Main-thread operation work is settled. */
    private fun nativeEditorImportQuiescent(
        activity: MainActivity,
        operation: NativeEditorImportOperation,
        edit: StandbyMediaEdit?,
        witness: NativeEditorBackCandidate?,
    ): Boolean {
        operation.host.verify(activity)
        if (!operation.permission.isSettled) return false
        val owner = operation.owner
        val current = ownerEdit(owner)
        check(current == null || current === edit) { "CHECK4_EDITOR operation changed before quiescence" }
        if (!nativeEditorRetired(activity, owner, witness)) return false
        val before = operation.permission.revisionBefore
        val revision = nativeEditorRevision(owner)
        val watermark = nativeEditorWatermark(operation.directory)
        if (edit != null) {
            check(revision in before + 1L..before + 2L && watermark == operation.permission.generation) {
                "CHECK4_EDITOR operation revision/generation changed during retirement"
            }
            // Public cancel's asynchronous discard must reach refreshActive, not merely clear edit synchronously.
            if (revision != before + 2L) return false
            val driver = nativeEditorField(StandbyMediaOwner::class.java, "driver").get(owner) as AndroidStandbyMediaDriver
            val request = nativeEditorField(AndroidStandbyMediaDriver::class.java, "request").get(driver)
            if (request != null && nativeEditorField(request.javaClass, "file").get(request) == edit.candidate.file) return false
        } else {
            check(revision == before || (operation.permission.wasInvoked && revision == before + 1L)) {
                "CHECK4_EDITOR no-candidate operation revision unknown"
            }
            check(watermark == operation.watermarkBefore || (operation.permission.wasInvoked &&
                revision == before + 1L && watermark == operation.permission.generation)) {
                "CHECK4_EDITOR no-candidate store generation unknown"
            }
        }
        return nativeEditorField(StandbyMediaOwner::class.java, "activeLoaded").getBoolean(owner) &&
            nativeEditorField(StandbyMediaOwner::class.java, "active").get(owner) == operation.active
    }

    /** Pure checks cover revoked queued start, late owned publication, mismatch rejection and one-cancel authority. */
    private fun checkNativeEditorImportPermission() {
        val revoked = NativeEditorImportPermission(10L, 2L, emptySet())
        revoked.revoke()
        check(revoked.isSettled && !revoked.begin() && !revoked.wasInvoked)
        val running = NativeEditorImportPermission(10L, 2L, emptySet())
        check(running.begin())
        running.markInvoked()
        running.revoke()
        check(!running.isSettled && !running.begin())
        running.finish()
        check(running.isSettled && running.owns(11L, 2L, setOf("candidate"), "candidate"))
        check(!running.claimCancel(12L, 2L, setOf("candidate"), "candidate"))
        check(!running.claimCancel(11L, 3L, setOf("candidate"), "candidate"))
        check(!running.claimCancel(11L, 2L, setOf("candidate", "other"), "candidate"))
        check(running.claimCancel(11L, 2L, setOf("candidate"), "candidate"))
        check(!running.claimCancel(11L, 2L, setOf("candidate"), "candidate"))
    }

    /** Private locked authorities; the closure retains the inaccessible binding identity without exposing it. */
    private class NativeEditorBackHost(
        val main: MainActivity,
        val lease: Long,
        val verify: (MainActivity) -> Unit,
    )

    /** Retains the first actual editor and transaction, never a later candidate. */
    private data class NativeEditorBackCandidate(val edit: StandbyMediaEdit, val editor: StandbyFramingEditorView, val overlay: View)

    /** Read-only physical identity excludes access time, which the required byte reads may legitimately update. */
    private data class NativeEditorFileFacts(
        val canonical: String,
        val device: Long,
        val inode: Long,
        val mode: Int,
        val links: Long,
        val uid: Int,
        val gid: Int,
        val size: Long,
        val modified: FileTime,
        val changedSeconds: Long,
    )

    /** Original bytes stay private and are compared by content, never formatted. */
    private data class NativeEditorProtectedFile(val file: File, val facts: NativeEditorFileFacts, val bytes: ByteArray)

    /** Reads fixed field metadata only. @param type Declaring production type. @param name Fixed field name. @return Accessible metadata. */
    private fun nativeEditorField(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }

    /** Pure alias guard used before Back/cancel. @param original Original canonical path. @param candidate Candidate canonical path. @param samePhysical Whether both paths name the same inode. @return Whether the candidate is distinct. */
    private fun nativeEditorDistinct(original: String, candidate: String, samePhysical: Boolean): Boolean =
        original != candidate && !samePhysical

    /** Reads regular single-link identity without following a symbolic link. @param file Private file to inspect. @return Physical and persisted metadata. */
    private fun nativeEditorFileFacts(file: File): NativeEditorFileFacts {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val stat = Os.lstat(file.path)
        check(attributes.isRegularFile && !attributes.isSymbolicLink && OsConstants.S_ISREG(stat.st_mode) && stat.st_nlink == 1L) {
            "CHECK4_EDITOR fixture=INCOMPLETE regular non-symlink single-link file required"
        }
        return NativeEditorFileFacts(file.canonicalPath, stat.st_dev, stat.st_ino, stat.st_mode, stat.st_nlink,
            stat.st_uid, stat.st_gid, stat.st_size, attributes.lastModifiedTime(), stat.st_ctime)
    }

    /** Captures private bytes bracketed by unchanged physical metadata. @param file Original protected file. @return Stable private conservation facts. */
    private fun nativeEditorReadProtected(file: File): NativeEditorProtectedFile {
        val before = nativeEditorFileFacts(file)
        val bytes = Files.readAllBytes(file.toPath())
        val after = nativeEditorFileFacts(file)
        check(before == after && bytes.size.toLong() == after.size) { "CHECK4_EDITOR protected file changed during read" }
        return NativeEditorProtectedFile(file, after, bytes)
    }

    /** Proves the import's candidate is a distinct store-created regular file before any tested/failure input. @param original Protected original. @param edit First locked transaction. @param directory Actual store directory. @param start Original phase clock. @param budgetMs Phase budget in milliseconds. @return Locked physical candidate facts. */
    private fun nativeEditorValidateCandidate(
        original: NativeEditorProtectedFile,
        edit: StandbyMediaEdit,
        directory: File,
        start: Long,
        budgetMs: Long = 5_000L,
    ): NativeEditorFileFacts {
        remainingBudget(start, budgetMs)
        val candidate = edit.candidate
        val facts = nativeEditorFileFacts(candidate.file)
        remainingBudget(start, budgetMs)
        check(!Files.isSymbolicLink(directory.toPath()) && candidate.file.canonicalFile.parentFile == directory.canonicalFile &&
            original.file.canonicalFile.parentFile == directory.canonicalFile && candidate.generation > 1L &&
            candidate.kind == StandbyMediaKind.IMAGE && candidate.framing == StandbyFraming(0.5f, 0.5f, 1.2f)
        ) { "CHECK4_EDITOR fixture=INCOMPLETE candidate outside retained store transaction" }
        val sameFile = Files.isSameFile(original.file.toPath(), candidate.file.toPath())
        remainingBudget(start, budgetMs)
        check(nativeEditorDistinct(original.facts.canonical, facts.canonical, sameFile ||
            (original.facts.device == facts.device && original.facts.inode == facts.inode))
        ) { "CHECK4_EDITOR fixture=INCOMPLETE candidate aliases protected original; no Back/cancel" }
        val created = nativeEditorField(StandbyMediaStore::class.java, "dirCreated").get(null) as Map<*, *>
        val owned = created[directory.canonicalPath] as? Set<*>
        check(owned?.contains(candidate.file.name) == true) { "CHECK4_EDITOR fixture=INCOMPLETE store-created ownership unproven" }
        remainingBudget(start, budgetMs)
        return facts
    }

    /** Reads native visibility including ancestor alpha and positive geometry. @param view Actual retained view. @return Whether it has positive visible layout. */
    private fun nativeEditorShown(view: View): Boolean {
        if (!view.isAttachedToWindow || !view.isShown || !view.isLaidOut || view.width <= 0 || view.height <= 0) return false
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE || current.alpha <= 0f) return false
            current = current.parent as? View
        }
        return view.getGlobalVisibleRect(android.graphics.Rect())
    }

    /** Checks the real Standby root and hidden IME without focus/navigation mutations. @param activity Original Main. */
    private fun nativeEditorAssertRoot(activity: MainActivity) {
        check(!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus() &&
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().singleOrNull() === activity
        ) { "CHECK4_EDITOR fixture=INCOMPLETE original focused RESUMED Main missing" }
        val navigation = activityField("navState").get(activity) as StandbyNavigationState
        val host = activityField("standbyHost").get(activity) as? BigStandbyHost
        check(navigation == StandbyNavigationState(StandbyDestination.STANDBY, StandbyDestination.STANDBY) &&
            host != null && nativeEditorShown(host) && host.tag == StandbyDestination.STANDBY &&
            !activity.findViewById<View>(R.id.drawer).isShown && !activity.findViewById<View>(R.id.drawerScrim).isShown &&
            (activityField("frozenDialog").get(activity) as Dialog?)?.isShowing != true
        ) { "CHECK4_EDITOR fixture=INCOMPLETE Standby root/drawer/modal prerequisite lost" }
        val insets = ViewCompat.getRootWindowInsets(activity.findViewById(R.id.root))
            ?: error("CHECK4_EDITOR fixture=INCOMPLETE IME visibility unknown")
        check(!insets.isVisible(WindowInsetsCompat.Type.ime()) && activity.currentFocus !is EditText) {
            "CHECK4_EDITOR fixture=INCOMPLETE unexpected IME/editable focus"
        }
    }

    /** Requires the editor Back not to arm or leave a stale root request. @param activity Original Main. */
    private fun nativeEditorAssertUnarmed(activity: MainActivity) {
        val root = activityField("rootExit").get(activity) as RootExitConfirmation
        check(!root.isPending && nativeEditorField(RootExitConfirmation::class.java, "pendingSince").get(root) == null) {
            "CHECK4_EDITOR editor Back armed root confirmation"
        }
    }

    /** Reads transaction/cleanup flags only. @param owner Original process owner. @return Whether imports, refresh and cleanup have settled. */
    private fun nativeEditorOwnerSettled(owner: StandbyMediaOwner): Boolean =
        listOf("staging", "loadingActive", "refreshPending", "cleanupPending", "cleanupInProgress").none {
            nativeEditorField(StandbyMediaOwner::class.java, it).getBoolean(owner)
        } && nativeEditorField(StandbyMediaOwner::class.java, "cleanupWaiter").get(owner) == null

    /** Captures post-ready lease/binding/session authorities without requiring one historical startup Main. @param activity Current scenario Main. @param owner Original owner. @param active Original Available media. @return Locked authorities, or null until readiness settles. */
    private fun nativeEditorCaptureHost(activity: MainActivity, owner: StandbyMediaOwner, active: StandbyActive): NativeEditorBackHost? {
        if (!activity.hasWindowFocus() || activity.isFinishing || activity.isDestroyed) return null
        val host = activityField("standbyHost").get(activity) as? BigStandbyHost ?: return null
        val lease = activityField("mediaLease").get(activity) as? Long ?: return null
        val leases = nativeEditorField(StandbyMediaOwner::class.java, "leases").get(owner) as StandbyHostLease
        val bindingField = nativeEditorField(StandbyMediaOwner::class.java, "binding")
        val binding = bindingField.get(owner) ?: return null
        val bindingLease = nativeEditorField(binding.javaClass, "lease")
        val bindingView = nativeEditorField(binding.javaClass, "view")
        if (!leases.isCurrent(lease) || bindingLease.getLong(binding) != lease || bindingView.get(binding) !== host.mediaUnderlay ||
            !nativeEditorShown(host) || !nativeEditorOwnerSettled(owner) || !activityField("standbyMediaReady").getBoolean(activity) ||
            ViewCompat.getRootWindowInsets(activity.findViewById(R.id.root)) == null
        ) return null
        nativeEditorAssertRoot(activity)
        nativeEditorAssertUnarmed(activity)
        check(nativeEditorRetired(activity, owner, null) &&
            nativeEditorField(StandbyMediaOwner::class.java, "active").get(owner) == active
        ) { "CHECK4_EDITOR fixture=INCOMPLETE retained owner is not idle" }
        val app = MiraxApp.instance
        val session = app.session
        val platform = SessionPlatformBinding.platform
        val forbidden = nativeEditorField(platform.javaClass, "forbiddenCalls").get(platform) as AtomicInteger
        val baseline = session.snapshot()
        val settings = session.exportSettings()
        check(!baseline.advertisingEnabled && !baseline.showPicture && baseline.phase != ScreenPhase.CONNECTED && forbidden.get() == 0) {
            "CHECK4_EDITOR fixture=INCOMPLETE idle isolated session required"
        }
        val delegate = nativeEditorField(BroadcastAutoStop::class.java, "timer\$delegate").get(BroadcastAutoStop) as Lazy<*>
        check(delegate.isInitialized()) { "CHECK4_EDITOR fixture=INCOMPLETE idle timer unavailable" }
        val timer = delegate.value as BroadcastAutoStopTimer
        val armed = nativeEditorField(BroadcastAutoStopTimer::class.java, "armedMinutes")
        val pending = nativeEditorField(BroadcastAutoStop::class.java, "pendingStop")
        check(armed.get(timer) == null && pending.get(BroadcastAutoStop) == null) { "CHECK4_EDITOR fixture=INCOMPLETE idle countdown active" }
        val task = activity.taskId
        check(task >= 0) { "CHECK4_EDITOR fixture=INCOMPLETE Main task unavailable" }
        return NativeEditorBackHost(activity, lease) { current ->
            check(current === activity && current.taskId == task && app.session === session &&
                app.standbyMediaOwner === owner && SessionPlatformBinding.platform === platform &&
                activityField("standbyHost").get(current) === host && activityField("mediaLease").get(current) == lease &&
                leases.isCurrent(lease) && bindingField.get(owner) === binding &&
                bindingLease.getLong(binding) == lease && bindingView.get(binding) === host.mediaUnderlay
            ) { "CHECK4_EDITOR fixture=INCOMPLETE original Main/host/lease/binding changed" }
            nativeEditorAssertRoot(current)
            val value = session.snapshot()
            check(value.phase == baseline.phase && value.advertisingEnabled == baseline.advertisingEnabled &&
                value.wfdOwner == baseline.wfdOwner && value.wfdAdvertise == baseline.wfdAdvertise &&
                value.showPicture == baseline.showPicture && value.connectionRuns == baseline.connectionRuns &&
                value.connectionOfferModes == baseline.connectionOfferModes && value.connectionPreferredMode == baseline.connectionPreferredMode &&
                value.selectedMode == baseline.selectedMode && session.exportSettings() == settings && forbidden.get() == 0 &&
                armed.get(timer) == null && pending.get(BroadcastAutoStop) == null
            ) { "CHECK4_EDITOR session/platform/idle invariant changed" }
        }
    }

    /** Requires detached old native views and settled owner/Main/driver state, not just edit=null. @param activity Original Main. @param owner Original owner. @param witness First editor, if published. @return Whether retirement is settled. */
    private fun nativeEditorRetired(activity: MainActivity, owner: StandbyMediaOwner, witness: NativeEditorBackCandidate?): Boolean {
        val driver = nativeEditorField(StandbyMediaOwner::class.java, "driver").get(owner) as AndroidStandbyMediaDriver
        val driverType = AndroidStandbyMediaDriver::class.java
        val request = nativeEditorField(driverType, "request").get(driver)
        val candidateGone = witness == null || request == null ||
            nativeEditorField(request.javaClass, "file").get(request) != witness.edit.candidate.file
        return ownerEdit(owner) == null && nativeEditorOwnerSettled(owner) &&
            !activityField("mediaStaging").getBoolean(activity) && activityField("mediaEdit").get(activity) == null &&
            activityField("mediaEditor").get(activity) == null && activityField("mediaEditorEdit").get(activity) == null &&
            activityField("mediaOverlay").get(activity) == null && activityField("pendingMediaPresentation").get(activity) == null &&
            !activityField("renderingMediaPresentation").getBoolean(activity) &&
            !nativeEditorField(driverType, "loading").getBoolean(driver) && nativeEditorField(driverType, "closing").get(driver) == null &&
            nativeEditorField(driverType, "closeFailure").get(driver) == null && candidateGone &&
            (witness == null || (!witness.editor.isAttachedToWindow && !witness.overlay.isAttachedToWindow &&
                witness.overlay.parent == null && witness.editor.rootView !== activity.window.decorView))
    }

    /** Narrows only the separately qualified root observation to its original 2s fence. @param start Root phase origin. @return Strictly positive remaining milliseconds. */
    private fun nativeEditorRootBudget(start: Long): Long {
        val elapsed = SystemClock.elapsedRealtime() - start
        check(elapsed in 0L until 2_000L) { "CHECK4_EDITOR root confirmation deadline expired" }
        return 2_000L - elapsed
    }

    /** Removes the method observer even if its scenario Main has died; queued-not-started work is cancelled only by CAS. @param callback This method's observer. */
    private fun nativeEditorRemoveObserver(callback: ActivityLifecycleCallback) {
        val start = SystemClock.elapsedRealtime()
        val handler = Handler(Looper.getMainLooper())
        val pending = AtomicBoolean(true)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val task = Runnable {
            if (pending.compareAndSet(true, false)) {
                try {
                    ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(callback)
                } catch (error: Throwable) {
                    Log.e("MainStandbyIntegration", "CHECK4_EDITOR observerRemovalFailure=${error.javaClass.simpleName}")
                    failure.set(error)
                } finally {
                    completed.countDown()
                }
            }
        }
        check(handler.post(task)) { "CHECK4_EDITOR observer removal rejected" }
        try {
            check(completed.await(remainingBudget(start), TimeUnit.MILLISECONDS)) { "CHECK4_EDITOR observer removal timed out" }
        } catch (error: Throwable) {
            if (pending.compareAndSet(true, false)) handler.removeCallbacks(task)
            throw error
        }
        failure.get()?.let { throw it }
        remainingBudget(start)
    }

    private fun activityField(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun activityMethod(name: String, vararg arguments: Class<*>) =
        MainActivity::class.java.getDeclaredMethod(name, *arguments).apply { isAccessible = true }

    private fun foregroundState(host: BigStandbyHost, theme: StandbyThemeId): StandbyForegroundState {
        val name = when (theme) {
            StandbyThemeId.LANDSCAPE -> "landscapeForeground"
            StandbyThemeId.MATTER -> "matterForeground"
            StandbyThemeId.HARDLINE -> "hardlineForeground"
            // Legacy hosts the dashboard column, so it owns no foreground view.
            StandbyThemeId.LEGACY -> error("LEGACY theme has no standby foreground view")
        }
        val foreground = BigStandbyHost::class.java.getDeclaredField(name).apply { isAccessible = true }
            .get(host) as StandbyForegroundView
        val state = StandbyForegroundView::class.java.getDeclaredField("foregroundState").apply { isAccessible = true }
        return state.get(foreground) as StandbyForegroundState
    }

    /**
     * Observes the owner's private edit on main without changing retained state.
     *
     * @param owner The media owner instance.
     * @return The active [StandbyMediaEdit] or null.
     */
    private fun ownerEdit(owner: StandbyMediaOwner): StandbyMediaEdit? {
        check(Looper.myLooper() == Looper.getMainLooper()) { "ownerEdit must be accessed on the main UI thread" }
        val field = StandbyMediaOwner::class.java.getDeclaredField("edit").apply { isAccessible = true }
        return field.get(owner) as StandbyMediaEdit?
    }

    /**
     * Observes the owner's private staging flag on main without changing retained state.
     *
     * @param owner The media owner instance.
     * @return True if import is currently staging.
     */
    private fun ownerStaging(owner: StandbyMediaOwner): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper()) { "ownerStaging must be accessed on the main UI thread" }
        val field = StandbyMediaOwner::class.java.getDeclaredField("staging").apply { isAccessible = true }
        return field.getBoolean(owner)
    }

    /** Drains any retained native-editor recovery continuation after test failure before runner teardown. */
    @After
    fun drainPendingRecoveryAfterTest() {
        if (pendingNativeEditorRecovery.get() != null) {
            drainPendingNativeEditorRecovery()
        }
    }

    /**
     * Awaits a condition off the main thread with a 25ms controlled pause using a bounded phase budget.
     *
     * @param start Monotonic start time in milliseconds from SystemClock.elapsedRealtime().
     * @param description Diagnostic label on timeout.
     * @param budgetMs Phase budget in milliseconds.
     * @param condition Evaluator running off the main thread.
     */
    private fun awaitPhase(
        start: Long,
        description: String,
        budgetMs: Long = 5_000L,
        condition: () -> Boolean,
    ) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Phase must be awaited off the main thread" }
        val phaseEnd = start + budgetMs
        while (true) {
            val remainingBefore = phaseEnd - SystemClock.elapsedRealtime()
            check(remainingBefore > 0L) { "Timed out awaiting $description after ${budgetMs}ms" }
            if (condition()) {
                val remainingAfter = phaseEnd - SystemClock.elapsedRealtime()
                check(remainingAfter > 0L) { "Timed out awaiting $description after ${budgetMs}ms" }
                return
            }
            val remainingSleep = phaseEnd - SystemClock.elapsedRealtime()
            check(remainingSleep > 0L) { "Timed out awaiting $description after ${budgetMs}ms" }
            Thread.sleep(minOf(25L, remainingSleep))
        }
    }

    /**
     * Awaits a condition off the main thread using the bounded native-editor recovery phase budget.
     *
     * @param start Monotonic start time in milliseconds from SystemClock.elapsedRealtime().
     * @param description Diagnostic label on timeout.
     * @param condition Evaluator running off the main thread.
     */
    private fun awaitRecoveryPhase(
        start: Long,
        description: String,
        condition: () -> Boolean,
    ) {
        awaitPhase(start, description, NATIVE_EDITOR_RECOVERY_BUDGET_MS, condition)
    }

    /**
     * Returns remaining budget from start time against the given phase budget, failing if expired.
     *
     * @param start Monotonic start time in milliseconds from SystemClock.elapsedRealtime().
     * @param budgetMs Phase budget in milliseconds.
     * @return Remaining milliseconds strictly greater than zero.
     */
    private fun remainingBudget(start: Long, budgetMs: Long = 5_000L): Long {
        val remaining = budgetMs - (SystemClock.elapsedRealtime() - start)
        check(remaining > 0L) { "Phase deadline expired" }
        return remaining
    }

    /**
     * Returns remaining milliseconds against the bounded native-editor recovery phase budget.
     *
     * @param start Monotonic start time in milliseconds from SystemClock.elapsedRealtime().
     * @return Remaining milliseconds strictly greater than zero.
     */
    private fun remainingRecoveryBudget(start: Long): Long =
        remainingBudget(start, NATIVE_EDITOR_RECOVERY_BUDGET_MS)

    /**
     * Pure in-memory assertion verifying single-drain recovery registration and idempotent drain semantics.
     */
    private fun checkNativeEditorPendingRecoverySelfTest() {
        check(pendingNativeEditorRecovery.get() == null) {
            "CHECK4_EDITOR stale pending recovery present before test"
        }
        var runs = 0
        try {
            val registered = pendingNativeEditorRecovery.compareAndSet(null) { runs += 1 }
            check(registered) { "CHECK4_EDITOR recovery registration failed" }
            check(!pendingNativeEditorRecovery.compareAndSet(null) { runs += 1 }) {
                "CHECK4_EDITOR duplicate recovery registration succeeded"
            }
            drainPendingNativeEditorRecovery()
            check(runs == 1 && pendingNativeEditorRecovery.get() == null) {
                "CHECK4_EDITOR single recovery drain failed"
            }
            drainPendingNativeEditorRecovery()
            check(runs == 1 && pendingNativeEditorRecovery.get() == null) {
                "CHECK4_EDITOR duplicate recovery drain ran twice"
            }
        } finally {
            pendingNativeEditorRecovery.set(null)
        }
    }

    /**
     * Chains a secondary failure as suppressed on the primary failure without duplicating instances.
     *
     * @param primary Existing failure, or null if this is the first error.
     * @param error New error to record.
     * @return The updated primary failure with error attached.
     */
    private fun recordThrowable(primary: Throwable?, error: Throwable): Throwable {
        if (primary == null) return error
        if (primary !== error && primary.suppressed.none { it === error }) {
            primary.addSuppressed(error)
        }
        return primary
    }

    /**
     * Pure in-memory assertion verifying the error chaining helper.
     */
    private fun checkErrorChainingSelfTest() {
        val first = AssertionError("first")
        val second = IllegalStateException("second")
        val recordedFirst = recordThrowable(null, first)
        check(recordedFirst === first)
        val recordedSecond = recordThrowable(first, second)
        check(recordedSecond === first && first.suppressed.singleOrNull() === second)
        val recordedDuplicate = recordThrowable(first, second)
        check(recordedDuplicate === first && first.suppressed.size == 1)
        val recordedSame = recordThrowable(first, first)
        check(recordedSame === first && first.suppressed.size == 1)
    }

    /**
     * Pure in-memory assertion verifying snapshot equality behavior across NeverSet and Available states.
     */
    private fun checkSnapshotBaselineSelfTest() {
        val neverSet: StandbySnapshot = StandbySnapshot.NeverSet
        check(neverSet == StandbySnapshot.NeverSet)
        val active1 = StandbyActive(File("a"), StandbyMediaKind.IMAGE, StandbyFraming.CENTERED, 1L)
        val active2 = StandbyActive(File("a"), StandbyMediaKind.IMAGE, StandbyFraming.CENTERED, 1L)
        val active3 = StandbyActive(File("a"), StandbyMediaKind.IMAGE, StandbyFraming.CENTERED, 2L)
        val activeDiffFile = active1.copy(file = File("b"))
        val activeDiffKind = active1.copy(kind = null)
        val activeDiffFraming = active1.copy(framing = StandbyFraming(0f, 0f, 2f))
        check(StandbySnapshot.Available(active1) == StandbySnapshot.Available(active2))
        check(StandbySnapshot.Available(active1) != StandbySnapshot.Available(active3))
        check(StandbySnapshot.Available(active1) != StandbySnapshot.Available(activeDiffFile))
        check(StandbySnapshot.Available(active1) != StandbySnapshot.Available(activeDiffKind))
        check(StandbySnapshot.Available(active1) != StandbySnapshot.Available(activeDiffFraming))
        check(StandbySnapshot.Available(active1) != neverSet)
    }

    companion object {
        private const val NATIVE_EDITOR_RECOVERY_BUDGET_MS = 15_000L
        private val pendingNativeEditorRecovery = AtomicReference<(() -> Unit)?>(null)

        /** Drains and executes any single pending native-editor recovery continuation. */
        internal fun drainPendingNativeEditorRecovery() {
            val recovery = pendingNativeEditorRecovery.getAndSet(null) ?: return
            recovery()
        }
    }
}
