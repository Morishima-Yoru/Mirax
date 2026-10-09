package me.trinitrix.mirax.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import me.trinitrix.mirax.MainActivity
import me.trinitrix.mirax.MiraxApp
import me.trinitrix.mirax.R
import me.trinitrix.mirax.session.SessionSnapshot
import me.trinitrix.mirax.session.WidgetStatus

/**
 * Status-only home-screen widget. Renders session [WidgetStatus] and never
 * toggles broadcast. Tapping opens Mirax. Does not require overlay permission.
 */
class BroadcastStatusWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val snapshot = MiraxApp.instance.session.snapshot()
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context, snapshot))
        }
    }

    companion object {
        fun updateAll(context: Context, snapshot: SessionSnapshot) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, BroadcastStatusWidget::class.java),
            )
            if (ids.isEmpty()) {
                return
            }
            val views = buildViews(context, snapshot)
            for (id in ids) {
                manager.updateAppWidget(id, views)
            }
        }

        private fun buildViews(context: Context, snapshot: SessionSnapshot): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_broadcast_status)
            val statusRes = when (snapshot.widgetStatus) {
                WidgetStatus.OFF -> R.string.widget_status_off
                WidgetStatus.ADVERTISING -> R.string.widget_status_advertising
                WidgetStatus.CONNECTED -> R.string.widget_status_connected
                WidgetStatus.UNAVAILABLE -> R.string.widget_status_unavailable
            }
            views.setTextViewText(R.id.widgetStatus, context.getString(statusRes))
            views.setTextViewText(R.id.widgetTitle, context.getString(R.string.widget_title))
            
            // Set background color based on widget status
            val bgColor = when (snapshot.widgetStatus) {
                WidgetStatus.OFF -> context.getColor(R.color.widget_bg_off)
                WidgetStatus.ADVERTISING -> context.getColor(R.color.widget_bg_advertising)
                WidgetStatus.CONNECTED -> context.getColor(R.color.widget_bg_connected)
                WidgetStatus.UNAVAILABLE -> context.getColor(R.color.widget_bg_unavailable)
            }
            views.setInt(R.id.widgetRoot, "setBackgroundColor", bgColor)
            
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // Tap opens Mirax only; it does not toggle broadcast.
            views.setOnClickPendingIntent(R.id.widgetRoot, open)
            return views
        }
    }
}
