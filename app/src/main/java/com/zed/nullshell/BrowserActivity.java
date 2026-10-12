package com.zed.nullshell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small tabbed browser on the system WebView. Traffic can be routed through an intercepting proxy
 * (BrowserProxy) and HTTPS from that proxy is accepted only for the private CAs in BrowserCerts.
 * Only a few tabs keep a live WebView; the rest are saved and rebuilt when selected.
 */
public class BrowserActivity extends Activity {
    static final String EXTRA_URL = "url";
    private static final String START = "nullshell://start";
    private static final int REQ_HISTORY = 51, REQ_FILE = 52;
    /** Desktop mode: pages are laid out 1280 CSS pixels wide, whatever their viewport tag says. */
    private static final String DESKTOP_JS = "(function(){if(window.__nsDesk)return;window.__nsDesk=1;var W='width=1280';function fix(){var h=document.head;var ms=document.querySelectorAll('meta[name=viewport]');if(!ms.length){if(!h)return;var m=document.createElement('meta');m.name='viewport';m.content=W;h.appendChild(m);}else for(var i=0;i<ms.length;i++)if(ms[i].content!==W)ms[i].content=W;}fix();var mo=new MutationObserver(function(rs){for(var i=0;i<rs.length;i++){var r=rs[i];if(r.type==='attributes'){if(r.target.name==='viewport'){fix();return;}}else for(var j=0;j<r.addedNodes.length;j++){var n=r.addedNodes[j].nodeName;if(n==='META'||n==='HEAD'||n==='HTML'){fix();return;}}}});mo.observe(document,{childList:true,subtree:true,attributes:true,attributeFilter:['content','name']});addEventListener('load',function(){setTimeout(function(){mo.disconnect();},2000);});})();";
    private static final Pattern HAS_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*:.*");
    private static final Pattern HOSTLIKE = Pattern.compile(
            "(?i)(localhost|[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+|\\d{1,3}(\\.\\d{1,3}){3}|\\[[0-9a-f:]+\\])(:(\\d{1,5}))?");
    private static final Pattern PLAIN_HOST = Pattern.compile(
            "(?i)(localhost|\\d{1,3}(\\.\\d{1,3}){3}|\\[[0-9a-f:]+\\]|.+\\.(local|lan|internal|home|test|localdomain))");

    /** Opens the browser (a single instance in its own task), optionally on a page. */
    static void open(Context c, String url) {
        Intent i = new Intent(c, BrowserActivity.class);
        if (url != null) i.putExtra(EXTRA_URL, url);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    private static final class Tab {
        String url = "", title = "";
        Bitmap icon;
        Bundle state;
        WebView web;
        boolean desktop, showingError, errorPending;
        ScriptHandler deskHandler;
        String sslNote;
        int progress = 100;
        long used;
    }

    private final List<Tab> tabs = new ArrayList<>();
    private int cur = -1;
    private boolean started;
    private String pendingUrl;
    private String proxySig = "";
    private final Set<String> autoAuth = new HashSet<>();
    private ValueCallback<Uri[]> fileCb;
    private BrowserHistory history;
    private BrowserDevTools dev;
    private FrameLayout panelHost;
    private LinearLayout content;
    private String dockMode = "bottom";
    private int dockLevel = 1;

    private LinearLayout root, switcherList;
    private View switcher;
    private FrameLayout webHost;
    private EditText address;
    private TextView lockView, reloadBtn, tabsCount, proxyBadge, menuBtn;
    private ProgressBar progressBar;
    private TextView switcherTitle;

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        history = BrowserHistory.get(this);
        WebView.setWebContentsDebuggingEnabled(BrowserPrefs.debugging(this));
        buildUi();
        dev = new BrowserDevTools(new DevHost(), panelHost);
        restoreTabs();
        pendingUrl = getIntent().getStringExtra(EXTRA_URL);
        proxySig = BrowserProxy.signature(this);
        BrowserProxy.apply(this, () -> {
            started = true;
            if (tabs.isEmpty()) addTab(null, true); else select(cur);
            consumePending();
            if (BrowserProxy.lastError != null) toast("Proxy: " + BrowserProxy.lastError);
        });
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingUrl = intent.getStringExtra(EXTRA_URL);
        if (started) consumePending();
    }

    private void consumePending() {
        if (pendingUrl == null) return;
        String u = pendingUrl;
        pendingUrl = null;
        addTab(u, true);
    }

    @Override protected void onResume() {
        super.onResume();
        Tab t = current();
        if (started && t != null && t.web == null) select(cur);        // released while the app was in the background
        t = current();
        if (t != null && t.web != null) t.web.onResume();
        if (started && !proxySig.equals(BrowserProxy.signature(this))) reapplyProxy(true);
        updateChrome();
    }

    @Override protected void onPause() {
        Tab t = current();
        if (t != null && t.web != null) t.web.onPause();
        saveTabs();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    /** Low memory: drop background pages first; once the app is cached in the background, the visible page too. */
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (!started) return;
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) releaseWebViews(true);
        else if (level == 15 /* TRIM_MEMORY_RUNNING_CRITICAL */ || level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) releaseWebViews(false);
    }

    /** Saves each page's state and frees its WebView; it is rebuilt when the tab is selected again. */
    private void releaseWebViews(boolean includeCurrent) {
        Tab visible = current();
        for (Tab t : tabs) {
            if (t.web != null && (t != visible || includeCurrent)) destroyWeb(t, true);
        }
    }

    @Override protected void onDestroy() {
        exitFullscreen();
        dev.destroy();
        for (Tab t : tabs) destroyWeb(t, false);
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (fsView != null) { exitFullscreen(); return; }
        if (switcher.getVisibility() == View.VISIBLE) { hideSwitcher(); return; }
        if (address.hasFocus()) { dropFocus(); updateChrome(); return; }
        if (dev.isOpen()) { dev.close(); return; }
        Tab t = current();
        if (t != null && t.web != null && t.web.canGoBack()) { t.web.goBack(); return; }
        if (tabs.size() > 1) { closeTab(cur); return; }
        super.onBackPressed();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE) {
            if (fileCb != null) {
                fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
                fileCb = null;
            }
        } else if (req == REQ_HISTORY && res == RESULT_OK && data != null) {
            String url = data.getStringExtra(BrowserHistoryActivity.EXTRA_URL);
            if (url == null) return;
            if (data.getBooleanExtra(BrowserHistoryActivity.EXTRA_NEW_TAB, false)) addTab(url, true);
            else load(url);
        }
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setFocusableInTouchMode(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 2), 0);

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(Ui.rounded(this, Ui.SURFACE2, 20));

        lockView = new TextView(this);
        lockView.setGravity(Gravity.CENTER);
        lockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pill.addView(lockView, new LinearLayout.LayoutParams(Ui.dp(this, 34), ViewGroup.LayoutParams.MATCH_PARENT));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setBackground(null);
        address.setPadding(0, 0, Ui.dp(this, 4), 0);
        address.setTextColor(Ui.TEXT);
        address.setHint("Search or enter address");
        address.setHintTextColor(Ui.HINT);
        address.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        address.setSelectAllOnFocus(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        address.setOnEditorActionListener((v, id, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (id == EditorInfo.IME_ACTION_GO || enter) {
                String text = address.getText().toString();
                dropFocus();
                load(text);
                return true;
            }
            return false;
        });
        address.setOnFocusChangeListener((v, has) -> { if (!has) updateChrome(); });
        pill.addView(address, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        proxyBadge = new TextView(this);
        proxyBadge.setText("PROXY");
        proxyBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        proxyBadge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        proxyBadge.setTextColor(Ui.ON_ACCENT);
        proxyBadge.setBackground(Ui.rounded(this, Ui.ACCENT, 6));
        proxyBadge.setPadding(Ui.dp(this, 5), Ui.dp(this, 2), Ui.dp(this, 5), Ui.dp(this, 2));
        proxyBadge.setOnClickListener(v -> openSettings());
        proxyBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.setMargins(0, 0, Ui.dp(this, 2), 0);
        pill.addView(proxyBadge, bp);

        reloadBtn = new TextView(this);
        reloadBtn.setGravity(Gravity.CENTER);
        reloadBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        reloadBtn.setTextColor(Ui.TEXT);
        reloadBtn.setClickable(true);
        reloadBtn.setOnClickListener(v -> reloadOrStop());
        pill.addView(reloadBtn, new LinearLayout.LayoutParams(Ui.dp(this, 38), ViewGroup.LayoutParams.MATCH_PARENT));
        bar.addView(pill, new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f));

        FrameLayout tabsWrap = new FrameLayout(this);
        tabsWrap.setBackground(Ui.ripple(this, 0, 22));
        tabsWrap.setClickable(true);
        tabsWrap.setOnClickListener(v -> toggleSwitcher());
        tabsCount = new TextView(this);
        tabsCount.setGravity(Gravity.CENTER);
        tabsCount.setTextColor(Ui.TEXT);
        tabsCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tabsCount.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        GradientDrawable box = new GradientDrawable();
        box.setColor(Color.TRANSPARENT);
        box.setStroke(Ui.dp(this, 2), Ui.TEXT);
        box.setCornerRadius(Ui.dp(this, 6));
        tabsCount.setBackground(box);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26), Gravity.CENTER);
        tabsWrap.addView(tabsCount, cp);
        bar.addView(tabsWrap, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        menuBtn = Ui.iconButton(this, "\u22EE");
        menuBtn.setOnClickListener(v -> showMenu());
        bar.addView(menuBtn);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));
        progressBar.setVisibility(View.INVISIBLE);
        root.addView(progressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 3)));

        FrameLayout body = new FrameLayout(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        webHost = new FrameLayout(this);
        webHost.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (r - l != or - ol || b - t != ob - ot) fitEmulation(); });
        content.addView(webHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        panelHost = new FrameLayout(this);
        panelHost.setVisibility(View.GONE);
        content.addView(panelHost, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0f));
        body.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        body.addView(buildSwitcher(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        Ui.applyInsets(root);
    }

    private View buildSwitcher() {
        LinearLayout sw = new LinearLayout(this);
        sw.setOrientation(LinearLayout.VERTICAL);
        sw.setBackgroundColor(Ui.BG);
        sw.setClickable(true);
        sw.setVisibility(View.GONE);
        switcher = sw;

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        switcherTitle = new TextView(this);
        switcherTitle.setTextColor(Ui.TEXT);
        switcherTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        switcherTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.addView(switcherTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView closeAll = Ui.button(this, "Close all", Ui.SECONDARY);
        closeAll.setOnClickListener(v -> closeAllTabs());
        head.addView(closeAll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 40)));
        TextView add = Ui.button(this, "+ New tab", Ui.PRIMARY);
        add.setOnClickListener(v -> { addTab(null, true); hideSwitcher(); address.requestFocus(); showKeyboard(); });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 40));
        ap.setMargins(Ui.dp(this, 8), 0, 0, 0);
        head.addView(add, ap);
        sw.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        switcherList = new LinearLayout(this);
        switcherList.setOrientation(LinearLayout.VERTICAL);
        switcherList.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), Ui.dp(this, 16));
        scroll.addView(switcherList, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sw.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return sw;
    }

    private void toggleSwitcher() {
        if (switcher.getVisibility() == View.VISIBLE) hideSwitcher();
        else { dropFocus(); refreshSwitcher(); switcher.setVisibility(View.VISIBLE); }
    }

    private void hideSwitcher() { switcher.setVisibility(View.GONE); }

    private void refreshSwitcher() {
        switcherTitle.setText(tabs.size() + (tabs.size() == 1 ? " tab" : " tabs"));
        switcherList.removeAllViews();
        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            Tab t = tabs.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
            row.setClickable(true);
            row.setBackground(Ui.ripple(this, i == cur ? Ui.SURFACE2 : Ui.SURFACE, 12));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.setMargins(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));

            View icon;
            if (t.icon != null) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(t.icon);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                icon = iv;
            } else {
                TextView letter = new TextView(this);
                String label = tabLabel(t);
                letter.setText(label.isEmpty() ? "\u2022" : label.substring(0, 1).toUpperCase());
                letter.setGravity(Gravity.CENTER);
                letter.setTextColor(Ui.ACCENT);
                letter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                letter.setBackground(Ui.rounded(this, Ui.BG, 6));
                icon = letter;
            }
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28));
            ip.setMargins(0, 0, Ui.dp(this, 12), 0);
            row.addView(icon, ip);

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(this);
            title.setText(tabLabel(t));
            title.setTextColor(Ui.TEXT);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            TextView url = new TextView(this);
            url.setText(isStart(t.url) || t.url.isEmpty() ? "New tab" : t.url);
            url.setTextColor(Ui.DIM);
            url.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            url.setSingleLine(true);
            url.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(url);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView close = Ui.iconButton(this, "\u2715");
            close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            close.setOnClickListener(v -> closeTab(index));
            row.addView(close);

            row.setOnClickListener(v -> { select(index); hideSwitcher(); });
            switcherList.addView(row, rp);
        }
    }

    /** Favicons are shown at 28 dp; keeping the page's full-size bitmap would waste memory per tab. */
    private static Bitmap small(Bitmap b) {
        if (b == null || (b.getWidth() <= 48 && b.getHeight() <= 48)) return b;
        float k = 48f / Math.max(b.getWidth(), b.getHeight());
        return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * k)), Math.max(1, Math.round(b.getHeight() * k)), true);
    }

    private static String tabLabel(Tab t) {
        if (t.title != null && !t.title.isEmpty() && !isStart(t.url)) return t.title;
        if (isStart(t.url) || t.url.isEmpty()) return "New tab";
        return t.url;
    }

    private void updateChrome() {
        Tab t = current();
        if (t == null) return;
        boolean blank = isStart(t.url) || t.url.isEmpty();
        if (!address.hasFocus()) address.setText(blank ? "" : t.url);
        if (blank) { lockView.setText(""); }
        else if (t.url.startsWith("https://")) { lockView.setText("\uD83D\uDD12"); lockView.setTextColor(Ui.ACCENT); }
        else { lockView.setText("\u24D8"); lockView.setTextColor(Ui.DIM); }
        tabsCount.setText(String.valueOf(tabs.size()));
        reloadBtn.setText(t.progress < 100 ? "\u2715" : "\u27F3");
        progressBar.setProgress(t.progress);
        progressBar.setVisibility(t.progress < 100 ? View.VISIBLE : View.INVISIBLE);
        proxyBadge.setVisibility(BrowserProxy.active(this) ? View.VISIBLE : View.GONE);
    }

    private void dropFocus() {
        address.clearFocus();
        root.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(address.getWindowToken(), 0);
    }

    private void showKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) address.post(() -> imm.showSoftInput(address, InputMethodManager.SHOW_IMPLICIT));
    }

    // ---- fullscreen video ----
    private View fsView;
    private WebChromeClient.CustomViewCallback fsCallback;
    private FrameLayout fsHolder;
    private Tab fsTab;
    private int fsOldOrientation;

    private void enterFullscreen(Tab t, View v, WebChromeClient.CustomViewCallback cb) {
        if (fsView != null) { cb.onCustomViewHidden(); return; }
        fsView = v; fsCallback = cb; fsTab = t;
        fsHolder = new FrameLayout(this);
        fsHolder.setBackgroundColor(Color.BLACK);
        fsHolder.addView(v, new FrameLayout.LayoutParams(-1, -1));
        ((ViewGroup) getWindow().getDecorView()).addView(fsHolder, new ViewGroup.LayoutParams(-1, -1));
        fsOldOrientation = getRequestedOrientation();
        if (BrowserPrefs.fullscreenLandscape(this))
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        applyImmersiveBars(true);
    }

    private void exitFullscreen() {
        if (fsView == null) return;
        View v = fsView; WebChromeClient.CustomViewCallback cb = fsCallback;
        fsView = null; fsCallback = null; fsTab = null;
        if (fsHolder != null) {
            fsHolder.removeAllViews();
            ViewGroup p = (ViewGroup) fsHolder.getParent();
            if (p != null) p.removeView(fsHolder);
            fsHolder = null;
        }
        applyImmersiveBars(false);
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            getWindow().setAttributes(lp);
        }
        setRequestedOrientation(fsOldOrientation);
        try { if (cb != null) cb.onCustomViewHidden(); } catch (Throwable ignored) { }
    }

    @SuppressWarnings("deprecation")
    private void applyImmersiveBars(boolean on) {
        android.view.Window w = getWindow();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = w.getInsetsController();
            if (c == null) return;
            if (on) {
                c.hide(android.view.WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(android.view.WindowInsets.Type.systemBars());
            }
        } else {
            w.getDecorView().setSystemUiVisibility(on
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                : View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    // ---- "open in app" / external schemes ----
    /** Returns true if the navigation was consumed (launched or deliberately blocked). */
    private boolean openExternal(Tab t, Uri u, boolean gesture) {
        String raw = u.toString();
        Intent in;
        String fallback = null;
        try {
            String sch = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            if (sch.equals("intent")) {
                in = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME);
                fallback = in.getStringExtra("browser_fallback_url");
            } else if (sch.equals("android-app")) {
                in = Intent.parseUri(raw, Intent.URI_ANDROID_APP_SCHEME);
            } else {
                in = new Intent(Intent.ACTION_VIEW, u);
            }
        } catch (Exception e) {
            return true;
        }
        if (!gesture) return true;   // never auto-launch apps from redirects/scripts
        // sanitize: only a plain BROWSABLE view-style launch, no explicit component / selector / URI grants
        in.addCategory(Intent.CATEGORY_BROWSABLE);
        in.setComponent(null);
        in.setSelector(null);
        in.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (in.getAction() == null) in.setAction(Intent.ACTION_VIEW);
        try {
            startActivity(in);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            if (fallback != null) {
                Uri f = Uri.parse(fallback);
                String fs = f.getScheme() == null ? "" : f.getScheme().toLowerCase();
                if (fs.equals("http") || fs.equals("https")) { if (t.web != null) t.web.loadUrl(fallback); return true; }
            }
            String pkg = in.getPackage();
            if (pkg != null) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true; }
                catch (Exception ignored) { }
            }
            toast("No app found to open this link");
            return true;
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ------------------------------------------------------------------ developer tools support

    private final class DevHost implements BrowserDevTools.Host {
        @Override public Activity activity() { return BrowserActivity.this; }
        @Override public WebView currentWeb() { Tab t = current(); return t == null ? null : t.web; }
        @Override public Object currentKey() { return current(); }
        @Override public String currentUrl() { Tab t = current(); return t == null || isStart(t.url) ? "" : t.url; }
        @Override public String currentTitle() { Tab t = current(); return t == null ? "" : t.title; }
        @Override public void forEachWeb(java.util.function.BiConsumer<Object, WebView> c) { for (Tab t : new ArrayList<>(tabs)) if (t.web != null) c.accept(t, t.web); }
        @Override public void openTab(String url) { addTab(url, true); }
        @Override public void closePanel() {
            panelHost.setVisibility(View.GONE);
            webHost.setVisibility(View.VISIBLE);
            LinearLayout.LayoutParams wp = (LinearLayout.LayoutParams) webHost.getLayoutParams();
            wp.weight = 1f;
            webHost.setLayoutParams(wp);
        }
        @Override public void setDock(String mode) { dock(mode); }
        @Override public void applyDeviceSettings() {
            for (Tab t : tabs) if (t.web != null) { dev.configure(t.web); applyUserAgent(t.web, t.desktop); }
            fitEmulation();
            reloadCurrent(false);
        }
        @Override public void reloadCurrent(boolean hard) {
            Tab t = current();
            if (t == null || t.web == null) return;
            if (hard) t.web.clearCache(true);
            reload(t);
        }
    }

    /** Places the tools panel below, beside or over the page. */
    private void dock(String mode) {
        if (mode == null || mode.isEmpty()) mode = dockMode;
        if ("cycle".equals(mode)) { dockLevel = (dockLevel + 1) % 3; mode = dockMode; }
        dockMode = mode;
        float f = "right".equals(mode) ? new float[]{0.4f, 0.5f, 0.6f}[dockLevel] : new float[]{0.35f, 0.55f, 0.8f}[dockLevel];
        panelHost.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams wp = (LinearLayout.LayoutParams) webHost.getLayoutParams();
        LinearLayout.LayoutParams pp = (LinearLayout.LayoutParams) panelHost.getLayoutParams();
        if ("right".equals(mode)) {
            content.setOrientation(LinearLayout.HORIZONTAL);
            wp.width = 0; wp.height = ViewGroup.LayoutParams.MATCH_PARENT; wp.weight = 1f - f;
            pp.width = 0; pp.height = ViewGroup.LayoutParams.MATCH_PARENT; pp.weight = f;
            webHost.setVisibility(View.VISIBLE);
        } else if ("full".equals(mode)) {
            content.setOrientation(LinearLayout.VERTICAL);
            pp.width = ViewGroup.LayoutParams.MATCH_PARENT; pp.height = 0; pp.weight = 1f;
            webHost.setVisibility(View.GONE);
        } else {
            content.setOrientation(LinearLayout.VERTICAL);
            wp.width = ViewGroup.LayoutParams.MATCH_PARENT; wp.height = 0; wp.weight = 1f - f;
            pp.width = ViewGroup.LayoutParams.MATCH_PARENT; pp.height = 0; pp.weight = f;
            webHost.setVisibility(View.VISIBLE);
        }
        webHost.setLayoutParams(wp);
        panelHost.setLayoutParams(pp);
    }

    /** Lays the page out at the size chosen in the Device panel and scales it down to fit. */
    private void fitEmulation() {
        Tab t = current();
        if (t == null || t.web == null || dev == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) t.web.getLayoutParams();
        if (lp == null) return;
        int w = dev.viewportW(), h = dev.viewportH();
        if (w <= 0 || h <= 0) {
            if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT || lp.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.gravity = Gravity.NO_GRAVITY;
                t.web.setLayoutParams(lp);
            }
            t.web.setScaleX(1f);
            t.web.setScaleY(1f);
            t.web.setTranslationX(0f);
            return;
        }
        int hw = webHost.getWidth(), hh = webHost.getHeight();
        if (hw <= 0 || hh <= 0) return;
        float d = getResources().getDisplayMetrics().density;
        int pw = Math.round(w * d), ph = Math.round(h * d);
        float k = Math.min(1f, Math.min(hw / (float) pw, hh / (float) ph));
        if (lp.width != pw || lp.height != ph) {
            lp.width = pw;
            lp.height = ph;
            lp.gravity = Gravity.TOP | Gravity.START;
            t.web.setLayoutParams(lp);
        }
        t.web.setPivotX(0f);
        t.web.setPivotY(0f);
        t.web.setScaleX(k);
        t.web.setScaleY(k);
        t.web.setTranslationX(Math.max(0f, (hw - pw * k) / 2f));
    }

    // ------------------------------------------------------------------ menu

    private void showMenu() {
        Tab t = current();
        TPopup pm = new TPopup(this, menuBtn);
        Menu m = pm.getMenu();
        boolean forward = t != null && t.web != null && t.web.canGoForward();
        m.add(0, 1, 0, "New tab");
        m.add(0, 2, 1, "Forward").setEnabled(forward);
        m.add(0, 3, 2, "History");
        m.add(0, 4, 3, "Desktop site").setCheckable(true).setChecked(t != null && t.desktop);
        m.add(0, 5, 4, BrowserProxy.active(this) ? "Proxy: on  \u00B7  " + BrowserProxy.label(this) : "Proxy: off");
        m.add(0, 6, 5, "Copy link");
        m.add(0, 7, 6, "Close tab");
        m.add(0, 10, 7, dev.isOpen() ? "Close developer tools" : "Developer tools");
        m.add(0, 9, 8, "Memory");
        m.add(0, 8, 9, "Proxy, certificates & settings");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: addTab(null, true); address.requestFocus(); showKeyboard(); return true;
                case 2: { Tab c = current(); if (c != null && c.web != null && c.web.canGoForward()) c.web.goForward(); return true; }
                case 3: startActivityForResult(new Intent(this, BrowserHistoryActivity.class), REQ_HISTORY); return true;
                case 4: toggleDesktop(); return true;
                case 5: toggleProxy(); return true;
                case 6: copyLink(); return true;
                case 7: closeTab(cur); return true;
                case 8: openSettings(); return true;
                case 10: dev.toggle(); return true;
                case 9: MemoryReport.show(this, "Free memory", () -> { releaseWebViews(false); toast("Released background tabs"); }); return true;
                default: return false;
            }
        });
        pm.show();
    }

    private void openSettings() { startActivity(new Intent(this, BrowserSettingsActivity.class)); }

    private void copyLink() {
        Tab t = current();
        if (t == null || t.url.isEmpty() || isStart(t.url)) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("url", t.url));
        toast("Link copied");
    }

    private void toggleDesktop() {
        Tab t = current();
        if (t == null) return;
        t.desktop = !t.desktop;
        if (t.web != null) {
            applyUserAgent(t.web, t.desktop);
            applyDesktopLayout(t, t.web);
            // a fresh request, not a reload from cache, so the server sees the new user agent
            if (!t.showingError && (t.url.startsWith("http://") || t.url.startsWith("https://"))) t.web.loadUrl(t.url); else reload(t);
        }
        toast(t.desktop ? "Desktop site" : "Mobile site");
    }

    private void toggleProxy() {
        if (!BrowserProxy.active(this) && BrowserPrefs.proxyHost(this).isEmpty()) { openSettings(); return; }
        boolean on = !BrowserPrefs.proxyOn(this);
        Prefs.sp(this).edit().putBoolean(BrowserPrefs.PROXY_ON, on).apply();
        reapplyProxy(true);
    }

    private void reapplyProxy(final boolean reload) {
        proxySig = BrowserProxy.signature(this);
        autoAuth.clear();
        BrowserProxy.apply(this, () -> {
            updateChrome();
            if (BrowserProxy.lastError != null) toast("Proxy: " + BrowserProxy.lastError);
            else toast(BrowserProxy.active(this) ? "Proxy on: " + BrowserProxy.label(this) : "Proxy off");
            Tab t = current();
            if (reload && t != null && t.web != null && !isStart(t.url) && !t.url.isEmpty()) reload(t);
        });
    }

    // ------------------------------------------------------------------ tabs

    private Tab current() { return cur >= 0 && cur < tabs.size() ? tabs.get(cur) : null; }

    private void addTab(String url, boolean select) {
        Tab t = new Tab();
        if (url != null) {
            String n = normalize(url);
            if (n != null) t.url = n;
        }
        tabs.add(t);
        if (select) select(tabs.size() - 1); else updateChrome();
    }

    private void select(int i) {
        if (i < 0 || i >= tabs.size()) return;
        exitFullscreen();
        Tab old = current();
        if (old != null && old.web != null) {
            old.web.onPause();
            webHost.removeView(old.web);
        }
        cur = i;
        Tab t = tabs.get(i);
        ensureWeb(t);
        dev.setCurrent(t);
        t.used = SystemClock.uptimeMillis();
        webHost.addView(t.web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        t.web.onResume();
        webHost.post(this::fitEmulation);
        trimLive();
        updateChrome();
    }

    private void closeTab(int i) {
        if (i < 0 || i >= tabs.size()) return;
        Tab t = tabs.get(i);
        boolean wasCurrent = i == cur;
        destroyWeb(t, false);
        dev.forget(t);
        tabs.remove(i);
        if (tabs.isEmpty()) {
            cur = -1;
            addTab(null, true);
        } else if (wasCurrent) {
            cur = -1;
            select(Math.min(i, tabs.size() - 1));
        } else {
            if (i < cur) cur--;
            updateChrome();
        }
        if (switcher.getVisibility() == View.VISIBLE) refreshSwitcher();
    }

    private void closeAllTabs() {
        for (Tab t : tabs) { destroyWeb(t, false); dev.forget(t); }
        tabs.clear();
        cur = -1;
        addTab(null, true);
        refreshSwitcher();
    }

    /** Keeps at most the configured number of live WebViews; the least recently used others are saved and released. */
    private void trimLive() {
        while (true) {
            int live = 0;
            Tab oldest = null;
            for (Tab t : tabs) {
                if (t.web == null) continue;
                live++;
                if (t != current() && (oldest == null || t.used < oldest.used)) oldest = t;
            }
            if (live <= BrowserPrefs.liveTabs(this) || oldest == null) return;
            destroyWeb(oldest, true);
        }
    }

    private void destroyWeb(Tab t, boolean keepState) {
        if (t.web == null) return;
        if (fsTab == t) exitFullscreen();
        WebView w = t.web;
        t.web = null;
        t.deskHandler = null;
        dev.detached(t);
        if (keepState) {
            Bundle s = new Bundle();
            w.saveState(s);
            t.state = s;
        }
        ViewGroup parent = (ViewGroup) w.getParent();
        if (parent != null) parent.removeView(w);
        w.stopLoading();
        w.setWebViewClient(new WebViewClient());
        w.destroy();
    }

    private void ensureWeb(Tab t) {
        if (t.web != null) return;
        t.web = newWebView(t);
        boolean restored = false;
        if (t.state != null && !isStart(t.url)) {
            try { restored = t.web.restoreState(t.state) != null; } catch (Exception ignored) { }
        }
        t.state = null;
        if (restored) return;
        if (t.url.isEmpty() || isStart(t.url)) loadStart(t);
        else t.web.loadUrl(t.url);
    }

    private void saveTabs() {
        try {
            JSONArray arr = new JSONArray();
            for (Tab t : tabs) {
                JSONObject o = new JSONObject();
                o.put("u", t.url);
                o.put("t", t.title);
                o.put("d", t.desktop);
                arr.put(o);
            }
            Prefs.sp(this).edit().putString(BrowserPrefs.TABS, arr.toString()).putInt(BrowserPrefs.ACTIVE, cur).apply();
        } catch (Exception ignored) { }
    }

    private void restoreTabs() {
        try {
            String json = Prefs.sp(this).getString(BrowserPrefs.TABS, "");
            if (json == null || json.isEmpty()) return;
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length() && i < 30; i++) {
                JSONObject o = arr.getJSONObject(i);
                Tab t = new Tab();
                t.url = o.optString("u", "");
                t.title = o.optString("t", "");
                t.desktop = o.optBoolean("d", false);
                tabs.add(t);
            }
            cur = Math.max(0, Math.min(Prefs.sp(this).getInt(BrowserPrefs.ACTIVE, 0), tabs.size() - 1));
            if (tabs.isEmpty()) cur = -1;
        } catch (Exception ignored) {
            tabs.clear();
            cur = -1;
        }
    }

    // ------------------------------------------------------------------ navigation

    private static boolean isStart(String url) { return START.equals(url); }

    private void load(String input) {
        Tab t = current();
        String url = normalize(input);
        if (t == null || url == null) return;
        ensureWeb(t);
        if (t.web.getParent() == null) webHost.addView(t.web);
        t.showingError = false;
        t.web.loadUrl(url);
    }

    private void reload(Tab t) {
        if (t.web == null) return;
        if (t.showingError && !t.url.isEmpty() && !isStart(t.url)) t.web.loadUrl(t.url);
        else t.web.reload();
    }

    private void reloadOrStop() {
        Tab t = current();
        if (t == null || t.web == null) return;
        if (t.progress < 100) t.web.stopLoading(); else reload(t);
    }

    /** Turns what was typed into a URL: addresses are opened, anything else is searched. */
    private String normalize(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;
        if (HAS_SCHEME.matcher(s).matches() && !s.matches("^[^/\\s]+:\\d+(/.*)?$")) return s;   // "host:8080" is not a scheme
        if (!s.contains(" ")) {
            String hostPart = s.split("[/?#]", 2)[0];
            String lower = hostPart.toLowerCase();
            // proxy CA download pages exist only over plain http, and only while the proxy is in use
            if (lower.equals("mitm.it") || lower.equals("burp") || lower.equals("caido.setup")) return "http://" + s;
            Matcher m = HOSTLIKE.matcher(hostPart);
            if (m.matches()) {
                String port = m.group(7);
                String host = port == null ? hostPart : hostPart.substring(0, hostPart.length() - port.length() - 1);
                boolean plain = PLAIN_HOST.matcher(host).matches() || (port != null && !"443".equals(port));
                return (plain ? "http://" : "https://") + s;
            }
        }
        return BrowserPrefs.searchUrl(this).replace("%s", Uri.encode(s));
    }

    private void loadStart(Tab t) {
        t.url = START;
        t.title = "New tab";
        t.showingError = false;
        t.web.loadDataWithBaseURL(START, startHtml(), "text/html", "UTF-8", START);
    }

    private String startHtml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>")
                .append("body{margin:0;padding:28px 20px;background:#0E1013;color:#E6E8EB;font-family:sans-serif}")
                .append("h1{font-size:22px;margin:0 0 6px}p{color:#8B929C;font-size:14px;margin:4px 0}")
                .append("a{display:block;padding:12px 14px;margin:6px 0;background:#171A1F;border-radius:10px;color:#E6E8EB;text-decoration:none;font-size:14px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}")
                .append("a small{display:block;color:#3DD6B0;font-size:12px;overflow:hidden;text-overflow:ellipsis}")
                .append(".s{color:#3DD6B0}h2{font-size:13px;color:#8B929C;margin:22px 0 4px;font-weight:normal}")
                .append("</style></head><body><h1>NullShell Browser</h1>");
        if (BrowserProxy.active(this)) {
            sb.append("<p class='s'>Proxy on: ").append(TextUtils.htmlEncode(BrowserProxy.label(this))).append("</p>");
        } else {
            sb.append("<p>No proxy</p>");
        }
        int cas = BrowserCerts.list(this).size();
        sb.append("<p>").append(cas).append(cas == 1 ? " CA certificate imported" : " CA certificates imported").append("</p>")
                .append("<p><a href='nullshell://settings' style='background:none;padding:6px 0;color:#3DD6B0'>Proxy &amp; certificate settings</a></p>");
        List<BrowserHistory.Item> recent = history.search("", 8);
        if (!recent.isEmpty()) {
            sb.append("<h2>Recent</h2>");
            for (BrowserHistory.Item it : recent) {
                String label = it.title.isEmpty() ? it.url : it.title;
                sb.append("<a href=\"").append(TextUtils.htmlEncode(it.url)).append("\">")
                        .append(TextUtils.htmlEncode(label)).append("<small>").append(TextUtils.htmlEncode(it.url)).append("</small></a>");
            }
        }
        return sb.append("</body></html>").toString();
    }

    private void showError(Tab t, String failingUrl, String description) {
        String hint = "";
        if (t.sslNote != null) {
            hint = t.sslNote + " To trust an intercepting proxy, import its CA certificate in the browser settings.";
        } else if (description.contains("PROXY") || description.contains("TUNNEL")) {
            hint = BrowserProxy.active(this)
                    ? "The proxy at " + BrowserProxy.label(this) + " is not answering. Start it, or turn the proxy off."
                    : "";
        } else if (description.contains("CONNECTION_REFUSED") && BrowserProxy.active(this)) {
            hint = "Requests go through the proxy " + BrowserProxy.label(this) + ".";
        }
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>"
                + "body{margin:0;padding:32px 22px;background:#0E1013;color:#E6E8EB;font-family:sans-serif}"
                + "h1{font-size:20px;margin:0 0 10px}p{color:#8B929C;font-size:14px;word-break:break-all}"
                + ".h{color:#E6E8EB;word-break:normal}a{display:inline-block;margin:14px 10px 0 0;padding:11px 18px;border-radius:10px;"
                + "background:#21262D;color:#E6E8EB;text-decoration:none;font-size:14px}a.p{background:#3DD6B0;color:#06201A}"
                + "</style></head><body><h1>Can't open this page</h1><p>" + TextUtils.htmlEncode(failingUrl) + "</p><p>"
                + TextUtils.htmlEncode(description) + "</p>"
                + (hint.isEmpty() ? "" : "<p class='h'>" + TextUtils.htmlEncode(hint) + "</p>")
                + "<a class='p' href=\"" + TextUtils.htmlEncode(failingUrl) + "\">Retry</a>"
                + "<a href='nullshell://settings'>Browser settings</a></body></html>";
        t.errorPending = true;
        t.web.loadDataWithBaseURL(failingUrl, html, "text/html", "UTF-8", failingUrl);
    }

    // ------------------------------------------------------------------ WebView

    private WebView newWebView(final Tab t) {
        final WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(BrowserPrefs.javascript(this));
        s.setDomStorageEnabled(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSafeBrowsingEnabled(false);           // no lookups to Google for every URL
        s.setSupportMultipleWindows(false);
        applyUserAgent(w, t.desktop);
        applyDesktopLayout(t, w);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        dev.prepare(w, t);
        w.setWebViewClient(new Client(t));
        w.setWebChromeClient(new Chrome(t));
        w.setDownloadListener((url, ua, cd, mime, len) -> startDownload(url, ua, cd, mime));
        w.setOnLongClickListener(v -> onLongPress(w));
        return w;
    }

    /** Layout side of desktop mode: no text inflation, wide viewport, and a script that overrides the page's viewport tag. */
    private void applyDesktopLayout(Tab t, WebView w) {
        WebSettings s = w.getSettings();
        s.setLayoutAlgorithm(t.desktop ? WebSettings.LayoutAlgorithm.NORMAL : WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        try {
            if (t.desktop && t.deskHandler == null && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                t.deskHandler = WebViewCompat.addDocumentStartJavaScript(w, DESKTOP_JS, Collections.singleton("*"));
            } else if (!t.desktop && t.deskHandler != null) {
                t.deskHandler.remove();
                t.deskHandler = null;
            }
        } catch (Throwable ignored) {
            t.deskHandler = null;
        }
    }

    private void applyUserAgent(WebView w, boolean desktop) {
        WebSettings s = w.getSettings();
        String forced = dev == null ? "" : dev.userAgent();
        if (!forced.isEmpty()) { s.setUserAgentString(forced); return; }
        if (!desktop) { s.setUserAgentString(null); return; }
        Matcher m = Pattern.compile("Chrome/[\\d.]+").matcher(WebSettings.getDefaultUserAgent(this));
        String chrome = m.find() ? m.group() : "Chrome/124.0.0.0";
        s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " + chrome + " Safari/537.36");
    }

    private boolean onLongPress(WebView w) {
        WebView.HitTestResult r = w.getHitTestResult();
        final String extra = r.getExtra();
        if (extra == null) return false;
        switch (r.getType()) {
            case WebView.HitTestResult.SRC_ANCHOR_TYPE:
                linkMenu(extra, null);
                return true;
            case WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE: {
                Handler h = new Handler(Looper.getMainLooper(), msg -> {
                    String link = msg.getData().getString("url");
                    linkMenu(link != null ? link : extra, extra);
                    return true;
                });
                w.requestFocusNodeHref(h.obtainMessage());
                return true;
            }
            case WebView.HitTestResult.IMAGE_TYPE:
                linkMenu(null, extra);
                return true;
            default:
                return false;
        }
    }

    private void linkMenu(final String link, final String image) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (link != null) {
            labels.add("Open in new tab");
            actions.add(() -> { addTab(link, false); toast("Opened in a new tab"); });
            labels.add("Copy link");
            actions.add(() -> copyText(link));
        }
        if (image != null) {
            labels.add("Open image in new tab");
            actions.add(() -> { addTab(image, false); toast("Opened in a new tab"); });
            labels.add("Save image");
            actions.add(() -> startDownload(image, null, null, null));
        }
        if (labels.isEmpty()) return;
        new Dlg(this).setTitle(link != null ? link : image)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run()).show();
    }

    private void copyText(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("url", text));
        toast("Copied");
    }

    // ------------------------------------------------------------------ downloads

    private void startDownload(String url, String ua, String disposition, String mime) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            toast("This kind of download is not supported");
            return;
        }
        toast("Downloading\u2026");
        BrowserDownloads.start(this, url, ua, disposition, mime, new BrowserDownloads.Listener() {
            @Override public void onDone(final File file, String guestPath, boolean cert) {
                String where = guestPath != null ? guestPath : file.getAbsolutePath();
                if (!cert) { toast("Saved to " + where); return; }
                new Dlg(BrowserActivity.this).setTitle("Certificate downloaded")
                        .setMessage(file.getName() + " was saved to " + where
                                + ".\n\nImport it as a trusted CA for this browser? Only do this for your own proxy.")
                        .setPositiveButton("Import", (d, w) -> importDownloadedCert(file))
                        .setNegativeButton("Keep file only", null).show();
            }
            @Override public void onError(String message) { toast("Download failed: " + message); }
        });
    }

    private void importDownloadedCert(File f) {
        try {
            List<BrowserCerts.Entry> added = BrowserCerts.importData(this, Files.readAllBytes(f.toPath()));
            toast("Imported " + (added.isEmpty() ? "nothing" : added.get(0).name));
        } catch (Exception e) {
            toast("Import failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ clients

    private final class Client extends WebViewClient {
        private final Tab t;
        Client(Tab tab) { t = tab; }

        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            switch (scheme) {
                case "http": case "https": case "about": case "data": case "javascript": case "blob": case "ws": case "wss":
                    return false;
                case "nullshell":
                    if ("settings".equals(u.getHost())) openSettings();
                    else if (START.equals(u.toString())) loadStart(t);
                    return true;
                case "mailto": case "tel": case "sms": case "geo":
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (ActivityNotFoundException ignored) { }
                    return true;
                default:
                    return openExternal(t, u, req.hasGesture());
            }
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            dev.onRequest(t, req);
            return null;
        }

        @Override public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse resp) {
            dev.onHttpError(t, req, resp);
        }

        @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
            dev.onPageStarted(t, view, url);
            if (t.desktop && t.deskHandler == null) view.evaluateJavascript(DESKTOP_JS, null);
            if (t.errorPending) { t.errorPending = false; t.showingError = true; }
            else { t.showingError = false; t.sslNote = null; }
            t.url = url == null ? "" : url;
            if (favicon != null) t.icon = small(favicon);
            t.progress = Math.min(t.progress, 5);
            if (t == current()) updateChrome();
        }

        @Override public void onPageCommitVisible(WebView view, String url) {
            if (t.desktop && t.deskHandler == null) view.evaluateJavascript(DESKTOP_JS, null);
        }

        @Override public void onPageFinished(WebView view, String url) {
            if (t.desktop && t.deskHandler == null) view.evaluateJavascript(DESKTOP_JS, null);
            t.progress = 100;
            String title = view.getTitle();
            if (title != null && !title.isEmpty() && !isStart(t.url)) t.title = title;
            if (!t.showingError && url != null && !isStart(url)) history.visit(url, title);
            if (t == current()) updateChrome();
        }

        @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            if (url != null && !url.equals("about:blank")) t.url = url;
            if (t == current()) updateChrome();
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (!req.isForMainFrame()) return;
            String desc = String.valueOf(err.getDescription());
            if (desc.contains("ERR_ABORTED")) return;
            String failing = req.getUrl().toString();
            if (isStart(failing)) { loadStart(t); return; }
            showError(t, failing, desc);
        }

        @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            String host = Uri.parse(error.getUrl()).getHost();
            boolean onlyUntrusted = error.hasError(SslError.SSL_UNTRUSTED)
                    && !error.hasError(SslError.SSL_EXPIRED) && !error.hasError(SslError.SSL_IDMISMATCH)
                    && !error.hasError(SslError.SSL_NOTYETVALID) && !error.hasError(SslError.SSL_DATE_INVALID)
                    && !error.hasError(SslError.SSL_INVALID);
            if (onlyUntrusted) {
                X509Certificate leaf = BrowserCerts.fromSsl(error.getCertificate());
                StringBuilder why = new StringBuilder();
                if (leaf != null && BrowserCerts.trusts(BrowserActivity.this, leaf, host, why)) {
                    handler.proceed();
                    return;
                }
                t.sslNote = (host == null ? "" : host + ": ") + (why.length() == 0 ? "The certificate could not be checked." : why.toString());
            } else {
                t.sslNote = (host == null ? "" : host + ": ") + describeSsl(error);
            }
            handler.cancel();
        }

        @Override public void onReceivedHttpAuthRequest(WebView view, final HttpAuthHandler handler, String host, String realm) {
            String key = host + "|" + realm;
            boolean isProxy = BrowserProxy.active(BrowserActivity.this) && host.equalsIgnoreCase(BrowserPrefs.proxyHost(BrowserActivity.this));
            String user = BrowserPrefs.proxyUser(BrowserActivity.this);
            if (isProxy && !user.isEmpty() && autoAuth.add(key)) {
                handler.proceed(user, BrowserPrefs.proxyPass(BrowserActivity.this));
                return;
            }
            askCredentials(handler, host, realm);
        }

        @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            boolean wasCurrent = t == current();
            destroyWeb(t, false);
            toast("The page crashed and was reloaded");
            if (wasCurrent) { int i = cur; cur = -1; select(i); }
            return true;
        }
    }

    private static String describeSsl(SslError e) {
        switch (e.getPrimaryError()) {
            case SslError.SSL_EXPIRED: return "The certificate has expired.";
            case SslError.SSL_NOTYETVALID: return "The certificate is not valid yet.";
            case SslError.SSL_IDMISMATCH: return "The certificate does not match the address.";
            case SslError.SSL_UNTRUSTED: return "The certificate authority is not trusted.";
            default: return "The certificate is invalid.";
        }
    }

    private void askCredentials(final HttpAuthHandler handler, String host, String realm) {
        final EditText user = new EditText(this);
        user.setSingleLine(true);
        user.setHint("Username");
        user.setTextColor(Ui.TEXT);
        user.setHintTextColor(Ui.HINT);
        Ui.tint(user);
        final EditText pass = new EditText(this);
        pass.setSingleLine(true);
        pass.setHint("Password");
        pass.setTextColor(Ui.TEXT);
        pass.setHintTextColor(Ui.HINT);
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Ui.tint(pass);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(user);
        box.addView(pass);
        new Dlg(this).setTitle("Sign in to " + host)
                .setMessage(realm == null || realm.isEmpty() ? null : realm)
                .setView(box)
                .setPositiveButton("Sign in", (d, w) -> handler.proceed(user.getText().toString(), pass.getText().toString()))
                .setNegativeButton("Cancel", (d, w) -> handler.cancel())
                .setOnCancelListener(d -> handler.cancel())
                .show();
    }

    private final class Chrome extends WebChromeClient {
        private final Tab t;
        Chrome(Tab tab) { t = tab; }

        @Override public void onShowCustomView(View view, CustomViewCallback cb) { enterFullscreen(t, view, cb); }
        @Override public void onHideCustomView() { exitFullscreen(); }

        @Override public void onProgressChanged(WebView view, int p) {
            t.progress = p;
            if (t == current()) updateChrome();
        }

        @Override public void onReceivedTitle(WebView view, String title) {
            if (title == null || title.isEmpty() || isStart(t.url)) return;
            t.title = title;
            String url = view.getUrl();
            if (!t.showingError && url != null) history.setTitle(url, title);
        }

        @Override public void onReceivedIcon(WebView view, Bitmap icon) { t.icon = small(icon); }

        @Override public boolean onConsoleMessage(ConsoleMessage m) {
            dev.onConsole(t, m);
            return super.onConsoleMessage(m);
        }

        @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fileCb != null) fileCb.onReceiveValue(null);
            fileCb = cb;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE);
                return true;
            } catch (Exception e) {
                fileCb = null;
                return false;
            }
        }
    }
}
