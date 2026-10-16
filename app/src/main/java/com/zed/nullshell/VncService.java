package com.zed.nullshell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Keeps the app process alive while the VNC server runs and shows a notification with a Stop button. It owns
 * nothing itself: {@link VncManager} runs the server, and this service stops itself once that is idle.
 */
public class VncService extends Service {
    private static final String CHANNEL = "vnc";
    static final String ACTION_STOP = "com.zed.nullshell.VNC_STOP";
    private static final int NOTIF_ID = 3;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private boolean foreground;

    static void start(Context c) {
        try { c.startForegroundService(new Intent(c, VncService.class)); } catch (Exception ignored) { }
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "VNC server", NotificationManager.IMPORTANCE_LOW));
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!VncManager.isBusy()) { shutDown(); return; }
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, build());
            main.postDelayed(this, 1500);
        }
    };

    private Notification build() {
        String text;
        if (VncManager.isReady()) text = "Running  ·  connect to " + VncManager.address(this);
        else if (VncManager.isRunning()) text = "Starting…";
        else text = "Stopping…";
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, VncActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 3,
                new Intent(this, VncService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_terminal)
                .setContentTitle("VNC server")
                .setContentText(text)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_terminal), "Stop", stop).build())
                .build();
    }

    private void startFg() {
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_ID, n);
        foreground = true;
    }

    private void shutDown() {
        main.removeCallbacks(tick);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        stopSelf();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            VncManager.stop(this);
            if (!foreground) { shutDown(); return START_NOT_STICKY; }
            // otherwise stay up until the server and its clean-up are really finished: the tick ends the service
            main.removeCallbacks(tick);
            main.post(tick);
            return START_NOT_STICKY;
        }
        if (!foreground) startFg();
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nullshell:vnc");
            wakeLock.acquire(12L * 60 * 60 * 1000);
        }
        main.removeCallbacks(tick);
        main.post(tick);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        main.removeCallbacks(tick);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
