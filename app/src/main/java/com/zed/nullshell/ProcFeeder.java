package com.zed.nullshell;

import android.content.Context;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Keeps the fake /proc/stat, /proc/loadavg and /proc/uptime fresh while at least one session runs.
 * The files are bound over the real (Android-blocked) ones inside Kali; see {@link ProcStats}.
 * They are rewritten in place with a constant length, so tools that keep /proc/stat open
 * (top) see new values on their next read.
 */
final class ProcFeeder {
    private static final ProcFeeder INSTANCE = new ProcFeeder();
    static ProcFeeder get() { return INSTANCE; }

    private final ProcStats stats = new ProcStats(new File("/proc"), new ProcStats.Env() {
        @Override public long elapsedMs() { return SystemClock.elapsedRealtime(); }
        @Override public long wallMs() { return System.currentTimeMillis(); }
        @Override public int cpus() { return Runtime.getRuntime().availableProcessors(); }
        @Override public boolean isMine(File pidDir) {
            try { return Os.stat(pidDir.getAbsolutePath()).st_uid == android.os.Process.myUid(); }
            catch (ErrnoException e) { return false; }
        }
    });

    private File dir;
    private int holders;
    private Thread thread;

    private ProcFeeder() { }

    /** Call when a session starts; creates the files so they exist before PRoot binds them. */
    synchronized void acquire(Context ctx) {
        dir = new File(ctx.getFilesDir(), "fakeproc");
        dir.mkdirs();
        holders++;
        stats.sample();
        writeAll();
        if (thread == null || !thread.isAlive()) {
            thread = new Thread(this::loop, "proc-feeder");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** Call exactly once per acquire(), when that session's process has ended. */
    synchronized void release() {
        if (holders > 0) holders--;
    }

    File file(String name) { return new File(dir, name); }

    private void loop() {
        try {
            while (true) {
                Thread.sleep(1000);
                synchronized (this) {
                    if (holders == 0) { thread = null; return; }
                    stats.sample();
                    writeAll();
                }
            }
        } catch (InterruptedException ignored) {
            synchronized (this) { thread = null; }
        }
    }

    private void writeAll() {
        try {
            writeInPlace(new File(dir, "stat"), stats.statText());
            writeInPlace(new File(dir, "loadavg"), stats.loadavgText());
            writeInPlace(new File(dir, "uptime"), stats.uptimeText());
        } catch (IOException ignored) { }
    }

    private static void writeInPlace(File f, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.US_ASCII);
        if (f.isFile() && f.length() == b.length) {
            try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
                r.seek(0);
                r.write(b);                    // one write: readers see old or new text, not a mix
            }
        } else {
            try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); }
        }
    }
}
