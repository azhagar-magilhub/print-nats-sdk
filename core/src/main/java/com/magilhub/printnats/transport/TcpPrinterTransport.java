package com.magilhub.printnats.transport;

import com.magilhub.printnats.queue.PrintOutcome;
import com.magilhub.printnats.queue.PrintResult;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.spi.PrinterTransport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Raw TCP (port 9100) printing for LAN thermal and Star printers — plain java.net, so it works on Android and
 * desktop (Java 8 / Windows XP). Outcome mapping: connect failure → CONNECTION_FAILED (nothing sent, safe to
 * retry); failure after connecting → AMBIGUOUS (bytes may have printed). Status back-channel (DLE EOT) is left
 * to platform transports that need it.
 */
public final class TcpPrinterTransport implements PrinterTransport, com.magilhub.printnats.spi.PrinterProbe {
    private final int connectTimeoutMs;
    private final int writeTimeoutMs;

    public TcpPrinterTransport(int connectTimeoutMs, int writeTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.writeTimeoutMs = writeTimeoutMs;
    }

    public TcpPrinterTransport() {
        this(3000, 10000);
    }

    /** Reachability only (plain TCP printers report no status here). */
    @Override
    public com.magilhub.printnats.queue.PrinterHealth probe(PrinterConfig printer) {
        String host = printer.address == null ? "" : printer.address.replaceFirst("^TCP:", "").split("\\|")[0].trim();
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, printer.port), connectTimeoutMs);
            com.magilhub.printnats.queue.PrinterHealth h = com.magilhub.printnats.queue.PrinterHealth.of(printer.id, true, true, null);
            h.statusSupported = false;
            return h;
        } catch (IOException e) {
            return com.magilhub.printnats.queue.PrinterHealth.of(printer.id, false, false, "Printer is Offline / Unreachable");
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    @Override
    public PrintResult send(PrinterConfig printer, byte[] data) {
        String host = printer.address == null ? "" : printer.address.replaceFirst("^TCP:", "").split("\\|")[0].trim();
        Socket socket = new Socket();
        try {
            try {
                socket.connect(new InetSocketAddress(host, printer.port), connectTimeoutMs);
                socket.setSoTimeout(writeTimeoutMs);
            } catch (IOException e) {
                return new PrintResult(PrintOutcome.CONNECTION_FAILED,
                        "Unable to connect to printer " + host + ":" + printer.port + " (" + e.getMessage() + ")");
            }
            try {
                OutputStream out = socket.getOutputStream();
                out.write(data);
                out.flush();
                return PrintResult.success();
            } catch (IOException e) {
                return new PrintResult(PrintOutcome.AMBIGUOUS, "Failed to send data to printer. (" + e.getMessage() + ")");
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }
}
