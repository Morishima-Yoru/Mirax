package me.trinitrix.mirax.broadcast

import android.content.Context
import android.view.ContextThemeWrapper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.trinitrix.mirax.PictureActivity
import me.trinitrix.mirax.R

/**
 * Confirmation before dropping the active Miracast session. Prefer the visible
 * [PictureActivity] so the dialog is themed; fall back to the application context.
 */
object EndConnectionConfirm {
    fun show(context: Context, onConfirmed: () -> Unit) {
        val activity = PictureActivity.current()
        if (activity != null && !activity.isFinishing) {
            activity.runOnUiThread {
                activity.showEndConnectionConfirm(onConfirmed)
            }
            return
        }
        val themed = ContextThemeWrapper(context.applicationContext, R.style.Theme_Mirax)
        MaterialAlertDialogBuilder(themed)
            .setMessage(R.string.end_connection_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.end_connection_confirm_action) { _, _ ->
                onConfirmed()
            }
            .show()
    }
}
