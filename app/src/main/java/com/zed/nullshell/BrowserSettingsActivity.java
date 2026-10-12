package com.zed.nullshell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.List;

/** Proxy, private CA certificates and general browser options. Settings apply the next time a page loads. */
public class BrowserSettingsActivity extends Activity {
    private static final int REQ_CERT = 41;
    private static final String DEFAULT_KALI_CA = "/root/.mitmproxy/mitmproxy-ca-cert.pem";

    private SharedPreferences sp;
    private LinearLayout content, card;
    private LinearLayout certList;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = Prefs.sp(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        TextView back = Ui.iconButton(this, "\u2190");
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = new TextView(this);
        title.setText("Browser");
        title.setTextColor(Ui.TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(Ui.dp(this, 8), 0, 0, 0);
        bar.addView(title);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 32));
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        Ui.applyInsets(root);

        buildProxy();
        buildCerts();
        buildGeneral();
    }

    // ------------------------------------------------------------------ layout helpers

    private void section(String name, String hint) {
        TextView h = new TextView(this);
        h.setText(name);
        h.setTextColor(Ui.ACCENT);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h.setPadding(Ui.dp(this, 4), Ui.dp(this, 14), 0, Ui.dp(this, 6));
        content.addView(h);
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.rounded(this, Ui.SURFACE, 14));
        content.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (hint != null) {
            TextView t = new TextView(this);
            t.setText(hint);
            t.setTextColor(Ui.DIM);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            t.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 4));
            card.addView(t);
        }
    }

    private LinearLayout rowBase(String label, TextView valueView) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        row.setMinimumHeight(Ui.dp(this, 52));
        row.setBackground(Ui.ripple(this, 0, 14));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Ui.TEXT);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (valueView != null) row.addView(valueView);
        card.addView(row);
        return row;
    }

    private TextView valueText(String s) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextColor(Ui.DIM);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setSingleLine(true);
        v.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.setMaxWidth(Ui.dp(this, 190));
        v.setGravity(Gravity.END);
        return v;
    }

    private void switchRow(String label, final String key, boolean def, final Runnable after) {
        Switch sw = new Switch(this);
        Ui.tint(sw);
        sw.setChecked(sp.getBoolean(key, def));
        sw.setOnCheckedChangeListener((v, on) -> { sp.edit().putBoolean(key, on).apply(); if (after != null) after.run(); });
        LinearLayout row = rowBase(label, null);
        row.addView(sw);
        row.setClickable(true);
        row.setOnClickListener(v -> sw.toggle());
    }

    /** A tappable row that edits one string preference. */
    private void textRow(String label, final String key, final String def, final boolean secret, final boolean numeric, String shown) {
        final TextView value = valueText(shown);
        LinearLayout row = rowBase(label, value);
        final String lbl = label;
        row.setClickable(true);
        row.setOnClickListener(v -> {
            String cur = numeric ? String.valueOf(sp.getInt(key, Integer.parseInt(def))) : sp.getString(key, def);
            final EditText input = new EditText(this);
            input.setSingleLine(true);
            input.setTextColor(Ui.TEXT);
            input.setText(cur);
            input.setSelection(input.getText().length());
            if (numeric) input.setInputType(InputType.TYPE_CLASS_NUMBER);
            else if (secret) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            else input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            Ui.tint(input);
            LinearLayout box = new LinearLayout(this);
            box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
            box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            new Dlg(this).setTitle(lbl).setView(box)
                    .setPositiveButton("Save", (d, w) -> {
                        String s = input.getText().toString().trim();
                        if (numeric) {
                            int n;
                            try { n = Integer.parseInt(s); } catch (NumberFormatException e) { n = -1; }
                            if (n < 1 || n > 65535) { Toast.makeText(this, "Enter a port from 1 to 65535", Toast.LENGTH_SHORT).show(); return; }
                            sp.edit().putInt(key, n).apply();
                            value.setText(String.valueOf(n));
                        } else {
                            sp.edit().putString(key, s).apply();
                            value.setText(display(s, secret));
                        }
                    }).setNegativeButton("Cancel", null).show();
        });
    }

    private static String liveText(int n) { return "Tabs kept loaded  \u00B7  " + n + (n == 1 ? " (others reload when opened)" : ""); }

    private static String display(String s, boolean secret) {
        if (s == null || s.isEmpty()) return "not set";
        return secret ? "\u2022\u2022\u2022\u2022\u2022\u2022" : s;
    }

    private void actionRow(String label, int color, final Runnable action) {
        LinearLayout row = rowBase(label, null);
        ((TextView) row.getChildAt(0)).setTextColor(color);
        row.setClickable(true);
        row.setOnClickListener(v -> action.run());
    }

    private void divider() {
        View d = new View(this);
        d.setBackgroundColor(Ui.LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(Ui.dp(this, 16), 0, 0, 0);
        card.addView(d, lp);
    }

    // ------------------------------------------------------------------ proxy

    private void buildProxy() {
        String hint = BrowserProxy.supported()
                ? "All browser traffic goes through this proxy, with no direct fallback. It applies to every tab, not one tab."
                : "This device's WebView has no proxy support. Update Android System WebView to use a proxy.";
        section("Proxy", hint);
        switchRow("Use proxy", BrowserPrefs.PROXY_ON, false, null);
        divider();

        final TextView scheme = valueText(BrowserPrefs.SCHEME_NAMES[schemeIndex()]);
        LinearLayout schemeRow = rowBase("Type", scheme);
        schemeRow.setClickable(true);
        schemeRow.setOnClickListener(v -> new Dlg(this).setTitle("Proxy type")
                .setSingleChoiceItems(BrowserPrefs.SCHEME_NAMES, schemeIndex(), (d, which) -> {
                    sp.edit().putString(BrowserPrefs.PROXY_SCHEME, BrowserPrefs.SCHEMES[which]).apply();
                    scheme.setText(BrowserPrefs.SCHEME_NAMES[which]);
                    d.dismiss();
                }).show());
        divider();
        textRow("Host", BrowserPrefs.PROXY_HOST, "127.0.0.1", false, false, BrowserPrefs.proxyHost(this));
        divider();
        textRow("Port", BrowserPrefs.PROXY_PORT, "8080", false, true, String.valueOf(BrowserPrefs.proxyPort(this)));
        divider();
        textRow("Username", BrowserPrefs.PROXY_USER, "", false, false, display(BrowserPrefs.proxyUser(this), false));
        divider();
        textRow("Password", BrowserPrefs.PROXY_PASS, "", true, false, display(BrowserPrefs.proxyPass(this), true));
        divider();
        textRow("Bypass list", BrowserPrefs.PROXY_BYPASS, "", false, false, display(BrowserPrefs.proxyBypass(this), false));
        divider();
        switchRow("Also proxy localhost (experimental)", BrowserPrefs.PROXY_LOOPBACK, false, null);
        TextView note = new TextView(this);
        note.setText("Bypass list: hosts separated by commas, for example *.example.com, 10.0.0.0/8. "
                + "Your Kali tools are reachable on 127.0.0.1, so mitmproxy, Caido or Burp running there work with the defaults. "
                + "The username and password are kept in this app's private storage.");
        note.setTextColor(Ui.DIM);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 12));
        card.addView(note);
    }

    private int schemeIndex() {
        String s = BrowserPrefs.proxyScheme(this);
        for (int i = 0; i < BrowserPrefs.SCHEMES.length; i++) if (BrowserPrefs.SCHEMES[i].equals(s)) return i;
        return 0;
    }

    // ------------------------------------------------------------------ certificates

    private void buildCerts() {
        section("CA certificates",
                "Imported only for this browser. They are stored privately in NullShell and never added to the Android trust store. "
                        + "A site is accepted only when its certificate was issued by one of these CAs and matches the address.");
        certList = new LinearLayout(this);
        certList.setOrientation(LinearLayout.VERTICAL);
        card.addView(certList);
        divider();
        actionRow("Import from file\u2026", Ui.TEXT, this::pickCertFile);
        divider();
        actionRow("Import from Kali path\u2026", Ui.TEXT, this::askKaliPath);
        refreshCerts();
    }

    private void refreshCerts() {
        certList.removeAllViews();
        final List<BrowserCerts.Entry> entries = BrowserCerts.list(this);
        if (entries.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("No CA imported yet");
            none.setTextColor(Ui.DIM);
            none.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            none.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
            certList.addView(none);
            return;
        }
        DateFormat df = DateFormat.getDateInstance(DateFormat.MEDIUM);
        for (final BrowserCerts.Entry e : entries) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
            row.setClickable(true);
            row.setBackground(Ui.ripple(this, 0, 14));
            TextView n = new TextView(this);
            n.setText(e.name);
            n.setTextColor(Ui.TEXT);
            n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            row.addView(n);
            TextView s = new TextView(this);
            boolean expired = e.cert.getNotAfter().getTime() < System.currentTimeMillis();
            s.setText((expired ? "Expired " : "Valid until ") + df.format(e.cert.getNotAfter())
                    + (BrowserCerts.isCa(e.cert) ? "" : "  \u00B7  not a CA (server certificate)"));
            s.setTextColor(expired ? Ui.DANGER : Ui.DIM);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            row.addView(s);
            row.setOnClickListener(v -> showCert(e));
            certList.addView(row);
        }
    }

    private void showCert(final BrowserCerts.Entry e) {
        String msg = "Subject\n" + e.cert.getSubjectX500Principal().getName()
                + "\n\nIssuer\n" + e.cert.getIssuerX500Principal().getName()
                + "\n\nValid\n" + e.cert.getNotBefore() + "\nto " + e.cert.getNotAfter()
                + "\n\nSHA-256\n" + e.sha256;
        new Dlg(this).setTitle(e.name).setMessage(msg)
                .setPositiveButton("Close", null)
                .setNegativeButton("Remove", (d, w) -> { BrowserCerts.remove(e); refreshCerts(); })
                .show();
    }

    private void pickCertFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(i, REQ_CERT); }
        catch (Exception ex) { Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show(); }
    }

    private void askKaliPath() {
        if (!RootfsManager.isInstalled(this)) {
            Toast.makeText(this, "Kali is not installed", Toast.LENGTH_SHORT).show();
            return;
        }
        Ui.prompt(this, "Certificate path in Kali", "/root/...", DEFAULT_KALI_CA, "Import", path -> {
            File f = GuestPaths.toHost(RootfsManager.dir(this), path.trim(), true);
            if (f == null || !f.isFile()) { Toast.makeText(this, "File not found in Kali: " + path.trim(), Toast.LENGTH_LONG).show(); return; }
            try (InputStream in = new FileInputStream(f)) { report(BrowserCerts.importStream(this, in)); }
            catch (Exception ex) { Toast.makeText(this, "Import failed: " + ex.getMessage(), Toast.LENGTH_LONG).show(); }
        });
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CERT || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new java.io.IOException("Cannot open the file");
            report(BrowserCerts.importStream(this, in));
        } catch (Exception ex) {
            Toast.makeText(this, "Import failed: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void report(List<BrowserCerts.Entry> added) {
        refreshCerts();
        StringBuilder sb = new StringBuilder("Imported ");
        for (int i = 0; i < added.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(added.get(i).name);
        }
        boolean anyCa = false;
        for (BrowserCerts.Entry e : added) anyCa |= BrowserCerts.isCa(e.cert);
        if (!anyCa) sb.append("\nThis is not a CA certificate, so it only trusts that one server.");
        Toast.makeText(this, sb.toString(), Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ general

    private void buildGeneral() {
        section("General", null);
        textRow("Search engine", BrowserPrefs.SEARCH, BrowserPrefs.DEFAULT_SEARCH, false, false, BrowserPrefs.searchUrl(this));
        divider();
        final TextView liveLabel = new TextView(this);
        liveLabel.setText(liveText(BrowserPrefs.liveTabs(this)));
        liveLabel.setTextColor(Ui.TEXT);
        liveLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        liveLabel.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), 0);
        card.addView(liveLabel);
        SeekBar live = new SeekBar(this);
        Ui.tint(live);
        live.setMax(5);
        live.setProgress(BrowserPrefs.liveTabs(this) - 1);
        live.setPadding(Ui.dp(this, 28), Ui.dp(this, 6), Ui.dp(this, 28), Ui.dp(this, 10));
        live.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                liveLabel.setText(liveText(p + 1));
                sp.edit().putInt(BrowserPrefs.LIVE, p + 1).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        card.addView(live);
        divider();
        switchRow("Fullscreen video turns the screen to landscape", BrowserPrefs.FS_LANDSCAPE, true, null);
        divider();
        switchRow("JavaScript", BrowserPrefs.JS, true, null);
        divider();
        switchRow("Remote debugging (chrome://inspect)", BrowserPrefs.DEBUG, false, null);
        divider();
        actionRow("Clear history", Ui.TEXT, () -> { BrowserHistory.get(this).clear(); Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show(); });
        divider();
        actionRow("Clear cookies and site data", Ui.DANGER, () -> new Dlg(this)
                .setTitle("Clear cookies and site data?")
                .setMessage("You will be signed out of every site in the browser.")
                .setPositiveButton("Clear", (d, w) -> {
                    CookieManager.getInstance().removeAllCookies(null);
                    CookieManager.getInstance().flush();
                    WebStorage.getInstance().deleteAllData();
                    Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("Cancel", null).show());
        TextView note = new TextView(this);
        note.setText("Search engine: use %s where the query goes. Remote debugging lets a computer connected over adb inspect pages.");
        note.setTextColor(Ui.DIM);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 12));
        card.addView(note);
    }
}
