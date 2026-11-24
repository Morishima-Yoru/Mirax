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
import me.trinitrix.mirax.broadcast.BroadcastAutoStop
import me.trinitrix.mirax.broadcast.EndConnectionConfirm
import me.trinitrix.mirax.host.SessionPlatformBinding
import me.trinitrix.mirax.session.PictureRotationReport
import me.trinitrix.mirax.session.WmSizeReading
import me.trinitrix.mirax.session.PictureScale
import me.trinitrix.mirax.session.PrePlayAttemptId
import me.trinitrix.mirax.session.PrePlayAttemptIdGenerator
import me.trinitrix.mirax.session.PrePlayFailureEpisodeOwner
import me.trinitrix.mirax.session.PrePlayNonFailureReason
import me.trinitrix.mirax.session.PrePlayPreemptReason
import me.trinitrix.mirax.session.SinkAttemptSource
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SessionEffect
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.TileState
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.wfd.SinkWmReadCoordinator
import me.trinitrix.mirax.wfd.SinkWmReadScope
import me.trinitrix.mirax.wfd.isCurrentSinkWmReading
import me.trinitrix.mirax.wfd.isCurrentSinkWmScope

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
    private const val TAG = "SessionHost"
    private const val WM_READ_DEADLINE_MS = 2_000L
    private val mainHandler = Handler(Looper.getMainLooper())
    private data class ProvisioningRead(
        val request: SinkWmReadCoordinator.Request,
        val scope: SinkWmReadScope,
        val deadline: Long,
        var expired: Boolean = false,
    )
    private var provisioningRead: ProvisioningRead? = null
    private var provisioningWaitingForWm: SinkWmReadCoordinator.Request? = null
    private var provisioningUnavailableOnce = false
    private var screenRead: SinkWmReadCoordinator.Request? = null
    private var useScreenSequence = 0L
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
            me.trinitrix.mirax.session.ScreenPhase.CONNECTED ->
                PrePlayFailureEpisodeOwner.getInstance()
                    .onPreempted(PrePlayPreemptReason.PROJECTION_STARTED)
            me.trinitrix.mirax.session.ScreenPhase.WIFI_PAUSED ->
                PrePlayFailureEpisodeOwner.getInstance()
                    .onPreempted(PrePlayPreemptReason.WIFI_PAUSED)
            me.trinitrix.mirax.session.ScreenPhase.FROZEN ->
                PrePlayFailureEpisodeOwner.getInstance()
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
        val snapshot = commit(context)
        // An explicit user stop via the tile supersedes any FAILED episode.
        if (!wasGray && wasAdvertising && !snapshot.advertisingEnabled) {
            explicitStopPrePlay()
        }
        return snapshot
    }

    /**
     * Starts a bounded asynchronous screen-size read after checking permission.
     *
     * @return The current snapshot; a valid completed read refreshes it asynchronously.
     */
    fun useThisScreen(context: Context, displayId: Int): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.report(me.trinitrix.mirax.session.MiraxDisplayReport(displayId))
        val snapshot = session.snapshot()
        if (!snapshot.canUseThisScreen) {
            useScreenSequence++
            return snapshot
        }
        val deadline = SystemClock.elapsedRealtime() + WM_READ_DEADLINE_MS
        val fence = SessionPlatformBinding.platform.provisioningFence()
        val revision = SessionPlatformBinding.platform.ownerRevision(snapshot.wfdOwner) ?: run {
            useScreenSequence++
            return snapshot
        }
        val scope = SinkWmReadScope(snapshot.wfdOwner, fence, revision)
        val request = SessionPlatformBinding.platform.requestWmReading(scope, deadline, displayId) ?: run {
            useScreenSequence++
            Log.w(TAG, "Screen WM read unavailable while another display read is pending")
            return snapshot
        }
        if (screenRead === request) return snapshot
        val readDeadline = minOf(deadline, request.deadlineMs)
        val sequence = ++useScreenSequence
        screenRead = request
        val appContext = context.applicationContext
        request.onComplete {
            mainHandler.post {
                if (screenRead === request) screenRead = null
                val current = session.snapshot()
                val reading = request.result()
                if (reading == null || SystemClock.elapsedRealtime() >= readDeadline || sequence != useScreenSequence ||
                    MiraxApp.instance.session !== session || current.wfdOwner != snapshot.wfdOwner ||
                    !current.canUseThisScreen || current.miraxDisplayId != displayId ||
                    current.preferredMode != snapshot.preferredMode ||
                    current.preferredModeText != snapshot.preferredModeText ||
                    !isCurrentSinkWmScope(
                        scope, current.wfdOwner, SessionPlatformBinding.platform.provisioningFence(),
                        SessionPlatformBinding.platform.ownerRevision(current.wfdOwner),
                    )
                ) return@post
                session.report(PictureRotationReport(degrees = pictureRotationDegrees(appContext, displayId)))
                session.handle(SessionAction.UseThisScreen(reading))
                commit(appContext)
                MainActivity.refreshIfShowing()
            }
        }
        return snapshot
    }

    /**
     * Persist settings, sync keep-alive / status surfaces, apply effects.
     */
    fun commit(context: Context): SessionSnapshot {
        val appContext = context.applicationContext
        val session = MiraxApp.instance.session
        SessionPlatformBinding.platform.syncOwner(appContext, session.snapshot().wfdOwner)
        val provisioningPending = maybeApplyProvisioningWmSize(appContext, session)
        val snapshot = publishSessionState(appContext, session)
        applyEffects(appContext, snapshot)
        // Apply one-shot effects before the owner receives the advertise command.
        val afterEffects = MiraxApp.instance.session.snapshot()
        SessionPlatformBinding.platform.syncOwner(appContext, afterEffects.wfdOwner)
        val waitingForProvisioning = provisioningPending && afterEffects.canReadWmSize &&
            SessionEffect.ReadPlainWmSizeForProvisioning in afterEffects.effects
        if (!waitingForProvisioning || afterEffects.wfdAdvertise == null) {
            val advertiseOk = SessionPlatformBinding.platform.syncAdvertise(appContext, afterEffects.wfdAdvertise)
            if (afterEffects.wfdAdvertise != null && !advertiseOk) {
                session.handle(SessionAction.BeaconFailed)
                val failureSnapshot = publishSessionState(appContext, session)
                applyEffects(appContext, failureSnapshot)
                SessionPlatformBinding.platform.syncAdvertise(appContext, session.snapshot().wfdAdvertise)
            }
            SessionPlatformBinding.platform.syncSink(appContext, session.snapshot().wfdAdvertise)
        }
        val finalSnapshot = MiraxApp.instance.session.snapshot()
        trackPrePlayPreemption(finalSnapshot)
        return finalSnapshot
    }

    /**
     * Publishes the original session's current state without applying effects or provisioning.
     *
     * @param appContext Application context of the current commit.
     * @param session Original session whose state is published.
     * @return Snapshot used for all publications.
     */
    private fun publishSessionState(
        appContext: Context,
        session: me.trinitrix.mirax.session.MiraxSession,
    ): SessionSnapshot {
        val snapshot = session.snapshot()
        SessionPreferences.saveAdvertising(appContext, snapshot.advertisingEnabled)
        SessionPreferences.saveResolutionSettings(appContext, session.exportSettings())
        SessionPlatformBinding.platform.syncWifi(appContext, snapshot.advertisingEnabled)
        BroadcastAutoStop.sync(snapshot)
        SessionPlatformBinding.platform.syncKeepAlive(appContext, snapshot)
        SessionPlatformBinding.platform.updateWidgets(appContext, snapshot)
        SessionPlatformBinding.platform.requestTileListening(appContext)
        SessionPlatformBinding.platform.syncFloatingBall(appContext, snapshot)
        return snapshot
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
     * System Back on the picture surface. Session decides toast vs end-connection.
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
     * User dismissed the picture status panel.
     */
    fun collapseBottomHandleExpanded(context: Context): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.CollapseBottomHandleExpanded)
        return commit(context)
    }

    /**
     * Persist and apply the bottom-handle setting.
     */
    fun setBottomHandleEnabled(context: Context, enabled: Boolean): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.SetBottomHandleEnabled(enabled))
        SessionPreferences.saveBottomHandleEnabled(context.applicationContext, enabled)
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
     * User tapped the thin bottom handle to expand or collapse its panel.
     */
    fun toggleBottomHandleExpanded(context: Context): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.ToggleBottomHandleExpanded)
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
     * User tapped the floating ball to return to projection.
     */
    fun floatingBallTapped(context: Context): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.handle(SessionAction.FloatingBallTapped)
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

    private fun maybeApplyProvisioningWmSize(
        context: Context,
        session: me.trinitrix.mirax.session.MiraxSession,
    ): Boolean {
        val snapshot = session.snapshot()
        if (SessionEffect.ReadPlainWmSizeForProvisioning !in snapshot.effects || !snapshot.canReadWmSize) {
            provisioningUnavailableOnce = false
            return false
        }
        provisioningRead?.let { pending ->
            return !pending.expired && SystemClock.elapsedRealtime() < pending.deadline &&
                isCurrentSinkWmScope(
                    pending.scope, snapshot.wfdOwner, SessionPlatformBinding.platform.provisioningFence(),
                    SessionPlatformBinding.platform.ownerRevision(snapshot.wfdOwner),
                )
        }
        if (provisioningUnavailableOnce) {
            provisioningUnavailableOnce = false
            return false
        }
        val deadline = SystemClock.elapsedRealtime() + WM_READ_DEADLINE_MS
        val fence = SessionPlatformBinding.platform.provisioningFence()
        val revision = SessionPlatformBinding.platform.ownerRevision(snapshot.wfdOwner) ?: return false
        val scope = SinkWmReadScope(snapshot.wfdOwner, fence, revision)
        val request = SessionPlatformBinding.platform.requestWmReading(scope, deadline) ?: run {
            val busy = SessionPlatformBinding.platform.activeWmRequest()
            if (busy != null && provisioningWaitingForWm !== busy) {
                provisioningWaitingForWm = busy
                busy.onComplete {
                    mainHandler.post {
                        if (provisioningWaitingForWm === busy) {
                            provisioningWaitingForWm = null
                            if (MiraxApp.instance.session === session) commit(context)
                        }
                    }
                }
            }
            return false
        }
        val pending = ProvisioningRead(request, scope, minOf(deadline, request.deadlineMs))
        provisioningRead = pending
        mainHandler.postDelayed({
            if (provisioningRead === pending) {
                pending.expired = true
                Log.w(TAG, "Provisioning WM read timed out; retaining the original worker")
                if (MiraxApp.instance.session === session) commit(context)
            }
        }, (pending.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        pending.request.onComplete {
            mainHandler.post {
                if (provisioningRead !== pending) return@post
                provisioningRead = null
                if (pending.expired) Log.i(TAG, "Provisioning WM read completed after its deadline")
                val current = session.snapshot()
                val eligible = MiraxApp.instance.session === session && current.canReadWmSize &&
                    SessionEffect.ReadPlainWmSizeForProvisioning in current.effects
                val now = SystemClock.elapsedRealtime()
                val expired = pending.expired || now >= pending.deadline
                val valid = isCurrentSinkWmReading(
                    nowMs = now,
                    deadlineMs = pending.deadline,
                    eligible = eligible && !pending.expired,
                    sameIdentity = isCurrentSinkWmScope(
                        pending.scope, current.wfdOwner, SessionPlatformBinding.platform.provisioningFence(),
                        SessionPlatformBinding.platform.ownerRevision(current.wfdOwner),
                    ),
                )
                val reading = pending.request.result()
                if (valid) {
                    if (reading != null) {
                        session.report(PictureRotationReport(degrees = pictureRotationDegrees(context, displayId = null)))
                        session.handle(SessionAction.ApplyProvisioningWmSize(reading))
                        if (SessionEffect.ReadPlainWmSizeForProvisioning in session.snapshot().effects) {
                            provisioningUnavailableOnce = true
                        }
                    } else {
                        Log.w(TAG, "Provisioning WM read completed without a usable size")
                        provisioningUnavailableOnce = true
                    }
                    commit(context)
                    MainActivity.refreshIfShowing()
                } else if (eligible) {
                    if (expired) provisioningUnavailableOnce = true
                    commit(context)
                }
            }
        }
        return true
    }

    /**
     * Map [Display.getRotation] to degrees the session understands.
     *
     * Args:
     *     context: Used to resolve the display manager.
     *     displayId: Specific display, or null for the default display.
     */
    private fun pictureRotationDegrees(context: Context, displayId: Int?): Int {
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

    private fun applyEffects(context: Context, snapshot: SessionSnapshot) {
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
            consumed = true
        }
        if (SessionEffect.PromptEnableWifi in snapshot.effects) {
            SessionPlatformBinding.platform.promptWifi(context)
            consumed = true
        }
        if (consumed) {
            MiraxApp.instance.session.handle(SessionAction.AcknowledgeEffects)
        }
    }
}
