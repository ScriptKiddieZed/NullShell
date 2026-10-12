package com.zed.nullshell;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * File manager for the Kali filesystem and the phone's shared storage: browse, search, sort, select, copy,
 * move, delete, rename, archives, permissions, open and share. Copy and move work across both places, which
 * is also how files get into and out of Kali.
 */
public class FileManagerActivity extends Activity {
    private static final String EXTRA_PATH = "path", EXTRA_PHONE = "phone", EXTRA_SELECT = "select", EXTRA_PICK = "pick";
    static final String RESULT_PATH = "picked_path";
    private int pickMode;    // 0 = normal file manager, 1 = choose a file, 2 = choose a folder
    private static final int REQ_STORAGE = 61;
    private static final int SORT_NAME = 0, SORT_SIZE = 1, SORT_DATE = 2, SORT_TYPE = 3;
    private static final int MAX_ENTRIES = 20000, MAX_RESULTS = 500;
    private static final String K_SORT = "fm_sort", K_DESC = "fm_desc", K_HIDDEN = "fm_hidden", K_LAST = "fm_last", K_MARKS = "fm_bookmarks";

    /** Opens the file manager on a Kali path (null = where it was last). */
    static void open(Context c, String guestPath) {
        Intent i = new Intent(c, FileManagerActivity.class);
        if (guestPath != null) i.putExtra(EXTRA_PATH, guestPath);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    /** Opens on a Kali folder with one entry (a Kali path) selected. */
    static void open(Context c, String guestDir, String selectGuestPath) {
        Intent i = new Intent(c, FileManagerActivity.class);
        if (guestDir != null) i.putExtra(EXTRA_PATH, guestDir);
        if (selectGuestPath != null) i.putExtra(EXTRA_SELECT, selectGuestPath);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    /** Opens on a phone-storage folder with one entry (a phone path) selected. */
    static void openPhone(Context c, String dir, String selectPath) {
        Intent i = new Intent(c, FileManagerActivity.class);
        i.putExtra(EXTRA_PHONE, dir);
        if (selectPath != null) i.putExtra(EXTRA_SELECT, selectPath);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    /** Choose a file (or folder) and get its Kali path back in the result as {@link #RESULT_PATH}. */
    static void pick(Activity a, int requestCode, boolean folder, String startGuestDir) {
        Intent i = new Intent(a, FileManagerActivity.class);
        i.putExtra(EXTRA_PICK, folder ? 2 : 1);
        if (startGuestDir != null) i.putExtra(EXTRA_PATH, startGuestDir);
        a.startActivityForResult(i, requestCode);
    }

    private interface JobBody { void run(FileOps.Job job) throws Exception; }

    private static ExecutorService daemon(final String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService lister = daemon("fm-list");
    private final ExecutorService worker = daemon("fm-work");
    private final DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);

    private SharedPreferences sp;
    private File rootfs;
    private boolean kaliOk;
    private String phoneRoot;

    // where we are
    private boolean guest = true;
    private String path = "/root";
    private int gen;
    private String error;
    private List<FileOps.Item> items = new ArrayList<>();
    private final List<FileOps.Item> shown = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean searching;
    private String pendingQuery = "";
    private String pendingPhonePath;
    private String selectAfterLoad;

    // view options
    private int sortBy;
    private boolean descending, showHidden;

    // clipboard for copy and move
    private final List<Path> clip = new ArrayList<>();
    private boolean clipMove;

    // views
    private LinearLayout root, chips, crumbs, pasteBar;
    private View normalBar, selBar, searchBar;
    private TextView titleView, subView, selCount, pasteText, empty;
    private EditText searchInput;
    private HorizontalScrollView crumbScroll;
    private ListView list;
    private ProgressBar loading;
    private Adapter adapter;

    private final Runnable searchRun = () -> runSearch(pendingQuery);

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = Prefs.sp(this);
        kaliOk = RootfsManager.isInstalled(this);
        rootfs = RootfsManager.dir(this);
        phoneRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
        sortBy = sp.getInt(K_SORT, SORT_NAME);
        descending = sp.getBoolean(K_DESC, false);
        showHidden = sp.getBoolean(K_HIDDEN, true);
        pickMode = getIntent().getIntExtra(EXTRA_PICK, 0);
        buildUi();
        rebuildChips();

        String start = getIntent().getStringExtra(EXTRA_PATH);
        String phone = getIntent().getStringExtra(EXTRA_PHONE);
        selectAfterLoad = getIntent().getStringExtra(EXTRA_SELECT);
        String last = sp.getString(K_LAST, null);
        if (phone != null) { guest = false; path = phone; }
        else if (start != null && kaliOk) { guest = true; path = GuestPaths.normalize(start); }
        else if (last != null && last.startsWith("p:") && hasPhoneAccess()) { guest = false; path = last.substring(2); }
        else if (last != null && last.startsWith("g:") && kaliOk) { guest = true; path = last.substring(2); }
        else if (kaliOk) { guest = true; path = "/root"; }
        else { guest = false; path = phoneRoot; }
        if (!guest && !hasPhoneAccess()) { askPhoneAccess(path); guest = kaliOk; path = kaliOk ? "/root" : phoneRoot; }
        reload(true);
    }

    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (pendingPhonePath != null && hasPhoneAccess()) {
            String p = pendingPhonePath;
            pendingPhonePath = null;
            go(false, p);
        } else if (!searching) {
            reload(false);
        }
    }

    @Override protected void onDestroy() {
        for (FileJobs.Record r : myJobs) r.listener = null;     // jobs carry on; they report by notification
        myJobs.clear();
        ui.removeCallbacksAndMessages(null);
        gen++;
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (!selected.isEmpty()) { clearSelection(); return; }
        if (searching) { closeSearch(true); return; }
        String up = upPath();
        if (up != null) go(guest, up); else finish();
    }

    private String upPath() {
        if (guest) return path.equals("/") ? null : GuestPaths.parent(path);
        if (path.equals(phoneRoot) || path.equals("/")) return null;
        File p = new File(path).getParentFile();
        return p == null ? null : p.getPath();
    }

    // ------------------------------------------------------------------ UI

    private LinearLayout bar() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        return l;
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        FrameLayout bars = new FrameLayout(this);

        LinearLayout nb = bar();
        TextView back = Ui.iconButton(this, "\u2190");
        back.setOnClickListener(v -> onBackPressed());
        nb.addView(back);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(this, 6), 0, 0, 0);
        titleView = new TextView(this);
        titleView.setTextColor(Ui.TEXT);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(titleView);
        subView = new TextView(this);
        subView.setTextColor(Ui.DIM);
        subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subView.setSingleLine(true);
        titles.addView(subView);
        nb.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView searchBtn = Ui.iconButton(this, "\uD83D\uDD0D");
        searchBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        searchBtn.setOnClickListener(v -> openSearch());
        nb.addView(searchBtn);
        TextView menu = Ui.iconButton(this, "\u22EE");
        menu.setOnClickListener(v -> showMainMenu(v));
        nb.addView(menu);
        normalBar = nb;
        bars.addView(nb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));

        LinearLayout sb = bar();
        TextView close = Ui.iconButton(this, "\u2715");
        close.setOnClickListener(v -> clearSelection());
        sb.addView(close);
        selCount = new TextView(this);
        selCount.setTextColor(Ui.TEXT);
        selCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        selCount.setPadding(Ui.dp(this, 6), 0, 0, 0);
        sb.addView(selCount, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView copy = Ui.key(this, "Copy");
        copy.setOnClickListener(v -> toClipboard(false));
        sb.addView(copy);
        TextView cut = Ui.key(this, "Cut");
        cut.setOnClickListener(v -> toClipboard(true));
        sb.addView(cut);
        TextView del = Ui.key(this, "Delete");
        del.setTextColor(Ui.DANGER);
        del.setOnClickListener(v -> askDelete());
        sb.addView(del);
        TextView more = Ui.iconButton(this, "\u22EE");
        more.setOnClickListener(v -> showSelectionMenu(v));
        sb.addView(more);
        selBar = sb;
        selBar.setVisibility(View.GONE);
        bars.addView(sb, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));

        LinearLayout se = bar();
        TextView sback = Ui.iconButton(this, "\u2190");
        sback.setOnClickListener(v -> closeSearch(true));
        se.addView(sback);
        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setBackground(null);
        searchInput.setHint("Search in this folder");
        searchInput.setHintTextColor(Ui.HINT);
        searchInput.setTextColor(Ui.TEXT);
        searchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            @Override public void afterTextChanged(Editable e) {
                pendingQuery = e.toString().trim();
                ui.removeCallbacks(searchRun);
                ui.postDelayed(searchRun, 350);
            }
        });
        se.addView(searchInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        TextView sclear = Ui.iconButton(this, "\u2715");
        sclear.setOnClickListener(v -> searchInput.setText(""));
        se.addView(sclear);
        searchBar = se;
        searchBar.setVisibility(View.GONE);
        bars.addView(se, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));
        root.addView(bars, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        chipScroll.addView(chips, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(chipScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 42)));

        crumbScroll = new HorizontalScrollView(this);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        crumbs = new LinearLayout(this);
        crumbs.setOrientation(LinearLayout.HORIZONTAL);
        crumbs.setGravity(Gravity.CENTER_VERTICAL);
        crumbs.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        crumbScroll.addView(crumbs, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(crumbScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 38)));
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        FrameLayout body = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setFastScrollEnabled(true);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> onItemTap(shown.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> { if (pickMode == 0) toggleSelect(shown.get(pos)); return true; });
        body.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        empty = new TextView(this);
        empty.setTextColor(Ui.DIM);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.dp(this, 30), 0, Ui.dp(this, 30), 0);
        empty.setVisibility(View.GONE);
        body.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loading = new ProgressBar(this);
        loading.setVisibility(View.GONE);
        body.addView(loading, new FrameLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36), Gravity.CENTER));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        pasteBar = new LinearLayout(this);
        pasteBar.setOrientation(LinearLayout.HORIZONTAL);
        pasteBar.setGravity(Gravity.CENTER_VERTICAL);
        pasteBar.setBackgroundColor(Ui.SURFACE);
        pasteBar.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        pasteText = new TextView(this);
        pasteText.setTextColor(Ui.TEXT);
        pasteText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        pasteBar.addView(pasteText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = Ui.button(this, "Clear", Ui.SECONDARY);
        clear.setOnClickListener(v -> { clip.clear(); updatePasteBar(); });
        pasteBar.addView(clear, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 40)));
        TextView paste = Ui.button(this, "Paste here", Ui.PRIMARY);
        paste.setOnClickListener(v -> paste());
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 40));
        pp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        pasteBar.addView(paste, pp);
        pasteBar.setVisibility(View.GONE);
        root.addView(pasteBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (pickMode == 2) {
            TextView use = Ui.button(this, "Select this folder", Ui.PRIMARY);
            use.setOnClickListener(v -> finishPick(guest, path));
            LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
            up.setMargins(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
            root.addView(use, up);
        }

        setContentView(root);
        Ui.applyInsets(root);
    }

    /** Returns a path the Kali side can use: Kali paths as they are, phone storage as /sdcard/... */
    private String guestPathFor(boolean isGuest, String loc) {
        if (isGuest) return loc;
        if (!Prefs.bindSdcard(this)) { toast("Turn on \u201CBind /sdcard\u201D in Settings so Kali can see phone storage"); return null; }
        if (loc.equals(phoneRoot)) return "/sdcard";
        if (loc.startsWith(phoneRoot + "/")) return "/sdcard" + loc.substring(phoneRoot.length());
        toast("Kali can only reach phone storage (/sdcard) and its own files");
        return null;
    }

    private void terminalHere(boolean isGuest, String loc) {
        String g = guestPathFor(isGuest, loc);
        if (g != null) MainActivity.runInTerminal(this, "cd " + ProotLauncher.quote(g));
    }

    private void finishPick(boolean isGuest, String loc) {
        String out = guestPathFor(isGuest, loc);
        if (out == null) return;
        setResult(RESULT_OK, new Intent().putExtra(RESULT_PATH, out));
        finish();
    }

    private TextView chip(String label, final Runnable action, boolean active) {
        TextView t = Ui.key(this, label);
        t.setOnClickListener(v -> action.run());
        Ui.setKeyActive(this, t, active);
        return t;
    }

    private void rebuildChips() {
        chips.removeAllViews();
        if (kaliOk) {
            chips.addView(chip("Kali /", () -> go(true, "/"), guest && path.equals("/")));
            chips.addView(chip("Home", () -> go(true, "/root"), guest && path.equals("/root")));
            chips.addView(chip("Downloads", () -> {
                File d = GuestPaths.toHost(rootfs, "/root/Downloads", true);
                if (d != null && !d.exists()) d.mkdirs();
                go(true, "/root/Downloads");
            }, guest && path.equals("/root/Downloads")));
        }
        chips.addView(chip("Phone", () -> go(false, phoneRoot), !guest && path.equals(phoneRoot)));
        chips.addView(chip("Download", () -> go(false, phoneRoot + "/Download"), !guest && path.equals(phoneRoot + "/Download")));
        for (final String mark : new ArrayList<>(sp.getStringSet(K_MARKS, new HashSet<String>()))) {
            final boolean g = mark.startsWith("g:");
            final String p = mark.substring(2);
            String label = p.equals("/") ? "/" : new File(p).getName();
            TextView t = chip("\u2605 " + label, () -> go(g, p), g == guest && p.equals(path));
            t.setOnLongClickListener(v -> {
                new Dlg(this).setTitle("Remove bookmark?").setMessage(p)
                        .setPositiveButton("Remove", (d, w) -> toggleBookmark(g, p))
                        .setNegativeButton("Cancel", null).show();
                return true;
            });
            chips.addView(t);
        }
    }

    private void toggleBookmark(boolean g, String p) {
        Set<String> marks = new HashSet<>(sp.getStringSet(K_MARKS, new HashSet<String>()));
        String key = (g ? "g:" : "p:") + p;
        if (!marks.remove(key)) marks.add(key);
        sp.edit().putStringSet(K_MARKS, marks).apply();
        rebuildChips();
    }

    private boolean isBookmarked() {
        return sp.getStringSet(K_MARKS, new HashSet<String>()).contains((guest ? "g:" : "p:") + path);
    }

    private void updateCrumbs() {
        crumbs.removeAllViews();
        List<String[]> parts = new ArrayList<>();       // label, target
        if (guest) {
            parts.add(new String[]{"Kali", "/"});
            String acc = "";
            for (String s : path.split("/")) {
                if (s.isEmpty()) continue;
                acc = acc + "/" + s;
                parts.add(new String[]{s, acc});
            }
        } else {
            String base = path.startsWith(phoneRoot) ? phoneRoot : "";
            parts.add(new String[]{base.isEmpty() ? "/" : "Phone", base.isEmpty() ? "/" : base});
            String acc = base;
            for (String s : path.substring(base.length()).split("/")) {
                if (s.isEmpty()) continue;
                acc = acc + "/" + s;
                parts.add(new String[]{s, acc});
            }
        }
        for (int i = 0; i < parts.size(); i++) {
            final String target = parts.get(i)[1];
            if (i > 0) {
                TextView sep = new TextView(this);
                sep.setText("\u203A");
                sep.setTextColor(Ui.DIM);
                sep.setPadding(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
                crumbs.addView(sep);
            }
            TextView t = new TextView(this);
            t.setText(parts.get(i)[0]);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            t.setTextColor(i == parts.size() - 1 ? Ui.TEXT : Ui.ACCENT);
            t.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
            t.setClickable(true);
            t.setOnClickListener(v -> go(guest, target));
            crumbs.addView(t);
        }
        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void updateBars() {
        boolean sel = !selected.isEmpty();
        normalBar.setVisibility(!sel && !searching ? View.VISIBLE : View.GONE);
        searchBar.setVisibility(!sel && searching ? View.VISIBLE : View.GONE);
        selBar.setVisibility(sel ? View.VISIBLE : View.GONE);
        selCount.setText(selected.size() + " selected");
        String name = guest ? (path.equals("/") ? "Kali" : GuestPaths.name(path)) : (path.equals(phoneRoot) ? "Phone storage" : new File(path).getName());
        titleView.setText(name.isEmpty() ? "/" : name);
        int dirs = 0;
        for (FileOps.Item it : shown) if (it.dir) dirs++;
        if (pickMode != 0 && !searching) { subView.setText(pickMode == 1 ? "Tap a file to choose it" : "Open a folder, then tap Select"); return; }
        subView.setText(searching ? shown.size() + " found" : shown.size() + " items" + (dirs > 0 ? "  \u00B7  " + dirs + " folders" : ""));
    }

    private void updatePasteBar() {
        if (clip.isEmpty()) { pasteBar.setVisibility(View.GONE); return; }
        pasteBar.setVisibility(View.VISIBLE);
        pasteText.setText(clip.size() + (clip.size() == 1 ? " item" : " items") + (clipMove ? " to move" : " to copy"));
    }

    // ------------------------------------------------------------------ list rows

    private final class RowView extends LinearLayout {
        final TextView icon, name, sub, check;

        RowView(Context c) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(Ui.dp(c, 14), Ui.dp(c, 7), Ui.dp(c, 12), Ui.dp(c, 7));
            icon = new TextView(c);
            icon.setGravity(Gravity.CENTER);
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
            icon.setBackground(Ui.rounded(c, Ui.SURFACE2, 10));
            addView(icon, new LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(VERTICAL);
            name = new TextView(c);
            name.setTextColor(Ui.TEXT);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            col.addView(name);
            sub = new TextView(c);
            sub.setTextColor(Ui.DIM);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(sub);
            LayoutParams lp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(Ui.dp(c, 12), 0, Ui.dp(c, 8), 0);
            addView(col, lp);
            check = new TextView(c);
            check.setText("\u2713");
            check.setTextColor(Ui.ACCENT);
            check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            check.setVisibility(GONE);
            addView(check);
        }
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public View getView(int pos, View convert, ViewGroup parent) {
            RowView v = convert instanceof RowView ? (RowView) convert : new RowView(FileManagerActivity.this);
            FileOps.Item it = shown.get(pos);
            boolean sel = selected.contains(it.loc);
            v.icon.setText(glyph(it));
            v.name.setText(it.name);
            v.name.setTextColor(it.broken ? Ui.DANGER : Ui.TEXT);
            v.sub.setText(subtitle(it));
            v.check.setVisibility(sel ? View.VISIBLE : View.GONE);
            v.setBackground(Ui.ripple(FileManagerActivity.this, sel ? Ui.SURFACE2 : Color.TRANSPARENT, 0));
            return v;
        }
    }

    private String subtitle(FileOps.Item it) {
        StringBuilder sb = new StringBuilder();
        if (it.rel != null) sb.append(it.rel).append("  \u00B7  ");
        if (it.broken) sb.append("Broken link");
        else {
            if (!it.dir && !it.other) sb.append(Formatter.formatShortFileSize(this, it.size)).append("  \u00B7  ");
            sb.append(dateFormat.format(new Date(it.modified)));
        }
        if (it.link && it.target != null) sb.append("  \u2192  ").append(it.target);
        return sb.toString();
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private static String glyph(FileOps.Item it) {
        if (it.dir) return "\uD83D\uDCC1";
        if (it.broken) return "\u26A0";
        switch (ext(it.name)) {
            case "png": case "jpg": case "jpeg": case "gif": case "webp": case "bmp": case "svg": return "\uD83D\uDDBC";
            case "mp3": case "ogg": case "wav": case "flac": case "m4a": case "opus": return "\uD83C\uDFB5";
            case "mp4": case "mkv": case "webm": case "avi": case "mov": return "\uD83C\uDFAC";
            case "pdf": return "\uD83D\uDCD5";
            case "apk": return "\uD83D\uDCE6";
            case "sh": case "py": case "pl": case "rb": return "\u2699";
            default: break;
        }
        if (FileOps.isArchive(it.name)) return "\uD83D\uDDDC";
        if (it.canExec) return "\u2699";
        return "\uD83D\uDCC4";
    }

    // ------------------------------------------------------------------ navigation and loading

    private Path hostDir(boolean g, String p) throws IOException {
        if (g) {
            File f = GuestPaths.toHost(rootfs, p, true);
            if (f == null) throw new IOException("Invalid path");
            return f.toPath();
        }
        return new File(p).toPath();
    }

    private Path currentDir() throws IOException { return hostDir(guest, path); }

    private void go(boolean toGuest, String p) {
        if (toGuest) {
            if (!kaliOk) { toast("Kali is not installed yet"); return; }
            p = GuestPaths.normalize(p);
        } else {
            if (!hasPhoneAccess()) { askPhoneAccess(p); return; }
            p = new File(p).toPath().normalize().toString();
        }
        guest = toGuest;
        path = p;
        selected.clear();
        if (searching) closeSearch(false);
        sp.edit().putString(K_LAST, (guest ? "g:" : "p:") + path).apply();
        rebuildChips();
        reload(true);
    }

    private void reload(final boolean resetScroll) {
        final int g = ++gen;
        final boolean gst = guest;
        final String p = path;
        final boolean hidden = showHidden;
        loading.setVisibility(View.VISIBLE);
        lister.execute(() -> {
            List<FileOps.Item> res = null;
            String err = null;
            try {
                res = FileOps.list(hostDir(gst, p), p, gst, rootfs, hidden, MAX_ENTRIES);
            } catch (AccessDeniedException e) {
                err = "Permission denied";
            } catch (NoSuchFileException e) {
                err = "This folder does not exist";
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final List<FileOps.Item> fr = res;
            final String fe = err;
            ui.post(() -> {
                if (g != gen || isFinishing()) return;
                loading.setVisibility(View.GONE);
                items = fr == null ? new ArrayList<FileOps.Item>() : fr;
                error = fe;
                selected.retainAll(locs(items));
                applySort(resetScroll);
                if (selectAfterLoad != null) {
                    String want = selectAfterLoad;
                    selectAfterLoad = null;
                    for (int i = 0; i < shown.size(); i++) {
                        if (shown.get(i).loc.equals(want)) {
                            selected.add(want);
                            adapter.notifyDataSetChanged();
                            updateBars();
                            list.setSelection(i);
                            break;
                        }
                    }
                }
            });
        });
    }

    private static Set<String> locs(List<FileOps.Item> l) {
        Set<String> s = new HashSet<>();
        for (FileOps.Item it : l) s.add(it.loc);
        return s;
    }

    private void applySort(boolean resetScroll) {
        shown.clear();
        shown.addAll(items);
        final int by = sortBy;
        final boolean desc = descending;
        Collections.sort(shown, new Comparator<FileOps.Item>() {
            @Override public int compare(FileOps.Item a, FileOps.Item b) {
                if (a.dir != b.dir) return a.dir ? -1 : 1;
                int r;
                switch (by) {
                    case SORT_SIZE: r = Long.compare(a.size, b.size); break;
                    case SORT_DATE: r = Long.compare(a.modified, b.modified); break;
                    case SORT_TYPE: r = ext(a.name).compareTo(ext(b.name)); break;
                    default: r = natural(a.name, b.name); break;
                }
                if (r == 0) r = natural(a.name, b.name);
                return desc ? -r : r;
            }
        });
        adapter.notifyDataSetChanged();
        if (resetScroll) list.setSelection(0);
        empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(error != null ? error : searching ? (pendingQuery.isEmpty() ? "Type to search" : "Nothing found") : "Empty folder");
        updateBars();
        updateCrumbs();
        updatePasteBar();
    }

    /** Name order where "file2" comes before "file10". */
    static int natural(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = a.charAt(i), y = b.charAt(j);
            if (Character.isDigit(x) && Character.isDigit(y)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (na.length() != nb.length()) return na.length() < nb.length() ? -1 : 1;
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int c = Character.compare(Character.toLowerCase(x), Character.toLowerCase(y));
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    // ------------------------------------------------------------------ search

    private void openSearch() {
        searching = true;
        pendingQuery = "";
        searchInput.setText("");
        updateBars();
        searchInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
        items = new ArrayList<>();
        error = null;
        applySort(true);
    }

    private void closeSearch(boolean reloadAfter) {
        searching = false;
        ui.removeCallbacks(searchRun);
        pendingQuery = "";
        searchInput.setText("");
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        updateBars();
        if (reloadAfter) reload(true);
    }

    private void runSearch(final String query) {
        if (!searching) return;
        final int g = ++gen;
        if (query.isEmpty()) {
            loading.setVisibility(View.GONE);
            items = new ArrayList<>();
            error = null;
            applySort(true);
            return;
        }
        final boolean gst = guest;
        final String start = path;
        final String q = query.toLowerCase(Locale.US);
        loading.setVisibility(View.VISIBLE);
        lister.execute(() -> {
            final List<FileOps.Item> found = new ArrayList<>();
            String err = null;
            try {
                ArrayDeque<Object[]> stack = new ArrayDeque<>();
                stack.push(new Object[]{hostDir(gst, start), start});
                int visited = 0;
                while (!stack.isEmpty() && found.size() < MAX_RESULTS && g == gen && visited++ < MAX_ENTRIES) {
                    Object[] top = stack.pop();
                    Path dir = (Path) top[0];
                    String loc = (String) top[1];
                    try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                        for (Path p : ds) {
                            String name = p.getFileName().toString();
                            String childLoc = FileOps.join(loc, name, gst);
                            if (name.toLowerCase(Locale.US).contains(q) && (showHidden || !name.startsWith("."))) {
                                FileOps.Item it = FileOps.describe(p, childLoc, gst, rootfs);
                                it.rel = loc;
                                found.add(it);
                                if (found.size() >= MAX_RESULTS) break;
                            }
                            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) stack.push(new Object[]{p, childLoc});
                        }
                    } catch (IOException ignored) { /* unreadable folder: skip it */ }
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String fe = err;
            ui.post(() -> {
                if (g != gen || isFinishing()) return;
                loading.setVisibility(View.GONE);
                items = found;
                error = fe;
                applySort(true);
            });
        });
    }

    // ------------------------------------------------------------------ selection

    private void toggleSelect(FileOps.Item it) {
        if (!selected.remove(it.loc)) selected.add(it.loc);
        adapter.notifyDataSetChanged();
        updateBars();
    }

    private void clearSelection() {
        selected.clear();
        adapter.notifyDataSetChanged();
        updateBars();
    }

    private List<FileOps.Item> selectedItems() {
        List<FileOps.Item> out = new ArrayList<>();
        for (FileOps.Item it : shown) if (selected.contains(it.loc)) out.add(it);
        return out;
    }

    private void onItemTap(FileOps.Item it) {
        if (pickMode != 0) {
            if (it.dir) { go(guest, it.loc); return; }
            if (pickMode == 1) {
                if (it.broken || it.other) { toast("Not a regular file"); return; }
                finishPick(guest, it.loc);
            }
            return;
        }
        if (!selected.isEmpty()) { toggleSelect(it); return; }
        if (it.dir) { go(guest, it.loc); return; }
        openItem(it);
    }

    // ------------------------------------------------------------------ opening files

    private File resolved(FileOps.Item it) {
        if (guest) {
            File f = GuestPaths.toHost(rootfs, it.loc, true);
            if (f != null) return f;
        }
        return it.path.toFile();
    }

    private boolean looksLikeText(File f) {
        try (InputStream in = Files.newInputStream(f.toPath())) {
            byte[] b = new byte[4096];
            int n = in.read(b);
            for (int i = 0; i < n; i++) if (b[i] == 0) return false;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void openItem(final FileOps.Item it) {
        if (it.broken) { toast("This link points to nothing"); return; }
        if (it.other) { toast("Not a regular file"); return; }
        if (FileOps.isArchive(it.name) && !it.name.toLowerCase(Locale.US).endsWith(".apk")) {
            new Dlg(this).setTitle(it.name)
                    .setItems(new String[]{"Extract here", "Open with\u2026"}, (d, which) -> {
                        if (which == 0) extract(it); else openWith(it);
                    }).show();
            return;
        }
        String e = ext(it.name);
        boolean media = e.matches("png|jpe?g|gif|webp|bmp|svg|mp3|ogg|wav|flac|m4a|opus|mp4|mkv|webm|avi|mov|pdf|apk|docx?|xlsx?|pptx?");
        if (guest && !media && it.size <= 8L * 1024 * 1024 && looksLikeText(resolved(it))) {
            EditorActivity.launch(this, it.loc, null);
        } else {
            openWith(it);
        }
    }

    private void openWith(FileOps.Item it) {
        File f = resolved(it);
        if (!f.isFile()) { toast("Cannot open this item"); return; }
        Uri uri = FileShareProvider.uriFor(f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, FileShareProvider.mimeOf(f.getName()));
        i.setClipData(ClipData.newRawUri("", uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, it.name));
        } catch (ActivityNotFoundException e) {
            toast("No app can open this file");
        }
    }

    private void share(List<FileOps.Item> its) {
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clipData = null;
        for (FileOps.Item it : its) {
            File f = resolved(it);
            if (!f.isFile()) { toast("Only files can be shared. Make an archive of folders first."); return; }
            Uri u = FileShareProvider.uriFor(f);
            uris.add(u);
            if (clipData == null) clipData = ClipData.newRawUri("", u); else clipData.addItem(new ClipData.Item(u));
        }
        Intent i = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
        i.setType("*/*");
        if (uris.size() == 1) i.putExtra(Intent.EXTRA_STREAM, uris.get(0)); else i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        i.setClipData(clipData);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Share"));
    }

    // ------------------------------------------------------------------ menus

    private void showMainMenu(View anchor) {
        TPopup pm = new TPopup(this, anchor);
        android.view.Menu m = pm.getMenu();
        m.add(0, 1, 0, "New file");
        m.add(0, 2, 1, "New folder");
        m.add(0, 3, 2, "Select all");
        m.add(0, 4, 3, "Sort by\u2026");
        m.add(0, 5, 4, "Reverse order").setCheckable(true).setChecked(descending);
        m.add(0, 6, 5, "Show hidden files").setCheckable(true).setChecked(showHidden);
        m.add(0, 7, 6, "Go to path\u2026");
        m.add(0, 8, 7, isBookmarked() ? "Remove bookmark" : "Bookmark this folder");
        m.add(0, 9, 8, "Open terminal here");
        m.add(0, 10, 9, "Copy path");
        m.add(0, 11, 10, "Refresh");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: askNew(false); return true;
                case 2: askNew(true); return true;
                case 3: for (FileOps.Item it : shown) selected.add(it.loc); adapter.notifyDataSetChanged(); updateBars(); return true;
                case 4: askSort(); return true;
                case 5: descending = !descending; sp.edit().putBoolean(K_DESC, descending).apply(); applySort(false); return true;
                case 6: showHidden = !showHidden; sp.edit().putBoolean(K_HIDDEN, showHidden).apply(); reload(false); return true;
                case 7: askGoTo(); return true;
                case 8: toggleBookmark(guest, path); return true;
                case 9: terminalHere(guest, path); return true;
                case 10: copyText(path); return true;
                case 11: reload(false); return true;
                default: return false;
            }
        });
        pm.show();
    }

    private void showSelectionMenu(View anchor) {
        final List<FileOps.Item> sel = selectedItems();
        if (sel.isEmpty()) return;
        TPopup pm = new TPopup(this, anchor);
        android.view.Menu m = pm.getMenu();
        m.add(0, 1, 0, "Select all");
        if (sel.size() == 1) m.add(0, 2, 1, "Rename");
        m.add(0, 3, 2, "Compress to archive\u2026");
        if (sel.size() == 1 && !sel.get(0).dir && FileOps.isArchive(sel.get(0).name)) m.add(0, 4, 3, "Extract here");
        if (sel.size() == 1 && !sel.get(0).dir) m.add(0, 5, 4, "Open with\u2026");
        m.add(0, 6, 5, "Share");
        m.add(0, 7, 6, "Properties");
        m.add(0, 8, 7, "Copy path");
        if (sel.size() == 1 && sel.get(0).dir) m.add(0, 9, 8, "Open terminal here");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: for (FileOps.Item it : shown) selected.add(it.loc); adapter.notifyDataSetChanged(); updateBars(); return true;
                case 2: askRename(sel.get(0)); return true;
                case 3: askArchive(sel); return true;
                case 4: extract(sel.get(0)); return true;
                case 5: openWith(sel.get(0)); return true;
                case 6: share(sel); return true;
                case 7: showProperties(sel); return true;
                case 8: copyText(joinLocs(sel)); return true;
                case 9: terminalHere(guest, sel.get(0).loc); return true;
                default: return false;
            }
        });
        pm.show();
    }

    private static String joinLocs(List<FileOps.Item> its) {
        StringBuilder sb = new StringBuilder();
        for (FileOps.Item it : its) { if (sb.length() > 0) sb.append('\n'); sb.append(it.loc); }
        return sb.toString();
    }

    private void askSort() {
        String[] names = {"Name", "Size", "Date modified", "Type"};
        new Dlg(this).setTitle("Sort by")
                .setSingleChoiceItems(names, sortBy, (d, which) -> {
                    sortBy = which;
                    sp.edit().putInt(K_SORT, which).apply();
                    d.dismiss();
                    applySort(false);
                }).show();
    }

    private void askGoTo() {
        Ui.prompt(this, "Go to path", "/root", path, "Go", text -> {
            String t = text.trim();
            if (t.isEmpty()) return;
            if (!t.startsWith("/")) t = (guest ? GuestPaths.join(path, t) : new File(path, t).getPath());
            if (guest) {
                File host = GuestPaths.toHost(rootfs, t, true);
                if (host == null || !host.isDirectory()) { toast("Not a folder: " + t); return; }
            } else if (!new File(t).isDirectory()) { toast("Not a folder: " + t); return; }
            go(guest, t);
        });
    }

    private void copyText(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("path", text));
        toast("Copied");
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ------------------------------------------------------------------ storage access

    private boolean hasPhoneAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return false;
        return Build.VERSION.SDK_INT > 29 || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void askPhoneAccess(final String wanted) {
        if (Build.VERSION.SDK_INT >= 30) {
            new Dlg(this).setTitle("Allow access to phone storage")
                    .setMessage("To browse and copy files on the phone, allow \"All files access\" for NullShell on the next screen. "
                            + "Kali's own files do not need it.")
                    .setPositiveButton("Open settings", (d, w) -> {
                        pendingPhonePath = wanted;
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    }).setNegativeButton("Cancel", null).show();
        } else {
            pendingPhonePath = wanted;
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_STORAGE && pendingPhonePath != null && hasPhoneAccess()) {
            String p = pendingPhonePath;
            pendingPhonePath = null;
            go(false, p);
        }
    }

    // ------------------------------------------------------------------ background jobs

    private final List<FileJobs.Record> myJobs = new ArrayList<>();
    private boolean resumed;

    private void runJob(final String title, final JobBody body, final Runnable after) {
        askNotificationPermission();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 22), 0);
        final TextView what = new TextView(this);
        what.setTextColor(Ui.TEXT);
        what.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        what.setSingleLine(true);
        what.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        what.setText("Preparing\u2026");
        box.addView(what);
        final ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(true);
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 24)));
        final TextView count = new TextView(this);
        count.setTextColor(Ui.DIM);
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        box.addView(count);

        final AlertDialog dlg = new Dlg(this).setTitle(title).setView(box).setCancelable(false)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Run in background", null).create();

        // The job lives in FileJobs (and its foreground service), not in this screen.
        final FileJobs.Record rec = FileJobs.start(this, title, body::run, r -> {
            myJobs.remove(r);
            r.listener = null;
            if (!isFinishing() && !isDestroyed() && dlg.isShowing()) dlg.dismiss();
            if (resumed) {
                Toast.makeText(FileManagerActivity.this, r.summary(), r.ok() ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            } else {
                FileJobs.notifyDone(getApplicationContext(), r);
            }
            if (!isFinishing() && !isDestroyed() && after != null) after.run();
        });
        myJobs.add(rec);
        final FileOps.Job job = rec.job;

        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> { job.cancelled = true; what.setText("Cancelling\u2026"); });
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            dlg.dismiss();
            toast("Running in the background. Progress is in the notification.");
        });

        final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!dlg.isShowing()) return;
                if (!job.cancelled) what.setText(!rec.started ? "Waiting\u2026" : job.current.isEmpty() ? "Preparing\u2026" : job.current);
                if (job.total > 0) {
                    bar.setIndeterminate(false);
                    bar.setProgress((int) Math.min(1000, job.done * 1000 / job.total));
                } else if (job.itemsTotal > 0) {
                    bar.setIndeterminate(false);
                    bar.setProgress(Math.min(1000, job.items * 1000 / job.itemsTotal));
                }
                String c = job.itemsTotal > 0 ? job.items + " of " + job.itemsTotal + " items" : "";
                if (job.total > 0) c += (c.isEmpty() ? "" : "  \u00B7  ") + Formatter.formatShortFileSize(FileManagerActivity.this, job.done)
                        + " of " + Formatter.formatShortFileSize(FileManagerActivity.this, job.total);
                count.setText(c);
                ui.postDelayed(this, 150);
            }
        };
        ui.post(tick);
    }

    /** Android 13+: without this the progress notification is hidden (the job still runs). Asked once. */
    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || sp.getBoolean("fm_asked_notif", false)) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        sp.edit().putBoolean("fm_asked_notif", true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 63);
    }

    // ------------------------------------------------------------------ copy, move, delete

    private void toClipboard(boolean move) {
        List<FileOps.Item> sel = selectedItems();
        if (sel.isEmpty()) return;
        clip.clear();
        for (FileOps.Item it : sel) clip.add(it.path);
        clipMove = move;
        clearSelection();
        updatePasteBar();
        toast(move ? "Open the destination folder and tap Paste" : "Copied. Open the destination folder and tap Paste");
    }

    private void paste() {
        if (clip.isEmpty()) return;
        if (searching) { toast("Leave the search first"); return; }
        final Path dest;
        try { dest = currentDir(); } catch (IOException e) { toast("Cannot use this folder"); return; }
        int conflicts = 0;
        for (Path p : clip) if (Files.exists(dest.resolve(p.getFileName().toString()), LinkOption.NOFOLLOW_LINKS)) conflicts++;
        if (conflicts == 0) { transfer(dest, FileOps.KEEP_BOTH); return; }
        new Dlg(this).setTitle(conflicts + (conflicts == 1 ? " item already exists" : " items already exist"))
                .setItems(new String[]{"Replace", "Skip", "Keep both"}, (d, which) -> {
                    int policy = which == 0 ? FileOps.REPLACE : which == 1 ? FileOps.SKIP : FileOps.KEEP_BOTH;
                    transfer(dest, policy);
                }).setNegativeButton("Cancel", null).show();
    }

    private void transfer(final Path dest, final int policy) {
        final List<Path> sources = new ArrayList<>(clip);
        final boolean move = clipMove;
        runJob(move ? "Moving" : "Copying", job -> {
            job.itemsTotal = sources.size();
            if (!move) job.total = FileOps.sizeOf(job, sources);
            FileOps.transfer(job, sources, dest, policy, move);
        }, () -> {
            if (move) clip.clear();
            updatePasteBar();
            reload(false);
        });
    }

    private void askDelete() {
        final List<FileOps.Item> sel = selectedItems();
        if (sel.isEmpty()) return;
        String names = sel.size() == 1 ? "\"" + sel.get(0).name + "\"" : sel.size() + " items";
        boolean system = guest && GuestPaths.normalize(path).split("/").length <= 2;
        new Dlg(this).setTitle("Delete " + names + "?")
                .setMessage((system ? "This is a system folder of Kali; deleting from it can break the system.\n\n" : "") + "This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    final List<Path> paths = new ArrayList<>();
                    for (FileOps.Item it : sel) paths.add(it.path);
                    runJob("Deleting", job -> {
                        job.itemsTotal = paths.size();
                        for (Path p : paths) {
                            try { FileOps.deleteTree(job, p); }
                            catch (FileOps.Cancelled c) { throw c; }
                            catch (IOException e) { job.fail(p.getFileName().toString(), e); }
                            job.items++;
                        }
                    }, () -> { clip.removeAll(paths); selected.clear(); updatePasteBar(); reload(false); });
                }).setNegativeButton("Cancel", null).show();
    }

    private void askRename(final FileOps.Item it) {
        Ui.prompt(this, "Rename", "Name", it.name, "Rename", text -> {
            String n = text.trim();
            if (n.equals(it.name)) return;
            try {
                FileOps.rename(it.path, n);
                selected.clear();
                reload(false);
            } catch (IOException e) {
                toast(e.getMessage());
            }
        });
    }

    private void askNew(final boolean folder) {
        Ui.prompt(this, folder ? "New folder" : "New file", "Name", "", "Create", text -> {
            try {
                Path dir = currentDir();
                String n = text.trim();
                if (folder) FileOps.createDir(dir, n); else FileOps.createFile(dir, n);
                reload(false);
            } catch (IOException e) {
                toast(e instanceof java.nio.file.FileAlreadyExistsException ? "Already exists" : String.valueOf(e.getMessage()));
            }
        });
    }

    // ------------------------------------------------------------------ archives

    private void askArchive(final List<FileOps.Item> sel) {
        final List<Path> sources = new ArrayList<>();
        for (FileOps.Item it : sel) sources.add(it.path);
        String base = sel.size() == 1 ? FileOps.archiveBase(sel.get(0).name) : (path.equals("/") ? "archive" : new File(path).getName());
        if (sel.size() == 1 && sel.get(0).dir) base = sel.get(0).name;
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(Ui.TEXT);
        input.setText(base);
        input.setSelection(base.length());
        Ui.tint(input);
        final RadioGroup group = new RadioGroup(this);
        RadioButton zip = new RadioButton(this);
        zip.setText("ZIP");
        zip.setTextColor(Ui.TEXT);
        zip.setId(View.generateViewId());
        RadioButton tar = new RadioButton(this);
        tar.setText("TAR.GZ  (keeps Linux permissions)");
        tar.setTextColor(Ui.TEXT);
        tar.setId(View.generateViewId());
        group.addView(zip);
        group.addView(tar);
        group.check(guest ? tar.getId() : zip.getId());
        final int tarId = tar.getId();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(input);
        box.addView(group);
        new Dlg(this).setTitle("Compress to archive").setView(box)
                .setPositiveButton("Create", (d, w) -> {
                    final boolean tarGz = group.getCheckedRadioButtonId() == tarId;
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.contains("/")) { toast("Invalid name"); return; }
                    try {
                        Path dir = currentDir();
                        final Path out = dir.resolve(FileOps.uniqueName(dir, name + (tarGz ? ".tar.gz" : ".zip")));
                        runJob("Compressing", job -> FileOps.createArchive(job, sources, out, tarGz), () -> { selected.clear(); reload(false); });
                    } catch (IOException e) {
                        toast(e.getMessage());
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void extract(final FileOps.Item it) {
        try {
            final Path dir = currentDir();
            final Path archive = resolved(it).toPath();
            boolean single = !it.name.toLowerCase(Locale.US).matches(".*(\\.zip|\\.jar|\\.apk|\\.tar|\\.tar\\.gz|\\.tgz|\\.tar\\.xz|\\.txz|\\.tar\\.bz2|\\.tbz2)$");
            final Path dest = single ? dir : dir.resolve(FileOps.uniqueName(dir, FileOps.archiveBase(it.name)));
            runJob("Extracting", job -> {
                if (!single) Files.createDirectories(dest);
                FileOps.extract(job, archive, dest);
            }, () -> { selected.clear(); reload(false); });
        } catch (IOException e) {
            toast(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ properties and permissions

    private TextView propRow(LinearLayout box, String label, String value) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(Ui.DIM);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        l.setPadding(0, Ui.dp(this, 10), 0, 0);
        box.addView(l);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(Ui.TEXT);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        v.setTextIsSelectable(true);
        box.addView(v);
        return v;
    }

    private void showProperties(final List<FileOps.Item> sel) {
        final FileOps.Item it = sel.get(0);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 4), Ui.dp(this, 22), 0);
        final TextView sizeView;
        if (sel.size() > 1) {
            propRow(box, "Selection", sel.size() + " items");
            sizeView = propRow(box, "Total size", "Calculating\u2026");
        } else {
            propRow(box, "Name", it.name);
            propRow(box, "Location", it.loc);
            String type = it.dir ? (it.link ? "Link to a folder" : "Folder") : it.link ? "Symbolic link" : FileShareProvider.mimeOf(it.name);
            propRow(box, "Type", type);
            if (it.link && it.target != null) propRow(box, "Link target", it.target);
            sizeView = propRow(box, "Size", it.dir ? "Calculating\u2026" : Formatter.formatFileSize(this, it.size) + "  (" + it.size + " bytes)");
            propRow(box, "Modified", dateFormat.format(new Date(it.modified)));
            int mode = FileOps.modeOf(resolved(it).toPath(), it.dir);
            propRow(box, "Permissions", FileOps.modeString(mode) + "  (" + Integer.toOctalString(mode) + ")");
        }
        final FileOps.Job job = new FileOps.Job();
        boolean needSize = sel.size() > 1 || it.dir;
        if (needSize) {
            final List<Path> paths = new ArrayList<>();
            for (FileOps.Item s : sel) paths.add(resolvedPath(s));
            worker.execute(() -> {
                String text;
                try {
                    long total = FileOps.sizeOf(job, paths);
                    text = Formatter.formatFileSize(this, total) + "  (" + total + " bytes)";
                } catch (IOException e) {
                    text = e instanceof FileOps.Cancelled ? "" : "Unavailable";
                }
                final String t = text;
                ui.post(() -> sizeView.setText(t));
            });
        }
        AlertDialog.Builder b = new Dlg(this).setTitle("Properties").setView(box)
                .setPositiveButton("Close", null)
                .setOnDismissListener(d -> job.cancelled = true);
        if (sel.size() == 1 && !it.broken) b.setNeutralButton("Permissions\u2026", (d, w) -> askPermissions(it));
        b.show();
    }

    private Path resolvedPath(FileOps.Item it) { return it.link ? resolved(it).toPath() : it.path; }

    private void askPermissions(final FileOps.Item it) {
        final Path p = resolved(it).toPath();
        int mode = FileOps.modeOf(p, it.dir);
        String[] who = {"Owner", "Group", "Others"};
        String[] what = {"Read", "Write", "Execute"};
        final CheckBox[] boxes = new CheckBox[9];
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 22), Ui.dp(this, 8), Ui.dp(this, 22), 0);
        for (int r = 0; r < 3; r++) {
            TextView h = new TextView(this);
            h.setText(who[r]);
            h.setTextColor(Ui.ACCENT);
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            h.setPadding(0, Ui.dp(this, 8), 0, 0);
            col.addView(h);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                int idx = r * 3 + c;
                CheckBox cb = new CheckBox(this);
                cb.setText(what[c]);
                cb.setTextColor(Ui.TEXT);
                cb.setChecked((mode & (0400 >> idx)) != 0);
                boxes[idx] = cb;
                row.addView(cb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            col.addView(row);
        }
        new Dlg(this).setTitle("Permissions").setView(col)
                .setPositiveButton("Apply", (d, w) -> {
                    int m = 0;
                    for (int i = 0; i < 9; i++) if (boxes[i].isChecked()) m |= 0400 >> i;
                    try {
                        FileOps.chmod(p, m);
                        toast("Now " + Integer.toOctalString(m));
                    } catch (Exception e) {
                        toast("Cannot change permissions here");
                    }
                }).setNegativeButton("Cancel", null).show();
    }
}
