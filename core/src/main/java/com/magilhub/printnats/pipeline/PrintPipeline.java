package com.magilhub.printnats.pipeline;

import com.google.gson.JsonObject;
import com.magilhub.printnats.nats.InboundMessage;
import com.magilhub.printnats.nats.NatsEvents;
import com.magilhub.printnats.nats.StatusPublisher;
import com.magilhub.printnats.queue.JobKind;
import com.magilhub.printnats.queue.PrintJob;
import com.magilhub.printnats.queue.PrintQueue;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.rules.Json;
import com.magilhub.printnats.rules.KotPayloadBuilder;
import com.magilhub.printnats.rules.KotRouter;
import com.magilhub.printnats.rules.MessageRules;
import com.magilhub.printnats.rules.Restaurant;
import com.magilhub.printnats.rules.Session;
import com.magilhub.printnats.spi.InboundStore;
import com.magilhub.printnats.spi.LogSink;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NATS message → printed tickets.
 * <ol>
 *   <li>parse {messageType, messageData}; ignore other locations and messages without orderNo (legacy);</li>
 *   <li>publish "received" (before dedup, like legacy — every delivery is visible on the dashboard);</li>
 *   <li>dedup by message id in {@link InboundStore}; a duplicate is acked and dropped;</li>
 *   <li>record the message, THEN ack — the crash-safety point (unprocessed records are replayed by {@link #recover});</li>
 *   <li>on the pipeline thread: {@link MessageRules} → {@link KotPayloadBuilder} → {@link KotRouter} → {@link PrintQueue}.</li>
 * </ol>
 * Local prints from the host UI enter at {@link #printKot}/{@link #printEditKot}/{@link #printReceipt}.
 */
public final class PrintPipeline implements NatsEvents {
    /** Legacy dedupedPrintKOT: same order/batch/status/void within 2 s is a duplicate. */
    static final long KOT_DEDUPE_MS = 2000;
    static final long DEDUP_RETENTION_MS = 48L * 60 * 60 * 1000;

    public interface Listener {
        void onStatusEvent(String subject, byte[] data, boolean history);

        void onConnectionEvent(String type, String detail);
    }

    public interface PrinterSource {
        List<PrinterConfig> all();
    }

    private final InboundStore inbound;
    private final PrintQueue queue;
    private final PrinterSource printers;
    private final StatusPublisher status;
    private final LogSink log;
    private final Listener listener;
    private final MessageRulesFactory rulesFactory;
    private volatile Restaurant restaurant;
    private volatile Session session;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "print-pipeline");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Long> lastKotByOrder = new ConcurrentHashMap<>();
    /** orderId|sortOrder|V → when this device printed that KOT itself (host call). See suppressNatsKotAfterHostPrint. */
    private final Map<String, Long> hostPrintedKots = new ConcurrentHashMap<>();
    static final long HOST_PRINT_MEMORY_MS = 12L * 60 * 60 * 1000;
    private volatile boolean suppressNatsKotAfterHostPrint = false;
    /** orderId|sortOrder|V → when this (master) device printed a KOT a client relayed; always suppresses NATS copies. */
    private final Map<String, Long> relayedKots = new ConcurrentHashMap<>();
    private volatile PrintRelay relay;

    void setRelay(PrintRelay relay) {
        this.relay = relay;
    }

    /**
     * Hosts that print their own KOTs at order time (maghilOrder: orders are created on the device, online or
     * offline) set this so a later NATS/FCM KOT for the same order + batch — the backend re-sending it after sync —
     * is dropped instead of printing a second ticket. Off by default (MerchantApp behaviour unchanged).
     */
    public void setSuppressNatsKotAfterHostPrint(boolean suppress) {
        this.suppressNatsKotAfterHostPrint = suppress;
    }

    private static String hostKotKey(JsonObject order, boolean cancelled) {
        return orEmpty(Json.str(order, "orderId")) + "|" + orEmpty(Json.str(order, "sortOrder")) + (cancelled ? "|V" : "");
    }

    /** Builds MessageRules for the current restaurant/session (lets tests inject a fake order lookup). */
    public interface MessageRulesFactory {
        MessageRules create(Restaurant restaurant, Session session);
    }

    public PrintPipeline(InboundStore inbound, PrintQueue queue, PrinterSource printers, StatusPublisher status,
                         MessageRulesFactory rulesFactory, Restaurant restaurant, Session session, LogSink log,
                         Listener listener) {
        this.inbound = inbound;
        this.queue = queue;
        this.printers = printers;
        this.status = status;
        this.rulesFactory = rulesFactory;
        this.restaurant = restaurant;
        this.session = session;
        this.log = log == null ? LogSink.NONE : log;
        this.listener = listener;
    }

    public void setRestaurant(Restaurant restaurant) {
        this.restaurant = restaurant;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    // ---- NatsEvents -----------------------------------------------------------------------------------

    @Override
    public void onPrintMessage(InboundMessage m) {
        JsonObject body = Json.parseObject(new String(m.data(), StandardCharsets.UTF_8));
        String messageType = Json.str(body, "messageType");
        String rawData = Json.str(body, "messageData");
        JsonObject md = Json.parseObject(rawData);
        String messageId = m.messageId() != null ? m.messageId() : String.valueOf(m.streamSequence());
        String orderNo = Json.str(md, "orderNo");

        if (md == null || orderNo == null) {
            log.append("fcmInsights_", "NATS message without orderNo ignored, messageId=" + messageId);
            m.ack();
            return;
        }
        Session s = session;
        if (s.locationId != null && !s.locationId.equals(Json.str(md, "locationId"))) {
            log.append("fcmInsights_", "NATS message for another location ignored, messageId=" + messageId);
            m.ack();
            return;
        }
        if (MessageRules.publishesReceived(messageType, md)) publishReceived(messageType, md, messageId);

        InboundStore.Inbound in = new InboundStore.Inbound(orderNo + "|" + messageId, messageType, rawData, messageId,
                System.currentTimeMillis());
        boolean fresh;
        try {
            fresh = inbound.record(in);
        } catch (RuntimeException e) {
            log.append("fcmInsights_", "inbound store failed, nak for redelivery: " + e);
            m.nak();
            return;
        }
        m.ack(); // recorded (or already recorded) → safe to ack
        if (!fresh) {
            log.append("fcmInsights_", "Duplicate EVENT dropped orderNo=" + orderNo + " messageId=" + messageId);
            return;
        }
        log.append("fcmInsights_", "NATS EVENT accepted orderNo=" + orderNo + " messageId=" + messageId);
        submit(in);
    }

    /**
     * A print message that reached the host another way — FCM, which the backend uses for receipt requests
     * ({@code PRINT_RECEIPT} with orderStatus 60: never sent over NATS) and as a second copy of KOT messages.
     * Same persist + dedup as NATS: a KOT's FCM copy carries the NATS message id, so whichever copy arrives second
     * is dropped. Receipt requests have no orderNo (the order is fetched by orderId), so orderId is accepted here.
     *
     * @param messageId the message's own id ({@code data.messageId}), else the transport's (FCM message id)
     * @return true when accepted as new, false for duplicates / other location / unusable data
     */
    public boolean onHostMessage(String messageType, String rawData, String messageId) {
        JsonObject md = Json.parseObject(rawData);
        if (md == null || messageId == null || messageId.isEmpty()) {
            log.append("fcmInsights_", "Host " + messageType + " without data/messageId ignored");
            return false;
        }
        String ref = Json.str(md, "orderNo");
        if (ref == null) ref = Json.str(md, "orderId");
        if (ref == null) {
            log.append("fcmInsights_", "Host " + messageType + " without orderNo/orderId ignored, messageId=" + messageId);
            return false;
        }
        Session s = session;
        if (s.locationId != null && !s.locationId.equals(Json.str(md, "locationId"))) {
            log.append("fcmInsights_", "Host message for another location ignored, messageId=" + messageId);
            return false;
        }
        InboundStore.Inbound in = new InboundStore.Inbound(ref + "|" + messageId, messageType, rawData, messageId,
                System.currentTimeMillis());
        if (!inbound.record(in)) {
            log.append("fcmInsights_", "Duplicate host EVENT dropped ref=" + ref + " messageId=" + messageId);
            return false;
        }
        if (MessageRules.publishesReceived(messageType, md)) publishReceived(messageType, md, messageId);
        log.append("fcmInsights_", "Host EVENT accepted " + messageType + " ref=" + ref + " messageId=" + messageId);
        submit(in);
        return true;
    }

    @Override
    public void onStatusEvent(String subject, byte[] data) {
        if (listener != null) listener.onStatusEvent(subject, data, false);
    }

    @Override
    public void onStatusHistoryEvent(String subject, byte[] data) {
        if (listener != null) listener.onStatusEvent(subject, data, true);
    }

    @Override
    public void onConnectionEvent(String type, String detail) {
        log.append("nats_", type + " " + (detail == null ? "" : detail));
        if (listener != null) listener.onConnectionEvent(type, detail);
    }

    /** Replay messages recorded but not processed before a crash; prune the dedup window. */
    public void recover() {
        inbound.prune(System.currentTimeMillis() - DEDUP_RETENTION_MS);
        for (final InboundStore.Inbound in : inbound.pending()) {
            if (in.key != null && in.key.startsWith(PrintRelay.KEY_PREFIX)) {
                final PrintRelay r = relay;
                if (r == null) {
                    inbound.markDone(in.key);
                    continue;
                }
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        r.replay(in);
                    }
                });
                continue;
            }
            submit(in);
        }
    }

    public void shutdown() {
        worker.shutdown();
    }

    // ---- processing -----------------------------------------------------------------------------------

    private void submit(final InboundStore.Inbound in) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    process(in);
                } catch (RuntimeException e) {
                    log.append("print_", "Exception:: pipeline failed for " + in.key + ": " + e);
                } finally {
                    inbound.markDone(in.key);
                }
            }
        });
    }

    void process(InboundStore.Inbound in) {
        JsonObject md = Json.parseObject(in.messageData);
        MessageRules rules = rulesFactory.create(restaurant, session);
        List<MessageRules.PrintAction> actions = rules.handle(in.messageType, md, in.messageId);
        if (actions.isEmpty()) {
            log.append("log_", "PRINT SKIPPED — nothing to print for " + in.messageType + " orderNo=" + Json.str(md, "orderNo"));
        }
        for (MessageRules.PrintAction a : actions) {
            switch (a.kind) {
                case KOT:
                    enqueueKot(a.order, Json.str(a.order, "tableName"), a.isOrderCancelled, in.messageId, false);
                    break;
                case EDIT_KOT:
                    enqueueEditKot(a.order, in.messageId);
                    break;
                case RECEIPT:
                    enqueueReceipt(a.order, in.messageId, 0);
                    break;
                case FAILED:
                    publishReprintFailed(md, a.order, a.reason);
                    break;
                default:
                    break;
            }
        }
    }

    // ---- entry points (NATS and host UI) ----------------------------------------------------------------

    /** printKOT: build the payload, route master + stations, queue. Returns the number of tickets queued. */
    public int printKot(JsonObject order, String tableName, boolean isOrderCancelled) {
        return enqueueKot(order, tableName, isOrderCancelled, null, false);
    }

    /**
     * A KOT a client device relayed to this master: printed like a host-UI KOT and remembered, so the backend's
     * later NATS/FCM copy of the same order + batch is dropped (regardless of suppressNatsKotAfterHostPrint).
     */
    int printRelayedKot(JsonObject order, String tableName, boolean isOrderCancelled) {
        return enqueueKot(order, tableName, isOrderCancelled, null, true);
    }

    public int printEditKot(JsonObject order) {
        return enqueueEditKot(order, null);
    }

    public int printReceipt(JsonObject order) {
        return enqueueReceipt(order, null, 0);
    }

    /** Host-UI receipt with the card-processing surcharge from UI state (Redux cpSurchargeByOrder). */
    public int printReceipt(JsonObject order, double cardSurcharge) {
        return enqueueReceipt(order, null, cardSurcharge);
    }

    /**
     * A receipt whose printReceiptJson payload the host already built (QR receipts, shift summary, cash log) —
     * legacy PrintFramework.printReceiptJson(json, isTextReceiptPrint). Queued on the receipt printer.
     */
    public int printReceiptJson(String receiptJson, boolean textReceipt) {
        PrinterConfig receiptPrinter = receiptPrinter();
        if (receiptPrinter == null) {
            log.append("print_", "Info:: Receipt skipped — no receipt printer configured");
            return 0;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("receiptJson", receiptJson);
        payload.addProperty("textReceipt", textReceipt);
        return enqueueOnce(job(UUID.randomUUID() + "|" + receiptPrinter.id, JobKind.RECEIPT, receiptPrinter.id, payload));
    }

    /** End-of-day report on the receipt printer (legacy printEOD). {@code itemReport}: JSON is an ItemReport array. */
    public int printEod(String json, boolean itemReport) {
        PrinterConfig receiptPrinter = receiptPrinter();
        if (receiptPrinter == null) {
            log.append("print_", "Info:: EOD skipped — no receipt printer configured");
            return 0;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty(itemReport ? "itemReportsJson" : "eodJson", json);
        return enqueueOnce(job(UUID.randomUUID() + "|" + receiptPrinter.id, JobKind.EOD, receiptPrinter.id, payload));
    }

    private PrinterConfig receiptPrinter() {
        for (PrinterConfig p : printers.all()) {
            if (p.purpose == PrinterConfig.Purpose.RECEIPT) return p;
        }
        return null;
    }

    private int enqueueKot(JsonObject order, String tableName, boolean cancelled, String messageId, boolean relayed) {
        Restaurant r = restaurant;
        if (r.branchName() == null) {
            log.append("print_", "Info:: KOT skipped — restaurant branchName missing (legacy guard)");
            return 0;
        }
        String key = String.join("|", orEmpty(Json.str(order, "orderNo")), orEmpty(Json.str(order, "sortOrder")),
                orEmpty(Json.str(order, "orderId")), orEmpty(Json.str(order, "status")), cancelled ? "V" : "");
        // Legacy's 2 s window is keyed per order/batch (not per station), so it also swallowed a second
        // station's REPRINT_STATION_KOT arriving within 2 s. NATS messages are already deduped by message id,
        // so the window only guards host-UI prints (double taps) here.
        if (messageId == null) {
            long now = System.currentTimeMillis();
            Long last = lastKotByOrder.get(key);
            if (last != null && now - last < KOT_DEDUPE_MS) {
                log.append("print_", "Info:: KOT de-dupe skip — same order " + key + " re-fired within " + KOT_DEDUPE_MS + "ms");
                return 0;
            }
            lastKotByOrder.put(key, now);
            if (suppressNatsKotAfterHostPrint) hostPrintedKots.put(hostKotKey(order, cancelled), now);
            if (relayed && Json.str(order, "orderId") != null) relayedKots.put(hostKotKey(order, cancelled), now);
        } else {
            String hk = hostKotKey(order, cancelled);
            Long printedAt = suppressNatsKotAfterHostPrint ? hostPrintedKots.get(hk) : null;
            if (printedAt == null) printedAt = relayedKots.get(hk);
            if (printedAt != null && System.currentTimeMillis() - printedAt < HOST_PRINT_MEMORY_MS) {
                log.append("print_", "Info:: KOT skipped — already printed on this device for " + hostKotKey(order, cancelled)
                        + " (message " + messageId + ")");
                return 0;
            }
        }
        JsonObject payload = new KotPayloadBuilder(r, com.magilhub.printnats.rules.PrintDates.systemDefault())
                .kot(order, tableName, cancelled);
        if (payload == null) return 0;
        return route(payload, messageId, JobKind.KOT);
    }

    private int enqueueEditKot(JsonObject order, String messageId) {
        Restaurant r = restaurant;
        if (r.branchName() == null) return 0;
        JsonObject payload = new KotPayloadBuilder(r, com.magilhub.printnats.rules.PrintDates.systemDefault()).editKot(order);
        return route(payload, messageId, JobKind.KOT);
    }

    private int route(JsonObject payload, String messageId, JobKind kind) {
        if (Json.truthy(payload, "isFlushDB")) {
            int n = queue.flushAll();
            log.append("print_", "Info:: isFlushDB — flushed " + n + " queued/failed jobs");
        }
        List<KotRouter.Ticket> tickets = KotRouter.route(payload, printers.all());
        String base = messageId != null ? messageId : UUID.randomUUID().toString();
        int n = 0;
        for (KotRouter.Ticket t : tickets) {
            PrintJob job = job(base + "|" + t.printer.id, kind, t.printer.id, t.payload);
            job.isStation = t.isStation;
            n += enqueueOnce(job);
        }
        log.append("print_", "Info:: Print Initiated Or.No: " + Json.str(payload, "orderNo") + " tickets=" + n);
        return n;
    }

    /**
     * Client, master unreachable (no internet / master down): the receipt goes straight to the master's receipt
     * printer over LAN. 0 when this device has no {@link PrinterConfig.Purpose#MASTER_RECEIPT} row.
     */
    public int printReceiptOnMasterPrinter(JsonObject order, double cardSurcharge) {
        PrinterConfig p = null;
        for (PrinterConfig c : printers.all()) if (c.purpose == PrinterConfig.Purpose.MASTER_RECEIPT) p = c;
        if (p == null) return 0;
        log.append("print_", "Info:: Master unreachable — receipt printed directly on the master's receipt printer Or.No: "
                + Json.str(order, "orderNo"));
        return enqueueReceipt(order, null, cardSurcharge, p);
    }

    private int enqueueReceipt(JsonObject order, String messageId, double cardSurcharge) {
        return enqueueReceipt(order, messageId, cardSurcharge, receiptPrinter());
    }

    private int enqueueReceipt(JsonObject order, String messageId, double cardSurcharge, PrinterConfig receiptPrinter) {
        if (receiptPrinter == null) {
            log.append("print_", "Info:: Receipt skipped — no receipt printer configured, orderNo=" + Json.str(order, "orderNo"));
            return 0;
        }
        // printReceipt → printNetworkReceipt → optimizeReceiptData (ReceiptPayloadBuilder) → printReceiptJson payload
        com.magilhub.printnats.rules.ReceiptPayloadBuilder.Result built = new com.magilhub.printnats.rules.ReceiptPayloadBuilder(
                com.magilhub.printnats.rules.PrintDates.systemDefault()).build(order, restaurant, receiptServices.create(session, cardSurcharge));
        JsonObject payload = new JsonObject();
        payload.addProperty("receiptJson", built.json());
        payload.addProperty("textReceipt", built.textReceipt);
        Json.copy(order, payload, "orderId");
        Json.copy(order, payload, "orderNo");
        Json.copy(order, payload, "messageId");
        String base = messageId != null ? messageId : UUID.randomUUID().toString();
        return enqueueOnce(job(base + "|" + receiptPrinter.id, JobKind.RECEIPT, receiptPrinter.id, payload));
    }

    /** Creates the receipt services for a session (lets hosts/tests swap network + device facts). */
    public interface ReceiptServicesFactory {
        com.magilhub.printnats.rules.receipt.ReceiptServices create(Session session, double cardSurcharge);
    }

    private volatile ReceiptServicesFactory receiptServices = new ReceiptServicesFactory() {
        @Override
        public com.magilhub.printnats.rules.receipt.ReceiptServices create(Session s, double surcharge) {
            return com.magilhub.printnats.rules.receipt.ReceiptServices.NONE;
        }
    };

    public void setReceiptServices(ReceiptServicesFactory factory) {
        this.receiptServices = factory;
    }

    /** Deterministic job ids make crash-replay idempotent: a job already queued is not queued again. */
    private int enqueueOnce(PrintJob job) {
        if (queue.exists(job.jobId)) return 0;
        queue.enqueue(job);
        return 1;
    }

    private static PrintJob job(String jobId, JobKind kind, String printerId, JsonObject payload) {
        PrintJob j = new PrintJob();
        j.jobId = jobId;
        j.kind = kind;
        j.printerId = printerId;
        j.payloadJson = payload.toString();
        j.orderId = Json.str(payload, "orderId");
        j.orderNo = Json.str(payload, "orderNo");
        j.sortOrder = Json.str(payload, "sortOrder");
        j.kotNo = Json.str(payload, "kotNo");
        j.messageId = Json.str(payload, "messageId");
        return j;
    }

    // ---- status helpers -------------------------------------------------------------------------------

    private void publishReceived(String messageType, JsonObject md, String messageId) {
        JsonObject extra = new JsonObject();
        Session s = session;
        Json.put(extra, "appVersion", s.appVersion);
        Json.put(extra, "buildNumber", s.buildNumber);
        Json.copy(md, extra, "orderDate");
        Json.copy(md, extra, "orderTime");
        String station = Json.str(md, "printStation") != null ? Json.str(md, "printStation") : Json.str(md, "stationName");
        status.publishReceived(messageId, "REPRINT_STATION_KOT".equals(messageType) ? "ReprintKot" : "CreateKot",
                Json.str(md, "orderNo"), Json.str(md, "kotNo"), Json.str(md, "sortOrder"), station, extra);
    }

    /** publishReprintSkipped: a station reprint that cannot happen lands in the Failed Print Queue. */
    private void publishReprintFailed(JsonObject rd, JsonObject stationOrder, String reason) {
        String orderNo = stationOrder != null && Json.str(stationOrder, "orderNo") != null ? Json.str(stationOrder, "orderNo") : Json.str(rd, "orderNo");
        String kotNo = stationOrder != null && Json.str(stationOrder, "kotNo") != null ? Json.str(stationOrder, "kotNo") : Json.str(rd, "kotNo");
        status.publishFailed(Json.str(rd, "messageId"), Json.str(rd, "printStation"), "ReprintKot", reason, orderNo, kotNo,
                Json.str(rd, "orderId"), Json.str(rd, "sortOrder"), Json.str(rd, "cuisineId"));
        log.append("log_", "REPRINT_STATION_KOT SKIPPED — " + reason + " orderId=" + Json.str(rd, "orderId"));
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
