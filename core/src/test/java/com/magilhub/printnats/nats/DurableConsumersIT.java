package com.magilhub.printnats.nats;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.nats.client.Connection;
import io.nats.client.Nats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Host streams + durable consumers (acknowledged app sync) against a real nats-server (skipped if not installed). */
public class DurableConsumersIT {
    private static final String STREAM = "OFFSYNC";
    private static final String SUBJECT = "offsync.L1";
    private static final long SEVEN_DAYS = 7L * 24 * 60 * 60 * 1000;

    @Rule
    public NatsServerRule server = new NatsServerRule();

    private final BlockingQueue<String> connectionEvents = new LinkedBlockingQueue<>();
    private final BlockingQueue<DurableMessage> received = new LinkedBlockingQueue<>();
    private final List<NatsClient> clients = new ArrayList<>();
    private Connection be;

    private final DurableHandler collect = new DurableHandler() {
        @Override
        public boolean onMessage(DurableMessage m) {
            received.add(m);
            return true;
        }
    };

    private NatsClient start() throws Exception {
        NatsConfig c = new NatsConfig();
        c.serverUrls = server.url();
        c.locationId = "L1";
        c.deviceId = "D1";
        c.testMode = true;
        c.initialBackoffMs = 200;
        c.maxBackoffMs = 400;
        c.reconnectWaitMs = 200;
        c.durableAckWaitMs = 1_000;
        NatsClient client = new NatsClient(c, new NatsEvents() {
            public void onPrintMessage(InboundMessage m) { }
            public void onStatusEvent(String s, byte[] d) { }
            public void onStatusHistoryEvent(String s, byte[] d) { }
            public void onConnectionEvent(String type, String detail) {
                connectionEvents.add(type);
            }
        }, null);
        clients.add(client);
        connectionEvents.clear();
        client.start();
        awaitEvent("subscribed");
        client.ensureStream(STREAM, Arrays.asList("offsync.>"), SEVEN_DAYS);
        return client;
    }

    private void awaitEvent(String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (type.equals(connectionEvents.poll(100, TimeUnit.MILLISECONDS))) return;
        }
        throw new AssertionError("no '" + type + "' connection event");
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String s(DurableMessage m) {
        return new String(m.data, StandardCharsets.UTF_8);
    }

    private long streamCount() throws Exception {
        if (be == null) be = Nats.connect(server.url());
        return be.jetStreamManagement().getStreamInfo(STREAM).getStreamState().getMsgCount();
    }

    @After
    public void tearDown() throws Exception {
        for (NatsClient c : clients) c.stop();
        if (be != null) be.close();
    }

    @Test
    public void sameMsgIdIsStoredOnce() throws Exception {
        NatsClient client = start();
        long first = client.publishDurable(SUBJECT, b("a"), "evt-1");
        long again = client.publishDurable(SUBJECT, b("a"), "evt-1");
        assertEquals("duplicate resolves the original sequence", first, again);
        assertEquals(1, streamCount());
        long other = client.publishDurable(SUBJECT, b("b"), "evt-2");
        assertEquals(first + 1, other);
        assertEquals(2, streamCount());

        // ensureStream is idempotent and keeps the 2-min dedup window
        client.ensureStream(STREAM, Arrays.asList("offsync.>"), SEVEN_DAYS);
        assertEquals(120_000, be.jetStreamManagement().getStreamInfo(STREAM).getConfiguration()
                .getDuplicateWindow().toMillis());
        assertEquals(2, streamCount());
    }

    @Test
    public void publishDurableRejectsWhenNoStreamOrNotConnected() throws Exception {
        NatsClient client = start();
        try {
            client.publishDurable("nostream.x", b("a"), "id-1");
            fail("no stream → no PubAck → throws");
        } catch (Exception expected) {
            // ok
        }
        server.stopServer();
        Thread.sleep(300);
        try {
            client.publishDurable(SUBJECT, b("a"), "id-2");
            fail("not connected → throws");
        } catch (Exception expected) {
            // ok
        }
    }

    @Test
    public void redeliveredUntilAcked() throws Exception {
        NatsClient client = start();
        client.startDurable(STREAM, "offsync-D1", SUBJECT, collect);
        client.startDurable(STREAM, "offsync-D1", SUBJECT, collect); // idempotent: still one delivery per message
        client.publishDurable(SUBJECT, b("x"), "evt-x");

        DurableMessage first = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(first);
        assertEquals(1, first.deliveredCount);
        assertEquals("offsync-D1", first.durable);
        assertEquals(SUBJECT, first.subject);
        assertEquals("x", s(first));
        assertNull("no duplicate delivery from the second startDurable", received.poll(500, TimeUnit.MILLISECONDS));

        DurableMessage again = received.poll(5, TimeUnit.SECONDS); // not acked → redelivered after ackWait (1 s)
        assertNotNull("redelivered", again);
        assertEquals(first.streamSeq, again.streamSeq);
        assertEquals(2, again.deliveredCount);
        assertFalse("unknown token: no-op",
                client.ackDurable("bogus-token"));
        assertTrue(client.ackDurable(again.token));
        assertFalse("token is single-use", client.ackDurable(again.token));
        assertNull("acked → not redelivered", received.poll(2_500, TimeUnit.MILLISECONDS));
    }

    @Test
    public void deliverNewStartsAtTheStreamTail() throws Exception {
        NatsClient client = start();
        client.startDurable(STREAM, "offsync-OLD", SUBJECT, collect); // makes sure the stream exists
        client.publishDurable(SUBJECT, b("before"), "evt-before");
        DurableMessage old = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(old);
        assertTrue(client.ackDurable(old.token));

        client.startDurable(STREAM, "offsync-NEW", SUBJECT, true, collect); // new tablet after a full resync
        client.publishDurable(SUBJECT, b("after"), "evt-after");
        List<String> got = new ArrayList<>();
        DurableMessage m;
        while ((m = received.poll(2, TimeUnit.SECONDS)) != null) {
            if ("offsync-NEW".equals(m.durable)) got.add(s(m));
            client.ackDurable(m.token);
        }
        assertEquals("only messages after creation", java.util.Collections.singletonList("after"), got);
    }

    @Test
    public void nakWithDelayRedeliversLater() throws Exception {
        NatsClient client = start();
        client.startDurable(STREAM, "offsync-D1", SUBJECT, collect);
        client.publishDurable(SUBJECT, b("n"), "evt-n");
        DurableMessage m = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(m);
        assertTrue(client.nakDurable(m.token, 0));
        DurableMessage again = received.poll(900, TimeUnit.MILLISECONDS);
        assertNotNull("nak(0) → prompt redelivery", again);
        assertTrue(client.nakDurable(again.token, 1_500));
        assertNull("nak(1500) → not before the delay", received.poll(1_000, TimeUnit.MILLISECONDS));
        DurableMessage third = received.poll(3, TimeUnit.SECONDS);
        assertNotNull(third);
        assertTrue(client.ackDurable(third.token));
    }

    @Test
    public void handlerDecliningLeavesMessageForRedelivery() throws Exception {
        NatsClient client = start();
        final BlockingQueue<Long> seen = new LinkedBlockingQueue<>();
        client.startDurable(STREAM, "offsync-D1", SUBJECT, new DurableHandler() {
            @Override
            public boolean onMessage(DurableMessage m) {
                seen.add(m.deliveredCount);
                return false; // "JS not running"
            }
        });
        client.publishDurable(SUBJECT, b("d"), "evt-d");
        assertEquals(Long.valueOf(1), seen.poll(5, TimeUnit.SECONDS));
        // handler replaced (idempotent start with the same stream + filter) → the redelivery reaches the new one
        client.startDurable(STREAM, "offsync-D1", SUBJECT, collect);
        DurableMessage m = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(m);
        assertEquals(2, m.deliveredCount);
        assertTrue(client.ackDurable(m.token));
    }

    @Test
    public void resumesFromAckFloorAfterClientRestart() throws Exception {
        NatsClient first = start();
        first.startDurable(STREAM, "offsync-D1", SUBJECT, collect);
        for (int i = 1; i <= 3; i++) first.publishDurable(SUBJECT, b("m" + i), "evt-" + i);
        List<DurableMessage> got = new ArrayList<>();
        for (int i = 0; i < 3; i++) got.add(received.poll(5, TimeUnit.SECONDS));
        assertEquals("m1", s(got.get(0)));
        assertTrue(first.ackDurable(got.get(0).token));
        assertTrue(first.ackDurable(got.get(1).token));
        // m3 left unacked; client "dies"
        first.stop();
        received.clear();

        NatsClient second = start();
        second.publishDurable(SUBJECT, b("m4"), "evt-4"); // arrived while the tablet was away
        second.startDurable(STREAM, "offsync-D1", SUBJECT, collect);
        Set<String> after = new HashSet<>();
        DurableMessage m;
        while ((m = received.poll(3, TimeUnit.SECONDS)) != null) {
            after.add(s(m));
            second.ackDurable(m.token);
        }
        assertEquals(new HashSet<>(Arrays.asList("m3", "m4")), after);
        ConsumerStats st = second.consumerInfo(STREAM, "offsync-D1");
        assertEquals(4, st.ackFloorStreamSeq);
        assertEquals(0, st.numPending);
        assertEquals(0, st.numAckPending);
    }

    @Test
    public void consumerInfoAndListCounts() throws Exception {
        NatsClient client = start();
        assertNull("no consumer yet", client.consumerInfo(STREAM, "offsync-D1"));
        assertNull("unknown stream", client.consumerInfo("NOPE", "offsync-D1"));
        for (int i = 1; i <= 3; i++) client.publishDurable(SUBJECT, b("m" + i), "evt-" + i);

        client.startDurable(STREAM, "offsync-D1", SUBJECT, collect); // DeliverPolicy.All: gets the 3 older ones
        List<DurableMessage> got = new ArrayList<>();
        for (int i = 0; i < 3; i++) got.add(received.poll(5, TimeUnit.SECONDS));
        ConsumerStats st = client.consumerInfo(STREAM, "offsync-D1");
        assertEquals("offsync-D1", st.durable);
        assertEquals(0, st.numPending);
        assertEquals(3, st.numAckPending);
        assertEquals(0, st.ackFloorStreamSeq);
        assertEquals(3, st.delivered);
        for (DurableMessage m : got) client.ackDurable(m.token);
        Thread.sleep(300);
        st = client.consumerInfo(STREAM, "offsync-D1");
        assertEquals(0, st.numAckPending);
        assertEquals(3, st.ackFloorStreamSeq);

        client.stopDurable("offsync-D1"); // consumer stays; the tablet is "away"
        client.publishDurable(SUBJECT, b("m4"), "evt-4");
        client.publishDurable(SUBJECT, b("m5"), "evt-5");
        assertEquals(2, client.consumerInfo(STREAM, "offsync-D1").numPending);

        client.startDurable(STREAM, "offsync-D2", SUBJECT, collect);
        List<ConsumerStats> all = client.listConsumers(STREAM);
        Set<String> names = new HashSet<>();
        for (ConsumerStats c : all) names.add(c.durable);
        assertEquals(new HashSet<>(Arrays.asList("offsync-D1", "offsync-D2")), names);

        assertTrue(client.deleteConsumer(STREAM, "offsync-D1"));
        assertNull(client.consumerInfo(STREAM, "offsync-D1"));
        assertFalse("already gone", client.deleteConsumer(STREAM, "offsync-D1"));
    }

    @Test
    public void reconnectReestablishesTheDurable() throws Throwable {
        NatsClient client = start();
        client.startDurable(STREAM, "offsync-D1", SUBJECT, collect);
        client.publishDurable(SUBJECT, b("before"), "evt-before");
        DurableMessage m = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(m);
        assertTrue(client.ackDurable(m.token));

        // Outage during which the server also loses the consumer.
        be = Nats.connect(server.url());
        be.jetStreamManagement().deleteConsumer(STREAM, "offsync-D1");
        be.close();
        be = null;
        server.stopServer();
        Thread.sleep(300);
        server.restartServer();
        awaitEvent("durable_recreated");

        be = Nats.connect(server.url());
        be.jetStream().publish(SUBJECT, b("after"));
        // The lost consumer's ack floor is gone: re-created with DeliverPolicy.All, so the stream replays from the
        // start (the app's versioned apply skips what it already has) and new messages keep flowing.
        List<String> got = new ArrayList<>();
        DurableMessage m2;
        while ((m2 = received.poll(3, TimeUnit.SECONDS)) != null) {
            got.add(s(m2));
            assertTrue(client.ackDurable(m2.token));
        }
        assertEquals(Arrays.asList("before", "after"), got);
        assertEquals(2, client.consumerInfo(STREAM, "offsync-D1").ackFloorStreamSeq);
    }

    @Test
    public void freshConnectionRebindsAndOldTokensAreStale() throws Exception {
        NatsClient client = start();
        client.startDurable(STREAM, "offsync-D1", SUBJECT, collect);
        client.publishDurable(SUBJECT, b("x"), "evt-x");
        DurableMessage m = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(m);

        // A full stop/start of the same client = new Connection: durables re-bound, old tokens dropped.
        client.stop();
        connectionEvents.clear();
        client.start();
        awaitEvent("durable_bound");
        assertFalse("stale token: no-op, no throw", client.ackDurable(m.token));
        DurableMessage again = received.poll(5, TimeUnit.SECONDS);
        assertNotNull("unacked message redelivered on the new connection", again);
        assertEquals(m.streamSeq, again.streamSeq);
        assertTrue(client.ackDurable(again.token));
    }
}
