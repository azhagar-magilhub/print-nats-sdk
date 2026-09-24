package com.magilhub.printnats;

import com.google.gson.JsonObject;
import com.magilhub.printnats.nats.LocalNatsServer;
import com.magilhub.printnats.nats.NatsConfig;
import com.magilhub.printnats.nats.NatsServerRule;
import com.magilhub.printnats.queue.InMemoryJobStore;
import com.magilhub.printnats.queue.PrintResult;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.rules.RulesFixtures;
import com.magilhub.printnats.rules.Session;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.nats.client.Connection;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.PushSubscribeOptions;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * LAN mode: the master runs the shop's nats-server (LocalNatsServer); devices talk only to it. The master alone
 * connects to the cloud (here a second nats-server) for backend KOTs and forwards print status there.
 */
public class LanModeIT {
    @Rule
    public NatsServerRule cloudServer = new NatsServerRule();

    private static final String SECRET = "test-secret";
    private LocalNatsServer local;
    private int localPort;
    private final List<PrintNats> sdks = new ArrayList<>();
    private Connection watcher;

    final class Device {
        final List<String> sent = Collections.synchronizedList(new ArrayList<String>());
        final List<String> log = Collections.synchronizedList(new ArrayList<String>());
        PrintNats sdk;
    }

    @Before
    public void startLocal() throws Exception {
        String bin = new File("/opt/homebrew/bin/nats-server").canExecute() ? "/opt/homebrew/bin/nats-server"
                : new File("/usr/local/bin/nats-server").canExecute() ? "/usr/local/bin/nats-server" : null;
        Assume.assumeTrue("nats-server not installed", bin != null);
        try (ServerSocket s = new ServerSocket(0)) {
            localPort = s.getLocalPort();
        }
        File dir = Files.createTempDirectory("lan-js").toFile();
        local = new LocalNatsServer(bin, dir, localPort, NatsConfig.lanToken(SECRET, "L1"), null);
        local.start();
        assertTrue("local server ready", local.awaitReady(10_000));
    }

    @After
    public void tearDown() throws Exception {
        for (PrintNats s : sdks) s.stop();
        if (watcher != null) watcher.close();
        if (local != null) local.stop();
    }

    private Device device(String deviceId, boolean master) {
        final Device d = new Device();
        Session s = new Session();
        s.locationId = "L1";
        s.deviceId = deviceId;
        s.accessToken = "tok";
        NatsConfig nc = new NatsConfig();
        nc.serverUrls = "nats://127.0.0.1:" + localPort;
        nc.authToken = NatsConfig.lanToken(SECRET, "L1");
        nc.lanMode = true;
        nc.locationId = "L1";
        nc.deviceId = deviceId;
        nc.isMaster = master;
        nc.initialBackoffMs = 200;
        nc.testMode = true; // lets the cloud connection self-provision PRINTKOT on the fake cloud
        if (master) nc.cloudServerUrls = cloudServer.url();
        PrinterConfig expo = new PrinterConfig();
        expo.id = "EXPO-" + deviceId;
        expo.purpose = PrinterConfig.Purpose.MASTER_KOT;
        expo.stationName = "-";
        expo.address = "10.255.255.1";
        d.sdk = PrintNats.builder().nats(nc).session(s).restaurant(RulesFixtures.restaurant(null).raw)
                .printers(Collections.singletonList(expo))
                .jobStore(new InMemoryJobStore())
                .relayToMaster(true)
                .transport((p, data) -> {
                    d.sent.add(p.id);
                    return PrintResult.success();
                })
                .log((file, content) -> d.log.add(file + content))
                .build();
        sdks.add(d.sdk);
        return d;
    }

    private static JsonObject freshOrder() {
        JsonObject order = RulesFixtures.order("OT-P");
        order.addProperty("orderTime", java.time.Instant.now().toString());
        order.addProperty("orderDate", java.time.Instant.now().toString());
        return order;
    }

    private static void await(String what, long ms, Check c) throws Exception {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (c.ok()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    interface Check {
        boolean ok() throws Exception;
    }

    @Test
    public void clientKotGoesToMasterOverTheLocalServerOnly() throws Exception {
        Device master = device("M1", true);
        Device client = device("C1", false);
        master.sdk.start();
        client.sdk.start();
        await("both on the local server", 10_000, () -> master.sdk.isNatsConnected() && client.sdk.isNatsConnected());
        assertFalse("client has no cloud link", client.sdk.hasCloudLink());
        assertTrue(master.sdk.hasCloudLink());
        Thread.sleep(300);

        assertEquals(1, client.sdk.printKot(freshOrder(), "T4", false));
        await("master printed the client's KOT", 10_000, () -> master.sent.contains("EXPO-M1"));
        assertEquals("client printed nothing itself", 0, client.sent.size());
    }

    @Test
    public void wrongTokenIsRejected() {
        io.nats.client.Options o = new io.nats.client.Options.Builder().server("nats://127.0.0.1:" + localPort)
                .token("nope".toCharArray()).maxReconnects(0).connectionTimeout(Duration.ofSeconds(2)).build();
        boolean connected;
        try {
            Nats.connect(o).close();
            connected = true;
        } catch (Exception expected) {
            connected = false;
        }
        assertFalse("the local server requires the location's LAN token", connected);
    }

    @Test
    public void everyDevicesStatusReachesTheCloudThroughTheMaster() throws Exception {
        watcher = Nats.connect(cloudServer.url());
        Device master = device("M1", true);
        Device client = device("C1", false);
        master.sdk.start();
        client.sdk.start();
        await("master cloud link up", 10_000, master.sdk::isCloudConnected);
        await("client on the local server", 10_000, client.sdk::isNatsConnected);
        Thread.sleep(500);

        // the client's KOT is printed by the master, which publishes its status on the LOCAL server only; the
        // status bridge forwards it to the cloud stream
        client.sdk.printKot(freshOrder(), "T4", false);
        JetStreamSubscription sub = watcher.jetStream().subscribe("printeventstatus.L1.M1", PushSubscribeOptions.builder()
                .configuration(ConsumerConfiguration.builder().deliverPolicy(DeliverPolicy.All).build()).build());
        Message m = null;
        long deadline = System.currentTimeMillis() + 15_000;
        while (m == null && System.currentTimeMillis() < deadline) m = sub.nextMessage(Duration.ofMillis(500));
        assertNotNull("status forwarded from the local server to the cloud stream", m);
        assertTrue(new String(m.getData(), StandardCharsets.UTF_8).contains("\"locationId\":\"L1\""));
    }

    @Test
    public void backendKotOnTheCloudPrintsOnTheMaster() throws Exception {
        Device master = device("M1", true);
        master.sdk.start();
        await("master cloud link up", 10_000, master.sdk::isCloudConnected);
        Thread.sleep(800); // test-mode PRINTKOT stream + consumer provisioned on the cloud
        watcher = Nats.connect(cloudServer.url());
        io.nats.client.Subscription acks = watcher.subscribe("printack.printkot.L1.M1");
        watcher.flush(Duration.ofSeconds(2));
        JsonObject envelope = new JsonObject();
        envelope.addProperty("messageType", "PRINT_RECEIPT");
        envelope.addProperty("messageData", RulesFixtures.messageData(null).toString());
        io.nats.client.impl.Headers h = new io.nats.client.impl.Headers();
        h.add("Nats-Msg-Id", "MSG-LAN-1");
        watcher.jetStream().publish(io.nats.client.impl.NatsMessage.builder().subject("printkot.L1.M1").headers(h)
                .data(envelope.toString().getBytes(StandardCharsets.UTF_8)).build());
        Message ack = acks.nextMessage(Duration.ofSeconds(10));
        assertNotNull("master's cloud link consumed the backend message and acked it to the backend", ack);
        assertTrue(new String(ack.getData(), StandardCharsets.UTF_8).contains("MSG-LAN-1"));
    }
}
