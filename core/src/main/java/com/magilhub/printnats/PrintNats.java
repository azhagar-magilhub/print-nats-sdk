package com.magilhub.printnats;

import com.google.gson.JsonObject;
import com.magilhub.printnats.nats.NatsClient;
import com.magilhub.printnats.nats.NatsConfig;
import com.magilhub.printnats.nats.StatusPublisher;
import com.magilhub.printnats.pipeline.InMemoryInboundStore;
import com.magilhub.printnats.pipeline.PrintPipeline;
import com.magilhub.printnats.queue.InMemoryJobStore;
import com.magilhub.printnats.queue.JobListener;
import com.magilhub.printnats.queue.PrintJob;
import com.magilhub.printnats.queue.PrintQueue;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.render.DefaultTicketRenderer;
import com.magilhub.printnats.rules.MessageRules;
import com.magilhub.printnats.rules.OrderLookup;
import com.magilhub.printnats.rules.Restaurant;
import com.magilhub.printnats.rules.Session;
import com.magilhub.printnats.rules.UrlConnectionHttpClient;
import com.magilhub.printnats.spi.DeviceState;
import com.magilhub.printnats.spi.HttpClient;
import com.magilhub.printnats.spi.InboundStore;
import com.magilhub.printnats.spi.JobStore;
import com.magilhub.printnats.spi.LogSink;
import com.magilhub.printnats.spi.PrinterTransport;
import com.magilhub.printnats.spi.ReceiptRenderer;
import com.magilhub.printnats.spi.StarEncoder;
import com.magilhub.printnats.spi.TicketRenderer;
import com.magilhub.printnats.transport.RoutingTransport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SDK entry point. Hosts (Android adapter, desktop sidecar, tests) supply platform pieces through the builder;
 * everything else — NATS, rules, routing, queue, templates, status events — lives here.
 *
 * <pre>
 * PrintNats sdk = PrintNats.builder().nats(natsConfig).session(session).restaurant(restaurantJson)
 *         .printers(printers).transport(transport).log(log).build();
 * sdk.start();
 * </pre>
 */
public final class PrintNats {
    public static final String VERSION = "0.1.0-SNAPSHOT";
    /** Finished jobs are kept for diagnosis this long (legacy log files are kept 3 days too). */
    static final long FINISHED_JOB_RETENTION_MS = 3L * 24 * 60 * 60 * 1000;

    /** Everything a UI may want to observe. All callbacks run on SDK threads. */
    public interface Listener extends JobListener, PrintPipeline.Listener,
            com.magilhub.printnats.discovery.PrinterRediscovery.AddressListener {
        /** A printer answered on a new IP (rediscovered by MAC); the host should save it to the backend. */
        @Override
        default void onPrinterAddressChanged(List<String> printerIds, String oldAddress, String newAddress) {
        }

        /** Core-NATS message on a subject subscribed with {@link #subscribeApp} (e.g. CartVue). */
        default void onAppMessage(String subject, byte[] data) {
        }
    }

    private final NatsClient nats;
    /** LAN mode, master only: the cloud connection (PRINTKOT + forwarding status to the cloud). */
    private final NatsClient cloud;
    static final String STATUS_BRIDGE_DURABLE = "statusbridge";
    private final PrintQueue queue;
    private final PrintPipeline pipeline;
    private final StatusPublisher status;
    private final OrderLookup orders;
    private final PrinterTransport transport;
    private final List<PrinterConfig> printers = new CopyOnWriteArrayList<>();
    private volatile Session session;
    private volatile boolean masterRole;
    private final LogSink logSink;
    private final com.magilhub.printnats.discovery.IpOverrides ipOverrides;
    private final TicketRenderer renderer;
    private final com.magilhub.printnats.discovery.PrinterRediscovery rediscovery;
    private final com.magilhub.printnats.pipeline.PrintRelay relay;
    private final boolean relayToMaster;
    private final String relaySubject;

    private PrintNats(Builder b) {
        this.session = b.session;
        this.ipOverrides = b.ipOverrides != null ? b.ipOverrides : new com.magilhub.printnats.discovery.IpOverrides();
        ipOverrides.apply(b.printers);
        this.printers.addAll(b.printers);
        LogSink log = b.log;
        this.logSink = log;
        this.masterRole = b.natsConfig != null && b.natsConfig.isMaster;
        final List<PrinterConfig> printerList = this.printers;
        PrintQueue.PrinterLookup lookup = new PrintQueue.PrinterLookup() {
            @Override
            public PrinterConfig get(String printerId) {
                for (PrinterConfig p : printerList) if (p.id.equals(printerId)) return p;
                return null;
            }

            /** Same role on the current printer list: receipt → receipt printer, Expo → master, station → same tag. */
            @Override
            public PrinterConfig replacementFor(PrintJob job) {
                if (job.kind != com.magilhub.printnats.queue.JobKind.KOT) {
                    for (PrinterConfig p : printerList) if (p.purpose == PrinterConfig.Purpose.RECEIPT) return p;
                    return null;
                }
                if (!job.isStation) {
                    for (PrinterConfig p : printerList) if (p.purpose == PrinterConfig.Purpose.MASTER_KOT) return p;
                    return null;
                }
                int hash = job.printerId == null ? -1 : job.printerId.lastIndexOf('#');
                String tag = hash < 0 ? null : job.printerId.substring(hash + 1);
                if (tag == null || tag.isEmpty()) return null;
                for (PrinterConfig p : printerList) if (tag.equals(p.cuisineId)) return p;
                return null;
            }
        };
        TicketRenderer renderer = new DefaultTicketRenderer(b.starEncoder, b.receiptRenderer, log);
        this.renderer = renderer;
        this.transport = b.transport;
        this.queue = new PrintQueue(b.jobStore, lookup, renderer, b.transport, log);
        this.orders = new OrderLookup(b.http, b.session, log);

        final PrintNats self = this;
        final Listener listener = b.listener;
        final PipelineHolder holder = new PipelineHolder();
        if (b.natsConfig != null && b.natsConfig.lanMode) b.natsConfig.consumePrintKot = false;
        this.nats = b.natsConfig == null ? null : new NatsClient(b.natsConfig, new com.magilhub.printnats.nats.NatsEvents() {
            @Override
            public void onPrintMessage(com.magilhub.printnats.nats.InboundMessage message) {
                holder.pipeline.onPrintMessage(message);
            }

            @Override
            public void onStatusEvent(String subject, byte[] data) {
                holder.pipeline.onStatusEvent(subject, data);
            }

            @Override
            public void onStatusHistoryEvent(String subject, byte[] data) {
                holder.pipeline.onStatusHistoryEvent(subject, data);
            }

            @Override
            public void onConnectionEvent(String type, String detail) {
                holder.pipeline.onConnectionEvent(type, detail);
                if (isConnectEvent(type, detail) && holder.queue != null) holder.queue.kickRelay(); // master may be back
            }

            @Override
            public void onAppMessage(String subject, byte[] data) {
                if (listener != null) listener.onAppMessage(subject, data);
            }
        }, log, b.outboxStore);
        this.cloud = b.natsConfig == null || !b.natsConfig.lanMode || b.natsConfig.cloudServerUrls == null
                || b.natsConfig.cloudServerUrls.isEmpty() ? null
                : new NatsClient(b.natsConfig.cloudCopy(), new com.magilhub.printnats.nats.NatsEvents() {
            @Override
            public void onPrintMessage(com.magilhub.printnats.nats.InboundMessage message) {
                holder.pipeline.onPrintMessage(message); // online orders from the backend
            }

            @Override
            public void onStatusEvent(String subject, byte[] data) {
            }

            @Override
            public void onStatusHistoryEvent(String subject, byte[] data) {
            }

            @Override
            public void onConnectionEvent(String type, String detail) {
                holder.pipeline.onConnectionEvent("cloud_" + type, detail);
            }

            @Override
            public void onAppMessage(String subject, byte[] data) {
            }
        }, log, b.cloudOutboxStore);
        this.status = new StatusPublisher(nats == null ? new NatsClient(new NatsConfig(), null, log) : nats, lookup, log,
                b.deviceState, b.session.locationId, b.session.deviceId);
        holder.queue = queue;
        queue.addListener(status);
        if (listener != null) queue.addListener(listener);
        this.pipeline = new PrintPipeline(b.inboundStore, queue, new PrintPipeline.PrinterSource() {
            @Override
            public List<PrinterConfig> all() {
                return new ArrayList<>(printerList);
            }
        }, status, new PrintPipeline.MessageRulesFactory() {
            @Override
            public MessageRules create(Restaurant restaurant, Session s) {
                return new MessageRules(self.orders, restaurant, s);
            }
        }, new Restaurant(b.restaurant), b.session, log, listener);
        holder.pipeline = pipeline;
        pipeline.setSuppressNatsKotAfterHostPrint(b.suppressNatsKotAfterHostPrint);

        // ---- master/client print relay ----
        this.relayToMaster = b.relayToMaster;
        this.relay = new com.magilhub.printnats.pipeline.PrintRelay(pipeline, b.inboundStore, log);
        relay.setHook(b.relayOrderHook);
        this.relaySubject = com.magilhub.printnats.pipeline.PrintRelay.subject(
                b.natsConfig != null && b.natsConfig.locationId != null ? b.natsConfig.locationId : b.session.locationId);
        final com.magilhub.printnats.pipeline.PrintRelay relayRef = relay;
        final NatsClient natsRef = nats;
        final String subjectRef = relaySubject;
        queue.setRelayTransport(new com.magilhub.printnats.transport.RelayTransport(
                new com.magilhub.printnats.transport.RelayTransport.Link() {
                    @Override
                    public boolean isMaster() {
                        return self.masterRole;
                    }

                    @Override
                    public boolean isOnline() {
                        return natsRef != null && natsRef.isConnected();
                    }

                    @Override
                    public byte[] request(byte[] body, long timeoutMs) {
                        return natsRef == null ? null : natsRef.request(subjectRef, body, timeoutMs);
                    }

                    @Override
                    public byte[] printLocally(byte[] body) {
                        return relayRef.handle(body);
                    }
                }, com.magilhub.printnats.transport.RelayTransport.DEFAULT_TIMEOUT_MS, log));
        // Every master answers relays (even hosts that don't relay themselves); subscribed only while master.
        if (nats != null) {
            nats.serveWhileMaster(relaySubject, new NatsClient.RequestHandler() {
                @Override
                public byte[] handle(byte[] request) {
                    return relayRef.handle(request);
                }
            });
        }

        final com.magilhub.printnats.discovery.PrinterRediscovery.AddressListener hostHook = b.addressHook;
        this.rediscovery = new com.magilhub.printnats.discovery.PrinterRediscovery(
                new com.magilhub.printnats.discovery.PrinterRediscovery.Host() {
                    @Override
                    public List<PrinterConfig> printers() {
                        return new ArrayList<>(printerList);
                    }

                    @Override
                    public boolean retry(String jobId) {
                        return queue.retry(jobId);
                    }

                    @Override
                    public List<PrintJob> failedJobs() {
                        return queue.failedJobs();
                    }
                },
                b.macLocator != null ? b.macLocator : new com.magilhub.printnats.discovery.MacLocator(
                        b.arpTable != null ? b.arpTable : new com.magilhub.printnats.discovery.SystemArpTable(), log),
                ipOverrides,
                new com.magilhub.printnats.discovery.PrinterRediscovery.AddressListener() {
                    @Override
                    public void onPrinterAddressChanged(List<String> ids, String oldAddress, String newAddress) {
                        if (hostHook != null) hostHook.onPrinterAddressChanged(ids, oldAddress, newAddress);
                        if (listener != null) listener.onPrinterAddressChanged(ids, oldAddress, newAddress);
                    }
                }, log);
        queue.addListener(rediscovery);
        final HttpClient http = b.http;
        final LogSink receiptLog = log;
        final Boolean hostDataCap = b.dataCapDevice;
        final DeviceState deviceState = b.deviceState;
        pipeline.setReceiptServices(new PrintPipeline.ReceiptServicesFactory() {
            @Override
            public com.magilhub.printnats.rules.receipt.ReceiptServices create(Session s, double surcharge) {
                boolean dataCap = s.isDataCapDevice != null ? s.isDataCapDevice : hostDataCap != null && hostDataCap;
                return new com.magilhub.printnats.rules.receipt.HttpReceiptServices(http, s, dataCap, surcharge, receiptLog, deviceState);
            }
        });
    }

    private static final class PipelineHolder {
        volatile PrintPipeline pipeline;
        volatile PrintQueue queue;
    }

    /** A fresh connect, a jnats transparent reconnect, or a master device announcing it serves relays. */
    static boolean isConnectEvent(String type, String detail) {
        if ("connected".equals(type) || "relay_master_online".equals(type)) return true;
        if (!"connection_event".equals(type) || detail == null) return false;
        String d = detail.toLowerCase(java.util.Locale.ROOT);
        return d.contains("reconnected") || d.contains("resubscribed");
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    /** Recover unfinished work, then connect to NATS. */
    public void start() {
        wakePrinters();
        queue.pruneFinished(System.currentTimeMillis() - FINISHED_JOB_RETENTION_MS);
        queue.recover();
        pipeline.recover();
        if (nats != null) nats.start();
        if (cloud != null) {
            cloud.start();
            startStatusBridge();
        }
    }

    /**
     * Master in LAN mode: every device publishes print status to the shop's local server; forward each one to the
     * cloud PRINTEVENTSTATUS stream. Acked once handed to the cloud connection, which buffers (outbox) while the
     * internet is out, so nothing is lost and the local ack floor keeps moving.
     */
    private void startStatusBridge() {
        final NatsClient cloudRef = cloud;
        final NatsClient localRef = nats;
        try {
            nats.startDurable(nats.config().statusStreamName, STATUS_BRIDGE_DURABLE,
                    "printeventstatus." + nats.config().locationId + ".>", true,
                    new com.magilhub.printnats.nats.DurableHandler() {
                        @Override
                        public boolean onMessage(com.magilhub.printnats.nats.DurableMessage m) {
                            cloudRef.publish(m.subject, m.data); // confirmed now, or kept in the cloud outbox
                            localRef.ackDurable(m.token);
                            return true;
                        }
                    });
        } catch (Exception e) {
            if (logSink != null) logSink.append("nats_", "Status bridge start failed: " + e);
        }
    }

    /** LAN mode, master: the cloud connection is up. */
    public boolean isCloudConnected() {
        return cloud != null && cloud.isConnected();
    }

    public boolean hasCloudLink() {
        return cloud != null;
    }

    public void stop() {
        if (cloud != null) cloud.stop();
        if (nats != null) nats.stop();
        pipeline.shutdown();
        queue.shutdown();
        rediscovery.shutdown();
        status.shutdown();
    }

    // ---- configuration --------------------------------------------------------------------------------

    /** New restaurantDetails JSON (feature flags, templates, order types…). */
    public void setRestaurant(JsonObject restaurantDetails) {
        pipeline.setRestaurant(new Restaurant(restaurantDetails));
    }

    public void setPrinters(List<PrinterConfig> list) {
        ipOverrides.apply(list); // a rediscovered IP stays until the backend list reports a different one
        printers.clear();
        printers.addAll(list);
    }

    /** Printer IP rediscovery (on by default for receipts, like legacy). */
    public com.magilhub.printnats.discovery.PrinterRediscovery rediscovery() {
        return rediscovery;
    }

    /** Rediscovered IPs not yet confirmed by the backend device list (hosts persist this). */
    public com.magilhub.printnats.discovery.IpOverrides ipOverrides() {
        return ipOverrides;
    }

    public List<PrinterConfig> printers() {
        return Collections.unmodifiableList(new ArrayList<>(printers));
    }

    public void setSession(Session s) {
        this.session = s;
        orders.setSession(s);
        pipeline.setSession(s);
    }

    /** Manual override; prefer {@link #setDevices} so the role follows the backend device list. */
    public void updateMasterRole(boolean isMaster) {
        boolean changed = masterRole != isMaster;
        masterRole = isMaster;
        if (nats != null) nats.updateMasterRole(isMaster);
        if (changed) queue.kickRelay(); // new master → print own waiting relays locally; new client → try the master
    }

    public boolean isMaster() {
        return masterRole;
    }

    /**
     * New backend device list (after PRINTER_CONFIG_UPDATE or a periodic refresh): re-derives printer rows AND the
     * master role, switching the status-subscription scope immediately if the role changed. Empty list → no-op.
     */
    public void setDevices(com.google.gson.JsonArray devices, JsonObject restaurantDetails) {
        if (devices == null || devices.size() == 0) return;
        com.magilhub.printnats.rules.Restaurant r = new com.magilhub.printnats.rules.Restaurant(restaurantDetails);
        List<PrinterConfig> derived = com.magilhub.printnats.rules.DeviceList.printers(devices, session.deviceId, r);
        if (derived != null) setPrinters(derived);
        boolean master = com.magilhub.printnats.rules.DeviceList.isMaster(devices, session.deviceId);
        if (master != masterRole) {
            masterRole = master;
            if (nats != null) nats.updateMasterRole(master);
            if (logSink != null) logSink.append("nats_", "Master role changed → " + (master ? "MASTER" : "client"));
            queue.kickRelay();
        }
    }

    // ---- printing from the host UI ----------------------------------------------------------------------

    /**
     * KOT from the host UI. With {@link Builder#relayToMaster} on and this device not the master, the KOT is NOT
     * printed here: one durable relay job (printer {@link PrinterConfig#RELAY_MASTER_ID}) hands it to the master
     * device, retrying until the master takes it. Returns the tickets queued (1 for a relay job).
     */
    public int printKot(JsonObject orderDetails, String tableName, boolean isOrderCancelled) {
        orderDetails = asHostKot(orderDetails);
        if (relayToMaster && !masterRole) {
            return enqueueRelay(com.magilhub.printnats.pipeline.PrintRelay.KOT, orderDetails, tableName, isOrderCancelled);
        }
        return pipeline.printKot(orderDetails, tableName, isOrderCancelled);
    }

    public int printEditKot(JsonObject orderDetails) {
        orderDetails = asHostKot(orderDetails);
        if (relayToMaster && !masterRole) {
            return enqueueRelay(com.magilhub.printnats.pipeline.PrintRelay.EDIT_KOT, orderDetails, null, false);
        }
        return pipeline.printEditKot(orderDetails);
    }

    /**
     * A KOT the host UI sends is a new / edited / cancelled ticket, never a reprint: without {@code isAutoPrint} the
     * renderers read false and print "REPRINTED" on every ticket (seen on desktop, where nothing upstream set it).
     * Reprints arrive as REPRINT_STATION_KOT, which sets the flag explicitly (MessageRules). Copies the order.
     */
    static JsonObject asHostKot(JsonObject order) {
        if (order == null || order.has("isAutoPrint")) return order;
        JsonObject copy = order.deepCopy();
        copy.addProperty("isAutoPrint", true);
        return copy;
    }

    private int enqueueRelay(String kind, JsonObject order, String tableName, boolean cancelled) {
        JsonObject req = com.magilhub.printnats.pipeline.PrintRelay.request(kind, order, tableName, cancelled, 0,
                session.deviceId);
        PrintJob j = new PrintJob();
        j.jobId = "relay|" + com.magilhub.printnats.rules.Json.str(req, "relayId");
        j.kind = com.magilhub.printnats.queue.JobKind.KOT;
        j.printerId = PrinterConfig.RELAY_MASTER_ID;
        j.isStation = false;
        j.payloadJson = req.toString();
        j.orderId = com.magilhub.printnats.rules.Json.str(order, "orderId");
        j.orderNo = com.magilhub.printnats.rules.Json.str(order, "orderNo");
        j.sortOrder = com.magilhub.printnats.rules.Json.str(order, "sortOrder");
        j.kotNo = com.magilhub.printnats.rules.Json.str(order, "kotNo");
        j.source = "relay";
        queue.enqueue(j);
        if (logSink != null) {
            logSink.append("print_", "Info:: " + kind + " relayed to master device Or.No: " + j.orderNo + " job=" + j.jobId);
        }
        return 1;
    }

    /**
     * Receipt printed by the master device (client without its own receipt printer): one synchronous request, no
     * queue. On the master itself this prints locally. Returns the tickets the master queued, or -1 when no master
     * answered within {@code timeoutMs} (or it reported an error) — the host then falls back (FCM PRINT_RECEIPT).
     */
    public int relayReceipt(JsonObject orderDetails, double cardSurcharge, long timeoutMs) {
        if (masterRole) return pipeline.printReceipt(orderDetails, cardSurcharge);
        if (nats == null || !nats.isConnected()) {
            // no internet / modem off: straight to the master's receipt printer over LAN
            int direct = pipeline.printReceiptOnMasterPrinter(orderDetails, cardSurcharge);
            return direct > 0 ? direct : -1;
        }
        JsonObject req = com.magilhub.printnats.pipeline.PrintRelay.request(com.magilhub.printnats.pipeline.PrintRelay.RECEIPT,
                orderDetails, null, false, cardSurcharge, session.deviceId);
        byte[] reply = nats.request(relaySubject, req.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), timeoutMs);
        JsonObject r = reply == null ? null
                : com.magilhub.printnats.rules.Json.parseObject(new String(reply, java.nio.charset.StandardCharsets.UTF_8));
        if (r == null) {
            // connected but the master tablet didn't answer (down / off Wi-Fi): its receipt printer may still be up
            int direct = pipeline.printReceiptOnMasterPrinter(orderDetails, cardSurcharge);
            if (direct > 0) return direct;
        }
        if (r == null || !com.magilhub.printnats.rules.Json.isTrueBoolean(r, "ok")) {
            if (logSink != null) {
                logSink.append("print_", "Info:: Receipt relay to master failed Or.No: " + com.magilhub.printnats.rules.Json.str(orderDetails, "orderNo")
                        + (r == null ? " (no master answered)" : " " + com.magilhub.printnats.rules.Json.str(r, "error")));
            }
            return -1;
        }
        String tickets = com.magilhub.printnats.rules.Json.str(r, "tickets");
        try {
            return tickets == null ? 0 : (int) Double.parseDouble(tickets);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** This device has a receipt printer row (the TAB's receiptPrinterId, or an explicit RECEIPT row). */
    public boolean hasReceiptPrinter() {
        return receiptPrinter() != null;
    }

    /** Master-side hook for relayed orders (e.g. assign the KOT number). null clears it. */
    public void setRelayOrderHook(com.magilhub.printnats.pipeline.RelayOrderHook hook) {
        relay.setHook(hook);
    }

    public boolean isRelayToMaster() {
        return relayToMaster;
    }

    public int printReceipt(JsonObject orderDetails) {
        return pipeline.printReceipt(orderDetails);
    }

    /** With the card-processing surcharge the UI holds for this order (Redux cpSurchargeByOrder). */
    public int printReceipt(JsonObject orderDetails, double cardSurcharge) {
        return pipeline.printReceipt(orderDetails, cardSurcharge);
    }

    // ---- cash drawer / printer health ------------------------------------------------------------------

    /** DantSu openCashBox — what legacy PrintFramework.openCashDrawer sends to the receipt printer. */
    static final byte[] CASH_DRAWER_PULSE = {0x1B, 0x70, 0x00, 0x3C, (byte) 0xFF};

    /**
     * Open the cash drawer on the receipt printer right away (not queued, not retried, no status polling) —
     * legacy PrintFramework.openCashDrawer, used by every cash-payment screen.
     */
    public com.magilhub.printnats.queue.PrintResult openCashDrawer() {
        PrinterConfig receipt = receiptPrinter();
        if (receipt == null) {
            if (logSink != null) logSink.append("print_", "Error:: Cash Drawer - No Cash Drawer Configured");
            return new com.magilhub.printnats.queue.PrintResult(com.magilhub.printnats.queue.PrintOutcome.FAULT,
                    "Please configure the Printer.");
        }
        com.magilhub.printnats.queue.PrintResult r = transport instanceof RoutingTransport
                ? ((RoutingTransport) transport).sendDirect(receipt, CASH_DRAWER_PULSE)
                : transport.send(receipt, CASH_DRAWER_PULSE);
        if (logSink != null) {
            logSink.append("print_", r.outcome == com.magilhub.printnats.queue.PrintOutcome.SUCCESS
                    ? "Info:: Cash Drawer Opened Successfully " : "Error:: Cash Drawer Error " + r.message);
        }
        return r;
    }

    /** Health of one printer row (null when unknown id). */
    public com.magilhub.printnats.queue.PrinterHealth printerStatus(String printerId) {
        for (PrinterConfig p : printers) {
            if (p.id.equals(printerId)) return probe(p);
        }
        return null;
    }

    /** Health of every PHYSICAL printer (one probe per connection+address, reported for each row). */
    public List<com.magilhub.printnats.queue.PrinterHealth> printerStatuses() {
        java.util.Map<String, com.magilhub.printnats.queue.PrinterHealth> byLane = new java.util.LinkedHashMap<>();
        List<com.magilhub.printnats.queue.PrinterHealth> out = new ArrayList<>();
        for (PrinterConfig p : printers) {
            com.magilhub.printnats.queue.PrinterHealth h = byLane.get(p.laneKey());
            if (h == null) byLane.put(p.laneKey(), h = probe(p));
            com.magilhub.printnats.queue.PrinterHealth row = com.magilhub.printnats.queue.PrinterHealth.of(p.id, h.reachable, h.ready, h.message);
            row.statusSupported = h.statusSupported;
            out.add(row);
        }
        return out;
    }

    /**
     * Legacy wakeConfiguredPrinters: a throwaway status query to each LAN printer so a Wi-Fi printer's radio is
     * awake before the first real (300 ms connect) print. Fire-and-forget; also run by {@link #start()}.
     */
    public void wakePrinters() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (PrinterConfig p : printers) {
                    if (p.connection != PrinterConfig.Connection.LAN || !seen.add(p.laneKey())) continue;
                    com.magilhub.printnats.queue.PrinterHealth h = probe(p);
                    if (logSink != null) logSink.append("print_", "WAKE:: " + p.address + " (" + p.resolvedStationName() + ") reachable="
                            + h.reachable + (h.message == null ? "" : " " + h.message));
                }
            }
        }, "print-nats-wake");
        t.setDaemon(true);
        t.start();
    }

    private com.magilhub.printnats.queue.PrinterHealth probe(PrinterConfig p) {
        try {
            if (transport instanceof com.magilhub.printnats.spi.PrinterProbe) {
                return ((com.magilhub.printnats.spi.PrinterProbe) transport).probe(p);
            }
        } catch (RuntimeException e) {
            return com.magilhub.printnats.queue.PrinterHealth.of(p.id, false, false, "Printer status check failed: " + e.getMessage());
        }
        com.magilhub.printnats.queue.PrinterHealth h = com.magilhub.printnats.queue.PrinterHealth.of(p.id, true, true, null);
        h.statusSupported = false;
        return h;
    }

    private PrinterConfig receiptPrinter() {
        for (PrinterConfig p : printers) if (p.purpose == PrinterConfig.Purpose.RECEIPT) return p;
        return null;
    }

    /**
     * Print message received by the host outside NATS (FCM): receipt requests only ever arrive that way, KOTs arrive
     * on both and are deduplicated against the NATS copy. See {@link PrintPipeline#onHostMessage}.
     */
    public boolean submitMessage(String messageType, String messageDataJson, String messageId) {
        return pipeline.onHostMessage(messageType, messageDataJson, messageId);
    }

    /**
     * Test print on one printer (legacy PrintFramework.testPrint): a short sample ticket through the same renderer and
     * transport as real KOTs — Star printers get Star commands via StarIO, thermal printers ESC/POS — sent directly
     * (not queued, never in the Failed Print Queue). The printer need not be in the configured list (Setup form).
     */
    public com.magilhub.printnats.queue.PrintResult testPrint(PrinterConfig printer) {
        long now = System.currentTimeMillis();
        PrintJob job = new PrintJob();
        job.jobId = "test-" + now;
        job.kind = com.magilhub.printnats.queue.JobKind.TEST;
        job.printerId = printer.id;
        job.isStation = false;
        job.payloadJson = testTicket(printer, now).toString();
        com.magilhub.printnats.render.RenderResult rendered;
        try {
            rendered = renderer.render(job, printer, now);
        } catch (RuntimeException e) {
            return new com.magilhub.printnats.queue.PrintResult(com.magilhub.printnats.queue.PrintOutcome.FAULT, "Test print render failed: " + e);
        }
        if (rendered.isSkipped()) {
            return new com.magilhub.printnats.queue.PrintResult(com.magilhub.printnats.queue.PrintOutcome.FAULT, "Test print skipped: " + rendered.skipReason);
        }
        com.magilhub.printnats.queue.PrintResult r = transport.send(printer, rendered.bytes);
        if (logSink != null) {
            logSink.append("test_Print_", "Test print " + printer.address + " star=" + printer.isStar + " → " + r.outcome
                    + (r.message == null ? "" : " " + r.message));
        }
        return r;
    }

    static JsonObject testTicket(PrinterConfig printer, long now) {
        java.util.Date d = new java.util.Date(now);
        JsonObject r = new JsonObject();
        r.addProperty("templateNo", "3");
        r.addProperty("currentDate", new java.text.SimpleDateFormat("MM/dd/yy", java.util.Locale.US).format(d));
        r.addProperty("currentTime", new java.text.SimpleDateFormat("hh:mm a", java.util.Locale.US).format(d));
        r.addProperty("currentFormattedDate", new java.text.SimpleDateFormat("ddMMMhh:mma", java.util.Locale.US).format(d));
        r.addProperty("orderNo", "TEST");
        r.addProperty("orderDate", new java.text.SimpleDateFormat("MM/dd/yyyy", java.util.Locale.US).format(d));
        r.addProperty("orderTime", new java.text.SimpleDateFormat("hh:mm:ss a", java.util.Locale.US).format(d));
        r.addProperty("orderTypeGroup", "Test Print");
        r.addProperty("orderType", "T");
        r.addProperty("isAutoPrint", true);
        r.addProperty("isPaymentDone", true);
        r.addProperty("kotFont", "2");
        r.addProperty("kotFontStyle", "");
        r.addProperty("kotAlignmenet", "TEXT_ALIGN_LEFT");
        r.addProperty("showUpperCaseItemName", "true");
        r.addProperty("showStationName", "true");
        r.addProperty("showBatchNote", "false");
        r.addProperty("stationName", printer.resolvedStationName());
        com.google.gson.JsonArray items = new com.google.gson.JsonArray();
        String[] lines = {"Printer test OK", (printer.name == null || printer.name.isEmpty() ? "Printer" : printer.name),
                printer.address == null ? "" : printer.address};
        for (String line : lines) {
            if (line.isEmpty()) continue;
            JsonObject it = new JsonObject();
            it.addProperty("quantity", "1");
            it.addProperty("itemName", line);
            it.addProperty("categoryName", "Test");
            items.add(it);
        }
        r.add("items", items);
        JsonObject footer = new JsonObject();
        footer.addProperty("line1", "Test print");
        r.add("footer", footer);
        return r;
    }

    /** Pre-built receipt JSON (legacy printReceiptJson). */
    public int printReceiptJson(String receiptJson, boolean textReceipt) {
        return pipeline.printReceiptJson(receiptJson, textReceipt);
    }

    /** End-of-day report JSON (legacy printEOD). */
    public int printEod(String eodJson) {
        return pipeline.printEod(eodJson, false);
    }

    // ---- Failed Print Queue -----------------------------------------------------------------------------

    public List<PrintJob> failedJobs() {
        return queue.failedJobs();
    }

    public boolean retry(String jobId) {
        return queue.retry(jobId);
    }

    public boolean cancel(String jobId, String staffName) {
        return queue.cancel(jobId, staffName);
    }

    public int retryAllForPrinter(String printerId) {
        return queue.retryAllForPrinter(printerId);
    }

    public int cancelAllForPrinter(String printerId, String staffName) {
        return queue.cancelAllForPrinter(printerId, staffName);
    }

    /** Advanced tuning (breaker, manual-retry budget) — see docs/parity-matrix.md. */
    public PrintQueue queue() {
        return queue;
    }

    // ---- app messaging (core NATS) -----------------------------------------------------------------------

    /** Fire-and-forget publish on any subject (e.g. {@code cartvue.<loc>.<device>}); briefly buffered offline. */
    public boolean publishApp(String subject, byte[] data) {
        return nats != null && nats.publishCore(subject, data);
    }

    /** Receive messages on {@code subject} via {@link Listener#onAppMessage}; survives reconnects. */
    public void subscribeApp(String subject) {
        if (nats != null) nats.subscribeApp(subject);
    }

    public void unsubscribeApp(String subject) {
        if (nats != null) nats.unsubscribeApp(subject);
    }

    public boolean isNatsConnected() {
        return nats != null && nats.isConnected();
    }

    // ---- acknowledged app sync (host JetStream streams + durable consumers) ------------------------------

    private NatsClient requireNats() {
        if (nats == null) throw new IllegalStateException("NATS not configured");
        return nats;
    }

    /** Idempotent create / update (File storage, 2-min Nats-Msg-Id dedup window); re-applied on every connect. */
    public void ensureStream(String name, List<String> subjects, long maxAgeMs) throws Exception {
        requireNats().ensureStream(name, subjects, maxAgeMs);
    }

    /** JetStream publish with Nats-Msg-Id; the PubAck stream sequence. Throws when not connected (no buffering). */
    public long publishDurable(String subject, byte[] data, String msgId) throws Exception {
        return requireNats().publishDurable(subject, data, msgId);
    }

    /** Push durable consumer, explicit ack; kept across reconnects; idempotent. See {@link NatsClient#startDurable}. */
    public void startDurable(String stream, String durable, String filterSubject,
                             com.magilhub.printnats.nats.DurableHandler handler) throws Exception {
        requireNats().startDurable(stream, durable, filterSubject, handler);
    }

    /** As above; {@code deliverNew}: a consumer created now starts at the stream tail. */
    public void startDurable(String stream, String durable, String filterSubject, boolean deliverNew,
                             com.magilhub.printnats.nats.DurableHandler handler) throws Exception {
        requireNats().startDurable(stream, durable, filterSubject, deliverNew, handler);
    }

    public void stopDurable(String durable) {
        if (nats != null) nats.stopDurable(durable);
    }

    /** False for an unknown / stale token (JetStream redelivers after ackWait). */
    public boolean ackDurable(String token) {
        return nats != null && nats.ackDurable(token);
    }

    public boolean nakDurable(String token, long delayMs) {
        return nats != null && nats.nakDurable(token, delayMs);
    }

    public boolean termDurable(String token) {
        return nats != null && nats.termDurable(token);
    }

    /** null when the consumer doesn't exist. */
    public com.magilhub.printnats.nats.ConsumerStats consumerInfo(String stream, String durable) throws Exception {
        return requireNats().consumerInfo(stream, durable);
    }

    public List<com.magilhub.printnats.nats.ConsumerStats> listConsumers(String stream) throws Exception {
        return requireNats().listConsumers(stream);
    }

    public boolean deleteConsumer(String stream, String durable) throws Exception {
        return requireNats().deleteConsumer(stream, durable);
    }

    // ---- builder --------------------------------------------------------------------------------------

    public static final class Builder {
        private NatsConfig natsConfig;
        private Session session = new Session();
        private JsonObject restaurant = new JsonObject();
        private final List<PrinterConfig> printers = new ArrayList<>();
        private JobStore jobStore = new InMemoryJobStore();
        private InboundStore inboundStore = new InMemoryInboundStore();
        private PrinterTransport transport;
        private StarEncoder starEncoder;
        private ReceiptRenderer receiptRenderer;
        private HttpClient http = new UrlConnectionHttpClient(60_000);
        private LogSink log = LogSink.NONE;
        private DeviceState deviceState = DeviceState.ALWAYS_ONLINE;
        private Listener listener;
        private Boolean dataCapDevice;
        private com.magilhub.printnats.spi.OutboxStore outboxStore;
        private com.magilhub.printnats.spi.OutboxStore cloudOutboxStore;
        private com.magilhub.printnats.discovery.IpOverrides ipOverrides;
        private com.magilhub.printnats.spi.ArpTable arpTable;
        private com.magilhub.printnats.discovery.MacLocator macLocator;
        private com.magilhub.printnats.discovery.PrinterRediscovery.AddressListener addressHook;

        private boolean suppressNatsKotAfterHostPrint;
        private boolean relayToMaster;
        private com.magilhub.printnats.pipeline.RelayOrderHook relayOrderHook;

        /**
         * Only the master device prints: on a client, {@link PrintNats#printKot}/{@link PrintNats#printEditKot} hand
         * the order to the master over NATS (durable relay job, retried until the master takes it). Default false
         * (MerchantApp: clients' KOTs reach the master via the backend).
         */
        public Builder relayToMaster(boolean relay) {
            this.relayToMaster = relay;
            return this;
        }

        /** See {@link PrintNats#setRelayOrderHook}. */
        public Builder relayOrderHook(com.magilhub.printnats.pipeline.RelayOrderHook hook) {
            this.relayOrderHook = hook;
            return this;
        }

        /** Drop NATS/FCM KOTs for an order + batch this device already printed itself (maghilOrder). */
        public Builder suppressNatsKotAfterHostPrint(boolean suppress) {
            this.suppressNatsKotAfterHostPrint = suppress;
            return this;
        }

        /** Rediscovered IPs restored from the host's saved config. */
        public Builder ipOverrides(com.magilhub.printnats.discovery.IpOverrides o) {
            this.ipOverrides = o;
            return this;
        }

        /** ARP cache reader for IP rediscovery (default: /proc/net/arp, ip neigh, arp -a). */
        public Builder arpTable(com.magilhub.printnats.spi.ArpTable a) {
            this.arpTable = a;
            return this;
        }

        /** Replace the subnet sweep (tests). */
        public Builder macLocator(com.magilhub.printnats.discovery.MacLocator l) {
            this.macLocator = l;
            return this;
        }

        /** Host hook on a rediscovered printer IP (persist overrides); runs before the UI listener. */
        public Builder onPrinterAddressChanged(com.magilhub.printnats.discovery.PrinterRediscovery.AddressListener l) {
            this.addressHook = l;
            return this;
        }

        /** Durable store for status events that couldn't be published (default: in memory). */
        /** LAN mode, master: durable buffer for status forwarded to the cloud while the internet is out. */
        public Builder cloudOutboxStore(com.magilhub.printnats.spi.OutboxStore s) {
            this.cloudOutboxStore = s;
            return this;
        }

        public Builder outboxStore(com.magilhub.printnats.spi.OutboxStore s) {
            this.outboxStore = s;
            return this;
        }

        /** Platform fact for text-vs-image receipts when the session doesn't set it (Android: brand == "pax"). */
        public Builder dataCapDevice(boolean isDataCap) {
            this.dataCapDevice = isDataCap;
            return this;
        }

        /** null = no NATS (UI-only printing). */
        public Builder nats(NatsConfig c) {
            this.natsConfig = c;
            return this;
        }

        public Builder session(Session s) {
            this.session = s;
            return this;
        }

        public Builder restaurant(JsonObject restaurantDetails) {
            this.restaurant = restaurantDetails;
            return this;
        }

        public Builder printers(List<PrinterConfig> list) {
            this.printers.clear();
            this.printers.addAll(list);
            return this;
        }

        public Builder jobStore(JobStore s) {
            this.jobStore = s;
            return this;
        }

        public Builder inboundStore(InboundStore s) {
            this.inboundStore = s;
            return this;
        }

        public Builder transport(PrinterTransport t) {
            this.transport = t;
            return this;
        }

        public Builder starEncoder(StarEncoder e) {
            this.starEncoder = e;
            return this;
        }

        public Builder receiptRenderer(ReceiptRenderer r) {
            this.receiptRenderer = r;
            return this;
        }

        public Builder http(HttpClient h) {
            this.http = h;
            return this;
        }

        public Builder log(LogSink l) {
            this.log = l == null ? LogSink.NONE : l;
            return this;
        }

        public Builder deviceState(DeviceState d) {
            this.deviceState = d;
            return this;
        }

        public Builder listener(Listener l) {
            this.listener = l;
            return this;
        }

        public PrintNats build() {
            if (transport == null) transport = new RoutingTransport(log);
            if (natsConfig != null) {
                if (natsConfig.locationId == null) natsConfig.locationId = session.locationId;
                if (natsConfig.deviceId == null) natsConfig.deviceId = session.deviceId;
            }
            return new PrintNats(this);
        }
    }
}
