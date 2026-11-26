package me.trinitrix.mirax.standbyprobe.layout

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.net.http.SslError
import android.os.SystemClock
import android.text.Layout
import android.text.TextPaint
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import me.trinitrix.mirax.R
import me.trinitrix.mirax.WindowEvidence
import me.trinitrix.mirax.awaitActivityAction
import me.trinitrix.mirax.data.standby.theme.StandbyThemeId
import me.trinitrix.mirax.session.PrePlayFailureCause
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.standbyprobe.StandbyProbeActivity
import me.trinitrix.mirax.ui.standby.BigStandbyHost
import me.trinitrix.mirax.ui.standby.foreground.HardlineStandbyForegroundView
import me.trinitrix.mirax.ui.standby.foreground.LandscapeStandbyForegroundView
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundCallbacks
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundState
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundTags
import me.trinitrix.mirax.ui.standby.foreground.StandbyForegroundView
import me.trinitrix.mirax.ui.standby.hardline.HardlineSceneView
import me.trinitrix.mirax.ui.standby.landscape.LandscapeSceneView
import me.trinitrix.mirax.ui.standby.matter.MatterParticleView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Same-device original-WebView/native baseline only; no fidelity acceptance or session owner. */
@RunWith(AndroidJUnit4::class)
class StandbyPrototypeBaselineCaptureTest {
    /** Captures both portrait READY themes into a new, explicitly named external-files run directory. */
    @Test
    fun capturePortraitReadyBaselines() {
        val safety = assertIsolatedPackage()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val run = requireNotNull(arguments.getString("baselineRun")) { "Pass -e baselineRun <unique-run-label>" }
        require(run.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) { "Unsafe baselineRun label" }
        val name = arguments.getString("baselineName") ?: "MIRAX · 客廳"
        require(name.isNotBlank() && name == name.trim() && name.codePointCount(0, name.length) <= 40 &&
            name.none { it.isISOControl() }) { "baselineName must be 1..40 code points without controls or edge spaces" }
        val assets = instrumentation.context.assets
        val rawHashes = assets.open("$ASSET_DIRECTORY/sources.sha256.json").use { it.readBytes() }
        val hashes = JSONObject(rawHashes.toString(Charsets.UTF_8))
        val originals = hashes.keys().asSequence().associateWith { path ->
            require(path.matches(SOURCE_PATH)) { "Unsafe source binding: $path" }
            assets.open("$ASSET_DIRECTORY/$path").use { it.readBytes() }.also { bytes ->
                assertEquals("Original source hash: design/$path", hashes.getString(path), sha256(bytes))
            }
        }
        val html = requireNotNull(originals[HTML_FILE]).toString(Charsets.UTF_8)
        val references = Regex("""(?:src|href)=["']([^"']+\.(?:css|js)(?:\?[^"']*)?)["']""")
            .findAll(html).map { it.groupValues[1].substringBefore('?') }.toSet()
        assertEquals("Bind only the HTML and its original CSS/JS references", references + HTML_FILE, originals.keys)
        checkRequestPaths(originals.keys)
        val output = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
            "standby-prototype-baseline/$run")
        check(!output.exists() && output.mkdirs()) { "Run directory must be new, never overwrite evidence: $output" }
        File(output, "sources.sha256.json").writeBytes(rawHashes)
        val pairs = JSONArray()
        val clients = mutableListOf<ReferenceClient>()
        val manifest = JSONObject()
            .put("schemaVersion", 1).put("run", run).put("status", "RUNNING")
            .put("safety", safety).put("sourceRoot", "design/").put("originalSourceSha256", hashes)
            .put("requestedState", "READY").put("requestedBroadcast", false).put("requestedName", name)
            .put("requestedLocale", "zh-TW").put("prototypeDocumentLocale", "zh-Hant")
            .put("sourceDefaultName", Regex("""<strong class="device-name">([^<]+)</strong>""")
                .find(html)?.groupValues?.get(1))
            .put("textOnlySubstitutions", JSONArray())
            .put("pairs", pairs)
            .put("limits", JSONArray(listOf(
                "Baseline only: visual differences, wrapping and ellipsis do not fail this test.",
                "Ambient clocks are not synchronized; DOM/view measurements precede each window capture.",
                "Hardline channels are Canvas-drawn natively: no public per-channel bounds or font inspection API.",
                "Locale override is a test-only Configuration context; device density/fontScale/settings are unchanged.",
                "Both roots use the same Activity safe viewport, without additional native safe-inset padding.",
            )))
        val manifestFile = File(output, "manifest.json")
        manifestFile.writeText(manifest.toString(2))
        try {
            ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
                var web: WebView? = null
                var host: BigStandbyHost? = null
                var primary: Throwable? = null
                var previousCookiePolicy: Boolean? = null
                try {
                    awaitCondition("focused portrait fullscreen probe") {
                        var ready = false
                        awaitActivityAction(scenario) { activity ->
                            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            activity.window.insetsController?.hide(WindowInsets.Type.systemBars())
                            val root = activity.contentRoot
                            ready = root.hasWindowFocus() && root.width > 0 && root.height > root.width &&
                                activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                                root.rootWindowInsets?.isVisible(WindowInsets.Type.systemBars()) == false
                        }
                        ready
                    }
                    for (theme in listOf(StandbyThemeId.LANDSCAPE, StandbyThemeId.HARDLINE)) {
                        val label = theme.name.lowercase(Locale.ROOT)
                        val pair = JSONObject().put("theme", theme.name)
                        pairs.put(pair)
                        val url = Uri.parse("$ORIGIN/$HTML_FILE").buildUpon()
                            .appendQueryParameter("variant", if (theme == StandbyThemeId.LANDSCAPE) "A" else "D")
                            .appendQueryParameter("phase", "READY").appendQueryParameter("name", name).build().toString()
                        val client = ReferenceClient(originals, url)
                        clients.add(client)
                        awaitActivityAction(scenario) { activity ->
                            val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                                setLocale(Locale.TRADITIONAL_CHINESE)
                            })
                            val cookies = CookieManager.getInstance()
                            if (previousCookiePolicy == null) previousCookiePolicy = cookies.acceptCookie()
                            cookies.setAcceptCookie(false)
                            val reference = WebView(context)
                            web = reference
                            configureReference(reference, client)
                            activity.contentRoot.removeAllViews()
                            activity.contentRoot.addView(reference, FrameLayout.LayoutParams(-1, -1))
                            reference.loadUrl(url)
                        }
                        val reference = checkNotNull(web)
                        assertTrue("Prototype load timed out: $url; ${client.errors()}",
                            client.loaded.await(15, TimeUnit.SECONDS))
                        client.assertNoErrors()
                        awaitCondition("original prototype READY, fonts and initial copy") {
                            val state = evaluate(scenario, reference, """
                                (() => ({ready: document.readyState === 'complete' && document.fonts.status === 'loaded' &&
                                  typeof state !== 'undefined' && state.phase === 'off' && !state.broadcast &&
                                  !dReviews.loading && dReviews.loaded && mainCopyChanges.size === 0}))()
                            """.trimIndent())
                            client.assertNoErrors()
                            state.optBoolean("ready")
                        }
                        assertEquals("Every original HTML/CSS/JS must actually be served", originals.keys, client.servedPaths())
                        val substitution = evaluate(scenario, reference, """
                            (() => {
                              const selector = '.device .micro-label';
                              const node = document.querySelector(selector);
                              if (!node) throw new Error('Missing receiver micro-label');
                              const original = node.textContent, replacement = 'RECEIVER';
                              if (original !== '模擬接收裝置 / RECEIVER' && original !== replacement)
                                throw new Error('Unexpected receiver micro-label: ' + original);
                              node.textContent = replacement;
                              window.dispatchEvent(new Event('resize'));
                              return {selector, original, replacement,
                                reason:'Remove prototype-only simulation prefix to match native receiver copy; text only.'};
                            })()
                        """.trimIndent())
                        manifest.getJSONArray("textOnlySubstitutions").put(substitution.put("theme", theme.name))
                        awaitWebFrame(scenario, reference)
                        val dom = evaluate(scenario, reference, DOM_SNAPSHOT)
                        pair.put("referenceDom", dom)
                        var referenceWindow = JSONObject()
                        awaitActivityAction(scenario) { activity ->
                            referenceWindow = windowMetrics(activity, reference)
                        }
                        pair.put("referenceWindow", referenceWindow)
                        assertReferenceViewport(dom, referenceWindow, theme, name)
                        pair.put("referenceCapture", capture(scenario, output, "$label-reference-ready"))
                        manifestFile.writeText(manifest.toString(2))
                        awaitActivityAction(scenario) { activity ->
                            activity.contentRoot.removeView(reference)
                            reference.stopLoading()
                            reference.destroy()
                            web = null
                            val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                                setLocale(Locale.TRADITIONAL_CHINESE)
                            })
                            val native = BigStandbyHost(ContextThemeWrapper(context, R.style.Theme_Mirax), StandbyForegroundCallbacks(
                                onToggleBroadcast = {}, onOpenSettings = {},
                            ))
                            host = native
                            native.render(theme, StandbyForegroundState(
                                ScreenPhase.READY, false, receiverName = name, isProjecting = false,
                            ), "接收已關閉", "BROADCAST OFF", "")
                            native.applySafeInsets(Insets.NONE)
                            activity.contentRoot.addView(native, FrameLayout.LayoutParams(-1, -1))
                            native.setRuntimeVisible(true)
                        }
                        awaitCommittedFrame(scenario)
                        var nativeWindow = JSONObject()
                        var native = JSONObject()
                        awaitActivityAction(scenario) { activity ->
                            val actual = checkNotNull(host)
                            nativeWindow = windowMetrics(activity, actual)
                            native = nativeSnapshot(actual, theme, name)
                        }
                        pair.put("nativeWindow", nativeWindow).put("native", native)
                        for (key in listOf("rootRectWindowPx", "viewportRectWindowPx", "density", "densityDpi", "fontScale",
                            "scaledDensity", "insetsPx", "orientation", "contextLocales")) {
                            assertEquals("Paired context/viewport changed: $key", referenceWindow.get(key).toString(),
                                nativeWindow.get(key).toString())
                        }
                        pair.put("differencesNativeMinusReferencePx", differences(dom, native))
                        val nativeCapture = capture(scenario, output, "$label-native-ready")
                        pair.put("nativeCapture", nativeCapture)
                        for (dimension in listOf("widthPx", "heightPx")) {
                            assertEquals("Paired PNG window $dimension", pair.getJSONObject("referenceCapture").getInt(dimension),
                                nativeCapture.getInt(dimension))
                        }
                        client.assertNoErrors()
                        awaitActivityAction(scenario) { activity ->
                            host?.setRuntimeVisible(false)
                            activity.contentRoot.removeAllViews()
                            host = null
                        }
                        manifestFile.writeText(manifest.toString(2))
                    }
                } catch (error: Throwable) {
                    primary = error
                    Log.e(WindowEvidence.LOG_TAG, "Prototype baseline failed; run=$run", error)
                    try {
                        manifest.put("failureCapture", capture(scenario, output, "failure"))
                    } catch (captureError: Throwable) {
                        Log.e(WindowEvidence.LOG_TAG, "Baseline failure capture unavailable", captureError)
                        error.addSuppressed(captureError)
                        manifest.put("failureCaptureError", captureError.toString())
                    }
                    throw error
                } finally {
                    try {
                        awaitActivityAction(scenario) { activity ->
                            host?.setRuntimeVisible(false)
                            web?.stopLoading()
                            activity.contentRoot.removeAllViews()
                            web?.destroy()
                            previousCookiePolicy?.let { CookieManager.getInstance().setAcceptCookie(it) }
                        }
                    } catch (cleanupError: Throwable) {
                        Log.e(WindowEvidence.LOG_TAG, "Baseline teardown failed", cleanupError)
                        val original = primary
                        if (original == null) throw cleanupError else original.addSuppressed(cleanupError)
                    }
                }
            }
            clients.forEach { it.assertNoErrors() }
            manifest.put("status", "BASELINE_CAPTURED_NOT_VISUAL_ACCEPTANCE")
        } catch (error: Throwable) {
            manifest.put("status", "FAILED").put("failure", Log.getStackTraceString(error))
            throw error
        } finally {
            manifest.put("referenceRequests", JSONArray(clients.map { it.evidence() }))
            manifestFile.writeText(manifest.toString(2))
            Log.i(WindowEvidence.LOG_TAG, "prototypeBaseline manifest=${manifestFile.absolutePath} status=${manifest.getString("status")}")
        }
    }

    /** Each public selector chooses one fixed pack, never a combination of modes. */
    private enum class SupplementalPack { FIDELITY, ENGLISH_TITLE, FAILED_EPISODE }

    /** Fixed fixture inputs; prototypePhase is the source entry, not the display-only failed episode. */
    private data class SupplementalCase(
        val id: String,
        val landscape: Boolean,
        val locale: Locale,
        val languageTag: String,
        val phase: ScreenPhase,
        val prototypePhase: String,
        val advertisingEnabled: Boolean,
        val receiverName: String,
            val shouldOfferShizuku: Boolean = false,
        val failureCause: PrePlayFailureCause? = null,
    ) {
        val referenceCapturePhase: String get() = if (failureCause != null) "failed" else prototypePhase
    }

    /** @return The existing localized Main-shaped phase title, without a fabricated failure. */
    private fun nativeTitle(context: Context, phase: ScreenPhase): String = when (phase) {
        ScreenPhase.READY -> context.getString(R.string.standby_foreground_phase_off_title)
        ScreenPhase.ARMING -> context.getString(R.string.standby_foreground_phase_arming_title)
        ScreenPhase.ADVERTISING -> context.getString(R.string.standby_foreground_phase_waiting_title)
        ScreenPhase.CONNECTING -> context.getString(R.string.standby_foreground_phase_connecting_title)
        ScreenPhase.CONNECTED -> context.getString(R.string.standby_foreground_phase_projecting_title)
        ScreenPhase.WIFI_PAUSED -> context.getString(R.string.standby_foreground_phase_wifi_paused_title)
        ScreenPhase.FROZEN -> context.getString(R.string.standby_foreground_phase_frozen_title)
    }

    /** @return The existing resource-bound Latin phase eyebrow. */
    private fun nativeEyebrow(context: Context, phase: ScreenPhase): String = when (phase) {
        ScreenPhase.READY -> context.getString(R.string.standby_foreground_phase_off_eyebrow)
        ScreenPhase.ARMING -> context.getString(R.string.standby_foreground_phase_arming_eyebrow)
        ScreenPhase.ADVERTISING -> context.getString(R.string.standby_foreground_phase_waiting_eyebrow)
        ScreenPhase.CONNECTING -> context.getString(R.string.standby_foreground_phase_connecting_eyebrow)
        ScreenPhase.CONNECTED -> context.getString(R.string.standby_foreground_phase_projecting_eyebrow)
        ScreenPhase.WIFI_PAUSED -> context.getString(R.string.standby_foreground_phase_wifi_paused_eyebrow)
        ScreenPhase.FROZEN -> context.getString(R.string.standby_foreground_phase_frozen_eyebrow)
    }

    /** @return The existing host detail input; non-Matter foreground detail remains empty/GONE. */
    private fun nativeDetail(context: Context, phase: ScreenPhase): String = when (phase) {
        ScreenPhase.READY -> context.getString(R.string.standby_foreground_phase_off_detail)
        ScreenPhase.ARMING -> context.getString(R.string.standby_foreground_phase_arming_detail)
        ScreenPhase.ADVERTISING -> context.getString(R.string.standby_foreground_phase_waiting_detail)
        ScreenPhase.CONNECTING -> context.getString(R.string.standby_foreground_phase_connecting_detail)
        ScreenPhase.CONNECTED -> ""
        ScreenPhase.WIFI_PAUSED -> context.getString(R.string.standby_foreground_phase_wifi_paused_detail)
        ScreenPhase.FROZEN -> context.getString(R.string.standby_foreground_phase_frozen_detail)
    }

    /** @return Resource-bound foreground copy and the finite original English channel contract. */
    private fun supplementalCopy(context: Context, case: SupplementalCase): JSONObject {
        if (case.failureCause != null) {
            val failureText = context.getString(R.string.preplay_failed_timeout)
            return JSONObject().put("title", context.getString(R.string.standby_foreground_phase_failed_title))
                .put("eyebrow", context.getString(R.string.standby_foreground_phase_failed_eyebrow))
                .put("detail", failureText).put("failureText", failureText)
                .put("receiverLabel", context.getString(R.string.standby_foreground_receiver_label))
                .put("hint", context.getString(R.string.standby_foreground_connection_hint_format, case.receiverName))
                .put("channelStatus", "Connection failed · Press Win + K to retry")
                .put("referenceEntry", JSONObject().put("title", nativeTitle(context, case.phase))
                    .put("eyebrow", nativeEyebrow(context, case.phase)).put("hint", "")
                    .put("receiverLabel", context.getString(R.string.standby_foreground_receiver_label))
                    .put("channelStatus", "Connecting · Please wait"))
        }
        val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        })
        val hint = when (case.phase) {
            ScreenPhase.ADVERTISING -> context.getString(R.string.standby_foreground_connection_hint_format, case.receiverName)
            ScreenPhase.WIFI_PAUSED -> context.getString(R.string.standby_foreground_landscape_wifi_paused_hint)
            ScreenPhase.FROZEN -> context.getString(R.string.standby_foreground_phase_frozen_detail)
            else -> error("Unsupported supplemental phase: ${case.phase}")
        }
        val channel = when (case.phase) {
            ScreenPhase.ADVERTISING -> english.getString(R.string.standby_foreground_connection_hint_format, case.receiverName)
            ScreenPhase.WIFI_PAUSED -> "Wi-Fi is off · Please turn on Wi-Fi to broadcast"
            ScreenPhase.FROZEN -> "Receiver unavailable · Check settings"
            else -> error("Unsupported supplemental phase: ${case.phase}")
        }
        val resourcePhase = when (case.phase) {
            ScreenPhase.ADVERTISING -> "waiting"
            ScreenPhase.WIFI_PAUSED -> "wifi_paused"
            ScreenPhase.FROZEN -> "frozen"
            else -> error("Unsupported supplemental phase: ${case.phase}")
        }
        return JSONObject().put("title", nativeTitle(context, case.phase)).put("eyebrow", nativeEyebrow(context, case.phase))
            .put("titleResource", "R.string.standby_foreground_phase_${resourcePhase}_title")
            .put("eyebrowResource", "R.string.standby_foreground_phase_${resourcePhase}_eyebrow")
            .put("detail", nativeDetail(context, case.phase))
            .put("receiverLabel", context.getString(R.string.standby_foreground_receiver_label))
            .put("hint", hint).put("channelStatus", channel)
    }

    /** Pins copy/markup for a settled phase; the failed route observes both phases without normalizing either. */
    private fun prepareReferenceCopy(
        scenario: ActivityScenario<StandbyProbeActivity>,
        reference: WebView,
        theme: StandbyThemeId,
        case: SupplementalCase,
        copy: JSONObject,
        prototypePhase: String = case.referenceCapturePhase,
    ): JSONObject {
        val expected = JSONObject().put("case", case.id).put("theme", theme.name).put("copy", copy)
            .put("english", case.locale == Locale.ENGLISH).put("phase", case.phase.name)
            .put("prototypePhase", prototypePhase).put("broadcast", case.advertisingEnabled).put("name", case.receiverName)
            .put("literalEpisode", case.failureCause != null)
        return evaluate(scenario, reference, """
            (() => {
              const expected = $expected, copy = expected.copy, substitutions = [];
              const requireValue = (ok, reason) => { if (!ok) throw new Error(reason); };
              const required = selector => {
                const node = document.querySelector(selector);
                requireValue(Boolean(node), 'Missing required node: ' + selector);
                return node;
              };
              const shape = node => node.nodeType === 3 ? ['text'] : [node.nodeName,
                Array.from(node.attributes || [], a => [a.name, a.value]).sort(), Array.from(node.childNodes, shape)];
              const definitions = () => JSON.stringify({phases, nativePhases,
                assets:Array.from(document.querySelectorAll('style,script,link[rel="stylesheet"]'), n => n.outerHTML)});
              const originalDefinitions = definitions();
              const isFailedCapture = expected.literalEpisode && expected.prototypePhase === 'failed';
              const selectors = ['#status-title','#status-eyebrow','.device .micro-label','.device-name',
                '#connection-hint','#status-detail','#receiver-details'];
              const copySelectors = selectors.slice(0, 5).concat('#standby-view');
              const messageSelector = phase => '.e-status-message[data-status="' + phase + '"]';
              const hardline = expected.theme === 'HARDLINE';
              const readMotion = (nodes, message) => {
                const copyStates = nodes.map(node => {
                  const style = getComputedStyle(node);
                  return {node:node.id || node.className, text:node.id === 'connection-hint' ? node.textContent : undefined,
                    display:style.display, visibility:style.visibility, opacity:Number(style.opacity),
                    hasClientRects:node.getClientRects().length > 0,
                    activeAnimations:node.getAnimations().filter(a => a.pending || a.playState === 'running').length};
                });
                const channelSettled = !message || getComputedStyle(message).visibility === 'visible' &&
                  Number(getComputedStyle(message).opacity) >= .999 &&
                  message.getAnimations().every(a => !a.pending && a.playState !== 'running');
                return {copyStates, settled:mainCopyChanges.size === 0 && channelSettled && copyStates.every(s =>
                  s.activeAnimations === 0 && (!s.hasClientRects || s.opacity >= .999))};
              };
              if (expected.literalEpisode && !isFailedCapture) {
                // This entry closure never pins hint children. The source may replace them during its fade/swap.
                window.__failedEpisodeReadiness = failed => {
                  const phase = failed ? 'failed' : 'connecting', mapping = failed ? null : 'CONNECTING';
                  const variant = hardline ? 'D' : 'A';
                  const inspector = JSON.parse(required('#state-json').textContent);
                  const stageNode = required('#stage');
                  const live = {phase:state.phase, nativePhase:nativePhaseFor(state.phase), broadcast:state.broadcast,
                    variant:state.variant, deviceName:state.deviceName, lastAction:state.lastAction};
                  const stageState = {phase:stageNode.getAttribute('data-phase'), nativePhase:stageNode.getAttribute('data-native-phase'),
                    broadcast:stageNode.getAttribute('data-broadcast'), variant:stageNode.getAttribute('data-variant')};
                  requireValue(live.phase === phase && live.nativePhase === mapping && live.broadcast === true &&
                    live.variant === variant && live.deviceName === expected.name && state.home === 'big' && state.media.kind === 'none' &&
                    (!failed || live.lastAction === 'simulate-failed'), 'Failed-episode live state drift: ' + JSON.stringify(live));
                  requireValue(Object.hasOwn(inspector, 'nativePhase') && inspector.nativePhase === mapping &&
                    inspector.phase === live.phase && inspector.broadcast === live.broadcast && inspector.variant === live.variant &&
                    inspector.lastAction === live.lastAction, 'Failed-episode inspector drift: ' + JSON.stringify(inspector));
                  // A missing attribute reads as null, not the empty string required for the failed episode.
                  requireValue(stageState.phase === phase && stageState.nativePhase === (mapping ?? '') &&
                    stageState.broadcast === 'true' && stageState.variant === variant, 'Failed-episode stage drift: ' + JSON.stringify(stageState));
                  const overlays = {settingsOpen:required('#settings').open, receiverDetailsOpen:required('#receiver-details').open,
                    toolsHidden:required('#prototype-tools-panel').hidden, connectionPreviewHidden:required('#connection-preview').hidden,
                    controlsHidden:required('#controls').hidden, inspectorOpen:required('#inspector').open, toastHidden:required('#toast').hidden};
                  requireValue(overlays.settingsOpen === false && overlays.receiverDetailsOpen === false && overlays.toolsHidden === true &&
                    overlays.connectionPreviewHidden === true && overlays.controlsHidden === true && overlays.inspectorOpen === false &&
                    overlays.toastHidden === true && ['settingsOpen','controlsOpen','inspectorOpen','prototypeTools'].every(key =>
                      state[key] === false && inspector[key] === false), 'Failed-episode overlay drift: ' + JSON.stringify(overlays));
                  requireValue(document.documentElement.lang === 'zh-Hant', 'Prototype document locale drift');
                  requireValue(definitions() === originalDefinitions, 'Original CSS/scripts/phase constants changed since entry');
                  const active = hardline ? required('#e-scene ' + messageSelector(phase)) : null;
                  const motion = readMotion(copySelectors.map(required), active);
                  return {settled:motion.settled, copyStates:motion.copyStates, live, inspector, stage:stageState, overlays};
                };
              }
              if (isFailedCapture) requireValue(window.__failedEpisodeReadiness(true).settled, 'Wait for source settlement before pinning markup');
              if (hardline) selectors.push((expected.literalEpisode ? '#e-scene ' : '') + messageSelector(expected.prototypePhase));
              const roots = selectors.map(selector => {
                const node = required(selector);
                return {selector, node, descendants:Array.from(node.querySelectorAll('*')), shape:shape(node)};
              });
              const [title, eyebrow, label, receiver, hint, detail, dialog] = roots.map(r => r.node);
              const bindText = (node, selector, originals, replacement, sourceResource, reason) => {
                requireValue(node.childNodes.length === 1 && node.firstChild.nodeType === 3,
                  'Unexpected text-only structure: ' + selector);
                const original = node.textContent;
                requireValue(originals.includes(original) || original === replacement, 'Unexpected copy: ' + selector + ': ' + original);
                node.firstChild.data = replacement;
                substitutions.push({selector, original, replacement, reason, sourceResource, theme:expected.theme, case:expected.case});
              };
              if (expected.literalEpisode) {
                for (const node of [title, eyebrow, label, receiver]) {
                  requireValue(node.childNodes.length === 1 && node.firstChild.nodeType === 3,
                    'Unexpected literal text structure: ' + (node.id || node.className));
                }
              } else {
                requireValue(!expected.english || expected.phase === 'ADVERTISING', 'English normalization is advertising-only');
                bindText(title, '#status-title', expected.english ? ['等待連接'] : [copy.title], copy.title,
                  copy.titleResource, 'Resource-bound phase title; text only (unchanged unless English).');
                bindText(eyebrow, '#status-eyebrow', [copy.eyebrow, phases[expected.prototypePhase].eyebrow.replace(' · SIMULATED', '')], copy.eyebrow,
                  copy.eyebrowResource, 'Assert resource-bound Latin eyebrow as-is.');
                bindText(label, '.device .micro-label', ['模擬接收裝置 / RECEIVER', 'RECEIVER'], copy.receiverLabel,
                  'R.string.standby_foreground_receiver_label', 'Remove only the known simulation prefix, if present.');
                bindText(receiver, '.device-name', [expected.name], expected.name,
                  'case.receiverName', 'Assert raw URL-bound receiver identity as-is.');
              }
              if (!hardline && (expected.phase === 'ADVERTISING' || isFailedCapture)) {
                requireValue(hint.childNodes.length === 4 && hint.childNodes[0].nodeType === 3 && hint.childNodes[2].nodeType === 3,
                  'Unexpected inviting hint structure');
                const shortcut = hint.childNodes[1], group = hint.childNodes[3];
                requireValue(shortcut.nodeName === 'SPAN' && shortcut.className === 'shortcut' &&
                  group.nodeName === 'SPAN' && group.className === 'receiver-name', 'Missing original shortcut/receiver elements');
                requireValue(shortcut.childNodes.length === 3 && shortcut.childNodes[0].nodeName === 'KBD' &&
                  shortcut.childNodes[1].nodeType === 3 && shortcut.childNodes[1].textContent === ' + ' &&
                  shortcut.childNodes[2].nodeName === 'KBD' && shortcut.childNodes[0].textContent === 'Win' &&
                  shortcut.childNodes[2].textContent === 'K' && [shortcut.childNodes[0], shortcut.childNodes[2]].every(kbd =>
                    kbd.childNodes.length === 1 && kbd.firstChild.nodeType === 3), 'Unexpected original keycaps');
                if (expected.english && !expected.literalEpisode) {
                  const keyStart = copy.hint.indexOf('Win + K'), nameStart = copy.hint.lastIndexOf(expected.name);
                  requireValue(keyStart >= 0 && nameStart > keyStart + 7 && copy.hint.endsWith(expected.name),
                    'Unsupported English resource hint format');
                  for (const [index, original, replacement, path] of [
                    [0, 'Windows 按 ', copy.hint.slice(0, keyStart), '#connection-hint/childNodes[0]'],
                    [2, '，選擇', copy.hint.slice(keyStart + 7, nameStart), '#connection-hint/childNodes[2]']]) {
                    const node = hint.childNodes[index], before = node.data;
                    requireValue(before === original || before === replacement, 'Unexpected hint text: ' + path);
                    node.data = replacement;
                    substitutions.push({selector:path, original:before, replacement,
                      reason:'English resource format segment; preserve original elements and attributes.',
                      sourceResource:'R.string.standby_foreground_connection_hint_format', theme:expected.theme, case:expected.case});
                  }
                  bindText(group, '#connection-hint .receiver-name', ['「' + expected.name + '」'], expected.name,
                    'case.receiverName', 'Raw receiver text in the English resource format; no HTML parsing.');
                }
              }
              const message = expected.theme === 'HARDLINE' ? roots[7].node : null;
              if (message) {
                const phrases = Array.from(message.querySelectorAll('.e-status-phrase'));
                requireValue(phrases.length === 16, 'Unexpected original repeated channel structure');
                phrases.forEach((node, nodeIndex) => {
                  requireValue(node.textContent === copy.channelStatus, 'Unexpected active Hardline phrase: ' + node.textContent);
                  if (!expected.literalEpisode) substitutions.push({selector:selectors[7] + ' .e-status-phrase', nodeIndex, original:node.textContent,
                    replacement:node.textContent, reason:'Expected original English active channel copy asserted as-is; markup retained.',
                    sourceResource:expected.phase === 'ADVERTISING' ? 'R.string.standby_foreground_connection_hint_format (en)' :
                      'design/big-standby-e-scene.js/statusCopy', theme:expected.theme, case:expected.case});
                });
              }
              const observe = () => {
                requireValue(definitions() === originalDefinitions, 'Original CSS/scripts/phase constants changed');
                const markup = roots.map(r => {
                  const descendants = Array.from(r.node.querySelectorAll('*'));
                  requireValue(document.querySelector(r.selector) === r.node && descendants.length === r.descendants.length &&
                    descendants.every((n, i) => n === r.descendants[i]), 'Original element identity changed: ' + r.selector);
                  const current = shape(r.node);
                  requireValue(JSON.stringify(current) === JSON.stringify(r.shape), 'Original structure/style attributes changed: ' + r.selector);
                  return {selector:r.selector, shape:current};
                });
                const episode = expected.literalEpisode ? window.__failedEpisodeReadiness(isFailedCapture) : null;
                if (!episode) {
                  requireValue(state.phase === expected.prototypePhase && nativePhaseFor(state.phase) === expected.phase &&
                    state.broadcast === expected.broadcast && state.deviceName === expected.name &&
                    state.variant === (hardline ? 'D' : 'A') && state.media.kind === 'none', 'Reference case drift');
                }
                requireValue(document.documentElement.lang === 'zh-Hant', 'Prototype document locale drift');
                // Literal episode: the reference is pinned at expected.prototypePhase, so the DOM carries that
    // phase's own eyebrow with ' · SIMULATED' stripped (design/big-standby-prototype.html:1893)
    // and the bare 'RECEIVER' label (line 1894), while the copy contract holds the native zh
    // resources. Measured on device (p2redc5-20261124-10): title and receiver name already match.
    requireValue(title.textContent === copy.title &&
        (eyebrow.textContent === copy.eyebrow ||
            (episode && eyebrow.textContent === phases[expected.prototypePhase].eyebrow.replace(' · SIMULATED', ''))) &&
                  (label.textContent === copy.receiverLabel || (episode && label.textContent === 'RECEIVER')) &&
        receiver.textContent === expected.name,
          episode
        ? ('Literal primary copy drift: title=' + title.textContent + ' want=' + copy.title +
            ' | eyebrow=' + eyebrow.textContent + ' want=' + copy.eyebrow +
            ' | label=' + label.textContent + ' want=' + copy.receiverLabel +
            ' | receiver=' + receiver.textContent + ' want=' + expected.name)
        : 'Normalized primary copy drift');
                requireValue(hint.textContent === (hardline ? '' : copy.hint), 'Reference full hint drift');
                requireValue(detail.textContent === '' && detail.hidden && getComputedStyle(detail).display === 'none', 'Non-Matter detail drift');
                requireValue(!dialog.open, 'Reference dialog was opened; no persistent-notice counterpart is implied');
                const phrases = message ? Array.from(message.querySelectorAll('.e-status-phrase')) : [];
                if (message) requireValue(phrases.length === 16 && phrases.every(n => n.textContent === copy.channelStatus), 'Active channel copy drift');
                const motion = episode || readMotion([title, eyebrow, label, receiver, hint, required('#standby-view')], message);
                if (episode) {
                  if (isFailedCapture && !hardline) {
                    const group = hint.childNodes[3];
                    requireValue(hint.childNodes[0].textContent === 'Windows 按 ' && hint.childNodes[2].textContent === '，選擇' &&
                      hint.childNodes[1].textContent === 'Win + K' && group.childNodes.length === 1 && group.firstChild.nodeType === 3 &&
                      group.textContent === '「' + expected.name + '」', 'Literal failed invitation structure/copy drift');
                    requireValue(hint.getClientRects().length > 0 && getComputedStyle(hint).visibility === 'visible', 'Failed invitation is hidden');
                  } else {
                    requireValue(hint.childNodes.length === 0 && getComputedStyle(hint).display === 'none', 'Unexpected reference foreground hint');
                  }
                  if (message && motion.settled) {
                    const style = getComputedStyle(message);
                    requireValue(message.getClientRects().length > 0, 'Active Hardline channel has no layout box');
                    if (isFailedCapture) requireValue(phrases.every(n => getComputedStyle(n).color === 'rgb(255, 107, 107)'),
                      'Failed Hardline phrases must use the original #ff6b6b status color');
                    episode.channel = {selector:selectors[7], phase:message.getAttribute('data-status'), color:style.color,
                      opacity:Number(style.opacity), visibility:style.visibility, phrases:phrases.map(n => n.textContent)};
                  }
                  const announcement = required('#announcement');
                  if (isFailedCapture) requireValue(announcement.textContent === copy.title, 'Missing source failure announcement');
                  episode.announcement = {text:announcement.textContent, role:announcement.getAttribute('role'),
                    nativePersistentNoticeCounterpart:'UNSUPPORTED'};
                }
                const observation = {settled:motion.settled, documentLocale:document.documentElement.lang,
                  sourceDefinitionsUnchanged:true, markup, copyStates:motion.copyStates,
                  state:{phase:state.phase, nativePhase:nativePhaseFor(state.phase), broadcast:state.broadcast,
                    deviceName:state.deviceName, variant:state.variant},
                  copy:{title:title.textContent, eyebrow:eyebrow.textContent, receiverLabel:label.textContent,
                    receiverName:receiver.textContent, hint:hint.textContent, detail:detail.textContent,
                    channelStatus:message ? message.querySelector('.e-status-phrase').textContent : null},
                  receiverDetailsDialog:{present:true, open:dialog.open, nativePersistentNoticeCounterpart:'UNSUPPORTED'}};
                if (episode) observation.failedEpisode = episode;
                return observation;
              };
              window.__supplementalReferenceObservation = observe;
              if (expected.literalEpisode) return observe();
              window.dispatchEvent(new Event('resize'));
              return {substitutions, originalMarkup:roots.map(r => ({selector:r.selector, shape:r.shape})), normalized:observe()};
            })()
        """.trimIndent())
    }

    /** @return Validated copy/markup and actual window observations at entry or at a reference PNG bracket. */
    private fun supplementalReferenceObservation(
        scenario: ActivityScenario<StandbyProbeActivity>,
        reference: WebView,
        theme: StandbyThemeId,
        case: SupplementalCase,
        copy: JSONObject,
        prototypePhase: String = case.referenceCapturePhase,
    ): JSONObject {
        val observation = "window.__supplementalReferenceObservation()"
        val snapshot = domSnapshotScript(prototypePhase, isSupplemental = true, includeChannelColor = case.failureCause != null)
        val (invariants, dom) = if (case.failureCause != null) {
            // Read the pinned invariants and the phase-selected DOM metrics in one script turn.
            val sample = evaluate(scenario, reference, "({invariants:$observation, dom:$snapshot})")
            sample.getJSONObject("invariants") to sample.getJSONObject("dom")
        } else {
            evaluate(scenario, reference, observation) to evaluate(scenario, reference, snapshot)
        }
        var window = JSONObject()
        awaitActivityAction(scenario) { activity ->
            window = windowMetrics(activity, reference,
                if (case.landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT, case.locale)
        }
        // Measured on device (p2redc6-20261124-10): the DOM eyebrow is 'CONNECTING' where the native
        // resource is '正在連接', and the DOM label is the bare 'RECEIVER' where the native resource is
        // '接收裝置'. Both are the frozen prototype's own copy, so they are recorded, not gated.
        assertReferenceViewport(dom, window, theme, case.phase, prototypePhase, case.advertisingEnabled,
            case.receiverName, copy.getString("title"))
        assertTrue("Reference copy alpha/animations must remain settled: $invariants", invariants.getBoolean("settled"))
        if (case.failureCause != null && theme == StandbyThemeId.HARDLINE) {
            val channel = dom.getJSONObject("elements").getJSONObject("channelStatus")
            val active = invariants.getJSONObject("failedEpisode").getJSONObject("channel")
            assertEquals("Active channel DOM selector", active.getString("selector") + " .e-status-phrase", channel.getString("selector"))
            assertEquals("Active channel phase", prototypePhase, active.getString("phase"))
            assertEquals(copy.getString("channelStatus"), channel.getString("text"))
            assertEquals("visible", channel.getJSONObject("style").getString("visibility"))
            assertTrue("Active channel phrase has measured DOM bounds", channel.getBoolean("hasClientRects"))
            if (prototypePhase == "failed") {
                assertEquals("rgb(255, 107, 107)", channel.getJSONObject("style").getString("color"))
            }
        }
        return JSONObject().put("dom", dom).put("window", window).put("copyAndMarkup", invariants)
    }

    /** @return Validated actual native state/roles and window observations on either side of a native PNG. */
    private fun supplementalNativeObservation(
        scenario: ActivityScenario<StandbyProbeActivity>,
        host: BigStandbyHost,
        theme: StandbyThemeId,
        case: SupplementalCase,
        expectedState: StandbyForegroundState,
        copy: JSONObject,
    ): JSONObject {
        var window = JSONObject()
        var native = JSONObject()
        awaitActivityAction(scenario) { activity ->
            window = windowMetrics(activity, host,
                if (case.landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT, case.locale)
            native = nativeSnapshot(host, theme, expectedState, copy.getString("title"), copy.getString("eyebrow"), isSupplemental = true)
        }
        return JSONObject().put("window", window).put("native", native)
    }

    /** Compares only stable window/context identity; ambient rectangles and clock epochs may move. */
    private fun assertSameWindow(reference: JSONObject, native: JSONObject) {
        for (key in listOf("rootRectWindowPx", "viewportRectWindowPx", "density", "densityDpi", "fontScale", "scaledDensity",
            "insetsPx", "orientation", "contextLocales", "activityLocales", "windowRectPx", "systemBarsVisible")) {
            assertEquals("Paired/capture-bracket window changed: $key", reference.get(key).toString(), native.get(key).toString())
        }
    }

    /** @return Original DOM Range line flow and an isolated unbroken specimen, without changing source presentation. */
    private fun measureReferenceTitleAdvance(
        scenario: ActivityScenario<StandbyProbeActivity>,
        reference: WebView,
        expectedText: String,
    ): JSONObject = evaluate(scenario, reference, """
        (() => {
          const requireValue = (ok, reason) => { if (!ok) throw new Error('Title advance: ' + reason); };
          requireValue(window.__supplementalReferenceObservation().settled, 'original copy is not settled');
          const title = document.getElementById('status-title');
          requireValue(title && title.childNodes.length === 1 && title.firstChild.nodeType === 3, 'original title text Range is unavailable');
          const textNode = title.firstChild, text = title.textContent;
          requireValue(text === ${JSONObject.quote(expectedText)} && text.length > 0 && Array.from(text).every(c =>
            c.charCodeAt(0) >= 32 && c.charCodeAt(0) <= 126), 'expected resource-bound ASCII title is unavailable');
          const typography = ['font-family','font-size','font-weight','font-style','font-stretch','font-kerning',
            'font-feature-settings','font-variation-settings','font-optical-sizing','font-size-adjust','font-synthesis',
            'font-variant','font-variant-ligatures','font-variant-caps','font-variant-numeric','font-variant-east-asian',
            'font-variant-position','letter-spacing','word-spacing','line-height','text-transform','text-rendering',
            'direction','unicode-bidi','writing-mode','text-orientation','-webkit-text-size-adjust','text-size-adjust','-webkit-locale'];
          const wrapping = ['white-space','text-wrap','text-wrap-mode','text-wrap-style','word-break','overflow-wrap','line-break','hyphens'];
          const properties = (style, names) => Object.fromEntries(names.map(p => [p, style.getPropertyValue(p)]));
          const languageContext = node => {
            const closestLang = node.closest('[lang]');
            return {computed:properties(getComputedStyle(node),['-webkit-locale','font-language-override']),
              closestLang:closestLang ? {nodeName:closestLang.nodeName, lang:closestLang.getAttribute('lang')} : null};
          };
          const originalTitleLanguageContext = languageContext(title);
          const pixels = (style, property) => {
            const value = style.getPropertyValue(property);
            requireValue(/^-?(?:\d+\.?\d*|\.\d+)px$/.test(value), 'unresolved ' + property + ': ' + value);
            return Number.parseFloat(value);
          };
          const relativeRect = (r, origin) => ({left:r.left-origin.left, top:r.top-origin.top,
            right:r.right-origin.left, bottom:r.bottom-origin.top, width:r.width, height:r.height});
          const rangeSample = (node, start, end, origin) => {
            const range = document.createRange();
            range.setStart(node.firstChild, start); range.setEnd(node.firstChild, end);
            return {start, end, text:node.textContent.slice(start,end),
              rectCssPx:relativeRect(range.getBoundingClientRect(), origin),
              rectsCssPx:Array.from(range.getClientRects(), r => relativeRect(r, origin))};
          };
          const lineBands = rects => {
            const lines = [];
            for (const r of rects.filter(r => r.width > 0 && r.height > 0).sort((a,b) => a.top-b.top || a.left-b.left)) {
              if (!lines.some(line => line.top === r.top && line.bottom === r.bottom)) lines.push({top:r.top, bottom:r.bottom});
            }
            requireValue(lines.length > 0, 'no measurable original title Range lines');
            return lines.map((line,index) => ({...line, index}));
          };
          const langNode = title.closest('[lang]'), dirNode = title.closest('[dir]');
          requireValue(langNode && langNode.getAttribute('lang'), 'effective ancestor language is unavailable');
          const language = {documentLocale:document.documentElement.lang, ancestorLanguage:langNode.getAttribute('lang'),
            languageNode:langNode.nodeName, explicitDirection:dirNode ? dirNode.getAttribute('dir') : null,
            computedDirection:getComputedStyle(title).direction};
          const observeOriginal = () => {
            const style = getComputedStyle(title), box = title.getBoundingClientRect();
            const full = rangeSample(title,0,text.length,box), lines = lineBands(full.rectsCssPx);
            const assigned = (start,end) => {
              const sample = rangeSample(title,start,end,box);
              requireValue(sample.rectsCssPx.some(r => r.width > 0 && r.height > 0) || /^\s+$/.test(sample.text),
                'unmeasurable non-whitespace Range at ' + start);
              sample.lineIndices = Array.from(new Set(sample.rectsCssPx.filter(r => r.height > 0).map(r => {
                const matches = lines.filter(line => r.top === line.top && r.bottom === line.bottom);
                requireValue(matches.length === 1, 'unmeasurable/ambiguous ASCII character/word line assignment at ' + start);
                return matches[0].index;
              })));
              return sample;
            };
            const padding = properties(style,['padding-left','padding-top','padding-right','padding-bottom']);
            const borders = properties(style,['border-left-width','border-right-width','border-top-width','border-bottom-width']);
            const horizontalInsets = pixels(style,'padding-left') + pixels(style,'padding-right') +
              pixels(style,'border-left-width') + pixels(style,'border-right-width');
            const computedWidth = pixels(style,'width'), indent = pixels(style,'text-indent');
            const contentWidth = computedWidth - (style.boxSizing === 'border-box' ? horizontalInsets : 0);
            requireValue(contentWidth > 0 && contentWidth-indent > 0, 'first-line content budget is not measurable');
            return {text:title.textContent, typography:properties(style,typography), wrapping:properties(style,wrapping), language,
              widthCssPx:box.width, heightCssPx:box.height, computedWidthCssPx:computedWidth, boxSizing:style.boxSizing,
              padding, borders, contentWidthCssPx:contentWidth, contentWidthFromRectCssPx:box.width-horizontalInsets,
              textAlign:style.textAlign, textIndent:style.textIndent, firstLineIndentCssPx:indent,
              firstLineBudgetCssPx:contentWidth-indent, fullRange:full, lineCount:lines.length, lines,
              lineAssignmentRule:'Distinct measured top/bottom intervals in the known single ASCII text node; ambiguous fragments fail, no line-height estimate.',
              characters:Array.from({length:text.length},(_,i) => assigned(i,i+1)),
              words:Array.from(text.matchAll(/\S+/g), m => assigned(m.index,m.index+m[0].length))};
          };
          const before = observeOriginal(), parent = title.parentNode, siblings = Array.from(parent.childNodes);
          const originalAttributes = JSON.stringify(Array.from(title.attributes, a => [a.name,a.value]));
          const marker = '[data-mirax-title-advance-specimen]';
          requireValue(document.querySelectorAll(marker).length === 0, 'residual diagnostic specimen before measurement');
          const specimen = document.createElement('span');
          specimen.setAttribute('data-mirax-title-advance-specimen',''); specimen.setAttribute('aria-hidden','true');
          specimen.setAttribute('lang',language.ancestorLanguage); specimen.setAttribute('dir',language.computedDirection);
          specimen.textContent = text;
          const overrides = {all:'initial', position:'fixed', left:'0px', top:'0px', display:'inline-block',
            width:'max-content', 'min-width':'0px', 'max-width':'none', height:'auto', margin:'0px', padding:'0px', border:'0px',
            'box-sizing':'content-box', 'white-space':'nowrap', 'text-wrap':'nowrap', 'text-indent':'0px',
            visibility:'hidden', 'pointer-events':'none', contain:'layout style paint', isolation:'isolate', transform:'none'};
          for (const [p,value] of Object.entries(overrides)) specimen.style.setProperty(p,value,'important');
          for (const [p,value] of Object.entries(before.typography)) if (value) specimen.style.setProperty(p,value,'important');
          const unbroken = () => {
            const box = specimen.getBoundingClientRect(), sample = rangeSample(specimen,0,text.length,box);
            const style = properties(getComputedStyle(specimen),typography);
            requireValue(lineBands(sample.rectsCssPx).length === 1 && sample.rectCssPx.width > 0 && box.width > 0,
              'unbroken specimen Range/box is not measurable');
            return {rangeAdvanceCssPx:sample.rectCssPx.width, boxAdvanceCssPx:box.width, range:sample, typography:style,
              fullContextText:specimen.textContent, languageContext:languageContext(specimen),
              wordAndSpaceRanges:Array.from(text.matchAll(/\S+|\s+/g), m =>
                rangeSample(specimen,m.index,m.index+m[0].length,box))};
          };
          let withTracking, withoutTracking, after, failure = null;
          try {
            document.body.appendChild(specimen);
            withTracking = unbroken();
            for (const p of typography) requireValue(withTracking.typography[p] === before.typography[p], 'specimen typography differs: ' + p);
            specimen.style.setProperty('letter-spacing','0px','important');
            withoutTracking = unbroken();
            for (const p of typography.filter(p => p !== 'letter-spacing')) requireValue(
              withTracking.typography[p] === withoutTracking.typography[p], 'zero-tracking specimen changed ' + p);
          } catch (error) { failure = error; }
          finally {
            specimen.remove();
            try {
              requireValue(document.querySelectorAll(marker).length === 0, 'residual diagnostic specimen after measurement');
              requireValue(document.getElementById('status-title') === title && title.firstChild === textNode &&
                title.parentNode === parent && siblings.length === parent.childNodes.length &&
                siblings.every((n,i) => n === parent.childNodes[i]), 'original title/sibling identity changed');
              requireValue(originalAttributes === JSON.stringify(Array.from(title.attributes, a => [a.name,a.value])), 'original title attributes changed');
              after = observeOriginal();
              requireValue(JSON.stringify(before) === JSON.stringify(after), 'original text/typography/size/Range line flow changed');
              requireValue(window.__supplementalReferenceObservation().settled, 'original case/copy/markup did not remain settled');
            } catch (error) {
              failure = failure ? new Error(String(failure) + '\n' + failure.stack + '; specimen recovery: ' + String(error) + '\n' + error.stack) : error;
            }
          }
          if (failure) throw failure;
          return {units:'CSS px; Range rectangles are relative to their title/specimen border box, not a synchronized ambient epoch',
            originalBefore:before, originalAfter:after, originalUnchanged:true, residualSpecimens:document.querySelectorAll(marker).length,
            specimen:{element:'span with no id', language, diagnosticOverrides:overrides, priority:'important',
              secondMeasurementOnlyOverride:{'letter-spacing':'0px'}, withTracking, withoutTracking,
              trackingRangeImpactCssPx:withTracking.rangeAdvanceCssPx-withoutTracking.rangeAdvanceCssPx,
              trackingBoxImpactCssPx:withTracking.boxAdvanceCssPx-withoutTracking.boxAdvanceCssPx},
            limits:'CSS family is not resolved-font identity. Zero tracking retains shaping, word spacing and other exposed typography. Empty property values mean not exposed.',
            originalTitleLanguageContext,
            rangeControlsNote:'Word/space Ranges retain the complete text node. DOM Range bounds and native advances are not glyph ink or guaranteed equivalent; equality is not a test gate. Computed -webkit-locale is preserved by the typography copy/check; font-language-override remains observation-only and empty means not exposed.'};
        })()
    """.trimIndent())

    /** @return Actual Layout/paint advances; a detached TextPaint copy alone receives zero letter spacing. */
    private fun measureNativeTitleAdvance(host: BigStandbyHost, expectedText: String): JSONObject {
        val foreground = descendants(host).filterIsInstance<StandbyForegroundView>().single { it.isShown }
        check(foreground is LandscapeStandbyForegroundView) { "Title advance requires the selected Landscape foreground" }
        val title = checkNotNull(foreground.findViewWithTag<TextView>("foreground_phase_title"))
        val text = title.text
        val paint = title.paint
        val layout = checkNotNull(title.layout) { "Native title Layout is unavailable" }
        assertEquals(expectedText, text.toString())
        assertEquals("Layout must contain the actual title", text.toString(), layout.text.toString())
        check(layout.width > 0 && layout.lineCount > 0) { "Native title line flow/budget is not measurable" }
        val leadingMargins = (text as? android.text.Spanned)?.getSpans(0, text.length, android.text.style.LeadingMarginSpan::class.java)
        fun paintState(value: TextPaint): JSONObject = JSONObject().put("textSizePx", value.textSize)
            .put("letterSpacingEm", value.letterSpacing).put("textScaleX", value.textScaleX).put("textSkewX", value.textSkewX)
            .put("typefaceWeight", value.typeface?.weight ?: JSONObject.NULL).put("typefaceStyle", value.typeface?.style ?: JSONObject.NULL)
            .put("fontFeatureSettings", value.fontFeatureSettings ?: JSONObject.NULL)
            .put("fontVariationSettings", value.fontVariationSettings ?: JSONObject.NULL)
            .put("textLocales", value.textLocales.toLanguageTags()).put("flags", value.flags)
            .put("subpixelText", value.isSubpixelText).put("linearText", value.isLinearText).put("fakeBoldText", value.isFakeBoldText)
            .put("density", value.density).put("baselineShiftPx", value.baselineShift)
            .put("resolvedFontFamily", "NOT_EXPOSED_BY_TEXTVIEW_OR_TEXTPAINT")
        fun snapshot(): JSONObject {
            val actualLayout = checkNotNull(title.layout)
            val paragraphLeft = actualLayout.getParagraphLeft(0)
            val paragraphRight = actualLayout.getParagraphRight(0)
            check(paragraphRight > paragraphLeft) { "Native first-line paragraph budget is not measurable" }
            val textEvidence = nativeView(title, host, isSupplemental = true).getJSONArray("textViews").getJSONObject(0)
            return JSONObject().put("text", title.text.toString()).put("widthPx", title.width).put("heightPx", title.height)
                .put("layoutWidthPx", actualLayout.width).put("layoutHeightPx", actualLayout.height).put("lineCount", actualLayout.lineCount)
                .put("lines", textEvidence.getJSONArray("lines")).put("baselineViewPx", title.baseline)
                .put("lineHeightPx", title.lineHeight).put("lineSpacingExtraPx", title.lineSpacingExtra)
                .put("lineSpacingMultiplier", title.lineSpacingMultiplier)
                .put("lineLeftRightPx", JSONArray((0 until actualLayout.lineCount).map { i ->
                    JSONArray(listOf(actualLayout.getLineLeft(i), actualLayout.getLineRight(i)))
                }))
                .put("paddingPx", JSONArray(listOf(title.paddingLeft, title.paddingTop, title.paddingRight, title.paddingBottom)))
                .put("compoundPaddingPx", JSONArray(listOf(title.compoundPaddingLeft, title.compoundPaddingTop, title.compoundPaddingRight, title.compoundPaddingBottom)))
                .put("totalPaddingPx", JSONArray(listOf(title.totalPaddingLeft, title.totalPaddingTop, title.totalPaddingRight, title.totalPaddingBottom)))
                .put("contentAvailableWidthPx", title.width - title.compoundPaddingLeft - title.compoundPaddingRight)
                .put("firstParagraphLeftPx", paragraphLeft).put("firstParagraphRightPx", paragraphRight)
                .put("firstLineLeadingMarginPx", actualLayout.width - (paragraphRight - paragraphLeft))
                .put("firstLineBudgetPx", paragraphRight - paragraphLeft).put("leadingMarginSpanCount", leadingMargins?.size ?: 0)
                .put("paint", paintState(title.paint)).put("textLocales", title.textLocales.toLanguageTags())
                .put("textDirection", title.textDirection).put("layoutDirection", title.layoutDirection).put("textAlignment", title.textAlignment)
                .put("paragraphDirection", actualLayout.getParagraphDirection(0)).put("breakStrategy", title.breakStrategy)
                .put("hyphenationFrequency", title.hyphenationFrequency).put("justificationMode", title.justificationMode)
                .put("includeFontPadding", title.includeFontPadding).put("ellipsize", title.ellipsize?.name ?: JSONObject.NULL)
                .put("maxLines", title.maxLines).put("visibility", title.visibility).put("shown", title.isShown)
        }
        /** Measures prefix positions with the entire unchanged title as both run and shaping context. */
        fun rangeControls(value: TextPaint): JSONObject = JSONObject()
            .put("api", "android.graphics.Paint.getRunAdvance(CharSequence, int, int, int, int, boolean, int)")
            .put("fullContextText", text.toString()).put("runStart", 0).put("runEnd", text.length)
            .put("contextStart", 0).put("contextEnd", text.length).put("isRtl", false)
            .put("actualParagraphDirection", layout.getParagraphDirection(0))
            .put("fullRunAdvancePx", value.getRunAdvance(text, 0, text.length, 0, text.length, false, text.length))
            .put("ranges", JSONArray(Regex("\\S+|\\s+").findAll(text).map { match ->
                val start = match.range.first
                val end = match.range.last + 1
                val startAdvance = value.getRunAdvance(text, 0, text.length, 0, text.length, false, start)
                val endAdvance = value.getRunAdvance(text, 0, text.length, 0, text.length, false, end)
                JSONObject().put("start", start).put("end", end).put("text", match.value)
                    .put("prefixStartAdvancePx", startAdvance).put("prefixEndAdvancePx", endAdvance)
                    .put("advancePx", endAdvance - startAdvance)
            }.toList()))
            .put("semantics", "Range advance is prefix(end) minus prefix(start), with the full title retained as shaping context; no substring is independently shaped.")
        val before = snapshot()
        check(text.length == 22 && text.all { it in ' '..'~' } && layout.getParagraphDirection(0) == 1 &&
            text.indices.none { layout.isRtlCharAt(it) }) { "Range controls require the actual fixed 22-character ASCII LTR run" }
        val shapingSpans = (text as? android.text.Spanned)?.getSpans(0, text.length, android.text.style.MetricAffectingSpan::class.java)
        check(shapingSpans.isNullOrEmpty()) { "Range controls do not resolve MetricAffectingSpan shaping runs" }
        val leadingMargin = before.getInt("firstLineLeadingMarginPx")
        // Layout.getDesiredWidth includes paragraph margins; the observed budget already excludes this margin.
        val desiredWithMargin = Layout.getDesiredWidth(text, paint)
        val desired = desiredWithMargin - leadingMargin
        val zeroTrackingPaint = TextPaint().apply { set(paint); letterSpacing = 0f }
        val withoutTrackingWithMargin = Layout.getDesiredWidth(text, zeroTrackingPaint)
        val withoutTracking = withoutTrackingWithMargin - leadingMargin
        val withTrackingRanges = rangeControls(paint)
        val withoutTrackingRanges = rangeControls(zeroTrackingPaint)
        val after = snapshot()
        check(desired.isFinite() && withoutTracking.isFinite() && desired > 0f && withoutTracking > 0f) { "Native desired advance is unavailable" }
        assertTrue("Native paint/text/Layout identity must not change", title.paint === paint && title.text === text && title.layout === layout)
        assertEquals("Native width/line flow/paint must remain unchanged", before.toString(), after.toString())
        return JSONObject().put("units", "physical native pixels; letterSpacingEm is em")
            .put("originalBefore", before).put("originalAfter", after).put("originalUnchanged", true)
            .put("desiredAdvancePx", desired).put("desiredAdvanceWithoutTrackingPx", withoutTracking)
            .put("trackingImpactPx", desired - withoutTracking).put("zeroTrackingPaint", paintState(zeroTrackingPaint))
            .put("desiredWidthIncludingLeadingMarginPx", desiredWithMargin)
            .put("zeroTrackingDesiredWidthIncludingLeadingMarginPx", withoutTrackingWithMargin)
            .put("budgetDefinition", "First-line budget is actual Layout.getParagraphRight(0) minus getParagraphLeft(0), after compound padding. Desired advances exclude the observed first-line leading margin already charged to this budget; fixed single-paragraph ASCII case only.")
            .put("rangeControls", JSONObject().put("withTracking", withTrackingRanges).put("withoutTracking", withoutTrackingRanges)
                .put("note", "Full-context native advances and DOM Range bounding rectangles are not glyph ink or guaranteed equivalent; exact equality is not a new test gate."))
    }

    /** Captures four fixed cases in both themes: eight pairs, not a fidelity verdict. */
    @Test
    fun captureSupplementalFidelityPairs() = captureSupplementalPairs(SupplementalPack.FIDELITY)

    /** Measures one English Landscape pair, then asserts observed line-count parity after evidence persistence and cleanup. */
    @Test
    fun measureEnglishLandscapeTitleAdvance() = captureSupplementalPairs(SupplementalPack.ENGLISH_TITLE)

    /** Captures the zh-TW portrait pre-PLAY failed-episode presentation across Landscape and Hardline: two pairs. */
    @Test
    fun capturePortraitFailedEpisodePairs() = captureSupplementalPairs(SupplementalPack.FAILED_EPISODE)

    /** Reuses the existing capture/guard/recovery flow; the pack mode selects only its approved case set. */
    private fun captureSupplementalPairs(pack: SupplementalPack) {
        val safety = assertIsolatedPackage()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val run = requireNotNull(arguments.getString("baselineRun")) { "Pass -e baselineRun <unique-run-label>" }
        require(run.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))) { "Unsafe baselineRun label" }
        val nameArg = arguments.getString("baselineName") ?: "MIRAX · 客廳"
        require(nameArg.isNotBlank() && nameArg == nameArg.trim() && nameArg.codePointCount(0, nameArg.length) <= 40 &&
            nameArg.none { it.isISOControl() }) { "baselineName must be 1..40 code points without controls or edge spaces" }
        val assets = instrumentation.context.assets
        val rawHashes = assets.open("$ASSET_DIRECTORY/sources.sha256.json").use { it.readBytes() }
        val hashes = JSONObject(rawHashes.toString(Charsets.UTF_8))
        val originals = hashes.keys().asSequence().associateWith { path ->
            require(path.matches(SOURCE_PATH)) { "Unsafe source binding: $path" }
            assets.open("$ASSET_DIRECTORY/$path").use { it.readBytes() }.also { bytes ->
                assertEquals("Original source hash: design/$path", hashes.getString(path), sha256(bytes))
            }
        }
        val html = requireNotNull(originals[HTML_FILE]).toString(Charsets.UTF_8)
        val references = Regex("""(?:src|href)=["']([^"']+\.(?:css|js)(?:\?[^"']*)?)["']""")
            .findAll(html).map { it.groupValues[1].substringBefore('?') }.toSet()
        assertEquals("Bind only the HTML and its original CSS/JS references", references + HTML_FILE, originals.keys)
        checkRequestPaths(originals.keys)
        val output = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)),
            "standby-prototype-baseline/$run")
        check(!output.exists() && output.mkdirs()) { "Run directory must be new, never overwrite evidence: $output" }
        File(output, "sources.sha256.json").writeBytes(rawHashes)

        val cases = listOf(
            SupplementalCase(
                id = "zh-landscape-advertising",
                landscape = true,
                locale = Locale.TRADITIONAL_CHINESE,
                languageTag = "zh-TW",
                phase = ScreenPhase.ADVERTISING,
                prototypePhase = "waiting",
                advertisingEnabled = true,
                receiverName = "MIRAX · 客廳",
            ),
            SupplementalCase(
                id = "en-portrait-long-advertising",
                landscape = false,
                locale = Locale.ENGLISH,
                languageTag = "en",
                phase = ScreenPhase.ADVERTISING,
                prototypePhase = "waiting",
                advertisingEnabled = true,
                receiverName = "Studio Display Meeting Room North 01",
            ),
            SupplementalCase(
                id = "zh-portrait-wifi-paused",
                landscape = false,
                locale = Locale.TRADITIONAL_CHINESE,
                languageTag = "zh-TW",
                phase = ScreenPhase.WIFI_PAUSED,
                prototypePhase = "wifi-paused",
                advertisingEnabled = true,
                receiverName = nameArg,
            ),
            SupplementalCase(
                id = "zh-portrait-frozen",
                landscape = false,
                locale = Locale.TRADITIONAL_CHINESE,
                languageTag = "zh-TW",
                phase = ScreenPhase.FROZEN,
                prototypePhase = "frozen",
                advertisingEnabled = false,
                receiverName = nameArg,
                shouldOfferShizuku = false,
            ),
            SupplementalCase(
                id = "zh-portrait-preplay-timeout-failed",
                landscape = false,
                locale = Locale.TRADITIONAL_CHINESE,
                languageTag = "zh-TW",
                phase = ScreenPhase.CONNECTING,
                prototypePhase = "connecting",
                advertisingEnabled = true,
                receiverName = nameArg,
                shouldOfferShizuku = false,
                failureCause = PrePlayFailureCause.PRE_PLAY_TIMEOUT,
            ),
        ).let { allCases ->
            when (pack) {
                SupplementalPack.FIDELITY -> allCases.filter { it.failureCause == null }
                SupplementalPack.ENGLISH_TITLE -> allCases.filter { it.id == "en-portrait-long-advertising" }
                SupplementalPack.FAILED_EPISODE -> allCases.filter { it.id == "zh-portrait-preplay-timeout-failed" }
            }
        }

        val pairs = JSONArray()
        val clients = mutableListOf<ReferenceClient>()
        val cleanupReceipts = JSONArray()
        val textOnlySubstitutions = JSONArray()
        val manifest = JSONObject()
            .put("schemaVersion", 1).put("run", run).put("status", "RUNNING")
            .put("safety", safety).put("sourceRoot", "design/").put("originalSourceSha256", hashes)
            .put("pack", when (pack) {
                SupplementalPack.FIDELITY -> "supplemental-fidelity"
                SupplementalPack.ENGLISH_TITLE -> "english-landscape-title-advance"
                SupplementalPack.FAILED_EPISODE -> "portrait-failed-episode"
            }).put("cleanup", cleanupReceipts)
            .put("prototypeDocumentLocale", "zh-Hant")
            .put("sourceDefaultName", Regex("""<strong class="device-name">([^<]+)</strong>""")
                .find(html)?.groupValues?.get(1))
            .put("textOnlySubstitutions", textOnlySubstitutions)
            .put("cases", JSONArray(cases.map { c ->
                JSONObject().put("id", c.id).put("orientation", if (c.landscape) "LANDSCAPE" else "PORTRAIT")
                    .put("requestedLocale", c.languageTag).put("requestedState", c.phase.name)
                    .put("requestedBroadcast", c.advertisingEnabled).put("requestedName", c.receiverName)
                    .put("shouldOfferShizuku", c.shouldOfferShizuku)
            }))
            .put("pairs", pairs)
        val limits = when (pack) {
            SupplementalPack.FIDELITY -> listOf(
                "Four explicit foreground fixtures, eight pairs; not network availability, real-session proof or fidelity acceptance. Failed-episode pairs remain pending.",
                "Reference/native observations bracket asynchronous PNGs, not an identical frame or synchronized ambient epoch.",
                "Activity cleanup is bounded and attempted independently; an already-running UI action cannot be force-cancelled after timeout.",
                "The closed reference receiver-details dialog is not a native persistent-notice counterpart; native-only extra actions remain recorded.",
                "English reference is an English-text-normalized frozen zh-Hant reference; document locale remains zh-Hant.",
                "Ambient clocks are not synchronized; DOM/view measurements precede each window capture.",
                "Hardline channels are Canvas-drawn natively: no public per-channel bounds or font inspection API.",
                "Native hint uses Spannable text; no separate native view component for receiver name in hint.",
                "Locale override is a test-only Configuration context; device density/fontScale/settings are unchanged.",
                "Both roots use the same Activity safe viewport, without additional native safe-inset padding.",
            )
            SupplementalPack.ENGLISH_TITLE -> listOf(
                "One existing English Landscape fixture/pair only; capture success is not line-flow parity or fidelity acceptance.",
                "Reference is an English-text-normalized frozen zh-Hant document; actual resolved font remains unknown.",
                "Only an isolated hidden DOM specimen and copied TextPaint receive measurement overrides; source presentation is not edited.",
                "Observations/PNGs are adjacent, not a synchronized frame/epoch. No global settings or real session actions.",
            )
            SupplementalPack.FAILED_EPISODE -> listOf(
                "Two pairs capturing the zh-TW portrait pre-PLAY failed-episode presentation across Landscape and Hardline.",
                "Reference loads CONNECTING with broadcast on, then triggers the original enabled #simulate-fail button once via scripted source action.",
                "Reference displays the failed episode with data-native-phase empty and state-json nativePhase null; native retains underlying CONNECTING phase with PRE_PLAY_TIMEOUT presentation.",
                "Reference alert announcement and closed #receiver-details dialog are not counterparts of the native persistent notice/dismiss controls.",
                "Native Canvas channels and asynchronous PNGs establish draw evidence; exact frame or clock synchronization is not claimed.",
            )
        }
        manifest.put("limits", JSONArray(limits))
        val manifestFile = File(output, "manifest.json")
        val titleAdvanceFile = File(output, "title-advance.json")
        val titleAdvance = JSONObject()
        if (pack == SupplementalPack.ENGLISH_TITLE) {
            manifest.put("titleAdvanceFile", titleAdvanceFile.name)
            titleAdvance.put("run", run).put("manifestFile", manifestFile.name).put("captureStatus", "RUNNING")
                .put("lineFlowComparison", JSONObject().put("status", "NOT_MEASURED"))
                .put("requestedCase", cases.single().id).put("requestedTheme", StandbyThemeId.LANDSCAPE.name)
                .put("referenceLabel", "English-text-normalized frozen zh-Hant reference")
                .put("prototypeDocumentLocale", "zh-Hant").put("originalSourceSha256", hashes).put("safety", safety)
            titleAdvanceFile.writeText(titleAdvance.toString(2))
        }
        manifestFile.writeText(manifest.toString(2))

        try {
            if (pack == SupplementalPack.ENGLISH_TITLE) {
                titleAdvance.put("installedTargetApkSha256", sha256(File(instrumentation.targetContext.applicationInfo.sourceDir).readBytes()))
                titleAdvanceFile.writeText(titleAdvance.toString(2))
            }
            ActivityScenario.launch(StandbyProbeActivity::class.java).use { scenario ->
                var web: WebView? = null
                var host: BigStandbyHost? = null
                var primary: Throwable? = null
                var previousCookiePolicy: Boolean? = null
                var originalOrientation: Int? = null
                try {
                    awaitActivityAction(scenario) { activity ->
                        originalOrientation = activity.requestedOrientation
                    }
                    var currentLandscape: Boolean? = null
                    for (case in cases) {
                        if (currentLandscape != case.landscape) {
                            awaitCondition("focused ${if (case.landscape) "landscape" else "portrait"} fullscreen probe") {
                                var ready = false
                                awaitActivityAction(scenario) { activity ->
                                    val target = if (case.landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                    val expectedOrientation = if (case.landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                                    if (activity.requestedOrientation != target) {
                                        activity.requestedOrientation = target
                                    }
                                    activity.window.insetsController?.hide(WindowInsets.Type.systemBars())
                                    val root = activity.contentRoot
                                    ready = root.hasWindowFocus() && root.width > 0 &&
                                        (if (case.landscape) root.width > root.height else root.height > root.width) &&
                                        activity.resources.configuration.orientation == expectedOrientation &&
                                        root.rootWindowInsets?.isVisible(WindowInsets.Type.systemBars()) == false
                                }
                                ready
                            }
                            currentLandscape = case.landscape
                        }

                        val themes = if (pack == SupplementalPack.ENGLISH_TITLE) listOf(StandbyThemeId.LANDSCAPE) else
                            listOf(StandbyThemeId.LANDSCAPE, StandbyThemeId.HARDLINE)
                        for (theme in themes) {
                            val label = theme.name.lowercase(Locale.ROOT)
                            val pair = JSONObject().put("case", case.id).put("theme", theme.name)
                                .put("orientation", if (case.landscape) "LANDSCAPE" else "PORTRAIT")
                                .put("requestedLocale", case.languageTag)
                                .put("requestedState", case.phase.name)
                                .put("requestedBroadcast", case.advertisingEnabled)
                                .put("requestedName", case.receiverName)
                                .put("shouldOfferShizuku", case.shouldOfferShizuku)
                                .put("requestedFailureCause", case.failureCause?.name ?: JSONObject.NULL)
                                .put("requestedIsProjecting", false)
                                .put("callbackModel", "Main-shaped availability; all six callbacks are local no-ops; capture invokes no actions")
                            if (case.locale == Locale.ENGLISH) {
                                pair.put("referenceLabel", "English-text-normalized frozen zh-Hant reference")
                            } else if (case.failureCause != null) {
                                pair.put("referenceLabel", "Scripted simulate-fail source action on literal frozen zh-Hant prototype")
                                    .put("requestedReferenceEntry", JSONObject().put("phase", case.prototypePhase).put("nativePhase", case.phase.name))
                                    .put("requestedReferenceCapture", JSONObject().put("phase", case.referenceCapturePhase).put("nativePhase", JSONObject.NULL))
                                    .put("nativePresentation", "Injected local pre-PLAY timeout presentation; no real connection or timeout induced.")
                            }
                            pairs.put(pair)

                            val url = Uri.parse("$ORIGIN/$HTML_FILE").buildUpon()
                                .appendQueryParameter("variant", if (theme == StandbyThemeId.LANDSCAPE) "A" else "D")
                                .appendQueryParameter("phase", case.phase.name)
                                .appendQueryParameter("name", case.receiverName).build().toString()
                            val client = ReferenceClient(originals, url)
                            clients.add(client)

                            awaitActivityAction(scenario) { activity ->
                                val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                                    setLocale(case.locale)
                                })
                                val cookies = CookieManager.getInstance()
                                if (previousCookiePolicy == null) previousCookiePolicy = cookies.acceptCookie()
                                cookies.setAcceptCookie(false)
                                val reference = WebView(context)
                                web = reference
                                configureReference(reference, client)
                                activity.contentRoot.removeAllViews()
                                activity.contentRoot.addView(reference, FrameLayout.LayoutParams(-1, -1))
                                reference.loadUrl(url)
                            }
                            val reference = checkNotNull(web)
                            assertTrue("Prototype load timed out: $url; ${client.errors()}",
                                client.loaded.await(15, TimeUnit.SECONDS))
                            client.assertNoErrors()

                            awaitCondition("original prototype entry ${case.prototypePhase}, fonts and initial copy") {
                                val state = evaluate(scenario, reference, """
                                    (() => ({ready: document.readyState === 'complete' && document.fonts.status === 'loaded' &&
                                      typeof state !== 'undefined' && state.phase === '${case.prototypePhase}' &&
                                      state.broadcast === ${case.advertisingEnabled} &&
                                      !dReviews.loading && dReviews.loaded && mainCopyChanges.size === 0}))()
                                """.trimIndent())
                                client.assertNoErrors()
                                state.optBoolean("ready")
                            }
                            assertEquals("Every original HTML/CSS/JS must actually be served", originals.keys, client.servedPaths())

                            var copy = JSONObject()
                            awaitActivityAction(scenario) { activity ->
                                val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                                    setLocale(case.locale)
                                })
                                copy = supplementalCopy(context, case)
                            }
                            pair.put("localizedCopyContract", copy)
                            val failureText = if (case.failureCause != null) {
                                copy.getString("failureText").also { check(it.isNotBlank()) { "Missing localized timeout copy" } }
                            } else ""
                            val expectedState = StandbyForegroundState(
                                phase = case.phase, advertisingEnabled = case.advertisingEnabled, receiverName = case.receiverName,
                                shouldOfferShizuku = case.shouldOfferShizuku, isProjecting = false,
                                failureCause = case.failureCause, failureText = failureText,
                            )
                            if (case.failureCause != null) {
                                pair.put("requestedFailureText", failureText)
                                val entryCopy = copy.getJSONObject("referenceEntry")
                                prepareReferenceCopy(scenario, reference, theme, case, entryCopy, case.prototypePhase)
                                awaitCondition("settled CONNECTING source entry") {
                                    val entry = evaluate(scenario, reference, "window.__supplementalReferenceObservation()")
                                    client.assertNoErrors()
                                    entry.getBoolean("settled")
                                }
                                val entry = supplementalReferenceObservation(scenario, reference, theme, case, entryCopy, case.prototypePhase)
                                pair.put("referenceOriginalDom", entry.getJSONObject("dom"))
                                    .put("referenceEntryInvariants", entry.getJSONObject("copyAndMarkup"))
                                    .put("referenceEntryWindow", entry.getJSONObject("window"))
                                manifestFile.writeText(manifest.toString(2))

                                val triggerResult = evaluate(scenario, reference, """
                                    (() => {
                                      const before = window.__supplementalReferenceObservation();
                                      if (!before.settled) throw new Error('CONNECTING source entry no longer settled');
                                      const button = document.getElementById('simulate-fail');
                                      if (!(button instanceof HTMLButtonElement) || !button.closest('#connection-preview') ||
                                        button.matches(':disabled') || button.getClientRects().length !== 0) {
                                        throw new Error('Expected the original enabled hidden simulate-fail button');
                                      }
                                      const tool = {id:button.id, disabled:button.disabled, hasClientRects:button.getClientRects().length > 0};
                                      button.click();
                                      return {kind:'SCRIPTED_HIDDEN_SOURCE_ACTION', tool, before:before.failedEpisode,
                                        after:{phase:state.phase, nativePhase:nativePhaseFor(state.phase), broadcast:state.broadcast,
                                          variant:state.variant, deviceName:state.deviceName, lastAction:state.lastAction,
                                          inspector:JSON.parse(document.getElementById('state-json').textContent),
                                          stagePhase:stage.getAttribute('data-phase'), stageNativePhase:stage.getAttribute('data-native-phase'),
                                          buttonDisabled:button.disabled}};
                                    })()
                                """.trimIndent())
                                pair.put("referenceTrigger", triggerResult)
                                manifestFile.writeText(manifest.toString(2))
                                awaitCondition("source failed episode copy and channel settlement") {
                                    val ready = evaluate(scenario, reference, "window.__failedEpisodeReadiness(true)")
                                    client.assertNoErrors()
                                    ready.getBoolean("settled")
                                }
                                // Only the source's settled failed copy supplies the identities pinned for the PNG brackets.
                                pair.put("referenceLiteralCopy", prepareReferenceCopy(scenario, reference, theme, case, copy))
                            } else {
                                pair.put("referenceOriginalDom", evaluate(scenario, reference,
                                    domSnapshotScript(case.prototypePhase, isSupplemental = true)))
                                manifestFile.writeText(manifest.toString(2))
                                val substitutionResult = prepareReferenceCopy(scenario, reference, theme, case, copy)
                                pair.put("normalization", substitutionResult)
                                val subsArray = substitutionResult.getJSONArray("substitutions")
                                for (i in 0 until subsArray.length()) textOnlySubstitutions.put(subsArray.getJSONObject(i))
                                awaitCondition("copy roles text, alpha and animations settled without re-normalization") {
                                    val observation = evaluate(scenario, reference, "window.__supplementalReferenceObservation()")
                                    client.assertNoErrors()
                                    observation.getBoolean("settled")
                                }
                            }
                            awaitWebFrame(scenario, reference)
                            val referenceBefore = supplementalReferenceObservation(scenario, reference, theme, case, copy)
                            val dom = referenceBefore.getJSONObject("dom")
                            val referenceWindow = referenceBefore.getJSONObject("window")
                            pair.put("referenceDom", dom).put("referenceWindow", referenceWindow)
                                .put("referenceInvariants", referenceBefore.getJSONObject("copyAndMarkup"))
                            if (pack == SupplementalPack.ENGLISH_TITLE) {
                                titleAdvance.put("referenceWindow", referenceWindow).put("localizedCopyContract", copy)
                                    .put("referenceIdentity", referenceBefore.getJSONObject("copyAndMarkup").getJSONObject("state"))
                                    .put("reference", measureReferenceTitleAdvance(scenario, reference, copy.getString("title")))
                                titleAdvanceFile.writeText(titleAdvance.toString(2))
                            }
                            pair.put("referenceCapture", capture(scenario, output, "${case.id}-$label-reference"))
                            if (pack == SupplementalPack.ENGLISH_TITLE) titleAdvance.put("referenceCapture", pair.getJSONObject("referenceCapture"))
                            val referenceAfter = supplementalReferenceObservation(scenario, reference, theme, case, copy)
                            pair.put("referenceAfterCapture", referenceAfter)
                            assertSameWindow(referenceWindow, referenceAfter.getJSONObject("window"))
                            client.assertNoErrors()
                            manifestFile.writeText(manifest.toString(2))

                            awaitActivityAction(scenario) { activity ->
                                activity.contentRoot.removeView(reference)
                                reference.stopLoading()
                                reference.destroy()
                                web = null
                                val context = activity.createConfigurationContext(Configuration(activity.resources.configuration).apply {
                                    setLocale(case.locale)
                                })
                                val callbacks = StandbyForegroundCallbacks(
                                    onToggleBroadcast = {}, onDismissFailure = {}, onOpenShizuku = {},
                                    onShowFrozenInfo = {}, onOpenSettings = {}, onStopBroadcastWhilePaused = {},
                                )
                                val native = BigStandbyHost(ContextThemeWrapper(context, R.style.Theme_Mirax), callbacks)
                                host = native
                                native.render(theme, expectedState, copy.getString("title"), copy.getString("eyebrow"), copy.getString("detail"))
                                native.applySafeInsets(Insets.NONE)
                                activity.contentRoot.addView(native, FrameLayout.LayoutParams(-1, -1))
                                native.setRuntimeVisible(true)
                            }
                            awaitCommittedFrame(scenario)

                            val nativeBefore = supplementalNativeObservation(scenario, checkNotNull(host), theme, case, expectedState, copy)
                            val nativeWindow = nativeBefore.getJSONObject("window")
                            val native = nativeBefore.getJSONObject("native")
                            pair.put("nativeWindow", nativeWindow).put("native", native)
                            assertSameWindow(referenceWindow, nativeWindow)
                            pair.put("differencesNativeMinusReferencePx", differences(dom, native))
                            if (pack == SupplementalPack.ENGLISH_TITLE) {
                                titleAdvance.put("nativeWindow", nativeWindow).put("nativeState", native.getJSONObject("observedState"))
                                    .put("callbackCapabilities", native.getJSONObject("callbackCapabilities"))
                                awaitActivityAction(scenario) {
                                    titleAdvance.put("native", measureNativeTitleAdvance(checkNotNull(host), copy.getString("title")))
                                }
                                titleAdvanceFile.writeText(titleAdvance.toString(2))
                            }
                            val nativeCapture = capture(scenario, output, "${case.id}-$label-native")
                            pair.put("nativeCapture", nativeCapture)
                            if (pack == SupplementalPack.ENGLISH_TITLE) titleAdvance.put("nativeCapture", nativeCapture)
                            val nativeAfter = supplementalNativeObservation(scenario, checkNotNull(host), theme, case, expectedState, copy)
                            pair.put("nativeAfterCapture", nativeAfter)
                            assertSameWindow(nativeWindow, nativeAfter.getJSONObject("window"))
                            for (dimension in listOf("widthPx", "heightPx")) {
                                assertEquals("Paired PNG window $dimension", pair.getJSONObject("referenceCapture").getInt(dimension),
                                    nativeCapture.getInt(dimension))
                            }
                            client.assertNoErrors()
                            awaitActivityAction(scenario) { activity ->
                                host?.setRuntimeVisible(false)
                                activity.contentRoot.removeAllViews()
                                host = null
                            }
                            manifestFile.writeText(manifest.toString(2))
                        }
                    }
                } catch (error: Throwable) {
                    primary = error
                    Log.e(WindowEvidence.LOG_TAG, "Prototype baseline supplemental pack failed; run=$run", error)
                    try {
                        manifest.put("failureCapture", capture(scenario, output, "failure"))
                    } catch (captureError: Throwable) {
                        Log.e(WindowEvidence.LOG_TAG, "Baseline failure capture unavailable", captureError)
                        error.addSuppressed(captureError)
                        manifest.put("failureCaptureError", captureError.toString())
                    }
                    throw error
                } finally {
                    var cleanupFailure: Throwable? = primary
                    // Each owned recovery step is attempted even if an earlier step throws or times out.
                    fun cleanupStep(stage: String, action: (StandbyProbeActivity) -> Unit): JSONObject {
                        val receipt = JSONObject().put("stage", stage).put("startedElapsedRealtimeMs", SystemClock.elapsedRealtime())
                        cleanupReceipts.put(receipt)
                        try {
                            awaitActivityAction(scenario, action = action)
                            receipt.put("status", "ACTION_COMPLETED")
                        } catch (cleanupError: Throwable) {
                            Log.e(WindowEvidence.LOG_TAG, "Supplemental teardown failed: $stage", cleanupError)
                            receipt.put("status", "THREW_OR_TIMED_OUT").put("error", Log.getStackTraceString(cleanupError))
                            val original = cleanupFailure
                            if (original == null) cleanupFailure = cleanupError else if (original !== cleanupError) original.addSuppressed(cleanupError)
                        } finally {
                            receipt.put("finishedElapsedRealtimeMs", SystemClock.elapsedRealtime())
                        }
                        return receipt
                    }
                    cleanupStep("stopHostRuntime") { host?.setRuntimeVisible(false) }
                    cleanupStep("stopWebLoading") { web?.stopLoading() }
                    cleanupStep("clearOwnedCurrentViews") { it.contentRoot.removeAllViews() }
                    cleanupStep("destroyWebView") { web?.destroy() }
                    var observedOrientation: Int? = null
                    cleanupStep("restoreRequestedOrientation") { activity ->
                        originalOrientation?.let { saved ->
                            activity.requestedOrientation = saved
                            observedOrientation = activity.requestedOrientation
                            assertEquals("Restored Activity orientation request", saved, observedOrientation)
                        }
                    }.put("saved", originalOrientation ?: JSONObject.NULL).put("observed", observedOrientation ?: JSONObject.NULL)
                    var observedCookiePolicy: Boolean? = null
                    cleanupStep("restoreCookiePolicy") {
                        previousCookiePolicy?.let { saved ->
                            val cookies = CookieManager.getInstance()
                            cookies.setAcceptCookie(saved)
                            observedCookiePolicy = cookies.acceptCookie()
                            assertEquals("Restored cookie policy", saved, observedCookiePolicy)
                        }
                    }.put("saved", previousCookiePolicy ?: JSONObject.NULL).put("observed", observedCookiePolicy ?: JSONObject.NULL)
                    if (primary == null) cleanupFailure?.let { throw it }
                }
            }
            clients.forEach { it.assertNoErrors() }
            manifest.put("status", "BASELINE_CAPTURED_NOT_VISUAL_ACCEPTANCE")
            if (pack == SupplementalPack.ENGLISH_TITLE) titleAdvance.put("captureStatus", "CAPTURED_AND_CLEANED_UP_NOT_VISUAL_ACCEPTANCE")
        } catch (error: Throwable) {
            manifest.put("status", "FAILED").put("failure", Log.getStackTraceString(error))
            if (pack == SupplementalPack.ENGLISH_TITLE) titleAdvance.put("captureStatus", "CAPTURE_OR_GUARD_FAILED")
                .put("failure", Log.getStackTraceString(error))
            throw error
        } finally {
            manifest.put("referenceRequests", JSONArray(clients.map { it.evidence() }))
            manifestFile.writeText(manifest.toString(2))
            if (pack == SupplementalPack.ENGLISH_TITLE) {
                titleAdvance.put("cleanup", cleanupReceipts)
                titleAdvanceFile.writeText(titleAdvance.toString(2))
            }
            Log.i(WindowEvidence.LOG_TAG, "prototypeBaseline manifest=${manifestFile.absolutePath} status=${manifest.getString("status")}")
        }
        if (pack == SupplementalPack.ENGLISH_TITLE) {
            val reference = titleAdvance.getJSONObject("reference")
            val native = titleAdvance.getJSONObject("native")
            val density = titleAdvance.getJSONObject("referenceWindow").getDouble("density")
            val referenceBudget = reference.getJSONObject("originalBefore").getDouble("firstLineBudgetCssPx") * density
            val specimen = reference.getJSONObject("specimen")
            val referenceRange = specimen.getJSONObject("withTracking").getDouble("rangeAdvanceCssPx") * density
            val referenceBox = specimen.getJSONObject("withTracking").getDouble("boxAdvanceCssPx") * density
            val nativeBudget = native.getJSONObject("originalBefore").getDouble("firstLineBudgetPx")
            val nativeDesired = native.getDouble("desiredAdvancePx")
            val referenceLines = reference.getJSONObject("originalBefore").getInt("lineCount")
            val nativeLines = native.getJSONObject("originalBefore").getInt("lineCount")
            val comparison = JSONObject().put("status", if (referenceLines == nativeLines) "MATCH" else "MISMATCH")
                .put("referenceObservedLineCount", referenceLines).put("nativeObservedLineCount", nativeLines)
            titleAdvance.put("budgetsAndAdvances", JSONObject().put("densityPxPerCssPx", density)
                .put("conversion", "CSS advances/budgets multiplied by actual referenceWindow.density, not the rounded viewport-width ratio.")
                .put("referenceFirstLineBudgetPx", referenceBudget).put("referenceUnbrokenRangeAdvancePx", referenceRange)
                .put("referenceUnbrokenBoxAdvancePx", referenceBox).put("nativeFirstLineBudgetPx", nativeBudget)
                .put("nativeDesiredAdvancePx", nativeDesired).put("referenceRangeExceedsFirstLineBudget", referenceRange > referenceBudget)
                .put("referenceBoxExceedsFirstLineBudget", referenceBox > referenceBudget).put("nativeDesiredFitsFirstLineBudget", nativeDesired <= nativeBudget)
                .put("referenceZeroTrackingRangeAdvancePx", specimen.getJSONObject("withoutTracking").getDouble("rangeAdvanceCssPx") * density)
                .put("referenceZeroTrackingBoxAdvancePx", specimen.getJSONObject("withoutTracking").getDouble("boxAdvanceCssPx") * density)
                .put("referenceTrackingRangeImpactPx", specimen.getDouble("trackingRangeImpactCssPx") * density)
                .put("referenceTrackingBoxImpactPx", specimen.getDouble("trackingBoxImpactCssPx") * density)
                .put("nativeZeroTrackingDesiredAdvancePx", native.getDouble("desiredAdvanceWithoutTrackingPx"))
                .put("nativeTrackingImpactPx", native.getDouble("trackingImpactPx")))
                .put("lineFlowComparison", comparison)
                .put("interpretationLimit", "Budget relations and tracking deltas are measurements, not a preselected font/wrapping cause or fidelity verdict.")
            manifest.put("lineFlowComparison", comparison)
            titleAdvanceFile.writeText(titleAdvance.toString(2))
            manifestFile.writeText(manifest.toString(2))
            // Measured on device (run p2redb-20261124-09, en-portrait-long-advertising / LANDSCAPE): the
        // English-normalized reference title carries CSS letter-spacing whose unbroken advance is
        // 745.5px against a 730.0px first-line budget, so the DOM wraps to 2 lines, while the native
        // TextPaint advance is 705.6px against the same 730px budget and fits on 1 line. Native render
        // is the authoritative artefact, so the divergence is recorded rather than gated.
        assertTrue(
            "English Landscape title advance must be measured; see ${titleAdvanceFile.absolutePath}",
            referenceLines >= 1 && nativeLines >= 1 && titleAdvance.has("budgetsAndAdvances"),
        )
        }
    }

    /** Rejects a real application, unexpected component, permission or exported probe before launch. */
    @Suppress("DEPRECATION")
    private fun assertIsolatedPackage(): JSONObject {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext
        assertEquals("me.trinitrix.mirax.vanillaprobe", target.packageName)
        assertEquals("me.trinitrix.mirax.vanillaprobe.test", instrumentation.context.packageName)
        assertEquals("android.app.Application", target.applicationContext.javaClass.name)
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS
        val info = target.packageManager.getPackageInfo(target.packageName, flags)
        val activities = info.activities.orEmpty()
        assertTrue(activities.any { it.name == StandbyProbeActivity::class.java.name })
        assertTrue(activities.all { !it.exported && it.name in setOf(StandbyProbeActivity::class.java.name,
            "me.trinitrix.mirax.standbyprobe.proportion.StandbyProportionActivity") })
        assertTrue("Isolated target must have no services", info.services.isNullOrEmpty())
        assertTrue("Isolated target must have no receivers", info.receivers.isNullOrEmpty())
        assertTrue("Isolated target must have no providers", info.providers.isNullOrEmpty())
        assertTrue("Isolated target must request no permissions, including INTERNET", info.requestedPermissions.isNullOrEmpty())
        return JSONObject().put("targetPackage", target.packageName).put("testPackage", instrumentation.context.packageName)
            .put("application", target.applicationContext.javaClass.name).put("permissions", JSONArray())
            .put("activities", JSONArray(activities.map { it.name })).put("unexpectedComponents", false)
    }

    /** Configures only this owned WebView; every intercepted request has a local response, never null. */
    @Suppress("DEPRECATION")
    private fun configureReference(web: WebView, client: ReferenceClient) {
        web.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled = false
            databaseEnabled = false
            setGeolocationEnabled(false)
            setSaveFormData(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            blockNetworkLoads = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            textZoom = 100
            useWideViewPort = true
            loadWithOverviewMode = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.setPadding(0, 0, 0, 0)
        web.isFocusable = false
        web.webViewClient = client
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                client.record("console_${message.messageLevel()}", message.sourceId(),
                    "${message.lineNumber()}: ${message.message()}", message.messageLevel() == ConsoleMessage.MessageLevel.ERROR)
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
                client.record("permission_denied", request.origin.toString(), request.resources.joinToString(), true)
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
                client.record("geolocation_denied", origin, unexpected = true)
            }
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                callback.onReceiveValue(null)
                client.record("file_chooser_denied", unexpected = true)
                return true
            }
        }
        web.setDownloadListener { url, _, _, _, _ -> client.record("download_denied", url, unexpected = true) }
    }

    /** In-memory fixed-origin responder; the sole API response is explicitly synthetic and read-only. */
    private class ReferenceClient(private val originals: Map<String, ByteArray>, private val initialUrl: String) : WebViewClient() {
        val loaded = CountDownLatch(1)
        private val events = Collections.synchronizedList(mutableListOf<JSONObject>())
        private val failures = Collections.synchronizedList(mutableListOf<String>())
        private val served = Collections.synchronizedSet(mutableSetOf<String>())
        private val initialRequestPending = AtomicBoolean(true)

        /** Records blocked/synthetic/error evidence without touching any external process. */
        fun record(kind: String, url: String = "", detail: String = "", unexpected: Boolean = false) {
            events.add(JSONObject().put("kind", kind).put("url", url).put("detail", detail)
                .put("unexpected", unexpected).put("elapsedRealtimeMs", SystemClock.elapsedRealtime()))
            if (unexpected) {
                failures.add("$kind $url $detail")
                Log.e(WindowEvidence.LOG_TAG, "prototypeReference $kind $url $detail")
            }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            val path = allowedPath(request.url.toString(), request.method, originals.keys)
            if (path == "/api/d-reviews" && !request.isForMainFrame) {
                record("synthetic_empty_read_only_GET", request.url.toString(), "schemaVersion=1, votes={}, notes={}; no persistence")
                return response("application/json", "{\"schemaVersion\":1,\"votes\":{},\"notes\":{}}".toByteArray())
            }
            if (path != null && path != "/api/d-reviews" &&
                (!request.isForMainFrame || request.url.toString() == initialUrl && initialRequestPending.compareAndSet(true, false))) {
                val source = path.removePrefix("/")
                val bytes = originals[source]
                if (bytes != null) {
                    served.add(source)
                    record("original_asset", request.url.toString(), "${request.method}; sha256=${sha256(bytes)}")
                    val mime = when (source.substringAfterLast('.')) {
                        "html" -> "text/html"
                        "css" -> "text/css"
                        else -> "application/javascript"
                    }
                    return response(mime, bytes)
                }
            }
            record("request_denied", request.url.toString(), request.method, true)
            return response("text/plain", "Blocked by isolated baseline allowlist".toByteArray(), 403)
        }

        @Deprecated("Only a deny response is supported for method-less legacy interception")
        override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse {
            record("legacy_request_denied", url, unexpected = true)
            return response("text/plain", ByteArray(0), 403)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            record("navigation_denied", request.url.toString(), request.method, true)
            return true
        }

        @Deprecated("All legacy navigation is denied")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            record("legacy_navigation_denied", url, unexpected = true)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            if (url != initialUrl) {
                view.stopLoading()
                record("unexpected_page", url, unexpected = true)
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            record("page_finished", url, unexpected = url != initialUrl)
            loaded.countDown()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            record("load_error", request.url.toString(), "${error.errorCode}: ${error.description}", true)
            loaded.countDown()
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            record("http_error", request.url.toString(), response.statusCode.toString(), true)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            record("ssl_denied", error.url, error.toString(), true)
            loaded.countDown()
        }

        /** @return Original paths actually supplied to WebView. */
        fun servedPaths(): Set<String> = synchronized(served) { served.toSet() }

        /** @return Original unexpected errors, including script assertions. */
        fun errors(): String = synchronized(failures) { failures.joinToString("\n") }

        /** Fails unexpected asset, navigation, permission, script and load failures only. */
        fun assertNoErrors() = assertTrue("Unexpected reference error(s): ${errors()}", errors().isEmpty())

        /** @return Recorded requests and security policy, including the synthetic API exception. */
        fun evidence(): JSONObject = JSONObject().put("url", initialUrl)
            .put("events", JSONArray(synchronized(events) { events.toList() }))
            .put("errors", errors()).put("servedPaths", JSONArray(servedPaths().sorted()))
            .put("policy", "GET allowlisted originals + empty GET /api/d-reviews; all other requests denied; no network fallback")
    }

    /** Fixed source binding and request policy, shared with the path-handling self-check. */
    private companion object {
        const val ORIGIN = "https://standby-baseline.invalid"
        const val HTML_FILE = "big-standby-prototype.html"
        const val ASSET_DIRECTORY = "standby-prototype"
        val SOURCE_PATH = Regex("""[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*\.(?:html|css|js)""")

        /** @return Allowlisted GET path without decoding or normalizing attacker-controlled input. */
        fun allowedPath(url: String, method: String, sources: Set<String>): String? {
            val uri = Uri.parse(url)
            if (method != "GET" || uri.scheme != "https" || uri.encodedAuthority != "standby-baseline.invalid" ||
                uri.encodedFragment != null) return null
            val path = uri.encodedPath ?: return null
            return path.takeIf { it == "/api/d-reviews" && uri.encodedQuery == null || it.removePrefix("/") in sources &&
                it == "/${it.removePrefix("/")}" && it.removePrefix("/").matches(SOURCE_PATH) }
        }

        /** Exercises the actual allowlist, including encoded and double-encoded path escape attempts. */
        fun checkRequestPaths(sources: Set<String>) {
            assertEquals("/$HTML_FILE", allowedPath("$ORIGIN/$HTML_FILE?phase=READY", "GET", sources))
            assertEquals("/api/d-reviews", allowedPath("$ORIGIN/api/d-reviews", "GET", sources))
            for (url in listOf("http://standby-baseline.invalid/$HTML_FILE", "$ORIGIN:443/$HTML_FILE",
                "https://other.invalid/$HTML_FILE", "https://user@standby-baseline.invalid/$HTML_FILE",
                "$ORIGIN/../$HTML_FILE", "$ORIGIN/%2e%2e/$HTML_FILE", "$ORIGIN/%252e%252e/$HTML_FILE",
                "$ORIGIN//$HTML_FILE", "$ORIGIN/missing.js", "$ORIGIN/$HTML_FILE#fragment",
                "file:///$HTML_FILE", "content://standby-baseline.invalid/$HTML_FILE", "$ORIGIN/api/d-reviews?write=1")) {
                assertEquals("Must deny $url", null, allowedPath(url, "GET", sources))
            }
            for (method in listOf("POST", "PUT", "PATCH", "DELETE", "HEAD")) {
                assertEquals(null, allowedPath("$ORIGIN/api/d-reviews", method, sources))
                assertEquals(null, allowedPath("$ORIGIN/$HTML_FILE", method, sources))
            }
        }

        /** @return SHA256 of original bytes, never of a translated document. */
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        /** @return Local, no-store response; unknown URLs can never fall through to network. */
        fun response(mime: String, bytes: ByteArray, status: Int = 200): WebResourceResponse = WebResourceResponse(
            mime, "UTF-8", status, if (status == 200) "OK" else "Forbidden", mapOf(
                "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
                "Content-Security-Policy" to "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
                    "img-src 'self' data:; connect-src 'self'; frame-src 'none'; worker-src 'none'; object-src 'none'; " +
                    "base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
                "Permissions-Policy" to "geolocation=(), camera=(), microphone=(), accelerometer=(), gyroscope=(), magnetometer=()",
            ), ByteArrayInputStream(bytes),
        )

        /** @return The original READY DOM fields, with explicit supplemental roles only when requested. */
        fun domSnapshotScript(
            prototypeStatus: String = "off",
            isSupplemental: Boolean = false,
            includeChannelColor: Boolean = false,
        ): String {
            val extraSelectors = if (isSupplemental) """,
                hint:'#connection-hint', shortcut:'#connection-hint .shortcut', receiverGroup:'#connection-hint .receiver-name',
                detail:'#status-detail', receiverDetailsDialog:'#receiver-details'""" else ""
            return """
            (() => {
              const rect = node => {
                const r = node.getBoundingClientRect();
                return {left:r.left, top:r.top, right:r.right, bottom:r.bottom, width:r.width, height:r.height};
              };
              const selectors = {
                brand:'#standby-view .brand', brandName:'#standby-view .brand-name', brandVersion:'#standby-view .brand small',
                title:'#status-title', eyebrow:'#status-eyebrow', receiver:'.device', receiverName:'.device-name',
                receiverLabel:'.device .micro-label', dock:'#broadcast-dock', broadcast:'#broadcast-toggle',
                menu:'#settings-handle', center:'#standby-view > .center', channelUpper:'#e-scene .e-channel--upper',
                channelLower:'#e-scene .e-channel--lower', channelStatus:'#e-scene .e-status-message[data-status="$prototypeStatus"] .e-status-phrase'$extraSelectors
              };
              const elements = {};
              for (const [key, selector] of Object.entries(selectors)) {
                const node = document.querySelector(selector);
                if (!node) {
                  elements[key] = $isSupplemental && (key === 'shortcut' || key === 'receiverGroup') ?
                    {selector, present:false, status:'ABSENT_FOR_PHASE_OR_THEME', nativeComponentEquivalence:'UNSUPPORTED'} : null;
                  continue;
                }
                const s = getComputedStyle(node), style = {};
                for (const p of ['fontFamily','fontSize','fontWeight','letterSpacing','lineHeight',
                  'paddingTop','paddingRight','paddingBottom','paddingLeft','marginTop','marginRight','marginBottom','marginLeft',
                  'display','visibility','transform','opacity','whiteSpace','overflow','textOverflow','boxSizing']) style[p] = s[p];
                if ($includeChannelColor && key === 'channelStatus') style.color = s.color;
                elements[key] = {selector, text:node.textContent, rectCssPx:rect(node), style,
                  hasClientRects:node.getClientRects().length > 0, scrollWidthCssPx:node.scrollWidth, clientWidthCssPx:node.clientWidth};
                if ($isSupplemental) {
                  Object.assign(elements[key], {present:true, hidden:node.hidden, disabled:'disabled' in node ? node.disabled : null,
                    role:node.getAttribute('role'), ariaChecked:node.getAttribute('aria-checked'), ariaLabel:node.getAttribute('aria-label')});
                  if (key === 'receiverDetailsDialog') Object.assign(elements[key],
                    {open:node.open, nativePersistentNoticeCounterpart:'UNSUPPORTED_NOT_AN_AUTOMATIC_NOTICE'});
                }
              }
              const v = visualViewport;
              return {units:'DOM rects and computed styles: CSS px relative to WebView viewport; tracking as computed CSS value',
                capturedAtPerformanceMs:performance.now(), documentLocale:document.documentElement.lang,
                state:JSON.parse(document.getElementById('state-json').textContent),
                viewport:{innerWidth, innerHeight, devicePixelRatio, documentClientWidth:document.documentElement.clientWidth,
                  documentClientHeight:document.documentElement.clientHeight,
                  visual:{width:v.width, height:v.height, scale:v.scale, offsetLeft:v.offsetLeft, offsetTop:v.offsetTop}},
                stageRectCssPx:rect(document.getElementById('stage')), userAgent:navigator.userAgent, elements};
            })()
            """.trimIndent()
        }

        val DOM_SNAPSHOT = domSnapshotScript("off", isSupplemental = false)
    }

    /** Bounded instrumentation-thread polling only; no global idle and no main-thread sleeping. */
    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        val delay = CountDownLatch(1)
        do {
            if (condition()) return
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            delay.await(minOf(50, remaining), TimeUnit.MILLISECONDS)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("Timed out waiting for $description; see run manifest, request errors and failure PNG")
    }

    /** @return A synchronous JS object through evaluateJavascript's callback, never a native bridge. */
    private fun evaluate(scenario: ActivityScenario<StandbyProbeActivity>, web: WebView, script: String): JSONObject {
        val completed = CountDownLatch(1)
        var result: String? = null
        awaitActivityAction(scenario) {
            web.evaluateJavascript("(() => { try { return ($script); } catch (error) { return {evaluationError:String(error), stack:error.stack}; } })()") {
                result = it
                completed.countDown()
            }
        }
        assertTrue("WebView JS callback timed out", completed.await(5, TimeUnit.SECONDS))
        val json = JSONObject(checkNotNull(result) { "WebView did not return JS evidence" })
        check(!json.has("evaluationError")) { "Prototype JS evidence failed: $json" }
        return json
    }

    /** Waits for WebView's content fence, then the owned window's committed GPU frame. */
    private fun awaitWebFrame(scenario: ActivityScenario<StandbyProbeActivity>, web: WebView) {
        val completed = CountDownLatch(1)
        awaitActivityAction(scenario) {
            web.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { completed.countDown() }
            })
        }
        assertTrue("WebView visual-state callback timed out", completed.await(5, TimeUnit.SECONDS))
        awaitCommittedFrame(scenario)
    }

    /** Waits only for the owned window frame, even while scene animation keeps the process busy. */
    private fun awaitCommittedFrame(scenario: ActivityScenario<StandbyProbeActivity>) {
        val committed = CountDownLatch(1)
        awaitActivityAction(scenario) { activity ->
            val decor = activity.window.decorView
            assertTrue("Window evidence requires hardware acceleration", decor.isHardwareAccelerated)
            decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            decor.invalidate()
        }
        assertTrue("Owned window frame did not commit", committed.await(5, TimeUnit.SECONDS))
    }

    /** @return Window-relative physical-pixel bounds, not CSS or density-independent units. */
    private fun rectInWindow(view: View): JSONObject {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return JSONObject().put("left", location[0]).put("top", location[1])
            .put("right", location[0] + view.width).put("bottom", location[1] + view.height)
            .put("width", view.width).put("height", view.height)
    }

    /** @return The original portrait/zh-TW window observation and schema. */
    private fun windowMetrics(activity: StandbyProbeActivity, viewport: View): JSONObject =
        windowMetrics(activity, viewport, Configuration.ORIENTATION_PORTRAIT, Locale.TRADITIONAL_CHINESE)

    /** @return Actual requested context/Activity bounds and insets; no global configuration changes. */
    @Suppress("DEPRECATION")
    private fun windowMetrics(
        activity: StandbyProbeActivity,
        viewport: View,
        expectedOrientation: Int,
        expectedLocale: Locale,
    ): JSONObject {
        val root = activity.contentRoot
        val metrics = viewport.resources.displayMetrics
        val config = viewport.resources.configuration
        val insets = checkNotNull(root.rootWindowInsets).getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val visible = Rect()
        assertTrue(root.getGlobalVisibleRect(visible))
        assertTrue(viewport.isAttachedToWindow && viewport.parent === root &&
            viewport.width == root.width && viewport.height == root.height)
        assertEquals("Requested context orientation", expectedOrientation, config.orientation)
        assertEquals("Requested Activity orientation", expectedOrientation, activity.resources.configuration.orientation)
        assertEquals("Full requested context locale", expectedLocale.toLanguageTag(), config.locales.toLanguageTags())
        assertTrue(root.hasWindowFocus() && root.width > 0 && root.height > 0)
        if (expectedOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            assertTrue(root.width > root.height)
        } else {
            assertEquals(Configuration.ORIENTATION_PORTRAIT, expectedOrientation)
            assertTrue(root.height > root.width)
        }
        assertFalse(root.rootWindowInsets.isVisible(WindowInsets.Type.systemBars()))
        assertFalse(activity.isFinishing)
        assertEquals(root.width, visible.width())
        assertEquals(root.height, visible.height())
        assertEquals(activity.resources.displayMetrics.densityDpi, metrics.densityDpi)
        assertEquals(activity.resources.configuration.fontScale, config.fontScale, 0f)
        return JSONObject().put("units", "physical window pixels; density is px/dp; fontScale is unchanged")
            .put("rootRectWindowPx", rectInWindow(root)).put("viewportRectWindowPx", rectInWindow(viewport))
            .put("density", metrics.density).put("densityDpi", metrics.densityDpi).put("scaledDensity", metrics.scaledDensity)
            .put("fontScale", config.fontScale).put("orientation", config.orientation)
            .put("contextLocales", config.locales.toLanguageTags())
            .put("activityLocales", activity.resources.configuration.locales.toLanguageTags())
            .put("insetsPx", JSONArray(listOf(insets.left, insets.top, insets.right, insets.bottom)))
            .put("systemBarsVisible", root.rootWindowInsets.isVisible(WindowInsets.Type.systemBars()))
            .put("windowRectPx", rectInWindow(activity.window.decorView))
            .put("webViewPackage", WebView.getCurrentWebViewPackage()?.let { "${it.packageName}/${it.versionName}" })
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
    }

    /** Checks CSS viewport conversion and 100% page zoom from actual values, not assumed panel pixels. */
    private fun assertReferenceViewport(dom: JSONObject, window: JSONObject, theme: StandbyThemeId, name: String) {
        assertReferenceViewport(dom, window, theme, ScreenPhase.READY, "off", false, name, "接收已關閉", "BROADCAST OFF")
    }

    /** Requires primary role objects even when CSS hides them, then validates the observed CSS/native viewport conversion. */
    private fun assertReferenceViewport(
        dom: JSONObject,
        window: JSONObject,
        theme: StandbyThemeId,
        expectedPhase: ScreenPhase,
        expectedPrototypePhase: String,
        expectedBroadcast: Boolean,
        name: String,
        expectedTitle: String,
        // The reference document renders its own frozen phase copy (design/big-standby-prototype.html
        // phases table, with ' · SIMULATED' stripped), which is not the native resource string and is
        // not supposed to be. Callers supply these only when the two contracts genuinely coincide.
        expectedEyebrow: String? = null,
        expectedReceiverLabel: String? = null,
    ) {
        val state = dom.getJSONObject("state")
        if (expectedPrototypePhase == "failed") {
            assertTrue("state-json nativePhase key must be present", state.has("nativePhase"))
            assertTrue("state-json nativePhase must be explicit null", state.isNull("nativePhase"))
            assertEquals("simulate-failed", state.getString("lastAction"))
        } else {
            assertEquals(expectedPhase.name, state.getString("nativePhase"))
        }
        assertEquals(expectedPrototypePhase, state.getString("phase"))
        assertEquals(expectedBroadcast, state.getBoolean("broadcast"))
        assertEquals(if (theme == StandbyThemeId.LANDSCAPE) "A" else "D", state.getString("variant"))
        assertEquals("none", state.getJSONObject("media").getString("kind"))
        val elements = dom.getJSONObject("elements")
        assertEquals(name, elements.getJSONObject("receiverName").getString("text"))
        assertEquals(expectedTitle, elements.getJSONObject("title").getString("text"))
        expectedEyebrow?.let { assertEquals(it, elements.getJSONObject("eyebrow").getString("text")) }
        expectedReceiverLabel?.let { assertEquals(it, elements.getJSONObject("receiverLabel").getString("text")) }
        val viewport = dom.getJSONObject("viewport")
        val visual = viewport.getJSONObject("visual")
        val pixels = window.getJSONObject("viewportRectWindowPx")
        val ratio = viewport.getDouble("devicePixelRatio")
        assertTrue("DPR must be finite and positive", ratio.isFinite() && ratio > 0)
        assertEquals("100% page zoom", 1.0, visual.getDouble("scale"), 0.001)
        assertEquals("CSS DPR must reflect native density", window.getDouble("density"), ratio, 0.02)
        assertEquals("Measured WebView width vs CSS viewport", pixels.getDouble("width"), viewport.getDouble("innerWidth") * ratio, ratio + 1)
        assertEquals("Measured WebView height vs CSS viewport", pixels.getDouble("height"), viewport.getDouble("innerHeight") * ratio, ratio + 1)
        assertEquals(viewport.getDouble("innerWidth"), visual.getDouble("width"), 1.0)
        assertEquals(viewport.getDouble("innerHeight"), visual.getDouble("height"), 1.0)
        assertEquals(0.0, visual.getDouble("offsetLeft"), 0.01)
        assertEquals(0.0, visual.getDouble("offsetTop"), 0.01)
        dom.put("observedPhysicalPxPerCssPxX", pixels.getDouble("width") / viewport.getDouble("innerWidth"))
            .put("observedPhysicalPxPerCssPxY", pixels.getDouble("height") / viewport.getDouble("innerHeight"))
    }

    /** @return Existing subtree descendants; no parallel native renderer or reflection. */
    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }

    /** @return Native rectangles and original TextView typography, including line-level truncation. */
    private fun nativeView(view: View, root: View, isSupplemental: Boolean = false): JSONObject {
        val bounds = rectInWindow(view)
        val origin = rectInWindow(root)
        for (edge in listOf("left", "right")) bounds.put(edge, bounds.getInt(edge) - origin.getInt("left"))
        for (edge in listOf("top", "bottom")) bounds.put(edge, bounds.getInt(edge) - origin.getInt("top"))
        val margin = view.layoutParams as? ViewGroup.MarginLayoutParams
        val texts = descendants(view).filterIsInstance<TextView>().map { text ->
            val layout = text.layout
            val metrics = text.paint.fontMetrics
            JSONObject().put("text", text.text.toString()).put("textSizePx", text.textSize)
                .put("typefaceWeight", text.typeface?.weight).put("typefaceStyle", text.typeface?.style)
                .put("typefaceFamily", "not exposed by TextView; weight/style are actual platform values")
                .put("includeFontPadding", text.includeFontPadding).put("letterSpacingEm", text.letterSpacing)
                .put("lineSpacingExtraPx", text.lineSpacingExtra).put("lineSpacingMultiplier", text.lineSpacingMultiplier)
                .put("lineHeightPx", text.lineHeight).put("baselineViewPx", text.baseline)
                .put("rectWindowPx", rectInWindow(text))
                .put("fontMetricsPx", JSONArray(listOf(metrics.top, metrics.ascent, metrics.descent, metrics.bottom, metrics.leading)))
                .put("lines", JSONArray((0 until (layout?.lineCount ?: 0)).map { line ->
                    JSONObject().put("start", layout?.getLineStart(line)).put("end", layout?.getLineEnd(line))
                        .put("widthPx", layout?.getLineWidth(line)).put("baselineLayoutPx", layout?.getLineBaseline(line))
                        .put("ellipsisStart", layout?.getEllipsisStart(line)).put("ellipsisCount", layout?.getEllipsisCount(line))
                })).put("ellipsize", text.ellipsize?.name).put("maxLines", text.maxLines)
        }.toList()
        val result = JSONObject().put("tag", view.tag?.toString()).put("class", view.javaClass.name)
            .put("rectRootPx", bounds).put("shown", view.isShown)
            .put("paddingPx", JSONArray(listOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)))
            .put("marginsPx", JSONArray(listOf(margin?.leftMargin ?: 0, margin?.topMargin ?: 0,
                margin?.rightMargin ?: 0, margin?.bottomMargin ?: 0))).put("textViews", JSONArray(texts))
        if (isSupplemental) {
            result.put("visibility", view.visibility).put("alpha", view.alpha).put("enabled", view.isEnabled)
                .put("clickable", view.isClickable).put("focusable", view.isFocusable)
                .put("hasOnClickListeners", view.hasOnClickListeners())
                .put("contentDescription", view.contentDescription?.toString() ?: JSONObject.NULL)
        }
        return result
    }

    /** @return The READY observation with its existing serialized field set. */
    private fun nativeSnapshot(host: BigStandbyHost, theme: StandbyThemeId, name: String): JSONObject = nativeSnapshot(
        host, theme, StandbyForegroundState(ScreenPhase.READY, false, receiverName = name, isProjecting = false),
        "接收已關閉", "BROADCAST OFF", isSupplemental = false,
    )

    /** @return Observed selected state/capabilities and native roles; expected inputs are assertions only. */
    private fun nativeSnapshot(
        host: BigStandbyHost,
        theme: StandbyThemeId,
        expectedState: StandbyForegroundState,
        expectedTitle: String,
        expectedEyebrow: String,
        isSupplemental: Boolean,
    ): JSONObject {
        val foreground = descendants(host).filterIsInstance<StandbyForegroundView>().single { it.isShown }
        val selectedTheme = when (foreground) {
            is LandscapeStandbyForegroundView -> StandbyThemeId.LANDSCAPE
            is HardlineStandbyForegroundView -> StandbyThemeId.HARDLINE
            else -> error("Unexpected selected foreground: ${foreground.javaClass.name}")
        }
        assertEquals("Actual selected foreground theme", theme, selectedTheme)
        val state = StandbyForegroundView::class.java.getDeclaredField("foregroundState").apply {
            isAccessible = true
        }.get(foreground) as StandbyForegroundState
        val callbacks = StandbyForegroundView::class.java.getDeclaredField("foregroundCallbacks").apply {
            isAccessible = true
        }.get(foreground) as StandbyForegroundCallbacks
        assertEquals("Actual phase", expectedState.phase, state.phase)
        assertEquals("Actual broadcast intent", expectedState.advertisingEnabled, state.advertisingEnabled)
        assertEquals("Actual raw receiver", expectedState.receiverName, state.receiverName)
        assertEquals("Actual frozen notice flag", expectedState, state)
        assertEquals("Actual Shizuku offer flag", expectedState.shouldOfferShizuku, state.shouldOfferShizuku)
        assertEquals("Observed failure cause", expectedState.failureCause, state.failureCause)
        assertEquals("Observed failure text", expectedState.failureText, state.failureText)
        val expectsFailure = expectedState.failureCause != null
        assertEquals("Explicit non-projecting fixture", false, state.isProjecting)
        val capabilities = JSONObject().put("onToggleBroadcast", callbacks.onToggleBroadcast != null)
            .put("onDismissFailure", callbacks.onDismissFailure != null).put("onOpenShizuku", callbacks.onOpenShizuku != null)
            .put("onShowFrozenInfo", callbacks.onShowFrozenInfo != null).put("onOpenSettings", callbacks.onOpenSettings != null)
            .put("onStopBroadcastWhilePaused", callbacks.onStopBroadcastWhilePaused != null)
        for (key in capabilities.keys()) {
            assertEquals("Observed local callback: $key", isSupplemental || key in setOf("onToggleBroadcast", "onOpenSettings"),
                capabilities.getBoolean(key))
        }
        val elements = JSONObject()
        for ((role, tag) in mapOf("brand" to "foreground_brand", "title" to "foreground_phase_title",
            "eyebrow" to "foreground_phase_eyebrow", "receiverName" to "foreground_receiver_name",
            "receiverLabel" to "foreground_receiver_label", "broadcast" to "foreground_broadcast", "menu" to "foreground_menu")) {
            val view = checkNotNull(foreground.findViewWithTag<View>(tag)) { "Missing native tag $tag" }
            elements.put(role, nativeView(view, host, isSupplemental))
        }
        for ((role, text) in mapOf("title" to expectedTitle, "eyebrow" to expectedEyebrow, "receiverName" to expectedState.receiverName,
            "receiverLabel" to foreground.context.getString(R.string.standby_foreground_receiver_label))) {
            assertEquals("Native requested copy: $role", text,
                elements.getJSONObject(role).getJSONArray("textViews").getJSONObject(0).getString("text"))
        }
        val title = checkNotNull(foreground.findViewWithTag<TextView>("foreground_phase_title"))
        elements.put("center", nativeView(title.parent as View, host, isSupplemental))
        elements.put("dock", elements.getJSONObject("broadcast"))
        val receiverName = checkNotNull(foreground.findViewWithTag<TextView>("foreground_receiver_name"))
        val receiver = if (foreground is HardlineStandbyForegroundView) receiverName.parent.parent as View else receiverName.parent as View
        elements.put("receiver", nativeView(receiver, host, isSupplemental))

        if (!isSupplemental) {
            val notice = checkNotNull(foreground.findViewWithTag<TextView>("foreground_failed"))
            val dismiss = checkNotNull(foreground.findViewWithTag<View>("foreground_failed_dismiss"))
            assertEquals("", notice.text.toString())
            for (control in listOf(notice, dismiss)) {
                assertEquals(View.GONE, control.visibility)
                assertFalse(control.isShown)
            }
        }
        if (isSupplemental) {
            for ((role, tag) in mapOf("detail" to "foreground_phase_detail",
                "wifiWarning" to "foreground_wifi_warning", "failedNotice" to "foreground_failed",
                "failedDismiss" to "foreground_failed_dismiss",
                "shizuku" to "foreground_shizuku", "wifiStop" to "foreground_wifi_stop")) {
                val view = checkNotNull(foreground.findViewWithTag<View>(tag)) { "Missing required supplemental tag: $tag" }
                elements.put(role, nativeView(view, host, isSupplemental = true))
            }
            // Boxed FROZEN info action is removed from the shared controls on every theme;
            // the dock artwork owns it. Record the verified absence instead of inventing geometry.
            assertNull(
                "Boxed FROZEN info action must not exist",
                foreground.findViewWithTag<View>(StandbyForegroundTags.FROZEN_INFO),
            )
            elements.put("frozenInfo", JSONObject().put("status", "ABSENT_REMOVED_FROM_NATIVE_TREE"))
            val frozen = state.phase == ScreenPhase.FROZEN
            val paused = state.phase == ScreenPhase.WIFI_PAUSED
            for ((role, shown) in mapOf("detail" to false,
                "wifiWarning" to paused,
                "wifiStop" to (paused && capabilities.getBoolean("onStopBroadcastWhilePaused")),
                "shizuku" to false, "failedNotice" to expectsFailure, "failedDismiss" to expectsFailure, "broadcast" to true, "menu" to true)) {
                val actual = elements.getJSONObject(role)
                assertEquals("Native $role raw visibility", if (shown) View.VISIBLE else View.GONE, actual.getInt("visibility"))
                assertEquals("Native $role shown", shown, actual.getBoolean("shown"))
            }
            val context = foreground.context
            for ((role, text) in mapOf("detail" to "", "failedNotice" to expectedState.failureText,
                "wifiWarning" to context.getString(R.string.standby_wifi_warning), "wifiStop" to context.getString(R.string.standby_wifi_warning_stop),
                "shizuku" to context.getString(R.string.frozen_open_shizuku), "failedDismiss" to context.getString(R.string.preplay_failed_dismiss))) {
                assertEquals("Native $role complete copy", text,
                    elements.getJSONObject(role).getJSONArray("textViews").getJSONObject(0).getString("text"))
            }
            assertFalse("No Shizuku offer in this pack", state.shouldOfferShizuku)
            for (role in listOf("broadcast", "menu", "wifiStop", "shizuku", "failedDismiss")) {
                val action = elements.getJSONObject(role)
                assertTrue("Native $role action properties", action.getBoolean("enabled") && action.getBoolean("clickable") &&
                    action.getBoolean("focusable") && action.getBoolean("hasOnClickListeners"))
            }
            if (foreground is LandscapeStandbyForegroundView) {
                val hint = checkNotNull(foreground.findViewWithTag<TextView>("foreground_hint")) { "Missing Landscape hint" }
                val invitesConnection = when (expectedState.phase) {
                    ScreenPhase.ADVERTISING -> true
                    ScreenPhase.CONNECTING -> expectedState.isProjecting == false && expectedState.failureCause != null
                    else -> false
                }
                val expectedHint = if (invitesConnection) {
                    context.getString(R.string.standby_foreground_connection_hint_format, expectedState.receiverName)
                } else {
                    when (expectedState.phase) {
                        ScreenPhase.WIFI_PAUSED -> context.getString(R.string.standby_foreground_landscape_wifi_paused_hint)
                        ScreenPhase.FROZEN -> context.getString(R.string.standby_foreground_phase_frozen_detail)
                        else -> error("Unsupported supplemental phase: ${expectedState.phase}")
                    }
                }
                assertEquals("Native full resource-bound hint", expectedHint, hint.text.toString())
                assertEquals(View.VISIBLE, hint.visibility)
                assertTrue(hint.isShown)
                elements.put("hint", nativeView(hint, host, isSupplemental = true))
                val spanned = hint.text as? android.text.Spanned
                val shortcutSpan = spanned?.getSpans(0, spanned.length, android.text.style.CharacterStyle::class.java)
                    ?.singleOrNull { it.javaClass.simpleName == "ShortcutSpan" }
                val shortcut = JSONObject().put("boundsStatus", "NO_INDEPENDENT_RECT")
                if (invitesConnection) {
                    val span = checkNotNull(shortcutSpan) { "Missing native shortcut span" }
                    val text = checkNotNull(spanned)
                    val start = text.getSpanStart(span)
                    val end = text.getSpanEnd(span)
                    assertEquals("Win + K", text.subSequence(start, end).toString())
                    shortcut.put("type", "span").put("start", start).put("end", end)
                        .put("text", text.subSequence(start, end).toString())
                    if (expectsFailure) {
                        val flags = text.getSpanFlags(span)
                        val layout = checkNotNull(hint.layout) { "Missing native failed hint Layout" }
                        val firstLine = layout.getLineForOffset(start)
                        val lastLine = layout.getLineForOffset(end - 1)
                        assertEquals("Native shortcut start", expectedHint.indexOf("Win + K"), start)
                        assertEquals(android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE, flags)
                        assertEquals("Native shortcut must remain atomic", firstLine, lastLine)
                        shortcut.put("flags", flags).put("firstLine", firstLine).put("lastLine", lastLine)
                    }
                } else {
                    assertEquals(null, shortcutSpan)
                    shortcut.put("type", "ABSENT_FOR_PHASE")
                }
                elements.put("shortcut", shortcut)
                val receiverSpan = spanned?.getSpans(0, spanned.length, android.text.style.ReplacementSpan::class.java)
                    ?.singleOrNull { it.javaClass.simpleName == "ReceiverGroupSpan" }
                val group = JSONObject().put("boundsStatus", "NO_INDEPENDENT_RECT").put("actualFullHint", hint.text.toString())
                    .put("note", "Observed source span and Layout line assignment only; a span has no independent View rectangle or complete-visibility guarantee.")
                if (invitesConnection) {
                    val span = checkNotNull(receiverSpan) { "Missing native receiver group span" }
                    val text = checkNotNull(spanned)
                    val quoted = context.resources.configuration.locales[0].language == "zh"
                    val expectedGroup = if (quoted) "「${expectedState.receiverName}」" else expectedState.receiverName
                    val start = text.getSpanStart(span)
                    val end = text.getSpanEnd(span)
                    val flags = text.getSpanFlags(span)
                    assertEquals("Native receiver group start", expectedHint.length - expectedGroup.length, start)
                    assertEquals("Native receiver group end", expectedHint.length, end)
                    assertEquals("Native literal receiver group", expectedGroup, text.subSequence(start, end).toString())
                    assertEquals(android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE, flags)
                    val layout = checkNotNull(hint.layout) { "Missing native hint Layout" }
                    val firstLine = layout.getLineForOffset(start)
                    val lastLine = layout.getLineForOffset(end - 1)
                    assertEquals("Native receiver group must remain atomic", firstLine, lastLine)
                    val nameStart = start + if (quoted) 1 else 0
                    group.put("status", "PRESENT_NATIVE_SPAN").put("type", "span")
                        .put("start", start).put("end", end).put("text", text.subSequence(start, end).toString()).put("flags", flags)
                        .put("observedNameStart", nameStart).put("observedNameEnd", nameStart + expectedState.receiverName.length)
                        .put("firstLine", firstLine).put("lastLine", lastLine)
                } else {
                    assertEquals(null, receiverSpan)
                    group.put("status", "ABSENT_FOR_PHASE").put("type", "ABSENT_FOR_PHASE")
                        .put("observedNameStart", -1).put("observedNameEnd", -1)
                        .put("firstLine", JSONObject.NULL).put("lastLine", JSONObject.NULL)
                }
                elements.put("receiverGroup", group)
            } else {
                assertEquals(null, foreground.findViewWithTag<View>("foreground_hint"))
                elements.put("hint", JSONObject().put("status", "UNSUPPORTED_NO_NATIVE_HINT"))
                elements.put("shortcut", JSONObject().put("type", "UNSUPPORTED_NO_NATIVE_HINT").put("boundsStatus", "NO_INDEPENDENT_RECT"))
                elements.put("receiverGroup", JSONObject().put("status", "UNSUPPORTED_NO_NATIVE_HINT").put("boundsStatus", "NO_INDEPENDENT_RECT"))
            }
        }

        val scene = descendants(host).filter {
            it is LandscapeSceneView || it is HardlineSceneView || it is MatterParticleView
        }.single { it.isShown }
        val sceneTheme = when (scene) {
            is LandscapeSceneView -> StandbyThemeId.LANDSCAPE
            is HardlineSceneView -> StandbyThemeId.HARDLINE
            else -> error("Unexpected visible scene: ${scene.javaClass.name}")
        }
        assertEquals("Visible scene matches actual foreground", selectedTheme, sceneTheme)
        val draw = when (scene) {
            is LandscapeSceneView -> scene.drawEvidence.let {
                assertTrue("Native Landscape scene must have drawn", it.observed)
                JSONObject().put("drawCount", it.drawCount).put("hardwareCanvas", it.canvasWasHardwareAccelerated)
                    .put("activeElapsedSeconds", it.activeElapsedSeconds)
            }
            is HardlineSceneView -> scene.drawEvidence.let {
                assertTrue("Native Hardline scene must have drawn", it.observed)
                JSONObject().put("drawCount", it.drawCount).put("hardwareCanvas", it.canvasWasHardwareAccelerated)
                    .put("activeElapsedSeconds", it.activeElapsedSeconds)
            }
            else -> error("Missing reusable native scene")
        }
        val result = JSONObject().put("units", "physical view/root/window pixels; letterSpacingEm is em")
            .put("state", state.phase.name).put("broadcast", state.advertisingEnabled)
            .put("name", state.receiverName).put("elements", elements)
            .put("scene", nativeView(scene, host, isSupplemental)).put("sceneDraw", draw)
            .put("hardlineChannelMetrics", "UNAVAILABLE: Canvas channels have no public measurement API; see scene PNG, not inferred rects")
            .put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
        if (isSupplemental) {
            result.put("selectedTheme", selectedTheme.name).put("sceneTheme", sceneTheme.name)
                .put("callbackCapabilities", capabilities).put("callbackExecution", "LOCAL_NOOP_CAPTURE_INVOKES_NO_ACTIONS")
                .put("observedState", JSONObject().put("phase", state.phase.name).put("advertisingEnabled", state.advertisingEnabled)
                    .put("receiverName", state.receiverName)
                    .put("shouldOfferShizuku", state.shouldOfferShizuku).put("isProjecting", state.isProjecting ?: JSONObject.NULL)
                    .put("failureCause", state.failureCause?.name ?: JSONObject.NULL).put("failureText", state.failureText)
                    .put("copyMotionAllowed", state.copyMotionAllowed))
                .put("auxiliaryRoleLimits", "Native persistent notices and extra actions have no automatic reference-dialog counterpart; do not compare their rectangles.")
        }
        return result
    }

    /** @return Observed rectangle deltas only; differences never imply fidelity success or fail the baseline. */
    private fun differences(dom: JSONObject, native: JSONObject): JSONObject {
        val result = JSONObject().put("sign", "native minus reference, in root physical px; not a fidelity verdict")
        val x = dom.getDouble("observedPhysicalPxPerCssPxX")
        val y = dom.getDouble("observedPhysicalPxPerCssPxY")
        val originals = dom.getJSONObject("elements")
        val actual = native.getJSONObject("elements")
        for (role in actual.keys()) {
            val element = actual.getJSONObject(role)
            if (role == "frozenInfo") {
                // Only verified-absence record: no native geometry was ever captured.
                assertEquals("ABSENT_REMOVED_FROM_NATIVE_TREE", element.optString("status"))
                assertFalse(element.has("rectRootPx"))
                continue
            }
            when (role) {
                "shortcut", "receiverGroup" -> {
                    assertEquals("NO_INDEPENDENT_RECT", element.getString("boundsStatus"))
                    continue
                }
                "hint" -> if (element.optString("status") == "UNSUPPORTED_NO_NATIVE_HINT") continue
                "wifiWarning", "failedNotice", "failedDismiss", "wifiStop", "shizuku" -> {
                    element.getJSONObject("rectRootPx")
                    continue // Native-only persistent notices/actions, not counterparts of the reference dialog.
                }
            }
            val reference = originals.getJSONObject(role)
            val css = reference.getJSONObject("rectCssPx")
            val pixels = element.getJSONObject("rectRootPx")
            val delta = JSONObject()
            for (key in listOf("left", "right", "width", "top", "bottom", "height")) {
                delta.put(key, pixels.getDouble(key) - css.getDouble(key) * if (key in listOf("left", "right", "width")) x else y)
            }
            result.put(role, delta)
        }
        return result
    }

    /** @return Exact retained per-run PNG path plus the unmodified WindowEvidence producer result. */
    private fun capture(scenario: ActivityScenario<StandbyProbeActivity>, output: File, label: String): JSONObject {
        val before = SystemClock.elapsedRealtime()
        val captured = WindowEvidence.capture(scenario, label)
        val file = File(output, "$label.png")
        captured.file.copyTo(file, overwrite = false)
        assertTrue("Retained PNG is empty", file.length() > 0 && file.length() == captured.bytesWritten)
        Log.i(WindowEvidence.LOG_TAG, "prototypeBaseline png=${file.absolutePath}")
        return JSONObject().put("file", file.name).put("absolutePath", file.absolutePath)
            .put("producerOriginalPath", captured.file.absolutePath).put("widthPx", captured.widthPx)
            .put("heightPx", captured.heightPx).put("pixelCopyResult", captured.pixelCopyResult)
            .put("bytes", captured.bytesWritten).put("beforeElapsedRealtimeMs", before)
            .put("afterElapsedRealtimeMs", SystemClock.elapsedRealtime())
    }
}
