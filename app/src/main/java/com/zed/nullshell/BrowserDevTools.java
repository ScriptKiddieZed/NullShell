package com.zed.nullshell;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/**
 * Developer tools for the browser. A small agent script runs inside every page (console, errors, fetch/XHR/WebSocket,
 * timing, DOM and CSS inspection, storage) and reports to this class, which also contributes what only the app can see
 * (every request the WebView makes, cookies, the TLS certificate) and runs the panel itself: a second WebView that shows
 * bundled HTML (assets/devtools) and talks to the app through one bridge.
 *
 * Everything coming from a page is untrusted. Messages are parsed and re-serialised before they go anywhere near the panel,
 * and the panel only ever receives them as data (JSON.parse of a quoted string), never as code.
 */
final class BrowserDevTools {
    static final String PAGE = "https://appassets.androidplatform.net/assets/devtools/index.html";
    private static final int MAX_EVENTS = 6000, MAX_MSG = 600000;
    private static final long MAX_CHARS = 8000000L, MAX_BODY = 3L * 1024 * 1024;
    private static final Pattern BROWSER_MSG = Pattern.compile("^(Failed to load resource|Access to .* has been blocked|Mixed Content|Refused to|Blocked a frame|Unsafe attempt|Cross-Origin|\\[Deprecation\\]|\\[Violation\\]|Error with Permissions-Policy|The resource .* was preloaded|Uncaught SecurityError).*", Pattern.DOTALL);
    private static final Pattern TEXTUAL = Pattern.compile("(?i).*(text/|json|xml|javascript|ecmascript|html|x-www-form-urlencoded|svg|graphql|yaml).*");

    interface Host {
        Activity activity();
        WebView currentWeb();
        Object currentKey();
        String currentUrl();
        String currentTitle();
        void forEachWeb(BiConsumer<Object, WebView> c);
        void openTab(String url);
        void setDock(String mode);          // "bottom", "right", "full", "cycle", or null to show again where it was
        void closePanel();
        void applyDeviceSettings();         // user agent, size and toggles changed: re-apply to every tab and reload
        void reloadCurrent(boolean hard);
    }

    private static final class Data {
        final ArrayDeque<String> ev = new ArrayDeque<>();
        long chars;
        boolean installed, agentReady;
        ScriptHandler handler;
    }

    private final Host host;
    private final Activity act;
    private final FrameLayout panelHost;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "devtools-io"); t.setDaemon(true); return t; });
    private final WebViewAssetLoader loader;
    private final Map<Object, Data> data = new WeakHashMap<>();
    private final Set<String> agentPending = new HashSet<>();     // main thread only
    private final List<String> outbox = new ArrayList<>();        // main thread only
    private boolean flushing;
    private WebView panel;
    private boolean panelReady;
    private volatile Object curKey;
    private String agentSrc;
    private int reqSeq;

    private boolean enabled;
    boolean preserve, noCache, noJs, noImages, remote;
    private String ua = "";
    private int vpW, vpH;

    BrowserDevTools(Host host, FrameLayout panelHost) {
        this.host = host;
        this.act = host.activity();
        this.panelHost = panelHost;
        this.enabled = BrowserPrefs.devtools(act);
        this.remote = BrowserPrefs.debugging(act);
        this.loader = new WebViewAssetLoader.Builder().addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(act)).build();
    }

    // ------------------------------------------------------------------ state shared with the activity

    String userAgent() { return ua; }
    int viewportW() { return vpW; }
    int viewportH() { return vpH; }
    boolean isOpen() { return panel != null; }
    boolean isEnabled() { return enabled; }

    /** Applies the tool toggles to one page view. */
    void configure(WebView w) {
        WebSettings s = w.getSettings();
        s.setCacheMode(noCache ? WebSettings.LOAD_NO_CACHE : WebSettings.LOAD_DEFAULT);
        s.setJavaScriptEnabled(!noJs && BrowserPrefs.javascript(act));
        s.setBlockNetworkImage(noImages);
    }

    private String agent() {
        if (agentSrc == null) {
            try (InputStream in = act.getAssets().open("devtools/agent.js")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                agentSrc = new String(out.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                agentSrc = "";
            }
        }
        return agentSrc;
    }

    private synchronized Data dataOf(Object key) {
        Data d = data.get(key);
        if (d == null) { d = new Data(); data.put(key, d); }
        return d;
    }

    // ------------------------------------------------------------------ attaching to pages

    /** Called for every new page view; does nothing until the tools have been turned on. */
    void prepare(WebView w, Object key) {
        configure(w);
        if (enabled) install(w, key);
    }

    private void install(final WebView w, final Object key) {
        Data d = dataOf(key);
        if (d.installed) return;
        d.installed = true;
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(w, "__nsdt", Collections.singleton("*"),
                        (view, message, origin, isMainFrame, reply) -> { String s = message.getData(); onAgent(key, s, isMainFrame); });
            } else {
                w.addJavascriptInterface(new Legacy(key), "__nsdtj");
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                d.handler = WebViewCompat.addDocumentStartJavaScript(w, agent(), Collections.singleton("*"));
            }
        } catch (Throwable t) {
            d.handler = null;
        }
    }

    private void uninstall(WebView w, Object key) {
        Data d = dataOf(key);
        if (!d.installed) return;
        d.installed = false;
        try { if (d.handler != null) d.handler.remove(); } catch (Throwable ignored) { }
        d.handler = null;
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(w, "__nsdt");
            else w.removeJavascriptInterface("__nsdtj");
        } catch (Throwable ignored) { }
    }

    private final class Legacy {
        private final Object key;
        Legacy(Object key) { this.key = key; }
        @JavascriptInterface public void post(final String s) { ui.post(() -> onAgent(key, s, true)); }
    }

    private void enable() {
        enabled = true;
        Prefs.sp(act).edit().putBoolean(BrowserPrefs.DEVTOOLS, true).apply();
        host.forEachWeb((k, w) -> install(w, k));
        host.reloadCurrent(false);                       // the agent can only watch from the start of a page
    }

    private void disable() {
        enabled = false;
        Prefs.sp(act).edit().putBoolean(BrowserPrefs.DEVTOOLS, false).apply();
        host.forEachWeb((k, w) -> uninstall(w, k));
        close();
        host.reloadCurrent(false);
    }

    // ------------------------------------------------------------------ events from pages and from the WebView

    private void onAgent(Object key, String msg, boolean mainFrame) {
        if (msg == null || msg.length() > MAX_MSG) return;
        final JSONObject o;
        try { o = new JSONObject(msg); } catch (JSONException e) { return; }
        String t = o.optString("t");
        Data d = dataOf(key);
        switch (t) {
            case "rpc": {
                if (!mainFrame) return;
                String id = o.optString("id");
                if (!agentPending.remove(id)) return;
                Object r = o.opt("r");
                boolean ok = o.optBoolean("ok");
                reply(id, ok, ok ? jsonText(r) : String.valueOf(r));
                return;
            }
            case "ready":
                if (mainFrame) d.agentReady = true;
                return;
            case "picked": case "domchange":
                if (!mainFrame) return;
                break;
            case "con": case "conclear": case "net": case "ws": case "perf": case "vital": case "life":
                break;
            default:
                return;
        }
        record(key, o.toString());
    }

    private static String jsonText(Object r) {
        if (r == null || r == JSONObject.NULL) return "null";
        if (r instanceof String) return JSONObject.quote((String) r);
        return String.valueOf(r);                                    // objects, arrays, numbers and booleans print as JSON
    }

    private void record(Object key, final String json) {
        synchronized (this) {
            Data d = dataOf(key);
            d.ev.add(json);
            d.chars += json.length();
            while ((d.chars > MAX_CHARS || d.ev.size() > MAX_EVENTS) && !d.ev.isEmpty()) d.chars -= d.ev.poll().length();
        }
        if (key == curKey) ui.post(() -> { if (panelReady) { outbox.add(json); scheduleFlush(); } });
    }

    /** Called from the WebView's request hook (a background thread). */
    void onRequest(Object key, WebResourceRequest r) {
        if (!enabled) return;
        String url = r.getUrl().toString();
        if (url.startsWith("data:") || url.startsWith("blob:") || url.contains("appassets.androidplatform.net")) return;
        try {
            JSONObject o = new JSONObject();
            o.put("t", "req").put("id", "n" + (++reqSeq)).put("url", url).put("method", r.getMethod()).put("main", r.isForMainFrame()).put("ts", System.currentTimeMillis());
            JSONArray hs = new JSONArray();
            Map<String, String> h = r.getRequestHeaders();
            if (h != null) for (Map.Entry<String, String> e : h.entrySet()) hs.put(new JSONArray().put(e.getKey()).put(e.getValue()));
            o.put("h", hs);
            record(key, o.toString());
        } catch (JSONException ignored) { }
    }

    void onHttpError(Object key, WebResourceRequest r, WebResourceResponse resp) {
        if (!enabled) return;
        try {
            JSONObject o = new JSONObject();
            o.put("t", "nres").put("url", r.getUrl().toString()).put("status", resp.getStatusCode()).put("st", resp.getReasonPhrase());
            JSONArray hs = new JSONArray();
            Map<String, String> h = resp.getResponseHeaders();
            if (h != null) for (Map.Entry<String, String> e : h.entrySet()) hs.put(new JSONArray().put(e.getKey()).put(e.getValue()));
            o.put("h", hs);
            record(key, o.toString());
        } catch (JSONException ignored) { }
    }

    /** Messages only the browser itself produces (blocked requests, CORS, mixed content); everything else comes from the agent. */
    void onConsole(Object key, ConsoleMessage m) {
        if (!enabled || m.message() == null || !BROWSER_MSG.matcher(m.message()).matches()) return;
        try {
            String lvl = m.messageLevel() == ConsoleMessage.MessageLevel.ERROR ? "error" : m.messageLevel() == ConsoleMessage.MessageLevel.WARNING ? "warn" : "info";
            record(key, new JSONObject().put("t", "ncon").put("l", lvl).put("text", m.message()).put("src", m.sourceId()).put("line", m.lineNumber()).toString());
        } catch (JSONException ignored) { }
    }

    /** A new page started loading in the main frame. */
    void onPageStarted(Object key, WebView w, String url) {
        if (!enabled) return;
        Data d = dataOf(key);
        d.agentReady = false;
        if (!preserve) { synchronized (this) { d.ev.clear(); d.chars = 0; } }
        try { record(key, new JSONObject().put("t", "nav").put("url", url).put("ts", System.currentTimeMillis()).toString()); } catch (JSONException ignored) { }
        if (d.installed && d.handler == null) w.evaluateJavascript(agent(), null);        // no document-start support: inject as early as we can
        if (key == curKey && panelReady) { updateInfo(); }
    }

    /** The page view of a tab was released (memory) or replaced; the next view must be set up again. */
    void detached(Object key) {
        Data d = dataOf(key);
        d.installed = false;
        d.handler = null;
        d.agentReady = false;
    }

    void forget(Object key) {
        synchronized (this) { data.remove(key); }
    }

    // ------------------------------------------------------------------ the panel

    void toggle() { if (isOpen()) close(); else open(); }

    void open() {
        if (panel != null) return;
        if (!enabled) enable();
        createPanel();
        host.setDock(null);
    }

    void close() {
        if (panel == null) return;
        WebView p = panel;
        panel = null;
        panelReady = false;
        panelHost.removeView(p);
        p.stopLoading();
        p.removeJavascriptInterface("NSDT");
        p.destroy();
        outbox.clear();
        host.closePanel();
    }

    void destroy() { close(); }

    private void createPanel() {
        WebView v = new WebView(act);
        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSafeBrowsingEnabled(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        v.setBackgroundColor(0xFF0E1013);
        v.addJavascriptInterface(new Bridge(), "NSDT");
        v.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse r = loader.shouldInterceptRequest(request.getUrl());
                // the panel never needs the network; answering here keeps stray requests away from the proxy
                return r != null ? r : new WebResourceResponse("text/plain", "utf-8", 404, "Not found", new java.util.HashMap<String, String>(), new java.io.ByteArrayInputStream(new byte[0]));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return true; }      // the panel never navigates
        });
        panel = v;
        panelHost.addView(v, new FrameLayout.LayoutParams(-1, -1));
        v.loadUrl(PAGE);
    }

    private final class Bridge {
        @JavascriptInterface public void call(final String id, final String method, final String args) { ui.post(() -> dispatch(id, method, args)); }
        @JavascriptInterface public void ready() { ui.post(BrowserDevTools.this::onPanelReady); }
        @JavascriptInterface public void log(String s) { android.util.Log.d("NullShellDevTools", String.valueOf(s)); }
    }

    private void onPanelReady() {
        if (panel == null) return;
        panelReady = true;
        sync();
    }

    /** Shows the current tab's recorded events in the panel. */
    void setCurrent(Object key) {
        curKey = key;
        if (panelReady) sync();
    }

    private void sync() {
        if (!panelReady) return;
        outbox.clear();
        updateInfo();
        pushState();
        List<String> snapshot;
        synchronized (this) { snapshot = new ArrayList<>(dataOf(curKey).ev); }
        for (int i = 0; i < snapshot.size(); i += 300) {
            evalPanel("NS.push(" + JSONObject.quote("[" + join(snapshot.subList(i, Math.min(snapshot.size(), i + 300))) + "]") + ")");
        }
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append(','); sb.append(l.get(i)); }
        return sb.toString();
    }

    private void updateInfo() {
        try {
            JSONObject o = new JSONObject().put("id", System.identityHashCode(curKey)).put("url", host.currentUrl()).put("title", host.currentTitle());
            evalPanel("NS.reset(" + JSONObject.quote(o.toString()) + ")");
        } catch (JSONException ignored) { }
    }

    private void pushState() {
        try {
            JSONObject s = new JSONObject().put("preserve", preserve).put("noCache", noCache).put("noJs", noJs).put("noImages", noImages).put("remote", remote)
                    .put("ua", ua).put("vpW", vpW).put("vpH", vpH);
            evalPanel("NS.state(" + JSONObject.quote(s.toString()) + ")");
        } catch (JSONException ignored) { }
    }

    private void scheduleFlush() {
        if (flushing) return;
        flushing = true;
        ui.postDelayed(() -> {
            flushing = false;
            if (!panelReady || outbox.isEmpty()) { outbox.clear(); return; }
            String batch = "[" + join(outbox) + "]";
            outbox.clear();
            evalPanel("NS.push(" + JSONObject.quote(batch) + ")");
        }, 70);
    }

    private void evalPanel(String js) {
        WebView p = panel;
        if (p != null) p.evaluateJavascript(js, null);
    }

    private void reply(String id, boolean ok, String payload) {
        evalPanel("NS.rpcResult(" + JSONObject.quote(id) + "," + ok + "," + JSONObject.quote(payload == null ? "" : payload) + ")");
    }

    // ------------------------------------------------------------------ requests from the panel

    private void dispatch(String id, String method, String argsJson) {
        try {
            if (method.startsWith("native.")) {
                nativeCall(id, method.substring(7), argsJson == null || argsJson.isEmpty() ? new JSONObject() : new JSONObject(argsJson));
                return;
            }
            WebView w = host.currentWeb();
            if (w == null) { reply(id, false, "No page is open"); return; }
            if (method.length() > 40 || !method.matches("[a-zA-Z.]+")) { reply(id, false, "Bad request"); return; }
            Data d = dataOf(host.currentKey());
            agentPending.add(id);
            if (!d.agentReady) w.evaluateJavascript(agent(), null);
            w.evaluateJavascript("window.__nsdt_agent&&window.__nsdt_agent.rpc(" + JSONObject.quote(id) + "," + JSONObject.quote(method) + "," + JSONObject.quote(argsJson == null ? "{}" : argsJson) + ")", null);
        } catch (Exception e) {
            reply(id, false, String.valueOf(e.getMessage()));
        }
    }

    private void nativeCall(final String id, String name, final JSONObject a) throws Exception {
        final WebView web = host.currentWeb();
        switch (name) {
            case "copy": {
                ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("devtools", a.optString("text")));
                reply(id, true, "null");
                return;
            }
            case "open": host.openTab(a.optString("url")); reply(id, true, "null"); return;
            case "close": close(); reply(id, true, "null"); return;
            case "dock": host.setDock(a.optString("mode")); reply(id, true, "null"); return;
            case "reload": host.reloadCurrent(a.optBoolean("hard")); reply(id, true, "null"); return;
            case "off": disable(); return;
            case "setting": {
                String k = a.optString("key");
                boolean v = a.optBoolean("value");
                boolean reload = false;
                switch (k) {
                    case "preserve": preserve = v; break;
                    case "noCache": noCache = v; break;
                    case "noJs": noJs = v; reload = true; break;
                    case "noImages": noImages = v; reload = true; break;
                    case "remote": remote = v; WebView.setWebContentsDebuggingEnabled(v); Prefs.sp(act).edit().putBoolean(BrowserPrefs.DEBUG, v).apply(); break;
                    default: break;
                }
                pushState();
                if (k.equals("noCache") || reload) host.applyDeviceSettings();
                reply(id, true, "null");
                return;
            }
            case "ua": ua = a.optString("ua").trim(); pushState(); host.applyDeviceSettings(); reply(id, true, "null"); return;
            case "viewport": vpW = Math.max(0, a.optInt("w")); vpH = Math.max(0, a.optInt("h")); pushState(); host.applyDeviceSettings(); reply(id, true, "null"); return;
            case "cookies": {
                JSONArray out = new JSONArray();
                String c = CookieManager.getInstance().getCookie(a.optString("url"));
                if (c != null) for (String part : c.split(";\\s*")) {
                    int i = part.indexOf('=');
                    if (part.isEmpty()) continue;
                    out.put(new JSONObject().put("name", i < 0 ? part : part.substring(0, i)).put("value", i < 0 ? "" : part.substring(i + 1)));
                }
                reply(id, true, out.toString());
                return;
            }
            case "setCookie": {
                CookieManager.getInstance().setCookie(a.optString("url"), a.optString("header"));
                CookieManager.getInstance().flush();
                reply(id, true, "null");
                return;
            }
            case "delCookie": deleteCookie(a.optString("url"), a.optString("name")); reply(id, true, "null"); return;
            case "clearCookies": {
                String c = CookieManager.getInstance().getCookie(a.optString("url"));
                if (c != null) for (String part : c.split(";\\s*")) { int i = part.indexOf('='); if (i > 0) deleteCookie(a.optString("url"), part.substring(0, i)); }
                reply(id, true, "null");
                return;
            }
            case "clearSite": {
                String url = a.optString("url");
                String c = CookieManager.getInstance().getCookie(url);
                if (c != null) for (String part : c.split(";\\s*")) { int i = part.indexOf('='); if (i > 0) deleteCookie(url, part.substring(0, i)); }
                try { Uri u = Uri.parse(url); WebStorage.getInstance().deleteOrigin(u.getScheme() + "://" + u.getAuthority()); } catch (Exception ignored) { }
                if (web != null) web.clearCache(true);
                reply(id, true, "null");
                return;
            }
            case "cert": reply(id, true, certInfo().toString()); return;
            case "screenshot": {
                if (web == null || web.getWidth() <= 0) { reply(id, false, "No page to capture"); return; }
                final Bitmap bmp = Bitmap.createBitmap(web.getWidth(), web.getHeight(), Bitmap.Config.ARGB_8888);
                web.draw(new Canvas(bmp));
                io.execute(() -> {
                    try {
                        String shotName = "screenshot-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".png";
                        ByteArrayOutputStream png = new ByteArrayOutputStream();
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, png);
                        final String path = save(shotName, png.toByteArray());
                        ui.post(() -> reply(id, true, JSONObject.quote(path)));
                    } catch (final Exception e) {
                        ui.post(() -> reply(id, false, String.valueOf(e.getMessage())));
                    }
                });
                return;
            }
            case "save": {
                final String fileName = a.optString("name", "export.txt"), content = a.optString("text");
                io.execute(() -> {
                    try {
                        final String path = save(fileName, content.getBytes(StandardCharsets.UTF_8));
                        ui.post(() -> reply(id, true, JSONObject.quote(path)));
                    } catch (final Exception e) {
                        ui.post(() -> reply(id, false, String.valueOf(e.getMessage())));
                    }
                });
                return;
            }
            case "replay": case "send": {
                final boolean replay = name.equals("replay");
                final String method = a.optString("method", "GET").toUpperCase(Locale.US), url = a.optString("url");
                final JSONArray headers = a.optJSONArray("headers");
                final String body = replay ? null : a.optString("body", "");
                final boolean follow = replay || a.optBoolean("follow");
                final String uaStr = web != null ? web.getSettings().getUserAgentString() : null;
                io.execute(() -> {
                    final JSONObject res = http(method, url, headers, body, follow, uaStr);
                    ui.post(() -> reply(id, true, res.toString()));
                });
                return;
            }
            default:
                reply(id, false, "Unknown action " + name);
        }
    }

    private void deleteCookie(String url, String name) {
        CookieManager cm = CookieManager.getInstance();
        Uri u = Uri.parse(url);
        String host = u.getHost() == null ? "" : u.getHost();
        List<String> paths = new ArrayList<>();
        paths.add("/");
        String acc = "";
        String p = u.getPath() == null ? "" : u.getPath();
        for (String seg : p.split("/")) { if (seg.isEmpty()) continue; acc += "/" + seg; paths.add(acc); }
        List<String> domains = new ArrayList<>();
        domains.add(null);
        String d = host;
        while (d.contains(".")) { domains.add(d); domains.add("." + d); d = d.substring(d.indexOf('.') + 1); }
        for (String path : paths) {
            for (String dom : domains) {
                String base = name + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=" + path + (dom != null ? "; Domain=" + dom : "");
                cm.setCookie(url, base);
                if ("https".equals(u.getScheme())) cm.setCookie(url, base + "; Secure");
            }
        }
        cm.flush();
    }

    private JSONObject certInfo() throws JSONException {
        JSONObject out = new JSONObject();
        WebView w = host.currentWeb();
        if (BrowserProxy.active(act)) out.put("proxy", BrowserProxy.label(act));
        SslCertificate sc = w == null ? null : w.getCertificate();
        X509Certificate x = BrowserCerts.fromSsl(sc);
        if (x == null) return out;
        JSONObject c = new JSONObject();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        c.put("subject", x.getSubjectX500Principal().getName()).put("issuer", x.getIssuerX500Principal().getName())
                .put("from", f.format(x.getNotBefore())).put("to", f.format(x.getNotAfter())).put("sigAlg", x.getSigAlgName())
                .put("serial", x.getSerialNumber().toString(16)).put("sha256", BrowserCerts.fingerprint(x));
        JSONArray sans = new JSONArray();
        try {
            Collection<List<?>> names = x.getSubjectAlternativeNames();
            if (names != null) for (List<?> n : names) if (n.size() > 1 && n.get(1) instanceof String) sans.put(n.get(1));
        } catch (Exception ignored) { }
        c.put("sans", sans);
        c.put("trust", BrowserCerts.chainTrusted(act, x, new StringBuilder()) ? "private" : "system");
        out.put("cert", c);
        return out;
    }

    private String save(String name, byte[] bytes) throws IOException {
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        File f = BrowserDownloads.unique(BrowserDownloads.targetDir(act), safe.isEmpty() ? "export" : safe);
        try (OutputStream os = new FileOutputStream(f)) { os.write(bytes); }
        String guest = BrowserDownloads.guestPath(act, f);
        return guest != null ? guest : f.getAbsolutePath();
    }

    /** One HTTP exchange through the browser's proxy and CA settings, with the page's cookies. Never throws. */
    private JSONObject http(String method, String url, JSONArray headers, String body, boolean follow, String uaStr) {
        JSONObject out = new JSONObject();
        long t0 = SystemClock.elapsedRealtime();
        HttpURLConnection c = null;
        try {
            URL u = new URL(url);
            if (!"http".equals(u.getProtocol()) && !"https".equals(u.getProtocol())) throw new IOException("Only http and https URLs can be requested");
            BrowserDownloads.installProxyAuth(act);
            c = (HttpURLConnection) u.openConnection(BrowserProxy.javaProxy(act, u.getHost()));
            if (c instanceof HttpsURLConnection) ((HttpsURLConnection) c).setSSLSocketFactory(BrowserCerts.sslContext(act).getSocketFactory());
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(follow);
            c.setRequestMethod(method);
            boolean hasCookie = false, hasUa = false;
            if (headers != null) for (int i = 0; i < headers.length(); i++) {
                JSONArray h = headers.optJSONArray(i);
                if (h == null || h.length() < 2) continue;
                String k = h.getString(0), v = h.getString(1), lk = k.toLowerCase(Locale.US);
                if (lk.equals("host") || lk.equals("content-length") || lk.equals("connection") || lk.equals("accept-encoding") || lk.equals("transfer-encoding") || lk.equals("expect") || lk.equals("upgrade")) continue;
                if (lk.equals("cookie")) hasCookie = true;
                if (lk.equals("user-agent")) hasUa = true;
                c.addRequestProperty(k, v);
            }
            if (!hasCookie) { String ck = CookieManager.getInstance().getCookie(url); if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck); }
            if (!hasUa && uaStr != null) c.setRequestProperty("User-Agent", uaStr);
            if (body != null && !body.isEmpty() && !method.equals("GET") && !method.equals("HEAD")) {
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream os = c.getOutputStream()) { os.write(b); }
            }
            int code = c.getResponseCode();
            JSONArray hs = new JSONArray();
            for (int i = 0; ; i++) {
                String k = c.getHeaderFieldKey(i), v = c.getHeaderField(i);
                if (k == null && v == null) break;
                if (k != null) hs.put(new JSONArray().put(k).put(v));
            }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            boolean truncated = false;
            long total = 0;
            if (in != null) try (InputStream is = in) {
                byte[] chunk = new byte[16384];
                int n;
                while ((n = is.read(chunk)) > 0) {
                    total += n;
                    if (buf.size() + n > MAX_BODY) { truncated = true; break; }
                    buf.write(chunk, 0, n);
                }
            }
            byte[] bytes = buf.toByteArray();
            String mime = c.getContentType() == null ? "" : c.getContentType().split(";")[0].trim();
            boolean text = TEXTUAL.matcher(mime).matches() || mime.isEmpty() && looksText(bytes);
            out.put("status", code).put("statusText", c.getResponseMessage()).put("headers", hs).put("mime", mime).put("size", truncated ? total : bytes.length)
                    .put("truncated", truncated).put("ms", SystemClock.elapsedRealtime() - t0);
            if (text) out.put("text", new String(bytes, StandardCharsets.UTF_8));
            else if (mime.startsWith("image/") && !truncated) out.put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP));
        } catch (Exception e) {
            try { out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage()).put("ms", SystemClock.elapsedRealtime() - t0); } catch (JSONException ignored) { }
        } finally {
            if (c != null) c.disconnect();
        }
        return out;
    }

    private static boolean looksText(byte[] b) {
        for (int i = 0; i < Math.min(b.length, 1024); i++) if (b[i] == 0) return false;
        return true;
    }
}
