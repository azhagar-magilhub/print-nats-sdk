package com.magilhub.printnats.nats;

import com.magilhub.printnats.spi.LogSink;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.ConnectionListener;
import io.nats.client.ErrorListener;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;

/**
 * NATS JetStream client for print delivery — platform-neutral port of MerchantApp's
 * {@code NatsConnectionService} (Release-25.1) minus the Android Service/JS bridge:
 * <ul>
 *   <li>connection loop with exponential backoff (5 s → 30 s), infinite client reconnects;</li>
 *   <li>binds to BE's durable consumer on PRINTKOT; if it is missing, self-provisions ONLY the consumer
 *       (never the stream) as a stopgap; {@code testMode} provisions both for local dev;</li>
 *   <li>ensures the PRINTEVENTSTATUS stream (printeventstatus.&gt;, 2-day retention);</li>
 *   <li>per message: application-level receipt ack to BE on {@code printack.<deviceSubject>} (core NATS),
 *       then hands the message to {@link NatsEvents#onPrintMessage}; the JetStream ack is the receiver's job
 *       (the pipeline acks once the job is persisted);</li>
 *   <li>status publishes are ack-confirmed JetStream publishes; failures are buffered (cap 200, oldest dropped)
 *       and flushed on the next connect; every buffer event is written to the local {@code natsStatus_} log.</li>
 * </ul>
 * Requires {@code java.time} (jnats API): Android hosts below API 26 must enable core library desugaring.
 */
public final class NatsClient {
    public static final String STATUS_LOG = "natsStatus_";

    private final NatsConfig config;
    private final NatsEvents events;
    private final LogSink log;

    private final AtomicBoolean running = new AtomicBoolean();
    private Thread loopThread;
    private volatile Connection connection;
    private volatile JetStream jetStream;
    private volatile boolean isMaster;

    private final Object statusLock = new Object();
    private Thread statusThread;
    private Thread historyThread;
    private JetStreamSubscription statusSub;
    private JetStreamSubscription historySub;

    private final com.magilhub.printnats.spi.OutboxStore outbox;

    // ---- app messaging (core NATS, e.g. CartVue) — independent of the print stream -----------------------
    private final java.util.Set<String> appSubjects = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile Dispatcher appDispatcher;
    /** Publishes made while disconnected: small, short-lived — live UI state, not jobs. */
    private final java.util.concurrent.ConcurrentLinkedDeque<PendingCore> pendingCore = new java.util.concurrent.ConcurrentLinkedDeque<>();
    static final int PENDING_CORE_CAP = 50;
    static final long PENDING_CORE_MAX_AGE_MS = 60_000;

    // ---- request/reply (print relay) — master serves, clients request ------------------------------------
    /** Answers one core-NATS request; returns the reply bytes (null = no reply). Runs on the relay dispatcher thread. */
    public interface RequestHandler {
        byte[] handle(byte[] request);
    }

    /** Queue group: if two devices wrongly both think they are master, only one answers each request. */
    static final String RELAY_QUEUE_GROUP = "printrelay-master";
    private final Object relayLock = new Object();
    private volatile String servedSubject;
    private volatile RequestHandler servedHandler;
    private Dispatcher relayDispatcher;
    private boolean relaySubscribed;

    private static final class PendingCore {
        final String subject;
        final byte[] data;
        final long at;

        PendingCore(String subject, byte[] data, long at) {
            this.subject = subject;
            this.data = data;
            this.at = at;
        }
    }

    /** Connection-event sink for helpers (e.g. {@link DurableConsumers}); details are redacted. */
    interface EventSink {
        void emit(String type, String detail);
    }

    private final DurableConsumers durables;

    public NatsClient(NatsConfig config, NatsEvents events, LogSink log) {
        this(config, events, log, null);
    }

    /** @param outbox durable store for unconfirmed publishes (null → in-memory, lost on restart) */
    public NatsClient(NatsConfig config, NatsEvents events, LogSink log, com.magilhub.printnats.spi.OutboxStore outbox) {
        this.config = config;
        this.events = events;
        this.log = log == null ? LogSink.NONE : log;
        this.isMaster = config.isMaster;
        this.outbox = outbox != null ? outbox : new InMemoryOutbox();
        this.durables = new DurableConsumers(config, new EventSink() {
            @Override
            public void emit(String type, String detail) {
                emitConnectionEvent(type, detail);
            }
        });
    }

    public synchronized void start() {
        if (running.getAndSet(true)) return;
        loopThread = new Thread(new Runnable() {
            @Override
            public void run() {
                connectionLoop();
            }
        }, "print-nats-connection");
        loopThread.setDaemon(true);
        loopThread.start();
    }

    public synchronized void stop() {
        running.set(false);
        if (loopThread != null) loopThread.interrupt();
        stopStatusSubscriptions();
        closeConnection();
    }

    public boolean isConnected() {
        Connection c = connection;
        return c != null && c.getStatus() == Connection.Status.CONNECTED;
    }

    // ---- app messaging ----------------------------------------------------------------------------

    /** Subscribe a core-NATS subject for the host (kept across reconnects; delivered via NatsEvents#onAppMessage). */
    public void subscribeApp(String subject) {
        if (subject == null || subject.isEmpty() || !appSubjects.add(subject)) return;
        Dispatcher d = appDispatcher;
        if (d != null) {
            try {
                d.subscribe(subject);
            } catch (RuntimeException e) {
                log.append("nats_", redact("app subscribe failed " + subject + ": " + e));
            }
        }
    }

    public void unsubscribeApp(String subject) {
        if (subject == null || !appSubjects.remove(subject)) return;
        Dispatcher d = appDispatcher;
        if (d != null) {
            try {
                d.unsubscribe(subject);
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
    }

    /**
     * Fire-and-forget core-NATS publish (no JetStream, no ack). While disconnected the message is kept briefly
     * (last {@value #PENDING_CORE_CAP}, at most {@value #PENDING_CORE_MAX_AGE_MS} ms old) and sent on reconnect.
     * @return true when handed to the connection now
     */
    public boolean publishCore(String subject, byte[] data) {
        Connection c = connection;
        if (c != null && c.getStatus() == Connection.Status.CONNECTED) {
            try {
                c.publish(subject, data);
                return true;
            } catch (Throwable t) {
                // fall through to buffering
            }
        }
        pendingCore.addLast(new PendingCore(subject, data, System.currentTimeMillis()));
        while (pendingCore.size() > PENDING_CORE_CAP) pendingCore.pollFirst();
        return false;
    }

    private void startAppMessaging(Connection nc) {
        Dispatcher d = nc.createDispatcher(msg -> {
            try {
                events.onAppMessage(msg.getSubject(), msg.getData());
            } catch (RuntimeException e) {
                log.append("nats_", redact("app message handler threw: " + e));
            }
        });
        for (String s : appSubjects) d.subscribe(s);
        appDispatcher = d;
        flushPendingCore(nc);
    }

    private void flushPendingCore(Connection nc) {
        long cutoff = System.currentTimeMillis() - PENDING_CORE_MAX_AGE_MS;
        PendingCore p;
        while ((p = pendingCore.pollFirst()) != null) {
            if (p.at < cutoff) continue;
            try {
                nc.publish(p.subject, p.data);
            } catch (Throwable t) {
                pendingCore.addFirst(p);
                return;
            }
        }
    }

    // ---- request/reply ----------------------------------------------------------------------------

    /**
     * Core-NATS request: the reply's bytes, or null when not connected, nobody is listening ("no responders"),
     * the wait exceeded {@code timeoutMs}, or the call failed.
     */
    public byte[] request(String subject, byte[] body, long timeoutMs) {
        Connection c = connection;
        if (c == null || c.getStatus() != Connection.Status.CONNECTED) return null;
        java.util.concurrent.CompletableFuture<Message> f = null;
        try {
            f = c.request(subject, body);
            Message m = f.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return m == null ? null : m.getData();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Throwable t) {
            if (f != null) f.cancel(true);
            return null;
        }
    }

    /**
     * Answer requests on {@code subject} while this device is the master (subscribed on connect / when it becomes
     * master, unsubscribed when it stops being master; kept across reconnects). One subject per client.
     */
    public void serveWhileMaster(String subject, RequestHandler handler) {
        synchronized (relayLock) {
            if (relaySubscribed && relayDispatcher != null && servedSubject != null && !servedSubject.equals(subject)) {
                try {
                    relayDispatcher.unsubscribe(servedSubject);
                } catch (RuntimeException ignored) {
                    // gone
                }
                relaySubscribed = false;
            }
            servedSubject = subject;
            servedHandler = handler;
        }
        applyRelaySubscription();
    }

    /** True while this client answers relay requests (master, connected, handler set). */
    public boolean isServingRelay() {
        synchronized (relayLock) {
            return relaySubscribed;
        }
    }

    private void applyRelaySubscription() {
        synchronized (relayLock) {
            Dispatcher d = relayDispatcher;
            if (d == null) {
                relaySubscribed = false;
                return;
            }
            boolean want = isMaster && servedHandler != null && servedSubject != null;
            try {
                if (want && !relaySubscribed) {
                    d.subscribe(servedSubject, RELAY_QUEUE_GROUP);
                    relaySubscribed = true;
                    emitConnectionEvent("relay_serving", servedSubject);
                    // tell waiting clients to retry now instead of after their backoff
                    Connection c = connection;
                    if (c != null) {
                        c.publish(onlineSubject(servedSubject), String.valueOf(config.deviceId).getBytes(StandardCharsets.UTF_8));
                    }
                } else if (!want && relaySubscribed) {
                    d.unsubscribe(servedSubject);
                    relaySubscribed = false;
                    emitConnectionEvent("relay_stopped", servedSubject);
                }
            } catch (RuntimeException e) {
                log.append("nats_", redact("relay subscription change failed: " + e));
            }
        }
    }

    /** Masters announce themselves here when they start answering relay requests. */
    public static String onlineSubject(String relaySubject) {
        return relaySubject + ".online";
    }

    private void startRelayServing(final Connection nc) {
        Dispatcher d = nc.createDispatcher(msg -> {
            RequestHandler h = servedHandler;
            String replyTo = msg.getReplyTo();
            if (h == null || replyTo == null) return;
            byte[] reply;
            try {
                reply = h.handle(msg.getData());
            } catch (Throwable t) {
                log.append("nats_", redact("relay handler threw: " + t));
                reply = ("{\"ok\":false,\"error\":\"Master error\"}").getBytes(StandardCharsets.UTF_8);
            }
            if (reply == null) return;
            try {
                nc.publish(replyTo, reply);
            } catch (Throwable t) {
                log.append("nats_", redact("relay reply failed: " + t));
            }
        });
        String served = servedSubject;
        if (served != null) {
            d.subscribe(onlineSubject(served), m -> emitConnectionEvent("relay_master_online",
                    new String(m.getData(), StandardCharsets.UTF_8)));
        }
        synchronized (relayLock) {
            relayDispatcher = d;
            relaySubscribed = false;
        }
        applyRelaySubscription();
    }

    // ---- host streams + durable consumers (acknowledged app sync) -----------------------------------------

    /**
     * Create the stream, or update its subjects / max age if they differ (File storage, Limits retention,
     * Nats-Msg-Id duplicate window {@link NatsConfig#durableDuplicateWindowMs}). Remembered and re-applied on every
     * connect. Throws {@link IllegalStateException} when not connected (it is still applied on the next connect).
     */
    public void ensureStream(String name, java.util.List<String> subjects, long maxAgeMs) throws Exception {
        durables.ensureStream(name, subjects, maxAgeMs);
    }

    /**
     * JetStream publish with a {@code Nats-Msg-Id} header (a repeat within the duplicate window is stored once).
     * Returns the stream sequence from the PubAck (the original one for a duplicate). No buffering: throws when not
     * connected or no PubAck arrived — the caller keeps its own outbox.
     */
    public long publishDurable(String subject, byte[] data, String msgId) throws Exception {
        return durables.publish(subject, data, msgId);
    }

    /**
     * Push durable consumer on {@code stream} ({@code AckPolicy.Explicit}, ackWait {@link NatsConfig#durableAckWaitMs},
     * maxAckPending {@link NatsConfig#durableMaxAckPending}, {@code DeliverPolicy.All} when first created). Kept across
     * reconnects; idempotent (same stream + filter only replaces the handler). While disconnected it is registered and
     * bound on the next connect. Throws when binding now fails (still retried on the next connect).
     */
    public void startDurable(String stream, String durable, String filterSubject, DurableHandler handler) throws Exception {
        durables.startDurable(stream, durable, filterSubject, handler);
    }

    /** Stop receiving; the consumer (and its ack floor) stays on the server. */
    public void stopDurable(String durable) {
        durables.stopDurable(durable);
    }

    /** @return false for an unknown / stale token (no-op; JetStream redelivers after ackWait) */
    public boolean ackDurable(String token) {
        return durables.settle(token, DurableConsumers.ACK, 0);
    }

    /** Negative ack: redeliver after {@code delayMs} (0 = now). False for an unknown / stale token. */
    public boolean nakDurable(String token, long delayMs) {
        return durables.settle(token, DurableConsumers.NAK, delayMs);
    }

    /** Never redeliver this message (poison). False for an unknown / stale token. */
    public boolean termDurable(String token) {
        return durables.settle(token, DurableConsumers.TERM, 0);
    }

    /** null when the stream or consumer doesn't exist; throws when not connected. */
    public ConsumerStats consumerInfo(String stream, String durable) throws Exception {
        return durables.consumerInfo(stream, durable);
    }

    public java.util.List<ConsumerStats> listConsumers(String stream) throws Exception {
        return durables.listConsumers(stream);
    }

    /** Delete a consumer (and stop it locally). False when it didn't exist. */
    public boolean deleteConsumer(String stream, String durable) throws Exception {
        return durables.deleteConsumer(stream, durable);
    }

    // ---- publishing --------------------------------------------------------------------------------

    /**
     * Ack-confirmed JetStream publish. Returns true only if the server confirmed storage now; otherwise the
     * publish is buffered for the next connect and false is returned.
     */
    public boolean publish(String subject, byte[] data) {
        JetStream js = jetStream;
        if (js == null) {
            buffer(subject, data);
            return false;
        }
        try {
            js.publish(subject, data);
            if (pendingPublishCount() > 0) flushAsync(); // connection is healthy again — drain the backlog
            return true;
        } catch (Throwable t) {
            buffer(subject, data);
            return false;
        }
    }

    private final Object outboxLock = new Object();

    private void buffer(String subject, byte[] data) {
        synchronized (outboxLock) {
            while (outbox.size() >= config.pendingPublishCap) {
                java.util.List<com.magilhub.printnats.spi.OutboxStore.Entry> oldest = outbox.peek(1);
                if (oldest.isEmpty()) break;
                com.magilhub.printnats.spi.OutboxStore.Entry d = oldest.get(0);
                outbox.remove(d.id);
                log.append(STATUS_LOG, "DROPPED (buffer full, never published) | " + d.subject + " | "
                        + new String(d.data, StandardCharsets.UTF_8));
            }
            outbox.add(subject, data);
        }
    }

    private void flushPendingPublishes(JetStream js) {
        while (true) {
            java.util.List<com.magilhub.printnats.spi.OutboxStore.Entry> batch;
            synchronized (outboxLock) {
                batch = outbox.peek(20);
            }
            if (batch.isEmpty()) return;
            for (com.magilhub.printnats.spi.OutboxStore.Entry e : batch) {
                try {
                    js.publish(e.subject, e.data);
                } catch (Throwable t) {
                    return; // still failing — keep it (oldest first) for the next reconnect instead of spinning
                }
                synchronized (outboxLock) {
                    outbox.remove(e.id);
                }
                log.append(STATUS_LOG, "PUBLISHED (from reconnect buffer) | " + e.subject + " | "
                        + new String(e.data, StandardCharsets.UTF_8));
            }
        }
    }

    private final AtomicBoolean flushing = new AtomicBoolean();

    private void flushAsync() {
        final JetStream js = jetStream;
        if (js == null || !flushing.compareAndSet(false, true)) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    flushPendingPublishes(js);
                } finally {
                    flushing.set(false);
                }
            }
        }, "print-nats-flush");
        t.setDaemon(true);
        t.start();
    }

    public int pendingPublishCount() {
        synchronized (outboxLock) {
            return outbox.size();
        }
    }

    /** Publishes left in the durable outbox from a previous run are flushed on the first connect. */
    public boolean hasBacklog() {
        return pendingPublishCount() > 0;
    }

    // ---- master role -------------------------------------------------------------------------------

    /** Switch status-subscription scope while connected (dashboard "switch master device"). */
    public void updateMasterRole(boolean master) {
        if (this.isMaster == master) return;
        emitConnectionEvent("role_changed", master ? "master" : "client");
        this.isMaster = master;
        config.isMaster = master;
        applyRelaySubscription();
        JetStream js = jetStream;
        if (js != null) {
            stopStatusSubscriptions();
            startStatusSubscriptions(js);
        }
    }

    // ---- connection loop ---------------------------------------------------------------------------

    private void connectionLoop() {
        long backoff = config.initialBackoffMs;
        while (running.get()) {
            try {
                Options.Builder builder = new Options.Builder()
                        .servers(config.serverUrls.split(","))
                        .connectionTimeout(Duration.ofMillis(config.connectTimeoutMs))
                        .maxReconnects(-1)
                        .reconnectWait(Duration.ofMillis(config.reconnectWaitMs))
                        .connectionListener((conn, type) -> {
                            emitConnectionEvent("connection_event", String.valueOf(type));
                            // jnats reconnects transparently (same Connection), so the fresh-connect flush below
                            // never re-runs; flush here too. (Legacy only flushed on a fresh connect, so status
                            // events buffered during a blip stayed unsent until the app restarted.)
                            if (type == ConnectionListener.Events.RECONNECTED || type == ConnectionListener.Events.RESUBSCRIBED) {
                                flushAsync();
                                if (conn != null) flushPendingCore(conn); // dispatcher subscriptions survive a reconnect
                                if (type == ConnectionListener.Events.RECONNECTED) {
                                    daemon("print-nats-durables", new Runnable() {
                                        @Override
                                        public void run() {
                                            durables.onReconnected();
                                        }
                                    });
                                }
                            }
                        })
                        .errorListener(new ErrorListener() {
                            @Override
                            public void errorOccurred(Connection conn, String error) {
                                emitConnectionEvent("error", error);
                            }

                            @Override
                            public void exceptionOccurred(Connection conn, Exception exp) {
                                emitConnectionEvent("exception", String.valueOf(exp));
                            }
                        });
                if (config.authToken != null && !config.authToken.isEmpty()) {
                    builder.token(config.authToken.toCharArray());
                }
                emitConnectionEvent("connecting", "testMode=" + config.testMode);
                Connection nc = Nats.connect(builder.build());
                connection = nc;
                emitConnectionEvent("connected", String.valueOf(nc.getStatus()));
                // App messaging first: a print-stream problem below (e.g. consumer bind) must not hold up CartVue.
                startAppMessaging(nc);
                startRelayServing(nc);

                JetStream js = nc.jetStream();
                jetStream = js;
                // Host streams + durables before the print consumer bind, which may throw and restart the loop.
                durables.onConnected(nc);
                // Stream first: a JetStream publish to a subject with no stream is delivered but never acked,
                // so flushing first would keep (and later re-send) the event. (Legacy had the same order.)
                ensureStatusStream(nc);
                flushPendingPublishes(js);
                if (config.testMode) provisionForTest(nc);

                JetStreamSubscription sub = bindPrintConsumer(nc, js);
                emitConnectionEvent("subscribed", "stream=" + config.streamName + " consumer=" + config.consumer());
                backoff = config.initialBackoffMs;
                startStatusSubscriptions(js);

                while (running.get()) {
                    Message msg = sub.nextMessage(Duration.ofSeconds(30));
                    if (msg == null) continue;
                    handle(nc, msg);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                emitConnectionEvent("connect_failed", String.valueOf(t));
                stopStatusSubscriptions();
                closeConnection();
                if (!running.get()) break;
                emitConnectionEvent("retrying", backoff + "ms");
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff = Math.min(backoff * 2, config.maxBackoffMs);
            }
        }
        stopStatusSubscriptions();
        closeConnection();
    }

    private void handle(Connection nc, final Message msg) {
        final String messageId = msg.getHeaders() != null ? msg.getHeaders().getFirst("Nats-Msg-Id") : null;
        sendAckToBackend(nc, messageId);
        try {
            events.onPrintMessage(new InboundMessage() {
                @Override
                public String subject() {
                    return msg.getSubject();
                }

                @Override
                public String messageId() {
                    return messageId;
                }

                @Override
                public long streamSequence() {
                    return msg.metaData().streamSequence();
                }

                @Override
                public byte[] data() {
                    return msg.getData();
                }

                @Override
                public void ack() {
                    msg.ack();
                }

                @Override
                public void nak() {
                    msg.nak();
                }
            });
        } catch (Throwable t) {
            // A handler crash must not become a redelivery loop on a poison message.
            emitConnectionEvent("handler_error", String.valueOf(t));
            try {
                msg.ack();
            } catch (Throwable ignored) {
                // nothing more to do
            }
        }
    }

    /** "BE, I received it" — plain core NATS publish that BE's PrintAckConsumer (printack.>) records. */
    private void sendAckToBackend(Connection nc, String messageId) {
        if (messageId == null || messageId.isEmpty()) return;
        try {
            nc.publish(config.backendAckSubject(),
                    ("{\"messageId\":\"" + messageId.replace("\"", "\\\"") + "\"}").getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            emitConnectionEvent("backend_ack_failed", String.valueOf(t));
        }
    }

    private JetStreamSubscription bindPrintConsumer(Connection nc, JetStream js) throws Exception {
        PushSubscribeOptions so = PushSubscribeOptions.bind(config.streamName, config.consumer());
        try {
            return js.subscribe(null, so);
        } catch (IllegalArgumentException | JetStreamApiException bindFailed) {
            // jnats throws IllegalArgumentException client-side for "consumer not found, required in bind mode".
            if (config.testMode) throw bindFailed;
            emitConnectionEvent("consumer_missing", "self-provisioning consumer only on " + config.streamName);
            nc.jetStreamManagement().addOrUpdateConsumer(config.streamName, consumerConfig());
            return js.subscribe(null, so);
        }
    }

    private ConsumerConfiguration consumerConfig() {
        return ConsumerConfiguration.builder()
                .durable(config.consumer())
                .deliverSubject(config.consumer())
                .filterSubject(config.deviceSubject())
                .ackPolicy(AckPolicy.Explicit)
                .ackWait(Duration.ofMillis(config.ackWaitMs))
                .build();
    }

    private void provisionForTest(Connection nc) {
        try {
            JetStreamManagement jsm = nc.jetStreamManagement();
            try {
                jsm.getStreamInfo(config.streamName);
            } catch (JetStreamApiException notFound) {
                jsm.addStream(StreamConfiguration.builder().name(config.streamName).subjects("printkot.>").build());
            }
            jsm.addOrUpdateConsumer(config.streamName, consumerConfig());
        } catch (Throwable t) {
            emitConnectionEvent("test_provision_failed", String.valueOf(t));
        }
    }

    private void ensureStatusStream(Connection nc) {
        try {
            JetStreamManagement jsm = nc.jetStreamManagement();
            try {
                jsm.getStreamInfo(config.statusStreamName);
            } catch (JetStreamApiException notFound) {
                jsm.addStream(StreamConfiguration.builder()
                        .name(config.statusStreamName)
                        .subjects("printeventstatus.>")
                        .storageType(StorageType.File)
                        .retentionPolicy(RetentionPolicy.Limits)
                        .maxAge(Duration.ofMillis(config.statusStreamMaxAgeMs))
                        .build());
            }
        } catch (Throwable t) {
            emitConnectionEvent("status_stream_failed", String.valueOf(t));
        }
    }

    // ---- status subscriptions ------------------------------------------------------------------------

    private void startStatusSubscriptions(final JetStream js) {
        synchronized (statusLock) {
            final String subject = config.statusSubscriptionSubject();
            try {
                statusSub = js.subscribe(subject, PushSubscribeOptions.builder()
                        .configuration(ConsumerConfiguration.builder().ackPolicy(AckPolicy.None)
                                .deliverPolicy(DeliverPolicy.New).build()).build());
                final JetStreamSubscription live = statusSub;
                statusThread = daemon("print-nats-status", new Runnable() {
                    @Override
                    public void run() {
                        pump(live, false);
                    }
                });
                historySub = js.subscribe(subject, PushSubscribeOptions.builder()
                        .configuration(ConsumerConfiguration.builder().ackPolicy(AckPolicy.None)
                                .deliverPolicy(DeliverPolicy.All).build()).build());
                final JetStreamSubscription history = historySub;
                historyThread = daemon("print-nats-status-history", new Runnable() {
                    @Override
                    public void run() {
                        pump(history, true);
                    }
                });
            } catch (Throwable t) {
                emitConnectionEvent("status_subscribe_failed", String.valueOf(t));
            }
        }
    }

    private void pump(JetStreamSubscription sub, boolean history) {
        try {
            while (running.get()) {
                Message m = sub.nextMessage(Duration.ofSeconds(history ? 3 : 30));
                if (m == null) {
                    if (history) break; // retained history drained
                    continue;
                }
                if (history) events.onStatusHistoryEvent(m.getSubject(), m.getData());
                else events.onStatusEvent(m.getSubject(), m.getData());
            }
        } catch (Throwable ignored) {
            // unsubscribe()/close() breaks nextMessage — normal shutdown path
        } finally {
            if (history) {
                try {
                    sub.unsubscribe();
                } catch (Throwable ignored) {
                    // already gone
                }
            }
        }
    }

    private void stopStatusSubscriptions() {
        synchronized (statusLock) {
            for (JetStreamSubscription s : new JetStreamSubscription[]{statusSub, historySub}) {
                if (s != null) {
                    try {
                        s.unsubscribe();
                    } catch (Throwable ignored) {
                        // already closed
                    }
                }
            }
            statusSub = null;
            historySub = null;
            if (statusThread != null) statusThread.interrupt();
            if (historyThread != null) historyThread.interrupt();
        }
    }

    private void closeConnection() {
        jetStream = null;
        durables.onClosed();
        appDispatcher = null; // closed with the connection; recreated (with every app subject) on the next connect
        synchronized (relayLock) {
            relayDispatcher = null;
            relaySubscribed = false;
        }
        Connection c = connection;
        connection = null;
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
                // closing anyway
            }
        }
    }

    private static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Connection events carry exception text, which for connect failures includes the server URL with credentials. */
    private void emitConnectionEvent(String type, String detail) {
        events.onConnectionEvent(type, redact(detail));
    }

    private static final java.util.regex.Pattern URL_CREDENTIALS =
            java.util.regex.Pattern.compile("(://)[^/@\\s:]+:[^@\\s/]+@");

    /** nats://user:pass@host → nats://***:***@host, so logs and events never carry NATS credentials. */
    static String redact(String s) {
        return s == null ? null : URL_CREDENTIALS.matcher(s).replaceAll("$1***:***@");
    }
}
