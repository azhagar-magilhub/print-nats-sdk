package com.magilhub.printnats.android.transport;

import android.content.Context;

import com.magilhub.printnats.queue.PrintOutcome;
import com.magilhub.printnats.queue.PrintResult;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.spi.LogSink;
import com.magilhub.printnats.spi.PrinterTransport;
import com.starmicronics.stario.StarIOPort;
import com.starmicronics.stario.StarIOPortException;
import com.starmicronics.stario.StarPrinterStatus;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Star printers over StarIO ports (TCP:/BT:/USB:). Per-job logic of MerchantApp's StarLanHybridPrintManager
 * (Release-25.1, the default isRequiredParallelQueue path): size cap, pre-status check, checked block with a
 * size-scaled ACK timeout, and a status-poll fallback for printers that never answer endCheckedBlock.
 * The SDK queue already serialises per physical printer, so no per-port executor here.
 */
public final class StarTransport implements PrinterTransport, com.magilhub.printnats.spi.PrinterProbe {
    private static final int MAX_PRINT_BYTES = 150_000;
    private static final int TIMEOUT_FLOOR_MS = 15_000;
    private static final int TIMEOUT_CEILING_MS = 120_000;
    private static final int TIMEOUT_BYTES_PER_MS = 10;
    private static final int WATCH_BASE_MS = 2_000;
    private static final int WATCH_CEILING_MS = 12_000;
    private static final int WATCH_BYTES_PER_MS = 30;
    private static final int STATUS_POLL_INTERVAL_MS = 400;

    private final Context context;
    private final LogSink log;
    private final Set<String> noAckPorts = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    public StarTransport(Context context, LogSink log) {
        this.context = context.getApplicationContext();
        this.log = log == null ? LogSink.NONE : log;
    }

    /** "TCP:ip" for LAN (legacy), "BT:"/"USB:" prefixes for the others unless the address already has one. */
    public static String portName(PrinterConfig p) {
        String address = p.address == null ? "" : p.address.split("\\|")[0].trim();
        if (address.contains(":") && (address.startsWith("TCP:") || address.startsWith("BT:") || address.startsWith("USB:"))) {
            return address;
        }
        switch (p.connection) {
            case BLUETOOTH:
                return "BT:" + address;
            case USB:
                return "USB:" + address;
            default:
                return "TCP:" + address;
        }
    }

    @Override
    public com.magilhub.printnats.queue.PrinterHealth probe(PrinterConfig printer) {
        StarIOPort port = null;
        try {
            port = StarIOPort.getPort(portName(printer), "", 10_000, context);
            StarPrinterStatus s = port.retreiveStatus();
            boolean ok = isAcknowledged(s);
            return com.magilhub.printnats.queue.PrinterHealth.of(printer.id, true, ok, ok ? null : resolvePrinterError(s));
        } catch (Exception e) {
            return com.magilhub.printnats.queue.PrinterHealth.of(printer.id, false, false, "Printer is offline or unreachable. Check power and LAN/Wi-Fi.");
        } finally {
            if (port != null) {
                try {
                    StarIOPort.releasePort(port);
                } catch (Exception ignored) {
                    // released anyway
                }
            }
        }
    }

    @Override
    public PrintResult send(PrinterConfig printer, byte[] commands) {
        String portName = portName(printer);
        if (commands.length > MAX_PRINT_BYTES) {
            return new PrintResult(PrintOutcome.FAULT, "Print is too large (" + (commands.length / 1024)
                    + " KB). Shorten item names or split the order.");
        }
        int ioTimeout = (int) Math.min(TIMEOUT_CEILING_MS, (long) TIMEOUT_FLOOR_MS + commands.length / TIMEOUT_BYTES_PER_MS);
        StarIOPort port = null;
        boolean written = false;
        try {
            port = StarIOPort.getPort(portName, "", ioTimeout, context);
            StarPrinterStatus pre = safeStatus(port);
            if (pre != null && !isAcknowledged(pre)) {
                return new PrintResult(PrintOutcome.FAULT, resolvePrinterError(pre));
            }
            String fault;
            if (noAckPorts.contains(portName)) {
                port.writePort(commands, 0, commands.length);
                written = true;
                fault = pollForFault(port, commands.length);
            } else {
                port.beginCheckedBlock();
                port.writePort(commands, 0, commands.length);
                written = true;
                port.setEndCheckedBlockTimeoutMillis(ioTimeout);
                try {
                    StarPrinterStatus ack = port.endCheckedBlock();
                    fault = isAcknowledged(ack) ? null : resolvePrinterError(ack);
                } catch (StarIOPortException endEx) {
                    noAckPorts.add(portName); // this printer doesn't answer checked blocks — poll status instead
                    log.append("print_", "Info:: Star " + portName + " no endCheckedBlock ACK, polling status: " + endEx.getMessage());
                    fault = pollForFault(port, commands.length);
                }
            }
            if (fault != null) return new PrintResult(PrintOutcome.FAULT, fault);
            log.append("print_", "SUCCESS :: Star Print ACKNOWLEDGED (printed) :: " + portName);
            return PrintResult.success();
        } catch (StarIOPortException e) {
            // Legacy message ("offline" → never auto-retried). Before any write it's certainly unsent.
            String msg = "Printer is offline or unreachable. Check power and LAN/Wi-Fi.";
            return new PrintResult(written ? PrintOutcome.AMBIGUOUS : PrintOutcome.FAULT, msg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PrintResult(PrintOutcome.AMBIGUOUS, "Interrupted while printing");
        } catch (Exception e) {
            return new PrintResult(written ? PrintOutcome.AMBIGUOUS : PrintOutcome.FAULT, String.valueOf(e.getMessage()));
        } finally {
            if (port != null) {
                try {
                    StarIOPort.releasePort(port);
                } catch (Exception ignored) {
                    // released anyway
                }
            }
        }
    }

    private String pollForFault(StarIOPort port, int commandBytes) throws InterruptedException {
        int watchMs = (int) Math.min(WATCH_CEILING_MS, (long) WATCH_BASE_MS + commandBytes / WATCH_BYTES_PER_MS);
        int polls = Math.max(1, watchMs / STATUS_POLL_INTERVAL_MS);
        for (int i = 0; i < polls; i++) {
            Thread.sleep(STATUS_POLL_INTERVAL_MS);
            StarPrinterStatus s = safeStatus(port);
            if (s != null && !isAcknowledged(s)) return resolvePrinterError(s);
        }
        return null;
    }

    private static StarPrinterStatus safeStatus(StarIOPort port) {
        try {
            return port.retreiveStatus();
        } catch (Exception e) {
            return null;
        }
    }

    static boolean isAcknowledged(StarPrinterStatus s) {
        return s != null && !s.offline && !s.coverOpen && !s.receiptPaperEmpty && !s.paperDetectionError
                && !s.cutterError && !s.headUpError;
    }

    static String resolvePrinterError(StarPrinterStatus s) {
        if (s == null) return "Printer is not responding";
        if (s.coverOpen) return "Cover open. Close the printer cover.";
        if (s.paperDetectionError) return "Out of paper. Load a new paper roll.";
        if (s.receiptPaperEmpty) return "Out of paper. Load a new paper roll.";
        if (s.receiptPaperNearEmptyInner) return "Paper low. Replace paper soon.";
        if (s.cutterError) return "Cutter error. Open cover and clear paper jam.";
        if (s.overTemp) return "Printer overheated. Please wait and retry.";
        if (s.headUpError) return "Print head is open. Close it firmly.";
        if (s.offline) return "Printer is offline. Check power, LAN/Wi-Fi.";
        return "Printer is not ready";
    }
}
