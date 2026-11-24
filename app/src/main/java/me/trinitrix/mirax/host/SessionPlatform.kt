package me.trinitrix.mirax.host

import android.content.Context
import me.trinitrix.mirax.session.OverlayPermissionReport
import me.trinitrix.mirax.session.PrivilegeReport
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.WfdAdvertiseCommand
import me.trinitrix.mirax.session.WfdOwner
import me.trinitrix.mirax.session.WifiReport
import me.trinitrix.mirax.wfd.SinkProvisioningFence
import me.trinitrix.mirax.wfd.SinkWmReadCoordinator
import me.trinitrix.mirax.wfd.SinkWmReadScope

/** Android-facing operations used by the session host. */
internal interface SessionPlatform {
    /**
     * Read the Wi-Fi radio state.
     * @param context Context used to access Wi-Fi.
     * @return Current radio report.
     */
    fun reportWifi(context: Context): WifiReport

    /**
     * Sync the Wi-Fi state listener.
     * @param context Context used to register the listener.
     * @param watch Whether to watch the radio.
     */
    fun syncWifi(context: Context, watch: Boolean)

    /**
     * Prompt the user to enable Wi-Fi.
     * @param context Context used to show the prompt.
     */
    fun promptWifi(context: Context)

    /**
     * Read the available privilege paths.
     * @param context Context used for privilege checks.
     * @return Current privilege report.
     */
    fun probePrivilege(context: Context): PrivilegeReport

    /**
     * Refresh the root helper asynchronously.
     * @param context Context used to start the helper.
     * @param onChanged Callback when the cache or helper state may have changed.
     */
    fun refreshRoot(context: Context, onChanged: () -> Unit)

    /**
     * Request Shizuku authorization.
     * @param context Context used for the permission request.
     */
    fun requestShizukuPermission(context: Context)

    /**
     * Sync the selected WFD owner.
     * @param context Context used to bind the owner.
     * @param owner Desired owner.
     */
    fun syncOwner(context: Context, owner: WfdOwner)

    /**
     * Read the current local owner revision.
     * @param owner Owner whose revision is requested.
     * @return Revision, or null when unavailable.
     */
    fun ownerRevision(owner: WfdOwner): Long?

    /**
     * Sync the WFD advertise command.
     * @param context Context used to access the owner.
     * @param command Desired command, or null to stop.
     * @return False if an advertise request could not be handed to an owner.
     */
    fun syncAdvertise(context: Context, command: WfdAdvertiseCommand?): Boolean

    /**
     * Read the sink provisioning identity.
     * @return Current source and generation fence.
     */
    fun provisioningFence(): SinkProvisioningFence

    /**
     * Start or join a bounded WM size read.
     * @param scope Owner and source requesting the read.
     * @param deadlineMs Original monotonic deadline.
     * @param displayId Display to query, or null for the plain size.
     * @return The request, or null when another read occupies the worker.
     */
    fun requestWmReading(
        scope: SinkWmReadScope,
        deadlineMs: Long,
        displayId: Int? = null,
    ): SinkWmReadCoordinator.Request?

    /**
     * Read the request currently occupying the WM worker.
     * @return Active request, or null when idle.
     */
    fun activeWmRequest(): SinkWmReadCoordinator.Request?

    /**
     * Sync the sink receive path.
     * @param context Context used by the sink.
     * @param command Desired advertise command, or null to stop.
     */
    fun syncSink(context: Context, command: WfdAdvertiseCommand?)

    /** Drop the active sink connection. */
    fun dropActiveConnection()

    /**
     * Read overlay permission state.
     * @param context Context used for the permission check.
     * @return Current overlay permission report.
     */
    fun reportOverlay(context: Context): OverlayPermissionReport

    /**
     * Request overlay permission.
     * @param context Context used to open the permission UI.
     */
    fun requestOverlay(context: Context)

    /**
     * Open the system overlay settings.
     * @param context Context used to open settings.
     */
    fun openOverlaySettings(context: Context)

    /**
     * Sync the advertising keep-alive service.
     * @param context Context used to access the service.
     * @param snapshot Current session snapshot.
     */
    fun syncKeepAlive(context: Context, snapshot: SessionSnapshot)

    /**
     * Update the broadcast widgets.
     * @param context Context used to update widgets.
     * @param snapshot Current session snapshot.
     */
    fun updateWidgets(context: Context, snapshot: SessionSnapshot)

    /**
     * Request a broadcast tile refresh.
     * @param context Context used to request listening.
     */
    fun requestTileListening(context: Context)

    /**
     * Sync the floating-ball service.
     * @param context Context used to access the service.
     * @param snapshot Current session snapshot.
     */
    fun syncFloatingBall(context: Context, snapshot: SessionSnapshot)
}
