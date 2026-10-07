package com.magilhub.printnats.queue;

import com.magilhub.printnats.render.RenderResult;
import com.magilhub.printnats.spi.JobStore;
import com.magilhub.printnats.spi.LogSink;
import com.magilhub.printnats.spi.PrinterTransport;
import com.magilhub.printnats.spi.TicketRenderer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Print queue: one serial lane per printer (one ticket at a time per printer, printers in parallel),
 * persisted jobs, auto-retry per {@link RetryPolicy}, per-printer {@link CircuitBreaker}, and a watchdog
 * around every send. Behaviour follows MerchantApp's station queue (Release-25.1): a retry goes to the back
 * of its printer's lane; physical faults are never auto-retried; a hung send is failed after the watchdog.
 */
public final class PrintQueue {
    public static final long DEFAULT_WATCHDOG_MS = 150_000; // PrintFrameworkModule STATION_PRINT_WATCHDOG_MS

    public interface PrinterLookup {
        PrinterConfig get(String printerId);

        /**
         * The job's printer row no longer exists (printers were reconfigured after it was queued): the row that now
         * does the same job, or null. Default: none.
         */
        default PrinterConfig replacementFor(PrintJob job) {
            return null;
        }
    }

    private final JobStore store;
    private final PrinterLookup printers;
    private final TicketRenderer renderer;
    private final PrinterTransport transport;
    private final LogSink log;
    private final long watchdogMs;
    private final RetryPolicy kotPolicy;
    private final RetryPolicy receiptPolicy;
    private volatile RetryPolicy relayPolicy = RetryPolicy.relay();
    /** Sends relay jobs ({@link PrinterConfig#RELAY_MASTER_ID}) to the master device; null = relay not wired. */
    private volatile PrinterTransport relayTransport;
    private static final PrinterConfig RELAY_PRINTER = PrinterConfig.relayMaster();
    private final List<JobListener> listeners = new CopyOnWriteArrayList<>();

    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(named("pq-sched"));
    private final ExecutorService sendExecutor = Executors.newCachedThreadPool(named("pq-send"));
    private final ExecutorService laneExecutor = Executors.newCachedThreadPool(named("pq-lane"));

    public PrintQueue(JobStore store, PrinterLookup printers, TicketRenderer renderer, PrinterTransport transport,
                      LogSink log, long watchdogMs, RetryPolicy kotPolicy, RetryPolicy receiptPolicy) {
        this.store = store;
        this.printers = printers;
        this.renderer = renderer;
        this.transport = transport;
        this.log = log == null ? LogSink.NONE : log;
        this.watchdogMs = watchdogMs;
        this.kotPolicy = kotPolicy;
        this.receiptPolicy = receiptPolicy;
    }

    public PrintQueue(JobStore store, PrinterLookup printers, TicketRenderer renderer, PrinterTransport transport, LogSink log) {
        this(store, printers, renderer, transport, log, DEFAULT_WATCHDOG_MS, RetryPolicy.kot(), RetryPolicy.receipt());
    }

    // Behaviour changes vs the legacy app are OFF by default (not yet reviewed); see docs/parity-matrix.md.
    private volatile int breakerThreshold = 0;
    private volatile long breakerCooldownMs = 60_000;
    private volatile boolean resetRetriesOnManualRetry = false;

    /**
     * Circuit breaker: pause a printer for {@code cooldownMs} after {@code threshold} consecutive jobs finally
     * failed to connect. {@code threshold <= 0} disables it (default, = legacy). Recommended: 2, 60 000. Call before use.
     */
    public void setBreaker(int threshold, long cooldownMs) {
        this.breakerThreshold = threshold;
        this.breakerCooldownMs = cooldownMs;
    }

    /** Manual retry gives a fresh auto-retry budget (legacy keeps the old count). Default false. */
    public void setResetRetriesOnManualRetry(boolean reset) {
        this.resetRetriesOnManualRetry = reset;
    }

    /** Transport for relay jobs (client → master device). Their payload is sent as-is (no rendering). */
    public void setRelayTransport(PrinterTransport t) {
        this.relayTransport = t;
    }

    public void setRelayPolicy(RetryPolicy p) {
        this.relayPolicy = p;
    }

    public void addListener(JobListener l) {
        listeners.add(l);
    }

    // ---- public API --------------------------------------------------------------------------------

    /** Persist a new job (status PENDING) and schedule it on its printer's lane. */
    public void enqueue(PrintJob job) {
        long now = System.currentTimeMillis();
        job.status = JobStatus.PENDING;
        if (job.createdAt == 0) job.createdAt = now;
        job.updatedAt = now;
        store.insert(job);
        emit(job, "inqueue");
        laneFor(job.printerId).offer(job.jobId);
    }

    /**
     * Crash recovery at startup. Jobs left IN_PROGRESS by a killed process go back to PENDING (legacy
     * sweep behaviour — may reprint a ticket that did print), then all PENDING jobs are scheduled in
     * orderNo, sortOrder order (legacy checkPrintQue sort).
     */
    public void recover() {
        for (PrintJob j : store.findByStatus(JobStatus.IN_PROGRESS)) {
            store.compareAndSetStatus(j.jobId, JobStatus.IN_PROGRESS, JobStatus.PENDING);
            log.append("print_", "Info:: Recovered interrupted job " + j);
        }
        List<PrintJob> pending = new ArrayList<>(store.findByStatus(JobStatus.PENDING));
        Collections.sort(pending, new Comparator<PrintJob>() {
            @Override
            public int compare(PrintJob a, PrintJob b) {
                int c = compareNumeric(a.orderNo, b.orderNo);
                return c != 0 ? c : compareNumeric(a.sortOrder, b.sortOrder);
            }
        });
        for (PrintJob j : pending) laneFor(j.printerId).offer(j.jobId);
    }

    /**
     * Manual retry from the Failed Print Queue: FAILED → PENDING with a fresh retry budget. A relay job that is
     * still auto-retrying (PENDING, shown in {@link #failedJobs()}) is attempted again right away.
     */
    public boolean retry(String jobId) {
        PrintJob j = store.get(jobId);
        if (j != null && j.status == JobStatus.PENDING && PrinterConfig.isRelay(j.printerId)) {
            laneFor(j.printerId).offer(j.jobId);
            return true;
        }
        if (j == null || j.status != JobStatus.FAILED) return false;
        j.status = JobStatus.PENDING;
        if (resetRetriesOnManualRetry) j.retries = 0;
        j.reason = null;
        j.category = null;
        j.updatedAt = System.currentTimeMillis();
        store.update(j);
        emit(j, "inqueue");
        laneFor(j.printerId).offer(j.jobId);
        return true;
    }

    public boolean cancel(String jobId, String staffName) {
        PrintJob j = store.get(jobId);
        if (j == null || (j.status != JobStatus.FAILED && j.status != JobStatus.PENDING)) return false;
        j.status = JobStatus.CANCELLED;
        j.reason = "Cancelled by " + (staffName == null ? "staff" : staffName);
        j.updatedAt = System.currentTimeMillis();
        store.update(j);
        emit(j, "cancelled");
        return true;
    }

    public int retryAllForPrinter(String printerId) {
        int n = 0;
        if (PrinterConfig.isRelay(printerId)) return kickRelay();
        for (PrintJob j : store.findByStatus(JobStatus.FAILED)) {
            if (printerId.equals(j.printerId) && retry(j.jobId)) n++;
        }
        return n;
    }

    public int cancelAllForPrinter(String printerId, String staffName) {
        int n = 0;
        for (PrintJob j : store.findByStatus(JobStatus.FAILED, JobStatus.PENDING)) {
            if (printerId.equals(j.printerId) && cancel(j.jobId, staffName)) n++;
        }
        return n;
    }

    /**
     * isFlushDB: legacy deleted every row of the print table (pending and failed) without publishing.
     * Here they are marked CANCELLED ("Flushed") silently — no status events, like legacy.
     */
    public int flushAll() {
        int n = 0;
        for (PrintJob j : store.findByStatus(JobStatus.PENDING, JobStatus.FAILED)) {
            j.status = JobStatus.CANCELLED;
            j.reason = "Flushed (isFlushDB)";
            j.updatedAt = System.currentTimeMillis();
            store.update(j);
            n++;
        }
        return n;
    }

    /** Delete finished jobs (SUCCESS/SKIPPED/CANCELLED) last updated before {@code cutoffMillis}. FAILED stay. */
    public int pruneFinished(long cutoffMillis) {
        int n = 0;
        for (PrintJob j : store.findByStatus(JobStatus.SUCCESS, JobStatus.SKIPPED, JobStatus.CANCELLED)) {
            if (j.updatedAt < cutoffMillis) {
                store.delete(j.jobId);
                n++;
            }
        }
        return n;
    }

    public boolean exists(String jobId) {
        return store.get(jobId) != null;
    }

    /**
     * The Failed Print Queue: FAILED jobs, plus relay jobs still waiting for the master device (PENDING or
     * IN_PROGRESS after at least one failed attempt — status stays PENDING because they keep auto-retrying;
     * {@code reason} says why, e.g. "Waiting for master device"). Retry/cancel work on those too.
     */
    public List<PrintJob> failedJobs() {
        List<PrintJob> out = new ArrayList<>(store.findByStatus(JobStatus.FAILED));
        for (PrintJob j : store.findByStatus(JobStatus.PENDING, JobStatus.IN_PROGRESS)) {
            if (PrinterConfig.isRelay(j.printerId) && j.retries > 0) out.add(j);
        }
        return out;
    }

    /**
     * Attempt every waiting relay job now (NATS (re)connected, master role changed): PENDING ones are offered to the
     * relay lane, FAILED ones (the master answered with an error) go back to PENDING. Returns the number kicked.
     */
    public int kickRelay() {
        int n = 0;
        for (PrintJob j : store.findByStatus(JobStatus.PENDING, JobStatus.FAILED)) {
            if (!PrinterConfig.isRelay(j.printerId)) continue;
            if (j.status == JobStatus.FAILED) {
                if (retry(j.jobId)) n++;
            } else {
                laneFor(j.printerId).offer(j.jobId);
                n++;
            }
        }
        return n;
    }

    public void shutdown() {
        scheduler.shutdownNow();
        laneExecutor.shutdownNow();
        sendExecutor.shutdownNow();
    }

    // ---- lanes -------------------------------------------------------------------------------------

    /** One lane (and breaker) per physical printer; unknown printers get their own lane and fail fast. */
    private PrinterConfig printerFor(String printerId) {
        return PrinterConfig.isRelay(printerId) ? RELAY_PRINTER : printers.get(printerId);
    }

    private String laneKeyOf(String printerId) {
        PrinterConfig p = printerFor(printerId);
        return p == null ? "id:" + printerId : p.laneKey();
    }

    private Lane laneFor(String printerId) {
        return lane(laneKeyOf(printerId));
    }

    private Lane lane(String laneKey) {
        Lane l = lanes.get(laneKey);
        if (l == null) {
            Lane created = new Lane(laneKey);
            l = lanes.putIfAbsent(laneKey, created);
            if (l == null) l = created;
        }
        return l;
    }

    private CircuitBreaker breaker(String laneKey) {
        CircuitBreaker b = breakers.get(laneKey);
        if (b == null) {
            CircuitBreaker created = new CircuitBreaker(breakerThreshold, breakerCooldownMs); // threshold<=0 → never opens
            b = breakers.putIfAbsent(laneKey, created);
            if (b == null) b = created;
        }
        return b;
    }

    /** Serial worker for one printer. At most one job of this printer is being sent at any time. */
    private final class Lane implements Runnable {
        private final String laneKey;
        private final ArrayDeque<String> queue = new ArrayDeque<>();
        private boolean running;

        Lane(String laneKey) {
            this.laneKey = laneKey;
        }

        synchronized void offer(String jobId) {
            if (!queue.contains(jobId)) queue.addLast(jobId);
            kick();
        }

        private synchronized void kick() {
            if (!running && !queue.isEmpty()) {
                running = true;
                laneExecutor.execute(this);
            }
        }

        @Override
        public void run() {
            while (true) {
                String jobId;
                synchronized (this) {
                    long wait = breaker(laneKey).waitMs(System.currentTimeMillis());
                    if (queue.isEmpty() || wait > 0) {
                        running = false;
                        if (wait > 0 && !queue.isEmpty()) {
                            scheduler.schedule(new Runnable() {
                                @Override
                                public void run() {
                                    kick();
                                }
                            }, wait, TimeUnit.MILLISECONDS);
                        }
                        return;
                    }
                    jobId = queue.pollFirst();
                }
                try {
                    process(jobId);
                } catch (RuntimeException e) {
                    log.append("print_", "Exception:: queue worker " + laneKey + " job " + jobId + ": " + e);
                }
            }
        }
    }

    // ---- one job -----------------------------------------------------------------------------------

    private void process(final String jobId) {
        if (!store.compareAndSetStatus(jobId, JobStatus.PENDING, JobStatus.IN_PROGRESS)) {
            return; // cancelled, already handled, or claimed elsewhere
        }
        final PrintJob job = store.get(jobId);
        final boolean relay = PrinterConfig.isRelay(job.printerId);
        PrinterConfig found = printerFor(job.printerId);
        if (found == null) {
            found = printers.replacementFor(job);
            if (found != null) {
                log.append("print_", "Info:: Printer " + job.printerId + " no longer configured — job " + job.jobId
                        + " re-routed to " + found.id + " (" + found.resolvedStationName() + ")");
                job.printerId = found.id;
                job.status = JobStatus.PENDING;
                job.updatedAt = System.currentTimeMillis();
                store.update(job);
                // print from the replacement printer's own lane (one ticket at a time per physical printer)
                laneFor(job.printerId).offer(job.jobId);
                return;
            }
        }
        final PrinterConfig printer = found;
        if (printer == null) {
            fail(job, new PrintResult(PrintOutcome.FAULT, "Printer not found (id=" + job.printerId + ")"));
            return;
        }

        final PrinterTransport sender = relay ? relayTransport : transport;
        if (sender == null) {
            fail(job, new PrintResult(PrintOutcome.FAULT, "Relay to master device not available"));
            return;
        }
        RenderResult rendered;
        if (relay) {
            // the payload IS the relay request; the master renders it for its own printers
            rendered = RenderResult.bytes(job.payloadJson == null ? new byte[0]
                    : job.payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8), "relay", 1);
        } else {
            try {
                rendered = renderer.render(job, printer, System.currentTimeMillis());
            } catch (RuntimeException e) {
                fail(job, new PrintResult(PrintOutcome.FAULT, "Render error: " + e));
                return;
            }
            if (rendered.isSkipped()) {
                finish(job, JobStatus.SKIPPED, rendered.skipReason, "skipped");
                return;
            }
        }

        final byte[] bytes = rendered.bytes;
        final RenderResult paced = !relay && rendered.isPaced() && transport instanceof com.magilhub.printnats.spi.PacedTransport ? rendered : null;
        PrintResult result;
        Future<PrintResult> f = sendExecutor.submit(new java.util.concurrent.Callable<PrintResult>() {
            @Override
            public PrintResult call() {
                if (paced != null) {
                    return ((com.magilhub.printnats.spi.PacedTransport) transport).sendPaced(printer, bytes, paced.chunkEnds, paced.chunkWaitsMs);
                }
                return sender.send(printer, bytes);
            }
        });
        try {
            result = f.get(watchdogMs, TimeUnit.MILLISECONDS);
            if (result == null) result = new PrintResult(PrintOutcome.AMBIGUOUS, "Transport returned no result");
        } catch (TimeoutException e) {
            f.cancel(true);
            result = new PrintResult(PrintOutcome.TIMEOUT, "Printer not responding (no answer in " + watchdogMs / 1000 + "s)");
        } catch (ExecutionException e) {
            result = new PrintResult(PrintOutcome.AMBIGUOUS, "Transport error: " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = new PrintResult(PrintOutcome.AMBIGUOUS, "Interrupted while printing");
        }

        if (result.outcome == PrintOutcome.SUCCESS) {
            if (!relay) breaker(printer.laneKey()).record(PrintOutcome.SUCCESS, System.currentTimeMillis());
            // the transport's success detail ("Printed - confirmed by printer in …ms") travels on the status event
            finish(job, JobStatus.SUCCESS, result.message, "print completed");
            return;
        }

        RetryPolicy policy = relay ? relayPolicy : job.kind == JobKind.RECEIPT ? receiptPolicy : kotPolicy;
        if (policy.shouldRetry(job.retries, result.outcome)) {
            final int nextAttempt = job.retries + 1;
            job.retries = nextAttempt;
            job.status = JobStatus.PENDING;
            job.reason = result.message;
            job.category = FailureClassifier.category(result.message);
            job.updatedAt = System.currentTimeMillis();
            store.update(job);
            log.append("print_", "Info:: Auto-retrying print :: Order=" + job.orderNo + " Attempt=" + nextAttempt + "/"
                    + policy.maxRetries + " Job=" + job.jobId + " Reason=" + result.message);
            emit(job, "retrying");
            scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    laneFor(job.printerId).offer(job.jobId);
                }
            }, policy.delayMs(nextAttempt), TimeUnit.MILLISECONDS);
            return;
        }
        // Breaker counts jobs that finally failed to connect (not individual attempts), so one job's own
        // retries keep legacy timing and only the printer's *later* jobs wait out the cooldown.
        if (!relay) breaker(printer.laneKey()).record(result.outcome, System.currentTimeMillis());
        fail(job, result);
    }

    private void fail(PrintJob job, PrintResult result) {
        job.category = FailureClassifier.category(result.message);
        finish(job, JobStatus.FAILED, result.message, "print_failed");
    }

    private void finish(PrintJob job, JobStatus status, String reason, String event) {
        job.status = status;
        job.reason = reason;
        job.updatedAt = System.currentTimeMillis();
        store.update(job);
        log.append("print_", "Info:: Job " + event + " :: " + job);
        emit(job, event);
    }

    private void emit(PrintJob job, String event) {
        for (JobListener l : listeners) {
            try {
                l.onJobEvent(job.copy(), event);
            } catch (RuntimeException e) {
                log.append("print_", "Exception:: job listener threw: " + e);
            }
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------

    static int compareNumeric(String a, String b) {
        if (a == null || b == null) return a == null ? (b == null ? 0 : -1) : 1;
        try {
            return Long.compare(Long.parseLong(a.trim()), Long.parseLong(b.trim()));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }

    private static ThreadFactory named(final String prefix) {
        final AtomicInteger n = new AtomicInteger();
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
    }
}
