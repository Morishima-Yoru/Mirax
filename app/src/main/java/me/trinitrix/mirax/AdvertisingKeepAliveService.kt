package me.trinitrix.mirax

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import me.trinitrix.mirax.session.ScreenPhase
import me.trinitrix.mirax.session.SessionSnapshot

/**
 * Foreground keep-alive while the shared broadcast switch is on.
 *
 * Does not implement the real WFD beacon (issue #6). Keeps the process and
 * the advertising phase alive after the activity is backgrounded until the
 * user turns broadcast off.
 */
class AdvertisingKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val snapshot = MiraxApp.instance.session.snapshot()
        if (!snapshot.advertisingEnabled) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground(snapshot)
        return START_STICKY
    }

    private fun startAsForeground(snapshot: SessionSnapshot) {
        ensureChannel()
        val notification = buildNotification(snapshot)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun buildNotification(snapshot: SessionSnapshot): Notification {
        val statusText = when (snapshot.phase) {
            ScreenPhase.CONNECTED -> getString(R.string.keepalive_status_connected)
            ScreenPhase.ADVERTISING -> getString(R.string.keepalive_status_advertising)
            ScreenPhase.READY, ScreenPhase.FROZEN -> getString(R.string.keepalive_status_waiting)
        }
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.keepalive_title))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(launch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.keepalive_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.keepalive_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "mirax_advertising"
        private const val NOTIFICATION_ID = 0x4D58_01

        /**
         * Start or stop the keep-alive service from the latest session snapshot.
         */
        fun sync(context: Context, snapshot: SessionSnapshot) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, AdvertisingKeepAliveService::class.java)
            if (snapshot.advertisingEnabled) {
                appContext.startForegroundService(intent)
            } else {
                appContext.stopService(intent)
            }
        }
    }
}
