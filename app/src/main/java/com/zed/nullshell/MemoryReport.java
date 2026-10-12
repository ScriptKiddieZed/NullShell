package com.zed.nullshell;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where this app's RAM goes. Android's own per-app numbers miss the Kali processes (language servers,
 * shells, tools), so this adds up the resident memory of every process of this app's user id.
 * The WebView renderer runs under a different id and is not counted. Resident memory counts shared
 * libraries in every process that uses them, so the total reads a little high.
 */
final class MemoryReport {
    private MemoryReport() { }

    private static final class Entry { String name; int count; long kb; }

    static String build(Context c) {
        long page = 4096;
        try { page = Os.sysconf(OsConstants._SC_PAGESIZE); } catch (Throwable ignored) { }
        int me = android.os.Process.myPid();
        int myUid = android.os.Process.myUid();
        Map<String, Entry> byName = new LinkedHashMap<>();
        long total = 0;
        File[] dirs = new File("/proc").listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                String n = d.getName();
                if (n.isEmpty() || !Character.isDigit(n.charAt(0))) continue;
                try {
                    if (Os.stat(d.getAbsolutePath()).st_uid != myUid) continue;
                    String statm = new String(Files.readAllBytes(new File(d, "statm").toPath()), StandardCharsets.UTF_8).trim();
                    String[] f = statm.split("\\s+");
                    if (f.length < 2) continue;
                    long kb = Long.parseLong(f[1]) * page / 1024;
                    String name = Integer.parseInt(n) == me ? "NullShell app"
                            : new String(Files.readAllBytes(new File(d, "comm").toPath()), StandardCharsets.UTF_8).trim();
                    Entry e = byName.get(name);
                    if (e == null) { e = new Entry(); e.name = name; byName.put(name, e); }
                    e.count++;
                    e.kb += kb;
                    total += kb;
                } catch (Exception ignored) { /* the process ended while reading */ }
            }
        }
        List<Entry> list = new ArrayList<>(byName.values());
        Collections.sort(list, (a, b) -> Long.compare(b.kb, a.kb));

        StringBuilder sb = new StringBuilder();
        sb.append("App total  ~ ").append(size(total)).append("\n");
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            sb.append("Phone  ").append(size(mi.availMem / 1024)).append(" free of ").append(size(mi.totalMem / 1024));
            if (mi.lowMemory) sb.append("  (low)");
            sb.append("\n");
        }
        sb.append("\nLargest processes\n");
        for (int i = 0; i < list.size() && i < 8; i++) {
            Entry e = list.get(i);
            sb.append(String.format(Locale.US, "%-18s %s\n", e.name + (e.count > 1 ? " x" + e.count : ""), size(e.kb)));
        }
        sb.append("\nBrowser pages are rendered by the WebView process and are not included.");
        return sb.toString();
    }

    private static String size(long kb) {
        return kb >= 1024 * 1024 ? String.format(Locale.US, "%.1f GB", kb / 1048576.0)
                : String.format(Locale.US, "%d MB", kb / 1024);
    }

    /** Shows the report; {@code action} (optional) is offered as a button that frees what the screen can. */
    static void show(Activity a, String actionLabel, final Runnable action) {
        AlertDialog.Builder b = new Dlg(a).setTitle("Memory").setMessage(build(a))
                .setPositiveButton("Close", null);
        if (action != null) b.setNeutralButton(actionLabel, (d, w) -> action.run());
        AlertDialog dlg = b.show();
        android.widget.TextView msg = dlg.findViewById(android.R.id.message);
        if (msg != null) msg.setTypeface(android.graphics.Typeface.MONOSPACE);
    }
}
