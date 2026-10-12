package com.zed.nullshell;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Entry point for other apps ("Open with", "Share", "Edit"). It has no screen: it checks what it was given and
 * hands only a URI or some text to the editor, which itself is not exported. The editor's own launch extras
 * (a path, a wait token) can therefore never be set from outside.
 */
public class OpenInEditorActivity extends Activity {
    private static final int MAX_TEXT = 400000;     // intents travel through Binder, which has a 1 MB limit

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent in = getIntent();
        Uri uri = null;
        String text = null;
        if (Intent.ACTION_SEND.equals(in.getAction())) {
            uri = streamOf(in);
            text = in.getStringExtra(Intent.EXTRA_TEXT);
        } else {
            uri = in.getData();
        }
        if (uri == null && (text == null || text.isEmpty())) {
            Toast.makeText(this, "Nothing to open", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (!RootfsManager.isInstalled(this)) {
            Toast.makeText(this, "Install Kali in NullShell first", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Intent out = new Intent(this, EditorActivity.class);
        out.setAction(EditorActivity.ACTION_OPEN_URI);
        if (uri != null) {
            out.setData(uri);
            out.setClipData(ClipData.newRawUri("", uri));
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            out.addFlags(flags);
        }
        if (text != null) out.putExtra(Intent.EXTRA_TEXT, text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text);
        startActivity(out);
        finish();
    }

    @SuppressWarnings("deprecation")
    private static Uri streamOf(Intent in) {
        Object o = in.getParcelableExtra(Intent.EXTRA_STREAM);
        return o instanceof Uri ? (Uri) o : null;
    }
}
