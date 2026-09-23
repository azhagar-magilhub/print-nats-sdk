package com.magilhub.printnats.android.render;

import android.content.Context;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.magilhub.printnats.android.legacy.escpos.EscPosCharsetEncoding;
import com.magilhub.printnats.android.legacy.escpos.EscPosPrinter;
import com.magilhub.printnats.android.legacy.escpos.connection.DeviceConnection;
import com.magilhub.printnats.android.legacy.framework.ConnectionUtils.PrintUtil;
import com.magilhub.printnats.android.legacy.framework.async.AsyncEscPosPrinter;
import com.magilhub.printnats.android.legacy.framework.models.EodReport;
import com.magilhub.printnats.android.legacy.framework.models.ItemReport;
import com.magilhub.printnats.queue.JobKind;
import com.magilhub.printnats.queue.PrintJob;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.render.RenderResult;
import com.magilhub.printnats.spi.ReceiptRenderer;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * Receipts through MerchantApp's own (copied, unmodified) {@code PrintUtil.getAsyncEscPosPrintReceipt}:
 * image receipts (Canvas + Outfit fonts + payment logos, dithered) or ESC/POS text receipts, captured into bytes
 * instead of streamed to a live connection. RECEIPT job payload: {@code {"receiptJson": "<json>", "textReceipt": bool}}
 * (the exact JSON + flag JS passed to native printReceiptJson).
 * EOD job payload: {@code {"eodJson": "<EodReport json>"}} or {@code {"itemReportsJson": "[…]"}} (legacy printEOD /
 * printEodItemReport → getAsyncEODPrinter).
 */
public final class LegacyReceiptRenderer implements ReceiptRenderer {
    /** PrintUtil keeps render state in static fields (mContext, mis58mm) — one render at a time. */
    private static final Object LOCK = new Object();

    private final Context context;

    public LegacyReceiptRenderer(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Captures everything the legacy code writes (sent + still buffered) AND where it paused: every
     * {@code send(addWaitingTime)} is a chunk boundary, replayed by the transport with the same
     * {@code addWaitingTime + length/16} ms pause the live DantSu connection slept.
     */
    static final class CaptureConnection extends DeviceConnection {
        private final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        private final java.util.List<int[]> chunks = new java.util.ArrayList<>(); // {end, addWaitMs}

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
            // no sleeping while rendering into memory — the pause is recorded and replayed by the transport
            sent.write(data, 0, data.length);
            data = new byte[0];
            chunks.add(new int[]{sent.size(), addWaitingTime});
        }

        /** A pause with no bytes (legacy AsyncEscPosPrint: Thread.sleep(500) after each text). */
        void pause(int ms) {
            chunks.add(new int[]{sent.size(), ms});
        }

        int[] chunkEnds() {
            int[] out = new int[chunks.size()];
            for (int i = 0; i < out.length; i++) out[i] = chunks.get(i)[0];
            return out;
        }

        int[] chunkWaits() {
            int[] out = new int[chunks.size()];
            for (int i = 0; i < out.length; i++) out[i] = chunks.get(i)[1];
            return out;
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
        return RenderResult.paced(bytes, text ? "receipt-text" : "receipt-image", capture.chunkEnds(), capture.chunkWaits());
    }

    private RenderResult renderEod(JsonObject payload, PrinterConfig printer) {
        Gson gson = new Gson();
        CaptureConnection capture = new CaptureConnection();
        capture.connect();
        try {
            synchronized (LOCK) {
                AsyncEscPosPrinter p;
                if (payload.has("itemReportsJson")) {
                    ItemReport[] items = gson.fromJson(payload.get("itemReportsJson").getAsString(), ItemReport[].class);
                    p = PrintUtil.getAsyncEODPrinter(context, capture, null, java.util.Arrays.asList(items), printer.is58mm);
                } else {
                    EodReport eod = gson.fromJson(payload.get("eodJson").getAsString(), EodReport.class);
                    p = PrintUtil.getAsyncEODPrinter(context, capture, eod, null, printer.is58mm);
                }
                flushQueuedTexts(p, capture);
            }
        } catch (Exception e) {
            throw new IllegalStateException("EOD render failed: " + e, e);
        }
        byte[] bytes = capture.all();
        return bytes.length == 0 ? RenderResult.skipped("EOD rendered no output")
                : RenderResult.paced(bytes, "eod", capture.chunkEnds(), capture.chunkWaits());
    }

    /** What AsyncEscPosPrint.doInBackground does with any formatted text the builder queued. */
    private static void flushQueuedTexts(AsyncEscPosPrinter p, CaptureConnection capture) throws Exception {
        if (p == null || p.getTextsToPrint().length == 0) return;
        EscPosPrinter printer = new EscPosPrinter(capture, p.getPrinterDpi(), p.getPrinterWidthMM(),
                p.getPrinterNbrCharactersPerLine(), new EscPosCharsetEncoding("windows-1252", 16));
        for (String text : p.getTextsToPrint()) {
            printer.printFormattedTextAndCut(text);
            capture.pause(500); // AsyncEscPosPrint.doInBackground: Thread.sleep(500) before the next text / disconnect
        }
    }
}
