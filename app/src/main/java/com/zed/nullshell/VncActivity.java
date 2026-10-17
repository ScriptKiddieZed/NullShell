package com.zed.nullshell;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;


/**
 * Start and stop the Kali VNC server (an IceWM desktop) and see how to connect to it from a VNC viewer app.
 * The server itself is run by {@link VncManager}; this screen only shows its state, refreshed once a second, so it
 * can be left and reopened while the server keeps running.
 */
public class VncActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView statusTitle, statusDetail;
    private TextView installBtn, startBtn, stopBtn, restartBtn, uninstallBtn, logBtn;
    private LinearLayout connectCard;
    private TextView addressValue, passwordValue, lanValue;
    private boolean showPassword;
    private boolean restartPending;

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
        title.setText("VNC server");
        title.setTextColor(Ui.TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(Ui.dp(this, 8), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView settings = Ui.iconButton(this, "⚙");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_CATEGORY, "vnc")));
        bar.addView(settings);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 14);
        content.setPadding(p, Ui.dp(this, 4), p, Ui.dp(this, 32));
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- status card
        LinearLayout status = card();
        statusTitle = new TextView(this);
        statusTitle.setTextColor(Ui.TEXT);
        statusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        statusTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        status.addView(statusTitle);
        statusDetail = new TextView(this);
        statusDetail.setTextColor(Ui.DIM);
        statusDetail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusDetail.setPadding(0, Ui.dp(this, 4), 0, 0);
        status.addView(statusDetail);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, Ui.dp(this, 12), 0, 0);
        installBtn = rowButton(buttons, "Install", Ui.PRIMARY);
        installBtn.setOnClickListener(v -> confirmInstall());
        startBtn = rowButton(buttons, "Start", Ui.PRIMARY);
        startBtn.setOnClickListener(v -> startServer());
        stopBtn = rowButton(buttons, "Stop", Ui.SECONDARY);
        stopBtn.setOnClickListener(v -> { restartPending = false; VncManager.stop(this); refresh(); });
        restartBtn = rowButton(buttons, "Restart", Ui.SECONDARY);
        restartBtn.setOnClickListener(v -> { restartPending = true; VncManager.stop(this); refresh(); });
        status.addView(buttons);

        logBtn = Ui.key(this, "View log");
        logBtn.setOnClickListener(v -> showLog());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 36));
        lp.topMargin = Ui.dp(this, 8);
        status.addView(logBtn, lp);
        content.addView(status, cardParams(0));

        // ---- connection card
        connectCard = card();
        connectCard.addView(heading("Connect from a VNC viewer app"));
        connectCard.addView(label("Address"));
        addressValue = value();
        addressValue.setOnClickListener(v -> copy("Address", VncManager.address(this)));
        connectCard.addView(addressValue);
        lanValue = new TextView(this);
        lanValue.setTextColor(Ui.TEXT);
        lanValue.setTypeface(Typeface.MONOSPACE);
        lanValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        lanValue.setPadding(0, Ui.dp(this, 2), 0, 0);
        connectCard.addView(lanValue);
        connectCard.addView(label("Password"));
        passwordValue = value();
        passwordValue.setOnClickListener(v -> { showPassword = !showPassword; refresh(); });
        connectCard.addView(passwordValue);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, Ui.dp(this, 12), 0, 0);
        TextView copyAddr = rowButton(actions, "Copy address", Ui.SECONDARY);
        copyAddr.setOnClickListener(v -> copy("Address", VncManager.address(this)));
        TextView copyPass = rowButton(actions, "Copy password", Ui.SECONDARY);
        copyPass.setOnClickListener(v -> copy("Password", Prefs.vncPassword(this)));
        connectCard.addView(actions);
        TextView open = Ui.button(this, "Open in a VNC viewer app", Ui.PRIMARY);
        open.setOnClickListener(v -> openViewer());
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        op.topMargin = Ui.dp(this, 8);
        connectCard.addView(open, op);
        TextView help = new TextView(this);
        help.setTextColor(Ui.DIM);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        help.setPadding(0, Ui.dp(this, 10), 0, 0);
        help.setText("Install any VNC viewer app (for example bVNC or RealVNC Viewer), add a connection with the address "
                + "and password above, and connect. Tap the address or password to copy it. Resolution, colour depth, "
                + "password and network access are in Settings > VNC server.");
        connectCard.addView(help);
        content.addView(connectCard, cardParams(Ui.dp(this, 12)));

        // ---- uninstall
        uninstallBtn = Ui.key(this, "Uninstall VNC server and IceWM");
        uninstallBtn.setOnClickListener(v -> confirmUninstall());
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 36));
        up.topMargin = Ui.dp(this, 14);
        content.addView(uninstallBtn, up);

        setContentView(root);
        Ui.applyInsets(root);
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        lanStamp = 0;      // settings may have changed while away
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (restartPending && !VncManager.isBusy()) { restartPending = false; VncManager.start(VncActivity.this); }
            refresh();
            ui.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ small view helpers

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.rounded(this, Ui.SURFACE, 14));
        int p = Ui.dp(this, 14);
        c.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 14));
        return c;
    }

    private LinearLayout.LayoutParams cardParams(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private TextView heading(String s) {
        TextView t = new TextView(this);
        t.setText(s.toUpperCase(java.util.Locale.US));
        t.setTextColor(Ui.ACCENT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setLetterSpacing(0.1f);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, 0, 0, Ui.dp(this, 4));
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Ui.DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
        return t;
    }

    private TextView value() {
        TextView t = new TextView(this);
        t.setTextColor(Ui.TEXT);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        return t;
    }

    /** A button that shares a horizontal row equally with its siblings. */
    private TextView rowButton(LinearLayout row, String label, int style) {
        TextView b = Ui.button(this, label, style);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = Ui.dp(this, 6);
        row.addView(b, lp);
        return b;
    }

    private void copy(String what, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(what, text));
        Toast.makeText(this, what + " copied", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ state

    private static void show(View v, boolean on) { v.setVisibility(on ? View.VISIBLE : View.GONE); }

    private void refresh() {
        boolean kali = RootfsManager.isInstalled(this);
        boolean installed = VncManager.isInstalled(this);
        String extras = kali && installed ? VncManager.extrasMissing(this) : "";
        boolean running = VncManager.isRunning();
        boolean ready = VncManager.isReady();
        boolean busy = VncManager.isBusy();
        String reason = VncManager.lastReason();

        statusDetail.setOnClickListener(null);
        if (!kali) {
            statusTitle.setText("Kali is not installed");
            statusDetail.setText("Install Kali from the main menu first, then come back here.");
        } else if (!installed && !busy) {
            String miss = VncManager.missing(this);
            statusTitle.setText("Not installed");
            statusDetail.setText((VncManager.anyInstalled(this) ? "Still needed: " + miss + ". " : "")
                    + "Install adds IceWM and a VNC server inside Kali. It is a real download and uses storage.");
        } else if (ready) {
            statusTitle.setText("Running");
            statusDetail.setText(Prefs.vncWidth(this) + "×" + Prefs.vncHeight(this) + "  ·  " + Prefs.vncDepth(this)
                    + "-bit  ·  display :" + Prefs.vncDisplay(this)
                    + (Prefs.vncLocalOnly(this) ? "  ·  this phone only" : "  ·  network access on"));
        } else if (running) {
            statusTitle.setText("Starting…");
            statusDetail.setText("This usually takes a few seconds. Use Stop if it seems stuck, then View log.");
        } else if (busy) {
            statusTitle.setText(restartPending ? "Restarting…" : "Stopping…");
            statusDetail.setText("");
        } else if (reason != null) {
            statusTitle.setText("Stopped");
            statusDetail.setText(reason + "\n\nTap here or View log for details.");
            statusDetail.setOnClickListener(v -> showLog());
        } else {
            statusTitle.setText("Stopped");
            statusDetail.setText("Ready. Tap Start, then connect with a VNC viewer app."
                    + (extras.isEmpty() ? "" : "\n\nFile managers (Thunar, PCManFM) also need: " + extras
                    + ". Tap Install extras to add them."));
        }
        show(statusDetail, statusDetail.getText().length() > 0);

        installBtn.setText(installed ? "Install extras" : "Install");
        show(installBtn, kali && (!installed || !extras.isEmpty()) && !busy && !running);
        show(startBtn, installed && !busy);
        show(stopBtn, running);
        show(restartBtn, ready);
        show(logBtn, !VncManager.lastLog().isEmpty());
        show(uninstallBtn, kali && VncManager.anyInstalled(this) && !running);

        show(connectCard, kali && installed);
        addressValue.setText(VncManager.address(this));
        passwordValue.setText(showPassword ? Prefs.vncPassword(this) : "••••••••   (tap to show)");
        long now = System.currentTimeMillis();
        if (now - lanStamp > 5000) {          // reading the network state every second would be wasteful
            lanStamp = now;
            StringBuilder sb = new StringBuilder();
            for (String s : VncManager.lanAddresses(this)) sb.append(sb.length() > 0 ? "\n" : "").append(s);
            lanValue.setText(sb.toString());
            show(lanValue, sb.length() > 0);
        }
    }

    private long lanStamp;

    // ------------------------------------------------------------------ actions

    private void startServer() {
        restartPending = false;
        VncManager.start(this);
        refresh();
    }

    private void showLog() {
        final String log = VncManager.lastLog();
        String reason = VncManager.lastReason();
        new Dlg(this).setTitle("VNC server log")
                .setMessage((reason == null ? "" : reason + "\n\n") + (log.isEmpty() ? "(no output yet)" : log))
                .setPositiveButton("Copy", (d, w) -> copy("Log", (reason == null ? "" : reason + "\n\n") + log))
                .setNegativeButton("Close", null).show();
    }

    private void openViewer() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("vnc://" + VncManager.address(this))));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No VNC viewer app found. Install one, then add the address and password above.", Toast.LENGTH_LONG).show();
        }
    }

    private void confirmInstall() {
        new Dlg(this).setTitle("Install VNC server and IceWM?")
                .setMessage("This runs in a terminal tab:\n\n" + VncManager.installCommand()
                        + "\n\nIt is a real download. When it finishes, come back here and tap Start.")
                .setPositiveButton("Install", (d, w) -> MainActivity.runInTerminal(this, VncManager.installCommand()))
                .setNegativeButton("Cancel", null).show();
    }

    private void confirmUninstall() {
        new Dlg(this).setTitle("Uninstall VNC server and IceWM?")
                .setMessage("This stops the server if it is running, then removes IceWM and the VNC server from Kali and "
                        + "deletes ~/.vnc. It runs in a terminal tab:\n\n" + VncManager.uninstallCommand())
                .setPositiveButton("Uninstall", (d, w) -> {
                    restartPending = false;
                    VncManager.stop(this);
                    MainActivity.runInTerminal(this, VncManager.uninstallCommand());
                })
                .setNegativeButton("Cancel", null).show();
    }
}
