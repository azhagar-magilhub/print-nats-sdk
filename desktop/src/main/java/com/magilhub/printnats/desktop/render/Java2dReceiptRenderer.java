package com.magilhub.printnats.desktop.render;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magilhub.printnats.desktop.render.compat.Context;
import com.magilhub.printnats.desktop.render.legacy.escpos.EscPosCharsetEncoding;
import com.magilhub.printnats.desktop.render.legacy.escpos.EscPosPrinter;
import com.magilhub.printnats.desktop.render.legacy.escpos.connection.DeviceConnection;
import com.magilhub.printnats.desktop.render.legacy.framework.ConnectionUtils.PrintUtil;
import com.magilhub.printnats.desktop.render.legacy.framework.async.AsyncEscPosPrinter;
import com.magilhub.printnats.desktop.render.legacy.framework.models.EodReport;
import com.magilhub.printnats.desktop.render.legacy.framework.models.ItemReport;
import com.magilhub.printnats.queue.JobKind;
import com.magilhub.printnats.queue.PrintJob;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.render.RenderResult;
import com.magilhub.printnats.spi.ReceiptRenderer;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Desktop twin of the Android adapter's {@code LegacyReceiptRenderer}: runs MerchantApp's own receipt/EOD code
 * ({@code render/legacy}, generated from the Android copy by {@code copy-from-android.sh}) on a Java2D
 * implementation of the Android graphics API ({@code render/compat}) and captures the ESC/POS bytes it writes.
 *
 * <p>Payloads are identical to the Android renderer:
 * RECEIPT {@code {"receiptJson": "<json>", "textReceipt": bool}};
 * EOD {@code {"eodJson": "<EodReport json>"}} or {@code {"itemReportsJson": "[…]"}}.
 *
 * <p>Image receipts come out as {@code LF, GS v 0 <raster>, GS V 1} (432 dots wide on 58 mm, 576 on 80 mm);
 * EOD as {@code GS v 0 <raster>, GS V 1} (383 / 631 dots); text receipts as ESC/POS text commands.
 * Thread-safe: renders are serialised (the legacy code keeps state in static fields).
 */
public final class Java2dReceiptRenderer implements ReceiptRenderer {
    /** PrintUtil keeps render state in static fields (mContext, mis58mm) — one render at a time. */
    private static final Object LOCK = new Object();

    private final Context context;

    public Java2dReceiptRenderer() {
        this(new Context());
    }

    public Java2dReceiptRenderer(Context context) {
        this.context = context;
    }

    /** Captures everything the legacy code writes (sent + still buffered). */
    static final class CaptureConnection extends DeviceConnection {
        private final ByteArrayOutputStream sent = new ByteArrayOutputStream();

        @Override
        public DeviceConnection connect() {
            this.outputStream = sent;
            return this;
        }

        @Override
        public DeviceConnection disconnect() {
            return this;
        }

        @Override
        public void send(int addWaitingTime) {
            // no pacing sleeps while rendering into memory
            sent.write(data, 0, data.length);
            data = new byte[0];
        }

        byte[] all() {
            byte[] s = sent.toByteArray();
            byte[] out = Arrays.copyOf(s, s.length + data.length);
            System.arraycopy(data, 0, out, s.length, data.length);
            return out;
        }
    }

    @Override
    public RenderResult render(PrintJob job, PrinterConfig printer, long nowMillis) {
        JsonObject payload = JsonParser.parseString(job.payloadJson).getAsJsonObject();
        if (job.kind == JobKind.EOD) return renderEod(payload, printer);
        JsonElement r = payload.get("receiptJson");
        String receiptJson = r == null ? null : (r.isJsonPrimitive() ? r.getAsString() : r.toString());
        if (receiptJson == null) return RenderResult.skipped("receipt job without receiptJson");
        boolean text = payload.has("textReceipt") && payload.get("textReceipt").getAsBoolean();

        CaptureConnection capture = new CaptureConnection();
        capture.connect();
        try {
            synchronized (LOCK) {
                flushQueuedTexts(PrintUtil.getAsyncEscPosPrintReceipt(context, capture, receiptJson, printer.is58mm, text), capture);
            }
        } catch (Exception e) {
            throw new IllegalStateException("receipt render failed: " + e, e);
        }
        byte[] bytes = capture.all();
        if (bytes.length == 0) return RenderResult.skipped("receipt rendered no output");
        return RenderResult.bytes(bytes, text ? "receipt-text" : "receipt-image", 0);
    }

    private RenderResult renderEod(JsonObject payload, PrinterConfig printer) {
        Gson gson = new Gson();
        CaptureConnection capture = new CaptureConnection();
        capture.connect();
        try {
            synchronized (LOCK) {
                AsyncEscPosPrinter p;
                if (payload.has("itemReportsJson")) {
                    ItemReport[] items = gson.fromJson(stringOrJson(payload.get("itemReportsJson")), ItemReport[].class);
                    p = PrintUtil.getAsyncEODPrinter(context, capture, null, Arrays.asList(items), printer.is58mm);
                } else if (payload.has("eodJson")) {
                    EodReport eod = gson.fromJson(stringOrJson(payload.get("eodJson")), EodReport.class);
                    p = PrintUtil.getAsyncEODPrinter(context, capture, eod, null, printer.is58mm);
                } else {
                    return RenderResult.skipped("EOD job without eodJson/itemReportsJson");
                }
                flushQueuedTexts(p, capture);
            }
        } catch (Exception e) {
            throw new IllegalStateException("EOD render failed: " + e, e);
        }
        byte[] bytes = capture.all();
        return bytes.length == 0 ? RenderResult.skipped("EOD rendered no output") : RenderResult.bytes(bytes, "eod", 0);
    }

    private static String stringOrJson(JsonElement e) {
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    /** What AsyncEscPosPrint.doInBackground does with any formatted text the builder queued. */
    private static void flushQueuedTexts(AsyncEscPosPrinter p, CaptureConnection capture) throws Exception {
        if (p == null || p.getTextsToPrint().length == 0) return;
        EscPosPrinter printer = new EscPosPrinter(capture, p.getPrinterDpi(), p.getPrinterWidthMM(),
                p.getPrinterNbrCharactersPerLine(), new EscPosCharsetEncoding("windows-1252", 16));
        for (String text : p.getTextsToPrint()) printer.printFormattedTextAndCut(text);
    }
}
