package com.zed.nullshell;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Routes every WebView request through the configured proxy (mitmproxy, Caido, Burp...).
 * The override is process-wide, not per tab. There is no fallback to a direct connection: if the
 * proxy is down, pages fail instead of leaking around it.
 */
final class BrowserProxy {
    private BrowserProxy() { }

    /** Human-readable reason the last apply() did not take effect, or null. */
    static volatile String lastError;

    static boolean supported() {
        try { return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE); }
        catch (Throwable t) { return false; }
    }

    static boolean active(Context c) {
        return BrowserPrefs.proxyOn(c) && !BrowserPrefs.proxyHost(c).isEmpty();
    }

    /** Everything that changes the effective proxy, so callers can tell when to re-apply. */
    static String signature(Context c) {
        return active(c) + "|" + url(c) + "|" + BrowserPrefs.proxyBypass(c) + "|" + BrowserPrefs.proxyLoopback(c);
    }

    static String url(Context c) {
        String host = BrowserPrefs.proxyHost(c);
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return BrowserPrefs.proxyScheme(c) + "://" + host + ":" + BrowserPrefs.proxyPort(c);
    }

    static String label(Context c) {
        return BrowserPrefs.SCHEME_NAMES[indexOf(BrowserPrefs.proxyScheme(c))] + " "
                + BrowserPrefs.proxyHost(c) + ":" + BrowserPrefs.proxyPort(c);
    }

    private static int indexOf(String scheme) {
        for (int i = 0; i < BrowserPrefs.SCHEMES.length; i++) if (BrowserPrefs.SCHEMES[i].equals(scheme)) return i;
        return 0;
    }

    /** Applies the current settings. {@code done} runs on the main thread exactly once, even on failure. */
    static void apply(Context c, final Runnable done) {
        final Handler main = new Handler(Looper.getMainLooper());
        final AtomicBoolean fired = new AtomicBoolean();
        final Runnable once = () -> { if (fired.compareAndSet(false, true) && done != null) done.run(); };
        final Executor exec = main::post;
        lastError = null;
        if (!supported()) {
            if (active(c)) lastError = "This WebView version has no proxy support. Update Android System WebView.";
            main.post(once);
            return;
        }
        main.postDelayed(once, 2500);   // safety net if the WebView never calls back
        try {
            if (!active(c)) {
                ProxyController.getInstance().clearProxyOverride(exec, once);
                return;
            }
            ProxyConfig.Builder b = new ProxyConfig.Builder().addProxyRule(url(c));
            for (String rule : BrowserPrefs.proxyBypass(c).split("[,;\\s]+")) {
                if (!rule.isEmpty()) b.addBypassRule(rule);
            }
            if (BrowserPrefs.proxyLoopback(c)) b.addBypassRule("<-loopback>");   // also proxy localhost / 127.0.0.1
            ProxyController.getInstance().setProxyOverride(b.build(), exec, once);
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            main.post(once);
        }
    }

    /** The same proxy for java.net connections (downloads). */
    static Proxy javaProxy(Context c, String targetHost) {
        if (!active(c)) return Proxy.NO_PROXY;
        boolean loop = "localhost".equalsIgnoreCase(targetHost) || "127.0.0.1".equals(targetHost) || "::1".equals(targetHost)
                || "[::1]".equals(targetHost);
        if (loop && !BrowserPrefs.proxyLoopback(c)) return Proxy.NO_PROXY;
        Proxy.Type type = "socks5".equals(BrowserPrefs.proxyScheme(c)) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        return new Proxy(type, InetSocketAddress.createUnresolved(BrowserPrefs.proxyHost(c), BrowserPrefs.proxyPort(c)));
    }
}
