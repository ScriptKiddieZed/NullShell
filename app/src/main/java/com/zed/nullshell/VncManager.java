package com.zed.nullshell;

import android.content.Context;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A VNC server inside Kali that shows an IceWM desktop, meant to be used from a separate VNC viewer app. It runs
 * Xtigervnc directly (no vncserver wrapper script, no noVNC/websockify) and starts IceWM in it.
 *
 * <p>The server lives as long as one PRoot process. Stopping kills that process and then sweeps /proc for the
 * desktop's own processes (see {@link #sweepGuestProcesses()}), because killing PRoot with SIGKILL does not reliably
 * take its children down. {@link VncService} keeps the app alive meanwhile.
 * State is all static so the screen can be left and reopened while the server keeps running.
 */
final class VncManager {
    private VncManager() { }

    /** Everything the desktop needs. tigervnc-tools is where vncpasswd lives; xfonts-base provides the "fixed" font
     *  without which Xtigervnc refuses to start at all; x11-xkb-utils provides xkbcomp, which it also needs. */
    static final String PACKAGES = "icewm xterm tigervnc-standalone-server tigervnc-common tigervnc-tools"
            + " xfonts-base fonts-dejavu-core x11-xserver-utils x11-xkb-utils xkb-data dbus-x11"
            // GTK file managers (Thunar, PCManFM) need the mime database and an icon theme with SVG support
            + " shared-mime-info adwaita-icon-theme librsvg2-common";

    /** Only what this feature added at the top level; shared libraries/fonts go via autoremove, and xterm, xkb-data,
     *  dbus-x11 are left alone because other things commonly depend on them. */
    private static final String REMOVE_PACKAGES = "icewm tigervnc-standalone-server tigervnc-common tigervnc-tools";

    /** Printed by the start script once the server accepts connections. */
    private static final String READY_MARKER = "VNC server is up";

    static String installCommand() {
        return "apt update && apt install -y " + PACKAGES;
    }

    static String uninstallCommand() {
        return "apt purge -y " + REMOVE_PACKAGES + " ; apt autoremove -y ; rm -rf ~/.vnc";
    }

    // ------------------------------------------------------------------ what is installed

    /** The packages still missing, comma separated; empty when everything needed is there. */
    static String missing(Context ctx) {
        File rootfs = RootfsManager.dir(ctx);
        if (!RootfsManager.isInstalled(ctx)) return "the Kali rootfs";
        List<String> m = new ArrayList<>();
        if (GuestPaths.which(rootfs, "Xtigervnc") == null && GuestPaths.which(rootfs, "Xvnc") == null) m.add("tigervnc-standalone-server");
        if (GuestPaths.which(rootfs, "vncpasswd") == null) m.add("tigervnc-tools");
        if (GuestPaths.which(rootfs, "icewm-session") == null) m.add("icewm");
        if (GuestPaths.which(rootfs, "xkbcomp") == null) m.add("x11-xkb-utils");
        if (!new File(rootfs, "var/lib/dpkg/info/xfonts-base.list").isFile()) m.add("xfonts-base");
        return String.join(", ", m);
    }

    static boolean isInstalled(Context ctx) { return missing(ctx).isEmpty(); }

    /**
     * Packages that GTK file managers (Thunar, PCManFM) need but that earlier installs did not add: the mime database
     * and an icon theme with SVG support. Not required to start the desktop, so this does not block Start.
     */
    /** dpkg names a package's file list "pkg.list", or "pkg:arm64.list" for packages that are built per architecture. */
    private static boolean dpkgHas(File info, String pkg) {
        if (new File(info, pkg + ".list").isFile()) return true;
        String[] names = info.list();
        if (names != null) for (String n : names) if (n.startsWith(pkg + ":") && n.endsWith(".list")) return true;
        return false;
    }

    static String extrasMissing(Context ctx) {
        File rootfs = RootfsManager.dir(ctx);
        List<String> m = new ArrayList<>();
        File info = new File(rootfs, "var/lib/dpkg/info");
        for (String p : new String[]{"shared-mime-info", "adwaita-icon-theme", "librsvg2-common"})
            if (!dpkgHas(info, p)) m.add(p);
        return String.join(", ", m);
    }

    /** True when at least some of the desktop is installed (so Uninstall still makes sense for a half install). */
    static boolean anyInstalled(Context ctx) {
        File rootfs = RootfsManager.dir(ctx);
        return GuestPaths.which(rootfs, "Xtigervnc") != null || GuestPaths.which(rootfs, "Xvnc") != null
                || GuestPaths.which(rootfs, "icewm-session") != null || GuestPaths.which(rootfs, "vncpasswd") != null;
    }

    // ------------------------------------------------------------------ connection details

    static int port(Context ctx) { return 5900 + Prefs.vncDisplay(ctx); }

    /** host:port for a VNC viewer app running on this phone. */
    static String address(Context ctx) { return "127.0.0.1:" + port(ctx); }

    /** Addresses other devices can use, one "label  ·  ip:port" per network; empty when only this phone may connect. */
    static List<String> lanAddresses(Context ctx) {
        List<String> out = new ArrayList<>();
        if (Prefs.vncLocalOnly(ctx)) return out;
        try {
            for (NetInfo.Iface f : NetInfo.collect(ctx)) {
                if (f.ipv4.isEmpty()) continue;
                if (f.kind == NetInfo.CELLULAR || f.kind == NetInfo.VPN) continue;
                out.add(f.label() + "  ·  " + f.ipv4 + ":" + port(ctx));
            }
        } catch (Exception ignored) { }
        return out;
    }

    // ------------------------------------------------------------------ state (all static; read from any thread)

    private static final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vnc-server");
        t.setDaemon(true);
        return t;
    });

    private static volatile Process process;
    private static volatile Process stoppedByUser;
    private static volatile boolean stopFlag;                   // Stop was tapped, possibly before the process existed
    private static volatile boolean ready;
    private static volatile String lastReason;                  // null = the last run did not fail
    private static final AtomicInteger busy = new AtomicInteger();   // queued or running start/cleanup jobs

    private static final StringBuilder lastLog = new StringBuilder();
    private static final int LAST_LOG_CAP = 12000;

    private static synchronized void appendLog(String s) {
        lastLog.append(s);
        if (lastLog.length() > LAST_LOG_CAP) lastLog.delete(0, lastLog.length() - LAST_LOG_CAP);
        if (!ready && lastLog.indexOf(READY_MARKER) >= 0) ready = true;
    }

    private static synchronized void clearLog() { lastLog.setLength(0); }

    /** Output of the most recent start attempt, for finding out why it failed. */
    static synchronized String lastLog() { return lastLog.toString(); }

    /** Why the last run ended badly, or null (never run, still running, or stopped on request). */
    static String lastReason() { return lastReason; }

    /** The server process is alive (it may still be starting). */
    static boolean isRunning() { Process p = process; return p != null && p.isAlive(); }

    /** Running and accepting connections. */
    static boolean isReady() { return ready && isRunning(); }

    /** A start or stop is queued or underway, or the server is up. Keeps {@link VncService} alive. */
    static boolean isBusy() { return busy.get() > 0 || isRunning(); }

    // ------------------------------------------------------------------ start / stop

    static void start(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (isBusy()) return;
        stopFlag = false;
        ready = false;
        lastReason = null;
        clearLog();
        String miss = missing(app);
        if (!miss.isEmpty()) {
            lastReason = "Not fully installed. Missing: " + miss + ". Use Install first.";
            return;
        }
        String problem = ProotLauncher.runtimeError(app);
        if (problem != null) { lastReason = problem; return; }
        busy.incrementAndGet();
        VncService.start(app);
        pool.execute(() -> {
            try { sweepGuestProcesses(); runBlocking(app); } finally { busy.decrementAndGet(); }
        });
    }

    /** Stops the server. Returns immediately; {@link #isBusy()} stays true until the clean-up has finished. */
    static void stop(Context ctx) {
        final Context app = ctx.getApplicationContext();
        stopFlag = true;
        Process p = process;
        stoppedByUser = p;
        if (p != null) p.destroyForcibly();
        busy.incrementAndGet();
        // Own thread, not the single-thread pool: that thread is parked in runBlocking() until the server is gone.
        Thread t = new Thread(() -> {
            try {
                // destroyForcibly() is a SIGKILL, so PRoot cannot clean up after itself and --kill-on-exit is not
                // reliable on every kernel: Xtigervnc and IceWM survive and keep the port. Kill them from this side.
                sweepGuestProcesses();
                try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                sweepGuestProcesses();   // second pass catches anything respawned/reparented meanwhile
            } finally { busy.decrementAndGet(); }
        }, "vnc-stop");
        t.setDaemon(true);
        t.start();
    }

    /** Guest program names that make up the desktop. */
    private static final java.util.Set<String> DESKTOP_PROCS = new java.util.HashSet<>(Arrays.asList(
            "Xtigervnc", "Xvnc", "icewm-session", "icewm", "icewmbg", "icewmtray", "vncconfig"));

    /** Set in the start script's environment: every process the desktop starts inherits it, however it is renamed. */
    private static final String ENV_MARK = "NS_VNC=1";

    /** True when the process's environment carries {@link #ENV_MARK} (needs the same uid; guest processes have it). */
    private static boolean hasMark(File procDir) {
        try {
            byte[] env = java.nio.file.Files.readAllBytes(new File(procDir, "environ").toPath());
            byte[] mark = ENV_MARK.getBytes(StandardCharsets.UTF_8);
            outer:
            for (int i = 0; i + mark.length <= env.length; i++) {
                if (i > 0 && env[i - 1] != 0) continue;            // must start a variable
                for (int j = 0; j < mark.length; j++) if (env[i + j] != mark[j]) continue outer;
                if (i + mark.length == env.length || env[i + mark.length] == 0) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    /**
     * Kills leftover desktop processes by reading /proc directly. The guest's pkill -x cannot be trusted: under PRoot
     * a process's comm is often the loader's name rather than the program's, so matching by name finds nothing.
     * The command line is reliable. All guest processes run as this app's uid, so killProcess is allowed.
     */
    private static void sweepGuestProcesses() {
        File[] dirs = new File("/proc").listFiles();
        if (dirs == null) return;
        final int self = android.os.Process.myPid();
        for (File d : dirs) {
            int pid;
            try { pid = Integer.parseInt(d.getName()); } catch (NumberFormatException e) { continue; }
            if (pid == self) continue;
            try {
                byte[] raw = java.nio.file.Files.readAllBytes(new File(d, "cmdline").toPath());
                if (raw.length == 0) continue;
                int end = 0;
                while (end < raw.length && raw[end] != 0) end++;
                String argv0 = new String(raw, 0, end, StandardCharsets.UTF_8);
                String base = argv0.substring(argv0.lastIndexOf('/') + 1);
                if (DESKTOP_PROCS.contains(base)) { android.os.Process.killProcess(pid); continue; }
            } catch (Exception ignored) { }   // not ours, or already gone
            // Everything else the desktop started (apps launched from it leave helpers behind: dbus, gvfs, at-spi, ...)
            // carries the marker from the start script. Without this, such a helper keeps PRoot alive and the app thinks
            // it still runs.
            if (hasMark(d)) android.os.Process.killProcess(pid);
        }
    }

    private static String script(Context ctx) {
        final int d = Prefs.vncDisplay(ctx);
        final int port = 5900 + d;
        final String sock = "/tmp/.X11-unix/X" + d;

        StringBuilder args = new StringBuilder();
        args.append(" -geometry ").append(Prefs.vncWidth(ctx)).append('x').append(Prefs.vncHeight(ctx));
        args.append(" -depth ").append(Prefs.vncDepth(ctx));
        args.append(" -dpi ").append(Prefs.vncDpi(ctx));
        args.append(" -rfbport ").append(port);
        if (Prefs.vncLocalOnly(ctx)) args.append(" -localhost");
        if (Prefs.vncShared(ctx)) args.append(" -AlwaysShared");
        args.append(" -fp \"$FP\"");
        args.append(" -SecurityTypes VncAuth -rfbauth \"$HOME/.vnc/passwd\" -desktop NullShell");
        String extra = Prefs.vncExtra(ctx).replace('\n', ' ').replace('\r', ' ');
        if (!extra.isEmpty()) args.append(' ').append(extra);

        StringBuilder s = new StringBuilder();
        s.append("export HOME=/root DISPLAY=:").append(d).append(" ").append(ENV_MARK).append("\n");
        // when the server ends for any reason, take everything the desktop started with it, or PRoot never exits
        s.append("ns_cleanup() {\n");
        s.append("  kp=\"\"\n");
        s.append("  for pd in /proc/[0-9]*; do p=${pd#/proc/}; [ \"$p\" = \"$$\" ] && continue;\n");
        s.append("    grep -qz '^NS_VNC=1$' \"$pd/environ\" 2>/dev/null && kp=\"$kp $p\"; done\n");
        s.append("  [ -n \"$kp\" ] && kill -9 $kp 2>/dev/null\n");
        s.append("}\n");
        s.append("trap ns_cleanup EXIT\n");
        s.append("mkdir -p \"$HOME/.vnc\" /tmp/.X11-unix\n");
        s.append("chmod 1777 /tmp/.X11-unix 2>/dev/null\n");
        s.append("XV=$(command -v Xtigervnc || command -v Xvnc)\n");
        s.append("[ -n \"$XV\" ] || { echo 'ERROR: the VNC server (Xtigervnc) is not installed - use Install.'; exit 127; }\n");
        s.append("command -v vncpasswd >/dev/null || { echo 'ERROR: vncpasswd is missing (package tigervnc-tools) - use Install.'; exit 127; }\n");
        s.append("command -v icewm-session >/dev/null || { echo 'ERROR: IceWM is not installed - use Install.'; exit 127; }\n");
        // leftovers from a run that was cut short would otherwise hold the port / display lock
        s.append("pkill -x Xtigervnc 2>/dev/null; pkill -x Xvnc 2>/dev/null; pkill -x icewm-session 2>/dev/null; pkill -x icewm 2>/dev/null\n");
        s.append("rm -f /tmp/.X").append(d).append("-lock ").append(sock).append("\n");
        s.append("printf '%s\\n' ").append(ProotLauncher.quote(Prefs.vncPassword(ctx)))
                .append(" | vncpasswd -f > \"$HOME/.vnc/passwd\" || { echo 'ERROR: could not create the password file.'; exit 1; }\n");
        s.append("chmod 600 \"$HOME/.vnc/passwd\"\n");
        // Font path: the server will not start without the "fixed" font, and "built-ins" always provides it, so it goes
        // last even if the xfonts packages' directories are missing or unregistered.
        s.append("FP=built-ins\n");
        s.append("for fd in /usr/share/fonts/X11/Type1 /usr/share/fonts/X11/75dpi /usr/share/fonts/X11/100dpi /usr/share/fonts/X11/misc; do ")
                .append("[ -d \"$fd\" ] && FP=\"$fd,$FP\"; done\n");
        s.append("echo \"Starting $XV on :").append(d).append(" (port ").append(port).append(")\"\n");
        s.append("\"$XV\" :").append(d).append(args).append(" &\n");
        s.append("XPID=$!\n");
        s.append("i=0\n");
        s.append("until (exec 3<>/dev/tcp/127.0.0.1/").append(port).append(") 2>/dev/null || [ -S ").append(sock).append(" ]; do\n");
        s.append("  kill -0 $XPID 2>/dev/null || { echo 'ERROR: the VNC server exited while starting - see the lines above.'; exit 1; }\n");
        s.append("  i=$((i+1)); [ $i -gt 150 ] && { echo 'ERROR: the VNC server was not ready after 30 seconds.'; exit 1; }\n");
        s.append("  sleep 0.2\n");
        s.append("done\n");
        s.append("echo '").append(READY_MARKER).append(" on port ").append(port).append("'\n");
        s.append("unset SESSION_MANAGER DBUS_SESSION_BUS_ADDRESS\n");
        // no accessibility bus, and no gvfs volume monitors (udisks cannot work here and only prints warnings)
        s.append("export NO_AT_BRIDGE=1 GIO_USE_VOLUME_MONITOR=unix\n");
        s.append("export XDG_RUNTIME_DIR=/tmp/runtime-root\n");
        s.append("mkdir -p \"$XDG_RUNTIME_DIR\"; chmod 700 \"$XDG_RUNTIME_DIR\"\n");
        s.append(IceWmConfig.script(ctx));
        if (Prefs.vncClipboard(ctx)) s.append("command -v vncconfig >/dev/null 2>&1 && vncconfig -nowin >/dev/null 2>&1 &\n");
        if (Prefs.vncTerminal(ctx)) s.append("command -v xterm >/dev/null 2>&1 && xterm -geometry 100x30+30+30 -fa Monospace -fs 11 >/dev/null 2>&1 &\n");
        s.append("if command -v dbus-launch >/dev/null 2>&1; then dbus-launch --exit-with-session icewm-session & else icewm-session & fi\n");
        s.append("wait $XPID\n");
        s.append("exit $?\n");
        return s.toString();
    }

    private static void runBlocking(Context ctx) {
        File rootfs = RootfsManager.dir(ctx);
        IceWmConfig.ensureBundled(ctx);
        try { KaliSession.prepareGuest(ctx, rootfs); } catch (Exception e) { lastReason = RootfsManager.message(e); return; }

        List<String> cmd = ProotLauncher.command(ctx, rootfs, new ArrayList<>(), "/root");
        List<String> argv = Arrays.asList("/bin/bash", "-c", script(ctx));
        cmd.addAll(ProotLauncher.guestCommand(ctx, argv, Arrays.asList("TERM=xterm-256color")));

        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            ProotLauncher.applyEnvironment(pb, ctx);
            pb.redirectErrorStream(true);
            p = pb.start();
            process = p;
            if (stopFlag) { stoppedByUser = p; p.destroyForcibly(); }   // Stop arrived while this was still launching

            // Read on its own thread: if some grandchild outlives PRoot and keeps the pipe open, a read here would never
            // return, and the stop would hang with it.
            final InputStream in = p.getInputStream();
            Thread reader = new Thread(() -> {
                byte[] buf = new byte[2048];
                try {
                    int n;
                    while ((n = in.read(buf)) != -1) appendLog(new String(buf, 0, n, StandardCharsets.UTF_8));
                } catch (Exception ignored) { }
            }, "vnc-log");
            reader.setDaemon(true);
            reader.start();

            int code = p.waitFor();
            reader.join(800);
            boolean requested = stoppedByUser == p;
            if (!requested) {
                lastReason = ready
                        ? "The VNC server stopped unexpectedly (exit " + code + ")."
                        : "The VNC server could not start (exit " + code + ").";
            }
        } catch (Exception e) {
            lastReason = RootfsManager.message(e);
        } finally {
            ready = false;
            process = null;
            if (p != null) p.destroyForcibly();
            sweepGuestProcesses();
        }
    }
}
