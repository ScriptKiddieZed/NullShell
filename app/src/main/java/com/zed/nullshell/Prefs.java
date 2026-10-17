package com.zed.nullshell;

import android.content.Context;
import android.content.SharedPreferences;

/** Typed access to the app settings (SharedPreferences "settings"). */
public final class Prefs {
    private Prefs() { }

    public static final String FONT_SIZE = "font_size";
    public static final String SCHEME = "scheme";
    public static final String KEEP_SCREEN_ON = "keep_screen_on";
    public static final String SHELL = "shell";
    public static final String WORKDIR = "workdir";
    public static final String LANG = "lang";
    public static final String DNS = "dns";
    public static final String BIND_SDCARD = "bind_sdcard";
    public static final String BIND_SYS = "bind_sys";
    public static final String FAKE_PROC = "fake_proc";
    public static final String WAKE_LOCK = "wake_lock";
    public static final String STARTUP_CMD = "startup_cmd";
    public static final String EXTRA_PROOT = "extra_proot";
    public static final String VNC_W = "vnc_w";
    public static final String VNC_H = "vnc_h";
    public static final String VNC_DEPTH = "vnc_depth";
    public static final String VNC_DPI = "vnc_dpi";
    public static final String VNC_DISPLAY = "vnc_display";
    public static final String VNC_LOCAL = "vnc_local";
    public static final String VNC_SHARED = "vnc_shared";
    public static final String VNC_TERMINAL = "vnc_terminal";
    public static final String VNC_CLIPBOARD = "vnc_clipboard";
    public static final String VNC_PASSWORD = "vnc_password";
    public static final String VNC_EXTRA = "vnc_extra";
    public static final String VNC_THEME = "vnc_theme";
    public static final String VNC_UISIZE = "vnc_uisize";
    public static final String VNC_KEEPCFG = "vnc_keepcfg";
    /** Colour depths the VNC server can be started with; VNC_DEPTH is an index into this. */
    public static final int[] VNC_DEPTHS = {16, 24};
    public static final String EXTRA_KEYS = "extra_keys";
    public static final String AUTO_START = "auto_start";
    public static final String EDITOR_ENV = "editor_env";
    public static final String ED_FONT = "ed_font";
    public static final String ED_WRAP = "ed_wrap";
    public static final String ED_LINES = "ed_lines";
    public static final String ED_TAB = "ed_tab";
    public static final String ED_SPACES = "ed_spaces";
    public static final String ED_AUTOSAVE = "ed_autosave";
    public static final String ED_THEME = "ed_theme";
    public static final String ED_SYMBOLS = "ed_symbols";
    public static final String ED_LSP = "ed_lsp";
    public static final String ED_LSP_IDLE = "ed_lsp_idle";
    public static final String ED_LSP_MEM = "ed_lsp_mem";
    public static final String ED_LARGE = "ed_large";
    public static final String TERM_SCROLLBACK = "term_scrollback";
    /** Id of the downloaded Nerd Font used by the terminal (see TerminalFonts); empty = system monospace. */
    public static final String TERM_FONT = "term_font";
    public static final String ED_LOADED = "ed_loaded";
    /** File sizes (KB) above which the editor switches to large-file mode; ED_LARGE is an index into this. */
    public static final int[] LARGE_KB = {128, 256, 512, 1024, 2048, 4096};
    public static final String ED_LSP_MAX = "ed_lsp_max";
    public static final String ED_LAST = "ed_last";
    public static final String[] ED_THEMES = {"auto", "nullshell", "darcula", "solarized_dark", "ayu-dark", "quietlight",
            "dracula", "nord", "monokai", "gruvbox-dark", "tokyo-night", "catppuccin-mocha", "solarized-light", "catppuccin-latte", "github-light"};
    public static final String[] ED_THEME_NAMES = {"Follow app theme", "NullShell", "Darcula", "Solarized dark", "Ayu dark", "Quiet light",
            "Dracula", "Nord", "Monokai", "Gruvbox dark", "Tokyo Night", "Catppuccin Mocha", "Solarized light", "Catppuccin Latte", "GitHub light"};

    /** {name, default foreground, default background} as ARGB. */
    public static final String[] SCHEME_NAMES = {"Follow app theme", "AMOLED black", "Solarized dark", "Dracula", "Green on black"};
    private static final int[][] SCHEME_COLORS = {
            {0xFFE0E0E0, 0xFF0E1013},
            {0xFFE0E0E0, 0xFF000000},
            {0xFF93A1A1, 0xFF002B36},
            {0xFFF8F8F2, 0xFF282A36},
            {0xFF33FF33, 0xFF000000},
    };

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public static int fontSizeSp(Context c) { return clamp(sp(c).getInt(FONT_SIZE, 13), 8, 32); }
    public static int scheme(Context c) { return clamp(sp(c).getInt(SCHEME, 0), 0, SCHEME_NAMES.length - 1); }
    public static int schemeFg(Context c) { return scheme(c) == 0 ? Theme.current.termFg : SCHEME_COLORS[scheme(c)][0]; }
    public static int schemeBg(Context c) { return scheme(c) == 0 ? Theme.current.termBg : SCHEME_COLORS[scheme(c)][1]; }
    public static boolean extraKeys(Context c) { return sp(c).getBoolean(EXTRA_KEYS, true); }
    public static boolean autoStart(Context c) { return sp(c).getBoolean(AUTO_START, true); }
    public static boolean editorEnv(Context c) { return sp(c).getBoolean(EDITOR_ENV, false); }
    public static int edFont(Context c) { return clamp(sp(c).getInt(ED_FONT, 14), 8, 32); }
    public static boolean edWrap(Context c) { return sp(c).getBoolean(ED_WRAP, false); }
    public static boolean edLines(Context c) { return sp(c).getBoolean(ED_LINES, true); }
    public static int edTab(Context c) { return clamp(sp(c).getInt(ED_TAB, 4), 1, 8); }
    public static boolean edSpaces(Context c) { return sp(c).getBoolean(ED_SPACES, true); }
    public static boolean edAutosave(Context c) { return sp(c).getBoolean(ED_AUTOSAVE, true); }
    /** The saved choice, which may be "auto". */
    public static String edThemeRaw(Context c) { return sp(c).getString(ED_THEME, "auto"); }
    /** The editor theme to use now ("auto" resolves to the one that goes with the app theme). */
    public static String edTheme(Context c) {
        String t = edThemeRaw(c);
        return "auto".equals(t) ? Theme.current.editor : t;
    }
    public static boolean edSymbols(Context c) { return sp(c).getBoolean(ED_SYMBOLS, true); }
    public static boolean edLsp(Context c) { return sp(c).getBoolean(ED_LSP, true); }
    /** Minutes a server may sit unused (no open file of its project) before it is stopped; 0 = never. */
    public static int lspIdleMinutes(Context c) { return clamp(sp(c).getInt(ED_LSP_IDLE, 5), 0, 60); }
    /** Heap cap in MB handed to Node and Go based servers; 0 = no cap. */
    public static int lspMemoryMb(Context c) { return clamp(sp(c).getInt(ED_LSP_MEM, 768), 0, 4096); }
    public static int edLargeIndex(Context c) { return clamp(sp(c).getInt(ED_LARGE, 2), 0, LARGE_KB.length - 1); }
    public static long edLargeBytes(Context c) { return LARGE_KB[edLargeIndex(c)] * 1024L; }
    /** Most editor tabs kept in memory at once (others are reopened from disk when selected); 0 = all. */
    public static int edLoaded(Context c) { return clamp(sp(c).getInt(ED_LOADED, 6), 0, 12); }
    /** Lines of terminal history kept per tab; applies to tabs opened afterwards. */
    public static int termScrollback(Context c) { return clamp(sp(c).getInt(TERM_SCROLLBACK, 3000), 500, 10000); }
    /** Most servers kept running at once; 0 = no limit. */
    public static int lspMaxServers(Context c) { return clamp(sp(c).getInt(ED_LSP_MAX, 2), 0, 6); }
    public static String serverCommand(Context c, String serverId) { return sp(c).getString("srv_cmd_" + serverId, "").trim(); }
    public static boolean keepScreenOn(Context c) { return sp(c).getBoolean(KEEP_SCREEN_ON, false); }

    public static String shell(Context c) { return nonEmpty(sp(c).getString(SHELL, ""), "/bin/bash"); }
    public static String workdir(Context c) { return nonEmpty(sp(c).getString(WORKDIR, ""), "/root"); }
    public static String lang(Context c) { return nonEmpty(sp(c).getString(LANG, ""), "C.UTF-8"); }
    public static String dns(Context c) { return nonEmpty(sp(c).getString(DNS, ""), "1.1.1.1, 8.8.8.8"); }
    public static boolean bindSdcard(Context c) { return sp(c).getBoolean(BIND_SDCARD, true); }
    public static boolean bindSys(Context c) { return sp(c).getBoolean(BIND_SYS, true); }
    public static boolean fakeProc(Context c) { return sp(c).getBoolean(FAKE_PROC, true); }
    public static boolean wakeLock(Context c) { return sp(c).getBoolean(WAKE_LOCK, true); }
    public static String startupCmd(Context c) { return sp(c).getString(STARTUP_CMD, "").trim(); }
    public static String extraProot(Context c) { return sp(c).getString(EXTRA_PROOT, "").trim(); }

    public static String termFont(Context c) { return sp(c).getString(TERM_FONT, ""); }

    public static int vncWidth(Context c) { return clamp(sp(c).getInt(VNC_W, 1280), 640, 2560); }
    public static int vncHeight(Context c) { return clamp(sp(c).getInt(VNC_H, 720), 480, 1600); }
    public static int vncDepthIndex(Context c) { return clamp(sp(c).getInt(VNC_DEPTH, 1), 0, VNC_DEPTHS.length - 1); }
    public static int vncDepth(Context c) { return VNC_DEPTHS[vncDepthIndex(c)]; }
    public static int vncDpi(Context c) { return clamp(sp(c).getInt(VNC_DPI, 96), 72, 240); }
    /** X display number; the VNC port is 5900 plus this. */
    public static int vncDisplay(Context c) { return clamp(sp(c).getInt(VNC_DISPLAY, 1), 1, 9); }
    /** True (default): only this phone can connect, i.e. a viewer app on the phone itself. */
    public static boolean vncLocalOnly(Context c) { return sp(c).getBoolean(VNC_LOCAL, true); }
    public static boolean vncShared(Context c) { return sp(c).getBoolean(VNC_SHARED, true); }
    public static boolean vncTerminal(Context c) { return sp(c).getBoolean(VNC_TERMINAL, true); }
    public static boolean vncClipboard(Context c) { return sp(c).getBoolean(VNC_CLIPBOARD, true); }
    /** IceWM theme folder name (a downloaded or bundled theme); empty for IceWM's own default. Nord by default. */
    public static String vncTheme(Context c) { return sp(c).getString(VNC_THEME, "Nord"); }
    /** 0 normal, 1 large, 2 extra large: fonts, title bars and taskbar. */
    public static int vncUiSize(Context c) { return clamp(sp(c).getInt(VNC_UISIZE, 1), 0, 2); }
    /** When on, the app writes ~/.icewm only if it does not exist yet. */
    public static boolean vncKeepConfig(Context c) { return sp(c).getBoolean(VNC_KEEPCFG, false); }
    public static String vncExtra(Context c) { return sp(c).getString(VNC_EXTRA, "").trim(); }

    /** The VNC password. VNC authentication only uses the first 8 characters, so it is exactly 8 when generated.
     *  Created on first use and kept in app-private prefs. */
    public static String vncPassword(Context c) {
        String p = sp(c).getString(VNC_PASSWORD, null);
        if (p == null || p.length() < 6 || p.length() > 8) {
            p = newVncPassword();
            sp(c).edit().putString(VNC_PASSWORD, p).apply();
        }
        return p;
    }

    public static String newVncPassword() {
        final String alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // no look-alikes
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return sb.toString();
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    private static String nonEmpty(String s, String def) {
        return (s == null || s.trim().isEmpty()) ? def : s.trim();
    }
}
