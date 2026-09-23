package com.magilhub.printnats.transport;

import com.magilhub.printnats.queue.PrintOutcome;
import com.magilhub.printnats.queue.PrintResult;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.spi.PrinterTransport;

import java.util.EnumMap;
import java.util.Map;

/**
 * Picks the transport by {@link PrinterConfig.Connection} (and Star vs thermal for LAN). Defaults: LAN thermal →
 * {@link LanThermalTransport} (legacy status checks), LAN Star → plain {@link TcpPrinterTransport} until a platform
 * registers a Star transport.
 */
public final class RoutingTransport implements PrinterTransport, com.magilhub.printnats.spi.PrinterProbe,
        com.magilhub.printnats.spi.PacedTransport {
    private final Map<PrinterConfig.Connection, PrinterTransport> byConnection = new EnumMap<>(PrinterConfig.Connection.class);
    private final Map<PrinterConfig.Connection, PrinterTransport> starByConnection = new EnumMap<>(PrinterConfig.Connection.class);

    public RoutingTransport() {
        this(null);
    }

    public RoutingTransport(com.magilhub.printnats.spi.LogSink log) {
        pacedLan = new PacedTcpTransport(log);
        byConnection.put(PrinterConfig.Connection.LAN, new LanThermalTransport(log));
        starByConnection.put(PrinterConfig.Connection.LAN, new TcpPrinterTransport());
    }

    /** Transport for Star printers on a connection (Android: StarIO ports). */
    public RoutingTransport registerStar(PrinterConfig.Connection connection, PrinterTransport transport) {
        starByConnection.put(connection, transport);
        return this;
    }

    public RoutingTransport register(PrinterConfig.Connection connection, PrinterTransport transport) {
        byConnection.put(connection, transport);
        return this;
    }

    private final PrinterTransport rawLan = new TcpPrinterTransport(300, 3000);
    private final PacedTcpTransport pacedLan;

    /**
     * Paced jobs (receipts / EOD) take the legacy printReceiptJson route: the ESC/POS connection for the printer's
     * connection type EVEN for Star printers (legacy sent receipt raster over raw TCP / USB / Bluetooth, not StarIO);
     * LAN = {@link PacedTcpTransport}. Transports that can't pace get the bytes in one write.
     */
    @Override
    public PrintResult sendPaced(PrinterConfig printer, byte[] data, int[] chunkEnds, int[] addWaitMs) {
        if (printer.connection == PrinterConfig.Connection.LAN) return pacedLan.sendPaced(printer, data, chunkEnds, addWaitMs);
        PrinterTransport t = byConnection.get(printer.connection);
        if (t == null) t = starByConnection.get(printer.connection);
        if (t == null) {
            return new PrintResult(PrintOutcome.FAULT, "No transport for " + printer.connection + " printers on this platform");
        }
        if (t instanceof com.magilhub.printnats.spi.PacedTransport) {
            return ((com.magilhub.printnats.spi.PacedTransport) t).sendPaced(printer, data, chunkEnds, addWaitMs);
        }
        return t.send(printer, data);
    }

    /** Health via the printer's own transport when it can probe; otherwise "unknown" (reachable, unsupported). */
    @Override
    public com.magilhub.printnats.queue.PrinterHealth probe(PrinterConfig printer) {
        PrinterTransport t = (printer.isStar ? starByConnection : byConnection).get(printer.connection);
        if (t instanceof com.magilhub.printnats.spi.PrinterProbe) return ((com.magilhub.printnats.spi.PrinterProbe) t).probe(printer);
        com.magilhub.printnats.queue.PrinterHealth h = com.magilhub.printnats.queue.PrinterHealth.of(printer.id, true, true, null);
        h.statusSupported = false;
        return h;
    }

    /**
     * Immediate raw send without status checks (cash drawer): LAN goes straight over TCP (legacy TcpConnection,
     * 300 ms connect) instead of the LAN thermal flow with its pre/post status polling.
     */
    public PrintResult sendDirect(PrinterConfig printer, byte[] data) {
        if (printer.connection == PrinterConfig.Connection.LAN) return rawLan.send(printer, data);
        return send(printer, data);
    }

    @Override
    public PrintResult send(PrinterConfig printer, byte[] data) {
        PrinterTransport t = (printer.isStar ? starByConnection : byConnection).get(printer.connection);
        if (t == null) {
            return new PrintResult(PrintOutcome.FAULT, "No transport for " + printer.connection + " printers on this platform");
        }
        return t.send(printer, data);
    }
}
