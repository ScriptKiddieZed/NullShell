package com.zed.nullshell;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/**
 * Hands single files to other apps ("Open with", "Share"). Only files inside the Kali filesystem or on
 * shared storage are served, read-only, and only through URIs this app granted explicitly.
 */
public class FileShareProvider extends ContentProvider {
    static final String AUTHORITY = "com.zed.nullshell.files";

    static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("f").appendPath(f.getName())
                .appendQueryParameter("p", f.getAbsolutePath()).build();
    }

    static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.US));
        return m != null ? m : "application/octet-stream";
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String p = uri.getQueryParameter("p");
        if (p == null || getContext() == null) throw new FileNotFoundException();
        try {
            File f = new File(p).getCanonicalFile();
            File kali = RootfsManager.dir(getContext()).getCanonicalFile();
            File shared = Environment.getExternalStorageDirectory().getCanonicalFile();
            boolean allowed = f.getPath().startsWith(kali.getPath() + File.separator) || f.getPath().startsWith(shared.getPath() + File.separator);
            if (!allowed || !f.isFile()) throw new FileNotFoundException();
            return f;
        } catch (IOException e) {
            throw new FileNotFoundException();
        }
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("read-only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        String p = uri.getQueryParameter("p");
        return p == null ? null : mimeOf(new File(p).getName());
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File f = resolve(uri);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
