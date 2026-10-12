package com.zed.nullshell;

import android.content.Context;
import android.net.http.SslCertificate;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Certificate authorities the user imported for intercepting proxies. They live only in this app's
 * private storage: nothing is added to the Android system or user trust store, and they are used
 * only by the NullShell browser. Private keys are never read or stored.
 */
final class BrowserCerts {
    private BrowserCerts() { }

    static final class Entry {
        final File file;
        final X509Certificate cert;
        final String name, sha256;
        Entry(File file, X509Certificate cert) {
            this.file = file;
            this.cert = cert;
            this.name = commonName(cert);
            this.sha256 = fingerprint(cert);
        }
    }

    private static final Pattern PEM = Pattern.compile("-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----", Pattern.DOTALL);
    private static final Pattern CN = Pattern.compile("(?:^|,)CN=((?:\\\\.|[^,\\\\])+)");
    private static final int MAX_BYTES = 1 << 20;

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "browser_ca");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    // ------------------------------------------------------------------ store

    static List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files == null) return out;
        Arrays.sort(files);
        CertificateFactory cf;
        try { cf = CertificateFactory.getInstance("X.509"); } catch (CertificateException e) { return out; }
        for (File f : files) {
            if (!f.getName().endsWith(".der")) continue;
            try {
                byte[] der = Files.readAllBytes(f.toPath());
                out.add(new Entry(f, (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der))));
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** Parses PEM (one or several certificates, other PEM blocks such as private keys are ignored) or DER. */
    static List<X509Certificate> parse(byte[] data) throws CertificateException {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> out = new ArrayList<>();
        String text = new String(data, StandardCharsets.ISO_8859_1);
        if (text.contains("-----BEGIN CERTIFICATE-----")) {
            Matcher m = PEM.matcher(text);
            while (m.find()) {
                byte[] der = Base64.decode(m.group(1).replaceAll("\\s", ""), Base64.DEFAULT);
                out.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der)));
            }
        } else {
            out.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(data)));
        }
        if (out.isEmpty()) throw new CertificateException("No certificate found in this file");
        return out;
    }

    /** Imports every certificate in the data. Returns the entries now in the store (new or already present). */
    static List<Entry> importData(Context c, byte[] data) throws CertificateException, IOException {
        List<Entry> result = new ArrayList<>();
        for (X509Certificate cert : parse(data)) {
            File f = new File(dir(c), fingerprint(cert).replace(":", "").substring(0, 32).toLowerCase(Locale.US) + ".der");
            if (!f.exists()) {
                try (FileOutputStream out = new FileOutputStream(f)) { out.write(cert.getEncoded()); }
            }
            result.add(new Entry(f, cert));
        }
        return result;
    }

    static List<Entry> importStream(Context c, InputStream in) throws CertificateException, IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_BYTES) throw new IOException("File is too large for a certificate");
        }
        return importData(c, buf.toByteArray());
    }

    static boolean remove(Entry e) { return e.file.delete(); }

    static void removeAll(Context c) {
        File[] files = dir(c).listFiles();
        if (files != null) for (File f : files) f.delete();
    }

    // ------------------------------------------------------------------ names

    static String commonName(X509Certificate cert) {
        String dn = cert.getSubjectX500Principal().getName();
        Matcher m = CN.matcher(dn);
        if (m.find()) return m.group(1).replace("\\", "");
        return dn;
    }

    static String fingerprint(X509Certificate cert) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format(Locale.US, "%02X", d[i] & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    static boolean isCa(X509Certificate cert) {
        return cert.getBasicConstraints() >= 0 || cert.getVersion() < 3;
    }

    // ------------------------------------------------------------------ trust decisions

    /** The server certificate behind a WebView SSL error, or null. */
    static X509Certificate fromSsl(SslCertificate sc) {
        if (sc == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                X509Certificate x = sc.getX509Certificate();
                if (x != null) return x;
            }
            Bundle b = SslCertificate.saveState(sc);
            byte[] der = b == null ? null : b.getByteArray("x509-certificate");
            if (der == null) return null;
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * True when the leaf is currently valid and was issued by (or is) one of the imported certificates.
     * {@code why} receives the reason when it is not.
     */
    static boolean chainTrusted(Context c, X509Certificate leaf, StringBuilder why) {
        List<Entry> store = list(c);
        if (store.isEmpty()) { why.append("No CA certificate is imported."); return false; }
        try {
            leaf.checkValidity();
        } catch (CertificateException e) {
            why.append("The server certificate is expired or not yet valid.");
            return false;
        }
        Date now = new Date();
        for (Entry e : store) {
            if (leaf.equals(e.cert)) return true;                          // imported server certificate (pinned)
            if (!leaf.getIssuerX500Principal().equals(e.cert.getSubjectX500Principal())) continue;
            if (!isCa(e.cert)) continue;
            try {
                e.cert.checkValidity(now);
                leaf.verify(e.cert.getPublicKey());
                return true;
            } catch (Exception ignored) { }
        }
        why.append("Issued by \"").append(issuerName(leaf))
                .append("\", which is not one of the imported CA certificates.");
        return false;
    }

    static String issuerName(X509Certificate cert) {
        String dn = cert.getIssuerX500Principal().getName();
        Matcher m = CN.matcher(dn);
        return m.find() ? m.group(1).replace("\\", "") : dn;
    }

    /** Chain check plus hostname check, for the WebView. */
    static boolean trusts(Context c, X509Certificate leaf, String host, StringBuilder why) {
        if (!chainTrusted(c, leaf, why)) return false;
        if (!hostMatches(leaf, host)) {
            why.append("The certificate is not valid for ").append(host).append('.');
            return false;
        }
        return true;
    }

    static boolean hostMatches(X509Certificate cert, String rawHost) {
        if (rawHost == null || rawHost.isEmpty()) return false;
        String host = rawHost.toLowerCase(Locale.US);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        boolean ip = host.indexOf(':') >= 0 || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean sawDns = false;
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    if (san.size() < 2 || !(san.get(0) instanceof Integer) || !(san.get(1) instanceof String)) continue;
                    int type = (Integer) san.get(0);
                    String val = (String) san.get(1);
                    if (ip && type == 7 && sameIp(val, host)) return true;
                    if (!ip && type == 2) {
                        sawDns = true;
                        if (dnsMatch(val, host)) return true;
                    }
                }
            }
        } catch (Exception ignored) { }
        if (ip || sawDns) return false;
        return dnsMatch(commonName(cert), host);                          // legacy CN fallback only without any DNS SAN
    }

    private static boolean sameIp(String a, String b) {
        try { return Arrays.equals(InetAddress.getByName(a).getAddress(), InetAddress.getByName(b).getAddress()); }
        catch (Exception e) { return false; }
    }

    private static boolean dnsMatch(String pattern, String host) {
        String p = pattern.toLowerCase(Locale.US);
        if (p.endsWith(".")) p = p.substring(0, p.length() - 1);
        if (!p.startsWith("*.")) return p.equals(host);
        String suffix = p.substring(1);                                    // ".example.com"
        if (!host.endsWith(suffix)) return false;
        String label = host.substring(0, host.length() - suffix.length());
        return !label.isEmpty() && label.indexOf('.') < 0;
    }

    // ------------------------------------------------------------------ java.net (downloads)

    /** TLS for downloads: the normal system trust, plus the imported CAs. Hostname checks stay on. */
    static SSLContext sslContext(final Context c) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        X509TrustManager found = null;
        for (TrustManager tm : tmf.getTrustManagers()) if (tm instanceof X509TrustManager) { found = (X509TrustManager) tm; break; }
        if (found == null) throw new IllegalStateException("No system trust manager");
        final X509TrustManager system = found;
        X509TrustManager combined = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                system.checkClientTrusted(chain, authType);
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    system.checkServerTrusted(chain, authType);
                } catch (CertificateException e) {
                    if (chain == null || chain.length == 0 || !chainTrusted(c, chain[0], new StringBuilder())) throw e;
                }
            }
            @Override public X509Certificate[] getAcceptedIssuers() { return system.getAcceptedIssuers(); }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{combined}, null);
        return ctx;
    }

}
