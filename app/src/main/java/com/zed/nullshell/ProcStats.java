package com.zed.nullshell;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Android hides /proc/stat, /proc/loadavg and /proc/uptime from apps. This class rebuilds them from
 * what an app may still read: /proc/PID/stat of its own processes. The numbers describe this app's
 * process tree (the Kali shells and everything they run), not the whole phone, expressed the way
 * the kernel does: CPU time as a share of all cores since boot, run-queue length as a load average.
 *
 * Output text has a constant length (lines are padded with trailing spaces) so the bound files can
 * be overwritten in place while tools such as top keep them open.
 *
 * Pure Java on purpose: the environment (clock, cpu count, process ownership) is injected so the
 * class can be tested on a desktop JVM against the real /proc.
 */
public final class ProcStats {
    public interface Env {
        long elapsedMs();                 // time since boot, including suspend
        long wallMs();                    // wall clock
        int cpus();
        boolean isMine(File pidDir);      // process belongs to this app's uid
    }

    private static final int HZ = 100;    // USER_HZ
    private static final int CPU_LINE = 192, OTHER_LINE = 96;

    private final File procRoot;
    private final Env env;
    private final int cpus;

    private final Map<Integer, long[]> last = new HashMap<>();   // pid -> {utime, stime}
    private boolean baselined;
    private long lastSampleWall;

    private long busyUser, busySys;       // accumulated clock ticks, never decreases
    private int running, blocked, total, maxPid;
    private long processesSeen = 3000;
    private double load1, load5, load15;

    public ProcStats(File procRoot, Env env) {
        this.procRoot = procRoot;
        this.env = env;
        this.cpus = Math.max(1, env.cpus());
    }

    public int cpus() { return cpus; }

    /** Reads every process of this app once and folds the result into the counters. */
    public synchronized void sample() {
        long now = env.wallMs();
        File[] dirs = procRoot.listFiles();
        Map<Integer, long[]> seen = new HashMap<>();
        int run = 0, blk = 0, count = 0, top = 0;
        if (dirs != null) for (File d : dirs) {
            int pid = parsePid(d.getName());
            if (pid < 0) continue;
            long[] prev = last.get(pid);
            boolean known = prev != null;
            if (!known && !env.isMine(d)) continue;
            String stat;
            try { stat = new String(Files.readAllBytes(new File(d, "stat").toPath()), StandardCharsets.US_ASCII); }
            catch (IOException | RuntimeException e) { continue; }      // exited meanwhile
            int close = stat.lastIndexOf(')');
            if (close < 0) continue;
            String[] f = stat.substring(close + 2).split(" ");
            if (f.length < 13) continue;
            long ut, st;
            try { ut = Long.parseLong(f[11]); st = Long.parseLong(f[12]); } catch (NumberFormatException e) { continue; }

            count++;
            if (pid > top) top = pid;
            char state = f[0].isEmpty() ? '?' : f[0].charAt(0);
            if (state == 'R') run++;
            else if (state == 'D') blk++;

            if (baselined) {
                long du, ds;
                if (known) { du = Math.max(0, ut - prev[0]); ds = Math.max(0, st - prev[1]); }
                else { du = ut; ds = st; processesSeen++; }                // born since the last sample
                busyUser += du;
                busySys += ds;
            }
            seen.put(pid, new long[]{ut, st});
        }
        last.clear();
        last.putAll(seen);

        running = run; blocked = blk; total = count; maxPid = top;
        double dt = lastSampleWall == 0 ? 1.0 : Math.max(0.05, Math.min(30, (now - lastSampleWall) / 1000.0));
        lastSampleWall = now;
        double busyNow = run + blk;
        load1 += (busyNow - load1) * (1 - Math.exp(-dt / 60.0));
        load5 += (busyNow - load5) * (1 - Math.exp(-dt / 300.0));
        load15 += (busyNow - load15) * (1 - Math.exp(-dt / 900.0));
        baselined = true;
    }

    private static int parsePid(String name) {
        if (name.isEmpty() || name.length() > 9) return -1;
        for (int i = 0; i < name.length(); i++) if (name.charAt(i) < '0' || name.charAt(i) > '9') return -1;
        return Integer.parseInt(name);
    }

    // ------------------------------------------------------------------ text

    public synchronized String statText() {
        long perCpuTicks = env.elapsedMs() / (1000 / HZ);
        long allTicks = perCpuTicks * cpus;
        long user = busyUser, sys = busySys;
        long idle = Math.max(0, allTicks - user - sys);

        StringBuilder out = new StringBuilder();
        out.append(line("cpu  " + user + " 0 " + sys + " " + idle + " 0 0 0 0 0 0", CPU_LINE));
        for (int i = 0; i < cpus; i++) {
            long u = user / cpus + (i == 0 ? user % cpus : 0);
            long s = sys / cpus + (i == 0 ? sys % cpus : 0);
            long id = Math.max(0, perCpuTicks - u - s);
            out.append(line("cpu" + i + " " + u + " 0 " + s + " " + id + " 0 0 0 0 0 0", CPU_LINE));
        }
        long btime = env.wallMs() / 1000 - env.elapsedMs() / 1000;
        out.append(line("intr 0", OTHER_LINE));
        out.append(line("ctxt " + (1000000 + (user + sys) * 200), OTHER_LINE));
        out.append(line("btime " + btime, OTHER_LINE));
        out.append(line("processes " + processesSeen, OTHER_LINE));
        out.append(line("procs_running " + Math.max(1, running), OTHER_LINE));
        out.append(line("procs_blocked " + blocked, OTHER_LINE));
        out.append(line("softirq 0 0 0 0 0 0 0 0 0 0 0", OTHER_LINE));
        return out.toString();
    }

    public synchronized String loadavgText() {
        String s = String.format(Locale.US, "%.2f %.2f %.2f %d/%d %d",
                load1, load5, load15, Math.max(1, running), Math.max(1, total), Math.max(1000, maxPid));
        return line(s, OTHER_LINE);
    }

    public synchronized String uptimeText() {
        double up = env.elapsedMs() / 1000.0;
        double idle = Math.max(0, up * cpus - (busyUser + busySys) / (double) HZ);
        return line(String.format(Locale.US, "%.2f %.2f", up, idle), OTHER_LINE);
    }

    /** Pads with trailing spaces so the file length never changes between updates. */
    private static String line(String s, int width) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < width) b.append(' ');
        return b.append('\n').toString();
    }

    // exposed for tests
    synchronized long busyTicks() { return busyUser + busySys; }
    synchronized Iterator<Integer> pids() { return last.keySet().iterator(); }
}
