package com.magilhub.printnats.nats;

import com.magilhub.printnats.spi.LogSink;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Subscription;
import io.nats.client.api.PublishAck;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Integration tests against a real local nats-server with JetStream (skipped if not installed). */
public class NatsClientIT {
    @Rule
    public NatsServerRule server = new NatsServerRule();

    private final BlockingQueue<InboundMessage> received = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> statusEvents = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> connectionEvents = new LinkedBlockingQueue<>();
    private final List<String> logLines = Collections.synchronizedList(new ArrayList<String>());
    private NatsClient client;
    private Connection be; // plays the backend

    private NatsConfig config(boolean master) {
        NatsConfig c = new NatsConfig();
        c.serverUrls = server.url();
        c.locationId = "L1";
        c.deviceId = "D1";
        c.isMaster = master;
        c.testMode = true;
        c.initialBackoffMs = 200;
        c.maxBackoffMs = 400;
        c.reconnectWaitMs = 200;
        c.ackWaitMs = 1_000;
        return c;
    }

    private NatsClient start(NatsConfig c) throws Exception {
        client = new NatsClient(c, new NatsEvents() {
            @Override
            public void onPrintMessage(InboundMessage m) {
                received.add(m);
            }

            @Override
            public void onStatusEvent(String subject, byte[] data) {
                statusEvents.add(subject + "|" + new String(data, StandardCharsets.UTF_8));
            }

            @Override
            public void onStatusHistoryEvent(String subject, byte[] data) {
            }

            @Override
            public void onConnectionEvent(String type, String detail) {
                connectionEvents.add(type);
            }
        }, new LogSink() {
            @Override
            public void append(String fileName, String content) {
                logLines.add(fileName + content);
            }
        });
        client.start();
        awaitEvent("subscribed");
        be = Nats.connect(server.url());
        return client;
    }

    private void awaitEvent(String type) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String e = connectionEvents.poll(100, TimeUnit.MILLISECONDS);
            if (type.equals(e)) return;
        }
        throw new AssertionError("no '" + type + "' connection event");
    }

    private PublishAck bePublish(String msgId, String body) throws Exception {
        Headers h = new Headers();
        if (msgId != null) h.add("Nats-Msg-Id", msgId);
        return be.jetStream().publish(NatsMessage.builder().subject("printkot.L1.D1").headers(h)
                .data(body.getBytes(StandardCharsets.UTF_8)).build());
    }

    @After
    public void tearDown() throws Exception {
        if (client != null) client.stop();
        if (be != null) be.close();
    }

    @Test
    public void deliversMessageWithIdAndAcksBackend() throws Exception {
        start(config(false));
        Subscription acks = be.subscribe("printack.printkot.L1.D1");
        be.flush(Duration.ofSeconds(2));

        bePublish("msg-1", "{\"messageType\":\"PRINT_RECEIPT\",\"messageData\":\"{}\"}");
        InboundMessage m = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(m);
        assertEquals("msg-1", m.messageId());
        assertEquals("printkot.L1.D1", m.subject());
        assertTrue(new String(m.data(), StandardCharsets.UTF_8).contains("PRINT_RECEIPT"));
        m.ack();

        io.nats.client.Message ack = acks.nextMessage(Duration.ofSeconds(5));
        assertNotNull("backend receipt ack on printack.<deviceSubject>", ack);
        assertEquals("{\"messageId\":\"msg-1\"}", new String(ack.getData(), StandardCharsets.UTF_8));
    }

    @Test
    public void unackedMessageIsRedeliveredAckedIsNot() throws Exception {
        start(config(false));
        bePublish("keep", "{}");
        InboundMessage first = received.poll(5, TimeUnit.SECONDS);
        assertNotNull(first);
        // do NOT ack → redelivered after ackWait (1 s)
        InboundMessage again = received.poll(5, TimeUnit.SECONDS);
        assertNotNull("redelivered after ackWait", again);
        assertEquals("keep", again.messageId());
        again.ack();
        assertNull("acked message is not redelivered", received.poll(2_500, TimeUnit.MILLISECONDS));
    }

    @Test
    public void nakRedeliversImmediately() throws Exception {
        start(config(false));
        bePublish("nak-me", "{}");
        InboundMessage first = received.poll(5, TimeUnit.SECONDS);
        first.nak();
        InboundMessage again = received.poll(900, TimeUnit.MILLISECONDS);
        assertNotNull("nak → prompt redelivery (before ackWait)", again);
        again.ack();
    }

    @Test
    public void publishesWhileOfflineAreBufferedThenFlushedAndLogged() throws Throwable {
        start(config(false));
        Subscription statuses = be.subscribe("printeventstatus.L1.D1");
        be.flush(Duration.ofSeconds(2));
        assertTrue(client.publish("printeventstatus.L1.D1", "online".getBytes()));
        assertNotNull(statuses.nextMessage(Duration.ofSeconds(3)));

        be.close();
        be = null;
        server.stopServer();
        Thread.sleep(300);
        assertFalse("offline publish is buffered", client.publish("printeventstatus.L1.D1", "while-down".getBytes()));
        assertEquals(1, client.pendingPublishCount());

        server.restartServer();
        long deadline = System.currentTimeMillis() + 15_000;
        while (client.pendingPublishCount() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertEquals("flushed after reconnect", 0, client.pendingPublishCount());
        boolean logged = false;
        synchronized (logLines) {
            for (String l : logLines) {
                if (l.startsWith("natsStatus_PUBLISHED (from reconnect buffer)") && l.contains("while-down")) logged = true;
            }
        }
        assertTrue("flush written to natsStatus_ log", logged);
    }

    @Test
    public void bufferedEventsSurviveARestart() throws Exception {
        // First "process": offline, buffers one status event into a (shared, i.e. durable) outbox, then dies.
        InMemoryOutbox durable = new InMemoryOutbox();
        NatsConfig offline = config(false);
        offline.serverUrls = "nats://127.0.0.1:1";
        NatsClient dead = new NatsClient(offline, new NatsEvents() {
            public void onPrintMessage(InboundMessage m) { }
            public void onStatusEvent(String s, byte[] d) { }
            public void onStatusHistoryEvent(String s, byte[] d) { }
            public void onConnectionEvent(String t, String d) { }
        }, null, durable);
        assertFalse(dead.publish("printeventstatus.L1.D1", "from-before-restart".getBytes()));
        assertEquals(1, durable.size());

        // Second "process" with the same outbox: flushes the backlog on its first connect.
        be = Nats.connect(server.url());
        Subscription statuses = be.subscribe("printeventstatus.L1.D1");
        be.flush(Duration.ofSeconds(2));
        client = new NatsClient(config(false), new NatsEvents() {
            public void onPrintMessage(InboundMessage m) { }
            public void onStatusEvent(String s, byte[] d) { }
            public void onStatusHistoryEvent(String s, byte[] d) { }
            public void onConnectionEvent(String t, String d) { connectionEvents.add(t); }
        }, (f, c) -> logLines.add(f + c), durable);
        client.start();
        io.nats.client.Message m = statuses.nextMessage(Duration.ofSeconds(10));
        assertNotNull("backlog published after restart", m);
        assertEquals("from-before-restart", new String(m.getData(), StandardCharsets.UTF_8));
        long deadline = System.currentTimeMillis() + 5_000; // removed right after the confirmed publish
        while (durable.size() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(20);
        assertEquals(0, durable.size());
    }

    @Test
    public void bufferCapDropsOldestAndLogsIt() throws Exception {
        NatsConfig c = config(false);
        c.pendingPublishCap = 2;
        c.serverUrls = "nats://127.0.0.1:1"; // never connects
        client = new NatsClient(c, new NatsEvents() {
            public void onPrintMessage(InboundMessage m) { }
            public void onStatusEvent(String s, byte[] d) { }
            public void onStatusHistoryEvent(String s, byte[] d) { }
            public void onConnectionEvent(String t, String d) { }
        }, (f, content) -> logLines.add(f + content));
        client.publish("s", "a".getBytes());
        client.publish("s", "b".getBytes());
        client.publish("s", "c".getBytes());
        assertEquals(2, client.pendingPublishCount());
        assertTrue(logLines.get(0).startsWith("natsStatus_DROPPED (buffer full, never published) | s | a"));
    }

    @Test
    public void masterSeesWholeLocationNonMasterOnlyOwn() throws Exception {
        start(config(false));
        be.flush(Duration.ofSeconds(1));
        be.jetStream().publish("printeventstatus.L1.OTHER", "other".getBytes());
        be.jetStream().publish("printeventstatus.L1.D1", "mine".getBytes());
        String first = statusEvents.poll(5, TimeUnit.SECONDS);
        assertEquals("printeventstatus.L1.D1|mine", first);
        assertNull(statusEvents.poll(500, TimeUnit.MILLISECONDS));

        client.updateMasterRole(true);
        Thread.sleep(300);
        be.jetStream().publish("printeventstatus.L1.OTHER", "other-2".getBytes());
        assertEquals("printeventstatus.L1.OTHER|other-2", statusEvents.poll(5, TimeUnit.SECONDS));
    }
}
