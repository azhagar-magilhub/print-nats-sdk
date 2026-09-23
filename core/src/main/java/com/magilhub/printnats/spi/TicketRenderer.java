package com.magilhub.printnats.spi;

import com.magilhub.printnats.queue.PrintJob;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.render.RenderResult;

/** Turns a job's payload into printer bytes for a specific printer (thermal vs Star, paper width, …). */
public interface TicketRenderer {
    RenderResult render(PrintJob job, PrinterConfig printer, long nowMillis);
}
