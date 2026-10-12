package com.zed.nullshell;

import io.github.rosemoe.sora.lsp.editor.LspEditor;
import io.github.rosemoe.sora.widget.CodeEditor;

import android.net.Uri;

import java.io.File;

/**
 * One open file. Every buffer owns its own CodeEditor view: that keeps undo history, scroll position, language
 * and the language-server bindings of different files from ever mixing (an LspEditor subscribes to its view's events).
 */
final class EditorBuffer {
    final String guestPath;
    final File hostFile;
    final LanguageSupport.Lang lang;
    CodeEditor editor;
    boolean modified;
    boolean crlf;
    long lastShown;              // uptime ms when the tab was last selected; decides which tab to unload first
    boolean external;            // a file outside Kali (phone storage): no language server
    Uri sourceUri;               // document opened from another app; saving also writes back to it
    boolean large;               // large-file mode: no highlighting, no language server, no wrapping
    long diskModified;           // mtime when last read or written
    int cursorLine, cursorColumn;
    String waitToken;            // set when a terminal command is waiting for this file to close

    LspEditor lsp;
    String lspProjectRoot;
    LspManager.State lspState = LspManager.State.OFF;
    String lspDetail = "";
    boolean lspSuspended;        // server was stopped to free memory; restarted when the editor is used again

    EditorBuffer(String guestPath, File hostFile, LanguageSupport.Lang lang) {
        this.guestPath = guestPath;
        this.hostFile = hostFile;
        this.lang = lang;
    }

    String title() { return GuestPaths.name(guestPath); }

    /** What the title bar shows: the phone path for external files, the Kali path otherwise. */
    String displayPath() { return external ? hostFile.getPath() : guestPath; }
}
