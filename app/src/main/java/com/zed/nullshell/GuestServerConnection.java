package com.zed.nullshell;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import io.github.rosemoe.sora.lsp.client.connection.StreamConnectionProvider;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts a language server inside Kali (through PRoot) and exposes its stdin/stdout to the LSP client.
 * stderr is kept apart, because it is not part of the protocol, and its tail is available for diagnosis.
 */
final class GuestServerConnection implements StreamConnectionProvider {
    private final Context ctx;
    private final File rootfs;
    private final List<String> argv;
    private final String workdir;
    private final List<String> extraEnv;
    private final StringBuilder log = new StringBuilder();

    private Process process;
    private volatile boolean closed = true;

    GuestServerConnection(Context ctx, File rootfs, List<String> argv, String workdir, List<String> extraEnv) {
        this.ctx = ctx.getApplicationContext();
        this.rootfs = rootfs;
        this.argv = argv;
        this.workdir = workdir;
        this.extraEnv = extraEnv == null ? new ArrayList<>() : extraEnv;
    }

    @WorkerThread
    @Override public void start() throws IOException {
        String problem = ProotLauncher.runtimeError(ctx);
        if (problem != null) throw new IOException(problem);
        try { KaliSession.prepareGuest(ctx, rootfs); } catch (Exception e) { throw new IOException(e); }

        List<String> cmd = ProotLauncher.command(ctx, rootfs, new ArrayList<>(), workdir);
        List<String> env = new ArrayList<>();
        env.add("TERM=dumb");
        env.addAll(extraEnv);
        cmd.addAll(ProotLauncher.guestCommand(ctx, argv, env));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        ProotLauncher.applyEnvironment(pb, ctx);
        pb.redirectErrorStream(false);
        process = pb.start();
        closed = false;

        final InputStream err = process.getErrorStream();
        Thread t = new Thread(() -> {
            byte[] b = new byte[2048];
            try {
                int n;
                while ((n = err.read(b)) != -1) {
                    synchronized (log) {
                        log.append(new String(b, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                        if (log.length() > 8000) log.delete(0, log.length() - 6000);
                    }
                }
            } catch (IOException ignored) { }
        }, "lsp-stderr");
        t.setDaemon(true);
        t.start();
    }

    @NonNull @Override public InputStream getInputStream() { return process.getInputStream(); }
    @NonNull @Override public OutputStream getOutputStream() { return process.getOutputStream(); }
    @Override public boolean isClosed() { return closed || process == null || !process.isAlive(); }

    @Override public void close() {
        closed = true;
        if (process != null) process.destroyForcibly();    // --kill-on-exit takes the server's children with it
    }

    /** The last part of what the server wrote to stderr. */
    String logTail() {
        synchronized (log) { return log.toString(); }
    }
}
