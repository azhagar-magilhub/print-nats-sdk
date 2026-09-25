package com.magilhub.printnats.desktop.lan;

import com.magilhub.printnats.nats.LocalNatsServer;
import com.magilhub.printnats.nats.NatsConfig;
import com.magilhub.printnats.spi.LogSink;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

/**
 * LAN mode on a desktop master (Windows / macOS): the desktop twin of the Android LanServer. Runs the official
 * nats-server as the shop's local server (core {@link LocalNatsServer}: token auth, JetStream, supervised) and
 * advertises it as {@code _maghilnats._tcp} / {@code maghil-<locationId>} over mDNS, so tablets find it the same way
 * they find an Android master. One per process; {@link #sync} follows the current config.
 * <p>
 * Binary: {@code --nats-server <path>} / {@code PRINT_NATS_SERVER}, else {@code nats-server[.exe]} next to the sidecar
 * jar (or in its {@code nats-server/} folder — the installer puts it there), else {@code nats-server} on the PATH.
 */
public final class DesktopLanServer {
    public static final String SERVICE_TYPE = "_maghilnats._tcp.local.";
    private static final String LOG = "natsServer_";

    private static String binaryOverride;
    private static LocalNatsServer server;
    private static String serverKey;
    private static JmDNS mdns;

    private DesktopLanServer() {
    }

    /** From the sidecar's command line ({@code --nats-server}). */
    public static synchronized void setBinary(String path) {
        binaryOverride = path;
    }

    /** Start the server when {@code nats.serveLocal}, stop it otherwise. Idempotent. */
    public static synchronized void sync(File dataDir, NatsConfig nats, String sessionLocationId, LogSink log) {
        boolean want = nats != null && nats.serveLocal;
        // The app's NATS settings don't always carry the location (the SDK fills it from the session later) — the
        // mDNS name must be maghil-<locationId> or tablets never find this master ("maghil-null").
        final String locationId = nats != null && nats.locationId != null ? nats.locationId : sessionLocationId;
        String key = want ? nats.localPort + "|" + nats.authToken + "|" + locationId : null;
        if (want && key.equals(serverKey) && server != null) return;
        stop();
        if (!want) return;
        String bin = findBinary();
        if (bin == null) {
            log.append(LOG, "nats-server not found (--nats-server, PRINT_NATS_SERVER, next to the jar, or PATH) — LAN mode unavailable");
            return;
        }
        File dir = new File(dataDir, "nats-local");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            log.append(LOG, "Cannot create " + dir);
            return;
        }
        server = new LocalNatsServer(bin, dir, nats.localPort, nats.authToken, log);
        serverKey = key;
        server.start();
        log.append(LOG, "Local NATS server for " + locationId + " using " + bin);
        advertise(locationId, nats.localPort, log);
    }

    public static synchronized void stop() {
        if (server != null) server.stop();
        server = null;
        serverKey = null;
        if (mdns != null) {
            try {
                mdns.unregisterAllServices();
                mdns.close();
            } catch (Throwable ignored) {
                // already closed
            }
            mdns = null;
        }
    }

    public static synchronized boolean isRunning() {
        return server != null && server.isRunning();
    }

    public static String serviceName(String locationId) {
        return "maghil-" + locationId;
    }

    /** This machine's IPv4 address on the shop network (Wi-Fi / Ethernet), or null. */
    public static String localIp() {
        String best = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || ni.isPointToPoint()) continue;
                String name = ni.getName().toLowerCase();
                // VPN / VM / container adapters are not the shop network
                if (name.startsWith("utun") || name.startsWith("docker") || name.startsWith("vbox")
                        || name.startsWith("vmnet") || name.startsWith("bridge")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    if (a.isSiteLocalAddress()) return a.getHostAddress();
                    if (best == null) best = a.getHostAddress();
                }
            }
        } catch (Throwable ignored) {
            // no interfaces
        }
        return best;
    }

    static String findBinary() {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "nats-server.exe" : "nats-server";
        String env = System.getenv("PRINT_NATS_SERVER");
        for (String p : new String[]{binaryOverride, env}) {
            if (p != null && !p.isEmpty() && new File(p).isFile()) return p;
        }
        try {
            File jarDir = new File(DesktopLanServer.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .getParentFile();
            for (File f : new File[]{new File(jarDir, exe), new File(new File(jarDir, "nats-server"), exe)}) {
                if (f.isFile()) return f.getAbsolutePath();
            }
        } catch (Exception ignored) {
            // not running from a jar
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                File f = new File(dir, exe);
                if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
            }
        }
        return null;
    }

    private static void advertise(final String locationId, final int port, final LogSink log) {
        final String ip = localIp();
        if (ip == null) {
            log.append(LOG, "mDNS advertise skipped — no shop-network address");
            return;
        }
        // JmDNS start-up can take a few seconds; never hold the caller (configure) for it.
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JmDNS m = JmDNS.create(InetAddress.getByName(ip), "maghil-desktop");
                    m.registerService(ServiceInfo.create(SERVICE_TYPE, serviceName(locationId), port, "print-nats"));
                    synchronized (DesktopLanServer.class) {
                        if (server == null) {
                            m.close(); // stopped meanwhile
                            return;
                        }
                        mdns = m;
                    }
                    log.append(LOG, "Advertised on the shop network as " + serviceName(locationId) + " (" + ip + ":" + port + ")");
                } catch (Throwable e) {
                    log.append(LOG, "mDNS advertise failed: " + e);
                }
            }
        }, "print-nats-mdns");
        t.setDaemon(true);
        t.start();
    }
}
