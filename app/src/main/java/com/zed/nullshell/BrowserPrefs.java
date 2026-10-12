package com.zed.nullshell;

import android.content.Context;
import android.content.SharedPreferences;

/** Browser settings, stored in the same "settings" preferences as everything else. */
final class BrowserPrefs {
    private BrowserPrefs() { }

    static final String PROXY_ON = "br_proxy_on";
    static final String PROXY_SCHEME = "br_proxy_scheme";
    static final String PROXY_HOST = "br_proxy_host";
    static final String PROXY_PORT = "br_proxy_port";
    static final String PROXY_USER = "br_proxy_user";
    static final String PROXY_PASS = "br_proxy_pass";
    static final String PROXY_BYPASS = "br_proxy_bypass";
    static final String PROXY_LOOPBACK = "br_proxy_loopback";
    static final String SEARCH = "br_search";
    static final String JS = "br_js";
    static final String DEBUG = "br_debug";
    static final String TABS = "br_tabs";
    static final String ACTIVE = "br_active";
    static final String LIVE = "br_live";
    static final String DEVTOOLS = "br_devtools";

    static final String DEFAULT_SEARCH = "https://duckduckgo.com/?q=%s";
    static final String[] SCHEMES = {"http", "https", "socks5"};
    static final String[] SCHEME_NAMES = {"HTTP", "HTTPS", "SOCKS5"};

    static final String FS_LANDSCAPE = "br_fs_landscape";
    static boolean fullscreenLandscape(Context c) { return sp(c).getBoolean(FS_LANDSCAPE, true); }

    private static SharedPreferences sp(Context c) { return Prefs.sp(c); }

    static boolean proxyOn(Context c) { return sp(c).getBoolean(PROXY_ON, false); }
    static String proxyScheme(Context c) {
        String s = sp(c).getString(PROXY_SCHEME, "http");
        for (String known : SCHEMES) if (known.equals(s)) return s;
        return "http";
    }
    static String proxyHost(Context c) { return sp(c).getString(PROXY_HOST, "127.0.0.1").trim(); }
    static int proxyPort(Context c) {
        int p = sp(c).getInt(PROXY_PORT, 8080);
        return p < 1 || p > 65535 ? 8080 : p;
    }
    static String proxyUser(Context c) { return sp(c).getString(PROXY_USER, ""); }
    static String proxyPass(Context c) { return sp(c).getString(PROXY_PASS, ""); }
    static String proxyBypass(Context c) { return sp(c).getString(PROXY_BYPASS, "").trim(); }
    static boolean proxyLoopback(Context c) { return sp(c).getBoolean(PROXY_LOOPBACK, false); }

    static String searchUrl(Context c) {
        String s = sp(c).getString(SEARCH, DEFAULT_SEARCH).trim();
        return s.contains("%s") ? s : DEFAULT_SEARCH;
    }
    /** Tabs that keep a live WebView at once; the others are saved and rebuilt when selected. */
    static int liveTabs(Context c) { return Math.max(1, Math.min(6, sp(c).getInt(LIVE, 3))); }
    /** Whether the developer tools agent is injected into pages (turned on the first time the tools are opened). */
    static boolean devtools(Context c) { return sp(c).getBoolean(DEVTOOLS, false); }
    static boolean javascript(Context c) { return sp(c).getBoolean(JS, true); }
    static boolean debugging(Context c) { return sp(c).getBoolean(DEBUG, false); }
}
