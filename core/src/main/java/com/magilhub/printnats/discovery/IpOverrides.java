package com.magilhub.printnats.discovery;

import com.magilhub.printnats.queue.PrinterConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Printer IPs found by rediscovery, kept until the backend device list catches up (the host saves the new
 * identifier to the backend, like legacy {@code updateIPAddress → EditPrinter}). An override applies only while
 * the configured IP is still the OLD one — once the backend reports anything else, the backend wins.
 * Serialized inside {@link com.magilhub.printnats.PrintNatsConfig} so it survives restarts.
 */
public final class IpOverrides {
    /** mac → {oldIp, newIp}. Public for Gson. */
    public Map<String, String[]> byMac = new LinkedHashMap<>();

    public synchronized void put(String mac, String oldIp, String newIp) {
        byMac.put(Macs.normalize(mac), new String[]{oldIp, newIp});
    }

    /** Rewrites matching rows in place; drops overrides the backend has already superseded. */
    public synchronized void apply(List<PrinterConfig> printers) {
        if (byMac == null || byMac.isEmpty() || printers == null) return;
        java.util.Set<String> seenStale = new java.util.HashSet<>();
        java.util.Set<String> seenApplied = new java.util.HashSet<>();
        for (PrinterConfig p : printers) {
            String mac = Macs.macOf(p.address);
            String[] o = mac == null ? null : byMac.get(mac);
            if (o == null) continue;
            String ip = Macs.ipOf(p.address);
            if (o[0].equals(ip)) {
                p.address = Macs.withIp(p.address, o[1]);
                seenApplied.add(mac);
            } else {
                seenStale.add(mac);
            }
        }
        seenStale.removeAll(seenApplied);
        for (String mac : seenStale) byMac.remove(mac);
    }

    public synchronized boolean isEmpty() {
        return byMac == null || byMac.isEmpty();
    }
}
