package com.zed.nullshell;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import io.github.rosemoe.sora.lsp.client.languageserver.serverdefinition.CustomLanguageServerDefinition;
import io.github.rosemoe.sora.lsp.editor.LspEditor;
import io.github.rosemoe.sora.lsp.editor.LspProject;
import io.github.rosemoe.sora.lsp.requests.Timeout;
import io.github.rosemoe.sora.lsp.requests.Timeouts;
import io.github.rosemoe.sora.widget.CodeEditor;

import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Language servers for the editor. Servers run inside Kali, so every path that crosses the LSP
 * boundary is a guest path ("/root/app/main.py"); sora-editor never touches the filesystem itself.
 * One server process is started per project folder and file extension, the first time such a file opens.
 */
final class LspManager {
    enum State { OFF, NO_SERVER, STARTING, READY, ERROR }

    interface Listener { void onLspState(EditorBuffer b); }

    /** A place in some file: guest path, zero-based line and column. */
    static final class Target {
        final String path;
        final int line, column;
        final String label;
        Target(String path, int line, int column, String label) { this.path = path; this.line = line; this.column = column; this.label = label; }
    }

    static final class Symbol {
        final String name, kind;
        final int line, depth;
        Symbol(String name, String kind, int line, int depth) { this.name = name; this.kind = kind; this.line = line; this.depth = depth; }
    }

    /** What a rename or format would change. Closed files are read from disk, open ones from the editor. */
    static final class ChangeSet {
        final Map<String, String> newText = new LinkedHashMap<>();    // guest path -> new full text
        int edits;
    }

    static {
        // PRoot makes servers slower than on a desktop; the library defaults assume a fast machine.
        Timeout.INSTANCE.set(Timeouts.INIT, 90000);
        Timeout.INSTANCE.set(Timeouts.COMPLETION, 6000);
        Timeout.INSTANCE.set(Timeouts.DEFINITION, 8000);
        Timeout.INSTANCE.set(Timeouts.REFERENCES, 12000);
        Timeout.INSTANCE.set(Timeouts.FORMATTING, 12000);
        Timeout.INSTANCE.set(Timeouts.HOVER, 4000);
        Timeout.INSTANCE.set(Timeouts.SYMBOLS, 8000);
        Timeout.INSTANCE.set(Timeouts.CODEACTION, 4000);
        Timeout.INSTANCE.set(Timeouts.SIGNATURE, 6000);
    }

    private final Context ctx;
    private final File rootfs;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "lsp-worker");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, LspProject> projects = new ConcurrentHashMap<>();
    private final Map<String, GuestServerConnection> connections = new ConcurrentHashMap<>();
    private volatile boolean disposed;

    // Memory management. Everything below is touched on the UI thread only.
    private final Map<String, Integer> rootUsers = new HashMap<>();   // project root -> buffers bound to its servers
    private final Map<String, Long> rootLast = new HashMap<>();       // project root -> last time a buffer was bound or released
    private boolean reaperOn;
    private static final long REAP_EVERY_MS = 30000;
    private final Runnable reaper = new Runnable() {
        @Override public void run() {
            reaperOn = false;
            if (disposed) return;
            long limit = Prefs.lspIdleMinutes(ctx) * 60000L;
            if (limit <= 0) return;
            long now = SystemClock.uptimeMillis();
            boolean waiting = false;
            for (String root : new ArrayList<>(rootLast.keySet())) {
                if (users(root) > 0) continue;
                Long last = rootLast.get(root);
                if (last != null && now - last >= limit) stopRoot(root); else waiting = true;
            }
            if (waiting) startReaper();
        }
    };

    LspManager(Context ctx, File rootfs, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.rootfs = rootfs;
        this.listener = listener;
    }

    static String uriOf(String guestPath) { return new File(guestPath).toURI().toString(); }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return (dot >= 0 ? name.substring(dot + 1) : name).toLowerCase(java.util.Locale.US);
    }

    private void post(EditorBuffer b, State s, String detail) {
        b.lspState = s;
        b.lspDetail = detail;
        ui.post(() -> { if (!disposed) listener.onLspState(b); });
    }

    // ------------------------------------------------------------------ attach / detach

    /**
     * Connects a buffer to its language server. The buffer's editor must already hold its text.
     * Does nothing when the buffer is already connected.
     */
    void attach(final EditorBuffer b, final io.github.rosemoe.sora.langs.textmate.TextMateLanguage language) {
        final CodeEditor editor = b.editor;
        if (editor == null) return;
        if (!Prefs.edLsp(ctx) || b.lang == null || b.lang.serverId == null) { post(b, State.OFF, ""); return; }
        if (b.lsp != null) return;
        final LanguageSupport.Server server = LanguageSupport.server(b.lang.serverId);
        final String override = Prefs.serverCommand(ctx, server.id);
        final List<String> argv = LanguageSupport.argv(server, override);
        if (GuestPaths.which(rootfs, argv.get(0)) == null) {
            post(b, State.NO_SERVER, server.install);
            return;
        }

        final String root = GuestPaths.projectRoot(rootfs, b.guestPath);
        final String ext = extensionOf(b.title());
        makeRoomFor(root, ext);
        final LspProject project = projects.computeIfAbsent(root, LspProject::new);
        if (project.getServerDefinition(ext, null) == null) {
            final String key = root + "|" + ext;
            final List<String> memEnv = LanguageSupport.memoryEnv(server, Prefs.lspMemoryMb(ctx));
            project.addServerDefinition(new CustomLanguageServerDefinition(ext,
                    workingDir -> {
                        GuestServerConnection c = new GuestServerConnection(ctx, rootfs, argv, workingDir, memEnv);
                        connections.put(key, c);
                        return c;
                    }));
        }

        final LspEditor lsp;
        try {
            lsp = project.createEditor(b.guestPath);
            lsp.setWrapperLanguage(language);
            lsp.setEditor(editor);
            lsp.setEnableHover(true);
            lsp.setEnableSignatureHelp(true);
        } catch (RuntimeException e) {
            post(b, State.ERROR, String.valueOf(e.getMessage()));
            return;
        }
        b.lsp = lsp;
        b.lspProjectRoot = root;
        b.lspSuspended = false;
        rootUsers.put(root, users(root) + 1);
        rootLast.put(root, SystemClock.uptimeMillis());
        post(b, State.STARTING, "Starting " + server.label + "\u2026 the first start can take a while");

        pool.execute(() -> {
            try {
                lsp.connectWithTimeoutBlocking();
                post(b, State.READY, server.label);
            } catch (Throwable t) {
                GuestServerConnection c = connections.get(root + "|" + ext);
                String tail = c == null ? "" : c.logTail().trim();
                String detail = (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage())
                        + (tail.isEmpty() ? "" : "\n\n" + tail);
                post(b, State.ERROR, detail);
            }
        });
    }

    /** Called when a buffer is closed: tells the server and releases the editor binding. */
    void detach(final EditorBuffer b) { release(b, false, ""); }

    private void release(final EditorBuffer b, boolean suspended, String detail) {
        final LspEditor lsp = b.lsp;
        b.lsp = null;
        b.lspState = State.OFF;
        b.lspSuspended = suspended;
        b.lspDetail = detail;
        if (lsp == null) return;
        String root = b.lspProjectRoot;
        if (root != null) {
            int n = users(root) - 1;
            if (n > 0) rootUsers.put(root, n); else rootUsers.remove(root);
            rootLast.put(root, SystemClock.uptimeMillis());
            startReaper();
        }
        pool.execute(() -> {
            try { lsp.disconnect(); } catch (Throwable ignored) { }
            try { lsp.dispose(); } catch (Throwable ignored) { }
        });
    }

    void dispose() {
        disposed = true;
        ui.removeCallbacks(reaper);
        rootUsers.clear();
        rootLast.clear();
        pool.execute(() -> {
            for (LspProject p : projects.values()) {
                try { p.dispose(); } catch (Throwable ignored) { }
            }
            projects.clear();
            for (GuestServerConnection c : connections.values()) c.close();
            connections.clear();
            pool.shutdown();
        });
    }

    // ------------------------------------------------------------------ memory

    private int users(String root) { Integer n = rootUsers.get(root); return n == null ? 0 : n; }

    private void startReaper() {
        if (reaperOn || disposed || Prefs.lspIdleMinutes(ctx) <= 0) return;
        reaperOn = true;
        ui.postDelayed(reaper, REAP_EVERY_MS);
    }

    private boolean hasServer(String root) {
        String prefix = root + "|";
        for (Map.Entry<String, GuestServerConnection> e : connections.entrySet()) {
            if (e.getKey().startsWith(prefix) && !e.getValue().isClosed()) return true;
        }
        return false;
    }

    private int liveServers() {
        int n = 0;
        for (GuestServerConnection c : connections.values()) if (!c.isClosed()) n++;
        return n;
    }

    /** Stops every server of one project and forgets the project; the next file opened there starts fresh. */
    private void stopRoot(String root) {
        final LspProject project = projects.remove(root);
        final List<GuestServerConnection> doomed = new ArrayList<>();
        String prefix = root + "|";
        for (Iterator<Map.Entry<String, GuestServerConnection>> it = connections.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, GuestServerConnection> e = it.next();
            if (e.getKey().startsWith(prefix)) { doomed.add(e.getValue()); it.remove(); }
        }
        rootUsers.remove(root);
        rootLast.remove(root);
        pool.execute(() -> {
            if (project != null) { try { project.dispose(); } catch (Throwable ignored) { } }
            for (GuestServerConnection c : doomed) c.close();
        });
    }

    /** Before a new server starts: when the limit is reached, stop the least recently used project nobody has open. */
    private void makeRoomFor(String root, String ext) {
        int max = Prefs.lspMaxServers(ctx);
        if (max <= 0 || connections.containsKey(root + "|" + ext)) return;
        while (liveServers() >= max) {
            String victim = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, Long> e : rootLast.entrySet()) {
                String r = e.getKey();
                if (r.equals(root) || users(r) > 0 || !hasServer(r)) continue;
                if (e.getValue() < oldest) { oldest = e.getValue(); victim = r; }
            }
            if (victim == null) return;     // every server is in use: never stop one under an open file
            stopRoot(victim);
        }
    }

    /** Stops the servers of every project that has no open file. */
    void stopIdle() {
        for (String root : new ArrayList<>(rootLast.keySet())) if (users(root) == 0) stopRoot(root);
    }

    /**
     * Stops all servers, including the ones for open files. With {@code resumable} the buffers are marked so
     * the editor restarts their servers when it is used again; otherwise they stay unbound until retried by hand.
     */
    void stopAll(List<EditorBuffer> buffers, boolean resumable) {
        for (EditorBuffer b : buffers) {
            if (b.lsp != null) release(b, resumable, resumable ? "Stopped to free memory" : "Stopped");
        }
        for (String root : new ArrayList<>(projects.keySet())) stopRoot(root);
        for (String root : new ArrayList<>(rootLast.keySet())) stopRoot(root);
    }

    /** The system is short of memory: free idle servers early, and everything once the app is in the background. */
    void onTrim(int level, List<EditorBuffer> buffers) {
        if (level == 15 /* TRIM_MEMORY_RUNNING_CRITICAL */ || level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) stopIdle();
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) stopAll(buffers, true);
    }

    // ------------------------------------------------------------------ diagnostics

    static final class Problem {
        final int line, column, severity;      // severity: 1 error, 2 warning, 3 info, 4 hint
        final String message, source;
        Problem(int line, int column, int severity, String message, String source) {
            this.line = line; this.column = column; this.severity = severity; this.message = message; this.source = source;
        }
    }

    List<Problem> problems(EditorBuffer b) {
        List<Problem> out = new ArrayList<>();
        if (b.lsp == null) return out;
        try {
            List<Diagnostic> list = b.lsp.getDiagnostics();
            if (list == null) return out;
            for (Diagnostic d : list) {
                int sev = d.getSeverity() == null ? 1 : d.getSeverity().getValue();
                out.add(new Problem(d.getRange().getStart().getLine(), d.getRange().getStart().getCharacter(), sev,
                        String.valueOf(d.getMessage()), d.getSource() == null ? "" : d.getSource()));
            }
        } catch (RuntimeException ignored) { }
        java.util.Collections.sort(out, (x, y) -> x.line != y.line ? Integer.compare(x.line, y.line) : Integer.compare(x.column, y.column));
        return out;
    }

    // ------------------------------------------------------------------ requests (blocking: call off the UI thread)

    private static final long WAIT_S = 20;

    private static TextDocumentIdentifier doc(EditorBuffer b) { return new TextDocumentIdentifier(uriOf(b.guestPath)); }

    private boolean ready(EditorBuffer b) { return b.lsp != null && b.lspState == State.READY && b.lsp.getRequestManager() != null; }

    // Result types are read through wildcards on purpose: lsp4j declares them with "? extends" and the Kotlin
    // wrapper without, and the code must compile against whichever form the library version has.

    List<Target> definition(EditorBuffer b, int line, int col) throws Exception {
        if (!ready(b)) throw new IllegalStateException("The language server is not ready");
        Either<?, ?> r = b.lsp.getRequestManager()
                .definition(new DefinitionParams(doc(b), new Position(line, col))).get(WAIT_S, TimeUnit.SECONDS);
        List<Target> out = new ArrayList<>();
        if (r != null) collectLocations(out, r.isLeft() ? r.getLeft() : r.getRight());
        return out;
    }

    List<Target> references(EditorBuffer b, int line, int col) throws Exception {
        if (!ready(b)) throw new IllegalStateException("The language server is not ready");
        ReferenceParams p = new ReferenceParams(doc(b), new Position(line, col), new ReferenceContext(true));
        List<?> r = b.lsp.getRequestManager().references(p).get(WAIT_S, TimeUnit.SECONDS);
        List<Target> out = new ArrayList<>();
        collectLocations(out, r);
        return out;
    }

    private static void collectLocations(List<Target> out, Object listObject) {
        if (!(listObject instanceof List)) return;
        for (Object o : (List<?>) listObject) {
            if (o instanceof Location) {
                Location l = (Location) o;
                addTarget(out, l.getUri(), l.getRange().getStart());
            } else if (o instanceof LocationLink) {
                LocationLink l = (LocationLink) o;
                addTarget(out, l.getTargetUri(), l.getTargetSelectionRange().getStart());
            }
        }
    }

    private static void addTarget(List<Target> out, String uri, Position start) {
        String path = GuestPaths.fromUri(uri);
        if (path != null) out.add(new Target(path, start.getLine(), start.getCharacter(), path));
    }

    List<Symbol> symbols(EditorBuffer b) throws Exception {
        if (!ready(b)) throw new IllegalStateException("The language server is not ready");
        List<?> r = b.lsp.getRequestManager().documentSymbol(new DocumentSymbolParams(doc(b))).get(WAIT_S, TimeUnit.SECONDS);
        List<Symbol> out = new ArrayList<>();
        if (r == null) return out;
        for (Object o : r) {
            if (!(o instanceof Either)) continue;
            Either<?, ?> e = (Either<?, ?>) o;
            Object payload = e.isRight() ? e.getRight() : e.getLeft();
            if (payload instanceof DocumentSymbol) {
                flatten((DocumentSymbol) payload, 0, out);
            } else if (payload instanceof SymbolInformation) {
                SymbolInformation s = (SymbolInformation) payload;
                out.add(new Symbol(s.getName(), String.valueOf(s.getKind()).toLowerCase(java.util.Locale.US),
                        s.getLocation().getRange().getStart().getLine(), 0));
            }
        }
        return out;
    }

    private static void flatten(DocumentSymbol s, int depth, List<Symbol> out) {
        out.add(new Symbol(s.getName(), String.valueOf(s.getKind()).toLowerCase(java.util.Locale.US),
                s.getSelectionRange().getStart().getLine(), depth));
        if (s.getChildren() != null) for (DocumentSymbol c : s.getChildren()) flatten(c, depth + 1, out);
    }

    /** Renames the symbol at the position. Texts of all affected files are computed but nothing is written yet. */
    ChangeSet rename(EditorBuffer b, int line, int col, String newName, TextSource source) throws Exception {
        if (!ready(b)) throw new IllegalStateException("The language server is not ready");
        WorkspaceEdit we = b.lsp.getRequestManager()
                .rename(new RenameParams(doc(b), new Position(line, col), newName)).get(WAIT_S, TimeUnit.SECONDS);
        return toChangeSet(we, source);
    }

    /** Formats the whole file. */
    ChangeSet format(EditorBuffer b, int tabSize, boolean spaces, TextSource source) throws Exception {
        if (!ready(b)) throw new IllegalStateException("The language server is not ready");
        FormattingOptions o = new FormattingOptions(tabSize, spaces);
        List<?> edits = b.lsp.getRequestManager().formatting(new DocumentFormattingParams(doc(b), o)).get(WAIT_S, TimeUnit.SECONDS);
        ChangeSet cs = new ChangeSet();
        if (edits == null || edits.isEmpty()) return cs;
        List<TextEditApply.Edit> list = new ArrayList<>();
        for (Object e : edits) { TextEdit te = asTextEdit(e); if (te != null) list.add(convert(te)); }
        cs.edits = list.size();
        String current = source.textOf(b.guestPath);
        if (current != null) {
            String result = TextEditApply.apply(current, list);
            if (!result.equals(current)) cs.newText.put(b.guestPath, result);
        }
        return cs;
    }

    /** Where current text comes from: the editor for open files, the disk otherwise. */
    interface TextSource { String textOf(String guestPath); }

    /** A plain TextEdit, whether the library hands it over directly or wrapped in an Either. */
    private static TextEdit asTextEdit(Object o) {
        if (o instanceof TextEdit) return (TextEdit) o;
        if (o instanceof Either) {
            Either<?, ?> e = (Either<?, ?>) o;
            Object l = e.isLeft() ? e.getLeft() : e.getRight();
            if (l instanceof TextEdit) return (TextEdit) l;
        }
        return null;
    }

    private static TextEditApply.Edit convert(TextEdit e) {
        return new TextEditApply.Edit(e.getRange().getStart().getLine(), e.getRange().getStart().getCharacter(),
                e.getRange().getEnd().getLine(), e.getRange().getEnd().getCharacter(), e.getNewText());
    }

    private ChangeSet toChangeSet(WorkspaceEdit we, TextSource source) {
        ChangeSet cs = new ChangeSet();
        if (we == null) return cs;
        Map<String, List<TextEditApply.Edit>> byFile = new LinkedHashMap<>();
        boolean haveDocumentChanges = we.getDocumentChanges() != null && !we.getDocumentChanges().isEmpty();
        if (!haveDocumentChanges && we.getChanges() != null) {      // documentChanges supersedes changes
            for (Map.Entry<String, List<TextEdit>> e : we.getChanges().entrySet()) {
                String path = GuestPaths.fromUri(e.getKey());
                if (path == null) continue;
                List<TextEditApply.Edit> l = byFile.computeIfAbsent(path, k -> new ArrayList<>());
                for (TextEdit te : e.getValue()) l.add(convert(te));
            }
        }
        if (haveDocumentChanges) {
            for (Either<TextDocumentEdit, org.eclipse.lsp4j.ResourceOperation> dc : we.getDocumentChanges()) {
                if (!dc.isLeft()) continue;                           // create / rename / delete of files: not handled
                TextDocumentEdit tde = dc.getLeft();
                String path = GuestPaths.fromUri(tde.getTextDocument().getUri());
                if (path == null) continue;
                List<TextEditApply.Edit> l = byFile.computeIfAbsent(path, k -> new ArrayList<>());
                for (Object o : (List<?>) tde.getEdits()) { TextEdit te = asTextEdit(o); if (te != null) l.add(convert(te)); }
            }
        }
        for (Map.Entry<String, List<TextEditApply.Edit>> e : byFile.entrySet()) {
            String current = source.textOf(e.getKey());
            if (current == null) continue;
            cs.edits += e.getValue().size();
            cs.newText.put(e.getKey(), TextEditApply.apply(current, e.getValue()));
        }
        return cs;
    }

    File rootfs() { return rootfs; }
}
