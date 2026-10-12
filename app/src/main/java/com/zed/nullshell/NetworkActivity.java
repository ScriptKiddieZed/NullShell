package com.zed.nullshell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This phone's addresses, the Wi-Fi gateway, and a scan of the devices on Wi-Fi and on the phone's own hotspot,
 * with a tap-to-copy on every address.
 */
public class NetworkActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private final List<ScanSection> scans = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        TextView back = Ui.iconButton(this, "←");
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = new TextView(this);
        title.setText("Network");
        title.setTextColor(Ui.TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(Ui.dp(this, 8), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView refresh = Ui.key(this, "Refresh");
        refresh.setOnClickListener(v -> build());
        bar.addView(refresh);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));

        ScrollView sv = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 24));
        sv.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Ui.applyInsets(root);
        build();
    }

    @Override protected void onDestroy() {
        for (ScanSection s : scans) s.stop();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ helpers

    private int dp(int v) { return Ui.dp(this, v); }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("address", text));
        Toast.makeText(this, "Copied " + text, Toast.LENGTH_SHORT).show();
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout card(String heading, String sub) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.rounded(this, Ui.SURFACE, 12));
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView h = text(heading, 15, Ui.TEXT);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        c.addView(h);
        if (sub != null) {
            TextView s = text(sub, 12, Ui.DIM);
            s.setPadding(0, dp(2), 0, dp(4));
            c.addView(s);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        content.addView(c, lp);
        return c;
    }

    /** One line: label on top, monospace value below, tap anywhere (or the Copy key) to copy the value. */
    private View addressRow(String label, final String value, String copyText) {
        final String toCopy = copyText == null ? value : copyText;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        if (label != null && !label.isEmpty()) left.addView(text(label, 12, Ui.DIM));
        TextView v = text(value, 16, Ui.TEXT);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextIsSelectable(false);
        left.addView(v);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView cp = Ui.key(this, "Copy");
        cp.setOnClickListener(x -> copy(toCopy));
        row.addView(cp);
        row.setOnClickListener(x -> copy(toCopy));
        return row;
    }

    // ------------------------------------------------------------------ screen

    private void build() {
        for (ScanSection s : scans) s.stop();
        scans.clear();
        content.removeAllViews();

        List<NetInfo.Iface> all = NetInfo.collect(this);

        LinearLayout me = card("This phone", "Tap an address to copy it");
        if (all.isEmpty()) me.addView(text("No network connection found.", 14, Ui.DIM));
        for (NetInfo.Iface f : all) {
            if (!f.ipv4.isEmpty()) me.addView(addressRow(f.label() + "  ·  " + f.name, f.ipv4, null));
            for (String v6 : f.ipv6) me.addView(addressRow(f.label() + "  ·  IPv6", v6, null));
        }

        NetInfo.Iface wifi = null;
        List<NetInfo.Iface> hotspots = new ArrayList<>();
        for (NetInfo.Iface f : all) {
            if (f.kind == NetInfo.WIFI && wifi == null) wifi = f;
            if (f.kind == NetInfo.HOTSPOT && !f.ipv4.isEmpty()) hotspots.add(f);
        }

        LinearLayout gw = card("Wi‑Fi router", wifi == null ? null : "Network " + wifi.cidr());
        if (wifi == null) {
            gw.addView(text("Not connected to Wi‑Fi.", 14, Ui.DIM));
        } else {
            if (!wifi.gateway.isEmpty()) gw.addView(addressRow("Gateway", wifi.gateway, null));
            else gw.addView(text("The gateway address was not reported.", 14, Ui.DIM));
            for (String d : wifi.dns) gw.addView(addressRow("DNS server", d, null));
        }

        if (wifi != null && wifi.scannable()) addScan("Devices on Wi‑Fi", wifi);
        else {
            LinearLayout c = card("Devices on Wi‑Fi", null);
            c.addView(text("Connect to a Wi‑Fi network to scan it.", 14, Ui.DIM));
        }

        if (hotspots.isEmpty()) {
            LinearLayout c = card("Devices on your hotspot", null);
            c.addView(text("The hotspot is off, or Android does not show it to apps. Turn on the hotspot (or USB tethering) and tap Refresh.", 14, Ui.DIM));
        } else {
            for (NetInfo.Iface h : hotspots) addScan("Devices on your " + h.label().toLowerCase(java.util.Locale.US), h);
        }

        TextView note = text("Devices that block every connection can stay hidden. Names come from reverse DNS and are often empty. "
                + "The scan only looks at the local /24 network.", 12, Ui.DIM);
        note.setPadding(dp(4), dp(14), dp(4), 0);
        content.addView(note);
    }

    private void addScan(String heading, NetInfo.Iface f) {
        LinearLayout c = card(heading, f.name + "  ·  " + f.cidr() + "  ·  this phone is " + f.ipv4);
        ScanSection s = new ScanSection(c, f);
        scans.add(s);
    }

    // ------------------------------------------------------------------ a scan card

    private final class ScanSection {
        private final NetInfo.Iface iface;
        private final LinearLayout card, list;
        private final TextView status, scanBtn, copyAll, nmapBtn;
        private final ProgressBar bar;
        private final List<LanScanner.Host> hosts = Collections.synchronizedList(new ArrayList<LanScanner.Host>());
        private LanScanner scanner;
        private boolean running;

        ScanSection(LinearLayout card, NetInfo.Iface iface) {
            this.card = card;
            this.iface = iface;

            LinearLayout buttons = new LinearLayout(this_());
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setGravity(Gravity.CENTER_VERTICAL);
            scanBtn = Ui.button(this_(), "Scan", Ui.PRIMARY);
            scanBtn.setOnClickListener(v -> toggle());
            buttons.addView(scanBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
            copyAll = Ui.key(this_(), "Copy all IPs");
            copyAll.setOnClickListener(v -> copyAll());
            LinearLayout.LayoutParams k = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            k.leftMargin = dp(8);
            buttons.addView(copyAll, k);
            nmapBtn = Ui.key(this_(), "nmap in Kali");
            nmapBtn.setOnClickListener(v -> MainActivity.runInTerminal(this_(), "nmap --unprivileged -sn -n -PS22,80,443,445,8080 " + iface.cidr()));
            nmapBtn.setVisibility(RootfsManager.isInstalled(this_()) ? View.VISIBLE : View.GONE);
            buttons.addView(nmapBtn, k);
            card.addView(buttons);

            bar = new ProgressBar(this_(), null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setVisibility(View.GONE);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(14));
            bp.topMargin = dp(8);
            card.addView(bar, bp);
            status = text("Tap Scan to look for devices.", 12, Ui.DIM);
            status.setPadding(0, dp(6), 0, dp(2));
            card.addView(status);
            list = new LinearLayout(this_());
            list.setOrientation(LinearLayout.VERTICAL);
            card.addView(list);
            copyAll.setVisibility(View.GONE);
        }

        private NetworkActivity this_() { return NetworkActivity.this; }

        void stop() {
            if (scanner != null) scanner.cancel();
        }

        private void toggle() {
            if (running) { stop(); status.setText("Stopping…"); return; }
            start();
        }

        private void start() {
            hosts.clear();
            list.removeAllViews();
            running = true;
            scanBtn.setText("Stop");
            copyAll.setVisibility(View.GONE);
            bar.setProgress(0);
            bar.setVisibility(View.VISIBLE);
            status.setText("Scanning " + iface.cidr() + "…");
            InetAddress local = null;
            NetworkInterface nif = null;
            try {
                local = InetAddress.getByName(iface.ipv4);
                nif = NetworkInterface.getByName(iface.name);
            } catch (Exception ignored) { }
            scanner = new LanScanner();
            final long[] lastProgress = {0};
            scanner.scan(LanScanner.subnetHosts(iface.ipv4, iface.prefix), iface.ipv4, iface.name, local, nif, new LanScanner.Listener() {
                @Override public void onHost(LanScanner.Host h) { ui.post(() -> addHost(h)); }
                @Override public void onUpdate(LanScanner.Host h) { ui.post(() -> updateHost(h)); }
                @Override public void onProgress(int done, int total) {
                    long now = System.currentTimeMillis();
                    if (now - lastProgress[0] < 100 && done < total) return;
                    lastProgress[0] = now;
                    ui.post(() -> bar.setProgress(total == 0 ? 1000 : done * 1000 / total));
                }
                @Override public void onDone(boolean cancelled) { ui.post(() -> finished(cancelled)); }
            });
        }

        private void finished(boolean cancelled) {
            running = false;
            scanBtn.setText("Scan again");
            bar.setVisibility(View.GONE);
            int n = hosts.size();
            status.setText((cancelled ? "Stopped. " : "Done. ") + n + (n == 1 ? " device found." : " devices found.")
                    + (n == 0 ? " If you expected some, check that both are on the same network." : ""));
            copyAll.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        }

        private final java.util.Map<String, TextView[]> rows = new java.util.HashMap<>();

        private void addHost(LanScanner.Host h) {
            if (isFinishing() || rows.containsKey(h.ip) && hosts.contains(h)) return;
            hosts.add(h);
            Collections.sort(hosts);
            LinearLayout row = new LinearLayout(this_());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));
            TextView ip = text(h.ip, 16, Ui.TEXT);
            ip.setTypeface(Typeface.MONOSPACE);
            TextView sub = text("", 12, Ui.DIM);
            row.addView(ip);
            row.addView(sub);
            row.setOnClickListener(v -> deviceMenu(h));
            rows.put(h.ip, new TextView[]{ip, sub});
            // keep the list sorted by address
            int index = hosts.indexOf(h);
            list.addView(row, Math.min(index, list.getChildCount()));
            describe(h);
        }

        private void updateHost(LanScanner.Host h) { if (rows.containsKey(h.ip)) describe(h); }

        private void describe(LanScanner.Host h) {
            TextView[] t = rows.get(h.ip);
            if (t == null) return;
            StringBuilder s = new StringBuilder();
            if (h.ip.equals(iface.gateway)) s.append("gateway");
            if (!h.name.isEmpty()) s.append(s.length() > 0 ? "  ·  " : "").append(h.name);
            if (!h.mac.isEmpty()) s.append(s.length() > 0 ? "  ·  " : "").append(h.mac);
            if (h.ms >= 0 && !h.fromArp) s.append(s.length() > 0 ? "  ·  " : "").append(h.ms).append(" ms");
            if (h.fromArp) s.append(s.length() > 0 ? "  ·  " : "").append("seen in ARP table");
            t[1].setText(s.length() == 0 ? "tap for options" : s.toString());
        }

        private void copyAll() {
            StringBuilder sb = new StringBuilder();
            synchronized (hosts) { for (LanScanner.Host h : hosts) sb.append(h.ip).append('\n'); }
            copy(sb.toString().trim());
        }

        private void deviceMenu(final LanScanner.Host h) {
            List<String> items = new ArrayList<>();
            items.add("Copy IP address");
            if (!h.name.isEmpty()) items.add("Copy IP and name");
            items.add("Open http://" + h.ip + " in the browser");
            final boolean kali = RootfsManager.isInstalled(NetworkActivity.this);
            if (kali) { items.add("Ping in the terminal"); items.add("nmap this device in the terminal"); }
            new Dlg(NetworkActivity.this).setTitle(h.ip + (h.name.isEmpty() ? "" : "  " + h.name))
                    .setItems(items.toArray(new String[0]), (d, which) -> {
                        String it = items.get(which);
                        if (it.equals("Copy IP address")) copy(h.ip);
                        else if (it.equals("Copy IP and name")) copy(h.ip + "  " + h.name);
                        else if (it.startsWith("Open http")) BrowserActivity.open(NetworkActivity.this, "http://" + h.ip);
                        else if (it.startsWith("Ping")) MainActivity.runInTerminal(NetworkActivity.this, "ping -c 4 " + h.ip);
                        else MainActivity.runInTerminal(NetworkActivity.this, "nmap --unprivileged -Pn -sT -sV -T4 " + h.ip);
                    }).show();
        }
    }
}
