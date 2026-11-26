package me.trinitrix.mirax

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import android.widget.Toast
import java.util.concurrent.atomic.AtomicInteger
import me.trinitrix.mirax.broadcast.BroadcastAutoStop
import me.trinitrix.mirax.broadcast.EndConnectionConfirm
import me.trinitrix.mirax.host.SessionPlatformBinding
import me.trinitrix.mirax.session.ControlPanelEntry
import me.trinitrix.mirax.session.MiraxDisplayReport
import me.trinitrix.mirax.session.MiraxSession
import me.trinitrix.mirax.session.PictureRotationReport
import me.trinitrix.mirax.session.PictureScale
import me.trinitrix.mirax.session.PrePlayAttemptId
import me.trinitrix.mirax.session.PrePlayAttemptIdGenerator
import me.trinitrix.mirax.session.PrePlayFailureEpisodeOwner
import me.trinitrix.mirax.session.PrePlayNonFailureReason
import me.trinitrix.mirax.session.PrePlayPreemptReason
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SessionEffect
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.SinkAttemptSource
import me.trinitrix.mirax.session.TileState
import me.trinitrix.mirax.session.VideoMode
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.session.WmSizeReading
import me.trinitrix.mirax.wfd.SinkWmReadCoordinator
import me.trinitrix.mirax.wfd.SinkWmReadScope
import me.trinitrix.mirax.wfd.isCurrentSinkWmReading

/**
 * Host-side bridge that applies session actions, persists settings, keeps the
 * advertising foreground service in sync, refreshes the Quick Settings tile and
 * home-screen widget, reads `wm size` when the session allows, applies the
 * privileged WFD advertise command, runs the app-process RTSP/RTP receive path,
 * syncs the floating-ball overlay, and shows toasts.
 *
 * Product decisions stay in [me.trinitrix.mirax.session.MiraxSession]; this
 * object only performs Android side effects. The app process never calls
 * `setWfdInfo`. Views do not interpret RTSP — connection events are forwarded
 * into the session here. The home-screen widget does not require overlay
 * permission. Base `wm size` and picture rotation are forwarded as facts;
 * the session decides visible-picture axes.
 */
object SessionHost {
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val projectionLaunchGeneration = AtomicInteger(0)
    private val wmCalibration by lazy {
        val platform = SessionPlatformBinding.platform
        SessionWmCalibration(
            sessionNow = { MiraxApp.instance.session },
            nowMs = SystemClock::elapsedRealtime,
            postMain = { delayMs, action -> mainHandler.postDelayed({ action() }, delayMs) },
            scopeNow = { owner ->
                val fence = platform.provisioningFence()
                platform.ownerRevision(owner)?.let { SinkWmReadScope(owner, fence, it) }
            },
            requestReading = platform::requestWmReading,
            activeRequest = platform::activeWmRequest,
        )
    }
    private var lastPrePlayAttempt: PrePlayAttemptId? = null

    /**
     * Returns the latest attempt identity issued for pre-play, or null.
     *
     * MainActivity consumes this to verify "still latest" before showing FAILED.
     *
     * @return Latest attempt, or null before the first pre-play entry.
     */
    fun latestPrePlayAttempt(): PrePlayAttemptId? = lastPrePlayAttempt

    /**
     * Registers a source accepted on the main thread, even during CONNECTING.
     *
     * @param source Accepted sink group identity.
     */
    internal fun onSinkAttemptStarted(source: SinkAttemptSource) {
        check(android.os.Looper.getMainLooper()?.isCurrentThread == true)
        val attempt = PrePlayAttemptIdGenerator.getInstance().next()
        if (PrePlayFailureEpisodeOwner.getInstance().onPrePlayAttemptStarted(attempt, source)) {
            lastPrePlayAttempt = attempt
        }
    }

    /**
     * Consumes a source immediately when its controller is stopped or restarted.
     */
    internal fun onSinkStopped() {
        check(android.os.Looper.getMainLooper()?.isCurrentThread == true)
        PrePlayFailureEpisodeOwner.getInstance().onExplicitStop()
    }

    /**
     * Consumes an unclassified natural group drop without dismissing a failure.
     *
     * @param source Source whose group dropped.
     */
    internal fun onSinkGroupDropped(source: SinkAttemptSource) {
        check(android.os.Looper.getMainLooper()?.isCurrentThread == true)
        PrePlayFailureEpisodeOwner.getInstance()
            .onPrePlayNonFailure(PrePlayNonFailureReason.UNKNOWN_DROP, source)
    }

    /**
     * Applies truth changes which supersede a failure episode.
     *
     * @param snapshot Latest session snapshot.
     */
    private fun trackPrePlayPreemption(snapshot: SessionSnapshot) {
        when (snapshot.phase) {
            ScreenPhase.CONNECTED -> PrePlayFailureEpisodeOwner.getInstance()
                .onPreempted(PrePlayPreemptReason.PROJECTION_STARTED)
            ScreenPhase.WIFI_PAUSED -> PrePlayFailureEpisodeOwner.getInstance()
                .onPreempted(PrePlayPreemptReason.WIFI_PAUSED)
            ScreenPhase.FROZEN -> PrePlayFailureEpisodeOwner.getInstance()
                .onPreempted(PrePlayPreemptReason.FROZEN_STATE)
            else -> Unit
        }
    }

    /**
     * Notifies the FAILED owner that Big Standby foreground visibility changed.
     * The 7.5s budget accrues only while visible. Must run on the main thread;
     * background threads are rejected by the owner guard.
     *
     * Registers a one-time static UI refresh on episode changes so a 7.5s
     * expiry (or dismiss/preemption) redraws the FAILED text without touching
     * broadcast, idle, or navigation state.
     *
     * @param visible True while Big Standby is in the visible foreground.
     */
    fun onStandbyForegroundVisible(visible: Boolean) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return
        val owner = PrePlayFailureEpisodeOwner.getInstance()
        owner.setEpisodeListener { MainActivity.refreshPrePlayFailureOnly() }
        owner.onForegroundVisibilityChanged(visible)
    }

    /**
     * Dashboard or other UI toggled the shared advertising switch.
     */
    fun setAdvertising(context: Context, enabled: Boolean): SessionSnapshot {
        val session = MiraxApp.instance.session
        val wasAdvertising = session.snapshot().advertisingEnabled
        session.report(SessionPlatformBinding.platform.reportWifi(context))
        session.handle(SessionAction.SetAdvertising(enabled))
        val snapshot = commit(context)
        // An explicit user stop supersedes any FAILED episode so a late
        // terminal for the consumed attempt cannot reopen it.
        if (!enabled && wasAdvertising) {
            explicitStopPrePlay()
        }
        return snapshot
    }

    /**
     * Quick Settings tile click. Refreshes privilege first.
     *
     * Gray tile: probe only — turn advertising on when an owner appears;
     * otherwise show the shared Shizuku-not-open toast without requesting
     * authorization. Non-gray: [SessionAction.TileTap] toggles advertising.
     */
    fun onTileClick(context: Context): SessionSnapshot {
        val session = MiraxApp.instance.session
        val wasGray = session.snapshot().tileState == TileState.GRAY
        val wasAdvertising = session.snapshot().advertisingEnabled
        session.report(SessionPlatformBinding.platform.probePrivilege(context))
        session.report(SessionPlatformBinding.platform.reportWifi(context))
        if (wasGray) {
            // Probe success turns broadcast on (not a toggle), so a switch that
            // was already on while frozen is not flipped off.
            if (session.snapshot().wfdOwner != WfdOwner.NONE) {
                session.handle(SessionAction.SetAdvertising(true))
            } else {
                session.handle(SessionAction.TileTap)
            }
        } else {
            session.handle(SessionAction.TileTap)
        }
        // Keep the tile's window token for the Wi-Fi prompt: a stopped
        // MainActivity means the application context cannot show a dialog.
        val snapshot = commit(
            context,
            tileWifiPrompt = { SessionPlatformBinding.platform.promptWifi(context) },
        )
        // An explicit user stop via the tile supersedes any FAILED episode.
        if (!wasGray && wasAdvertising && !snapshot.advertisingEnabled) {
            explicitStopPrePlay()
        }
        return snapshot
    }

    /**
     * Starts a bounded asynchronous screen-size read after checking permission.
     *
     * @param context Activity or application context.
     * @param displayId Display hosting the Mirax window.
     * @return Immediate snapshot; a valid completed read refreshes it asynchronously.
     */
    fun useThisScreen(context: Context, displayId: Int): SessionSnapshot {
        val appContext = context.applicationContext ?: context
        return wmCalibration.useThisScreen(
            session = MiraxApp.instance.session,
            displayId = displayId,
            rotationDegrees = { pictureRotationDegrees(appContext, displayId) },
            commit = { commit(appContext) },
            refresh = ::refreshAfterWmReading,
        )
    }

    /**
     * Persist settings, sync keep-alive / status surfaces, apply effects.
     *
     * @param context Activity or application context.
     * @return Reconciled session snapshot.
     */
    fun commit(context: Context): SessionSnapshot = commit(context, tileWifiPrompt = null)

    /**
     * Commit with the Quick Settings tile context available to the Wi-Fi prompt.
     *
     * Only [SessionEffect.PromptEnableWifi] may run from the original tile
     * context; every other effect, publish, and provisioning call still uses
     * the application context.
     *
     * @param context Activity or application context.
     * @param tileWifiPrompt Synchronous tile-context prompt presenter, or null.
     * @return Reconciled session snapshot.
     */
    private fun commit(context: Context, tileWifiPrompt: (() -> Unit)?): SessionSnapshot {
        val appContext = context.applicationContext ?: context
        val platform = SessionPlatformBinding.platform
        val session = MiraxApp.instance.session
        val before = session.snapshot()
        if (!before.showPicture) projectionLaunchGeneration.incrementAndGet()
        platform.syncOwner(appContext, before.wfdOwner)
        wmCalibration.requestProvisioning(
            session = session,
            rotationDegrees = { pictureRotationDegrees(appContext, displayId = null) },
            commit = { commit(appContext) },
            refresh = ::refreshAfterWmReading,
        )
        commitAdvertising(
            session = session,
            syncAdvertise = { command ->
                platform.syncOwner(appContext, session.snapshot().wfdOwner)
                platform.syncAdvertise(appContext, command)
            },
            applyEffects = { snapshot ->
                applyEffects(
                    session = session,
                    context = appContext,
                    snapshot = snapshot,
                    tileWifiPrompt = tileWifiPrompt,
                )
            },
            publish = { snapshot ->
                if (!snapshot.showPicture) projectionLaunchGeneration.incrementAndGet()
                SessionPreferences.saveAdvertising(appContext, snapshot.advertisingEnabled)
                SessionPreferences.saveResolutionSettings(appContext, session.exportSettings())
                platform.syncWifi(appContext, snapshot.advertisingEnabled)
                BroadcastAutoStop.sync(snapshot)
                platform.syncKeepAlive(appContext, snapshot)
                platform.updateWidgets(appContext, snapshot)
                platform.requestTileListening(appContext)
                platform.syncFloatingBall(appContext, snapshot)
            },
            syncSink = { command -> platform.syncSink(appContext, command) },
            holdAdvertiseHandoff = { snapshot -> wmCalibration.holdAdvertiseHandoff(session, snapshot) },
        )
        val finalSnapshot = session.snapshot()
        trackPrePlayPreemption(finalSnapshot)
        return finalSnapshot
    }

    /**
     * Reconcile owner acceptance before publishing a single final state to status surfaces.
     * One-shot effects are consumed before handing the command to the owner.
     *
     * @param session Session owning the advertising state.
     * @param syncAdvertise Owner command boundary.
     * @param applyEffects One-shot effect boundary.
     * @param publish Settings and status surfaces boundary.
     * @param syncSink Receiver boundary.
     * @param holdAdvertiseHandoff Whether the post-effect state must wait for current calibration.
     * @return The final state shown to the caller and external surfaces.
     */
    internal fun commitAdvertising(
        session: MiraxSession,
        syncAdvertise: (WfdAdvertiseCommand?) -> Boolean,
        applyEffects: (SessionSnapshot) -> Unit,
        publish: (SessionSnapshot) -> Unit,
        syncSink: (WfdAdvertiseCommand?) -> Unit,
        holdAdvertiseHandoff: (SessionSnapshot) -> Boolean = { false },
    ): SessionSnapshot {
        applyEffects(session.snapshot())
        val afterEffects = session.snapshot()
        val command = afterEffects.wfdAdvertise
        if (command != null && holdAdvertiseHandoff(afterEffects)) {
            publish(afterEffects)
            return afterEffects
        }
        if (!syncAdvertise(command) && command != null) {
            session.handle(SessionAction.BeaconFailed)
            applyEffects(session.snapshot())
            syncAdvertise(session.snapshot().wfdAdvertise)
        }
        val finalSnapshot = session.snapshot()
        publish(finalSnapshot)
        syncSink(finalSnapshot.wfdAdvertise)
        return finalSnapshot
    }

    /**
     * Forward a connection event from the RTSP/RTP host into the session.
     * Views must not interpret RTSP themselves.
     */
    fun dispatchConnectionEvent(context: Context, action: SessionAction): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.handle(action)
        val snapshot = commit(context)
        // Genuine RTSP terminal evidence opens FAILED; unproven paths stay silent.
        // PrePlayGroupDropped / user drops carry no RtspFailure and never fake FAILED.
        MainActivity.refreshIfShowing()
        return snapshot
    }

    /**
     * Applies a run-local history update without recursively committing the host.
     *
     * @param action Append or finish action accepted by the sink arbiter.
     */
    internal fun onSinkHistoryEvent(action: SessionAction) {
        check(android.os.Looper.getMainLooper()?.isCurrentThread == true)
        check(action is SessionAction.AppendConnectionLog || action is SessionAction.FinishConnectionRun)
        MiraxApp.instance.session.handle(action)
    }

    /** Refreshes connection diagnostics after a current run's log changes. */
    internal fun refreshSinkDiagnostics() {
        check(android.os.Looper.getMainLooper()?.isCurrentThread == true)
        MainActivity.refreshIfShowing()
    }

    /**
     * Forwards genuine RTSP sink terminal evidence for the current attempt.
     * Must be called on the main thread; background threads are rejected.
     * Null or unproven evidence never opens an episode.
     *
     * @param failure Terminal evidence from the sink session, or null.
     * @param source Sink attempt source identity at group-up boundary.
     */
    fun onRtspTerminal(
        failure: me.trinitrix.mirax.wfd.rtsp.RtspFailure?,
        source: me.trinitrix.mirax.session.SinkAttemptSource
    ) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return
        PrePlayFailureEpisodeOwner.getInstance().onPrePlayTerminal(failure, source)
        MainActivity.refreshPrePlayFailureOnly()
    }

    /** Forwards a proven current-run TCP establishment timeout. */
    internal fun onTcpEstablishmentTimeout(
        evidence: me.trinitrix.mirax.session.TcpEstablishmentTimeout,
        source: SinkAttemptSource,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        PrePlayFailureEpisodeOwner.getInstance().onTcpEstablishmentTimeout(evidence, source)
        MainActivity.refreshPrePlayFailureOnly()
    }

    /**
     * Clears any FAILED episode on an explicit user stop. Runs on the main
     * thread; background threads are rejected by the owner guard. Changes no
     * broadcast state and emits no session effects.
     */
    private fun explicitStopPrePlay() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return
        PrePlayFailureEpisodeOwner.getInstance().onExplicitStop()
        MainActivity.refreshPrePlayFailureOnly()
    }

    /**
     * Dismisses the FAILED presentation. Changes no broadcast state, resets no
     * idle clock, and navigates nowhere; purely presentational.
     * Must be called on the main thread.
     */
    fun dismissPrePlayFailure() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return
        PrePlayFailureEpisodeOwner.getInstance().onDismissed()
        MainActivity.refreshPrePlayFailureOnly()
    }

    /**
     * Freeze the M3 offer for the connection that is about to speak RTSP.
     *
     * Does not re-apply the listen beacon. The wm-size preference is for this
     * connection's capability answer only.
     *
     * @param context Context used to read rotation.
     * @param reading Run-local wm size, when available.
     * @param canCommit Claims the originating request immediately before mutation.
     * @param isCurrent Revalidates the source after the claim.
     * @return The frozen snapshot, or null when the request was superseded.
     */
    internal fun freezeConnectionOffer(
        context: Context,
        reading: WmSizeReading?,
        canCommit: () -> Boolean,
        isCurrent: () -> Boolean,
    ): SessionSnapshot? {
        val rotation = pictureRotationDegrees(context, null)
        if (!canCommit() || !isCurrent()) return null
        val session = MiraxApp.instance.session
        session.report(PictureRotationReport(rotation))
        if (reading != null && SessionEffect.ReadPlainWmSizeForProvisioning in session.snapshot().effects &&
            session.snapshot().canReadWmSize
        ) {
            session.handle(SessionAction.ApplyProvisioningWmSize(reading))
            SessionPreferences.saveResolutionSettings(context.applicationContext, session.exportSettings())
        }
        if (!isCurrent()) return null
        session.handle(SessionAction.FreezeConnectionOffer(reading))
        return session.snapshot()
    }

    /**
     * System Back on the picture surface closes an expanded control panel only.
     */
    fun onSystemBack(context: Context): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.SystemBack)
        return commit(context)
    }

    /**
     * User asked to end this connection; host shows a confirmation dialog first.
     */
    fun requestEndConnection(context: Context): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.RequestEndConnection)
        return commit(context)
    }

    /**
     * User confirmed ending this connection.
     */
    fun confirmEndConnection(context: Context): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.EndConnection)
        return commit(context)
    }

    /**
     * User dismissed the control panel (tap on scrim).
     */
    fun collapseControlPanel(context: Context): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.CollapseControlPanel)
        return commit(context)
    }

    /**
     * Persist and apply the floating-ball setting.
     */
    fun setFloatingBallEnabled(context: Context, enabled: Boolean): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.SetFloatingBallEnabled(enabled))
        SessionPreferences.saveFloatingBallEnabled(context.applicationContext, enabled)
        return commit(context)
    }

    /**
     * Persist and apply picture scale. Relayouts the picture surface when shown.
     */
    fun setPictureScale(context: Context, scale: PictureScale): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.SetPictureScale(scale))
        SessionPreferences.savePictureScale(context.applicationContext, scale)
        val snapshot = commit(context)
        PictureActivity.relayoutIfShowing()
        return snapshot
    }

    /**
     * Persist and apply orientation lock setting.
     */
    fun toggleOrientationLock(context: Context, enabled: Boolean): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.SetOrientationLock(enabled))
        SessionPreferences.saveOrientationLockEnabled(context.applicationContext, enabled)
        return commit(context)
    }

    /**
     * Dashboard reminder: ask again for overlay permission (or open settings).
     */
    fun requestOverlayPermission(context: Context): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.report(SessionPlatformBinding.platform.reportOverlay(context))
        session.handle(SessionAction.RequestOverlayPermission)
        return commit(context)
    }

    /**
     * User left projection for the phone home screen.
     */
    fun leftProjectionToHome(context: Context): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.report(SessionPlatformBinding.platform.reportOverlay(context))
        session.handle(SessionAction.LeftProjectionToHome)
        return commit(context)
    }

    /**
     * User opened Mirax's own dashboard while connected.
     */
    fun openedMiraxDashboard(context: Context): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.OpenedMiraxDashboard)
        return commit(context)
    }

    /**
     * Confirm a resumed projection and cancel any queued background launch for it.
     *
     * @param context resumed projection activity
     * @return current connected picture state
     */
    fun projectionPresented(context: Context): SessionSnapshot {
        projectionLaunchGeneration.incrementAndGet()
        MiraxApp.instance.session.handle(SessionAction.ProjectionPresented)
        return commit(context)
    }

    /**
     * User tapped the floating ball to return to projection.
     */
    fun floatingBallTapped(context: Context): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.FloatingBallTapped)
        return commit(context)
    }

    /**
     * Toggle the control panel from the projection activity.
     */
    fun toggleControlPanel(context: Context): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.ToggleControlPanel)
        return commit(context)
    }

    /**
     * Persist and apply the control panel enabled setting.
     */
    fun setControlPanelEnabled(context: Context, enabled: Boolean): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.SetControlPanelEnabled(enabled))
        SessionPreferences.saveControlPanelEnabled(context.applicationContext, enabled)
        return commit(context)
    }

    /** Persist and apply the projection control panel entry selection. */
    fun setControlPanelEntry(context: Context, entry: ControlPanelEntry): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.SetControlPanelEntry(entry))
        SessionPreferences.saveControlPanelEntry(context.applicationContext, entry)
        val snapshot = commit(context)
        PictureActivity.refreshIfShowing()
        return snapshot
    }

    /**
     * Enter mock connected state for debug / E2E verification without real remote device.
     */
    fun enterMockConnected(context: Context): SessionSnapshot {
        MiraxApp.instance.session.enterMockConnected()
        return commit(context)
    }

    /**
     * Refresh privilege, overlay grant, and push status surfaces without a user action.
     */
    fun refreshPrivilegeAndSurfaces(context: Context): SessionSnapshot {
        val appContext = context.applicationContext
        // Root probe / su helper start off the main thread; re-commit if state moves.
        SessionPlatformBinding.platform.refreshRoot(appContext) {
            val session = MiraxApp.instance.session
            session.report(SessionPlatformBinding.platform.probePrivilege(appContext))
            commit(appContext)
        }
        val session = MiraxApp.instance.session
        session.report(SessionPlatformBinding.platform.probePrivilege(appContext))
        session.report(SessionPlatformBinding.platform.reportOverlay(appContext))
        session.report(SessionPlatformBinding.platform.reportWifi(appContext))
        return commit(appContext)
    }

    private fun refreshAfterWmReading() {
        MainActivity.refreshIfShowing()
        PictureActivity.refreshIfShowing()
    }

    /**
     * Map [Display.getRotation] to degrees the session understands.
     *
     * Args:
     *     context: Used to resolve the display manager.
     *     displayId: Specific display, or null for the default display.
     */
    internal fun pictureRotationDegrees(context: Context, displayId: Int?): Int {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return 0
        val display = if (displayId == null) {
            manager.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            manager.getDisplay(displayId)
        } ?: return 0
        return when (display.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    /**
     * Applies one-shot effects and acknowledges them.
     *
     * Only [SessionEffect.PromptEnableWifi] honours [tileWifiPrompt]; the
     * fallback stays [defaultWifiPrompt] so every non-tile caller is unchanged.
     *
     * @param session Session owning the effects.
     * @param context Application context used by every effect.
     * @param snapshot Snapshot carrying the pending effects.
     * @param tileWifiPrompt Tile-context Wi-Fi presenter, or null for the default.
     * @param defaultWifiPrompt Platform Wi-Fi presenter used without a tile.
     */
    internal fun applyEffects(
        session: MiraxSession,
        context: Context,
        snapshot: SessionSnapshot,
        tileWifiPrompt: (() -> Unit)? = null,
        defaultWifiPrompt: () -> Unit = {
            SessionPlatformBinding.platform.promptWifi(context)
        },
    ) {
        var consumed = false
        if (SessionEffect.ShowShizukuNotOpenToast in snapshot.effects) {
            Toast.makeText(
                context.applicationContext,
                context.getString(R.string.shizuku_not_open),
                Toast.LENGTH_SHORT,
            ).show()
            consumed = true
        }
        if (SessionEffect.ShowAdvertiseFailedToast in snapshot.effects) {
            Toast.makeText(
                context.applicationContext,
                context.getString(R.string.advertise_failed_toast),
                Toast.LENGTH_LONG,
            ).show()
            consumed = true
        }
        if (SessionEffect.ConfirmEndConnection in snapshot.effects) {
            EndConnectionConfirm.show(context) {
                confirmEndConnection(context.applicationContext)
            }
            consumed = true
        }
        if (SessionEffect.DropActiveConnection in snapshot.effects) {
            SessionPlatformBinding.platform.dropActiveConnection()
            consumed = true
        }
        if (SessionEffect.RequestOverlayPermission in snapshot.effects) {
            SessionPlatformBinding.platform.requestOverlay(context)
            consumed = true
        }
        if (SessionEffect.OpenOverlaySettings in snapshot.effects) {
            SessionPlatformBinding.platform.openOverlaySettings(context)
            consumed = true
        }
        if (SessionEffect.BringProjectionToFront in snapshot.effects) {
            val generation = projectionLaunchGeneration.incrementAndGet()
            if (MainActivity.isShowing() || PictureActivity.current()?.hasWindowFocus() == true) {
                context.applicationContext.startActivity(
                    Intent(
                        context.applicationContext,
                        PictureActivity::class.java,
                    ).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                    ),
                )
            } else {
                // A foreground service alone does not exempt background activity launches.
                SessionPlatformBinding.platform.bringProjectionToFront {
                    projectionLaunchGeneration.get() == generation
                }
            }
            consumed = true
        }
        if (SessionEffect.PromptEnableWifi in snapshot.effects) {
            (tileWifiPrompt ?: defaultWifiPrompt)()
            consumed = true
        }
        if (consumed) {
            session.handle(SessionAction.AcknowledgeEffects)
        }
    }
}

/**
 * Coordinates provisioning and display calibration on the main thread, using the sink's bounded WM worker.
 *
 * @param sessionNow Currently active app session.
 * @param nowMs Monotonic time in milliseconds.
 * @param postMain Enqueues a main-thread callback after the specified delay.
 * @param scopeNow Current sink fence and owner revision, or null if unavailable.
 * @param requestReading Starts or joins a read for the given scope, deadline, and optional display.
 * @param activeRequest Read occupying the worker when a request cannot start.
 */
internal class SessionWmCalibration(
    private val sessionNow: () -> MiraxSession,
    private val nowMs: () -> Long,
    private val postMain: (delayMs: Long, action: () -> Unit) -> Unit,
    private val scopeNow: (WfdOwner) -> SinkWmReadScope?,
    private val requestReading: (SinkWmReadScope, Long, Int?) -> SinkWmReadCoordinator.Request?,
    private val activeRequest: () -> SinkWmReadCoordinator.Request?,
) {
    private companion object {
        const val TAG = "SessionHost"
        const val WM_READ_DEADLINE_MS = 2_000L
    }

    private data class PendingRead(
        val session: MiraxSession,
        val request: SinkWmReadCoordinator.Request,
        val deadlineMs: Long,
        var expired: Boolean = false,
    )

    private data class ScreenRead(
        val pending: PendingRead,
        val sequence: Long,
        val preferredModeGeneration: Long,
        val preferredMode: VideoMode?,
        var holdingHandoff: Boolean,
    )

    private var provisioningRead: PendingRead? = null
    private var provisioningWaitingForWm: SinkWmReadCoordinator.Request? = null
    private var provisioningUnavailableOnce = false
    private var screenRead: ScreenRead? = null
    private var useScreenSequence = 0L

    /** Starts or joins first-time provisioning; an unavailable read does not block handoff. */
    fun requestProvisioning(
        session: MiraxSession,
        rotationDegrees: () -> Int,
        commit: () -> Unit,
        refresh: () -> Unit,
    ) {
        val snapshot = session.snapshot()
        if (!needsProvisioning(snapshot)) {
            provisioningUnavailableOnce = false
            return
        }
        if (provisioningRead != null) return
        if (provisioningUnavailableOnce) {
            provisioningUnavailableOnce = false
            return
        }
        val deadline = nowMs() + WM_READ_DEADLINE_MS
        val scope = scopeNow(snapshot.wfdOwner) ?: return
        val request = requestReading(scope, deadline, null) ?: run {
            val busy = activeRequest() ?: return
            if (provisioningWaitingForWm === busy) return
            provisioningWaitingForWm = busy
            busy.onComplete {
                postMain(0L) {
                    if (provisioningWaitingForWm === busy) {
                        provisioningWaitingForWm = null
                        if (sessionNow() === session && needsProvisioning(session.snapshot())) commit()
                    }
                }
            }
            return
        }
        val pending = PendingRead(session, request, minOf(deadline, request.deadlineMs))
        provisioningRead = pending
        postMain((pending.deadlineMs - nowMs()).coerceAtLeast(0)) {
            if (provisioningRead === pending) {
                pending.expired = true
                Log.w(TAG, "Provisioning WM read timed out; retaining the original worker")
                if (sessionNow() === session) commit()
            }
        }
        request.onComplete {
            postMain(0L) complete@{
                if (provisioningRead !== pending) return@complete
                provisioningRead = null
                val snapshotNow = session.snapshot()
                if (sessionNow() !== session || !needsProvisioning(snapshotNow)) return@complete
                val now = nowMs()
                val expired = pending.expired || now >= pending.deadlineMs
                val current = isCurrentRead(pending, snapshotNow, now)
                if (current) {
                    val reading = request.result()
                    if (reading != null) {
                        session.report(PictureRotationReport(rotationDegrees()))
                        session.handle(SessionAction.ApplyProvisioningWmSize(reading))
                    } else {
                        Log.w(TAG, "Provisioning WM read completed without a usable size")
                    }
                } else if (expired) {
                    Log.i(TAG, "Provisioning WM read completed after its deadline")
                }
                provisioningUnavailableOnce = expired || (current && needsProvisioning(session.snapshot()))
                commit()
                if (current) refresh()
            }
        }
    }

    /** Schedules display calibration; only the latest eligible request may apply and refresh. */
    fun useThisScreen(
        session: MiraxSession,
        displayId: Int,
        rotationDegrees: () -> Int,
        commit: () -> Unit,
        refresh: () -> Unit,
    ): SessionSnapshot {
        session.report(MiraxDisplayReport(displayId))
        val snapshot = session.snapshot()
        val scope = if (snapshot.canUseThisScreen) scopeNow(snapshot.wfdOwner) else null
        val deadline = nowMs() + WM_READ_DEADLINE_MS
        val request = scope?.let { requestReading(it, deadline, displayId) }
        if (request == null) {
            useScreenSequence++
            if (scope != null) Log.w(TAG, "Screen WM read unavailable while another display read is pending")
            return snapshot
        }
        val previous = screenRead
        if (previous?.pending?.request === request) return snapshot
        val pending = PendingRead(session, request, minOf(deadline, request.deadlineMs))
        val screen = ScreenRead(
            pending, ++useScreenSequence, session.preferredModeGeneration, snapshot.preferredMode,
            holdingHandoff = previous?.takeIf { it.pending.session === session }?.holdingHandoff == true,
        )
        screenRead = screen
        postMain((pending.deadlineMs - nowMs()).coerceAtLeast(0)) {
            if (screenRead === screen) {
                pending.expired = true
                resumeScreenHandoff(screen, commit)
            }
        }
        request.onComplete {
            postMain(0L) complete@{
                if (screenRead !== screen) return@complete
                screenRead = null
                val reading = request.result()
                if (reading != null && isCurrentScreen(screen, session, session.snapshot())) {
                    session.report(PictureRotationReport(rotationDegrees()))
                    session.handle(SessionAction.UseThisScreen(reading))
                    commit()
                    refresh()
                } else {
                    resumeScreenHandoff(screen, commit)
                }
            }
        }
        return snapshot
    }

    /** Holds only a non-null handoff for current calibration strictly before its deadline. */
    fun holdAdvertiseHandoff(session: MiraxSession, snapshot: SessionSnapshot): Boolean {
        val pending = provisioningRead
        if (pending?.session === session && needsProvisioning(snapshot) && isCurrentRead(pending, snapshot)) {
            return true
        }
        val screen = screenRead ?: return false
        if (screen.pending.session !== session) return false
        screen.holdingHandoff = isCurrentScreen(screen, session, snapshot)
        return screen.holdingHandoff
    }

    private fun resumeScreenHandoff(screen: ScreenRead, commit: () -> Unit) {
        if (!screen.holdingHandoff) return
        screen.holdingHandoff = false
        val session = screen.pending.session
        if (sessionNow() === session && session.snapshot().wfdAdvertise != null) commit()
    }

    private fun isCurrentScreen(screen: ScreenRead, session: MiraxSession, snapshot: SessionSnapshot): Boolean =
        screen.pending.session === session && screen.sequence == useScreenSequence && snapshot.canUseThisScreen &&
            snapshot.miraxDisplayId == screen.pending.request.displayId &&
            session.preferredModeGeneration == screen.preferredModeGeneration &&
            snapshot.preferredMode == screen.preferredMode && isCurrentRead(screen.pending, snapshot)

    private fun isCurrentRead(pending: PendingRead, snapshot: SessionSnapshot, now: Long = nowMs()): Boolean =
        isCurrentSinkWmReading(
            nowMs = now,
            deadlineMs = pending.deadlineMs,
            eligible = !pending.expired && sessionNow() === pending.session,
            sameIdentity = pending.request.scope == scopeNow(snapshot.wfdOwner),
        )

    private fun needsProvisioning(snapshot: SessionSnapshot): Boolean =
        snapshot.canReadWmSize && SessionEffect.ReadPlainWmSizeForProvisioning in snapshot.effects
}
