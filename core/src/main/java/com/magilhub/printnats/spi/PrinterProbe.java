package com.magilhub.printnats.spi;

import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.queue.PrinterHealth;

/** Optional capability of a transport: check a printer's health without printing. */
public interface PrinterProbe {
    PrinterHealth probe(PrinterConfig printer);
}
