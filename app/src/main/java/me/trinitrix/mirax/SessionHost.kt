package me.trinitrix.mirax

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import android.widget.Toast
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.trinitrix.mirax.broadcast.BroadcastAutoStop
import me.trinitrix.mirax.broadcast.EndConnectionConfirm
import me.trinitrix.mirax.host.OverlayPermission
import me.trinitrix.mirax.host.PrivilegeProbe
import me.trinitrix.mirax.host.RootHelper
import me.trinitrix.mirax.session.MiraxSession
import me.trinitrix.mirax.session.PictureRotationReport
import me.trinitrix.mirax.session.PictureScale
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SessionEffect
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.TileState
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.session.WmSizeReading
import me.trinitrix.mirax.tile.BroadcastTileService
import me.trinitrix.mirax.wfd.SinkConnectionController
import me.trinitrix.mirax.wfd.WfdOwnerBridge
import me.trinitrix.mirax.widget.BroadcastStatusWidget
import me.trinitrix.mirax.wifi.WifiRadio
import me.trinitrix.mirax.wm.WmSize

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
    private const val SOURCE_NAME_TIMEOUT_NS = 2_000_000_000L
    private val sourceNameScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val calibrationInFlightCount = AtomicInteger(0)
    private val provisioningReadInFlight = AtomicBoolean(false)
    private val useThisScreenGeneration = AtomicInteger(0)

    internal var ioRunner: (Runnable) -> Unit = { task -> sourceNameScope.launch { task.run() } }
    internal var mainPoster: (Runnable) -> Unit = { task -> mainHandler.post(task) }
    internal var plainWmSizeReader: () -> WmSizeReading? = { WmSize.readPlain() }
    internal var displayWmSizeReader: (Int) -> WmSizeReading? = { displayId -> WmSize.readForDisplay(displayId) }

    /**
     * Reset asynchronous calibration state and restore production seams for unit tests.
     */
    internal fun resetForTests() {
        calibrationInFlightCount.set(0)
        provisioningReadInFlight.set(false)
        useThisScreenGeneration.incrementAndGet()
        ioRunner = { task -> sourceNameScope.launch { task.run() } }
        mainPoster = { task -> mainHandler.post(task) }
        plainWmSizeReader = { WmSize.readPlain() }
        displayWmSizeReader = { displayId -> WmSize.readForDisplay(displayId) }
    }
    /**
     * Dashboard or other UI toggled the shared advertising switch.
     */
    fun setAdvertising(context: Context, enabled: Boolean): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.report(WifiRadio.report(context))
        session.handle(SessionAction.SetAdvertising(enabled))
        return commit(context)
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
        session.report(PrivilegeProbe.probe(context))
        session.report(WifiRadio.report(context))
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
        return commit(context)
    }

    /**
     * "Use this screen" after confirming the session allows a `wm size` read.
     *
     * @param context Activity or application context.
     * @param displayId Display hosting the Mirax window.
     * @return Immediate session snapshot while the display `wm size` read runs off the main thread.
     */
    fun useThisScreen(context: Context, displayId: Int): SessionSnapshot {
        val appContext = context.applicationContext ?: context
        val session = MiraxApp.instance.session
        return scheduleUseThisScreen(
            session = session,
            displayId = displayId,
            rotationDegrees = { pictureRotationDegrees(appContext, displayId) },
            onCalibratedOnMain = {
                if (MiraxApp.instance.session === session) {
                    commitInternal(appContext, allowProvisioningRead = false)
                    MainActivity.refreshIfShowing()
                    PictureActivity.refreshIfShowing()
                }
            },
        )
    }

    /**
     * Persist settings, sync keep-alive / status surfaces, apply effects.
     *
     * @param context Activity or application context.
     * @return Reconciled session snapshot.
     */
    fun commit(context: Context): SessionSnapshot {
        return commitInternal(context.applicationContext ?: context, allowProvisioningRead = true)
    }

    /**
     * Reconcile session state and surfaces with optional provisioning calibration.
     *
     * @param appContext application context for owner and surface sync
     * @param allowProvisioningRead whether a missing calibration may start an off-main read
     * @return reconciled session snapshot
     */
    private fun commitInternal(appContext: Context, allowProvisioningRead: Boolean): SessionSnapshot {
        val session = MiraxApp.instance.session
        WfdOwnerBridge.syncOwner(appContext, session.snapshot().wfdOwner)
        if (allowProvisioningRead) {
            maybeScheduleProvisioningWmSize(
                session = session,
                rotationDegrees = { pictureRotationDegrees(appContext, displayId = null) },
                onCalibratedOnMain = {
                    if (MiraxApp.instance.session === session) {
                        commitInternal(appContext, allowProvisioningRead = false)
                        MainActivity.refreshIfShowing()
                        PictureActivity.refreshIfShowing()
                    }
                },
            )
        }
        return commitAdvertising(
            session = session,
            syncAdvertise = { command ->
                WfdOwnerBridge.syncOwner(appContext, session.snapshot().wfdOwner)
                WfdOwnerBridge.sync(appContext, command)
            },
            applyEffects = { snapshot -> applyEffects(appContext, snapshot) },
            publish = { snapshot ->
                SessionPreferences.saveAdvertising(appContext, snapshot.advertisingEnabled)
                SessionPreferences.saveResolutionSettings(appContext, session.exportSettings())
                WifiRadio.sync(appContext, snapshot.advertisingEnabled)
                BroadcastAutoStop.sync(snapshot)
                AdvertisingKeepAliveService.sync(appContext, snapshot)
                BroadcastStatusWidget.updateAll(appContext, snapshot)
                BroadcastTileService.requestListening(appContext)
                FloatingBallService.sync(appContext, snapshot)
            },
            syncSink = { command -> SinkConnectionController.sync(appContext, command) },
            holdAdvertiseHandoff = calibrationInFlightCount.get() > 0,
        )
    }

    /**
     * Reconcile owner acceptance before publishing a single final state to status surfaces.
     * One-shot effects are consumed before handing the command to the owner.
     *
     * @param session session owning the advertising state
     * @param syncAdvertise owner command boundary
     * @param applyEffects one-shot effect boundary
     * @param publish settings and status surfaces boundary
     * @param syncSink receiver boundary
     * @param holdAdvertiseHandoff whether an off-main `wm size` calibration is in flight and
     *     non-null advertise hand-off must wait until calibration finishes
     * @return the final state shown to the caller and external surfaces
     */
    internal fun commitAdvertising(
        session: MiraxSession,
        syncAdvertise: (WfdAdvertiseCommand?) -> Boolean,
        applyEffects: (SessionSnapshot) -> Unit,
        publish: (SessionSnapshot) -> Unit,
        syncSink: (WfdAdvertiseCommand?) -> Unit,
        holdAdvertiseHandoff: Boolean = calibrationInFlightCount.get() > 0,
    ): SessionSnapshot {
        applyEffects(session.snapshot())
        val command = session.snapshot().wfdAdvertise
        if (command != null && holdAdvertiseHandoff) {
            val pendingSnapshot = session.snapshot()
            publish(pendingSnapshot)
            return pendingSnapshot
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
        MainActivity.refreshIfShowing()
        if (action is SessionAction.BeginConnectionRun && action.remoteHost.isNotBlank()) {
            resolveSourceDeviceName(context.applicationContext, session, action.remoteHost, session.sourceNameGeneration)
        }
        return snapshot
    }

    /**
     * Resolve the source's hostname off the main thread and ignore stale connection results.
     *
     * @param context application context for dispatching the name update
     * @param session session receiving the resolved name
     * @param host source IP address
     * @param generation connection generation used to reject stale lookups
     */
    private fun resolveSourceDeviceName(
        context: Context,
        session: me.trinitrix.mirax.session.MiraxSession,
        host: String,
        generation: Long,
    ) {
        sourceNameScope.launch {
            // shortcut: timed-out platform DNS may occupy an IO worker; use cancellable DNS if timeouts recur.
            val startedAt = System.nanoTime()
            val name = try {
                InetAddress.getByName(host).hostName.takeUnless { it.equals(host, ignoreCase = true) }
            } catch (e: Exception) {
                Log.w("SessionHost", "Failed to resolve source device name for $host", e)
                null
            }
            if (System.nanoTime() - startedAt > SOURCE_NAME_TIMEOUT_NS) {
                Log.w("SessionHost", "Source device name lookup timed out for $host")
                return@launch
            }
            if (name == null) return@launch
            mainHandler.post {
                if (MiraxApp.instance.session !== session || session.sourceNameGeneration != generation) return@post
                session.handle(SessionAction.SetSourceDeviceName(name))
                commit(context)
                PictureActivity.refreshIfShowing()
            }
        }
    }

    /**
     * Freeze the M3 offer for the connection that is about to speak RTSP.
     *
     * Does not re-apply the listen beacon. The wm-size preference is for this
     * connection's capability answer only.
     */
    fun freezeConnectionOffer(context: Context, reading: WmSizeReading?): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.report(PictureRotationReport(pictureRotationDegrees(context, null)))
        session.handle(SessionAction.FreezeConnectionOffer(reading))
        val snapshot = session.snapshot()
        MainActivity.refreshIfShowing()
        return snapshot
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
        session.report(OverlayPermission.report(context))
        session.handle(SessionAction.RequestOverlayPermission)
        return commit(context)
    }

    /**
     * User left projection for the phone home screen.
     */
    fun leftProjectionToHome(context: Context): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.report(OverlayPermission.report(context))
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
        RootHelper.refreshAsync(appContext) {
            val session = MiraxApp.instance.session
            session.report(PrivilegeProbe.probe(appContext))
            commit(appContext)
        }
        val session = MiraxApp.instance.session
        session.report(PrivilegeProbe.probe(appContext))
        session.report(OverlayPermission.report(appContext))
        session.report(WifiRadio.report(appContext))
        return commit(appContext)
    }

    /**
     * Schedule an off-main plain `wm size` read when provisioning requires calibration.
     *
     * @param session session owning the provisioning state
     * @param rotationDegrees supplier for the default display rotation on the main thread
     * @param onCalibratedOnMain callback invoked on the main thread after the read finishes
     * @return true when a provisioning read is currently in flight and advertise hand-off should wait
     */
    internal fun maybeScheduleProvisioningWmSize(
        session: MiraxSession,
        rotationDegrees: () -> Int,
        onCalibratedOnMain: () -> Unit,
    ): Boolean {
        val snapshot = session.snapshot()
        if (SessionEffect.ReadPlainWmSizeForProvisioning !in snapshot.effects || !snapshot.canReadWmSize) {
            return false
        }
        if (!provisioningReadInFlight.compareAndSet(false, true)) {
            return true
        }
        calibrationInFlightCount.incrementAndGet()
        ioRunner(
            Runnable {
                val reading = try {
                    plainWmSizeReader()
                } catch (err: Exception) {
                    Log.w("SessionHost", "Plain wm size read failed", err)
                    null
                }
                mainPoster(
                    Runnable {
                        try {
                            val current = session.snapshot()
                            if (reading != null &&
                                current.canReadWmSize &&
                                SessionEffect.ReadPlainWmSizeForProvisioning in current.effects
                            ) {
                                session.report(PictureRotationReport(degrees = rotationDegrees()))
                                session.handle(SessionAction.ApplyProvisioningWmSize(reading))
                            }
                        } finally {
                            calibrationInFlightCount.decrementAndGet()
                            provisioningReadInFlight.set(false)
                        }
                        onCalibratedOnMain()
                    },
                )
            },
        )
        return true
    }

    /**
     * Report the active display and read its `wm size` off the main thread before applying
     * [SessionAction.UseThisScreen].
     *
     * @param session session receiving the display and calibration updates
     * @param displayId display hosting the Mirax window
     * @param rotationDegrees supplier for that display's rotation on the main thread
     * @param onCalibratedOnMain callback invoked on the main thread after calibration settles
     * @return immediate session snapshot before the asynchronous read completes
     */
    internal fun scheduleUseThisScreen(
        session: MiraxSession,
        displayId: Int,
        rotationDegrees: () -> Int,
        onCalibratedOnMain: () -> Unit,
    ): SessionSnapshot {
        session.report(me.trinitrix.mirax.session.MiraxDisplayReport(displayId))
        val snapshot = session.snapshot()
        if (!snapshot.canUseThisScreen) {
            return snapshot
        }
        val generation = useThisScreenGeneration.incrementAndGet()
        calibrationInFlightCount.incrementAndGet()
        ioRunner(
            Runnable {
                val reading = try {
                    displayWmSizeReader(displayId)
                } catch (err: Exception) {
                    Log.w("SessionHost", "Display wm size read failed for $displayId", err)
                    null
                }
                mainPoster(
                    Runnable {
                        try {
                            val current = session.snapshot()
                            if (useThisScreenGeneration.get() == generation &&
                                reading != null &&
                                current.canUseThisScreen && current.miraxDisplayId == displayId
                            ) {
                                session.report(PictureRotationReport(degrees = rotationDegrees()))
                                session.handle(SessionAction.UseThisScreen(reading))
                            }
                        } finally {
                            calibrationInFlightCount.decrementAndGet()
                        }
                        onCalibratedOnMain()
                    },
                )
            },
        )
        return snapshot
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
            SinkConnectionController.dropActiveConnection()
            consumed = true
        }
        if (SessionEffect.RequestOverlayPermission in snapshot.effects) {
            OverlayPermission.request(context)
            consumed = true
        }
        if (SessionEffect.OpenOverlaySettings in snapshot.effects) {
            OverlayPermission.openSettings(context)
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
            WifiRadio.prompt(context)
            consumed = true
        }
        if (consumed) {
            MiraxApp.instance.session.handle(SessionAction.AcknowledgeEffects)
        }
    }
}
