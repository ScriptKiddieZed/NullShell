package com.zed.nullshell;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One terminal tab: an emulator plus (optionally) a PRoot process. id 0 is the "Console" tab,
 * which only shows logs and never starts a process.
 *
 * PTY strategy: Java cannot call forkpty(), so inside the guest we run
 * {@code script -qefc ns-shell /dev/null}. script(1) allocates a real pseudo-terminal, which
 * gives proper job control, Ctrl+C, colours and full-screen programs. Window-size changes are
 * delivered through a per-session FIFO that a tiny guest-side watcher turns into
 * {@code stty rows R cols C} on the PTY (the kernel then raises SIGWINCH).
 */
public final class KaliSession {
    public interface Listener {
        void onScreenChanged(KaliSession s);
        void onExit(KaliSession s, int code);
    }

    public final int id;
    private final TermEmulator emu = new TermEmulator(80, 24);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService writer;
    private final AtomicBoolean redrawPending = new AtomicBoolean(false);

    private volatile Listener listener;
    private volatile Process proc;
    private volatile OutputStream procIn;
    private volatile RandomAccessFile fifo;
    private volatile File fifoFile;
    private volatile RandomAccessFile cmdFifo;
    private volatile File cmdFifoFile;
    private volatile Context appContext;
    private volatile boolean started;
    private final java.util.concurrent.atomic.AtomicBoolean feederHeld = new java.util.concurrent.atomic.AtomicBoolean(false);

    public KaliSession(int id) {
        this.id = id;
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "kali-writer-" + id);
            t.setDaemon(true);
            return t;
        });
        emu.setResponder(this::write);
    }

    public TermEmulator emulator() { return emu; }
    public void trimScrollback(int keep) { emu.trimScrollback(keep); }
    public boolean isConsole() { return id == 0; }
    public boolean isAlive() { Process p = proc; return p != null && p.isAlive(); }
    public boolean isEnded() { return started && !isAlive(); }
    private volatile String customName = "";

    /** The shown name: the one the user gave it, else "Kali N". The console keeps its fixed name. */
    public String title() {
        if (isConsole()) return "Console";
        String n = customName;
        return n.isEmpty() ? "Kali " + id : n;
    }

    /** Renames the session; an empty name goes back to "Kali N". Names live as long as the session. */
    public void setName(String name) {
        if (isConsole()) return;
        String n = name == null ? "" : name.replaceAll("\\p{Cntrl}", " ").trim();
        if (n.length() > 32) n = n.substring(0, 32).trim();
        customName = n;
    }

    public boolean isRenamed() { return !customName.isEmpty(); }
    public void setListener(Listener l) { listener = l; }

    // ------------------------------------------------------------------ logging into the terminal

    public void log(String s) {
        emu.append(s.replace("\r\n", "\n").replace("\n", "\r\n"));
        scheduleRedraw();
    }

    private void scheduleRedraw() {
        if (redrawPending.compareAndSet(false, true)) {
            main.postDelayed(() -> {
                redrawPending.set(false);
                Listener l = listener;
                if (l != null) l.onScreenChanged(this);
            }, 16);
        }
    }

    // ------------------------------------------------------------------ input / resize

    public void write(byte[] data) {
        final OutputStream out = procIn;
        if (out == null) return;
        writer.execute(() -> {
            try { out.write(data); out.flush(); } catch (Exception ignored) { }
        });
    }

    public void resize(int cols, int rows) {
        emu.resize(cols, rows);
        final RandomAccessFile f = fifo;
        if (f != null) {
            final byte[] msg = (rows + " " + cols + "\n").getBytes(StandardCharsets.US_ASCII);
            writer.execute(() -> {
                try { synchronized (f) { f.write(msg); } } catch (Exception ignored) { }
            });
        }
        scheduleRedraw();
    }

    // ------------------------------------------------------------------ lifecycle

    /** Returns null on success, otherwise a human-readable error. */
    public synchronized String start(Context ctx, File rootfs, int cols, int rows) {
        if (isConsole()) return "The console tab cannot run a shell.";
        if (isAlive()) return null;
        started = true;

        String runtimeProblem = ProotLauncher.runtimeError(ctx);
        if (runtimeProblem != null) return runtimeProblem;
        appContext = ctx.getApplicationContext();

        try {
            prepareGuest(ctx, rootfs);
            List<String> fakeBinds = new ArrayList<>();
            if (Prefs.fakeProc(ctx)) {
                ProcFeeder.get().acquire(ctx);               // live stat / loadavg / uptime while this session runs
                feederHeld.set(true);
                fakeBinds = prepareFakeProc(ctx);
            }

            String fifoName = ".ns-winsize-" + id;
            fifoFile = new File(rootfs, "tmp/" + fifoName);
            Files.deleteIfExists(fifoFile.toPath());
            Os.mkfifo(fifoFile.getAbsolutePath(), 0600);

            emu.resize(cols, rows);

            String cmdFifoName = ".ns-cmd-" + id;
            cmdFifoFile = new File(rootfs, "tmp/" + cmdFifoName);
            Files.deleteIfExists(cmdFifoFile.toPath());
            Os.mkfifo(cmdFifoFile.getAbsolutePath(), 0600);

            String shell = Prefs.shell(ctx);
            // Fake /proc copies must come after the /proc bind (ProotLauncher adds them there).
            List<String> command = ProotLauncher.command(ctx, rootfs, fakeBinds, Prefs.workdir(ctx));
            List<String> env = new ArrayList<>();
            env.add("SHELL=" + shell);
            env.add("TERM=xterm-256color");
            env.add("COLORTERM=truecolor");
            env.add("NS_ROWS=" + rows);
            env.add("NS_COLS=" + cols);
            env.add("NS_FIFO=/tmp/" + fifoName);
            env.add("NS_CMD_FIFO=/tmp/" + cmdFifoName);
            env.add("NS_SESSION=" + id);
            env.add("NS_SHELL=" + shell);
            env.add("NS_INIT=" + Prefs.startupCmd(ctx));
            env.add("NMAP_UNPRIVILEGED=1");      // apps get no raw sockets or netlink routes: nmap must use plain TCP connects
            if (Prefs.editorEnv(ctx)) { env.add("EDITOR=ns-edit -w"); env.add("VISUAL=ns-edit -w"); }
            List<String> argv = new ArrayList<>();
            argv.add("/usr/local/bin/ns-session");
            command.addAll(ProotLauncher.guestCommand(ctx, argv, env));

            ProcessBuilder pb = new ProcessBuilder(command);
            ProotLauncher.applyEnvironment(pb, ctx);
            pb.redirectErrorStream(true);

            // O_RDWR on a FIFO never blocks and keeps the pipe alive for the whole session,
            // so resize messages are buffered even before the guest watcher opens it.
            fifo = new RandomAccessFile(fifoFile, "rw");
            cmdFifo = new RandomAccessFile(cmdFifoFile, "rw");
            startCommandReader(cmdFifo);

            final Process p = pb.start();
            proc = p;
            procIn = p.getOutputStream();
            log("[+] Starting Kali through PRoot…\n");

            Thread reader = new Thread(() -> pump(p), "kali-output-" + id);
            reader.setDaemon(true);
            reader.start();
            Thread waiter = new Thread(() -> await(p), "kali-waiter-" + id);
            waiter.setDaemon(true);
            waiter.start();
            return null;
        } catch (Exception e) {
            cleanupFifo();
            releaseFeeder();
            proc = null;
            procIn = null;
            return "Start failed: " + describe(e);
        }
    }

    public void stop() {
        Process p = proc;
        if (p == null) return;
        proc = null;
        procIn = null;
        p.destroyForcibly();   // --kill-on-exit makes PRoot take the guest processes with it
        cleanupFifo();
        log("\n[+] Session stopped.\n");
    }

    private void pump(Process p) {
        byte[] buf = new byte[16 * 1024];
        try (InputStream in = p.getInputStream()) {
            int n;
            while ((n = in.read(buf)) != -1) {
                emu.process(buf, 0, n);
                scheduleRedraw();
            }
        } catch (Exception ignored) { }
    }

    private void await(Process p) {
        int code = -1;
        try { code = p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        final int exit = code;
        releaseFeeder();
        if (proc == p) {            // exited on its own (not through stop())
            proc = null;
            procIn = null;
            cleanupFifo();
            log("\n[i] Session ended (exit " + exit + ").\n");
        }
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onExit(this, exit);
        });
    }

    private void releaseFeeder() {
        if (feederHeld.compareAndSet(true, false)) ProcFeeder.get().release();
    }

    private void cleanupFifo() {
        RandomAccessFile f = fifo;
        fifo = null;
        if (f != null) try { f.close(); } catch (Exception ignored) { }
        File ff = fifoFile;
        fifoFile = null;
        if (ff != null) try { Files.deleteIfExists(ff.toPath()); } catch (Exception ignored) { }

        RandomAccessFile cf = cmdFifo;
        cmdFifo = null;
        if (cf != null) try { cf.close(); } catch (Exception ignored) { }
        File cff = cmdFifoFile;
        cmdFifoFile = null;
        if (cff != null) try { Files.deleteIfExists(cff.toPath()); } catch (Exception ignored) { }
    }

    /**
     * Requests from inside Kali (the ns-edit command). One line per request: {@code edit<TAB>token<TAB>path}.
     * The path is validated against the rootfs before anything opens.
     */
    private void startCommandReader(final RandomAccessFile f) {
        Thread t = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f.getFD()), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] parts = line.split("\t", 3);
                    if (parts.length == 3 && parts[0].equals("edit")) {
                        final String token = parts[1], path = parts[2];
                        final Context c = appContext;
                        if (c != null) main.post(() -> EditorActivity.launch(c, path, "-".equals(token) ? null : token));
                    } else if (parts.length >= 1 && parts[0].equals("edit-home")) {
                        final Context c = appContext;
                        if (c != null) main.post(() -> EditorActivity.launch(c, null, null));
                    }
                }
            } catch (Exception ignored) { /* fifo closed with the session */ }
        }, "kali-cmd-" + id);
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ guest preparation

    /**
     * Shared by all sessions. Files are only rewritten when their content changes, because other
     * sessions may be executing these very scripts.
     */
    static synchronized void prepareGuest(Context ctx, File rootfs) throws Exception {
        File tmp = new File(rootfs, "tmp");
        tmp.mkdirs();
        tmp.setReadable(true, false); tmp.setWritable(true, false); tmp.setExecutable(true, false);
        new File(rootfs, "root").mkdirs();
        File bin = new File(rootfs, "usr/local/bin");
        bin.mkdirs();

        // DNS: the rootfs usually ships a dangling systemd-resolved symlink.
        StringBuilder ns = new StringBuilder();
        for (String s : Prefs.dns(ctx).split("[,\\s]+")) if (!s.isEmpty()) ns.append("nameserver ").append(s).append('\n');
        File resolv = new File(rootfs, "etc/resolv.conf");
        if (Files.isSymbolicLink(resolv.toPath())) Files.deleteIfExists(resolv.toPath());
        writeIfChanged(resolv, ns.toString(), false);
        File hosts = new File(rootfs, "etc/hosts");
        if (!hosts.isFile()) writeIfChanged(hosts, "127.0.0.1 localhost\n::1 localhost\n", false);

        // systemd/dbus packages expect a valid machine-id; their own setup tools fail under PRoot.
        File machineId = new File(rootfs, "etc/machine-id");
        if (!machineId.isFile() || machineId.length() < 32) {
            Files.deleteIfExists(machineId.toPath());
            writeIfChanged(machineId, UUID.randomUUID().toString().replace("-", "") + "\n", false);
        }

        // dpkg wrapper: neutralises postinst scripts of packages that cannot work without an
        // init system / real kernel (systemd...). The package list is editable by the user.
        File skip = new File(rootfs, "etc/ns-skip-postinst");
        File legacySkip = new File(rootfs, "etc/nh-skip-postinst");   // written before the app was renamed
        if (!skip.isFile() && legacySkip.isFile()) Files.move(legacySkip.toPath(), skip.toPath());
        if (!skip.isFile()) writeIfChanged(skip, NS_SKIP_DEFAULT, false);
        writeIfChanged(new File(bin, "ns-dpkg"), NS_DPKG, true);
        File aptConf = new File(rootfs, "etc/apt/apt.conf.d");
        aptConf.mkdirs();
        writeIfChanged(new File(aptConf, "99nullshell"),
                "// Generated by NullShell\n" +
                "Dir::Bin::dpkg \"/usr/local/bin/ns-dpkg\";\n" +
                "APT::Sandbox::User \"root\";\n", false);

        writeIfChanged(new File(bin, "ns-session"), NS_SESSION, true);
        writeIfChanged(new File(bin, "ns-shell"), NS_SHELL, true);
        writeIfChanged(new File(bin, "ns-winwatch"), NS_WINWATCH, true);
        writeIfChanged(new File(bin, "ns-edit"), NS_EDIT, true);

        // Remove helper files from before the rename. The new apt config is already in place, so apt
        // never points at a missing wrapper.
        for (String legacy : new String[]{"usr/local/bin/nh-session", "usr/local/bin/nh-shell",
                "usr/local/bin/nh-winwatch", "usr/local/bin/nh-dpkg", "etc/apt/apt.conf.d/99nh-proot", "etc/nh-skip-postinst"}) {
            Files.deleteIfExists(new File(rootfs, legacy).toPath());
        }
    }

    private static void writeIfChanged(File f, String content, boolean exec) throws Exception {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        if (f.isFile() && !Files.isSymbolicLink(f.toPath())) {
            try {
                if (java.util.Arrays.equals(Files.readAllBytes(f.toPath()), data)) {
                    f.setReadable(true, false);
                    if (exec) f.setExecutable(true, false);
                    return;
                }
            } catch (Exception ignored) { }
        }
        try (OutputStream o = new FileOutputStream(f)) { o.write(data); }
        f.setReadable(true, false);
        if (exec) f.setExecutable(true, false);
    }

    private static final String NS_SESSION =
            "#!/bin/bash\n" +
            "# Generated by NullShell - runs inside PRoot.\n" +
            "/usr/local/bin/ns-dpkg --ns-stub-only 2>/dev/null\n" +
            "if command -v script >/dev/null 2>&1; then\n" +
            "  start=$SECONDS\n" +
            "  script -qefc /usr/local/bin/ns-shell /dev/null\n" +
            "  rc=$?\n" +
            "  if [ \"$rc\" -eq 0 ] || [ $((SECONDS - start)) -ge 2 ]; then exit \"$rc\"; fi\n" +
            "  echo '[!] Could not allocate a PTY; falling back to a plain shell.' >&2\n" +
            "else\n" +
            "  echo '[!] script(1) not found (package bsdutils); running without a PTY.' >&2\n" +
            "fi\n" +
            "exec /bin/bash --login\n";

    private static final String NS_SHELL =
            "#!/bin/bash\n" +
            "# Generated by NullShell - runs on the PTY created by script(1).\n" +
            "TTY=$(tty 2>/dev/null)\n" +
            "if [ -n \"$NS_ROWS\" ] && [ -n \"$NS_COLS\" ]; then stty rows \"$NS_ROWS\" cols \"$NS_COLS\" 2>/dev/null; fi\n" +
            "if [ -p \"$NS_FIFO\" ] && [ -n \"$TTY\" ] && command -v setsid >/dev/null 2>&1; then\n" +
            "  setsid /usr/local/bin/ns-winwatch \"$TTY\" \"$NS_FIFO\" </dev/null >/dev/null 2>&1 &\n" +
            "fi\n" +
            "if [ -n \"$NS_INIT\" ]; then ( eval \"$NS_INIT\" ); fi\n" +
            "SH=\"${NS_SHELL:-/bin/bash}\"\n" +
            "[ -x \"$SH\" ] || { echo \"[!] $SH not found, using /bin/bash\" >&2; SH=/bin/bash; }\n" +
            "unset NS_ROWS NS_COLS NS_FIFO NS_INIT NS_SHELL\n" +
            "exec \"$SH\" -l\n";

    private static final String NS_WINWATCH =
            "#!/bin/bash\n" +
            "# Applies window-size changes sent by the app through a FIFO.\n" +
            "TTY=\"$1\"; FIFO=\"$2\"\n" +
            "while read -r r c < \"$FIFO\"; do\n" +
            "  if [ -n \"$r\" ] && [ -n \"$c\" ]; then stty -F \"$TTY\" rows \"$r\" cols \"$c\" 2>/dev/null; fi\n" +
            "done\n";

    private static final String NS_EDIT =
            "#!/bin/bash\n" +
            "# ns-edit [-w] FILE...   open files in the NullShell editor (-w: wait until the file is closed there)\n" +
            "wait=0\n" +
            "if [ \"$1\" = \"-w\" ] || [ \"$1\" = \"--wait\" ]; then wait=1; shift; fi\n" +
            "if [ ! -p \"$NS_CMD_FIFO\" ]; then echo 'ns-edit: only works inside a NullShell terminal tab' >&2; exit 1; fi\n" +
            "if [ $# -eq 0 ]; then printf 'edit-home\\n' > \"$NS_CMD_FIFO\"; exit 0; fi\n" +
            "for f in \"$@\"; do\n" +
            "  p=$(realpath -m -- \"$f\") || exit 1\n" +
            "  token=-\n" +
            "  if [ $wait -eq 1 ]; then token=\"/tmp/.ns-wait.$$.$RANDOM\"; : > \"$token\"; fi\n" +
            "  printf 'edit\\t%s\\t%s\\n' \"$token\" \"$p\" > \"$NS_CMD_FIFO\"\n" +
            "  if [ $wait -eq 1 ]; then while [ -e \"$token\" ]; do sleep 0.3; done; fi\n" +
            "done\n";

    private static final String NS_SKIP_DEFAULT =
            "# Packages whose postinst scripts are replaced by a no-op stub (one per line).\n" +
            "# They cannot run under PRoot (no init system, no machine-id setup, no udev).\n" +
            "# Originals are kept as <name>.postinst.ns-orig in /var/lib/dpkg/info.\n" +
            "systemd\n" +
            "systemd-sysv\n" +
            "systemd-resolved\n" +
            "systemd-timesyncd\n" +
            "udev\n";

    private static final String NS_DPKG =
            "#!/bin/bash\n" +
            "# NS-WRAPPER: apt calls this instead of /usr/bin/dpkg (see /etc/apt/apt.conf.d/99nullshell).\n" +
            "INFO=/var/lib/dpkg/info\n" +
            "LIST=/etc/ns-skip-postinst\n" +
            "if [ -r \"$LIST\" ]; then\n" +
            "  while read -r pkg _; do\n" +
            "    case \"$pkg\" in ''|'#'*) continue;; esac\n" +
            "    for f in \"$INFO/$pkg.postinst\" \"$INFO/$pkg:arm64.postinst\"; do\n" +
            "      [ -f \"$f\" ] || continue\n" +
            "      grep -q 'N[HS]-STUB' \"$f\" 2>/dev/null && continue\n" +
            "      [ -e \"$f.ns-orig\" ] || cp -p \"$f\" \"$f.ns-orig\"\n" +
            "      printf '#!/bin/sh\\n# NS-STUB: skipped under PRoot\\nexit 0\\n' > \"$f\"\n" +
            "      chmod 755 \"$f\"\n" +
            "    done\n" +
            "  done < \"$LIST\"\n" +
            "fi\n" +
            "[ \"$1\" = \"--ns-stub-only\" ] && exit 0\n" +
            "exec /usr/bin/dpkg \"$@\"\n";

    /**
     * Android hides several /proc files from apps (SELinux), which breaks top, htop, uptime, vmstat, ps...
     * stat, loadavg and uptime are live (see {@link ProcFeeder}); the rest are static stand-ins.
     * All of them are bound over the real paths.
     */
    private static synchronized List<String> prepareFakeProc(Context ctx) throws Exception {
        File dir = new File(ctx.getFilesDir(), "fakeproc");
        dir.mkdirs();

        String vmstat =
                "nr_free_pages 100000\nnr_inactive_anon 0\nnr_active_anon 0\nnr_inactive_file 0\n" +
                "nr_active_file 0\npgpgin 0\npgpgout 0\npswpin 0\npswpout 0\npgfault 0\npgmajfault 0\n";

        String[][] files = {
                {"version", "Linux version 6.1.0 (nullshell@android) (gcc) #1 SMP PREEMPT\n", "/proc/version"},
                {"vmstat", vmstat, "/proc/vmstat"},
                {"cap_last_cap", "40\n", "/proc/sys/kernel/cap_last_cap"},
        };
        List<String> binds = new ArrayList<>();
        for (String[] f : files) {
            File host = new File(dir, f[0]);
            writeIfChanged(host, f[1], false);
            binds.add(host.getAbsolutePath() + ":" + f[2]);
        }
        binds.add(ProcFeeder.get().file("stat").getAbsolutePath() + ":/proc/stat");
        binds.add(ProcFeeder.get().file("loadavg").getAbsolutePath() + ":/proc/loadavg");
        binds.add(ProcFeeder.get().file("uptime").getAbsolutePath() + ":/proc/uptime");
        return binds;
    }

    private static String describe(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }
}
