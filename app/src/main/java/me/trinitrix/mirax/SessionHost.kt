package me.trinitrix.mirax

import android.content.Context
import android.widget.Toast
import me.trinitrix.mirax.session.SessionAction
import me.trinitrix.mirax.session.SessionEffect
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.TileState
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.tile.BroadcastTileService
import me.trinitrix.mirax.widget.BroadcastStatusWidget

/**
 * Host-side bridge that applies session actions, persists settings, keeps the
 * advertising foreground service in sync, refreshes the Quick Settings tile and
 * home-screen widget, reads `wm size` when the session allows, and shows toasts.
 *
 * Product decisions stay in [me.trinitrix.mirax.session.MiraxSession]; this
 * object only performs Android side effects.
 */
object SessionHost {
    /**
     * Dashboard or other UI toggled the shared advertising switch.
     */
    fun setAdvertising(context: Context, enabled: Boolean): SessionSnapshot {
        val session = MiraxApp.instance.session
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
        val reading = WmSizeReader.readForDisplay(displayId) ?: return snapshot
        session.handle(SessionAction.UseThisScreen(reading))
        return commit(context)
    }

    /**
     * Persist settings, sync keep-alive / status surfaces, apply effects.
     */
    fun commit(context: Context): SessionSnapshot {
        val appContext = context.applicationContext
        val session = MiraxApp.instance.session
        maybeApplyProvisioningWmSize(session)
        val snapshot = session.snapshot()
        SessionPreferences.saveAdvertising(appContext, snapshot.advertisingEnabled)
        SessionPreferences.saveResolutionSettings(appContext, session.exportSettings())
        AdvertisingKeepAliveService.sync(appContext, snapshot)
        BroadcastStatusWidget.updateAll(appContext, snapshot)
        BroadcastTileService.requestListening(appContext)
        applyEffects(appContext, snapshot)
        return session.snapshot()
    }

    /**
     * Refresh privilege and push status surfaces without a user action.
     */
    fun refreshPrivilegeAndSurfaces(context: Context): SessionSnapshot {
        val session = MiraxApp.instance.session
        session.report(PrivilegeProbe.probe(context))
        return commit(context)
    }

    private fun maybeApplyProvisioningWmSize(session: me.trinitrix.mirax.session.MiraxSession) {
        val snapshot = session.snapshot()
        if (SessionEffect.ReadPlainWmSizeForProvisioning !in snapshot.effects) {
            return
        }
        if (!snapshot.canReadWmSize) {
            return
        }
        val reading = WmSizeReader.readPlain() ?: return
        session.handle(SessionAction.ApplyProvisioningWmSize(reading))
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
        if (consumed) {
            MiraxApp.instance.session.handle(SessionAction.AcknowledgeEffects)
        }
    }
}
