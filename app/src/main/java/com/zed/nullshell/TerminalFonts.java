package com.zed.nullshell;

import android.content.Context;
import android.graphics.Typeface;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Nerd Fonts for the terminal: the programming fonts patched with the icons and symbols that prompts and tools such as
 * Starship, Powerlevel10k, eza and lsd draw. They are downloaded on request from the Nerd Fonts project's GitHub
 * releases and kept in the app's private storage. Only the "Mono" variant (every glyph exactly one cell wide, which a
 * terminal grid needs) is kept, and only its Regular and Bold files.
 */
final class TerminalFonts {
    private TerminalFonts() { }

    static final class Font {
        final String id, label, zip, prefix;
        /** @param zip release archive name without ".zip"; @param prefix start of the font file names inside it */
        Font(String id, String label, String zip, String prefix) {
            this.id = id; this.label = label; this.zip = zip; this.prefix = prefix;
        }
    }

    static final List<Font> ALL = Arrays.asList(
            new Font("jetbrains", "JetBrains Mono", "JetBrainsMono", "JetBrainsMono"),
            new Font("firacode", "Fira Code", "FiraCode", "FiraCode"),
            new Font("hack", "Hack", "Hack", "Hack"),
            new Font("meslo", "Meslo LG S", "Meslo", "MesloLGS"),
            new Font("caskaydia", "Caskaydia Cove (Cascadia Code)", "CascadiaCode", "CaskaydiaCove"),
            new Font("ubuntumono", "Ubuntu Mono", "UbuntuMono", "UbuntuMono"),
            new Font("saucecode", "Sauce Code Pro (Source Code Pro)", "SourceCodePro", "SauceCodePro")
    );

    static Font byId(String id) {
        for (Font f : ALL) if (f.id.equals(id)) return f;
        return null;
    }

    private static File dir(Context c, Font f) { return new File(new File(c.getFilesDir(), "fonts"), f.id); }

    static boolean installed(Context c, Font f) { return new File(dir(c, f), "Regular.ttf").isFile(); }

    /** Disk used by all downloaded fonts. */
    static long installedBytes(Context c) {
        long n = 0;
        for (Font f : ALL) {
            File[] files = dir(c, f).listFiles();
            if (files != null) for (File x : files) n += x.length();
        }
        return n;
    }

    static int installedCount(Context c) {
        int n = 0;
        for (Font f : ALL) if (installed(c, f)) n++;
        return n;
    }

    /** Removes every downloaded font. */
    static void removeAll(Context c) {
        for (Font f : ALL) {
            File[] files = dir(c, f).listFiles();
            if (files != null) for (File x : files) //noinspection ResultOfMethodCallIgnored
                x.delete();
            //noinspection ResultOfMethodCallIgnored
            dir(c, f).delete();
        }
        synchronized (cache) { cache.clear(); }
    }

    /** The name shown for the font chosen in settings. */
    static String currentLabel(Context c) {
        Font f = byId(Prefs.termFont(c));
        return f != null && installed(c, f) ? f.label + " Nerd Font" : "System monospace";
    }

    // ------------------------------------------------------------------ loading

    private static final Map<String, Typeface[]> cache = new HashMap<>();

    /**
     * {regular, bold} of the chosen Nerd Font (bold may be null: the terminal then fakes it), or null for the system
     * monospace font (nothing chosen, not downloaded any more, or the file cannot be read).
     */
    static Typeface[] current(Context c) {
        Font f = byId(Prefs.termFont(c));
        if (f == null || !installed(c, f)) return null;
        File reg = new File(dir(c, f), "Regular.ttf");
        File bold = new File(dir(c, f), "Bold.ttf");
        String key = f.id + ":" + reg.lastModified() + ":" + bold.lastModified();
        synchronized (cache) {
            Typeface[] hit = cache.get(key);
            if (hit != null) return hit;
            try {
                Typeface r = Typeface.createFromFile(reg);
                Typeface b = null;
                if (bold.isFile()) {
                    try { b = Typeface.createFromFile(bold); } catch (RuntimeException ignored) { }
                }
                Typeface[] pair = {r, b};
                cache.clear();
                cache.put(key, pair);
                return pair;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ downloading

    interface Progress {
        void onProgress(long done, long total);
        boolean cancelled();
    }

    static final class Cancelled extends IOException {
        Cancelled() { super("Cancelled"); }
    }

    private static final String RELEASE_URL = "https://github.com/ryanoasis/nerd-fonts/releases/latest/download/";

    /** Counts what has been read from the network and lets the caller stop the transfer. */
    private static final class Counting extends FilterInputStream {
        private final Progress progress;
        private final long total;
        private long done;
        private long lastReport;

        Counting(InputStream in, long total, Progress p) { super(in); this.total = total; this.progress = p; }

        private void tick(int n) throws IOException {
            if (n <= 0) return;
            done += n;
            if (progress.cancelled()) throw new Cancelled();
            long now = System.currentTimeMillis();
            if (now - lastReport > 150) { lastReport = now; progress.onProgress(done, total); }
        }

        @Override public int read() throws IOException { int b = super.read(); if (b >= 0) tick(1); return b; }
        @Override public int read(byte[] buf, int off, int len) throws IOException { int n = super.read(buf, off, len); tick(n); return n; }
    }

    private static final class Candidate {
        byte[] data;
        String name = "";
        boolean exact;
        boolean beats(String candidateName, boolean candidateExact) {
            if (data == null) return true;
            if (candidateExact != exact) return candidateExact;
            return candidateName.length() < name.length();
        }
    }

    /**
     * Downloads the font's release archive and keeps its Regular and Bold Mono files. The archive is read as it
     * arrives (nothing is stored but the two fonts) and reading stops as soon as both are found.
     */
    static void download(Context c, Font f, Progress p) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(RELEASE_URL + f.zip + ".zip").openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "NullShell");
        byte[][] picked;
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("The download failed (HTTP " + code + ")");
            long total = conn.getContentLengthLong();
            p.onProgress(0, total);
            picked = pick(new BufferedInputStream(new Counting(conn.getInputStream(), total, p), 64 * 1024), f);
        } finally {
            conn.disconnect();
        }
        final byte[] regularData = picked[0], boldData = picked[1];
        if (regularData == null) throw new IOException("The font file was not found in the download");
        p.onProgress(1, 1);

        File d = dir(c, f);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("Could not create the font folder");
        write(new File(d, "Regular.ttf"), regularData);
        if (boldData != null) write(new File(d, "Bold.ttf"), boldData);
        else //noinspection ResultOfMethodCallIgnored
            new File(d, "Bold.ttf").delete();
        try {
            Typeface.createFromFile(new File(d, "Regular.ttf"));
        } catch (RuntimeException e) {
            removeAll(c);
            throw new IOException("The downloaded font could not be loaded");
        }
        synchronized (cache) { cache.clear(); }
    }

    /**
     * Reads a Nerd Fonts archive as a stream and returns {regular, bold} file contents (bold may be null). The exact
     * "<prefix>NerdFontMono-Regular.ttf" wins; otherwise the shortest matching name is used. Stops reading as soon as
     * both exact files have been found.
     */
    static byte[][] pick(InputStream raw, Font f) throws IOException {
        Candidate reg = new Candidate(), bold = new Candidate();
        try (ZipInputStream z = new ZipInputStream(raw)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                String name = e.getName();
                String lower = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
                boolean isReg = lower.endsWith("nerdfontmono-regular.ttf");
                boolean isBold = lower.endsWith("nerdfontmono-bold.ttf");
                if (!isReg && !isBold) continue;
                String want = (f.prefix + "NerdFontMono-" + (isReg ? "Regular" : "Bold") + ".ttf").toLowerCase(Locale.ROOT);
                boolean exact = lower.equals(want);
                Candidate slot = isReg ? reg : bold;
                if (!slot.beats(lower, exact)) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
                byte[] buf = new byte[32 * 1024];
                int n;
                while ((n = z.read(buf)) != -1) out.write(buf, 0, n);
                slot.data = out.toByteArray();
                slot.name = lower;
                slot.exact = exact;
                if (reg.exact && bold.exact) break;
            }
        }
        return new byte[][]{reg.data, bold.data};
    }

    private static void write(File dest, byte[] data) throws IOException {
        File tmp = new File(dest.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(data); }
        if (dest.exists() && !dest.delete()) throw new IOException("Could not replace " + dest.getName());
        if (!tmp.renameTo(dest)) throw new IOException("Could not save " + dest.getName());
    }

    /** All font ids, for building lists. */
    static List<String> ids() {
        List<String> out = new ArrayList<>();
        for (Font f : ALL) out.add(f.id);
        return out;
    }
}
