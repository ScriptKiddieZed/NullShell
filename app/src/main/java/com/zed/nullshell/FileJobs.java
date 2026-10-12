package com.zed.nullshell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Copy, move, delete, compress and extract jobs. They belong to the process, not to a screen, so
 * they keep running while the file manager is closed; {@link FileJobService} keeps the process
 * alive and shows progress in a notification. Jobs run one after another.
 */
final class FileJobs {
    private FileJobs() { }

    interface Body { void run(FileOps.Job job) throws Exception; }

    /** Called on the main thread while a screen is watching. */
    interface Listener { void onFinished(Record r); }

    static final class Record {
        final int id;
        final String title;
        final FileOps.Job job = new FileOps.Job();
        volatile boolean started, finished;
        volatile String result;            // "Cancelled" or null on success
        volatile Listener listener;
        Record(int id, String title) { this.id = id; this.title = title; }

        String summary() {
            if (result != null) return result;
            if (job.errors == 0) return "Done";
            return "Finished with " + job.errors + (job.errors == 1 ? " error: " : " errors. First: ") + job.firstError;
        }
        boolean ok() { return result == null && job.errors == 0; }
    }

    static final String CHANNEL = "file_jobs";
    static final String CHANNEL_DONE = "file_jobs_done";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "file-jobs");
        t.setDaemon(true);
        return t;
    });
    private static final List<Record> ACTIVE = new ArrayList<>();      // main thread only
    private static int nextId = 1;

    /** Main thread. */
    static Record start(Context c, String title, Body body, Listener listener) {
        final Context app = c.getApplicationContext();
        final Record r = new Record(nextId++, title);
        r.listener = listener;
        ACTIVE.add(r);
        FileJobService.start(app);
        POOL.execute(() -> {
            r.started = true;
            String result = null;
            try {
                if (r.job.cancelled) throw new FileOps.Cancelled();
                body.run(r.job);
            } catch (FileOps.Cancelled e) {
                result = "Cancelled";
            } catch (Throwable e) {
                r.job.fail(title, e instanceof Exception ? (Exception) e : new RuntimeException(e));
            }
            r.result = result;
            MAIN.post(() -> finish(app, r));
        });
        return r;
    }

    private static void finish(Context app, Record r) {
        r.finished = true;
        ACTIVE.remove(r);
        Listener l = r.listener;
        if (l != null) l.onFinished(r);
        else notifyDone(app, r);
        FileJobService.changed(app);
    }

    /** Main thread. */
    static List<Record> active() { return new ArrayList<>(ACTIVE); }

    static void cancel(int id) {
        for (Record r : ACTIVE) if (r.id == id) r.job.cancelled = true;
    }

    static void cancelAll() {
        for (Record r : ACTIVE) r.job.cancelled = true;
    }

    static void ensureChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "File operations", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_DONE, "File operations finished", NotificationManager.IMPORTANCE_DEFAULT));
    }

    static void notifyDone(Context app, Record r) {
        ensureChannels(app);
        PendingIntent open = PendingIntent.getActivity(app, 0,
                new Intent(app, FileManagerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(app, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_stat_terminal)
                .setContentTitle(r.title + (r.ok() ? " finished" : r.result != null ? " cancelled" : " finished with errors"))
                .setContentText(r.summary())
                .setStyle(new Notification.BigTextStyle().bigText(r.summary()))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        app.getSystemService(NotificationManager.class).notify(1000 + r.id, n);
    }
}
