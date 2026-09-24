package com.magilhub.printnats.nats;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.MessageHandler;
import io.nats.client.PublishOptions;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.ConsumerInfo;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.PublishAck;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;

/**
 * Host-owned JetStream streams and durable push consumers (acknowledged app sync, e.g. OFFSYNC), independent of the
 * print stream. Owned by {@link NatsClient}, which forwards connection lifecycle:
 * <ul>
 *   <li>streams passed to {@link #ensureStream} are remembered and re-applied on every fresh connect;</li>
 *   <li>durables passed to {@link #startDurable} are (re)bound on every fresh connect, and after a transparent
 *       jnats reconnect the consumer is re-created if the server lost it (same deliver subject, so the surviving
 *       subscription keeps receiving);</li>
 *   <li>consumers are push, {@code AckPolicy.Explicit}, {@code DeliverPolicy.All} when first created, with a deliver
 *       group named after the durable — a lingering interest from a dead connection then can't make the bind fail
 *       with "consumer already bound";</li>
 *   <li>each delivered message gets a token kept in a bounded per-durable map until ack / nak / term. Tokens die
 *       with the connection; settling a dead token is a no-op (the message is redelivered after ackWait).</li>
 * </ul>
 */
final class DurableConsumers {
    static final int ERR_CONSUMER_NOT_FOUND = 10014;
    static final int ERR_STREAM_NOT_FOUND = 10059;

    private final NatsConfig config;
    private final NatsClient.EventSink events;

    private volatile Connection connection;
    private Dispatcher dispatcher;
    private final Object lock = new Object();

    private final Map<String, StreamSpec> streams = new ConcurrentHashMap<>();
    private final Map<String, Durable> durables = new ConcurrentHashMap<>();
    private final AtomicLong tokenSeq = new AtomicLong();
    /** Changes with every fresh connection: tokens from an older one are dropped. */
    private final AtomicLong generation = new AtomicLong();

    private static final class StreamSpec {
        final String name;
        final List<String> subjects;
        final long maxAgeMs;

        StreamSpec(String name, List<String> subjects, long maxAgeMs) {
            this.name = name;
            this.subjects = subjects;
            this.maxAgeMs = maxAgeMs;
        }
    }

    private final class Durable {
        final String stream;
        final String name;
        final String filterSubject;
        volatile DurableHandler handler;
        JetStreamSubscription sub;
        Dispatcher subDispatcher;
        String deliverSubject;
        String deliverGroup;
        /** token → message, oldest first; bounded (evicted = left unacked → redelivered). */
        final LinkedHashMap<String, Message> pending = new LinkedHashMap<>();

        Durable(String stream, String name, String filterSubject, DurableHandler handler) {
            this.stream = stream;
            this.name = name;
            this.filterSubject = filterSubject;
            this.handler = handler;
        }

        boolean sameSpec(String s, String f) {
            return stream.equals(s) && filterSubject.equals(f);
        }
    }

    DurableConsumers(NatsConfig config, NatsClient.EventSink events) {
        this.config = config;
        this.events = events;
    }

    // ---- lifecycle (from NatsClient) ------------------------------------------------------------------

    /** Fresh connection: re-apply streams, (re)bind every durable. Never throws. */
    void onConnected(Connection nc) {
        synchronized (lock) {
            generation.incrementAndGet();
            connection = nc;
            dispatcher = nc.createDispatcher();
            for (Durable d : durables.values()) {
                synchronized (d.pending) {
                    d.pending.clear();
                }
                d.sub = null;
                d.subDispatcher = null;
            }
        }
        for (StreamSpec s : streams.values()) {
            try {
                applyStream(nc, s);
            } catch (Throwable t) {
                events.emit("stream_failed", s.name + ": " + t);
            }
        }
        for (Durable d : durables.values()) {
            try {
                bind(d);
            } catch (Throwable t) {
                events.emit("durable_failed", d.name + ": " + t);
            }
        }
    }

    /**
     * jnats reconnected the same connection: its subscriptions are back, but the server may have lost the consumer
     * (e.g. restarted without its store) — re-create any that are missing, bind any that never bound.
     */
    void onReconnected() {
        Connection nc = connection;
        if (nc == null) return;
        for (StreamSpec s : streams.values()) {
            try {
                applyStream(nc, s);
            } catch (Throwable t) {
                events.emit("stream_failed", s.name + ": " + t);
            }
        }
        for (Durable d : durables.values()) {
            try {
                boolean bound;
                synchronized (lock) {
                    bound = d.sub != null;
                }
                if (bound) {
                    if (info(nc, d.stream, d.name) == null) {
                        createConsumer(nc, d, d.deliverSubject, d.deliverGroup);
                        events.emit("durable_recreated", d.name);
                    }
                } else {
                    bind(d);
                }
            } catch (Throwable t) {
                events.emit("durable_failed", d.name + ": " + t);
            }
        }
    }

    void onClosed() {
        synchronized (lock) {
            generation.incrementAndGet();
            connection = null;
            dispatcher = null; // closed with the connection
            for (Durable d : durables.values()) {
                d.sub = null;
                d.subDispatcher = null;
                synchronized (d.pending) {
                    d.pending.clear();
                }
            }
        }
    }

    // ---- streams / publish ----------------------------------------------------------------------------

    void ensureStream(String name, List<String> subjects, long maxAgeMs) throws IOException, JetStreamApiException {
        if (name == null || name.isEmpty() || subjects == null || subjects.isEmpty()) {
            throw new IllegalArgumentException("stream name and subjects are required");
        }
        StreamSpec spec = new StreamSpec(name, new ArrayList<>(subjects), maxAgeMs);
        streams.put(name, spec); // re-applied on every connect
        applyStream(requireConnected(), spec);
    }

    private void applyStream(Connection nc, StreamSpec s) throws IOException, JetStreamApiException {
        JetStreamManagement jsm = nc.jetStreamManagement();
        long dupWindow = config.durableDuplicateWindowMs;
        if (s.maxAgeMs > 0 && s.maxAgeMs < dupWindow) dupWindow = s.maxAgeMs; // server rejects window > max age
        StreamInfo existing = null;
        try {
            existing = jsm.getStreamInfo(s.name);
        } catch (JetStreamApiException e) {
            if (e.getApiErrorCode() != ERR_STREAM_NOT_FOUND) throw e;
        }
        if (existing == null) {
            jsm.addStream(StreamConfiguration.builder()
                    .name(s.name)
                    .subjects(s.subjects)
                    .storageType(StorageType.File)
                    .retentionPolicy(RetentionPolicy.Limits)
                    .maxAge(Duration.ofMillis(s.maxAgeMs))
                    .duplicateWindow(Duration.ofMillis(dupWindow))
                    .build());
            events.emit("stream_created", s.name);
            return;
        }
        StreamConfiguration cur = existing.getConfiguration();
        boolean same = new java.util.HashSet<>(cur.getSubjects()).equals(new java.util.HashSet<>(s.subjects))
                && cur.getMaxAge().toMillis() == s.maxAgeMs
                && cur.getDuplicateWindow().toMillis() == dupWindow;
        if (same) return;
        jsm.updateStream(StreamConfiguration.builder(cur)
                .subjects(s.subjects)
                .maxAge(Duration.ofMillis(s.maxAgeMs))
                .duplicateWindow(Duration.ofMillis(dupWindow))
                .build());
        events.emit("stream_updated", s.name);
    }

    /** JetStream publish with a Nats-Msg-Id header; the stream sequence (the original one for a duplicate). */
    long publish(String subject, byte[] data, String msgId) throws IOException, JetStreamApiException {
        if (msgId == null || msgId.isEmpty()) throw new IllegalArgumentException("msgId is required");
        JetStream js = requireConnected().jetStream();
        PublishAck ack = js.publish(subject, data, PublishOptions.builder().messageId(msgId).build());
        return ack.getSeqno();
    }

    // ---- durables -------------------------------------------------------------------------------------

    /**
     * Register (and, when connected, bind now) a durable. Idempotent: the same stream + filter only swaps the
     * handler. While disconnected it is only registered — bound on the next connect.
     */
    void startDurable(String stream, String name, String filterSubject, DurableHandler handler)
            throws IOException, JetStreamApiException {
        if (stream == null || stream.isEmpty() || name == null || name.isEmpty() || filterSubject == null
                || filterSubject.isEmpty() || handler == null) {
            throw new IllegalArgumentException("stream, durable, filterSubject and handler are required");
        }
        if (name.contains(".") || name.contains("*") || name.contains(">") || name.contains(" ")) {
            throw new IllegalArgumentException("durable name must not contain '.', '*', '>' or spaces: " + name);
        }
        Durable d;
        synchronized (lock) {
            d = durables.get(name);
            if (d != null && d.sameSpec(stream, filterSubject)) {
                d.handler = handler;
                if (d.sub != null || connection == null) return;
            } else {
                if (d != null) unsubscribe(d);
                d = new Durable(stream, name, filterSubject, handler);
                durables.put(name, d);
                if (connection == null) return;
            }
        }
        bind(d);
    }

    void stopDurable(String name) {
        synchronized (lock) {
            Durable d = durables.remove(name);
            if (d != null) unsubscribe(d);
        }
    }

    private void unsubscribe(Durable d) {
        if (d.sub != null && d.subDispatcher != null) {
            try {
                d.subDispatcher.unsubscribe(d.sub); // bind mode: the durable stays on the server
            } catch (Throwable ignored) {
                // connection gone
            }
        }
        d.sub = null;
        d.subDispatcher = null;
        synchronized (d.pending) {
            d.pending.clear();
        }
    }

    private void bind(final Durable d) throws IOException, JetStreamApiException {
        Connection nc;
        Dispatcher disp;
        synchronized (lock) {
            nc = connection;
            disp = dispatcher;
            if (nc == null || disp == null || d.sub != null || durables.get(d.name) != d) return;
        }
        JetStreamManagement jsm = nc.jetStreamManagement();
        ConsumerInfo ci = info(nc, d.stream, d.name);
        String deliverSubject;
        String deliverGroup;
        if (ci == null) {
            deliverSubject = "_INBOX.durable." + d.name;
            deliverGroup = d.name;
            createConsumer(nc, d, deliverSubject, deliverGroup);
            events.emit("durable_created", d.name);
        } else {
            ConsumerConfiguration cc = ci.getConsumerConfiguration();
            if (cc.getDeliverSubject() == null) {
                throw new IllegalStateException("consumer " + d.name + " on " + d.stream + " is a pull consumer");
            }
            deliverSubject = cc.getDeliverSubject();
            deliverGroup = cc.getDeliverGroup();
            if (!d.filterSubject.equals(cc.getFilterSubject())) {
                jsm.addOrUpdateConsumer(d.stream, ConsumerConfiguration.builder(cc).filterSubject(d.filterSubject).build());
            }
        }
        final long gen = generation.get();
        MessageHandler handler = new MessageHandler() {
            @Override
            public void onMessage(Message msg) {
                deliver(d, gen, msg);
            }
        };
        JetStreamSubscription sub = nc.jetStream().subscribe(null, deliverGroup, disp, handler, false,
                PushSubscribeOptions.bind(d.stream, d.name));
        synchronized (lock) {
            if (connection != nc || durables.get(d.name) != d || d.sub != null) {
                try {
                    disp.unsubscribe(sub);
                } catch (Throwable ignored) {
                    // raced with a reconnect / stop
                }
                return;
            }
            d.sub = sub;
            d.subDispatcher = disp;
            d.deliverSubject = deliverSubject;
            d.deliverGroup = deliverGroup;
        }
        events.emit("durable_bound", d.name);
    }

    private void createConsumer(Connection nc, Durable d, String deliverSubject, String deliverGroup)
            throws IOException, JetStreamApiException {
        nc.jetStreamManagement().addOrUpdateConsumer(d.stream, ConsumerConfiguration.builder()
                .durable(d.name)
                .deliverSubject(deliverSubject)
                .deliverGroup(deliverGroup)
                .filterSubject(d.filterSubject)
                .deliverPolicy(DeliverPolicy.All)
                .ackPolicy(AckPolicy.Explicit)
                .ackWait(Duration.ofMillis(config.durableAckWaitMs))
                .maxAckPending(config.durableMaxAckPending)
                .build());
    }

    private void deliver(Durable d, long gen, Message msg) {
        if (gen != generation.get()) return; // old connection — redelivered on the current one
        long streamSeq;
        long delivered;
        try {
            streamSeq = msg.metaData().streamSequence();
            delivered = msg.metaData().deliveredCount();
        } catch (Throwable t) {
            return; // not a JetStream message (status etc.)
        }
        String token = gen + "-" + tokenSeq.incrementAndGet();
        synchronized (d.pending) {
            d.pending.put(token, msg);
            int cap = Math.max(2 * config.durableMaxAckPending, 50);
            Iterator<String> it = d.pending.keySet().iterator();
            while (d.pending.size() > cap && it.hasNext()) {
                it.next();
                it.remove(); // never settled → JetStream redelivers it
            }
        }
        boolean taken;
        try {
            DurableHandler h = d.handler;
            taken = h != null && h.onMessage(new DurableMessage(token, d.name, msg.getSubject(), msg.getData(), streamSeq, delivered));
        } catch (Throwable t) {
            events.emit("durable_handler_error", d.name + ": " + t);
            taken = false;
        }
        if (!taken) {
            synchronized (d.pending) {
                d.pending.remove(token);
            }
        }
    }

    // ---- settle ---------------------------------------------------------------------------------------

    static final int ACK = 0;
    static final int NAK = 1;
    static final int TERM = 2;

    /** @return false for an unknown / stale token (nothing sent — JetStream redelivers after ackWait). */
    boolean settle(String token, int kind, long delayMs) {
        if (token == null) return false;
        Message msg = null;
        for (Durable d : durables.values()) {
            synchronized (d.pending) {
                msg = d.pending.remove(token);
            }
            if (msg != null) break;
        }
        if (msg == null) return false;
        try {
            if (kind == ACK) msg.ack();
            else if (kind == TERM) msg.term();
            else if (delayMs > 0) msg.nakWithDelay(Duration.ofMillis(delayMs));
            else msg.nak();
            return true;
        } catch (Throwable t) {
            return false; // connection gone — redelivered
        }
    }

    // ---- info -----------------------------------------------------------------------------------------

    ConsumerStats consumerInfo(String stream, String durable) throws IOException, JetStreamApiException {
        ConsumerInfo ci = info(requireConnected(), stream, durable);
        return ci == null ? null : stats(ci);
    }

    List<ConsumerStats> listConsumers(String stream) throws IOException, JetStreamApiException {
        List<ConsumerStats> out = new ArrayList<>();
        for (ConsumerInfo ci : requireConnected().jetStreamManagement().getConsumers(stream)) out.add(stats(ci));
        return out;
    }

    /** @return false when it did not exist. Also stops a local durable of that name. */
    boolean deleteConsumer(String stream, String durable) throws IOException, JetStreamApiException {
        Connection nc = requireConnected();
        synchronized (lock) {
            Durable d = durables.get(durable);
            if (d != null && d.stream.equals(stream)) {
                durables.remove(durable);
                unsubscribe(d);
            }
        }
        try {
            return nc.jetStreamManagement().deleteConsumer(stream, durable);
        } catch (JetStreamApiException e) {
            if (e.getApiErrorCode() == ERR_CONSUMER_NOT_FOUND || e.getApiErrorCode() == ERR_STREAM_NOT_FOUND) return false;
            throw e;
        }
    }

    private static ConsumerInfo info(Connection nc, String stream, String durable) throws IOException, JetStreamApiException {
        try {
            return nc.jetStreamManagement().getConsumerInfo(stream, durable);
        } catch (JetStreamApiException e) {
            if (e.getApiErrorCode() == ERR_CONSUMER_NOT_FOUND || e.getApiErrorCode() == ERR_STREAM_NOT_FOUND) return null;
            throw e;
        }
    }

    private static ConsumerStats stats(ConsumerInfo ci) {
        return new ConsumerStats(ci.getName(), ci.getNumPending(), ci.getNumAckPending(),
                ci.getAckFloor() == null ? 0 : ci.getAckFloor().getStreamSequence(),
                ci.getDelivered() == null ? 0 : ci.getDelivered().getStreamSequence());
    }

    private Connection requireConnected() {
        Connection nc = connection;
        if (nc == null || nc.getStatus() != Connection.Status.CONNECTED) throw new IllegalStateException("NATS not connected");
        return nc;
    }
}
