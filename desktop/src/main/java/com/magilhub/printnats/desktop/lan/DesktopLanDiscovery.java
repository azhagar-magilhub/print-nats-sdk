package com.magilhub.printnats.desktop.lan;

import java.net.Inet4Address;
import java.net.InetAddress;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

/**
 * Desktop client: find this location's master server on the shop network over mDNS ({@code _maghilnats._tcp},
 * service {@code maghil-<locationId>}) — the desktop twin of the Android LanDiscovery. Blocking.
 */
public final class DesktopLanDiscovery {
    private DesktopLanDiscovery() {
    }

    /** {@code "host:port"} of the master's server, or null when none answered within {@code timeoutMs}. */
    public static String find(String locationId, long timeoutMs) throws Exception {
        String ip = DesktopLanServer.localIp();
        JmDNS m = ip == null ? JmDNS.create() : JmDNS.create(InetAddress.getByName(ip));
        try {
            ServiceInfo info = m.getServiceInfo(DesktopLanServer.SERVICE_TYPE, DesktopLanServer.serviceName(locationId),
                    timeoutMs);
            if (info == null) return null;
            for (Inet4Address a : info.getInet4Addresses()) {
                return a.getHostAddress() + ":" + info.getPort();
            }
            return null;
        } finally {
            m.close();
        }
    }
}
