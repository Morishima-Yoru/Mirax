package com.secondscreen.receiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Keeps the projection process alive and shows a ball that brings the picture back. */
public class FloatingBallService extends Service {
    private static final String CHANNEL = "projection";
    private WindowManager windows;
    private View ball;
    private WindowManager.LayoutParams ballParams;
    private float downRawX;
    private float downRawY;
    private int downX;
    private int downY;
    private boolean dragging;

    public static void show(Context context) {
        Intent intent = new Intent(context, FloatingBallService.class);
        intent.setAction("show");
        context.startForegroundService(intent);
    }

    public static void hide(Context context) {
        context.stopService(new Intent(context, FloatingBallService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promote();
        if (intent != null && "show".equals(intent.getAction()) && Settings.canDrawOverlays(this)) {
            showBall();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        ShellBridge.endSession();
        ShellBridge.setBroadcast(false);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        hideBall();
        super.onDestroy();
    }

    private void promote() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "投影", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        Intent open = new Intent(this, SetupActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(
                this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_slideshow)
                .setContentTitle("第二螢幕正在投影")
                .setContentText("點懸浮球回到畫面")
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
        startForeground(71, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    }

    private void showBall() {
        if (ball != null) {
            return;
        }
        windows = getSystemService(WindowManager.class);
        float density = getResources().getDisplayMetrics().density;
        int window = Math.round(72 * density);
        int circle = Math.round(52 * density);
        int closeSize = Math.round(22 * density);
        FrameLayout root = new FrameLayout(this);
        TextView open = new TextView(this);
        open.setText("投");
        open.setTextColor(0xFF12141A);
        open.setTextSize(16);
        open.setGravity(Gravity.CENTER);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(0xFFF3F0E8);
        open.setBackground(shape);
        FrameLayout.LayoutParams openParams = new FrameLayout.LayoutParams(circle, circle, Gravity.START | Gravity.BOTTOM);
        root.addView(open, openParams);
        TextView close = new TextView(this);
        close.setText("×");
        close.setTextColor(0xFFF3F0E8);
        close.setTextSize(14);
        close.setGravity(Gravity.CENTER);
        GradientDrawable closeShape = new GradientDrawable();
        closeShape.setShape(GradientDrawable.OVAL);
        closeShape.setColor(0xFF2A2E36);
        close.setBackground(closeShape);
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                closeSize, closeSize, Gravity.END | Gravity.TOP);
        root.addView(close, closeParams);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        ballParams = new WindowManager.LayoutParams(
                window,
                window,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        ballParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        ballParams.x = Math.round(12 * density);
        ballParams.y = 0;
        open.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return drag(event);
            }
        });
        windows.addView(root, ballParams);
        ball = root;
    }

    private void dismiss() {
        ShellBridge.endSession();
        ShellBridge.setBroadcast(false);
        stopSelf();
    }

    private boolean drag(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downRawX = event.getRawX();
            downRawY = event.getRawY();
            downX = ballParams.x;
            downY = ballParams.y;
            dragging = false;
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            int dx = Math.round(event.getRawX() - downRawX);
            int dy = Math.round(event.getRawY() - downRawY);
            if (!dragging && dx * dx + dy * dy > 64) {
                dragging = true;
                int[] location = new int[2];
                ball.getLocationOnScreen(location);
                ballParams.gravity = Gravity.TOP | Gravity.START;
                downX = location[0];
                downY = location[1];
                downRawX = event.getRawX();
                downRawY = event.getRawY();
            }
            if (dragging) {
                ballParams.x = downX + Math.round(event.getRawX() - downRawX);
                ballParams.y = downY + Math.round(event.getRawY() - downRawY);
                windows.updateViewLayout(ball, ballParams);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP && !dragging) {
            Intent open = new Intent(this, SetupActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(open);
            return true;
        }
        return true;
    }

    private void hideBall() {
        if (windows != null && ball != null) {
            windows.removeView(ball);
        }
        ball = null;
    }
}
