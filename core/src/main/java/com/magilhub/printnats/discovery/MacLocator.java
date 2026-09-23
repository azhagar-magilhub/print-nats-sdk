package com.magilhub.printnats.discovery;

import com.magilhub.printnats.spi.ArpTable;
import com.magilhub.printnats.spi.LogSink;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Finds a printer's current IP from its MAC — port of legacy {@code SubnetDevices.fromLocalAddress().findDevices}:
 * touch every host of this device's /24 (a TCP connect to 9100 is enough to make the OS resolve and cache its
 * MAC, whether or not the port answers), then look the MAC up in the ARP cache.
 */
public class MacLocator {
    private static final int THREADS = 32;
    private static final int CONNECT_TIMEOUT_MS = 400;

    private final ArpTable arp;
    private final LogSink log;

    public MacLocator(ArpTable arp, LogSink log) {
        this.arp = arp;
        this.log = log == null ? LogSink.NONE : log;
    }

    /** Current IP of {@code mac}, or null (not found / ARP cache unreadable). Blocks for a few seconds. */
    public String find(String mac, String lastKnownIp, int port) {
        String want = Macs.normalize(mac);
        if (want == null) return null;
        List<String> prefixes = subnetPrefixes(lastKnownIp);
        if (prefixes.isEmpty()) {
            log.append("print_", "IP-RESCAN:: no local IPv4 network — skipped");
            return null;
        }
        for (String prefix : prefixes) sweep(prefix, port);
        Map<String, String> table = arp.ipToMac();
        if (table.isEmpty()) {
            log.append("print_", "IP-RESCAN:: ARP cache not readable on this device — cannot map MAC " + want + " to an IP");
            return null;
        }
        for (Map.Entry<String, String> e : table.entrySet()) {
            if (want.equals(e.getValue())) return e.getKey();
        }
        return null;
    }

    /** "a.b.c." of every site-local IPv4 interface; the one containing lastKnownIp first. */
    protected List<String> subnetPrefixes(String lastKnownIp) {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    String prefix = ip.substring(0, ip.lastIndexOf('.') + 1);
                    if (!out.contains(prefix)) out.add(prefix);
                }
            }
        } catch (Exception e) {
            log.append("print_", "IP-RESCAN:: interface listing failed: " + e);
        }
        if (lastKnownIp != null) {
            int dot = lastKnownIp.lastIndexOf('.');
            String hint = dot > 0 ? lastKnownIp.substring(0, dot + 1) : null;
            if (hint != null && out.remove(hint)) out.add(0, hint);
        }
        return out;
    }

    protected void sweep(final String prefix, final int port) {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        for (int i = 1; i < 255; i++) {
            final String host = prefix + i;
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                    } catch (Exception ignored) {
                        // refused / timed out — the ARP resolution already happened if the host is up
                    }
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
