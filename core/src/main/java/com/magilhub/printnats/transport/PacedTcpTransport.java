package com.magilhub.printnats.transport;

import com.magilhub.printnats.queue.PrintOutcome;
import com.magilhub.printnats.queue.PrintResult;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.spi.LogSink;
import com.magilhub.printnats.spi.PacedTransport;
import com.magilhub.printnats.spi.PrinterTransport;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Receipts / EOD over LAN exactly like legacy {@code printReceiptJson}: DantSu {@code TcpConnection(ip, 9100, 3000)}
 * for thermal AND Star printers (raw ESC/POS, not StarIO), paced writes, no DLE EOT status queries before or after
 * (a status connection opened while a large raster image is still printing can cut it short on some printers).
 */
public final class PacedTcpTransport implements PrinterTransport, PacedTransport {
    static final int CONNECT_TIMEOUT_MS = 3000; // legacy getPrinterConnection: new TcpConnection(address, 9100, 3000)

    private final LogSink log;

    public PacedTcpTransport(LogSink log) {
        this.log = log == null ? LogSink.NONE : log;
    }

    @Override
    public PrintResult send(PrinterConfig printer, byte[] data) {
        return sendPaced(printer, data, new int[]{data.length}, new int[]{0});
    }

    @Override
    public PrintResult sendPaced(PrinterConfig printer, byte[] data, int[] chunkEnds, int[] addWaitMs) {
        String host = printer.address == null ? "" : printer.address.replaceFirst("^TCP:", "").split("\\|")[0].trim();
        Socket socket = new Socket();
        try {
            try {
                socket.connect(new InetSocketAddress(InetAddress.getByName(host), printer.port), CONNECT_TIMEOUT_MS);
            } catch (IOException e) {
                return new PrintResult(PrintOutcome.CONNECTION_FAILED, "Unable to connect to printer while preparing the print job.");
            }
            long paused = 0;
            try {
                OutputStream out = socket.getOutputStream();
                int start = 0;
                for (int i = 0; i < chunkEnds.length; i++) {
                    int end = Math.min(chunkEnds[i], data.length);
                    if (end > start) {
                        out.write(data, start, end - start);
                        out.flush();
                    }
                    long pause = PacedTransport.pauseMs(addWaitMs[i], Math.max(0, end - start));
                    if (pause > 0) {
                        Thread.sleep(pause);
                        paused += pause;
                    }
                    start = Math.max(start, end);
                }
                if (start < data.length) { // anything after the last recorded chunk
                    out.write(data, start, data.length - start);
                    out.flush();
                }
            } catch (IOException e) {
                log.append("print_", "Info:: Receipt Print OnError: " + host + " - " + e.getMessage());
                return new PrintResult(PrintOutcome.AMBIGUOUS, "Printer is Offline / Unreachable");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new PrintResult(PrintOutcome.AMBIGUOUS, "Interrupted while printing");
            }
            log.append("print_", "Info:: Receipt Print sent: " + host + " bytes=" + data.length + " chunks=" + chunkEnds.length
                    + " paced=" + paused + "ms");
            return PrintResult.success();
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }
}
