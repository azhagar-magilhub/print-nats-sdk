package com.magilhub.printnats;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.magilhub.printnats.nats.NatsConfig;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.rules.Session;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the SDK needs, as one JSON document — sent by the JS layer ({@code configure()}), persisted by
 * hosts so a background service/sidecar can rebuild the SDK without the UI running.
 * <pre>
 * { "nats": {serverUrls, authToken, consumerName?, testMode?, isMaster?},
 *   "session": {apiBaseUrl, accessToken, merchantId, locationId, deviceId, appVersion, buildNumber},
 *   "restaurant": { …restaurantDetails… },
 *   "printers": [{id, name, connection: LAN|USB|BLUETOOTH|SERIAL|WINDOWS_QUEUE, address, port,
 *                 purpose: RECEIPT|MASTER_KOT|STATION_KOT, isStar, is58mm, utf8, modelName,
 *                 cuisineId, stationName, kotSpace}],
 *   "autoStartOnBoot": false }
 * </pre>
 */
public final class PrintNatsConfig {
    private static final Gson GSON = new GsonBuilder().create();

    public NatsConfig nats;
    public Session session = new Session();
    public JsonObject restaurant = new JsonObject();
    public List<PrinterConfig> printers = new ArrayList<>();
    /**
     * Optional raw backend device list (GET /devices/fetch-devices). When present the SDK derives BOTH the printer
     * rows and this device's master role from it ({@link com.magilhub.printnats.rules.DeviceList}); explicit
     * {@code printers} / {@code nats.isMaster} are then ignored.
     */
    public com.google.gson.JsonArray devices;
    public boolean autoStartOnBoot;
    /** See PrintPipeline#setSuppressNatsKotAfterHostPrint (maghilOrder: true). */
    public boolean suppressNatsKotAfterHostPrint;
    /** Printer IPs found by MAC rediscovery that the backend device list doesn't reflect yet. */
    public com.magilhub.printnats.discovery.IpOverrides ipOverrides;

    public static PrintNatsConfig fromJson(String json) {
        PrintNatsConfig c = GSON.fromJson(json, PrintNatsConfig.class);
        if (c.session == null) c.session = new Session();
        if (c.restaurant == null) c.restaurant = new JsonObject();
        if (c.printers == null) c.printers = new ArrayList<>();
        c.applyDevices();
        for (PrinterConfig p : c.printers) {
            if (p.connection == null) p.connection = PrinterConfig.Connection.LAN;
            if (p.purpose == null) p.purpose = PrinterConfig.Purpose.STATION_KOT;
            if (p.port == 0) p.port = 9100;
        }
        return c;
    }

    /** Derive printers + master role from {@link #devices} (no-op when absent or empty). */
    public void applyDevices() {
        if (devices == null) return;
        com.magilhub.printnats.rules.Restaurant r = new com.magilhub.printnats.rules.Restaurant(restaurant);
        List<PrinterConfig> derived = com.magilhub.printnats.rules.DeviceList.printers(devices, session.deviceId, r);
        if (derived != null) printers = derived;
        if (nats != null && devices.size() > 0) nats.isMaster = com.magilhub.printnats.rules.DeviceList.isMaster(devices, session.deviceId);
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /** A builder pre-filled with this config; hosts add platform pieces (stores, transports, logs). */
    public PrintNats.Builder toBuilder() {
        if (ipOverrides == null) ipOverrides = new com.magilhub.printnats.discovery.IpOverrides();
        PrintNats.Builder b = PrintNats.builder().session(session).restaurant(restaurant).printers(printers)
                .ipOverrides(ipOverrides);
        if (nats != null && nats.serverUrls != null && !nats.serverUrls.isEmpty()) b.nats(nats);
        b.suppressNatsKotAfterHostPrint(suppressNatsKotAfterHostPrint);
        return b;
    }
}
