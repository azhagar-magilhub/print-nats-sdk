package com.magilhub.printnats.android.lan;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import com.magilhub.printnats.nats.LocalNatsServer;
import com.magilhub.printnats.nats.NatsConfig;
import com.magilhub.printnats.spi.LogSink;

import java.io.File;

/**
 * LAN mode on the master device: runs the bundled nats-server ({@code libnatsserver.so}) and advertises it on the
 * shop network as {@code _maghilnats._tcp} (service name {@code maghil-<locationId>}), so client devices can find it
 * even when the master's IP changed. One per process; {@link #sync} follows the current config.
 */
public final class LanServer {
    public static final String SERVICE_TYPE = "_maghilnats._tcp.";
    private static final String BINARY = "libnatsserver.so";
    private static final String LOG = "natsServer_";

    private static LocalNatsServer server;
    private static String serverKey;
    private static NsdManager.RegistrationListener registration;
    private static android.net.wifi.WifiManager.MulticastLock beaconLock;
    private static Context appContext;

    private LanServer() {
    }

    /** Start the server when {@code nats.serveLocal}, stop it otherwise. Idempotent. */
    public static synchronized void sync(Context context, NatsConfig nats, LogSink log) {
        appContext = context.getApplicationContext();
        boolean want = nats != null && nats.serveLocal;
        String key = want ? nats.localPort + "|" + nats.authToken + "|" + nats.locationId : null;
        if (want && key.equals(serverKey) && server != null) return;
        stop(context);
        if (!want) return;
        Context app = context.getApplicationContext();
        File bin = new File(app.getApplicationInfo().nativeLibraryDir, BINARY);
        if (!bin.exists()) {
            log.append(LOG, "Local NATS server not bundled in this build (" + bin + ") — LAN mode unavailable");
            return;
        }
        File dir = new File(app.getFilesDir(), "nats-local");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            log.append(LOG, "Cannot create " + dir);
            return;
        }
        server = new LocalNatsServer(bin.getAbsolutePath(), dir, nats.localPort, nats.authToken, log);
        serverKey = key;
        server.start();
        advertise(app, nats.locationId, nats.localPort, log);
    }

    /**
     * LAN mode: many Android devices drop UDP broadcasts while the screen is off or to save power unless a Wi-Fi
     * MulticastLock is held — the master beacon (LanBeacon, port 41222) needs them delivered. Held while LAN mode is on.
     */
    public static synchronized void holdBeaconLock(Context context, boolean on) {
        try {
            if (on && beaconLock == null) {
                android.net.wifi.WifiManager wifi = (android.net.wifi.WifiManager)
                        context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifi == null) return;
                beaconLock = wifi.createMulticastLock("print-nats-lan-beacon");
                beaconLock.setReferenceCounted(false);
                beaconLock.acquire();
            } else if (!on && beaconLock != null) {
                beaconLock.release();
                beaconLock = null;
            }
        } catch (Throwable ignored) {
            // no Wi-Fi service / permission — beacons may still arrive
        }
    }

    /** Sign-out / SDK stop. */
    public static synchronized void stop() {
        if (appContext != null) stop(appContext);
    }

    public static synchronized void stop(Context context) {
        if (server != null) server.stop();
        server = null;
        serverKey = null;
        if (registration != null) {
            try {
                nsd(context).unregisterService(registration);
            } catch (Throwable ignored) {
                // not registered
            }
            registration = null;
        }
    }

    public static synchronized boolean isRunning() {
        return server != null && server.isRunning();
    }

    /** Waits until the local server accepts connections (master start-up). */
    public static boolean awaitReady(long timeoutMs) throws InterruptedException {
        LocalNatsServer s;
        synchronized (LanServer.class) {
            s = server;
        }
        return s != null && s.awaitReady(timeoutMs);
    }

    public static String serviceName(String locationId) {
        return "maghil-" + locationId;
    }

    private static void advertise(Context app, String locationId, int port, final LogSink log) {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(serviceName(locationId));
        info.setServiceType(SERVICE_TYPE);
        info.setPort(port);
        registration = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo s, int errorCode) {
                log.append(LOG, "NSD advertise failed code=" + errorCode);
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo s, int errorCode) {
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo s) {
                log.append(LOG, "Advertised on the shop network as " + s.getServiceName());
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo s) {
            }
        };
        try {
            nsd(app).registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (Throwable t) {
            registration = null;
            log.append(LOG, "NSD advertise failed: " + t);
        }
    }

    static NsdManager nsd(Context context) {
        return (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
    }
}
