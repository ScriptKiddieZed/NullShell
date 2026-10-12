package com.zed.nullshell;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The look of the whole app: a named palette plus a few personal options (accent colour, corner
 * roundness, automatic day / night). {@link #load} copies the chosen palette into {@link Ui}'s colour fields,
 * so every screen that draws with Ui.* follows it.
 */
final class Theme {
    final String id, name;
    final boolean light;
    final int bg, surface, surface2, line, text, dim, accent, onAccent, danger;
    final int termFg, termBg;
    final int[] ansi;          // 16 terminal colours (ARGB), or null for the classic set
    final String editor;       // matching code-editor theme id

    private Theme(String id, String name, boolean light, int bg, int surface, int surface2, int line, int text, int dim,
                  int accent, int onAccent, int danger, int termFg, int termBg, int[] ansi, String editor) {
        this.id = id; this.name = name; this.light = light;
        this.bg = bg; this.surface = surface; this.surface2 = surface2; this.line = line; this.text = text; this.dim = dim;
        this.accent = accent; this.onAccent = onAccent; this.danger = danger;
        this.termFg = termFg; this.termBg = termBg; this.ansi = ansi; this.editor = editor;
    }

    private static int c(int rgb) { return 0xFF000000 | rgb; }

    private static int[] pal(int... rgb) {
        int[] a = new int[16];
        for (int i = 0; i < 16; i++) a[i] = c(rgb[i]);
        return a;
    }

    /** Classic terminal colours (the palette the app always had). */
    static final int[] CLASSIC = pal(0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
            0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF);

    static final List<Theme> ALL = new ArrayList<>();
    static {
        ALL.add(new Theme("nullshell", "NullShell", false, c(0x0E1013), c(0x171A1F), c(0x21262D), c(0x262B33), c(0xE6E8EB), c(0x8B929C),
                c(0x3DD6B0), c(0x06201A), c(0xE5534B), c(0xE0E0E0), c(0x0E1013), null, "nullshell"));
        ALL.add(new Theme("amoled", "AMOLED black", false, c(0x000000), c(0x0B0B0D), c(0x17181B), c(0x202225), c(0xE8E8E8), c(0x8A8D93),
                c(0x3DD6B0), c(0x06201A), c(0xE5534B), c(0xE0E0E0), c(0x000000), null, "nullshell"));
        ALL.add(new Theme("kali", "Kali blue", false, c(0x0F1218), c(0x171B24), c(0x212735), c(0x2A3142), c(0xE3E7EF), c(0x8C96AA),
                c(0x367BF0), c(0xFFFFFF), c(0xF0524F), c(0xE3E7EF), c(0x0F1218), null, "nullshell"));
        ALL.add(new Theme("midnight", "Midnight", false, c(0x0B1020), c(0x111832), c(0x1A2347), c(0x222C55), c(0xDCE3F5), c(0x8792B5),
                c(0x5B8DEF), c(0x07122B), c(0xFF6B6B), c(0xDCE3F5), c(0x0B1020), null, "tokyo-night"));
        ALL.add(new Theme("dracula", "Dracula", false, c(0x21222C), c(0x282A36), c(0x343746), c(0x44475A), c(0xF8F8F2), c(0x9DA5CE),
                c(0xBD93F9), c(0x1E1B2E), c(0xFF5555), c(0xF8F8F2), c(0x282A36),
                pal(0x21222C, 0xFF5555, 0x50FA7B, 0xF1FA8C, 0xBD93F9, 0xFF79C6, 0x8BE9FD, 0xF8F8F2,
                        0x6272A4, 0xFF6E6E, 0x69FF94, 0xFFFFA5, 0xD6ACFF, 0xFF92DF, 0xA4FFFF, 0xFFFFFF), "dracula"));
        ALL.add(new Theme("nord", "Nord", false, c(0x242933), c(0x2E3440), c(0x3B4252), c(0x434C5E), c(0xECEFF4), c(0x9AA5B8),
                c(0x88C0D0), c(0x1B2430), c(0xBF616A), c(0xD8DEE9), c(0x2E3440),
                pal(0x3B4252, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x88C0D0, 0xE5E9F0,
                        0x4C566A, 0xBF616A, 0xA3BE8C, 0xEBCB8B, 0x81A1C1, 0xB48EAD, 0x8FBCBB, 0xECEFF4), "nord"));
        ALL.add(new Theme("gruvbox", "Gruvbox dark", false, c(0x1D2021), c(0x282828), c(0x3C3836), c(0x504945), c(0xEBDBB2), c(0xA89984),
                c(0xFABD2F), c(0x1D2021), c(0xFB4934), c(0xEBDBB2), c(0x282828),
                pal(0x282828, 0xCC241D, 0x98971A, 0xD79921, 0x458588, 0xB16286, 0x689D6A, 0xA89984,
                        0x928374, 0xFB4934, 0xB8BB26, 0xFABD2F, 0x83A598, 0xD3869B, 0x8EC07C, 0xEBDBB2), "gruvbox-dark"));
        ALL.add(new Theme("solarized-dark", "Solarized dark", false, c(0x00212B), c(0x002B36), c(0x073642), c(0x0F4A58), c(0xA7B5B5), c(0x7F9499),
                c(0x2AA198), c(0x00212B), c(0xDC322F), c(0x93A1A1), c(0x002B36),
                pal(0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5,
                        0x002B36, 0xCB4B16, 0x586E75, 0x657B83, 0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3), "solarized_dark"));
        ALL.add(new Theme("tokyo", "Tokyo Night", false, c(0x16161E), c(0x1A1B26), c(0x24283B), c(0x2F334D), c(0xC0CAF5), c(0x8089B3),
                c(0x7AA2F7), c(0x16161E), c(0xF7768E), c(0xC0CAF5), c(0x1A1B26),
                pal(0x15161E, 0xF7768E, 0x9ECE6A, 0xE0AF68, 0x7AA2F7, 0xBB9AF7, 0x7DCFFF, 0xA9B1D6,
                        0x414868, 0xF7768E, 0x9ECE6A, 0xE0AF68, 0x7AA2F7, 0xBB9AF7, 0x7DCFFF, 0xC0CAF5), "tokyo-night"));
        ALL.add(new Theme("monokai", "Monokai", false, c(0x1E1F1C), c(0x272822), c(0x3E3D32), c(0x49483E), c(0xF8F8F2), c(0xA6A68F),
                c(0xA6E22E), c(0x1E1F1C), c(0xF92672), c(0xF8F8F2), c(0x272822),
                pal(0x272822, 0xF92672, 0xA6E22E, 0xE6DB74, 0x66D9EF, 0xAE81FF, 0xA1EFE4, 0xF8F8F2,
                        0x75715E, 0xF92672, 0xA6E22E, 0xE6DB74, 0x66D9EF, 0xAE81FF, 0xA1EFE4, 0xF9F8F5), "monokai"));
        ALL.add(new Theme("mocha", "Catppuccin Mocha", false, c(0x181825), c(0x1E1E2E), c(0x313244), c(0x45475A), c(0xCDD6F4), c(0x9399B2),
                c(0xCBA6F7), c(0x1E1E2E), c(0xF38BA8), c(0xCDD6F4), c(0x1E1E2E),
                pal(0x45475A, 0xF38BA8, 0xA6E3A1, 0xF9E2AF, 0x89B4FA, 0xF5C2E7, 0x94E2D5, 0xBAC2DE,
                        0x585B70, 0xF38BA8, 0xA6E3A1, 0xF9E2AF, 0x89B4FA, 0xF5C2E7, 0x94E2D5, 0xA6ADC8), "catppuccin-mocha"));
        ALL.add(new Theme("hacker", "Hacker green", false, c(0x000000), c(0x07110A), c(0x0E1F13), c(0x143020), c(0x8CFFA0), c(0x45A862),
                c(0x00E639), c(0x001A06), c(0xFF4D4D), c(0x33FF33), c(0x000000),
                pal(0x000000, 0xCC3333, 0x33FF33, 0xCCCC33, 0x3399FF, 0xCC33CC, 0x33CCCC, 0xCCFFCC,
                        0x336633, 0xFF5555, 0x66FF66, 0xFFFF66, 0x66B2FF, 0xFF66FF, 0x66FFFF, 0xFFFFFF), "nullshell"));
        // light
        ALL.add(new Theme("light", "NullShell light", true, c(0xF6F7F9), c(0xFFFFFF), c(0xE9ECF0), c(0xD9DDE3), c(0x1B1F24), c(0x5C6670),
                c(0x0A8F74), c(0xFFFFFF), c(0xCF222E), c(0x24292F), c(0xFFFFFF),
                pal(0x24292F, 0xCF222E, 0x116329, 0x7A5900, 0x0969DA, 0x8250DF, 0x1B7C83, 0x6E7781,
                        0x57606A, 0xA40E26, 0x1A7F37, 0x633C01, 0x218BFF, 0xA475F9, 0x3192AA, 0x8C959F), "github-light"));
        ALL.add(new Theme("solarized-light", "Solarized light", true, c(0xFDF6E3), c(0xEEE8D5), c(0xE3DCC6), c(0xD3CBB1), c(0x3F565F), c(0x657B83),
                c(0x268BD2), c(0xFFFFFF), c(0xDC322F), c(0x586E75), c(0xFDF6E3),
                pal(0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5,
                        0x002B36, 0xCB4B16, 0x586E75, 0x657B83, 0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3), "solarized-light"));
        ALL.add(new Theme("latte", "Catppuccin Latte", true, c(0xEFF1F5), c(0xFFFFFF), c(0xE6E9EF), c(0xCCD0DA), c(0x4C4F69), c(0x6C6F85),
                c(0x8839EF), c(0xFFFFFF), c(0xD20F39), c(0x4C4F69), c(0xEFF1F5),
                pal(0x5C5F77, 0xD20F39, 0x40A02B, 0xDF8E1D, 0x1E66F5, 0xEA76CB, 0x179299, 0xACB0BE,
                        0x6C6F85, 0xD20F39, 0x40A02B, 0xDF8E1D, 0x1E66F5, 0xEA76CB, 0x179299, 0x4C4F69), "catppuccin-latte"));
        ALL.add(new Theme("paper", "Paper", true, c(0xFAF8F3), c(0xFFFFFF), c(0xF0ECE2), c(0xDDD7C8), c(0x2B2B2B), c(0x6B665B),
                c(0xB5541C), c(0xFFFFFF), c(0xC0392B), c(0x2B2B2B), c(0xFFFDF8),
                pal(0x2B2B2B, 0xC0392B, 0x2F7D32, 0x8A6D00, 0x1F5FA8, 0x8E3B8E, 0x1B7C83, 0x777777,
                        0x555555, 0xE74C3C, 0x3E9E43, 0xB38600, 0x2E7AD1, 0xA64CA6, 0x2699A3, 0x9A9A9A), "quietlight"));
    }

    static Theme byId(String id) {
        for (Theme t : ALL) if (t.id.equals(id)) return t;
        return ALL.get(0);
    }

    // ------------------------------------------------------------------ options

    static final String K_THEME = "theme_id", K_ACCENT = "theme_accent", K_AUTO = "theme_auto", K_DAY = "theme_day", K_NIGHT = "theme_night",
            K_RADIUS = "theme_radius";

    /** Accent colours offered next to "Theme default". */
    static final int[] ACCENTS = {0xFF3DD6B0, 0xFF5B8DEF, 0xFFBD93F9, 0xFFFF79C6, 0xFFF76C6C, 0xFFFF9E64, 0xFFFABD2F, 0xFFA6E22E, 0xFF00E639, 0xFF2AA198};
    static final String[] RADIUS_NAMES = {"Sharp", "Normal", "Round", "Pill"};
    static final float[] RADIUS_SCALE = {0.35f, 1f, 1.5f, 2.2f};

    private static SharedPreferences sp(Context c) { return Prefs.sp(c); }

    /** Bumped whenever the look changes; screens compare it to know when to rebuild. */
    static volatile int generation = 1;
    static Theme current = ALL.get(0);
    static int accentOverride;           // 0 = use the theme's own

    static boolean auto(Context c) { return sp(c).getBoolean(K_AUTO, false); }
    static String chosenId(Context c) { return sp(c).getString(K_THEME, "nullshell"); }
    static String dayId(Context c) { return sp(c).getString(K_DAY, "light"); }
    static String nightId(Context c) { return sp(c).getString(K_NIGHT, "nullshell"); }
    static int accent(Context c) { return sp(c).getInt(K_ACCENT, 0); }
    static int radiusIndex(Context c) { return Math.max(0, Math.min(RADIUS_SCALE.length - 1, sp(c).getInt(K_RADIUS, 1))); }

    static boolean systemIsNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    static Theme resolve(Context c) {
        if (auto(c)) return byId(systemIsNight(c) ? nightId(c) : dayId(c));
        return byId(chosenId(c));
    }

    /** Reads the saved choice and installs it into Ui; returns true when anything changed. */
    static boolean load(Context c) {
        Theme t = resolve(c);
        int acc = accent(c);
        int rad = radiusIndex(c);
        boolean changed = t != current || acc != accentOverride || Ui.radiusScale != RADIUS_SCALE[rad];
        current = t;
        accentOverride = acc;
        Ui.install(t, acc, RADIUS_SCALE[rad]);
        TermEmulator.setAnsiPalette(t.ansi != null ? t.ansi : CLASSIC);
        if (changed) generation++;
        return changed;
    }

    static void save(Context c, String id, boolean auto, String day, String night, int accent, int radius) {
        sp(c).edit().putString(K_THEME, id).putBoolean(K_AUTO, auto).putString(K_DAY, day).putString(K_NIGHT, night)
                .putInt(K_ACCENT, accent).putInt(K_RADIUS, radius).apply();
        load(c);
    }

    static List<Theme> darkThemes() {
        List<Theme> out = new ArrayList<>();
        for (Theme t : ALL) if (!t.light) out.add(t);
        return Collections.unmodifiableList(out);
    }

    static List<Theme> lightThemes() {
        List<Theme> out = new ArrayList<>();
        for (Theme t : ALL) if (t.light) out.add(t);
        return Collections.unmodifiableList(out);
    }

    // ------------------------------------------------------------------ colour maths

    static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    static int withAlpha(int rgb, int alpha) { return (alpha << 24) | (rgb & 0xFFFFFF); }

    /** Relative luminance 0..1 (sRGB). */
    static double luminance(int c) {
        double[] v = new double[3];
        int[] ch = {(c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF};
        for (int i = 0; i < 3; i++) {
            double s = ch[i] / 255.0;
            v[i] = s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2];
    }

    /** Readable text colour (near black or white) for a filled background. */
    static int onColor(int bg) { return luminance(bg) > 0.4 ? 0xFF10141A : 0xFFFFFFFF; }

    /** Parses "RRGGBB" or "#RRGGBB"; returns 0 when invalid. */
    static int parseHex(String s) {
        if (s == null) return 0;
        s = s.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (!s.matches("[0-9A-Fa-f]{6}")) return 0;
        return 0xFF000000 | Integer.parseInt(s, 16);
    }
}
