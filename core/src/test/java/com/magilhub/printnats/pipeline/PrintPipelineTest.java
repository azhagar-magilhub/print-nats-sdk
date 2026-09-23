package com.magilhub.printnats.pipeline;

import com.google.gson.JsonObject;
import com.magilhub.printnats.nats.InboundMessage;
import com.magilhub.printnats.nats.NatsClient;
import com.magilhub.printnats.nats.NatsConfig;
import com.magilhub.printnats.nats.StatusPublisher;
import com.magilhub.printnats.queue.InMemoryJobStore;
import com.magilhub.printnats.queue.JobStatus;
import com.magilhub.printnats.queue.PrintJob;
import com.magilhub.printnats.queue.PrintQueue;
import com.magilhub.printnats.queue.PrintResult;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.render.RenderResult;
import com.magilhub.printnats.rules.MessageRules;
import com.magilhub.printnats.rules.RulesFixtures;
import com.magilhub.printnats.rules.Session;
import com.magilhub.printnats.spi.InboundStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PrintPipelineTest {
    private InMemoryJobStore jobs;
    private InMemoryInboundStore inbound;
    private PrintQueue queue;
    private PrintPipeline pipeline;
    private RulesFixtures.FakeLookup lookup;
    private final List<String> sent = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> log = Collections.synchronizedList(new ArrayList<String>());

    static final class FakeMessage implements InboundMessage {
        final String id;
        final byte[] data;
        int acks;
        int naks;

        FakeMessage(String id, String messageType, JsonObject md) {
            this.id = id;
            JsonObject env = new JsonObject();
            env.addProperty("messageType", messageType);
            env.addProperty("messageData", md.toString());
            this.data = env.toString().getBytes(StandardCharsets.UTF_8);
        }

        public String subject() { return "printkot.L1.D1"; }
        public String messageId() { return id; }
        public long streamSequence() { return 7; }
        public byte[] data() { return data; }
        public void ack() { acks++; }
        public void nak() { naks++; }
    }

    private static PrinterConfig printer(String id, PrinterConfig.Purpose purpose, String cuisine) {
        PrinterConfig p = new PrinterConfig();
        p.id = id;
        p.purpose = purpose;
        p.cuisineId = cuisine;
        return p;
    }

    @Before
    public void setUp() {
        final List<PrinterConfig> printers = Arrays.asList(
                printer("EXPO", PrinterConfig.Purpose.MASTER_KOT, null),
                printer("BAR", PrinterConfig.Purpose.STATION_KOT, "C-BAR"),
                printer("RCPT", PrinterConfig.Purpose.RECEIPT, null));
        jobs = new InMemoryJobStore();
        inbound = new InMemoryInboundStore();
        PrintQueue.PrinterLookup byId = id -> {
            for (PrinterConfig p : printers) if (p.id.equals(id)) return p;
            return null;
        };
        queue = new PrintQueue(jobs, byId, (job, p, now) -> RenderResult.bytes(job.jobId.getBytes(), "t", 1),
                (p, data) -> {
                    sent.add(p.id);
                    return PrintResult.success();
                }, null);
        final Session session = new Session();
        session.locationId = "L1";
        session.deviceId = "D1";
        lookup = new RulesFixtures.FakeLookup(RulesFixtures.order("OT-P"));
        NatsConfig nc = new NatsConfig();
        StatusPublisher status = new StatusPublisher(new NatsClient(nc, null, null), byId,
                (f, c) -> log.add(f + c), null, "L1", "D1");
        pipeline = new PrintPipeline(inbound, queue, () -> printers, status,
                (r, s) -> new MessageRules(lookup, r, s), RulesFixtures.restaurant(null), session,
                (f, c) -> log.add(f + c), null);
    }

    @After
    public void tearDown() {
        pipeline.shutdown();
        queue.shutdown();
    }

    private void awaitJobs(int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (jobs.findByStatus(JobStatus.SUCCESS).size() >= n) return;
            Thread.sleep(10);
        }
        throw new AssertionError("expected " + n + " printed jobs, got " + jobs.findByStatus(JobStatus.SUCCESS));
    }

    @Test
    public void acceptsRoutesAndAcksOnce() throws Exception {
        FakeMessage m = new FakeMessage("M1", "PRINT_RECEIPT", RulesFixtures.messageData(null));
        pipeline.onPrintMessage(m);
        assertEquals(1, m.acks);
        awaitJobs(2); // EXPO (master items) + BAR (C-BAR items); C-TANDOOR has no printer
        assertTrue(sent.containsAll(Arrays.asList("EXPO", "BAR")));
        boolean received = false;
        synchronized (log) {
            for (String l : log) if (l.startsWith("natsStatus_") && l.contains("\"status\":\"received\"")) received = true;
        }
        assertTrue("received event published", received);
    }

    @Test
    public void duplicateDeliveryIsAckedButNotPrintedTwice() throws Exception {
        FakeMessage a = new FakeMessage("M1", "PRINT_RECEIPT", RulesFixtures.messageData(null));
        FakeMessage b = new FakeMessage("M1", "PRINT_RECEIPT", RulesFixtures.messageData(null));
        pipeline.onPrintMessage(a);
        pipeline.onPrintMessage(b);
        awaitJobs(2);
        Thread.sleep(300);
        assertEquals("legacy never acked duplicates", 1, b.acks);
        assertEquals(2, sent.size());
    }

    @Test
    public void otherLocationAndMissingOrderNoAreIgnored() {
        JsonObject other = RulesFixtures.messageData(null);
        other.addProperty("locationId", "L2");
        FakeMessage m1 = new FakeMessage("M1", "PRINT_RECEIPT", other);
        pipeline.onPrintMessage(m1);
        JsonObject noOrder = RulesFixtures.messageData(null);
        noOrder.remove("orderNo");
        FakeMessage m2 = new FakeMessage("M2", "PRINT_RECEIPT", noOrder);
        pipeline.onPrintMessage(m2);
        assertEquals(1, m1.acks);
        assertEquals(1, m2.acks);
        assertTrue(inbound.pending().isEmpty());
        assertTrue(lookup.calls.isEmpty());
    }

    @Test
    public void crashReplayDoesNotQueueTwice() throws Exception {
        // Simulate: message recorded + acked, then the process died before processing.
        JsonObject md = RulesFixtures.messageData(null);
        inbound.record(new InboundStore.Inbound("042217|M1", "PRINT_RECEIPT", md.toString(), "M1", System.currentTimeMillis()));
        pipeline.recover();
        awaitJobs(2);
        // A second recover (e.g. another restart) must not re-queue the same tickets.
        inbound.record(new InboundStore.Inbound("042217|M1b", "PRINT_RECEIPT", md.toString(), "M1", System.currentTimeMillis()));
        pipeline.recover();
        Thread.sleep(300);
        List<PrintJob> all = jobs.findByStatus(JobStatus.values());
        assertEquals("deterministic job ids → idempotent", 2, all.size());
    }

    @Test
    public void receiptMessageQueuesBuiltReceiptOnReceiptPrinter() throws Exception {
        FakeMessage m = new FakeMessage("R1", "PRINT_RECEIPT", RulesFixtures.messageData("60"));
        pipeline.onPrintMessage(m);
        awaitJobs(1);
        List<PrintJob> done = jobs.findByStatus(JobStatus.SUCCESS);
        assertEquals(1, done.size());
        PrintJob j = done.get(0);
        assertEquals("RCPT", j.printerId);
        assertEquals(com.magilhub.printnats.queue.JobKind.RECEIPT, j.kind);
        JsonObject payload = com.magilhub.printnats.rules.Json.parseObject(j.payloadJson);
        assertTrue("printReceiptJson payload", payload.get("receiptJson").getAsString().contains("\"orderNo\""));
        assertEquals(false, payload.get("textReceipt").getAsBoolean());
        assertEquals(Arrays.asList("RCPT"), sent);
    }

    @Test
    public void fcmReceiptRequestWithoutOrderNoPrintsOnceOnReceiptPrinter() throws Exception {
        // Backend receipt requests (orderStatus 60) only come over FCM and carry orderId, not orderNo.
        JsonObject md = RulesFixtures.messageData("60");
        md.remove("orderNo");
        String fcmId = "0:1790174735450798%9bb5b9b7f9fd7ecd";
        assertTrue(pipeline.onHostMessage("PRINT_RECEIPT", md.toString(), fcmId));
        assertEquals("FCM redelivery", false, pipeline.onHostMessage("PRINT_RECEIPT", md.toString(), fcmId));
        awaitJobs(1);
        Thread.sleep(200);
        assertEquals(Arrays.asList("RCPT"), sent);
    }

    @Test
    public void kotArrivingOnNatsAndFcmPrintsOnce() throws Exception {
        FakeMessage nats = new FakeMessage("M-KOT", "PRINT_RECEIPT", RulesFixtures.messageData(null));
        pipeline.onPrintMessage(nats);
        assertEquals("FCM copy carries the NATS message id → duplicate", false,
                pipeline.onHostMessage("PRINT_RECEIPT", RulesFixtures.messageData(null).toString(), "M-KOT"));
        awaitJobs(2);
        Thread.sleep(300);
        assertEquals(2, sent.size());
    }

    @Test
    public void hostMessageForAnotherLocationIsIgnored() {
        JsonObject md = RulesFixtures.messageData("60");
        md.addProperty("locationId", "L2");
        assertEquals(false, pipeline.onHostMessage("PRINT_RECEIPT", md.toString(), "F1"));
        assertTrue(inbound.pending().isEmpty());
    }

    @Test
    public void natsKotForAnOrderThisDevicePrintedIsDroppedWhenEnabled() throws Exception {
        pipeline.setSuppressNatsKotAfterHostPrint(true);
        JsonObject order = RulesFixtures.order("OT-P");
        assertEquals(2, pipeline.printKot(order, null, false)); // device printed it at order time
        awaitJobs(2);
        FakeMessage m = new FakeMessage("M-LATER", "PRINT_RECEIPT", RulesFixtures.messageData(null)); // BE re-sends after sync
        pipeline.onPrintMessage(m);
        assertEquals("acked, not redelivered", 1, m.acks);
        Thread.sleep(400);
        assertEquals("no second ticket", 2, sent.size());
    }

    @Test
    public void natsKotStillPrintsByDefault() throws Exception {
        JsonObject order = RulesFixtures.order("OT-P");
        assertEquals(2, pipeline.printKot(order, null, false));
        awaitJobs(2);
        pipeline.onPrintMessage(new FakeMessage("M-LATER", "PRINT_RECEIPT", RulesFixtures.messageData(null)));
        awaitJobs(4);
        assertEquals("MerchantApp behaviour unchanged", 4, sent.size());
    }

    @Test
    public void hostUiKotIsDedupedWithinTwoSeconds() throws Exception {
        JsonObject order = RulesFixtures.order("OT-P");
        assertEquals(2, pipeline.printKot(order, null, false));
        assertEquals("double tap within 2 s", 0, pipeline.printKot(order, null, false));
        assertEquals("void of same order is a different ticket", 2, pipeline.printKot(order, null, true));
    }
}
