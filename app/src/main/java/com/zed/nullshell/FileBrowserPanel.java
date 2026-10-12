package com.zed.nullshell;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/** Folder browser for the Kali filesystem, used as the editor's side panel. Paths shown are guest paths. */
final class FileBrowserPanel extends LinearLayout {
    interface Host {
        void onOpenFile(String guestPath);
        boolean isOpen(String guestPath);
        void onDeleted(String guestPath);
        void onClose();
        default void onNeedPhoneAccess() { }
    }

    private static final int MAX_ENTRIES = 2000;

    private final File rootfs;
    private final Host host;
    private final TextView pathView;
    private final LinearLayout list;
    private final ScrollView scroll;
    private String dir = "/root";

    FileBrowserPanel(Context c, File rootfs, Host host) {
        super(c);
        this.rootfs = rootfs;
        this.host = host;
        setOrientation(VERTICAL);
        setBackgroundColor(Ui.SURFACE);
        setClickable(true);

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(c, 16), Ui.dp(c, 2), Ui.dp(c, 4), 0);
        TextView title = new TextView(c);
        title.setText("Files");
        title.setTextColor(Ui.TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView newFile = Ui.iconButton(c, "+");
        newFile.setOnClickListener(v -> askNew(false));
        head.addView(newFile);
        TextView menu = Ui.iconButton(c, "\u22EE");
        menu.setOnClickListener(v -> {
            TPopup pm = new TPopup(c, v);
            pm.getMenu().add(0, 1, 0, "New file");
            pm.getMenu().add(0, 2, 1, "New folder");
            pm.getMenu().add(0, 3, 2, "Go to /root");
            pm.getMenu().add(0, 4, 3, "Go to /");
            pm.getMenu().add(0, 5, 4, "Go to /sdcard");
            pm.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 1: askNew(false); return true;
                    case 2: askNew(true); return true;
                    case 3: showDir("/root"); return true;
                    case 4: showDir("/"); return true;
                    case 5: showDir("/sdcard"); return true;
                    default: return false;
                }
            });
            pm.show();
        });
        head.addView(menu);
        addView(head, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 48)));

        pathView = new TextView(c);
        pathView.setTypeface(Typeface.MONOSPACE);
        pathView.setTextColor(Ui.ACCENT);
        pathView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pathView.setSingleLine(true);
        pathView.setEllipsize(TextUtils.TruncateAt.START);
        pathView.setPadding(Ui.dp(c, 16), Ui.dp(c, 2), Ui.dp(c, 16), Ui.dp(c, 8));
        addView(pathView);

        scroll = new ScrollView(c);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list = new LinearLayout(c);
        list.setOrientation(VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    String currentDir() { return dir; }

    private static boolean isPhonePath(String g) { return g.equals("/sdcard") || g.startsWith("/sdcard/"); }

    /**
     * The real folder or file behind a Kali path. Inside Kali /sdcard is the phone's storage (a bind mount), but on disk the
     * Kali side only has an empty stub, so those paths are read from the phone's storage directly.
     */
    private File hostFor(String guest, boolean followLast) {
        String g = GuestPaths.normalize(guest);
        if (isPhonePath(g) && Prefs.bindSdcard(getContext())) {
            File base = Environment.getExternalStorageDirectory();
            return g.equals("/sdcard") ? base : new File(base, g.substring(8));
        }
        return GuestPaths.toHost(rootfs, g, followLast);
    }

    void showDir(String guestDir) {
        String d = GuestPaths.normalize(guestDir);
        File dirFile = hostFor(d, true);
        if ((dirFile == null || !dirFile.isDirectory()) && !(isPhonePath(d) && Prefs.bindSdcard(getContext()))) { d = "/root"; dirFile = hostFor(d, true); }
        dir = d;
        pathView.setText(d);
        list.removeAllViews();

        if (!d.equals("/")) list.addView(row("..", true, -1, () -> showDir(GuestPaths.parent(dir)), null));

        if (isPhonePath(d) && !Prefs.bindSdcard(getContext())) {
            list.addView(note("Phone storage isn't linked to Kali. Turn on \u201CBind /sdcard\u201D in Settings."));
            return;
        }
        File[] kids = dirFile == null ? null : dirFile.listFiles();
        if (kids == null) {
            if (isPhonePath(d)) {
                list.addView(note("Can't read phone storage yet."));
                list.addView(row("Allow access to phone storage", false, -1, host::onNeedPhoneAccess, null));
            } else {
                list.addView(note("Cannot read this folder"));
            }
            return;
        }
        Arrays.sort(kids, (a, b) -> {
            boolean da = a.isDirectory(), db = b.isDirectory();
            if (da != db) return da ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        int shown = 0;
        for (File k : kids) {
            if (shown++ >= MAX_ENTRIES) { list.addView(note("Showing the first " + MAX_ENTRIES + " entries")); break; }
            final String path = GuestPaths.join(d, k.getName());
            final boolean isDir = k.isDirectory();
            list.addView(row(k.getName(), isDir, isDir ? -1 : k.length(),
                    () -> { if (isDir) showDir(path); else { openAndClose(path); } },
                    v -> menuFor(v, path, isDir)));
        }
        if (kids.length == 0) list.addView(note("Empty folder"));
        scroll.scrollTo(0, 0);
    }

    private void openAndClose(String path) { host.onOpenFile(path); host.onClose(); }

    private View note(String s) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextColor(Ui.DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(Ui.dp(getContext(), 16), Ui.dp(getContext(), 12), Ui.dp(getContext(), 16), 0);
        return t;
    }

    private interface Menu { void show(View anchor); }

    private View row(String name, boolean isDir, long size, Runnable tap, Menu longPress) {
        Context c = getContext();
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(c, 16), Ui.dp(c, 11), Ui.dp(c, 12), Ui.dp(c, 11));
        r.setClickable(true);
        r.setBackground(Ui.ripple(c, 0, 0));
        TextView t = new TextView(c);
        t.setText(isDir ? name + "/" : name);
        t.setTextColor(isDir ? Ui.ACCENT : Ui.TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        r.addView(t, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (size >= 0) {
            TextView s = new TextView(c);
            s.setText(RootfsManager.formatBytes(size));
            s.setTextColor(Ui.DIM);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            r.addView(s);
        }
        r.setOnClickListener(v -> tap.run());
        if (longPress != null) r.setOnLongClickListener(v -> { longPress.show(v); return true; });
        return r;
    }

    private void menuFor(View anchor, String path, boolean isDir) {
        TPopup pm = new TPopup(getContext(), anchor);
        if (!isDir) pm.getMenu().add(0, 1, 0, "Open");
        pm.getMenu().add(0, 2, 1, "Rename");
        pm.getMenu().add(0, 3, 2, "Delete");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: openAndClose(path); return true;
                case 2: askRename(path); return true;
                case 3: askDelete(path, isDir); return true;
                default: return false;
            }
        });
        pm.show();
    }

    private void askNew(boolean folder) {
        Ui.prompt(getContext(), folder ? "New folder" : "New file", folder ? "folder name" : "file name", "", "Create", name -> {
            name = name.trim();
            if (name.isEmpty() || name.contains("/")) { toast("Use a plain name without slashes"); return; }
            File parentDir = hostFor(dir, true);
            if (parentDir == null) return;
            File f = new File(parentDir, name);
            if (f.exists()) { toast("Already exists"); return; }
            try {
                if (folder) { if (!f.mkdirs()) throw new IOException("mkdir failed"); }
                else if (!f.createNewFile()) throw new IOException("create failed");
            } catch (IOException e) { toast("Could not create: " + e.getMessage()); return; }
            showDir(dir);
            if (!folder) { host.onOpenFile(GuestPaths.join(dir, name)); host.onClose(); }
        });
    }

    private void askRename(String path) {
        if (host.isOpen(path)) { toast("Close the file in the editor first"); return; }
        Ui.prompt(getContext(), "Rename", "new name", GuestPaths.name(path), "Rename", name -> {
            name = name.trim();
            if (name.isEmpty() || name.contains("/")) { toast("Use a plain name without slashes"); return; }
            File from = hostFor(path, false);
            File to = from == null ? null : new File(from.getParentFile(), name);
            if (from == null || to.exists() || !from.renameTo(to)) { toast("Rename failed"); return; }
            showDir(dir);
        });
    }

    private void askDelete(String path, boolean isDir) {
        new Dlg(getContext())
                .setTitle("Delete " + GuestPaths.name(path) + (isDir ? " and everything in it?" : "?"))
                .setPositiveButton("Delete", (d, w) -> {
                    File f = hostFor(path, false);
                    if (f == null) return;
                    host.onDeleted(path);
                    RootfsManager.deleteTree(f);
                    showDir(dir);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void toast(String s) { Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show(); }
}
