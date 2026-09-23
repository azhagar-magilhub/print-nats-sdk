package com.magilhub.printnats.transport;

import com.magilhub.printnats.queue.PrintOutcome;
import com.magilhub.printnats.queue.PrintResult;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.spi.PrinterTransport;

import java.util.EnumMap;
import java.util.Map;

/** Picks the transport by {@link PrinterConfig.Connection}; LAN defaults to {@link TcpPrinterTransport}. */
public final class RoutingTransport implements PrinterTransport {
    private final Map<PrinterConfig.Connection, PrinterTransport> byConnection = new EnumMap<>(PrinterConfig.Connection.class);

    public RoutingTransport() {
        byConnection.put(PrinterConfig.Connection.LAN, new TcpPrinterTransport());
    }

    public RoutingTransport register(PrinterConfig.Connection connection, PrinterTransport transport) {
        byConnection.put(connection, transport);
        return this;
    }

    @Override
    public PrintResult send(PrinterConfig printer, byte[] data) {
        PrinterTransport t = byConnection.get(printer.connection);
        if (t == null) {
            return new PrintResult(PrintOutcome.FAULT, "No transport for " + printer.connection + " printers on this platform");
        }
        return t.send(printer, data);
    }
}
