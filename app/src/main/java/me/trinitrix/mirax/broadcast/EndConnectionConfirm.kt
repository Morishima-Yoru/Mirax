package me.trinitrix.mirax.broadcast

import android.content.Context
import me.trinitrix.mirax.PictureActivity

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
// No Activity can host a window here. A Dialog built on the application
        // context has no window token and throws BadTokenException from show(),
        // which crashed the app instead of asking the user to confirm. The
        // caller consumes ConfirmEndConnection either way, so skipping the
        // confirm is strictly better than crashing.
        return
    }
}
