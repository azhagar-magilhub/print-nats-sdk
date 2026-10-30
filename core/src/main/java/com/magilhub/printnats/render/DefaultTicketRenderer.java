package com.magilhub.printnats.render;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.magilhub.printnats.model.LenientModelAdapters;
import com.magilhub.printnats.model.Receipt;
import com.magilhub.printnats.queue.JobKind;
import com.magilhub.printnats.queue.PrintJob;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.spi.KotBitmapRenderer;
import com.magilhub.printnats.spi.LogSink;
import com.magilhub.printnats.spi.ReceiptRenderer;
import com.magilhub.printnats.spi.StarEncoder;
import com.magilhub.printnats.spi.TicketRenderer;

/** KOT → thermal ({@link ThermalKotRenderer}) or Star ({@link StarKotRenderer}); receipts → host {@link ReceiptRenderer}. */
public final class DefaultTicketRenderer implements TicketRenderer {
    private static final Gson GSON = new GsonBuilder().registerTypeAdapterFactory(new LenientModelAdapters()).create();

    private final StarEncoder starEncoder;
    private final ReceiptRenderer receiptRenderer;
    private final KotBitmapRenderer kotBitmapRenderer;
    private final LogSink log;

    public DefaultTicketRenderer(StarEncoder starEncoder, ReceiptRenderer receiptRenderer, LogSink log) {
        this(starEncoder, receiptRenderer, null, log);
    }

    /** {@code kotBitmapRenderer} rasterises Template 6 (null ⇒ T6 prints in text mode). */
    public DefaultTicketRenderer(StarEncoder starEncoder, ReceiptRenderer receiptRenderer,
                                 KotBitmapRenderer kotBitmapRenderer, LogSink log) {
        this.starEncoder = starEncoder;
        this.receiptRenderer = receiptRenderer;
        this.kotBitmapRenderer = kotBitmapRenderer;
        this.log = log == null ? LogSink.NONE : log;
    }

    @Override
    public RenderResult render(PrintJob job, PrinterConfig printer, long nowMillis) {
        if (job.kind == JobKind.RECEIPT || job.kind == JobKind.EOD) {
            if (receiptRenderer == null) throw new IllegalStateException("no ReceiptRenderer configured for " + job.kind);
            return receiptRenderer.render(job, printer, nowMillis);
        }
        Receipt receipt = GSON.fromJson(job.payloadJson, Receipt.class);
        String station = printer.resolvedStationName();
        if (!printer.isStar) {
            return ThermalKotRenderer.render(receipt, station, job.isStation, printer.is58mm, printer.kotSpace,
                    nowMillis, kotBitmapRenderer);
        }
        if (starEncoder == null) throw new IllegalStateException("no StarEncoder configured for Star printer " + printer.id);
        StarSink sink = starEncoder.newSink(printer);
        String skip = StarKotRenderer.render(sink, receipt, station, job.isStation, printer.is58mm, printer.utf8,
                printer.kotSpace, log, nowMillis);
        if (skip != null) return RenderResult.skipped(skip);
        return RenderResult.bytes(starEncoder.toBytes(sink), "star-T" + ThermalKotRenderer.resolveTemplate(receipt.getTemplateNo()), 0);
    }
}
