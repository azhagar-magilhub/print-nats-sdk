package com.magilhub.printnats;

import com.google.gson.JsonObject;
import com.magilhub.printnats.nats.NatsConfig;
import com.magilhub.printnats.nats.NatsServerRule;
import com.magilhub.printnats.pipeline.PrintRelay;
import com.magilhub.printnats.queue.InMemoryJobStore;
import com.magilhub.printnats.queue.JobStatus;
import com.magilhub.printnats.queue.PrintJob;
import com.magilhub.printnats.queue.PrintResult;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.rules.Json;
import com.magilhub.printnats.rules.RulesFixtures;
import com.magilhub.printnats.rules.Session;
import com.magilhub.printnats.transport.RelayTransport;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Master/client print relay over a real nats-server: only the master device prints. */
public class PrintRelayIT {
    @Rule
    public NatsServerRule server = new NatsServerRule();

    private final List<PrintNats> sdks = new ArrayList<>();
    private Connection other;
    private HttpServer api;

    /** One device: its printer sends, job store and log. */
    final class Device {
        final List<String> sent = Collections.synchronizedList(new ArrayList<String>());
        final List<String> log = Collections.synchronizedList(new ArrayList<String>());
        final InMemoryJobStore jobs = new InMemoryJobStore();
        PrintNats sdk;

        int sentCount() {
            return sent.size();
        }

        PrintJob job(String jobId) {
            return jobs.get(jobId);
        }

        boolean logged(String needle) {
            synchronized (log) {
                for (String l : log) if (l.contains(needle)) return true;
            }
            return false;
        }
    }

    @After
    public void tearDown() throws Exception {
        for (PrintNats s : sdks) s.stop();
        if (other != null) other.close();
        if (api != null) api.stop(0);
    }

    private Device device(String deviceId, boolean master, boolean relayToMaster, String apiBaseUrl) {
        final Device d = new Device();
        Session s = new Session();
        s.locationId = "L1";
        s.deviceId = deviceId;
        s.apiBaseUrl = apiBaseUrl;
        s.accessToken = "tok";
        NatsConfig nc = new NatsConfig();
        nc.serverUrls = server.url();
        nc.testMode = true;
        nc.initialBackoffMs = 200;
        nc.isMaster = master;
        PrinterConfig expo = new PrinterConfig();
        expo.id = "EXPO-" + deviceId;
        expo.purpose = PrinterConfig.Purpose.MASTER_KOT;
        expo.stationName = "-";
        expo.address = "10.255.255.1"; // never dialled: fake transport
        d.sdk = PrintNats.builder().nats(nc).session(s).restaurant(RulesFixtures.restaurant(null).raw)
                .printers(Collections.singletonList(expo))
                .jobStore(d.jobs)
                .relayToMaster(relayToMaster)
                .transport((p, data) -> {
                    d.sent.add(p.id);
                    return PrintResult.success();
                })
                .log((file, content) -> d.log.add(file + content))
                .build();
        sdks.add(d.sdk);
        return d;
    }

    private static void awaitConnected(PrintNats sdk) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!sdk.isNatsConnected() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertTrue("connected", sdk.isNatsConnected());
        Thread.sleep(300); // dispatchers + relay subscription registered
    }

    private static JsonObject freshOrder() {
        JsonObject order = RulesFixtures.order("OT-P");
        order.addProperty("orderTime", java.time.Instant.now().toString()); // passes the 45-min freshness guard
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

    private static String onlyRelayJobId(Device d) {
        for (PrintJob j : d.jobs.findByStatus(JobStatus.values())) {
            if (PrinterConfig.RELAY_MASTER_ID.equals(j.printerId)) return j.jobId;
        }
        throw new AssertionError("no relay job");
    }

    // (a)
    @Test
    public void clientRelaysKotAndOnlyMasterPrintsOnce() throws Exception {
        Device master = device("M1", true, true, null);
        Device client = device("C1", false, true, null);
        master.sdk.start();
        client.sdk.start();
        awaitConnected(master.sdk);
        awaitConnected(client.sdk);

        assertEquals("one relay job", 1, client.sdk.printKot(freshOrder(), "T4", false));
        final String jobId = onlyRelayJobId(client);
        await("client relay job SUCCESS", 10_000, () -> client.job(jobId).status == JobStatus.SUCCESS);
        await("master printed", 10_000, () -> master.sentCount() >= 1);
        Thread.sleep(700);
        assertEquals("master printed exactly once", 1, master.sentCount());
        assertEquals("master printed on its own printer", "EXPO-M1", master.sent.get(0));
        assertEquals("client printed nothing itself", 0, client.sentCount());
        PrintJob j = client.job(jobId);
        assertEquals("relay#master", j.printerId);
        assertEquals("042217", j.orderNo);
        assertEquals("1", j.sortOrder);
        assertEquals("0171", j.kotNo);
    }

    // (b)
    @Test
    public void noMasterJobWaitsVisiblyThenPrintsWhenMasterStarts() throws Exception {
        Device client = device("C1", false, true, null);
        client.sdk.start();
        awaitConnected(client.sdk);

        client.sdk.printKot(freshOrder(), null, false);
        final String jobId = onlyRelayJobId(client);
        await("relay job in failedJobs", 10_000, () -> {
            for (PrintJob j : client.sdk.failedJobs()) if (j.jobId.equals(jobId)) return true;
            return false;
        });
        PrintJob waiting = null;
        for (PrintJob j : client.sdk.failedJobs()) if (j.jobId.equals(jobId)) waiting = j;
        assertNotNull(waiting);
        assertEquals(RelayTransport.WAITING_FOR_MASTER, waiting.reason);
        assertEquals(PrinterConfig.RELAY_MASTER_ID, waiting.printerId);
        assertTrue("still auto-retrying", waiting.status == JobStatus.PENDING || waiting.status == JobStatus.IN_PROGRESS);
        assertTrue(waiting.retries >= 1);

        Device master = device("M1", true, true, null);
        long masterStart = System.currentTimeMillis();
        master.sdk.start();
        await("client relay job SUCCESS", 15_000, () -> client.job(jobId).status == JobStatus.SUCCESS);
        assertTrue("re-kicked by the master's online announcement, not the backoff",
                System.currentTimeMillis() - masterStart < 5_000);
        await("master printed", 10_000, () -> master.sentCount() == 1);
        assertFalse("gone from the failed queue", client.sdk.failedJobs().stream().anyMatch(j -> j.jobId.equals(jobId)));
        assertEquals(0, client.sentCount());
    }

    // (c)
    @Test
    public void duplicateRelayIdPrintsOnce() throws Exception {
        Device master = device("M1", true, true, null);
        master.sdk.start();
        awaitConnected(master.sdk);
        other = Nats.connect(server.url());

        JsonObject req = PrintRelay.request(PrintRelay.KOT, freshOrder(), null, false, 0, "C9");
        byte[] body = req.toString().getBytes(StandardCharsets.UTF_8);
        Message first = other.request(PrintRelay.subject("L1"), body, Duration.ofSeconds(5));
        Message second = other.request(PrintRelay.subject("L1"), body, Duration.ofSeconds(5));
        assertNotNull(first);
        assertNotNull(second);
        JsonObject r1 = Json.parseObject(new String(first.getData(), StandardCharsets.UTF_8));
        JsonObject r2 = Json.parseObject(new String(second.getData(), StandardCharsets.UTF_8));
        assertTrue(Json.isTrueBoolean(r1, "ok"));
        assertEquals("1", Json.str(r1, "tickets"));
        assertTrue("duplicate still acknowledged", Json.isTrueBoolean(r2, "ok"));
        assertEquals("0", Json.str(r2, "tickets"));
        await("printed", 5_000, () -> master.sentCount() >= 1);
        Thread.sleep(700);
        assertEquals(1, master.sentCount());
    }

    // (d)
    @Test
    public void backendKotAfterRelayedKotIsSuppressedOnMaster() throws Exception {
        final JsonObject order = freshOrder();
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/api", exchange -> {
            byte[] b = order.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, b.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(b);
            }
        });
        api.start();
        String base = "http://127.0.0.1:" + api.getAddress().getPort() + "/api";
        // master WITHOUT suppressNatsKotAfterHostPrint: relayed KOTs are always remembered
        Device master = device("M1", true, false, base);
        Device client = device("C1", false, true, base);
        master.sdk.start();
        client.sdk.start();
        awaitConnected(master.sdk);
        awaitConnected(client.sdk);

        client.sdk.printKot(order, null, false);
        await("master printed relayed KOT", 10_000, () -> master.sentCount() == 1);

        other = Nats.connect(server.url());
        JsonObject envelope = new JsonObject();
        envelope.addProperty("messageType", "PRINT_RECEIPT");
        envelope.addProperty("messageData", RulesFixtures.messageData(null).toString());
        Headers h = new Headers();
        h.add("Nats-Msg-Id", "MSG-AFTER-SYNC");
        other.jetStream().publish(NatsMessage.builder().subject("printkot.L1.M1").headers(h)
                .data(envelope.toString().getBytes(StandardCharsets.UTF_8)).build());
        await("backend KOT handled", 10_000, () -> master.logged("already printed on this device"));
        Thread.sleep(700);
        assertEquals("no second ticket", 1, master.sentCount());
    }

    // (e)
    @Test
    public void relayReceiptWithoutMasterFailsWithinTimeout() throws Exception {
        Device client = device("C1", false, true, null);
        client.sdk.start();
        awaitConnected(client.sdk);
        long t0 = System.currentTimeMillis();
        assertEquals(-1, client.sdk.relayReceipt(freshOrder(), 0, 2000));
        assertTrue("returned within the timeout", System.currentTimeMillis() - t0 < 3000);
        assertFalse(client.sdk.hasReceiptPrinter());
    }

    @Test
    public void relayReceiptPrintsOnMastersReceiptPrinter() throws Exception {
        Device master = device("M1", true, true, null);
        PrinterConfig receipt = new PrinterConfig();
        receipt.id = "RCPT";
        receipt.purpose = PrinterConfig.Purpose.RECEIPT;
        receipt.address = "10.255.255.2";
        List<PrinterConfig> list = new ArrayList<>(master.sdk.printers());
        list.add(receipt);
        master.sdk.setPrinters(list);
        assertTrue(master.sdk.hasReceiptPrinter());
        Device client = device("C1", false, true, null);
        master.sdk.start();
        client.sdk.start();
        awaitConnected(master.sdk);
        awaitConnected(client.sdk);
        assertEquals(1, client.sdk.relayReceipt(freshOrder(), 0, 8000));
    }

    @Test
    public void hookAdjustsRelayedOrderBeforePrinting() throws Exception {
        Device master = device("M1", true, true, null);
        final List<String> seen = Collections.synchronizedList(new ArrayList<String>());
        master.sdk.setRelayOrderHook((kind, o) -> {
            seen.add(kind);
            o.addProperty("kotNo", "0999");
            return o;
        });
        Device client = device("C1", false, true, null);
        master.sdk.start();
        client.sdk.start();
        awaitConnected(master.sdk);
        awaitConnected(client.sdk);
        client.sdk.printKot(freshOrder(), null, false);
        await("master printed", 10_000, () -> master.sentCount() == 1);
        assertEquals(Collections.singletonList("KOT"), seen);
        boolean kot999 = false;
        for (PrintJob j : master.jobs.findByStatus(JobStatus.SUCCESS)) if ("0999".equals(j.kotNo)) kot999 = true;
        assertTrue("printed with the hook's KOT number", kot999);
    }

    @Test
    public void queuedRelayJobsPrintLocallyWhenDeviceBecomesMaster() throws Exception {
        Device client = device("C1", false, true, null);
        client.sdk.start();
        awaitConnected(client.sdk);
        client.sdk.printKot(freshOrder(), null, false);
        final String jobId = onlyRelayJobId(client);
        await("waiting", 10_000, () -> client.job(jobId).retries >= 1);
        client.sdk.updateMasterRole(true);
        await("relay job SUCCESS", 10_000, () -> client.job(jobId).status == JobStatus.SUCCESS);
        await("printed locally", 10_000, () -> client.sentCount() == 1);
        assertEquals("EXPO-C1", client.sent.get(0));
        // new KOTs on the (now) master print directly
        JsonObject next = freshOrder();
        next.addProperty("sortOrder", 2);
        assertEquals(1, client.sdk.printKot(next, null, false));
        await("printed directly", 10_000, () -> client.sentCount() == 2);
    }

    @Test
    public void relayOffKeepsLocalPrinting() throws Exception {
        Device d = device("C1", false, false, null);
        assertEquals("MerchantApp behaviour: client prints itself", 1, d.sdk.printKot(freshOrder(), null, false));
        d.sdk.start();
        await("printed locally", 10_000, () -> d.sentCount() == 1);
    }
}
