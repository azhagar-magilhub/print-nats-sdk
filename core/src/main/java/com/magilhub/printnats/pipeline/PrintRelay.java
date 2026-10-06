package com.magilhub.printnats.pipeline;

import com.google.gson.JsonObject;
import com.magilhub.printnats.rules.Json;
import com.magilhub.printnats.spi.InboundStore;
import com.magilhub.printnats.spi.LogSink;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Master/client print relay: in a location only the master device prints; a client hands its KOTs / receipts to
 * the master over core NATS request/reply on {@link #subject(String)}.
 * <pre>
 * request: {relayId, kind: "KOT"|"EDIT_KOT"|"RECEIPT", order: {...}, tableName, cancelled, cardSurcharge, from}
 * reply:   {ok: true, tickets: n}  |  {ok: false, error: "..."}
 * </pre>
 * The master side ({@link #handle}) deduplicates by relayId in the {@link InboundStore} (key {@code relay|<relayId>};
 * a duplicate replies ok with 0 tickets), lets the host adjust the order ({@link RelayOrderHook}), then prints via
 * the same entry points as a host-UI print. Relayed KOTs are remembered as printed here, so the backend's later
 * PRINTKOT copy of the same order + batch is skipped.
 */
public final class PrintRelay {
    public static final String KOT = "KOT";
    public static final String EDIT_KOT = "EDIT_KOT";
    public static final String RECEIPT = "RECEIPT";
    static final String KEY_PREFIX = "relay|";

    private final PrintPipeline pipeline;
    private final InboundStore inbound;
    private final LogSink log;
    private volatile RelayOrderHook hook;

    public PrintRelay(PrintPipeline pipeline, InboundStore inbound, LogSink log) {
        this.pipeline = pipeline;
        this.inbound = inbound;
        this.log = log == null ? LogSink.NONE : log;
        pipeline.setRelay(this);
    }

    public static String subject(String locationId) {
        return "printrelay." + locationId + ".master";
    }

    public void setHook(RelayOrderHook hook) {
        this.hook = hook;
    }

    /** A new relay request (fresh relayId). */
    public static JsonObject request(String kind, JsonObject order, String tableName, boolean cancelled,
                                     double cardSurcharge, String fromDeviceId) {
        JsonObject r = new JsonObject();
        r.addProperty("relayId", UUID.randomUUID().toString());
        r.addProperty("kind", kind);
        r.add("order", order == null ? new JsonObject() : order);
        if (tableName != null) r.addProperty("tableName", tableName);
        r.addProperty("cancelled", cancelled);
        r.addProperty("cardSurcharge", cardSurcharge);
        if (fromDeviceId != null) r.addProperty("from", fromDeviceId);
        return r;
    }

    // ---- master side ----------------------------------------------------------------------------------

    /** Receipts print off the request thread: the client waits for the reply, not for the render. */
    private final java.util.concurrent.ExecutorService receiptWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "printnats-relay-receipt");
                t.setDaemon(true);
                return t;
            });

    /**
     * KOTs print off the request thread too, one at a time in arrival order. The relay hook waits for the host's
     * JS (KOT number), which is slow while the master's own app is busy; answering only after it made a client's
     * KOTs queue up behind each other (each request waited its turn) and print long after the master's own,
     * instead of in the order the orders were placed.
     */
    private final java.util.concurrent.ExecutorService kotWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "printnats-relay-kot");
                t.setDaemon(true);
                return t;
            });

    /** Request bytes → reply bytes (never null). */
    public byte[] handle(byte[] body) {
        String raw = body == null ? null : new String(body, StandardCharsets.UTF_8);
        JsonObject req = Json.parseObject(raw);
        if (req != null && RECEIPT.equals(Json.str(req, "kind")) && Json.obj(req, "order") != null) {
            return acceptReceipt(raw).toString().getBytes(StandardCharsets.UTF_8);
        }
        String kind = req == null ? null : Json.str(req, "kind");
        if ((KOT.equals(kind) || EDIT_KOT.equals(kind)) && Json.obj(req, "order") != null) {
            return acceptKot(raw, req).toString().getBytes(StandardCharsets.UTF_8);
        }
        return handle(raw).toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A client's receipt: answer at once (1 = accepted, 0 = this master has no receipt printer) and print in the
     * background. The relay hook (host JS) plus the receipt render can take longer than the client's request
     * timeout; a late answer made the client fall back to the cloud, which has never seen an offline order.
     */
    private JsonObject acceptReceipt(final String raw) {
        if (!pipeline.hasReceiptPrinter()) return ok(0);
        receiptWorker.execute(() -> {
            JsonObject r = handle(raw);
            if (!Json.isTrueBoolean(r, "ok")) log.append("print_", "Info:: relayed receipt not printed: " + Json.str(r, "error"));
        });
        return ok(1);
    }

    /**
     * A client's KOT: store it durably (deduplicated by relayId), answer at once, print in the background in
     * arrival order. A crash after the answer leaves it pending in the inbound store → printed by
     * {@link #replay} at the next start, exactly as before.
     */
    private JsonObject acceptKot(final String raw, JsonObject req) {
        String relayId = Json.str(req, "relayId");
        String kind = Json.str(req, "kind");
        if (relayId == null) return error("Bad relay request");
        final String key = KEY_PREFIX + relayId;
        boolean fresh;
        synchronized (this) {
            try {
                fresh = inbound.record(new InboundStore.Inbound(key, "RELAY_" + kind, raw, relayId, System.currentTimeMillis()));
            } catch (RuntimeException e) {
                log.append("fcmInsights_", "Relay store failed relayId=" + relayId + ": " + e);
                return error("Master could not store the request: " + e.getMessage());
            }
            if (!fresh && !stillPending(key)) {
                log.append("fcmInsights_", "Duplicate relay dropped relayId=" + relayId + " from=" + Json.str(req, "from"));
                return ok(0);
            }
            if (!fresh) return ok(1); // still pending from an earlier delivery — already queued / replayed
        }
        log.append("fcmInsights_", "Relay accepted " + kind + " relayId=" + relayId + " from=" + Json.str(req, "from")
                + " orderNo=" + Json.str(Json.obj(req, "order"), "orderNo"));
        final JsonObject queued = req;
        kotWorker.execute(() -> {
            try {
                print(queued);
            } catch (RuntimeException e) {
                // left pending: replayed at the next start
                log.append("print_", "Exception:: relay print failed relayId=" + Json.str(queued, "relayId") + ": " + e);
                return;
            }
            inbound.markDone(key);
        });
        return ok(1);
    }

    synchronized JsonObject handle(String raw) {
        JsonObject req = Json.parseObject(raw);
        String relayId = Json.str(req, "relayId");
        String kind = Json.str(req, "kind");
        if (req == null || relayId == null || kind == null || Json.obj(req, "order") == null) {
            return error("Bad relay request");
        }
        String key = KEY_PREFIX + relayId;
        InboundStore.Inbound in = new InboundStore.Inbound(key, "RELAY_" + kind, raw, relayId, System.currentTimeMillis());
        boolean fresh;
        try {
            fresh = inbound.record(in);
        } catch (RuntimeException e) {
            log.append("fcmInsights_", "Relay store failed relayId=" + relayId + ": " + e);
            return error("Master could not store the request: " + e.getMessage());
        }
        if (!fresh && !stillPending(key)) {
            log.append("fcmInsights_", "Duplicate relay dropped relayId=" + relayId + " from=" + Json.str(req, "from"));
            return ok(0);
        }
        log.append("fcmInsights_", "Relay accepted " + kind + " relayId=" + relayId + " from=" + Json.str(req, "from")
                + " orderNo=" + Json.str(Json.obj(req, "order"), "orderNo"));
        try {
            int n = print(req);
            inbound.markDone(key);
            return ok(n);
        } catch (RuntimeException e) {
            // left pending: replayed at the next start, or printed when the client retries
            log.append("print_", "Exception:: relay print failed relayId=" + relayId + ": " + e);
            return error("Master could not print: " + e.getMessage());
        }
    }

    /** Crash recovery: a relay recorded but not printed before the process died. */
    void replay(InboundStore.Inbound in) {
        JsonObject req = Json.parseObject(in.messageData);
        try {
            if (req != null && Json.obj(req, "order") != null) {
                log.append("fcmInsights_", "Relay replayed after restart relayId=" + in.messageId);
                print(req);
            }
        } catch (RuntimeException e) {
            log.append("print_", "Exception:: relay replay failed relayId=" + in.messageId + ": " + e);
        } finally {
            inbound.markDone(in.key);
        }
    }

    private boolean stillPending(String key) {
        try {
            for (InboundStore.Inbound p : inbound.pending()) if (key.equals(p.key)) return true;
        } catch (RuntimeException ignored) {
            // treat as done
        }
        return false;
    }

    private int print(JsonObject req) {
        String kind = Json.str(req, "kind");
        JsonObject order = Json.obj(req, "order");
        RelayOrderHook h = hook;
        if (h != null) {
            try {
                JsonObject prepared = h.prepare(kind, order.deepCopy());
                if (prepared != null) order = prepared;
            } catch (Throwable t) {
                log.append("print_", "Exception:: relay order hook failed, printing the original: " + t);
            }
        }
        if (KOT.equals(kind)) {
            return pipeline.printRelayedKot(order, Json.str(req, "tableName"), Json.isTrueBoolean(req, "cancelled"));
        }
        if (EDIT_KOT.equals(kind)) return pipeline.printEditKot(order);
        if (RECEIPT.equals(kind)) {
            double surcharge = 0;
            try {
                String s = Json.str(req, "cardSurcharge");
                if (s != null) surcharge = Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                // 0
            }
            return pipeline.printReceipt(order, surcharge);
        }
        throw new IllegalArgumentException("Unknown relay kind " + kind);
    }

    private static JsonObject ok(int tickets) {
        JsonObject r = new JsonObject();
        r.addProperty("ok", true);
        r.addProperty("tickets", tickets);
        return r;
    }

    private static JsonObject error(String message) {
        JsonObject r = new JsonObject();
        r.addProperty("ok", false);
        r.addProperty("error", message);
        return r;
    }
}
