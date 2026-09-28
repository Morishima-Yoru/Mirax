package me.trinitrix.mirax

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import me.trinitrix.mirax.session.OverlayPermissionReport

/**
 * Host-side overlay ("display over other apps") helpers. Product decisions stay
 * in [me.trinitrix.mirax.session.MiraxSession]; this object only reads the
 * platform grant and starts the system UI.
 *
 * The home-screen widget does not use this permission.
 */
object OverlayPermission {
    @Volatile
    private var manageUiOfferedThisStay: Boolean = false

    fun report(context: Context): OverlayPermissionReport {
        val granted = canDrawOverlays(context)
        return OverlayPermissionReport(
            granted = granted,
            canPrompt = !manageUiOfferedThisStay || granted,
        )
    }

    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * Present the package-scoped manage-overlay UI.
     */
    fun request(context: Context) {
        manageUiOfferedThisStay = true
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Open the system overlay settings list when the package-scoped UI will not
     * be shown again.
     */
    fun openSettings(context: Context) {
        manageUiOfferedThisStay = true
        val listIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (listIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(listIntent)
            return
        }
        val details = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(details)
    }
}
