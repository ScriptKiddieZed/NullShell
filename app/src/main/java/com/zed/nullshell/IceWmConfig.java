package com.zed.nullshell;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * IceWM looks for the VNC desktop. Themes are real IceWM themes: the ~100 community themes of the official
 * "icewm-extra" collection (GPL-3.0, github.com/ice-wm/icewm-extra) are downloaded on request into ~/.icewm/themes in
 * Kali, and the themes that come with IceWM itself are offered too. On every start a small shell snippet writes the
 * selected theme, an interface size (fonts), no CPU/network graphs, a solid background and a start menu with the
 * installed Kali GUI tools. Everything else is plain text, so it needs no packages.
 */
final class IceWmConfig {
    private IceWmConfig() { }

    static final String[] SIZE_NAMES = {"Normal", "Large", "Extra large"};
    private static final int[] FONT_PT = {11, 13, 16};

    /** Themes from the collection that suit a phone screen, shown first in the picker (when installed). */
    static final List<String> RECOMMENDED = Arrays.asList(
            "Nord", "Nitrogen", "Ice7", "Arc-Dark", "dotFLAT", "win95-dark", "twilite", "dusk", "Crux-Teal", "nice2", "gtk2", "SilverXP");

    /** Themes shipped inside the APK (assets/icewm-themes), copied into ~/.icewm/themes on first use. */
    private static final String ASSET_DIR = "icewm-themes";

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static File home(Context c) { return new File(new File(RootfsManager.dir(c), "root"), ".icewm"); }
    private static File userThemes(Context c) { return new File(home(c), "themes"); }
    private static File systemThemes(Context c) { return new File(RootfsManager.dir(c), "usr/share/icewm/themes"); }

    // ------------------------------------------------------------------ bundled themes

    /** Copies the themes that ship with the app into the Kali home folder (once each). Needs the rootfs to be installed. */
    static void ensureBundled(Context c) {
        if (!RootfsManager.isInstalled(c)) return;
        try {
            android.content.res.AssetManager am = c.getAssets();
            String[] names = am.list(ASSET_DIR);
            if (names == null) return;
            for (String name : names) {
                if (!SAFE_NAME.matcher(name).matches()) continue;
                File target = new File(userThemes(c), name);
                if (new File(target, "default.theme").isFile()) continue;
                copyAsset(am, ASSET_DIR + "/" + name, target);
            }
        } catch (IOException ignored) { }          // the picker simply shows what is there
    }

    private static void copyAsset(android.content.res.AssetManager am, String path, File dest) throws IOException {
        String[] kids = am.list(path);
        if (kids != null && kids.length > 0) {
            if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("Could not create " + dest.getName());
            for (String k : kids) copyAsset(am, path + "/" + k, new File(dest, k));
            return;
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create " + parent.getName());
        try (InputStream in = am.open(path); FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    // ------------------------------------------------------------------ what is installed

    /** Names of every theme that can be selected: downloaded ones and those bundled with IceWM, sorted, no duplicates. */
    static List<String> installedThemes(Context c) {
        Set<String> names = new HashSet<>();
        for (File dir : new File[]{userThemes(c), systemThemes(c)}) {
            File[] kids = dir.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                if (k.isDirectory() && SAFE_NAME.matcher(k.getName()).matches() && new File(k, "default.theme").isFile())
                    names.add(k.getName());
            }
        }
        List<String> out = new ArrayList<>(names);
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    static int downloadedCount(Context c) {
        File[] kids = userThemes(c).listFiles();
        int n = 0;
        if (kids != null) for (File k : kids) if (k.isDirectory()) n++;
        return n;
    }

    /** Label for the button: the chosen theme, or the default. */
    static String themeLabel(Context c) {
        String t = Prefs.vncTheme(c);
        return t.isEmpty() || !installedThemes(c).contains(t) ? "IceWM default" : t;
    }

    // ------------------------------------------------------------------ reset / remove

    /** Deletes the files this app writes (not the downloaded themes); they are written again at the next start. */
    static void reset(Context c) {
        for (String n : new String[]{"preferences", "prefoverride", "theme", "menu"})
            //noinspection ResultOfMethodCallIgnored
            new File(home(c), n).delete();
    }

    static void removeDownloadedThemes(Context c) { deleteTree(userThemes(c)); }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ------------------------------------------------------------------ downloading the theme collection

    private static final String ZIP_URL = "https://github.com/ice-wm/icewm-extra/archive/refs/heads/main.zip";

    private static final class Counting extends FilterInputStream {
        private final TerminalFonts.Progress progress;
        private final long total;
        private long done, lastReport;

        Counting(InputStream in, long total, TerminalFonts.Progress p) { super(in); this.total = total; this.progress = p; }

        private void tick(int n) throws IOException {
            if (n <= 0) return;
            done += n;
            if (progress.cancelled()) throw new TerminalFonts.Cancelled();
            long now = System.currentTimeMillis();
            if (now - lastReport > 150) { lastReport = now; progress.onProgress(done, total); }
        }

        @Override public int read() throws IOException { int b = super.read(); if (b >= 0) tick(1); return b; }
        @Override public int read(byte[] buf, int off, int len) throws IOException { int n = super.read(buf, off, len); tick(n); return n; }
    }

    /**
     * Downloads the icewm-extra archive and unpacks every theme into ~/.icewm/themes. The archive is read as it arrives,
     * so nothing but the themes is stored. Returns the number of themes unpacked.
     */
    static int downloadThemes(Context c, TerminalFonts.Progress p) throws IOException {
        File dest = userThemes(c);
        if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("Could not create the themes folder");
        String destPath = dest.getCanonicalPath() + File.separator;

        HttpURLConnection conn = (HttpURLConnection) new URL(ZIP_URL).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "NullShell");
        Set<String> themes = new HashSet<>();
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("The download failed (HTTP " + code + ")");
            long total = conn.getContentLengthLong();
            p.onProgress(0, total);
            try (ZipInputStream z = new ZipInputStream(new java.io.BufferedInputStream(new Counting(conn.getInputStream(), total, p), 64 * 1024))) {
                ZipEntry e;
                byte[] buf = new byte[32 * 1024];
                while ((e = z.getNextEntry()) != null) {
                    // entries look like "icewm-extra-main/<Theme>/<file>"; the first folder is dropped
                    String name = e.getName();
                    int slash = name.indexOf('/');
                    if (slash < 0) continue;
                    String rel = name.substring(slash + 1);
                    if (rel.isEmpty() || rel.startsWith(".")) continue;
                    int second = rel.indexOf('/');
                    if (second < 0) continue;                                   // files in the repository root
                    String theme = rel.substring(0, second);
                    if (!SAFE_NAME.matcher(theme).matches()) continue;
                    File out = new File(dest, rel);
                    if (!out.getCanonicalPath().startsWith(destPath)) continue; // never write outside the folder
                    if (e.isDirectory()) { //noinspection ResultOfMethodCallIgnored
                        out.mkdirs(); continue; }
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Could not create " + parent.getName());
                    try (FileOutputStream fo = new FileOutputStream(out)) {
                        int n;
                        while ((n = z.read(buf)) != -1) fo.write(buf, 0, n);
                    }
                    themes.add(theme);
                }
            }
        } finally {
            conn.disconnect();
        }
        if (themes.isEmpty()) throw new IOException("The download did not contain any themes");
        p.onProgress(1, 1);
        return themes.size();
    }

    // ------------------------------------------------------------------ what gets written at start

    private static String font(int pt, boolean bold) { return "\"sans:" + (bold ? "bold:" : "") + "size=" + pt + "\""; }

    /** Settings that must win over whatever the theme says (IceWM reads "prefoverride" after the theme). */
    private static String override(Context c) {
        int size = Prefs.vncUiSize(c);
        int pt = FONT_PT[size];
        StringBuilder p = new StringBuilder();
        p.append("# Written by NullShell. \"Keep my own desktop config\" in Settings stops this.\n");
        // larger interface: bigger title bars, borders and taskbar icons than the theme's (themes are drawn for desktops)
        if (size > 0) {
            p.append("TitleBarHeight=").append(size == 1 ? 30 : 38).append('\n');
            p.append("BorderSizeX=").append(size == 1 ? 4 : 6).append("\nBorderSizeY=").append(size == 1 ? 4 : 6).append('\n');
            p.append("SmallIconSize=").append(size == 1 ? 24 : 32).append('\n');
        }
        p.append("TaskBarShowCPUStatus=0\nTaskBarShowNetStatus=0\nTaskBarShowMEMStatus=0\nTaskBarShowAPMStatus=0\n");
        p.append("TaskBarShowMailboxStatus=0\nTaskBarShowClock=1\nTaskBarShowWorkspaces=0\nTaskBarShowTray=1\n");
        p.append("TaskBarDoubleHeight=0\nTaskBarAutoHide=0\n");
        String normal = font(pt, false), bold = font(pt, true);
        for (String k : new String[]{"MenuFontNameXft", "StatusFontNameXft", "NormalTaskBarFontNameXft",
                "ToolButtonFontNameXft", "NormalButtonFontNameXft", "ListBoxFontNameXft", "ClockFontNameXft",
                "LabelFontNameXft", "InputFontNameXft", "ToolTipFontNameXft", "QuickSwitchFontNameXft"})
            p.append(k).append('=').append(normal).append('\n');
        for (String k : new String[]{"TitleFontNameXft", "ActiveTaskBarFontNameXft", "ActiveButtonFontNameXft"})
            p.append(k).append('=').append(bold).append('\n');
        return p.toString();
    }

    /** Shell snippet for the VNC start script. Quoted heredocs, so nothing in the text is expanded by the shell. */
    static String script(Context c) {
        StringBuilder s = new StringBuilder();
        boolean keep = Prefs.vncKeepConfig(c);
        // with "keep my own config" nothing is written once ~/.icewm/preferences exists
        s.append("if ").append(keep ? "[ ! -e \"$HOME/.icewm/preferences\" ]" : "true").append("; then\n");
        s.append("mkdir -p \"$HOME/.icewm/themes\"\n");

        String t = Prefs.vncTheme(c);
        if (!SAFE_NAME.matcher(t).matches()) t = "";
        s.append("NS_THEME=").append(ProotLauncher.quote(t)).append("\n");
        // the theme is used only if it is really there (it may have been removed since it was chosen)
        s.append("if [ -n \"$NS_THEME\" ] && { [ -f \"$HOME/.icewm/themes/$NS_THEME/default.theme\" ] || ")
                .append("[ -f \"/usr/share/icewm/themes/$NS_THEME/default.theme\" ]; }; then\n");
        s.append("  printf 'Theme=\"%s/default.theme\"\\n' \"$NS_THEME\" > \"$HOME/.icewm/theme\"\n");
        s.append("  printf '# Written by NullShell\\nTheme=\"%s/default.theme\"\\n' \"$NS_THEME\" > \"$HOME/.icewm/preferences\"\n");
        s.append("else\n");
        s.append("  rm -f \"$HOME/.icewm/theme\"\n");
        s.append("  printf '# Written by NullShell\\n' > \"$HOME/.icewm/preferences\"\n");
        s.append("fi\n");

        s.append("cat > \"$HOME/.icewm/prefoverride\" <<'NS_OVERRIDE_EOF'\n").append(override(c)).append("NS_OVERRIDE_EOF\n");
        // a solid background, unless the chosen theme brings its own wallpaper
        s.append("NS_TF=\"$HOME/.icewm/themes/$NS_THEME/default.theme\"; [ -f \"$NS_TF\" ] || NS_TF=\"/usr/share/icewm/themes/$NS_THEME/default.theme\"\n");
        s.append("if [ -z \"$NS_THEME\" ] || ! grep -qE '^[[:space:]]*DesktopBackgroundImage[[:space:]]*=[[:space:]]*\"[^\"]+\"' \"$NS_TF\" 2>/dev/null; then\n");
        s.append("  printf 'DesktopBackgroundImage=\"\"\\nDesktopBackgroundColor=\"rgb:20/24/2C\"\\n' >> \"$HOME/.icewm/prefoverride\"\n");
        s.append("fi\n");

        s.append("{\n");
        s.append("  echo '# Written by NullShell: the tools below appear once they are installed.'\n");
        s.append("  add() { command -v \"$2\" >/dev/null 2>&1 && printf 'prog \"%s\" - %s\\n' \"$1\" \"$3\"; }\n");
        s.append("  add 'Terminal' xterm 'xterm -fa Monospace -fs 11'\n");
        s.append("  add 'Files' thunar thunar\n");
        s.append("  add 'Files' pcmanfm pcmanfm\n");
        s.append("  add 'Web browser' firefox-esr firefox-esr\n");
        s.append("  add 'Text editor' mousepad mousepad\n");
        s.append("  echo 'separator'\n");
        s.append("  add 'Wireshark' wireshark wireshark\n");
        s.append("  add 'Burp Suite' burpsuite burpsuite\n");
        s.append("  add 'Zenmap' zenmap zenmap\n");
        s.append("  add 'Ettercap' ettercap 'ettercap -G'\n");
        s.append("  add 'GIMP' gimp gimp\n");
        s.append("  echo 'separator'\n");
        s.append("  command -v icewm-menu-fdo >/dev/null 2>&1 && echo 'menuprog \"All applications\" - icewm-menu-fdo'\n");
        s.append("  echo 'separator'\n");
        s.append("  echo 'prog \"Restart IceWM\" - icewm --restart'\n");
        s.append("} > \"$HOME/.icewm/menu\"\n");
        s.append("fi\n");
        return s.toString();
    }
}
