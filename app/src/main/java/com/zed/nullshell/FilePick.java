package com.zed.nullshell;

import android.app.Activity;
import android.content.Intent;

/** Asks the file manager for a file or folder and hands the Kali path to a callback. */
final class FilePick {
    private FilePick() { }

    static final int REQUEST = 7731;

    interface Callback { void picked(String guestPath); }

    private static Callback pending;

    static void request(Activity a, boolean folder, String startGuestDir, Callback cb) {
        pending = cb;
        FileManagerActivity.pick(a, REQUEST, folder, startGuestDir);
    }

    /** Call from Activity.onActivityResult; returns true if the result was ours. */
    static boolean handle(int req, int res, Intent data) {
        if (req != REQUEST) return false;
        Callback cb = pending;
        pending = null;
        if (cb != null && res == Activity.RESULT_OK && data != null) {
            String p = data.getStringExtra(FileManagerActivity.RESULT_PATH);
            if (p != null) cb.picked(p);
        }
        return true;
    }
}
