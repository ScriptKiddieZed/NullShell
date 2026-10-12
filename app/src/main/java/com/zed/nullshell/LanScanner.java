package com.zed.nullshell;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Finds the devices on a local network without root: a ping sweep plus a few TCP connects per address
 * (a refused connection also proves the device is there), then the ARP table where Android lets us read it.
 * Pure Java (no Android imports) so it can be tested on a desktop JVM.
 */
final class LanScanner {
    static final int[] PORTS = {80, 443, 22, 53, 445, 139, 8080, 62078, 5000, 7};

    static final class Host implements Comparable<Host> {
        final String ip;
        volatile String name = "";
        volatile String mac = "";
        volatile int ms = -1;
        volatile boolean fromArp;
        Host(String ip) { this.ip = ip; }
        @Override public int compareTo(Host o) { return Long.compare(toLong(ip), toLong(o.ip)); }
    }

    interface Listener {
        /** Worker thread: a device answered (name and MAC may still be empty and get updated by onUpdate). */
        void onHost(Host h);
        void onUpdate(Host h);
        void onProgress(int done, int total);
        void onDone(boolean cancelled);
    }

    private volatile boolean cancelled;
    private ExecutorService pool, namePool;

    void cancel() { cancelled = true; }

    // ------------------------------------------------------------------ addresses

    static long toLong(String ip) {
        String[] p = ip.split("\\.");
        if (p.length != 4) return 0;
        long v = 0;
        for (String s : p) v = (v << 8) | (Integer.parseInt(s) & 0xFF);
        return v;
    }

    static String fromLong(long v) {
        return ((v >> 24) & 0xFF) + "." + ((v >> 16) & 0xFF) + "." + ((v >> 8) & 0xFF) + "." + (v & 0xFF);
    }

    /** "192.168.1.0/24" for an address and prefix length. */
    static String cidr(String ip, int prefix) {
        long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        return fromLong(toLong(ip) & mask) + "/" + prefix;
    }

    /**
     * Addresses worth probing: the whole subnet, but never more than the /24 that holds our own address
     * (a bigger sweep would take minutes and trip alarms). Network and broadcast addresses are skipped.
     */
    static List<String> subnetHosts(String ip, int prefix) {
        int p = Math.max(24, Math.min(30, prefix));
        long mask = (0xFFFFFFFFL << (32 - p)) & 0xFFFFFFFFL;
        long net = toLong(ip) & mask;
        long bcast = net | (~mask & 0xFFFFFFFFL);
        List<String> out = new ArrayList<>();
        for (long a = net + 1; a < bcast; a++) out.add(fromLong(a));
        return out;
    }

    static boolean inSubnet(String ip, String ownIp, int prefix) {
        int p = Math.max(24, Math.min(30, prefix));
        long mask = (0xFFFFFFFFL << (32 - p)) & 0xFFFFFFFFL;
        return (toLong(ip) & mask) == (toLong(ownIp) & mask);
    }

    // ------------------------------------------------------------------ ARP table

    /** Parses /proc/net/arp. Only entries the kernel marks as resolved are returned (ip to MAC). */
    static Map<String, String> parseArp(List<String> lines, String device) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).trim().split("\\s+");
            if (f.length < 6) continue;
            String ip = f[0], flags = f[2], mac = f[3], dev = f[5];
            if (device != null && !device.equals(dev)) continue;
            if (flags.equals("0x0") || mac.equals("00:00:00:00:00:00")) continue;
            out.put(ip, mac.toUpperCase(java.util.Locale.US));
        }
        return out;
    }

    /** Empty when Android hides the table (it does for most apps on Android 10+). */
    static Map<String, String> readArp(String device) {
        try {
            return parseArp(Files.readAllLines(Paths.get("/proc/net/arp"), StandardCharsets.UTF_8), device);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    // ------------------------------------------------------------------ probing

    /**
     * Milliseconds until the address showed signs of life, or -1. {@code local} (our own address on that network)
     * pins the traffic to the right interface, which matters for hotspot subnets.
     */
    static int probe(String ip, InetAddress local, NetworkInterface nif, int[] ports, int pingMs, int connectMs) {
        long t0 = System.nanoTime();
        try {
            InetAddress target = InetAddress.getByName(ip);
            boolean up = nif != null ? target.isReachable(nif, 0, pingMs) : target.isReachable(pingMs);
            if (up) return ms(t0);
        } catch (IOException ignored) { }
        for (int port : ports) {
            try (Socket s = new Socket()) {
                if (local != null) s.bind(new InetSocketAddress(local, 0));
                s.connect(new InetSocketAddress(ip, port), connectMs);
                return ms(t0);
            } catch (SocketTimeoutException ignored) {
                // silent: filtered, or nobody home
            } catch (ConnectException e) {
                String m = e.getMessage();
                if (m != null && m.toLowerCase(java.util.Locale.US).contains("refused")) return ms(t0);   // a reset means the device is there
            } catch (IOException ignored) { }
        }
        return -1;
    }

    private static int ms(long t0) { return (int) Math.max(1, (System.nanoTime() - t0) / 1_000_000); }

    // ------------------------------------------------------------------ scan

    /**
     * Scans on background threads and reports through the listener. {@code ownIp} is shown by the caller, not scanned.
     * Returns immediately.
     */
    void scan(final List<String> ips, final String ownIp, final String device, final InetAddress local,
              final NetworkInterface nif, final Listener l) {
        cancelled = false;
        final int total = ips.size();
        final AtomicInteger done = new AtomicInteger();
        final Map<String, Host> found = Collections.synchronizedMap(new LinkedHashMap<String, Host>());
        pool = Executors.newFixedThreadPool(48, r -> { Thread t = new Thread(r, "lan-scan"); t.setDaemon(true); return t; });
        namePool = Executors.newFixedThreadPool(12, r -> { Thread t = new Thread(r, "lan-name"); t.setDaemon(true); return t; });
        final List<Future<?>> futures = new ArrayList<>();
        for (final String ip : ips) {
            if (ip.equals(ownIp)) { done.incrementAndGet(); continue; }
            futures.add(pool.submit(() -> {
                try {
                    if (cancelled) return;
                    int ms = probe(ip, local, nif, PORTS, 250, 180);
                    if (ms >= 0 && !cancelled) {
                        Host h = new Host(ip);
                        h.ms = ms;
                        found.put(ip, h);
                        l.onHost(h);
                        final Host fh = h;
                        namePool.execute(() -> {
                            String n = resolveName(ip, 900);
                            if (!n.isEmpty()) { fh.name = n; l.onUpdate(fh); }
                        });
                    }
                } finally {
                    l.onProgress(done.incrementAndGet(), total);
                }
            }));
        }
        Thread finisher = new Thread(() -> {
            for (Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) { }
            }
            if (!cancelled) addFromArp(ips, ownIp, device, found, l);
            try { namePool.shutdown(); namePool.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            pool.shutdownNow();
            namePool.shutdownNow();
            l.onDone(cancelled);
        }, "lan-finish");
        finisher.setDaemon(true);
        finisher.start();
        if (ips.isEmpty() || done.get() == total) l.onProgress(total, total);
    }

    /** Devices that ignore every probe still answered ARP while we tried; add them and fill in MAC addresses. */
    private void addFromArp(List<String> ips, String ownIp, String device, Map<String, Host> found, Listener l) {
        Map<String, String> arp = readArp(device);
        if (arp.isEmpty()) return;
        java.util.HashSet<String> wanted = new java.util.HashSet<>(ips);
        for (Map.Entry<String, String> e : arp.entrySet()) {
            String ip = e.getKey();
            if (!wanted.contains(ip) || ip.equals(ownIp)) continue;
            Host h = found.get(ip);
            if (h == null) {
                h = new Host(ip);
                h.fromArp = true;
                h.mac = e.getValue();
                found.put(ip, h);
                l.onHost(h);
                final Host nh = h;
                String n = resolveName(ip, 600);
                if (!n.isEmpty()) { nh.name = n; l.onUpdate(nh); }
            } else {
                h.mac = e.getValue();
                l.onUpdate(h);
            }
        }
    }

    private String resolveName(final String ip, int timeoutMs) {
        ExecutorService one = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "lan-rdns"); t.setDaemon(true); return t; });
        Future<String> f = one.submit(() -> InetAddress.getByName(ip).getCanonicalHostName());
        try {
            String n = f.get(timeoutMs, TimeUnit.MILLISECONDS);
            return n == null || n.equals(ip) ? "" : n;
        } catch (Exception e) {
            return "";
        } finally {
            f.cancel(true);
            one.shutdownNow();
        }
    }
}
