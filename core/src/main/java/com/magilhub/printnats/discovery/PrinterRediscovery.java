package com.magilhub.printnats.discovery;

import com.magilhub.printnats.queue.FailureClassifier;
import com.magilhub.printnats.queue.JobKind;
import com.magilhub.printnats.queue.JobListener;
import com.magilhub.printnats.queue.PrintJob;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.spi.LogSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Printer IP rediscovery — port of legacy {@code PrintFrameworkModule.showAlert → retryPrintWithNewIp}: when a
 * print to a LAN printer whose identifier carries a MAC ({@code ip|mac}) finally fails, find the MAC on the local
 * subnet; if it answers on a new IP, switch every row of that printer to it, tell the host (which saves it to the
 * backend), and re-queue that printer's failed receipts.
 *
 * Legacy parity: triggered by receipt failures only, and only receipts are re-queued (legacy's KOT retry line is
 * commented out). {@link #setIncludeKot} extends both to KOTs — off by default (not yet reviewed).
 * Not triggered for faults that prove the printer is reachable (paper out, cover open, mechanical).
 */
public final class PrinterRediscovery implements JobListener {
    public interface Host {
        List<PrinterConfig> printers();

        /** Re-queue one FAILED job; false when it is no longer failed. */
        boolean retry(String jobId);

        List<PrintJob> failedJobs();
    }

    /** Host callback: persist the new identifier (legacy emitted {@code updateIPAddress} → JS EditPrinter). */
    public interface AddressListener {
        /** {@code printerIds}: every SDK row of the physical printer. Addresses as stored ({@code [TCP:]ip|mac}). */
        void onPrinterAddressChanged(List<String> printerIds, String oldAddress, String newAddress);
    }

    static final long COOLDOWN_MS = 60_000;

    private final Host host;
    private final MacLocator locator;
    private final IpOverrides overrides;
    private final AddressListener listener;
    private final LogSink log;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "print-nats-ip-rescan");
            t.setDaemon(true);
            return t;
        }
    });
    private final Map<String, Long> lastScan = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    private volatile boolean includeKot = false;

    public PrinterRediscovery(Host host, MacLocator locator, IpOverrides overrides, AddressListener listener, LogSink log) {
        this.host = host;
        this.locator = locator;
        this.overrides = overrides;
        this.listener = listener;
        this.log = log == null ? LogSink.NONE : log;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setIncludeKot(boolean includeKot) {
        this.includeKot = includeKot;
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    @Override
    public void onJobEvent(PrintJob job, String event) {
        if (!enabled || !"print_failed".equals(event)) return;
        if (job.kind != JobKind.RECEIPT && !(includeKot && job.kind == JobKind.KOT)) return;
        String category = job.category;
        if (FailureClassifier.CATEGORY_PAPER_OUT.equals(category) || FailureClassifier.CATEGORY_COVER_OPEN.equals(category)
                || FailureClassifier.CATEGORY_MECHANICAL.equals(category)) {
            return; // the printer answered — its IP is fine
        }
        PrinterConfig printer = find(job.printerId);
        if (printer == null || printer.connection != PrinterConfig.Connection.LAN) return;
        final String mac = Macs.macOf(printer.address);
        if (mac == null) return;
        long now = System.currentTimeMillis();
        Long last = lastScan.get(mac);
        if (last != null && now - last < COOLDOWN_MS) return;
        lastScan.put(mac, now);
        final String oldAddress = printer.address;
        final int port = printer.port;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                rescan(mac, oldAddress, port);
            }
        });
    }

    void rescan(String mac, String oldAddress, int port) {
        String oldIp = Macs.ipOf(oldAddress);
        log.append("print_", "IP-RESCAN:: looking for " + mac + " (last IP " + oldIp + ")");
        String newIp;
        try {
            newIp = locator.find(mac, oldIp, port);
        } catch (RuntimeException e) {
            log.append("print_", "IP-RESCAN:: failed: " + e);
            return;
        }
        if (newIp == null || newIp.equals(oldIp)) {
            log.append("print_", "IP-RESCAN:: " + mac + (newIp == null ? " not found" : " still at " + oldIp) + " — no change");
            return;
        }
        String newAddress = Macs.withIp(oldAddress, newIp);
        List<String> ids = new ArrayList<>();
        for (PrinterConfig p : host.printers()) {
            if (mac.equals(Macs.macOf(p.address)) && oldIp.equals(Macs.ipOf(p.address))) {
                p.address = Macs.withIp(p.address, newIp);
                ids.add(p.id);
            }
        }
        overrides.put(mac, oldIp, newIp);
        log.append("print_", "IP-RESCAN:: " + mac + " moved " + oldIp + " → " + newIp + " (rows " + ids + ")");
        if (listener != null) {
            try {
                listener.onPrinterAddressChanged(ids, oldAddress, newAddress);
            } catch (RuntimeException e) {
                log.append("print_", "IP-RESCAN:: address listener threw: " + e);
            }
        }
        int n = 0;
        for (PrintJob j : host.failedJobs()) {
            if (!ids.contains(j.printerId)) continue;
            if (j.kind != JobKind.RECEIPT && !(includeKot && j.kind == JobKind.KOT)) continue;
            if (host.retry(j.jobId)) n++;
        }
        log.append("print_", "IP-RESCAN:: re-queued " + n + " failed job(s) on the new IP");
    }

    private PrinterConfig find(String id) {
        for (PrinterConfig p : host.printers()) if (p.id.equals(id)) return p;
        return null;
    }
}
