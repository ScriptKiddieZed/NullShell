package com.zed.nullshell;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.utils.CountingInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * File operations behind the file manager. Everything works on host paths (java.nio) and never follows
 * symlinks unless told to, because Kali's absolute links point inside the guest, not at the phone.
 * Long operations take a {@link Job} that carries progress out and cancellation in.
 */
final class FileOps {
    private FileOps() { }

    static final int REPLACE = 0, SKIP = 1, KEEP_BOTH = 2;

    /** Progress and cancellation for one long operation. Fields are read from the UI thread. */
    static final class Job {
        volatile boolean cancelled;
        volatile String current = "";
        volatile long done, total;               // bytes; total 0 = unknown
        volatile int items, itemsTotal;          // top-level items finished / to do
        volatile int errors;
        volatile String firstError;

        void fail(String where, Exception e) {
            errors++;
            if (firstError == null) firstError = where + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }

        void check() throws Cancelled { if (cancelled) throw new Cancelled(); }
    }

    static final class Cancelled extends IOException {
        Cancelled() { super("Cancelled"); }
    }

    /** One entry of a folder listing (or of a search). */
    static final class Item {
        Path path;               // the entry itself; symlinks are not followed
        String loc;              // where to navigate: guest path or phone path
        String name;
        String rel;              // search results only: the folder it was found in
        boolean dir;             // a directory, or a link to one
        boolean link, broken, other, canExec;
        String target;           // symlink target as written
        long size, modified;
    }

    // ------------------------------------------------------------------ listing

    static String join(String dirLoc, String name, boolean guest) {
        return guest ? GuestPaths.join(dirLoc, name) : new File(dirLoc, name).getPath();
    }

    static List<Item> list(Path dir, String dirLoc, boolean guest, File rootfs, boolean showHidden, int cap) throws IOException {
        List<Item> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (!showHidden && name.startsWith(".")) continue;
                out.add(describe(p, join(dirLoc, name, guest), guest, rootfs));
                if (out.size() >= cap) break;
            }
        }
        return out;
    }

    static Item describe(Path p, String loc, boolean guest, File rootfs) {
        Item it = new Item();
        it.path = p;
        it.loc = loc;
        it.name = p.getFileName().toString();
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            it.modified = a.lastModifiedTime().toMillis();
            if (a.isSymbolicLink()) {
                it.link = true;
                try { it.target = Files.readSymbolicLink(p).toString(); } catch (IOException ignored) { }
                File t = guest ? GuestPaths.toHost(rootfs, loc, true) : p.toFile();
                BasicFileAttributes ta = null;
                if (t != null) {
                    try { ta = Files.readAttributes(t.toPath(), BasicFileAttributes.class); } catch (IOException ignored) { }
                }
                if (ta == null) it.broken = true;
                else { it.dir = ta.isDirectory(); it.size = ta.isDirectory() ? 0 : ta.size(); }
            } else {
                it.dir = a.isDirectory();
                it.other = a.isOther();
                it.size = a.isDirectory() ? 0 : a.size();
            }
            it.canExec = !it.dir && !it.broken && Files.isExecutable(p);
        } catch (IOException e) {
            it.broken = true;
        }
        return it;
    }

    // ------------------------------------------------------------------ names and modes

    static String uniqueName(Path dir, String name) {
        if (!Files.exists(dir.resolve(name), LinkOption.NOFOLLOW_LINKS)) return name;
        String lower = name.toLowerCase(Locale.US);
        int dot = name.lastIndexOf('.');
        for (String dbl : new String[]{".tar.gz", ".tar.xz", ".tar.bz2"}) if (lower.endsWith(dbl)) dot = name.length() - dbl.length();
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            String n = base + " (" + i + ")" + ext;
            if (!Files.exists(dir.resolve(n), LinkOption.NOFOLLOW_LINKS)) return n;
        }
        return base + "-" + System.currentTimeMillis() + ext;
    }

    static int modeOf(Path p, boolean isDir) {
        try {
            int m = 0;
            for (PosixFilePermission f : Files.getPosixFilePermissions(p)) m |= bit(f);
            return m;
        } catch (Exception e) {
            return isDir ? 0755 : 0644;
        }
    }

    private static int bit(PosixFilePermission f) {
        switch (f) {
            case OWNER_READ: return 0400;
            case OWNER_WRITE: return 0200;
            case OWNER_EXECUTE: return 0100;
            case GROUP_READ: return 040;
            case GROUP_WRITE: return 020;
            case GROUP_EXECUTE: return 010;
            case OTHERS_READ: return 04;
            case OTHERS_WRITE: return 02;
            default: return 01;
        }
    }

    static Set<PosixFilePermission> permissionsOf(int mode) {
        Set<PosixFilePermission> s = new HashSet<>();
        for (PosixFilePermission f : PosixFilePermission.values()) if ((mode & bit(f)) != 0) s.add(f);
        return s;
    }

    static void chmod(Path p, int mode) throws IOException { Files.setPosixFilePermissions(p, permissionsOf(mode & 0777)); }

    static String modeString(int mode) {
        StringBuilder sb = new StringBuilder();
        String rwx = "rwxrwxrwx";
        for (int i = 0; i < 9; i++) sb.append((mode & (0400 >> i)) != 0 ? rwx.charAt(i) : '-');
        return sb.toString();
    }

    private static void copyAttributes(Path src, Path dst, boolean dir) {
        try { Files.setPosixFilePermissions(dst, Files.getPosixFilePermissions(src)); } catch (Exception ignored) { }
        if (!dir) { try { dst.toFile().setLastModified(Files.getLastModifiedTime(src).toMillis()); } catch (Exception ignored) { } }
    }

    private static void makeAccessible(Path dir) {
        File f = dir.toFile();
        f.setReadable(true, true);
        f.setWritable(true, true);
        f.setExecutable(true, true);
    }

    // ------------------------------------------------------------------ simple edits

    static void rename(Path p, String newName) throws IOException {
        if (newName.isEmpty() || newName.contains("/") || newName.equals(".") || newName.equals("..")) throw new IOException("Invalid name");
        Path dst = p.resolveSibling(newName);
        if (Files.exists(dst, LinkOption.NOFOLLOW_LINKS)) throw new IOException("\"" + newName + "\" already exists");
        Files.move(p, dst);
    }

    static void createFile(Path dir, String name) throws IOException {
        checkName(name);
        Files.createFile(dir.resolve(name));
    }

    static void createDir(Path dir, String name) throws IOException {
        checkName(name);
        Files.createDirectory(dir.resolve(name));
    }

    private static void checkName(String name) throws IOException {
        if (name.isEmpty() || name.contains("/") || name.equals(".") || name.equals("..")) throw new IOException("Invalid name");
    }

    // ------------------------------------------------------------------ sizes

    /** Total bytes of regular files under the given paths. */
    static long sizeOf(final Job job, List<Path> paths) throws IOException {
        final long[] total = {0};
        for (Path root : paths) {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    job.check();
                    if (a.isRegularFile()) total[0] += a.size();
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path f, IOException e) { return FileVisitResult.CONTINUE; }
            });
        }
        return total[0];
    }

    // ------------------------------------------------------------------ copy, move, delete

    private static void copyData(Job job, InputStream in, Path out) throws IOException {
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 65536)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                job.check();
                os.write(buf, 0, n);
                job.done += n;
            }
        }
    }

    static void copyTree(final Job job, final Path src, final Path dst) throws IOException {
        Files.walkFileTree(src, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                job.check();
                Files.createDirectories(dst.resolve(src.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                job.check();
                job.current = f.getFileName().toString();
                Path t = dst.resolve(src.relativize(f).toString());
                try {
                    if (a.isSymbolicLink()) {
                        Files.deleteIfExists(t);
                        Files.createSymbolicLink(t, Files.readSymbolicLink(f));
                    } else if (a.isRegularFile()) {
                        try (InputStream in = Files.newInputStream(f)) { copyData(job, in, t); }
                        copyAttributes(f, t, false);
                    }                                         // sockets, pipes and devices are skipped
                } catch (Cancelled c) {
                    throw c;
                } catch (IOException e) {
                    job.fail(f.getFileName().toString(), e);
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path f, IOException e) {
                job.fail(f.getFileName() == null ? f.toString() : f.getFileName().toString(), e);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                copyAttributes(dir, dst.resolve(src.relativize(dir).toString()), true);   // after the children, in case it is read-only
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static void deleteTree(final Job job, Path p) throws IOException {
        BasicFileAttributes a;
        try {
            a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return;
        }
        if (!a.isDirectory()) {
            job.check();
            job.current = p.getFileName().toString();
            Files.delete(p);                                 // a symlink is removed, never its target
            return;
        }
        Files.walkFileTree(p, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes at) throws IOException {
                job.check();
                makeAccessible(dir);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes at) throws IOException {
                job.check();
                job.current = f.getFileName().toString();
                try { Files.delete(f); } catch (IOException e) { job.fail(f.getFileName().toString(), e); }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path f, IOException e) {
                job.fail(f.getFileName() == null ? f.toString() : f.getFileName().toString(), e);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                try { Files.delete(dir); } catch (IOException ex) { job.fail(dir.getFileName().toString(), ex); }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Copies or moves the sources into destDir. A conflict is settled by {@code policy} for every item. */
    static void transfer(Job job, List<Path> sources, Path destDir, int policy, boolean move) throws IOException {
        Path destNorm = destDir.toAbsolutePath().normalize();
        for (Path src : sources) {
            job.check();
            String name = src.getFileName().toString();
            job.current = name;
            Path srcNorm = src.toAbsolutePath().normalize();
            Path dst = destDir.resolve(name);
            boolean exists = Files.exists(dst, LinkOption.NOFOLLOW_LINKS);
            boolean same = exists && srcNorm.equals(dst.toAbsolutePath().normalize());
            if (move && same) { job.items++; continue; }                               // moving into its own folder
            if (Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS) && destNorm.startsWith(srcNorm)) {
                job.fail(name, new IOException("A folder cannot be copied into itself"));
                job.items++;
                continue;
            }
            if (exists) {
                if (same || policy == KEEP_BOTH) dst = destDir.resolve(uniqueName(destDir, name));
                else if (policy == SKIP) { job.items++; continue; }
                else deleteTree(job, dst);
            }
            try {
                if (move) moveOne(job, src, dst); else copyTree(job, src, dst);
            } catch (Cancelled c) {
                throw c;
            } catch (IOException e) {
                job.fail(name, e);
            }
            job.items++;
        }
    }

    private static void moveOne(Job job, Path src, Path dst) throws IOException {
        try {
            Files.move(src, dst);                           // a rename when both are on the same storage
        } catch (IOException e) {
            int before = job.errors;
            copyTree(job, src, dst);                        // other storage (Kali <-> phone): copy, then remove
            if (job.errors == before) deleteTree(job, src);   // keep the original if anything failed to copy
        }
    }

    // ------------------------------------------------------------------ archives

    static boolean isArchive(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".apk") || n.endsWith(".tar") || n.endsWith(".tar.gz")
                || n.endsWith(".tgz") || n.endsWith(".tar.xz") || n.endsWith(".txz") || n.endsWith(".tar.bz2")
                || n.endsWith(".tbz2") || n.endsWith(".gz") || n.endsWith(".xz") || n.endsWith(".bz2");
    }

    /** Name for the folder an archive extracts into: the file name without its archive suffix. */
    static String archiveBase(String name) {
        String n = name;
        String l = name.toLowerCase(Locale.US);
        for (String ext : new String[]{".tar.gz", ".tar.xz", ".tar.bz2", ".tgz", ".txz", ".tbz2", ".zip", ".jar", ".apk", ".tar", ".gz", ".xz", ".bz2"}) {
            if (l.endsWith(ext)) { n = name.substring(0, name.length() - ext.length()); break; }
        }
        return n.isEmpty() ? "extracted" : n;
    }

    private interface Sink {
        void dir(String name, long mtime, int mode) throws IOException;
        void link(String name, String target, long mtime) throws IOException;
        void file(String name, Path p, long size, long mtime, int mode) throws IOException;
    }

    private static void walkInto(final Job job, final Path root, final String base, final Sink sink) throws IOException {
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
            private String nameOf(Path p) {
                String rel = root.relativize(p).toString().replace(File.separatorChar, '/');
                return rel.isEmpty() ? base : base + "/" + rel;
            }
            @Override public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) throws IOException {
                job.check();
                sink.dir(nameOf(d), a.lastModifiedTime().toMillis(), modeOf(d, true));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                job.check();
                job.current = f.getFileName().toString();
                try {
                    if (a.isSymbolicLink()) sink.link(nameOf(f), Files.readSymbolicLink(f).toString(), a.lastModifiedTime().toMillis());
                    else if (a.isRegularFile()) sink.file(nameOf(f), f, a.size(), a.lastModifiedTime().toMillis(), modeOf(f, false));
                } catch (Cancelled c) {
                    throw c;
                } catch (IOException e) {
                    job.fail(f.getFileName().toString(), e);
                }
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path f, IOException e) {
                job.fail(f.getFileName() == null ? f.toString() : f.getFileName().toString(), e);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Writes a .zip or .tar.gz holding the sources (each under its own name); links and permissions are kept. */
    static void createArchive(final Job job, List<Path> sources, Path out, boolean tarGz) throws IOException {
        job.total = sizeOf(job, sources);
        try {
            if (tarGz) {
                try (TarArchiveOutputStream tar = new TarArchiveOutputStream(
                        new GzipCompressorOutputStream(new BufferedOutputStream(Files.newOutputStream(out), 65536)))) {
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                    tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
                    final TarArchiveOutputStream t = tar;
                    Sink sink = new Sink() {
                        @Override public void dir(String name, long mtime, int mode) throws IOException {
                            TarArchiveEntry e = new TarArchiveEntry(name + "/");
                            e.setModTime(mtime);
                            e.setMode(mode);
                            t.putArchiveEntry(e);
                            t.closeArchiveEntry();
                        }
                        @Override public void link(String name, String target, long mtime) throws IOException {
                            TarArchiveEntry e = new TarArchiveEntry(name, TarConstants.LF_SYMLINK);
                            e.setLinkName(target);
                            e.setModTime(mtime);
                            t.putArchiveEntry(e);
                            t.closeArchiveEntry();
                        }
                        @Override public void file(String name, Path p, long size, long mtime, int mode) throws IOException {
                            TarArchiveEntry e = new TarArchiveEntry(name);
                            e.setSize(size);
                            e.setModTime(mtime);
                            e.setMode(mode);
                            t.putArchiveEntry(e);
                            try (InputStream in = Files.newInputStream(p)) { copyTo(job, in, t); }
                            t.closeArchiveEntry();
                        }
                    };
                    for (Path s : sources) walkInto(job, s, s.getFileName().toString(), sink);
                    tar.finish();
                }
            } else {
                try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(out.toFile())) {
                    final ZipArchiveOutputStream z = zip;
                    Sink sink = new Sink() {
                        @Override public void dir(String name, long mtime, int mode) throws IOException {
                            ZipArchiveEntry e = new ZipArchiveEntry(name + "/");
                            e.setTime(mtime);
                            e.setUnixMode(UnixStat.DIR_FLAG | mode);
                            z.putArchiveEntry(e);
                            z.closeArchiveEntry();
                        }
                        @Override public void link(String name, String target, long mtime) throws IOException {
                            byte[] data = target.getBytes(StandardCharsets.UTF_8);
                            ZipArchiveEntry e = new ZipArchiveEntry(name);
                            e.setTime(mtime);
                            e.setSize(data.length);
                            e.setUnixMode(UnixStat.LINK_FLAG | 0777);
                            z.putArchiveEntry(e);
                            z.write(data);
                            z.closeArchiveEntry();
                        }
                        @Override public void file(String name, Path p, long size, long mtime, int mode) throws IOException {
                            ZipArchiveEntry e = new ZipArchiveEntry(name);
                            e.setSize(size);
                            e.setTime(mtime);
                            e.setUnixMode(UnixStat.FILE_FLAG | mode);
                            z.putArchiveEntry(e);
                            try (InputStream in = Files.newInputStream(p)) { copyTo(job, in, z); }
                            z.closeArchiveEntry();
                        }
                    };
                    for (Path s : sources) walkInto(job, s, s.getFileName().toString(), sink);
                    zip.finish();
                }
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(out); } catch (IOException ignored) { }      // never leave a half-written archive
            throw e;
        }
    }

    private static void copyTo(Job job, InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            job.check();
            out.write(buf, 0, n);
            job.done += n;
        }
    }

    /** Resolves an entry name below dest, or null when it would leave dest or pass through a symlink. */
    private static Path safeTarget(Path dest, String entryName) {
        String n = entryName.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        if (n.isEmpty() || n.equals(".")) return null;
        Path out = dest.resolve(n).normalize();
        if (!out.startsWith(dest) || out.equals(dest)) return null;
        for (Path p = out.getParent(); p != null && !p.equals(dest); p = p.getParent()) {
            if (Files.isSymbolicLink(p)) return null;
        }
        return out;
    }

    private static void applyMode(Path p, int mode, boolean dir) {
        int m = (mode & 0777) | (dir ? 0700 : 0600);       // the owner can always read and write what was extracted
        try { Files.setPosixFilePermissions(p, permissionsOf(m)); } catch (Exception ignored) { }
    }

    /** Extracts into destDir, which must exist. Entries that would escape it are skipped and counted as errors. */
    static void extract(Job job, Path archive, Path destDir) throws IOException {
        Path dest = destDir.toAbsolutePath().normalize();
        String n = archive.getFileName().toString().toLowerCase(Locale.US);
        job.total = Files.size(archive);
        if (n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".apk")) { extractZip(job, archive, dest); return; }
        CountingInputStream counter = new CountingInputStream(Files.newInputStream(archive));
        try (InputStream raw = new BufferedInputStream(counter, 65536)) {
            InputStream data;
            boolean tar = n.endsWith(".tar") || n.contains(".tar.") || n.endsWith(".tgz") || n.endsWith(".txz") || n.endsWith(".tbz2");
            if (n.endsWith(".gz") || n.endsWith(".tgz")) data = new GzipCompressorInputStream(raw);
            else if (n.endsWith(".xz") || n.endsWith(".txz")) data = new XZCompressorInputStream(raw);
            else if (n.endsWith(".bz2") || n.endsWith(".tbz2")) data = new BZip2CompressorInputStream(raw);
            else data = raw;
            if (tar) {
                extractTar(job, new TarArchiveInputStream(data), dest, counter);
            } else {
                Path out = dest.resolve(archiveBase(archive.getFileName().toString()));    // a single compressed file
                job.current = out.getFileName().toString();
                copyData(job, data, out);
            }
        }
    }

    private static void extractTar(Job job, TarArchiveInputStream tin, Path dest, CountingInputStream counter) throws IOException {
        try {
            TarArchiveEntry e;
            while ((e = (TarArchiveEntry) tin.getNextEntry()) != null) {
                job.check();
                job.current = e.getName();
                Path out = safeTarget(dest, e.getName());
                if (out == null) { if (!e.getName().equals("./")) job.fail(e.getName(), new IOException("unsafe path skipped")); continue; }
                try {
                    if (e.isDirectory()) {
                        Files.createDirectories(out);
                        applyMode(out, e.getMode(), true);
                    } else if (e.isSymbolicLink()) {
                        Files.createDirectories(out.getParent());
                        Files.deleteIfExists(out);
                        Files.createSymbolicLink(out, Paths.get(e.getLinkName()));
                    } else if (e.isLink()) {
                        Path from = safeTarget(dest, e.getLinkName());
                        if (from == null) throw new IOException("unsafe link skipped");
                        Files.createDirectories(out.getParent());
                        Files.copy(from, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } else if (e.isFile()) {
                        Files.createDirectories(out.getParent());
                        copyCounted(job, tin, out, counter);
                        applyMode(out, e.getMode(), false);
                    }
                } catch (Cancelled c) {
                    throw c;
                } catch (IOException | RuntimeException ex) {
                    job.fail(e.getName(), ex instanceof IOException ? (IOException) ex : new IOException(ex.getMessage()));
                }
                job.done = counter.getBytesRead();
            }
        } finally {
            tin.close();
        }
    }

    private static void copyCounted(Job job, InputStream in, Path out, CountingInputStream counter) throws IOException {
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 65536)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                job.check();
                os.write(buf, 0, n);
                job.done = counter.getBytesRead();
            }
        }
    }

    private static void extractZip(Job job, Path archive, Path dest) throws IOException {
        try (ZipFile zf = new ZipFile(archive.toFile())) {
            long total = 0;
            Enumeration<ZipArchiveEntry> sizes = zf.getEntries();
            while (sizes.hasMoreElements()) total += Math.max(0, sizes.nextElement().getSize());
            job.total = total;
            Enumeration<ZipArchiveEntry> en = zf.getEntries();
            while (en.hasMoreElements()) {
                job.check();
                ZipArchiveEntry e = en.nextElement();
                job.current = e.getName();
                Path out = safeTarget(dest, e.getName());
                if (out == null) { job.fail(e.getName(), new IOException("unsafe path skipped")); continue; }
                try {
                    if (e.isDirectory()) {
                        Files.createDirectories(out);
                        applyMode(out, e.getUnixMode(), true);
                    } else if (e.isUnixSymlink()) {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        try (InputStream in = zf.getInputStream(e)) { copyTo(job, in, bos); }
                        Files.createDirectories(out.getParent());
                        Files.deleteIfExists(out);
                        Files.createSymbolicLink(out, Paths.get(new String(bos.toByteArray(), StandardCharsets.UTF_8)));
                    } else {
                        Files.createDirectories(out.getParent());
                        try (InputStream in = zf.getInputStream(e)) { copyData(job, in, out); }
                        applyMode(out, e.getUnixMode() == 0 ? 0644 : e.getUnixMode(), false);
                    }
                } catch (Cancelled c) {
                    throw c;
                } catch (IOException | RuntimeException ex) {
                    job.fail(e.getName(), ex instanceof IOException ? (IOException) ex : new IOException(ex.getMessage()));
                }
            }
        }
    }
}
