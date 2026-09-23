package com.magilhub.printnats.spi;

import com.magilhub.printnats.queue.PrintJob;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.render.RenderResult;

/** Renders RECEIPT / EOD jobs (bitmap receipts today: Android Canvas; desktop Java2D later). */
public interface ReceiptRenderer {
    RenderResult render(PrintJob job, PrinterConfig printer, long nowMillis);
}
