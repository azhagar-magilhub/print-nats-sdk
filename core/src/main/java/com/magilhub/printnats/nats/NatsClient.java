package com.magilhub.printnats.nats;

import com.magilhub.printnats.spi.LogSink;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

import io.nats.client.Connection;
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

    private final Deque<Object[]> pendingPublishes = new ArrayDeque<>();

    public NatsClient(NatsConfig config, NatsEvents events, LogSink log) {
        this.config = config;
        this.events = events;
        this.log = log == null ? LogSink.NONE : log;
        this.isMaster = config.isMaster;
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

    private void buffer(String subject, byte[] data) {
        synchronized (pendingPublishes) {
            if (pendingPublishes.size() >= config.pendingPublishCap) {
                Object[] dropped = pendingPublishes.pollFirst();
                if (dropped != null) {
                    log.append(STATUS_LOG, "DROPPED (buffer full, never published) | " + dropped[0] + " | "
                            + new String((byte[]) dropped[1], StandardCharsets.UTF_8));
                }
            }
            pendingPublishes.addLast(new Object[]{subject, data});
        }
    }

    private void flushPendingPublishes(JetStream js) {
        while (true) {
            Object[] item;
            synchronized (pendingPublishes) {
                item = pendingPublishes.pollFirst();
            }
            if (item == null) return;
            try {
                js.publish((String) item[0], (byte[]) item[1]);
                log.append(STATUS_LOG, "PUBLISHED (from reconnect buffer) | " + item[0] + " | "
                        + new String((byte[]) item[1], StandardCharsets.UTF_8));
            } catch (Throwable t) {
                synchronized (pendingPublishes) {
                    pendingPublishes.addFirst(item);
                }
                return; // still failing — wait for the next reconnect instead of spinning
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
        synchronized (pendingPublishes) {
            return pendingPublishes.size();
        }
    }

    // ---- master role -------------------------------------------------------------------------------

    /** Switch status-subscription scope while connected (dashboard "switch master device"). */
    public void updateMasterRole(boolean master) {
        if (this.isMaster == master) return;
        events.onConnectionEvent("role_changed", master ? "master" : "client");
        this.isMaster = master;
        config.isMaster = master;
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
                            events.onConnectionEvent("connection_event", String.valueOf(type));
                            // jnats reconnects transparently (same Connection), so the fresh-connect flush below
                            // never re-runs; flush here too. (Legacy only flushed on a fresh connect, so status
                            // events buffered during a blip stayed unsent until the app restarted.)
                            if (type == ConnectionListener.Events.RECONNECTED || type == ConnectionListener.Events.RESUBSCRIBED) {
                                flushAsync();
                            }
                        })
                        .errorListener(new ErrorListener() {
                            @Override
                            public void errorOccurred(Connection conn, String error) {
                                events.onConnectionEvent("error", error);
                            }

                            @Override
                            public void exceptionOccurred(Connection conn, Exception exp) {
                                events.onConnectionEvent("exception", String.valueOf(exp));
                            }
                        });
                if (config.authToken != null && !config.authToken.isEmpty()) {
                    builder.token(config.authToken.toCharArray());
                }
                events.onConnectionEvent("connecting", "testMode=" + config.testMode);
                Connection nc = Nats.connect(builder.build());
                connection = nc;
                events.onConnectionEvent("connected", String.valueOf(nc.getStatus()));

                JetStream js = nc.jetStream();
                jetStream = js;
                flushPendingPublishes(js);
                ensureStatusStream(nc);
                if (config.testMode) provisionForTest(nc);

                JetStreamSubscription sub = bindPrintConsumer(nc, js);
                events.onConnectionEvent("subscribed", "stream=" + config.streamName + " consumer=" + config.consumer());
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
                events.onConnectionEvent("connect_failed", String.valueOf(t));
                stopStatusSubscriptions();
                closeConnection();
                if (!running.get()) break;
                events.onConnectionEvent("retrying", backoff + "ms");
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
            events.onConnectionEvent("handler_error", String.valueOf(t));
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
            events.onConnectionEvent("backend_ack_failed", String.valueOf(t));
        }
    }

    private JetStreamSubscription bindPrintConsumer(Connection nc, JetStream js) throws Exception {
        PushSubscribeOptions so = PushSubscribeOptions.bind(config.streamName, config.consumer());
        try {
            return js.subscribe(null, so);
        } catch (IllegalArgumentException | JetStreamApiException bindFailed) {
            // jnats throws IllegalArgumentException client-side for "consumer not found, required in bind mode".
            if (config.testMode) throw bindFailed;
            events.onConnectionEvent("consumer_missing", "self-provisioning consumer only on " + config.streamName);
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
            events.onConnectionEvent("test_provision_failed", String.valueOf(t));
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
            events.onConnectionEvent("status_stream_failed", String.valueOf(t));
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
                events.onConnectionEvent("status_subscribe_failed", String.valueOf(t));
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
}
