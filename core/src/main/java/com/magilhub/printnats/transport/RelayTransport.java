package com.magilhub.printnats.transport;

import com.google.gson.JsonObject;
import com.magilhub.printnats.queue.PrintOutcome;
import com.magilhub.printnats.queue.PrintResult;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.rules.Json;
import com.magilhub.printnats.spi.LogSink;
import com.magilhub.printnats.spi.PrinterTransport;

import java.nio.charset.StandardCharsets;

/**
 * "Prints" a relay job ({@link PrinterConfig#RELAY_MASTER_ID}) by handing its request to the location's master
 * device over NATS request/reply ({@code printrelay.<locationId>.master}):
 * <ul>
 *   <li>reply {@code {ok:true}} → SUCCESS (the master queued the tickets on its own printers);</li>
 *   <li>not connected / no master listening / no reply in time → CONNECTION_FAILED "Waiting for master device"
 *       (auto-retried indefinitely by {@code RetryPolicy.relay()});</li>
 *   <li>reply {@code {ok:false, error}} → FAULT with the master's error (Failed Print Queue, manual retry).</li>
 * </ul>
 * If this device has meanwhile become the master, or it has no NATS connection (no internet, modem off), the request
 * is printed here instead, on the kitchen printers this device reaches over LAN — the same path the master uses for
 * incoming relays — so KOTs never wait for the internet.
 */
public final class RelayTransport implements PrinterTransport {
    public static final String WAITING_FOR_MASTER = "Waiting for master device";
    public static final long DEFAULT_TIMEOUT_MS = 5000;

    /** How the transport reaches the master (wired by PrintNats). */
    public interface Link {
        boolean isMaster();

        /** Core-NATS request; null when not connected, nobody answered, or timed out. */
        byte[] request(byte[] body, long timeoutMs);

        /** false = no NATS connection (no internet): the master can't be reached, print locally. */
        boolean isOnline();

        /** Print the request on this device's printers; returns the reply JSON bytes. */
        byte[] printLocally(byte[] body);
    }

    private final Link link;
    private final long timeoutMs;
    private final LogSink log;

    public RelayTransport(Link link, long timeoutMs, LogSink log) {
        this.link = link;
        this.timeoutMs = timeoutMs;
        this.log = log == null ? LogSink.NONE : log;
    }

    @Override
    public PrintResult send(PrinterConfig printer, byte[] data) {
        boolean master = link.isMaster();
        boolean offline = !master && !link.isOnline();
        boolean local = master || offline;
        if (offline) log.append("print_", "Info:: No NATS connection — relayed KOT printed on this device's printers");
        byte[] reply;
        try {
            reply = local ? link.printLocally(data) : link.request(data, timeoutMs);
        } catch (RuntimeException e) {
            reply = null;
            log.append("print_", "Exception:: relay " + (local ? "local print" : "request") + " failed: " + e);
        }
        if (reply == null) {
            return new PrintResult(local ? PrintOutcome.FAULT : PrintOutcome.CONNECTION_FAILED,
                    local ? "Could not print relayed order on this device" : WAITING_FOR_MASTER);
        }
        JsonObject r = Json.parseObject(new String(reply, StandardCharsets.UTF_8));
        if (r != null && Json.isTrueBoolean(r, "ok")) {
            log.append("print_", "Info:: Relay " + (offline ? "printed locally (offline)" : local ? "printed locally (this device is master now)" : "accepted by master")
                    + " tickets=" + Json.str(r, "tickets"));
            return PrintResult.success();
        }
        String error = r == null ? null : Json.str(r, "error");
        return new PrintResult(PrintOutcome.FAULT, error == null || error.isEmpty() ? "Master device could not print" : error);
    }
}
