package com.magilhub.printnats.discovery;

import java.util.Locale;

/** MAC / "ip|mac" address helpers. Printer device identifiers are stored as {@code [TCP:]ip|mac} (legacy). */
public final class Macs {
    private Macs() {
    }

    /** "AA-BB-CC-DD-EE-FF" / "aa:bb:…" → "aa:bb:cc:dd:ee:ff"; null when not a 6-octet MAC. */
    public static String normalize(String mac) {
        if (mac == null) return null;
        String m = mac.trim().toLowerCase(Locale.ROOT).replace('-', ':');
        String[] parts = m.split(":");
        if (parts.length != 6) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 2) return null;
            for (int k = 0; k < p.length(); k++) if (Character.digit(p.charAt(k), 16) < 0) return null;
            if (i > 0) sb.append(':');
            if (p.length() == 1) sb.append('0');
            sb.append(p);
        }
        String out = sb.toString();
        return "00:00:00:00:00:00".equals(out) ? null : out; // incomplete ARP entries
    }

    /** MAC part of "[TCP:]ip|mac", normalized; null when the address carries none. */
    public static String macOf(String address) {
        if (address == null) return null;
        int bar = address.indexOf('|');
        return bar < 0 ? null : normalize(address.substring(bar + 1));
    }

    /** IP part of "[TCP:]ip|mac". */
    public static String ipOf(String address) {
        if (address == null) return null;
        String a = address.startsWith("TCP:") ? address.substring(4) : address;
        int bar = a.indexOf('|');
        return (bar < 0 ? a : a.substring(0, bar)).trim();
    }

    /** Same address with the IP replaced; keeps the "TCP:" prefix and the MAC exactly as stored. */
    public static String withIp(String address, String newIp) {
        String prefix = address.startsWith("TCP:") ? "TCP:" : "";
        String a = address.substring(prefix.length());
        int bar = a.indexOf('|');
        return prefix + newIp + (bar < 0 ? "" : a.substring(bar));
    }
}
