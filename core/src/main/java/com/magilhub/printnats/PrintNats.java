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

    /** Everything a UI may want to observe. All callbacks run on SDK threads. */
    public interface Listener extends JobListener, PrintPipeline.Listener {
    }

    private final NatsClient nats;
    private final PrintQueue queue;
    private final PrintPipeline pipeline;
    private final StatusPublisher status;
    private final OrderLookup orders;
    private final List<PrinterConfig> printers = new CopyOnWriteArrayList<>();
    private volatile Session session;

    private PrintNats(Builder b) {
        this.session = b.session;
        this.printers.addAll(b.printers);
        LogSink log = b.log;
        final List<PrinterConfig> printerList = this.printers;
        PrintQueue.PrinterLookup lookup = new PrintQueue.PrinterLookup() {
            @Override
            public PrinterConfig get(String printerId) {
                for (PrinterConfig p : printerList) if (p.id.equals(printerId)) return p;
                return null;
            }
        };
        TicketRenderer renderer = new DefaultTicketRenderer(b.starEncoder, b.receiptRenderer, log);
        this.queue = new PrintQueue(b.jobStore, lookup, renderer, b.transport, log);
        this.orders = new OrderLookup(b.http, b.session, log);

        final PrintNats self = this;
        final Listener listener = b.listener;
        final PipelineHolder holder = new PipelineHolder();
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
            }
        }, log);
        this.status = new StatusPublisher(nats == null ? new NatsClient(new NatsConfig(), null, log) : nats, lookup, log,
                b.deviceState, b.session.locationId, b.session.deviceId);
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
    }

    private static final class PipelineHolder {
        volatile PrintPipeline pipeline;
    }

    public static Builder builder() {
        return new Builder();
    }

    // ---- lifecycle ------------------------------------------------------------------------------------

    /** Recover unfinished work, then connect to NATS. */
    public void start() {
        queue.recover();
        pipeline.recover();
        if (nats != null) nats.start();
    }

    public void stop() {
        if (nats != null) nats.stop();
        pipeline.shutdown();
        queue.shutdown();
        status.shutdown();
    }

    // ---- configuration --------------------------------------------------------------------------------

    /** New restaurantDetails JSON (feature flags, templates, order types…). */
    public void setRestaurant(JsonObject restaurantDetails) {
        pipeline.setRestaurant(new Restaurant(restaurantDetails));
    }

    public void setPrinters(List<PrinterConfig> list) {
        printers.clear();
        printers.addAll(list);
    }

    public List<PrinterConfig> printers() {
        return Collections.unmodifiableList(new ArrayList<>(printers));
    }

    public void setSession(Session s) {
        this.session = s;
        orders.setSession(s);
        pipeline.setSession(s);
    }

    public void updateMasterRole(boolean isMaster) {
        if (nats != null) nats.updateMasterRole(isMaster);
    }

    // ---- printing from the host UI ----------------------------------------------------------------------

    public int printKot(JsonObject orderDetails, String tableName, boolean isOrderCancelled) {
        return pipeline.printKot(orderDetails, tableName, isOrderCancelled);
    }

    public int printEditKot(JsonObject orderDetails) {
        return pipeline.printEditKot(orderDetails);
    }

    public int printReceipt(JsonObject orderDetails) {
        return pipeline.printReceipt(orderDetails);
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

    public boolean isNatsConnected() {
        return nats != null && nats.isConnected();
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
