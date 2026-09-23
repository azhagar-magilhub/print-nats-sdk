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
public final class RoutingTransport implements PrinterTransport {
    private final Map<PrinterConfig.Connection, PrinterTransport> byConnection = new EnumMap<>(PrinterConfig.Connection.class);
    private final Map<PrinterConfig.Connection, PrinterTransport> starByConnection = new EnumMap<>(PrinterConfig.Connection.class);

    public RoutingTransport() {
        this(null);
    }

    public RoutingTransport(com.magilhub.printnats.spi.LogSink log) {
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

    @Override
    public PrintResult send(PrinterConfig printer, byte[] data) {
        PrinterTransport t = (printer.isStar ? starByConnection : byConnection).get(printer.connection);
        if (t == null) {
            return new PrintResult(PrintOutcome.FAULT, "No transport for " + printer.connection + " printers on this platform");
        }
        return t.send(printer, data);
    }
}
