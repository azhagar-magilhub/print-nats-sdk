package com.magilhub.printnats.transport;

import com.magilhub.printnats.queue.PrintOutcome;
import com.magilhub.printnats.queue.PrintResult;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.spi.LogSink;
import com.magilhub.printnats.spi.PrinterTransport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * LAN thermal (ESC/POS over TCP 9100) with MerchantApp's LanUtil.ipPrintKot flow (Release-25.1):
 * <ol>
 *   <li>pre-print DLE EOT status — a physical fault (cover/paper/cutter/mechanical) fails immediately;</li>
 *   <li>connect (300 ms, as legacy) and write — connect failure: nothing sent (CONNECTION_FAILED);
 *       write failure: re-query status, report the blocker or "Printer is Offline / Unreachable" (AMBIGUOUS);</li>
 *   <li>after a successful write, poll status up to 30 × 1 s for a definitive answer: a print fault fails the
 *       job, a clean status or an inconclusive result (no DLE EOT support / unreachable) is success.</li>
 * </ol>
 */
public final class LanThermalTransport implements PrinterTransport {
    static final int ACK_POLL_MAX_ATTEMPTS = 30;
    static final long ACK_POLL_INTERVAL_MS = 1000;

    public interface StatusProbe {
        ThermalPrinterStatus query(String ip, int port);
    }

    private final LogSink log;
    private final int connectTimeoutMs;
    private final StatusProbe probe;
    private final int ackAttempts;
    private final long ackIntervalMs;

    public LanThermalTransport(LogSink log) {
        this(log, 300, new StatusProbe() {
            @Override
            public ThermalPrinterStatus query(String ip, int port) {
                return ThermalStatusQuery.query(ip, port);
            }
        }, ACK_POLL_MAX_ATTEMPTS, ACK_POLL_INTERVAL_MS);
    }

    public LanThermalTransport(LogSink log, int connectTimeoutMs, StatusProbe probe, int ackAttempts, long ackIntervalMs) {
        this.log = log == null ? LogSink.NONE : log;
        this.connectTimeoutMs = connectTimeoutMs;
        this.probe = probe;
        this.ackAttempts = ackAttempts;
        this.ackIntervalMs = ackIntervalMs;
    }

    @Override
    public PrintResult send(PrinterConfig printer, byte[] data) {
        String ip = printer.address == null ? "" : printer.address.split("\\|")[0].trim();
        int port = printer.port;

        ThermalPrinterStatus pre = probe.query(ip, port);
        log.append("print_", "Info:: Thermal Pre-Print Status: " + ip + " - " + pre);
        if (pre.hasPreflightBlocker()) {
            String msg = pre.userMessage();
            log.append("print_", "Error:: Thermal Pre-Print Blocker: " + ip + " - " + msg);
            return new PrintResult(PrintOutcome.FAULT, msg);
        }
        boolean canVerifyAck = !pre.statusQueryUnsupported;

        Socket socket = new Socket();
        try {
            try {
                socket.connect(new InetSocketAddress(InetAddress.getByName(ip), port), connectTimeoutMs);
            } catch (IOException e) {
                return new PrintResult(PrintOutcome.CONNECTION_FAILED, "Unable to connect to printer while preparing the print job.");
            }
            try {
                OutputStream out = socket.getOutputStream();
                out.write(data);
                out.flush();
            } catch (IOException e) {
                ThermalPrinterStatus post = probe.query(ip, port);
                String msg = post.hasBlocker() ? post.userMessage() : "Printer is Offline / Unreachable";
                log.append("print_", "Info:: Thermal Print OnError: " + ip + " - " + e.getMessage() + " - userMessage: " + msg);
                return new PrintResult(PrintOutcome.AMBIGUOUS, msg);
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }

        if (!canVerifyAck) {
            log.append("print_", "Info:: Thermal Print OnSuccess (ack verify skipped, DLE EOT unsupported): " + ip);
            return PrintResult.success();
        }
        ThermalPrinterStatus post = null;
        for (int attempt = 1; attempt <= ackAttempts; attempt++) {
            post = probe.query(ip, port);
            boolean definitive = post.hasPrintFault() || !(post.unreachable || post.statusQueryUnsupported);
            if (definitive) break;
            if (attempt < ackAttempts) {
                try {
                    Thread.sleep(ackIntervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (post != null && post.hasPrintFault()) {
            String msg = post.userMessage();
            log.append("print_", "Error:: Thermal Post-Print(Success) FAULT -> FAILED: " + ip + " - " + msg);
            return new PrintResult(PrintOutcome.FAULT, msg);
        }
        log.append("print_", post == null || post.unreachable || post.statusQueryUnsupported
                ? "Info:: Thermal Print ACK inconclusive after " + ackAttempts + " attempts (trusting write): " + ip
                : "Info:: Thermal Print ACKNOWLEDGED (printed): " + ip);
        return PrintResult.success();
    }
}
