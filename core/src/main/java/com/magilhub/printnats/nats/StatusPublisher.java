package com.magilhub.printnats.nats;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magilhub.printnats.queue.FailureClassifier;
import com.magilhub.printnats.queue.JobListener;
import com.magilhub.printnats.queue.PrintJob;
import com.magilhub.printnats.queue.PrintQueue;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.spi.DeviceState;
import com.magilhub.printnats.spi.LogSink;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Publishes print-status events to {@code printeventstatus.<locationId>.<deviceId>} with the exact JSON
 * shape of MerchantApp's {@code NatsPrintStatusPublisher} + {@code PrintFrameworkModule.buildPrintStatusEvent}
 * (Release-25.1), so maghil-admin and the Failed Print Queue keep working unchanged. Every event is also
 * written to the local {@code natsStatus_} log with its outcome, whether or not it reached the server.
 * Publishing runs on its own single thread and can never block or fail a print.
 */
public final class StatusPublisher implements JobListener {
    private final NatsClient nats;
    private final PrintQueue.PrinterLookup printers;
    private final LogSink log;
    private final DeviceState device;
    private final String locationId;
    private final String deviceId;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "print-status-publisher");
        t.setDaemon(true);
        return t;
    });

    public StatusPublisher(NatsClient nats, PrintQueue.PrinterLookup printers, LogSink log, DeviceState device,
                           String locationId, String deviceId) {
        this.nats = nats;
        this.printers = printers;
        this.log = log == null ? LogSink.NONE : log;
        this.device = device == null ? DeviceState.ALWAYS_ONLINE : device;
        this.locationId = locationId;
        this.deviceId = deviceId;
    }

    /** Queue events → legacy status strings. "retrying" is not published (legacy never did). */
    @Override
    public void onJobEvent(PrintJob job, String event) {
        // relay jobs are hand-offs to the master device, which publishes the real print status
        if (com.magilhub.printnats.queue.PrinterConfig.isRelay(job.printerId)) return;
        String status;
        switch (event) {
            case "inqueue":
                status = "inqueue";
                break;
            case "print completed":
            case "skipped": // legacy reported stale/empty KOTs as completed
                status = "print completed";
                break;
            case "print_failed":
                status = "print_failed";
                break;
            case "cancelled":
                status = "print_cancelled";
                break;
            default:
                return;
        }
        final JsonObject json = buildJobEvent(job, status, "print_failed".equals(status) ? job.reason : null);
        publishAsync(json);
    }

    /**
     * The "received" event FCMService.tsx publishes when a PRINT_RECEIPT (not status 60) or REPRINT_STATION_KOT
     * message arrives: {@code printjobid = messageId}, type CreateKot/ReprintKot.
     */
    public void publishReceived(String messageId, String type, String orderNo, String kotNo, String sortOrder,
                                String printStation, JsonObject extraData) {
        JsonObject json = new JsonObject();
        put(json, "messageId", messageId);
        put(json, "locationId", locationId);
        put(json, "printjobid", messageId);
        put(json, "printStation", printStation);
        put(json, "status", "received");
        put(json, "type", type);
        put(json, "orderNo", orderNo);
        put(json, "kotNo", kotNo);
        put(json, "sortOrder", sortOrder);
        if (extraData != null) json.addProperty("extraData", extraData.toString());
        json.addProperty("timestamp", System.currentTimeMillis());
        publishAsync(json);
    }

    /** A print_failed that has no local job (legacy JS publishReprintSkipped for REPRINT_STATION_KOT). */
    public void publishFailed(String messageId, String printStation, String type, String reason, String orderNo,
                              String kotNo, String orderId, String sortOrder, String cuisineId) {
        JsonObject json = new JsonObject();
        put(json, "messageId", messageId);
        put(json, "locationId", locationId);
        put(json, "printjobid", messageId);
        put(json, "printStation", printStation);
        put(json, "status", "print_failed");
        put(json, "type", type);
        put(json, "reason", reason);
        put(json, "orderNo", orderNo);
        put(json, "kotNo", kotNo);
        put(json, "orderId", orderId);
        put(json, "sortOrder", sortOrder);
        put(json, "cuisineId", cuisineId);
        json.addProperty("timestamp", System.currentTimeMillis());
        publishAsync(json);
    }

    /** Port of PrintFrameworkModule.buildPrintStatusEvent + NatsPrintStatusPublisher JSON. */
    JsonObject buildJobEvent(PrintJob job, String status, String reason) {
        JsonObject data = parse(job.payloadJson);
        JsonObject extra = parse(str(data, "extraData"));
        boolean hasOrderMeta = false;
        for (String k : new String[]{"orderDate", "orderTime", "orderTypeGroup", "orderTypeId", "orderSource", "tableName"}) {
            if (str(data, k) != null) hasOrderMeta = true;
        }
        if (hasOrderMeta) {
            for (String k : new String[]{"orderDate", "orderTime", "orderTypeGroup", "orderTypeId", "orderSource", "tableName"}) {
                put(extra, k, str(data, k));
            }
            for (String k : new String[]{"isKioskOrder", "isQSROrder", "isVoiceOrder", "isEventOrder"}) {
                extra.addProperty(k, bool(data, k, false));
            }
        }
        boolean isLocalRetry = bool(data, "isLocalRetry", false);
        extra.addProperty("isLocal", isLocalRetry);
        extra.addProperty("isAutoPrint", bool(data, "isAutoPrint", true));

        String type = job.retries > 0 ? "RetryKot" : "CreateKot";
        if (job.retries == 0 && bool(data, "reprintKOT", false) && !isLocalRetry) type = "ReprintKot";
        if (bool(data, "isOrderCancelled", false)) {
            type = "CancelKot";
            JsonElement items = data.get("items");
            if (items != null && items.isJsonArray() && ((JsonArray) items).size() > 0) extra.add("originalItems", items);
        }
        if ("print_failed".equals(status)) {
            extra.addProperty("deviceOnline", device.isNetworkConnected());
            extra.addProperty("failureCategory", FailureClassifier.category(reason));
        }

        PrinterConfig printer = printers.get(job.printerId);
        Boolean isMaster = null;
        String cuisineId = null;
        String station = null;
        boolean isStation = job.isStation;
        if (printer != null) {
            cuisineId = printer.cuisineId;
            isMaster = printer.purpose == PrinterConfig.Purpose.MASTER_KOT;
            station = printer.resolvedStationName();
            if (printer.address != null && !printer.address.isEmpty()) {
                extra.addProperty("printerIp", printer.address.split("\\|")[0]);
            }
        }

        JsonObject json = new JsonObject();
        put(json, "messageId", str(data, "messageId"));
        put(json, "locationId", str(data, "locationId") != null ? str(data, "locationId") : locationId);
        put(json, "printjobid", job.jobId);
        put(json, "printStation", station);
        put(json, "status", status);
        put(json, "type", type);
        put(json, "reason", reason);
        put(json, "orderNo", str(data, "orderNo"));
        put(json, "kotNo", str(data, "kotNo"));
        put(json, "orderId", str(data, "orderId"));
        put(json, "sortOrder", str(data, "sortOrder"));
        put(json, "orderStatus", str(data, "orderStatus"));
        json.addProperty("retries", job.retries);
        put(json, "cuisineId", cuisineId);
        if (isMaster != null) json.addProperty("isMaster", isMaster);
        json.addProperty("isStation", isStation);
        json.addProperty("extraData", extra.toString());
        json.addProperty("timestamp", System.currentTimeMillis());
        return json;
    }

    private void publishAsync(final JsonObject json) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                String subject = "printeventstatus." + (locationId) + "." + deviceId;
                try {
                    if (locationId == null || deviceId == null) {
                        log.append(NatsClient.STATUS_LOG, "SKIPPED (missing locationId/deviceId) | " + json);
                        return;
                    }
                    boolean ok = nats.publish(subject, json.toString().getBytes(StandardCharsets.UTF_8));
                    log.append(NatsClient.STATUS_LOG, (ok ? "PUBLISHED" : "BUFFERED (offline/failed, retried on reconnect)")
                            + " | " + subject + " | " + json);
                } catch (Throwable t) {
                    log.append(NatsClient.STATUS_LOG, "ERROR " + t + " | " + subject + " | " + json);
                }
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
    }

    // ---- json helpers (org.json semantics: null values are omitted) ----------------------------------

    static JsonObject parse(String s) {
        if (s == null || s.isEmpty()) return new JsonObject();
        try {
            JsonElement e = JsonParser.parseString(s);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return null;
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    static boolean bool(JsonObject o, String key, boolean def) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsBoolean();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    static void put(JsonObject o, String key, String value) {
        if (value != null) o.addProperty(key, value);
    }
}
