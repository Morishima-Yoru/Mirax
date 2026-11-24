package me.trinitrix.mirax.host

import android.content.Context
import me.trinitrix.mirax.BuildConfig
import me.trinitrix.mirax.AdvertisingKeepAliveService
import me.trinitrix.mirax.FloatingBallService
import me.trinitrix.mirax.session.OverlayPermissionReport
import me.trinitrix.mirax.session.PrivilegeReport
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.session.WifiReport
import me.trinitrix.mirax.tile.BroadcastTileService
import me.trinitrix.mirax.wfd.SinkConnectionController
import me.trinitrix.mirax.wfd.SinkProvisioningFence
import me.trinitrix.mirax.wfd.SinkWmReadCoordinator
import me.trinitrix.mirax.wfd.SinkWmReadScope
import me.trinitrix.mirax.wfd.SinkWmSizeReader
import me.trinitrix.mirax.wfd.WfdOwnerBridge
import me.trinitrix.mirax.widget.BroadcastStatusWidget
import me.trinitrix.mirax.wifi.WifiRadio

/** Production platform delegation; hardware objects are accessed only by method calls. */
/**
 Whether privileged helper socket and cached root observation stay enabled.
 Trial Shizuku-only builds opt out; production default keeps them enabled.
 */
internal fun helperPathsAllowed(trialShizukuOnly: Boolean = BuildConfig.TRIAL_SHIZUKU_ONLY): Boolean =
    !trialShizukuOnly

internal class AndroidSessionPlatform : SessionPlatform {
    override fun reportWifi(context: Context): WifiReport = WifiRadio.report(context)

    override fun syncWifi(context: Context, watch: Boolean) = WifiRadio.sync(context, watch)

    override fun promptWifi(context: Context) = WifiRadio.prompt(context)

    override fun probePrivilege(context: Context): PrivilegeReport = PrivilegeProbe.probe(context)

    override fun refreshRoot(context: Context, onChanged: () -> Unit) {
        //Trial must never start or replace the shared helper socket.
        if (!helperPathsAllowed()) return
        RootHelper.refreshAsync(context, onChanged)
    }

    override fun requestShizukuPermission(context: Context) = PrivilegeProbe.requestShizukuPermission(context)

    override fun syncOwner(context: Context, owner: WfdOwner) = WfdOwnerBridge.syncOwner(context, owner)

    override fun ownerRevision(owner: WfdOwner): Long? = WfdOwnerBridge.ownerRevision(owner)

    override fun syncAdvertise(context: Context, command: WfdAdvertiseCommand?): Boolean =
        WfdOwnerBridge.sync(context, command)

    override fun provisioningFence(): SinkProvisioningFence = SinkConnectionController.provisioningFence()

    override fun requestWmReading(
        scope: SinkWmReadScope,
        deadlineMs: Long,
        displayId: Int?,
    ): SinkWmReadCoordinator.Request? = SinkWmSizeReader.request(scope, deadlineMs, displayId)

    override fun activeWmRequest(): SinkWmReadCoordinator.Request? = SinkWmSizeReader.activeRequest()

    override fun syncSink(context: Context, command: WfdAdvertiseCommand?) =
        SinkConnectionController.sync(context, command)

    override fun dropActiveConnection() = SinkConnectionController.dropActiveConnection()

    override fun reportOverlay(context: Context): OverlayPermissionReport = OverlayPermission.report(context)

    override fun requestOverlay(context: Context) = OverlayPermission.request(context)

    override fun openOverlaySettings(context: Context) = OverlayPermission.openSettings(context)

    override fun syncKeepAlive(context: Context, snapshot: SessionSnapshot) =
        AdvertisingKeepAliveService.sync(context, snapshot)

    override fun updateWidgets(context: Context, snapshot: SessionSnapshot) =
        BroadcastStatusWidget.updateAll(context, snapshot)

    override fun requestTileListening(context: Context) = BroadcastTileService.requestListening(context)

    override fun syncFloatingBall(context: Context, snapshot: SessionSnapshot) =
        FloatingBallService.sync(context, snapshot)
}
