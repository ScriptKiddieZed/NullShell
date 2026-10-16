package com.zed.nullshell;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String EXTRA_RUN = "run";

    /** Opens the terminal screen and types a command into a fresh session (used by the editor to install language servers). */
    static void runInTerminal(Context c, String command) {
        Intent i = new Intent(c, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra(EXTRA_RUN, command);
        c.startActivity(i);
    }

    private static final String EXTRA_PASTE = "paste";

    /**
     * Brings the terminal forward and puts text on its command line WITHOUT pressing Enter, so it can be checked first.
     * Several lines go in as one bracketed paste, so the shell does not run them as they arrive.
     */
    static void pasteInTerminal(Context c, String text) {
        Intent i = new Intent(c, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra(EXTRA_PASTE, text);
        c.startActivity(i);
    }

    private static boolean autoStarted;      // once per process, not per Activity instance
    private static String lastError;         // shown on the empty screen after a failed install

    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3})%");

    private final SessionManager manager = SessionManager.get();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean opRunning;

    // commands drawer
    private DrawerPanel drawer;
    private FrameLayout drawerSlot;
    private View scrim;
    private boolean drawerOpen;
    private CommandRunner runner;
    private boolean macroRunning;
    private LinearLayout keysRow;

    private TerminalView term;
    // sessions drawer (left)
    private SessionsPanel sessionsPanel;
    private FrameLayout sessionsSlot;
    private boolean sessionsOpen;
    private TextView titleView, subView;

    private LinearLayout empty, busyStrip;
    private HorizontalScrollView keysScroll;
    private View keysDivider;
    private TextView busyText, btnMenu;
    private ProgressBar busyBar;
    private TextView ctrlKey, altKey;
    private TextView emptyTitle, emptySub, emptyError, emptyPrimary, emptySecondary;

    /** Progress sink for long rootfs operations: thin progress strip + console tab. */
    private final RootfsManager.Progress progress = new RootfsManager.Progress() {
        @Override public void status(String s) { main.post(() -> showBusy(s)); }
        @Override public void log(String s) { manager.console().log(s); }
    };

    private final Runnable busyPoll = new Runnable() {
        @Override public void run() {
            refreshUi();
            if (busy()) main.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        if (FilePick.handle(req, res, data)) return;
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        TermEmulator.setDefaultScrollback(Prefs.termScrollback(this));
        setContentView(R.layout.activity_main);
        Ui.recolor(findViewById(R.id.root));
        Ui.applyInsets(findViewById(R.id.root));

        term = findViewById(R.id.terminal);
        titleView = findViewById(R.id.session_title);
        subView = findViewById(R.id.session_sub);
        titleView.setTextColor(Ui.TEXT);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        subView.setTextColor(Ui.DIM);
        View titleBox = findViewById(R.id.title_box);
        titleBox.setBackground(Ui.ripple(this, 0, 10));
        titleBox.setOnClickListener(v -> { if (sessionsOpen) closeSessions(); else openSessions(); });
        titleBox.setOnLongClickListener(v -> {
            KaliSession a = manager.active();
            if (a.isConsole()) return false;
            renameSession(a);
            return true;
        });

        TextView btnSessions = Ui.iconButton(this, "\u2630");
        btnSessions.setOnClickListener(v -> { if (sessionsOpen) closeSessions(); else openSessions(); });
        ((ViewGroup) findViewById(R.id.btn_sessions_slot)).addView(btnSessions);
        empty = findViewById(R.id.empty);
        busyStrip = findViewById(R.id.busy);
        busyText = findViewById(R.id.busy_text);
        busyBar = findViewById(R.id.busy_bar);
        keysScroll = findViewById(R.id.keys_scroll);
        keysDivider = findViewById(R.id.keys_divider);

        busyBar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        busyBar.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        busyBar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.LINE));

        TextView btnCmds = Ui.iconButton(this, "{}");
        btnCmds.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        btnCmds.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        btnCmds.setOnClickListener(v -> { if (drawerOpen) closeDrawer(); else openDrawer(); });
        ((ViewGroup) findViewById(R.id.btn_cmds_slot)).addView(btnCmds);

        TextView btnNew = Ui.iconButton(this, "+");
        btnNew.setOnClickListener(v -> startKali());
        ((ViewGroup) findViewById(R.id.btn_new_slot)).addView(btnNew);
        btnMenu = Ui.iconButton(this, "\u22EE");
        btnMenu.setOnClickListener(v -> showMenu());
        ((ViewGroup) findViewById(R.id.btn_menu_slot)).addView(btnMenu);

        SnippetStore.get().init(this);
        keysRow = findViewById(R.id.extrakeys);
        buildExtraKeys();
        buildEmptyState();
        buildDrawer();
        buildSessionsDrawer();

        term.setModifierListener((ctrl, alt) -> main.post(() -> {
            Ui.setKeyActive(this, ctrlKey, ctrl);
            Ui.setKeyActive(this, altKey, alt);
        }));
        manager.setCallback(new SessionManager.Callback() {
            @Override public void onTabsChanged() { refreshUi(); }
            @Override public void onScreen(KaliSession s) { if (s == term.getSession()) term.invalidate(); }
            @Override public void onExit(KaliSession s, int code) { refreshUi(); }
        });

        term.attach(manager.active());
        refreshUi();

        final String pendingPaste = getIntent().getStringExtra(EXTRA_PASTE);
        final String pendingRun = getIntent().getStringExtra(EXTRA_RUN);
        if (pendingPaste != null) {
            getIntent().removeExtra(EXTRA_PASTE);
            autoStarted = true;
            runAfterLayout(() -> pasteToTerminal(pendingPaste));
        } else if (pendingRun != null) {
            getIntent().removeExtra(EXTRA_RUN);
            autoStarted = true;
            runAfterLayout(() -> runInNewSession(pendingRun));
        } else if (!autoStarted) {
            autoStarted = true;
            if (Prefs.autoStart(this) && RootfsManager.isInstalled(this)
                    && manager.aliveCount() == 0 && manager.all().size() == 1) {
                runAfterLayout(this::startKali);   // needs the real terminal size
            }
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String paste = intent.getStringExtra(EXTRA_PASTE);
        if (paste != null) {
            intent.removeExtra(EXTRA_PASTE);
            runAfterLayout(() -> pasteToTerminal(paste));
            return;
        }
        String run = intent.getStringExtra(EXTRA_RUN);
        if (run != null) {
            intent.removeExtra(EXTRA_RUN);
            runAfterLayout(() -> runInNewSession(run));
        }
    }

    /** Types text into the current shell (starting one if needed) without pressing Enter. */
    private void pasteToTerminal(String text) {
        KaliSession cur = manager.active();
        if (cur.isConsole() || !cur.isAlive()) {
            startKali();
            cur = manager.active();
            if (cur.isConsole() || !cur.isAlive()) return;
        }
        final KaliSession s = cur;
        String t = text.replace("\r\n", "\n").replaceAll("\\s+$", "");
        final byte[] bytes = (t.indexOf('\n') >= 0 ? "\u001b[200~" + t + "\u001b[201~" : t).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        main.postDelayed(() -> { if (s.isAlive()) s.write(bytes); }, 400);
    }

    /** Starts a session and types the command; bytes typed before the shell is ready wait in the terminal's input queue. */
    private void runInNewSession(String command) {
        startKali();
        final KaliSession s = manager.active();
        if (s.isConsole() || !s.isAlive()) return;
        main.postDelayed(() -> { if (s.isAlive()) s.write((command + "\r").getBytes(java.nio.charset.StandardCharsets.UTF_8)); }, 400);
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // history is only dropped when the system is about to reclaim the app
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE) SessionManager.get().trimScrollback(500);
    }

    @Override protected void onResume() {
        super.onResume();
        TermEmulator.setDefaultScrollback(Prefs.termScrollback(this));
        android.graphics.Typeface[] face = TerminalFonts.current(this);
        term.setFont(face == null ? null : face[0], face == null ? null : face[1]);
        term.applySettings(Prefs.fontSizeSp(this), Prefs.schemeFg(this), Prefs.schemeBg(this));
        if (Prefs.keepScreenOn(this)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int vis = Prefs.extraKeys(this) ? View.VISIBLE : View.GONE;
        keysScroll.setVisibility(vis);
        keysDivider.setVisibility(vis);
        buildExtraKeys();
        if (drawerOpen) drawer.refresh();
        if (sessionsOpen) sessionsPanel.refresh(manager.all(), manager.active());
        main.removeCallbacks(busyPoll);
        busyPoll.run();
    }

    @Override public void onBackPressed() {
        if (drawerOpen) closeDrawer();
        else if (sessionsOpen) closeSessions();
        else super.onBackPressed();
    }

    @Override protected void onPause() {
        main.removeCallbacks(busyPoll);
        super.onPause();
    }

    @Override protected void onDestroy() {
        // Sessions live on in SessionManager; the foreground service keeps the process alive.
        manager.setCallback(null);
        super.onDestroy();
    }

    private void runAfterLayout(Runnable r) {
        if (term.getWidth() > 0) { term.post(r); return; }
        term.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (term.getWidth() <= 0) return;
                term.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                term.post(r);
            }
        });
    }

    // ------------------------------------------------------------------ UI state

    private boolean busy() { return opRunning || RootfsManager.isBusy(); }

    private void refreshUi() {
        KaliSession active = manager.active();
        if (term.getSession() != active) term.attach(active);
        updateTitle(active);
        if (sessionsOpen) sessionsPanel.refresh(manager.all(), active);

        boolean busy = busy();
        busyStrip.setVisibility(busy || macroRunning ? View.VISIBLE : View.GONE);

        boolean installed = RootfsManager.isInstalled(this);
        boolean idle = installed && manager.aliveCount() == 0;
        boolean showEmpty = !busy && active.isConsole() && (!installed || idle);
        empty.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        if (!showEmpty) return;

        if (!installed) {
            emptyTitle.setText("Kali Linux");
            emptySub.setText("A rootless Kali terminal.\nDownload the rootfs to get started, or import one you exported earlier.");
            emptyPrimary.setText("Install Kali");
            emptyPrimary.setOnClickListener(v -> confirmInstall());
            emptySecondary.setText("Import rootfs");
            emptySecondary.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
            emptySecondary.setVisibility(View.VISIBLE);
        } else {
            emptyTitle.setText("No session running");
            emptySub.setText("Open a terminal inside Kali.");
            emptyPrimary.setText("New session");
            emptyPrimary.setOnClickListener(v -> startKali());
            emptySecondary.setVisibility(View.GONE);
        }
        emptyError.setText(lastError == null ? "" : lastError);
        emptyError.setVisibility(lastError == null ? View.GONE : View.VISIBLE);
    }

    private void showBusy(String s) {
        busyStrip.setVisibility(View.VISIBLE);
        busyText.setText(s);
        Matcher m = PERCENT.matcher(s);
        if (m.find()) {
            busyBar.setIndeterminate(false);
            busyBar.setProgress(Math.min(100, Integer.parseInt(m.group(1))));
        } else {
            busyBar.setIndeterminate(true);
        }
    }

    // ------------------------------------------------------------------ sessions

    /** The top bar shows the name and state of the selected session. */
    private void updateTitle(KaliSession active) {
        titleView.setText(active.title());
        int n = 0;
        for (KaliSession k : manager.all()) if (!k.isConsole()) n++;
        String state = SessionsPanel.state(active);
        subView.setText(n > 1 ? state + " \u00B7 " + n + " sessions" : state);
    }

    private void renameSession(KaliSession s) {
        if (s.isConsole()) return;
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32)});
        input.setHint("Kali " + s.id);
        input.setText(s.isRenamed() ? s.title() : "");
        input.setSelection(input.getText().length());
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.DIM);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        FrameLayout box = new FrameLayout(this);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);
        box.addView(input, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog.Builder d = new Dlg(this).setTitle("Rename session");
        d.setView(box);
        d.setPositiveButton("Rename", (x, w) -> { s.setName(input.getText().toString()); refreshUi(); manager.notifyService(); });
        if (s.isRenamed()) d.setNeutralButton("Reset", (x, w) -> { s.setName(""); refreshUi(); manager.notifyService(); });
        d.setNegativeButton("Cancel", null);
        AlertDialog shown = d.show();
        input.setOnEditorActionListener((v, id, e) -> {
            s.setName(input.getText().toString());
            refreshUi();
            manager.notifyService();
            shown.dismiss();
            return true;
        });
        input.requestFocus();
        if (shown.getWindow() != null) shown.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }

    private void closeTab(KaliSession s) {
        if (!s.isAlive()) { manager.close(s); return; }
        new Dlg(this)
                .setTitle("Close " + s.title() + "?")
                .setMessage("Running processes in this session will be killed.")
                .setPositiveButton("Close", (d, w) -> manager.close(s))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------ menu & actions

    private void showMenu() {
        KaliSession active = manager.active();
        boolean installed = RootfsManager.isInstalled(this);
        TPopup pm = new TPopup(this, btnMenu);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "New session");
        m.add(0, 2, 1, "Stop session").setEnabled(!active.isConsole() && active.isAlive());
        if (!active.isConsole()) m.add(0, 3, 2, "Show console");
        m.add(0, 6, 3, "Editor").setEnabled(installed);
        m.add(0, 7, 4, "Browser");
        m.add(0, 9, 5, "Files");
        m.add(0, 11, 6, "AI chat");
        m.add(0, 10, 7, "Network");
        m.add(0, 12, 8, "VNC server");
        m.add(0, 8, 9, "Memory");
        m.add(0, 4, 10, installed ? "Reinstall Kali" : "Install Kali");
        m.add(0, 5, 11, "Settings");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: startKali(); return true;
                case 2: stopActive(); return true;
                case 3: manager.select(manager.console()); return true;
                case 4: confirmInstall(); return true;
                case 5: startActivity(new Intent(this, SettingsActivity.class)); return true;
                case 6: startActivity(new Intent(this, EditorActivity.class)); return true;
                case 7: BrowserActivity.open(this, null); return true;
                case 9: FileManagerActivity.open(this, null); return true;
                case 10: startActivity(new Intent(this, NetworkActivity.class)); return true;
                case 12: startActivity(new Intent(this, VncActivity.class)); return true;
                case 11: startActivity(new Intent(this, ChatActivity.class)); return true;
                case 8: MemoryReport.show(this, "Trim scrollback", () -> SessionManager.get().trimScrollback(500)); return true;
                default: return false;
            }
        });
        pm.show();
    }

    private void confirmInstall() {
        if (busy()) { toConsole("[!] Another rootfs operation is still running.\n"); return; }
        if (!RootfsManager.isInstalled(this)) { installKali(); return; }
        new Dlg(this)
                .setTitle("Reinstall Kali?")
                .setMessage("This downloads a fresh rootfs and replaces the current one, including everything you added.\n\nTip: export it first in Settings.")
                .setPositiveButton("Reinstall", (d, w) -> installKali())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void installKali() {
        lastError = null;
        manager.stopAll();
        manager.select(manager.console());
        opRunning = true;
        showBusy("Preparing\u2026");
        refreshUi();
        new Thread(() -> {
            boolean ok = false;
            try {
                RootfsManager.install(this, progress);
                ok = true;
            } catch (Exception e) {
                lastError = "Install failed: " + RootfsManager.message(e);
                progress.log("[!] " + lastError + "\n");
            }
            final boolean success = ok;
            main.post(() -> {
                opRunning = false;
                refreshUi();
                if (success && RootfsManager.isInstalled(this)) startKali();
            });
        }, "kali-installer").start();
    }

    private void startKali() {
        if (busy()) { toConsole("[!] A rootfs operation is still running. Wait for it to finish.\n"); return; }
        if (!RootfsManager.isInstalled(this)) { manager.select(manager.console()); refreshUi(); return; }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        KaliSession s = manager.create();            // also selects it and attaches the view
        if (term.getSession() != s) term.attach(s);
        String error = s.start(this, RootfsManager.dir(this), term.getCols(), term.getRows());
        if (error != null) {
            s.log("[!] " + error + "\n");
            refreshUi();
            return;
        }
        KaliService.start(this);
        refreshUi();
        term.requestFocus();
        term.showKeyboard();
    }

    private void stopActive() {
        KaliSession s = manager.active();
        if (s.isConsole() || !s.isAlive()) return;
        s.stop();
        manager.notifyService();
        refreshUi();
    }

    private void toConsole(String text) {
        manager.console().log(text);
        manager.select(manager.console());
    }

    // ------------------------------------------------------------------ empty state & extra keys

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void buildEmptyState() {
        TextView glyph = text(">_", 40, Ui.ACCENT);
        glyph.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        empty.addView(glyph);

        emptyTitle = text("", 22, Ui.TEXT);
        emptyTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 14);
        empty.addView(emptyTitle, lp);

        emptySub = text("", 14, Ui.DIM);
        emptySub.setLineSpacing(0, 1.15f);
        lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 8);
        empty.addView(emptySub, lp);

        emptyError = text("", 13, Ui.DANGER);
        lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 14);
        empty.addView(emptyError, lp);

        emptyPrimary = Ui.button(this, "", Ui.PRIMARY);
        lp = new LinearLayout.LayoutParams(Ui.dp(this, 240), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 26);
        empty.addView(emptyPrimary, lp);

        emptySecondary = Ui.button(this, "", Ui.SECONDARY);
        lp = new LinearLayout.LayoutParams(Ui.dp(this, 240), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        empty.addView(emptySecondary, lp);
    }

    private void buildExtraKeys() {
        LinearLayout row = keysRow;
        row.removeAllViews();
        ctrlKey = addKey(row, "CTRL", v -> term.toggleCtrl());
        altKey = addKey(row, "ALT", v -> term.toggleAlt());
        Ui.setKeyActive(this, ctrlKey, term.isCtrlSticky());
        Ui.setKeyActive(this, altKey, term.isAltSticky());
        addKey(row, "ESC", v -> term.handleKey(KeyEvent.KEYCODE_ESCAPE, null));
        addKey(row, "TAB", v -> term.handleKey(KeyEvent.KEYCODE_TAB, null));
        addKey(row, "\u2190", v -> term.handleKey(KeyEvent.KEYCODE_DPAD_LEFT, null));
        addKey(row, "\u2193", v -> term.handleKey(KeyEvent.KEYCODE_DPAD_DOWN, null));
        addKey(row, "\u2191", v -> term.handleKey(KeyEvent.KEYCODE_DPAD_UP, null));
        addKey(row, "\u2192", v -> term.handleKey(KeyEvent.KEYCODE_DPAD_RIGHT, null));
        addKey(row, "HOME", v -> term.handleKey(KeyEvent.KEYCODE_MOVE_HOME, null));
        addKey(row, "END", v -> term.handleKey(KeyEvent.KEYCODE_MOVE_END, null));
        addKey(row, "PGUP", v -> term.handleKey(KeyEvent.KEYCODE_PAGE_UP, null));
        addKey(row, "PGDN", v -> term.handleKey(KeyEvent.KEYCODE_PAGE_DOWN, null));
        addKey(row, "-", v -> term.typeText("-"));
        addKey(row, "/", v -> term.typeText("/"));
        addKey(row, "|", v -> term.typeText("|"));
        addKey(row, "~", v -> term.typeText("~"));
        addKey(row, "PASTE", v -> term.paste());
        addKey(row, "KB", v -> term.toggleKeyboard());

        List<Snippet> pinned = SnippetStore.get().pinned();
        if (!pinned.isEmpty()) {
            View sep = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 1), Ui.dp(this, 22));
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
            sep.setBackgroundColor(Ui.LINE);
            row.addView(sep, lp);
            for (Snippet sn : pinned) {
                TextView k = addKey(row, sn.name.isEmpty() ? "cmd" : sn.name, v -> useSnippet(sn));
                k.setTextColor(Ui.ACCENT);
            }
        }
    }

    private TextView addKey(LinearLayout row, String label, View.OnClickListener l) {
        TextView k = Ui.key(this, label);
        k.setOnClickListener(l);
        row.addView(k);
        return k;
    }

    // ------------------------------------------------------------------ commands drawer

    private void buildDrawer() {
        scrim = findViewById(R.id.scrim);
        drawerSlot = findViewById(R.id.drawer_slot);
        drawerSlot.setElevation(Ui.dp(this, 8));
        scrim.setOnClickListener(v -> { closeDrawer(); closeSessions(); });
        drawer = new DrawerPanel(this, new DrawerPanel.Host() {
            @Override public void onTap(Snippet s) { closeDrawer(); useSnippet(s); }
            @Override public void onInsert(Snippet s) {
                closeDrawer();
                if (s.template().hasFields()) useSnippet(s);          // the form has an Insert button
                else term.pasteText(KeyTokens.stripTokens(s.body));
            }
            @Override public void onEdit(Snippet s) { openEditor(s.id); }
            @Override public void onNew() { openEditor(null); }
            @Override public void onChanged() { buildExtraKeys(); }
        });
        drawerSlot.addView(drawer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void openEditor(String id) {
        Intent i = new Intent(this, SnippetEditorActivity.class);
        if (id != null) i.putExtra(SnippetEditorActivity.EXTRA_ID, id);
        startActivity(i);
    }

    private void buildSessionsDrawer() {
        sessionsSlot = findViewById(R.id.sessions_slot);
        sessionsSlot.setElevation(Ui.dp(this, 8));
        sessionsPanel = new SessionsPanel(this, new SessionsPanel.Host() {
            @Override public void onSelect(KaliSession s) { manager.select(s); closeSessions(); term.requestFocus(); }
            @Override public void onRename(KaliSession s) { renameSession(s); }
            @Override public void onClose(KaliSession s) { closeTab(s); }
            @Override public void onNew() { closeSessions(); startKali(); }
        });
        sessionsSlot.addView(sessionsPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void openSessions() {
        if (drawerOpen) {                                   // only one drawer at a time
            drawerOpen = false;
            drawerSlot.animate().cancel();
            drawerSlot.setVisibility(View.GONE);
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(term.getWindowToken(), 0);
        sessionsPanel.refresh(manager.all(), manager.active());
        sessionsOpen = true;
        showScrim();
        sessionsSlot.animate().cancel();
        sessionsSlot.setTranslationX(-Ui.dp(this, 280));
        sessionsSlot.setVisibility(View.VISIBLE);
        sessionsSlot.animate().translationX(0f).setDuration(180);
    }

    private void closeSessions() {
        if (!sessionsOpen) return;
        sessionsOpen = false;
        hideScrim();
        sessionsSlot.animate().translationX(-Ui.dp(this, 280)).setDuration(160)
                .withEndAction(() -> { if (!sessionsOpen) sessionsSlot.setVisibility(View.GONE); });
    }

    private void showScrim() {
        scrim.animate().cancel();
        if (scrim.getVisibility() != View.VISIBLE) scrim.setAlpha(0f);
        scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(150);
    }

    private void hideScrim() {
        scrim.animate().alpha(0f).setDuration(150)
                .withEndAction(() -> { if (!drawerOpen && !sessionsOpen) scrim.setVisibility(View.GONE); });
    }

    private void openDrawer() {
        if (sessionsOpen) {
            sessionsOpen = false;
            sessionsSlot.animate().cancel();
            sessionsSlot.setVisibility(View.GONE);
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(term.getWindowToken(), 0);
        drawer.clearSearch();
        drawer.refresh();
        drawerOpen = true;
        showScrim();
        drawerSlot.animate().cancel();
        drawerSlot.setTranslationX(Ui.dp(this, 300));
        drawerSlot.setVisibility(View.VISIBLE);
        drawerSlot.animate().translationX(0f).setDuration(180);
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        hideScrim();
        drawerSlot.animate().translationX(Ui.dp(this, 300)).setDuration(160)
                .withEndAction(() -> { if (!drawerOpen) drawerSlot.setVisibility(View.GONE); });
    }

    // ------------------------------------------------------------------ using a snippet

    private final RunDialog.Actions snippetActions = new RunDialog.Actions() {
        @Override public void run(String text, boolean enter) { runText(text, enter); }
        @Override public void insert(String text) { term.pasteText(KeyTokens.stripTokens(text)); }
        @Override public void copy(String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("command", KeyTokens.stripTokens(text)));
            Toast.makeText(MainActivity.this, "Copied", Toast.LENGTH_SHORT).show();
        }
    };

    private void useSnippet(Snippet s) {
        if (s.template().hasFields()) {
            RunDialog.show(this, s, builtins(), snippetActions);
        } else if (s.confirm) {
            new Dlg(this).setTitle("Run this command?").setMessage(s.body)
                    .setPositiveButton("Run", (d, w) -> runText(s.body, s.enter))
                    .setNegativeButton("Cancel", null).show();
        } else {
            runText(s.body, s.enter);
        }
    }

    private Map<String, String> builtins() {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                if (t != null) m.put("clipboard", t.toString());
            }
        } catch (RuntimeException ignored) { }
        m.put("selection", term.getSelectionText());
        Date now = new Date();
        m.put("date", new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now));
        m.put("datetime", new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now));
        return m;
    }

    private void runText(String text, boolean enter) {
        KaliSession target = manager.active();
        if (target.isConsole() || !target.isAlive()) {
            Toast.makeText(this, "Open a running session first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (macroRunning && runner != null) runner.cancel();
        List<KeyTokens.Action> actions = KeyTokens.parse(text, enter);
        final CommandRunner r = new CommandRunner();
        runner = r;
        final boolean longRun = CommandRunner.hasPauses(actions);
        if (longRun) {
            macroRunning = true;
            showBusy("Running command\u2026 tap to cancel");
            busyStrip.setOnClickListener(v -> r.cancel());
        }
        r.start(target, actions, cancelled -> main.post(() -> {
            if (runner == r) {
                macroRunning = false;
                busyStrip.setOnClickListener(null);
                refreshUi();
            }
        }));
        term.requestFocus();
    }
}
