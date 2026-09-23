package com.magilhub.printnats.spi;

import com.magilhub.printnats.queue.PrintResult;
import com.magilhub.printnats.queue.PrinterConfig;

/**
 * Sends already-rendered bytes to one printer and reports what happened. Blocking; the queue calls it
 * from a per-printer worker and enforces its own watchdog, so implementations should still use sane
 * socket/USB timeouts but need not guard against hanging forever.
 */
public interface PrinterTransport {
    PrintResult send(PrinterConfig printer, byte[] data);
}
