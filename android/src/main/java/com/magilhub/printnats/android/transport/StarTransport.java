package com.magilhub.printnats.android.transport;

import android.content.Context;

import com.magilhub.printnats.queue.FailureClassifier;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Star printers over StarIO ports (TCP:/BT:/USB:). Per-job logic of MerchantApp's StarLanHybridPrintManager.
 *
 * <p>LAN (TCP:) — Release-30.5: the print is confirmed by the printer's ETB counter. An ETB byte (0x17) is sent
 * after the ticket; the printer increments its counter only once it has processed everything before it, and the
 * counter is read back with an explicit status request, so completion is known whether or not the printer pushes
 * status (ASB). A printer that stops answering status requests fails in ~7 s instead of holding the station.
 *
 * <p>Bluetooth / USB — Release-25.1 behaviour, unchanged: checked block with a size-scaled ACK timeout, and a
 * status-poll fallback for printers that never answer endCheckedBlock.
 *
 * <p>The SDK queue already serialises per physical printer, so no per-port executor here.
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

    // ---- LAN: ETB-confirmed print ------------------------------------------------------------------
    /** Sent after the ticket; the printer's ETB counter moves only when everything before it has printed. */
    private static final byte ETB = 0x17;
    private static final int ETB_POLL_INTERVAL_MS = 250;
    /**
     * A healthy printer answers a status request in ~130 ms, even mid-print. A dead one (power pulled, cable out)
     * leaves retreiveStatus blocked for the whole port timeout, so each read gets this long before it counts as
     * unanswered.
     */
    private static final int STATUS_REPLY_TIMEOUT_MS = 2_000;
    /** Unanswered status reads in a row (after the data was sent) before the printer "stopped responding". */
    static final int MAX_CONSECUTIVE_NO_STATUS = 3;
    /** How long to wait for the counter to move: 20 s + 20 ms/byte, capped at 120 s (under the queue's 150 s watchdog). */
    private static final int COMPLETION_FLOOR_MS = 20_000;
    private static final int COMPLETION_MS_PER_BYTE = 20;

    // User-facing results. FailureClassifier.isPhysicalFault / category match on these words — keep them in sync.
    static final String MSG_PORT_BUSY = "Printer is busy (another device is connected to it).";
    static final String MSG_UNREACHABLE = "Printer is offline or unreachable. Check power and LAN/Wi-Fi.";
    static final String MSG_WRITE_FAILED = "Connection dropped while sending the ticket to the printer.";
    static final String MSG_STOPPED_RESPONDING =
            "Printer not responding mid-print. Ticket may be incomplete - check the printer.";
    static final String MSG_NO_COMPLETION_PREFIX = "Printer did not confirm the print";

    private static final ExecutorService STATUS_EXECUTOR = Executors.newCachedThreadPool();

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
            log.append("print_", "ERROR:: PRINT TOO LARGE :: bytes=" + commands.length + " max=" + MAX_PRINT_BYTES
                    + " port=" + portName);
            return new PrintResult(PrintOutcome.FAULT, "Print is too large (" + (commands.length / 1024)
                    + " KB). Shorten item names or split the order.");
        }
        return portName.startsWith("TCP:") ? sendEtbConfirmed(portName, commands) : sendChecked(portName, commands);
    }

    /** LAN: send the ticket + ETB and wait for the printer's ETB counter to move. */
    private PrintResult sendEtbConfirmed(String portName, byte[] commands) {
        int ioTimeout = (int) Math.min(TIMEOUT_CEILING_MS, (long) TIMEOUT_FLOOR_MS + commands.length / TIMEOUT_BYTES_PER_MS);
        StarIOPort port = null;
        boolean written = false;
        log.append("print_", "INFO:: PRINT START :: port=" + portName + " bytes=" + commands.length);
        try {
            // 1. Open the port. A busy port (another client holds 9100) is transient; anything else: never reached it.
            try {
                port = StarIOPort.getPort(portName, "", ioTimeout, context);
            } catch (StarIOPortException e) {
                boolean busy = isPortBusy(e.getMessage());
                log.append("print_", "ERROR:: PRINT CONNECT FAIL :: code=" + (busy ? "PORT_BUSY" : "UNREACHABLE")
                        + " reason=" + e.getMessage() + " port=" + portName);
                return failed(busy ? MSG_PORT_BUSY : MSG_UNREACHABLE, false);
            }

            // 2. Pre-check + ETB baseline.
            StarPrinterStatus pre = safeStatus(port);
            if (pre == null) {
                log.append("print_", "ERROR:: PRINT NOT READY :: code=UNREACHABLE reason=no status reply port=" + portName);
                return failed(MSG_UNREACHABLE, false);
            }
            if (!isAcknowledged(pre)) {
                log.append("print_", "ERROR:: PRINT NOT READY :: " + describeStatus(pre) + " port=" + portName);
                return failed(resolvePrinterError(pre), false);
            }
            boolean etbSupported = pre.etbAvailable;
            int etbBefore = pre.etbCounter;

            // 3. Send the ticket (+ ETB marker when the printer supports it).
            byte[] payload = etbSupported ? withEtb(commands) : commands;
            long writeAt = System.currentTimeMillis();
            try {
                port.writePort(payload, 0, payload.length);
            } catch (StarIOPortException e) {
                log.append("print_", "ERROR:: PRINT WRITE FAIL :: code=WRITE_FAILED reason=" + e.getMessage() + " port=" + portName);
                return failed(MSG_WRITE_FAILED, true);
            }
            written = true;
            log.append("print_", "INFO:: PRINT SENT :: port=" + portName + " etbSupported=" + etbSupported
                    + " etbBefore=" + etbBefore);

            if (!etbSupported) {
                // Can't confirm — watch for a fault, then report honestly.
                String fault = pollForFault(port, commands.length);
                if (fault != null) {
                    log.append("print_", "ERROR:: PRINT FAULT :: reason=" + fault + " port=" + portName);
                    return failed(fault, true);
                }
                log.append("print_", "WARN:: PRINT UNCONFIRMED :: (printer reports no ETB) port=" + portName);
                return PrintResult.success(unconfirmedMessage());
            }

            // 4. Wait for the printer to confirm (counter moves), or a fault, or silence, or the budget running out.
            int budget = completionBudgetForCommands(commands.length);
            long deadline = writeAt + budget;
            int noStatus = 0;
            StarPrinterStatus last = pre;
            StatusReader reader = new StatusReader(port);
            while (true) {
                Thread.sleep(ETB_POLL_INTERVAL_MS);
                StarPrinterStatus s = reader.read(STATUS_REPLY_TIMEOUT_MS);
                long elapsed = System.currentTimeMillis() - writeAt;
                Verdict v = evaluate(s, etbBefore, noStatus, System.currentTimeMillis() >= deadline, budget);
                noStatus = s == null ? noStatus + 1 : 0;
                if (s != null) last = s;
                if (v.kind == Verdict.Kind.PENDING) continue;
                if (v.kind == Verdict.Kind.CONFIRMED) {
                    log.append("print_", "SUCCESS:: PRINT CONFIRMED :: etb=" + etbBefore + "->" + s.etbCounter
                            + " printMs=" + elapsed + " port=" + portName);
                    return PrintResult.success(confirmedMessage(elapsed));
                }
                log.append("print_", "ERROR:: PRINT NOT CONFIRMED :: code=" + v.code + " afterMs=" + elapsed
                        + " last{" + describeStatus(last) + "} port=" + portName);
                return failed(v.message, true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PrintResult(PrintOutcome.AMBIGUOUS, "Interrupted while printing");
        } catch (Exception e) {
            log.append("print_", "ERROR:: PRINT ERROR :: reason=" + e.getMessage() + " port=" + portName);
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

    /**
     * Failure → retry decision, same as MerchantApp: a fault a person has to fix (and "offline", "not responding",
     * "did not confirm") is never auto-retried; anything else is — as "may have reached the printer" once the
     * ticket was written, as a plain connection failure before that.
     */
    static PrintResult failed(String message, boolean written) {
        if (FailureClassifier.isPhysicalFault(message)) return new PrintResult(PrintOutcome.FAULT, message);
        return new PrintResult(written ? PrintOutcome.AMBIGUOUS : PrintOutcome.CONNECTION_FAILED, message);
    }

    // ---- confirmation logic (pure, unit-tested) ----------------------------------------------------

    static final class Verdict {
        enum Kind { PENDING, CONFIRMED, FAILED }

        final Kind kind;
        final String code;
        final String message;

        private Verdict(Kind kind, String code, String message) {
            this.kind = kind;
            this.code = code;
            this.message = message;
        }

        static final Verdict PENDING = new Verdict(Kind.PENDING, null, null);
        static final Verdict CONFIRMED = new Verdict(Kind.CONFIRMED, "PRINTED", null);

        static Verdict failed(String code, String message) {
            return new Verdict(Kind.FAILED, code, message);
        }
    }

    /** One poll step. {@code status} is this read (null = no reply); {@code noStatusBefore} = unanswered reads so far. */
    static Verdict evaluate(StarPrinterStatus status, int etbBefore, int noStatusBefore, boolean pastDeadline, int budgetMs) {
        if (status == null) {
            if (noStatusBefore + 1 >= MAX_CONSECUTIVE_NO_STATUS) {
                return Verdict.failed("STOPPED_RESPONDING", MSG_STOPPED_RESPONDING);
            }
        } else {
            // A fault wins over a moved counter: the printer may have reached ETB but failed to cut, or the cover is open.
            if (!isAcknowledged(status)) return Verdict.failed(faultCode(status), resolvePrinterError(status));
            if (status.etbCounter != etbBefore) return Verdict.CONFIRMED;
        }
        if (pastDeadline) {
            return Verdict.failed("NO_COMPLETION", MSG_NO_COMPLETION_PREFIX + " within " + (budgetMs / 1000)
                    + "s. Check the printer and reprint if needed.");
        }
        return Verdict.PENDING;
    }

    static int completionBudgetForCommands(int commandBytes) {
        return (int) Math.min(TIMEOUT_CEILING_MS, (long) COMPLETION_FLOOR_MS + (long) commandBytes * COMPLETION_MS_PER_BYTE);
    }

    static boolean isPortBusy(String starIoMessage) {
        return starIoMessage != null && starIoMessage.toLowerCase().contains("busy");
    }

    static byte[] withEtb(byte[] commands) {
        byte[] out = new byte[commands.length + 1];
        System.arraycopy(commands, 0, out, 0, commands.length);
        out[commands.length] = ETB;
        return out;
    }

    static String confirmedMessage(long printMs) {
        return PrintResult.CONFIRMED_PREFIX + " by printer in " + printMs + "ms";
    }

    static String unconfirmedMessage() {
        return "Sent to printer - unconfirmed (printer does not report completion)";
    }

    /**
     * Status read with a reply deadline. retreiveStatus blocks for the port's I/O timeout when the printer is gone,
     * so it runs off the print thread; a read still in flight is not re-issued (StarIOPort is synchronized, a
     * second call would only queue behind it) — it keeps counting as unanswered until it returns.
     */
    private static final class StatusReader {
        private final StarIOPort port;
        private Future<StarPrinterStatus> inFlight;

        StatusReader(StarIOPort port) {
            this.port = port;
        }

        StarPrinterStatus read(int timeoutMs) throws InterruptedException {
            if (inFlight == null) {
                inFlight = STATUS_EXECUTOR.submit(new Callable<StarPrinterStatus>() {
                    @Override
                    public StarPrinterStatus call() throws Exception {
                        return port.retreiveStatus();
                    }
                });
            }
            try {
                StarPrinterStatus s = inFlight.get(timeoutMs, TimeUnit.MILLISECONDS);
                inFlight = null;
                return s;
            } catch (TimeoutException e) {
                return null;
            } catch (ExecutionException e) {
                inFlight = null;
                return null;
            }
        }
    }

    /** Bluetooth / USB: checked block, with a status-poll fallback (Release-25.1 behaviour). */
    private PrintResult sendChecked(String portName, byte[] commands) {
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

    /** Compact, log-friendly dump of the printer's status. */
    static String describeStatus(StarPrinterStatus s) {
        if (s == null) return "status=null (no response)";
        return "offline=" + s.offline
                + " coverOpen=" + s.coverOpen
                + " receiptPaperEmpty=" + s.receiptPaperEmpty
                + " paperDetectionError=" + s.paperDetectionError
                + " cutterError=" + s.cutterError
                + " headUpError=" + s.headUpError
                + " jamError=" + s.jamError
                + " mechError=" + s.mechError
                + " etbAvailable=" + s.etbAvailable
                + " etbCounter=" + s.etbCounter
                + " fault=" + (isAcknowledged(s) ? "none" : faultCode(s));
    }

    static boolean isAcknowledged(StarPrinterStatus s) {
        return s != null && !s.offline && !s.coverOpen && !s.receiptPaperEmpty && !s.paperDetectionError
                && !s.cutterError && !s.headUpError && !s.jamError && !s.mechError && !s.unrecoverableError
                && !s.voltageError && !s.receiveBufferOverflow && !s.overTemp;
    }

    static String faultCode(StarPrinterStatus s) {
        if (s == null) return "NO_STATUS";
        if (s.coverOpen) return "COVER_OPEN";
        if (s.paperDetectionError || s.receiptPaperEmpty) return "PAPER_OUT";
        if (s.jamError) return "PAPER_JAM";
        if (s.cutterError) return "CUTTER_ERROR";
        if (s.headUpError) return "HEAD_UP";
        if (s.overTemp) return "OVERHEATED";
        if (s.voltageError) return "VOLTAGE_ERROR";
        if (s.mechError) return "MECH_ERROR";
        if (s.unrecoverableError) return "UNRECOVERABLE";
        if (s.receiveBufferOverflow) return "BUFFER_OVERFLOW";
        if (s.offline) return "OFFLINE";
        return "NONE";
    }

    static String resolvePrinterError(StarPrinterStatus s) {
        if (s == null) return "Printer is not responding";
        if (s.coverOpen) return "Cover open. Close the printer cover.";
        if (s.paperDetectionError) return "Out of paper. Load a new paper roll.";
        if (s.receiptPaperEmpty) return "Out of paper. Load a new paper roll.";
        if (s.jamError) return "Paper jam. Open the cover and clear the paper jam.";
        if (s.cutterError) return "Cutter error. Open cover and clear paper jam.";
        if (s.headUpError) return "Print head is open. Close it firmly.";
        if (s.overTemp) return "Printer overheated. Please wait and retry.";
        if (s.voltageError) return "Printer power/voltage error. Check the power supply.";
        if (s.mechError) return "Printer mechanical error. Power-cycle the printer.";
        if (s.unrecoverableError) return "Printer unrecoverable error. Power-cycle the printer.";
        if (s.receiveBufferOverflow) return "Printer receive buffer overflow. Ticket data was lost.";
        if (s.offline) return "Printer is offline. Check power, LAN/Wi-Fi.";
        if (s.receiptPaperNearEmptyInner) return "Paper low. Replace paper soon.";
        return "Printer is not ready";
    }
}
