package com.zed.nullshell;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Brings a file into the chat: copies it to private storage, checks the size and decides how it is sent. */
final class ChatAttachments {
    private ChatAttachments() { }

    /** A file picked for the next message. */
    static final class Pending {
        String name, mime;
        long size;
        File file;
    }

    private static File pendingDir(Context c) {
        File d = new File(c.getCacheDir(), "ai_pending");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static void clearPendingFiles(Context c) {
        File[] fs = pendingDir(c).listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    private static String safe(String name) {
        String n = name == null || name.isEmpty() ? "file" : name;
        n = n.replaceAll("[^A-Za-z0-9._-]", "_");
        return n.length() > 80 ? n.substring(n.length() - 80) : n;
    }

    /** A file the person picked through the system picker (photos, Drive, downloads...). */
    static Pending fromUri(Context c, Uri uri) throws IOException {
        String name = null;
        long size = -1;
        try (Cursor q = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (q != null && q.moveToFirst()) {
                name = q.getString(0);
                if (!q.isNull(1)) size = q.getLong(1);
            }
        } catch (RuntimeException ignored) { }
        if (name == null || name.isEmpty()) name = uri.getLastPathSegment() == null ? "file" : uri.getLastPathSegment();
        if (size > AttachKind.MAX_FILE) throw tooBig(name);
        File dest = new File(pendingDir(c), System.nanoTime() + "_" + safe(name));
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Couldn't open " + name);
            copy(in, dest, name);
        } catch (SecurityException e) {
            throw new IOException("No permission to read " + name);
        }
        return classify(c, dest, name, c.getContentResolver().getType(uri));
    }

    /** A file picked in the app's own file manager, given as a Kali path (Kali files or /sdcard). */
    static Pending fromGuestPath(Context c, String guest) throws IOException {
        File src = null;
        if (guest.equals("/sdcard") || guest.startsWith("/sdcard/")) {
            if (Prefs.bindSdcard(c)) src = new File(Environment.getExternalStorageDirectory(), guest.length() > 8 ? guest.substring(8) : "");
        }
        if (src == null) src = GuestPaths.toHost(RootfsManager.dir(c), guest, true);
        if (src == null || !src.exists()) throw new IOException("Couldn't find " + guest);
        if (src.isDirectory()) throw new IOException("That is a folder. Pick a file.");
        if (!src.canRead()) throw new IOException("Can't read " + src.getName() + ". Allow access to phone storage in Settings.");
        if (src.length() > AttachKind.MAX_FILE) throw tooBig(src.getName());
        File dest = new File(pendingDir(c), System.nanoTime() + "_" + safe(src.getName()));
        try (InputStream in = new java.io.FileInputStream(src)) { copy(in, dest, src.getName()); }
        return classify(c, dest, src.getName(), null);
    }

    private static IOException tooBig(String name) {
        return new IOException(name + " is too large (the limit is " + AttachKind.humanSize(AttachKind.MAX_FILE) + " per file).");
    }

    private static void copy(InputStream in, File dest, String name) throws IOException {
        long total = 0;
        byte[] buf = new byte[65536];
        try (OutputStream out = new FileOutputStream(dest)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > AttachKind.MAX_FILE) { out.close(); dest.delete(); throw tooBig(name); }
                out.write(buf, 0, n);
            }
        }
    }

    private static Pending classify(Context c, File f, String name, String providerMime) throws IOException {
        String mime = AttachKind.mimeFor(name);
        if (mime == null && providerMime != null && (providerMime.startsWith("image/") || providerMime.startsWith("audio/") || providerMime.startsWith("video/")
                || providerMime.equals("application/pdf"))) mime = providerMime;
        if (mime == null) {
            byte[] head = new byte[8192];
            int n;
            try (InputStream in = new java.io.FileInputStream(f)) { n = in.read(head); }
            if (AttachKind.looksLikeText(head, Math.max(0, n))) mime = AttachKind.TEXT;
        }
        if (mime == null) {
            f.delete();
            throw new IOException(name + ": this type of file can't be sent. Images, PDFs, audio, video and text or code files work.");
        }
        Pending p = new Pending();
        p.name = name; p.mime = mime; p.file = f; p.size = f.length();
        return p;
    }

    /** Moves a picked file into permanent storage for a message and returns the new location. */
    static File store(Context c, Pending p, long msgId, int index) throws IOException {
        File dest = new File(ChatStore.filesDir(c), msgId + "_" + index + "_" + safe(p.name));
        if (!p.file.renameTo(dest)) {
            try (InputStream in = new java.io.FileInputStream(p.file)) { copy(in, dest, p.name); }
            p.file.delete();
        }
        return dest;
    }
}
