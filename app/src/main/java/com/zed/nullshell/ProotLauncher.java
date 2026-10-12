package com.zed.nullshell;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Builds PRoot command lines. Shared by terminal sessions, language servers and quick one-off commands. */
final class ProotLauncher {
    private ProotLauncher() { }

    static final String PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    static File nativeDir(Context ctx) { return new File(ctx.getApplicationInfo().nativeLibraryDir); }

    /** Null when the bundled PRoot runtime is complete, otherwise a human-readable problem. */
    static String runtimeError(Context ctx) {
        File dir = nativeDir(ctx);
        if (!new File(dir, "libproot.so").isFile() || !new File(dir, "libproot-loader.so").isFile()
                || !new File(dir, "libtalloc.so").isFile() || !new File(dir, "libandroid-shmem.so").isFile()) {
            return "PRoot runtime is incomplete in nativeLibraryDir:\n" + dir +
                    "\nExpected libproot.so, libproot-loader.so, libtalloc.so and libandroid-shmem.so.\n" +
                    "Run scripts/prepare-proot-from-termux.sh and rebuild/reinstall the APK.";
        }
        return null;
    }

    /**
     * The PRoot part of a command line, ending with {@code -w <workdir>}; append the guest program after it.
     * fakeBinds are "host:guest" pairs that must follow the /proc bind.
     */
    static List<String> command(Context ctx, File rootfs, List<String> fakeBinds, String workdir) {
        List<String> c = new ArrayList<>();
        c.add(new File(nativeDir(ctx), "libproot.so").getAbsolutePath());
        c.add("--kill-on-exit");
        c.add("--link2symlink");
        c.add("-0");
        c.add("-r"); c.add(rootfs.getAbsolutePath());
        c.add("-b"); c.add("/dev");
        c.add("-b"); c.add("/proc");
        for (String b : fakeBinds) { c.add("-b"); c.add(b); }
        if (Prefs.bindSys(ctx)) { c.add("-b"); c.add("/sys"); }
        if (Prefs.bindSdcard(ctx)) { c.add("-b"); c.add("/sdcard"); }
        String extra = Prefs.extraProot(ctx);
        if (!extra.isEmpty()) for (String a : extra.split("\\s+")) c.add(a);
        c.add("-w"); c.add(workdir);
        return c;
    }

    /** Loader and library settings PRoot needs in its own process environment. */
    static void applyEnvironment(ProcessBuilder pb, Context ctx) {
        File dir = nativeDir(ctx);
        pb.environment().put("PROOT_LOADER", new File(dir, "libproot-loader.so").getAbsolutePath());
        File loader32 = new File(dir, "libproot-loader32.so");
        if (loader32.isFile()) pb.environment().put("PROOT_LOADER_32", loader32.getAbsolutePath());
        pb.environment().put("LD_LIBRARY_PATH", dir.getAbsolutePath());
        pb.environment().put("PROOT_TMP_DIR", ctx.getCacheDir().getAbsolutePath());
        pb.directory(ctx.getFilesDir());
    }

    /** A guest command wrapped in a clean environment: env -i HOME=... PATH=... argv... */
    static List<String> guestCommand(Context ctx, List<String> argv, List<String> extraEnv) {
        List<String> c = new ArrayList<>();
        c.add("/usr/bin/env");
        c.add("-i");
        c.add("HOME=/root");
        c.add("USER=root");
        c.add("LOGNAME=root");
        c.add("PATH=" + PATH);
        c.add("LANG=" + Prefs.lang(ctx));
        c.add("TMPDIR=/tmp");
        c.addAll(extraEnv);
        c.addAll(argv);
        return c;
    }

    /** Runs a short shell snippet in the guest and returns its exit code (-1 on failure or timeout). */
    static int runShell(Context ctx, File rootfs, String script, long timeoutMs) {
        try {
            List<String> cmd = command(ctx, rootfs, new ArrayList<>(), "/root");
            List<String> argv = new ArrayList<>();
            argv.add("/bin/sh");
            argv.add("-c");
            argv.add(script);
            cmd.addAll(guestCommand(ctx, argv, new ArrayList<>()));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            applyEnvironment(pb, ctx);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            drain(p.getInputStream());
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) { p.destroyForcibly(); return -1; }
            return p.exitValue();
        } catch (IOException | InterruptedException e) {
            return -1;
        }
    }

    private static void drain(InputStream in) {
        Thread t = new Thread(() -> {
            byte[] b = new byte[4096];
            try { while (in.read(b) != -1) { /* discard */ } } catch (IOException ignored) { }
        }, "proot-drain");
        t.setDaemon(true);
        t.start();
    }

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
