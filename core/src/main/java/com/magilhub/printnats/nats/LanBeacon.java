package com.magilhub.printnats.nats;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * LAN mode master announcement over UDP broadcast (port {@link #PORT}): the master sends
 * {@code {"t":"maghil-master","v":1,"loc","dev","epoch","port"}} every {@link #SEND_EVERY_MS}; every device in LAN mode
 * listens and reports each master it hears — with the SENDER's address taken from the packet (no stored IP) — to
 * {@link Listener}. The host decides who the master is: the highest {@code epoch} wins (a new "set as master" makes a
 * higher one), so a stale master that still announces is ignored and steps down when it hears the newer one.
 * Reports only on change or every {@link #REPORT_EVERY_MS} per master, to keep logs quiet. Java 8; no Android APIs
 * (the Android host holds a Wi-Fi MulticastLock so broadcasts are delivered).
 */
public final class LanBeacon {
    public static final int PORT = 41222;
    static final long SEND_EVERY_MS = 2000;
    static final long REPORT_EVERY_MS = 10_000;
    private static final String TYPE = "maghil-master";

    public interface Listener {
        /** A master announced itself for this location. {@code ip} is the packet's source address. */
        void onMaster(String deviceId, long epoch, String ip, int port);
    }

    private final String locationId;
    private final String deviceId;
    private final Listener listener;
    private volatile boolean sending;
    private volatile long epoch;
    private volatile int natsPort;
    private volatile boolean running;
    private DatagramSocket socket;
    private final Map<String, long[]> lastReport = new HashMap<>(); // dev → {epoch, reportedAt}

    public LanBeacon(String locationId, String deviceId, Listener listener) {
        this.locationId = locationId;
        this.deviceId = deviceId;
        this.listener = listener;
    }

    /** Master: announce with this epoch (0 / false stops announcing; listening continues). */
    public void announce(boolean on, long epoch, int natsPort) {
        this.sending = on;
        this.epoch = epoch;
        this.natsPort = natsPort;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.setSoTimeout(1000);
            socket.bind(new InetSocketAddress(PORT));
        } catch (Exception e) {
            running = false;
            if (listener != null) report("_error", 0, String.valueOf(e), 0);
            return;
        }
        Thread rx = new Thread(this::receiveLoop, "print-nats-lan-beacon-rx");
        rx.setDaemon(true);
        rx.start();
        Thread tx = new Thread(this::sendLoop, "print-nats-lan-beacon-tx");
        tx.setDaemon(true);
        tx.start();
    }

    public synchronized void stop() {
        running = false;
        if (socket != null) socket.close();
        socket = null;
    }

    private void sendLoop() {
        while (running) {
            if (sending && epoch > 0) {
                JsonObject o = new JsonObject();
                o.addProperty("t", TYPE);
                o.addProperty("v", 1);
                o.addProperty("loc", locationId);
                o.addProperty("dev", deviceId);
                o.addProperty("epoch", epoch);
                o.addProperty("port", natsPort);
                byte[] data = o.toString().getBytes(StandardCharsets.UTF_8);
                for (InetAddress target : broadcastTargets()) {
                    try {
                        DatagramSocket s = socket;
                        if (s != null) s.send(new DatagramPacket(data, data.length, target, PORT));
                    } catch (Exception ignored) {
                        // interface went away — next round
                    }
                }
            }
            try {
                Thread.sleep(SEND_EVERY_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[1024];
        while (running) {
            DatagramSocket s = socket;
            if (s == null) return;
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                s.receive(p);
            } catch (SocketTimeoutException t) {
                continue;
            } catch (Exception e) {
                if (!running) return;
                continue;
            }
            try {
                JsonObject o = JsonParser.parseString(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (!TYPE.equals(str(o, "t")) || !locationId.equals(str(o, "loc"))) continue;
                String dev = str(o, "dev");
                if (dev == null || dev.equals(deviceId)) continue; // our own announcement
                long e = o.has("epoch") ? o.get("epoch").getAsLong() : 0;
                int port = o.has("port") ? o.get("port").getAsInt() : 4222;
                report(dev, e, p.getAddress().getHostAddress(), port);
            } catch (RuntimeException ignored) {
                // not ours
            }
        }
    }

    private void report(String dev, long e, String ip, int port) {
        long now = System.currentTimeMillis();
        synchronized (lastReport) {
            long[] last = lastReport.get(dev);
            if (last != null && last[0] == e && now - last[1] < REPORT_EVERY_MS) return;
            lastReport.put(dev, new long[]{e, now});
        }
        if (listener != null) listener.onMaster(dev, e, ip, port);
    }

    /** 255.255.255.255 plus every interface's directed broadcast (routers often drop the limited one). */
    static Set<InetAddress> broadcastTargets() {
        Set<InetAddress> out = new LinkedHashSet<>();
        try {
            out.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress a : ni.getInterfaceAddresses()) {
                    if (a.getBroadcast() != null) out.add(a.getBroadcast());
                }
            }
        } catch (Exception ignored) {
            // limited broadcast only
        }
        return out;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }
}
