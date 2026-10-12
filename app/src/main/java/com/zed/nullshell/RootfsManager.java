package com.zed.nullshell;

import android.content.Context;
import android.net.Uri;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Everything that touches the Kali rootfs on disk. All methods block; call them off the UI thread. */
public final class RootfsManager {
    private RootfsManager() { }

    public interface Progress {
        void status(String s);   // short, replaces the previous status
        void log(String s);      // appended to the console
    }

    static final String ROOTFS_URL =
            "https://artifacts.kali.org/images-nethunter/nethunter-installer/kali-2026.2/" +
            "kali-nethunter-2026.2-generic-arm64-minimal.zip";
    static final String ROOTFS_NAME = "kali-arm64";
    static final String INSTALLED_MARKER = ".installed";

    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    public static boolean isBusy() { return BUSY.get(); }

    private static void begin() throws IOException {
        if (!BUSY.compareAndSet(false, true)) throw new IOException("Another rootfs operation is already running");
    }

    // ------------------------------------------------------------------ state

    public static File dir(Context c) { return new File(c.getFilesDir(), ROOTFS_NAME); }

    public static boolean isInstalled(Context c) {
        File root = dir(c);
        return root.isDirectory() && new File(root, INSTALLED_MARKER).isFile() && findBash(root) != null;
    }

    public static long sizeOf(File f) {
        try {
            StructStat st = Os.lstat(f.getAbsolutePath());
            if (OsConstants.S_ISREG(st.st_mode)) return st.st_size;
            if (OsConstants.S_ISDIR(st.st_mode)) {
                long sum = 0;
                File[] kids = f.listFiles();
                if (kids != null) for (File k : kids) sum += sizeOf(k);
                return sum;
            }
        } catch (ErrnoException ignored) { }
        return 0;
    }

    // ------------------------------------------------------------------ install (download)

    public static void install(Context c, Progress p) throws IOException {
        begin();
        File zip = new File(c.getCacheDir(), "kali-installer.zip");
        File staging = new File(c.getFilesDir(), ROOTFS_NAME + ".installing");
        try {
            cleanLeftovers(c);
            staging.mkdirs();
            download(zip, p);
            p.log("[+] Download complete: " + formatBytes(zip.length()) + "\n");
            p.status("Extracting Kali rootfs…");
            extractFromInstallerZip(zip, staging, p);
            finalizeInstall(c, staging, p);
        } catch (IOException | RuntimeException e) {
            deleteTree(staging);
            throw e;
        } finally {
            zip.delete();
            BUSY.set(false);
        }
    }

    // ------------------------------------------------------------------ import

    public static void importFrom(Context c, Uri uri, Progress p) throws IOException {
        begin();
        File staging = new File(c.getFilesDir(), ROOTFS_NAME + ".installing");
        File tempZip = new File(c.getCacheDir(), "import-installer.zip");
        try {
            cleanLeftovers(c);
            staging.mkdirs();
            p.status("Importing rootfs…");

            try (InputStream raw = c.getContentResolver().openInputStream(uri)) {
                if (raw == null) throw new IOException("Could not open the selected file");
                BufferedInputStream in = new BufferedInputStream(raw, 1 << 16);
                in.mark(1024);
                byte[] head = new byte[512];
                int n = readFully(in, head);
                in.reset();

                if (n >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
                    p.log("[i] Detected NetHunter installer ZIP\n");
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tempZip))) {
                        copy(in, out, new byte[128 * 1024]);
                    }
                    extractFromInstallerZip(tempZip, staging, p);
                } else if (n >= 2 && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B) {
                    p.log("[i] Detected tar.gz archive\n");
                    try (TarArchiveInputStream tar = new TarArchiveInputStream(
                            new BufferedInputStream(new GzipCompressorInputStream(in, true)))) {
                        extractTar(tar, staging, p);
                    }
                } else if (n >= 6 && (head[0] & 0xFF) == 0xFD && head[1] == '7' && head[2] == 'z'
                        && head[3] == 'X' && head[4] == 'Z' && head[5] == 0) {
                    p.log("[i] Detected tar.xz archive\n");
                    try (TarArchiveInputStream tar = new TarArchiveInputStream(
                            new BufferedInputStream(new XZCompressorInputStream(in)))) {
                        extractTar(tar, staging, p);
                    }
                } else if (n >= 262 && head[257] == 'u' && head[258] == 's' && head[259] == 't'
                        && head[260] == 'a' && head[261] == 'r') {
                    p.log("[i] Detected plain tar archive\n");
                    try (TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
                        extractTar(tar, staging, p);
                    }
                } else {
                    throw new IOException("Unsupported file. Use .tar.gz, .tar.xz, .tar or a NetHunter installer .zip");
                }
            }
            finalizeInstall(c, staging, p);
        } catch (IOException | RuntimeException e) {
            deleteTree(staging);
            throw e;
        } finally {
            tempZip.delete();
            BUSY.set(false);
        }
    }

    // ------------------------------------------------------------------ export

    public static void exportTo(Context c, Uri uri, Progress p) throws IOException {
        begin();
        try {
            File root = dir(c);
            if (!isInstalled(c)) throw new IOException("Kali is not installed");
            p.status("Exporting rootfs…");

            long[] counters = new long[3];   // files, bytes, skipped
            try (OutputStream raw = c.getContentResolver().openOutputStream(uri, "wt")) {
                if (raw == null) throw new IOException("Could not open the destination file");
                try (GzipCompressorOutputStream gz = new GzipCompressorOutputStream(new BufferedOutputStream(raw, 1 << 16));
                     TarArchiveOutputStream tar = new TarArchiveOutputStream(gz)) {
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                    tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
                    File[] top = root.listFiles();
                    if (top != null) {
                        Arrays.sort(top);
                        for (File f : top) {
                            if (f.getName().equals(INSTALLED_MARKER)) continue;   // recreated on import
                            addToTar(tar, f, f.getName(), counters, p);
                        }
                    }
                    tar.finish();
                }
            }
            p.log("[+] Exported " + counters[0] + " entries (" + formatBytes(counters[1]) + " uncompressed)"
                    + (counters[2] > 0 ? ", skipped " + counters[2] + " unreadable/special files" : "") + "\n");
        } finally {
            BUSY.set(false);
        }
    }

    private static void addToTar(TarArchiveOutputStream tar, File f, String name, long[] cnt, Progress p) throws IOException {
        StructStat st;
        try { st = Os.lstat(f.getAbsolutePath()); }
        catch (ErrnoException e) { cnt[2]++; return; }

        int perm = st.st_mode & 07777;
        long mtimeMs = st.st_mtime * 1000L;

        if (OsConstants.S_ISLNK(st.st_mode)) {
            String target;
            try { target = Os.readlink(f.getAbsolutePath()); }
            catch (ErrnoException e) { cnt[2]++; return; }
            TarArchiveEntry e = new TarArchiveEntry(name, TarConstants.LF_SYMLINK);
            e.setLinkName(target);
            e.setModTime(mtimeMs);
            tar.putArchiveEntry(e);
            tar.closeArchiveEntry();
            cnt[0]++;
        } else if (OsConstants.S_ISDIR(st.st_mode)) {
            File[] kids = f.listFiles();
            if (kids == null) {                     // mode 000 directories created by guest chmod
                try { Os.chmod(f.getAbsolutePath(), perm | 0700); } catch (ErrnoException ignored) { }
                kids = f.listFiles();
            }
            TarArchiveEntry e = new TarArchiveEntry(name + "/");
            e.setMode(perm);
            e.setModTime(mtimeMs);
            tar.putArchiveEntry(e);
            tar.closeArchiveEntry();
            cnt[0]++;
            if (kids != null) {
                Arrays.sort(kids);
                for (File k : kids) addToTar(tar, k, name + "/" + k.getName(), cnt, p);
            } else {
                cnt[2]++;
            }
        } else if (OsConstants.S_ISREG(st.st_mode)) {
            if (!f.canRead()) {
                try { Os.chmod(f.getAbsolutePath(), perm | 0600); } catch (ErrnoException ignored) { }
            }
            InputStream in;
            try { in = new FileInputStream(f); }
            catch (IOException e) { cnt[2]++; return; }
            try (InputStream src = in) {
                TarArchiveEntry e = new TarArchiveEntry(name);
                e.setMode(perm);
                e.setSize(st.st_size);
                e.setModTime(mtimeMs);
                tar.putArchiveEntry(e);
                byte[] buf = new byte[128 * 1024];
                long left = st.st_size;
                while (left > 0) {
                    int r = src.read(buf, 0, (int) Math.min(buf.length, left));
                    if (r < 0) throw new IOException("File shrank while exporting: " + name);
                    tar.write(buf, 0, r);
                    left -= r;
                }
                tar.closeArchiveEntry();
            }
            cnt[0]++;
            cnt[1] += st.st_size;
        } else {
            cnt[2]++;       // fifos, sockets, devices
            return;
        }
        if (cnt[0] % 2000 == 0) p.status("Exporting… " + cnt[0] + " entries, " + formatBytes(cnt[1]));
    }

    // ------------------------------------------------------------------ delete

    public static void deleteRootfs(Context c, Progress p) throws IOException {
        begin();
        try {
            File root = dir(c);
            File trash = new File(c.getFilesDir(), ROOTFS_NAME + ".deleting");
            deleteTree(trash);
            p.status("Deleting rootfs…");
            if (root.exists() && !root.renameTo(trash)) {
                // rename failed: delete in place
                deleteTree(root);
            }
            deleteTree(trash);
            deleteTree(new File(c.getFilesDir(), "fakeproc"));
            if (root.exists()) throw new IOException("Some files could not be deleted");
        } finally {
            BUSY.set(false);
        }
    }

    // ------------------------------------------------------------------ shared pieces

    private static void cleanLeftovers(Context c) {
        deleteTree(new File(c.getFilesDir(), ROOTFS_NAME + ".installing"));
        deleteTree(new File(c.getFilesDir(), ROOTFS_NAME + ".normalized"));
        deleteTree(new File(c.getFilesDir(), ROOTFS_NAME + ".old"));
        deleteTree(new File(c.getFilesDir(), ROOTFS_NAME + ".deleting"));
    }

    /** Verifies the extracted tree, then atomically swaps it in as the active rootfs. */
    private static void finalizeInstall(Context c, File staging, Progress p) throws IOException {
        File rootfsDir = dir(c);
        File actualRoot = findRootfsDirectory(staging);
        if (actualRoot == null) {
            throw new IOException("Kali rootfs directory was not found after extraction");
        }
        if (!actualRoot.equals(staging)) {
            File normalized = new File(c.getFilesDir(), ROOTFS_NAME + ".normalized");
            deleteTree(normalized);
            if (!actualRoot.renameTo(normalized)) copyTree(actualRoot, normalized);
            deleteTree(staging);
            staging = normalized;
        }
        if (findBash(staging) == null) {
            throw new IOException("Rootfs extracted, but bash was not found (checked /bin/bash and /usr/bin/bash)");
        }

        // The marker is created last: it means the complete rootfs passed verification.
        File marker = new File(staging, INSTALLED_MARKER);
        if (!marker.createNewFile() && !marker.isFile()) {
            throw new IOException("Could not create installation marker");
        }

        File oldRoot = new File(c.getFilesDir(), ROOTFS_NAME + ".old");
        deleteTree(oldRoot);
        if (rootfsDir.exists() && !rootfsDir.renameTo(oldRoot)) {
            throw new IOException("Could not move the previous rootfs out of the way");
        }
        if (!staging.renameTo(rootfsDir)) {
            copyTree(staging, rootfsDir);
            deleteTree(staging);
        }
        deleteTree(oldRoot);

        if (!isInstalled(c)) throw new IOException("Installation finished but verification failed");
        p.log("[+] Kali rootfs installed and verified in app-private storage.\n");
    }

    private static void download(File zip, Progress p) throws IOException {
        p.status("Downloading Kali rootfs… 0%");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(ROOTFS_URL).openConnection();
            c.setConnectTimeout(30000);
            c.setReadTimeout(120000);
            c.setRequestProperty("User-Agent", "NullShell/0.16");
            c.connect();
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);

            long total = c.getContentLengthLong();
            long done = 0;
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(zip))) {
                byte[] buf = new byte[128 * 1024];
                int n, lastPercent = -1;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    done += n;
                    int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : -1;
                    if (percent != lastPercent && percent >= 0) {
                        lastPercent = percent;
                        p.status("Downloading Kali rootfs… " + percent + "%");
                    }
                }
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Kali's generic NetHunter installer ZIP contains a rootfs .tar.xz. */
    private static void extractFromInstallerZip(File installerZip, File dest, Progress p) throws IOException {
        try (ZipFile zip = new ZipFile(installerZip)) {
            ZipEntry rootfsArchive = null;
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
                ZipEntry entry = e.nextElement();
                if (!entry.isDirectory() && entry.getName().toLowerCase().endsWith(".tar.xz")) {
                    rootfsArchive = entry;
                    break;
                }
            }
            if (rootfsArchive == null) throw new IOException("Installer ZIP does not contain a .tar.xz rootfs");

            p.log("[+] Found rootfs archive: " + rootfsArchive.getName() + "\n");
            p.status("Extracting Kali rootfs…");
            try (InputStream raw = new BufferedInputStream(zip.getInputStream(rootfsArchive));
                 XZCompressorInputStream xz = new XZCompressorInputStream(raw);
                 TarArchiveInputStream tar = new TarArchiveInputStream(new BufferedInputStream(xz))) {
                extractTar(tar, dest, p);
            }
        }
    }

    private static void extractTar(TarArchiveInputStream tar, File dest, Progress p) throws IOException {
        TarArchiveEntry entry;
        byte[] buffer = new byte[128 * 1024];
        int count = 0;
        while ((entry = tar.getNextTarEntry()) != null) {
            String name = entry.getName();
            if (name == null || name.isEmpty()) continue;

            String normalized = normalizeTarPath(name);
            if (normalized.isEmpty()) continue;
            File out = safeChild(dest, normalized);

            if (entry.isDirectory()) {
                out.mkdirs();
                // guest-side chmod 000 directories must stay traversable for the app itself
                continue;
            }

            File parent = out.getParentFile();
            if (parent != null) parent.mkdirs();

            if (entry.isSymbolicLink()) {
                deleteTree(out);
                createSymlink(dest, out, entry.getLinkName());
            } else if (entry.isLink()) {
                // Hardlinks are rare in a rootfs; preserve when possible, else copy.
                File linkTarget = safeChild(dest, normalizeTarPath(entry.getLinkName()));
                if (linkTarget.exists()) {
                    deleteTree(out);
                    try { Files.createLink(out.toPath(), linkTarget.toPath()); }
                    catch (Exception ignored) { copyFile(linkTarget, out); }
                } else {
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        copy(tar, os, buffer);
                    }
                }
            } else if (entry.isFile()) {
                deleteTree(out);
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                    copy(tar, os, buffer);
                }
                // Keep the executable bit for programs that PRoot runs from the guest.
                if ((entry.getMode() & 0100) != 0) out.setExecutable(true, false);
            }
            // other types (devices, fifos) are skipped

            if (++count % 5000 == 0) p.status("Extracting rootfs… " + count + " entries");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String normalizeTarPath(String name) throws IOException {
        name = name.replace('\\', '/');
        while (name.startsWith("./")) name = name.substring(2);
        while (name.startsWith("/")) name = name.substring(1);
        if (name.isEmpty()) return "";
        StringBuilder result = new StringBuilder();
        for (String part : name.split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) throw new IOException("Unsafe tar entry: " + name);
            if (result.length() > 0) result.append('/');
            result.append(part);
        }
        return result.toString();
    }

    private static File safeChild(File base, String relative) throws IOException {
        File f = new File(base, relative);
        String bp = base.getCanonicalPath();
        // Canonicalising a path through an existing symlink would escape; check the parent instead.
        File parent = f.getParentFile() == null ? f : f.getParentFile();
        String pp = parent.getCanonicalPath();
        if (!pp.equals(bp) && !pp.startsWith(bp + File.separator)) {
            throw new IOException("Unsafe archive entry: " + relative);
        }
        return f;
    }

    private static void createSymlink(File root, File link, String target) throws IOException {
        if (target == null || target.isEmpty()) throw new IOException("Empty symlink target: " + link);
        String linkTarget = target;
        if (target.startsWith("/")) {
            // A Linux absolute link is absolute inside the guest root, not on Android.
            Path guestTarget = root.toPath().resolve(target.substring(1)).normalize();
            linkTarget = link.getParentFile().toPath().relativize(guestTarget).toString();
        }
        Files.createSymbolicLink(link.toPath(), Paths.get(linkTarget));
    }

    private static File findRootfsDirectory(File staging) {
        if (findBash(staging) != null) return staging;
        File[] children = staging.listFiles(File::isDirectory);
        if (children != null) for (File child : children) if (findBash(child) != null) return child;
        return null;
    }

    private static File findBash(File root) {
        File a = new File(root, "bin/bash");
        if (a.exists() || Files.isSymbolicLink(a.toPath())) return a;
        File b = new File(root, "usr/bin/bash");
        if (b.exists() || Files.isSymbolicLink(b.toPath())) return b;
        return null;
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0, n;
        while (total < buf.length && (n = in.read(buf, total, buf.length - total)) > 0) total += n;
        return total;
    }

    private static void copy(InputStream in, OutputStream out, byte[] buffer) throws IOException {
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(src));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
            copy(in, out, new byte[128 * 1024]);
        }
    }

    private static void copyTree(File src, File dst) throws IOException {
        if (Files.isSymbolicLink(src.toPath())) {
            File parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            Files.createSymbolicLink(dst.toPath(), Files.readSymbolicLink(src.toPath()));
            return;
        }
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] files = src.listFiles();
            if (files != null) for (File f : files) copyTree(f, new File(dst, f.getName()));
        } else {
            copyFile(src, dst);
            if (src.canExecute()) dst.setExecutable(true, false);
        }
    }

    /** Recursive delete that never follows symlinks and repairs guest-chmodded directories. */
    static void deleteTree(File f) {
        if (f == null) return;
        Path path = f.toPath();
        if (Files.isSymbolicLink(path)) { f.delete(); return; }
        if (!f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids == null) {
                try { Os.chmod(f.getAbsolutePath(), 0700); } catch (ErrnoException ignored) { }
                kids = f.listFiles();
            }
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        if (!f.delete()) {
            File parent = f.getParentFile();
            if (parent != null) try { Os.chmod(parent.getAbsolutePath(), 0700); } catch (ErrnoException ignored) { }
            f.delete();
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0));
        return String.format(java.util.Locale.US, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    public static String message(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }
}
