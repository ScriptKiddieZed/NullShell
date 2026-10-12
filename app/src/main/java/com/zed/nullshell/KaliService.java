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
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Foreground service that keeps the app process (and therefore the PRoot session) alive while
 * the Activity is in the background. Holds a partial wake lock so long installs/scans keep running.
 */
public class KaliService extends Service {
    static final String ACTION_STOP = "com.zed.nullshell.STOP";
    private static final String CHANNEL = "kali_session";
    private static final int NOTIF_ID = 1;

    private PowerManager.WakeLock wakeLock;

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, KaliService.class));
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, KaliService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Kali session", NotificationManager.IMPORTANCE_LOW));
    }

    private Notification buildNotification() {
        int n = SessionManager.get().aliveCount();
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, KaliService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_terminal)
                .setContentTitle(n == 1 ? "Kali session running" : n + " Kali sessions running")
                .setContentText("Tap to open the terminal")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat_terminal), "Stop all", stop).build())
                .build();
    }

    /** Runs on the main thread whenever a session starts, ends or is closed. */
    private void onSessionsChanged() {
        if (SessionManager.get().aliveCount() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        } else {
            getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            SessionManager.get().stopAll();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
        SessionManager.get().setServiceObserver(this::onSessionsChanged);

        if (wakeLock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nullshell:session");
            wakeLock.setReferenceCounted(false);
        }
        if (Prefs.wakeLock(this)) {
            if (!wakeLock.isHeld()) wakeLock.acquire();
        } else if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        SessionManager.get().setServiceObserver(null);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
