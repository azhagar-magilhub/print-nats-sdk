package com.magilhub.printnats.desktop.render;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.magilhub.printnats.model.Receipt;
import com.magilhub.printnats.render.RenderResult;
import com.magilhub.printnats.render.ThermalKotRenderer;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Template 6 through the real Java2D rasteriser — the only place the bitmap path runs on the JVM
 * (the Android twin needs a device). Also writes the ticket to
 * {@code <java.io.tmpdir>/printnats-t6-kot.bin} so it can be sent straight to a printer:
 * {@code cat /tmp/printnats-t6-kot.bin | nc -w 20 <printer-ip> 9100}.
 */
public class T6KotBitmapRendererTest {
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    /** GS v 0 — the ESC/POS raster-bitmap command. */
    private static final String RASTER = "\u001Dv0";

    /** Same fixture as core's KotT6ImageModeTest; repeated because core's test classes are not on this classpath. */
    private static Receipt t6Receipt() {
        Date now = new Date();
        JsonObject it = new JsonObject();
        it.addProperty("quantity", "1");
        it.addProperty("itemName", "chicken biryani");
        it.addProperty("categoryName", "Mains");
        it.addProperty("comment", "extra spicy");
        JsonObject op = new JsonObject();
        op.addProperty("optionName", "Spice:hot");
        op.addProperty("quantity", "1");
        JsonArray ops = new JsonArray();
        ops.add(op);
        it.add("options", ops);
        JsonArray items = new JsonArray();
        items.add(it);

        JsonObject r = new JsonObject();
        r.addProperty("templateNo", "6");
        r.addProperty("currentDate", new SimpleDateFormat("MM/dd/yy").format(now));
        r.addProperty("currentTime", new SimpleDateFormat("hh:mm a").format(now));
        r.addProperty("orderNo", "042217");
        r.addProperty("orderDate", "10/30/2026");
        r.addProperty("orderTime", "07:15:02 PM");
        r.addProperty("orderTypeGroup", "Dine In");
        r.addProperty("orderType", "D");
        r.addProperty("isAutoPrint", false);
        r.addProperty("isPaymentDone", true);
        r.addProperty("showUpperCaseItemName", "false");
        r.addProperty("showKotNumber", "true");
        r.addProperty("kotNo", "0171");
        r.addProperty("serverStaffName", "Nancy S");
        r.addProperty("tableName", "B6");
        r.addProperty("guestCount", "4");
        r.addProperty("buzzerNo", "17");
        r.addProperty("fullName", "Arun Kumar");
        r.addProperty("comment", "no onions please");
        r.addProperty("isCustomizationCountRequired", true);
        r.add("items", items);
        return new Gson().fromJson(r, Receipt.class);
    }

    private static RenderResult renderT6(boolean is58mm) {
        return ThermalKotRenderer.render(t6Receipt(), "Tandoor", true, is58mm, 0,
                System.currentTimeMillis(), new T6KotBitmapRenderer());
    }

    @Test
    public void template6PrintsAsOneBitmap() throws Exception {
        RenderResult res = renderT6(false);
        assertFalse("skipped: " + res.skipReason, res.isSkipped());
        assertNotNull(res.bytes);
        String ascii = new String(res.bytes, LATIN1);
        assertTrue("emits a raster image", ascii.contains(RASTER));
        // The whole ticket is drawn, so none of its text travels as characters.
        assertFalse("item text must not be sent as text", ascii.contains("Chicken Biryani"));
        assertFalse("header must not be sent as text", ascii.contains("ORDER:"));
        // A drawn ticket is far bigger than the ~1.3 KB text one.
        assertTrue("raster is substantial: " + res.bytes.length, res.bytes.length > 20000);

        File out = new File(System.getProperty("java.io.tmpdir"), "printnats-t6-kot.bin");
        try (FileOutputStream f = new FileOutputStream(out)) {
            f.write(res.bytes);
        }
        System.out.println("T6 image KOT: " + res.bytes.length + " bytes -> " + out);
    }

    @Test
    public void narrowPaperRastersToo() {
        RenderResult res = renderT6(true);
        assertFalse(res.isSkipped());
        assertTrue(new String(res.bytes, LATIN1).contains(RASTER));
        // 432 px of 58 mm paper against 576 px of 80 mm — the same ticket, fewer bytes per row.
        assertTrue(res.bytes.length < renderT6(false).bytes.length);
    }
}
