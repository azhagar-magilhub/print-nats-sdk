package com.magilhub.printnats.android.lan;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;

import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Client side of LAN mode: finds the master's local NATS server on the shop network (NSD / mDNS,
 * {@link LanServer#SERVICE_TYPE}, service {@code maghil-<locationId>}). Blocking; call off the UI thread.
 */
public final class LanDiscovery {
    private LanDiscovery() {
    }

    /** {@code "host:port"} of this location's master server, or null when none answered within {@code timeoutMs}. */
    public static String find(Context context, String locationId, long timeoutMs) throws InterruptedException {
        final Context app = context.getApplicationContext();
        final NsdManager nsd = LanServer.nsd(app);
        final String wanted = LanServer.serviceName(locationId);
        final AtomicReference<String> found = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = wifi == null ? null : wifi.createMulticastLock("print-nats-discovery");
        if (lock != null) {
            lock.setReferenceCounted(false);
            lock.acquire();
        }
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                done.countDown();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                if (!wanted.equals(info.getServiceName())) return;
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo s, int errorCode) {
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo s) {
                        InetAddress host = s.getHost();
                        if (host != null && found.compareAndSet(null, host.getHostAddress() + ":" + s.getPort())) {
                            done.countDown();
                        }
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
            }
        };
        try {
            nsd.discoverServices(LanServer.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (Throwable ignored) {
                // never started
            }
            if (lock != null) lock.release();
        }
        return found.get();
    }
}
