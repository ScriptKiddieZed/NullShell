package com.zed.nullshell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.graphics.Typeface;
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
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.event.SelectionChangeEvent;
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.EditorSearcher;
import io.github.rosemoe.sora.widget.SelectionMovement;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Code editor on top of sora-editor. Files are edited in place inside the Kali filesystem; language
 * servers run in Kali as well (see {@link LspManager}). One CodeEditor view per open file.
 */
public class EditorActivity extends Activity implements LspManager.Listener, FileBrowserPanel.Host {
    private static final String EXTRA_PATH = "path", EXTRA_TOKEN = "token";
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    /** Opens the editor on a guest path (null path = just the editor). Token: a file the terminal waits on. */
    static void launch(Context c, String guestPath, String waitToken) {
        Intent i = new Intent(c, EditorActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (guestPath != null) i.putExtra(EXTRA_PATH, guestPath);
        if (waitToken != null) i.putExtra(EXTRA_TOKEN, waitToken);
        c.startActivity(i);
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "editor-io");
        t.setDaemon(true);
        return t;
    });
    private final List<EditorBuffer> buffers = new ArrayList<>();
    private EditorBuffer current;
    private File rootfs;
    private LspManager lsp;

    private FrameLayout editorFrame, drawerSlot;
    private LinearLayout tabBar, keyRow, findBar, empty;
    private HorizontalScrollView tabScroll;
    private TextView titleView, saveBtn, statusPos, statusLsp, findCount;
    private EditText findInput, replaceInput;
    private View scrim;
    private FileBrowserPanel files;
    private boolean drawerOpen, caseSensitive, regex;

    private final Runnable autosave = this::saveAllQuiet;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            refreshStatus();
            ui.postDelayed(this, 2000);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        rootfs = RootfsManager.dir(this);
        EditorSetup.ensure(this);
        EditorSetup.applyTheme(this);
        lsp = new LspManager(this, rootfs, this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.addView(buildTopBar());
        root.addView(divider());
        root.addView(buildTabStrip());
        buildFindBar();
        root.addView(findBar);

        FrameLayout body = new FrameLayout(this);
        editorFrame = new FrameLayout(this);
        body.addView(editorFrame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildEmptyState();
        body.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim = new View(this);
        scrim.setBackgroundColor(Ui.SCRIM);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> closeDrawer());
        body.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        files = new FileBrowserPanel(this, rootfs, this);
        drawerSlot = new FrameLayout(this);
        drawerSlot.setElevation(Ui.dp(this, 8));
        drawerSlot.setVisibility(View.GONE);
        drawerSlot.addView(files, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(Ui.dp(this, 290), ViewGroup.LayoutParams.MATCH_PARENT);
        dlp.gravity = Gravity.START;
        body.addView(drawerSlot, dlp);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(divider());
        root.addView(buildKeyRow());
        root.addView(buildStatusBar());

        setContentView(root);
        Ui.applyInsets(root);

        handleIntent(getIntent());
        refreshUi();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** Opens the file manager on the current file's folder with the file selected. */
    private void showInFileManager() {
        if (current == null) { FileManagerActivity.open(this, null); return; }
        if (current.external) FileManagerActivity.openPhone(this, current.hostFile.getParent(), current.hostFile.getPath());
        else FileManagerActivity.open(this, GuestPaths.parent(current.guestPath), current.guestPath);
    }

    private void handleIntent(Intent i) {
        if (ACTION_OPEN_URI.equals(i.getAction())) {
            i.setAction(null);                         // do not reopen it if the intent is delivered again
            openUri(i.getData(), i.getStringExtra(Intent.EXTRA_TEXT));
            return;
        }
        String path = i.getStringExtra(EXTRA_PATH);
        String token = i.getStringExtra(EXTRA_TOKEN);
        if (path != null) {
            openFile(path, token);
        } else if (buffers.isEmpty()) {
            String last = Prefs.sp(this).getString(Prefs.ED_LAST, "/root");
            files.showDir(last);
            openDrawer();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        EditorSetup.applyTheme(this);
        for (EditorBuffer b : buffers) applySettings(b);
        keyRow.setVisibility(Prefs.edSymbols(this) ? View.VISIBLE : View.GONE);
        checkDiskChanges();
        ui.removeCallbacks(poll);
        ui.post(poll);
        for (EditorBuffer b : new ArrayList<>(buffers)) {
            if (b.lspSuspended && b.lsp == null) { b.lspSuspended = false; retryLsp(b); }   // servers stopped while in the background
        }
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        lsp.onTrim(level, buffers);
        if (level == 15 /* TRIM_MEMORY_RUNNING_CRITICAL */ || level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) unloadBackground();
        if (current != null) refreshStatus();
    }

    @Override protected void onPause() {
        ui.removeCallbacks(poll);
        if (Prefs.edAutosave(this)) saveAllQuiet();
        super.onPause();
    }

    @Override protected void onDestroy() {
        releaseWaiters();
        for (EditorBuffer b : new ArrayList<>(buffers)) {
            lsp.detach(b);
            if (b.editor != null) b.editor.release();
        }
        buffers.clear();
        lsp.dispose();
        io.shutdown();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (drawerOpen) { closeDrawer(); return; }
        if (findBar.getVisibility() == View.VISIBLE) { closeFind(); return; }
        boolean anyModified = false;
        for (EditorBuffer b : buffers) anyModified |= b.modified;
        if (anyModified && !Prefs.edAutosave(this)) {
            new Dlg(this).setTitle("Unsaved changes")
                    .setPositiveButton("Save all", (d, w) -> { saveAllQuiet(); finish(); })
                    .setNegativeButton("Discard", (d, w) -> finish())
                    .setNeutralButton("Cancel", null).show();
        } else {
            if (anyModified) saveAllQuiet();
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------ opening and closing files

    private EditorBuffer find(String guestPath) {
        for (EditorBuffer b : buffers) if (b.guestPath.equals(guestPath)) return b;
        return null;
    }

    void openFile(String guestPath, String waitToken) {
        final String path = GuestPaths.normalize(guestPath);
        EditorBuffer existing = find(path);
        if (existing != null) {
            if (waitToken != null) existing.waitToken = waitToken;
            show(existing);
            return;
        }
        final File host = GuestPaths.toHost(rootfs, path, true);
        if (host == null) { toast("Cannot open " + path); return; }
        // /sdcard is PRoot's bind of phone storage; inside the rootfs folder it is empty, so map it back
        File phone = phoneFileFor(host);
        if (phone != null) {
            if (phone.isDirectory()) { FileManagerActivity.openPhone(this, phone.getPath(), null); return; }
            if (!hasPhoneAccess()) { askPhoneAccess(); return; }
            openBuffer("ext:" + phone.getPath(), phone, waitToken, true, null);
            return;
        }
        if (host.isDirectory()) { files.showDir(path); openDrawer(); return; }
        openBuffer(path, host, waitToken, false, null);
    }

    /** The phone-storage file behind a Kali path under /sdcard (when it is bound), else null. */
    private File phoneFileFor(File host) {
        if (!Prefs.bindSdcard(this)) return null;
        String g = GuestPaths.toGuest(rootfs, host);
        if (g == null) return null;
        if (g.equals("/sdcard")) return Environment.getExternalStorageDirectory();
        if (g.startsWith("/sdcard/")) return new File(Environment.getExternalStorageDirectory(), g.substring(8));
        return null;
    }

    private boolean hasPhoneAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void askPhoneAccess() {
        toast("Allow access to phone storage first");
        try {
            if (Build.VERSION.SDK_INT >= 30)
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            else requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 62);
        } catch (Exception ignored) { }
    }

    /** A file on the phone's storage, outside Kali. It is identified as "ext:<path>" and gets no language server. */
    private void openExternal(File host) {
        if (host.isDirectory()) { toast("That is a folder"); return; }
        openBuffer("ext:" + host.getPath(), host, null, true, null);
    }

    private void openBuffer(final String id, final File host, String waitToken, final boolean external, final Uri source) {
        EditorBuffer existing = find(id);
        if (existing != null) {
            if (waitToken != null) existing.waitToken = waitToken;
            show(existing);
            return;
        }
        if (host.exists() && host.length() > MAX_BYTES) { toast("File is larger than 8 MB"); return; }
        File parent = host.getParentFile();
        if (!host.exists() && (parent == null || !parent.isDirectory())) { toast("Folder does not exist"); return; }

        final String token = waitToken;
        final boolean largeFile = host.exists() && host.length() > Prefs.edLargeBytes(this);
        io.execute(() -> {
            String text = "";
            long modified = 0;
            try {
                if (host.exists()) {
                    byte[] bytes = Files.readAllBytes(host.toPath());
                    for (int i = 0, n = Math.min(bytes.length, 8192); i < n; i++) {
                        if (bytes[i] == 0) { ui.post(() -> toast("This looks like a binary file")); return; }
                    }
                    text = new String(bytes, StandardCharsets.UTF_8);
                    modified = host.lastModified();
                }
            } catch (IOException e) {
                ui.post(() -> toast("Cannot read file: " + e.getMessage()));
                return;
            }
            final String content = text;
            final long mtime = modified;
            ui.post(() -> {
                if (isFinishing() || find(id) != null) return;
                EditorBuffer b = new EditorBuffer(id, host, LanguageSupport.forFile(host.getName()));
                b.diskModified = mtime;
                b.waitToken = token;
                b.external = external;
                b.sourceUri = source;
                b.crlf = content.contains("\r\n");
                b.large = largeFile;
                createEditorView(b, content);
                buffers.add(b);
                if (!external) Prefs.sp(this).edit().putString(Prefs.ED_LAST, GuestPaths.parent(id)).apply();
                show(b);
                if (b.large) toast("Large file: highlighting, language server and wrapping are off");
            });
        });
    }

    // ------------------------------------------------------------------ documents from other apps

    static final String ACTION_OPEN_URI = "com.zed.nullshell.OPEN_URI";
    private static int importCounter;

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) return false;
        return Build.VERSION.SDK_INT > 29 || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isInside(File f, File dir) {
        return f.getPath().startsWith(dir.getPath() + File.separator);
    }

    /** The real file behind a URI when it is inside Kali or on accessible phone storage, otherwise null. */
    private File directFile(Uri uri) {
        try {
            File f = null;
            if ("file".equals(uri.getScheme())) {
                f = uri.getPath() == null ? null : new File(uri.getPath());
            } else if ("content".equals(uri.getScheme())) {
                String auth = uri.getAuthority();
                if (FileShareProvider.AUTHORITY.equals(auth)) {
                    String p = uri.getQueryParameter("p");
                    if (p != null) f = new File(p);
                } else if ("com.android.externalstorage.documents".equals(auth)) {
                    String doc = DocumentsContract.getDocumentId(uri);
                    int colon = doc.indexOf(':');
                    if (colon > 0 && doc.substring(0, colon).equals("primary")) f = new File(Environment.getExternalStorageDirectory(), doc.substring(colon + 1));
                }
            }
            if (f == null) return null;
            File canon = f.getCanonicalFile();
            if (isInside(canon, rootfs.getCanonicalFile())) return canon;
            if (isInside(canon, Environment.getExternalStorageDirectory().getCanonicalFile()) && hasStorageAccess()) return canon;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void openHostFile(File canon) {
        String guest = GuestPaths.toGuest(rootfs, canon);
        if (guest != null) openFile(guest, null); else openExternal(canon);
    }

    private File newImportFile(String name) throws IOException {
        File base = new File(rootfs, "tmp/ns-open");
        File[] old = base.listFiles();
        if (old != null) {
            long cutoff = System.currentTimeMillis() - 3L * 24 * 3600 * 1000;
            for (File d : old) {
                if (d.lastModified() < cutoff) { try { FileOps.deleteTree(new FileOps.Job(), d.toPath()); } catch (IOException ignored) { } }
            }
        }
        File dir = new File(base, System.currentTimeMillis() + "-" + (importCounter++));
        if (!dir.mkdirs()) throw new IOException("Cannot create a folder in Kali's /tmp");
        StringBuilder cleaned = new StringBuilder();
        for (char ch : name.toCharArray()) cleaned.append("\\/:*?\"<>|".indexOf(ch) >= 0 || Character.isISOControl(ch) ? '_' : ch);
        String safe = cleaned.toString();
        while (safe.startsWith(".")) safe = safe.substring(1);
        return new File(dir, safe.isEmpty() ? "document.txt" : safe);
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) { }
        if (name == null || name.trim().isEmpty()) name = uri.getLastPathSegment();
        if (name == null || name.trim().isEmpty()) name = "document";
        String type = getContentResolver().getType(uri);
        if (!name.contains(".") && type != null && type.startsWith("text/")) name += ".txt";
        return name;
    }

    /** Opens a document another app handed over: in place when it is a real file we can reach, else as a copy that is written back on save. */
    void openUri(final Uri uri, final String sharedText) {
        io.execute(() -> {
            try {
                if (uri == null) {
                    if (sharedText == null || sharedText.isEmpty()) return;
                    final File out = newImportFile("shared-text.txt");
                    Files.write(out.toPath(), sharedText.getBytes(StandardCharsets.UTF_8));
                    ui.post(() -> openImported(out, null));
                    return;
                }
                final File direct = directFile(uri);
                if (direct != null) { ui.post(() -> openHostFile(direct)); return; }
                if ("file".equals(uri.getScheme())) {
                    ui.post(() -> toast("Cannot open this file. For files on the phone, allow \"All files access\" in the file manager first."));
                    return;
                }
                final File out = newImportFile(displayName(uri));
                long total = 0;
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream os = new FileOutputStream(out)) {
                    if (in == null) throw new IOException("The document cannot be opened");
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_BYTES) throw new IOException("File is larger than 8 MB");
                        os.write(buf, 0, n);
                    }
                } catch (IOException e) {
                    out.delete();
                    throw e;
                }
                final boolean canWrite = checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(),
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED;
                ui.post(() -> openImported(out, canWrite ? uri : null));
            } catch (Exception e) {
                ui.post(() -> toast("Cannot open the document: " + e.getMessage()));
            }
        });
    }

    private void openImported(File out, Uri source) {
        String guest = GuestPaths.toGuest(rootfs, out);
        if (guest == null) { toast("Cannot open the document"); return; }
        openBuffer(guest, out, null, false, source);
        if (source == null) toast("Opened a copy in Kali: " + guest);
    }

    /** After a save: also update the document in the app it came from. */
    private void writeBack(EditorBuffer b, String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        try {
            OutputStream out;
            try {
                out = getContentResolver().openOutputStream(b.sourceUri, "wt");
            } catch (RuntimeException | IOException e) {
                out = getContentResolver().openOutputStream(b.sourceUri, "w");
            }
            if (out == null) throw new IOException("not writable");
            try { out.write(data); } finally { out.close(); }
        } catch (IOException | RuntimeException e) {
            toast("The original document could not be updated. The copy in Kali was saved: " + b.guestPath);
            b.sourceUri = null;
        }
    }

    private void createEditorView(final EditorBuffer b, String text) {
        final CodeEditor ed = new CodeEditor(this);
        b.editor = ed;
        ed.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ed.setTypefaceText(EditorSetup.mono(this));
        ed.setTypefaceLineNumber(EditorSetup.mono(this));
        ed.setColorScheme(TextMateColorScheme.create(ThemeRegistry.getInstance()));
        ed.setText(text);
        ed.setVisibility(View.GONE);
        applySettings(b);

        ed.subscribeEvent(ContentChangeEvent.class, (event, unsubscribe) -> {
            if (event.getAction() != ContentChangeEvent.ACTION_SET_NEW_TEXT && !b.modified) {
                b.modified = true;
                refreshUi();
            }
            if (b.modified && Prefs.edAutosave(this)) {
                ui.removeCallbacks(autosave);
                ui.postDelayed(autosave, 2500);
            }
        });
        ed.subscribeEvent(SelectionChangeEvent.class, (event, unsubscribe) -> {
            b.cursorLine = ed.getCursor().getLeftLine();
            b.cursorColumn = ed.getCursor().getLeftColumn();
            if (b == current) refreshStatus();
        });
        editorFrame.addView(ed);

        // The language is set here when no language server will bind the editor, otherwise by LspManager.attach.
        TextMateLanguage language = null;
        if (!b.large && b.lang != null && b.lang.scope != null) {
            try {
                language = TextMateLanguage.create(b.lang.scope, true);
                language.useTab(!Prefs.edSpaces(this));
            } catch (RuntimeException ignored) { language = null; }
        }
        boolean wantsServer = Prefs.edLsp(this) && !b.external && b.lang != null && b.lang.serverId != null && language != null;
        if (wantsServer) lsp.attach(b, language);
        if (b.lsp == null && language != null) ed.setEditorLanguage(language);
    }

    private void applySettings(EditorBuffer b) {
        CodeEditor ed = b.editor;
        if (ed == null) return;
        ed.setTextSize(Prefs.edFont(this));
        ed.setWordwrap(Prefs.edWrap(this) && !b.large);
        ed.setLineNumberEnabled(Prefs.edLines(this));
        ed.setTabWidth(Prefs.edTab(this));
    }

    private void show(EditorBuffer b) {
        if (!ensureLoaded(b)) return;
        current = b;
        b.lastShown = android.os.SystemClock.uptimeMillis();
        for (EditorBuffer o : buffers) if (o.editor != null) o.editor.setVisibility(o == b ? View.VISIBLE : View.GONE);
        b.editor.requestFocus();
        closeDrawer();
        refreshUi();
        scheduleFindRefresh();
        trimLoaded();
    }

    // ------------------------------------------------------------------ keeping RAM low: unloading idle tabs

    /** Reopens a tab whose view was released. Cursor position is kept; the text is read from disk again. */
    private boolean ensureLoaded(EditorBuffer b) {
        if (b.editor != null) return true;
        try {
            String text = b.hostFile.exists() ? new String(Files.readAllBytes(b.hostFile.toPath()), StandardCharsets.UTF_8) : "";
            int line = b.cursorLine, col = b.cursorColumn;
            b.crlf = text.contains("\r\n");
            b.diskModified = b.hostFile.exists() ? b.hostFile.lastModified() : 0;
            createEditorView(b, text);
            try { b.editor.setSelection(line, col); } catch (RuntimeException ignored) { /* the file got shorter */ }
            return true;
        } catch (IOException e) {
            toast("Cannot open " + b.title() + ": " + e.getMessage());
            return false;
        }
    }

    /** Frees the view, text and language-server binding of a tab nobody is looking at. Tabs with unsaved edits stay. */
    private void unload(EditorBuffer b) {
        if (b == current || b.modified || b.editor == null) return;
        lsp.detach(b);
        editorFrame.removeView(b.editor);
        b.editor.release();
        b.editor = null;
    }

    private void unloadBackground() { for (EditorBuffer b : new ArrayList<>(buffers)) unload(b); }

    /** Keeps at most the configured number of tabs in memory, dropping the least recently selected clean ones. */
    private void trimLoaded() {
        int max = Prefs.edLoaded(this);
        if (max <= 0) return;
        while (true) {
            int loaded = 0;
            EditorBuffer oldest = null;
            for (EditorBuffer o : buffers) {
                if (o.editor == null) continue;
                loaded++;
                if (o != current && !o.modified && (oldest == null || o.lastShown < oldest.lastShown)) oldest = o;
            }
            if (loaded <= max || oldest == null) return;
            unload(oldest);
        }
    }

    private void closeBuffer(final EditorBuffer b, boolean askIfModified) {
        if (askIfModified && b.modified && !Prefs.edAutosave(this)) {
            new Dlg(this).setTitle("Save " + b.title() + "?")
                    .setPositiveButton("Save", (d, w) -> { save(b); closeBuffer(b, false); })
                    .setNegativeButton("Discard", (d, w) -> { b.modified = false; closeBuffer(b, false); })
                    .setNeutralButton("Cancel", null).show();
            return;
        }
        if (b.modified) save(b);
        int index = buffers.indexOf(b);
        buffers.remove(b);
        lsp.detach(b);
        releaseWaiter(b);
        if (b.editor != null) { editorFrame.removeView(b.editor); b.editor.release(); }
        if (current == b) {
            current = buffers.isEmpty() ? null : buffers.get(Math.max(0, Math.min(index, buffers.size() - 1)));
            if (current != null) show(current);
        }
        refreshUi();
        if (buffers.isEmpty() && getIntent().getStringExtra(EXTRA_TOKEN) != null) finish();
    }

    // ------------------------------------------------------------------ saving

    private void save(final EditorBuffer b) {
        if (b.editor == null) return;
        final String text = b.editor.getText().toString();
        try {
            File dest = b.hostFile;
            File tmp = new File(dest.getParentFile(), "." + dest.getName() + ".ns-tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (dest.exists() && dest.canExecute()) tmp.setExecutable(true, false);
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            b.diskModified = dest.lastModified();
            b.modified = false;
            if (b.sourceUri != null) writeBack(b, text);
            if (b.lsp != null && b.lspState == LspManager.State.READY) {
                io.execute(() -> { try { b.lsp.saveDocumentBlocking(); } catch (Throwable ignored) { } });
            }
        } catch (IOException e) {
            toast("Save failed: " + e.getMessage());
        }
        refreshUi();
    }

    private void saveAllQuiet() {
        ui.removeCallbacks(autosave);
        for (EditorBuffer b : buffers) if (b.modified) save(b);
    }

    private void checkDiskChanges() {
        for (final EditorBuffer b : new ArrayList<>(buffers)) {
            if (!b.hostFile.exists() || b.hostFile.lastModified() == b.diskModified) continue;
            b.diskModified = b.hostFile.lastModified();
            if (!b.modified) { reloadFromDisk(b); continue; }
            new Dlg(this).setTitle(b.title() + " changed on disk")
                    .setMessage("You also have unsaved edits.")
                    .setPositiveButton("Reload from disk", (d, w) -> reloadFromDisk(b))
                    .setNegativeButton("Keep mine", null).show();
        }
    }

    private void reloadFromDisk(EditorBuffer b) {
        if (b.editor == null) { b.diskModified = b.hostFile.lastModified(); return; }   // unloaded: it is read fresh when selected
        try {
            String text = new String(Files.readAllBytes(b.hostFile.toPath()), StandardCharsets.UTF_8);
            b.editor.getText().replace(0, b.editor.getText().length(), text);
            b.modified = false;
            b.diskModified = b.hostFile.lastModified();
            refreshUi();
        } catch (IOException e) {
            toast("Cannot reload: " + e.getMessage());
        }
    }

    private void releaseWaiter(EditorBuffer b) {
        if (b.waitToken == null || b.waitToken.equals("-")) return;
        File token = GuestPaths.toHost(rootfs, b.waitToken, false);
        if (token != null) token.delete();
        b.waitToken = null;
    }

    private void releaseWaiters() { for (EditorBuffer b : buffers) releaseWaiter(b); }

    // ------------------------------------------------------------------ UI pieces

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Ui.LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        bar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)));

        TextView back = Ui.iconButton(this, "\u2190");
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back);
        TextView filesBtn = Ui.iconButton(this, "\u2261");
        filesBtn.setOnClickListener(v -> { if (drawerOpen) closeDrawer(); else openDrawer(); });
        bar.addView(filesBtn);

        titleView = new TextView(this);
        titleView.setTextColor(Ui.TEXT);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        titleView.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        saveBtn = Ui.key(this, "Save");
        saveBtn.setOnClickListener(v -> { if (current != null) save(current); });
        bar.addView(saveBtn);

        TextView menu = Ui.iconButton(this, "\u22EE");
        menu.setOnClickListener(this::showMenu);
        bar.addView(menu);
        return bar;
    }

    private View buildTabStrip() {
        tabScroll = new HorizontalScrollView(this);
        tabScroll.setScrollbarFadingEnabled(true);
        tabScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        tabBar = new LinearLayout(this);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        tabBar.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        tabScroll.addView(tabBar);
        tabScroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 38)));
        return tabScroll;
    }

    private void rebuildTabs() {
        tabBar.removeAllViews();
        View active = null;
        for (final EditorBuffer b : buffers) {
            boolean isCurrent = b == current;
            LinearLayout tab = new LinearLayout(this);
            tab.setGravity(Gravity.CENTER_VERTICAL);
            tab.setBackground(Ui.ripple(this, isCurrent ? Ui.SURFACE2 : 0, 8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 30));
            lp.setMargins(0, 0, Ui.dp(this, 4), 0);
            tab.setLayoutParams(lp);
            tab.setOnClickListener(v -> show(b));

            TextView label = new TextView(this);
            label.setText((b.modified ? "\u25CF " : "") + b.title());
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            label.setTextColor(isCurrent ? Ui.TEXT : Ui.DIM);
            label.setPadding(Ui.dp(this, 10), 0, isCurrent ? Ui.dp(this, 2) : Ui.dp(this, 10), 0);
            tab.addView(label);
            if (isCurrent) {
                TextView close = new TextView(this);
                close.setText("\u00D7");
                close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                close.setTextColor(Ui.DIM);
                close.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 10), 0);
                close.setOnClickListener(v -> closeBuffer(b, true));
                tab.addView(close);
                active = tab;
            }
            tabBar.addView(tab);
        }
        final View target = active;
        if (target != null) tabScroll.post(() -> tabScroll.smoothScrollTo(Math.max(0, target.getLeft() - Ui.dp(this, 30)), 0));
        tabScroll.setVisibility(buffers.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void buildEmptyState() {
        empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setBackgroundColor(Ui.BG);
        TextView t = new TextView(this);
        t.setText("No file open");
        t.setTextColor(Ui.DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setGravity(Gravity.CENTER);
        empty.addView(t);
        TextView open = Ui.button(this, "Browse files", Ui.PRIMARY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 200), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 16);
        open.setOnClickListener(v -> openDrawer());
        empty.addView(open, lp);
    }

    private View buildKeyRow() {
        HorizontalScrollView sv = new HorizontalScrollView(this);
        sv.setScrollbarFadingEnabled(true);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        keyRow = new LinearLayout(this);
        keyRow.setPadding(Ui.dp(this, 3), Ui.dp(this, 2), Ui.dp(this, 3), Ui.dp(this, 2));
        sv.addView(keyRow);

        addKey("Tab", () -> insert("\t", 1));
        addKey("\u21B6", () -> { if (current != null && current.editor.canUndo()) current.editor.undo(); });
        addKey("\u21B7", () -> { if (current != null && current.editor.canRedo()) current.editor.redo(); });
        addKey("\u2190", () -> move(SelectionMovement.LEFT));
        addKey("\u2192", () -> move(SelectionMovement.RIGHT));
        addKey("\u2191", () -> move(SelectionMovement.UP));
        addKey("\u2193", () -> move(SelectionMovement.DOWN));
        addKey("Home", () -> move(SelectionMovement.LINE_START));
        addKey("End", () -> move(SelectionMovement.LINE_END));
        String[][] pairs = {{"{", "{}"}, {"}", "}"}, {"(", "()"}, {")", ")"}, {"[", "[]"}, {"]", "]"}, {"<", "<"}, {">", ">"},
                {";", ";"}, {":", ":"}, {"\"", "\"\""}, {"'", "''"}, {"=", "="}, {"+", "+"}, {"-", "-"}, {"*", "*"}, {"/", "/"},
                {"\\", "\\"}, {"|", "|"}, {"&", "&"}, {"!", "!"}, {"_", "_"}, {"#", "#"}, {"$", "$"}, {"%", "%"}, {"@", "@"},
                {"`", "`"}, {"~", "~"}, {"^", "^"}, {"?", "?"}};
        for (final String[] p : pairs) addKey(p[0], () -> insert(p[1], p[1].length() == 2 ? 1 : p[1].length()));
        return sv;
    }

    private void addKey(String label, Runnable action) {
        TextView k = Ui.key(this, label);
        k.setOnClickListener(v -> action.run());
        keyRow.addView(k);
    }

    private void insert(String text, int caretOffset) {
        if (current != null) current.editor.insertText(text, caretOffset);
    }

    private void move(SelectionMovement m) {
        if (current != null) current.editor.moveSelection(m);
    }

    private View buildStatusBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Ui.SURFACE);
        bar.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        bar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 28)));
        statusPos = new TextView(this);
        statusPos.setTextColor(Ui.DIM);
        statusPos.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        bar.addView(statusPos, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statusLsp = new TextView(this);
        statusLsp.setTextColor(Ui.DIM);
        statusLsp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statusLsp.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), 0, Ui.dp(this, 4));
        statusLsp.setOnClickListener(v -> onStatusTap());
        bar.addView(statusLsp);
        return bar;
    }

    // ------------------------------------------------------------------ state display

    private void refreshUi() {
        rebuildTabs();
        empty.setVisibility(current == null ? View.VISIBLE : View.GONE);
        titleView.setText(current == null ? "Editor" : (current.modified ? "\u25CF " : "") + current.displayPath());
        saveBtn.setVisibility(current == null ? View.GONE : View.VISIBLE);
        Ui.setKeyActive(this, saveBtn, current != null && current.modified);
        refreshStatus();
    }

    private void refreshStatus() {
        if (current == null) { statusPos.setText(""); statusLsp.setText(""); return; }
        String lang = current.lang == null ? "Plain text" : current.lang.name;
        statusPos.setText(lang + "  \u00B7  Ln " + (current.cursorLine + 1) + ", Col " + (current.cursorColumn + 1)
                + (current.crlf ? "  \u00B7  CRLF" : ""));
        switch (current.lspState) {
            case OFF:
                statusLsp.setText(current.large ? "Large file mode" : current.lang == null || current.lang.serverId == null ? ""
                        : !Prefs.edLsp(this) ? "LSP off" : current.lspDetail.startsWith("Stopped") ? "Server stopped \u00B7 tap to start" : "");
                statusLsp.setTextColor(Ui.DIM);
                break;
            case NO_SERVER:
                LanguageSupport.Server s = LanguageSupport.server(current.lang.serverId);
                statusLsp.setText("Install " + (s == null ? "server" : s.binary));
                statusLsp.setTextColor(Ui.ACCENT);
                break;
            case STARTING:
                statusLsp.setText("Starting server\u2026");
                statusLsp.setTextColor(Ui.DIM);
                break;
            case READY:
                int errors = 0, warnings = 0;
                for (LspManager.Problem p : lsp.problems(current)) { if (p.severity == 1) errors++; else if (p.severity == 2) warnings++; }
                statusLsp.setText(errors + " errors  \u00B7  " + warnings + " warnings");
                statusLsp.setTextColor(errors > 0 ? Ui.DANGER : Ui.DIM);
                break;
            default:
                statusLsp.setText("Server error");
                statusLsp.setTextColor(Ui.DANGER);
        }
    }

    @Override public void onLspState(EditorBuffer b) { if (b == current) refreshStatus(); }

    private void onStatusTap() {
        if (current == null) return;
        switch (current.lspState) {
            case NO_SERVER: {
                final LanguageSupport.Server s = LanguageSupport.server(current.lang.serverId);
                new Dlg(this).setTitle("Install " + s.label + "?")
                        .setMessage("This runs in a terminal tab:\n\n" + s.install)
                        .setPositiveButton("Install", (d, w) -> MainActivity.runInTerminal(this, s.install))
                        .setNegativeButton("Cancel", null).show();
                break;
            }
            case ERROR:
                new Dlg(this).setTitle("Language server problem").setMessage(current.lspDetail)
                        .setPositiveButton("Retry", (d, w) -> retryLsp(current))
                        .setNegativeButton("Close", null).show();
                break;
            case READY: showProblems(); break;
            case OFF:
                if (!current.large && current.lang != null && current.lang.serverId != null && Prefs.edLsp(this)) retryLsp(current);   // restart a stopped server
                break;
            default: break;
        }
    }

    private void retryLsp(EditorBuffer b) {
        lsp.detach(b);
        if (b.large || b.external) return;
        TextMateLanguage language = null;
        if (b.lang != null && b.lang.scope != null) {
            try { language = TextMateLanguage.create(b.lang.scope, true); } catch (RuntimeException ignored) { }
        }
        if (language != null) lsp.attach(b, language);
    }

    // ------------------------------------------------------------------ drawer

    private void openDrawer() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && current != null) imm.hideSoftInputFromWindow(current.editor.getWindowToken(), 0);
        if (current != null && !current.external && !drawerOpen) files.showDir(GuestPaths.parent(current.guestPath));
        drawerOpen = true;
        scrim.setAlpha(0f);
        scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(150);
        drawerSlot.setTranslationX(-Ui.dp(this, 290));
        drawerSlot.setVisibility(View.VISIBLE);
        drawerSlot.animate().translationX(0f).setDuration(180);
    }

    private void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        scrim.animate().alpha(0f).setDuration(150).withEndAction(() -> scrim.setVisibility(View.GONE));
        drawerSlot.animate().translationX(-Ui.dp(this, 290)).setDuration(160).withEndAction(() -> drawerSlot.setVisibility(View.GONE));
    }

    @Override public void onOpenFile(String guestPath) { openFile(guestPath, null); }
    @Override public boolean isOpen(String guestPath) { return find(guestPath) != null; }
    @Override public void onDeleted(String guestPath) {
        for (EditorBuffer b : new ArrayList<>(buffers)) {
            if (b.guestPath.equals(guestPath) || b.guestPath.startsWith(guestPath + "/")) { b.modified = false; closeBuffer(b, false); }
        }
    }
    @Override public void onClose() { closeDrawer(); }
    @Override public void onNeedPhoneAccess() { askPhoneAccess(); }

    // ------------------------------------------------------------------ menu and actions

    private void showMenu(View anchor) {
        TPopup pm = new TPopup(this, anchor);
        boolean has = current != null;
        boolean ready = has && current.lspState == LspManager.State.READY;
        pm.getMenu().add(0, 1, 0, "Find and replace").setEnabled(has);
        pm.getMenu().add(0, 2, 1, "Go to line").setEnabled(has);
        pm.getMenu().add(0, 3, 2, "Go to definition").setEnabled(ready);
        pm.getMenu().add(0, 4, 3, "Find references").setEnabled(ready);
        pm.getMenu().add(0, 5, 4, "Rename symbol").setEnabled(ready);
        pm.getMenu().add(0, 6, 5, "Format document").setEnabled(ready);
        pm.getMenu().add(0, 7, 6, "Outline").setEnabled(ready);
        pm.getMenu().add(0, 8, 7, "Problems").setEnabled(ready);
        pm.getMenu().add(0, 9, 8, "Save all").setEnabled(has);
        pm.getMenu().add(0, 10, 9, "Reload from disk").setEnabled(has);
        pm.getMenu().add(0, 12, 10, "Stop language servers");
        pm.getMenu().add(0, 14, 11, "Show in file manager");
        pm.getMenu().add(0, 13, 12, "Memory");
        pm.getMenu().add(0, 11, 13, "Editor settings");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: openFind(); return true;
                case 2: askGoToLine(); return true;
                case 3: gotoDefinition(); return true;
                case 4: findReferences(); return true;
                case 5: askRename(); return true;
                case 6: formatDocument(); return true;
                case 7: showOutline(); return true;
                case 8: showProblems(); return true;
                case 9: saveAllQuiet(); return true;
                case 10: reloadFromDisk(current); return true;
                case 14: showInFileManager(); return true;
                case 13: MemoryReport.show(this, "Free memory", () -> { lsp.stopIdle(); unloadBackground(); toast("Freed idle servers and background tabs"); }); return true;
                case 12: lsp.stopAll(buffers, false); toast("Language servers stopped"); refreshStatus(); return true;
                case 11: startActivity(new Intent(this, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_CATEGORY, "editor")); return true;
                default: return false;
            }
        });
        pm.show();
    }

    private void askGoToLine() {
        Ui.prompt(this, "Go to line", "1 to " + current.editor.getLineCount(), "", "Go", s -> {
            try {
                int n = Integer.parseInt(s.trim());
                current.editor.jumpToLine(Math.max(0, Math.min(current.editor.getLineCount() - 1, n - 1)));
            } catch (NumberFormatException e) { toast("Enter a line number"); }
        });
    }

    private void jumpTo(LspManager.Target t) {
        EditorBuffer b = find(t.path);
        if (b == null) {
            openFile(t.path, null);
            final LspManager.Target target = t;
            ui.postDelayed(() -> {
                EditorBuffer opened = find(target.path);
                if (opened != null) opened.editor.setSelection(target.line, target.column);
            }, 400);
        } else {
            show(b);
            b.editor.setSelection(t.line, t.column);
        }
    }

    private interface Job<T> { T run() throws Exception; }
    private interface Result<T> { void done(T value); }

    private <T> void background(final String what, final Job<T> job, final Result<T> result) {
        toast(what + "\u2026");
        io.execute(() -> {
            try {
                final T value = job.run();
                ui.post(() -> result.done(value));
            } catch (final Exception e) {
                ui.post(() -> toast(what + " failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())));
            }
        });
    }

    private void gotoDefinition() {
        final EditorBuffer b = current;
        final int line = b.editor.getCursor().getLeftLine(), col = b.editor.getCursor().getLeftColumn();
        background("Go to definition", () -> lsp.definition(b, line, col), targets -> {
            if (targets.isEmpty()) toast("No definition found");
            else if (targets.size() == 1) jumpTo(targets.get(0));
            else pickTarget("Definitions", targets);
        });
    }

    private void findReferences() {
        final EditorBuffer b = current;
        final int line = b.editor.getCursor().getLeftLine(), col = b.editor.getCursor().getLeftColumn();
        background("Find references", () -> lsp.references(b, line, col), targets -> {
            if (targets.isEmpty()) toast("No references found");
            else pickTarget(targets.size() + " references", targets);
        });
    }

    private void pickTarget(String title, final List<LspManager.Target> targets) {
        String[] items = new String[targets.size()];
        for (int i = 0; i < items.length; i++) {
            LspManager.Target t = targets.get(i);
            items[i] = t.path + ":" + (t.line + 1);
        }
        new Dlg(this).setTitle(title).setItems(items, (d, which) -> jumpTo(targets.get(which))).show();
    }

    private void showOutline() {
        final EditorBuffer b = current;
        background("Outline", () -> lsp.symbols(b), symbols -> {
            if (symbols.isEmpty()) { toast("Nothing to show"); return; }
            String[] items = new String[symbols.size()];
            for (int i = 0; i < items.length; i++) {
                LspManager.Symbol s = symbols.get(i);
                StringBuilder sb = new StringBuilder();
                for (int d = 0; d < s.depth; d++) sb.append("   ");
                items[i] = sb + s.name + "   \u00B7 " + s.kind + "  " + (s.line + 1);
            }
            new Dlg(this).setTitle("Outline")
                    .setItems(items, (d, which) -> b.editor.setSelection(symbols.get(which).line, 0)).show();
        });
    }

    private void showProblems() {
        if (current == null) return;
        final List<LspManager.Problem> list = lsp.problems(current);
        if (list.isEmpty()) { toast("No problems"); return; }
        String[] items = new String[list.size()];
        for (int i = 0; i < items.length; i++) {
            LspManager.Problem p = list.get(i);
            String sev = p.severity == 1 ? "error" : p.severity == 2 ? "warning" : p.severity == 3 ? "info" : "hint";
            items[i] = (p.line + 1) + ":" + (p.column + 1) + "  " + sev + "  " + p.message.replace('\n', ' ');
        }
        final EditorBuffer b = current;
        new Dlg(this).setTitle(list.size() + " problems")
                .setItems(items, (d, which) -> b.editor.setSelection(list.get(which).line, list.get(which).column)).show();
    }

    // ------------------------------------------------------------------ rename and format

    private LspManager.TextSource textSource() {
        return path -> {
            EditorBuffer b = find(path);
            if (b != null && b.editor != null) return b.editor.getText().toString();
            File f = GuestPaths.toHost(rootfs, path, true);
            try { return f != null && f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null; }
            catch (IOException e) { return null; }
        };
    }

    private void askRename() {
        final EditorBuffer b = current;
        final int line = b.editor.getCursor().getLeftLine(), col = b.editor.getCursor().getLeftColumn();
        Ui.prompt(this, "Rename symbol", "new name", "", "Rename", name -> {
            final String n = name.trim();
            if (n.isEmpty()) return;
            background("Rename", () -> lsp.rename(b, line, col, n, textSource()), this::confirmChanges);
        });
    }

    private void formatDocument() {
        final EditorBuffer b = current;
        background("Format", () -> lsp.format(b, Prefs.edTab(this), Prefs.edSpaces(this), textSource()), cs -> {
            if (cs.newText.isEmpty()) { toast("Already formatted"); return; }
            applyChanges(cs);
        });
    }

    private void confirmChanges(final LspManager.ChangeSet cs) {
        if (cs.newText.isEmpty()) { toast("The server returned no changes"); return; }
        StringBuilder sb = new StringBuilder();
        for (String p : cs.newText.keySet()) sb.append(p).append(find(p) == null || find(p).editor == null ? "  (will be written)" : "").append('\n');
        new Dlg(this).setTitle(cs.edits + " changes in " + cs.newText.size() + (cs.newText.size() == 1 ? " file" : " files"))
                .setMessage(sb.toString().trim())
                .setPositiveButton("Apply", (d, w) -> applyChanges(cs))
                .setNegativeButton("Cancel", null).show();
    }

    private void applyChanges(LspManager.ChangeSet cs) {
        for (java.util.Map.Entry<String, String> e : cs.newText.entrySet()) {
            EditorBuffer b = find(e.getKey());
            if (b != null && b.editor != null) {
                b.editor.getText().replace(0, b.editor.getText().length(), e.getValue());
            } else {
                File f = GuestPaths.toHost(rootfs, e.getKey(), true);
                if (f == null) continue;
                try { Files.write(f.toPath(), e.getValue().getBytes(StandardCharsets.UTF_8)); }
                catch (IOException ex) { toast("Cannot write " + e.getKey()); }
            }
        }
        refreshUi();
    }

    // ------------------------------------------------------------------ find and replace

    private void buildFindBar() {
        findBar = new LinearLayout(this);
        findBar.setOrientation(LinearLayout.VERTICAL);
        findBar.setBackgroundColor(Ui.SURFACE);
        findBar.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
        findBar.setVisibility(View.GONE);

        LinearLayout r1 = new LinearLayout(this);
        r1.setGravity(Gravity.CENTER_VERTICAL);
        findInput = field("Find");
        findInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { runSearch(); }
        });
        r1.addView(findInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        findCount = new TextView(this);
        findCount.setTextColor(Ui.DIM);
        findCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        findCount.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        r1.addView(findCount);
        final TextView aa = Ui.key(this, "Aa");
        aa.setOnClickListener(v -> { caseSensitive = !caseSensitive; Ui.setKeyActive(this, aa, caseSensitive); runSearch(); });
        r1.addView(aa);
        final TextView re = Ui.key(this, ".*");
        re.setOnClickListener(v -> { regex = !regex; Ui.setKeyActive(this, re, regex); runSearch(); });
        r1.addView(re);
        TextView prev = Ui.key(this, "\u2191");
        prev.setOnClickListener(v -> { if (current != null) { current.editor.getSearcher().gotoPrevious(); scheduleFindRefresh(); } });
        r1.addView(prev);
        TextView next = Ui.key(this, "\u2193");
        next.setOnClickListener(v -> { if (current != null) { current.editor.getSearcher().gotoNext(); scheduleFindRefresh(); } });
        r1.addView(next);
        TextView close = Ui.key(this, "\u00D7");
        close.setOnClickListener(v -> closeFind());
        r1.addView(close);
        findBar.addView(r1);

        LinearLayout r2 = new LinearLayout(this);
        r2.setGravity(Gravity.CENTER_VERTICAL);
        replaceInput = field("Replace with");
        r2.addView(replaceInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView one = Ui.key(this, "Replace");
        one.setOnClickListener(v -> {
            if (current != null) { current.editor.getSearcher().replaceCurrentMatch(replaceInput.getText().toString()); scheduleFindRefresh(); }
        });
        r2.addView(one);
        TextView all = Ui.key(this, "All");
        all.setOnClickListener(v -> {
            if (current != null) { current.editor.getSearcher().replaceAll(replaceInput.getText().toString()); scheduleFindRefresh(); }
        });
        r2.addView(all);
        findBar.addView(r2);
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setHintTextColor(Ui.HINT);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        Ui.tint(e);
        return e;
    }

    private void openFind() {
        findBar.setVisibility(View.VISIBLE);
        if (current != null && current.editor.getCursor().isSelected()) {
            CharSequence sel = current.editor.getText().subSequence(
                    current.editor.getCursor().getLeft(), current.editor.getCursor().getRight());
            if (sel.length() < 200 && sel.toString().indexOf('\n') < 0) findInput.setText(sel.toString());
        }
        findInput.requestFocus();
        findInput.setSelection(findInput.getText().length());
        runSearch();
    }

    private void closeFind() {
        findBar.setVisibility(View.GONE);
        for (EditorBuffer b : buffers) if (b.editor != null) b.editor.getSearcher().stopSearch();
        if (current != null) current.editor.requestFocus();
    }

    private void runSearch() {
        if (current == null || findBar.getVisibility() != View.VISIBLE) return;
        String q = findInput.getText().toString();
        EditorSearcher s = current.editor.getSearcher();
        if (q.isEmpty()) { s.stopSearch(); findCount.setText(""); return; }
        try {
            s.search(q, new EditorSearcher.SearchOptions(!caseSensitive, regex));
        } catch (RuntimeException e) {
            findCount.setText("bad pattern");
            return;
        }
        scheduleFindRefresh();
    }

    private void scheduleFindRefresh() {
        ui.postDelayed(() -> {
            if (current == null || findBar.getVisibility() != View.VISIBLE) return;
            EditorSearcher s = current.editor.getSearcher();
            if (!s.hasQuery()) { findCount.setText(""); return; }
            int n = s.getMatchedPositionCount();
            findCount.setText(n == 0 ? "0" : (s.getCurrentMatchedPositionIndex() + 1) + "/" + n);
        }, 250);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

}
