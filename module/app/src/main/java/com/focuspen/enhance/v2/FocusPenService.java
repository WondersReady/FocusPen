package com.focuspen.enhance.v2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Foreground service for module-side runtime work (skeleton phase: rise + log only).
 *
 * <p>Runs in the module's own app process, never inside a hooked target. Uses a
 * {@code specialUse} FGS type; the subtype {@code <property>} is declared in the manifest.
 * On targetSdk 34+ this requires {@code FOREGROUND_SERVICE} +
 * {@code FOREGROUND_SERVICE_SPECIAL_USE} (both normal-level, auto-granted).</p>
 *
 * <p>P3+ hangs the overlay / audio engine here.</p>
 */
public class FocusPenService extends Service {

    private static final String TAG = "FocusPen";
    private static final String CHANNEL_ID = "focuspen";
    private static final int NOTIFICATION_ID = 0x4650; // 'FP'

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand startId=" + startId);
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        // Persistent service: restart if the system kills it.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "焦点笔增强", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ch.setDescription("模块运行状态");
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("焦点笔增强")
                .setContentText("模块服务运行中")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build();
    }
}
