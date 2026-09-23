package com.magilhub.printnats;

import com.magilhub.printnats.nats.NatsConfig;
import com.magilhub.printnats.nats.NatsServerRule;
import com.magilhub.printnats.rules.Session;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Subscription;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** CartVue-style core-NATS messaging through the SDK connection (no JetStream). */
public class AppMessagingIT {
    @Rule
    public NatsServerRule server = new NatsServerRule();

    private PrintNats sdk;
    private Connection other;

    @After
    public void tearDown() throws Exception {
        if (sdk != null) sdk.stop();
        if (other != null) other.close();
    }

    @Test
    public void subscribePublishAndOfflineBuffer() throws Exception {
        final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        Session s = new Session();
        s.locationId = "L1";
        s.deviceId = "D1";
        NatsConfig nc = new NatsConfig();
        nc.serverUrls = server.url();
        nc.testMode = true;
        nc.initialBackoffMs = 200;
        sdk = PrintNats.builder().nats(nc).session(s).listener(new PrintNats.Listener() {
            @Override
            public void onJobEvent(com.magilhub.printnats.queue.PrintJob job, String event) {
            }

            @Override
            public void onStatusEvent(String subject, byte[] data, boolean history) {
            }

            @Override
            public void onConnectionEvent(String type, String detail) {
            }

            @Override
            public void onAppMessage(String subject, byte[] data) {
                received.add(subject + "|" + new String(data, StandardCharsets.UTF_8));
            }
        }).build();

        sdk.subscribeApp("cartvue.L1.D1");
        // published before the connection exists → buffered, sent on connect
        sdk.publishApp("cartvue.L1.DISPLAY", "early".getBytes(StandardCharsets.UTF_8));

        other = Nats.connect(server.url());
        Subscription displayInbox = other.subscribe("cartvue.L1.DISPLAY");
        other.flush(Duration.ofSeconds(2));

        sdk.start();
        long deadline = System.currentTimeMillis() + 10_000;
        while (!sdk.isNatsConnected() && System.currentTimeMillis() < deadline) Thread.sleep(50);

        Message early = displayInbox.nextMessage(Duration.ofSeconds(5));
        assertNotNull("buffered publish delivered after connect", early);
        assertEquals("early", new String(early.getData(), StandardCharsets.UTF_8));

        Thread.sleep(300); // dispatcher subscription registered
        other.publish("cartvue.L1.D1", "{\"type\":\"LOYALTY_CUSTOMER_LINKED\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("cartvue.L1.D1|{\"type\":\"LOYALTY_CUSTOMER_LINKED\"}", received.poll(5, TimeUnit.SECONDS));

        sdk.publishApp("cartvue.L1.DISPLAY", "live".getBytes(StandardCharsets.UTF_8));
        Message live = displayInbox.nextMessage(Duration.ofSeconds(5));
        assertNotNull(live);
        assertEquals("live", new String(live.getData(), StandardCharsets.UTF_8));

        sdk.unsubscribeApp("cartvue.L1.D1");
        Thread.sleep(300);
        other.publish("cartvue.L1.D1", "after".getBytes(StandardCharsets.UTF_8));
        assertEquals("nothing after unsubscribe", null, received.poll(700, TimeUnit.MILLISECONDS));
    }
}
