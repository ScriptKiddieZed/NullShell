package com.zed.nullshell;

import android.app.Notification;
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
import android.text.format.Formatter;

import java.util.List;

/** Keeps the process alive and shows progress while {@link FileJobs} are running. */
public class FileJobService extends Service {
    static final String ACTION_CANCEL = "com.zed.nullshell.FILEJOB_CANCEL";
    private static final int NOTIF_ID = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private boolean foreground;

    static void start(Context c) {
        try { c.startForegroundService(new Intent(c, FileJobService.class)); } catch (Exception ignored) { }
    }

    /** A job ended: let the service re-evaluate (it stops itself when nothing is left). */
    static void changed(Context c) {
        try { c.startService(new Intent(c, FileJobService.class)); } catch (Exception ignored) { }
    }

    @Override public void onCreate() {
        super.onCreate();
        FileJobs.ensureChannels(this);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            List<FileJobs.Record> jobs = FileJobs.active();
            if (jobs.isEmpty()) { shutDown(); return; }
            getSystemService(NotificationManager.class).notify(NOTIF_ID, build(jobs));
            main.postDelayed(this, 700);
        }
    };

    private Notification build(List<FileJobs.Record> jobs) {
        FileJobs.Record first = jobs.get(0);
        FileOps.Job j = first.job;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, FileManagerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent cancel = PendingIntent.getService(this, 2,
                new Intent(this, FileJobService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String title = first.title + (jobs.size() > 1 ? "  (+" + (jobs.size() - 1) + " waiting)" : "");
        StringBuilder text = new StringBuilder();
        if (j.cancelled) text.append("Cancelling…");
        else if (!first.started) text.append("Waiting…");
        else {
            String cur = j.current == null ? "" : j.current;
            text.append(cur.isEmpty() ? "Working…" : cur);
            if (j.total > 0) text.append("  ·  ").append(Formatter.formatShortFileSize(this, j.done)).append(" of ").append(Formatter.formatShortFileSize(this, j.total));
            else if (j.itemsTotal > 0) text.append("  ·  ").append(j.items).append(" of ").append(j.itemsTotal);
        }
        Notification.Builder b = new Notification.Builder(this, FileJobs.CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_terminal)
                .setContentTitle(title)
                .setContentText(text.toString())
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_terminal), "Cancel", cancel).build());
        if (j.total > 0) b.setProgress(1000, (int) Math.min(1000, j.done * 1000 / j.total), false);
        else if (j.itemsTotal > 0) b.setProgress(1000, Math.min(1000, j.items * 1000 / j.itemsTotal), false);
        else b.setProgress(0, 0, true);
        return b.build();
    }

    private void shutDown() {
        main.removeCallbacks(tick);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        stopSelf();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) FileJobs.cancelAll();
        List<FileJobs.Record> jobs = FileJobs.active();
        if (jobs.isEmpty() && !foreground) {
            // started with nothing to do (e.g. the process was recreated): satisfy the foreground contract, then leave
            startFg(build0());
            shutDown();
            return START_NOT_STICKY;
        }
        if (!foreground) startFg(jobs.isEmpty() ? build0() : build(jobs));
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nullshell:filejobs");
            wakeLock.acquire(3L * 60 * 60 * 1000);
        }
        main.removeCallbacks(tick);
        main.post(tick);
        return START_NOT_STICKY;
    }

    private Notification build0() {
        return new Notification.Builder(this, FileJobs.CHANNEL).setSmallIcon(R.drawable.ic_stat_terminal)
                .setContentTitle("File operations").build();
    }

    private void startFg(Notification n) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NOTIF_ID, n);
        foreground = true;
    }

    @Override public void onDestroy() {
        main.removeCallbacks(tick);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
