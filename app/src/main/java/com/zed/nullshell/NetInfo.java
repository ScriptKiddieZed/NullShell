package com.zed.nullshell;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What the phone's own network connections look like: addresses, gateway, DNS, and which one is the hotspot. */
final class NetInfo {
    private NetInfo() { }

    static final int WIFI = 0, HOTSPOT = 1, CELLULAR = 2, ETHERNET = 3, VPN = 4, OTHER = 5;

    static final class Iface {
        int kind = OTHER;
        String name = "";
        String ipv4 = "";
        int prefix = 24;
        final List<String> ipv6 = new ArrayList<>();
        String gateway = "";
        final List<String> dns = new ArrayList<>();

        String label() {
            switch (kind) {
                case WIFI: return "Wi‑Fi";
                case HOTSPOT: return name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("ncm") ? "USB tethering"
                        : name.startsWith("bt-pan") ? "Bluetooth tethering" : "Hotspot";
                case CELLULAR: return "Mobile data";
                case ETHERNET: return "Ethernet";
                case VPN: return "VPN";
                default: return "Other";
            }
        }
        boolean scannable() { return (kind == WIFI || kind == HOTSPOT) && !ipv4.isEmpty(); }
        String cidr() { return LanScanner.cidr(ipv4, Math.max(24, Math.min(30, prefix))); }
    }

    static List<Iface> collect(Context c) {
        List<Iface> out = new ArrayList<>();
        Set<String> covered = new HashSet<>();
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                LinkProperties lp = cm.getLinkProperties(n);
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (lp == null || lp.getInterfaceName() == null) continue;
                Iface f = new Iface();
                f.name = lp.getInterfaceName();
                if (caps != null) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) f.kind = WIFI;
                    else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) f.kind = CELLULAR;
                    else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) f.kind = ETHERNET;
                    else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) f.kind = VPN;
                }
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (a instanceof Inet4Address && f.ipv4.isEmpty()) { f.ipv4 = a.getHostAddress(); f.prefix = la.getPrefixLength(); }
                    else if (a instanceof Inet6Address && !a.isLinkLocalAddress() && !a.isLoopbackAddress()) f.ipv6.add(stripScope(a.getHostAddress()));
                }
                for (RouteInfo r : lp.getRoutes()) {
                    if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address && f.gateway.isEmpty()) f.gateway = r.getGateway().getHostAddress();
                }
                for (InetAddress d : lp.getDnsServers()) if (d instanceof Inet4Address) f.dns.add(d.getHostAddress());
                if (f.kind == WIFI && f.gateway.isEmpty()) f.gateway = dhcpGateway(c);
                covered.add(f.name);
                if (!f.ipv4.isEmpty() || !f.ipv6.isEmpty()) out.add(f);
            }
        } catch (Exception ignored) { }

        // interfaces Android does not treat as a "network": the hotspot and USB / Bluetooth tethering
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en != null) for (NetworkInterface ni : Collections.list(en)) {
                String name = ni.getName();
                if (covered.contains(name) || !ni.isUp() || ni.isLoopback()) continue;
                if (name.startsWith("dummy") || name.startsWith("tun") || name.startsWith("ip6") || name.startsWith("sit")) continue;
                Iface f = new Iface();
                f.name = name;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && f.ipv4.isEmpty()) { f.ipv4 = a.getHostAddress(); f.prefix = ia.getNetworkPrefixLength(); }
                    else if (a instanceof Inet6Address && !a.isLinkLocalAddress() && !a.isLoopbackAddress()) f.ipv6.add(stripScope(a.getHostAddress()));
                }
                if (f.ipv4.isEmpty()) continue;
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("v4-")) f.kind = CELLULAR;
                else if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("softap") || name.startsWith("swlan")
                        || name.startsWith("wigig") || name.startsWith("p2p") || name.startsWith("rndis") || name.startsWith("usb")
                        || name.startsWith("ncm") || name.startsWith("bt-pan") || name.startsWith("eth")) f.kind = HOTSPOT;
                else f.kind = OTHER;
                out.add(f);
            }
        } catch (Exception ignored) { }

        // Wi-Fi first, then hotspot, then the rest
        Collections.sort(out, (a, b) -> Integer.compare(a.kind, b.kind));
        return out;
    }

    @SuppressWarnings("deprecation")
    private static String dhcpGateway(Context c) {
        try {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int g = wm.getDhcpInfo().gateway;
            if (g == 0) return "";
            return (g & 0xFF) + "." + ((g >> 8) & 0xFF) + "." + ((g >> 16) & 0xFF) + "." + ((g >> 24) & 0xFF);
        } catch (Exception e) {
            return "";
        }
    }

    private static String stripScope(String v6) {
        int i = v6.indexOf('%');
        return i < 0 ? v6 : v6.substring(0, i);
    }
}
