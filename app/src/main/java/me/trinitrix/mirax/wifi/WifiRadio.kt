package me.trinitrix.mirax.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.trinitrix.mirax.MainActivity
import me.trinitrix.mirax.MiraxApp
import me.trinitrix.mirax.R
import me.trinitrix.mirax.SessionHost
import me.trinitrix.mirax.session.WifiReport

/**
 * Device Wi-Fi radio for Miracast advertising: whether it is on, watching it
 * while broadcast intent is on, and asking the user to turn it on.
 */
object WifiRadio {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != WifiManager.WIFI_STATE_CHANGED_ACTION) {
                return
            }
            val app = context.applicationContext
            MiraxApp.instance.session.report(report(app))
            SessionHost.commit(app)
            MainActivity.refreshIfShowing()
        }
    }

    fun report(context: Context): WifiReport {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return WifiReport(enabled = false)
        @Suppress("DEPRECATION")
        val enabled = when (wifi.wifiState) {
            WifiManager.WIFI_STATE_ENABLED,
            WifiManager.WIFI_STATE_ENABLING,
            -> true
            else -> false
        }
        return WifiReport(enabled = enabled)
    }

    /** Register or drop the radio listener while the user wants broadcast on. */
    fun sync(context: Context, watch: Boolean) {
        val app = context.applicationContext
        if (watch && !registered) {
            val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
            registered = true
        } else if (!watch && registered) {
            app.unregisterReceiver(receiver)
            registered = false
        }
    }

    fun prompt(context: Context) {
        val host = MainActivity.dialogHost()
        if (host != null && !host.isFinishing) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                showOn(host)
            } else {
                host.runOnUiThread { showOn(host) }
            }
            return
        }
        showOn(ContextThemeWrapper(context.applicationContext, R.style.Theme_Mirax))
    }

    private fun showOn(context: Context) {
        val canPanel = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val message = if (canPanel) {
            context.getString(R.string.wifi_required_message_panel)
        } else {
            context.getString(R.string.wifi_required_message_settings)
        }
        val positiveLabel = if (canPanel) {
            context.getString(R.string.wifi_required_turn_on)
        } else {
            context.getString(R.string.wifi_required_open_settings)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.wifi_required_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(positiveLabel) { _, _ ->
                openWifiUi(context)
            }
            .show()
    }

    private fun openWifiUi(context: Context) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            app.startActivity(
                Intent(Settings.Panel.ACTION_WIFI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        Toast.makeText(app, app.getString(R.string.wifi_required_toast), Toast.LENGTH_LONG).show()
        app.startActivity(
            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
