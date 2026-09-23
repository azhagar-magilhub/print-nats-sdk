package com.magilhub.printnats.desktop.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.magilhub.printnats.desktop.render.compat.Bitmap;
import com.magilhub.printnats.desktop.render.compat.Context;
import com.magilhub.printnats.desktop.render.legacy.framework.ConnectionUtils.PrintUtil;
import com.magilhub.printnats.desktop.render.legacy.framework.async.AsyncEscPosPrinter;
import com.magilhub.printnats.desktop.render.legacy.framework.pojo.ReceiptPojo.ReceiptPojo;
import com.magilhub.printnats.queue.JobKind;
import com.magilhub.printnats.queue.PrintJob;
import com.magilhub.printnats.queue.PrinterConfig;
import com.magilhub.printnats.render.RenderResult;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.imageio.ImageIO;

public class Java2dReceiptRendererTest {
    static {
        System.setProperty("java.awt.headless", "true");
    }

    private static final File PREVIEWS = new File(System.getProperty("user.dir"), "build/receipt-previews");
    private static final Java2dReceiptRenderer RENDERER = new Java2dReceiptRenderer();

    @BeforeClass
    public static void setUp() {
        assertTrue(PREVIEWS.isDirectory() || PREVIEWS.mkdirs());
        System.out.println("receipt previews: " + PREVIEWS.getAbsolutePath());
    }

    // ---- image receipts ----------------------------------------------------------------------------------------

    @Test
    public void fullImageReceipt58() throws Exception {
        imageReceipt("receipt-full", ReceiptFixtures.fullReceipt(), true);
    }

    @Test
    public void fullImageReceipt80() throws Exception {
        imageReceipt("receipt-full", ReceiptFixtures.fullReceipt(), false);
    }

    @Test
    public void tipSuggestionReceipt58And80() throws Exception {
        imageReceipt("receipt-eod-tip", ReceiptFixtures.tipReceipt(), true);
        imageReceipt("receipt-eod-tip", ReceiptFixtures.tipReceipt(), false);
    }

    @Test
    public void splitTransactionReceipt58And80() throws Exception {
        imageReceipt("receipt-split-txn", ReceiptFixtures.splitTransactionReceipt(), true);
        imageReceipt("receipt-split-txn", ReceiptFixtures.splitTransactionReceipt(), false);
    }

    /** Image receipt stream = LF, GS v 0 (432 / 576 dots), GS V 1 — PrintUtil.printReceiptFromJson. */
    private void imageReceipt(String name, JsonObject receipt, boolean is58mm) throws Exception {
        RenderResult r = render(JobKind.RECEIPT, ReceiptFixtures.receiptPayload(receipt, false), is58mm);
        assertFalse(r.skipReason, r.isSkipped());
        assertEquals("receipt-image", r.layout);
        byte[] b = r.bytes;
        assertEquals(0x0A, b[0]);
        assertEquals(0x1D, b[1]);
        assertEquals(0x76, b[2]);
        assertEquals(0x30, b[3]);
        assertEquals(0x00, b[4]);
        int widthDots = is58mm ? 432 : 576;
        List<EscPosPreview.Raster> rasters = EscPosPreview.rasters(b);
        assertEquals(1, rasters.size());
        EscPosPreview.Raster raster = rasters.get(0);
        assertEquals(widthDots / 8, raster.bytesPerLine);
        assertTrue("receipt too short: " + raster.height, raster.height > 400);
        assertEquals(1 + 8 + raster.bytesPerLine * raster.height + 3, b.length);
        assertTail(b, 0x1D, 0x56, 0x01);
        assertTrue("raster is blank", blackDots(raster) > 5000);
        String tag = name + "-" + (is58mm ? "58mm" : "80mm");
        File png = writePng(raster, tag + "-printed.png");
        File canvas = writeCanvasPreview(receipt, is58mm, tag + "-canvas.png");
        System.out.println("  " + tag + ": " + raster.widthDots() + "x" + raster.height + " dots, " + b.length
                + " bytes -> " + png.getName() + ", " + canvas.getName());
    }

    // ---- text receipt ------------------------------------------------------------------------------------------

    @Test
    public void textReceipt58And80() throws Exception {
        for (boolean is58mm : new boolean[]{true, false}) {
            RenderResult r = render(JobKind.RECEIPT, ReceiptFixtures.receiptPayload(ReceiptFixtures.textReceipt(), true), is58mm);
            assertFalse(r.skipReason, r.isSkipped());
            assertEquals("receipt-text", r.layout);
            byte[] b = r.bytes;
            // printReceipt: connect, GS ! 0 (normal size), ESC a 1 (centre) ...
            assertEquals(0x1D, b[0]);
            assertEquals(0x21, b[1]);
            assertEquals(0x00, b[2]);
            assertTail(b, 0x1D, 0x56, 0x01);
            String text = EscPosPreview.text(b);
            String tag = "receipt-text-" + (is58mm ? "58mm" : "80mm");
            File txt = new File(PREVIEWS, tag + ".txt");
            try (FileOutputStream out = new FileOutputStream(txt)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            System.out.println("  " + tag + ": " + b.length + " bytes -> " + txt.getName());
            assertTrue(text, text.contains("Maghil Test"));
            assertTrue(text, text.contains("Mango Lassi"));
            assertTrue(text, text.contains("Garlic Naan"));
            assertTrue(text, text.contains("71.32"));
            int maxLine = 0;
            for (String line : text.split("\n")) maxLine = Math.max(maxLine, line.length());
            assertTrue("line wider than paper: " + maxLine, maxLine <= (is58mm ? 32 : 48));
        }
    }

    // ---- EOD ---------------------------------------------------------------------------------------------------

    @Test
    public void eodReport58And80() throws Exception {
        for (boolean is58mm : new boolean[]{true, false}) {
            eod("eod-report", ReceiptFixtures.eodPayload(ReceiptFixtures.eodReport()), is58mm);
        }
    }

    @Test
    public void eodItemReport58And80() throws Exception {
        for (boolean is58mm : new boolean[]{true, false}) {
            eod("eod-items", ReceiptFixtures.itemReportsPayload(ReceiptFixtures.itemReports()), is58mm);
        }
    }

    /** EOD stream = GS v 0 (383 / 631 dots — the legacy targetWidth; 631 = DantSu mmToPx(79 mm)), GS V 1. */
    private void eod(String name, String payload, boolean is58mm) throws Exception {
        RenderResult r = render(JobKind.EOD, payload, is58mm);
        assertFalse(r.skipReason, r.isSkipped());
        assertEquals("eod", r.layout);
        byte[] b = r.bytes;
        assertEquals(0x1D, b[0]);
        assertEquals(0x76, b[1]);
        assertEquals(0x30, b[2]);
        int widthDots = is58mm ? 383 : new AsyncEscPosPrinter(null, 203, 79f, 46).mmToPx(79f);
        EscPosPreview.Raster raster = EscPosPreview.rasters(b).get(0);
        assertEquals((widthDots + 7) / 8, raster.bytesPerLine);
        assertEquals(8 + raster.bytesPerLine * raster.height + 3, b.length);
        assertTail(b, 0x1D, 0x56, 0x01);
        assertTrue("raster is blank", blackDots(raster) > 2000);
        String tag = name + "-" + (is58mm ? "58mm" : "80mm");
        File png = writePng(raster, tag + "-printed.png");
        System.out.println("  " + tag + ": " + raster.widthDots() + "x" + raster.height + " dots, " + b.length
                + " bytes -> " + png.getName());
    }

    // ---- misc --------------------------------------------------------------------------------------------------

    @Test
    public void receiptWithoutJsonIsSkipped() {
        RenderResult r = render(JobKind.RECEIPT, "{\"textReceipt\":false}", true);
        assertTrue(r.isSkipped());
    }

    @Test
    public void rendersAreDeterministic() {
        String payload = ReceiptFixtures.receiptPayload(ReceiptFixtures.fullReceipt(), false);
        byte[] a = render(JobKind.RECEIPT, payload, false).bytes;
        byte[] b = render(JobKind.RECEIPT, payload, false).bytes;
        assertTrue(java.util.Arrays.equals(a, b));
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static RenderResult render(JobKind kind, String payload, boolean is58mm) {
        PrintJob job = new PrintJob();
        job.jobId = "test";
        job.kind = kind;
        job.payloadJson = payload;
        PrinterConfig printer = new PrinterConfig();
        printer.id = "p1";
        printer.is58mm = is58mm;
        return RENDERER.render(job, printer, System.currentTimeMillis());
    }

    private static void assertTail(byte[] b, int... tail) {
        for (int i = 0; i < tail.length; i++) {
            assertEquals("tail byte " + i, (byte) tail[i], b[b.length - tail.length + i]);
        }
    }

    private static int blackDots(EscPosPreview.Raster r) {
        int n = 0;
        for (byte x : r.data) n += Integer.bitCount(x & 0xFF);
        return n;
    }

    private static File writePng(EscPosPreview.Raster r, String fileName) throws IOException {
        File f = new File(PREVIEWS, fileName);
        ImageIO.write(r.toImage(), "png", f);
        return f;
    }

    /** The anti-aliased canvas before scaling/thresholding (PrintUtil's private image builders). */
    private static File writeCanvasPreview(JsonObject receipt, boolean is58mm, String fileName) throws Exception {
        ReceiptPojo pojo = new Gson().fromJson(receipt.toString(), ReceiptPojo.class);
        boolean tipOrTxn = pojo.getEodTipConfig() != null
                && (pojo.getEodTipConfig().isEodTipEnabled() || pojo.getEodTipConfig().isTransactionReceipt());
        Method m = PrintUtil.class.getDeclaredMethod(tipOrTxn ? "generateEodTipReceiptImageFromJson" : "generateReceiptImageFromJson",
                Context.class, ReceiptPojo.class, boolean.class);
        m.setAccessible(true);
        Bitmap bmp = (Bitmap) m.invoke(null, new Context(), pojo, is58mm);
        assertNotNull(bmp);
        File f = new File(PREVIEWS, fileName);
        ImageIO.write(bmp.toBufferedImage(), "png", f);
        return f;
    }
}
