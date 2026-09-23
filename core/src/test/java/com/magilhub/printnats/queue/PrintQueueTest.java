package com.magilhub.printnats.queue;

import com.magilhub.printnats.render.RenderResult;
import com.magilhub.printnats.spi.PrinterTransport;
import com.magilhub.printnats.spi.TicketRenderer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PrintQueueTest {
    private static final RetryPolicy FAST_KOT = new RetryPolicy(3, 10, true, false);
    private static final RetryPolicy FAST_RECEIPT = new RetryPolicy(5, 10, false, false);

    private InMemoryJobStore store;
    private Map<String, PrinterConfig> printers;
    private ScriptedTransport transport;
    private PrintQueue queue;
    private final List<String> events = Collections.synchronizedList(new ArrayList<String>());
    private final Map<String, Boolean> skip = new ConcurrentHashMap<>();

    /** Per-printer scripted outcomes (default SUCCESS); records concurrency per printer. */
    static final class ScriptedTransport implements PrinterTransport {
        final Map<String, Deque<PrintResult>> script = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
        final Map<String, Integer> maxInFlight = new ConcurrentHashMap<>();
        final AtomicInteger globalInFlight = new AtomicInteger();
        volatile int maxGlobal;
        final List<String> sent = Collections.synchronizedList(new ArrayList<String>());
        volatile long sleepMs;

        void will(String printerId, PrintResult... results) {
            Deque<PrintResult> d = new ArrayDeque<>();
            Collections.addAll(d, results);
            script.put(printerId, d);
        }

        @Override
        public PrintResult send(PrinterConfig printer, byte[] data) {
            AtomicInteger c = inFlight.computeIfAbsent(printer.id, k -> new AtomicInteger());
            int now = c.incrementAndGet();
            maxInFlight.merge(printer.id, now, Math::max);
            int g = globalInFlight.incrementAndGet();
            if (g > maxGlobal) maxGlobal = g;
            try {
                if (sleepMs > 0) Thread.sleep(sleepMs);
                sent.add(printer.id + ":" + new String(data));
                Deque<PrintResult> d = script.get(printer.id);
                PrintResult r = d == null ? null : d.pollFirst();
                return r == null ? PrintResult.success() : r;
            } catch (InterruptedException e) {
                return new PrintResult(PrintOutcome.AMBIGUOUS, "interrupted");
            } finally {
                c.decrementAndGet();
                globalInFlight.decrementAndGet();
            }
        }
    }

    @Before
    public void setUp() {
        store = new InMemoryJobStore();
        printers = new HashMap<>();
        for (String id : new String[]{"P1", "P2"}) {
            PrinterConfig p = new PrinterConfig();
            p.id = id;
            printers.put(id, p);
        }
        transport = new ScriptedTransport();
        TicketRenderer renderer = new TicketRenderer() {
            @Override
            public RenderResult render(PrintJob job, PrinterConfig printer, long now) {
                if (skip.containsKey(job.jobId)) return RenderResult.skipped("stale: order older than 45 minutes");
                return RenderResult.bytes(job.jobId.getBytes(), "test", 1);
            }
        };
        queue = newQueue(renderer, 2_000);
    }

    private PrintQueue newQueue(TicketRenderer renderer, long watchdogMs) {
        PrintQueue q = new PrintQueue(store, id -> printers.get(id), renderer, transport, null, watchdogMs, FAST_KOT, FAST_RECEIPT);
        q.addListener((job, event) -> events.add(job.jobId + ":" + event));
        return q;
    }

    @After
    public void tearDown() {
        queue.shutdown();
    }

    private static PrintJob job(String id, String printer) {
        PrintJob j = new PrintJob();
        j.jobId = id;
        j.printerId = printer;
        j.orderNo = id;
        return j;
    }

    private void awaitStatus(String jobId, JobStatus status) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            PrintJob j = store.get(jobId);
            if (j != null && j.status == status) return;
            Thread.sleep(5);
        }
        throw new AssertionError(jobId + " never reached " + status + ", now " + store.get(jobId));
    }

    @Test
    public void successPrintsOnceAndReportsCompleted() throws Exception {
        queue.enqueue(job("J1", "P1"));
        awaitStatus("J1", JobStatus.SUCCESS);
        assertEquals(Collections.singletonList("P1:J1"), transport.sent);
        assertTrue(events.contains("J1:inqueue"));
        assertTrue(events.contains("J1:print completed"));
    }

    @Test
    public void connectionFailuresRetryThreeTimesThenFail() throws Exception {
        PrintResult down = new PrintResult(PrintOutcome.CONNECTION_FAILED, "Failed to connect to printer");
        transport.will("P1", down, down, down, down, down);
        queue.enqueue(job("J1", "P1"));
        awaitStatus("J1", JobStatus.FAILED);
        assertEquals("initial attempt + 3 retries", 4, transport.sent.size());
        PrintJob j = store.get("J1");
        assertEquals(3, j.retries);
        assertEquals(FailureClassifier.CATEGORY_OFFLINE, j.category);
        assertTrue(events.contains("J1:print_failed"));
    }

    @Test
    public void retrySucceedsMidway() throws Exception {
        transport.will("P1", new PrintResult(PrintOutcome.CONNECTION_FAILED, "Failed to connect"));
        queue.enqueue(job("J1", "P1"));
        awaitStatus("J1", JobStatus.SUCCESS);
        assertEquals(2, transport.sent.size());
        assertTrue(events.contains("J1:retrying"));
    }

    @Test
    public void physicalFaultIsNeverAutoRetried() throws Exception {
        transport.will("P1", PrintResult.failure("Cover open. Close the printer cover."));
        queue.enqueue(job("J1", "P1"));
        awaitStatus("J1", JobStatus.FAILED);
        assertEquals(1, transport.sent.size());
        assertEquals(FailureClassifier.CATEGORY_COVER_OPEN, store.get("J1").category);
    }

    @Test
    public void ambiguousSendIsNotRetriedByDefault() throws Exception {
        transport.will("P1", PrintResult.failure("Failed to send data to printer."));
        queue.enqueue(job("J1", "P1"));
        awaitStatus("J1", JobStatus.FAILED);
        assertEquals("no duplicate ticket", 1, transport.sent.size());
    }

    @Test
    public void watchdogFailsHungSendAndLaneMovesOn() throws Exception {
        queue.shutdown();
        transport.sleepMs = 400;
        queue = newQueue((job, p, now) -> RenderResult.bytes(job.jobId.getBytes(), "t", 1), 100);
        queue.enqueue(job("J1", "P1"));
        awaitStatus("J1", JobStatus.FAILED);
        assertTrue(store.get("J1").reason.contains("not responding"));
        transport.sleepMs = 0;
        queue.enqueue(job("J2", "P1"));
        awaitStatus("J2", JobStatus.SUCCESS);
    }

    @Test
    public void onePrinterPrintsSeriallyPrintersRunInParallel() throws Exception {
        transport.sleepMs = 60;
        for (int i = 0; i < 5; i++) {
            queue.enqueue(job("A" + i, "P1"));
            queue.enqueue(job("B" + i, "P2"));
        }
        for (int i = 0; i < 5; i++) {
            awaitStatus("A" + i, JobStatus.SUCCESS);
            awaitStatus("B" + i, JobStatus.SUCCESS);
        }
        assertEquals(1, (int) transport.maxInFlight.get("P1"));
        assertEquals(1, (int) transport.maxInFlight.get("P2"));
        assertEquals("both printers were busy at the same time", 2, transport.maxGlobal);
        // FIFO within a printer
        List<String> p1 = new ArrayList<>();
        for (String s : transport.sent) if (s.startsWith("P1:")) p1.add(s);
        assertEquals(java.util.Arrays.asList("P1:A0", "P1:A1", "P1:A2", "P1:A3", "P1:A4"), p1);
    }

    @Test
    public void skippedRenderIsNotSent() throws Exception {
        skip.put("J1", true);
        queue.enqueue(job("J1", "P1"));
        awaitStatus("J1", JobStatus.SKIPPED);
        assertTrue(transport.sent.isEmpty());
        assertTrue(store.get("J1").reason.startsWith("stale"));
    }

    @Test
    public void unknownPrinterFailsImmediately() throws Exception {
        queue.enqueue(job("J1", "NOPE"));
        awaitStatus("J1", JobStatus.FAILED);
        assertTrue(store.get("J1").reason.contains("Printer not found"));
    }

    @Test
    public void breakerHoldsLaterJobsAfterAJobFailsToConnect() throws Exception {
        queue.shutdown();
        queue = newQueue((job, p, now) -> RenderResult.bytes(job.jobId.getBytes(), "t", 1), 2_000);
        queue.setBreaker(1, 400);
        PrintResult down = new PrintResult(PrintOutcome.CONNECTION_FAILED, "Failed to connect");
        transport.will("P1", down, down, down, down); // J1: initial + 3 retries, all down
        queue.enqueue(job("J1", "P1"));
        awaitStatus("J1", JobStatus.FAILED);           // J1 keeps legacy timing (no breaker delay)
        long failedAt = System.currentTimeMillis();
        queue.enqueue(job("J2", "P1"));
        Thread.sleep(150);
        assertEquals("J2 held while breaker open", JobStatus.PENDING, store.get("J2").status);
        assertEquals(4, transport.sent.size());
        awaitStatus("J2", JobStatus.SUCCESS);          // printer back after cooldown → prints on its own
        assertTrue(System.currentTimeMillis() - failedAt >= 350);
        assertEquals("J2 spent no retries while held", 0, store.get("J2").retries);
    }

    @Test
    public void recoverResetsInterruptedJobsAndRunsInOrderNoSequence() throws Exception {
        transport.sleepMs = 20;
        for (String[] r : new String[][]{{"J3", "3", "1"}, {"J1", "1", "2"}, {"J2", "1", "10"}}) {
            PrintJob j = job(r[0], "P1");
            j.orderNo = r[1];
            j.sortOrder = r[2];
            j.status = r[0].equals("J2") ? JobStatus.IN_PROGRESS : JobStatus.PENDING;
            store.insert(j);
        }
        queue.recover();
        for (String id : new String[]{"J1", "J2", "J3"}) awaitStatus(id, JobStatus.SUCCESS);
        assertEquals(java.util.Arrays.asList("P1:J1", "P1:J2", "P1:J3"), transport.sent);
    }

    @Test
    public void manualRetryAndCancel() throws Exception {
        transport.will("P1", PrintResult.failure("Out of paper. Load a new paper roll."));
        queue.enqueue(job("J1", "P1"));
        awaitStatus("J1", JobStatus.FAILED);
        assertTrue(queue.retry("J1"));
        awaitStatus("J1", JobStatus.SUCCESS);
        assertFalse("only FAILED jobs can be retried", queue.retry("J1"));

        transport.will("P1", PrintResult.failure("Cover open."));
        queue.enqueue(job("J2", "P1"));
        awaitStatus("J2", JobStatus.FAILED);
        assertTrue(queue.cancel("J2", "Priya"));
        assertEquals(JobStatus.CANCELLED, store.get("J2").status);
        assertEquals("Cancelled by Priya", store.get("J2").reason);
    }

    @Test
    public void receiptJobsUseReceiptPolicy() throws Exception {
        PrintResult down = new PrintResult(PrintOutcome.CONNECTION_FAILED, "Failed to connect");
        transport.will("P1", down, down, down, down, down, down, down);
        PrintJob j = job("R1", "P1");
        j.kind = JobKind.RECEIPT;
        queue.enqueue(j);
        awaitStatus("R1", JobStatus.FAILED);
        assertEquals("initial + 5 receipt retries", 6, transport.sent.size());
    }
}
