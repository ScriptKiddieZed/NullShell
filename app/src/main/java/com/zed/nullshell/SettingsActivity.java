package com.zed.nullshell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SettingsActivity extends Activity {
    static final String EXTRA_CATEGORY = "category";
    private static final String[][] CATEGORIES = {
            {"appearance", "Appearance"},
            {"terminal", "Terminal"},
            {"sessions", "Sessions"},
            {"environment", "Environment"},
            {"rootfs", "Kali rootfs"},
            {"editor", "Editor"},
            {"browser", "Browser"},
            {"vnc", "VNC server"},
            {"commands", "Commands"},
            {"about", "About & reset"},
    };
    private static final int REQ_IMPORT = 1, REQ_EXPORT = 2, REQ_CMD_IMPORT = 3, REQ_CMD_EXPORT = 4;

    private interface Op { void run(RootfsManager.Progress p) throws Exception; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences sp;
    private LinearLayout content;   // vertical list of sections
    private LinearLayout card;      // card currently being filled
    private TextView rootfsInfo, opStatus, preview;
    private String category;                 // null = the category list
    private boolean pageMode;
    private TextView titleView;
    private final java.util.Map<String, TextView> summaries = new java.util.LinkedHashMap<>();
    private TextView importBtn, exportBtn, deleteBtn;

    private final RootfsManager.Progress progress = new RootfsManager.Progress() {
        @Override public void status(String s) { main.post(() -> opStatus.setText(s)); }
        @Override public void log(String s) { SessionManager.get().console().log(s); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = Prefs.sp(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // top bar: back arrow + title
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        TextView back = Ui.iconButton(this, "\u2190");
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        category = getIntent().getStringExtra(EXTRA_CATEGORY);
        pageMode = category != null;
        TextView title = new TextView(this);
        titleView = title;
        title.setText(pageMode ? categoryTitle(category) : "Settings");
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
        content.setPadding(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 32));
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Ui.applyInsets(root);

        if (!pageMode) {
            buildHome();
        } else {
            content.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 32));
            switch (category) {
                case "terminal": buildTerminalSection(); break;
                case "sessions": buildSessionSection(); break;
                case "environment": buildEnvironmentSection(); break;
                case "rootfs": buildRootfsSection(); refreshRootfsInfo(); break;
                case "commands": buildCommandsSection(); break;
                case "editor": buildEditorSection(); break;
                case "vnc": buildVncSection(); break;
                default: buildOtherSection(); break;
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (!pageMode) refreshSummaries();
    }

    private static String categoryTitle(String id) {
        for (String[] c : CATEGORIES) if (c[0].equals(id)) return c[1];
        return "Settings";
    }

    // ------------------------------------------------------------------ category list

    private void buildHome() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(Ui.rounded(this, Ui.SURFACE, 14));
        content.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 32));
        content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < CATEGORIES.length; i++) {
            final String id = CATEGORIES[i][0];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14));
            row.setClickable(true);
            row.setBackground(Ui.ripple(this, 0, 14));
            row.setOnClickListener(v -> startActivity("browser".equals(id)
                    ? new Intent(this, BrowserSettingsActivity.class)
                    : "appearance".equals(id) ? new Intent(this, ThemeActivity.class)
                    : new Intent(this, SettingsActivity.class).putExtra(EXTRA_CATEGORY, id)));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView t = new TextView(this);
            t.setText(CATEGORIES[i][1]);
            t.setTextColor(Ui.TEXT);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            texts.addView(t);
            TextView sub = new TextView(this);
            sub.setTextColor(Ui.DIM);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setPadding(0, Ui.dp(this, 2), 0, 0);
            texts.addView(sub);
            summaries.put(id, sub);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView chevron = new TextView(this);
            chevron.setText("\u203A");
            chevron.setTextColor(Ui.DIM);
            chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            row.addView(chevron);
            list.addView(row);

            if (i < CATEGORIES.length - 1) {
                View d = new View(this);
                d.setBackgroundColor(Ui.LINE);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
                lp.setMargins(Ui.dp(this, 16), 0, 0, 0);
                list.addView(d, lp);
            }
        }
        refreshSummaries();
    }

    /** One-line state under each category name. */
    private void refreshSummaries() {
        setSummary("appearance", (Theme.auto(this) ? "Automatic  \u00B7  " + Theme.byId(Theme.dayId(this)).name + " / " + Theme.byId(Theme.nightId(this)).name
                : Theme.current.name) + (Theme.accent(this) != 0 ? "  \u00B7  custom accent" : ""));
        String fontLabel = TerminalFonts.currentLabel(this);
        setSummary("terminal", Prefs.fontSizeSp(this) + " sp  \u00B7  " + Prefs.SCHEME_NAMES[Prefs.scheme(this)]
                + (fontLabel.startsWith("System") ? "" : "  \u00B7  " + fontLabel));

        String shell = Prefs.shell(this);
        setSummary("sessions", shell.substring(shell.lastIndexOf('/') + 1) + "  \u00B7  " + Prefs.workdir(this)
                + (Prefs.autoStart(this) ? "  \u00B7  opens on start" : ""));

        java.util.List<String> env = new java.util.ArrayList<>();
        if (Prefs.bindSdcard(this)) env.add("/sdcard");
        if (Prefs.bindSys(this)) env.add("/sys");
        if (Prefs.fakeProc(this)) env.add("live /proc");
        if (Prefs.wakeLock(this)) env.add("wake lock");
        setSummary("environment", env.isEmpty() ? "Nothing enabled" : String.join("  \u00B7  ", env));

        setSummary("editor", Prefs.ED_THEME_NAMES[themeIndex()] + "  \u00B7  " + Prefs.edFont(this) + " sp"
                + (Prefs.edLsp(this) ? "  \u00B7  language servers" : ""));

        setSummary("browser", (BrowserProxy.active(this) ? "Proxy " + BrowserProxy.label(this) : "No proxy")
                + "  \u00B7  " + BrowserCerts.list(this).size() + " CA");

        setSummary("vnc", Prefs.vncWidth(this) + "×" + Prefs.vncHeight(this) + "  ·  :" + Prefs.vncDisplay(this)
                + "  ·  " + (Prefs.vncLocalOnly(this) ? "this phone only" : "network access"));

        SnippetStore.get().init(this);
        int n = SnippetStore.get().all().size();
        setSummary("commands", n == 0 ? "None saved" : n + " saved");

        setSummary("about", "Version 1.1");

        if (!RootfsManager.dir(this).exists()) {
            setSummary("rootfs", "Not installed");
        } else {
            setSummary("rootfs", (RootfsManager.isInstalled(this) ? "Installed" : "Incomplete") + "  \u00B7  calculating size\u2026");
            new Thread(() -> {
                final long size = RootfsManager.sizeOf(RootfsManager.dir(this));
                final boolean ok = RootfsManager.isInstalled(this);
                main.post(() -> setSummary("rootfs", (ok ? "Installed" : "Incomplete") + "  \u00B7  " + RootfsManager.formatBytes(size)));
            }, "rootfs-size-home").start();
        }
    }

    private void setSummary(String id, String text) {
        TextView t = summaries.get(id);
        if (t != null) t.setText(text);
    }

    // ------------------------------------------------------------------ sections

    private void buildTerminalSection() {
        section("Terminal");

        final TextView sizeLabel = rowLabel("Font size  \u00B7  " + Prefs.fontSizeSp(this) + " sp");
        SeekBar seek = new SeekBar(this);
        Ui.tint(seek);
        seek.setMax(32 - 8);
        seek.setProgress(Prefs.fontSizeSp(this) - 8);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                int v = p + 8;
                sizeLabel.setText("Font size  \u00B7  " + v + " sp");
                sp.edit().putInt(Prefs.FONT_SIZE, v).apply();
                updatePreview();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        card.addView(seek);

        rowLabel("Color scheme");
        final TextView scheme = Ui.button(this, Prefs.SCHEME_NAMES[Prefs.scheme(this)], Ui.SECONDARY);
        scheme.setOnClickListener(v -> new Dlg(this)
                .setTitle("Color scheme")
                .setSingleChoiceItems(Prefs.SCHEME_NAMES, Prefs.scheme(this), (d, which) -> {
                    sp.edit().putInt(Prefs.SCHEME, which).apply();
                    scheme.setText(Prefs.SCHEME_NAMES[which]);
                    updatePreview();
                    d.dismiss();
                }).show());
        card.addView(scheme);

        rowLabel("Font");
        fontBtn = Ui.button(this, TerminalFonts.currentLabel(this), Ui.SECONDARY);
        fontBtn.setOnClickListener(v -> showFontPicker());
        card.addView(fontBtn);
        removeFontsBtn = Ui.button(this, "", Ui.SECONDARY);
        removeFontsBtn.setOnClickListener(v -> confirmRemoveFonts());
        card.addView(removeFontsBtn, topMargin(8));
        note("Nerd Fonts add the icons and symbols that prompts and tools such as Starship, Powerlevel10k, eza and lsd "
                + "draw. A font is downloaded once from github.com/ryanoasis/nerd-fonts and kept in the app. Until one is "
                + "selected, the icons in the preview below show as empty boxes.");

        preview = new TextView(this);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setText("root@kali:~# ls --color\nDocuments  Downloads  tools\n main         ");
        preview.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        card.addView(preview, topMargin(10));
        refreshFontRows();

        final TextView sbLabel = rowLabel(scrollbackText(Prefs.termScrollback(this)));
        SeekBar sb = new SeekBar(this);
        Ui.tint(sb);
        sb.setMax(19);
        sb.setProgress(Prefs.termScrollback(this) / 500 - 1);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                sbLabel.setText(scrollbackText((p + 1) * 500));
                sp.edit().putInt(Prefs.TERM_SCROLLBACK, (p + 1) * 500).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        card.addView(sb);
        note("History is kept in RAM for every tab. It is cut to the newest 500 lines if Android is about to reclaim the app. "
                + "A new length applies to tabs opened afterwards.");

        toggle("Show extra keys row", Prefs.EXTRA_KEYS, true);
        toggle("Keep screen on", Prefs.KEEP_SCREEN_ON, false);
    }

    private static String scrollbackText(int lines) { return "Scrollback  \u00B7  " + lines + " lines"; }

    private void buildSessionSection() {
        section("Sessions");
        toggle("Open a session when the app starts", Prefs.AUTO_START, true);
        note("The options below apply to new sessions.");
        text("Shell", Prefs.SHELL, "/bin/bash", "Path inside Kali, e.g. /bin/zsh. Falls back to /bin/bash if missing.");
        text("Start directory", Prefs.WORKDIR, "/root", null);
        text("Locale (LANG)", Prefs.LANG, "C.UTF-8", null);
        text("DNS servers", Prefs.DNS, "1.1.1.1, 8.8.8.8", "Comma or space separated. Written to /etc/resolv.conf at each start.");
        text("Startup command", Prefs.STARTUP_CMD, "", "Runs once before the prompt of every new session.");

    }

    private void buildEnvironmentSection() {
        section("Environment");
        toggle("Bind /sdcard (shared storage)", Prefs.BIND_SDCARD, true);
        toggle("Bind /sys", Prefs.BIND_SYS, true);
        toggle("Live /proc stats (top, htop, uptime)", Prefs.FAKE_PROC, true);
        toggle("Keep CPU awake while sessions run", Prefs.WAKE_LOCK, true);
        text("Extra PRoot arguments", Prefs.EXTRA_PROOT, "", "Advanced. Split on spaces, e.g. -b /sdcard/Download:/mnt/dl");
    }

    private void buildRootfsSection() {
        section("Kali rootfs");
        rootfsInfo = rowLabel("");
        opStatus = note("");
        opStatus.setTextColor(Ui.ACCENT);

        importBtn = Ui.button(this, "Import\u2026", Ui.SECONDARY);
        importBtn.setOnClickListener(v -> askStopThen("Import replaces the current rootfs.", this::pickImport));
        card.addView(importBtn, topMargin(8));

        exportBtn = Ui.button(this, "Export\u2026", Ui.SECONDARY);
        exportBtn.setOnClickListener(v -> askStopThen("Export needs all sessions stopped for a consistent backup.", this::pickExport));
        card.addView(exportBtn, topMargin(8));

        deleteBtn = Ui.button(this, "Delete rootfs", Ui.DANGER_STYLE);
        deleteBtn.setOnClickListener(v -> confirmDelete());
        card.addView(deleteBtn, topMargin(8));

        note("Import accepts .tar.gz, .tar.xz, .tar and NetHunter installer .zip files. Export writes a .tar.gz.");
    }

    private int themeIndex() {
        String t = Prefs.edThemeRaw(this);
        for (int i = 0; i < Prefs.ED_THEMES.length; i++) if (Prefs.ED_THEMES[i].equals(t)) return i;
        return 0;
    }

    private void buildEditorSection() {
        section("Editor");

        final TextView sizeLabel = rowLabel("Font size  \u00B7  " + Prefs.edFont(this) + " sp");
        SeekBar seek = new SeekBar(this);
        Ui.tint(seek);
        seek.setMax(32 - 8);
        seek.setProgress(Prefs.edFont(this) - 8);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                sizeLabel.setText("Font size  \u00B7  " + (p + 8) + " sp");
                sp.edit().putInt(Prefs.ED_FONT, p + 8).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        card.addView(seek);

        rowLabel("Color theme");
        final TextView theme = Ui.button(this, Prefs.ED_THEME_NAMES[themeIndex()], Ui.SECONDARY);
        theme.setOnClickListener(v -> new Dlg(this).setTitle("Editor theme")
                .setSingleChoiceItems(Prefs.ED_THEME_NAMES, themeIndex(), (d, which) -> {
                    sp.edit().putString(Prefs.ED_THEME, Prefs.ED_THEMES[which]).apply();
                    theme.setText(Prefs.ED_THEME_NAMES[which]);
                    d.dismiss();
                }).show());
        card.addView(theme);

        final TextView tabLabel = rowLabel("Tab width  \u00B7  " + Prefs.edTab(this));
        SeekBar tab = new SeekBar(this);
        Ui.tint(tab);
        tab.setMax(7);
        tab.setProgress(Prefs.edTab(this) - 1);
        tab.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                tabLabel.setText("Tab width  \u00B7  " + (p + 1));
                sp.edit().putInt(Prefs.ED_TAB, p + 1).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        card.addView(tab);

        toggle("Indent with spaces", Prefs.ED_SPACES, true);
        toggle("Wrap long lines", Prefs.ED_WRAP, false);
        toggle("Show line numbers", Prefs.ED_LINES, true);
        toggle("Symbol row above the keyboard", Prefs.ED_SYMBOLS, true);
        toggle("Save automatically", Prefs.ED_AUTOSAVE, true);
        toggle("Use as $EDITOR in new terminal sessions", Prefs.EDITOR_ENV, false);
        note("With this on, commands such as git commit or crontab -e open files in the NullShell editor. "
                + "You can always run: ns-edit FILE   (ns-edit -w waits until the tab is closed).");

        final TextView loadedLabel = rowLabel(loadedText(Prefs.edLoaded(this)));
        SeekBar loaded = new SeekBar(this);
        Ui.tint(loaded);
        loaded.setMax(12);
        loaded.setProgress(Prefs.edLoaded(this));
        loaded.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                loadedLabel.setText(loadedText(p));
                sp.edit().putInt(Prefs.ED_LOADED, p).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        card.addView(loaded);
        note("Tabs beyond this number are released from memory (cursor position is kept) and read from disk again when you "
                + "select them. Tabs with unsaved edits are never released.");

        final TextView largeLabel = rowLabel(largeText(Prefs.edLargeIndex(this)));
        SeekBar large = new SeekBar(this);
        Ui.tint(large);
        large.setMax(Prefs.LARGE_KB.length - 1);
        large.setProgress(Prefs.edLargeIndex(this));
        large.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                largeLabel.setText(largeText(p));
                sp.edit().putInt(Prefs.ED_LARGE, p).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        card.addView(large);
        note("Files above this size open in large-file mode: no syntax highlighting, no language server and no line wrapping, "
                + "which keeps big logs and dumps from using hundreds of MB. Files over 8 MB are not opened.");

        section("Language servers");
        toggle("Enable language servers", Prefs.ED_LSP, true);
        note("Servers run inside Kali and are started when you open a matching file. Each needs a few hundred MB of RAM; "
                + "close tabs you are done with. Takes effect for files opened afterwards.");
        final TextView idleLabel = rowLabel(idleText(Prefs.lspIdleMinutes(this)));
        SeekBar idle = new SeekBar(this);
        Ui.tint(idle);
        idle.setMax(30);
        idle.setProgress(Prefs.lspIdleMinutes(this));
        idle.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                idleLabel.setText(idleText(p));
                sp.edit().putInt(Prefs.ED_LSP_IDLE, p).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        card.addView(idle);
        final TextView maxLabel = rowLabel(maxText(Prefs.lspMaxServers(this)));
        SeekBar max = new SeekBar(this);
        Ui.tint(max);
        max.setMax(6);
        max.setProgress(Prefs.lspMaxServers(this));
        max.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                maxLabel.setText(maxText(p));
                sp.edit().putInt(Prefs.ED_LSP_MAX, p).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        card.addView(max);
        note("A server is stopped when no file of its project is open for the time above, or to make room when the limit is reached "
                + "(servers under open files are never stopped for the limit). All servers also stop when Android runs low on memory "
                + "with the app in the background, and restart when you come back.");
        final TextView memLabel = rowLabel(memText(Prefs.lspMemoryMb(this)));
        SeekBar mem = new SeekBar(this);
        Ui.tint(mem);
        mem.setMax(16);
        mem.setProgress(Prefs.lspMemoryMb(this) / 128);
        mem.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                memLabel.setText(memText(p * 128));
                sp.edit().putInt(Prefs.ED_LSP_MEM, p * 128).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        card.addView(mem);
        note("The memory cap applies to JavaScript, TypeScript, shell, JSON, HTML, CSS and YAML servers (Node) and to gopls. "
                + "If a server stops with an out-of-memory error on a big project, raise it. clangd already starts with "
                + "-j=2 and on-disk precompiled headers; change its command below if you prefer other flags.");
        final java.io.File rootfs = RootfsManager.dir(this);
        for (final LanguageSupport.Server server : LanguageSupport.servers()) {
            final String override = Prefs.serverCommand(this, server.id);
            final boolean installed = rootfs.exists() && GuestPaths.which(rootfs, LanguageSupport.binary(server, override)) != null;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 8), 0, 0);
            TextView name = new TextView(this);
            name.setText(server.label);
            name.setTextColor(Ui.TEXT);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView state = Ui.key(this, installed ? "Installed" : "Install");
            if (!installed) {
                Ui.setKeyActive(this, state, true);
                state.setOnClickListener(v -> new Dlg(this).setTitle("Install " + server.label + "?")
                        .setMessage("This runs in a terminal tab:\n\n" + server.install)
                        .setPositiveButton("Install", (d, w) -> MainActivity.runInTerminal(this, server.install))
                        .setNegativeButton("Cancel", null).show());
            }
            row.addView(state);
            card.addView(row);
            EditText cmd = new EditText(this);
            cmd.setSingleLine(true);
            cmd.setTextColor(Ui.TEXT);
            cmd.setHintTextColor(Ui.HINT);
            cmd.setHint(server.defaultCommand());
            cmd.setTypeface(android.graphics.Typeface.MONOSPACE);
            cmd.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            cmd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            cmd.setText(override);
            Ui.tint(cmd);
            cmd.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable s) { sp.edit().putString("srv_cmd_" + server.id, s.toString()).apply(); }
            });
            card.addView(cmd);
        }
        note("The line under each server overrides its start command, e.g. python3 -m pylsp. Leave empty for the default.");
    }

    // ------------------------------------------------------------------ VNC server

    private SeekBar vncWBar, vncHBar;
    private TextView vncWLabel, vncHLabel;

    private static final int VNC_STEP = 8;

    private static String vncWText(int w) { return "Width  ·  " + w + " px"; }
    private static String vncHText(int h) { return "Height  ·  " + h + " px"; }
    private static String vncDpiText(int dpi) { return "Scale  ·  " + dpi + " DPI"; }
    private static String vncDisplayText(int d) { return ":" + d + "  (port " + (5900 + d) + ")"; }

    private void buildVncSection() {
        section("VNC server");
        note("A graphical IceWM desktop inside Kali, served over VNC for a separate VNC viewer app. It is software "
                + "rendered, so a smaller size and 16-bit colour run faster. Changes apply the next time the server "
                + "starts (use Restart on the VNC server screen).");

        // ---- resolution
        vncWLabel = rowLabel(vncWText(Prefs.vncWidth(this)));
        vncWBar = new SeekBar(this);
        Ui.tint(vncWBar);
        vncWBar.setMax((2560 - 640) / VNC_STEP);
        vncWBar.setProgress((Prefs.vncWidth(this) - 640) / VNC_STEP);
        vncWBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                int v = 640 + p * VNC_STEP;
                vncWLabel.setText(vncWText(v));
                sp.edit().putInt(Prefs.VNC_W, v).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        card.addView(vncWBar);

        vncHLabel = rowLabel(vncHText(Prefs.vncHeight(this)));
        vncHBar = new SeekBar(this);
        Ui.tint(vncHBar);
        vncHBar.setMax((1600 - 480) / VNC_STEP);
        vncHBar.setProgress((Prefs.vncHeight(this) - 480) / VNC_STEP);
        vncHBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                int v = 480 + p * VNC_STEP;
                vncHLabel.setText(vncHText(v));
                sp.edit().putInt(Prefs.VNC_H, v).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        card.addView(vncHBar);

        final TextView presets = Ui.button(this, "Size presets…", Ui.SECONDARY);
        presets.setOnClickListener(v -> showVncPresets());
        card.addView(presets, topMargin(8));

        // ---- colour depth
        rowLabel("Colour depth");
        final String[] depthNames = {"16-bit  ·  faster", "24-bit  ·  better colour"};
        final TextView depthBtn = Ui.button(this, depthNames[Prefs.vncDepthIndex(this)], Ui.SECONDARY);
        depthBtn.setOnClickListener(v -> new Dlg(this).setTitle("Colour depth")
                .setSingleChoiceItems(depthNames, Prefs.vncDepthIndex(this), (d, which) -> {
                    sp.edit().putInt(Prefs.VNC_DEPTH, which).apply();
                    depthBtn.setText(depthNames[which]);
                    d.dismiss();
                }).show());
        card.addView(depthBtn);

        // ---- scale
        final TextView dpiLabel = rowLabel(vncDpiText(Prefs.vncDpi(this)));
        SeekBar dpiBar = new SeekBar(this);
        Ui.tint(dpiBar);
        dpiBar.setMax((240 - 72) / VNC_STEP);
        dpiBar.setProgress((Prefs.vncDpi(this) - 72) / VNC_STEP);
        dpiBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user) return;
                int v = 72 + p * VNC_STEP;
                dpiLabel.setText(vncDpiText(v));
                sp.edit().putInt(Prefs.VNC_DPI, v).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        card.addView(dpiBar);
        note("Raise this (120–160) if text and buttons look tiny on a phone screen.");

        // ---- display number
        rowLabel("Display");
        final String[] displayNames = new String[9];
        for (int i = 0; i < 9; i++) displayNames[i] = vncDisplayText(i + 1);
        final TextView displayBtn = Ui.button(this, vncDisplayText(Prefs.vncDisplay(this)), Ui.SECONDARY);
        displayBtn.setOnClickListener(v -> new Dlg(this).setTitle("Display number")
                .setSingleChoiceItems(displayNames, Prefs.vncDisplay(this) - 1, (d, which) -> {
                    sp.edit().putInt(Prefs.VNC_DISPLAY, which + 1).apply();
                    displayBtn.setText(displayNames[which]);
                    d.dismiss();
                }).show());
        card.addView(displayBtn);
        note("The viewer connects to port 5900 plus the display number. Change it only if that port is already in use.");

        // ---- desktop look
        section("Desktop look");
        IceWmConfig.ensureBundled(this);
        note("Real IceWM themes: Nord, Nitrogen and Ice7 come with the app (install Kali first), and you can download about "
                + "100 more from the official icewm-extra project. The choice is written into the Kali home folder each time "
                + "the server starts.");
        rowLabel("Theme");
        final TextView themeBtn = Ui.button(this, IceWmConfig.themeLabel(this), Ui.SECONDARY);
        themeBtn.setOnClickListener(v -> showIceWmThemePicker(themeBtn));
        card.addView(themeBtn);
        final TextView themeDl = Ui.button(this, IceWmConfig.downloadedCount(this) > 0
                ? "Update themes (" + IceWmConfig.downloadedCount(this) + " installed)" : "Download themes", Ui.SECONDARY);
        themeDl.setOnClickListener(v -> new Dlg(this).setTitle("Download IceWM themes?")
                .setMessage("Downloads the icewm-extra theme collection from GitHub (GPL-3.0) into the Kali home folder. "
                        + "It needs an internet connection and some storage.")
                .setPositiveButton("Download", (d, w) -> downloadIceWmThemes(themeBtn, themeDl))
                .setNegativeButton("Cancel", null).show());
        card.addView(themeDl, topMargin(8));
        TextView themeRm = Ui.button(this, "Remove downloaded themes", Ui.SECONDARY);
        themeRm.setOnClickListener(v -> new Dlg(this).setTitle("Remove downloaded themes?")
                .setPositiveButton("Remove", (d, w) -> {
                    IceWmConfig.removeDownloadedThemes(this);
                    themeBtn.setText(IceWmConfig.themeLabel(this));
                    themeDl.setText("Download themes");
                })
                .setNegativeButton("Cancel", null).show());
        card.addView(themeRm, topMargin(8));
        rowLabel("Interface size");
        final TextView sizeBtn = Ui.button(this, IceWmConfig.SIZE_NAMES[Prefs.vncUiSize(this)], Ui.SECONDARY);
        sizeBtn.setOnClickListener(v -> new Dlg(this).setTitle("Interface size")
                .setSingleChoiceItems(IceWmConfig.SIZE_NAMES, Prefs.vncUiSize(this), (d, which) -> {
                    sp.edit().putInt(Prefs.VNC_UISIZE, which).apply();
                    sizeBtn.setText(IceWmConfig.SIZE_NAMES[which]);
                    d.dismiss();
                }).show());
        card.addView(sizeBtn);
        note("Sets the font, title bar and taskbar size. Use Large or Extra large on a phone.");
        rowLabel("Wallpaper");
        final TextView wallBtn = Ui.button(this, IceWmConfig.WALLPAPER_NAMES[Prefs.vncWallpaper(this)], Ui.SECONDARY);
        wallBtn.setOnClickListener(v -> new Dlg(this).setTitle("Theme wallpaper")
                .setSingleChoiceItems(IceWmConfig.WALLPAPER_NAMES, Prefs.vncWallpaper(this), (d, which) -> {
                    sp.edit().putInt(Prefs.VNC_WALL, which).apply();
                    wallBtn.setText(IceWmConfig.WALLPAPER_NAMES[which]);
                    d.dismiss();
                }).show());
        card.addView(wallBtn, topMargin(8));
        final TextView ownWall = Ui.button(this, wallpaperLabel(), Ui.SECONDARY);
        ownWall.setOnClickListener(v -> FilePick.request(this, false, "/root", path -> {
            String low = path.toLowerCase(Locale.ROOT);
            if (!IceWmConfig.safeGuestPath(path)) { toast("That file name has characters that cannot be used."); return; }
            if (!(low.endsWith(".png") || low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".xpm")
                    || low.endsWith(".bmp") || low.endsWith(".gif"))) { toast("Pick a PNG or JPEG image."); return; }
            sp.edit().putString(Prefs.VNC_WALLPATH, path).apply();
            ownWall.setText(wallpaperLabel());
            toast("Wallpaper set. It shows the next time the desktop starts.");
        }));
        card.addView(ownWall, topMargin(8));
        TextView ownWallOff = Ui.button(this, "Use the theme's wallpaper", Ui.SECONDARY);
        ownWallOff.setOnClickListener(v -> {
            sp.edit().remove(Prefs.VNC_WALLPATH).apply();
            ownWall.setText(wallpaperLabel());
        });
        card.addView(ownWallOff, topMargin(8));
        note("Your own image: pick a PNG or JPEG from Kali's files (copy a photo in with the file manager first). "
                + "The setting above decides how it fits. Applies at the next desktop start.");
        note("Many themes include a wallpaper made for one screen size. Stretch fills your desktop; the others keep its "
                + "shape (centre, tile) or hide it."); 
        toggle("Keep my own desktop config", Prefs.VNC_KEEPCFG, false);
        note("On: once ~/.icewm/preferences exists, the app never overwrites your config. It still writes it the first time.");
        TextView resetCfg = Ui.button(this, "Reset desktop config", Ui.SECONDARY);
        resetCfg.setOnClickListener(v -> new Dlg(this).setTitle("Reset desktop config?")
                .setMessage("Deletes the preferences, theme choice and menu files in Kali. They are written again from the settings the next time the server starts. Downloaded themes are kept.")
                .setPositiveButton("Reset", (d, w) -> {
                    IceWmConfig.reset(this);
                    Toast.makeText(this, "Desktop config reset", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show());
        card.addView(resetCfg, topMargin(8));

        // ---- behaviour
        toggle("Only this phone can connect", Prefs.VNC_LOCAL, true);
        note("On: use a viewer app on this phone (address 127.0.0.1). Off: other devices on your Wi-Fi or hotspot can "
                + "connect too, still with the password.");
        toggle("Allow several viewers at once", Prefs.VNC_SHARED, true);
        toggle("Open a terminal when the desktop starts", Prefs.VNC_TERMINAL, true);
        toggle("Share clipboard with the viewer", Prefs.VNC_CLIPBOARD, true);

        // ---- password
        section("Password");
        note("The viewer asks for this. VNC only uses the first 8 characters, so it is 6 to 8 long.");
        final TextView passView = rowLabel("");
        passView.setTypeface(Typeface.MONOSPACE);
        passView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        final boolean[] shown = {false};
        final Runnable paint = () -> passView.setText(shown[0] ? Prefs.vncPassword(this)
                : "••••••••");
        paint.run();

        final TextView showBtn = Ui.button(this, "Show", Ui.SECONDARY);
        showBtn.setOnClickListener(v -> {
            shown[0] = !shown[0];
            showBtn.setText(shown[0] ? "Hide" : "Show");
            paint.run();
        });
        card.addView(showBtn, topMargin(4));
        TextView change = Ui.button(this, "Change password…", Ui.SECONDARY);
        change.setOnClickListener(v -> {
            final EditText e = new EditText(this);
            e.setSingleLine(true);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            e.setTextColor(Ui.TEXT);
            e.setTypeface(Typeface.MONOSPACE);
            e.setText(Prefs.vncPassword(this));
            Ui.tint(e);
            LinearLayout wrap = new LinearLayout(this);
            wrap.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
            wrap.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            new Dlg(this).setTitle("VNC password").setView(wrap)
                    .setPositiveButton("Save", (d, w) -> {
                        String pw = e.getText().toString();
                        if (pw.length() < 6 || pw.length() > 8 || pw.matches(".*\\s.*")) {
                            Toast.makeText(this, "Use 6 to 8 characters, no spaces", Toast.LENGTH_LONG).show();
                            return;
                        }
                        sp.edit().putString(Prefs.VNC_PASSWORD, pw).apply();
                        paint.run();
                    })
                    .setNegativeButton("Cancel", null).show();
        });
        card.addView(change, topMargin(8));
        TextView regenerate = Ui.button(this, "Generate a new random password", Ui.SECONDARY);
        regenerate.setOnClickListener(v -> new Dlg(this).setTitle("Generate a new password?")
                .setMessage("Viewer apps with the old password saved will need the new one.")
                .setPositiveButton("Generate", (d, w) -> {
                    sp.edit().putString(Prefs.VNC_PASSWORD, Prefs.newVncPassword()).apply();
                    paint.run();
                })
                .setNegativeButton("Cancel", null).show());
        card.addView(regenerate, topMargin(8));

        // ---- advanced
        section("Advanced");
        text("Extra server arguments", Prefs.VNC_EXTRA, "-ZlibLevel 1",
                "Added to the Xtigervnc command line. Leave empty unless you know you need it.");
    }

    private void showIceWmThemePicker(final TextView button) {
        IceWmConfig.ensureBundled(this);
        java.util.List<String> installed = IceWmConfig.installedThemes(this);
        final java.util.List<String> ids = new java.util.ArrayList<>();
        final java.util.List<String> names = new java.util.ArrayList<>();
        ids.add(""); names.add("IceWM default");
        for (String r : IceWmConfig.RECOMMENDED) if (installed.contains(r)) { ids.add(r); names.add("\u2605 " + r); }
        for (String t : installed) if (!IceWmConfig.RECOMMENDED.contains(t)) { ids.add(t); names.add(t); }
        if (ids.size() == 1) {
            Toast.makeText(this, "No themes found. Install Kali first, or use Download themes.", Toast.LENGTH_LONG).show();
            return;
        }
        String cur = Prefs.vncTheme(this);
        new Dlg(this).setTitle("Desktop theme")
                .setSingleChoiceItems(names.toArray(new String[0]), Math.max(0, ids.indexOf(cur)), (d, which) -> {
                    sp.edit().putString(Prefs.VNC_THEME, ids.get(which)).apply();
                    button.setText(ids.get(which).isEmpty() ? "IceWM default" : ids.get(which));
                    d.dismiss();
                }).show();
    }

    private void downloadIceWmThemes(final TextView themeBtn, final TextView themeDl) {
        final boolean[] cancelled = {false};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 22), Ui.dp(this, 4));
        final TextView status = new TextView(this);
        status.setTextColor(Ui.TEXT);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setText("Connecting\u2026");
        box.addView(status);
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setMax(1000);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = Ui.dp(this, 12);
        box.addView(bar, bp);
        final AlertDialog dlg = new Dlg(this).setTitle("Downloading themes").setView(box).setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> cancelled[0] = true).show();

        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            try {
                final int n = IceWmConfig.downloadThemes(app, new TerminalFonts.Progress() {
                    @Override public void onProgress(long done, long total) {
                        main.post(() -> {
                            if (total > 0) {
                                bar.setIndeterminate(false);
                                bar.setProgress((int) Math.min(1000, done * 1000 / total));
                                status.setText(RootfsManager.formatBytes(done) + " of " + RootfsManager.formatBytes(total));
                            } else {
                                status.setText(RootfsManager.formatBytes(done) + " downloaded");
                            }
                        });
                    }
                    @Override public boolean cancelled() { return cancelled[0]; }
                });
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    dlg.dismiss();
                    themeBtn.setText(IceWmConfig.themeLabel(this));
                    themeDl.setText("Update themes (" + IceWmConfig.downloadedCount(this) + " installed)");
                    Toast.makeText(this, n + " themes installed. Pick one under Theme.", Toast.LENGTH_LONG).show();
                });
            } catch (java.io.IOException e) {
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    dlg.dismiss();
                    if (!cancelled[0]) Toast.makeText(this, "Theme download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "icewm-themes-download").start();
    }

    private void showVncPresets() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        final int longEdge = Math.max(dm.widthPixels, dm.heightPixels);
        final int shortEdge = Math.min(dm.widthPixels, dm.heightPixels);
        final String[] names = {"This phone, landscape", "This phone, portrait", "1024 × 768", "1280 × 720",
                "1600 × 900", "1920 × 1080"};
        final int[][] sizes = {{longEdge, shortEdge}, {shortEdge, longEdge}, {1024, 768}, {1280, 720}, {1600, 900}, {1920, 1080}};
        new Dlg(this).setTitle("Size presets")
                .setItems(names, (d, which) -> setVncSize(sizes[which][0], sizes[which][1])).show();
    }

    /** Fits a size into the supported range (keeping its shape), snaps it to the slider step and saves it. */
    private void setVncSize(int w, int h) {
        double f = Math.min(1.0, Math.min(2560.0 / w, 1600.0 / h));
        w = (int) Math.round(w * f);
        h = (int) Math.round(h * f);
        w = 640 + Math.round((Math.max(640, Math.min(2560, w)) - 640) / (float) VNC_STEP) * VNC_STEP;
        h = 480 + Math.round((Math.max(480, Math.min(1600, h)) - 480) / (float) VNC_STEP) * VNC_STEP;
        sp.edit().putInt(Prefs.VNC_W, w).putInt(Prefs.VNC_H, h).apply();
        vncWLabel.setText(vncWText(w));
        vncHLabel.setText(vncHText(h));
        vncWBar.setProgress((w - 640) / VNC_STEP);
        vncHBar.setProgress((h - 480) / VNC_STEP);
    }

    private void buildCommandsSection() {
        section("Commands");
        SnippetStore.get().init(this);
        note("Saved commands and templates live outside the rootfs, so deleting Kali never removes them.");
        TextView exp = Ui.button(this, "Export commands\u2026", Ui.SECONDARY);
        exp.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, "nullshell-commands.json");
            startActivityForResult(i, REQ_CMD_EXPORT);
        });
        card.addView(exp, topMargin(8));
        TextView imp = Ui.button(this, "Import commands\u2026", Ui.SECONDARY);
        imp.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_CMD_IMPORT);
        });
        card.addView(imp, topMargin(8));
        note("Importing adds the commands from the file; ones with the same id are replaced.");
    }

    private void buildOtherSection() {
        section("Other");
        TextView reset = Ui.button(this, "Reset settings to defaults", Ui.SECONDARY);
        reset.setOnClickListener(v -> new Dlg(this)
                .setTitle("Reset settings?")
                .setMessage("Your rootfs is not affected.")
                .setPositiveButton("Reset", (d, w) -> { sp.edit().clear().apply(); recreate(); })
                .setNegativeButton("Cancel", null).show());
        card.addView(reset);
        note("NullShell 1.1");
        note("Open-source licenses: see THIRD_PARTY_NOTICES.md in the project.");
    }

    // ------------------------------------------------------------------ rootfs actions

    private void askStopThen(String why, Runnable action) {
        if (RootfsManager.isBusy()) { toast("Another rootfs operation is running"); return; }
        if (SessionManager.get().aliveCount() == 0) { action.run(); return; }
        new Dlg(this)
                .setTitle("Stop all sessions?")
                .setMessage(why + "\n\nAll running Kali sessions will be stopped.")
                .setPositiveButton("Stop and continue", (d, w) -> { SessionManager.get().stopAll(); action.run(); })
                .setNegativeButton("Cancel", null).show();
    }

    private void pickImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    private void pickExport() {
        if (!RootfsManager.isInstalled(this)) { toast("Kali is not installed"); return; }
        String name = "kali-rootfs-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".tar.gz";
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/gzip");
        i.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(i, REQ_EXPORT);
    }

    private String wallpaperLabel() {
        String w = Prefs.vncWallpaperPath(this);
        if (w.isEmpty()) return "Choose your own wallpaper…";
        return "Wallpaper: " + w.substring(w.lastIndexOf('/') + 1);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (FilePick.handle(req, res, data)) return;
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (req == REQ_IMPORT) {
            if (RootfsManager.isInstalled(this)) {
                new Dlg(this)
                        .setTitle("Replace current rootfs?")
                        .setMessage("The imported rootfs replaces the installed one. Export it first if you need a backup.")
                        .setPositiveButton("Import", (d, w) -> runOp("Import", p -> RootfsManager.importFrom(this, uri, p)))
                        .setNegativeButton("Cancel", null).show();
            } else {
                runOp("Import", p -> RootfsManager.importFrom(this, uri, p));
            }
        } else if (req == REQ_EXPORT) {
            runOp("Export", p -> RootfsManager.exportTo(this, uri, p));
        } else if (req == REQ_CMD_EXPORT) {
            try (java.io.OutputStream o = getContentResolver().openOutputStream(uri, "wt")) {
                o.write(SnippetStore.get().exportJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                toast("Commands exported");
            } catch (Exception e) {
                toast("Export failed: " + RootfsManager.message(e));
            }
        } else if (req == REQ_CMD_IMPORT) {
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[16 * 1024];
                int n;
                while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
                int count = SnippetStore.get().importJson(new String(buf.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
                toast("Imported " + count + (count == 1 ? " command" : " commands"));
            } catch (Exception e) {
                toast("Import failed: " + RootfsManager.message(e));
            }
        }
    }

    private void confirmDelete() {
        if (RootfsManager.isBusy()) { toast("Another rootfs operation is running"); return; }
        if (!RootfsManager.dir(this).exists()) { toast("Nothing to delete"); return; }
        new Dlg(this)
                .setTitle("Delete Kali rootfs?")
                .setMessage("This permanently removes installed packages, files in /root and all other data inside Kali. Settings are kept.\n\nExport first if you want a backup.")
                .setPositiveButton("Delete", (d, w) -> askStopThen("Deleting removes the filesystem the sessions are using.",
                        () -> runOp("Delete", p -> RootfsManager.deleteRootfs(this, p))))
                .setNegativeButton("Cancel", null).show();
    }

    private void runOp(String name, Op op) {
        setButtonsEnabled(false);
        SessionManager.get().console().log("[i] " + name + " started\n");
        new Thread(() -> {
            String result;
            try {
                op.run(progress);
                result = name + " finished";
                SessionManager.get().console().log("[+] " + result + "\n");
            } catch (Exception e) {
                result = name + " failed: " + RootfsManager.message(e);
                SessionManager.get().console().log("[!] " + result + "\n");
            }
            final String msg = result;
            main.post(() -> {
                opStatus.setText(msg);
                setButtonsEnabled(true);
                refreshRootfsInfo();
                toast(msg);
            });
        }, "rootfs-" + name).start();
    }

    private void setButtonsEnabled(boolean on) {
        for (TextView b : new TextView[]{importBtn, exportBtn, deleteBtn}) {
            b.setEnabled(on);
            b.setClickable(on);
            b.setAlpha(on ? 1f : 0.4f);
        }
    }

    private void refreshRootfsInfo() {
        if (rootfsInfo == null) return;
        if (!RootfsManager.dir(this).exists()) { rootfsInfo.setText("Not installed"); return; }
        rootfsInfo.setText((RootfsManager.isInstalled(this) ? "Installed" : "Incomplete") + "  \u00B7  calculating size\u2026");
        new Thread(() -> {
            final long size = RootfsManager.sizeOf(RootfsManager.dir(this));
            final boolean ok = RootfsManager.isInstalled(this);
            main.post(() -> rootfsInfo.setText((ok ? "Installed" : "Incomplete")
                    + "  \u00B7  " + RootfsManager.formatBytes(size) + " on disk"));
        }, "rootfs-size").start();
    }

    // ------------------------------------------------------------------ widgets

    private TextView fontBtn, removeFontsBtn;

    private void refreshFontRows() {
        fontBtn.setText(TerminalFonts.currentLabel(this));
        removeFontsBtn.setVisibility(TerminalFonts.installedCount(this) == 0 ? View.GONE : View.VISIBLE);
        removeFontsBtn.setText("Remove downloaded fonts  ·  " + RootfsManager.formatBytes(TerminalFonts.installedBytes(this)));
        updatePreview();
    }

    private void showFontPicker() {
        final String[] items = new String[1 + TerminalFonts.ALL.size()];
        items[0] = "System monospace  ·  no icons";
        int checked = 0;
        String cur = Prefs.termFont(this);
        for (int i = 0; i < TerminalFonts.ALL.size(); i++) {
            TerminalFonts.Font f = TerminalFonts.ALL.get(i);
            boolean have = TerminalFonts.installed(this, f);
            items[i + 1] = f.label + " Nerd Font  ·  " + (have ? "downloaded" : "download");
            if (have && f.id.equals(cur)) checked = i + 1;
        }
        new Dlg(this).setTitle("Terminal font")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    d.dismiss();
                    if (which == 0) {
                        sp.edit().putString(Prefs.TERM_FONT, "").apply();
                        refreshFontRows();
                        return;
                    }
                    TerminalFonts.Font f = TerminalFonts.ALL.get(which - 1);
                    if (TerminalFonts.installed(this, f)) {
                        sp.edit().putString(Prefs.TERM_FONT, f.id).apply();
                        refreshFontRows();
                    } else {
                        confirmDownload(f);
                    }
                }).show();
    }

    private void confirmDownload(final TerminalFonts.Font f) {
        new Dlg(this).setTitle("Download " + f.label + " Nerd Font?")
                .setMessage("This downloads the font from github.com/ryanoasis/nerd-fonts. The archive holds the whole "
                        + "font family, so it can be a large download (use Wi-Fi if you can). Only the regular and bold "
                        + "files are kept in the app.")
                .setPositiveButton("Download", (d, w) -> downloadFont(f))
                .setNegativeButton("Cancel", null).show();
    }

    private void downloadFont(final TerminalFonts.Font f) {
        final boolean[] cancelled = {false};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 22), Ui.dp(this, 4));
        final TextView status = new TextView(this);
        status.setTextColor(Ui.TEXT);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setText("Connecting…");
        box.addView(status);
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setMax(1000);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = Ui.dp(this, 12);
        box.addView(bar, bp);
        final AlertDialog dlg = new Dlg(this).setTitle("Downloading " + f.label).setView(box).setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> cancelled[0] = true).show();

        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            try {
                TerminalFonts.download(app, f, new TerminalFonts.Progress() {
                    @Override public void onProgress(long done, long total) {
                        main.post(() -> {
                            if (total > 0) {
                                bar.setIndeterminate(false);
                                bar.setProgress((int) Math.min(1000, done * 1000 / total));
                                status.setText(RootfsManager.formatBytes(done) + " of " + RootfsManager.formatBytes(total));
                            } else {
                                status.setText(RootfsManager.formatBytes(done) + " downloaded");
                            }
                        });
                    }
                    @Override public boolean cancelled() { return cancelled[0]; }
                });
                sp.edit().putString(Prefs.TERM_FONT, f.id).apply();
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    dlg.dismiss();
                    refreshFontRows();
                    Toast.makeText(this, f.label + " Nerd Font installed and selected", Toast.LENGTH_SHORT).show();
                });
            } catch (java.io.IOException e) {
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    dlg.dismiss();
                    if (!cancelled[0]) Toast.makeText(this, "Font download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "font-download").start();
    }

    private void confirmRemoveFonts() {
        new Dlg(this).setTitle("Remove downloaded fonts?")
                .setMessage("This deletes the downloaded Nerd Fonts (" + RootfsManager.formatBytes(TerminalFonts.installedBytes(this))
                        + ") and switches the terminal back to the system monospace font.")
                .setPositiveButton("Remove", (d, w) -> {
                    TerminalFonts.removeAll(this);
                    sp.edit().putString(Prefs.TERM_FONT, "").apply();
                    refreshFontRows();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void updatePreview() {
        Typeface[] face = TerminalFonts.current(this);
        preview.setTypeface(face == null ? Typeface.MONOSPACE : face[0]);
        preview.setTextColor(Prefs.schemeFg(this));
        preview.setBackground(Ui.rounded(this, Prefs.schemeBg(this), 8));
        preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.min(Prefs.fontSizeSp(this), 18));
    }

    /** A fresh rounded card that the next rows go into (the page title is the heading). */
    private int sectionCount;

    private void section(String title) {
        if (!pageMode) throw new IllegalStateException("sections only exist on category pages");
        if (sectionCount++ > 0) {                       // the page title heads the first card; later cards get their own heading
            TextView h = new TextView(this);
            h.setText(title.toUpperCase(Locale.US));
            h.setTextColor(Ui.ACCENT);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            h.setLetterSpacing(0.1f);
            h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            h.setPadding(Ui.dp(this, 6), Ui.dp(this, 22), 0, Ui.dp(this, 8));
            content.addView(h);
        }
        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.rounded(this, Ui.SURFACE, 14));
        int p = Ui.dp(this, 14);
        card.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 14));
        content.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TextView rowLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Ui.TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        card.addView(t);
        return t;
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Ui.DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 2));
        card.addView(t);
        return t;
    }

    private static String loadedText(int n) { return n == 0 ? "Tabs kept in memory  \u00B7  all" : "Tabs kept in memory  \u00B7  " + n; }

    private static String largeText(int index) {
        int kb = Prefs.LARGE_KB[index];
        return "Large-file mode above  \u00B7  " + (kb >= 1024 ? (kb / 1024) + " MB" : kb + " KB");
    }

    private static String memText(int mb) { return mb == 0 ? "Server memory cap  \u00B7  none" : "Server memory cap  \u00B7  " + mb + " MB"; }

    private static String idleText(int minutes) { return minutes == 0 ? "Stop idle servers  \u00B7  never" : "Stop idle servers after  \u00B7  " + minutes + " min"; }

    private static String maxText(int n) { return n == 0 ? "Running servers  \u00B7  no limit" : "Running servers at most  \u00B7  " + n; }

    private void toggle(String title, String key, boolean def) {
        Switch sw = new Switch(this);
        sw.setText(title);
        sw.setTextColor(Ui.TEXT);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sw.setChecked(sp.getBoolean(key, def));
        sw.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        Ui.tint(sw);
        sw.setOnCheckedChangeListener((v, on) -> sp.edit().putBoolean(key, on).apply());
        card.addView(sw);
    }

    private void text(String title, String key, String hint, String help) {
        TextView l = rowLabel(title);
        l.setTextColor(Ui.DIM);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.HINT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setHint(hint.isEmpty() ? "(none)" : hint);
        e.setText(sp.getString(key, ""));
        Ui.tint(e);
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { sp.edit().putString(key, s.toString()).apply(); }
        });
        card.addView(e);
        if (help != null) note(help);
    }

    private LinearLayout.LayoutParams topMargin(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, dp);
        return lp;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
