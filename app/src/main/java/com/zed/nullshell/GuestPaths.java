package com.zed.nullshell;

import java.io.File;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Paths inside Kali ("/root/x.py") versus paths on the phone (".../files/kali-arm64/root/x.py").
 * Symlinks are resolved the way the guest would: an absolute target is relative to the guest root,
 * so nothing ever escapes the rootfs. Pure Java for desktop testing.
 */
final class GuestPaths {
    private GuestPaths() { }

    private static final String[] PROJECT_MARKERS = {
            ".git", "package.json", "pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", "tsconfig.json",
            "Cargo.toml", "go.mod", "compile_commands.json", "CMakeLists.txt", "Makefile", ".project"};

    /** Absolute, with "." and ".." resolved and no trailing slash ("/" stays "/"). */
    static String normalize(String path) {
        List<String> out = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (!out.isEmpty()) out.remove(out.size() - 1); continue; }
            out.add(part);
        }
        if (out.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder();
        for (String s : out) sb.append('/').append(s);
        return sb.toString();
    }

    static String parent(String guest) {
        String n = normalize(guest);
        int i = n.lastIndexOf('/');
        return i <= 0 ? "/" : n.substring(0, i);
    }

    static String name(String guest) {
        String n = normalize(guest);
        return n.substring(n.lastIndexOf('/') + 1);
    }

    static String join(String dir, String name) {
        return normalize((dir.endsWith("/") ? dir : dir + "/") + name);
    }

    /**
     * Where a guest path lives on the phone, or null if the path is invalid. Follows symlinks like the
     * guest does; the final component is only followed when followLast is true.
     */
    static File toHost(File rootfs, String guest, boolean followLast) {
        if (guest == null || !guest.startsWith("/")) return null;
        List<String> pending = new ArrayList<>();
        String[] parts = normalize(guest).split("/");
        for (int i = parts.length - 1; i >= 1; i--) pending.add(parts[i]);     // used as a stack: last = next
        List<String> resolved = new ArrayList<>();
        int hops = 0;
        while (!pending.isEmpty()) {
            String part = pending.remove(pending.size() - 1);
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (!resolved.isEmpty()) resolved.remove(resolved.size() - 1); continue; }
            File candidate = hostOf(rootfs, resolved, part);
            boolean last = pending.isEmpty();
            Path p = candidate.toPath();
            if (Files.isSymbolicLink(p) && (!last || followLast)) {
                if (++hops > 40) return null;
                String target;
                try { target = Files.readSymbolicLink(p).toString(); } catch (Exception e) { return null; }
                if (target.startsWith("/")) resolved.clear();                   // guest-absolute: restart at the guest root
                String[] tp = target.split("/");
                for (int i = tp.length - 1; i >= 0; i--) pending.add(tp[i]);
            } else {
                resolved.add(part);
            }
        }
        return hostOf(rootfs, resolved, null);
    }

    private static File hostOf(File rootfs, List<String> parts, String extra) {
        File f = rootfs;
        for (String s : parts) f = new File(f, s);
        return extra == null ? f : new File(f, extra);
    }

    /** Inverse of toHost for paths that are plainly inside the rootfs, or null. */
    static String toGuest(File rootfs, File host) {
        try {
            String root = rootfs.getCanonicalPath();
            String h = host.getCanonicalPath();
            if (h.equals(root)) return "/";
            if (!h.startsWith(root + File.separator)) return null;
            return normalize(h.substring(root.length()));
        } catch (Exception e) {
            return null;
        }
    }

    /** "file:///root/a%20b.py" or "file:/root/a.py" to "/root/a b.py"; null for anything else. */
    static String fromUri(String uri) {
        if (uri == null || !uri.startsWith("file:")) return null;
        try {
            String path = URI.create(uri).getPath();
            if (path == null) path = URLDecoder.decode(uri.replaceFirst("^file:(//)?", ""), StandardCharsets.UTF_8.name());
            return normalize(path);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The folder a language server should treat as the project: the nearest parent with a project marker,
     * otherwise the file's own folder. Never "/" and never the home folder (servers would crawl everything).
     */
    static String projectRoot(File rootfs, String guestFile) {
        String start = parent(guestFile);
        String dir = start;
        while (!dir.equals("/") && !dir.equals("/root")) {
            File host = toHost(rootfs, dir, true);
            if (host != null) for (String m : PROJECT_MARKERS) if (new File(host, m).exists()) return dir;
            dir = parent(dir);
        }
        return start;
    }

    private static final String[] PATH_DIRS = {"/usr/local/sbin", "/usr/local/bin", "/usr/sbin", "/usr/bin", "/sbin", "/bin"};

    /** Guest path of an executable found on the guest's PATH (or given absolutely), or null. */
    static String which(File rootfs, String program) {
        if (program == null || program.isEmpty()) return null;
        if (program.startsWith("/")) return isExecutable(rootfs, program) ? normalize(program) : null;
        for (String dir : PATH_DIRS) {
            String candidate = dir + "/" + program;
            if (isExecutable(rootfs, candidate)) return candidate;
        }
        return null;
    }

    private static boolean isExecutable(File rootfs, String guest) {
        File f = toHost(rootfs, guest, true);
        return f != null && f.isFile() && f.canExecute();
    }
}
