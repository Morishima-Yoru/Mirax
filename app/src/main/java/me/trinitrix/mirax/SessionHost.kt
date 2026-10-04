package me.trinitrix.mirax

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import android.widget.Toast
import me.trinitrix.mirax.broadcast.BroadcastAutoStop
import me.trinitrix.mirax.broadcast.EndConnectionConfirm
import me.trinitrix.mirax.host.OverlayPermission
import me.trinitrix.mirax.host.PrivilegeProbe
import me.trinitrix.mirax.host.RootHelper
import me.trinitrix.mirax.session.PictureRotationReport
import me.trinitrix.mirax.session.WmSizeReading
import me.trinitrix.mirax.session.PictureScale
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SessionEffect
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.TileState
import me.trinitrix.mirax.session.WfdOwner
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
     * "Use this screen" after confirming the session allows a wm size read.
     */
    fun useThisScreen(context: Context, displayId: Int): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.report(me.trinitrix.mirax.session.MiraxDisplayReport(displayId))
        val snapshot = session.snapshot()
        if (!snapshot.canUseThisScreen) {
            return snapshot
        }
        val reading = WmSize.readForDisplay(displayId) ?: return snapshot
        session.report(PictureRotationReport(degrees = pictureRotationDegrees(context, displayId)))
        session.handle(SessionAction.UseThisScreen(reading))
        return commit(context)
    }

    /**
     * Persist settings, sync keep-alive / status surfaces, apply effects.
     */
    fun commit(context: Context): SessionSnapshot {
        val appContext = context.applicationContext
        val session = MiraxApp.instance.session
        WfdOwnerBridge.syncOwner(appContext, session.snapshot().wfdOwner)
        maybeApplyProvisioningWmSize(appContext, session)
        val snapshot = session.snapshot()
        SessionPreferences.saveAdvertising(appContext, snapshot.advertisingEnabled)
        SessionPreferences.saveResolutionSettings(appContext, session.exportSettings())
        WifiRadio.sync(appContext, snapshot.advertisingEnabled)
        BroadcastAutoStop.sync(snapshot)
        AdvertisingKeepAliveService.sync(appContext, snapshot)
        BroadcastStatusWidget.updateAll(appContext, snapshot)
        BroadcastTileService.requestListening(appContext)
        FloatingBallService.sync(appContext, snapshot)
        applyEffects(appContext, snapshot)
        // StopHelper runs before advertise so Shizuku takes exclusive ownership.
        val afterEffects = MiraxApp.instance.session.snapshot()
        WfdOwnerBridge.syncOwner(appContext, afterEffects.wfdOwner)
        val advertiseOk = WfdOwnerBridge.sync(appContext, afterEffects.wfdAdvertise)
        if (afterEffects.wfdAdvertise != null && !advertiseOk) {
            session.handle(SessionAction.BeaconFailed)
            applyEffects(appContext, session.snapshot())
        }
        SinkConnectionController.sync(appContext, afterEffects.wfdAdvertise)
        return MiraxApp.instance.session.snapshot()
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
        return snapshot
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

    private fun maybeApplyProvisioningWmSize(
        context: Context,
        session: me.trinitrix.mirax.session.MiraxSession,
    ) {
        val snapshot = session.snapshot()
        if (SessionEffect.ReadPlainWmSizeForProvisioning !in snapshot.effects) {
            return
        }
        if (!snapshot.canReadWmSize) {
            return
        }
        val reading = WmSize.readPlain() ?: return
        session.report(
            PictureRotationReport(degrees = pictureRotationDegrees(context, displayId = null)),
        )
        session.handle(SessionAction.ApplyProvisioningWmSize(reading))
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
        if (SessionEffect.StopHelper in snapshot.effects) {
            PrivilegeProbe.requestStopHelper()
            consumed = true
        }
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
