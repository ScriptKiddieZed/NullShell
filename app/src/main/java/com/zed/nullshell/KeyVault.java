package com.zed.nullshell;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keeps the Gemini API key encrypted with a key that lives in the Android Keystore (it never leaves the secure hardware/OS store). */
final class KeyVault {
    private KeyVault() { }

    private static final String ALIAS = "nullshell_ai_key";
    private static final String PREF_ENC = "ai_key_enc", PREF_PLAIN = "ai_key_plain";

    static boolean has(Context c) { return !get(c).isEmpty(); }

    static String get(Context c) {
        android.content.SharedPreferences sp = Prefs.sp(c);
        String enc = sp.getString(PREF_ENC, "");
        if (!enc.isEmpty()) {
            try {
                byte[] all = Base64.decode(enc, Base64.NO_WRAP);
                byte[] iv = java.util.Arrays.copyOfRange(all, 0, 12);
                byte[] ct = java.util.Arrays.copyOfRange(all, 12, all.length);
                Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
                ci.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
                return new String(ci.doFinal(ct), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "";   // keystore was reset (restore, lock-screen change): the key has to be entered again
            }
        }
        return sp.getString(PREF_PLAIN, "");
    }

    static void set(Context c, String key) {
        key = key == null ? "" : key.trim();
        android.content.SharedPreferences.Editor ed = Prefs.sp(c).edit();
        if (key.isEmpty()) {
            ed.remove(PREF_ENC).remove(PREF_PLAIN).apply();
            return;
        }
        try {
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = ci.getIV();
            byte[] ct = ci.doFinal(key.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            ed.putString(PREF_ENC, Base64.encodeToString(all, Base64.NO_WRAP)).remove(PREF_PLAIN).apply();
        } catch (Exception e) {
            // No usable keystore on this device: fall back to the app's private storage rather than losing the key.
            ed.putString(PREF_PLAIN, key).remove(PREF_ENC).apply();
        }
    }

    /** "AIza…x7Qd" style hint so the screen can show a key is saved without revealing it. */
    static String masked(String key) {
        if (key == null || key.length() < 10) return key == null || key.isEmpty() ? "" : "••••";
        return key.substring(0, 4) + "••••••••" + key.substring(key.length() - 4);
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }
}
