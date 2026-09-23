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
    public boolean autoStartOnBoot;

    public static PrintNatsConfig fromJson(String json) {
        PrintNatsConfig c = GSON.fromJson(json, PrintNatsConfig.class);
        if (c.session == null) c.session = new Session();
        if (c.restaurant == null) c.restaurant = new JsonObject();
        if (c.printers == null) c.printers = new ArrayList<>();
        for (PrinterConfig p : c.printers) {
            if (p.connection == null) p.connection = PrinterConfig.Connection.LAN;
            if (p.purpose == null) p.purpose = PrinterConfig.Purpose.STATION_KOT;
            if (p.port == 0) p.port = 9100;
        }
        return c;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /** A builder pre-filled with this config; hosts add platform pieces (stores, transports, logs). */
    public PrintNats.Builder toBuilder() {
        PrintNats.Builder b = PrintNats.builder().session(session).restaurant(restaurant).printers(printers);
        if (nats != null && nats.serverUrls != null && !nats.serverUrls.isEmpty()) b.nats(nats);
        return b;
    }
}
