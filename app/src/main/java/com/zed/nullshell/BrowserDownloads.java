package com.zed.nullshell;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.URLUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

/**
 * Plain downloader for the browser. It goes through the same proxy and trusts the same imported CAs
 * as the pages do (the system DownloadManager would do neither). Files land in the Kali home
 * (/root/Downloads) when Kali is installed, so tools can use them right away.
 */
final class BrowserDownloads {
    private BrowserDownloads() { }

    interface Listener {
        /** guestPath is null when the file was saved outside Kali. */
        void onDone(File file, String guestPath, boolean looksLikeCertificate);
        void onError(String message);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void start(final Context ctx, final String url, final String userAgent, final String disposition,
                      final String mime, final Listener l) {
        final Context c = ctx.getApplicationContext();
        Thread t = new Thread(() -> run(c, url, userAgent, disposition, mime, l), "browser-download");
        t.setDaemon(true);
        t.start();
    }

    private static void run(Context c, String url, String ua, String disposition, String mime, Listener l) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            if (!"http".equals(u.getProtocol()) && !"https".equals(u.getProtocol())) throw new IllegalArgumentException("Only http(s) downloads are supported");
            installProxyAuth(c);
            conn = (HttpURLConnection) u.openConnection(BrowserProxy.javaProxy(c, u.getHost()));
            if (conn instanceof HttpsURLConnection) ((HttpsURLConnection) conn).setSSLSocketFactory(BrowserCerts.sslContext(c).getSocketFactory());
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            if (ua != null && !ua.isEmpty()) conn.setRequestProperty("User-Agent", ua);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            int code = conn.getResponseCode();
            if (code >= 400) throw new java.io.IOException("Server answered HTTP " + code);

            String cd = conn.getHeaderField("Content-Disposition");
            String type = conn.getContentType() != null ? conn.getContentType() : mime;
            String name = sanitize(URLUtil.guessFileName(conn.getURL().toString(), cd != null ? cd : disposition, type));

            File dir = targetDir(c);
            File out = unique(dir, name);
            try (InputStream in = conn.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            final File done = out;
            final String guest = guestPath(c, done);
            final boolean cert = looksLikeCert(done.getName(), type);
            MAIN.post(() -> l.onDone(done, guest, cert));
        } catch (final Exception e) {
            final String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            MAIN.post(() -> l.onError(msg));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static void installProxyAuth(Context c) {
        final String user = BrowserPrefs.proxyUser(c), pass = BrowserPrefs.proxyPass(c);
        if (!BrowserProxy.active(c) || user.isEmpty()) return;
        Authenticator.setDefault(new Authenticator() {
            @Override protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() == RequestorType.PROXY) return new PasswordAuthentication(user, pass.toCharArray());
                return null;
            }
        });
    }

    static File targetDir(Context c) {
        File dir;
        if (RootfsManager.isInstalled(c)) {
            dir = new File(RootfsManager.dir(c), "root/Downloads");
        } else {
            dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = new File(c.getFilesDir(), "downloads");
        }
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static String guestPath(Context c, File f) {
        File root = new File(RootfsManager.dir(c), "root/Downloads");
        return f.getParentFile() != null && f.getParentFile().equals(root) ? "/root/Downloads/" + f.getName() : null;
    }

    private static String sanitize(String name) {
        String n = new File(name == null ? "" : name).getName().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        while (n.startsWith(".")) n = n.substring(1);
        return n.isEmpty() ? "download" : n;
    }

    static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    private static boolean looksLikeCert(String name, String mime) {
        String n = name.toLowerCase(Locale.US);
        String m = mime == null ? "" : mime.toLowerCase(Locale.US);
        return m.contains("x509") || m.contains("pkix-cert") || n.endsWith(".crt") || n.endsWith(".cer")
                || n.endsWith(".der") || n.endsWith(".pem");
    }
}
